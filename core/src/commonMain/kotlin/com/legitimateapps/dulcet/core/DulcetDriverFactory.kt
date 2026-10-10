package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver

/** Platform composition owns location/context; SQLDelight types never cross an app facade. */
internal expect class DulcetDriverFactory {
    fun createDriver(): SqlDriver
}

internal fun DulcetDriverFactory.openDulcetDatabase(): DulcetDatabaseStore {
    val driver = createDriver()
    return try {
        DulcetDatabaseStore.open(driver)
    } catch (failure: Throwable) {
        // A failed composition owns this handle too. In particular, it must not retain an
        // Apple pool lease when setup fails before a store can take ownership of it.
        try {
            driver.close()
        } catch (_: Throwable) {
        }
        throw failure
    }
}
