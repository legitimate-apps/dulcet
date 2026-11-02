package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real platform TLS probes: topology storms share admission without retiring healthy work. */
internal suspend fun connectionReconnectContinuityProof(
    anchor: OwnershipAnchor, control: suspend (String, String) -> JsonObject,
) = coroutineScope {
    suspend fun receipts() = control("state", "GET").getValue("requests").jsonArray.map { it.jsonObject }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    try {
        control("reset", "POST")
        control("outage?routeId=home&state=on", "POST")
        delay(300)
        repeat(100) { ConnectionNetworkEvents.topologyChanged() }
        delay(300)
        assertTrue(receipts().isEmpty(), "A topology storm without demand must create zero idle traffic")
        val admitted = List(30) { async { ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username).lease } }.awaitAll()
        val active = assertNotNull(admitted.first())
        assertEquals("tunnel", active.routeId)
        assertEquals(setOf(active), admitted.toSet(), "Concurrent native consumers must join one selection round")
        delay(500) // All lower preference jobs in this three-route round have settled.
        val first = receipts()
        assertTrue(first.isNotEmpty(), "The proof must execute real TLS admission")
        assertTrue(first.all { it.text("endpoint") == "dulcet" && it["queryAuth"]?.jsonPrimitive?.boolean == false })
        assertTrue(first.groupBy { it.text("routeId") }.values.all { it.size == 1 }, "One probe per candidate in the shared round")
        delay(300)
        assertEquals(first.size, receipts().size, "There is no idle preference polling")

        control("outage?routeId=home&state=off", "POST")
        repeat(100) { ConnectionNetworkEvents.topologyChanged() }
        delay(300)
        assertEquals(first.size, receipts().size, "A preference hint cannot itself send traffic")
        val preferred = List(30) { async { ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username).lease } }.awaitAll()
        val next = assertNotNull(preferred.first())
        assertEquals("home", next.routeId)
        assertEquals(setOf(next), preferred.toSet())
        assertNotEquals(active.attemptGeneration, next.attemptGeneration)
        assertTrue(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, active.baseUrl, active.generation),
            "Preferring home for future requests cannot revoke healthy in-flight tunnel media")
        assertEquals(anchor.normalizedBaseUrl, ConnectionRouteRegistry.displayed(ConnectionSnapshot(anchor, emptyList(), null, 1, true)).anchor.normalizedBaseUrl)
        println("CONNECTIONS MP-08 topology-hints=200 consumers=60 shared-rounds=2 idle-probes=0 healthy-old-authority=retained")
    } finally {
        control("reset", "POST")
        delay(300)
        ConnectionNetworkEvents.topologyChanged()
    }
}
