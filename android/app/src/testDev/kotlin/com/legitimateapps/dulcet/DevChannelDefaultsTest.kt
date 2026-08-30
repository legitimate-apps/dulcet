package com.legitimateapps.dulcet

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The positive control for [ProdChannelDefaultsTest].
 *
 * DEV is expected to carry a preconfigured server URL for convenience. Asserting that here is what
 * makes PROD's null assertion meaningful: if a refactor collapsed both flavours onto one
 * implementation returning null, PROD's test would still pass while proving nothing, and this test
 * is what would fail instead.
 */
class DevChannelDefaultsTest {
    @Test
    fun devCarriesAPreconfiguredServerUrlSoTheProdAssertionIsNotVacuous() {
        assertNotNull(
            ActiveChannelDefaults.preconfiguredServerUrl,
            "DEV lost its preconfigured server URL. That is not merely a DEV inconvenience: it makes " +
                "the PROD null assertion vacuous, because both flavours would then be null.",
        )
    }
}
