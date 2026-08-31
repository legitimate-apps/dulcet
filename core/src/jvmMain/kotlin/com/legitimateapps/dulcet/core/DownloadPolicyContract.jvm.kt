package com.legitimateapps.dulcet.core

import java.nio.file.Files
import okio.FileSystem

internal actual fun createDownloadControlEnvironment(): DownloadControlEnvironment {
    val root = Files.createTempDirectory("dulcet-download-control-")
    val database = DulcetDriverFactory(root.resolve("dulcet.db").toString()).openDulcetDatabase()
    val engine = DownloadPolicyEngine(
        database.database,
        DownloadFileStore(root.resolve("files").toString(), FileSystem.SYSTEM),
    )
    return DownloadControlEnvironment(engine, database) {
        root.toFile().deleteRecursively()
    }
}
