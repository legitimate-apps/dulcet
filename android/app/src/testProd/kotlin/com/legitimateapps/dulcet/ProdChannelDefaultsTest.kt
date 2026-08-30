package com.legitimateapps.dulcet

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * PROD must never ship a preconfigured server URL.
 *
 * A hardcoded address in a published binary leaks a private address to everyone who downloads the
 * app, and is broken for every user who is not the person who hardcoded it. The design makes that
 * structural rather than remembered: `ChannelDefaults` is an interface in `main`, and each flavour
 * source set supplies its own `ActiveChannelDefaults`. PROD's returns null because the source set
 * that could carry a URL is not part of that variant.
 *
 * This test guards the structure, not a value. Its counterpart in `testDev` asserts the DEV variant
 * DOES carry one, so a change that collapsed the two source sets into a single null-returning
 * implementation would fail there instead of passing silently here. A negative assertion whose
 * instrument cannot produce a positive proves nothing.
 */
class ProdChannelDefaultsTest {
    @Test
    fun prodShipsNoPreconfiguredServerUrl() {
        assertNull(
            ActiveChannelDefaults.preconfiguredServerUrl,
            "PROD compiled a preconfigured server URL. This is a disclosure defect, not a config " +
                "preference: check that the prod source set still supplies its own ActiveChannelDefaults.",
        )
    }
}
