package com.legitimateapps.dulcet.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The independent review of `feat/playlists-core` @ e3188155 (spec §18.6): one or more tests per
 * finding. A test that describes a fix was seen failing before it; a test named after a reviewer's
 * mutant (`r1…`, `r11…`) kills that mutant, which the earlier suite let survive.
 */
class PlaylistEditingReviewTest {
    // ---- Blocker 1: a cancelled lost create never deletes on a heuristic ---------------------------

    /** A create that was sent, whose answer was lost, then deleted here: the cancelled row. */
    private suspend fun TestScope.cancelledLostCreate(
        env: PlaylistEnv,
        name: String,
        songs: List<String>,
        landed: Boolean,
    ): Pair<LibraryReaderSession, String> {
        val session = env.session()
        if (landed) env.server.applyThenLose += "createPlaylist" else env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val localId = assertNotNull(session.playlists.create(name, songs).localId)
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"), "the create was sent once")
        env.server.applyThenLose.clear()
        env.server.failWithError.clear()
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(localId), "a create in doubt is cancelled, not dropped")
        env.clock.now += 1_000
        return session to localId
    }

    @Test
    fun aCancelledLostCreateDeletesOnlyItsProvenOwnPlaylist() = playlistTest { env ->
        // The positive control every negative below stands on: one candidate passing every test.
        val (session, _) = cancelledLostCreate(env, "Road", listOf("song-1", "song-2"), landed = true)
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(env.server.playlists.isEmpty(), "the proven create was deleted: ${env.server.playlists.map { it.name }}")
        assertEquals(1, env.server.count("deletePlaylist"))
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated })
    }

    @Test
    fun aPlaylistCreatedBeforeTheAttemptIsNeverDeleted() = playlistTest { env ->
        // The probe's case: an empty "New Playlist" made elsewhere earlier, never seen here.
        val older = env.server.add("New Playlist", emptyList(), created = env.clock.now - 60_000)
        val (session, localId) = cancelledLostCreate(env, "New Playlist", emptyList(), landed = false)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf(older.id), env.server.playlists.map { it.id })
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "New Playlist"), env.outcomes.last())
    }

    @Test
    fun twoCandidatesAreNeverDeleted() = playlistTest { env ->
        val (session, localId) = cancelledLostCreate(env, "New Playlist", emptyList(), landed = true)
        env.server.add("New Playlist", emptyList()) // another device, after the attempt
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(2, env.server.playlists.size)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "New Playlist"), env.outcomes.last())
    }

    @Test
    fun aCandidateWithOtherEntriesIsNeverDeleted() = playlistTest { env ->
        val (session, _) = cancelledLostCreate(env, "Road", listOf("song-1"), landed = false)
        env.server.add("Road", listOf("song-1", "song-9"))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size)
        assertEquals(0, env.server.count("deletePlaylist"))
    }

    @Test
    fun aCandidateWithTheSameCountButOtherSongsIsNeverDeleted() = playlistTest { env ->
        // A song count is not an identity: the entries themselves must be the ones sent.
        val (session, localId) = cancelledLostCreate(env, "Road", listOf("song-1"), landed = false)
        env.server.add("Road", listOf("song-2"))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "Road"), env.outcomes.last())
    }

    @Test
    fun aCandidateOfAnotherOwnerIsNeverDeleted() = playlistTest { env ->
        val (session, _) = cancelledLostCreate(env, "Road", listOf("song-1"), landed = false)
        env.server.add("Road", listOf("song-1"), owner = "someone-else", isPublic = true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated }, "another user's playlist cannot be this create: nothing to tell")
    }

    @Test
    fun aCandidateWhoseOwnerTheServerDoesNotStateIsNeverDeleted() = playlistTest { env ->
        // Ownership is part of the proof: a server that does not state it cannot supply it.
        val (session, localId) = cancelledLostCreate(env, "Road", listOf("song-1"), landed = true)
        env.server.omitOwner = true
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "Road"), env.outcomes.last())
    }

    @Test
    fun aCandidateOfAnotherNameIsNeverDeleted() = playlistTest { env ->
        val (session, _) = cancelledLostCreate(env, "Road", listOf("song-1"), landed = false)
        env.server.add("Road 2", listOf("song-1"))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated }, "no playlist of that name: nothing to tell")
    }

    @Test
    fun aCancelledCreateWhoseSongsTheServerDroppedIsToldNotDeleted() = playlistTest { env ->
        // Adopting may allow a dropped unknown id; an irreversible delete requires identical entries.
        val (session, localId) = cancelledLostCreate(env, "Odd", listOf("song-1", "song-unknown"), landed = true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1"), env.server.playlists.single().entries)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "Odd"), env.outcomes.last())
    }

    // ---- 2: the lost-create search finds the normal cases -----------------------------------------------

    @Test
    fun aLostCreateIsFoundAfterTheListWasReadAgain() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        env.open(session, LibraryQuery.Playlists) // the list read caches the new playlist: "seen"
        advanceUntilIdle()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size, "found, not duplicated")
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id), env.outcomes.last())
    }

    @Test
    fun aLostCreateWithADroppedUnknownSongIsFound() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        session.playlists.create("Once", listOf("song-1", "song-unknown", "song-2"))
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size, "found, not duplicated")
        assertEquals(1, env.server.count("createPlaylist"))
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last())
        assertEquals(listOf("song-1", "song-2"), diverged.serverEntries)
    }

    @Test
    fun anEmptyNamesakeIsNotAdoptedForACreateThatCarriedSongs() = playlistTest { env ->
        // A create that never arrived, and an empty playlist of its name made after it: that
        // playlist holds none of the songs sent, so it proves nothing and the create is sent again.
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        env.clock.now += 1_000
        val namesake = env.server.add("Road", emptyList())
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(2, env.server.count("createPlaylist"))
        assertEquals(emptyList(), namesake.entries, "the namesake is untouched")
        val created = assertIs<PlaylistEditOutcome.Created>(env.outcomes.last { it is PlaylistEditOutcome.Created })
        assertEquals(localId, created.localId)
        assertEquals(listOf("song-1"), env.server.playlists.single { it.id == created.playlistId }.entries)
    }

    // ---- 3: removals send verified positions; a whole list reports what it can see -------------------------

    @Test
    fun removingSeveralSendsTheirPositionsDescending() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.log.clear()
        session.playlists.remove(p.id, setOf(0, 2), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("2", "0"), env.server.writes().single().all("songIndexToRemove"))
        assertEquals(listOf("getPlaylist", "updatePlaylist", "getPlaylist"), env.server.log.map { it.endpoint })
        assertEquals(listOf("song-2", "song-4"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun aConcurrentAppendDuringARemovalIsKept() = playlistTest { env ->
        // What a whole-list replace would have lost: another client's song added in the window.
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "updatePlaylist") p.entries += "song-9" }
        session.playlists.remove(p.id, setOf(1), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-3", "song-9"), p.entries, "the other client's song-9 survives")
        // Not exactly the intended ids went missing with nothing else changed: told, with the server's list.
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last())
        assertEquals(listOf("song-1", "song-3", "song-9"), diverged.serverEntries)
    }

    @Test
    fun aRemovalRacedInTheWindowIsReportedWithTheServersList() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "updatePlaylist") p.entries.removeAt(0) }
        session.playlists.remove(p.id, setOf(2), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last())
        assertEquals(p.entries, diverged.serverEntries)
        assertEquals(p.entries, detail.last.items.map { it.rawId }, "the person sees the server's list")
    }

    @Test
    fun aHeaderChangeInAMovesWindowIsReported() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "createPlaylist") p.name = "Renamed elsewhere" }
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries)
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last())
        assertEquals(listOf("song-3", "song-1", "song-2"), diverged.serverEntries)
    }

    @Test
    fun anEntryChangeInAMovesWindowIsOverwrittenAndCannotBeSeen() = playlistTest { env ->
        // The residual exposure §18.6 states, pinned so it is never described as detected.
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "createPlaylist") p.entries += "song-9" }
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries, "the other client's song-9 is lost")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    // ---- 4: one concurrency slot from the re-read to the write -------------------------------------------

    @Test
    fun theReReadAndTheWriteHoldOneSlot() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val other = env.server.add("Other", listOf("song-4"))
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, serverConcurrency = 1, lookAheadInFlight = 1))
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        val otherDetail = env.open(session, LibraryQuery.Playlist(other.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        session.setOnline(true)
        env.server.log.clear()
        launch { session.playlists.flush() }
        otherDetail.handle.refresh() // competes for the one slot
        advanceUntilIdle()
        val endpoints = env.server.log.map { it.endpoint + ":" + (it.one("id") ?: it.one("playlistId")) }
        val read = endpoints.indexOf("getPlaylist:${p.id}")
        assertTrue(read >= 0 && endpoints.contains("getPlaylist:${other.id}"), "both reads ran: $endpoints")
        assertEquals("createPlaylist:${p.id}", endpoints[read + 1], "nothing between the re-read and the write: $endpoints")
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries)
    }

    // ---- 5: this device's own in-doubt edit is not "changed elsewhere" -----------------------------------

    @Test
    fun anAppendInDoubtWithADroppedUnknownSongIsNotSentAgain() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-2", "song-unknown"))
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("updatePlaylist"))
        assertEquals(listOf("song-1", "song-2"), p.entries)
        assertEquals(listOf("song-1", "song-2"), assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last()).serverEntries)
    }

    @Test
    fun anAppendInDoubtThenAMoveIsThisDevicesOwnListNotAChangeElsewhere() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-3"))
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "the append landed; its answer did not")
        env.server.applyThenLose.clear()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId }))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
        assertEquals(1, env.server.count("updatePlaylist"), "the append was not sent again")
    }

    @Test
    fun anAppendInDoubtThenAnInsertAtTheEndSendsOnlyTheNewSong() = playlistTest { env ->
        // An insert at the end is recorded as a positional edit; the append in doubt must survive it.
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "the append landed; its answer did not")
        env.server.applyThenLose.clear()
        session.setOnline(false)
        val view = detail.last.items.map { it.rawId }
        assertEquals(PlaylistEditRecord.Pending, session.playlists.insert(p.id, listOf("song-4"), view.size, view))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3", "song-4"), p.entries, "the landed append is not sent again")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun anAppendInDoubtThenAnInsertAmongItsSongsIsWrittenAgainstTheLandedList() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.insert(p.id, listOf("song-4"), 2, detail.last.items.map { it.rawId }))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-4", "song-3"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun anEditOverAnAppendSentOntoAListChangedElsewhereMeetsThatChange() = playlistTest { env ->
        // The append went onto another client's reorder; a later move made on this device's older
        // view must not be taken as this device's own list and written over that reorder.
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        p.entries.add(0, p.entries.removeAt(1)) // another client: [song-2, song-1]
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-3"))
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-1", "song-3"), p.entries, "fixture: the append landed onto the reorder")
        env.server.applyThenLose.clear()
        session.setOnline(false)
        val view = detail.last.items.map { it.rawId }
        assertEquals(listOf("song-1", "song-2", "song-3"), view, "fixture: this device's view predates the reorder")
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(p.id, 2, 0, view))
        env.server.log.clear()
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(emptyList(), env.server.writes().map { it.endpoint })
        assertEquals(PlaylistEditOutcome.ChangedElsewhere(p.id, listOf("song-2", "song-1", "song-3")), env.outcomes.last())
    }

    @Test
    fun anAppendInDoubtThatNeverArrivedIsSentAgain() = playlistTest { env ->
        // A server holding the list the append was sent onto is not "the append, all ids dropped".
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithError["updatePlaylist"] = DomainError.Transport.Timeout
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        assertEquals(listOf("song-1"), p.entries, "fixture: the append never arrived, and nothing proves it")
        env.server.failWithError.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun anInsertInDoubtThatNeverArrivedIsWrittenAgain() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        session.playlists.insert(p.id, listOf("song-9"), 1, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "fixture: the write never arrived, and nothing proves it")
        env.server.failWithError.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-9", "song-2", "song-3"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun aWholeListInDoubtWithADroppedUnknownSongIsNotChangedElsewhere() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        session.playlists.insert(p.id, listOf("song-unknown"), 3, detail.last.items.map { it.rawId })
        env.server.applyThenLose += "createPlaylist"
        session.setOnline(true)
        session.playlists.flush()
        env.server.applyThenLose.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"))
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last(), "${env.outcomes}")
        assertEquals(listOf("song-3", "song-1", "song-2"), diverged.serverEntries)
    }

    /** A removal of the last entry lands with its answer lost; [elsewhere] then changes the list. */
    private suspend fun TestScope.inDoubtRemovalThenChangedElsewhere(env: PlaylistEnv, elsewhere: (MutableList<String>) -> Unit) {
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.remove(p.id, setOf(3), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "fixture: the removal landed, its answer did not")
        env.server.applyThenLose.clear()
        elsewhere(p.entries)
        val changed = p.entries.toList()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.remove(p.id, setOf(2), detail.last.items.map { it.rawId }))
        env.server.log.clear()
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        // Not this device's own write less dropped ids: another client's change, never written over.
        assertEquals(emptyList(), env.server.writes().map { it.endpoint }, "nothing written over another client's change")
        assertEquals(changed, p.entries)
        assertEquals(PlaylistEditOutcome.ChangedElsewhere(p.id, changed), env.outcomes.last())
    }

    @Test
    fun aListReorderedElsewhereAfterAnInDoubtWriteIsNotTakenForThatWrite() = playlistTest { env ->
        inDoubtRemovalThenChangedElsewhere(env) { it.add(0, it.removeAt(1)) }
    }

    @Test
    fun anEntryRemovedElsewhereAfterAnInDoubtWriteIsNotTakenForADroppedId() = playlistTest { env ->
        // Only ids THIS device added can have been dropped by the server; song-1 was already there.
        inDoubtRemovalThenChangedElsewhere(env) { it.remove("song-1") }
    }

    @Test
    fun aGatewayErrorAfterTheWriteLandedStillLeavesItThisDevicesOwn() = playlistTest { env ->
        // A 502 does not prove the request never reached the server, so the record of the write stays.
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenStatus["createPlaylist"] = 502
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries)
        env.server.applyThenStatus.clear()
        session.setOnline(false)
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-3", "song-1"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    // ---- 6: large playlists and statuses without an envelope ---------------------------------------------

    @Test
    fun aProxysHttp414IsANamedRefusalNotAMalformedEnvelope() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.httpStatus["createPlaylist"] = 414
        session.playlists.move(p.id, 1, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        val refused = assertIs<PlaylistEditOutcome.NotSaved>(env.outcomes.last())
        assertEquals(DomainError.Server.HttpStatus(414), refused.error)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aGatewayThatCannotReachTheServerStopsTheFlushAndKeepsTheChange() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 503
        val report = session.playlists.flush()
        assertEquals(DomainError.Server.HttpStatus(503), report.stoppedBy)
        assertEquals(1, report.stillPending)
        env.server.httpStatus.clear()
        session.playlists.flush()
        assertEquals("Evening", p.name)
    }

    @Test
    fun aServerErrorWithoutAnEnvelopeIsRetriedAndTheFlushMovesOn() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 500
        val report = session.playlists.flush()
        assertEquals(null, report.stoppedBy, "a 500 is this change's failure, not an unreachable server")
        assertEquals(1, report.deferred)
        assertEquals(1, report.stillPending)
    }

    @Test
    fun withFormPostALongReorderIsOneFormBodyInOrder() = playlistTest { env ->
        val all = (1..2_500).map { "song-${(it * 7) % 2_900 + 1}" }
        val p = env.server.add("Long", all)
        val session = env.session(formPost = true)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.playlists.move(p.id, 2_499, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        val write = env.server.writes().single()
        assertTrue(write.formPost)
        assertEquals(listOf(all.last()) + all.dropLast(1), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun withoutFormPostALongAppendIsSentInBatchesInOrder() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session(formPost = false)
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.urlLengths.clear()
        val many = (100..899).map { "song-$it" }
        session.playlists.append(p.id, many)
        advanceUntilIdle()
        assertEquals(listOf("song-1") + many, p.entries)
        assertTrue(env.server.count("updatePlaylist") > 1, "batched")
        assertTrue(env.server.writes().none { it.formPost })
        assertTrue(env.server.urlLengths.all { it <= PlaylistEditor.QUERY_BUDGET_BYTES }, "${env.server.urlLengths.max()}")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun aBatchedAppendInterruptedAfterItsFirstBatchNeverSendsThatBatchAgain() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session(formPost = false)
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = {
            // The first batch lands; the next request never reaches the server.
            env.server.beforeWrite = {}
            env.server.failWithError["updatePlaylist"] = DomainError.Transport.Unreachable
        }
        val many = (100..899).map { "song-$it" }
        session.playlists.append(p.id, many)
        advanceUntilIdle()
        val landed = p.entries.size - 1
        assertTrue(landed in 1 until many.size, "fixture: exactly the first batch landed ($landed)")
        env.server.failWithError.clear()
        session.playlists.flush()
        assertEquals(listOf("song-1") + many, p.entries, "every song once, in order")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun aMoveAfterAnInterruptedBatchedAppendCountsTheLandedBatchAsThisDevicesOwn() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session(formPost = false)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = {
            env.server.beforeWrite = {}
            env.server.failWithError["updatePlaylist"] = DomainError.Transport.Unreachable
        }
        // Two append batches, yet a whole list that still fits one query string.
        val many = (100..499).map { "song-$it" }
        session.playlists.append(p.id, many)
        advanceUntilIdle()
        assertTrue(p.entries.size in 2 until many.size + 1, "fixture: only the first batch landed")
        env.server.failWithError.clear()
        session.setOnline(false)
        val view = detail.last.items.map { it.rawId }
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(p.id, 0, view.lastIndex, view))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(many + "song-1", p.entries, "the landed batch is this device's own, not a change elsewhere")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun withoutFormPostALongRemovalIsSentInDescendingBatchesEachVerified() = playlistTest { env ->
        val all = (100..899).map { "song-$it" }
        val p = env.server.add("Mix", all)
        val session = env.session(formPost = false)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.log.clear()
        env.server.urlLengths.clear()
        session.playlists.remove(p.id, (0 until 700).toSet(), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(all.drop(700), p.entries)
        val batches = env.server.writes()
        assertTrue(batches.size > 1, "batched")
        assertEquals((699 downTo 0).map(Int::toString), batches.flatMap { it.all("songIndexToRemove") })
        // Every batch is preceded by a read that verified the list it names positions in.
        val endpoints = env.server.log.map { it.endpoint }
        endpoints.forEachIndexed { index, endpoint -> if (endpoint == "updatePlaylist") assertEquals("getPlaylist", endpoints[index - 1]) }
        assertTrue(env.server.urlLengths.all { it <= PlaylistEditor.QUERY_BUDGET_BYTES })
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun withoutFormPostALongReorderIsRefusedWithAReason() = playlistTest { env ->
        val all = (100..899).map { "song-$it" }
        val p = env.server.add("Mix", all)
        val session = env.session(formPost = false)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.playlists.move(p.id, 5, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        val refused = assertIs<PlaylistEditOutcome.NotSaved>(env.outcomes.last())
        assertEquals(DomainError.CapabilityUnsupported(CapabilityFeature.PlaylistWholeListWrite), refused.error)
        assertTrue(env.server.writes().isEmpty())
        assertEquals(all, p.entries)
        assertEquals(all, detail.last.items.map { it.rawId }, "the person sees the server's list again")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun withoutFormPostALongCreateIsCreatedThenAppendedInBatches() = playlistTest { env ->
        val many = (100..899).map { "song-$it" }
        val session = env.session(formPost = false)
        val localId = assertNotNull(session.playlists.create("Big", many).localId)
        advanceUntilIdle()
        val p = env.server.playlists.single()
        assertEquals(many, p.entries)
        assertEquals(1, env.server.count("createPlaylist"))
        assertTrue(env.server.count("updatePlaylist") > 1)
        assertTrue(env.server.urlLengths.all { it <= PlaylistEditor.QUERY_BUDGET_BYTES }, "${env.server.urlLengths.max()}")
        assertEquals(PlaylistEditOutcome.Created(localId, p.id), env.outcomes.first())
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.Diverged || it is PlaylistEditOutcome.NotSaved }, "${env.outcomes}")
        assertEquals(0L, session.playlists.pendingCount())
    }

    // ---- 7: the reviewer's surviving mutants -----------------------------------------------------------------

    @Test
    fun r1ASameSizeReorderElsewhereIsAChangedList() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.move(p.id, 0, 2, detail.last.items.map { it.rawId })
        p.entries.reverse()
        session.setOnline(true)
        env.server.log.clear()
        session.playlists.flush()
        assertIs<PlaylistEditOutcome.ChangedElsewhere>(env.outcomes.last())
        assertEquals(listOf("song-3", "song-2", "song-1"), p.entries)
        assertTrue(env.server.writes().isEmpty())
    }

    @Test
    fun r7AServerThatOmitsReadonlyEditsOnlyWhatThisAccountOwns() = playlistTest { env ->
        env.server.omitReadonly = true
        val mine = env.server.add("Mine", listOf("song-1"))
        val theirs = env.server.add("Theirs", listOf("song-1"), owner = "someone-else", isPublic = true)
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        advanceUntilIdle()
        assertTrue(list.last.playlist(mine.id).editable)
        assertFalse(list.last.playlist(theirs.id).editable)
        assertEquals("someone-else", list.last.playlist(theirs.id).owner)
        assertEquals(PlaylistEditRecord.NotEditable, session.playlists.rename(theirs.id, "x"))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.rename(mine.id, "y"))
    }

    @Test
    fun r8AnUndoOfAnEditInDoubtIsStillSent() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "createPlaylist"
        session.playlists.move(p.id, 1, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(p.id, 1, 0, detail.last.items.map { it.rawId }), "an undo over a send in doubt is kept")
        session.setOnline(true)
        session.playlists.flush()
        assertEquals(listOf("song-1", "song-2"), p.entries, "the undo reached the server")
    }

    @Test
    fun r10ACreateWhoseSongsTheServerDroppedIsTold() = playlistTest { env ->
        val session = env.session()
        session.playlists.create("Odd", listOf("song-1", "song-unknown"))
        advanceUntilIdle()
        assertIs<PlaylistEditOutcome.Created>(env.outcomes[0])
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes[1])
        assertEquals(PlaylistRowKind.Create, diverged.kind)
        assertEquals(listOf("song-1"), diverged.serverEntries)
    }

    @Test
    fun r11AHeaderTheServerDidNotApplyIsTold() = playlistTest { env ->
        env.server.ignoreComment = true
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.playlists.setComment(p.id, "never kept")
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.Diverged(p.id, PlaylistRowKind.Details, null), env.outcomes.last())
    }

    // ---- Adjacent: an edit made while its playlist's create is in flight -------------------------------------

    @Test
    fun anEditWhileTheCreateIsInFlightIsSentAsAnEditNotASecondCreate() = playlistTest { env ->
        val session = env.session()
        var localId = ""
        env.server.beforeWrite = {
            if (it.endpoint == "createPlaylist") {
                env.server.beforeWrite = {}
                assertEquals(PlaylistEditRecord.Pending, session.playlists.append(localId, listOf("song-2")))
                assertEquals(PlaylistEditRecord.Pending, session.playlists.rename(localId, "Renamed"))
            }
        }
        localId = assertNotNull(session.playlists.create("Fresh", listOf("song-1")).localId)
        advanceUntilIdle()
        val p = env.server.playlists.single()
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(listOf("song-1", "song-2"), p.entries)
        assertEquals("Renamed", p.name)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aDeleteWhileTheCreateIsInFlightDeletesWhatTheCreateMade() = playlistTest { env ->
        val session = env.session()
        var localId = ""
        env.server.beforeWrite = {
            if (it.endpoint == "createPlaylist") {
                env.server.beforeWrite = {}
                assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(localId))
            }
        }
        localId = assertNotNull(session.playlists.create("Fleeting", listOf("song-1")).localId)
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"))
        assertTrue(env.server.playlists.isEmpty(), "the create landed, and the delete made during it followed")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun editsWhileACreateIsInDoubtFollowTheCreateTheServerHolds() = playlistTest { env ->
        // The proof looks for what was SENT, not for the playlist as edited here since.
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.rename(localId, "Street"))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.append(localId, listOf("song-2")))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        val p = env.server.playlists.single()
        assertEquals(1, env.server.count("createPlaylist"), "found, not created again under the new name")
        assertEquals("Street", p.name)
        assertEquals(listOf("song-1", "song-2"), p.entries)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aCreateRenamedThenDeletedWhileInDoubtDeletesWhatWasSent() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.setOnline(false)
        session.playlists.rename(localId, "Street")
        session.playlists.append(localId, listOf("song-2"))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(localId))
        env.clock.now += 1_000
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(env.server.playlists.isEmpty(), "the playlist the send made is proven and deleted")
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated })
    }

    // ---- Nits: reconnect order and a failing outbox --------------------------------------------------------------

    @Test
    fun reconnectFlushesPlaylistsBeforeReadingTheEpoch() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val first = env.session()
        val detail = env.open(first, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        first.setOnline(false)
        first.playlists.move(p.id, 1, 0, detail.last.items.map { it.rawId })
        detail.handle.close()
        val session = env.session() // a relaunch: no epoch read yet in this session
        env.server.log.clear()
        session.reader.reconnect()
        advanceUntilIdle()
        val endpoints = env.server.log.map { it.endpoint }
        assertEquals(listOf("song-2", "song-1"), p.entries)
        assertTrue(endpoints.indexOf("createPlaylist") in 0 until endpoints.indexOf("getScanStatus"), "§16.14 step 1 before step 2: $endpoints")
        assertTrue(endpoints.subList(0, endpoints.indexOf("createPlaylist")).none { it == "getScanStatus" }, "no epoch read before the write: $endpoints")
    }

    @Test
    fun aPlaylistFlushOverABrokenDatabaseReportsInsteadOfThrowing() = playlistTest { env ->
        val session = env.session()
        session.setOnline(false)
        session.playlists.create("Draft")
        session.setOnline(true)
        env.driver.execute(null, "DROP TABLE mutation_outbox", 0)
        val report = session.playlists.flush()
        assertTrue(report.interruptedLocally)
        assertTrue(env.server.writes().isEmpty())
    }

    @Test
    fun aBrokenOutboxDoesNotSkipTheScrobblesOrTheEpochRead() = sessionTest { env ->
        val session = env.session(otherOutboxes = ReconnectOutboxes { env.server.log += SessionTestServer.Request("scrobble-outbox", emptyMap()) })
        env.driver.execute(null, "DROP TABLE mutation_outbox", 0) // favourites and playlists now fail to read
        session.reader.reconnect()
        advanceUntilIdle()
        val endpoints = env.server.endpoints()
        assertTrue("scrobble-outbox" in endpoints, "the scrobble flush still ran: $endpoints")
        assertTrue(endpoints.indexOf("scrobble-outbox") < endpoints.indexOf("getScanStatus"), "and the epoch was read after it: $endpoints")
    }

    @Test
    fun aThrowingOutboxDoesNotSkipTheEpochRead() = readerTest { env ->
        val reader = env.reader(outboxes = ReconnectOutboxes { throw IllegalStateException("outbox failed") })
        reader.reconnect()
        advanceUntilIdle()
        assertEquals(1, env.server.count("getScanStatus"), "§16.14 step 2 still ran")
    }
}
