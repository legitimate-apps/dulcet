package com.legitimateapps.dulcet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidArtworkRepository
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.SavedAccountConnection
import com.legitimateapps.dulcet.search.SearchAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the covers on screen right now are doing. [waiting] are still within their first quick tries;
 * [painted] show a picture; of those without one, [missing] are covers the server said it does not
 * have, [failed] are still being asked for after their quick tries failed, and [notKept] belong to an
 * account awaiting Reconnect on a device that never kept them. [undrawn] have their picture and have not
 * yet composed it, so a frame taken now shows the placeholder. [keyless] are items the server gave no
 * cover id at all. Every tile in composition is in exactly one bucket.
 */
public data class ArtworkActivity(
    val waiting: Int,
    val painted: Int,
    val missing: Int,
    val failed: Int,
    val notKept: Int,
    val undrawn: Int = 0,
    val keyless: Int = 0,
)

/** One tile as [ArtworkImages.tiles] reports it: which cover, at what size, and what became of it. */
public data class ArtworkTileReport(val key: String?, val pixels: Int, val state: String)

/** Decoded cover art shared by every surface in the process. Bytes come only from the core repository. */
public object ArtworkImages {
    private val decoded = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val accent = LruCache<String, Color>(512)
    private val samples = LruCache<String, IntArray>(1024)
    private var repository: Pair<String, AndroidArtworkRepository>? = null

    /**
     * Counts the decodes added to the cache. A composable that asks [cachedAtAnySize] reads it, so it
     * recomposes when another surface finishes loading the same cover at a different size.
     */
    private val revision = mutableIntStateOf(0)

    private val tiles = ConcurrentHashMap.newKeySet<ArtworkTile>()

    internal fun track(tile: ArtworkTile) { tiles += tile }

    internal fun untrack(tile: ArtworkTile) { tiles -= tile }

    internal val failedReadCount = AtomicInteger()

    /** Starts counting a cover another loader is fetching; close it when it leaves composition. */
    public fun beginLoad(): ArtworkLoad = ArtworkTile().let { tile -> track(tile); ArtworkLoad(tile) }

    /** Reads of a cover that answered nothing, since the process began: what a capture can blame on the loader. */
    public fun failedReads(): Int = failedReadCount.get()

    /** Counts for every cover in composition, read by anything that must know the page's art has landed. */
    public fun activity(): ArtworkActivity {
        val now = tiles.toList()
        val bare = now.filterNot { it.painted }
        return ArtworkActivity(
            waiting = bare.count { it.state() == ArtworkTileState.Waiting },
            painted = now.count { it.painted },
            missing = bare.count { it.state() == ArtworkTileState.Missing },
            failed = bare.count { it.state() == ArtworkTileState.Failed },
            notKept = bare.count { it.state() == ArtworkTileState.NotKept },
            undrawn = bare.count { it.state() == ArtworkTileState.Undrawn },
            keyless = bare.count { it.state() == ArtworkTileState.Keyless },
        )
    }

    /** Every tile in composition, one by one: what tells "this item has art and it is not on screen" from "this item has none". */
    public fun tiles(): List<ArtworkTileReport> =
        tiles.map { ArtworkTileReport(it.key, it.pixels, it.state().name.lowercase()) }

    @Synchronized
    private fun repository(context: Context, account: SearchAccount): AndroidArtworkRepository {
        repository?.takeIf { it.first == account.providerInstanceId }?.let { return it.second }
        return AndroidArtworkRepository(context.applicationContext, PlaybackEndpointAccount(account.providerInstanceId,
            account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp))
            .also { repository = account.providerInstanceId to it }
    }

    public fun cached(account: SearchAccount, key: String, pixels: Int): ImageBitmap? =
        decoded.get(cacheKey(account, key, pixels))

    /** Keeps a decode of [key] at [pixels] for every surface, and tells the composables waiting on that cover. */
    public fun store(account: SearchAccount, key: String, pixels: Int, image: ImageBitmap) {
        decoded.put(cacheKey(account, key, pixels), image)
        revision.intValue++
    }

    /**
     * The largest decode of [key] already in the cache at any size — a first paint while the requested size loads.
     * Read in composition, it stays current: a decode stored later, at any size, recomposes the reader.
     */
    public fun cachedAtAnySize(account: SearchAccount, key: String): ImageBitmap? {
        revision.intValue
        val prefix = account.providerInstanceId + "\u0000" + key + "\u0000"
        var best: ImageBitmap? = null
        for ((k, v) in decoded.snapshot()) {
            if (k.startsWith(prefix) && (best == null || v.width > best.width)) best = v
        }
        return best
    }

