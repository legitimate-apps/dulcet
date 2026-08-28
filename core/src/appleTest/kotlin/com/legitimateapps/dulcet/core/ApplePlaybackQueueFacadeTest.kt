package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
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
        assertEquals(listOf("Album A", "Album A"), transition.snapshot?.entries?.map {
            it.sourceDisplayName
        })
        assertEquals(1, transition.snapshot?.currentIndex)
        assertEquals("track-b", transition.startDirective?.rawId)
        assertEquals("session:2", transition.startDirective?.playbackSessionId)
        assertEquals("attempt:3", transition.startDirective?.attemptId)
        assertEquals(true, transition.startDirective?.shouldAutoPlay)
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

    @Test
    fun engineEventsDriveAuthoritativeReadyProgressAndRepeatSnapshots() {
        val fixture = fixture()
        fixture.client.replaceAndStart(queueRequest())
        fixture.client.cycleRepeatMode()
        fixture.client.cycleRepeatMode()

        val ready = fixture.client.recordReady("attempt:2", 180_000, "seekable")
        assertEquals("Ready", ready.snapshot?.currentSession?.phase)
        assertEquals("Seekable", ready.snapshot?.currentSession?.seekability)

        val progressing = fixture.client.recordPlaybackProgressBegan(
            "attempt:2",
            1_788_000_000_000,
            1_000,
        )
        assertEquals("Progressing", progressing.snapshot?.currentSession?.phase)
        assertEquals(1_000, progressing.snapshot?.currentSession?.positionMilliseconds)

        val repeated = fixture.client.recordEndedNaturally("attempt:2", 180_000)
        assertEquals("track-a", repeated.startDirective?.rawId)
        assertNotEquals("session:1", repeated.startDirective?.playbackSessionId)
        assertNotEquals("attempt:2", repeated.startDirective?.attemptId)
        fixture.driver.close()
    }

    @Test
    fun staleAttemptEventCannotReplaceTheCurrentCoreSnapshot() {
        val fixture = fixture()
        fixture.client.replaceAndStart(queueRequest(rawIds = listOf("track-a", "track-b")))
        val advanced = fixture.client.next()
        val currentAttempt = advanced.startDirective?.attemptId

        val stale = fixture.client.recordReady("attempt:3", 180_000, "seekable")

        assertEquals(currentAttempt, stale.snapshot?.currentSession?.attemptId)
        assertEquals("Created", stale.snapshot?.currentSession?.phase)
        fixture.driver.close()
    }

    @Test
    fun facadeRejectsStaleSessionAndDefersSeekabilityToReady() {
        val fixture = fixture()
        val started = fixture.client.replaceAndStart(
            queueRequest(rawIds = listOf("track-a", "track-b")),
        )
        val firstSession = assertNotNull(started.startDirective?.playbackSessionId)
        val firstAttempt = assertNotNull(started.startDirective?.attemptId)

        assertEquals(false, fixture.client.acceptsCommand(firstSession, true))
        fixture.client.recordReady(firstAttempt, 180_000, "seekable")
        assertEquals(true, fixture.client.acceptsCommand(firstSession, true))

        val second = fixture.client.nextForSession(firstSession)
        val secondSession = assertNotNull(second.startDirective?.playbackSessionId)
        val stale = fixture.client.previousForSession(firstSession)
        assertNull(stale.startDirective)
        assertEquals(secondSession, stale.snapshot?.currentSession?.playbackSessionId)
        assertEquals(false, fixture.client.acceptsCommand(firstSession, false))
        fixture.driver.close()
    }

    @Test
    fun submittedPlayIsPersistedBeforeEventIngestionReturns() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val resumePositions = PersistentResumePositionStore(database)
        var identity = 0
        val client = ApplePlaybackQueueClient(
            database = database,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = resumePositions,
                identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
            ),
            resumePositions = resumePositions,
        )
        try {
            client.configurePersistenceOnlyDelivery(
                PersistentScrobbleOutbox(
                    database,
                    OutboxWallClock { 1_788_000_000_000 },
                ),
            )
            client.replaceAndStart(queueRequest())
            client.recordReady("attempt:2", 30_000, "seekable")
            client.recordPlaybackProgressBegan("attempt:2", 1_788_000_000_000, 0)
            client.recordPositionChanged("attempt:2", 4_000, 4_000_000_000)
            client.recordPositionChanged("attempt:2", 8_000, 8_000_000_000)
            client.recordPositionChanged("attempt:2", 12_000, 12_000_000_000)
            client.recordPositionChanged("attempt:2", 16_000, 16_000_000_000)

            client.recordEndedNaturally("attempt:2", 30_000)

            assertEquals(1, client.pendingSubmittedPlayCount())
        } finally {
            client.close()
            driver.close()
        }
    }

    private fun fixture(): FacadeFixture {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        var identity = 0
        return FacadeFixture(
            driver,
            ApplePlaybackQueueClient(
                PlaybackQueueController(
                    queues = PersistentQueueStore(database),
                    resumePositions = PersistentResumePositionStore(database),
                    identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
                ),
            ),
        )
    }

    private fun queueRequest(rawIds: List<String> = listOf("track-a")) =
        ApplePlaybackQueueRequestDto(
            items = rawIds.map { ApplePlaybackQueueItemDto("server", it, 180_000) },
            sourceKind = "album",
            sourceRawId = "album-a",
            sourceDisplayName = "Album A",
            startIndex = 0,
            shuffle = false,
        )

    private data class FacadeFixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val client: ApplePlaybackQueueClient,
    )
}
