package com.legitimateapps.dulcet.core

import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Source
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

    /**
     * A temporary file no task owns survives relaunch only for a row whose transfer ENDED and was
     * recorded, and only when the platform says it resumes it; the default (Apple's) keeps none. A
     * row still downloading with no task died mid-write: its file is never offered, whatever the
     * platform answers.
     */
    @Test
    fun relaunchKeepsATemporaryFileOnlyForAnEndedTransferThePlatformResumes() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val ended = fixture.engine.enqueue(request())
        assertEquals(ended.downloadId, assertIs<DownloadScheduleResult.Start>(fixture.engine.schedule(scheduleContext())).record.downloadId)
        fixture.engine.writeCompletedTemporaryFile(ended.downloadId, MP3_BYTES)
        fixture.engine.recordFailure(ended.downloadId, DomainError.Transport.Unreachable, NOW)
        val crashed = fixture.engine.enqueue(request(identity = DownloadIdentity(SERVER_ID, "raw:crashed", DownloadIdentity.ORIGINAL_PROFILE)))
        assertEquals(crashed.downloadId, assertIs<DownloadScheduleResult.Start>(fixture.engine.schedule(scheduleContext())).record.downloadId)
        fixture.engine.writeCompletedTemporaryFile(crashed.downloadId, MP3_BYTES)
        val offered = mutableListOf<DownloadId>()

        val resuming = DownloadPolicyEngine(fixture.database.database, fixture.files)
        val first = resuming.reconcile(emptyList(), mapOf(SERVER_ID to 1L)) { row -> offered += row.downloadId; true }

        assertEquals(listOf(ended.downloadId), offered, "only the ended transfer's row is asked")
        assertEquals(setOf(crashed.downloadId), first.deletedTemporaryFiles)
        assertTrue(FileSystem.SYSTEM.exists(resuming.temporaryFilePath(ended.downloadId).toPath()))
        assertFalse(FileSystem.SYSTEM.exists(resuming.temporaryFilePath(crashed.downloadId).toPath()))
        assertEquals(DownloadState.Interrupted, resuming.record(ended.downloadId)?.state)

        val byDefault = DownloadPolicyEngine(fixture.database.database, fixture.files)
        val second = byDefault.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        assertEquals(setOf(ended.downloadId), second.deletedTemporaryFiles, "the default keeps no temporary file")
        assertFalse(FileSystem.SYSTEM.exists(byDefault.temporaryFilePath(ended.downloadId).toPath()))
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
    fun relaunchSchedulesPreviouslyCompletedAndStaleDownloadsWhoseFilesAreMissing() {
        for (stale in listOf(false, true)) withFixture { fixture ->
            fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            val row = fixture.engine.enqueue(request())
            fixture.engine.writeCompletedTemporaryFile(row.downloadId, MP3_BYTES)
            val promoted = assertIs<DownloadPromotionResult.Promoted>(fixture.engine.promote(
                row.downloadId,
                DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(MP3_BYTES.size.toLong())),
            )).record
            if (stale) fixture.engine.reconcileServerItem(
                row.identity,
                DownloadServerSnapshot(durationMilliseconds = 2_000, sizeBytes = MP3_BYTES.size.toLong()),
                NOW + 1,
            )
            assertEquals(if (stale) DownloadState.Stale else DownloadState.Complete,
                fixture.engine.record(row.downloadId)?.state)
            FileSystem.SYSTEM.delete(fixture.files.destinationPath(promoted))

            // Apple's saved launch calls reconcile and then schedule, without a new enqueue or
            // Reconnect. A lost file must therefore be fetched again, even for a completed row.
            val relaunched = DownloadPolicyEngine(fixture.database.database, fixture.files)
            val result = relaunched.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            assertEquals(setOf(row.downloadId), result.interruptedRows)
            assertEquals(DownloadState.Interrupted, relaunched.record(row.downloadId)?.state)
            val scheduled = assertIs<DownloadScheduleResult.Start>(relaunched.schedule(scheduleContext()))
            assertEquals(row.downloadId, scheduled.record.downloadId)
            assertEquals(row.identity, scheduled.record.identity)
            assertEquals(1L, scheduled.record.credentialGeneration)
            assertEquals(DownloadScheduleResult.NothingQueued, relaunched.schedule(scheduleContext()))
        }
    }

    @Test
    fun relaunchSchedulesPreviouslyCompletedAndStaleDownloadsAfterCredentialGenerationChanges() {
        for (stale in listOf(false, true)) withFixture { fixture ->
            fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            val row = fixture.engine.enqueue(request())
            fixture.engine.writeCompletedTemporaryFile(row.downloadId, MP3_BYTES)
            assertIs<DownloadPromotionResult.Promoted>(fixture.engine.promote(
                row.downloadId,
                DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(MP3_BYTES.size.toLong())),
            ))
            if (stale) fixture.engine.reconcileServerItem(
                row.identity,
                DownloadServerSnapshot(durationMilliseconds = 2_000, sizeBytes = MP3_BYTES.size.toLong()),
                NOW + 1,
            )

            // An unchanged generation keeps the existing file and schedules nothing. Changing
            // it requeues even a downloaded row; Apple's launch scheduler then starts a read.
            val unchanged = DownloadPolicyEngine(fixture.database.database, fixture.files)
            unchanged.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            assertEquals(DownloadScheduleResult.NothingQueued, unchanged.schedule(scheduleContext()))
            val changed = DownloadPolicyEngine(fixture.database.database, fixture.files)
            changed.reconcile(emptyList(), mapOf(SERVER_ID to 2L))
            val scheduled = assertIs<DownloadScheduleResult.Start>(changed.schedule(scheduleContext()))
            assertEquals(row.downloadId, scheduled.record.downloadId)
            assertEquals(row.identity, scheduled.record.identity)
            assertEquals(2L, scheduled.record.credentialGeneration)
            assertEquals(DownloadScheduleResult.NothingQueued, changed.schedule(scheduleContext()))
        }
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

    @Test
    fun rejectedResumeDataClearsThePayloadAndIsImmediatelyRestartedFromZero() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val row = fixture.engine.enqueue(request())
        assertIs<DownloadScheduleResult.Start>(fixture.engine.schedule(scheduleContext()))
        fixture.engine.recordResumeData(row.downloadId, byteArrayOf(1, 2, 3), NOW)

        fixture.engine.rejectResumeData(row.downloadId)

        assertEquals(DownloadState.Queued, fixture.engine.record(row.downloadId)?.state)
        assertIs<DownloadResumeDecision.RestartFromZero>(
            fixture.engine.resumeDecision(row.downloadId, NOW),
        )
        val restarted = assertIs<DownloadScheduleResult.Start>(
            fixture.engine.schedule(scheduleContext()),
        )
        assertEquals(row.downloadId, restarted.record.downloadId)
    }

    @Test
    fun retryBoundaryIsExposedUntilInterruptedWorkBecomesEligible() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val row = fixture.engine.enqueue(request())
        val retryAt = NOW + 30.seconds.inWholeMilliseconds
        fixture.engine.recordFailure(row.downloadId, DomainError.Server.Busy(30.seconds), NOW)

        assertEquals(retryAt, fixture.engine.nextRetryNotBefore(SERVER_ID))
        assertIs<DownloadScheduleResult.NothingQueued>(
            fixture.engine.schedule(scheduleContext(wallClockMilliseconds = retryAt - 1)),
        )
        val restarted = assertIs<DownloadScheduleResult.Start>(
            fixture.engine.schedule(scheduleContext(wallClockMilliseconds = retryAt)),
        )
        assertEquals(row.downloadId, restarted.record.downloadId)
    }

    @Test
    fun accountRemovalDeletesOwnedMediaAndEveryServerScopedDatabaseRow() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L, OTHER_SERVER_ID to 1L))
        val completed = fixture.engine.enqueue(request())
        fixture.engine.writeCompletedTemporaryFile(completed.downloadId, MP3_BYTES)
        assertIs<DownloadPromotionResult.Promoted>(
            fixture.engine.promote(
                completed.downloadId,
                DownloadResponseMetadata(
                    "audio/mpeg",
                    PlaybackContentLength.Exact(MP3_BYTES.size.toLong()),
                ),
            ),
        )
        val partial = fixture.engine.enqueue(
            request(identity = DownloadIdentity(SERVER_ID, "opaque:partial", "mp3:192")),
        )
        fixture.engine.writeCompletedTemporaryFile(partial.downloadId, MP3_BYTES)
        val other = fixture.engine.enqueue(
            request(identity = DownloadIdentity(OTHER_SERVER_ID, "opaque:other", "original")),
        )
        seedEveryNonDownloadServerTable(fixture)

        assertEquals(
            23L,
            fixture.database.database.serverDataQueries.countRowsForServer(SERVER_ID)
                .executeAsOne().sum,
        )
        assertTrue(FileSystem.SYSTEM.exists(fixture.files.destinationPath(completed)))
        assertTrue(FileSystem.SYSTEM.exists(fixture.files.temporaryPath(partial)))

        fixture.engine.removeAccountData(SERVER_ID)

        assertEquals(
            0L,
            fixture.database.database.serverDataQueries.countRowsForServer(SERVER_ID)
                .executeAsOne().sum,
        )
        assertFalse(FileSystem.SYSTEM.exists(fixture.files.destinationPath(completed)))
        assertFalse(FileSystem.SYSTEM.exists(fixture.files.temporaryPath(partial)))
        assertEquals(DownloadState.Queued, fixture.engine.record(other.downloadId)?.state)
        assertTrue(
            fixture.database.database.serverDataQueries.countRowsForServer(OTHER_SERVER_ID)
                .executeAsOne().sum!! > 0,
        )
    }

    @Test
    fun removingOneDownloadDeletesItsFilesAndRowAndLeavesEveryOtherRow() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val completed = fixture.engine.enqueue(request())
        fixture.engine.writeCompletedTemporaryFile(completed.downloadId, MP3_BYTES)
        assertIs<DownloadPromotionResult.Promoted>(
            fixture.engine.promote(
                completed.downloadId,
                DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(MP3_BYTES.size.toLong())),
            ),
        )
        val partial = fixture.engine.enqueue(
            request(identity = DownloadIdentity(SERVER_ID, "opaque:partial", DownloadIdentity.ORIGINAL_PROFILE)),
        )
        fixture.engine.writeCompletedTemporaryFile(partial.downloadId, MP3_BYTES)
        val kept = fixture.engine.enqueue(
            request(identity = DownloadIdentity(SERVER_ID, "opaque:kept", DownloadIdentity.ORIGINAL_PROFILE)),
        )
        assertEquals(
            listOf(completed.downloadId, partial.downloadId, kept.downloadId),
            fixture.engine.records(SERVER_ID).map(DownloadRecord::downloadId),
        )

        assertEquals(completed.downloadId, fixture.engine.remove(completed.identity)?.downloadId)
        assertEquals(partial.downloadId, fixture.engine.remove(partial.identity)?.downloadId)

        assertFalse(FileSystem.SYSTEM.exists(fixture.files.destinationPath(completed)))
        assertFalse(FileSystem.SYSTEM.exists(fixture.files.temporaryPath(partial)))
        assertNull(fixture.engine.record(completed.downloadId))
        assertNull(fixture.engine.record(partial.downloadId))
        assertIs<OfflinePlaybackPlanResult.NotDownloaded>(fixture.engine.offlinePlaybackPlan(completed.identity))
        assertEquals(listOf(kept.downloadId), fixture.engine.records(SERVER_ID).map(DownloadRecord::downloadId))
        assertNull(fixture.engine.remove(completed.identity), "a second removal finds nothing to remove")
    }

    @Test
    fun aFileFarLargerThanTheValidationWindowPromotesFromABoundedPrefix() {
        val reading = CountingFileSystem(FileSystem.SYSTEM)
        withFixture(reading) { fixture ->
            fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
            val large = wav(DOWNLOAD_VALIDATION_WINDOW_BYTES.toInt() * 16 + 3)
            val row = fixture.engine.enqueue(request(declaredLength = PlaybackContentLength.Exact(large.size.toLong()), container = AudioContainer.Wav))
            fixture.engine.writeCompletedTemporaryFile(row.downloadId, large)
            reading.bytesRead = 0

            val promoted = assertIs<DownloadPromotionResult.Promoted>(
                fixture.engine.promote(
                    row.downloadId,
                    DownloadResponseMetadata("audio/wav", PlaybackContentLength.Exact(large.size.toLong())),
                ),
            )

            assertEquals(large.size.toLong(), promoted.record.fileSizeBytes, "the size is the file's length on disk")
            assertTrue(
                reading.bytesRead in 1..DOWNLOAD_VALIDATION_WINDOW_BYTES,
                "validation read ${reading.bytesRead} of ${large.size} bytes; it may read only its window",
            )
            val plan = assertIs<OfflinePlaybackPlanResult.Available>(fixture.engine.offlinePlaybackPlan(row.identity)).plan
            reading.bytesRead = 0
            assertEquals(OfflinePlaybackVerification.Valid, fixture.engine.verifyOffline(plan))
            assertTrue(reading.bytesRead in 1..DOWNLOAD_VALIDATION_WINDOW_BYTES, "offline verification reads only its window")
        }
    }

    @Test
    fun aLargeFileWhoseLengthOnDiskDiffersFromTheExactLengthIsRejected() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val large = wav(DOWNLOAD_VALIDATION_WINDOW_BYTES.toInt() * 4)
        val row = fixture.engine.enqueue(request(declaredLength = null, container = AudioContainer.Wav))
        fixture.engine.writeCompletedTemporaryFile(row.downloadId, large)

        // The prefix is flawless; only the length on disk, beyond the window, betrays the truncation.
        assertIs<DownloadPromotionResult.Rejected>(
            fixture.engine.promote(
                row.downloadId,
                DownloadResponseMetadata("audio/wav", PlaybackContentLength.Exact(large.size + 1L)),
            ),
        )
        assertFalse(fixture.engine.destinationExists(row.downloadId))
    }

    @Test
    fun aFlacBehindAnId3TagLargerThanTheWindowIsStillRecognised() = withFixture { fixture ->
        fixture.engine.reconcile(emptyList(), mapOf(SERVER_ID to 1L))
        val tagPayload = DOWNLOAD_VALIDATION_WINDOW_BYTES.toInt() * 2 + 17
        val flac = id3Header(tagPayload) + ByteArray(tagPayload) { 0x20 } + "fLaC".encodeToByteArray() + ByteArray(4_096)
        val row = fixture.engine.enqueue(
            DownloadRequest(
                identity = DownloadIdentity(SERVER_ID, "raw:flac", DownloadIdentity.ORIGINAL_PROFILE),
                expectedContainer = AudioContainer.Flac,
                declaredContentLength = null,
                serverSnapshot = DownloadServerSnapshot(1_000, null),
                credentialGeneration = 1,
                wallClockMilliseconds = NOW,
            ),
        )
        fixture.engine.writeCompletedTemporaryFile(row.downloadId, flac)

        assertIs<DownloadPromotionResult.Promoted>(
            fixture.engine.promote(
                row.downloadId,
                DownloadResponseMetadata("audio/flac", PlaybackContentLength.Exact(flac.size.toLong())),
            ),
        )
    }

    private fun wav(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }.also { bytes ->
        "RIFF".encodeToByteArray().copyInto(bytes, 0)
        "WAVE".encodeToByteArray().copyInto(bytes, 8)
    }

    /** An ID3v2.4 header declaring a [payload]-byte tag, its size in four 7-bit bytes. */
    private fun id3Header(payload: Int): ByteArray = "ID3".encodeToByteArray() + byteArrayOf(
        4, 0, 0,
        (payload shr 21 and 0x7F).toByte(),
        (payload shr 14 and 0x7F).toByte(),
        (payload shr 7 and 0x7F).toByte(),
        (payload and 0x7F).toByte(),
    )

    /** Counts every byte read through [source], so a test can bound what validation reads. */
    private class CountingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        var bytesRead = 0L
        override fun source(file: Path): Source = object : ForwardingSource(super.source(file)) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it > 0) bytesRead += it }
        }
    }

    private fun seedEveryNonDownloadServerTable(fixture: Fixture) {
        val statements = listOf(
            "INSERT INTO mutation_outbox VALUES ('$SERVER_ID', 'target', 'starred', 'true', 1, $NOW)",
            "INSERT INTO resume_position VALUES ('$SERVER_ID', 'resume', 41)",
            "INSERT INTO scrobble_outbox VALUES ('$SERVER_ID', 'scrobble', $NOW, $NOW, 0)",
            "INSERT INTO queue_state VALUES ('$SERVER_ID', NULL, 'off', 0)",
            "INSERT INTO active_queue VALUES (1, '$SERVER_ID')",
            "INSERT INTO queue_entry VALUES ('$SERVER_ID', 'entry', 'track', 'library', NULL, 'Library', 'play_now', 0, 0)",
            "INSERT INTO music_folder VALUES ('$SERVER_ID', 'folder', 'Folder', 'folder-key', 7, NULL)",
            "INSERT INTO artist(server_id, raw_id, name, media_source_id, content_key, valid_from_generation, valid_to_generation) VALUES ('$SERVER_ID', 'artist', 'Artist', NULL, 'artist-key', 7, NULL)",
            "INSERT INTO album(server_id, raw_id, title, artist_name, artist_raw_id, year, duration_milliseconds, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) VALUES ('$SERVER_ID', 'album', 'Album', 'Artist', 'artist', NULL, 1000, NULL, NULL, 'album-key', 7, NULL)",
            "INSERT INTO track(server_id, raw_id, album_raw_id, title, artist_name, artist_raw_id, album_title, disc_number, track_number, duration_milliseconds, source_container, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) VALUES ('$SERVER_ID', 'track', 'album', 'Track', 'Artist', 'artist', 'Album', 1, 1, 1000, 'mp3', NULL, NULL, 'track-key', 7, NULL)",
            "INSERT INTO credit(server_id, seen_key, owner_kind, owner_raw_id, role, ordinal, name, artist_raw_id, content_key, valid_from_generation, valid_to_generation) VALUES ('$SERVER_ID', 'credit', 'track', 'track', 'artist', 0, 'Artist', 'artist', 'credit-key', 7, NULL)",
            "INSERT INTO playlist VALUES ('$SERVER_ID', 'playlist', 'Playlist', 'playlist-key', 7, NULL)",
            "INSERT INTO playlist_entry VALUES ('$SERVER_ID', 'playlist-entry', 'playlist', 0, 'track', 'playlist-entry-key', 7, NULL)",
            "INSERT INTO library_starred VALUES ('$SERVER_ID', 'track', 'track', 'starred-track', 7, NULL)",
            "INSERT INTO genre VALUES ('$SERVER_ID', 'Genre', 1, 1, 'genre-key', 7, NULL)",
            "INSERT INTO sync_checkpoint VALUES ('$SERVER_ID', 7, 'folders', 0, 1, '[]', 0, 0)",
            "INSERT INTO sync_seen VALUES ('$SERVER_ID', 7, 'folders', 'seen')",
            "INSERT INTO sync_generation VALUES (7, '$SERVER_ID', 'verified')",
            "INSERT INTO deletion_reconciliation VALUES ('$SERVER_ID', 7, 'deleted', 0, 0)",
        )
        statements.forEach { fixture.database.driver.execute(null, it, 0) }
    }

    private fun request(
        identity: DownloadIdentity = DownloadIdentity(
            SERVER_ID,
            OPAQUE_RAW_ID,
            DownloadIdentity.ORIGINAL_PROFILE,
        ),
        declaredLength: PlaybackContentLength? = PlaybackContentLength.Exact(MP3_BYTES.size.toLong()),
        container: AudioContainer = AudioContainer.Mp3,
    ): DownloadRequest = DownloadRequest(
        identity = identity,
        expectedContainer = container,
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
        wallClockMilliseconds: Long = NOW,
    ): DownloadSchedulingContext = DownloadSchedulingContext(
        serverId = SERVER_ID,
        wallClockMilliseconds = wallClockMilliseconds,
        diskBudgetBytes = diskBudgetBytes,
        unknownLengthReservationBytes = 100_000,
        activeTranscodes = activeTranscodes,
        playbackSessionActive = playbackSessionActive,
    )

    /**
     * What the reader treats as on the device (§16.14): one server's tracks whose download is
     * complete, or stale and still playable -- never one queued, running or interrupted, and never
     * another server's.
     */
    @Test
    fun theReadersDownloadSourceIsTheServersCompleteAndStaleDownloads() = withFixture { fixture ->
        val queries = fixture.database.database.downloadsQueries
        fun row(server: String, rawId: String, state: String) {
            val id = "download-$server-$rawId"
            queries.insertDownload(server, rawId, "original", id, "$rawId.part", null)
            queries.insertDownloadPolicyState(server, rawId, "original", "mp3", null, null, 0, 0, NOW)
            queries.updateDownloadState(state, id)
        }
        row(SERVER_ID, "complete-track", "complete")
        row(SERVER_ID, "stale-track", "stale")
        row(SERVER_ID, "queued-track", "queued")
        row(SERVER_ID, "downloading-track", "downloading")
        row(SERVER_ID, "interrupted-track", "interrupted")
        row(OTHER_SERVER_ID, "other-server-track", "complete")

        val source = DownloadedTrackSource.fromDownloads(fixture.database.database)

        assertEquals(setOf("complete-track", "stale-track"), source.downloadedTrackRawIds(SERVER_ID))
        assertEquals(setOf("other-server-track"), source.downloadedTrackRawIds(OTHER_SERVER_ID))
        assertEquals(emptySet(), DownloadedTrackSource.None.downloadedTrackRawIds(SERVER_ID))
    }

    private fun withFixture(block: (Fixture) -> Unit) = withFixture(FileSystem.SYSTEM, block)

    private fun withFixture(fileSystem: FileSystem, block: (Fixture) -> Unit) {
        val database = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "dulcet-download-test-${secureRandomBytes(8).toLowerHex()}"
        val files = DownloadFileStore(root.toString(), fileSystem)
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
        const val OTHER_SERVER_ID = "server:other"
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
