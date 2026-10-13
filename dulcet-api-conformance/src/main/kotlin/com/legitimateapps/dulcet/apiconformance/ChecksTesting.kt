package com.legitimateapps.dulcet.apiconformance

import com.legitimateapps.dulcet.apiconformance.Profile.Testing

/** The hook operations, each with a path the suite can call without knowing a real checkpoint id. */
private fun Ctx.hookCalls(base: String, checkpointId: String): List<Triple<String, String, String>> = listOf(
    Triple("GET", "getTestSettings", "$base/admin/test/settings"),
    Triple("PUT", "replaceTestSettings", "$base/admin/test/settings"),
    Triple("POST", "createTestCheckpoint", "$base/admin/test/checkpoints"),
    Triple("POST", "restoreTestCheckpoint", "$base/admin/test/checkpoints/${id(checkpointId)}/restore"),
)

/** Runs the operator's [command] with `sh -c`; returns its exit code, or null if it was still running at [timeoutSeconds] (then killed). */
private fun runCommand(command: String, timeoutSeconds: Int): Int? {
    val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    if (process.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)) return process.exitValue()
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
    return null
}

fun testingChecks(): List<Check> = listOf(
    Check("DA-TEST-1", Testing, "discovery says testMode; the hooks answer an admin and refuse anyone else", "README Testing profile") {
        val discovery = body(send("GET", "getDiscovery", run.origin + "/.well-known/dulcet"))
        ensure(discovery.path("testMode").asBoolean(false)) { "discovery does not say testMode: true" }
        val admin = adminToken
        val current = api("GET", "getTestSettings", "/admin/test/settings", token = admin)
        expectStatus(current, 200, what = "reading the test overrides as an admin")
        val sample = json.createObjectNode().put("syncRetentionSeconds", 3600)
        withTestSettings({ it.put("syncRetentionSeconds", 3600) }) {
            val read = body(api("GET", "getTestSettings", "/admin/test/settings", token = admin))
            ensure(read.path("syncRetentionSeconds").asInt() == 3600) { "a PUT override did not read back: $read" }
        }
        for ((method, op, url) in hookCalls(run.apiBase, "ckpt-never-issued-${run.runId}")) {
            val body = if (method == "PUT") json.writeValueAsBytes(sample) else null
            val asUser = send(method, op, url, token = tokenA, body = body)
            expectProblem(asUser, 403, what = "$op with a non-admin token")
            val anonymous = send(method, op, url, token = null, body = body)
            expectProblem(anonymous, 401, "unauthenticated", what = "$op with no token")
        }
        val after = body(api("GET", "getTestSettings", "/admin/test/settings", token = admin))
        ensure(after == run.testSettings) { "the refused writes changed the overrides: $after" }
    },
    Check("DA-TEST-2", Testing, "the same build started without test mode answers 404 on every hook", "README Testing profile") {
        val base = config.nonTestUrl?.trimEnd('/')
            ?: skip("set DULCET_NON_TEST_URL and DULCET_NON_TEST_ADMIN_TOKEN (the same build started without test mode) to check that its hooks are absent")
        val admin = config.nonTestAdminToken ?: skip("DULCET_NON_TEST_ADMIN_TOKEN is not set")
        run.secrets.add(admin, SecretKind.TOKEN)
        val origin = java.net.URI(base).let { "${it.scheme}://${it.rawAuthority}" }
        val discovery = body(send("GET", "getDiscovery", "$origin/.well-known/dulcet"))
        ensure(!discovery.path("testMode").asBoolean(false)) { "the instance at DULCET_NON_TEST_URL says testMode: true" }
        val apiBase = discovery.str("apiBase")?.let { java.net.URI(origin + "/").resolve(it).toString().trimEnd('/') } ?: "$origin/dulcet/v1"
        expectStatus(send("GET", "getMe", "$apiBase/me", token = admin), 200, what = "DULCET_NON_TEST_ADMIN_TOKEN on that instance")
        for ((method, op, url) in hookCalls(apiBase, "ckpt-never-issued-${run.runId}")) {
            val body = if (method == "PUT") "{\"syncRetentionSeconds\":1}".toByteArray() else null
            val ex = send(method, op, url, token = admin, body = body)
            ensure(ex.status == 404) { "$op on a server without test mode answered ${ex.status}, not 404" }
        }
    },
    Check("DA-TEST-3", Testing, "a server refuses to start in test mode on data first served without it", "README Testing profile") {
        val command = config.serverStartCommand
            ?: manual("starting a server is outside the API: pass --server-start-command (start this build in test mode on data a normal start served), or confirm by hand that it refuses")
        when (val exit = runCommand(command, config.serverCommandTimeoutSeconds)) {
            null -> fail("the server started in test mode on data first served without it (still running after ${config.serverCommandTimeoutSeconds} s; stopped)")
            0 -> fail("--server-start-command exited 0; a server that refuses must exit non-zero")
            else -> ensure(exit != 127) { "--server-start-command exited 127 (command not found), which proves nothing" }
        }
    },
    Check("DA-TEST-4", Testing, "autoApprove still enforces PKCE, exact redirects, iss and scope limits", "README Testing profile autoApprove") {
        requires("DA-DISC-4")
        if (config.oauthMode == OAuthMode.OFF) skip("OAuth flow checks are off (--oauth=off)")
        if (config.oauthMode == OAuthMode.INTERACTIVE) skip("--oauth=interactive signs in by hand, so autoApprove is not in play")
        ensureAutoApprove()
        val plain = run.oauth.authorize(this, "catalog:read", method = "plain", purpose = "plain PKCE under autoApprove")
        if (plain.code != null) {
            val ex = run.oauth.exchangeCode(this, plain)
            fail("autoApprove issued a code for plain PKCE" + if (ex.status == 200) " and the code was exchanged for tokens" else "")
        }
        val wrong = run.oauth.authorize(this, "catalog:read", purpose = "wrong verifier under autoApprove")
        ensure(wrong.code != null) { "autoApprove gave no code: ${wrong.error}" }
        ensure(wrong.iss != null && wrong.iss.trimEnd('/') == run.oauth.metadata(this).str("issuer")?.trimEnd('/')) { "the autoApprove redirect carries iss=${wrong.iss}, not the issuer" }
        val badVerifier = run.oauth.exchangeCode(this, wrong, verifier = wrong.verifier.reversed() + "x")
        ensure(badVerifier.status == 400 && body(badVerifier).str("error") == "invalid_grant") { "a wrong verifier under autoApprove answered ${badVerifier.status} ${badVerifier.json?.str("error")}" }
        val redirect = run.oauth.authorize(this, "catalog:read", purpose = "redirect mismatch under autoApprove")
        ensure(redirect.code != null) { "autoApprove gave no code: ${redirect.error}" }
        val badRedirect = run.oauth.exchangeCode(this, redirect, redirectUri = redirect.redirectUri.replace("/callback", "/other"))
        ensure(badRedirect.status == 400 && body(badRedirect).str("error") == "invalid_grant") { "a different redirect_uri under autoApprove answered ${badRedirect.status}" }
        val scoped = run.oauth.authorize(this, "catalog:read admin account", purpose = "admin and account under autoApprove")
        if (scoped.code != null) {
            val ex = run.oauth.exchangeCode(this, scoped)
            if (ex.status == 200) {
                val b = body(ex)
                run.cleanups += "revoke the scope-limit grant" to { run.oauth.revoke(this, b.str("access_token")!!, "access_token"); Unit }
                val granted = b.str("scope")?.split(' ').orEmpty()
                ensure("admin" !in granted && "account" !in granted) { "autoApprove gave an unverified client $granted" }
                val reportedScopes = me(b.str("access_token")!!).path("token").path("scopes").map { it.asText() }
                ensure("admin" !in reportedScopes && "account" !in reportedScopes) { "the autoApprove token holds $reportedScopes" }
            }
        }
    },
    Check("DA-TEST-5", Testing, "overrides end when the server restarts", "README Testing profile") {
        val command = config.serverRestartCommand
            ?: manual("restarting a server is outside the API: pass --server-restart-command, or set an override, restart and confirm GET /admin/test/settings answers {} by hand")
        val admin = adminToken
        setTestSettings { it.put("syncRetentionSeconds", 3600) }
        val exit = runCommand(command, config.serverCommandTimeoutSeconds) ?: fail("--server-restart-command did not return within ${config.serverCommandTimeoutSeconds} s")
        ensure(exit == 0) { "--server-restart-command exited $exit" }
        val deadline = System.nanoTime() + config.serverCommandTimeoutSeconds * 1_000_000_000L
        while (true) {
            val up = runCatching { send("GET", "getDiscovery", run.origin + "/.well-known/dulcet") }.getOrNull()
            if (up?.status == 200 && up.json?.path("testMode")?.asBoolean(false) == true) break
            if (System.nanoTime() > deadline) fail("the server did not answer discovery in test mode within ${config.serverCommandTimeoutSeconds} s of the restart")
            Thread.sleep(500)
        }
        val after = api("GET", "getTestSettings", "/admin/test/settings", token = admin)
        expectStatus(after, 200, what = "reading the overrides after the restart")
        run.testSettings = json.createObjectNode()
        ensure(body(after).isObject && body(after).size() == 0) { "the overrides outlived the restart: ${body(after)}" }
    },
    Check("DA-TEST-6", Testing, "rateLimit answers 429 rate-limited with Retry-After per token, and never limits the hooks", "README Testing profile rateLimit; §14") {
        val admin = adminToken
        withTestSettings({ it.putObject("rateLimit").put("requestsPerWindow", 3).put("windowSeconds", 60) }) {
            var limited: Exchange? = null
            for (i in 1..10) {
                val ex = api("GET", "getMe", "/me", token = admin)
                if (ex.status == 429) { limited = ex; break }
                expectStatus(ex, 200, what = "request $i under the limit")
            }
            val ex = limited ?: fail("10 requests in a 60-second window of 3 were never refused")
            expectProblem(ex, 429, "rate-limited", what = "the request past the limit")
            val retry = ex.header("Retry-After")?.trim()?.toIntOrNull()
            ensure(retry != null && retry in 1..60) { "Retry-After is ${ex.header("Retry-After")}, not a number of seconds within the window" }
            expectStatus(api("GET", "getMe", "/me", token = tokenB), 200, what = "another token while the admin token is limited")
            expectStatus(api("GET", "getTestSettings", "/admin/test/settings", token = admin), 200, what = "a hook while the token is limited")
        }
        expectStatus(api("GET", "getMe", "/me", token = admin), 200, what = "the admin token once rateLimit is cleared")
    },
)
