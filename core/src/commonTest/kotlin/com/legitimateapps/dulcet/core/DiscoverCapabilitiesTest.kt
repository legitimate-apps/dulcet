package com.legitimateapps.dulcet.core

import kotlin.test.*

class DiscoverCapabilitiesTest {
    private fun access(vararg names: String) = DiscoverCapabilityResolver.resolve(names.associateWith { setOf(1) }, true)
    @Test fun discoverAndWishlistAreIndependent() {
        val wishes = access("dulcetWishlist")
        assertTrue(wishes.canReadWishlist && wishes.canRemoveWish)
        assertFalse(wishes.canSearch || wishes.canAddWish)
        val discover = access("dulcetDiscover")
        assertTrue(discover.canSearch)
        assertFalse(discover.canReadWishlist || discover.canAddWish)
        assertTrue(access("dulcetDiscover", "dulcetWishlist").canAddWish)
    }
    @Test fun detectsExtensionsOnAnyServer() {
        // Product identity is deliberately not an input to this gate.
        assertTrue(access("dulcetDiscover").canSearch)
        assertFalse(DiscoverCapabilityResolver.resolve(mapOf("dulcetDiscover" to setOf(2)), true).canSearch)
    }
    @Test fun neitherExtensionMeansNoFeatureTraffic() {
        val paths = mutableListOf("ping", "getAlbumList2")
        val baseline = paths.toList()
        val gate = access()
        if (gate.canSearch) paths += "searchDulcetDiscover"
        if (gate.canReadWishlist) paths += "getDulcetWishlist"
        assertEquals(baseline, paths)
        assertEquals("known", gate.detection)
    }
    @Test fun rejectionWorksWithEitherExtension() {
        assertTrue(access("dulcetDiscover").canRejectMatch)
        assertTrue(access("dulcetWishlist").canRejectMatch)
        assertFalse(access().canRejectMatch)
    }
    @Test fun failedDetectionIsNotAnEmptyExtensionSet() {
        val unresolved = DiscoverCapabilityResolver.resolve(null, true)
        assertEquals("unresolved", unresolved.detection)
        assertNotNull(unresolved.error)
        assertFalse(unresolved.canSearch)
        val retained = DiscoverCapabilityResolver.resolve(null, true, access("dulcetDiscover"))
        assertTrue(retained.discoverAvailable)
        assertEquals("unresolved", retained.detection)
        assertFalse(retained.canSearch)
    }
    @Test fun accountPolicyBlocksEveryOperation() {
        val gate = DiscoverCapabilityResolver.resolve(mapOf("dulcetDiscover" to setOf(1), "dulcetWishlist" to setOf(1)), false)
        assertTrue(gate.discoverAvailable && gate.wishlistAvailable)
        assertFalse(gate.canSearch || gate.canReadWishlist || gate.canAddWish || gate.canRemoveWish || gate.canRejectMatch)
    }
}
