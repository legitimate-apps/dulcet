package com.legitimateapps.dulcet.core

import java.nio.file.Files

internal actual fun createLibrarySyncControlDatabase(): LibrarySyncControlDatabase {
    val path = Files.createTempFile("dulcet-library-sync-control-", ".db")
    Files.delete(path)
    val primary = DulcetDriverFactory(path.toString()).openDulcetDatabase()
    val observer = DulcetDriverFactory(path.toString()).openDulcetDatabase()
    return LibrarySyncControlDatabase(primary, observer) {
        Files.deleteIfExists(path)
    }
}
