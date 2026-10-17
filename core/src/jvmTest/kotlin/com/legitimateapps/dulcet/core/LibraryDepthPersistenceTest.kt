package com.legitimateapps.dulcet.core

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryDepthPersistenceTest {
    @Test fun pinnedAlbumPublishesImmediatelyOfflineAfterClosingAndReopeningTheDatabase() = runTest {
        val path = Files.createTempFile("dulcet-pinned-relaunch-", ".db")
        val server = FakeReaderServer(albumCount = 1)
        server.albums[0] = server.albums[0].copy(name = "ThresholdBoundary")
        val albumId = server.albums[0].id
        val binding = ReaderEnv.BINDING
        val clock = ManualWallClock()
        val firstScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val firstDatabase = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            try {
                val first = LibraryReader(SeenCacheStore(firstDatabase, clock).bind(binding), server, firstScope)
                first.connect()
                val detail = first.open(LibraryQuery.Album(albumId), Publications(server))
                advanceUntilIdle()
                assertEquals(1, server.count("getAlbum"))
                assertEquals("ThresholdBoundary", first.cache.album(albumId)?.record?.title)
                first.changeLibraryDepth { it.cache.setLibraryPin("album", albumId, true) }
                assertEquals(1, first.libraryDepthState().pinCount)
                detail.close()
                assertTrue(first.uncaughtFailures.isEmpty())
            } finally {
                firstScope.cancel()
                firstDatabase.close()
            }
            val requestsBeforeRelaunch = server.log.size
            val secondScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val reopenedDatabase = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            try {
                val reopened = LibraryReader(SeenCacheStore(reopenedDatabase, clock).bind(binding), server, secondScope)
                reopened.setOnline(false)
                assertEquals(1, reopened.libraryDepthState().pinCount)
                val pins = Publications(server)
                val window = reopened.open(LibraryQuery.Pinned, pins)
                val album = assertIs<LibraryItem.Album>(pins.last.items.single())
                assertEquals(albumId, album.rawId)
                assertEquals("ThresholdBoundary", album.title)
                assertEquals(LibraryCoverage.Complete, pins.last.coverage)
                advanceUntilIdle()
                assertEquals(requestsBeforeRelaunch, server.log.size)
                assertTrue(reopened.uncaughtFailures.isEmpty())
                window.close()
            } finally {
                secondScope.cancel()
                reopenedDatabase.close()
            }
        } finally {
            firstScope.cancel()
            Files.deleteIfExists(path)
        }
    }
}
