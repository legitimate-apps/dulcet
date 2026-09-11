package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.legitimateapps.dulcet.database.DulcetDatabase
import platform.Foundation.NSUUID

/**
 * 🚨 **The name must be unique per driver, and `inMemory` is not what makes it private.**
 *
 * SQLiter shares an in-memory database *by name* across the process. With one fixed name every
 * test in the suite connected to the same database, and the suite passed only because each test
 * happened to close its driver — the last close dropped the database, which looked like isolation
 * and was not.
 *
 * MEASURED 2026-09-11: one sync test that forgot a single `close()` failed **26 tests across six
 * unrelated classes**, none of them the test that leaked. And a test that *fails* leaks too, since
 * the assertion throws before its `close()` line — so the first real failure in a run contaminates
 * everything after it, which is the worst possible time to lose isolation.
 *
 * A unique name makes a leak unable to reach another test at all, rather than making tests
 * responsible for cleanup they cannot guarantee on their failure path. `twoTestDriversAreIndependentDatabases`
 * is the control; it fails against a fixed name.
 *
 * The JVM driver is an anonymous `IN_MEMORY` connection and already had this property, which is
 * why the defect was invisible to `:core:jvmTest`.
 */
internal actual fun createTestDriver(): SqlDriver = NativeSqliteDriver(
    schema = DulcetDatabase.Schema,
    name = "dulcet-test-${NSUUID().UUIDString}.db",
    onConfiguration = { configuration ->
        configuration.copy(
            inMemory = true,
            extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
        )
    },
)
