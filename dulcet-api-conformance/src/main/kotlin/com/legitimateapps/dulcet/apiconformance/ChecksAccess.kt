package com.legitimateapps.dulcet.apiconformance

import com.legitimateapps.dulcet.apiconformance.Profile.Core
import java.net.URI
import java.util.UUID

/** Scopes token A must hold for the Core checks that write. */
val REQUIRED_SCOPES = listOf("catalog:read", "media:read", "user:read", "user:write", "user:delete", "plays:write", "account")

val OAUTH_OPERATIONS = setOf("requestToken", "revokeToken", "authorize", "requestDeviceAuthorization", "registerClient")

fun Ctx.me(token: String = tokenA): com.fasterxml.jackson.databind.JsonNode {
    val ex = api("GET", "getMe", "/me", token = token)
    expectStatus(ex, 200, what = "GET /me")
    return body(ex)
}

fun Ctx.scopesOf(token: String): Set<String> = me(token).path("token").path("scopes").map { it.asText() }.toSet()

/** Asks for a direct playback plan for [trackId]; returns the plan JSON. */
fun Ctx.directPlan(trackId: String, token: String = tokenA): com.fasterxml.jackson.databind.JsonNode {
    val track = body(api("GET", "getTrack", "/tracks/${id(trackId)}", token = tokenA))
    val audio = track.path("audio")
    val direct = mutableListOf(mapOf("container" to (audio.str("container") ?: "mp3"), "codec" to (audio.str("codec") ?: "mp3")))
    for ((c, k) in listOf("mp3" to "mp3", "flac" to "flac", "ogg" to "opus", "mp4" to "aac", "mp4" to "alac", "ogg" to "vorbis")) {
        direct += mapOf("container" to c, "codec" to k)
    }
    val ex = api("POST", "createPlaybackDecision", "/playback/decisions", token = token,
        body = mapOf("trackId" to trackId, "purpose" to "stream", "profile" to mapOf("directPlay" to direct)))
    expectStatus(ex, 200, what = "playback decision")
    ensure(ex.header("Cache-Control")?.contains("no-store") == true) { "a response carrying a signed URL must be Cache-Control: no-store, got ${ex.header("Cache-Control")}" }
    return body(ex)
}

fun Ctx.fetchMedia(url: String, range: String? = null): Exchange =
    send("GET", "getMedia", url, headers = if (range == null) emptyMap() else mapOf("Range" to range))

