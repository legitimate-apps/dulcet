package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Exact owned-row witness shared by real SQLite native executor proofs. */
internal fun SqlDriver.connectionOwnershipSnapshot(): Map<String, List<String>> {
    fun strings(sql: String, column: Int = 0): List<String> = executeQuery(null, sql, { cursor ->
        val values = mutableListOf<String>()
        while (cursor.next().value) values += cursor.getString(column).orEmpty()
        QueryResult.Value(values)
    }, 0).value
    return strings("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .filterNot { it in setOf("connection_enrollment", "server_route", "android_metadata") }
        .associateWith { table ->
            val columns = strings("PRAGMA table_info(\"$table\")", 1)
            strings("SELECT ${columns.joinToString(" || char(31) || ") { "quote(\"$it\")" }} FROM \"$table\"").sorted()
        }
}

/**
 * Migration fixtures intentionally contain opaque non-audio file bytes. Native reconciliation
 * correctly rejects those, so materialize only the completed audio witness before its first
 * native startup. Retention, DownloadId, queue/outbox/library state and partial files stay seeded.
 */
internal suspend fun materializeConnectionOwnershipMedia(
    store: DulcetDatabaseStore,
    root: okio.Path,
    routeBase: String,
    username: String,
    password: String,
    trackId: String,
    expectedSha256: String,
) {
    val client = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(routeBase, username, password, false),
        "connection.fixture-audio")
    val response = try { client.request("download", mapOf("id" to trackId)) } finally { client.close() }
    kotlin.test.assertEquals(200, response.statusCode, "Disposable fixture must supply valid completed audio")
    val bytes = okio.Buffer().write(response.body).readByteString()
    kotlin.test.assertEquals(expectedSha256, bytes.sha256().hex(), "Complete seed bytes must match the disposable corpus SHA-256")
    val path = root / "downloads/fixture-audio-v1.bin"
    okio.FileSystem.SYSTEM.write(path) { write(bytes) }
    store.database.downloadsQueries.markDownloadComplete(bytes.size.toLong(), bytes.size.toLong(), "download:opaque-alpha")
    store.driver.execute(null,
        "UPDATE download_policy_state SET expected_container = 'mp3' WHERE server_id = 'server:fixture-alpha' AND raw_id = 'track:download-opaque' AND transcode_profile = 'direct:audio/flac'", 0)
    // The witness includes automatic intent. A disabled setting would legitimately clear that
    // intent during startup, which would test preference reconciliation rather than route change.
    store.driver.execute(null, "UPDATE offline_library_settings SET automatic_downloads = 1 WHERE server_id = 'server:fixture-alpha'", 0)
    store.driver.execute(null, "UPDATE my_library_state SET complete = 0 WHERE server_id = 'server:fixture-alpha'", 0)
}

/** Require explicit/automatic complete and partial ownership, rather than any retention row. */
internal fun DulcetDatabaseStore.assertConnectionOwnershipMediaWitness(root: okio.Path) {
    val rows = database.downloadsQueries.selectDownloadsForServer("server:fixture-alpha").executeAsList()
    val retention = database.offlineLibraryQueries.selectServerRetentions("server:fixture-alpha").executeAsList()
    for (automatic in listOf(false, true)) for (complete in listOf(false, true)) {
        kotlin.test.assertTrue(rows.any { row ->
            (row.state in setOf("complete", "stale")) == complete && retention.any {
                it.raw_id == row.raw_id && it.transcode_profile == row.transcode_profile &&
                    (if (automatic) it.automatic_download else it.explicit_download) == 1L
            } && (if (complete) root / row.file_relative_path else root / ".tmp/${row.download_id}.partial").let { path ->
                okio.FileSystem.SYSTEM.metadataOrNull(path)?.size?.let { it > 0 } == true
            }
        }, "Native witness requires ${if (automatic) "automatic" else "explicit"} ${if (complete) "completed" else "partial"} ownership; states=${rows.map { it.download_id to it.state }} retention=${retention.map { Triple(it.transcode_profile, it.explicit_download, it.automatic_download) }}")
    }
}
