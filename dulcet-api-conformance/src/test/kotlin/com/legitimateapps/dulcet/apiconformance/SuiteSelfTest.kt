package com.legitimateapps.dulcet.apiconformance

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The suite's own evidence. A healthy mock must pass every check the mock can serve, and every
 * deliberate fault must turn the check that covers it into a FAIL (the negative controls).
 */
class SuiteSelfTest {

    /** Only the checks the contract keeps manual (restarting or starting a server is outside the API). */
    private val expectedSkips = emptySet<String>()

    class RunResult(val report: Report, val code: Int, val output: String, val mock: MockServer, val workDir: Path) {
        fun outcome(id: String) = report.results.first { it.id == id }.outcome
        fun failedIds() = report.results.filter { it.outcome == Outcome.FAIL }.map { it.id }.toSet()
        fun skippedIds() = report.results.filter { it.outcome == Outcome.SKIP }.map { it.id }.toSet()
    }

    private fun runAgainst(faults: Set<Fault> = emptySet(), testMode: Boolean = true, tweak: (Config) -> Config = { it }): RunResult {
        val work = Files.createTempDirectory("dulcet-conf-selftest")
        val library = Files.createDirectories(work.resolve("library"))
        val log = Files.createFile(work.resolve("server.log"))
        val mock = MockServer(faults, refreshWindowSeconds = 1, libraryDir = library, logFile = log, testMode = testMode)
        // The second instance is the same build started without test mode: it serves the foreign-cursor
        // checks and DA-TEST-2, so HOOK_WITHOUT_TEST_MODE belongs on it.
        val other = MockServer(faults.intersect(setOf(Fault.HOOK_WITHOUT_TEST_MODE)), logFile = Files.createFile(work.resolve("other.log")), testMode = false)
        val buffer = ByteArrayOutputStream()
        try {
            val config = Config(
                baseUrl = mock.origin, profiles = Profile.entries.toSet(),
                tokenA = mock.tokenA, tokenB = mock.tokenB, adminToken = mock.adminToken,
                otherInstanceUrl = other.origin, otherInstanceToken = other.tokenA,
                nonTestUrl = other.origin + "/dulcet/v1", nonTestAdminToken = other.adminToken,
                // A refusing start exits non-zero (curl -f on the 409); one that starts keeps running.
                serverStartCommand = "curl -sf -X POST ${mock.origin}/__mock/start-on-served-data && sleep 30",
                serverRestartCommand = "curl -sf -X POST ${mock.origin}/__mock/restart",
                serverCommandTimeoutSeconds = 3,
                oauthMode = OAuthMode.AUTO, oauthTimeoutSeconds = 10,
                libraryDir = library, serverLog = log, refreshWindowSeconds = 1, scanTimeoutSeconds = 10,
                eventTimeoutSeconds = 5, quietSeconds = 1,
                reportJson = work.resolve("report.json"), reportJunit = work.resolve("junit.xml"),
                out = PrintStream(buffer, true, Charsets.UTF_8),
            )
            val (report, code) = runSuite(tweak(config))
            return RunResult(report, code, buffer.toString(Charsets.UTF_8), mock, work)
        } finally {
            mock.close(); other.close()
        }
    }

    @Test fun `home profile accepts valid shelves and rejects duplicates`() {
        val selected = setOf("DA-PRE-1", "DA-PRE-2", "DA-HOME-1")
        val healthy = runAgainst { it.copy(only = selected) }
        assertEquals(Outcome.PASS, healthy.outcome("DA-HOME-1"), healthy.output)
        val broken = runAgainst(setOf(Fault.HOME_DUPLICATE_ITEM)) { it.copy(only = selected) }
        assertEquals(Outcome.FAIL, broken.outcome("DA-HOME-1"), broken.output)
    }

    @Test
    fun `a healthy server passes every check it can serve`() {
        val r = runAgainst()
        println(r.output.lines().last { it.startsWith("DULCET-CONFORMANCE") })
        for (p in Profile.entries) println("profile $p: " + r.report.results.filter { it.profile == p }.groupingBy { it.outcome }.eachCount())
        assertEquals(emptySet(), r.failedIds(), "unexpected failures:\n${r.output}")
        assertEquals(expectedSkips, r.skippedIds(), "unexpected skips:\n${r.output}")
        assertTrue(r.report.complete, "the healthy run is not complete:\n${r.output}")
        assertEquals(0, r.code)
        for (p in listOf(Profile.Queue, Profile.Events, Profile.Webhooks, Profile.MCP)) {
            assertTrue(r.report.results.any { it.profile == p && it.outcome == Outcome.PASS }, "no $p check passed")
        }
        assertTrue(r.report.passed >= 80, "too few checks ran: ${r.report.passed}\n${r.output}")
        assertTrue(r.output.lines().any { it.startsWith("DULCET-CONFORMANCE suite=") })
    }

