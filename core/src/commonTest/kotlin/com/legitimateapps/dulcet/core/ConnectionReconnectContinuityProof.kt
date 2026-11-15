package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real platform TLS probes: topology storms share admission without retiring healthy work. */
internal suspend fun connectionReconnectContinuityProof(
    anchor: OwnershipAnchor, control: suspend (String, String) -> JsonObject,
    candidateBases: Map<String, String>, probeCalls: suspend () -> List<String>,
) = coroutineScope {
    suspend fun receipts() = control("state", "GET").getValue("requests").jsonArray.map { it.jsonObject }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    fun assertPublicDiscovery(requests: List<JsonObject>) {
        assertTrue(requests.all { request ->
            request.text("endpoint") == "dulcet" &&
                listOf("queryAuth", "bodyAuth", "authorization").all { field ->
                    request[field]?.jsonPrimitive?.booleanOrNull == false
                }
        }, "Every native HTTP attempt must remain credential-free public discovery")
    }
    fun assertOneCallPerCandidate(calls: List<String>, winner: RouteLease, round: String) {
        assertTrue(calls.isNotEmpty(), "$round must invoke the real candidate probe")
        assertTrue(calls.all { it in candidateBases.values }, "$round must verify only admitted candidates")
        assertEquals(1, calls.count { it == winner.baseUrl }, "$round must verify its admitted winner exactly once")
        assertTrue(calls.groupingBy { it }.eachCount().values.all { it == 1 },
            "One logical ConnectionCandidateProbe.verify call per candidate in $round")
    }
    try {
        control("reset", "POST")
        control("outage?routeId=home&state=on", "POST")
        delay(300)
        repeat(100) { ConnectionNetworkEvents.topologyChanged() }
        delay(300)
        assertTrue(receipts().isEmpty(), "A topology storm without demand must create zero idle traffic")
        val idleCalls = probeCalls()
        assertTrue(idleCalls.isEmpty(), "Restoring routes and idle topology hints must not invoke any candidate probe")
        val admitted = List(30) { async { ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username).lease } }.awaitAll()
        val active = assertNotNull(admitted.first())
        assertEquals("tunnel", active.routeId)
        assertEquals(setOf(active), admitted.toSet(), "Concurrent native consumers must join one selection round")
        delay(500) // All lower preference jobs in this three-route round have settled.
        val first = receipts()
        assertTrue(first.isNotEmpty(), "The proof must execute real TLS admission")
        assertPublicDiscovery(first)
        assertTrue(first.any { it.text("routeId") == "home" } && first.any { it.text("routeId") == "tunnel" },
            "The failed home and successful tunnel must both execute real TLS discovery")
        // A native HTTP engine may make several wire attempts inside one verify invocation.
        // Count actual coordinator calls, retaining every wire attempt for security/idle checks.
        val firstCalls = probeCalls()
        assertEquals(1, firstCalls.count { it == candidateBases.getValue("home") },
            "The first round must logically probe the unavailable home exactly once")
        assertOneCallPerCandidate(firstCalls, active, "the shared first round")
        delay(300)
        assertEquals(first.size, receipts().size, "There is no idle preference polling")
        assertEquals(firstCalls, probeCalls(), "No logical candidate probe may start during idle")

        control("outage?routeId=home&state=off", "POST")
        repeat(100) { ConnectionNetworkEvents.topologyChanged() }
        delay(300)
        assertEquals(first.size, receipts().size, "A preference hint cannot itself send traffic")
        assertEquals(firstCalls, probeCalls(), "Preference hints alone cannot invoke candidate verification")
        val preferred = List(30) { async { ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username).lease } }.awaitAll()
        val next = assertNotNull(preferred.first())
        assertEquals("home", next.routeId)
        assertEquals(setOf(next), preferred.toSet())
        delay(500) // Allow cancelled lower-priority jobs in this round to settle before its snapshot.
        val allCalls = probeCalls()
        assertEquals(firstCalls, allCalls.take(firstCalls.size), "Probe snapshots retain every earlier invocation")
        assertOneCallPerCandidate(allCalls.drop(firstCalls.size), next, "the shared second round")
        val second = receipts()
        assertPublicDiscovery(second)
        delay(300)
        assertEquals(second.size, receipts().size, "The second round must not start idle preference polling")
        assertEquals(allCalls, probeCalls(), "The second round must not start another logical probe while idle")
        assertNotEquals(active.attemptGeneration, next.attemptGeneration)
        assertTrue(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, active.baseUrl, active.generation),
            "Preferring home for future requests cannot revoke healthy in-flight tunnel media")
        assertEquals(anchor.normalizedBaseUrl, ConnectionRouteRegistry.displayed(ConnectionSnapshot(anchor, emptyList(), null, 1, true)).anchor.normalizedBaseUrl)
        println("CONNECTIONS MP-08 topology-hints=200 consumers=60 shared-rounds=2 " +
            "logical-probes=${firstCalls.size}/${allCalls.size - firstCalls.size} " +
            "idle-probes=0 healthy-old-authority=retained")
    } finally {
        control("reset", "POST")
        delay(300)
        ConnectionNetworkEvents.topologyChanged()
    }
}
