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
            "https://music.invalid/rest/ping.view" to
                "https://music.invalid./collect",
            "https://%69.invalid/rest/ping.view" to
                "https://i.invalid/collect",
            "https://[::1]/rest/ping.view" to
                "https://[0:0:0:0:0:0:0:1]/collect",
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

    private fun redirect(current: String, target: String): RedirectPolicyDecision =
        AccountConnectionContract.redirectDecision(
            currentUrl = current,
            targetUrl = target,
            redirectsAlreadyFollowed = 0,
        )
}