    @Test
    fun `the reports carry no secret and the JUnit XML is well formed`() {
        val r = runAgainst(setOf(Fault.ECHO_QUERY_IN_ERROR))
        val junit = Files.readString(r.workDir.resolve("junit.xml"))
        val report = Files.readString(r.workDir.resolve("report.json"))
        for (secret in listOf(r.mock.tokenA, r.mock.tokenB, r.mock.adminToken)) {
            assertFalse(junit.contains(secret), "JUnit XML carries a token")
            assertFalse(report.contains(secret), "JSON report carries a token")
            assertFalse(r.output.contains(secret), "console output carries a token")
        }
        assertFalse(Regex("dulcet_(pat|at|rt|ac)_[A-Za-z0-9_-]{8,}").containsMatchIn(junit + report + r.output), "a credential-shaped value leaked")
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(r.workDir.resolve("junit.xml").toFile())
        val parsed = json.readTree(report)
        assertEquals(r.report.failed, parsed.path("counts").path("Core").path("failed").asInt() + parsed.path("counts").path("Sync").path("failed").asInt())
    }

    /** Each deliberate fault and the check(s) that must catch it. */
    private val negativeControls: Map<Fault, Set<String>> = linkedMapOf(
        Fault.HOME_DUPLICATE_ITEM to setOf("DA-HOME-1"),
        Fault.PLAYS_DOUBLE_COUNT to setOf("DA-PLAY-1"),
        Fault.TOKEN_IN_QUERY_ACCEPTED to setOf("DA-AUTH-11"),
        Fault.ECHO_QUERY_IN_ERROR to setOf("DA-AUTH-11"),
        Fault.IGNORE_IF_NONE_MATCH to setOf("DA-ETAG-1"),
        Fault.ETAG_IGNORES_USER_STATE to setOf("DA-ETAG-2"),
        Fault.NO_REFRESH_RETRY_WINDOW to setOf("DA-AUTH-7"),
        Fault.PLAIN_PKCE_ACCEPTED to setOf("DA-AUTH-1"),
        Fault.REVOKE_KEEPS_MEDIA to setOf("DA-AUTH-10"),
        Fault.PAT_SCOPE_ESCALATION to setOf("DA-TOK-3"),
        Fault.DELETE_WITHOUT_DELETE_SCOPE to setOf("DA-DEL-1"),
        Fault.IGNORE_IDEMPOTENCY_KEY to setOf("DA-IDEM-1"),
        Fault.PLAIN_JSON_ERRORS to setOf("DA-ERR-1"),
        Fault.NO_RANGE to setOf("DA-MEDIA-1"),
        Fault.CORS_MISSING to setOf("DA-CORS-1"),
        Fault.SECRET_IN_URL to setOf("DA-LOG-1"),
        Fault.LOGS_SECRETS to setOf("DA-LOG-2"),
        Fault.DROP_TOMBSTONES to setOf("DA-SYNC-8", "DA-SYNC-14"),
        Fault.CURSOR_TYPES_UNBOUND to setOf("DA-SYNC-5"),
        Fault.FOREIGN_CURSOR_ACCEPTED to setOf("DA-SYNC-11"),
        Fault.HIDDEN_NOT_EMITTED to setOf("DA-SYNC-15"),
        Fault.RETAG_NEW_ID to setOf("DA-SYNC-13"),
        Fault.BAD_TRACK_SCHEMA to setOf("DA-CAT-4"),
        Fault.DEVICE_TOKEN_BEFORE_APPROVAL to setOf("DA-DEV-1"),
        Fault.HOOK_WITHOUT_TEST_MODE to setOf("DA-TEST-2"),
        Fault.HOOK_NON_ADMIN to setOf("DA-TEST-1"),
        Fault.AUTO_APPROVE_SKIPS_PKCE to setOf("DA-TEST-4", "DA-AUTH-1", "DA-AUTH-2"),
        Fault.TTL_IGNORED to setOf("DA-MEDIA-3"),
        Fault.RETENTION_IGNORED to setOf("DA-SYNC-17"),
        Fault.RESTORE_KEEPS_INSTANCE to setOf("DA-SYNC-18"),
        Fault.MERGE_NO_REPLACED_BY to setOf("DA-SYNC-16"),
        Fault.RATE_LIMITS_HOOKS to setOf("DA-TEST-6"),
        Fault.TOKEN_LIFETIME_UNBOUNDED to setOf("DA-TOK-4"),
        Fault.STARTS_ON_SERVED_DATA to setOf("DA-TEST-3"),
        Fault.RESTART_KEEPS_OVERRIDES to setOf("DA-TEST-5"),
        Fault.NOW_PLAYING_CLEAR_HEADER_ONLY to setOf("DA-NP-1"),
        Fault.STALE_QUEUE_WRITE_ACCEPTED to setOf("DA-Q-2", "DA-Q-6", "DA-Q-10"),
        Fault.NON_ACTIVE_MOVES_POSITION to setOf("DA-Q-3", "DA-Q-4"),
        Fault.WEBHOOK_UNSIGNED to setOf("DA-WH-3", "DA-WH-4"),
        Fault.MCP_DELETE_WITHOUT_SCOPE to setOf("DA-MCP-4"),
        Fault.WEBHOOK_SURVIVES_REVOCATION to setOf("DA-WH-8"),
        Fault.PRIVATE_TARGET_WITHOUT_OVERRIDE to setOf("DA-WH-2"),
        Fault.WEBHOOK_TARGET_REFUSED_AS_400 to setOf("DA-WH-2"),
        Fault.WEBHOOK_SCOPE_FILTERED to setOf("DA-WH-7"),
        Fault.WEBHOOK_RETRY_IGNORES_SCHEDULE to setOf("DA-WH-5"),
        Fault.WEBHOOK_NEVER_DISABLED to setOf("DA-WH-9"),
        Fault.WEBHOOK_DISABLED_ON_REVOCATION to setOf("DA-WH-8", "DA-WH-10"),
        Fault.TRUSTED_CLIENTS_IGNORED to setOf("DA-WH-10"),
        Fault.MCP_TOOLS_WITHOUT_META to setOf("DA-MCP-3"),
        Fault.PRESENCE_NEEDS_STREAM to setOf("DA-Q-12"),
        Fault.EVENTS_DEFAULT_REFUSES_UNREADABLE to setOf("DA-EV-9"),
    )

