package com.legitimateapps.dulcet.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class DownloadPolicyTest {
    @Test
    fun reconciliationIsMandatoryAndCompositeIdentityKeepsProfilesSeparate() = withFixture { fixture ->
        assertFailsWith<IllegalStateException> { fixture.engine.enqueue(request()) }
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))

        val original = fixture.engine.enqueue(request())
        val duplicate = fixture.engine.enqueue(request())
        val transcoded = fixture.engine.enqueue(
            request(identity = DownloadIdentity(SERVER_ID, OPAQUE_RAW_ID, "mp3:320")),
        )

        assertEquals(original.downloadId, duplicate.downloadId)
        assertNotEquals(original.downloadId, transcoded.downloadId)
        assertEquals(OPAQUE_RAW_ID, original.identity.rawId)
        assertEquals(OPAQUE_RAW_ID, transcoded.identity.rawId)
    }

    @Test
    fun relaunchReconciliationInterruptsMissingTasksCancelsOrphansAndDeletesCrashTemps() =
        withFixture { fixture ->
            fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            val row = fixture.engine.enqueue(request())
            assertIs<DownloadScheduleResult.Start>(fixture.engine.schedule(scheduleContext()))
            fixture.engine.writeCompletedTemporaryFile(row.downloadId, MP3_BYTES)

            val relaunched = DownloadPolicyEngine(fixture.database.database, fixture.files)
            val orphan = DownloadId("download:orphan-task")
            val result = relaunched.reconcile(
                outstandingTasks = listOf(OutstandingDownloadTask(orphan)),
                currentCredentialGenerations = mapOf(SERVER_ID to 1L),
            )

            assertEquals(setOf(orphan), result.taskIdsToCancel)
            assertEquals(setOf(row.downloadId), result.interruptedRows)
            assertEquals(setOf(row.downloadId), result.deletedTemporaryFiles)
            assertEquals(DownloadState.Interrupted, relaunched.record(row.downloadId)?.state)
            assertFalse(FileSystem.SYSTEM.exists(relaunched.temporaryFilePath(row.downloadId).toPath()))
        }

    @Test
    fun credentialGenerationChangeCancelsAndRequeuesOutstandingTask() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val row = fixture.engine.enqueue(request())
        assertIs<DownloadScheduleResult.Start>(fixture.engine.schedule(scheduleContext()))

        val relaunched = DownloadPolicyEngine(fixture.database.database, fixture.files)
        val result = relaunched.reconcile(
            listOf(OutstandingDownloadTask(row.downloadId)),
            mapOf(SERVER_ID to 2L),
        )

        assertEquals(setOf(row.downloadId), result.taskIdsToCancel)
        assertEquals(DownloadState.Queued, relaunched.record(row.downloadId)?.state)
        assertEquals(2L, relaunched.record(row.downloadId)?.credentialGeneration)
    }

    @Test
    fun exactLengthMustMatchBeforePromotionAndEstimatedLengthNeverRejectsTerminalBodyEnd() =
        withFixture { fixture ->
            fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            val mismatch = fixture.engine.enqueue(
                request(declaredLength = PlaybackContentLength.Exact(MP3_BYTES.size + 1L)),
            )
            fixture.engine.writeCompletedTemporaryFile(mismatch.downloadId, MP3_BYTES)

            assertIs<DownloadPromotionResult.Rejected>(
                fixture.engine.promote(
                    mismatch.downloadId,
                    DownloadResponseMetadata(
                        "audio/mpeg",
                        PlaybackContentLength.Exact(MP3_BYTES.size + 1L),
                    ),
                ),
            )
            assertIs<OfflinePlaybackPlanResult.NotDownloaded>(
                fixture.engine.offlinePlaybackPlan(mismatch.identity),
            )

            val estimated = fixture.engine.enqueue(
                request(
                    identity = DownloadIdentity(SERVER_ID, "opaque:estimated", "mp3:192"),
                    declaredLength = PlaybackContentLength.Estimated(MP3_BYTES.size + 100L),
                ),
            )
            fixture.engine.writeCompletedTemporaryFile(estimated.downloadId, MP3_BYTES)
            val promoted = assertIs<DownloadPromotionResult.Promoted>(
                fixture.engine.promote(
                    estimated.downloadId,
                    DownloadResponseMetadata(
                        "audio/mpeg",
                        PlaybackContentLength.Estimated(MP3_BYTES.size + 100L),
                    ),
                ),
            )
            assertEquals(MP3_BYTES.size.toLong(), promoted.record.exactByteLength)
            assertEquals(MP3_BYTES.size.toLong(), promoted.record.fileSizeBytes)
            assertFalse(FileSystem.SYSTEM.exists(fixture.engine.temporaryFilePath(estimated.downloadId).toPath()))
        }

    @Test
    fun duplicateDeliveryIsHarmlessAndOfflinePlanReadsOnlyPromotedBytes() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val row = fixture.engine.enqueue(request())
        fixture.engine.writeCompletedTemporaryFile(row.downloadId, MP3_BYTES)
        assertIs<DownloadPromotionResult.Promoted>(
            fixture.engine.promote(
                row.downloadId,
                DownloadResponseMetadata(
                    "audio/mpeg",
                    PlaybackContentLength.Exact(MP3_BYTES.size.toLong()),
                ),
            ),
        )
        assertIs<DownloadPromotionResult.AlreadyPromoted>(
            fixture.engine.promote(
                row.downloadId,
                DownloadResponseMetadata("audio/mpeg", null),
            ),
        )

        val plan = assertIs<OfflinePlaybackPlanResult.Available>(
            fixture.engine.offlinePlaybackPlan(row.identity),
        ).plan
        val loaded = assertIs<OfflinePlaybackLoadResult.Audio>(fixture.engine.loadOffline(plan))
        assertContentEquals(MP3_BYTES, loaded.bytes)
        assertEquals(row.downloadId, plan.downloadId)
    }

    @Test
    fun schedulerPreservesEnqueueOrderAndEnforcesDiskAndPlaybackReservations() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val first = fixture.engine.enqueue(request())
        fixture.engine.enqueue(
            request(identity = DownloadIdentity(SERVER_ID, "opaque:second", "mp3:320")),
        )

        assertIs<DownloadScheduleResult.DiskBudgetExceeded>(
            fixture.engine.schedule(scheduleContext(diskBudgetBytes = MP3_BYTES.size - 1L)),
        )
        val start = assertIs<DownloadScheduleResult.Start>(fixture.engine.schedule(scheduleContext()))
        assertEquals(first.downloadId, start.record.downloadId)
        assertEquals(first.downloadId.value, start.platformTaskIdentifier)

        assertIs<DownloadScheduleResult.TranscodeBudgetReservedForPlayback>(
            fixture.engine.schedule(
                scheduleContext(
                    playbackSessionActive = true,
                    activeTranscodes = ActiveTranscodeCounts(downloads = 1),
                ),
            ),
        )
    }

    @Test
    fun retryResumeStalenessAndEvictionPoliciesKeepCompletedExplicitFiles() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val partial = fixture.engine.enqueue(request())
        val busy = fixture.engine.recordFailure(
            partial.downloadId,
            DomainError.Server.Busy(30.seconds),
            NOW,
        )
        assertEquals(NOW + 30.seconds.inWholeMilliseconds, busy?.retryAtWallClock)

        fixture.engine.recordResumeData(partial.downloadId, byteArrayOf(1, 2, 3), NOW)
        assertIs<DownloadResumeDecision.Resume>(
            fixture.engine.resumeDecision(partial.downloadId, NOW + 7.days.inWholeMilliseconds),
        )
        assertIs<DownloadResumeDecision.RestartFromZero>(
            fixture.engine.resumeDecision(partial.downloadId, NOW + 7.days.inWholeMilliseconds + 1),
        )

        val complete = fixture.engine.enqueue(
            request(identity = DownloadIdentity(SERVER_ID, "opaque:complete", DownloadIdentity.ORIGINAL_PROFILE)),
        )
        fixture.engine.writeCompletedTemporaryFile(complete.downloadId, MP3_BYTES)
        assertIs<DownloadPromotionResult.Promoted>(
            fixture.engine.promote(
                complete.downloadId,
                DownloadResponseMetadata(
                    "audio/mpeg",
                    PlaybackContentLength.Exact(MP3_BYTES.size.toLong()),
                ),
            ),
        )
        val stale = fixture.engine.reconcileServerItem(
            complete.identity,
            DownloadServerSnapshot(durationMilliseconds = 2_000, sizeBytes = MP3_BYTES.size.toLong()),
            NOW + 1,
        )
        assertEquals(DownloadState.Stale, stale?.state)
        assertIs<OfflinePlaybackPlanResult.Available>(fixture.engine.offlinePlaybackPlan(complete.identity))

        val evicted = fixture.engine.evictAbandonedPartials(NOW + 31.days.inWholeMilliseconds)
        assertTrue(partial.downloadId in evicted)
        assertFalse(complete.downloadId in evicted)
        assertNull(fixture.engine.record(partial.downloadId))
        assertEquals(DownloadState.Stale, fixture.engine.record(complete.downloadId)?.state)
    }

    private fun request(
        identity: DownloadIdentity = DownloadIdentity(
            SERVER_ID,
            OPAQUE_RAW_ID,
            DownloadIdentity.ORIGINAL_PROFILE,
        ),
        declaredLength: PlaybackContentLength? = PlaybackContentLength.Exact(MP3_BYTES.size.toLong()),
    ): DownloadRequest = DownloadRequest(
        identity = identity,
        expectedContainer = AudioContainer.Mp3,
        declaredContentLength = declaredLength,
        serverSnapshot = DownloadServerSnapshot(
            durationMilliseconds = 1_000,
            sizeBytes = MP3_BYTES.size.toLong(),
        ),
        credentialGeneration = 1,
        wallClockMilliseconds = NOW,
    )

    private fun scheduleContext(
        diskBudgetBytes: Long = 1_000_000,
        playbackSessionActive: Boolean = false,
        activeTranscodes: ActiveTranscodeCounts = ActiveTranscodeCounts(),
    ): DownloadSchedulingContext = DownloadSchedulingContext(
        serverId = SERVER_ID,
        wallClockMilliseconds = NOW,
        diskBudgetBytes = diskBudgetBytes,
        unknownLengthReservationBytes = 100_000,
        activeTranscodes = activeTranscodes,
        playbackSessionActive = playbackSessionActive,
    )

    private fun withFixture(block: (Fixture) -> Unit) {
        val database = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "dulcet-download-test-${secureRandomBytes(8).toLowerHex()}"
        val files = DownloadFileStore(root.toString())
        val fixture = Fixture(database, files, DownloadPolicyEngine(database.database, files), root)
        try {
            block(fixture)
        } finally {
            database.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private data class Fixture(
        val database: DulcetDatabaseStore,
        val files: DownloadFileStore,
        val engine: DownloadPolicyEngine,
        val root: Path,
    )

    private companion object {
        const val SERVER_ID = "server:opaque"
        const val OPAQUE_RAW_ID = "raw:not-an-integer:01HXYZ"
        const val NOW = 1_800_000_000_000L
        val MP3_BYTES = byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            4,
            0,
            0,
        )
    }
}
