package com.legitimateapps.dulcet.core

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal actual fun createDownloadControlEnvironment(): DownloadControlEnvironment {
    val identity = NSUUID().UUIDString
    val root = "${NSTemporaryDirectory()}dulcet-download-control-$identity".toPath(normalize = true)
    FileSystem.SYSTEM.createDirectories(root)
    val database = DulcetDriverFactory(
        databaseName = "dulcet-download-control-$identity.db",
        inMemory = true,
    ).openDulcetDatabase()
    val engine = DownloadPolicyEngine(
        database.database,
        DownloadFileStore((root / "files").toString(), FileSystem.SYSTEM),
    )
    return DownloadControlEnvironment(engine, database) {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }
}
