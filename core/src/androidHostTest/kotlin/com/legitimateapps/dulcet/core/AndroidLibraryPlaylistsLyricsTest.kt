package com.legitimateapps.dulcet.core

import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Android playlist and lyrics facades (spec §18.6, §18.4) over the production reader session,
 * with the in-process playlist server that carries the reference server's measured semantics. What is
 * proved here is the facade's own part: each call reaches the core's editor on the reader's thread in
 * call order, the core's answer comes back mapped, the outcome stream reports the core's outcomes,
 * and a closed reader answers `Closed` instead of never answering. The core's editing rules
 * themselves are the core's tests.
 */
class AndroidLibraryPlaylistsLyricsTest {
    private val driver = createTestDriver()
    private val database = DulcetDatabaseStore.open(driver)
    private val store = SeenCacheStore(database, ManualWallClock(now = 3_000_000))
    private val server = FakePlaylistServer(user = "listener")
    private val readers = mutableListOf<AndroidLibraryReader>()

    private fun reader(): AndroidLibraryReader = AndroidLibraryReader(
        account = null,
        compose = { scope, foreground ->
            AndroidLibraryReaderComposition(LibraryReaderSession(
                database.database, store.bind(PlaylistEnv.BINDING), server, scope,
                LibraryReaderConfig(lookAheadMaxPerViewport = 0), formPost = false, foreground = foreground,
            ))
        },
        readerDispatcher = newLibraryReaderDispatcher(),
        // Completions and publications run on the reader's thread as they are emitted.
        mainDispatcher = Dispatchers.Unconfined,
        initiallyForeground = false,
    ).also { readers += it }

    @AfterTest
    fun tearDown() {
        readers.forEach { reader ->
            runBlocking {
                reader.close()
                withTimeout(30_000) { reader.awaitTermination() }
            }
        }
        driver.close()
    }

    private fun <T> answer(call: ((T) -> Unit) -> Unit): T {
        val box = Collections.synchronizedList(mutableListOf<T>())
        call { box += it }
        poll("the completion") { box.isNotEmpty() }
        return box.single()
    }

