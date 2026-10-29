package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionResourcePolicyTest {
    @Test fun relativeGrantsKeepTheRouteBaseAsADirectory() {
        assertEquals("https://home.example.test/music/dulcet/v1/media/opaque?grant=secret",
            ConnectionResourcePolicy.resolve(BASE, "dulcet/v1/media/opaque?grant=secret"))
        assertEquals("https://home.example.test/music/rest/stream.view?opaque=%2F",
            ConnectionResourcePolicy.resolve(BASE, "/music/rest/stream.view?opaque=%2F"))
        assertEquals("https://home.example.test/music/artwork/x",
            ConnectionResourcePolicy.resolve(BASE, "https://HOME.example.test:443/music/artwork/x"))
    }

    @Test fun everyForeignOriginDowngradeAndBaseEscapeFailsBeforeDispatch() {
        listOf(
            "https://remote.example.test/music/dulcet/v1/media/x", // another enrolled alias still fails
            "http://home.example.test/music/dulcet/v1/media/x",
            "https://home.example.test:444/music/x",
            "//home.example.test/music/x", "//remote.example.test/x",
            "https://user:secret@home.example.test/music/x",
            "/music2/x", "/dulcet/v1/media/x", "../outside", "x/../../outside",
            "%2e%2e/outside", "/music/%2E%2e/outside", "x%2f..%2foutside", "x%5c..%5coutside",
            "%252e%252e/outside", "x\\..\\outside", "x#fragment", " file:///music/x", "file:///music/x",
        ).forEach { resource -> assertNull(ConnectionResourcePolicy.resolve(BASE, resource), resource) }
    }

    @Test fun legacyHttpResourcesRemainBoundToTheirExactOriginalRoute() {
        assertEquals("http://127.0.0.1:4533/base/rest/stream.view",
            ConnectionResourcePolicy.resolve("http://127.0.0.1:4533/base", "rest/stream.view"))
        assertNull(ConnectionResourcePolicy.resolve("http://127.0.0.1:4533/base", "http://127.0.0.1:4534/base/x"))
        assertEquals("{\"kind\":\"Security.ResourceOutsideRoute\"}",
            DomainError.Security.ResourceOutsideRoute.toDiagnosticJson())
        assertEquals(PlaybackFailureOwner.Connection, playbackFailureOwner(DomainError.Security.ResourceOutsideRoute))
    }

    companion object { const val BASE = "https://home.example.test/music" }
}
