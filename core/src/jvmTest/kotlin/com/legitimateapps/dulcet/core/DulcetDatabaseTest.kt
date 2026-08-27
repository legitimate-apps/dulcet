package com.legitimateapps.dulcet.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
