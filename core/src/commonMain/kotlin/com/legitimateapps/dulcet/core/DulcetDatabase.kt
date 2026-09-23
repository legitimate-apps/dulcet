package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase

internal const val DULCET_SCHEMA_VERSION: Long = 6
internal const val DULCET_CACHE_FORMAT_VERSION: Long = 1

internal data class DulcetSchemaMetadata(
    val schemaVersion: Long,
    val cacheFormatVersion: Long,
    val committedGeneration: Long,
)

internal class DulcetDatabaseStore private constructor(
    internal val database: DulcetDatabase,
    internal val driver: SqlDriver,
) {
    internal fun metadata(): DulcetSchemaMetadata =
        database.schemaMetaQueries.selectMetadata { schemaVersion, cacheFormatVersion, generation ->
            DulcetSchemaMetadata(schemaVersion, cacheFormatVersion, generation)
        }.executeAsOne()

    internal fun updateCommittedGeneration(generation: Long) {
        require(generation >= 0)
        database.schemaMetaQueries.updateCommittedGeneration(generation)
    }

    internal fun reconcileVersions(schemaVersion: Long, cacheFormatVersion: Long) {
        val current = metadata()
        check(current.schemaVersion <= schemaVersion) {
            "Dulcet database schema is newer than this build"
        }
        check(current.cacheFormatVersion <= cacheFormatVersion) {
            "Dulcet cache format is newer than this build"
        }
        if (current.schemaVersion < schemaVersion) {
            database.schemaMetaQueries.updateSchemaVersion(schemaVersion)
        }
        if (current.cacheFormatVersion < cacheFormatVersion) {
            database.schemaMetaQueries.updateCacheFormatVersion(cacheFormatVersion)
        }
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
            database.transaction {
                database.libraryQueries.initializeSearchIndex()
                if (database.libraryQueries.selectSearchIndexVersion().executeAsOne() < 1) {
                    backfillSearchIndex(database)
                    database.libraryQueries.completeSearchIndexBackfill()
                }
                // The seen-cache (spec §16.10). Rows seeded from the mirror by the additive
                // migration carry whatever normalization the mirror had, which may be none; the
                // shared normalizer runs here, because SQLite cannot NFKD or case-fold text.
                database.seenCacheQueries.initializeIssueCounter()
                backfillSeenCacheNormalization(database)
                store.reconcileVersions(
                    schemaVersion = DulcetDatabase.Schema.version,
                    cacheFormatVersion = DULCET_CACHE_FORMAT_VERSION,
                )
                check(store.metadata().schemaVersion == DulcetDatabase.Schema.version)
                check(store.metadata().cacheFormatVersion == DULCET_CACHE_FORMAT_VERSION)
            }
            return store
        }
    }
}
