package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.wishlist.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WishFulfilmentTest {
    private val group="11111111-1111-4111-8111-111111111111"
    private val artist="22222222-2222-4222-8222-222222222222"
    private fun recording(n:Int)="${n.toString().padStart(8,'0')}-3333-4333-8333-333333333333"
    private fun config(f:FixtureDiscoverUpstream):Config {
        val root=Files.createTempDirectory("wish-fulfil")
        return Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music").also(Files::createDirectories),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,lrclibUrl=null,scanOnStart=false,discover=DiscoverSettings(musicBrainzUrl=f.baseUrl,musicBrainzMinGapMillis=0,listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl,coverArtArchiveUrl=f.baseUrl))
    }
    private fun add(ctx:ServerContext,user:String,kind:DiscoverKind)=ctx.wishlist.add(user,AddWish(kind,mapOf(kind.registryKey to when(kind){DiscoverKind.Artist->artist;DiscoverKind.Album->group;DiscoverKind.Song->recording(1)}),WishSnapshot(if(kind==DiscoverKind.Artist)"Artist" else if(kind==DiscoverKind.Album)"Album" else "Song 1","Artist"),WishSource("api"),UUID.randomUUID().toString())).wish!!
    private fun track(ctx:ServerContext,n:Int) = Fixtures.track(ctx.config.musicFolder.resolve("$n.flac"),tags=mapOf("title" to "Song $n","album" to "Album","artist" to "Artist","tracknumber" to "$n","MUSICBRAINZ_RELEASEGROUPID" to group,"MUSICBRAINZ_ARTISTID" to artist,"MUSICBRAINZ_ALBUMARTISTID" to artist,"MUSICBRAINZ_TRACKID" to recording(n),"MUSICBRAINZ_RELEASETRACKID" to recording(n)))
    private fun editions(f:FixtureDiscoverUpstream,count:Int) {
        val tracks=(1..count).joinToString(",") { """{"position":$it,"title":"Song $it","recording":{"id":"${recording(it)}","title":"Song $it"}}""" }
        f.json("/ws/2/release","""{"release-count":1,"releases":[{"id":"${recording(99)}","release-group":{"id":"$group"},"artist-credit":[{"name":"Artist"}],"media":[{"position":1,"track-count":$count,"tracks":[$tracks]}]}]}""")
    }
    @Test fun scanArrivesSongAlbumArtistAndTheirOwnMembership() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> val ctx=ServerContext(config(f),Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false)
                val wishes=DiscoverKind.entries.map { add(ctx,user.id,it) };editions(f,1);track(ctx,1)
                ctx.scanner.scan();ctx.wishFulfilment.drain()
                for(w in wishes)assertEquals("fulfilled",ctx.wishlist.get(user.id,w.id)!!.state)
                assertEquals(setOf("song","album","artist"),ctx.db.read { query("SELECT item_type FROM library_item") { it.string("item_type") }.toSet() })
                assertEquals(3,ctx.db.read { long("SELECT count(*) FROM api_library_receipt") })
                assertNotNull(ctx.db.read { string("SELECT value FROM meta WHERE key=?","library_changed:${user.id}") })
                val journal=ctx.db.read { long("SELECT count(*) FROM api_journal WHERE type='wish'") }
                ctx.wishFulfilment.drain();assertEquals(journal,ctx.db.read { long("SELECT count(*) FROM api_journal WHERE type='wish'") })
                assertEquals(1,f.requests.size)
                ctx.db.write { execute("DELETE FROM track");execute("DELETE FROM album");execute("DELETE FROM artist") }
                assertTrue(wishes.all { ctx.wishlist.get(user.id,it.id)!!.state=="fulfilled" })
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun eightOfTwelveStaysOpenUntilLastTracksScan() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> val ctx=ServerContext(config(f),Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false);val w=add(ctx,user.id,DiscoverKind.Album);editions(f,12)
                (1..8).forEach { track(ctx,it) };ctx.scanner.scan();ctx.wishFulfilment.drain()
                val partial=ctx.wishlist.get(user.id,w.id)!!
                assertEquals("wanted",partial.state);assertEquals(8,partial.availability.matchedTrackCount);assertEquals(12,partial.availability.totalTrackCount)
                (9..12).forEach { track(ctx,it) };ctx.scanner.scan();ctx.wishFulfilment.drain()
                assertEquals("fulfilled",ctx.wishlist.get(user.id,w.id)!!.state);assertEquals(1,f.requests.size)
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun upstreamFailureDoesNotFailScanAndRestartReplaysPending() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f ->
            val config=config(f);val file=config.dataFolder.resolve("catalog.db");val ctx=ServerContext(config,Db.open(file));var user="";var id=""
            try {
                user=ctx.users.create("user","password",false).id;id=add(ctx,user,DiscoverKind.Album).id;track(ctx,1)
                f.json("/ws/2/release","{}",503,mapOf("Retry-After" to "120"))
                assertNull(ctx.scanner.scan()!!.refused);ctx.wishFulfilment.drain()
                assertEquals("wanted",ctx.wishlist.get(user,id)!!.state)
                assertTrue(ctx.db.read { long("SELECT count(*) FROM wishlist_scan_pending") }>0)
            } finally {ctx.close();ctx.db.close()}
            val reopened=ServerContext(config,Db.open(file))
            try {
                editions(f,1);reopened.db.write { execute("UPDATE wishlist_scan_pending SET next_attempt=0") };reopened.wishFulfilment.drain()
                assertEquals("fulfilled",reopened.wishlist.get(user,id)!!.state)
                assertEquals(1,reopened.db.read { long("SELECT count(*) FROM library_item") })
            } finally {reopened.close();reopened.db.close()}
        }
    }
    @Test fun removalWinsAgainstLatchedMetadata() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> val ctx=ServerContext(config(f),Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false);val wish=add(ctx,user.id,DiscoverKind.Album);track(ctx,1);editions(f,1)
                ctx.scanner.scan();f.entered=java.util.concurrent.CountDownLatch(1);f.release=java.util.concurrent.CountDownLatch(1)
                val drain=async(Dispatchers.IO) { ctx.wishFulfilment.drain() }
                assertTrue(withContext(Dispatchers.IO) { f.entered!!.await(5,java.util.concurrent.TimeUnit.SECONDS) })
                ctx.wishlist.remove(user.id,RemoveWish(wish.id,null,UUID.randomUUID().toString(),System.currentTimeMillis()+1))
                f.release!!.countDown();drain.await()
                assertNull(ctx.wishlist.get(user.id,wish.id));assertEquals(0,ctx.db.read { long("SELECT count(*) FROM library_item") })
            } finally {f.release?.countDown();ctx.close();ctx.db.close()}
        }
    }
    @Test fun onlyOpenWishCandidateUsesCachedReleaseLookup() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> val ctx=ServerContext(config(f),Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false);val wish=add(ctx,user.id,DiscoverKind.Album)
                Fixtures.track(ctx.config.musicFolder.resolve("one.flac"),tags=mapOf("title" to "Song 1","album" to "Album","artist" to "Artist","MUSICBRAINZ_ALBUMID" to recording(99),"MUSICBRAINZ_TRACKID" to recording(1)))
                Fixtures.track(ctx.config.musicFolder.resolve("other.flac"),tags=mapOf("title" to "Other","album" to "Other","artist" to "Other","MUSICBRAINZ_ALBUMID" to recording(98)))
                f.json("/ws/2/release/${recording(99)}","""{"id":"${recording(99)}","release-group":{"id":"$group"},"artist-credit":[{"name":"Artist"}],"media":[{"position":1,"track-count":1,"tracks":[{"position":1,"title":"Song 1","recording":{"id":"${recording(1)}","title":"Song 1"}}]}]}""")
                editions(f,1);ctx.scanner.scan();ctx.wishFulfilment.drain()
                assertEquals("fulfilled",ctx.wishlist.get(user.id,wish.id)!!.state)
                ctx.musicBrainz.release(recording(99));assertEquals(2,f.requests.size)
                assertFalse(f.requests.any { it.path.endsWith(recording(98)) })
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun discoverOffCanFulfilLocalSongButKeepsUnknownAlbumPending() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> val c=config(f);val ctx=ServerContext(c.copy(discover=c.discover.copy(enabled=false)),Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false);val song=add(ctx,user.id,DiscoverKind.Song);val album=add(ctx,user.id,DiscoverKind.Album)
                track(ctx,1);ctx.scanner.scan();ctx.wishFulfilment.drain()
                assertEquals("fulfilled",ctx.wishlist.get(user.id,song.id)!!.state)
                assertEquals("wanted",ctx.wishlist.get(user.id,album.id)!!.state);assertEquals(0,f.requests.size)
            } finally {ctx.close();ctx.db.close()}
        }
    }
    @Test fun rollbackChangesNeitherWishNorMembership() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> val ctx=ServerContext(config(f),Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false);val wish=add(ctx,user.id,DiscoverKind.Song);track(ctx,1);ctx.scanner.scan()
                val item=DiscoverItem(kind=wish.kind,title=wish.snapshot.title,artist=wish.snapshot.artist,externalIds=wish.externalIds)
                val availability=ctx.discoverMatcher.match(user.id,item)
                val stamp=ctx.db.read { string("SELECT value FROM meta WHERE key='discover_scan_stamp'") }!!
                assertFailsWith<IllegalStateException> { ctx.db.write {
                    assertTrue(ctx.wishlist.fulfil(user.id,wish.id,availability.libraryItems.single(),wish.revision,stamp,availability))
                    error("rollback")
                } }
                assertEquals("wanted",ctx.wishlist.get(user.id,wish.id)!!.state);assertEquals(0,ctx.db.read { long("SELECT count(*) FROM library_item") })
                ctx.wishFulfilment.drain();assertEquals("fulfilled",ctx.wishlist.get(user.id,wish.id)!!.state)
            } finally {ctx.close();ctx.db.close()}
        }
    }

    @Test fun cliScanDrainsSamePostCommitQueueBeforeExit() {
        FixtureDiscoverUpstream().use { f ->
            val c=config(f);val file=c.dataFolder.resolve("dulcet.db");val ctx=ServerContext(c,Db.open(file));var user="";var wish=""
            try {user=ctx.users.create("user","password",false).id;wish=add(ctx,user,DiscoverKind.Song).id;track(ctx,1)} finally {ctx.close();ctx.db.close()}
            main(arrayOf("scan","--music=${c.musicFolder}","--data=${c.dataFolder}","--ffmpeg=${Fixtures.ffmpeg}","--ffprobe=${Fixtures.ffprobe}","--discover=off","--musicbrainz-url=${f.baseUrl}","--cover-art-archive-url=${f.baseUrl}","--listenbrainz-url=${f.baseUrl}","--listenbrainz-labs-url=${f.baseUrl}"))
            Db.open(file).use { db -> assertEquals("fulfilled",db.read { string("SELECT state FROM wishlist_wish WHERE id=? AND user_id=?",wish,user) }) }
            assertEquals(0,f.requests.size)
        }
    }

}
