package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LibrarySyncTest {
    @Test
    fun committedReadsRemainPinnedUntilTheGenerationCommit() {
        val driver = createTestDriver()
        val store = DulcetDatabaseStore.open(driver)
        val repository = LibrarySyncRepository(store)

        repository.putTracks(SERVER, 1, rows("old-album", track("old-track")))
        repository.completeStage(SERVER, 1, LibrarySyncStage.Tracks)
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)

        repository.putTracks(SERVER, 2, rows("new-album", track("new-track")))
        repository.completeStage(SERVER, 2, LibrarySyncStage.Tracks)

        assertEquals(1, repository.readCommitted(SERVER).generation)
        assertEquals(listOf("old-track"), repository.readCommitted(SERVER).trackIds)

        repository.commit(SERVER, 2, LibrarySyncStability.Verified)

        assertEquals(2, repository.readCommitted(SERVER).generation)
        assertEquals(listOf("new-track"), repository.readCommitted(SERVER).trackIds)
        driver.close()
    }

    @Test
    fun exceptionAfterGenerationUpdateRollsBackTheWholeCommit() {
        val driver = createTestDriver()
        val store = DulcetDatabaseStore.open(driver)
        val repository = LibrarySyncRepository(store)
        repository.putTracks(SERVER, 1, rows("old-album", track("old-track")))
        repository.completeStage(SERVER, 1, LibrarySyncStage.Tracks)
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)

        repository.putTracks(SERVER, 2, rows("new-album", track("new-track")))
        repository.completeStage(SERVER, 2, LibrarySyncStage.Tracks)
        val interrupted = LibrarySyncRepository(store) {
            throw CommitInterruption
        }

        assertFailsWith<CommitInterruption> {
            interrupted.commit(SERVER, 2, LibrarySyncStability.Verified)
        }
        assertEquals(1, store.metadata().committedGeneration)
        assertEquals(listOf("old-track"), repository.readCommitted(SERVER).trackIds)
        assertEquals(null, store.database.libraryQueries.selectSyncGeneration(2).executeAsOneOrNull())

        repository.commit(SERVER, 2, LibrarySyncStability.Verified)
        assertEquals(2, store.metadata().committedGeneration)
        assertEquals(listOf("new-track"), repository.readCommitted(SERVER).trackIds)
        driver.close()
    }

    @Test
    fun failureAfterCommitTransactionCannotReviseSuccess() = runTest {
        assertPostCommitFailureCannotReviseSuccess(LibrarySyncPostCommitSite.AfterTransaction)
    }

    @Test
    fun failureBeforeReconciliationDeliveryCannotReviseSuccess() = runTest {
        assertPostCommitFailureCannotReviseSuccess(
            LibrarySyncPostCommitSite.BeforeReconciliationDelivery,
        )
    }

    @Test
    fun pruningFailureCannotReviseSuccess() = runTest {
        assertPostCommitFailureCannotReviseSuccess(LibrarySyncPostCommitSite.BeforePruning)
    }

    @Test
    fun changedReferencedTrackIsNotReportedAsDeletedButRemovalIs() {
        val driver = createTestDriver()
        val store = DulcetDatabaseStore.open(driver)
        val database = store.database
        val repository = LibrarySyncRepository(store)
        database.queueQueries.insertQueueStateIfMissing(SERVER)
        database.queueQueries.insertQueueEntry(
            server_id = SERVER,
            queue_entry_id = "queue-entry",
            raw_id = "stable-track",
            source_context_kind = "library",
            source_context_raw_id = null,
            source_context_display_name = "Library",
            added_by = "play_now",
            original_position = 0,
            playback_position = 0,
        )

        repository.putTracks(SERVER, 1, rows("album", track("stable-track", "Before")))
        repository.completeStage(SERVER, 1, LibrarySyncStage.Tracks)
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)

        repository.putTracks(SERVER, 2, rows("album", track("stable-track", "After")))
        repository.completeStage(SERVER, 2, LibrarySyncStage.Tracks)
        assertEquals(0, repository.commit(SERVER, 2, LibrarySyncStability.Verified)
            .deletionReconciliations.size)
        assertEquals(emptyList(), repository.deletionReconciliations(SERVER, 2))

        repository.putTracks(SERVER, 3, emptyList())
        repository.completeStage(SERVER, 3, LibrarySyncStage.Tracks)
        assertEquals(1, repository.commit(SERVER, 3, LibrarySyncStability.Verified)
            .deletionReconciliations.size)
        assertEquals(
            listOf(LibraryDeletionReconciliation(3, "stable-track", 0, 1)),
            repository.deletionReconciliations(SERVER, 3),
        )
        driver.close()
    }

    @Test
    fun stabilityWitnessStopsAfterThreeDifferencesAndCommitsUnverified() = runTest {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val source = MutableSource().apply { mutateStarredAfterEveryRead = true }

        val result = LibrarySyncEngine(repository, enumerationPageSize = 2)
            .synchronize(SERVER, source)

        val completed = assertIs<LibrarySyncResult.Completed>(result)
        assertEquals(LibrarySyncStability.Unverified, completed.stability)
        assertEquals(4, source.starredReadCount, "initial witness plus exactly three bounded attempts")
        assertEquals(1, repository.readCommitted(SERVER).generation)
        driver.close()
    }

    @Test
    fun perItemPlaylistConcurrencyNeverExceedsFour() = runTest {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val source = MutableSource(playlistCount = 9, playlistDelayMilliseconds = 1)

        assertIs<LibrarySyncResult.Completed>(
            LibrarySyncEngine(repository, enumerationPageSize = 2).synchronize(SERVER, source),
        )

        // Playlists are the only stage that still reads one server item per request, so this is
        // where the bounded-concurrency contract (spec 16.5 rule 6) is now observable at all.
        assertTrue(source.maximumActivePlaylistRequests <= 4)
        assertEquals(4, source.maximumActivePlaylistRequests)
        driver.close()
    }

    @Test
    fun changedPlaylistPrefixInvalidatesItsCheckpointBeforeResume() = runTest {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val source = MutableSource(playlistCount = 2).apply { failPlaylistIdOnce = "playlist-1" }
        val engine = LibrarySyncEngine(repository, enumerationPageSize = 2, maxInFlight = 1)

        assertIs<LibrarySyncResult.Failed>(engine.synchronize(SERVER, source))
        val checkpoint = requireNotNull(repository.checkpoint(SERVER))
        assertEquals(LibrarySyncStage.Playlists, checkpoint.stage)
        assertEquals(1, checkpoint.cursor)

        source.playlistTracks["playlist-0"] = listOf("replacement-track")
        val resumed = engine.synchronize(SERVER, source)

        assertIs<LibrarySyncResult.Completed>(resumed)
        assertEquals(
            4,
            source.playlistReads.getValue("playlist-0"),
            "initial read, changed-prefix validation, restarted stage, and stable witness",
        )
        driver.close()
    }

    @Test
    fun repositoryRejectsCrossServerRows() {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))

        assertFailsWith<IllegalArgumentException> {
            repository.putArtists(
                SERVER,
                1,
                listOf(LibraryArtist(ProviderItemId("other-server", "artist"), "Artist", null)),
            )
        }
        driver.close()
    }

    @Test
    fun aPendingServerGenerationBlocksAnotherServerFromAdvancingTheGlobalGeneration() = runTest {
        val driver = createTestDriver()
        val store = DulcetDatabaseStore.open(driver)
        val repository = LibrarySyncRepository(store)
        repository.prepareCheckpoint(SERVER, restart = false)

        val result = LibrarySyncEngine(repository).synchronize("server:other", MutableSource())

        assertIs<LibrarySyncResult.Failed>(result)
        assertEquals(0, store.metadata().committedGeneration)
        assertEquals(SERVER, store.database.libraryQueries.selectOtherCheckpointServer("server:other")
            .executeAsOne())
        driver.close()
    }

    @Test
    fun albumWitnessAttemptIsReservedBeforeItsFallibleWalk() = runTest {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val source = InterruptingAlbumPaginationSource(MutableSource(albumCount = 4))
        val engine = LibrarySyncEngine(repository, enumerationPageSize = 1)

        assertIs<LibrarySyncResult.Failed>(engine.synchronize(SERVER, source))
        val interrupted = requireNotNull(repository.checkpoint(SERVER))
        assertEquals(LibrarySyncStage.Albums, interrupted.stage)
        assertEquals(
            2,
            interrupted.attempt,
            "the interrupted walk must have durably consumed its attempt",
        )

        val completed = assertIs<LibrarySyncResult.Completed>(engine.synchronize(SERVER, source))
        assertEquals(LibrarySyncStability.Unverified, completed.stability)
        assertEquals(
            4,
            source.walkStarts,
            "resume must have only attempt 3 left instead of replenishing the interrupted attempt",
        )
        driver.close()
    }

    @Test
    fun progressExplicitlyIdentifiesFirstSyncAndDurableStages() = runTest {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val observed = mutableListOf<LibrarySyncProgress>()
        val engine = LibrarySyncEngine(repository)

        assertIs<LibrarySyncResult.Completed>(
            engine.synchronize(SERVER, MutableSource(), progress = observed::add),
        )

        assertTrue(observed.all { it.isFirstSync })
        assertEquals("folders", observed.first().stage)
        assertEquals("complete", observed.last().stage)
        assertEquals(7, observed.last().completedStageCount)

        observed.clear()
        assertIs<LibrarySyncResult.Completed>(
            engine.synchronize(SERVER, MutableSource(), progress = observed::add),
        )
        assertTrue(observed.none { it.isFirstSync })
        driver.close()
    }

    @Test
    fun referencesToTracksOmittedByPaginationAreClosedBeforeCommit() {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val playlistSummary = LibraryPlaylistSummary(id("playlist"), "Playlist")
        val playlist = LibraryPlaylist(playlistSummary, listOf(id("track")))
        val starred = LibraryStarredItem(LibraryStarredKind.Track, id("track"))

        repository.putTracks(SERVER, 1, rows("album", track("track")))
        repository.completeStage(SERVER, 1, LibrarySyncStage.Tracks)
        repository.putPlaylists(SERVER, 1, listOf(playlist))
        repository.completeStage(SERVER, 1, LibrarySyncStage.Playlists)
        repository.putStarred(SERVER, 1, listOf(starred))
        repository.completeStage(SERVER, 1, LibrarySyncStage.Starred)
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)

        repository.putTracks(SERVER, 2, emptyList())
        repository.completeStage(SERVER, 2, LibrarySyncStage.Tracks)
        repository.putPlaylists(SERVER, 2, listOf(playlist))
        repository.completeStage(SERVER, 2, LibrarySyncStage.Playlists)
        repository.putStarred(SERVER, 2, listOf(starred))
        repository.completeStage(SERVER, 2, LibrarySyncStage.Starred)
        repository.commit(SERVER, 2, LibrarySyncStability.Unverified)

        assertEquals(0, repository.visibleDanglingReferenceCount(SERVER))
        assertEquals(emptyList(), repository.readCommitted(SERVER).starredIds)
        driver.close()
    }

    @Test
    fun cancellationWhileWaitingForGlobalSyncLockIsReportedAsCancelled() = runTest {
        val driver = createTestDriver()
        val repository = LibrarySyncRepository(DulcetDatabaseStore.open(driver))
        val heldMutex = Mutex(locked = true)
        val manager = LibrarySyncManager(
            LibrarySyncEngine(repository, syncMutex = heldMutex),
            repository,
        )
        var response: LibrarySyncResponse? = null

        val queuedSync = launch(start = CoroutineStart.UNDISPATCHED) {
            response = manager.synchronize(SERVER, MutableSource())
        }
        queuedSync.cancel()
        queuedSync.join()

        val failed = assertIs<LibrarySyncResponse.Failed>(response)
        assertEquals(DomainError.Transport.Cancelled, failed.error)
        assertEquals(0, repository.committedGeneration())
        driver.close()
    }

    private suspend fun assertPostCommitFailureCannotReviseSuccess(site: LibrarySyncPostCommitSite) {
        val driver = createTestDriver()
        val store = DulcetDatabaseStore.open(driver)
        val repository = LibrarySyncRepository(
            store = store,
            postCommitProbe = LibrarySyncPostCommitProbe { visited ->
                if (visited == site) throw PostCommitInterruption
            },
        )
        val manager = LibrarySyncManager(LibrarySyncEngine(repository), repository)

        val response = manager.synchronize(SERVER, MutableSource())
        val completed = assertIs<LibrarySyncResponse.Completed>(response)

        assertEquals(1, completed.generation)
        assertEquals(1, store.metadata().committedGeneration)
        assertEquals(null, repository.checkpoint(SERVER))
        assertEquals(
            SERVER,
            store.database.libraryQueries.selectSyncGeneration(1).executeAsOne().server_id,
        )
        driver.close()
    }

    private class MutableSource(
        albumCount: Int = 1,
        playlistCount: Int = 0,
        private val playlistDelayMilliseconds: Long = 0,
    ) : LibrarySyncSource {
        private val albums = List(albumCount) { index -> album("album-$index", track("track-$index")) }
        private val artists = listOf(LibraryArtist(id("artist"), "Artist", null))
        private val trackRows = albums.flatMap { value ->
            value.tracks.map { LibraryTrackRow(value.id.rawId, it) }
        }
        private val playlistSummaries = List(playlistCount) { index ->
            LibraryPlaylistSummary(id("playlist-$index"), "Playlist $index")
        }
        val playlistTracks = playlistSummaries.associate { it.id.rawId to listOf("track-0") }.toMutableMap()
        val playlistReads = mutableMapOf<String, Int>()
        var failPlaylistIdOnce: String? = null
        var mutateStarredAfterEveryRead = false
        var starredReadCount = 0
        var trackPageReads = 0
            private set
        private var starred = false
        private var activePlaylistRequests = 0
        var maximumActivePlaylistRequests = 0
            private set

        override suspend fun musicFolders(): List<LibraryMusicFolder> =
            listOf(LibraryMusicFolder(id("folder"), "Music"))

        override suspend fun probeEnumeration(): LibraryEnumerationProbe =
            LibraryEnumerationProbe(
                knownPositiveAlbumCount = minOf(albums.size, 1),
                enumeratedAlbumCount = minOf(albums.size, 1),
            )

        override suspend fun artistPage(offset: Long, size: Int): List<LibraryArtist> =
            artists.drop(offset.toInt()).take(size)

        override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> =
            albums.drop(offset.toInt()).take(size).map { summary(it) }

        override suspend fun trackPage(offset: Long, size: Int): List<LibraryTrackRow> {
            trackPageReads += 1
            return trackRows.drop(offset.toInt()).take(size)
        }

        override suspend fun playlists(): List<LibraryPlaylistSummary> = playlistSummaries

        override suspend fun playlist(summary: LibraryPlaylistSummary): LibraryPlaylist {
            val rawId = summary.id.rawId
            playlistReads[rawId] = playlistReads.getOrElse(rawId) { 0 } + 1
            if (failPlaylistIdOnce == rawId) {
                failPlaylistIdOnce = null
                error("interrupted playlist stage")
            }
            activePlaylistRequests += 1
            maximumActivePlaylistRequests =
                maxOf(maximumActivePlaylistRequests, activePlaylistRequests)
            try {
                if (playlistDelayMilliseconds > 0) delay(playlistDelayMilliseconds)
                return LibraryPlaylist(summary, playlistTracks.getValue(rawId).map(::id))
            } finally {
                activePlaylistRequests -= 1
            }
        }

        override suspend fun starred(): List<LibraryStarredItem> {
            starredReadCount += 1
            val result = if (starred) {
                listOf(LibraryStarredItem(LibraryStarredKind.Track, id("track-0")))
            } else {
                emptyList()
            }
            if (mutateStarredAfterEveryRead) starred = !starred
            return result
        }

        override suspend fun genres(): List<LibraryGenre> = listOf(LibraryGenre("Genre", 1, 1))
    }

    private class InterruptingAlbumPaginationSource(
        private val delegate: LibrarySyncSource,
    ) : LibrarySyncSource by delegate {
        var walkStarts = 0
            private set
        private var shifted = false
        private var interruptAtWalkStart: Int? = 3

        override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> {
            check(size == 1)
            if (offset == 0L) {
                walkStarts += 1
                if (interruptAtWalkStart == walkStarts) {
                    interruptAtWalkStart = null
                    error("interrupt after the first durable retry checkpoint")
                }
            }
            val page = delegate.albumPage(offset + if (shifted) 1 else 0, size)
            if (offset == 0L) shifted = !shifted
            return page
        }
    }

    private companion object {
        const val SERVER = "server:opaque"

        object CommitInterruption : RuntimeException()
        object PostCommitInterruption : RuntimeException()

        fun id(rawId: String) = ProviderItemId(SERVER, rawId)

        fun track(rawId: String, title: String = rawId) = LibraryTrack(
            id = id(rawId),
            title = title,
            credits = emptyList(),
            albumTitle = "Album",
            discNumber = 1,
            trackNumber = 1,
            duration = 30.seconds,
            sourceContainer = AudioContainer.Flac,
            mediaSourceId = null,
            artworkKey = null,
        )

        fun album(rawId: String, vararg tracks: LibraryTrack) = LibraryAlbum(
            id = id(rawId),
            title = rawId,
            credits = emptyList(),
            year = null,
            duration = tracks.fold(kotlin.time.Duration.ZERO) { total, track -> total + track.duration },
            mediaSourceId = null,
            artworkKey = null,
            tracks = tracks.toList(),
        )

        fun rows(albumRawId: String, vararg tracks: LibraryTrack) =
            tracks.map { LibraryTrackRow(albumRawId, it) }

        fun summary(album: LibraryAlbum) = AlbumSummary(
            id = album.id,
            title = album.title,
            credits = album.credits,
            year = album.year,
            duration = album.duration,
            mediaSourceId = album.mediaSourceId,
            artworkKey = album.artworkKey,
        )
    }
}
