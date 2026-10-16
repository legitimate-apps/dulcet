package com.legitimateapps.dulcet.apiconformance

import com.legitimateapps.dulcet.apiconformance.Profile.MCP

/** An initialized MCP session as a fresh personal token holding exactly [scopes]. */
fun Ctx.mcpAs(scopes: List<String>): McpClient {
    val pat = mintToken(scopes)
    return McpClient(this, pat.secret).also { it.initialize() }
}

private val READ_SCOPES = listOf("catalog:read", "user:read")
private val WRITE_SCOPES = READ_SCOPES + "user:write"
private val ALL_SCOPES = WRITE_SCOPES + "user:delete"

private fun Ctx.playlistExists(pid: String, token: String = tokenA): Boolean =
    api("GET", "getPlaylist", "/playlists/${id(pid)}", token = token).status == 200

// ---- MCP profile (README §13, CONFORMANCE.md "Other profiles") -------------------------------------

fun mcpChecks(): List<Check> = listOf(
    Check("DA-MCP-1", MCP, "the MCP endpoint publishes its protected-resource metadata and refuses a call without a token", "README §13; openapi getMcpProtectedResourceMetadata, mcpMessage") {
        requires("DA-PRE-1")
        val meta = send("GET", "getMcpProtectedResourceMetadata", run.origin + "/.well-known/oauth-protected-resource/dulcet/v1/mcp")
        expectStatus(meta, 200, what = "the MCP protected-resource metadata")
        ensure(body(meta).str("resource") == run.apiBase + "/mcp") { "the metadata's resource is ${body(meta).str("resource")}, not ${run.apiBase}/mcp" }
        ensure(body(meta).path("authorization_servers").size() > 0) { "the metadata names no authorization server" }
        val anonymous = McpClient(this, null).request("initialize", mapOf("protocolVersion" to MCP_PROTOCOL_VERSION, "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "dulcet-api-conformance", "version" to SUITE_VERSION))).first
        expectStatus(anonymous, 401, what = "MCP initialize without a token")
    },
    Check("DA-MCP-2", MCP, "initialize negotiates a protocol version and offers tools", "README §13; MCP lifecycle") {
        requires("DA-PRE-1")
        val pat = mintToken(READ_SCOPES)
        val mcp = McpClient(this, pat.secret)
        val result = mcp.initialize()
        ensure(result.path("capabilities").path("tools").isObject) { "the server's capabilities offer no tools: ${result.path("capabilities")}" }
        ensure(result.path("serverInfo").str("name") != null) { "initialize returns no serverInfo.name" }
    },
    Check("DA-MCP-3", MCP, "tools/list returns exactly mcp-tools.json filtered by the token's scopes, each tool's scopes in _meta[\"dulcet/scopes\"]", "README §13; openapi mcpMessage; docs/dulcet-api/mcp-tools.json") {
        requires("DA-PRE-1")
        for (scopes in listOf(listOf("catalog:read"), READ_SCOPES, WRITE_SCOPES, ALL_SCOPES)) {
            val listed = mcpAs(scopes).listTools()
            val expected = McpTools.forScopes(scopes)
            val names = listed.map { it.str("name") }
            ensure(names.toSet().size == names.size) { "tools/list for $scopes repeats a tool" }
            ensure(names.toSet() == expected.map { it.str("name") }.toSet()) {
                "tools/list for $scopes: extra ${names.toSet() - expected.map { it.str("name") }.toSet()}, missing ${expected.map { it.str("name") }.toSet() - names.toSet()}"
            }
            for (tool in listed) {
                val published = McpTools.byName[tool.str("name")]!!
                for (field in McpTools.compared) {
                    ensure(tool.path(field) == published.path(field)) { "tools/list ${tool.str("name")}.$field differs from mcp-tools.json" }
                }
                val metaScopes = tool.path("_meta").path("dulcet/scopes")
                ensure(metaScopes.isArray && metaScopes.map { it.asText() } == listOf(McpTools.scopeOf(published))) {
                    "tools/list ${tool.str("name")} carries _meta[\"dulcet/scopes\"] = $metaScopes, not [\"${McpTools.scopeOf(published)}\"]"
                }
                ensure(!tool.has("x-dulcet-scope")) { "tools/list ${tool.str("name")} still carries x-dulcet-scope; the contract replaces it with _meta" }
            }
        }
    },
    Check("DA-MCP-4", MCP, "a tool outside the token's scopes cannot be called: no delete without user:delete, no write without user:write", "README §13; openapi mcpMessage") {
        requireFixture()
        val (_, pid) = createPlaylist("conformance mcp keep ${run.runId}", listOf(fixture.track(0)))
        val revision = body(api("GET", "getPlaylist", "/playlists/${id(pid)}")).str("revision")!!
        val noDelete = mcpAs(WRITE_SCOPES)
        val del = noDelete.callTool("delete_playlist", mapOf("playlistId" to pid, "revision" to revision))
        ensure(del.refused) { "delete_playlist ran for a token without user:delete" }
        ensure(playlistExists(pid)) { "delete_playlist deleted a playlist for a token without user:delete" }
        val t = fixture.track(3)
        val before = userState(t).path("favoritedAt")
        val readOnly = mcpAs(READ_SCOPES)
        val fav = readOnly.callTool("set_favorite", mapOf("itemId" to t, "favorite" to before.isNull))
        ensure(fav.refused) { "set_favorite ran for a token without user:write" }
        ensure(userState(t).path("favoritedAt") == before) { "set_favorite changed a favourite for a token without user:write" }
    },
    Check("DA-MCP-5", MCP, "tool results match the API and validate against their outputSchema", "README §13; docs/dulcet-api/mcp-tools.json") {
        requireFixture()
        val mcp = mcpAs(ALL_SCOPES)
        val t0 = fixture.track(0)
        val got = mcp.callTool("get_track", mapOf("id" to t0))
        ensure(!got.refused) { "get_track failed: ${got.message}" }
        val rest = track(t0)
        ensure(got.structured?.str("id") == t0 && got.structured?.str("title") == rest.str("title")) { "get_track says ${got.structured}, the API says ${rest.str("title")}" }
        val t1 = fixture.track(1)
        val fav = mcp.callTool("set_favorite", mapOf("itemId" to t1, "favorite" to true))
        run.cleanups += "unfavourite $t1" to { api("DELETE", "removeFavorite", "/me/favorites/${id(t1)}"); Unit }
        ensure(!fav.refused) { "set_favorite failed: ${fav.message}" }
        val state = userState(t1)
        ensure(!state.path("favoritedAt").isNull && fav.structured?.path("favoritedAt") == state.path("favoritedAt")) {
            "set_favorite answered favoritedAt ${fav.structured?.path("favoritedAt")}, the API shows ${state.path("favoritedAt")}"
        }
        val (_, pid) = createPlaylist("conformance mcp list ${run.runId}", listOf(t0))
        val restRevision = body(api("GET", "getPlaylist", "/playlists/${id(pid)}")).str("revision")
        val lists = mcp.callTool("list_playlists", mapOf("limit" to 100))
        ensure(!lists.refused) { "list_playlists failed: ${lists.message}" }
        val listed = lists.structured?.path("items")?.firstOrNull { it.str("id") == pid } ?: fail("list_playlists does not include the playlist just created")
        ensure(listed.str("revision") == restRevision) { "list_playlists shows revision ${listed.str("revision")}, the API $restRevision" }
        val q = mcp.callTool("get_queue", emptyMap())
        ensure(!q.refused) { "get_queue failed: ${q.message}" }
        val restQueue = body(api("GET", "getQueue", "/me/queue"))
        ensure(q.structured?.path("entryCount")?.asInt() == restQueue.path("entryCount").asInt()) { "get_queue entryCount ${q.structured?.path("entryCount")}, the API ${restQueue.path("entryCount")}" }
    },
    Check("DA-MCP-6", MCP, "no tool bypasses a REST rule: a stale revision deletes nothing, another user's private playlist is out of reach", "README §13; openapi mcpMessage") {
        requireFixture()
        val mcp = mcpAs(ALL_SCOPES)
        val (_, pid) = createPlaylist("conformance mcp stale ${run.runId}", listOf(fixture.track(0)))
        val stale = mcp.callTool("delete_playlist", mapOf("playlistId" to pid, "revision" to "conformance-stale"))
        ensure(stale.refused && playlistExists(pid)) { "delete_playlist with a stale revision deleted the playlist (the API requires a matching If-Match)" }
        val other = tokenB
        val created = api("POST", "createPlaylist", "/playlists", token = other, body = mapOf("name" to "conformance b private ${run.runId}", "visibility" to "private", "itemIds" to listOf(fixture.track(1))))
        expectStatus(created, 201, what = "user B creating a private playlist")
        val bPid = body(created).str("id")!!
        run.cleanups += "delete B's playlist $bPid" to { api("DELETE", "deletePlaylist", "/playlists/${id(bPid)}", token = other); Unit }
        val read = mcp.callTool("get_playlist", mapOf("id" to bPid))
        ensure(read.refused) { "get_playlist returned another user's private playlist" }
        val del = mcp.callTool("delete_playlist", mapOf("playlistId" to bPid, "revision" to body(created).str("revision")))
        ensure(del.refused && playlistExists(bPid, other)) { "delete_playlist reached another user's playlist" }
    },
    Check("DA-MCP-7", MCP, "a foreign Origin is refused and a session id works only with the token that opened it", "README §13; openapi mcpMessage") {
        requires("DA-PRE-1")
        val foreign = McpClient(this, tokenA, origin = "https://attacker.invalid")
        val (refused, _) = foreign.request("initialize", mapOf("protocolVersion" to MCP_PROTOCOL_VERSION, "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "dulcet-api-conformance", "version" to SUITE_VERSION)))
        expectStatus(refused, 403, what = "MCP initialize with Origin https://attacker.invalid")
        val owner = mintToken(READ_SCOPES)
        val mcp = McpClient(this, owner.secret).also { it.initialize() }
        val session = mcp.sessionId ?: return@Check // a server without sessions has nothing to bind
        val stranger = mintToken(READ_SCOPES)
        val (borrowed, msg) = mcp.request("tools/list", emptyMap<String, Any>(), token = stranger.secret, session = session)
        ensure(borrowed.status in 400..499 || msg?.has("error") == true) { "another token used session $session: ${borrowed.describe()}" }
        val (bare, _) = mcp.request("tools/list", emptyMap<String, Any>(), token = null, session = session)
        expectStatus(bare, 401, what = "a session id without a token")
    },
)
