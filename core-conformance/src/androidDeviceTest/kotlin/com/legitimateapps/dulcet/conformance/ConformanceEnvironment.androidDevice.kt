package com.legitimateapps.dulcet.conformance

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.legitimateapps.dulcet.core.AndroidDownloadConformanceResourceProvider
import com.legitimateapps.dulcet.core.AndroidDownloadConformanceResources
import com.legitimateapps.dulcet.core.AndroidLibrarySyncConformanceDriverProvider
import com.legitimateapps.dulcet.core.AndroidLibrarySyncConformanceDrivers
import okio.FileSystem
import java.io.File
import java.util.UUID

/**
 * On a device there is no process environment to configure, so the suite's variables arrive as
 * instrumentation arguments under the same names (`-e DULCET_CONFORMANCE_BASE_URL …`).
 */
internal actual fun environmentOrNull(name: String): String? {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    AndroidLibrarySyncConformanceDriverProvider.install { createLibrarySyncControlDrivers(context) }
    AndroidDownloadConformanceResourceProvider.install { createDownloadControlResources(context) }
    return InstrumentationRegistry.getArguments().getString(name)?.takeIf(String::isNotBlank)
}

/** Creates nothing: the core creates its own schema, as it does over the host's JDBC driver. */
private object CoreCreatesItsOwnSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/** A driver over [file] with foreign keys on, matching the host driver's `foreign_keys=true`. */
private fun openDriver(context: Context, file: File): AndroidSqliteDriver = AndroidSqliteDriver(
    schema = CoreCreatesItsOwnSchema,
    context = context,
    name = file.absolutePath,
    callback = object : AndroidSqliteDriver.Callback(CoreCreatesItsOwnSchema) {
        override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
    },
)

private fun freshDirectory(context: Context, label: String): File =
    File(context.cacheDir, "dulcet-$label-${UUID.randomUUID()}").also { check(it.mkdirs()) }

private fun createLibrarySyncControlDrivers(context: Context): AndroidLibrarySyncConformanceDrivers {
    val root = freshDirectory(context, "android-library-sync-control")
    val database = File(root, "control.db")
    val primary = openDriver(context, database)
    return try {
        val observer = openDriver(context, database)
        AndroidLibrarySyncConformanceDrivers(primary, observer) { root.deleteRecursively() }
    } catch (failure: Throwable) {
        primary.close()
        root.deleteRecursively()
        throw failure
    }
}

private fun createDownloadControlResources(context: Context): AndroidDownloadConformanceResources {
    val root = freshDirectory(context, "android-download-control")
    val driver = openDriver(context, File(root, "dulcet.db"))
    return try {
        AndroidDownloadConformanceResources(
            driver = driver,
            filesRoot = File(root, "files").absolutePath,
            fileSystem = FileSystem.SYSTEM,
        ) { root.deleteRecursively() }
    } catch (failure: Throwable) {
        driver.close()
        root.deleteRecursively()
        throw failure
    }
}