    /** [cachedOnly]: read only what this device has kept, sending nothing (an account awaiting Reconnect). */
    public suspend fun load(context: Context, account: SearchAccount, key: String, pixels: Int, cachedOnly: Boolean = false): ImageBitmap? {
        decoded.get(cacheKey(account, key, pixels))?.let { return it }
        val covers = repository(context, account)
        val bytes = covers.load(key, pixels, cachedOnly)
        if (bytes == null) {
            paintKeptCopy(covers, account, key, pixels)
            return null
        }
        val image = decodeAndKeep(account, key, bytes, pixels, pixels)
        // Bytes no decoder can read would otherwise fail from the disk on every try, for as long as they stay there.
        if (image == null) covers.discardUndecodable(key, pixels)
        return image
    }

    /** True once the server has said it has no such cover. Worth asking again only in a later process. */
    internal fun isKnownMissing(context: Context, account: SearchAccount, key: String, pixels: Int): Boolean =
        repository(context, account).isKnownMissing(key, pixels)

    /**
     * The requested size could not be had, so a copy this device kept at another size paints in its place.
     * It is kept apart from the requested size, so the tile goes on asking for the sharper picture.
     */
    private suspend fun paintKeptCopy(covers: AndroidArtworkRepository, account: SearchAccount, key: String, pixels: Int) {
        if (covers.isKnownMissing(key, pixels) || hasAnySize(account, key)) return
        val kept = covers.keptAtAnotherSize(key, pixels) ?: return
        decodeAndKeep(account, key, kept, pixels, KEPT_COPY_PIXELS)
    }

    private fun hasAnySize(account: SearchAccount, key: String): Boolean {
        val prefix = account.providerInstanceId + "\u0000" + key + "\u0000"
        return decoded.snapshot().keys.any { it.startsWith(prefix) }
    }

    private suspend fun decodeAndKeep(account: SearchAccount, key: String, bytes: ByteArray, pixels: Int, storedAs: Int): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val bitmap = decodeForDisplay(bytes, pixels) ?: return@withContext null
            val tiny = scaledSample(bitmap)
            val accentColor = averageColor(bitmap, tiny)
            accent.put(cacheKey(account, key, 0), accentColor)
            IntArray(ACCENT_SAMPLES * ACCENT_SAMPLES).also { out ->
                tiny.getPixels(out, 0, ACCENT_SAMPLES, 0, 0, ACCENT_SAMPLES, ACCENT_SAMPLES)
                samples.put(cacheKey(account, key, 0), out)
            }
            if (tiny !== bitmap) tiny.recycle()
            flattenTransparency(bitmap, accentColor).asImageBitmap().also { store(account, key, storedAs, it) }
        }

    /**
     * Decodes [bytes], a server's picture, at a size bounded by [pixels]: halved while both sides stay at
     * least [pixels], and while the longer side exceeds [MAX_SIDE_FACTOR] times it, so a long thin picture
     * cannot decode whole. A picture with no readable bounds, or absurd ones, is refused before any decode.
     */
    public fun decodeForDisplay(bytes: ByteArray, pixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE ||
            width.toLong() * height > MAX_SOURCE_PIXELS) return null
        val wanted = pixels.coerceAtLeast(1)
        val longest = maxOf(width, height)
        var sample = 1
        while ((width / (sample * 2) >= wanted && height / (sample * 2) >= wanted) || longest / sample > wanted * MAX_SIDE_FACTOR) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * The cover's 24x24 sample, decided once off the main thread when it decodes: a surface that judges
     * the picture (a server's pale stand-in portrait, say) reads this and never touches the full bitmap.
     * ARGB ints, row by row; null until the cover has decoded.
     */
    public fun sample(account: SearchAccount, key: String): IntArray? = samples.get(cacheKey(account, key, 0))

    /** A muted average of the cover, for backgrounds that follow the artwork. */
    public fun accent(account: SearchAccount, key: String): Color? = accent.get(cacheKey(account, key, 0))

    /**
     * Cover art with an alpha channel — a server's default art is often one — would float on the
     * placeholder colour behind it: composite it over the cover's own accent, so the art fills its
     * frame. Opaque covers are returned as decoded.
     */
    private fun flattenTransparency(bitmap: Bitmap, background: Color): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val edge = listOf(
            0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1, bitmap.width - 1 to bitmap.height - 1,
            bitmap.width / 2 to 0, 0 to bitmap.height / 2, bitmap.width - 1 to bitmap.height / 2,
        ).any { (x, y) -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) < 255 }
        if (!edge) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(flat)
        canvas.drawColor(background.toArgb())
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        return flat
    }

    private fun scaledSample(bitmap: Bitmap): Bitmap = Bitmap.createScaledBitmap(bitmap, ACCENT_SAMPLES, ACCENT_SAMPLES, true)

    private fun averageColor(bitmap: Bitmap, tiny: Bitmap): Color {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val hsv = FloatArray(3)
        for (y in 0 until tiny.height) {
            for (x in 0 until tiny.width) {
                val pixel = tiny.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                // A cover's field is usually white or black; the accent should come from its colour.
                if (hsv[1] < 0.18f || hsv[2] < 0.25f) continue
                red += android.graphics.Color.red(pixel)
                green += android.graphics.Color.green(pixel)
                blue += android.graphics.Color.blue(pixel)
                count++
            }
        }
        if (count > 0) {
            return Color(android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()))
        }
        val single = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val pixel = single.getPixel(0, 0)
        if (single !== bitmap) single.recycle()
        return Color(pixel)
    }

    private const val ACCENT_SAMPLES = 24
    private const val KEPT_COPY_PIXELS = -1
    private const val MAX_SIDE_FACTOR = 4
    private const val MAX_SOURCE_SIDE = 32_768
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    private fun cacheKey(account: SearchAccount, key: String, pixels: Int) =
        account.providerInstanceId + "\u0000" + key + "\u0000" + pixels
}

