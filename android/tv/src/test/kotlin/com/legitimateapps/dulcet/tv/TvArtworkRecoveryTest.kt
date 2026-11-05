package com.legitimateapps.dulcet.tv

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.legitimateapps.dulcet.core.AndroidArtworkRepository
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.ArtworkImages
import com.legitimateapps.dulcet.ui.artworkRetryDelayMillis
import com.legitimateapps.dulcet.ui.artworkTryAtAnotherSize
import com.legitimateapps.dulcet.ui.rememberArtwork
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a cover does when the first answers are not a picture: a tile on screen must not hold the
 * placeholder for as long as it stays there because a server was away for a few seconds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvArtworkRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Volatile private var lastQuery = ""
    private val requests = AtomicInteger()
    private var respond: (Int) -> Pair<Int, ByteArray> = { 200 to png() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            lastQuery = exchange.requestURI.query.orEmpty()
            val (status, body) = respond(requests.incrementAndGet())
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val id = "provider:recovery-${System.nanoTime()}"
    private val account = SearchAccount(id, "http://127.0.0.1:${server.address.port}", "u", "p", true)

    @After fun stop() = server.stop(0)

    private fun png(size: Int = 64): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun painted() = compose.onNodeWithTag("cover").fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription].single()

    /** Moves the composition's clock in steps, with real time between them for the real requests to finish. */
    private fun advanceUntil(virtualMillis: Long, done: () -> Boolean): Boolean {
        var elapsed = 0L
        while (elapsed < virtualMillis) {
            compose.mainClock.advanceTimeBy(500)
            elapsed += 500
            Thread.sleep(25)
            compose.waitForIdle()
            if (done()) return true
        }
        return done()
    }

    @Test fun aCoverStillFailingAfterTheQuickTriesPaintsOnceTheServerAnswers() {
        respond = { n -> if (n <= 8) 503 to ByteArray(0) else 200 to png() }
        compose.setContent { Probe("flapping") }
        assertTrue(advanceUntil(120_000) { painted() != "none" },
            "The placeholder stayed after ${requests.get()} requests; the server was answering from the ninth")
        assertEquals("64", painted())
    }

    @Test fun aStoredAnswerNoDecoderCanReadIsFetchedAgainNotServedForever() {
        val signedButBroken = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        respond = { n -> if (n == 1) 200 to signedButBroken else 200 to png() }
        compose.setContent { Probe("corrupt") }
        assertTrue(advanceUntil(30_000) { painted() != "none" }, "Stuck on the broken copy after ${requests.get()} requests")
        assertEquals(2, requests.get())
    }

    @Test fun aSizeTheServerKeepsAnsweringBadlyGivesWayToAnotherSize() {
        respond = { if ("size=256" in lastQuery) 200 to ByteArray(0) else 200 to png() }
        compose.setContent { Probe("one-size-bad") }
        assertTrue(advanceUntil(30_000) { painted() != "none" }, "Still the placeholder after ${requests.get()} requests at one size")
        assertEquals("64", painted())
    }

    @Test fun aTileAsksAtTheSizeItWantsExceptOnEveryOtherSlowTry() {
        val quick = (0 until 5).count(::artworkTryAtAnotherSize)
        assertEquals(0, quick, "The quick tries are for connections a fresh device has reset, and they ask for the size wanted")
        assertTrue(artworkTryAtAnotherSize(5))
        assertTrue(!artworkTryAtAnotherSize(6), "It goes back to the size wanted, which is the one it stores")
    }

    /** Dulcet Server's picture for an item with no art, drawn as `server/.../media/Artwork.kt` draws it. */
    private fun drawnNote(edge: Int, field: Int = 0xFF2B2D33.toInt(), mark: Int = 0xFF6C707A.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(field)
        val paint = android.graphics.Paint().apply { color = mark; isAntiAlias = true }
        val unit = edge / 10f
        canvas.drawOval(3 * unit, 6 * unit, 6 * unit, 8 * unit, paint)
        canvas.drawRect(5.5f * unit, 2 * unit, 6 * unit, 7 * unit, paint)
        canvas.drawRect(5.5f * unit, 2 * unit, 7.5f * unit, 2.5f * unit, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun theDrawnNoteIsNotACover(edge: Int) {
        respond = { 200 to drawnNote(edge) }
        compose.setContent { Probe("drawn-$edge") }
        advanceUntil(30_000) { ArtworkImages.activity().waiting == 0 && requests.get() > 0 }
        assertEquals("none", painted(), "at $edge")
    }

    @Test fun theServersDrawnNoteLeavesTheSurfacesOwnPlaceholderAtASmallSize() = theDrawnNoteIsNotACover(96)

    @Test fun theServersDrawnNoteLeavesTheSurfacesOwnPlaceholderAtALargeSize() = theDrawnNoteIsNotACover(600)

    @Test fun aDarkCoverWithADrawingOnItIsStillACover() {
        respond = { 200 to drawnNote(96, mark = 0xFFC04040.toInt()) }
        compose.setContent { Probe("dark-red") }
        assertTrue(advanceUntil(30_000) { painted() != "none" })
    }

    @Test fun aCoverTheServerDoesNotHaveIsAskedForOnce() {
        respond = { 404 to ByteArray(0) }
        compose.setContent { Probe("absent") }
        advanceUntil(30_000) { ArtworkImages.activity().missing == 1 }
        assertEquals(1, ArtworkImages.activity().missing, "${ArtworkImages.activity()}")
        assertEquals(0, ArtworkImages.activity().waiting)
        compose.mainClock.advanceTimeBy(30_000)
        assertEquals(1, requests.get(), "The server said there is none; there is nothing to ask again")
        assertEquals("none", painted())
    }

    @Test fun aCoverKeptAtAnotherSizePaintsWhenTheServerHasGoneAway() {
        val covers = AndroidArtworkRepository(ApplicationProvider.getApplicationContext(),
            PlaybackEndpointAccount(id, account.normalizedBaseUrl, "u", "p", true))
        runBlocking { checkNotNull(covers.load("kept", 96)) { "control: the device keeps it at the small size" } }
        respond = { 503 to ByteArray(0) }
        compose.setContent { Probe("kept", pixels = 512) }
        assertTrue(advanceUntil(10_000) { painted() != "none" }, "The device kept this cover and showed a placeholder")
        assertEquals(null, ArtworkImages.cached(account, "kept", 512), "It stays a stand-in: the sharper picture is still asked for")
    }

    @Test fun aFailingServerIsAskedOnASlowingScheduleThatEnds() {
        val schedule = generateSequence(0) { it + 1 }.map(::artworkRetryDelayMillis).takeWhile { it != null }.map { it!! }.toList()
        assertEquals(800L, schedule.first(), "The quick tries stay quick: a fresh device's first connections are reset")
        assertTrue(schedule.zipWithNext().all { (a, b) -> b >= a }, "The waits never shorten: $schedule")
        assertTrue(schedule.sum() >= 240_000, "A server back within a few minutes still fills the page: $schedule")
        assertTrue(schedule.size <= 20, "A tile does not ask for ever: $schedule")
    }

    @Composable
    private fun Probe(key: String, pixels: Int = 256) {
        val image = rememberArtwork(account, key, pixels)
        Box(Modifier.testTag("cover").semantics { contentDescription = image?.width?.toString() ?: "none" })
    }
}
