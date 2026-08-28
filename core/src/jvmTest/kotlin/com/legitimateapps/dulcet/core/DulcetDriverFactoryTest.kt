package com.legitimateapps.dulcet.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DulcetDriverFactoryTest {
    @Test
    fun jvmFactoryEnablesForeignKeys() {
        val driver = DulcetDriverFactory(":memory:").createDriver()
        try {
            assertTrue(driver.foreignKeysEnabled())
        } finally {
            driver.close()
        }
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
            assertEquals(DULCET_SCHEMA_VERSION, reopened.metadata().schemaVersion)
            assertEquals(73, reopened.metadata().committedGeneration)
            reopened.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
