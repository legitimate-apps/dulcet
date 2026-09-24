package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Validated image bytes, or null when the server has none or the request failed. */
    public suspend fun load(artworkKey: String, sizePixels: Int): ByteArray? {
        if (artworkKey.isBlank()) return null
        val bucket = bucketFor(sizePixels)
        val name = digest(artworkKey + "\u0000" + bucket.pixels) + ".image"
        if (name in unavailable) return null
        val file = File(root, name)
        readCached(file)?.let { return it }
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

    private suspend fun fetch(key: String, bucket: ArtworkSizeBucket, file: File, name: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val result = fetcher.fetch(ArtworkFetchRequest(account.providerInstanceId, key, bucket,
                account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp))
            when (result) {
                is ArtworkFetchResult.Loaded -> result.bytes.also { store(file, it) }
                ArtworkFetchResult.Unavailable -> { unavailable += name; null }
                is ArtworkFetchResult.Failed -> null
            }
        }

    private suspend fun readCached(file: File): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        try {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) { null }
    }

    private fun store(file: File, bytes: ByteArray) {
        try {
            root.mkdirs()
            sweepOrphans()
            val partial = File(root, file.name + PARTIAL)
            partial.writeBytes(bytes)
            if (!partial.renameTo(file)) partial.delete()
            trim()
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
        const val DEFAULT_BUDGET_BYTES: Long = 64L * 1024 * 1024
        private const val PARTIAL = ".partial"

        internal fun bucketFor(pixels: Int): ArtworkSizeBucket =
            ArtworkSizeBucket.entries.firstOrNull { it.pixels >= pixels } ?: ArtworkSizeBucket.Px1024

        // Keys are opaque server strings: hashed only to make a safe file name, never parsed.
        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
