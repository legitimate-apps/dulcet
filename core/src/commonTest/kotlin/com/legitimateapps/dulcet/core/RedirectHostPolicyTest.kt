package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RedirectHostPolicyTest {
    @Test
    fun unicodeCaseFoldingCannotPreserveCredentialsAcrossAuthorities() {
        listOf(
            "https://i.invalid/rest/ping.view" to "https://ı.invalid/collect",
            "https://I.invalid/rest/ping.view" to "https://İ.invalid/collect",
        ).forEach { (current, target) ->
            assertEquals(
                RedirectPolicyDecision.Reject(
                    RedirectRejectionReason.UnsupportedInternationalizedHost,
                ),
                redirect(current, target),
                "$current -> $target",
            )
        }
    }

    @Test
    fun equivalentAsciiAndIpv6HostSpellingsPreserveCredentials() {
        listOf(
            "https://MUSIC.INVALID/rest/ping.view" to
                "https://music.invalid/collect",
            "https://music.invalid/rest/ping.view" to
                "https://music.invalid./collect",
            "https://%69.invalid/rest/ping.view" to
                "https://i.invalid/collect",
            "https://[::1]/rest/ping.view" to
                "https://[0:0:0:0:0:0:0:1]/collect",
            "https://[ABCD::1]/rest/ping.view" to
                "https://[abcd::1]/collect",
        ).forEach { (current, target) ->
            assertEquals(
                RedirectPolicyDecision.PreserveCredentials,
                redirect(current, target),
                "$current -> $target",
            )
        }
    }

    @Test
    fun nonAsciiRedirectHostIsRejectedBeforeComparison() {
        assertEquals(
            RedirectPolicyDecision.Reject(
                RedirectRejectionReason.UnsupportedInternationalizedHost,
            ),
            redirect(
                "https://xn--bcher-kva.invalid/rest/ping.view",
                "https://bücher.invalid/collect",
            ),
        )
    }

    @Test
    fun ipv6ZonesEmbeddedIpv4AndInvalidHostsAreClassifiedThroughRedirectPolicy() {
        listOf(
            "https://[fe80::1%25eth0]/rest/ping.view" to
                "https://[fe80:0:0:0:0:0:0:1%25eth0]/collect",
            "https://[::ffff:127.0.0.1]/rest/ping.view" to
                "https://[::ffff:7f00:1]/collect",
        ).forEach { (current, target) ->
            assertEquals(
                RedirectPolicyDecision.PreserveCredentials,
                redirect(current, target),
                "$current -> $target",
            )
        }

        listOf(
            "https://[fe80::1%25]/collect",
            "https://[fe80::1%25eth%7C0]/collect",
            "https://[fe80::1%25eth0%2525nested]/collect",
            "https://[1::2::3]/collect",
            "https://[1:2:3:4:5:6:7:8:9]/collect",
            "https://[12345::1]/collect",
            "https://%zz.invalid/collect",
        ).forEach { target ->
            assertEquals(
                RedirectPolicyDecision.Reject(RedirectRejectionReason.InvalidLocation),
                redirect("https://music.invalid/rest/ping.view", target),
                target,
            )
        }
    }

    private fun redirect(current: String, target: String): RedirectPolicyDecision =
        AccountConnectionContract.redirectDecision(
            currentUrl = current,
            targetUrl = target,
            redirectsAlreadyFollowed = 0,
        )
}