/** Cover art for [key] at about [pixels] square — another size's cached decode while that loads — or null when there is none. */
@Composable
public fun rememberArtwork(account: SearchAccount, key: String?, pixels: Int): ImageBitmap? {
    val context = LocalContext.current
    // A saved account awaiting Reconnect sends nothing (§13.1): its covers come from this device's
    // cache, and the ones it lacks load when the person reconnects.
    val waiting by SavedAccountConnection.waitingForReconnect.collectAsState()
    val cachedOnly = account.providerInstanceId in waiting
    var loaded by remember(account.providerInstanceId, key, pixels) {
        mutableStateOf(key?.let { ArtworkImages.cached(account, it, pixels) })
    }
    val tile = remember(account.providerInstanceId, key, pixels) {
        ArtworkTile(key, pixels, if (key.isNullOrBlank()) ArtworkOutcome.NoKey else ArtworkOutcome.Waiting)
    }
    DisposableEffect(tile) {
        ArtworkImages.track(tile)
        onDispose { ArtworkImages.untrack(tile) }
    }
    LaunchedEffect(account.providerInstanceId, key, pixels, cachedOnly) {
        if (key.isNullOrBlank()) return@LaunchedEffect
        tile.outcome = ArtworkOutcome.Waiting
        if (cachedOnly) {
            val kept = runCatching { ArtworkImages.load(context, account, key, pixels, cachedOnly = true) }.getOrNull()
            kept?.let { loaded = it }
            tile.outcome = if (kept != null) ArtworkOutcome.Answered else ArtworkOutcome.NotKept
            return@LaunchedEffect
        }
        // A null is a missing cover or a failed fetch, and the repository says which. A missing cover
        // is settled. A failed fetch is asked for again: quickly at first (a fresh device's first
        // connections are reset), then more slowly, so a server that comes back within a few minutes
        // fills in a page still on screen without the person leaving it.
        var attempt = 0
        while (true) {
            val fetched = ArtworkImages.cached(account, key, pixels) ?: try {
                ArtworkImages.load(context, account, key, pixels)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // leaving composition cancels the load; that is not a missing cover
            } catch (_: Exception) { null }
            if (fetched != null) { loaded = fetched; tile.outcome = ArtworkOutcome.Answered; return@LaunchedEffect }
            if (ArtworkImages.isKnownMissing(context, account, key, pixels)) { tile.outcome = ArtworkOutcome.Missing; return@LaunchedEffect }
            ArtworkImages.failedReadCount.incrementAndGet()
            if (attempt + 1 >= QUICK_ATTEMPTS) tile.outcome = ArtworkOutcome.Failed
            kotlinx.coroutines.delay(artworkRetryDelayMillis(attempt) ?: return@LaunchedEffect)
            attempt++
        }
    }
    // The same cover at another size — the mini player's thumb, say — paints while the requested size
    // loads, however it got into the cache; a failed fetch never replaces it with the placeholder.
    val picture = loaded ?: key?.takeIf { it.isNotBlank() }?.let { ArtworkImages.cachedAtAnySize(account, it) }
    // Dulcet Server answers an item with no art with a drawn note. It is not a cover: each surface's
    // own placeholder says what kind of thing the tile is, in the surface's colours.
    val image = picture?.takeUnless { key != null && ArtworkImages.sample(account, key)?.let(::isServerDrawnPlaceholder) == true }
    val serverDrawn = picture != null && image == null
    SideEffect {
        tile.painted = image != null
        tile.serverDrawn = serverDrawn
    }
    return image
}

