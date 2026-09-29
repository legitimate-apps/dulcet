package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The outbox is durable across a real relaunch: a change made offline is written to a database
 * file, the file is closed, and a new process-equivalent (a new driver, store, reader and outbox)
 * overlays it in its first publication and delivers it on reconnect.
 */
class MutationOutboxRelaunchTest {
    @Test
    fun aChangeMadeOfflineSurvivesClosingTheDatabaseAndIsSentAfterReopening() = runTest {
        val path = Files.createTempFile("dulcet-outbox-relaunch-", ".db")
        val server = SessionTestServer()
        val binding = CacheBinding("server:relaunch", "https://music.example", "listener")
        val album = LibraryEntityRef(LibraryEntityKind.Album, albumId(7))
        val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
        val clock = ManualWallClock(now = 3_000_000)
        try {
            fun open(scope: CoroutineScope): Pair<DulcetDatabaseStore, LibraryReaderSession> {
                val store = DulcetDriverFactory(path.toString()).openDulcetDatabase()
                return store to LibraryReaderSession(store.database, SeenCacheStore(store, clock).bind(binding), server, scope, formPost = false, foreground = false)
            }

            val firstScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val (firstStore, first) = open(firstScope)
            first.reader.connect()
            first.reader.open(grid) {}
            advanceUntilIdle()
            first.setOnline(false)
            first.favourites.setFavourite(album, true)
            assertEquals(1L, first.favourites.pendingCount())
            firstScope.cancel()
            firstStore.close()

            val secondScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val (secondStore, second) = open(secondScope)
            try {
                val published = mutableListOf<LibraryPublication>()
                val before = server.log.size
                second.reader.open(grid) { published += it }
                assertEquals(before, server.log.size, "fixture: the first publication precedes any request")
                assertEquals(true, published.first().album(albumId(7)).starred, "the reopened outbox is overlaid at once")
                second.reader.reconnect()
                advanceUntilIdle()
                assertEquals(listOf("star"), server.log.map { it.endpoint }.filter { it == "star" })
                assertEquals(true, server.base.albums[7].starred)
                assertEquals(0L, second.favourites.pendingCount())
            } finally {
                secondScope.cancel()
                secondStore.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun aChangeWhoseAnswerWasLostIsStillTreatedAsPossiblyDeliveredAfterRelaunch() = runTest {
        val path = Files.createTempFile("dulcet-outbox-attempted-", ".db")
        val server = SessionTestServer()
        val binding = CacheBinding("server:relaunch", "https://music.example", "listener")
        val album = LibraryEntityRef(LibraryEntityKind.Album, albumId(7))
        val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
        val clock = ManualWallClock(now = 3_000_000)
        try {
            fun open(scope: CoroutineScope): Pair<DulcetDatabaseStore, LibraryReaderSession> {
                val store = DulcetDriverFactory(path.toString()).openDulcetDatabase()
                return store to LibraryReaderSession(store.database, SeenCacheStore(store, clock).bind(binding), server, scope, formPost = false, foreground = false)
            }

            val firstScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val (firstStore, first) = open(firstScope)
            first.reader.connect()
            first.reader.open(grid) {}
            advanceUntilIdle()
            server.applyThenLose += "star"
            first.favourites.setFavourite(album, true)
            advanceUntilIdle()
            assertEquals(true, server.base.albums[7].starred, "fixture: applied, answer lost")
            assertEquals(setOf(1), first.outbox.all().single().attemptedValues)
            firstScope.cancel()
            firstStore.close()
            server.applyThenLose.clear()

            val secondScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
            val (secondStore, second) = open(secondScope)
            try {
                assertEquals(setOf(1), second.outbox.all().single().attemptedValues, "the attempted set survives relaunch")
                second.setOnline(false)
                assertEquals(MutationRecord.Pending, second.favourites.setFavourite(album, false),
                    "the star may be on the server, so the unstar is kept, not compacted away")
                second.setOnline(true)
                second.reader.reconnect()
                advanceUntilIdle()
                assertEquals("unstar", server.log.last { it.endpoint in setOf("star", "unstar") }.endpoint)
                assertEquals(false, server.base.albums[7].starred)
                assertEquals(0L, second.favourites.pendingCount())
            } finally {
                secondScope.cancel()
                secondStore.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
