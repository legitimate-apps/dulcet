package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DulcetDatabaseTest {
    /**
     * 🚨 **Two test drivers must not be the same database.**
     *
     * On Kotlin/Native `createTestDriver` opened a NAMED in-memory database, and SQLiter shares an
     * in-memory database by name across the process — so every test in the suite connected to one
     * database, kept alive by whichever connections were open. The suite passed only because every
     * test happened to close its driver, and the last close dropped the database. One test that
     * forgot left a populated library visible to everything that ran after it.
     *
     * MEASURED 2026-09-11: a single missing `close()` in one sync test failed **26 tests across six
     * unrelated classes**, none of them the test that leaked. That is what makes this class of
     * defect expensive — the failures land on somebody else's work.
     *
     * A leak detector would have named the leaker. This is better, because it makes a leak unable
     * to reach anyone: "the test closed what it opened" is a weaker property than "it does not
     * matter whether it did".
     *
     * ⚠️ On the JVM every driver is an anonymous `IN_MEMORY` connection, so this has always held
     * there and this control can only discriminate on **native** — which is precisely the gap that
     * let the defect through: `:core:jvmTest` runs commonTest on the JVM and
     * `:core:compileTestKotlinIosSimulatorArm64` only compiles it. Compiling for native is not
     * running on native, and `:core:macosArm64Test` is the check that closes it.
     */
    @Test
    fun twoTestDriversAreIndependentDatabases() {
        val first = createTestDriver()
        val second = createTestDriver()
        try {
            DulcetDatabaseStore.open(first).updateCommittedGeneration(41)

            assertEquals(
                41,
                DulcetDatabaseStore.open(first).metadata().committedGeneration,
                "the driver that wrote must read its own write back"
            )
            assertEquals(
                0,
                DulcetDatabaseStore.open(second).metadata().committedGeneration,
                "a second driver must not see the first driver's writes"
            )
        } finally {
            // This control demonstrated the failure-path leak itself: when it first ran against a
            // shared name, the assertion threw and the trailing closes never executed, which then
            // failed the NEXT test in this class. It must not be able to do that.
            first.close()
            second.close()
        }
    }

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