/**
 * True for the picture Dulcet Server draws when an item has no art: a charcoal field holding a grey note,
 * so a 24x24 sample holds those two colours and the blends between them, and mostly the field. A dark cover
 * of a real record has some other colour in it. [pixels] are ARGB ints.
 */
internal fun isServerDrawnPlaceholder(pixels: IntArray): Boolean {
    if (pixels.isEmpty()) return false
    fun near(a: Int, b: Int) = kotlin.math.abs(a - b) <= 6
    var field = 0
    var note = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // Along the line from the field 0x2B2D33 to the note 0x6C707A.
        val along = (r - 0x2B) / (0x6C - 0x2B).toFloat()
        if (along < -0.1f || along > 1.1f) return false
        if (!near(g, (0x2D + along * (0x70 - 0x2D)).toInt()) || !near(b, (0x33 + along * (0x7A - 0x33)).toInt())) return false
        if (along < 0.1f) field++ else if (along > 0.5f) note++
    }
    return field * 2 >= pixels.size && note > 0
}

internal enum class ArtworkOutcome { Waiting, Answered, Missing, Failed, NotKept, NoKey }

internal enum class ArtworkTileState { Painted, Waiting, Undrawn, Missing, Failed, NotKept, Keyless }

/** One cover in composition, as [ArtworkImages.activity] counts it. */
internal class ArtworkTile(val key: String? = null, val pixels: Int = 0, outcome: ArtworkOutcome = ArtworkOutcome.Waiting) {
    @Volatile var outcome: ArtworkOutcome = outcome
    @Volatile var painted: Boolean = false
    /** The picture that arrived is the one Dulcet Server draws for an item with no art. */
    @Volatile var serverDrawn: Boolean = false

    fun state(): ArtworkTileState = when {
        painted -> ArtworkTileState.Painted
        serverDrawn -> ArtworkTileState.Missing
        else -> when (outcome) {
            ArtworkOutcome.Waiting -> ArtworkTileState.Waiting
            ArtworkOutcome.Answered -> ArtworkTileState.Undrawn
            ArtworkOutcome.Missing -> ArtworkTileState.Missing
            ArtworkOutcome.Failed -> ArtworkTileState.Failed
            ArtworkOutcome.NotKept -> ArtworkTileState.NotKept
            ArtworkOutcome.NoKey -> ArtworkTileState.Keyless
        }
    }
}

/** A cover loaded outside [rememberArtwork], counted with the rest in [ArtworkImages.activity]. */
public class ArtworkLoad internal constructor(private val tile: ArtworkTile) {
    public fun painted() { tile.painted = true; tile.outcome = ArtworkOutcome.Answered }
    public fun missing() { tile.outcome = ArtworkOutcome.Missing }
    public fun failed() { ArtworkImages.failedReadCount.incrementAndGet(); tile.outcome = ArtworkOutcome.Failed }
    public fun close() { ArtworkImages.untrack(tile) }
}

/** How long a tile waits after failed try [attempt] (from 0) before asking again; null when it has asked enough. */
public fun artworkRetryDelayMillis(attempt: Int): Long? = when {
    attempt < 0 -> null
    attempt < QUICK_ATTEMPTS - 1 -> QUICK_RETRY_DELAY_MILLIS
    else -> SLOW_RETRY_DELAYS_MILLIS.getOrNull(attempt - (QUICK_ATTEMPTS - 1))
}

private const val QUICK_ATTEMPTS = 5
private const val QUICK_RETRY_DELAY_MILLIS = 800L
private val SLOW_RETRY_DELAYS_MILLIS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000)