    @Test
    fun `without start and restart commands those checks are MANUAL and a complete run is refused`() {
        val r = runAgainst(tweak = { it.copy(serverStartCommand = null, serverRestartCommand = null, requireComplete = true, only = setOf("DA-PRE-1", "DA-TEST-3", "DA-TEST-5")) })
        assertEquals(Outcome.MANUAL, r.outcome("DA-TEST-3"), r.output)
        assertEquals(Outcome.MANUAL, r.outcome("DA-TEST-5"), r.output)
        assertEquals(1, r.code, "--require-complete accepted MANUAL checks")
        assertTrue(r.output.lines().any { it.startsWith("DULCET-CONFORMANCE") && "manual=2" in it && "complete=false" in it }, r.output)
    }

    @Test
    fun `the suite refuses a server that is not in test mode and runs no check`() {
        val r = runAgainst(testMode = false)
        assertEquals(2, r.code, r.output)
        assertTrue(r.report.results.isEmpty(), "checks ran against a server without test mode")
        assertTrue(r.output.contains("testMode: true"), r.output)
    }

    @TestFactory
    fun `every deliberate fault fails the check that covers it`() = negativeControls.map { (fault, ids) ->
        DynamicTest.dynamicTest("$fault fails ${ids.joinToString()}") {
            // JUnit's method timeout only bounds the factory, not its dynamic children.
            assertTimeoutPreemptively(Duration.ofSeconds(90)) {
                val only = checksForNegativeControl(ids)
                // Prove the same selection passes without the fault: missing setup must never
                // masquerade as detection. The full healthy test above still covers all profiles.
                val healthy = runAgainst(tweak = { it.copy(only = only) })
                assertEquals(emptySet(), healthy.failedIds(), "$fault healthy control:\n${healthy.output}")
                for (id in only) assertEquals(Outcome.PASS, healthy.outcome(id), "$fault healthy $id:\n${healthy.output}")
                val r = runAgainst(setOf(fault), tweak = { it.copy(only = only) })
                val failed = r.failedIds()
                assertTrue(failed.containsAll(ids), "$fault: expected ${ids} to FAIL, failures were $failed\n${r.output}")
                assertEquals(1, r.code)
            }
        }
    }

    /** Shared subjects and the few checks that consume state left by an earlier check. */
    private fun checksForNegativeControl(ids: Set<String>): Set<String> {
        val only = (ids + setOf("DA-PRE-1", "DA-PRE-2", "DA-DISC-1", "DA-DISC-4")).toMutableSet()
        if ("DA-AUTH-7" in ids) only += setOf("DA-AUTH-3", "DA-AUTH-6")
        if ("DA-AUTH-10" in ids) only += setOf("DA-AUTH-3", "DA-AUTH-9")
        if (ids.any { it in setOf("DA-SYNC-13", "DA-SYNC-14") }) only += "DA-SYNC-12"
        // DA-ERR-1 inspects earlier errors; request an unknown catalog id to produce a 404.
        if ("DA-ERR-1" in ids) only += "DA-CAT-5"
        // DA-LOG-1 inspects earlier responses; exercise issuance and fetching of a media URL.
        if ("DA-LOG-1" in ids) only += "DA-MEDIA-1"
        return only
    }

