package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import java.io.PrintStream
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

const val SUITE_VERSION = "0.1.0"

enum class Profile { Core, Sync, Device, Testing, Queue, Events, Webhooks, MCP }

/** MANUAL: the check needs an action outside the API that the run was not given a command for. */
enum class Outcome { PASS, FAIL, SKIP, MANUAL }

class CheckFailure(message: String) : Exception(message)
class CheckSkip(message: String) : Exception(message)
class CheckManual(message: String) : Exception(message)

enum class OAuthMode { OFF, AUTO, INTERACTIVE }

data class Config(
    val baseUrl: String,
    val profiles: Set<Profile> = setOf(Profile.Core, Profile.Sync),
    val tokenA: String? = null,
    val tokenB: String? = null,
    val adminToken: String? = null,
    val otherInstanceUrl: String? = null,
    val otherInstanceToken: String? = null,
    val oauthMode: OAuthMode = OAuthMode.AUTO,
    val clientId: String = "https://conformance.dulcet.invalid/oauth/client.json",
    val oauthTimeoutSeconds: Int = 300,
    val libraryDir: Path? = null,
    val serverLog: Path? = null,
    val serverLogCommand: String? = null,
    val serverStartCommand: String? = null,
    val serverRestartCommand: String? = null,
    val serverCommandTimeoutSeconds: Int = 120,
    val nonTestUrl: String? = null,
    val nonTestAdminToken: String? = null,
    val refreshWindowSeconds: Int = 60,
    val reportJson: Path? = null,
    val reportJunit: Path? = null,
    val only: Set<String>? = null,
    val contractFile: Path? = null,
    val scanTimeoutSeconds: Int = 180,
    val requireComplete: Boolean = false,
    /** Where the in-suite webhook receiver listens (host:port; port 0 picks a free one). */
    val webhookListen: String = "127.0.0.1:0",
    /** The base URL the server delivers to, reaching [webhookListen] (for example through a TLS proxy); default the listener itself. */
    val webhookUrl: String? = null,
    /** How long to wait for an event or a delivery that must arrive. */
    val eventTimeoutSeconds: Int = 15,
    /** How long to watch before concluding that an event or delivery that must not arrive did not. */
    val quietSeconds: Int = 5,
    val out: PrintStream = System.out,
)

/** A check with a stable id, its profile and the contract text it proves. */
class Check(
    val id: String,
    val profile: Profile,
    val title: String,
    val ref: String,
    val body: Ctx.() -> Unit,
)

data class CheckResult(
    val id: String,
    val profile: Profile,
    val title: String,
    val ref: String,
    val outcome: Outcome,
    val message: String,
    val durationMs: Long,
)

/** Catalog items the run found and uses as subjects. */
class Fixture {
    val albums = mutableListOf<JsonNode>()
    val tracks = mutableListOf<JsonNode>()
    val artists = mutableListOf<JsonNode>()
    /** Album id to its track ids, in disc and track order. */
    val albumTracks = LinkedHashMap<String, List<String>>()
    val ready: Boolean get() = albums.size >= 2 && tracks.size >= 4

    fun track(i: Int): String = tracks[i % tracks.size].path("id").asText()
    fun album(i: Int): String = albums[i % albums.size].path("id").asText()
}

class PersonalToken(val id: String, val secret: String, val body: JsonNode)

/** Shared state of one run. */
class Run(val config: Config) {
    val secrets = Secrets()
    val contract: Contract = Contract.load(config.contractFile)
    val exchanges = mutableListOf<Exchange>()
    var current: Ctx? = null
    val http = Http { exchange -> record(exchange) }
    val origin: String
    var apiBase: String
    var discovery: JsonNode? = null
    var asMetadata: JsonNode? = null
    var capabilities: JsonNode? = null
    val fixture = Fixture()
    val failedPreconditions = mutableSetOf<String>()
    val cleanups = mutableListOf<Pair<String, () -> Unit>>()
    val runId: String = UUID.randomUUID().toString().substring(0, 8)
    val startedAt: Instant = Instant.now()
    var serverLogStart: Long = 0
    var library: LibraryFixture? = null
    val oauth = OAuthDriver(this)
    var grant: Grant? = null
    val libraryPaths = HashMap<String, Path>()
    val libraryIds = HashMap<String, String>()
    /** The Testing-profile overrides the suite has set (PUT replaces them all, so the suite keeps the whole object). */
    var testSettings: com.fasterxml.jackson.databind.node.ObjectNode = json.createObjectNode()
    var testSettingsCleanup = false
    private var listener: WebhookListener? = null
    /** The in-suite webhook receiver, started on first use and stopped when the run ends. */
    val webhookListener: WebhookListener
        @Synchronized get() = listener ?: WebhookListener(config.webhookListen, config.webhookUrl, runId).also {
            listener = it
            cleanups.add(0, "stop the webhook receiver" to { it.close() })
        }

