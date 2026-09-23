package com.legitimateapps.dulcet.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidArtworkRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root get() = File(context.cacheDir, "artwork")
    private val account = PlaybackEndpointAccount("provider:artwork", "http://127.0.0.1:9",
        "artwork-user-canary", "artwork-password-canary", true)

    @After fun clean() { root.deleteRecursively() }

    @Test fun onlyValidatedImagesAreCachedAndACachedImageIsNotRefetched() = runBlocking {
        val requested = mutableListOf<String>()
        val repository = repository { id -> requested += id; if (id == "good") PNG else ERROR_40 }

        assertContentEquals(PNG, repository.load("good", 200))
        assertContentEquals(PNG, repository.load("good", 200))
        assertEquals(listOf("good"), requested, "The second read must come from the validated disk cache")

        assertNull(repository.load("denied", 200), "An error envelope must never be returned as artwork")
        assertNull(repository.load("denied", 200))
        assertEquals(listOf("good", "denied", "denied"), requested, "A failure is not cached; it may succeed later")

        val files = root.walk().filter { it.isFile }.toList()
        assertEquals(1, files.size, "Only the validated image may reach the disk")
        assertContentEquals(PNG, files.single().readBytes())
        for (secret in listOf("good", "artwork-user-canary", "artwork-password-canary", "provider"))
            assertFalse(files.single().path.contains(secret), "Cache paths carry only digests")
    }

    @Test fun missingArtworkIsRememberedForTheProcess() = runBlocking {
        var requests = 0
        val repository = repository { requests++; NOT_FOUND_70 }
        assertNull(repository.load("absent", 96))
        assertNull(repository.load("absent", 96))
        assertEquals(1, requests)
        assertTrue(root.walk().none { it.isFile })
    }

    @Test fun theBudgetEvictsTheLeastRecentlyUsedImage() = runBlocking {
        val repository = repository(budget = PNG.size + 4L) { PNG }
        assertNotNull(repository.load("first", 256))
        val first = root.walk().single { it.isFile }
        assertTrue(first.setLastModified(System.currentTimeMillis() - 60_000))
        assertNotNull(repository.load("second", 256))
        val remaining = root.walk().filter { it.isFile }.toList()
        assertEquals(1, remaining.size)
        assertNotEquals(first.name, remaining.single().name, "The older image must be the one evicted")
    }

    private fun repository(budget: Long = 1_000_000, respond: (String) -> ByteArray) =
        AndroidArtworkRepository(context, account, ArtworkFetcher(ArtworkEndpointTransport { parameters ->
            ArtworkEndpointResponse(200, respond(parameters.getValue("id")), "<redacted-url>")
        }), budget)

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val ERROR_40 = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""".toByteArray()
        val NOT_FOUND_70 = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Artwork not found"}}}""".toByteArray()
    }
}
