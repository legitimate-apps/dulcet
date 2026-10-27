package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import java.nio.file.Files

class WishOutboxRelaunchTest {

    @Test fun lostReceiptCancellationReplaysAfterClosingAndReopeningFile() = runTest {
        val path=Files.createTempFile("dulcet-wish-relaunch-", ".db");Files.delete(path)
        val wire=WishlistTestWire().apply {loseNextAdd=true}
        var db:DulcetDatabaseStore?=null
        try {
            db=DulcetDriverFactory(path.toString()).openDulcetDatabase()
            val store=DiscoverStore(db.database,"account-A")
            val first=outbox(store,wire)
            val add=assertIs<DiscoverResult.Value<WishEnqueued>>(first.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
            first.flush();first.enqueueRemove(add.rowKey)
            val immutable=store.pending().map {it.mutation}
            first.close();db.close();db=null
            db=DulcetDriverFactory(path.toString()).openDulcetDatabase()
            val reopened=DiscoverStore(db.database,"account-A")
            assertEquals(immutable,reopened.pending().map {it.mutation})
            assertEquals(2L,reopened.pendingCount())
            val replay=outbox(reopened,wire);replay.flush()
            assertNull(wire.wish);assertEquals(0L,reopened.pendingCount())
            assertTrue(replay.overlay(WishListSnapshot(emptyList(),"settled")).isEmpty())
        } finally {db?.close();listOf("","-wal","-shm").forEach {Files.deleteIfExists(java.nio.file.Path.of(path.toString()+it))}}
    }
}
