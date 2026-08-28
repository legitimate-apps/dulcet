package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertTrue

class DulcetDriverFactoryTest {
    @Test
    fun appleFactoryEnablesForeignKeys() {
        val driver = DulcetDriverFactory(
            databaseName = "dulcet-factory-test.db",
            inMemory = true,
        ).createDriver()
        try {
            assertTrue(driver.foreignKeysEnabled())
        } finally {
            driver.close()
        }
    }
}
