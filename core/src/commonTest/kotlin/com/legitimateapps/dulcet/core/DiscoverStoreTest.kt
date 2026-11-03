package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.test.*
import kotlin.test.*

internal fun sampleDiscoverItem(id: String = "item", mbid: String? = DISCOVER_MBID, kind: String = "song", status: String = "notHeld", completeness: String = "unknown", refs: List<DiscoverLibraryRef> = emptyList()) =
    DiscoverItem(id,kind,mbid?.let { DiscoverTarget(DiscoverKind.entries.first { it.wire == kind },it) },"Studio",null,null,null,null,null,null,null,null,emptyList(),mbid != null,
        DiscoverAvailability(status,completeness,null,null,null,null,refs))
internal fun sampleWish(id: String = "wish", target: DiscoverTarget? = sampleDiscoverItem().target, state: String = "wanted") =
    DiscoverWish(id,"owner",target,"song",WishSnapshot("Studio",null,null,null),WishSource("search",null),1000,1000,state,null,null,null,null,
        DiscoverAvailability("notHeld","unknown",null,null,null,null,emptyList()),"revision")
internal fun sampleAdd(id: String = "add-A", time: Long = 1000) = WishMutation.Add(id,time, sampleDiscoverItem().target!!,WishSnapshot("Studio",null,null,null),WishSource("search",null))

internal class DiscoverStoreEnv(val database: DulcetDatabase, val seen: SeenCacheStore, var now: Long = 1000) {
    fun store(id: String = "account-A", jsonLimit: Long = 16*1024*1024, artLimit: Long = 32*1024*1024) = DiscoverStore(database,id,{now},jsonLimit,artLimit)
}
internal fun discoverStoreTest(block: suspend TestScope.(DiscoverStoreEnv) -> Unit) = runTest {
    val driver = createTestDriver()
    try {
        val db = DulcetDatabaseStore.open(driver)
        block(DiscoverStoreEnv(db.database, SeenCacheStore(db,SeenCacheWallClock {1000})))
    } finally { driver.close() }
}
class DiscoverStoreTest {
    @Test fun migration13To14RetainsPendingMyLibraryMediaOwnershipAndQueueContext() {
        val driver=createTestDriver()
        try {
            val before=DulcetDatabaseStore.open(driver)
            before.database.protectedReservedDataQueries.insertPendingMutation("A","song:opaque","inLibrary","1",1,1000)
            before.database.libraryDepthQueries.putEntry("A","download.mediaBinding","binding","opaque-owner",1)
            before.database.queueQueries.insertQueueStateIfMissing("A")
            before.database.queueQueries.insertQueueEntry(
                server_id = "A", queue_entry_id = "queue:discover-upgrade", raw_id = "song:opaque",
                source_context_kind = "album", source_context_raw_id = "album:opaque",
                source_context_display_name = "Retained album", added_by = "play_now",
                original_position = 0, playback_position = 0,
                duration_milliseconds = 42000, album_raw_id = "album:opaque",
            )
            for (table in listOf("discover_read","wishlist_snapshot","wish_clock","wish_intent","wish_receipt"))
                driver.execute(null,"DROP TABLE IF EXISTS $table",0).value
            before.database.schemaMetaQueries.updateSchemaVersion(13)
            DulcetDatabase.Schema.migrate(driver,13,14)
            val after=DulcetDatabaseStore.open(driver)
            assertEquals(1L,after.database.protectedReservedDataQueries.countMutationsForServer("A").executeAsOne())
            assertEquals("opaque-owner",after.database.libraryDepthQueries.selectEntry("A","download.mediaBinding","binding").executeAsOne())
            assertEquals(0L,DiscoverStore(after.database,"A").pendingCount())
            assertEquals(DulcetDatabase.Schema.version,after.metadata().schemaVersion)
            val queue = after.database.queueQueries.selectQueueEntriesByOriginalPosition("A").executeAsOne()
            assertEquals("queue:discover-upgrade", queue.queue_entry_id)
            assertEquals(42000L, queue.duration_milliseconds)
            assertEquals("album:opaque", queue.album_raw_id)
        } finally { driver.close() }
    }

