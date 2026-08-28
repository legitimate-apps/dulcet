package com.legitimateapps.dulcet.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DulcetDriverFactoryTest {
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
