package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Production reader/Discover/outbox transports, with disposable listener failures only. */
internal suspend fun connectionIntentContinuityProof(
    store: DulcetDatabaseStore, anchor: OwnershipAnchor, password: String, trackId: String,
    control: suspend (String, String) -> JsonObject,
) = coroutineScope {
    val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    val request = AccountConnectionRequest(anchor.normalizedBaseUrl, anchor.username, password,
        anchor.normalizedBaseUrl.startsWith("http:"))
    val identity = ServerIdentityGate(store.database, anchor.accountId, request)
    val transport = KtorLibraryEndpointTransport(LibraryBrowseRequest(anchor.accountId,
        anchor.normalizedBaseUrl, anchor.username, password, request.allowLocalHttp), null, null, systemHostResolver())
    val cache = SeenCacheStore(store, SeenCacheWallClock { Clock.System.now().toEpochMilliseconds() })
        .bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
    val session = LibraryReaderSession(store.database, cache, transport, scope, formPost = true,
        foreground = false, validateServerIdentity = { identity.validate() })
    val sender = ScrobbleEndpointSender(PlaybackEndpointAccount(anchor.accountId, anchor.normalizedBaseUrl,
        anchor.username, password, request.allowLocalHttp))
    suspend fun waitFor(description: String, condition: suspend () -> Boolean) {
        try { withTimeout(20_000) { while (!condition()) delay(20) } }
        catch (timeout: TimeoutCancellationException) { fail(description, timeout) }
    }
    suspend fun receipts() = control("state", "GET").getValue("requests").jsonArray.map { it.jsonObject }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
    suspend fun <T> command(start: ((DiscoverResult<T>) -> Unit) -> DiscoverOperation): T =
        suspendCancellableCoroutine { continuation ->
            val operation = start { result -> if (continuation.isActive) continuation.resumeWith(runCatching {
                assertIs<DiscoverResult.Value<T>>(result).value
            }) }
            continuation.invokeOnCancellation { operation.cancel() }
        }
    try {
        control("reset", "POST")
        delay(300) // A distinct topology after the preceding scenario's coalesced hints.
        ConnectionNetworkEvents.topologyChanged()
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        val client = session.openDiscoverClient()
        var publication: DiscoverPublication? = null
        val subscription = client.observe { publication = it }
        try {
            client.refreshWishlist(WishFilter())
            client.setQuery("Fixture")
            waitFor("Owned fixture must advertise Wishlist and return actual Discover songs") {
                publication?.access?.canAddWish == true && publication?.groups?.any { it.kind == "song" && it.page?.items?.isNotEmpty() == true } == true
            }
            val item = publication!!.groups.single { it.kind == "song" }.page!!.items.first { it.wishable && it.target != null }
            session.reader.setOnline(false)
            val added = command<WishEnqueued> { client.add(item, WishSource("search", null), it) }
            val wishes = DiscoverStore(store.database, anchor.accountId)
            val original = wishes.pending().single { it.mutation.operationId == added.operationId }
            assertIs<WishMutation.Add>(original.mutation)
            val route = ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username)!!.routeId
            control("lose-reply?routeId=$route&endpoint=addToDulcetWishlist&state=on", "POST")
            session.reader.setOnline(true)
            assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
            waitFor("Actual wish commit must lose its acknowledgement") {
                receipts().any { it.text("routeId") == route && it.text("endpoint") == "addToDulcetWishlist" && it.flag("lostReply") }
            }
            session.reader.setOnline(false)
            assertEquals(original.mutation, wishes.pending().single { it.mutation.operationId == added.operationId }.mutation,
                "Operation ID, action time, desired target and order cannot be replaced during route recovery")
            ConnectionNetworkEvents.topologyChanged(); session.reader.setOnline(true)
            assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
            waitFor("Same durable wish must acknowledge through an alternate") { wishes.pendingCount() == 0L }
            val replay = assertIs<DiscoverResult.Value<WishReceipt>>(RestWishlistTransport(session.reader).apply(original.mutation)).value
            val remote = assertIs<DiscoverResult.Value<WishListSnapshot>>(RestWishlistTransport(session.reader).snapshot(WishFilter())).value
            assertEquals(1, remote.items.count { it.target == item.target })
            assertNotNull(replay.wish)
            assertEquals(original.mutation, wishes.intents().single { it.mutation.operationId == added.operationId }.mutation)
            assertTrue(receipts().any { it.text("routeId") != route && it.text("endpoint") == "addToDulcetWishlist" && it.flag("completed") })
        } finally { subscription.close(); client.close() }

        // A separate durable play demonstrates the intentional REST at-least-once boundary.
        val now = Clock.System.now().toEpochMilliseconds()
        val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { now })
        val event = RecordedPlaybackEvent.SubmittedPlay(ProviderItemId(anchor.accountId, trackId), PlaybackWallClockTime(now - 90_000))
        outbox.persistForAtLeastOnceDelivery(event)
        val before = outbox.pending(ServerId(anchor.accountId)).single()
        val route = ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username)!!.routeId
        control("lose-reply?routeId=$route&endpoint=scrobble&state=on", "POST")
        var monotonic = 0.seconds
        val worker = ScrobbleOutboxDeliveryWorker(ServerId(anchor.accountId), outbox, sender,
            OutboxWallClock { now }, OutboxMonotonicClock { monotonic }, ScrobbleOutboxDiagnosticSink {},
            validateServerIdentity = { identity.validate() })
        assertEquals(0, worker.onForeground().deliveredCount)
        assertTrue(receipts().any { it.text("routeId") == route && it.text("endpoint") == "scrobble" && it.flag("lostReply") })
        val held = outbox.pending(ServerId(anchor.accountId)).single()
        assertEquals(before.sessionStartWallClock, held.sessionStartWallClock)
        assertEquals(before.createdAtWallClock, held.createdAtWallClock)
        assertEquals(before.rawId, held.rawId)
        assertEquals(1L, held.attemptCount)
        ConnectionNetworkEvents.topologyChanged(); monotonic = 65.seconds
        assertEquals(1, worker.onReachable().deliveredCount)
        assertTrue(outbox.pending(ServerId(anchor.accountId)).isEmpty())
        assertTrue(receipts().any { it.text("routeId") != route && it.text("endpoint") == "scrobble" && it.flag("completed") })
        println("CONNECTIONS MP-06 wishlist=stable-operation-time-order lost-ack=1 server-wish=1 scrobble=at-least-once same-event=1")
    } finally {
        session.reader.setOnline(false); session.closeDiscoverClients(); scope.cancel(); sender.close(); transport.close()
        control("reset", "POST")
    }
}