fun accessChecks(): List<Check> = listOf(
    Check("DA-PRE-1", Core, "token A is accepted and holds the scopes the suite needs", "CONFORMANCE.md Shape") {
        val scopes = scopesOf(tokenA)
        val missing = REQUIRED_SCOPES.filter { it !in scopes }
        ensure(missing.isEmpty()) { "DULCET_TOKEN lacks $missing; mint one with ${REQUIRED_SCOPES.joinToString(" ")}" }
        config.tokenB?.let { b ->
            val bScopes = scopesOf(b)
            val bMissing = listOf("catalog:read", "user:read", "user:write").filter { it !in bScopes }
            ensure(bMissing.isEmpty()) { "DULCET_TOKEN_B lacks $bMissing" }
            ensure(me(b).str("id") != me(tokenA).str("id")) { "DULCET_TOKEN_B must belong to a different user than DULCET_TOKEN" }
        }
    },
    Check("DA-PRE-2", Core, "the catalog has subjects: at least 2 albums and 4 tracks", "CONFORMANCE.md Shape") {
        requires("DA-PRE-1")
        fixture.albums.clear(); fixture.tracks.clear(); fixture.artists.clear(); fixture.albumTracks.clear()
        fixture.albums += walk("/albums", "listAlbums", query = listOf("limit" to "100"))
        fixture.tracks += walk("/tracks", "listTracks", query = listOf("limit" to "100"))
        fixture.artists += walk("/artists", "listArtists", query = listOf("limit" to "100"))
        ensure(fixture.ready) { "found ${fixture.albums.size} albums and ${fixture.tracks.size} tracks; scan a library with at least 2 albums and 4 tracks" }
        for (album in fixture.albums.take(2)) {
            val detail = body(api("GET", "getAlbum", "/albums/${id(album.path("id").asText())}"))
            fixture.albumTracks[album.path("id").asText()] = detail.path("discs").flatMap { it.path("tracks").ids() }
        }
    },

    // Discovery (README §2, §16)
    Check("DA-DISC-1", Core, "discovery document names v1 at /dulcet/v1", "README §2") {
        val ex = send("GET", "getDiscovery", run.origin + "/.well-known/dulcet")
        expectStatus(ex, 200)
        val b = body(ex)
        run.discovery = b
        val v1 = b.path("apis").firstOrNull { it.str("version") == "1" } ?: fail("apis has no entry with version \"1\"")
        val base = v1.str("baseUrl") ?: fail("the v1 entry has no baseUrl")
        ensure(URI(base).path.trimEnd('/').endsWith("/dulcet/v1")) { "the v1 baseUrl path is ${URI(base).path}, not /dulcet/v1" }
        ensure(!b.path("server").str("instanceId").isNullOrEmpty()) { "server.instanceId is empty" }
    },
    Check("DA-DISC-2", Core, "capabilities without a token show no permissions", "README §16") {
        val ex = api("GET", "getCapabilities", "/capabilities", token = null)
        expectStatus(ex, 200)
        val b = body(ex)
        run.capabilities = b
        ensure(!b.has("permissions")) { "an anonymous capabilities response carries permissions" }
    },
    Check("DA-DISC-3", Core, "capabilities with a token add permissions", "README §16") {
        val b = body(api("GET", "getCapabilities", "/capabilities").also { expectStatus(it, 200) })
        ensure(b.has("permissions")) { "capabilities read with a token carry no permissions" }
        run.capabilities = b
    },
    Check("DA-DISC-4", Core, "authorization server metadata: S256 only, iss parameter advertised", "README §3 Flows") {
        val ex = send("GET", "getAuthorizationServerMetadata", run.origin + "/.well-known/oauth-authorization-server")
        expectStatus(ex, 200)
        val b = body(ex)
        run.asMetadata = b
        val methods = b.path("code_challenge_methods_supported").map { it.asText() }
        ensure(methods == listOf("S256")) { "code_challenge_methods_supported is $methods, expected exactly [S256]" }
        ensure(b.path("authorization_response_iss_parameter_supported").asBoolean(false)) { "authorization_response_iss_parameter_supported is not true" }
        ensure(b.has("revocation_endpoint")) { "no revocation_endpoint (RFC 7009 revocation is required)" }
        run.discovery?.str("authorizationServer")?.let { asUrl ->
            ensure(b.str("issuer")?.trimEnd('/') == asUrl.trimEnd('/')) { "issuer ${b.str("issuer")} differs from discovery's authorizationServer $asUrl" }
        }
    },
    Check("DA-DISC-5", Core, "protected resource metadata allows header bearer tokens only", "README §3 Transport") {
        val ex = send("GET", "getProtectedResourceMetadata", run.origin + "/.well-known/oauth-protected-resource")
        expectStatus(ex, 200)
        val methods = body(ex).path("bearer_methods_supported")
        if (!methods.isMissingNode) ensure(methods.map { it.asText() } == listOf("header")) { "bearer_methods_supported is $methods, expected [header]" }
    },

    // Header-only authentication (README §1, §3)
    Check("DA-AUTH-11", Core, "a token in a query parameter is refused and never echoed", "README §1 Header-only") {
        val canary = "dulcet_at_CANARY" + UUID.randomUUID().toString().replace("-", "")
        run.secrets.add(canary, SecretKind.CANARY)
        for (param in listOf("access_token", "token", "api_key", "apiKey", "t")) {
            for (value in listOf(tokenA, canary)) {
                val ex = api("GET", "getMe", "/me", token = null, query = listOf(param to value))
                expectProblem(ex, 401, "unauthenticated", what = "token in ?$param")
                val echoed = ex.text().contains(value) || ex.headers.values.flatten().any { it.contains(value) }
                ensure(!echoed) { "the 401 for ?$param echoed the token" }
            }
        }
    },
    Check("DA-AUTH-12", Core, "no token or a bad token answers 401 with WWW-Authenticate", "openapi Unauthorized") {
        val none = api("GET", "getMe", "/me", token = null)
        expectProblem(none, 401, "unauthenticated", what = "no token")
        ensure(none.header("WWW-Authenticate")?.startsWith("Bearer", ignoreCase = true) == true) { "401 without a WWW-Authenticate: Bearer header" }
        val bad = api("GET", "getMe", "/me", token = "dulcet_at_not-a-real-token-" + run.runId)
        expectProblem(bad, 401, "unauthenticated", what = "unknown token")
    },

    // OAuth (README §3)
    Check("DA-AUTH-1", Core, "plain PKCE is refused", "README §3 Flows") {
        requires("DA-DISC-4")
        val auth = run.oauth.authorize(this, "catalog:read", method = "plain", purpose = "plain PKCE: the server should refuse; approve if asked")
        if (auth.code != null) {
            val ex = run.oauth.exchangeCode(this, auth)
            ensure(ex.status != 200) { "a plain PKCE authorization was issued a code and the code was exchanged for tokens" }
            fail("a plain PKCE authorization was issued a code (the exchange was then refused, but the authorization must be)")
        }
    },
    Check("DA-AUTH-2", Core, "a wrong code verifier is refused", "README §3 Flows") {
        requires("DA-DISC-4")
        val auth = run.oauth.authorize(this, "catalog:read", purpose = "wrong-verifier test")
        ensure(auth.code != null) { "authorization failed: ${auth.error}" }
        val ex = run.oauth.exchangeCode(this, auth, verifier = auth.verifier.reversed() + "x")
        expectStatus(ex, 400, what = "exchanging with a wrong verifier")
        ensure(body(ex).str("error") == "invalid_grant") { "error is ${body(ex).str("error")}, expected invalid_grant" }
    },
    Check("DA-AUTH-5", Core, "a mismatched redirect URI is refused", "README §3 Flows") {
        requires("DA-DISC-4")
        val auth = run.oauth.authorize(this, "catalog:read", purpose = "redirect-mismatch test")
        ensure(auth.code != null) { "authorization failed: ${auth.error}" }
        val ex = run.oauth.exchangeCode(this, auth, redirectUri = auth.redirectUri.replace("/callback", "/other"))
        expectStatus(ex, 400, what = "exchanging with a different redirect_uri")
        ensure(body(ex).str("error") == "invalid_grant") { "error is ${body(ex).str("error")}, expected invalid_grant" }
    },
    Check("DA-AUTH-3", Core, "S256 code flow: iss and state returned, prefixed tokens issued", "README §3 Flows, Tokens") {
        requires("DA-DISC-4")
        val auth = run.oauth.authorize(this, "catalog:read media:read user:read offline_access account",
            purpose = "main grant; an unverified client must not be given account")
        ensure(auth.code != null) { "authorization failed: ${auth.error}" }
        ensure(auth.state == auth.expectedState) { "state was not returned unchanged" }
        val issuer = run.oauth.metadata(this).str("issuer")
        ensure(auth.iss != null && auth.iss.trimEnd('/') == issuer?.trimEnd('/')) { "authorization response iss=${auth.iss}, expected the issuer $issuer (RFC 9207)" }
        val ex = run.oauth.exchangeCode(this, auth)
        expectStatus(ex, 200, what = "exchanging the code")
        val b = body(ex)
        ensure(b.str("access_token")!!.startsWith("dulcet_at_")) { "access token lacks the dulcet_at_ prefix" }
        val rt = b.str("refresh_token") ?: fail("no refresh token despite offline_access")
        ensure(rt.startsWith("dulcet_rt_")) { "refresh token lacks the dulcet_rt_ prefix" }
        run.grant = Grant(auth, b.str("access_token")!!, rt, b.str("scope"))
        val again = run.oauth.exchangeCode(this, auth)
        expectStatus(again, 400, what = "reusing the code")
        ensure(body(again).str("error") == "invalid_grant") { "reused code: error ${body(again).str("error")}, expected invalid_grant" }
    },
    Check("DA-AUTH-4", Core, "an unverified client is never granted account or admin", "README §3 Flows") {
        val grant = run.grant ?: skip("needs the grant from DA-AUTH-3")
        val scopes = scopesOf(grant.access)
        ensure("account" !in scopes && "admin" !in scopes) { "the unverified client ${config.clientId} was granted ${scopes.intersect(setOf("account", "admin"))}" }
        val mint = api("POST", "createPersonalToken", "/me/tokens", token = grant.access,
            body = mapOf("name" to "conformance escalation", "scopes" to listOf("catalog:read"), "expiresInDays" to 1))
        expectProblem(mint, 403, "insufficient-scope", what = "minting a token from an unverified client's grant")
    },
    Check("DA-AUTH-6", Core, "refresh rotates both tokens", "README §3 Tokens") {
        val grant = run.grant ?: skip("needs the grant from DA-AUTH-3")
        val ex = run.oauth.refresh(this, grant.refresh)
        expectStatus(ex, 200, what = "refreshing")
        val b = body(ex)
        val access = b.str("access_token")!!
        val refresh = b.str("refresh_token") ?: fail("refresh returned no new refresh token")
        ensure(refresh != grant.refresh) { "the refresh token did not rotate" }
        ensure(access != grant.access) { "the access token did not change" }
        expectStatus(api("GET", "getMe", "/me", token = access), 200, what = "the new access token")
        grant.previousRefresh = grant.refresh
        grant.rotatedAt = System.nanoTime()
        grant.firstRotation = access to refresh
        grant.access = access; grant.refresh = refresh
    },
    Check("DA-AUTH-7", Core, "the old refresh token within the retry window returns the same pair", "README §3 Tokens") {
        val grant = run.grant ?: skip("needs the grant from DA-AUTH-3")
        val old = grant.previousRefresh ?: skip("needs the rotation from DA-AUTH-6")
        val ex = run.oauth.refresh(this, old)
        expectStatus(ex, 200, what = "presenting the old refresh token inside the window")
        val b = body(ex)
        ensure(b.str("access_token") to b.str("refresh_token") == grant.firstRotation) {
            "presenting the old refresh token within ${config.refreshWindowSeconds} s returned a different pair, not the same new pair"
        }
    },
    Check("DA-AUTH-9", Core, "refreshing the access token does not kill signed media URLs", "README §9 Signed URLs") {
        requireFixture()
        val grant = run.grant ?: skip("needs the grant from DA-AUTH-3")
        val plan = directPlan(fixture.track(0), token = grant.access)
        grant.mediaUrl = plan.str("url")
        val ex = run.oauth.refresh(this, grant.refresh)
        expectStatus(ex, 200, what = "refreshing")
        val b = body(ex)
        grant.access = b.str("access_token")!!; grant.refresh = b.str("refresh_token")!!
        expectStatus(fetchMedia(plan.str("url")!!, "bytes=0-15"), 206, 200, what = "the media URL after a refresh")
    },
    Check("DA-AUTH-10", Core, "revoking the refresh token stops the access token and its media URLs at once", "README §3 Tokens") {
        val grant = run.grant ?: skip("needs the grant from DA-AUTH-3")
        val media = grant.mediaUrl
        val ex = run.oauth.revoke(this, grant.refresh, "refresh_token")
        expectStatus(ex, 200, what = "RFC 7009 revocation")
        expectProblem(api("GET", "getMe", "/me", token = grant.access), 401, "unauthenticated", what = "the access token after revocation")
        if (media != null) expectProblem(fetchMedia(media, "bytes=0-15"), 403, "media-grant-expired", what = "a media URL issued under the revoked grant")
        val refresh = run.oauth.refresh(this, grant.refresh)
        expectStatus(refresh, 400, what = "the revoked refresh token")
    },
    Check("DA-AUTH-8", Core, "replaying a rotated refresh token after the window revokes the grant", "README §3 Tokens") {
        requires("DA-DISC-4")
        val tokens = run.oauth.signIn(this, "catalog:read offline_access", "replay-after-window test")
        val first = tokens.str("refresh_token") ?: fail("no refresh token")
        val rotated = body(run.oauth.refresh(this, first).also { expectStatus(it, 200, what = "first refresh") })
        Thread.sleep((config.refreshWindowSeconds + 2) * 1000L)
        val replay = run.oauth.refresh(this, first)
        expectStatus(replay, 400, what = "replaying the old refresh token after ${config.refreshWindowSeconds} s")
        ensure(body(replay).str("error") == "invalid_grant") { "replay: error ${body(replay).str("error")}, expected invalid_grant" }
        expectProblem(api("GET", "getMe", "/me", token = rotated.str("access_token")), 401, "unauthenticated", what = "the grant's newest access token after the replay")
        expectStatus(run.oauth.refresh(this, rotated.str("refresh_token")!!), 400, what = "the grant's newest refresh token after the replay")
    },

    // Personal tokens (README §3 Tokens)
    Check("DA-TOK-1", Core, "a personal token carries exactly its scopes, a prefix and its parent", "README §3 Tokens") {
        requires("DA-PRE-1")
        val pat = mintToken(listOf("catalog:read"))
        ensure(pat.secret.startsWith("dulcet_pat_")) { "personal token secret lacks the dulcet_pat_ prefix" }
        ensure(pat.body.path("scopes").map { it.asText() } == listOf("catalog:read")) { "created token scopes ${pat.body.path("scopes")} != [catalog:read]" }
        val parent = me().path("token").str("id")
        ensure(pat.body.str("parentId") == parent) { "parentId ${pat.body.str("parentId")} is not the creating token's id $parent" }
        ensure(scopesOf(pat.secret) == setOf("catalog:read")) { "the token works with scopes ${scopesOf(pat.secret)}" }
    },
    Check("DA-TOK-2", Core, "a personal token's secret is shown once", "README §3 Tokens") {
        requires("DA-PRE-1")
        val pat = mintToken(listOf("catalog:read"))
        val list = api("GET", "listTokens", "/me/tokens", query = listOf("limit" to "500"))
        expectStatus(list, 200)
        ensure(!list.text().contains(pat.secret)) { "GET /me/tokens shows the secret again" }
        ensure(body(list).path("items").any { it.str("id") == pat.id }) { "the new token is not listed" }
    },
    Check("DA-TOK-3", Core, "a scope above the calling token's is refused", "README §3 Tokens") {
        requires("DA-PRE-1")
        val parent = mintToken(listOf("catalog:read", "account"))
        val ex = api("POST", "createPersonalToken", "/me/tokens", token = parent.secret,
            body = mapOf("name" to "conformance escalation", "scopes" to listOf("catalog:read", "user:write"), "expiresInDays" to 1))
        expectProblem(ex, 403, "insufficient-scope", what = "minting user:write from a catalog:read+account token")
    },
    Check("DA-TOK-4", Core, "a lifetime up to limits.maxTokenLifetimeDays is accepted and one day more answers 422", "openapi Capabilities.limits.maxTokenLifetimeDays") {
        requires("DA-PRE-1")
        val caps = run.capabilities ?: body(api("GET", "getCapabilities", "/capabilities", token = null)).also { run.capabilities = it }
        val max = caps.path("limits").path("maxTokenLifetimeDays").takeIf { it.isInt }?.asInt() ?: fail("capabilities.limits has no maxTokenLifetimeDays")
        mintToken(listOf("catalog:read"), name = "conformance lifetime max", expiresInDays = max)
        val ex = api("POST", "createPersonalToken", "/me/tokens",
            body = mapOf("name" to "conformance lifetime over", "scopes" to listOf("catalog:read"), "expiresInDays" to max + 1))
        if (ex.status == 201) ex.json?.str("id")?.let { tid -> api("DELETE", "revokeTokenById", "/me/tokens/${id(tid)}") }
        expectProblem(ex, 422, what = "a personal token one day past maxTokenLifetimeDays ($max)")
    },
    Check("DA-TOK-5", Core, "revoking a token revokes the tokens minted under it", "README §3 Tokens") {
        requires("DA-PRE-1")
        val parent = mintToken(listOf("catalog:read", "account"))
        val child = mintToken(listOf("catalog:read"), parent = parent.secret)
        ensure(child.body.str("parentId") == parent.id) { "the child's parentId is ${child.body.str("parentId")}, not ${parent.id}" }
        expectStatus(api("DELETE", "revokeTokenById", "/me/tokens/${id(parent.id)}"), 204, what = "revoking the parent")
        expectProblem(api("GET", "getMe", "/me", token = parent.secret), 401, "unauthenticated", what = "the revoked parent")
        expectProblem(api("GET", "getMe", "/me", token = child.secret), 401, "unauthenticated", what = "a child of the revoked parent")
    },
    Check("DA-TOK-6", Core, "revoking a token kills the media URLs issued under it", "README §3 Tokens, §9") {
        requireFixture()
        val pat = mintToken(listOf("catalog:read", "media:read"))
        val plan = directPlan(fixture.track(1), token = pat.secret)
        expectStatus(fetchMedia(plan.str("url")!!, "bytes=0-15"), 206, 200, what = "the media URL before revocation")
        expectStatus(api("DELETE", "revokeTokenById", "/me/tokens/${id(pat.id)}"), 204, what = "revoking the token")
        expectProblem(fetchMedia(plan.str("url")!!, "bytes=0-15"), 403, "media-grant-expired", what = "the media URL after revocation")
    },

    // Scopes and the delete scope (README §3 Scopes)
    Check("DA-SCOPE-1", Core, "an operation called without its scope answers 403 insufficient-scope with requiredScopes", "README §3 Scopes, §14") {
        requireFixture()
        val pat = mintToken(listOf("catalog:read"))
        val t = fixture.track(0)
        val probes = listOf(
            Triple("user:read", "listFavorites", { api("GET", "listFavorites", "/me/favorites", token = pat.secret) }),
            Triple("user:read", "listMyLibrary", { api("GET", "listMyLibrary", "/me/library", token = pat.secret) }),
            Triple("user:write", "addFavorite", { api("PUT", "addFavorite", "/me/favorites/${id(t)}", token = pat.secret) }),
            Triple("user:write", "createPlaylist", { api("POST", "createPlaylist", "/playlists", token = pat.secret, body = mapOf("name" to "x")) }),
            Triple("plays:write", "submitPlays", { api("POST", "submitPlays", "/me/plays", token = pat.secret, body = mapOf("plays" to listOf(playBody(UUID.randomUUID().toString(), t)))) }),
            Triple("media:read", "createPlaybackDecision", { api("POST", "createPlaybackDecision", "/playback/decisions", token = pat.secret, body = mapOf("trackId" to t, "profile" to mapOf("directPlay" to listOf(mapOf("container" to "mp3", "codec" to "mp3"))))) }),
            Triple("account", "createPersonalToken", { api("POST", "createPersonalToken", "/me/tokens", token = pat.secret, body = mapOf("name" to "x", "scopes" to listOf("catalog:read"), "expiresInDays" to 1)) }),
            Triple("user:read", "listChanges(itemState)", { api("GET", "listChanges", "/sync/changes", token = pat.secret, query = listOf("start" to "now", "types" to "itemState")) }),
        )
        for ((scope, name, call) in probes) {
            val ex = call()
            expectProblem(ex, 403, "insufficient-scope", what = "$name without $scope")
            ensure(body(ex).path("requiredScopes").any { it.asText() == scope }) { "$name: requiredScopes ${body(ex).path("requiredScopes")} does not name $scope" }
        }
    },
    Check("DA-DEL-1", Core, "removals need user:delete; user:write alone answers 403", "README §3 Scopes, §8") {
        requireFixture()
        val writer = mintToken(listOf("catalog:read", "user:read", "user:write"))
        val t0 = fixture.track(0); val t1 = fixture.track(1)
        val created = api("POST", "createPlaylist", "/playlists", body = mapOf("name" to "conformance del ${run.runId}", "itemIds" to listOf(t0, t1)))
        expectStatus(created, 201, what = "creating a playlist")
        val pl = body(created).str("id")!!
        run.cleanups += "delete playlist" to { api("DELETE", "deletePlaylist", "/playlists/${id(pl)}") ; Unit }
        val items = body(api("GET", "listPlaylistItems", "/playlists/${id(pl)}/items"))
        val rev = items.str("revision")!!
        val entry = items.path("items")[0].str("entryId")!!
        val probes = listOf(
            "remove from My Library" to { api("DELETE", "removeFromMyLibrary", "/me/library/${id(t0)}", token = writer.secret) },
            "delete a play" to { api("DELETE", "deletePlay", "/me/plays/${UUID.randomUUID()}", token = writer.secret) },
            "delete a playlist" to { api("DELETE", "deletePlaylist", "/playlists/${id(pl)}", token = writer.secret) },
            "remove a playlist entry" to { api("DELETE", "removePlaylistItem", "/playlists/${id(pl)}/items/${id(entry)}", token = writer.secret) },
            "replace entries dropping one" to { api("PUT", "replacePlaylistItems", "/playlists/${id(pl)}/items", token = writer.secret, headers = mapOf("If-Match" to "\"$rev\""), body = mapOf("itemIds" to listOf(t0))) },
        )
        for ((what, call) in probes) expectProblem(call(), 403, "insufficient-scope", what = "$what with user:write alone")
        val batch = api("POST", "applyUserStateBatch", "/me/batch", token = writer.secret, body = mapOf("operations" to listOf(
            mapOf("op" to "favorite", "itemId" to t1), mapOf("op" to "libraryRemove", "itemId" to t0))))
        expectStatus(batch, 200, what = "a batch with libraryRemove under user:write")
        val results = body(batch).path("results")
        ensure(results.size() == 2 && results[0].path("status").asInt() in 200..299 && results[1].path("status").asInt() == 403) {
            "batch results ${results.map { it.path("status").asInt() }}: expected [2xx, 403] (only libraryRemove fails)"
        }
        api("DELETE", "removeFavorite", "/me/favorites/${id(t1)}")
    },
)

/** The grant the OAuth checks share, in order. */
class Grant(val auth: Authorization, var access: String, var refresh: String, val scope: String?) {
    var previousRefresh: String? = null
    var rotatedAt: Long = 0
    var firstRotation: Pair<String?, String?>? = null
    var mediaUrl: String? = null
}

fun playBody(playId: String, trackId: String, listenedMs: Int = 200_000): Map<String, Any> = mapOf(
    "id" to playId,
    "trackId" to trackId,
    "startedAt" to java.time.Instant.now().minusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
    "listenedMs" to listenedMs,
    "completed" to true,
    "client" to "dulcet-api-conformance",
)
