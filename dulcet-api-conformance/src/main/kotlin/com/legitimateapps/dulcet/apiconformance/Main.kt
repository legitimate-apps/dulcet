package com.legitimateapps.dulcet.apiconformance

import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/** Every check, in run order. Aggregate checks judge the whole run, so they come last. */
fun allChecks(): List<Check> = accessChecks() + dataChecks() + syncChecks() + deviceChecks() + testingChecks() + queueChecks() + eventChecks() + webhookChecks() + mcpChecks() + aggregateChecks()

const val USAGE = """dulcet-api-conformance: the Dulcet API v1 conformance suite (docs/dulcet-api/CONFORMANCE.md)

Usage:
  java -jar dulcet-api-conformance.jar --base-url https://music.test [options]

The suite writes to the server (favourites, plays, playlists, tokens, library files) and uses the
Testing-profile hooks, so it runs only against a server started in test mode: it refuses unless
/.well-known/dulcet says testMode: true (README "Testing profile").

Credentials come from the environment, never the URL:
  DULCET_TOKEN                 required: a token with catalog:read media:read user:read user:write
                               user:delete plays:write account
  DULCET_TOKEN_B               a second user's token (isolation, readOnly, visibility checks)
  DULCET_ADMIN_TOKEN           an admin token (scans, source visibility)
  DULCET_OTHER_INSTANCE_URL    a second disposable instance, for foreign-cursor checks
  DULCET_OTHER_INSTANCE_TOKEN  a token on that instance
  DULCET_NON_TEST_URL          the same build started WITHOUT test mode (Testing profile: hooks must 404)
  DULCET_NON_TEST_ADMIN_TOKEN  an admin token on that instance

Options:
  --profile Core,Sync,Device,Testing,Queue,Events,Webhooks,MCP
                                profiles to run (default Core,Sync)
  --oauth auto|interactive|off  OAuth flow checks (default auto: the suite sets autoApprove for its
                                client id as user A; interactive prints URLs for a person to approve)
  --client-id URL               OAuth client id (default an unverifiable https URL, so the server
                                treats the suite as an unverified client with a loopback redirect)
  --refresh-window-seconds N    the refresh-token retry window to wait out (default 60)
  --library-dir DIR             a directory the server scans; the suite adds, retags and deletes its
                                own files in a subfolder and triggers POST /admin/scans
  --scan-timeout-seconds N      how long to wait for a scan (default 180)
  --server-log FILE             the server's log, scanned for canaries and secrets after the run
  --server-log-command CMD      a command that prints the server's log (e.g. docker logs NAME), run
                                after the checks, for servers whose log is not a file
  --server-start-command CMD    starts the same build in test mode on data a normal start already
                                served (DA-TEST-3); it must exit non-zero, refusing, within the timeout
  --server-restart-command CMD  restarts the server under test and returns (DA-TEST-5); the suite then
                                waits for discovery and checks the overrides are gone
  --server-command-timeout-seconds N   limit for those two commands (default 120)
  --webhook-listen HOST:PORT    where the suite's webhook receiver listens (Webhooks profile;
                                default 127.0.0.1:0, an ephemeral loopback port)
  --webhook-url URL             the base URL the server delivers to when it cannot reach the
                                listener directly, e.g. an HTTPS proxy in front of it (default the
                                listener's own http:// address). The suite sets the Testing override
                                webhooks.allowPrivateTargets, so a server that refuses it FAILS
  --event-timeout-seconds N     how long to wait for an event or a delivery (default 15)
  --quiet-seconds N             how long to watch for something that must NOT arrive (default 5)
  --junit FILE                  write JUnit XML (one test case per check id)
  --report-json FILE            write the JSON report
  --only ID,ID                  run only these check ids (preconditions are not added for you)
  --contract FILE               validate against this openapi.yaml instead of the bundled one
  --require-complete            exit non-zero unless every check ran and passed (no SKIP or MANUAL)
  --list                        print the checks and exit
"""

class UsageError(message: String) : Exception(message)

