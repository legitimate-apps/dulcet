package com.legitimateapps.dulcet.core

import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class LocalLibrarySearchTest {
    @Test fun firstCharacterAndUnseenQueriesRankAllFourTiersFromCommittedRows() = withLibrary { store, repository ->
        val tracks = listOf(track("substring", "Reecho"), track("word", "An Echo"),
            track("prefix", "Echoes"), track("exact:opaque/not-an-int", "Écho"))
        repository.putTracks(SERVER, 1, rowsOf(tracks))
        repository.putArtists(SERVER, 1, listOf(LibraryArtist(id("artist"), "Echo", null)))
        repository.putAlbums(SERVER, 1, listOf(AlbumSummary(id("album"), "Echo", emptyList(), null, 1.seconds, null, null)))
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)
        val search = LocalLibrarySearch(store)
        assertEquals(listOf("exact:opaque/not-an-int", "album", "artist", "prefix", "word", "substring"),
            search.search(SERVER, " ECHO ").map { it.id.rawId })
        assertEquals(6, search.search(SERVER, "e").size)
        assertEquals(listOf("prefix"), search.search(SERVER, "choes").map { it.id.rawId })
        assertEquals(emptyList(), search.search("another-account", "e"))
        assertEquals(emptyList(), search.search(SERVER, "  "))
    }

    @Test fun sharedNormalizationIndexesCompatibilityCaseFoldingAndEveryCredit() = withLibrary { store, repository ->
        val accented = track("opaque", "Ｃａｆé Straße").copy(credits = listOf(
            Credit(CreditRole.Artist, "First", null), Credit(CreditRole.AlbumArtist, "Beyoncé", id("artist"))))
        repository.putTracks(SERVER, 1, rowsOf(listOf(accented)))
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)
        val search = LocalLibrarySearch(store)
        for (query in listOf("cafe", "STRASSE", "beyonce")) {
            assertEquals(listOf("opaque"), search.search(SERVER, query).map { it.id.rawId })
        }
        assertEquals(accented.credits, search.search(SERVER, "cafe").single().credits)
        assertEquals("cafe strasse", normalizeSearchText(accented.title))
        assertEquals(normalizeSearchText("ΟΣ"), normalizeSearchText("ος"))
    }

    @Test fun pendingUpdatesAndDeletionStayInvisibleUntilCommit() = withLibrary { store, repository ->
        repository.putTracks(SERVER, 1, rowsOf(listOf(track("opaque", "Old"), track("gone", "Old deleted"))))
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)
        repository.putTracks(SERVER, 2, rowsOf(listOf(track("opaque", "New"))))
        repository.completeStage(SERVER, 2, LibrarySyncStage.Tracks)
        val search = LocalLibrarySearch(store)
        assertEquals(2, search.search(SERVER, "old").size)
        assertEquals(emptyList(), search.search(SERVER, "new"))
        repository.commit(SERVER, 2, LibrarySyncStability.Verified)
        assertEquals(emptyList(), search.search(SERVER, "old"))
        assertEquals(listOf("opaque"), search.search(SERVER, "new").map { it.id.rawId })
    }

    @Test fun migratedRowsAreBackfilledBeforeReopenPublishesTheNewVersion() = withLibrary { store, repository ->
        repository.putTracks(SERVER, 1, rowsOf(listOf(track("legacy", "École"))))
        repository.commit(SERVER, 1, LibrarySyncStability.Verified)
        store.driver.execute(null, "UPDATE track SET normalized_title = NULL, normalized_album_title = NULL", 0)
        store.driver.execute(null, "UPDATE search_index_meta SET normalization_version = 0", 0)
        val reopened = DulcetDatabaseStore.open(store.driver)
        assertEquals(5, reopened.metadata().schemaVersion)
        assertEquals(listOf("legacy"), LocalLibrarySearch(reopened).search(SERVER, "ec").map { it.id.rawId })
        assertEquals(emptyList(), reopened.database.libraryQueries.selectTrackSearchBackfill().executeAsList())
    }

    private fun withLibrary(test: (DulcetDatabaseStore, LibrarySyncRepository) -> Unit) {
        val driver = createTestDriver()
        try {
            val store = DulcetDatabaseStore.open(driver)
            test(store, LibrarySyncRepository(store))
        } finally { driver.close() }
    }
    private fun id(raw: String) = ProviderItemId(SERVER, raw)
    private fun track(raw: String, title: String) = LibraryTrack(id(raw), title, emptyList(), null,
        1, 2, 30.seconds, AudioContainer.Flac, "source", "artwork")
    private fun rowsOf(tracks: List<LibraryTrack>) = tracks.map { LibraryTrackRow("album", it) }
    private companion object { const val SERVER = "local-search-account" }
}
