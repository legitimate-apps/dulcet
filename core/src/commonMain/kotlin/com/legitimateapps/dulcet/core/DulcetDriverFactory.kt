package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver

/** Platform composition owns location/context; SQLDelight types never cross an app facade. */
internal expect class DulcetDriverFactory {
    fun createDriver(): SqlDriver
}

internal fun DulcetDriverFactory.openDulcetDatabase(): DulcetDatabaseStore =
    DulcetDatabaseStore.open(createDriver())
