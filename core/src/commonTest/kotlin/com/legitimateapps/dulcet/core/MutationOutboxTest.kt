package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONF-84 (§16.20) and the §18.3 conflict rule, driven through the production [LibraryReader],
 * [MutationOutbox] and [LibraryFavourites] over a real database. Request counts and publication
 * sequences are asserted, never wall time.
 */
class MutationOutboxTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val album4 = LibraryEntityRef(LibraryEntityKind.Album, albumId(4))

    private class Opened(val session: LibraryReaderSession, val pubs: Recorder<LibraryPublication>, val handle: LibraryWindowHandle)

    private suspend fun TestScope.openGrid(env: SessionEnv, session: LibraryReaderSession = env.session()): Opened {
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "fixture: the grid is live")
        return Opened(session, pubs, handle)
    }

    private fun sends(env: SessionEnv) = env.server.log.filter { it.endpoint in setOf("star", "unstar", "setRating") }

    // ---- CONF-84 ------------------------------------------------------------------------------------

    @Test
    fun conf84AStarIsInThePublicationOfTheTapBeforeAnyRequest() = sessionTest { env ->
        val opened = openGrid(env)
        val before = env.server.log.size
        val publicationsBefore = opened.pubs.all.size
        assertEquals(MutationRecord.Pending, opened.session.favourites.setFavourite(album4, true))
        assertEquals(publicationsBefore + 1, opened.pubs.all.size, "the tap republishes synchronously")
        assertEquals(before, opened.pubs.all.last().requestsIssued, "the change is published before any request")
        assertEquals(true, opened.pubs.last.album(albumId(4)).starred)
        assertEquals(false, env.cache().album(albumId(4))?.record?.userState?.starred, "the overlay is never written into the cache")
        advanceUntilIdle()
        assertEquals(listOf(SessionTestServer.Request("star", mapOf("albumId" to albumId(4)))), sends(env))
        assertTrue(env.server.base.albums[4].starred)
    }

    @Test
    fun conf84ARevalidationThatLandsBeforeTheSendCompletesNeverRemovesTheStar() = sessionTest { env ->
        val opened = openGrid(env)
        env.server.holdBeforeApply += "star"
        val tapped = opened.pubs.all.size
        opened.session.favourites.setFavourite(album4, true)
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "fixture: the send is in flight and not yet applied")
        val mark = opened.pubs.all.size
        opened.handle.refresh()
        advanceUntilIdle()
        val during = opened.pubs.all.drop(mark).map { it.value }
        assertTrue(during.isNotEmpty(), "fixture: the revalidation published")
        assertTrue(during.any { it.freshness == LibraryFreshness.Live }, "fixture: the live read landed while the send was held")
        assertEquals(false, env.cache().album(albumId(4))?.record?.userState?.starred, "fixture: that read said not starred")
        assertTrue(during.all { it.album(albumId(4)).starred == true }, "no frame may show the star off")
        env.server.release()
        advanceUntilIdle()
        assertTrue(opened.pubs.all.drop(tapped).all { it.value.album(albumId(4)).starred == true },
            "from the tap on, every publication shows the star")
        assertEquals(0L, opened.session.favourites.pendingCount())
    }

    @Test
    fun conf84CompactionSendsOnlyTheLastValue() = sessionTest { env ->
        val opened = openGrid(env)
        opened.session.setOnline(false)
        val favourites = opened.session.favourites
        favourites.setFavourite(album4, true)
        favourites.setFavourite(album4, false)
        favourites.setFavourite(album4, true)
        val track = LibraryEntityRef(LibraryEntityKind.Track, "album-0004-track-0")
        favourites.setRating(track, 5)
        favourites.setRating(track, 2)
        assertEquals(2L, favourites.pendingCount(), "one row per (target, field)")
        opened.session.setOnline(true)
        opened.session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(
            listOf(
                SessionTestServer.Request("star", mapOf("albumId" to albumId(4))),
                SessionTestServer.Request("setRating", mapOf("id" to "album-0004-track-0", "rating" to "2")),
            ),
            sends(env),
        )
    }

    @Test
    fun conf84StarThenUnstarOfAnUnstarredAlbumLeavesNothingToSend() = sessionTest { env ->
        val opened = openGrid(env)
        opened.session.setOnline(false)
        val favourites = opened.session.favourites
        assertEquals(MutationRecord.Pending, favourites.setFavourite(album4, true))
        assertEquals(true, opened.pubs.last.album(albumId(4)).starred)
        assertEquals(MutationRecord.CompactedAway, favourites.setFavourite(album4, false))
        assertEquals(false, opened.pubs.last.album(albumId(4)).starred)
        assertEquals(0L, favourites.pendingCount())
        opened.session.setOnline(true)
        opened.session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(emptyList(), sends(env))
    }

    @Test
    fun conf84TheEchoIsAdoptedAndAReadAnsweredBeforeItCannotUndoIt() = sessionTest { env ->
        val opened = openGrid(env)
        // A revalidation is answered NOW (not starred) and delivered only after the star is saved.
        env.server.holdAfterAnswer += "getAlbumList2"
        opened.handle.refresh()
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "fixture: an older answer is in flight")
        env.server.holdAfterAnswer.clear()
        val outcomes = mutableListOf<MutationOutcome>()
        opened.session.favourites.addOutcomeListener { outcomes += it }
        val tapped = opened.pubs.all.size
        opened.session.favourites.setFavourite(album4, true)
        advanceUntilIdle()
        assertEquals(listOf<MutationOutcome>(MutationOutcome.Saved(album4, MutationField.Starred, 1)), outcomes)
        assertEquals(0L, opened.session.favourites.pendingCount(), "the acknowledged row is removed")
        assertEquals(true, env.cache().album(albumId(4))?.record?.userState?.starred, "the echo is adopted into the cache")
        env.server.release()
        advanceUntilIdle()
        assertEquals(true, env.cache().album(albumId(4))?.record?.userState?.starred,
            "a read issued before the acknowledgement must not overwrite the adopted value")
        assertEquals(true, opened.pubs.last.album(albumId(4)).starred)
        assertTrue(opened.pubs.all.drop(tapped).all { it.value.album(albumId(4)).starred == true },
            "no frame after the tap shows the star off")
    }

    // ---- §18.3 conflict rule ------------------------------------------------------------------------

    /** Rates album 4 at [base] on the server and reads it, then rates it 5 offline. */
    private suspend fun TestScope.offlineRatingOver(env: SessionEnv, base: Int): Opened {
        env.server.ratings[albumId(4)] = base
        val opened = openGrid(env)
        assertEquals(base, opened.pubs.last.album(albumId(4)).userRating, "fixture: the server's rating was read")
        opened.session.setOnline(false)
        opened.session.favourites.setRating(album4, 5)
        assertEquals(5, opened.pubs.last.album(albumId(4)).userRating)
        return opened
    }

    /** Back online, a read of the album is issued AFTER the change and lands before the flush. */
    private suspend fun TestScope.readBeforeFlush(opened: Opened) {
        opened.session.setOnline(true)
        opened.handle.refresh()
        advanceUntilIdle()
        opened.session.reader.reconnect()
        advanceUntilIdle()
    }

    @Test
    fun conflictAServerValueChangedAfterTheChangeWins() = sessionTest { env ->
        val opened = offlineRatingOver(env, base = 3)
        val outcomes = mutableListOf<MutationOutcome>()
        opened.session.favourites.addOutcomeListener { outcomes += it }
        env.server.ratings[albumId(4)] = 4 // changed elsewhere
        readBeforeFlush(opened)
        assertEquals(emptyList(), sends(env), "the server's newer value wins; nothing is sent")
        assertEquals(listOf<MutationOutcome>(MutationOutcome.Superseded(album4, MutationField.Rating, 4)), outcomes)
        assertEquals(4, opened.pubs.last.album(albumId(4)).userRating)
        assertEquals(0L, opened.session.favourites.pendingCount())
    }

    @Test
    fun conflictAnUnchangedServerValueLeavesTheLocalChangeTheNewest() = sessionTest { env ->
        val opened = offlineRatingOver(env, base = 3)
        readBeforeFlush(opened)
        assertEquals(listOf(SessionTestServer.Request("setRating", mapOf("id" to albumId(4), "rating" to "5"))), sends(env))
        assertEquals(5, opened.pubs.last.album(albumId(4)).userRating)
    }

    @Test
    fun conflictAServerAlreadyHoldingTheValueIsAdoptedWithoutSending() = sessionTest { env ->
        val opened = offlineRatingOver(env, base = 3)
        env.server.ratings[albumId(4)] = 5
        readBeforeFlush(opened)
        assertEquals(emptyList(), sends(env))
        assertEquals(5, opened.pubs.last.album(albumId(4)).userRating)
        assertEquals(0L, opened.session.favourites.pendingCount())
    }

    @Test
    fun conflictAChangeWithNoLaterReadIsSentFirstOnReconnect() = sessionTest { env ->
        val opened = offlineRatingOver(env, base = 3)
        env.server.ratings[albumId(4)] = 4
        val before = env.server.log.size
        opened.session.setOnline(true)
        opened.session.reader.reconnect()
        advanceUntilIdle()
        assertEquals("setRating", env.server.log[before].endpoint, "user-authored data is flushed before the epoch read (§16.14)")
        assertEquals("getScanStatus", env.server.log[before + 1].endpoint)
        assertEquals(5, env.server.ratings[albumId(4)])
    }

    @Test
    fun conflictRuleDecisionTable() {
        fun change(value: Int, base: Int?, attempted: Boolean = false) =
            PendingMutation(album4, MutationField.Rating, value, base, attempted, localSequence = 10, wallClock = 0)
        assertEquals(DeliveryDecision.Send, decideDelivery(change(5, 3), serverValue = 4, serverReadIssueSeq = 9), "read before the change")
        assertEquals(DeliveryDecision.Send, decideDelivery(change(5, 3), serverValue = 4, serverReadIssueSeq = 10))
        assertEquals(DeliveryDecision.ServerWins(4), decideDelivery(change(5, 3), serverValue = 4, serverReadIssueSeq = 11))
        assertEquals(DeliveryDecision.Send, decideDelivery(change(5, 3), serverValue = 3, serverReadIssueSeq = 11))
        assertEquals(DeliveryDecision.AlreadyApplied, decideDelivery(change(5, 3), serverValue = 5, serverReadIssueSeq = 11))
        assertEquals(DeliveryDecision.Send, decideDelivery(change(5, null), serverValue = 4, serverReadIssueSeq = 11), "unknown base never loses")
        assertEquals(DeliveryDecision.Send, decideDelivery(change(5, 3, attempted = true), serverValue = 4, serverReadIssueSeq = 11),
            "a possibly-delivered change never loses to what may be its own echo")
        assertEquals(DeliveryDecision.Send, decideDelivery(change(5, 3), serverValue = null, serverReadIssueSeq = 11))
    }

    // ---- Delivery -----------------------------------------------------------------------------------

    @Test
    fun aLostAnswerIsSentAgainAndOrderIsKept() = sessionTest { env ->
        val opened = openGrid(env)
        val favourites = opened.session.favourites
        env.server.applyThenLose += "star"
        favourites.setFavourite(album4, true)
        advanceUntilIdle()
        assertTrue(env.server.base.albums[4].starred, "fixture: the server applied it")
        assertEquals(1L, favourites.pendingCount(), "an answer that never arrived leaves the change pending")
        assertEquals(true, opened.pubs.last.album(albumId(4)).starred)

        // A later change queues behind it and is not sent past it.
        val track = LibraryEntityRef(LibraryEntityKind.Track, "album-0004-track-1")
        favourites.setRating(track, 4)
        advanceUntilIdle()
        assertEquals(listOf("star", "star"), sends(env).map { it.endpoint }, "the older change is retried first and stops the flush")

        env.server.applyThenLose.clear()
        val report = favourites.flush()
        assertEquals(listOf("star", "star", "star", "setRating"), sends(env).map { it.endpoint },
            "at-least-once: the server received the same star three times")
        assertEquals(2, report.saved)
        assertNull(report.stoppedBy)
        assertEquals(0L, favourites.pendingCount())
    }

    @Test
    fun aPossiblyDeliveredChangeIsNeverCompactedAway() = sessionTest { env ->
        val opened = openGrid(env)
        val favourites = opened.session.favourites
        env.server.applyThenLose += "star"
        favourites.setFavourite(album4, true)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        opened.session.setOnline(false)
        assertEquals(MutationRecord.Pending, favourites.setFavourite(album4, false),
            "the star may be on the server, so the unstar must be sent")
        opened.session.setOnline(true)
        opened.session.reader.reconnect()
        advanceUntilIdle()
        assertEquals("unstar", sends(env).last().endpoint)
        assertFalse(env.server.base.albums[4].starred)
    }

    @Test
    fun aRefusedChangeIsRemovedAndThePersonIsTold() = sessionTest { env ->
        val opened = openGrid(env)
        env.server.failWithCode["star"] = 70
        val outcomes = mutableListOf<MutationOutcome>()
        opened.session.favourites.addOutcomeListener { outcomes += it }
        opened.session.favourites.setFavourite(album4, true)
        assertEquals(true, opened.pubs.last.album(albumId(4)).starred)
        advanceUntilIdle()
        val refused = assertIs<MutationOutcome.NotSaved>(outcomes.single())
        assertEquals(DomainError.Server.Known(70), refused.error)
        assertEquals(false, opened.pubs.last.album(albumId(4)).starred, "the server's value shows again")
        assertEquals(0L, opened.session.favourites.pendingCount())
    }

    @Test
    fun aTransientFailureKeepsTheChange() = sessionTest { env ->
        val opened = openGrid(env)
        env.server.failWithCode["star"] = 0 // the reference server's generic, busy-style error
        opened.session.favourites.setFavourite(album4, true)
        advanceUntilIdle()
        assertEquals(1L, opened.session.favourites.pendingCount())
        assertEquals(true, opened.pubs.last.album(albumId(4)).starred)
    }

    @Test
    fun theOutboxSurvivesARelaunchAndIsOverlaidBeforeAnyRequest() = sessionTest { env ->
        val first = openGrid(env)
        first.session.setOnline(false)
        first.session.favourites.setFavourite(album4, true)
        first.handle.close()

        val relaunched = env.session() // new reader, outbox and favourites over the same database
        val pubs = Recorder<LibraryPublication>(env.server)
        val before = env.server.log.size
        relaunched.reader.open(grid, pubs)
        assertEquals(before, pubs.all.first().requestsIssued)
        assertEquals(true, pubs.all.first().value.album(albumId(4)).starred, "the first publication after relaunch overlays it")
        relaunched.reader.reconnect()
        advanceUntilIdle()
        assertEquals(listOf("star"), sends(env).map { it.endpoint })
        assertEquals(0L, relaunched.favourites.pendingCount())
    }

    @Test
    fun theQueueHoldsNoCredentialAndNothingButTheChange() = sessionTest { env ->
        val canary = CacheBinding("server:session", "https://canary-host.example/secret-path", "canary-user")
        val session = env.session(binding = canary)
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        session.favourites.setRating(LibraryEntityRef(LibraryEntityKind.Artist, "artist-1"), 3)
        val rows = env.driver.executeQuery(null, "SELECT server_id, target_id, field, value FROM mutation_outbox ORDER BY local_sequence", { cursor ->
            val out = mutableListOf<List<String>>()
            while (cursor.next().value) out += (0..3).map { cursor.getString(it)!! }
            QueryResult.Value(out)
        }, 0).value
        assertEquals(
            listOf(
                listOf("server:session", albumId(4), "starred", """{"kind":"album","value":1,"attempted":false}"""),
                listOf("server:session", "artist-1", "rating", """{"kind":"artist","value":3,"attempted":false}"""),
            ),
            rows,
        )
        val all = rows.flatten().joinToString("|")
        listOf("canary", "secret", "https", "listener").forEach { assertFalse(it in all, "the queue carries '$it'") }
    }

    @Test
    fun aRebindingDiscardsChangesAuthoredAsTheOtherAccount() = sessionTest { env ->
        val first = env.session()
        first.setOnline(false)
        first.favourites.setFavourite(album4, true)
        assertEquals(1L, first.favourites.pendingCount())
        val rebound = env.session(binding = CacheBinding(SessionEnv.BINDING.serverId, "https://music.example", "someone-else"))
        assertEquals(0L, rebound.favourites.pendingCount(), "changes are never sent as a different user")
    }

    // ---- The favourites API -------------------------------------------------------------------------

    @Test
    fun favouritesReadAsTheyArePublishedAndToggle() = sessionTest { env ->
        val opened = openGrid(env)
        val favourites = opened.session.favourites
        opened.session.setOnline(false)
        assertEquals(false, favourites.isFavourite(album4))
        assertTrue(favourites.toggleFavourite(album4))
        assertEquals(true, favourites.isFavourite(album4))
        assertEquals(MutationRecord.Unchanged, LibraryFavourites(opened.session.reader, opened.session.outbox)
            .let { it.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(5)), false) },
            "unfavouriting what is not a favourite queues nothing")
        val unknown = LibraryEntityRef(LibraryEntityKind.Track, "never-seen")
        assertNull(favourites.isFavourite(unknown))
        assertTrue(favourites.toggleFavourite(unknown), "an unknown state toggles to favourite")
        favourites.setRating(album4, 4)
        assertEquals(4, favourites.rating(album4))
    }

    @Test
    fun searchRowsCarryTheFavouriteOverlayInTheSamePublication() = sessionTest { env ->
        val opened = openGrid(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = opened.session.openSearch(listener = pubs)
        search.updateQuery("Album 0004")
        assertEquals(false, pubs.last.rows.single().favourite)
        val count = pubs.all.size
        opened.session.favourites.setFavourite(album4, true)
        assertEquals(count + 1, pubs.all.size)
        assertEquals(true, pubs.last.rows.single().favourite)
    }
}
