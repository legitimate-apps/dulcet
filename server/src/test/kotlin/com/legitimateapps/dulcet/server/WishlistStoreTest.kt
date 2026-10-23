package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.wishlist.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WishlistStoreTest {
    private val mbid="ABCDEFAB-1111-4111-8111-111111111111"
    private fun op()=UUID.randomUUID().toString()
    private fun add(operation:String=op(),time:Long?=null)=AddWish(DiscoverKind.Album,mapOf(DiscoverKind.Album.registryKey to mbid),WishSnapshot("Album","Artist"),WishSource("api"),operation,time)
    private inline fun fixture(block:(ServerContext,String,WishlistService)->Unit) {
        val ctx=Fixtures.context(Files.createTempDirectory("wishlist-store"))
        try { val u=ctx.users.create("user","password",false);block(ctx,u.id,WishlistService(ctx)) } finally {ctx.db.close()}
    }
    @Test fun concurrentCanonicalAddsKeepOneIdAndOriginalTime() = runBlocking<Unit> {
        fixture { ctx,user,s ->
            val first=s.add(user,add(time=10)).wish!!
            runBlocking { coroutineScope { (1..20).map { async(Dispatchers.Default) { s.add(user,add(time=20)) } }.awaitAll() }.forEach { assertEquals(first.id,it.wish!!.id) } }
            assertEquals(10,first.addedAt);assertEquals("wanted",first.state)
            assertEquals(1,ctx.db.read { long("SELECT count(*) FROM api_journal WHERE type='wish'") })
            val another=ctx.users.create("other","password",false)
            assertNotEquals(first.id,s.add(another.id,add()).wish!!.id)
            assertEquals("abcdefab-1111-4111-8111-111111111111",first.externalIds[DiscoverKind.Album.registryKey])
        }
    }
    @Test fun removedAddRetryCannotResurrectAfterRestartAndRetryCacheExpiry() {
        val root=Files.createTempDirectory("wish-restart"); val dbFile=root.resolve("db"); val operation=add(time=100)
        val config=Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music"),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,scanOnStart=false,lrclibUrl=null)
        var user="";var oldId=""
        Db.open(dbFile).use { db -> val ctx=ServerContext(config,db);user=ctx.users.create("user","password",false).id;val s=WishlistService(ctx,{200L});oldId=s.add(user,operation).wish!!.id;s.remove(user,RemoveWish(oldId,null,op(),200)) }
        Db.open(dbFile).use { db ->
            val s=WishlistService(ServerContext(config,db),{3*DiscoverCache.DAY})
            val replay=s.add(user,operation);assertFalse(replay.applied);assertTrue(replay.removed);assertNull(replay.wish)
            val newer=s.add(user,add(time=300)).wish!!;assertNotEquals(oldId,newer.id)
            assertFalse(s.remove(user,RemoveWish(oldId,null,op(),400)).applied)
            assertNotNull(s.get(user,newer.id))
        }
    }
    @Test fun removeBeforeAddCancelsUnacknowledgedOperation() = fixture { _,user,s ->
        val a=add(time=100);s.remove(user,RemoveWish(null,a.operationId,op(),200))
        assertTrue(s.add(user,a).removed);assertTrue(s.list(user,WishFilter()).isEmpty())
        assertNotNull(s.add(user,add(time=300)).wish)
    }
    @Test fun offlineClockRejectsOlderAddAndRemovalWinsTie() = fixture { _,user,s ->
        val first=s.add(user,add(time=200)).wish!!
        assertFalse(s.remove(user,RemoveWish(first.id,null,op(),100)).applied)
        assertNotNull(s.get(user,first.id))
        s.remove(user,RemoveWish(first.id,null,op(),200))
        assertTrue(s.add(user,add(time=200)).removed)
        assertNotNull(s.add(user,add(time=201)).wish)
    }
    @Test fun journalWishAndReceiptsRollbackTogether() = fixture { ctx,user,s ->
        assertFailsWith<IllegalStateException> { ctx.db.write { s.add(user,add());error("rollback") } }
        assertEquals(0,ctx.db.read { long("SELECT count(*) FROM wishlist_wish") })
        assertEquals(0,ctx.db.read { long("SELECT count(*) FROM wishlist_operation") })
        assertEquals(0,ctx.db.read { long("SELECT count(*) FROM api_journal WHERE type='wish'") })
    }
    @Test fun fulfilledDefaultListBoundaryIsThirtyDays() = fixture { ctx,user,_ ->
        var now=1_000_000L
        val s=WishlistService(ctx,{now});val wish=s.add(user,add()).wish!!
        ctx.db.write { execute("UPDATE wishlist_wish SET state='fulfilled',fulfilled=? WHERE id=?",now,wish.id) }
        now+=30*DiscoverCache.DAY-1
        assertEquals(1,s.list(user).size)
        now++
        assertTrue(s.list(user).isEmpty())
        assertEquals(1,s.list(user,WishFilter(includeFulfilledHistory=true)).size)
        assertNotNull(s.get(user,wish.id))
    }
    @Test fun operationReuseConflictsAndDuplicateSnapshotStaysOriginal() = fixture { _,user,s ->
        val a=add(); val first=s.add(user,a).wish!!
        assertFailsWith<com.legitimateapps.dulcet.server.v1.ApiProblem> { s.add(user,a.copy(snapshot=WishSnapshot("Changed"))) }
        assertEquals(first.snapshot,s.add(user,add().copy(snapshot=WishSnapshot("Changed"))).wish!!.snapshot)
    }
}
