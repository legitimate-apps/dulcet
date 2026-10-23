package com.legitimateapps.dulcet.apiconformance

import kotlin.test.*

class McpCapabilityTest {
    @Test fun optionalToolsRequireTheirAdvertisedVersionAndScope() {
        val scopes=listOf("catalog:read","user:read","user:write")
        val absent=McpTools.forScopes(scopes).map{it.path("name").asText()}
        assertFalse("search_discover" in absent);assertFalse("list_wishes" in absent);assertFalse("add_wish" in absent)
        val caps=json.readTree("""{"dulcet.discover":{"versions":[1]},"dulcet.wishlist":{"versions":[1]}}""")
        val present=McpTools.forScopes(scopes,caps).map{it.path("name").asText()}
        assertTrue(present.containsAll(listOf("search_discover","list_wishes","add_wish")))
        assertFalse("add_wish" in McpTools.forScopes(listOf("user:read"),caps).map{it.path("name").asText()})
        val future=json.readTree("""{"dulcet.discover":{"versions":[2]}}""")
        assertFalse("search_discover" in McpTools.forScopes(scopes,future).map{it.path("name").asText()})
    }
}
