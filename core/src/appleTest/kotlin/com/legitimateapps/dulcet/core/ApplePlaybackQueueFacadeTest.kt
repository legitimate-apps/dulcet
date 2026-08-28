package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ApplePlaybackQueueFacadeTest {
    @Test
    fun facadeCopiesCoreQueueAndOpaqueIdentitiesIntoClosedDtos() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        var identity = 0
        val client = ApplePlaybackQueueClient(
            PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = PersistentResumePositionStore(database),
                identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
            ),
        )

        val transition = client.replaceAndStart(
            ApplePlaybackQueueRequestDto(
                items = listOf(
                    ApplePlaybackQueueItemDto("server", "track-a", 180.seconds.inWholeMilliseconds),
                    ApplePlaybackQueueItemDto("server", "track-b", 200.seconds.inWholeMilliseconds),
                ),
                sourceKind = "album",
                sourceRawId = "album-a",
                sourceDisplayName = "Album A",
                startIndex = 1,
                shuffle = false,
            ),
        )

        assertNull(transition.errorKind)
        assertEquals(listOf("track-a", "track-b"), transition.snapshot?.entries?.map { it.rawId })
        assertEquals(1, transition.snapshot?.currentIndex)
        assertEquals("track-b", transition.startDirective?.rawId)
        assertEquals("session:2", transition.startDirective?.playbackSessionId)
        assertEquals("attempt:3", transition.startDirective?.attemptId)
        assertNotNull(transition.snapshot?.currentSession)
        driver.close()
    }

    @Test
    fun malformedRequestReturnsAClosedErrorInsteadOfThrowing() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val client = ApplePlaybackQueueClient(
            PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = PersistentResumePositionStore(database),
                identities = PlaybackIdentitySource { "identity" },
            ),
        )

        val transition = client.replaceAndStart(
            ApplePlaybackQueueRequestDto(
                items = emptyList(),
                sourceKind = "album",
                sourceRawId = "album-a",
                sourceDisplayName = "Album A",
                startIndex = 0,
                shuffle = false,
            ),
        )

        assertEquals("input", transition.errorKind)
        assertNull(transition.snapshot)
        driver.close()
    }
}
