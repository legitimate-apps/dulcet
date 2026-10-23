package com.legitimateapps.dulcet.core

import okio.FileSystem
import kotlin.test.*

class SavedServerMediaIsolationTest {
    @Test fun selectingAnotherSavedServerPreservesInactivePartialAndCompletedMedia() {
        val store = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-saved-media-${secureRandomBytes(8).toLowerHex()}"
        val files = DownloadFileStore(root.toString(), FileSystem.SYSTEM)
        try {
            fun bound(id: String) = DownloadPolicyEngine(store.database, files).also {
                it.bindMedia(DownloadMediaBinding(id, "https://$id.example", "alice"))
                it.reconcile(emptyList(), mapOf(id to 1L))
            }
            fun request(id: String, raw: String) = DownloadRequest(
                DownloadIdentity(id, raw, DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Mp3,
                PlaybackContentLength.Exact(AUDIO.size.toLong()),
                DownloadServerSnapshot(1, AUDIO.size.toLong()), 1, 1)
            val a = bound("a")
            val partial = a.enqueue(request("a", "partial"))
            a.writeCompletedTemporaryFile(partial.downloadId, AUDIO)
            val complete = a.enqueue(request("a", "same-id"))
            a.writeCompletedTemporaryFile(complete.downloadId, AUDIO)
            assertIs<DownloadPromotionResult.Promoted>(a.promote(complete.downloadId,
                DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(AUDIO.size.toLong()))))
            val b = bound("b")
            assertTrue(FileSystem.SYSTEM.exists(files.temporaryPath(partial)), "inactive saved transfer survives B reconciliation")
            assertTrue(FileSystem.SYSTEM.exists(files.destinationPath(complete)))
            assertIs<OfflinePlaybackPlanResult.NotDownloaded>(b.offlinePlaybackPlan(DownloadIdentity("b", "same-id", "original")))
            val returned = bound("a")
            assertIs<OfflinePlaybackPlanResult.Available>(returned.offlinePlaybackPlan(complete.identity))
            assertContentEquals(AUDIO, FileSystem.SYSTEM.read(files.destinationPath(complete)) { readByteArray() })
        } finally { store.close(); FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }
    }

    @Test fun retiringOneServerDeletesItsIndependentIdentityAndPreferencesOnly() {
        val store = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-retired-identity-${secureRandomBytes(8).toLowerHex()}"
        try {
            val identities = ServerIdentityStore(store.database)
            identities.accept("old", ServerIdentityPolicy.identity("Navidrome"))
            identities.accept("kept", ServerIdentityPolicy.identity("dulcet", "history"))
            store.database.libraryDepthQueries.putEntry("old", "search.recent", "query", "old value", 1)
            val engine = DownloadPolicyEngine(store.database, DownloadFileStore(root.toString(), FileSystem.SYSTEM))
            engine.reconcile(emptyList(), emptyMap())
            engine.removeAccountData("old")
            assertTrue(store.database.libraryDepthQueries.selectEntries("old").executeAsList().isEmpty())
            assertEquals(0L, store.database.serverDataQueries.countRowsForServer("old").executeAsOne().sum)
            assertTrue(identities.read("kept").isNotEmpty())
        } finally { store.close(); FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }
    }

    private companion object { val AUDIO = byteArrayOf(73, 68, 51, 4, 0, 0) }
}
