package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Queue policy metadata survives a new controller without another library read. */
class PlaybackQueueMetadataTest {
    @Test
    fun sameAlbumInASearchQueueStaysGaplessAfterRelaunch() = withDatabase { database ->
        val queue = controller(database)
        queue.setCrossfade(6.seconds)
        queue.replaceAndStart(request(item("a", album = "same"), item("b", album = "same")))
        assertEquals(Duration.ZERO, queue.snapshot().crossfadeIntoNext)

        val restored = controller(database)
        restored.setCrossfade(6.seconds)
        restored.restoreCurrentPaused()
        assertEquals(Duration.ZERO, restored.snapshot().crossfadeIntoNext)
    }

    @Test
    fun differentTrackAlbumsOverrideAlbumSourceEvenAfterRelaunch() = withDatabase { database ->
        val queue = controller(database)
        queue.setCrossfade(6.seconds)
        queue.replaceAndStart(request(item("a", album = "one"), item("b", album = "two")).copy(
            sourceContext = QueueSourceContext(QueueSourceKind.Album, ProviderItemId(SERVER, "source"), "Album"),
        ))
        assertEquals(6.seconds, queue.snapshot().crossfadeIntoNext)

        val restored = controller(database)
        restored.setCrossfade(6.seconds)
        restored.restoreCurrentPaused()
        assertEquals(6.seconds, restored.snapshot().crossfadeIntoNext)
    }

    @Test
    fun shortTrackKeepsItsDurationAndDeclinesCrossfadeBeforeEngineReadyAfterRelaunch() = withDatabase { database ->
        val queue = controller(database)
        queue.setCrossfade(12.seconds)
        queue.replaceAndStart(request(item("short", duration = 30.seconds), item("next")))
        assertEquals(Duration.ZERO, queue.snapshot().crossfadeIntoNext)

        val restored = controller(database)
        restored.setCrossfade(12.seconds)
        val start = assertNotNull(restored.restoreCurrentPaused().startDirective)
        assertEquals(30.seconds, start.duration)
        assertEquals(Duration.ZERO, restored.snapshot().crossfadeIntoNext)
    }

    @Test
    fun insertedAndAutoplayMetadataSurviveShuffleEditsAndRelaunch() = withDatabase { database ->
        val queue = controller(database)
        queue.replaceAndStart(request(item("a")))
        queue.enqueue(PlaybackQueueInsertion(listOf(item("inserted", duration = 40.seconds, album = "same")),
            QueueSourceContext(QueueSourceKind.Search, null, "Search"), QueueInsertionMode.Append))
        queue.setAutoplay(true)
        val ask = assertNotNull(queue.takeAutoplayRequest())
        queue.appendAutoplay(ask.key, listOf(item("auto", album = "same")))
        // Shuffle removes upcoming AutoPlay, so exercise it before adding the final tail.
        queue.setShuffle(true)
        queue.setShuffle(false)
        val retry = assertNotNull(queue.takeAutoplayRequest())
        queue.appendAutoplay(retry.key, listOf(item("auto", album = "same")))
        queue.next()

        val restored = controller(database)
        restored.setCrossfade(6.seconds)
        val start = assertNotNull(restored.restoreCurrentPaused().startDirective)
        assertEquals("inserted", start.itemId.rawId)
        assertEquals(40.seconds, start.duration)
        assertEquals(Duration.ZERO, restored.snapshot().crossfadeIntoNext)
        assertEquals(listOf(false, false, true), restored.snapshot().entries.map { it.isAutoplay })
    }

    @Test
    fun removalAndClearUpcomingRetainOnlyMetadataForEntriesStillInTheQueue() = withDatabase { database ->
        val queue = controller(database)
        queue.replaceAndStart(request(item("a", album = "keep"), item("remove", album = "remove"),
            item("clear", album = "clear")))
        val removed = queue.snapshot().entries[1].queueEntryId
        queue.remove(removed)
        assertEquals(listOf("keep", "clear"), PersistentQueueStore(database).load(ServerId(SERVER)).entries.map { it.albumRawId })
        queue.clearUpcoming()
        val remaining = PersistentQueueStore(database).load(ServerId(SERVER)).entries.single()
        assertEquals("a", remaining.providerItemId.rawId)
        assertEquals("keep", remaining.albumRawId)
        assertEquals(180.seconds, remaining.duration)
        assertEquals(1, queue.snapshot().entries.size)
    }

    @Test
    fun migrationPreservesOldQueueAndBackfillsPinnedMetadataWithoutInventingUnknownValues() {
        val driver = createReleasedSchemaTestDriver(RELEASED_SCHEMA_6_STATEMENTS)
        val fresh = createTestDriver()
        try {
            // Reproduce the last released shape, then apply only the new migration.
            DulcetDatabase.Schema.migrate(driver, 6, 12)
            driver.execute(null, "INSERT INTO queue_state VALUES ('$SERVER', 0, 'off', 0)", 0)
            driver.execute(null, "INSERT INTO active_queue VALUES (1, '$SERVER')", 0)
            for ((position, id) in listOf("known", "placeholder").withIndex()) {
                driver.execute(null, "INSERT INTO queue_entry VALUES ('$SERVER', '$id', '$id', 'search', NULL, 'Search', 'play_now', $position, $position)", 0)
            }
            driver.execute(null, "INSERT INTO cache_track(server_id, raw_id, album_raw_id, title, duration_milliseconds, issue_seq, last_access_wall) VALUES ('$SERVER', 'known', 'album', 'Known', 42000, 1, 0)", 0)
            driver.execute(null, "INSERT INTO cache_track(server_id, raw_id, issue_seq, last_access_wall, metadata_missing) VALUES ('$SERVER', 'placeholder', 1, 0, 1)", 0)
            DulcetDatabase.Schema.migrate(driver, 12, DulcetDatabase.Schema.version)
            val database = DulcetDatabaseStore.open(driver).database
            val entries = PersistentQueueStore(database).load(ServerId(SERVER)).entries
            assertEquals(listOf("known", "placeholder"), entries.map { it.providerItemId.rawId })
            assertEquals(42.seconds, entries[0].duration)
            assertEquals("album", entries[0].albumRawId)
            assertNull(entries[1].duration)
            assertNull(entries[1].albumRawId)
            assertEquals(42.seconds, controller(database).restoreCurrentPaused().startDirective?.duration)
            val columns = "SELECT name || '|' || type || '|' || \"notnull\" FROM pragma_table_info('queue_entry') ORDER BY cid"
            assertEquals(fresh.strings(columns), driver.strings(columns))
        } finally { driver.close(); fresh.close() }
    }

    private var identity = 0

    private fun controller(database: DulcetDatabase) = PlaybackQueueController(
        PersistentQueueStore(database), PersistentResumePositionStore(database),
        PlaybackIdentitySource { "$it:${identity++}" },
    )

    private fun request(vararg items: PlaybackQueueItem) = PlaybackQueueRequest(items.toList(),
        QueueSourceContext(QueueSourceKind.Search, null, "Search"), 0, false)

    private fun item(rawId: String, duration: Duration = 180.seconds, album: String? = null) =
        PlaybackQueueItem(ProviderItemId(SERVER, rawId), duration, album)

    private fun withDatabase(body: (DulcetDatabase) -> Unit) {
        val driver = createTestDriver()
        try { body(DulcetDatabaseStore.open(driver).database) } finally { driver.close() }
    }

    private companion object { const val SERVER = "queue-metadata" }
}
