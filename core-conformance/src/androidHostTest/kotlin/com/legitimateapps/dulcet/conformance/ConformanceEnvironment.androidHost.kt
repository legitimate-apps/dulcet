package com.legitimateapps.dulcet.conformance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.core.AndroidLibrarySyncConformanceDriverProvider
import com.legitimateapps.dulcet.core.AndroidLibrarySyncConformanceDrivers
import java.nio.file.Files
import java.util.Properties

internal actual fun environmentOrNull(name: String): String? {
    AndroidLibrarySyncConformanceDriverProvider.install(::createLibrarySyncControlDrivers)
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
