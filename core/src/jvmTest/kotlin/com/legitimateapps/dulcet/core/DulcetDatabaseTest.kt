package com.legitimateapps.dulcet.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files

class DulcetDatabaseTest {
    @Test
    fun schemaMetadataIsInitializedOnceAndSurvivesReopen() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DulcetDatabase.Schema.create(driver)

        val first = DulcetDatabaseStore.open(driver)
        assertEquals(
            DulcetSchemaMetadata(
                schemaVersion = 1,
                cacheFormatVersion = 1,
                committedGeneration = 0,
            ),
            first.metadata(),
        )
        first.updateCommittedGeneration(41)

        val reopened = DulcetDatabaseStore.open(driver)
        assertEquals(41, reopened.metadata().committedGeneration)
        assertEquals(DulcetDatabase.Schema.version, reopened.metadata().schemaVersion)
        driver.close()
    }

    @Test
    fun jvmFactoryCreatesAndReopensTheVersionedDatabase() {
        val path = Files.createTempFile("dulcet-driver-factory-", ".db")
        Files.delete(path)
        try {
            val first = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            first.updateCommittedGeneration(73)
            first.close()

            val reopened = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            assertEquals(1, reopened.metadata().schemaVersion)
            assertEquals(73, reopened.metadata().committedGeneration)
            reopened.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
