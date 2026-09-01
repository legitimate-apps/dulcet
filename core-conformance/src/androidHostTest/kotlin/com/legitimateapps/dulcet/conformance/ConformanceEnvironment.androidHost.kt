package com.legitimateapps.dulcet.conformance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.core.AndroidDownloadConformanceResourceProvider
import com.legitimateapps.dulcet.core.AndroidDownloadConformanceResources
import com.legitimateapps.dulcet.core.AndroidLibrarySyncConformanceDriverProvider
import com.legitimateapps.dulcet.core.AndroidLibrarySyncConformanceDrivers
import java.nio.file.Files
import okio.FileSystem
import java.util.Properties

internal actual fun environmentOrNull(name: String): String? {
    AndroidLibrarySyncConformanceDriverProvider.install(::createLibrarySyncControlDrivers)
    AndroidDownloadConformanceResourceProvider.install(::createDownloadControlResources)
    return System.getenv(name)?.takeIf(String::isNotBlank)
}

private fun createLibrarySyncControlDrivers(): AndroidLibrarySyncConformanceDrivers {
    val path = Files.createTempFile("dulcet-android-library-sync-control-", ".db")
    Files.delete(path)
    val properties = Properties().apply { put("foreign_keys", "true") }
    val primary = JdbcSqliteDriver("jdbc:sqlite:$path", properties)
    return try {
        val observer = JdbcSqliteDriver("jdbc:sqlite:$path", properties)
        AndroidLibrarySyncConformanceDrivers(primary, observer) {
            Files.deleteIfExists(path)
        }
    } catch (failure: Throwable) {
        primary.close()
        Files.deleteIfExists(path)
        throw failure
    }
}

private fun createDownloadControlResources(): AndroidDownloadConformanceResources {
    val root = Files.createTempDirectory("dulcet-android-download-control-")
    val databasePath = root.resolve("dulcet.db")
    val properties = Properties().apply { put("foreign_keys", "true") }
    val driver = JdbcSqliteDriver("jdbc:sqlite:$databasePath", properties)
    return try {
        AndroidDownloadConformanceResources(
            driver = driver,
            filesRoot = root.resolve("files").toString(),
            fileSystem = FileSystem.SYSTEM,
        ) {
            root.toFile().deleteRecursively()
        }
    } catch (failure: Throwable) {
        driver.close()
        root.toFile().deleteRecursively()
        throw failure
    }
}
