package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase

internal const val DULCET_SCHEMA_VERSION: Long = 1
internal const val DULCET_CACHE_FORMAT_VERSION: Long = 1

internal data class DulcetSchemaMetadata(
    val schemaVersion: Long,
    val cacheFormatVersion: Long,
    val committedGeneration: Long,
)

internal class DulcetDatabaseStore private constructor(
    internal val database: DulcetDatabase,
    private val driver: SqlDriver,
) {
    internal fun metadata(): DulcetSchemaMetadata =
        database.schemaMetaQueries.selectMetadata { schemaVersion, cacheFormatVersion, generation ->
            DulcetSchemaMetadata(schemaVersion, cacheFormatVersion, generation)
        }.executeAsOne()

    internal fun updateCommittedGeneration(generation: Long) {
        require(generation >= 0)
        database.schemaMetaQueries.updateCommittedGeneration(generation)
    }

    internal fun close() {
        driver.close()
    }

    internal companion object {
        fun open(driver: SqlDriver): DulcetDatabaseStore {
            check(DulcetDatabase.Schema.version == DULCET_SCHEMA_VERSION)
            val database = DulcetDatabase(driver)
            database.schemaMetaQueries.initialize(
                schema_version = DULCET_SCHEMA_VERSION,
                cache_format_version = DULCET_CACHE_FORMAT_VERSION,
            )
            val store = DulcetDatabaseStore(database, driver)
            check(store.metadata().schemaVersion == DulcetDatabase.Schema.version)
            return store
        }
    }
}
