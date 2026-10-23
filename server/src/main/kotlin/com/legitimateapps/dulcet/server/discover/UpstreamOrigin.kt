package com.legitimateapps.dulcet.server.discover

import java.net.URI
import java.util.Locale

/** Canonical origin identity for queues and socket connection defaults. Paths are excluded. */
internal data class UpstreamOrigin(val scheme:String,val host:String,val port:Int) {
    companion object {
        fun of(uri:URI):UpstreamOrigin {
            val scheme=uri.scheme.lowercase(Locale.ROOT)
            return UpstreamOrigin(scheme,uri.host.lowercase(Locale.ROOT),if(uri.port>=0)uri.port else if(scheme=="https")443 else 80)
        }
    }
}