fun parseArgs(args: Array<String>, env: Map<String, String>, out: PrintStream): Config? {
    val opts = HashMap<String, String>()
    val flags = HashSet<String>()
    var i = 0
    val valued = setOf("--base-url", "--profile", "--oauth", "--client-id", "--refresh-window-seconds", "--library-dir",
        "--scan-timeout-seconds", "--server-log", "--server-log-command", "--server-start-command", "--server-restart-command", "--server-command-timeout-seconds", "--junit", "--report-json", "--only", "--contract", "--oauth-timeout-seconds",
        "--webhook-listen", "--webhook-url", "--event-timeout-seconds", "--quiet-seconds")
    val boolean = setOf("--require-complete", "--list", "--help", "-h")
    while (i < args.size) {
        val arg = args[i]
        val (name, inline) = if (arg.startsWith("--") && arg.contains('=')) arg.substringBefore('=') to arg.substringAfter('=') else arg to null
        when (name) {
            in valued -> {
                val value = inline ?: args.getOrNull(++i) ?: throw UsageError("$name needs a value")
                opts[name] = value
            }
            in boolean -> flags += name
            else -> throw UsageError("unknown option $arg")
        }
        i++
    }
    if ("--help" in flags || "-h" in flags) { out.println(USAGE); return null }
    if ("--list" in flags) {
        allChecks().forEach { out.println("%-13s %-5s %s  [%s]".format(it.id, it.profile, it.title, it.ref)) }
        return null
    }
    val base = opts["--base-url"] ?: throw UsageError("--base-url is required")
    val uri = runCatching { URI(base) }.getOrNull() ?: throw UsageError("--base-url is not a URL")
    if (uri.scheme !in setOf("http", "https") || uri.host == null) throw UsageError("--base-url must be an http(s) URL")
    if (uri.rawUserInfo != null || uri.rawQuery != null) throw UsageError("--base-url must not carry credentials or a query; pass tokens through DULCET_TOKEN")
    val profiles = (opts["--profile"] ?: "Core,Sync").split(',').map { p ->
        Profile.entries.firstOrNull { it.name.equals(p.trim(), ignoreCase = true) } ?: throw UsageError("unknown profile $p (known: ${Profile.entries.joinToString()})")
    }.toSet()
    val oauth = when (opts["--oauth"]?.lowercase() ?: "auto") {
        "off" -> OAuthMode.OFF
        "auto" -> OAuthMode.AUTO
        "interactive" -> OAuthMode.INTERACTIVE
        else -> throw UsageError("--oauth must be off, auto or interactive")
    }
    fun path(name: String): Path? = opts[name]?.let { Paths.get(it) }
    fun int(name: String, default: Int): Int = opts[name]?.let { it.toIntOrNull() ?: throw UsageError("$name must be a number") } ?: default
    val token = env["DULCET_TOKEN"]?.takeIf { it.isNotBlank() } ?: throw UsageError("DULCET_TOKEN is not set")
    path("--library-dir")?.let { if (!Files.isDirectory(it)) throw UsageError("--library-dir $it is not a directory") }
    path("--server-log")?.let { if (!Files.isRegularFile(it)) throw UsageError("--server-log $it is not a file") }
    fun positive(name: String, default: Int): Int = opts[name]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw UsageError("$name takes a positive number") } ?: default
    val listen = opts["--webhook-listen"] ?: "127.0.0.1:0"
    if (listen.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 0..65535 } == null) throw UsageError("--webhook-listen takes HOST:PORT")
    opts["--webhook-url"]?.let { w ->
        val wu = runCatching { URI(w) }.getOrNull()
        if (wu == null || wu.scheme !in setOf("http", "https") || wu.host == null || wu.rawUserInfo != null || wu.rawQuery != null)
            throw UsageError("--webhook-url must be an http(s) URL without credentials or a query")
    }
    return Config(
        baseUrl = base,
        profiles = profiles,
        tokenA = token,
        tokenB = env["DULCET_TOKEN_B"]?.takeIf { it.isNotBlank() },
        adminToken = env["DULCET_ADMIN_TOKEN"]?.takeIf { it.isNotBlank() },
        otherInstanceUrl = env["DULCET_OTHER_INSTANCE_URL"]?.takeIf { it.isNotBlank() },
        otherInstanceToken = env["DULCET_OTHER_INSTANCE_TOKEN"]?.takeIf { it.isNotBlank() },
        oauthMode = oauth,
        clientId = opts["--client-id"] ?: Config(baseUrl = base).clientId,
        oauthTimeoutSeconds = int("--oauth-timeout-seconds", 300),
        libraryDir = path("--library-dir"),
        serverLog = path("--server-log"),
        serverLogCommand = opts["--server-log-command"],
        serverStartCommand = opts["--server-start-command"],
        serverRestartCommand = opts["--server-restart-command"],
        serverCommandTimeoutSeconds = opts["--server-command-timeout-seconds"]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw UsageError("--server-command-timeout-seconds takes a positive number") } ?: 120,
        nonTestUrl = env["DULCET_NON_TEST_URL"]?.takeIf { it.isNotBlank() },
        nonTestAdminToken = env["DULCET_NON_TEST_ADMIN_TOKEN"]?.takeIf { it.isNotBlank() },
        refreshWindowSeconds = int("--refresh-window-seconds", 60),
        reportJson = path("--report-json"),
        reportJunit = path("--junit"),
        only = opts["--only"]?.split(',')?.map { it.trim() }?.toSet(),
        contractFile = path("--contract"),
        scanTimeoutSeconds = int("--scan-timeout-seconds", 180),
        requireComplete = "--require-complete" in flags,
        webhookListen = listen,
        webhookUrl = opts["--webhook-url"]?.trimEnd('/'),
        eventTimeoutSeconds = positive("--event-timeout-seconds", 15),
        quietSeconds = positive("--quiet-seconds", 5),
        out = out,
    )
}

