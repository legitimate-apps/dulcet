package com.legitimateapps.dulcet.core

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*

/** Binding acceptance owned separately from the implementation; exact byte ownership is the oracle. */
class OfflineMediaBindingSafetyTest {
    @Test fun changingEndpointOrUsernameCannotPlayThePreviousBindingsExplicitOrCachedBytes() = fixture { f ->
        for ((index, binding) in listOf(
            DownloadMediaBinding("account", "https://other.invalid", "alice"),
            DownloadMediaBinding("account", "https://music.invalid", "bob"),
        ).withIndex()) {
            val before = f.bound(DownloadMediaBinding("account", "https://music.invalid", "alice"))
            SeenCacheStore(f.store, SeenCacheWallClock { 1 }).bind(CacheBinding("account", "https://music.invalid", "alice"))
            val explicit = promote(before, "explicit-$index")
            val policy = OfflineLibraryPolicy(f.store.database, before)
            val write = assertNotNull(policy.beginCacheWrite(request("cached-$index"), 200))
            assertTrue(policy.appendCacheWrite(write.writeId, AUDIO, 0))
            assertIs<DownloadPromotionResult.Promoted>(policy.finishCacheWrite(write.writeId, response(), 3))
            val cached = assertIs<OfflinePlaybackPlanResult.Available>(policy.offlinePlaybackPlan("account", "cached-$index", 4)).plan
            val explicitPath = assertIs<OfflinePlaybackPlanResult.Available>(before.offlinePlaybackPlan(explicit.identity)).plan.absolutePath
            // A reader may rebind first; its cache purge must not erase media's independent owner.
            SeenCacheStore(f.store, SeenCacheWallClock { 5 }).bind(CacheBinding("account", binding.normalizedBaseUrl, binding.username))
            val after = f.bound(binding)
            val newPolicy = OfflineLibraryPolicy(f.store.database, after)
            assertIs<OfflinePlaybackPlanResult.NotDownloaded>(newPolicy.offlinePlaybackPlan("account", "explicit-$index", 6), "new binding must not resolve old explicit bytes")
            assertIs<OfflinePlaybackPlanResult.NotDownloaded>(newPolicy.offlinePlaybackPlan("account", "cached-$index", 6), "new binding must not resolve old cache bytes")
            assertFalse(FileSystem.SYSTEM.exists(explicitPath.toPath()), "the chosen rebind policy purges the old explicit payload")
            assertFalse(FileSystem.SYSTEM.exists(cached.absolutePath.toPath()), "the chosen rebind policy purges the old cached payload")
        }
    }

    @Test fun reopeningTheSameBindingKeepsExplicitOwnershipAndExactBytes() = fixture { f ->
        val binding = DownloadMediaBinding("account", "https://music.invalid", "alice")
        val first = f.bound(binding)
        val row = promote(first, "same")
        val reopened = f.bound(binding)
        val policy = OfflineLibraryPolicy(f.store.database, reopened)
        policy.reconcileAutomaticDownloads("account", listOf(AutomaticDownloadMember(request("same"), 3)))
        policy.reconcileAutomaticDownloads("account", emptyList())
        policy.setAutomaticDownloadsEnabled("account", false)
        val plan = assertIs<OfflinePlaybackPlanResult.Available>(policy.offlinePlaybackPlan("account", "same", 4)).plan
        assertEquals(row.downloadId, plan.downloadId)
        assertContentEquals(AUDIO, FileSystem.SYSTEM.read(plan.absolutePath.toPath()) { readByteArray() })
    }

    @Test fun oldExecutorsAndInflightWritesStayRevokedEvenAfterTheBindingReturns() = fixture { f ->
        val a = DownloadMediaBinding("account", "https://music.invalid", "alice")
        val old = f.bound(a)
        val oldPolicy = OfflineLibraryPolicy(f.store.database, old)
        val pending = old.enqueue(request("pending"))
        old.writeCompletedTemporaryFile(pending.downloadId, AUDIO)
        val write = assertNotNull(oldPolicy.beginCacheWrite(request("stream"), 200))
        assertTrue(oldPolicy.appendCacheWrite(write.writeId, AUDIO, 0))
        f.bound(DownloadMediaBinding("account", "https://music.invalid", "bob"))
        val returned = f.bound(a)
        assertFalse(old.isMediaBindingCurrent())
        assertTrue(runCatching { old.enqueue(request("new-old-work")) }.isFailure, "an old controller must not recreate old media")
        assertFalse(runCatching { old.promote(pending.downloadId, response()) }.getOrNull() is DownloadPromotionResult.Promoted)
        assertFalse(runCatching { oldPolicy.finishCacheWrite(write.writeId, response(), 9) }.getOrNull() is DownloadPromotionResult.Promoted)
        assertIs<OfflinePlaybackPlanResult.NotDownloaded>(OfflineLibraryPolicy(f.store.database, returned).offlinePlaybackPlan("account", "stream", 10))
        assertIs<OfflinePlaybackPlanResult.NotDownloaded>(returned.offlinePlaybackPlan(request("pending").identity))
    }

