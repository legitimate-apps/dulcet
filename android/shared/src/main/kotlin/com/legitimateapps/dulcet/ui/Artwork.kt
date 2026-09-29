package com.legitimateapps.dulcet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidArtworkRepository
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.search.SearchAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded cover art shared by every surface in the process. Bytes come only from the core repository. */
public object ArtworkImages {
    private val decoded = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val accent = LruCache<String, Color>(512)
    private var repository: Pair<String, AndroidArtworkRepository>? = null

    @Synchronized
    private fun repository(context: Context, account: SearchAccount): AndroidArtworkRepository {
        repository?.takeIf { it.first == account.providerInstanceId }?.let { return it.second }
        return AndroidArtworkRepository(context.applicationContext, PlaybackEndpointAccount(account.providerInstanceId,
            account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp))
            .also { repository = account.providerInstanceId to it }
    }

    public fun cached(account: SearchAccount, key: String, pixels: Int): ImageBitmap? =
        decoded.get(cacheKey(account, key, pixels))

    public suspend fun load(context: Context, account: SearchAccount, key: String, pixels: Int): ImageBitmap? {
        val cacheKey = cacheKey(account, key, pixels)
        decoded.get(cacheKey)?.let { return it }
        val bytes = repository(context, account).load(key, pixels) ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= pixels && bounds.outHeight / (sample * 2) >= pixels) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
            accent.put(cacheKey(account, key, 0), averageColor(bitmap))
            bitmap.asImageBitmap().also { decoded.put(cacheKey, it) }
        }
    }

    /** A muted average of the cover, for backgrounds that follow the artwork. */
    public fun accent(account: SearchAccount, key: String): Color? = accent.get(cacheKey(account, key, 0))

    private fun averageColor(bitmap: Bitmap): Color {
        val tiny = Bitmap.createScaledBitmap(bitmap, ACCENT_SAMPLES, ACCENT_SAMPLES, true)
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
        if (tiny !== bitmap) tiny.recycle()
        if (count > 0) {
            return Color(android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()))
        }
        val single = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val pixel = single.getPixel(0, 0)
        if (single !== bitmap) single.recycle()
        return Color(pixel)
    }

    private const val ACCENT_SAMPLES = 24

    private fun cacheKey(account: SearchAccount, key: String, pixels: Int) =
        account.providerInstanceId + "\u0000" + key + "\u0000" + pixels
}

/** Cover art for [key] at roughly [pixels] square, or null while loading and when there is none. */
@Composable
public fun rememberArtwork(account: SearchAccount, key: String?, pixels: Int): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState(key?.let { ArtworkImages.cached(account, it, pixels) }, account.providerInstanceId, key, pixels) {
        if (key.isNullOrBlank()) { value = null; return@produceState }
        // A null is a missing cover and a failed fetch alike, and the repository remembers only the
        // missing one — so asking again is cheap when there is no cover, and recovers a tile whose
        // fetch hit a transient failure (a fresh device's first connections are reset).
        repeat(LOAD_ATTEMPTS) { attempt ->
            value = ArtworkImages.cached(account, key, pixels) ?: try {
                ArtworkImages.load(context, account, key, pixels)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // leaving composition cancels the load; that is not a missing cover
            } catch (_: Exception) { null }
            if (value != null) return@produceState
            if (attempt + 1 < LOAD_ATTEMPTS) kotlinx.coroutines.delay(LOAD_RETRY_DELAY_MILLIS)
        }
    }
    return image
}

private const val LOAD_ATTEMPTS = 3
private const val LOAD_RETRY_DELAY_MILLIS = 700L