/** Runs the suite; returns the results and the process exit code. */
fun runSuite(config: Config, checks: List<Check> = allChecks()): Pair<Report, Int> {
    val run = Run(config)
    config.serverLog?.let { run.serverLogStart = Files.size(it) }
    config.out.println("Dulcet API conformance suite $SUITE_VERSION against ${run.origin} (contract ${run.contract.version}, sha256 ${run.contract.sha256.take(12)})")
    testModeRefusal(run)?.let { reason ->
        config.out.println("refusing to run: $reason")
        return Report(run, emptyList()) to 2
    }
    val results = Runner(run, checks).execute()
    val report = Report(run, results)
    config.reportJson?.let { report.writeJson(it) }
    config.reportJunit?.let { report.writeJunit(it) }
    config.out.println(report.summaryLine())
    val code = if (report.failed > 0 || (config.requireComplete && !report.complete)) 1 else 0
    return report to code
}

/**
 * The suite writes to the server and drives its test hooks, so it runs only where the server itself
 * says it was started in test mode (README "Testing profile"); returns why not, or null.
 */
fun testModeRefusal(run: Run): String? {
    val ex = try {
        run.http.send("getDiscovery", "GET", run.origin + "/.well-known/dulcet", emptyMap(), null)
    } catch (e: CheckFailure) {
        return "could not read /.well-known/dulcet: ${e.message}"
    }
    if (ex.status != 200) return "/.well-known/dulcet answered ${ex.status}"
    val testMode = ex.json?.path("testMode")
    if (testMode == null || !testMode.isBoolean || !testMode.asBoolean()) {
        return "/.well-known/dulcet does not say testMode: true. Start a disposable server in test mode; the suite never runs against a server that is not."
    }
    return null
}

fun main(args: Array<String>) {
    val config = try {
        parseArgs(args, System.getenv(), System.out) ?: return
    } catch (e: UsageError) {
        System.err.println("error: ${e.message}\n")
        System.err.println(USAGE)
        exitProcess(2)
    }
    val (_, code) = runSuite(config)
    exitProcess(code)
}
