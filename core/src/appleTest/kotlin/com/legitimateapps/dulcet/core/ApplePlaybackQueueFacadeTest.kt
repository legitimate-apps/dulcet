package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun deliveryReportCountsASubmittedPlayOnlyAfterTheServerAcknowledgesIt() {
        val transport = QueuedScrobbleTransport(ArrayDeque(listOf(okEnvelope(), okEnvelope())))
        val delivery = deliveryFixture(transport)
        try {
            val client = delivery.client
            client.replaceAndStart(queueRequest())
            client.recordReady("attempt:2", 30_000, "seekable")
            client.recordPlaybackProgressBegan("attempt:2", 1_788_000_000_000, 0)
            client.recordPositionChanged("attempt:2", 4_000, 4_000_000_000)
            client.recordPositionChanged("attempt:2", 8_000, 8_000_000_000)
            client.recordPositionChanged("attempt:2", 12_000, 12_000_000_000)

            // Now-playing went out on progress begin; nothing was submitted below the threshold.
            assertEquals(listOf("false"), transport.parameters.map { it["submission"] })
            val beforeThreshold = client.deliveryReport()
            assertEquals(1, beforeThreshold.nowPlayingSent)
            assertEquals(0, beforeThreshold.submittedPlaysPersisted)
            assertEquals(0, beforeThreshold.submittedPlaysDelivered)

            client.recordPositionChanged("attempt:2", 16_000, 16_000_000_000)

            assertEquals(listOf("false", "true"), transport.parameters.map { it["submission"] })
            val afterThreshold = client.deliveryReport()
            assertEquals(1, afterThreshold.submittedPlaysPersisted)
            assertEquals(1, afterThreshold.submittedPlaysDelivered)
            assertEquals(0, afterThreshold.submittedPlaysPending)
            assertEquals(0, afterThreshold.submittedPlayFailedAttempts)
            // The observer saw the persisted-but-undelivered state before the delivered one, so a
            // shell watching it cannot read "delivered" off the hand-off alone.
            val persistedFirst = delivery.reports.first { it.submittedPlaysPersisted == 1L }
            assertEquals(0, persistedFirst.submittedPlaysDelivered)
            assertEquals(1, delivery.reports.last().submittedPlaysDelivered)
        } finally {
            delivery.close()
        }
    }

    @Test
    fun deliveryReportDoesNotCountAPlayTheServerRejected() {
        val transport = QueuedScrobbleTransport(ArrayDeque(listOf(okEnvelope(), failedEnvelope())))
        val delivery = deliveryFixture(transport)
        try {
            val client = delivery.client
            client.replaceAndStart(queueRequest())
            client.recordReady("attempt:2", 30_000, "seekable")
            client.recordPlaybackProgressBegan("attempt:2", 1_788_000_000_000, 0)
            client.recordPositionChanged("attempt:2", 4_000, 4_000_000_000)
            client.recordPositionChanged("attempt:2", 8_000, 8_000_000_000)
            client.recordPositionChanged("attempt:2", 12_000, 12_000_000_000)
            client.recordPositionChanged("attempt:2", 16_000, 16_000_000_000)

            assertEquals(listOf("false", "true"), transport.parameters.map { it["submission"] })
            val report = client.deliveryReport()
            assertEquals(1, report.submittedPlaysPersisted)
            assertEquals(0, report.submittedPlaysDelivered)
            assertEquals(1, report.submittedPlaysPending)
            assertEquals(1, report.submittedPlayFailedAttempts)
            assertFalse(delivery.reports.any { it.submittedPlaysDelivered > 0 })
        } finally {
            delivery.close()
        }
    }

    @Test
    fun deliveryReportSurvivesAClosedDatabaseAndAClosedClient() {
        val transport = QueuedScrobbleTransport(ArrayDeque(listOf(okEnvelope(), failedEnvelope())))
        val delivery = deliveryFixture(transport)
        val client = delivery.client
        client.replaceAndStart(queueRequest())
        client.recordReady("attempt:2", 30_000, "seekable")
        client.recordPlaybackProgressBegan("attempt:2", 1_788_000_000_000, 0)
        client.recordPositionChanged("attempt:2", 4_000, 4_000_000_000)
        client.recordPositionChanged("attempt:2", 8_000, 8_000_000_000)
        client.recordPositionChanged("attempt:2", 12_000, 12_000_000_000)
        client.recordPositionChanged("attempt:2", 16_000, 16_000_000_000)
        val live = client.deliveryReport()
        assertEquals(1, live.submittedPlaysPending)

        // The pending count is a SQLite read. With the database gone it must not throw across
        // the Objective-C boundary; it reports the last count it managed to read.
        delivery.closeDriver()
        val afterDatabase = client.deliveryReport()
        assertEquals(1, afterDatabase.submittedPlaysPersisted)
        assertEquals(1, afterDatabase.submittedPlaysPending)
        assertEquals(0, afterDatabase.submittedPlaysDelivered)

        client.close()
        val afterClose = client.deliveryReport()
        assertEquals(1, afterClose.submittedPlaysPersisted)
        assertEquals(1, afterClose.submittedPlaysPending)
        client.setDeliveryReportObserver { }
        client.setDeliveryReportObserver(null)
    }

    private fun deliveryFixture(transport: QueuedScrobbleTransport): DeliveryFixture {
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
            // Unconfined runs delivery inline on the ingesting thread, so every report is visible
            // the moment the ingestion call returns and the test needs no main run loop.
            deliveryDispatcher = Dispatchers.Unconfined,
        )
        val reports = mutableListOf<ApplePlaybackDeliveryReportDto>()
        client.setDeliveryReportObserver { reports += it }
        client.installDelivery(
            serverId = ServerId("server"),
            sender = ScrobbleEndpointSender(transport),
            outbox = PersistentScrobbleOutbox(database, OutboxWallClock { 1_788_000_000_000 }),
        )
        return DeliveryFixture(driver, client, reports)
    }

    private class DeliveryFixture(
        private val driver: app.cash.sqldelight.db.SqlDriver,
        val client: ApplePlaybackQueueClient,
        val reports: MutableList<ApplePlaybackDeliveryReportDto>,
    ) {
        fun closeDriver() {
            driver.close()
        }

        fun close() {
            client.close()
            driver.close()
        }
    }

    private class QueuedScrobbleTransport(
        private val responses: ArrayDeque<AuthenticatedEndpointResponse>,
    ) : ScrobbleEndpointTransport {
        val parameters = mutableListOf<Map<String, String>>()

        override suspend fun request(
            parameters: Map<String, String>,
        ): AuthenticatedEndpointResponse {
            this.parameters += parameters
            return responses.removeFirst()
        }
    }

    private fun okEnvelope() = envelope("""{"subsonic-response":{"status":"ok"}}""")

    private fun failedEnvelope() =
        envelope("""{"subsonic-response":{"status":"failed","error":{"code":0}}}""")

    private fun envelope(json: String): AuthenticatedEndpointResponse {
        val body = json.encodeToByteArray()
        val redactedUrl = "https://music.invalid:443/rest/scrobble.view?<redacted>"
        return AuthenticatedEndpointResponse(
            statusCode = 200,
            body = body,
            redactedUrl = redactedUrl,
            headers = AuthenticatedEndpointResponseHeaders(
                contentType = "application/json",
                contentLength = PlaybackContentLength.Exact(body.size.toLong()),
                retryAfter = null,
                acceptRanges = null,
                contentRange = null,
            ),
            requestTrace = RequestTrace.observed(
                endpoint = "scrobble",
                method = "GET",
                redactedUrl = redactedUrl,
                authenticationLocation = AuthenticationLocation.Query,
                queryAuthenticationParameters = emptySet(),
                formAuthenticationParameters = emptySet(),
                channels = emptySet(),
                requestedProtocolVersion = "1.16.1",
                saltFingerprint = "fixture",
            ),
        )
    }

    @Test
    fun queueEditingCrossesTheBoundaryAsClosedDtosAndNeverThrows() {
        val fixture = fixture()
        val client = fixture.client
        val started = client.replaceAndStart(queueRequest(listOf("track-a", "track-b")))
        val session = assertNotNull(started.startDirective).playbackSessionId

        val playNext = client.enqueue(insertion(listOf("x", "y"), "playNext"))
        assertNull(playNext.errorKind)
        assertEquals(listOf("track-a", "x", "y", "track-b"), playNext.snapshot?.entries?.map { it.rawId })
        assertEquals(session, playNext.snapshot?.currentSession?.playbackSessionId)

        val later = client.enqueue(insertion(listOf("z"), "playLater"))
        assertEquals("z", later.snapshot?.entries?.last()?.rawId)

        val entries = assertNotNull(later.snapshot).entries
        val moved = client.moveEntry(entries.last().queueEntryId, 1)
        assertEquals(listOf("track-a", "z", "x", "y", "track-b"), moved.snapshot?.entries?.map { it.rawId })

        val removed = client.removeEntry(entries[1].queueEntryId)
        assertEquals(listOf("track-a", "z", "y", "track-b"), removed.snapshot?.entries?.map { it.rawId })

        // Refusals and malformed input are closed error kinds, never an exception.
        assertEquals("input", client.removeEntry(entries[0].queueEntryId).errorKind)
        assertEquals("input", client.moveEntry("queue-entry:missing", 0).errorKind)
        assertEquals("input", client.enqueue(insertion(listOf("q"), "sideways")).errorKind)
        assertEquals("input", client.enqueue(insertion(emptyList(), "playNext")).errorKind)
        assertEquals("input", client.jumpTo("queue-entry:missing").errorKind)

        val cleared = client.clearUpcoming()
        assertEquals(listOf("track-a"), cleared.snapshot?.entries?.map { it.rawId })

        val jumpedBack = client.enqueue(insertion(listOf("w"), "playLater"))
        val w = assertNotNull(jumpedBack.snapshot).entries.last().queueEntryId
        val jumped = client.jumpTo(w)
        assertEquals("w", jumped.startDirective?.rawId)
        assertNotEquals(session, jumped.startDirective?.playbackSessionId)
        fixture.driver.close()
    }

    @Test
    fun preloadDirectiveIsSeparateFromTheStartDirectiveAndDiscardIsReported() {
        val fixture = fixture()
        val client = fixture.client
        val started = assertNotNull(
            client.replaceAndStart(queueRequest(listOf("track-a", "track-b"))).startDirective,
        )

        val preload = client.preloadNextForSession(started.playbackSessionId)
        assertNull(preload.startDirective, "a preload is never something to start")
        val directive = assertNotNull(preload.preloadDirective)
        assertEquals("track-b", directive.rawId)
        assertNull(preload.discardedPreloadAttemptId)

        val discarded = client.discardPreload(directive.attemptId)
        assertEquals(directive.attemptId, discarded.discardedPreloadAttemptId)
        assertNull(client.discardPreload(directive.attemptId).discardedPreloadAttemptId)
        fixture.driver.close()
    }

    @Test
    fun queueEndKeepsTheLastEntryAndStartCurrentReplaysIt() {
        val fixture = fixture()
        val client = fixture.client
        val started = assertNotNull(client.replaceAndStart(queueRequest()).startDirective)

        val ended = client.recordEndedNaturally(started.attemptId, 180_000)

        assertEquals(0, ended.snapshot?.currentIndex)
        assertNull(ended.snapshot?.currentSession)
        val replay = assertNotNull(client.startCurrent().startDirective)
        assertEquals("track-a", replay.rawId)
        assertNotEquals(started.playbackSessionId, replay.playbackSessionId)
        fixture.driver.close()
    }

    @Test
    fun retryCurrentCrossesTheBoundaryKeepingTheSessionAfterAFailureBeforeStart() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val resumePositions = PersistentResumePositionStore(database)
        var identity = 0
        // Store-backed, so the position a failure after partial playback saves is really saved.
        val client = ApplePlaybackQueueClient(
            database = database,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = resumePositions,
                identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
            ),
            resumePositions = resumePositions,
        )
        val started = assertNotNull(client.replaceAndStart(queueRequest()).startDirective)

        val failed = client.recordFailedBeforeStart(started.attemptId, "sourceUnavailable")
        assertEquals("Failed", failed.snapshot?.currentSession?.phase)
        assertEquals("beforeStart", failed.snapshot?.currentSession?.failure)

        val retried = client.retryCurrent()
        val directive = assertNotNull(retried.startDirective)
        assertNull(retried.errorKind)
        assertEquals(started.playbackSessionId, directive.playbackSessionId)
        assertNotEquals(started.attemptId, directive.attemptId)
        assertNull(retried.snapshot?.currentSession?.failure, "the new attempt has not failed")

        // It plays, then drops: a failure after partial playback, and its retry is a new play.
        client.recordReady(directive.attemptId, 180_000, "seekable")
        client.recordPlaybackProgressBegan(directive.attemptId, 1_788_000_000_000, 1_000)
        val dropped = client.recordFailedAfterPartial(directive.attemptId, 40_000, "transport")
        assertEquals("afterPartial", dropped.snapshot?.currentSession?.failure)
        val replay = assertNotNull(client.retryCurrent().startDirective)
        assertNotEquals(started.playbackSessionId, replay.playbackSessionId)
        assertEquals(40_000, replay.resumePositionMilliseconds)
        client.close()
        driver.close()
    }

    private fun insertion(rawIds: List<String>, mode: String) = ApplePlaybackQueueInsertionDto(
        items = rawIds.map { ApplePlaybackQueueItemDto("server", it, 180_000) },
        sourceKind = "search",
        sourceRawId = null,
        sourceDisplayName = "Search",
        mode = mode,
    )

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