    init {
        val uri = URI(config.baseUrl.trimEnd('/'))
        origin = "${uri.scheme}://${uri.rawAuthority}"
        apiBase = "$origin/dulcet/v1"
        secrets.add(config.tokenA, SecretKind.TOKEN)
        secrets.add(config.tokenB, SecretKind.TOKEN)
        secrets.add(config.adminToken, SecretKind.TOKEN)
        secrets.add(config.otherInstanceToken, SecretKind.TOKEN)
    }

    private fun record(exchange: Exchange) {
        synchronized(exchanges) { exchanges += exchange }
        val ctx = current ?: return
        val id = exchange.operationId ?: return
        val violations = contract.validate(id, exchange.status, exchange.contentType, exchange.body, exchange.json)
        violations.forEach { ctx.violations += "${exchange.describe()}: $it" }
        // Learn any media grant the server hands out, so it is redacted and looked for in logs.
        exchange.json?.let { learnGrants(it) }
    }

    private fun learnGrants(node: JsonNode) {
        when {
            node.isObject -> node.fields().forEach { (_, v) -> learnGrants(v) }
            node.isArray -> node.forEach { learnGrants(it) }
            node.isTextual -> {
                val text = node.asText()
                val marker = "/media/"
                if (text.startsWith("http") && text.contains(marker)) {
                    secrets.add(text.substringAfter(marker).substringBefore('?'), SecretKind.GRANT)
                }
            }
        }
    }
}

/** What a check body can do. */
class Ctx(val run: Run, val check: Check) {
    val violations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** Event streams and other live resources this check opened; closed when it ends, their contract violations added. */
    val streams = java.util.concurrent.CopyOnWriteArrayList<EventStream>()

    fun closeStreams() {
        for (s in streams) {
            s.close()
            violations += s.violations
        }
        streams.clear()
    }
    val config get() = run.config
    val fixture get() = run.fixture

