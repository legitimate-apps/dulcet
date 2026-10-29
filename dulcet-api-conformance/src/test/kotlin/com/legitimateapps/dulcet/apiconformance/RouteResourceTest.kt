package com.legitimateapps.dulcet.apiconformance

import kotlin.test.*

class RouteResourceTest {
    @Test fun `relative grants preserve route prefix and absolute same route remains valid`() {
        val base = "https://home.example.test/music"
        assertEquals("$base/dulcet/v1/media/opaque?grant=canary", resolveRouteResource(base, "dulcet/v1/media/opaque?grant=canary"))
        assertEquals("$base/dulcet/v1/media/opaque", resolveRouteResource(base, "$base/dulcet/v1/media/opaque"))
    }
    @Test fun `foreign alias downgrade authority tricks and outside base are refused before dispatch`() {
        val base = "https://home.example.test/music"
        for (resource in listOf("https://remote.example.test/music/dulcet/v1/media/opaque", "http://home.example.test/music/dulcet/v1/media/opaque",
            "//home.example.test/music/dulcet/v1/media/opaque", "https://u@home.example.test/music/media", "/music2/media", "/dulcet/v1/media/opaque",
            "../media", "%2e%2e/media", "dulcet/%252e%252e/media", "dulcet%2f..%2fmedia")) {
            val failure = assertFailsWith<CheckFailure> { resolveRouteResource(base, resource) }
            assertEquals("Media resource is outside the issuing route", failure.message)
        }
    }
}
