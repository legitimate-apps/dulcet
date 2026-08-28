package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DulcetDatabaseTest {
    @Test
    fun schemaMetadataIsInitializedOnceAndSurvivesReopen() {
        val driver = createTestDriver()

        val first = DulcetDatabaseStore.open(driver)
        assertEquals(
            DulcetSchemaMetadata(
                schemaVersion = DULCET_SCHEMA_VERSION,
                cacheFormatVersion = 1,
                committedGeneration = 0,
            ),
            first.metadata(),
        )
        first.updateCommittedGeneration(41)

        val reopened = DulcetDatabaseStore.open(driver)
        assertEquals(41, reopened.metadata().committedGeneration)
        assertEquals(DULCET_SCHEMA_VERSION, reopened.metadata().schemaVersion)
        driver.close()
    }

    @Test
    fun deletingQueueStateCascadesEntriesAndActiveQueuePointer() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val serverId = "server:cascade"
        database.queueQueries.insertQueueStateIfMissing(serverId)
        database.queueQueries.insertQueueEntry(
            server_id = serverId,
            queue_entry_id = "queue:cascade",
            raw_id = "track:cascade",
            source_context_kind = "album",
            source_context_raw_id = null,
            source_context_display_name = "Cascade album",
            added_by = "play_now",
            original_position = 0,
            playback_position = 0,
        )
        database.queueQueries.activateQueue(serverId)

        assertEquals(1, database.queueQueries.selectQueueEntriesByOriginalPosition(serverId).executeAsList().size)
        assertEquals(serverId, database.queueQueries.selectActiveServerId().executeAsOne())

        database.queueQueries.deleteQueueState(serverId)

        assertEquals(emptyList(), database.queueQueries.selectQueueEntriesByOriginalPosition(serverId).executeAsList())
        assertNull(database.queueQueries.selectActiveServerId().executeAsOneOrNull())
        driver.close()
    }

    @Test
    fun cacheFormatVersionHasAnExplicitForwardOnlyUpdatePath() {
        val driver = createTestDriver()
        val store = DulcetDatabaseStore.open(driver)

        store.reconcileVersions(
            schemaVersion = DULCET_SCHEMA_VERSION,
            cacheFormatVersion = DULCET_CACHE_FORMAT_VERSION + 1,
        )

        assertEquals(DULCET_CACHE_FORMAT_VERSION + 1, store.metadata().cacheFormatVersion)
        driver.close()
    }
}
