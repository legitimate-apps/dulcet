package com.legitimateapps.dulcet

import androidx.annotation.StringRes
import com.legitimateapps.dulcet.core.ConnectionCheckResult
import com.legitimateapps.dulcet.core.ConnectionRoute
import com.legitimateapps.dulcet.core.ConnectionSnapshot
import com.legitimateapps.dulcet.shared.R

/** How one route reads on the Connections page; phone and TV show the same states. */
public enum class ConnectionRouteStatus(@StringRes public val text: Int) {
    Current(R.string.connections_status_current),
    Verified(R.string.connections_status_verified),
    Unavailable(R.string.connections_status_unavailable),
    DifferentServer(R.string.connections_status_different_server),
}

/** A refusal or an outage outranks selection: a selected route that last failed is not shown as current. */
public fun ConnectionSnapshot.status(route: ConnectionRoute): ConnectionRouteStatus {
    val check = checks[route.id]
    return when {
        check?.result == ConnectionCheckResult.DifferentServer -> ConnectionRouteStatus.DifferentServer
        check?.result == ConnectionCheckResult.Unavailable -> ConnectionRouteStatus.Unavailable
        route.id == selectedRouteId -> ConnectionRouteStatus.Current
        route.verifiedAtEnrollment || check?.result == ConnectionCheckResult.Ready -> ConnectionRouteStatus.Verified
        else -> ConnectionRouteStatus.Unavailable
    }
}
