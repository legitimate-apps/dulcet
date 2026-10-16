package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.legitimateapps.dulcet.apiconformance.Profile.Webhooks
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/** A webhook the suite created, pointing at its own receiver path. */
class Hook(val id: String, val secret: String, val path: String, val url: String, val body: JsonNode)

/**
 * Creates a webhook as [token] that delivers to a fresh receiver path, with the Testing override
 * `webhooks.allowPrivateTargets` set so the server may deliver to the suite's own receiver.
 */
fun Ctx.createHook(events: List<String>, token: String = tokenA, label: String = "hook", path: String = run.webhookListener.newPath(label)): Hook {
    ensureWebhookTargets()
    val url = run.webhookListener.url(path)
    val ex = api("POST", "createWebhook", "/webhooks", token = token, body = mapOf("url" to url, "events" to events))
    if (ex.status == 400 || ex.status == 422) {
        fail("the server refused the suite's webhook receiver $url with webhooks.allowPrivateTargets set (${ex.describe()}${ex.json?.str("type")?.let { ", $it" } ?: ""}); " +
            "if the server cannot reach it, pass --webhook-url with a base that reaches --webhook-listen")
    }
    expectStatus(ex, 201, what = "creating a webhook")
    val b = body(ex)
    val secret = b.str("secret") ?: fail("the created webhook carries no secret")
    run.secrets.add(secret, SecretKind.TOKEN)
    val hook = Hook(b.str("id")!!, secret, path, url, b)
    val owner = tokenA
    run.cleanups += "delete webhook ${hook.id}" to { run.http.send("deleteWebhook", "DELETE", url("/webhooks/${id(hook.id)}"), mapOf("Authorization" to "Bearer $owner")); Unit }
    return hook
}

/** Fails unless [ex] refused a webhook target as the contract says: `422 webhook-target-refused`. */
private fun Ctx.expectTargetRefused(ex: Exchange, target: String, setting: String) {
    if (ex.status == 201) ex.json?.str("id")?.let { api("DELETE", "deleteWebhook", "/webhooks/${id(it)}") }
    expectProblem(ex, 422, "webhook-target-refused", what = "a webhook to $target $setting")
}

/** Fails unless [d] carries Standard Webhooks headers that verify with [hook]'s secret, a current timestamp and a valid body. */
fun Ctx.verifyDelivery(hook: Hook, d: Delivery, what: String) {
    val msgId = d.header("webhook-id") ?: fail("$what: no webhook-id header")
    val ts = d.header("webhook-timestamp") ?: fail("$what: no webhook-timestamp header")
    val sig = d.header("webhook-signature") ?: fail("$what: no webhook-signature header")
    val seconds = ts.toLongOrNull() ?: fail("$what: webhook-timestamp $ts is not integer seconds")
    ensure(abs(Instant.now().epochSecond - seconds) <= 300) { "$what: webhook-timestamp is ${Instant.now().epochSecond - seconds} s off" }
    ensure(WebhookSignature.verify(hook.secret, msgId, ts, d.body, sig)) { "$what: webhook-signature does not verify with the secret shown at creation" }
    val b = d.json ?: fail("$what: the body is not JSON")
    run.contract.validateComponent("WebhookEvent", b).forEach { violations += "$what: $it" }
    ensure(b.str("id") == msgId) { "$what: body id ${b.str("id")} differs from webhook-id $msgId" }
    ensure(d.rawQuery == null && run.webhookListener.url(d.path) == hook.url) { "$what: delivered to ${d.path}${d.rawQuery?.let { "?…" } ?: ""}, not exactly the registered URL" }
    val leaked = (run.secrets.all().keys + hook.secret).filter { s -> (d.path + (d.rawQuery ?: "")).contains(s) || d.headers.any { (k, v) -> k != "webhook-signature" && v.contains(s) } }
    ensure(leaked.isEmpty()) { "$what: a secret travels in the delivery URL or headers" }
}

private fun Ctx.recordPlay(): String {
    val playId = UUID.randomUUID().toString()
    expectStatus(api("POST", "submitPlays", "/me/plays", body = mapOf("plays" to listOf(playBody(playId, fixture.track(0))))), 200, what = "recording a play")
    run.cleanups += "delete play $playId" to { api("DELETE", "deletePlay", "/me/plays/${id(playId)}"); Unit }
    return playId
}

private fun Delivery.isPlay(playId: String) = json?.str("type") == "play.recorded" && json?.path("data")?.str("id") == playId

