package com.legitimateapps.dulcet.core

/** Pure account-scoped gate; product identity cannot grant an extension. */
internal object DiscoverCapabilityResolver {
    fun resolve(extensions: Map<String, Set<Int>>?, catalogAllowed: Boolean): DiscoverFeatureAccess =
        resolve(extensions, catalogAllowed, null)

    /** A failed redetection retains metadata, but does not authorize new requests. */
    fun resolve(extensions: Map<String, Set<Int>>?, catalogAllowed: Boolean, previous: DiscoverFeatureAccess?): DiscoverFeatureAccess {
        val known = extensions != null
        val discover = extensions?.get("dulcetDiscover")?.contains(1) ?: previous?.discoverAvailable ?: false
        val wishlist = extensions?.get("dulcetWishlist")?.contains(1) ?: previous?.wishlistAvailable ?: false
        val allowed = known && catalogAllowed
        return DiscoverFeatureAccess(if (known) "known" else "unresolved",
            if (known) null else DiscoverFailure("invalid-response", null, true), discover, wishlist,
            allowed && discover, allowed && wishlist, allowed && discover && wishlist,
            allowed && wishlist, allowed && (discover || wishlist))
    }
}