    fun fail(message: String): Nothing = throw CheckFailure(message)
    fun skip(message: String): Nothing = throw CheckSkip(message)
    /** Ends the check as MANUAL: it needs [message]'s step outside the API, which never counts as complete. */
    fun manual(message: String): Nothing = throw CheckManual(message)
    fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) fail(message())
    }

    fun requires(vararg preconditions: String) {
        preconditions.firstOrNull { it in run.failedPreconditions }?.let { skip("precondition $it failed; see that check") }
    }

    fun requireFixture() {
        requires("DA-PRE-1", "DA-PRE-2")
        if (!fixture.ready) skip("no catalog subjects; see DA-PRE-2")
    }

    val tokenA: String get() = config.tokenA ?: skip("DULCET_TOKEN is not set")
    val tokenB: String get() = config.tokenB ?: skip("DULCET_TOKEN_B is not set: this check needs a second disposable user")
    val adminToken: String get() = config.adminToken ?: skip("DULCET_ADMIN_TOKEN is not set: this check needs an admin token for the disposable instance")

    fun url(path: String, query: List<Pair<String, String?>> = emptyList()): String {
        val q = Http.query(query)
        return run.apiBase + path + if (q.isEmpty()) "" else "?$q"
    }

    /** Calls the API at [path] (already holding encoded ids) as [token]; [body] is serialised as JSON unless it is a ByteArray. */
    fun api(
        method: String,
        operationId: String,
        path: String,
        token: String? = tokenA,
        query: List<Pair<String, String?>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
        contentType: String = "application/json",
    ): Exchange = send(method, operationId, url(path, query), token, headers, body, contentType)

    fun send(
        method: String,
        operationId: String?,
        absoluteUrl: String,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
        contentType: String = "application/json",
    ): Exchange {
        val all = LinkedHashMap<String, String>()
        if (token != null) all["Authorization"] = "Bearer $token"
        val bytes = when (body) {
            null -> null
            is ByteArray -> body
            is JsonNode -> json.writeValueAsBytes(body)
            else -> json.writeValueAsBytes(body)
        }
        if (bytes != null) all["Content-Type"] = contentType
        all.putAll(headers)
        return run.http.send(operationId, method, absoluteUrl, all, bytes)
    }

    fun id(value: String) = Http.encodePath(value)

    fun expectStatus(exchange: Exchange, vararg expected: Int, what: String = "") {
        if (exchange.status !in expected) {
            val detail = exchange.json?.path("type")?.asText()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            fail("${if (what.isEmpty()) "" else "$what: "}expected ${expected.joinToString("/")}, got ${exchange.describe()}$detail")
        }
    }

    /** The response is RFC 9457 problem details with this status and, when given, this problem type. */
    fun expectProblem(exchange: Exchange, status: Int, type: String? = null, what: String = "") {
        expectStatus(exchange, status, what = what)
        ensure(exchange.contentType == "application/problem+json") {
            "${exchange.describe()}: error body is ${exchange.contentType}, not application/problem+json"
        }
        if (type != null) {
            val actual = exchange.json?.path("type")?.asText()
            ensure(actual == "urn:dulcet:problem:$type") { "${exchange.describe()}: problem type $actual, expected urn:dulcet:problem:$type" }
        }
    }

    fun body(exchange: Exchange): JsonNode = exchange.json ?: fail("${exchange.describe()}: expected a JSON body")

    /**
     * Changes the Testing-profile overrides (README "Testing profile") with [mutate] and PUTs the whole
     * object, as the operation replaces every override at once. The run resets them to `{}` at the end.
     */
    fun setTestSettings(mutate: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit) {
        val admin = adminToken
        val next = run.testSettings.deepCopy().also(mutate)
        val ex = api("PUT", "replaceTestSettings", "/admin/test/settings", token = admin, body = next)
        expectStatus(ex, 200, what = "setting test overrides ${next.fieldNames().asSequence().toList()}")
        run.testSettings = next
        if (!run.testSettingsCleanup) {
            run.testSettingsCleanup = true
            run.cleanups.add(0, "reset the test overrides" to {
                run.http.send("replaceTestSettings", "PUT", run.apiBase + "/admin/test/settings",
                    mapOf("Authorization" to "Bearer $admin", "Content-Type" to "application/json"), "{}".toByteArray())
            })
        }
    }

    /** Runs [block] with the overrides changed by [mutate], then puts the previous overrides back. */
    fun <T> withTestSettings(mutate: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit, block: () -> T): T {
        val previous = run.testSettings.deepCopy()
        setTestSettings(mutate)
        try {
            return block()
        } finally {
            run.http.send("replaceTestSettings", "PUT", run.apiBase + "/admin/test/settings",
                mapOf("Authorization" to "Bearer $adminToken", "Content-Type" to "application/json"), json.writeValueAsBytes(previous))
            run.testSettings = previous
        }
    }

    /** Lets OAuth sign-ins and device codes from the suite's client id complete without a person, as user A. */
    fun ensureAutoApprove(clientId: String = config.clientId) {
        val listed = run.testSettings.path("autoApprove").any { it.str("clientId") == clientId }
        if (listed) return
        val userId = me().path("id").asText()
        setTestSettings { s ->
            val list = s.get("autoApprove") as? com.fasterxml.jackson.databind.node.ArrayNode ?: s.putArray("autoApprove")
            list.addObject().put("clientId", clientId).put("userId", userId)
        }
    }

    /** The `webhooks` object of the Testing overrides, created if absent, for use inside [setTestSettings]. */
    fun webhookSettings(s: com.fasterxml.jackson.databind.node.ObjectNode): com.fasterxml.jackson.databind.node.ObjectNode =
        s.get("webhooks") as? com.fasterxml.jackson.databind.node.ObjectNode ?: s.putObject("webhooks")

    /** Lets the server deliver to the suite's own plain-HTTP receiver (Testing override `webhooks.allowPrivateTargets`). */
    fun ensureWebhookTargets() {
        if (run.testSettings.path("webhooks").path("allowPrivateTargets").asBoolean(false)) return
        setTestSettings { webhookSettings(it).put("allowPrivateTargets", true) }
    }

    /** Lists the suite's trusted client in the Testing override `trustedClients`, so it signs in verified. */
    fun ensureTrustedClient(client: TrustedClient = TrustedClient.SUITE) {
        if (run.testSettings.path("trustedClients").any { it.str("client_id") == client.clientId }) return
        setTestSettings { s ->
            val list = s.get("trustedClients") as? com.fasterxml.jackson.databind.node.ArrayNode ?: s.putArray("trustedClients")
            list.add(json.valueToTree<JsonNode>(client.document()))
        }
    }

    /** Mints a personal token from token A with exactly [scopes]; revoked at the end of the run. */
    fun mintToken(scopes: List<String>, name: String = "conformance ${check.id}", parent: String = tokenA, expiresInDays: Int = 1): PersonalToken {
        val ex = api("POST", "createPersonalToken", "/me/tokens", token = parent,
            body = mapOf("name" to name, "scopes" to scopes, "expiresInDays" to expiresInDays))
        expectStatus(ex, 201, what = "minting a personal token with $scopes")
        val b = body(ex)
        val secret = b.path("secret").asText()
        run.secrets.add(secret, SecretKind.TOKEN)
        val token = PersonalToken(b.path("id").asText(), secret, b)
        val owner = parent
        run.cleanups += "revoke token ${token.id}" to {
            run.http.send("revokeTokenById", "DELETE", run.apiBase + "/me/tokens/" + id(token.id), mapOf("Authorization" to "Bearer ${config.tokenA ?: owner}"))
        }
        return token
    }

    /** Pages a collection to its end; fails on a loop. */
    fun walk(path: String, operationId: String, token: String? = tokenA, query: List<Pair<String, String?>> = emptyList(), maxPages: Int = 200): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        val seenCursors = HashSet<String>()
        for (page in 0 until maxPages) {
            val ex = api("GET", operationId, path, token, query + listOfNotNull(cursor?.let { "cursor" to it }))
            expectStatus(ex, 200, what = "paging $path")
            val b = body(ex)
            b.path("items").forEach { items.add(it) }
            val next = b.path("nextCursor")
            if (next.isNull || next.isMissingNode) return items
            cursor = next.asText()
            ensure(seenCursors.add(cursor)) { "paging $path: the server repeated a cursor (a loop)" }
        }
        fail("paging $path: still not at the end after $maxPages pages")
    }
}

