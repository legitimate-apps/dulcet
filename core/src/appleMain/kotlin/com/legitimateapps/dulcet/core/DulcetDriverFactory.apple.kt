package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.legitimateapps.dulcet.database.DulcetDatabase
import platform.Foundation.NSLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal actual class DulcetDriverFactory(
    private val databaseName: String = "dulcet.db",
    private val inMemory: Boolean = false,
) {
    actual fun createDriver(): SqlDriver = if (inMemory) {
        newDriver()
    } else {
        AppleDatabaseDrivers.acquire(databaseName, ::newDriver)
    }

    /** A committed-state observer must not join another composition's same-thread transaction. */
    internal fun createIndependentDriver(): SqlDriver = newDriver()

    private fun newDriver(): NativeSqliteDriver = NativeSqliteDriver(
        schema = DulcetDatabase.Schema,
        name = databaseName,
        onConfiguration = { configuration ->
            configuration.copy(
                inMemory = inMemory,
                extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
            )
        },
    )
}

/**
 * One native connection pool per database file in this process. Independent pools have independent
 * writers. Hosted run 37980371329 failed reader setup with SQLITE_BUSY; local controls reproduce
 * BUSY during simultaneous startup and a read-then-write transaction. NativeSqliteDriver serializes
 * writes within its pool; every composition opening this file must participate in that pool.
 * Each composition owns a lease so closing the reader cannot close playback or downloads. A
 * deliberately independent committed-state observer uses createIndependentDriver instead; another
 * process's file locks still belong to SQLite's busy handling.
 */
@OptIn(ExperimentalAtomicApi::class)
private object AppleDatabaseDrivers {
    private class Entry(val driver: NativeSqliteDriver, var owners: Int = 0)
    private val lock = NSLock()
    private val entries = mutableMapOf<String, Entry>()

    fun acquire(name: String, create: () -> NativeSqliteDriver): SqlDriver {
        lock.lock()
        try {
            val entry = entries.getOrPut(name) { Entry(create()) }
            entry.owners += 1
            return object : SqlDriver by entry.driver {
                private val closed = AtomicBoolean(false)

                override fun close() {
                    if (closed.compareAndSet(false, true)) release(name, entry)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun release(name: String, entry: Entry) {
        lock.lock()
        try {
            check(entries[name] === entry && entry.owners > 0)
            entry.owners -= 1
            if (entry.owners == 0) {
                entries.remove(name)
                entry.driver.close()
            }
        } finally {
            lock.unlock()
        }
    }
}