// ---- Webhooks profile (README §12 Webhooks, CONFORMANCE.md "Other profiles") ----------------------------

fun webhookChecks(): List<Check> = listOf(
    Check("DA-WH-1", Webhooks, "a webhook's whsec_ secret is shown once and never read back", "README §12 Webhooks; openapi createWebhook") {
        requires("DA-PRE-1")
        val hook = createHook(listOf("play.recorded"), label = "secret")
        ensure(hook.secret.startsWith("whsec_")) { "the secret lacks the whsec_ prefix" }
        val read = api("GET", "getWebhook", "/webhooks/${id(hook.id)}")
        expectStatus(read, 200, what = "reading the webhook")
        val list = api("GET", "listWebhooks", "/webhooks")
        expectStatus(list, 200, what = "listing webhooks")
        ensure(body(list).path("items").any { it.str("id") == hook.id }) { "the new webhook is not listed" }
        for (ex in listOf(read, list)) {
            ensure(!ex.text().contains(hook.secret) && !body(ex).toString().contains("\"secret\"")) { "${ex.describe()} returns the secret again" }
        }
    },
    Check("DA-WH-2", Webhooks, "plain HTTP, loopback and private targets answer 422 webhook-target-refused unless allowPrivateTargets is set; link-local and metadata always do", "openapi createWebhook (Targets), TestSettings.webhooks") {
        requires("DA-PRE-1")
        fun create(target: String) = api("POST", "createWebhook", "/webhooks", body = mapOf("url" to target, "events" to listOf("play.recorded")))
        val always = listOf("https://169.254.169.254/latest/meta-data/", "https://[fe80::1]/dulcet")
        val unlessAllowed = listOf("http://hooks.example.net/dulcet", "https://127.0.0.1/dulcet", "https://10.20.30.40/dulcet", "https://[fd00::1]/dulcet")
        withTestSettings({ s -> (s.get("webhooks") as? com.fasterxml.jackson.databind.node.ObjectNode)?.remove("allowPrivateTargets") }) {
            for (target in always + unlessAllowed) expectTargetRefused(create(target), target, "without allowPrivateTargets")
        }
        ensureWebhookTargets()
        for (target in always) expectTargetRefused(create(target), target, "with allowPrivateTargets")
    },
    Check("DA-WH-3", Webhooks, "a test ping is delivered signed (Standard Webhooks), to exactly the registered URL, with a body matching the contract", "README §12; openapi testWebhook, webhooks.delivery") {
        requires("DA-PRE-1")
        val hook = createHook(listOf("play.recorded"), label = "ping")
        val ex = api("POST", "testWebhook", "/webhooks/${id(hook.id)}/test")
        expectStatus(ex, 200, what = "sending a test ping")
        ensure(body(ex).path("responseStatus").asInt() == 204) { "the ping's delivery record says responseStatus ${body(ex).path("responseStatus")}, the receiver answered 204" }
        val d = run.webhookListener.await(hook.path, eventTimeoutMs) ?: fail("the receiver got no ping within ${config.eventTimeoutSeconds} s")
        verifyDelivery(hook, d, "the ping")
        ensure(d.json?.str("type") == "ping") { "the test delivery's type is ${d.json?.str("type")}, not ping" }
        ensure(body(ex).str("id") == d.header("webhook-id")) { "the delivery record's id ${body(ex).str("id")} differs from the webhook-id sent" }
    },
    Check("DA-WH-4", Webhooks, "a recorded play is delivered as play.recorded carrying the Play", "README §12; openapi WebhookEvent") {
        requireFixture()
        val hook = createHook(listOf("play.recorded"), label = "play")
        val playId = recordPlay()
        val d = run.webhookListener.await(hook.path, eventTimeoutMs) { it.isPlay(playId) }
            ?: fail("no play.recorded delivery for play $playId within ${config.eventTimeoutSeconds} s")
        verifyDelivery(hook, d, "the play.recorded delivery")
        run.contract.validateComponent("Play", d.json!!.path("data")).forEach { violations += "play.recorded data: $it" }
    },
    Check("DA-WH-5", Webhooks, "a failed delivery is retried on webhooks.retryScheduleSeconds, with the same webhook-id and a fresh signature, until it succeeds", "README §12; openapi webhooks.delivery, TestSettings.webhooks") {
        requireFixture()
        val schedule = listOf(1, 2)
        withTestSettings({ s -> webhookSettings(s).put("allowPrivateTargets", true).put("disableAfterFailures", 10).putArray("retryScheduleSeconds").apply { schedule.forEach { add(it) } } }) {
            val path = run.webhookListener.newPath("retry")
            run.webhookListener.script(path, 500, 500)
            val hook = createHook(listOf("play.recorded"), path = path)
            val playId = recordPlay()
            val attempts = mutableListOf(run.webhookListener.await(path, eventTimeoutMs) { it.isPlay(playId) } ?: fail("no first delivery within ${config.eventTimeoutSeconds} s"))
            ensure(attempts[0].status == 500) { "suite bug: the scripted 500 was not served" }
            for ((i, delay) in schedule.withIndex()) {
                val next = run.webhookListener.await(path, delay * 1000L + eventTimeoutMs) { d -> d.isPlay(playId) && attempts.none { it === d } }
                    ?: fail("retry ${i + 1} did not arrive within ${delay} s (retryScheduleSeconds) plus ${config.eventTimeoutSeconds} s")
                val gapMs = java.time.Duration.between(attempts.last().receivedAt, next.receivedAt).toMillis()
                ensure(gapMs >= delay * 1000L - 250) { "retry ${i + 1} came ${gapMs} ms after the previous attempt; retryScheduleSeconds says $delay s" }
                ensure(next.header("webhook-id") == attempts[0].header("webhook-id")) { "retry ${i + 1} carries webhook-id ${next.header("webhook-id")}, the first ${attempts[0].header("webhook-id")}" }
                verifyDelivery(hook, next, "retry ${i + 1}")
                attempts += next
            }
            ensure(attempts.last().status == 204) { "suite bug: the last scripted attempt was not answered 204" }
            Thread.sleep(quietMs + 1000L)
            ensure(run.webhookListener.at(path).count { it.isPlay(playId) } == attempts.size) { "the server kept delivering after the receiver answered 204" }
            val read = body(api("GET", "getWebhook", "/webhooks/${id(hook.id)}"))
            ensure(read.path("active").asBoolean(false) && read.path("consecutiveFailures").asInt(-1) == 0) { "after a delivered retry the webhook reads active=${read.path("active")} consecutiveFailures=${read.path("consecutiveFailures")}" }
        }
    },
    Check("DA-WH-6", Webhooks, "a target's redirect is not followed", "README §12; openapi createWebhook (Targets)") {
        requires("DA-PRE-1")
        val listener = run.webhookListener
        val path = listener.newPath("redirects")
        val target = listener.newPath("redirect-target")
        listener.redirect(path, target)
        val hook = createHook(listOf("play.recorded"), path = path)
        val ex = api("POST", "testWebhook", "/webhooks/${id(hook.id)}/test")
        expectStatus(ex, 200, what = "pinging a target that redirects")
        listener.await(path, eventTimeoutMs) ?: fail("the redirecting target got no ping")
        Thread.sleep(quietMs)
        ensure(listener.at(target).isEmpty()) { "the server followed the target's redirect" }
        ensure(body(ex).path("responseStatus").asInt() != 204) { "the delivery record claims 204 for a target that answered 307" }
    },
    Check("DA-WH-7", Webhooks, "a webhook for an event type outside the token's scopes answers 403 insufficient-scope naming those events", "README §12; openapi createWebhook, Problem.events") {
        requires("DA-PRE-1")
        ensureWebhookTargets()
        val accountOnly = mintToken(listOf("account"))
        val path = run.webhookListener.newPath("narrow")
        val ex = api("POST", "createWebhook", "/webhooks", token = accountOnly.secret, body = mapOf("url" to run.webhookListener.url(path), "events" to listOf("play.recorded")))
        if (ex.status == 201) body(ex).str("id")?.let { hid -> run.cleanups += "delete webhook $hid" to { api("DELETE", "deleteWebhook", "/webhooks/${id(hid)}"); Unit } }
        expectProblem(ex, 403, "insufficient-scope", what = "a play.recorded webhook from a token without user:read")
        val named = body(ex).path("events").map { it.asText() }
        ensure(named == listOf("play.recorded")) { "the problem names events $named, not [play.recorded]" }
        ensure(body(ex).path("requiredScopes").any { it.asText() == "user:read" }) { "the problem's requiredScopes ${body(ex).path("requiredScopes")} omit user:read" }
    },
    Check("DA-WH-8", Webhooks, "revoking the token that created a webhook deletes it, and it delivers nothing more", "README §12; openapi createWebhook, revokeTokenById") {
        requireFixture()
        val parent = mintToken(listOf("account", "user:read"))
        val hook = createHook(listOf("play.recorded"), token = parent.secret, label = "orphan")
        expectStatus(api("DELETE", "revokeTokenById", "/me/tokens/${id(parent.id)}"), 204, what = "revoking the webhook's token")
        expectDeletedAndSilent(hook, "its token was revoked")
    },
    Check("DA-WH-9", Webhooks, "after webhooks.disableAfterFailures failed attempts the webhook is disabled with delivery-failures and gets nothing more", "README §12; openapi Webhook.disabledReason, TestSettings.webhooks") {
        requireFixture()
        withTestSettings({ s -> webhookSettings(s).put("allowPrivateTargets", true).put("disableAfterFailures", 2).putArray("retryScheduleSeconds").add(1).add(1).add(1) }) {
            val path = run.webhookListener.newPath("failing")
            run.webhookListener.script(path, *IntArray(10) { 500 })
            val hook = createHook(listOf("play.recorded"), path = path)
            val playId = recordPlay()
            run.webhookListener.await(path, 1000L + eventTimeoutMs) { d -> d.isPlay(playId) && run.webhookListener.at(path).count { it.isPlay(playId) } >= 2 }
                ?: fail("two failed attempts did not arrive within ${1 + config.eventTimeoutSeconds} s")
            Thread.sleep(2000L + quietMs)
            val attempts = run.webhookListener.at(path).count { it.isPlay(playId) }
            ensure(attempts == 2) { "the webhook got $attempts attempts; disableAfterFailures is 2" }
            val read = body(api("GET", "getWebhook", "/webhooks/${id(hook.id)}"))
            ensure(!read.path("active").asBoolean(true)) { "after 2 failed attempts the webhook is still active" }
            ensure(read.str("disabledReason") == "delivery-failures") { "disabledReason is ${read.path("disabledReason")}, not delivery-failures" }
            ensure(read.path("consecutiveFailures").asInt() >= 2) { "consecutiveFailures is ${read.path("consecutiveFailures")} after 2 failures" }
            val later = recordPlay()
            Thread.sleep(quietMs)
            ensure(run.webhookListener.at(path).none { it.isPlay(later) }) { "a disabled webhook was sent a new delivery" }
        }
    },
    Check("DA-WH-10", Webhooks, "a webhook made under a verified client's OAuth grant is deleted when the grant is revoked", "README §3 Registered, §12; openapi createWebhook, TestSettings.trustedClients") {
        requireFixture()
        val client = TrustedClient.SUITE
        ensureTrustedClient(client)
        val tokens = run.oauth.signIn(this, "account user:read offline_access", "a verified client's grant with account", client = client)
        val granted = tokens.str("scope")?.split(' ').orEmpty()
        ensure("account" in granted) { "a client listed in trustedClients was granted $granted, without account (a verified client may hold it)" }
        val access = tokens.str("access_token") ?: fail("the token response has no access_token")
        val refresh = tokens.str("refresh_token")
        val hook = createHook(listOf("play.recorded"), token = access, label = "grant")
        val revoked = run.oauth.revoke(this, refresh ?: access, if (refresh != null) "refresh_token" else "access_token", clientId = client.clientId)
        expectStatus(revoked, 200, what = "revoking the verified client's grant")
        expectDeletedAndSilent(hook, "its OAuth grant was revoked")
    },
)

/** Fails unless [hook] now reads 404 (deleted, not disabled) and a new play reaches a live barrier webhook but not [hook]. */
private fun Ctx.expectDeletedAndSilent(hook: Hook, why: String) {
    val barrier = createHook(listOf("play.recorded"), label = "barrier")
    val read = api("GET", "getWebhook", "/webhooks/${id(hook.id)}")
    ensure(read.status == 404) { "after $why the webhook reads ${read.describe()} active=${read.json?.path("active")} disabledReason=${read.json?.path("disabledReason")}; it must be deleted" }
    ensure(body(api("GET", "listWebhooks", "/webhooks")).path("items").none { it.str("id") == hook.id }) { "after $why the webhook is still listed" }
    val playId = recordPlay()
    run.webhookListener.await(barrier.path, eventTimeoutMs) { it.isPlay(playId) } ?: fail("the live webhook got no play.recorded")
    Thread.sleep(quietMs)
    ensure(run.webhookListener.at(hook.path).isEmpty()) { "a webhook whose grant ended still delivered" }
}