fun JsonNode.str(name: String): String? = path(name).let { if (it.isNull || it.isMissingNode) null else it.asText() }
fun Iterable<JsonNode>.ids(): List<String> = map { it.path("id").asText() }

/** Runs checks in order and collects results. */
class Runner(private val run: Run, private val checks: List<Check>) {
    fun execute(): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        for (check in checks) {
            if (check.profile !in run.config.profiles) continue
            if (run.config.only != null && check.id !in run.config.only) continue
            val ctx = Ctx(run, check)
            run.current = ctx
            val start = System.nanoTime()
            var outcome: Outcome
            var message: String
            try {
                check.body(ctx)
                ctx.closeStreams()
                if (ctx.violations.isNotEmpty()) {
                    outcome = Outcome.FAIL
                    message = "response does not match openapi.yaml: " + ctx.violations.take(5).joinToString("; ")
                } else {
                    outcome = Outcome.PASS
                    message = ""
                }
            } catch (e: CheckFailure) {
                outcome = Outcome.FAIL
                message = e.message ?: "failed"
                if (ctx.violations.isNotEmpty()) message += "; contract: " + ctx.violations.take(3).joinToString("; ")
            } catch (e: CheckSkip) {
                outcome = Outcome.SKIP
                message = e.message ?: "skipped"
            } catch (e: CheckManual) {
                outcome = Outcome.MANUAL
                message = e.message ?: "manual"
            } catch (e: Exception) {
                outcome = Outcome.FAIL
                message = "unexpected ${e.javaClass.simpleName}: ${e.message}"
            }
            runCatching { ctx.closeStreams() }
            run.current = null
            if (outcome == Outcome.FAIL && check.id.startsWith("DA-PRE-")) run.failedPreconditions += check.id
            val result = CheckResult(check.id, check.profile, check.title, check.ref, outcome,
                run.secrets.redact(message), (System.nanoTime() - start) / 1_000_000)
            results += result
            run.config.out.println(formatLine(result))
        }
        for ((what, action) in run.cleanups.asReversed()) {
            runCatching { action() }.onFailure { run.config.out.println("cleanup: $what failed: ${run.secrets.redact(it.message ?: "")}") }
        }
        return results
    }

    private fun formatLine(r: CheckResult): String {
        val tail = if (r.message.isEmpty()) "" else "\n       ${r.message}"
        return "%-4s %-13s %s  [%s]%s".format(r.outcome.name, r.id, r.title, r.ref, tail)
    }
}
