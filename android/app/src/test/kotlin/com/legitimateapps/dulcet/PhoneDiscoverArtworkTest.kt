package com.legitimateapps.dulcet

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import com.legitimateapps.dulcet.ui.ArtworkImages
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Discover cover that failed to read keeps being asked for, and every cover counts with the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneDiscoverArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = FakeDiscoverWishlistSource()
    private val session = DiscoverWishlistSession(source)
    private val cover = ByteArrayOutputStream().also {
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    @After fun close() = session.close()

    private fun show() {
        compose.setContent { MaterialTheme { DiscoverArtwork(session, "art", "Cover", loadTag = "cover") } }
        compose.waitForIdle()
    }

    @Test fun aFailedReadIsAskedForAgainUntilTheCoverArrives() {
        val before = ArtworkImages.activity().painted
        source.artworkAnswer = {
            if (source.artworkReads.size == 1) DiscoverResult.Failed(DiscoverFailure("server-unavailable", null, true))
            else DiscoverResult.Value(cover)
        }
        show()
        compose.waitUntil(15_000) { source.artworkReads.size == 2 }
        compose.waitForIdle()
        assertEquals(before + 1, ArtworkImages.activity().painted, "the second read's cover is on screen")
    }

    @Test fun anAnswerThatThereIsNoCoverIsNotAskedAgain() {
        show()
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(listOf("art"), source.artworkReads)
        assertEquals(0, ArtworkImages.activity().waiting, "a cover the server does not have is not still out")
    }
}