    @Test fun artistMembershipKeepsMetadataWithoutDownloadingADiscography() = fixture { f ->
        val engine = f.bound(DownloadMediaBinding("account", "https://music.invalid", "alice"))
        val library = f.store.database.myLibraryQueries
        library.ensureState("account")
        library.updateRead(null, 1, 1, 1, "account")
        library.upsertItem("account", "artist", "artist-only", 1, 9, 1)
        val policy = OfflineLibraryPolicy(f.store.database, engine)
        val changes = policy.reconcileFromSeenCache("account", 1, 2)
        assertTrue(changes.queued.isEmpty())
        assertTrue(engine.records("account").isEmpty())
        assertTrue(policy.missingAutomaticDownloadAlbumIds("account").isEmpty())
        assertTrue(policy.missingAutomaticDownloadTrackIds("account").isEmpty())
        assertEquals(1L, library.selectItem("account", "artist", "artist-only").executeAsOne().member)
    }

    @Test fun mediaWithoutProvenBindingIsPurgedInsteadOfAdoptedByTheFirstAccount() = fixture { f ->
        val legacy = DownloadPolicyEngine(f.store.database, f.files)
        legacy.reconcile(emptyList(), mapOf("account" to 1L))
        val row = promote(legacy, "legacy")
        val path = assertIs<OfflinePlaybackPlanResult.Available>(legacy.offlinePlaybackPlan(row.identity)).plan.absolutePath
        val bound = f.bound(DownloadMediaBinding("account", "https://music.invalid", "alice"))
        assertIs<OfflinePlaybackPlanResult.NotDownloaded>(bound.offlinePlaybackPlan(row.identity))
        assertFalse(FileSystem.SYSTEM.exists(path.toPath()))
    }

