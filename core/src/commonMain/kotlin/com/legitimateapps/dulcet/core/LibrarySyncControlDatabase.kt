package com.legitimateapps.dulcet.core

internal class LibrarySyncControlDatabase(
    val primary: DulcetDatabaseStore,
    val observer: DulcetDatabaseStore,
    private val cleanup: () -> Unit,
) {
    fun close() {
        try {
            primary.close()
        } finally {
            try {
                observer.close()
            } finally {
                cleanup()
            }
        }
    }
}

internal expect fun createLibrarySyncControlDatabase(): LibrarySyncControlDatabase
