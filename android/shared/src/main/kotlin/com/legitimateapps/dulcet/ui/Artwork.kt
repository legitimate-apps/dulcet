package com.legitimateapps.dulcet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
        val cacheKey = cacheKey(account, key, pixels)
        decoded.get(cacheKey)?.let { return it }
        val bytes = repository(context, account).load(key, pixels, cachedOnly) ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = decodeForDisplay(bytes, pixels) ?: return@withContext null
            val tiny = scaledSample(bitmap)
            val accentColor = averageColor(bitmap, tiny)
            accent.put(cacheKey(account, key, 0), accentColor)
            IntArray(ACCENT_SAMPLES * ACCENT_SAMPLES).also { out ->
                tiny.getPixels(out, 0, ACCENT_SAMPLES, 0, 0, ACCENT_SAMPLES, ACCENT_SAMPLES)
                samples.put(cacheKey(account, key, 0), out)
            }
            if (tiny !== bitmap) tiny.recycle()
            flattenTransparency(bitmap, accentColor).asImageBitmap().also { store(account, key, pixels, it) }
        }
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
    LaunchedEffect(account.providerInstanceId, key, pixels, cachedOnly) {
        if (key.isNullOrBlank()) return@LaunchedEffect
        if (cachedOnly) {
            runCatching { ArtworkImages.load(context, account, key, pixels, cachedOnly = true) }.getOrNull()?.let { loaded = it }
            return@LaunchedEffect
        }
        // A null is a missing cover and a failed fetch alike, and the repository remembers only the
        // missing one — so asking again is cheap when there is no cover, and recovers a tile whose
        // fetch hit a transient failure (a fresh device's first connections are reset).
        repeat(LOAD_ATTEMPTS) { attempt ->
            val fetched = ArtworkImages.cached(account, key, pixels) ?: try {
                ArtworkImages.load(context, account, key, pixels)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // leaving composition cancels the load; that is not a missing cover
            } catch (_: Exception) { null }
            if (fetched != null) { loaded = fetched; return@LaunchedEffect }
            if (attempt + 1 < LOAD_ATTEMPTS) kotlinx.coroutines.delay(LOAD_RETRY_DELAY_MILLIS)
        }
    }
    // The same cover at another size — the mini player's thumb, say — paints while the requested size
    // loads, however it got into the cache; a failed fetch never replaces it with the placeholder.
    return loaded ?: key?.takeIf { it.isNotBlank() }?.let { ArtworkImages.cachedAtAnySize(account, it) }
}

private const val LOAD_ATTEMPTS = 5
private const val LOAD_RETRY_DELAY_MILLIS = 800L
