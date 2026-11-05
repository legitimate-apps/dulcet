package com.legitimateapps.dulcet.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover art for one account, read through the core's validated `getCoverArt` fetcher (spec §18.2).
 *
 * Only bytes the fetcher accepted as an image reach the disk or a caller, so an error envelope can
 * never be cached or decoded as artwork. No URL is ever produced: presentation layers and the media
 * session receive bytes, because a signed request URL handed to the system would carry credentials
 * out of the process.
 */
public class AndroidArtworkRepository internal constructor(
    context: Context,
    private val account: PlaybackEndpointAccount,
    private val fetcher: ArtworkFetcher,
    private val budgetBytes: Long,
) {
    public constructor(context: Context, account: PlaybackEndpointAccount) :
        this(context, account, ArtworkFetcher(), DEFAULT_BUDGET_BYTES)

    private val root = File(context.applicationContext.cacheDir, "artwork/${digest(account.providerInstanceId)}")
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    // "No artwork" is remembered for this process only; a later scan may add a cover.
    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    private val undecodable = ConcurrentHashMap.newKeySet<String>()

    /**
     * Validated image bytes, or null when the server has none or the request failed. [cachedOnly] reads
     * only what this device has kept and sends nothing: an account that is saved and not connected.
     */
    public suspend fun load(artworkKey: String, sizePixels: Int, cachedOnly: Boolean = false): ByteArray? {
        if (artworkKey.isBlank()) return null
        val bucket = bucketFor(sizePixels)
        val name = fileName(artworkKey, bucket)
        if (name in unavailable) return null
        val file = File(root, name)
        readCached(file)?.let { return it }
        if (cachedOnly || AndroidServerIdentity.isRejected(account.providerInstanceId)) return null
        val mine = CompletableDeferred<ByteArray?>()
        val existing = inFlight.putIfAbsent(name, mine)
        if (existing != null) return existing.await()
        return try {
            val loaded = fetch(artworkKey, bucket, file, name)
            mine.complete(loaded)
            loaded
        } catch (error: Throwable) {
            mine.complete(null)
            throw error
        } finally {
            inFlight.remove(name, mine)
        }
    }

    /** True when the server said it has no cover for [artworkKey] at this size, earlier in this process. */
    public fun isKnownMissing(artworkKey: String, sizePixels: Int): Boolean =
        artworkKey.isNotBlank() && fileName(artworkKey, bucketFor(sizePixels)) in unavailable

    /**
     * The nearest copy of this cover the device kept at another size, the next larger first. Reads the
     * disk only, so it answers for an account awaiting Reconnect and for a server that has gone away.
     */
    public suspend fun keptAtAnotherSize(artworkKey: String, sizePixels: Int): ByteArray? {
        if (artworkKey.isBlank()) return null
        val wanted = bucketFor(sizePixels)
        val larger = ArtworkSizeBucket.entries.filter { it.pixels > wanted.pixels }
        val smaller = ArtworkSizeBucket.entries.filter { it.pixels < wanted.pixels }.reversed()
        for (bucket in larger + smaller) readCached(File(root, fileName(artworkKey, bucket)))?.let { return it }
        return null
    }

    /**
     * The presentation layer could not decode what [load] returned. The copy is dropped so the next try
     * fetches it afresh; a second such answer means the server's picture is unusable, which is treated
     * as no cover for the rest of this process rather than fetched for as long as a tile is on screen.
     */
    public fun discardUndecodable(artworkKey: String, sizePixels: Int) {
        val bucket = bucketFor(sizePixels)
        val name = fileName(artworkKey, bucket)
        File(root, name).delete()
        val second = !undecodable.add(name)
        if (second) unavailable += name
        Log.w(LOG_TAG, "cover ${name.take(LOGGED_NAME_LENGTH)} at ${bucket.pixels}: undecodable, ${if (second) "treated as missing" else "dropped"}")
    }

    /** The next size a server can be asked for this cover at: the next larger, or the largest but one. */
    public fun anotherSize(sizePixels: Int): Int {
        val wanted = bucketFor(sizePixels)
        return (ArtworkSizeBucket.entries.firstOrNull { it.pixels > wanted.pixels }
            ?: ArtworkSizeBucket.entries.last { it.pixels < wanted.pixels }).pixels
    }

    private fun fileName(artworkKey: String, bucket: ArtworkSizeBucket) =
        digest(artworkKey + "\u0000" + bucket.pixels) + ".image"

    private suspend fun fetch(key: String, bucket: ArtworkSizeBucket, file: File, name: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val authorization = AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)
                ?: return@withContext null
            val result = fetcher.fetch(ArtworkFetchRequest(account.providerInstanceId, key, bucket,
                account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp))
            currentCoroutineContext().ensureActive()
            if (authorization != AndroidServerIdentity.authorizationEpoch(account.providerInstanceId)) return@withContext null
            when (result) {
                is ArtworkFetchResult.Loaded -> result.bytes.also { store(file, it) }
                ArtworkFetchResult.Unavailable -> {
                    if (unavailable.add(name)) Log.i(LOG_TAG, "cover ${name.take(LOGGED_NAME_LENGTH)} at ${bucket.pixels}: server has none")
                    null
                }
                is ArtworkFetchResult.Failed -> {
                    // A digest and an error's kind only: the key is a server's string, and the error can carry the URL.
                    if (result.error !is DomainError.Transport.Cancelled) {
                        Log.w(LOG_TAG, "cover ${name.take(LOGGED_NAME_LENGTH)} at ${bucket.pixels}: failed, ${result.error::class.simpleName}")
                    }
                    null
                }
            }
        }

    private suspend fun readCached(file: File): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        try {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) { null }
    }

    private fun store(file: File, bytes: ByteArray) {
        // A load that finishes after its account was signed out must not recreate the account's
        // directory (spec §14.7), including removal racing the post-fetch check.
        if (AndroidAccountData.isRemoved(account.providerInstanceId)) return
        try {
            root.mkdirs()
            sweepOrphans()
            val partial = File(root, file.name + PARTIAL)
            partial.writeBytes(bytes)
            if (!partial.renameTo(file)) partial.delete()
            if (!AndroidDownloadController.trimArtwork(account.providerInstanceId, root)) trim()
        } catch (_: Exception) {
            // A cache that cannot be written only costs a later refetch.
        }
    }

    /**
     * A process killed between writing and renaming leaves a `.partial` file no reader will ever
     * open. Those from earlier processes are removed once, before this process writes its first.
     */
    @Volatile private var swept = false
    private fun sweepOrphans() {
        if (swept) return
        swept = true
        root.listFiles { entry -> entry.isFile && entry.name.endsWith(PARTIAL) }?.forEach { it.delete() }
    }

    /**
     * Least recently used first, until the directory fits its budget. Every file counts, a
     * partial one included, so nothing in the directory escapes the budget.
     */
    private fun trim() {
        val files = root.listFiles { entry -> entry.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return
        for (entry in files.sortedBy { it.lastModified() }) {
            if (total <= budgetBytes) break
            val length = entry.length()
            if (entry.delete()) total -= length
        }
    }

    internal companion object {
        private val trimLocks = ConcurrentHashMap<String, Any>()

        /** Filesystem executor only: the common policy chooses the target and pinned keys. */
        internal fun trimForPolicy(root: File, reservationBytes: Long,
            plan: (Long, Long, Long) -> ArtworkCacheBudgetPlan) = synchronized(trimLocks.getOrPut(root.path) { Any() }) {
            val protected = plan(0, 0, reservationBytes).protectedArtworkKeys
                .mapTo(mutableSetOf()) { digest(it + "\u0000" + ArtworkSizeBucket.Px256.pixels) + ".image" }
            val files = root.listFiles { file -> file.isFile }.orEmpty()
            val pinned = files.filter { it.name in protected }.sumOf(File::length)
            var evictable = files.filterNot { it.name in protected }.sumOf(File::length)
            val target = plan(evictable, pinned, reservationBytes).targetEvictableBytes
            for (file in files.filterNot { it.name in protected }.sortedBy(File::lastModified)) {
                if (evictable <= target) break
                val size = file.length()
                if (file.delete()) evictable -= size
            }
            plan(evictable, pinned, 0)
        }

        const val DEFAULT_BUDGET_BYTES: Long = 64L * 1024 * 1024
        private const val PARTIAL = ".partial"
        private const val LOG_TAG = "DulcetArtwork"
        private const val LOGGED_NAME_LENGTH = 8

        internal fun bucketFor(pixels: Int): ArtworkSizeBucket =
            ArtworkSizeBucket.entries.firstOrNull { it.pixels >= pixels } ?: ArtworkSizeBucket.Px1024

        // Keys are opaque server strings: hashed only to make a safe file name, never parsed.
        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
