package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlist editing through the production reader session (spec §18.6, CONF-89..91 in core): every
 * edit is in the publication of the tap before any request, is sent as a verified whole list or an
 * append — never as a server-side position — and is read back. [FakePlaylistServer] carries the
 * reference server's measured semantics, including the positional removal that makes a stale
 * index remove the wrong song.
 */
class PlaylistEditingTest {
    // ---- The hazard, and the fake's fidelity to it -------------------------------------------------

    @Test
    fun theFakeServerRemovesByPositionAgainstTheListAsItIsNow() = playlistTest { env ->
        // The control every prevention test below stands on: without the editor, a stale index
        // removes the wrong song, exactly as CONF-88 measured on the reference server.
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val view = p.entries.toList()
        env.server.requestRepeated("updatePlaylist", listOf("playlistId" to p.id, "songIndexToRemove" to "0"), false)
        env.server.requestRepeated("updatePlaylist", listOf("playlistId" to p.id, "songIndexToRemove" to view.indexOf("song-3").toString()), false)
        assertEquals(listOf("song-2", "song-3"), p.entries, "the stale index 2 removed song-4, not the song-3 it was aimed at")
    }

    @Test
    fun aRemovalAimedAtAMovedListIsRefusedAndRemovesNothing() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        val view = detail.last.items.map { it.rawId }
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.remove(p.id, setOf(2), view))
        assertEquals(listOf("song-1", "song-2", "song-4"), detail.last.items.map { it.rawId }, "the removal is in the publication of the tap")
        // Another client removes the first entry while this device is offline.
        p.entries.removeAt(0)
        env.server.log.clear()
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-3", "song-4"), p.entries, "nothing was removed: the edit's base no longer matched")
        assertTrue(env.server.writes().isEmpty(), "no write was sent: ${env.server.writes()}")
        val refused = assertIs<PlaylistEditOutcome.ChangedElsewhere>(env.outcomes.last())
        assertEquals(listOf("song-2", "song-3", "song-4"), refused.serverEntries)
        assertEquals(listOf("song-2", "song-3", "song-4"), detail.last.items.map { it.rawId }, "the person now sees the server's list")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aRemovalOnAnUnchangedListSendsTheVerifiedPositionAndIsReadBack() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.log.clear()
        // Remove the SECOND song-2: duplicates are positions, never ids (§18.6).
        assertEquals(PlaylistEditRecord.Pending, session.playlists.remove(p.id, setOf(3), detail.last.items.map { it.rawId }))
        val tap = detail.all.last()
        assertEquals(0, tap.requestsIssued, "shown before any request")
        assertEquals(listOf("song-1", "song-2", "song-3"), tap.value.items.map { it.rawId })
        assertTrue(assertIs<LibraryItem.Playlist>(tap.value.header).pendingChanges)
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries)
        // A removal only: the verified position of the entry chosen, never a whole list (§18.6).
        val write = env.server.writes().single()
        assertEquals("updatePlaylist", write.endpoint)
        assertEquals(listOf("playlistId" to p.id, "songIndexToRemove" to "3"), write.parameters)
        assertTrue(write.formPost, "repeated parameters travel as a form body when the server advertises formPost")
        assertEquals(listOf("getPlaylist", "updatePlaylist", "getPlaylist"), env.server.log.map { it.endpoint }, "verified, written, read back")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
        assertFalse(assertIs<LibraryItem.Playlist>(detail.last.header).pendingChanges)
    }

    @Test
    fun aMoveIsTheWholeListInItsNewOrder() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId }))
        assertEquals(listOf("song-3", "song-1", "song-2"), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries)
        assertEquals("Mix", p.name, "a replace keeps the playlist's header")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun emptyingAPlaylistRemovesEveryVerifiedIndex() = playlistTest { env ->
        // The one positional write: a replace with no songs is ignored by the reference server.
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.playlists.remove(p.id, setOf(0, 1), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(emptyList(), p.entries)
        val write = env.server.writes().single()
        assertEquals("updatePlaylist", write.endpoint)
        assertEquals(listOf("1", "0"), write.all("songIndexToRemove"), "descending")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun aReadBackThatDiffersFromWhatWasMeantIsTold() = playlistTest { env ->
        // The server silently drops a song id it does not know (OBSERVED), so the write is `ok`
        // and the list is not what was meant: only the read-back can say so.
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.playlists.insert(p.id, listOf("song-unknown"), 1, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        val diverged = assertIs<PlaylistEditOutcome.Diverged>(env.outcomes.last())
        assertEquals(listOf("song-1", "song-2"), diverged.serverEntries)
        assertEquals(listOf("song-1", "song-2"), detail.last.items.map { it.rawId }, "the screen shows the server's list")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aTapOnAViewThatHasSinceMovedIsRefusedAtOnce() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        val staleView = listOf("song-9") + detail.last.items.map { it.rawId }
        env.server.log.clear()
        assertEquals(PlaylistEditRecord.StaleView, session.playlists.remove(p.id, setOf(1), staleView))
        assertEquals(PlaylistEditRecord.Invalid, session.playlists.remove(p.id, setOf(3), detail.last.items.map { it.rawId }))
        assertEquals(PlaylistEditRecord.Invalid, session.playlists.move(p.id, 1, 1, detail.last.items.map { it.rawId }))
        advanceUntilIdle()
        assertEquals(0L, session.playlists.pendingCount())
        assertTrue(env.server.log.isEmpty())
    }

    // ---- Appends ------------------------------------------------------------------------------------

    @Test
    fun anAppendIsSentAsAnAppendEvenWhenTheListChangedElsewhere() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.append(p.id, listOf("song-5", "song-5")))
        assertEquals(listOf("song-1", "song-2", "song-5", "song-5"), detail.last.items.map { it.rawId })
        assertEquals(4, assertIs<LibraryItem.Playlist>(detail.last.header).songCount)
        p.entries.removeAt(0)
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-5", "song-5"), p.entries, "appends are not positional: sent onto whatever is there")
        assertEquals(listOf("songIdToAdd" to "song-5", "songIdToAdd" to "song-5"), env.server.writes().single().parameters.drop(1))
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun anAppendToAPlaylistNeverOpenedNeedsNoBase() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        advanceUntilIdle()
        assertEquals(PlaylistEditRecord.NotCached, session.playlists.remove(p.id, setOf(0), listOf("song-1")))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.append(p.id, listOf("song-3")))
        assertEquals(2, list.last.playlist(p.id).songCount, "the list's count carries the pending append")
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-3"), p.entries)
    }

    @Test
    fun anAlbumIsAppendedInItsOrder() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        assertEquals(PlaylistEditRecord.Pending, session.playlists.appendAlbum(p.id, "album-1"))
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-10", "song-11", "song-12"), p.entries)
    }

    // ---- Create, details, delete ---------------------------------------------------------------------

    @Test
    fun aCreateIsShownAtTheTapThenSentOnceAndReadBack() = playlistTest { env ->
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        advanceUntilIdle()
        env.server.log.clear()
        val created = session.playlists.create("  Road Trip ", listOf("song-2", "song-1", "song-2"), comment = "for the car", isPublic = true)
        assertEquals(PlaylistEditRecord.Pending, created.record)
        val localId = assertNotNull(created.localId)
        val tap = list.all.last()
        assertEquals(0, tap.requestsIssued)
        val pending = tap.value.playlist(localId)
        assertEquals("Road Trip", pending.name)
        assertTrue(pending.local && pending.pendingChanges && pending.editable)
        assertEquals(3, pending.songCount)
        advanceUntilIdle()
        val server = env.server.playlists.single()
        assertEquals(listOf("song-2", "song-1", "song-2"), server.entries)
        assertEquals("for the car", server.comment)
        assertTrue(server.isPublic)
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(PlaylistEditOutcome.Created(localId, server.id), env.outcomes.single())
        val shown = list.last.playlist(server.id)
        assertFalse(shown.local || shown.pendingChanges)
        assertTrue(list.last.items.none { it.rawId == localId })
        // The local id keeps working for a screen opened on it.
        val detail = env.open(session, LibraryQuery.Playlist(localId))
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-1", "song-2"), detail.last.items.map { it.rawId })
    }

    @Test
    fun aLocalIdIsNeverSent() = playlistTest { env ->
        val session = env.session()
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Fresh", listOf("song-1")).localId)
        // Online again, with the create not yet sent: a screen opened on the local id reads nothing.
        session.setOnline(true)
        val detail = env.open(session, LibraryQuery.Playlist(localId))
        assertEquals(listOf("song-1"), detail.last.items.map { it.rawId })
        assertTrue(assertIs<LibraryItem.Playlist>(detail.last.header).local)
        advanceUntilIdle()
        assertTrue(env.server.log.none { request -> request.parameters.any { it.second.startsWith(LOCAL_PLAYLIST_PREFIX) } }, "${env.server.log}")
        session.reader.reconnect()
        advanceUntilIdle()
        val sentIds = env.server.log.flatMap { request -> request.parameters.map { it.second } }
        assertTrue(sentIds.none { it.startsWith(LOCAL_PLAYLIST_PREFIX) }, "a local id reached the server: ${env.server.log}")
        assertEquals(1, env.server.count("createPlaylist"))
        assertTrue(env.server.count("getPlaylist") >= 1, "the created playlist is read back under its server id")
    }

    @Test
    fun editsOfAPlaylistNotYetCreatedFoldIntoItsCreate() = playlistTest { env ->
        val session = env.session()
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Draft", listOf("song-1", "song-2")).localId)
        session.playlists.append(localId, listOf("song-3"))
        session.playlists.rename(localId, "Final")
        session.playlists.move(localId, 2, 0, listOf("song-1", "song-2", "song-3"))
        assertEquals(1L, session.playlists.pendingCount())
        session.reader.reconnect()
        advanceUntilIdle()
        val write = env.server.writes().single()
        assertEquals(listOf("name" to "Final", "songId" to "song-3", "songId" to "song-1", "songId" to "song-2"), write.parameters)
    }

    @Test
    fun deletingAPlaylistNotYetCreatedSendsNothing() = playlistTest { env ->
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Oops").localId)
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.delete(localId))
        assertTrue(list.last.items.isEmpty())
        session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(env.server.writes().isEmpty())
    }

    @Test
    fun renameCommentAndVisibilityAreShownAtOnceAndReadBack() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.rename(p.id, "Evening"))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.setComment(p.id, "quiet"))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.setPublic(p.id, true))
        assertEquals(PlaylistEditRecord.Invalid, session.playlists.rename(p.id, "   "))
        val header = assertIs<LibraryItem.Playlist>(detail.last.header)
        assertEquals(Triple("Evening", "quiet", true), Triple(header.name, header.comment, header.isPublic))
        session.reader.reconnect()
        advanceUntilIdle()
        val write = env.server.writes().single()
        assertEquals(setOf("name" to "Evening", "comment" to "quiet", "public" to "true"), write.parameters.drop(1).toSet())
        assertEquals(Triple("Evening", "quiet", true), Triple(p.name, p.comment, p.isPublic))
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Details), env.outcomes.last())
        // Clearing the comment sends an empty one, which the server takes as "none".
        session.setOnline(true)
        session.playlists.setComment(p.id, "")
        advanceUntilIdle()
        assertNull(p.comment)
    }

    @Test
    fun aRenameBackToTheServersNameLeavesNoChange() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Other")
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.rename(p.id, "Mix"))
        assertEquals(PlaylistEditRecord.Unchanged, session.playlists.rename(p.id, "Mix"))
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        session.playlists.move(p.id, 0, 0, detail.last.items.map { it.rawId }).let { assertEquals(PlaylistEditRecord.Invalid, it) }
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aRenameAnotherClientOverrodeLosesToTheServer() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Mine")
        p.name = "Theirs"
        session.setOnline(true)
        detail.handle.refresh() // a live read issued after the change shows a third value
        advanceUntilIdle()
        env.server.log.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals("Theirs", p.name)
        assertTrue(env.server.writes().isEmpty())
        assertEquals(PlaylistEditOutcome.Superseded(p.id, setOf(PlaylistDetailField.Name)), env.outcomes.last())
    }

    @Test
    fun aDeleteHidesThePlaylistAtOnceAndTheListIsReadAgain() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val keep = env.server.add("Keep", listOf("song-2"))
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.log.clear()
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(p.id))
        assertEquals(listOf(keep.id), list.last.items.map { it.rawId })
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone), detail.last.freshness)
        assertEquals(PlaylistEditRecord.Deleted, session.playlists.append(p.id, listOf("song-3")))
        advanceUntilIdle()
        assertEquals(listOf(keep.id), env.server.playlists.map { it.id })
        assertEquals(listOf("deletePlaylist", "getPlaylists"), env.server.log.map { it.endpoint })
        assertEquals(listOf(keep.id), list.last.items.map { it.rawId })
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone), detail.last.freshness)
    }

    // ---- Permissions -----------------------------------------------------------------------------------

    @Test
    fun anotherUsersPlaylistIsNotEditableAndNothingIsQueued() = playlistTest { env ->
        val theirs = env.server.add("Theirs", listOf("song-1"), owner = "someone-else", isPublic = true)
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(theirs.id))
        advanceUntilIdle()
        assertFalse(assertIs<LibraryItem.Playlist>(detail.last.header).editable)
        val view = detail.last.items.map { it.rawId }
        assertEquals(PlaylistEditRecord.NotEditable, session.playlists.rename(theirs.id, "Mine"))
        assertEquals(PlaylistEditRecord.NotEditable, session.playlists.append(theirs.id, listOf("song-2")))
        assertEquals(PlaylistEditRecord.NotEditable, session.playlists.remove(theirs.id, setOf(0), view))
        assertEquals(PlaylistEditRecord.NotEditable, session.playlists.delete(theirs.id))
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aServerRefusalIsToldAndTheOverlayGoes() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithCode["updatePlaylist"] = 50
        session.playlists.rename(p.id, "Nope")
        advanceUntilIdle()
        val refused = assertIs<PlaylistEditOutcome.NotSaved>(env.outcomes.last())
        assertEquals(DomainError.Auth.Forbidden, refused.error)
        assertEquals("Mix", assertIs<LibraryItem.Playlist>(detail.last.header).name)
        assertEquals(0L, session.playlists.pendingCount())
    }

    // ---- At least once -----------------------------------------------------------------------------------

    @Test
    fun aReplaceWhoseAnswerWasLostIsNotWrittenTwice() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "createPlaylist"
        session.playlists.move(p.id, 1, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-1"), p.entries)
        assertEquals(1L, session.playlists.pendingCount(), "a lost answer stays pending")
        env.server.applyThenLose.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("createPlaylist"), env.server.writes().map { it.endpoint }, "the re-read showed it landed: nothing sent again")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun anAppendWhoseAnswerWasLostIsNotAppendedTwice() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-2"))
        advanceUntilIdle()
        p.entries.add(0, "song-9") // another client meanwhile, so the whole-list check cannot decide it
        env.server.applyThenLose.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-9", "song-1", "song-2"), p.entries)
        assertEquals(1, env.server.count("updatePlaylist"))
    }

    @Test
    fun anAppendAfterAnAppendInDoubtSendsOnlyTheRest() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-2"))
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.setOnline(false)
        session.playlists.append(p.id, listOf("song-3"))
        session.setOnline(true)
        session.playlists.flush()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "the landed song-2 is not appended again")
        assertEquals(listOf("songIdToAdd" to "song-3"), env.server.writes().last().parameters.drop(1))
    }

    @Test
    fun aWholeListInDoubtIsRecognisedAsThisDevicesOwnAfterALaterEdit() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "createPlaylist"
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-3", "song-1", "song-2"), p.entries, "the first write landed; its answer did not")
        env.server.applyThenLose.clear()
        session.setOnline(false)
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        // A later send that provably never arrived must not erase the record of the one in doubt.
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Unreachable
        session.setOnline(true)
        assertEquals(DomainError.Transport.Unreachable, session.playlists.flush().stoppedBy)
        env.server.failWithError.clear()
        session.playlists.flush()
        assertEquals(listOf("song-2", "song-3", "song-1"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last(), "not mistaken for another client's change")
    }

    @Test
    fun aCreateWhoseAnswerWasLostIsFoundNotDuplicated() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size)
        env.server.applyThenLose.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.playlists.size, "the lost create was found, not sent again")
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id), env.outcomes.last())
    }

    @Test
    fun anUnreachableServerStopsTheFlushAndKeepsEverything() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "A")
        session.playlists.append(p.id, listOf("song-2"))
        env.server.failWithError["updatePlaylist"] = DomainError.Transport.Unreachable
        session.setOnline(true)
        val report = session.playlists.flush()
        assertEquals(DomainError.Transport.Unreachable, report.stoppedBy)
        assertEquals(2, report.stillPending)
        env.server.failWithError.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals("A", p.name)
        assertEquals(listOf("song-1", "song-2"), p.entries)
    }

    @Test
    fun queuedEditsSurviveARelaunch() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1", "song-2"))
        val first = env.session()
        val detail = env.open(first, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        first.setOnline(false)
        first.playlists.move(p.id, 1, 0, detail.last.items.map { it.rawId })
        detail.handle.close()
        val second = env.session()
        val reopened = env.open(second, LibraryQuery.Playlist(p.id))
        assertEquals(listOf("song-2", "song-1"), reopened.last.items.map { it.rawId }, "the overlay is durable")
        second.reader.reconnect()
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-1"), p.entries)
    }

    @Test
    fun favouritesIgnorePlaylistRowsInTheSharedOutbox() = playlistTest { env ->
        val session = env.session()
        session.setOnline(false)
        session.playlists.create("Shared")
        assertEquals(0L, session.favourites.pendingCount())
        assertEquals(1L, session.playlists.pendingCount())
    }
}

