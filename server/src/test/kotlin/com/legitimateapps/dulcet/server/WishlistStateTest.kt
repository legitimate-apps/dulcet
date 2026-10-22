package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.wishlist.*
import com.legitimateapps.dulcet.server.v1.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WishlistStateTest {
    private val mbid="11111111-1111-4111-8111-111111111111"
    private fun op()=UUID.randomUUID().toString()
    private inline fun fixture(block:(ServerContext,String,Principal,WishlistService,Wish)->Unit) {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-state"))
        try {
            val user=ctx.users.create("user","password",false);val admin=ctx.users.create("admin","password",true)
            val token=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin"))
            val p=ctx.apiAuth.authenticate("Bearer $token")!!;val s=WishlistService(ctx)
            val wish=s.add(user.id,AddWish(DiscoverKind.Song,mapOf(DiscoverKind.Song.registryKey to mbid),WishSnapshot("One","Artist"),WishSource("api"),op())).wish!!
            block(ctx,user.id,p,s,wish)
        } finally {ctx.db.close()}
    }
    @Test fun allSixteenOpenTransitionsPermitNotes() = fixture { ctx,_,p,s,w ->
        val states=listOf("wanted","requested","searching","unavailable")
        for(from in states) for(to in states) {
            s.setState(p,w.id,from,null,null)
            val changed=s.setState(p,w.id,to,"note $from $to",null)
            assertEquals(to,changed.state);assertEquals("note $from $to",changed.note);assertNotNull(changed.noteAt)
        }
        assertTrue(ctx.db.read { long("SELECT count(*) FROM api_journal WHERE type='wish'") }>16)
    }
    @Test fun adminCannotSetOrLeaveFulfilledAndRevisionIsChecked() = fixture { ctx,_,p,s,w ->
        assertEquals(422,assertFailsWith<ApiProblem> { s.setState(p,w.id,"fulfilled",null,null) }.status)
        assertEquals(412,assertFailsWith<ApiProblem> { s.setState(p,w.id,"wanted",null,"wrong") }.status)
        ctx.db.write { execute("UPDATE wishlist_wish SET state='fulfilled',fulfilled=1 WHERE id=?",w.id) }
        assertEquals(409,assertFailsWith<ApiProblem> { s.setState(p,w.id,"wanted",null,null) }.status)
        ctx.db.write { execute("UPDATE user SET is_admin=0 WHERE id=?",p.user.id) }
        assertEquals(403,assertFailsWith<ApiProblem> { s.setState(p,w.id,"wanted",null,null) }.status)
    }
    @Test fun noteBoundsAndNullClear() = fixture { _,_,p,s,w ->
        val note="🎵".repeat(1000)
        assertEquals(note,s.setState(p,w.id,"requested",note,null).note)
        assertFailsWith<ApiProblem> { s.setState(p,w.id,"requested",note+"x",null) }
        val cleared=s.setState(p,w.id,"wanted",null,null);assertNull(cleared.note);assertNull(cleared.noteAt)
    }
    @Test fun rejectionReopensOnlyLinkedPairAndSurvivesWishRemoval() = fixture { ctx,user,_,s,w ->
        Fixtures.track(ctx.config.musicFolder.resolve("one.flac"),tags=mapOf("title" to "One","artist" to "Artist","album" to "Album","MUSICBRAINZ_TRACKID" to mbid));ctx.scanner.scan()
        val track=ctx.db.read { string("SELECT id FROM track") }!!;val ref=LibraryRef(track,"track")
        ctx.db.write {
            execute("INSERT INTO library_item(user_id,item_type,item_id,added) VALUES(?,'song',?,1)",user,track)
            execute("UPDATE wishlist_wish SET state='fulfilled',fulfilled=1,library_id=?,library_type='track',note='old',note_at=1 WHERE id=?",track,w.id)
        }
        val target=ExternalTarget(w.kind,w.externalIds)
        val reopened=s.rejectMatch(user,target,ref,w.id,op()).wish!!
        assertEquals("wanted",reopened.state);assertNull(reopened.fulfilledAt);assertNull(reopened.note)
        assertEquals(1,ctx.db.read { long("SELECT count(*) FROM library_item") })
        assertTrue(s.isRejected(user,target,ref));assertFalse(s.isRejected("other",target,ref))
        val matcher=DiscoverMatcher(ctx.db,ctx.access) { u,item,pair -> s.isRejected(u,ExternalTarget(item.kind,item.externalIds),pair) }
        assertEquals("notHeld",matcher.match(user,DiscoverItem(kind=w.kind,title="One",artist="Artist",externalIds=w.externalIds)).status)
        assertTrue(WishlistService(ctx).isRejected(user,target,ref))
        s.remove(user,RemoveWish(w.id,null,op()))
        val added=s.add(user,AddWish(w.kind,w.externalIds,w.snapshot,w.source,op(),System.currentTimeMillis()+1)).wish!!
        assertNotEquals(w.id,added.id);assertTrue(s.isRejected(user,target,ref))
        assertEquals(404,assertFailsWith<ApiProblem> { s.rejectMatch(user,target,ref,w.id,op()) }.status)
        assertEquals(added.id,s.list(user).single().id)
        assertEquals(404,assertFailsWith<ApiProblem> { s.rejectMatch("other",target,ref,added.id,op()) }.status)
    }
    @Test fun discoverOnlyCanRejectPairWithoutCreatingWish() {
        val template=Fixtures.context(Files.createTempDirectory("discover-only"));val config=template.config.copy(discover=DiscoverSettings(enabled=true,wishlistEnabled=false));template.db.close()
        val ctx=ServerContext(config,com.legitimateapps.dulcet.server.db.Db.inMemory())
        try {
            val user=ctx.users.create("user","password",false);val s=WishlistService(ctx)
            Fixtures.track(config.musicFolder.resolve("one.flac"),tags=mapOf("title" to "One","artist" to "Artist","album" to "Album"));ctx.scanner.scan()
            val ref=LibraryRef(ctx.db.read { string("SELECT id FROM track") }!!,"track")
            val target=ExternalTarget(DiscoverKind.Song,mapOf(DiscoverKind.Song.registryKey to mbid))
            assertNull(s.rejectMatch(user.id,target,ref,null,op()).wish)
            assertTrue(s.isRejected(user.id,target,ref));assertEquals(0,ctx.db.read { long("SELECT count(*) FROM wishlist_wish") })
        } finally {ctx.db.close()}
    }

}
