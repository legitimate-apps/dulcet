@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.toQueueTrack
import com.legitimateapps.dulcet.playback.SystemMediaLibrary
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
class QueueTrackAdaptersTest {
    @Test fun serverSearchAnswerKeepsAlbumIdentityThroughTheQueueAdapter() = runBlocking {
        val body = """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"song:opaque","title":"Song","albumId":"album:opaque","duration":40}]}}}"""
        val answer = ServerSearch(SearchEndpointTransport { SearchEndpointResponse(200, body, "<redacted>") })
            .search(SearchPageRequest("server", "https://music.example.invalid", "listener", "password", false,
                "song", 0, 0, 0, 0, 10, 0))
        val item = (answer as SearchPageResult.Loaded).page.results.single()
        val track = assertNotNull(item.toQueueTrack("server"))
        assertEquals("album:opaque", track.albumRawId)
        assertEquals(40_000L, track.durationMilliseconds)
    }

    @Test fun untitledSearchTracksKeepAlbumIdentityAndStillRequireDuration() {
        // Cached/local Search rows can carry a blank title even though remote Search requires one.
        val item = SearchResultItem(ProviderItemId("server", "song:opaque"), SearchResultType.Track,
            " \t", emptyList(), null, null, 40.seconds, null, null, null, null, null, "album:opaque")
        val track = assertNotNull(item.toQueueTrack("server"))
        assertTrue(track.title.isBlank(), "The playback controller supplies the localized fallback")
        assertEquals("album:opaque", track.albumRawId)
        assertEquals(40_000L, track.durationMilliseconds)
        assertNull(item.copy(duration = null).toQueueTrack("server"))
    }

    @Test fun untitledSystemMediaTracksKeepAlbumIdentityAndStillRequireDuration() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val callback = SystemMediaLibrary(RuntimeEnvironment.getApplication(), scope) { null }
            for (title in listOf(null, "", " \t")) {
                val item = AndroidLibraryItem.Track("song:opaque", title, "album:opaque", "Album", "Artist",
                    null, null, null, 40_000, null, null, null, null, null, AndroidLibraryPlayability.Streamable, false)
                with(callback) {
                    val track = assertNotNull(item.toTrack("server"))
                    assertTrue(track.title.isBlank())
                    assertEquals("album:opaque", track.albumRawId)
                    assertEquals(40_000L, track.durationMilliseconds)
                    assertNull(item.copy(durationMilliseconds = null).toTrack("server"))
                    assertNull(item.copy(playability = AndroidLibraryPlayability.UnavailableOffline).toTrack("server"))
                }
            }
        } finally { scope.cancel() }
    }

    @Test fun systemMediaTrackAdapterKeepsTheLibraryAlbumsOpaqueIdentity() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val callback = SystemMediaLibrary(RuntimeEnvironment.getApplication(), scope) { null }
            val item = AndroidLibraryItem.Track("song:opaque", "Song", "album:opaque", "Album", "Artist",
                null, null, null, 40_000, null, null, null, null, null, AndroidLibraryPlayability.Streamable, false)
            val track = with(callback) { assertNotNull(item.toTrack("server")) }
            assertEquals("album:opaque", track.albumRawId)
            assertEquals(40_000L, track.durationMilliseconds)
        } finally { scope.cancel() }
    }
}
