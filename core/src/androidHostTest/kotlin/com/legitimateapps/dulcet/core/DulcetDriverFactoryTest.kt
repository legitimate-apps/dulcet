package com.legitimateapps.dulcet.core

import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DulcetDriverFactoryTest {
    @Test
    fun androidFactoryEnablesForeignKeys() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "dulcet-factory-test.db"
        context.deleteDatabase(databaseName)
        val driver = DulcetDriverFactory(context, databaseName).createDriver()
        try {
            assertTrue(driver.foreignKeysEnabled())
        } finally {
            driver.close()
            context.deleteDatabase(databaseName)
        }
    }
}
