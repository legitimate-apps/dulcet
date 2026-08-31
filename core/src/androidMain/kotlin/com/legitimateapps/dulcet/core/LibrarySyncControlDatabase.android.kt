package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase

/** Android-host driver pair supplied by the separate conformance module. */
public class AndroidLibrarySyncConformanceDrivers(
    internal val primary: SqlDriver,
    internal val observer: SqlDriver,
    internal val cleanup: () -> Unit,
)

/** Installs the file-backed drivers used only by the executable Android conformance controls. */
public object AndroidLibrarySyncConformanceDriverProvider {
    private var factory: (() -> AndroidLibrarySyncConformanceDrivers)? = null

    @Synchronized
    public fun install(factory: () -> AndroidLibrarySyncConformanceDrivers) {
        this.factory = factory
    }

    @Synchronized
    internal fun create(): AndroidLibrarySyncConformanceDrivers =
        checkNotNull(factory) {
            "Android library-sync conformance drivers were not installed"
        }.invoke()
}

internal actual fun createLibrarySyncControlDatabase(): LibrarySyncControlDatabase {
    val drivers = AndroidLibrarySyncConformanceDriverProvider.create()
    try {
        DulcetDatabase.Schema.create(drivers.primary).value
        return LibrarySyncControlDatabase(
            primary = DulcetDatabaseStore.open(drivers.primary),
            observer = DulcetDatabaseStore.open(drivers.observer),
            cleanup = drivers.cleanup,
        )
    } catch (failure: Throwable) {
        try {
            drivers.primary.close()
        } finally {
            try {
                drivers.observer.close()
            } finally {
                drivers.cleanup()
            }
        }
        throw failure
    }
}
