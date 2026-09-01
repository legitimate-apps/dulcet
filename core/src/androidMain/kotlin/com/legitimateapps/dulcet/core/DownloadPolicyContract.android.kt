package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import okio.FileSystem

/**
 * Android-host download-control resources supplied by the separate conformance module.
 *
 * The JVM actual builds its own driver from a temp path, and Android cannot: its production
 * [DulcetDriverFactory] needs a `Context`, which no common-source control can obtain. Rather than
 * declare the controls unavailable here -- the shape that made CONF-31/32/33 silently unrunnable on
 * Android until it was caught by a live run -- the conformance module installs file-backed resources
 * the same way it does for library sync.
 */
public class AndroidDownloadConformanceResources(
    internal val driver: SqlDriver,
    internal val filesRoot: String,
    internal val fileSystem: FileSystem,
    internal val cleanup: () -> Unit,
)

/** Installs the file-backed resources used only by the executable Android download controls. */
public object AndroidDownloadConformanceResourceProvider {
    private var factory: (() -> AndroidDownloadConformanceResources)? = null

    @Synchronized
    public fun install(factory: () -> AndroidDownloadConformanceResources) {
        this.factory = factory
    }

    @Synchronized
    internal fun create(): AndroidDownloadConformanceResources =
        checkNotNull(factory) {
            "Android download conformance resources were not installed"
        }.invoke()
}

internal actual fun createDownloadControlEnvironment(): DownloadControlEnvironment {
    val resources = AndroidDownloadConformanceResourceProvider.create()
    try {
        DulcetDatabase.Schema.create(resources.driver).value
        val store = DulcetDatabaseStore.open(resources.driver)
        return DownloadControlEnvironment(
            engine = DownloadPolicyEngine(
                store.database,
                DownloadFileStore(resources.filesRoot, resources.fileSystem),
            ),
            database = store,
            cleanup = resources.cleanup,
        )
    } catch (failure: Throwable) {
        try {
            resources.driver.close()
        } finally {
            resources.cleanup()
        }
        throw failure
    }
}