    @Test fun dnsCaseKeepsLegacyExplicitBytes() = equivalentLegacyUrl("https://MUSIC.invalid", "https://music.invalid")
    @Test fun defaultHttpsPortKeepsLegacyExplicitBytes() = equivalentLegacyUrl("https://music.invalid:443", "https://music.invalid/")
    @Test fun defaultHttpPortKeepsLegacyExplicitBytes() = equivalentLegacyUrl("http://music.invalid:80/base/", "http://MUSIC.invalid/base")
    @Test fun trailingBaseSlashKeepsLegacyExplicitBytes() = equivalentLegacyUrl("https://music.invalid/base/", "https://music.invalid/base")
    @Test fun punycodeDnsCaseKeepsLegacyExplicitBytes() = equivalentLegacyUrl("https://XN--BCHER-KVA.invalid:443/music", "https://xn--bcher-kva.invalid/music/")
    @Test fun equivalentIpv6KeepsLegacyExplicitBytes() = equivalentLegacyUrl("https://[2001:0DB8:0:0:0:0:0:1]:443/base/", "https://[2001:db8::1]/base")
    @Test fun escapedPathAndInteriorSlashesKeepLegacyExplicitBytes() = equivalentLegacyUrl("https://music.invalid/Some%2FPath//Inside/", "https://MUSIC.invalid:443/Some%2FPath//Inside")
    private fun equivalentLegacyUrl(legacy: String, canonical: String) = fixture { f ->
            // Seed the pre-upgrade JSON spelling explicitly; new encoders must not hide this case.
            val epoch = "[\"$legacy\",\"alice\",\"0123456789abcdef0123456789abcdef\"]"
            f.store.database.libraryDepthQueries.putEntry("account", MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY, epoch, 0)
            val first = f.bound(DownloadMediaBinding("account", legacy, "alice"))
            val row = promote(first, "kept")
            val beforeEpoch = f.store.database.libraryDepthQueries.selectEntry("account", MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne()
            assertEquals(epoch, beforeEpoch, "legacy epoch must be adopted, not regenerated")
            SeenCacheStore(f.store, SeenCacheWallClock { 1 }).bind(CacheBinding("account", legacy, "alice"))
            val seen = SeenCacheStore(f.store, SeenCacheWallClock { 2 }).bind(CacheBinding("account", canonical, "alice"))
            val reopened = f.bound(DownloadMediaBinding("account", canonical, "alice"))
            val plan = assertIs<OfflinePlaybackPlanResult.Available>(reopened.offlinePlaybackPlan(row.identity), "$legacy -> $canonical").plan
            assertFalse(seen.purgedOnBind, "equivalent endpoint must keep metadata: $legacy -> $canonical")
            assertEquals(row.downloadId, plan.downloadId)
            assertContentEquals(AUDIO, FileSystem.SYSTEM.read(plan.absolutePath.toPath()) { readByteArray() })
            assertTrue(first.isMediaBindingCurrent(), "equivalent refresh must not revoke a current executor")
            assertEquals(epoch, f.store.database.libraryDepthQueries.selectEntry("account", MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne())
    }

    @Test fun genuinelyDifferentOriginOrPathPurgesExplicitBytes() {
        for (replacement in listOf(
            "https://other.invalid/base", "https://music.invalid:444/base", "http://music.invalid/base",
            "https://music.invalid/BASE", "https://music.invalid/other", "https://music.invalid/base/child",
            "https://music.invalid/base%2Fchild", "https://[2001:db8::2]/base",
        )) fixture { f ->
            val old = f.bound(DownloadMediaBinding("account", "https://music.invalid/base", "alice"))
            val row = promote(old, "changed")
            val path = assertIs<OfflinePlaybackPlanResult.Available>(old.offlinePlaybackPlan(row.identity)).plan.absolutePath
            val next = f.bound(DownloadMediaBinding("account", replacement, "alice"))
            assertIs<OfflinePlaybackPlanResult.NotDownloaded>(next.offlinePlaybackPlan(row.identity), replacement)
            assertFalse(FileSystem.SYSTEM.exists(path.toPath()), replacement)
            assertFalse(old.isMediaBindingCurrent(), replacement)
        }
    }

    @Test fun invalidOrUnsupportedReplacementCannotDeleteTheCurrentOwnersExplicitFile() {
        for (url in listOf("https://bücher.invalid", "https://bad.invalid/path?query=1", "not a server URL")) fixture { f ->
            val old = f.bound(DownloadMediaBinding("account", "https://music.invalid", "alice"))
            val row = promote(old, "kept-on-invalid-input")
            val plan = assertIs<OfflinePlaybackPlanResult.Available>(old.offlinePlaybackPlan(row.identity)).plan
            assertFailsWith<IllegalArgumentException>(url) { f.bound(DownloadMediaBinding("account", url, "alice")) }
            assertTrue(old.isMediaBindingCurrent(), url)
            assertContentEquals(AUDIO, FileSystem.SYSTEM.read(plan.absolutePath.toPath()) { readByteArray() })
            assertIs<OfflinePlaybackPlanResult.Available>(old.offlinePlaybackPlan(row.identity), url)
        }
    }

    @Test fun unconfirmedCaseDistinctUsernamesRemainDifferentOwners() = fixture { f ->
        val old = f.bound(DownloadMediaBinding("account", "https://music.invalid", "Alice"))
        val row = promote(old, "case-sensitive-user")
        val path = assertIs<OfflinePlaybackPlanResult.Available>(old.offlinePlaybackPlan(row.identity)).plan.absolutePath
        val next = f.bound(DownloadMediaBinding("account", "https://music.invalid", "alice"))
        assertIs<OfflinePlaybackPlanResult.NotDownloaded>(next.offlinePlaybackPlan(row.identity))
        assertFalse(FileSystem.SYSTEM.exists(path.toPath()))
    }

    private fun promote(engine: DownloadPolicyEngine, id: String): DownloadRecord {
        val row = engine.enqueue(request(id))
        engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
        assertIs<DownloadPromotionResult.Promoted>(engine.promote(row.downloadId, response()))
        return row
    }
    private fun request(raw: String) = DownloadRequest(DownloadIdentity("account", raw, "original"), AudioContainer.Mp3,
        PlaybackContentLength.Exact(AUDIO.size.toLong()), DownloadServerSnapshot(1, AUDIO.size.toLong()), 1, 1)
    private fun response() = DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(AUDIO.size.toLong()))
    private fun fixture(block: (Fixture) -> Unit) {
        val store = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-media-binding-${secureRandomBytes(8).toLowerHex()}"
        try { block(Fixture(store, DownloadFileStore(root.toString(), FileSystem.SYSTEM))) }
        finally { store.close(); FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }
    }
    private data class Fixture(val store: DulcetDatabaseStore, val files: DownloadFileStore) {
        fun bound(binding: DownloadMediaBinding) = DownloadPolicyEngine(store.database, files).also {
            it.bindMedia(binding)
            it.reconcile(emptyList(), mapOf(binding.serverId to 1L))
        }
    }
    private companion object { val AUDIO = byteArrayOf(73, 68, 51, 4, 0, 0) }
}