    @Test fun restartRetainsIntentButClearCacheDoesNotDeleteIt() = discoverStoreTest { env ->
        val store = env.store()
        assertIs<DiscoverResult.Value<WishEnqueued>>(store.enqueue(sampleAdd()))
        store.putRead("search",byteArrayOf(1,2),false,1)
        env.database.serverDataQueries.deleteSeenCacheForServer("account-A")
        val reopened = env.store()
        assertEquals(sampleAdd(),reopened.pending().single().mutation)
        assertNull(reopened.read("search"))
        assertEquals(1L,reopened.pendingCount())
    }
    @Test fun sameMbidAcrossTwoAccountsNeverSharesRows() = discoverStoreTest { env ->
        env.store().enqueue(sampleAdd())
        env.store().replaceSnapshot(WishFilter(),WishListSnapshot(listOf(sampleWish()),"clock"),1)
        assertTrue(env.store("account-B").pending().isEmpty())
        assertNull(env.store("account-B").snapshot(WishFilter()))
        assertEquals(1,env.store().snapshot(WishFilter())?.items?.size)
    }
    @Test fun bindingPurgePreservesAcceptedIdentityUntilTrueRemoval() = discoverStoreTest { env ->
        val binding = CacheBinding("account-A","https://music.example","one")
        env.seen.bind(binding)
        ServerIdentityStore(env.database).accept("account-A",ServerIdentityPolicy.identity("dulcet","lineage"))
        env.store().enqueue(sampleAdd())
        env.seen.bind(binding.copy(username="two"))
        assertTrue(env.store().pending().isEmpty())
        assertEquals(ServerIdentityPolicy.identity("dulcet","lineage"),ServerIdentityStore(env.database).read("account-A"))
    }
    @Test fun cacheBudgetsEvictLeastRecentWithoutTouchingIntents() = discoverStoreTest { env ->
        val store = env.store(jsonLimit=4,artLimit=4)
        store.enqueue(sampleAdd())
        store.putRead("a",byteArrayOf(1,2),false,1)
        store.putRead("b",byteArrayOf(3,4),false,2)
        assertNotNull(store.read("a"))
        store.putRead("c",byteArrayOf(5,6),false,3)
        assertNull(store.read("b"))
        store.putRead("art",ByteArray(5),true,4)
        assertNull(store.read("art"))
        assertEquals(1L,store.pendingCount())
        assertNotNull(store.read("a"))
    }
    @Test fun olderReadAndSnapshotCannotOverwriteNewerEvidence() = discoverStoreTest { env ->
        val store=env.store()
        store.putRead("a",byteArrayOf(2),false,2)
        store.putRead("a",byteArrayOf(1),false,1)
        assertContentEquals(byteArrayOf(2),store.read("a"))
        store.replaceSnapshot(WishFilter(),WishListSnapshot(listOf(sampleWish("new")),"new-clock"),2)
        store.replaceSnapshot(WishFilter(),WishListSnapshot(emptyList(),"old-clock"),1)
        assertEquals("new",store.snapshot(WishFilter())?.items?.single()?.id)
    }
    @Test fun operationUuidIsImmutableAndActionClockSurvivesAcknowledgement() = discoverStoreTest { env ->
        val store=env.store()
        assertIs<DiscoverResult.Value<WishEnqueued>>(store.enqueue(sampleAdd()))
        assertIs<DiscoverResult.Value<WishEnqueued>>(store.enqueue(sampleAdd()))
        assertEquals(1L,store.pendingCount())
        assertEquals("conflict",assertIs<DiscoverResult.Failed>(store.enqueue(sampleAdd().copy(actionTimeMillis=2000))).error.code)
        store.acknowledge("add-A",WishReceipt(true,sampleWish(),false))
        assertEquals(0L,store.pendingCount())
        env.now = 1
        assertEquals(1001,env.store().nextActionTime())
    }
    @Test fun closedStorageCannotPublishEnqueueSuccess() = runTest {
        val driver=createTestDriver()
        val database=DulcetDatabaseStore.open(driver).database
        val store=DiscoverStore(database,"A")
        driver.close()
        assertEquals("storage",assertIs<DiscoverResult.Failed>(store.enqueue(sampleAdd())).error.code)
        assertNull(store.pendingCount())
    }
}
