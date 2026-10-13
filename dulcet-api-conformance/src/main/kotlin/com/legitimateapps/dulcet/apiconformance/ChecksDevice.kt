package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.legitimateapps.dulcet.apiconformance.Profile.Device
import java.net.URI

private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
private const val DEVICE_SCOPE = "catalog:read user:read offline_access"

/** Starts a device authorization (RFC 8628) and returns its response body, schema-checked by the run. */
private fun Ctx.startDeviceAuthorization(): JsonNode {
    val metadata = run.oauth.metadata(this)
    val endpoint = metadata.str("device_authorization_endpoint")
        ?: fail("authorization server metadata has no device_authorization_endpoint")
    val ex = send("POST", "requestDeviceAuthorization", endpoint, token = null,
        body = Http.form(listOf("client_id" to config.clientId, "scope" to DEVICE_SCOPE)),
        contentType = "application/x-www-form-urlencoded")
    expectStatus(ex, 200, what = "starting a device authorization")
    val b = body(ex)
    run.secrets.add(b.str("device_code"), SecretKind.TOKEN)
    return b
}

private fun Ctx.pollDevice(deviceCode: String): Exchange =
    run.oauth.token(this, listOf("grant_type" to DEVICE_GRANT, "client_id" to config.clientId, "device_code" to deviceCode))

fun deviceChecks(): List<Check> = listOf(
    Check("DA-DEV-1", Device, "the device grant is advertised, starts on the server's origin and stays pending until approved", "README §3 Flows; openapi requestDeviceAuthorization") {
        requires("DA-DISC-4")
        // autoApprove would approve the code at once, so the pending state is checked with it lifted.
        if (run.testSettings.has("autoApprove")) withTestSettings({ it.remove("autoApprove") }) { checkPendingDevice() } else checkPendingDevice()
    },
    Check("DA-DEV-2", Device, "an approved device code yields tokens once, polled no faster than the interval", "README §3 Flows; RFC 8628 §3.4-3.5") {
        requires("DA-DISC-4")
        when (config.oauthMode) {
            OAuthMode.OFF -> skip("OAuth flow checks are off (--oauth=off)")
            OAuthMode.AUTO -> ensureAutoApprove()
            OAuthMode.INTERACTIVE -> {}
        }
        val start = startDeviceAuthorization()
        if (config.oauthMode == OAuthMode.INTERACTIVE) {
            val link = start.str("verification_uri_complete") ?: start.str("verification_uri")
            config.out.println("\n  >> ${check.id}: open $link, enter ${start.str("user_code")} if asked, and approve.\n")
        }
        var interval = if (start.has("interval")) start.path("interval").asLong() else 5L
        val deadline = System.nanoTime() + minOf(config.oauthTimeoutSeconds.toLong(), start.path("expires_in").asLong()) * 1_000_000_000
        val deviceCode = start.str("device_code")!!
        val issued: JsonNode
        while (true) {
            if (System.nanoTime() > deadline) fail("the device code was not approved within the timeout")
            Thread.sleep(interval * 1000)
            val ex = pollDevice(deviceCode)
            if (ex.status == 200) { issued = body(ex); break }
            when (val error = body(ex).str("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                else -> fail("polling the device code answered error=$error")
            }
        }
        val access = issued.str("access_token")!!
        ensure(access.startsWith("dulcet_at_")) { "the device grant's access token lacks the dulcet_at_ prefix" }
        run.cleanups += "revoke the device grant" to { run.oauth.revoke(this, issued.str("refresh_token") ?: access, if (issued.has("refresh_token")) "refresh_token" else "access_token") }
        ensure(me(access).path("token").str("kind") == "oauth") { "the device grant's token does not report kind oauth" }
        val again = pollDevice(deviceCode)
        ensure(again.status == 400 && !body(again).has("access_token")) { "a device code issued tokens a second time" }
    },
)

private fun Ctx.checkPendingDevice() {
        val metadata = run.oauth.metadata(this)
        val grants = metadata.path("grant_types_supported")
        if (grants.isArray) ensure(grants.any { it.asText() == DEVICE_GRANT }) { "grant_types_supported does not list $DEVICE_GRANT" }
        val start = startDeviceAuthorization()
        val verification = URI(start.str("verification_uri")!!)
        val origin = URI(run.origin)
        ensure(verification.scheme == origin.scheme && verification.rawAuthority == origin.rawAuthority) {
            "verification_uri is not on the server's own origin"
        }
        ensure(start.path("expires_in").asInt() > 0) { "expires_in is not positive" }
        if (start.has("interval")) ensure(start.path("interval").asInt() >= 1) { "interval is below one second" }
        val pending = pollDevice(start.str("device_code")!!)
        ensure(pending.status == 400) { "polling an unapproved device code answered ${pending.status}, not 400" }
        val error = body(pending).str("error")
        ensure(error == "authorization_pending" || error == "slow_down") { "polling an unapproved device code answered error=$error, not authorization_pending" }
        ensure(!body(pending).has("access_token")) { "an unapproved device code was issued an access token" }
        val unknown = pollDevice("dulcet-conformance-unknown-device-code-" + run.runId)
        ensure(unknown.status == 400 && body(unknown).str("error") == "invalid_grant") {
            "an unknown device code answered ${unknown.status} error=${unknown.json?.str("error")}, not 400 invalid_grant"
        }
}