    @Test
    fun `the validator rejects a body that breaks the schema`() {
        val contract = Contract.load(null)
        val bad = """{"id":"t1","type":"track","title":"x","revision":"r","album":{"id":"a"},"audio":{"codec":"mp3","container":"mp3"},"durationMs":"long"}"""
        val violations = contract.validate("getTrack", 200, "application/json", bad.toByteArray(), json.readTree(bad))
        assertTrue(violations.isNotEmpty(), "an invalid Track validated")
        val problem = """{"type":"urn:dulcet:problem:not-found","title":"No such track.","status":404}"""
        assertEquals(emptyList(), contract.validate("getTrack", 404, "application/problem+json", problem.toByteArray(), json.readTree(problem)))
    }

    @Test
    fun `arguments refuse credentials in the URL and a run without a token`() {
        val out = PrintStream(ByteArrayOutputStream())
        val env = mapOf("DULCET_TOKEN" to "dulcet_pat_canary-value-0001")
        assertFailsWith<UsageError> { parseArgs(arrayOf("--base-url", "https://user:pw@music.test"), env, out) }
        assertFailsWith<UsageError> { parseArgs(arrayOf("--base-url", "https://music.test/?token=x"), env, out) }
        assertFailsWith<UsageError> { parseArgs(arrayOf("--base-url", "https://music.test"), emptyMap(), out) }
        val config = parseArgs(arrayOf("--base-url", "https://music.test", "--profile", "core", "--server-log-command", "cat server.log"), env, out)!!
        assertEquals(setOf(Profile.Core), config.profiles)
        assertEquals(OAuthMode.AUTO, config.oauthMode)
        assertEquals("cat server.log", config.serverLogCommand)
        val hooks = parseArgs(arrayOf("--base-url", "https://music.test", "--profile", "Queue,Events,Webhooks,MCP", "--webhook-listen", "0.0.0.0:8099",
            "--webhook-url", "https://hooks.example.net/", "--event-timeout-seconds", "7", "--quiet-seconds", "2"), env, out)!!
        assertEquals(setOf(Profile.Queue, Profile.Events, Profile.Webhooks, Profile.MCP), hooks.profiles)
        assertEquals("0.0.0.0:8099", hooks.webhookListen)
        assertEquals("https://hooks.example.net", hooks.webhookUrl)
        assertEquals(listOf(7, 2), listOf(hooks.eventTimeoutSeconds, hooks.quietSeconds))
        assertFailsWith<UsageError> { parseArgs(arrayOf("--base-url", "https://music.test", "--webhook-url", "https://u:p@hooks.example.net"), env, out) }
        assertFailsWith<UsageError> { parseArgs(arrayOf("--base-url", "https://music.test", "--webhook-listen", "nowhere"), env, out) }
        assertFailsWith<UsageError> { parseArgs(arrayOf("--base-url", "https://music.test", "--quiet-seconds", "0"), env, out) }
    }

    @Test
    fun `webhook signatures verify with the Standard Webhooks example and fail with any change`() {
        // The published Standard Webhooks example (secret, id, timestamp, payload, signature).
        val secret = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
        val body = "{\"test\": 2432232314}".toByteArray()
        val header = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE="
        assertTrue(WebhookSignature.verify(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", "1614265330", body, header))
        assertTrue(WebhookSignature.verify(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", "1614265330", body, "v1,bm9wZQ== $header"))
        assertFalse(WebhookSignature.verify(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", "1614265331", body, header))
        assertFalse(WebhookSignature.verify("whsec_" + java.util.Base64.getEncoder().encodeToString(ByteArray(24)), "msg_p5jXN8AQM9LWM0D4loKWxJek", "1614265330", body, header))
        assertFalse(WebhookSignature.verify(secret, "msg_p5jXN8AQM9LWM0D4loKWxJek", "1614265330", body, header.removePrefix("v1,")))
    }

    @Test
    fun `check ids are unique and every check names its contract reference`() {
        val checks = allChecks()
        assertEquals(checks.size, checks.map { it.id }.toSet().size, "duplicate check id")
        checks.forEach { assertTrue(it.ref.isNotBlank(), "${it.id} has no contract reference") }
    }
}
