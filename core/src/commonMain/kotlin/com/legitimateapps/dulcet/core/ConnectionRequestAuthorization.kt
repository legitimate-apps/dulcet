package com.legitimateapps.dulcet.core

/** Recheck a prepared native request after asynchronous callbacks and before engine dispatch.
 * Ownership equality alone cannot authorize a transport route that has since been removed.
 */
public object ConnectionRequestAuthorization {
    public fun isCurrent(anchorBaseUrl: String, username: String, routeBaseUrl: String, generation: Long?): Boolean {
        val enrolled = ConnectionRouteRegistry.isEnrolled(anchorBaseUrl, username)
        return if (generation == null) {
            !enrolled &&
                ConnectionResourcePolicy.resolve(anchorBaseUrl, routeBaseUrl) != null
        } else {
            ConnectionRouteRegistry.authorizes(anchorBaseUrl, username, routeBaseUrl, generation)
        }
    }
}
