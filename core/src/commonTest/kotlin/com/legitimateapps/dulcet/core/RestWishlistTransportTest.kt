package com.legitimateapps.dulcet.core

import kotlin.test.*

internal fun wishJson(id: String = "wish:id") = """{"id":"$id","userId":"owner","kind":"song","externalIds":{"musicbrainzRecordingId":"$DISCOVER_MBID"},"snapshot":{"title":"Studio"},"source":{"type":"search"},"addedAt":"2026-10-22T12:00:00Z","updatedAt":"2026-10-22T12:00:00Z","state":"future-state","availability":{"status":"notHeld","completeness":"unknown","libraryItems":{"item":[]}},"revision":"opaque:revision"}"""
class RestWishlistTransportTest {
    @Test fun emptyInitialWishlistHasNullableChanged() = readerTest { env ->
        val wire = DiscoverWireFixture().apply { json = { _, _ -> discoverEnvelope("\"dulcetWishlist\":{\"wish\":[]}") } }
        val result = assertIs<DiscoverResult.Value<WishListSnapshot>>(RestWishlistTransport(env.reader(transport = wire)).snapshot(WishFilter())).value
        assertNull(result.stamp)
        assertTrue(result.items.isEmpty())
        assertTrue(wire.requests.size >= 3)
    }
    @Test fun restSnapshotChecksChangedAcrossAllPages() = readerTest { env ->
        val wire = DiscoverWireFixture().apply { json = { _, p ->
            val rows = if (p["size"] == "1" || p["cursor"] != null) wishJson("last") else (1..20).joinToString(",") { wishJson("w$it") }
            val cursor = if (p["size"] != "1" && p["cursor"] == null) ",\"nextCursor\":\"opaque:+/\"" else ""
            discoverEnvelope("\"dulcetWishlist\":{\"changed\":\"clock\",\"wish\":[$rows]$cursor}")
        } }
        val transport = RestWishlistTransport(env.reader(transport = wire))
        val result = assertIs<DiscoverResult.Value<WishListSnapshot>>(transport.snapshot(WishFilter(state="fulfilled"))).value
        assertEquals(21, result.items.size)
        assertEquals("future-state", result.items.first().state)
        assertTrue(wire.requests.all { it.second["state"] == "fulfilled" })
        var clock = 0
        wire.json = { _, _ -> discoverEnvelope("\"dulcetWishlist\":{\"changed\":\"${clock++}\",\"wish\":[]}") }
        val start = wire.requests.size
        assertEquals("conflict", assertIs<DiscoverResult.Failed>(transport.snapshot(WishFilter())).error.code)
        assertEquals(9, wire.requests.size - start)
    }
    @Test fun removeLostAddUsesAddOperationId() = readerTest { env ->
        val wire = DiscoverWireFixture().apply { json = { _, _ -> discoverEnvelope("\"dulcetWishMutation\":{\"applied\":false,\"removed\":true}") } }
        val transport = RestWishlistTransport(env.reader(transport = wire))
        val r = assertIs<DiscoverResult.Value<WishReceipt>>(transport.apply(WishMutation.Remove("remove-uuid", 1000, null, "add-uuid"))).value
        assertTrue(r.removed)
        val sent = wire.requests.single()
        assertEquals("removeFromDulcetWishlist", sent.first)
        assertEquals("add-uuid", sent.second["addOperationId"])
        assertEquals("remove-uuid", sent.second["operationId"])
        assertEquals("1970-01-01T00:00:01Z", sent.second["actionTime"])
        assertFalse("id" in sent.second)
        assertEquals("invalid-response", assertIs<DiscoverResult.Failed>(transport.apply(WishMutation.Remove("bad",1000,"id","add"))).error.code)
    }
    @Test fun mapsWishAddReadAndExplicitPairRejection() = readerTest { env ->
        val wire = DiscoverWireFixture().apply { json = { endpoint, _ -> discoverEnvelope(if (endpoint == "getDulcetWish") "\"dulcetWish\":" + wishJson() else "\"dulcetWishMutation\":{\"applied\":true,\"removed\":false,\"wish\":${wishJson()}}") } }
        val transport = RestWishlistTransport(env.reader(transport = wire))
        assertIs<DiscoverResult.Value<DiscoverWish>>(transport.wish("wish:id"))
        assertIs<DiscoverResult.Value<WishReceipt>>(transport.apply(WishMutation.Add("a",1000,DiscoverTarget(DiscoverKind.Song,DISCOVER_MBID),WishSnapshot("A & B",null,null,null),WishSource("search",null))))
        assertIs<DiscoverResult.Value<WishReceipt>>(transport.apply(WishMutation.Reject("r", DiscoverTarget(DiscoverKind.Song,DISCOVER_MBID),DiscoverLibraryRef("server:reader","song","local:/id"),"wish:id")))
        assertEquals("A & B", wire.requests[1].second["title"])
        assertEquals("local:/id", wire.requests[2].second["libraryId"])
        assertEquals("song", wire.requests[2].second["libraryType"])
    }
}
