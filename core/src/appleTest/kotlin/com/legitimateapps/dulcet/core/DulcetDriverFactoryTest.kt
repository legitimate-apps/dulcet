package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertTrue
import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Deferred
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

class DulcetDriverFactoryTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun aFailedStoreOpenReleasesItsHandleBeforeTheFileIsReopened() {
        val name = "dulcet-failed-open-${NSUUID().UUIDString}.db"
        val original = DulcetDriverFactory(name).openDulcetDatabase()
        val path = original.driver.executeQuery(null, "PRAGMA database_list", { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getString(2)))
        }, 0).value
        fun removeFiles() {
            listOf("", "-wal", "-shm").forEach {
                NSFileManager.defaultManager.removeItemAtPath(path + it, null)
            }
        }
        try {
            original.database.schemaMetaQueries.updateCacheFormatVersion(DULCET_CACHE_FORMAT_VERSION + 1)
            assertFailsWith<IllegalStateException> { DulcetDriverFactory(name).openDulcetDatabase() }
            original.close()
            removeFiles()
            val reopened = DulcetDriverFactory(name).openDulcetDatabase()
            try {
                assertEquals(DULCET_CACHE_FORMAT_VERSION, reopened.metadata().cacheFormatVersion)
            } finally {
                reopened.close()
            }
        } finally {
            original.close()
            removeFiles()
        }
    }

    @OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)
    @Test
    fun aCompetingCompositionCannotInvalidateAReadThenWriteTransaction() = runBlocking {
        val name = "dulcet-transaction-${NSUUID().UUIDString}.db"
        val first = DulcetDriverFactory(name).openDulcetDatabase()
        val second = DulcetDriverFactory(name).openDulcetDatabase()
        val path = first.driver.executeQuery(null, "PRAGMA database_list", { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getString(2)))
        }, 0).value
        var writer: Deferred<Unit>? = null
        val state = AtomicInt(0)
        try {
            first.database.transaction {
                // Establish the reader's snapshot, as cache binding does during setup.
                first.metadata()
                writer = async(Dispatchers.Default) {
                    state.store(1)
                    second.updateCommittedGeneration(2)
                    state.store(2)
                }
                val started = TimeSource.Monotonic.markNow()
                while (state.load() == 0) {
                    check(started.elapsedNow() < 5.seconds) { "writer did not start" }
                    platform.posix.usleep(1_000u)
                }
                // With independent pools the write commits inside our snapshot. With one
                // pool it waits for this transaction: neither case needs a server or timeout change.
                val observed = TimeSource.Monotonic.markNow()
                while (state.load() != 2 && observed.elapsedNow() < 250.milliseconds) {
                    platform.posix.usleep(1_000u)
                }
                println("Competing writer committed inside snapshot=${state.load() == 2}")
                first.updateCommittedGeneration(1)
            }
            writer?.await()
            assertEquals(2L, first.metadata().committedGeneration)
            // Closing one composition, even twice, must not close another's database.
            first.close()
            first.close()
            second.updateCommittedGeneration(3)
            assertEquals(3L, second.metadata().committedGeneration)
        } finally {
            writer?.await()
            first.close()
            second.close()
            listOf("", "-wal", "-shm").forEach {
                NSFileManager.defaultManager.removeItemAtPath(path + it, null)
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)
    @Test
    fun concurrentStartupCompositionsOpenTheSameFreshDatabase() = runBlocking {
        repeat(25) {
            val name = "dulcet-startup-${NSUUID().UUIDString}.db"
            val locator = DulcetDriverFactory(name).createDriver()
            val path = locator.executeQuery(null, "PRAGMA database_list", { cursor ->
                check(cursor.next().value)
                QueryResult.Value(requireNotNull(cursor.getString(2)))
            }, 0).value
            locator.close()
            fun removeFiles() {
                listOf("", "-wal", "-shm").forEach { suffix ->
                    NSFileManager.defaultManager.removeItemAtPath(path + suffix, null)
                }
            }
            removeFiles()
            val ready = AtomicInt(0)
            try {
                val outcomes = (0..1).map { index ->
                    async(Dispatchers.Default) {
                        ready.addAndFetch(1)
                        while (ready.load() < 2) platform.posix.usleep(1_000u)
                        var stage = "driver"
                        var driver: app.cash.sqldelight.db.SqlDriver? = null
                        try {
                            driver = DulcetDriverFactory(name).createDriver()
                            stage = "store"
                            val store = DulcetDatabaseStore.open(driver)
                            if (index == 0) {
                                stage = "bind"
                                SeenCacheStore(store, object : SeenCacheWallClock {
                                    override fun nowEpochMilliseconds() = 1000L
                                }).bind(CacheBinding("startup", "http://127.0.0.1", "canary"))
                            }
                            "opened"
                        } catch (failure: Throwable) {
                            val code = (failure as? co.touchlab.sqliter.interop.SQLiteExceptionErrorCode)?.errorType
                            "$stage:${code?.name}:${code?.code}"
                        } finally {
                            driver?.close()
                        }
                    }
                }.awaitAll()
                assertEquals(listOf("opened", "opened"), outcomes)
            } finally {
                removeFiles()
            }
        }
    }

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
