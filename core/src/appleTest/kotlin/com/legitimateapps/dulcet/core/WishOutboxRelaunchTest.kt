@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import app.cash.sqldelight.db.QueryResult
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

class WishOutboxRelaunchTest {

    @Test fun lostReceiptCancellationReplaysAfterClosingAndReopeningFile() = runTest {
        val name="dulcet-wish-relaunch-${NSUUID().UUIDString}.db"
        val locator=DulcetDriverFactory(name).createDriver()
        val path=locator.executeQuery(null,"PRAGMA database_list",{cursor ->check(cursor.next().value);QueryResult.Value(cursor.getString(2)!!)},0).value
        locator.close()
        val wire=WishlistTestWire().apply {loseNextAdd=true}
        var db:DulcetDatabaseStore?=null
        try {
            db=DulcetDriverFactory(name).openDulcetDatabase()
            val store=DiscoverStore(db.database,"account-A")
            val first=outbox(store,wire)
            val add=assertIs<DiscoverResult.Value<WishEnqueued>>(first.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
            first.flush();first.enqueueRemove(add.rowKey)
            val immutable=store.pending().map {it.mutation}
            first.close();db.close();db=null
            db=DulcetDriverFactory(name).openDulcetDatabase()
            val reopened=DiscoverStore(db.database,"account-A")
            assertEquals(immutable,reopened.pending().map {it.mutation})
            assertEquals(2L,reopened.pendingCount())
            val replay=outbox(reopened,wire);replay.flush()
            assertNull(wire.wish);assertEquals(0L,reopened.pendingCount())
            assertTrue(replay.overlay(WishListSnapshot(emptyList(),"settled")).isEmpty())
        } finally {db?.close();listOf("","-wal","-shm").forEach {NSFileManager.defaultManager.removeItemAtPath(path+it,null)}}
    }
}