    private fun poll(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private class Recorded {
        val all: MutableList<AndroidLibraryPublication> = Collections.synchronizedList(mutableListOf())
        val last: AndroidLibraryPublication? get() = synchronized(all) { all.lastOrNull() }
        fun entries(): List<String> = last?.items?.map { it.rawId }.orEmpty()
    }

    private fun AndroidLibraryReader.record(query: AndroidLibraryQuery): Recorded =
        Recorded().also { recorded -> openWindow(query) { recorded.all += it } }

    @Test
    fun aStaleViewIsRefusedAndACurrentOneIsWrittenAndReadBackThroughTheFacade() {
        val playlist = server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val reader = reader()
        val outcomes = Collections.synchronizedList(mutableListOf<AndroidPlaylistOutcome>())
        reader.playlists.addOutcomeListener { outcomes += it }
        val detail = reader.record(AndroidLibraryQuery.Playlist(playlist.id))
        poll("the playlist live") { detail.last?.freshness == AndroidLibraryFreshness.Live && detail.entries().size == 3 }
        val header = assertIs<AndroidLibraryItem.Playlist>(detail.last?.header)
        assertTrue(header.editable, "the account's own playlist is editable")
        assertEquals(false, header.local)

        // A view that is not the one published: refused, nothing recorded, nothing sent.
        val stale = answer { done -> reader.playlists.remove(playlist.id, setOf(1), listOf("song-1", "song-3", "song-2"), done) }
        assertEquals(AndroidPlaylistEditRecord.StaleView, stale.record)
        assertNull(stale.failure)
        assertTrue(server.writes().isEmpty(), "a stale view sends nothing: ${server.writes()}")

        val view = detail.entries()
        val removed = answer { done -> reader.playlists.remove(playlist.id, setOf(1), view, done) }
        assertEquals(AndroidPlaylistEditRecord.Pending, removed.record)
        poll("the removal saved") { outcomes.any { it is AndroidPlaylistOutcome.Saved } }
        assertEquals(listOf("song-1", "song-3"), playlist.entries, "the server removed the entry the person chose")

        poll("the removal published") { detail.entries() == listOf("song-1", "song-3") }
        val moved = answer { done -> reader.playlists.move(playlist.id, 0, 1, detail.entries(), done) }
        assertEquals(AndroidPlaylistEditRecord.Pending, moved.record)
        poll("the move saved") { outcomes.count { it is AndroidPlaylistOutcome.Saved } == 2 }
        assertEquals(listOf("song-3", "song-1"), playlist.entries)
        assertEquals(listOf(AndroidPlaylistChange.Entries, AndroidPlaylistChange.Entries),
            outcomes.filterIsInstance<AndroidPlaylistOutcome.Saved>().map { it.change })
        poll("the read-back published") {
            detail.entries() == listOf("song-3", "song-1") &&
                (detail.last?.header as? AndroidLibraryItem.Playlist)?.pendingChanges == false
        }
    }

    @Test
    fun anOfflineCreateShowsUnderItsLocalIdAndIsWithdrawnWithoutARequest() {
        val reader = reader()
        reader.setOnline(false)
        val list = reader.record(AndroidLibraryQuery.Playlists)
        poll("the list's first publication") { list.last != null }
        val created = answer { done -> reader.playlists.create("Road", listOf("song-1", "song-2"), done) }
        assertEquals(AndroidPlaylistEditRecord.Pending, created.record)
        val localId = assertNotNull(created.localId)
        poll("the local playlist listed") { list.last?.items?.any { it.rawId == localId } == true }
        val shown = list.last!!.items.filterIsInstance<AndroidLibraryItem.Playlist>().single { it.rawId == localId }
        assertEquals("Road", shown.name)
        assertTrue(shown.local, "shown as made on this device")
        assertTrue(shown.pendingChanges, "and not yet on the server")

        val pending = answer { done -> reader.playlists.pendingChanges(done) }
        assertEquals(listOf(localId to AndroidPlaylistChange.Create), pending.changes.map { it.playlistId to it.change })

        val withdrawn = answer { done -> reader.playlists.withdraw(localId, AndroidPlaylistChange.Create, done) }
        assertNull(withdrawn.failure)
        assertTrue(withdrawn.record == AndroidPlaylistEditRecord.CompactedAway || withdrawn.record == AndroidPlaylistEditRecord.Pending,
            "an unsent create is withdrawn, was ${withdrawn.record}")
        poll("the local playlist gone") { list.last?.items?.none { it.rawId == localId } == true }
        assertEquals(emptyList(), answer { done -> reader.playlists.pendingChanges(done) }.changes)
        assertEquals(0, server.count("createPlaylist"), "nothing was sent")
    }

    @Test
    fun lyricsNeverStoredAreUnavailableOfflineWithNoRequest() {
        val reader = reader()
        reader.setOnline(false)
        val lyrics = AndroidLibraryLyrics(reader, listOf("en"))
        val track = AndroidLyricsTrack("song-1", "Artist", "Title")
        val before = server.log.size
        val cached = answer { done -> lyrics.cached(track, done) }
        val read = answer { done -> lyrics.read(track, done) }
        for (publication in listOf(cached, read)) {
            assertEquals(AndroidLyricsState.Unavailable, publication.state)
            assertEquals("song-1", publication.trackRawId)
            assertTrue(publication.lines.isEmpty())
            assertNull(publication.failure)
        }
        assertEquals(AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.NotCachedOffline), read.freshness,
            "offline with nothing stored says so")
        assertEquals(before, server.log.size, "offline, nothing is asked — not even the extension list")
    }

    @Test
    fun aClosedReaderAnswersClosedRatherThanNever() {
        val reader = reader()
        runBlocking {
            reader.close()
            withTimeout(30_000) { reader.awaitTermination() }
        }
        val edit = answer { done -> reader.playlists.rename("pl-1", "New", done) }
        assertEquals(AndroidOperationFailure.Closed, edit.failure)
        assertNull(edit.record)
        val lyrics = answer { done -> AndroidLibraryLyrics(reader, emptyList()).read(AndroidLyricsTrack("song-1", null, null), done) }
        assertEquals(AndroidOperationFailure.Closed, lyrics.failure)
        assertEquals(AndroidLyricsState.Unavailable, lyrics.state)
    }
}