// ---- Harness -------------------------------------------------------------------------------------------

internal class PlaylistEnv(
    val server: FakePlaylistServer,
    private val database: DulcetDatabaseStore,
    private val store: SeenCacheStore,
    private val scope: CoroutineScope,
    val clock: ManualWallClock,
    val driver: app.cash.sqldelight.db.SqlDriver,
) {
    val outcomes = mutableListOf<PlaylistEditOutcome>()

    fun session(
        formPost: Boolean = true,
        config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
    ): LibraryReaderSession = LibraryReaderSession(
        database = database.database,
        cache = store.bind(BINDING),
        transport = server,
        scope = scope,
        config = config,
        formPost = formPost,
    ).also { it.playlists.addOutcomeListener(outcomes::add) }

    class Opened(val handle: LibraryWindowHandle, val all: MutableList<Seen>) {
        data class Seen(val value: LibraryPublication, val requestsIssued: Int)

        val last: LibraryPublication get() = all.last().value
    }

    fun open(session: LibraryReaderSession, query: LibraryQuery): Opened {
        val all = mutableListOf<Opened.Seen>()
        val handle = session.reader.open(query) { all += Opened.Seen(it, server.log.size) }
        return Opened(handle, all)
    }

    companion object {
        val BINDING = CacheBinding("server:playlists", "https://music.example", "listener")
    }
}

internal fun playlistTest(block: suspend TestScope.(PlaylistEnv) -> Unit) = runTest {
    val driver = createTestDriver()
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    try {
        val database = DulcetDatabaseStore.open(driver)
        val clock = ManualWallClock(now = 3_000_000)
        // The device and the server share one clock here; §18.6 states what a skew costs.
        val server = FakePlaylistServer(now = { clock.now })
        block(PlaylistEnv(server, database, SeenCacheStore(database, clock), scope, clock, driver))
    } finally {
        scope.cancel()
        driver.close()
    }
}

internal fun LibraryPublication.playlist(rawId: String): LibraryItem.Playlist =
    items.filterIsInstance<LibraryItem.Playlist>().first { it.rawId == rawId }
