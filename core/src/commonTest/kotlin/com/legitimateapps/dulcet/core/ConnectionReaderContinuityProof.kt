package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Clock

/** Shared test scenario; callers supply an owned disposable fixture and their real platform driver. */
internal suspend fun connectionReaderContinuityProof(
    store: DulcetDatabaseStore,
    anchor: OwnershipAnchor,
    password: String,
    albumId: String,
    control: suspend (String, String) -> JsonObject,
) = coroutineScope {
    val ownerScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    val wire = KtorLibraryEndpointTransport(LibraryBrowseRequest(anchor.accountId,
        anchor.normalizedBaseUrl, anchor.username, password, anchor.normalizedBaseUrl.startsWith("http:")),
        saltSource = null, logSink = null, hostResolver = systemHostResolver())
    val cache = SeenCacheStore(store, SeenCacheWallClock { Clock.System.now().toEpochMilliseconds() })
        .bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
    val session = LibraryReaderSession(store.database, cache, wire, ownerScope, formPost = true, foreground = false,
        validateServerIdentity = { ServerIdentityGate(store.database, anchor.accountId,
            AccountConnectionRequest(anchor.normalizedBaseUrl, anchor.username, password,
                anchor.normalizedBaseUrl.startsWith("http:"))).validate() })
    val windows = mutableListOf<LibraryWindowHandle>()
    suspend fun awaitCondition(description: String, condition: suspend () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(20) }
        assertTrue(condition(), description)
    }
    suspend fun receipts() = control("state", "GET").getValue("requests").jsonArray.map { it.jsonObject }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
    fun sequence() = store.database.seenCacheQueries.selectAlbumSequences(anchor.accountId, albumId)
        .executeAsOneOrNull()?.issue_seq ?: -1L
    val query = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    try {
        control("reset", "POST")
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        session.myLibrary.refresh()
        val first = mutableListOf<LibraryPublication>()
        val oldWindow = session.reader.open(query) { first += it }.also(windows::add)
        awaitCondition("Initial catalogue must be read through the admitted home route") { first.any { p -> p.items.any { it.rawId == albumId } } }
        val initialSequence = sequence()
        control("hold?routeId=home&endpoint=getAlbumList2&state=on", "POST")
        oldWindow.refresh()
        awaitCondition("An old page must really be held after backend execution") { receipts().any { it.text("routeId") == "home" && it.flag("held") } }
        control("outage?routeId=home&state=on", "POST")
        ConnectionNetworkEvents.topologyChanged()
        val newer = mutableListOf<LibraryPublication>()
        session.reader.open(query) { newer += it }.also(windows::add)
        awaitCondition("New request demand must read the same catalogue through the alternate") {
            sequence() > initialSequence && receipts().any { it.text("routeId") == "tunnel" && it.text("endpoint") == "getAlbumList2" && it.flag("completed") }
        }
        assertTrue(newer.first().items.any { it.rawId == albumId }, "The new native reader window must publish cache before its reply")
        val newerSequence = sequence()
        control("hold?routeId=home&endpoint=getAlbumList2&state=off", "POST")
        awaitCondition("The old healthy authority may finish after preference changes") { receipts().any { it.flag("held") && it.flag("holdReleased") && it.flag("completed") } }
        delay(100)
        assertEquals(newerSequence, sequence(), "An older reply cannot replace the newer issue under the frozen owner")

        session.reader.setOnline(false)
        val offline = mutableListOf<LibraryPublication>()
        val count = receipts().size
        session.reader.open(query) { offline += it }.also(windows::add)
        assertTrue(offline.first().items.any { it.rawId == albumId })
        delay(100)
        assertEquals(count, receipts().size, "Opening the retained catalogue offline must dispatch nothing")
        val target = LibraryEntityRef(LibraryEntityKind.Album, albumId)
        assertEquals(MutationRecord.Pending, session.favourites.setFavourite(target, true))
        assertEquals(MutationRecord.Pending, session.favourites.setRating(target, 4))
        assertEquals(MutationRecord.Pending, session.myLibrary.add(target))
        val pending = session.favourites.pendingChanges()
        assertEquals(3, pending.size)
        val star = pending.single { it.field == MutationField.Starred }
        control("lose-reply?routeId=tunnel&endpoint=star&state=on", "POST")
        session.reader.setOnline(true)
        awaitCondition("The old route must commit the actual queued mutation and lose its reply") { receipts().any { it.text("routeId") == "tunnel" && it.text("endpoint") == "star" && it.flag("lostReply") } }
        session.reader.setOnline(false)
        val retainedStar = session.favourites.pendingChanges().single { it.field == MutationField.Starred }
        assertEquals(star.localSequence, retainedStar.localSequence)
        assertEquals(star.wallClock, retainedStar.wallClock)
        assertEquals(star.value, retainedStar.value)
        ConnectionNetworkEvents.topologyChanged()
        session.reader.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        awaitCondition("Desired values must reconcile through the public route") { session.favourites.pendingChanges().isEmpty() }
        assertEquals(true, session.favourites.isFavourite(target))
        assertEquals(4, session.favourites.rating(target))
        val finalReceipts = receipts()
        assertTrue(finalReceipts.any { it.text("routeId") == "remote" && it.text("endpoint") == "star" && it.flag("completed") })
        assertTrue(finalReceipts.none { it.text("routeId") in setOf("different", "unlisted", "legacy") && it.flag("queryAuth") },
            "No user intent may be dispatched to an unadmitted lineage or retired HTTP anchor")
        assertEquals(anchor.accountId, cache.serverId)
        assertFalse(cache.purgedOnBind)
    } finally {
        session.reader.setOnline(false)
        windows.forEach { it.close() }
        session.closeDiscoverClients()
        ownerScope.cancel()
        wire.close()
        control("reset", "POST")
    }
}
