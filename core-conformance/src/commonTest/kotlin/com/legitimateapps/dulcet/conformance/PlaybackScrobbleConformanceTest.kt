package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionContract
import com.legitimateapps.dulcet.core.AttemptId
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.DownloadControlPayload
import com.legitimateapps.dulcet.core.DownloadIdentity
import com.legitimateapps.dulcet.core.DownloadPolicyContract
import com.legitimateapps.dulcet.core.DirectPlayAudioProfile
import com.legitimateapps.dulcet.core.LegacyPlaybackPreference
import com.legitimateapps.dulcet.core.LogSink
import com.legitimateapps.dulcet.core.PlaybackByteRange
import com.legitimateapps.dulcet.core.PlaybackContentLength
import com.legitimateapps.dulcet.core.PlaybackDeliveryPath
import com.legitimateapps.dulcet.core.PlaybackDeviceProfile
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.core.PlaybackLoadResult
import com.legitimateapps.dulcet.core.PlaybackResolutionResult
import com.legitimateapps.dulcet.core.PlaybackResolveRequest
import com.legitimateapps.dulcet.core.PlaybackSessionId
import com.legitimateapps.dulcet.core.PlaybackPlan
import com.legitimateapps.dulcet.core.PlaybackWallClockTime
import com.legitimateapps.dulcet.core.PlaybackWireClient
import com.legitimateapps.dulcet.core.PlaybackWireTranscodeDecision
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.RecordedPlaybackEvent
import com.legitimateapps.dulcet.core.SaltSource
import com.legitimateapps.dulcet.core.ScrobbleEndpointRequest
import com.legitimateapps.dulcet.core.ScrobbleEndpointSender
import com.legitimateapps.dulcet.core.ScrobbleSendResult
import com.legitimateapps.dulcet.core.TranscodingAudioProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class PlaybackScrobbleConformanceTest {
    @Test
    fun conf11SuccessfulLegacyStreamHasPlausibleContentTypeAndSignatureBytes() = runTest {
        withFixture {
            val song = requireSong("CONF-11", HEALTH_PROBE_TITLE)
            assertEquals(AudioContainer.Flac, song.container, "CONF-11 corpus format drifted")

            val wire = rest.get("stream", mapOf("id" to song.id))
            assertEquals(200, wire.status, "CONF-11 stream success status drifted")
            assertTrue(
                wire.contentType.mediaType() in FLAC_CONTENT_TYPES,
                "CONF-11 implausible FLAC content type: ${wire.contentType.mediaType()}",
            )
            assertTrue(wire.body.matchesAscii(0, "fLaC"), "CONF-11 FLAC signature is absent")

            val plan = requireLegacyPlan("CONF-11", song)
            val loaded = requireAudio(
                playback.load(plan),
                "CONF-11 production stream loader rejected the reference FLAC",
            )
            assertEquals(AudioContainer.Flac, loaded.validation.container)
            assertTrue(loaded.bytes.matchesAscii(0, "fLaC"))
            record(
                "CONF-11 OBSERVED status=200 content_type=${wire.contentType.mediaType()} " +
                    "signature=fLaC bytes=${wire.body.size}",
            )
        }
    }

    @Test
    fun conf12DeliveryPathsRetainTheirDistinctMeasuredErrorShapes() = runTest {
        withFixture {
            val source = requireSong("CONF-12", HEALTH_PROBE_TITLE)
            val decision = requireTranscodingCapability("CONF-12", source)
            val missing = source.copy(id = MISSING_OPAQUE_ID)

            val legacyPlan = requireLegacyPlan("CONF-12", missing)
            val legacyFailure = assertIs<PlaybackLoadResult.Failed>(
                playback.load(legacyPlan),
                "CONF-12 legacy bad id must be rejected by production validation",
            )
            assertEquals(
                200,
                legacyFailure.statusCode,
                "CONF-12 legacy stream measured HTTP status changed",
            )
            assertEquals(
                com.legitimateapps.dulcet.core.PlaybackErrorResponseShape.EnvelopeAtSuccess,
                legacyFailure.shape,
                "CONF-12 legacy stream must retain its HTTP-200 error-envelope shape",
            )

            val extensionFailure = rest.getTranscodeStream(
                decision.params,
                mediaId = MISSING_OPAQUE_ID,
            )
            assertEquals(
                404,
                extensionFailure.status,
                "CONF-12 getTranscodeStream measured HTTP status changed",
            )
            assertEquals("text/plain", extensionFailure.contentType.mediaType())
            assertFalse(extensionFailure.body.isSubsonicEnvelope())
            assertEquals("Not Found", extensionFailure.body.decodeToString().trim())
            record(
                "CONF-12 OBSERVED legacy_status=200 legacy_shape=envelope_at_success " +
                    "extension_status=404 extension_shape=bare_http_error",
            )
        }
    }

    @Test
    fun conf13RawAndTranscodedStreamsHonorTheSameExplicitByteRange() = runTest {
        withFixture {
            val source = requireSong("CONF-13", HEALTH_PROBE_TITLE)
            val decision = requireTranscodingCapability("CONF-13", source)
            val range = "bytes=0-63"

            val raw = rest.get("stream", mapOf("id" to source.id), range)
            assertPartialRange("CONF-13 raw", raw, range, expectedSize = 64)
            assertTrue(raw.body.matchesAscii(0, "fLaC"))

            // A cold Navidrome transcode is a live pipe and explicitly reports Accept-Ranges:
            // none. Consume it once so CONF-13 measures the cached, seekable representation on
            // every platform instead of depending on the test runner's execution order.
            val transcodeWarmup = rest.getTranscodeStream(decision.params, mediaId = source.id)
            assertEquals(200, transcodeWarmup.status, "CONF-13 transcode warmup status")
            assertTrue(
                transcodeWarmup.body.hasMp3Signature(),
                "CONF-13 transcode warmup MP3 signature absent",
            )

            val transcoded = rest.getTranscodeStream(
                decision.params,
                mediaId = source.id,
                range = range,
            )
            assertPartialRange("CONF-13 transcoded", transcoded, range, expectedSize = 64)
            assertTrue(transcoded.body.hasMp3Signature(), "CONF-13 transcoded MP3 signature absent")

            val plan = requireExtensionPlan("CONF-13", source)
            val productionRange = requireAudio(
                playback.load(plan, range = PlaybackByteRange(0, 63)),
                "CONF-13 production loader rejected a ranged transcoded stream",
            )
            assertEquals(64, productionRange.bytes.size)
            assertTrue(productionRange.validation.supportsByteRanges)
            record(
                "CONF-13 OBSERVED raw_status=206 transcoded_status=206 requested_range=0-63 " +
                    "raw_content_range=${raw.contentRange} " +
                    "transcoded_content_range=${transcoded.contentRange}",
            )
        }
    }

    @Test
    fun conf14aLegacyTranscodeOffsetReturnsAudioAtTheRequestedTimeOffset() = runTest {
        withFixture {
            val source = requireSong("CONF-14a", THIRTY_ONE_SECOND_TITLE)
            assertEquals(31, source.durationSeconds, "CONF-14a boundary fixture duration drifted")
            requireTranscodingCapability("CONF-14a", source)

            // Distinct bitrates make both cache keys cold in the disposable instance. Reusing one
            // item/bitrate can silently turn the second observation into Navidrome's warm path.
            val full = requireAudio(
                playback.load(
                    requireLegacyMp3Plan(
                        "CONF-14a",
                        source,
                        offsetSeconds = 0,
                        maxBitRateKbps = FULL_BITRATE_KBPS,
                    ),
                ),
                "CONF-14a full legacy transcode failed after the capability precondition passed",
            )
            val seeked = requireAudio(
                playback.load(
                    requireLegacyMp3Plan(
                        "CONF-14a",
                        source,
                        offsetSeconds = SEEK_SECONDS,
                        maxBitRateKbps = SEEK_BITRATE_KBPS,
                    ),
                ),
                "CONF-14a legacy offset transcode failed after the capability precondition passed",
            )
            val fullEstimate = assertIs<PlaybackContentLength.Estimated>(full.plan.contentLength)
            val seekEstimate = assertIs<PlaybackContentLength.Estimated>(seeked.plan.contentLength)
            assertTrue(
                fullEstimate.estimatedByteCount > full.bytes.size,
                "CONF-14a cold full transcode did not expose the pinned estimate overshoot",
            )
            assertTrue(
                seekEstimate.estimatedByteCount > seeked.bytes.size,
                "CONF-14a cold offset transcode did not expose the pinned estimate overshoot",
            )
            assertTrue(full.bytes.hasMp3Signature())
            assertTrue(seeked.bytes.hasMp3Signature())
            assertTrue(seeked.bytes.size < full.bytes.size, "CONF-14a offset did not shorten the audio")
            val observedRemainingRatio =
                seeked.bytes.size.toDouble() / full.bytes.size *
                    FULL_BITRATE_KBPS / SEEK_BITRATE_KBPS
            val expectedRemainingRatio =
                (source.durationSeconds - SEEK_SECONDS).toDouble() / source.durationSeconds
            assertTrue(
                abs(observedRemainingRatio - expectedRemainingRatio) <= OFFSET_RATIO_TOLERANCE,
                "CONF-14a requested offset=$SEEK_SECONDS s expected remaining ratio " +
                    "$expectedRemainingRatio, observed $observedRemainingRatio " +
                    "(${seeked.bytes.size}/${full.bytes.size} bytes)",
            )
            record(
                "CONF-14a OBSERVED requested_offset_seconds=$SEEK_SECONDS " +
                    "source_duration_seconds=${source.durationSeconds} full_bytes=${full.bytes.size} " +
                    "full_estimate=${fullEstimate.estimatedByteCount} " +
                    "offset_bytes=${seeked.bytes.size} offset_estimate=${seekEstimate.estimatedByteCount} " +
                    "full_bitrate_kbps=$FULL_BITRATE_KBPS offset_bitrate_kbps=$SEEK_BITRATE_KBPS " +
                    "bitrate_normalized_remaining_ratio=$observedRemainingRatio",
            )
        }
    }

    @Test
    fun conf17LegacyTranscodeContentLengthIsUsableOnlyAsAnEstimate() = runTest {
        withFixture {
            val source = requireSong("CONF-17", HEALTH_PROBE_TITLE)
            requireTranscodingCapability("CONF-17", source)

            // 73 kbps is unique in this suite, so this request cannot inherit a warmed transcode.
            val audio = requireAudio(
                playback.load(
                    requireLegacyMp3Plan(
                        "CONF-17",
                        source,
                        offsetSeconds = 0,
                        maxBitRateKbps = CONF_17_COLD_BITRATE_KBPS,
                    ),
                ),
                "CONF-17 cold legacy transcode did not complete with an estimated length",
            )
            val estimate = assertIs<PlaybackContentLength.Estimated>(audio.plan.contentLength)
            assertTrue(audio.bytes.hasMp3Signature())
            assertTrue(
                estimate.estimatedByteCount > audio.bytes.size,
                "CONF-17 expected the pinned cold-cache estimate to overshoot the complete body",
            )
            record(
                "CONF-17 OBSERVED content_length_kind=estimate " +
                    "estimated_bytes=${estimate.estimatedByteCount} body_bytes=${audio.bytes.size} " +
                    "authoritative=false cache=cold bitrate_kbps=$CONF_17_COLD_BITRATE_KBPS",
            )
        }
    }

    @Test
    fun conf14bTranscodingExtensionHasNoOffsetAndIgnoresLegacyTimeOffset() = runTest {
        withFixture {
            val source = requireSong("CONF-14b", HEALTH_PROBE_TITLE)
            val decision = requireTranscodingCapability("CONF-14b", source)

            val baseline = rest.getTranscodeStream(decision.params, source.id)
            val unexpectedLegacyOffset = rest.getTranscodeStream(
                decision.params,
                source.id,
                extraParameters = mapOf("timeOffset" to "1"),
            )
            assertEquals(200, baseline.status)
            assertEquals(200, unexpectedLegacyOffset.status)
            assertContentEquals(
                baseline.body,
                unexpectedLegacyOffset.body,
                "CONF-14b reference server unexpectedly applied legacy timeOffset to Path A",
            )

            val withoutOffset = requireAudio(
                playback.load(requireExtensionPlan("CONF-14b", source)),
                "CONF-14b baseline extension load failed after the capability precondition passed",
            )
            val legacyOffsetOnExtensionRequest = extensionRequest(source, legacyOffsetSeconds = 1)
            val withIgnoredLegacyOffset = requireAudio(
                playback.load(
                    assertIs<PlaybackResolutionResult.Resolved>(
                        playback.resolve(legacyOffsetOnExtensionRequest),
                        "CONF-14b extension resolution failed after capability assertion",
                    ).plan,
                ),
                "CONF-14b extension load failed with a legacy offset present",
            )
            assertContentEquals(
                withoutOffset.bytes,
                withIgnoredLegacyOffset.bytes,
                "CONF-14b production Path A must not reinterpret the legacy offset",
            )
            record(
                "CONF-14b OBSERVED extension_offset_parameter=absent " +
                    "unrecognized_timeOffset_behavior=ignored status=200 " +
                    "baseline_bytes=${baseline.body.size} offset_attempt_bytes=${unexpectedLegacyOffset.body.size}",
            )
        }
    }

    @Test
    fun conf15ClientInfoDecisionOpaqueParamsRoundTripUnmodifiedToTranscodeStream() = runTest {
        withFixture {
            val source = requireSong("CONF-15", HEALTH_PROBE_TITLE)
            val decision = requireTranscodingCapability("CONF-15", source)
            assertEquals("POST", decision.method)
            assertTrue(decision.parameterLength > 0)

            val exactRoundTrip = rest.getTranscodeStream(decision.params, source.id)
            assertEquals(200, exactRoundTrip.status)
            assertTrue(exactRoundTrip.body.hasMp3Signature())
            assertTrue(
                rest.observations.last().opaqueParamsUnmodified == true,
                "CONF-15 conformance probe modified the opaque parameter",
            )

            val productionPlan = requireExtensionPlan("CONF-15", source)
            assertIs<PlaybackWireTranscodeDecision.Transcoded>(productionPlan.transcode)
            val productionLoad = requireAudio(
                playback.load(productionPlan),
                "CONF-15 production round-trip rejected the server-issued opaque parameters",
            )
            assertTrue(productionLoad.bytes.hasMp3Signature())
            record(
                "CONF-15 OBSERVED decision_status=200 decision_method=POST canTranscode=true " +
                    "opaque_parameter_characters=${decision.parameterLength} " +
                    "stream_status=200 round_trip=byte_for_byte_unmodified",
            )
        }
    }

    @Test
    fun conf22NowPlayingDoesNotIncrementButSubmittedScrobbleDoes() = runTest {
        withFixture {
            val song = requireSong("CONF-22", TWENTY_NINE_SECOND_TITLE)
            val before = rest.playCount(song.id)

            assertIs<ScrobbleSendResult.Sent>(
                scrobble.send(
                    ScrobbleEndpointRequest(RecordedPlaybackEvent.NowPlaying(song.itemId)),
                ),
                "CONF-22 submission=false failed",
            )
            val afterNowPlaying = rest.playCount(song.id)
            assertEquals(
                before,
                afterNowPlaying,
                "CONF-22 submission=false changed play count",
            )

            assertIs<ScrobbleSendResult.Sent>(
                scrobble.send(
                    ScrobbleEndpointRequest(
                        RecordedPlaybackEvent.SubmittedPlay(
                            song.itemId,
                            PlaybackWallClockTime(CONF_22_SESSION_TIME),
                        ),
                    ),
                ),
                "CONF-22 submission=true failed",
            )
            val afterSubmitted = rest.awaitPlayCount(song.id, before + 1)
            assertEquals(
                before + 1,
                afterSubmitted,
                "CONF-22 submission=true did not increment play count exactly once",
            )
            record(
                "CONF-22 OBSERVED before=$before after_submission_false=$afterNowPlaying " +
                    "after_submission_true=$afterSubmitted",
            )
        }
    }

    @Test
    fun conf23RepeatedSameTimeScrobbleIsNotDeduplicatedByReferenceServer() = runTest {
        withFixture {
            val song = requireSong("CONF-23", THIRTY_ONE_SECOND_TITLE)
            val before = rest.playCount(song.id)
            val request = ScrobbleEndpointRequest(
                RecordedPlaybackEvent.SubmittedPlay(
                    song.itemId,
                    PlaybackWallClockTime(CONF_23_REPEATED_SESSION_TIME),
                ),
            )

            assertIs<ScrobbleSendResult.Sent>(scrobble.send(request))
            val afterFirst = rest.awaitPlayCount(song.id, before + 1)
            assertIs<ScrobbleSendResult.Sent>(scrobble.send(request))
            val afterSecond = rest.awaitPlayCount(song.id, before + 2)
            assertEquals(before + 1, afterFirst, "CONF-23 first submission increment drifted")
            assertEquals(
                before + 2,
                afterSecond,
                "CONF-23 reference server unexpectedly deduplicated identical-time scrobbles",
            )
            record(
                "CONF-23 OBSERVED before=$before after_first=$afterFirst after_second=$afterSecond " +
                    "same_time_deduplicated=false",
            )
        }
    }

    @Test
    fun conf51LiveDownloadsValidateBeforeAtomicPromotion() = runTest {
        withFixture {
            val source = requireSong("CONF-51", HEALTH_PROBE_TITLE)
            assertEquals(AudioContainer.Flac, source.container, "CONF-51 corpus format drifted")

            val direct = requireAudio(
                playback.load(requireLegacyPlan("CONF-51", source)),
                "CONF-51 direct source failed before download promotion",
            )
            val directLength = assertIs<PlaybackContentLength.Exact>(
                direct.validation.contentLength,
                "CONF-51 direct source must supply an exact integrity boundary",
            )
            val directControl = DownloadPolicyContract.validatedAtomicPromotion(
                DownloadControlPayload(
                    serverId = CONFORMANCE_PROVIDER_ID,
                    rawId = source.id,
                    transcodeProfile = DownloadIdentity.ORIGINAL_PROFILE,
                    container = source.container,
                    contentType = "audio/flac",
                    contentLength = directLength,
                    bytes = direct.bytes,
                ),
            )
            assertEquals(direct.bytes.size.toLong(), directControl.promotedByteCount)
            assertEquals(direct.bytes.size.toLong(), directControl.storedExactByteCount)
            assertTrue(directControl.temporaryFileRemoved)
            assertTrue(directControl.exactMismatchRejected)
            assertTrue(
                directControl.exactMismatchLeftNoDestination,
                "CONF-51 exact-length mismatch appeared at the destination before validation",
            )
            assertTrue(directControl.duplicateDeliveryWasIdempotent)

            // Missing ffmpeg/transcoding is a failed precondition, never a skipped green control.
            requireTranscodingCapability("CONF-51", source)
            val coldLegacy = requireAudio(
                playback.load(
                    requireLegacyMp3Plan(
                        "CONF-51",
                        source,
                        offsetSeconds = 0,
                        maxBitRateKbps = CONF_51_COLD_BITRATE_KBPS,
                    ),
                    purpose = com.legitimateapps.dulcet.core.PlaybackWireRequestPurpose.Download,
                ),
                "CONF-51 cold legacy transcode failed after capability assertion",
            )
            val estimate = assertIs<PlaybackContentLength.Estimated>(
                coldLegacy.validation.contentLength,
                "CONF-51 cold legacy transcode must retain estimated-length semantics",
            )
            assertNotEquals(
                estimate.estimatedByteCount,
                coldLegacy.bytes.size.toLong(),
                "CONF-51 cold transcode unexpectedly lost the pinned estimate mismatch",
            )
            val estimatedControl = DownloadPolicyContract.validatedAtomicPromotion(
                DownloadControlPayload(
                    serverId = CONFORMANCE_PROVIDER_ID,
                    rawId = source.id,
                    transcodeProfile = "legacy-mp3:$CONF_51_COLD_BITRATE_KBPS",
                    container = AudioContainer.Mp3,
                    contentType = "audio/mpeg",
                    contentLength = estimate,
                    bytes = coldLegacy.bytes,
                ),
            )
            assertEquals(coldLegacy.bytes.size.toLong(), estimatedControl.storedExactByteCount)
            assertTrue(estimatedControl.temporaryFileRemoved)
            assertTrue(estimatedControl.exactMismatchLeftNoDestination)
            record(
                "CONF-51 OBSERVED direct_exact_bytes=${directLength.byteCount} " +
                    "cold_estimate_bytes=${estimate.estimatedByteCount} " +
                    "cold_observed_exact_after_close=${estimatedControl.storedExactByteCount} " +
                    "exact_mismatch_rejected=${directControl.exactMismatchRejected} " +
                    "destination_absent_on_rejection=${directControl.exactMismatchLeftNoDestination} " +
                    "duplicate_idempotent=${directControl.duplicateDeliveryWasIdempotent}",
            )
        }
    }

    @Test
    fun conf52PromotedLiveItemProducesLocalPlanAfterNetworkClientsClose() = runTest {
        withFixture {
            val source = requireSong("CONF-52", HEALTH_PROBE_TITLE)
            val downloaded = requireAudio(
                playback.load(requireLegacyPlan("CONF-52", source)),
                "CONF-52 source failed before the offline boundary",
            )
            val exactLength = assertIs<PlaybackContentLength.Exact>(
                downloaded.validation.contentLength,
                "CONF-52 direct source did not supply an exact length",
            )
            closeNetworkClients()

            val offline = DownloadPolicyContract.offlinePlayback(
                DownloadControlPayload(
                    serverId = CONFORMANCE_PROVIDER_ID,
                    rawId = source.id,
                    transcodeProfile = DownloadIdentity.ORIGINAL_PROFILE,
                    container = source.container,
                    contentType = "audio/flac",
                    contentLength = exactLength,
                    bytes = downloaded.bytes,
                ),
            )
            assertIs<PlaybackPlan>(offline.plan)
            assertEquals(source.id, offline.plan.identity.rawId)
            assertEquals(downloaded.bytes.size.toLong(), offline.plan.exactByteLength)
            assertContentEquals(downloaded.bytes, offline.loadedBytes)
            record(
                "CONF-52 OBSERVED network_clients=closed plan=LocalPlaybackPlan " +
                    "bytes=${offline.loadedBytes.size} source=promoted_destination",
            )
        }
    }

    private suspend fun PlaybackFixture.requireSong(confId: String, title: String): SeedSong {
        val response = rest.getJson(
            "search3",
            mapOf(
                "query" to title,
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to "20",
            ),
        ).payload(confId)
        val result = response.objectField("searchResult3", confId)
        val songs = result.arrayField("song", confId)
        val match = songs.mapNotNull { it as? JsonObject }.singleOrNull {
            it.stringField("title", confId) == title
        } ?: error("$confId required seeded song is absent: $title")
        val suffix = match.stringField("suffix", confId)
        val container = when (suffix.lowercase()) {
            "flac" -> AudioContainer.Flac
            "mp3" -> AudioContainer.Mp3
            "m4a", "mp4" -> AudioContainer.Mp4
            "ogg" -> AudioContainer.Ogg
            else -> error("$confId unsupported seeded container: $suffix")
        }
        return SeedSong(
            id = match.stringField("id", confId),
            title = title,
            container = container,
            durationSeconds = match.intField("duration", confId),
        )
    }

    private suspend fun PlaybackFixture.requireTranscodingCapability(
        confId: String,
        source: SeedSong,
    ): TranscodeDecisionEvidence {
        val extensions = rest.getJson("getOpenSubsonicExtensions").payload(confId)
            .arrayField("openSubsonicExtensions", confId)
            .mapNotNull { it as? JsonObject }
        val transcoding = extensions.singleOrNull {
            it.stringField("name", confId) == "transcoding"
        } ?: error("$confId precondition failed: transcoding extension is absent")
        assertTrue(
            transcoding.arrayField("versions", confId).map { version ->
                (version as? JsonPrimitive)?.intOrNull
                    ?: error("$confId precondition failed: malformed extension version")
            }.contains(1),
            "$confId precondition failed: transcoding v1 is absent",
        )

        val response = rest.postJson(
            endpoint = "getTranscodeDecision",
            parameters = mapOf("mediaId" to source.id, "mediaType" to "song"),
            body = TRANSCODE_CLIENT_INFO,
        )
        assertEquals(200, response.status, "$confId transcode decision status")
        val decision = response.payload(confId).objectField("transcodeDecision", confId)
        assertEquals(
            false,
            decision.booleanField("canDirectPlay", confId),
            "$confId precondition failed: restrictive profile unexpectedly direct-plays",
        )
        assertEquals(
            true,
            decision.booleanField("canTranscode", confId),
            "$confId precondition failed: server did not prove canTranscode=true",
        )
        val params = OpaqueTranscodeParams.fromDecision(decision, confId)
        return TranscodeDecisionEvidence(
            params = params,
            method = rest.observations.last().method,
            parameterLength = params.length,
        )
    }

    private suspend fun PlaybackFixture.requireLegacyPlan(
        confId: String,
        song: SeedSong,
    ) = assertIs<PlaybackResolutionResult.Resolved>(
        playback.resolve(legacyRequest(song)),
        "$confId legacy plan did not resolve",
    ).plan.also {
        assertEquals(PlaybackDeliveryPath.Legacy, it.path, "$confId resolved the wrong path")
    }

    private suspend fun PlaybackFixture.requireLegacyMp3Plan(
        confId: String,
        song: SeedSong,
        offsetSeconds: Int,
        maxBitRateKbps: Int = FULL_BITRATE_KBPS,
    ) = assertIs<PlaybackResolutionResult.Resolved>(
        playback.resolve(
            legacyRequest(song).copy(
                legacyPreference = LegacyPlaybackPreference(AudioContainer.Mp3, maxBitRateKbps),
                legacyTimeOffset = offsetSeconds.seconds,
            ),
        ),
        "$confId legacy MP3 plan did not resolve",
    ).plan.also {
        assertEquals(PlaybackDeliveryPath.Legacy, it.path, "$confId resolved the wrong path")
        assertIs<PlaybackWireTranscodeDecision.LegacyHint>(it.transcode)
    }

    private suspend fun PlaybackFixture.requireExtensionPlan(
        confId: String,
        song: SeedSong,
    ) = assertIs<PlaybackResolutionResult.Resolved>(
        playback.resolve(extensionRequest(song)),
        "$confId extension plan did not resolve after canTranscode=true",
    ).plan.also {
        assertEquals(
            PlaybackDeliveryPath.ExtensionTranscode,
            it.path,
            "$confId precondition failed: production did not select extension transcoding",
        )
        assertIs<PlaybackWireTranscodeDecision.Transcoded>(it.transcode)
    }

    private fun legacyRequest(song: SeedSong) = PlaybackResolveRequest(
        playbackSessionId = PlaybackSessionId("session:${song.title}"),
        attemptId = AttemptId("attempt:${song.title}:legacy"),
        itemId = song.itemId,
        sourceContainer = song.container,
        supportsTranscodingExtension = false,
        deviceProfile = DEVICE_PROFILE,
        legacyPreference = LegacyPlaybackPreference(null, null),
    )

    private fun extensionRequest(song: SeedSong, legacyOffsetSeconds: Int? = null) =
        PlaybackResolveRequest(
            playbackSessionId = PlaybackSessionId("session:${song.title}"),
            attemptId = AttemptId("attempt:${song.title}:extension:${legacyOffsetSeconds ?: 0}"),
            itemId = song.itemId,
            sourceContainer = song.container,
            supportsTranscodingExtension = true,
            deviceProfile = DEVICE_PROFILE,
            legacyPreference = LegacyPlaybackPreference(null, null),
            legacyTimeOffset = legacyOffsetSeconds?.seconds,
        )

    private suspend fun <T> withFixture(block: suspend PlaybackFixture.() -> T): T {
        val fixture = PlaybackFixture()
        return try {
            fixture.block()
        } finally {
            fixture.closeAndAssertRedaction()
        }
    }

    private fun assertPartialRange(
        label: String,
        response: RestResponse,
        requestedRange: String,
        expectedSize: Int,
    ) {
        assertEquals(206, response.status, "$label status")
        assertEquals(expectedSize, response.body.size, "$label body length")
        assertTrue(
            response.contentRange?.startsWith("bytes ${requestedRange.removePrefix("bytes=")}/") == true,
            "$label Content-Range did not preserve $requestedRange: ${response.contentRange}",
        )
        assertEquals("bytes", response.acceptRanges?.lowercase(), "$label Accept-Ranges")
    }


    /**
     * `assertIs` reports only that the value was `Failed` rather than `Audio`, which discards the
     * entire finding: a conformance failure IS the DomainError, the wire status and the response
     * shape the server actually produced. OBSERVED 2026-08-28, a CONF-14a failure in core-ci said
     * only "Expected value to be of type ...Audio, actual ...Failed", which named the symptom and
     * nothing that could be acted on. Surface what the load actually returned.
     */
    private fun requireAudio(
        result: PlaybackLoadResult,
        message: String,
    ): PlaybackLoadResult.Audio = when (result) {
        is PlaybackLoadResult.Audio -> result
        is PlaybackLoadResult.Failed -> fail(
            "$message -- load returned Failed(error=${result.error}, " +
                "presentationError=${result.presentationError}, shape=${result.shape}, " +
                "path=${result.plan.path}, expectedContainer=${result.plan.expectedContainer})",
        )
        else -> fail("$message -- load returned ${result::class.simpleName}")
    }

    private fun record(message: String) {
        println(message)
    }

    private companion object {
        const val HEALTH_PROBE_TITLE = "Dulcet Health Probe"
        const val TWENTY_NINE_SECOND_TITLE = "Twenty Nine Seconds"
        const val THIRTY_ONE_SECOND_TITLE = "Thirty One Seconds"
        const val MISSING_OPAQUE_ID = "missing:opaque:CONF-12:not-an-integer"
        const val SEEK_SECONDS = 15
        const val FULL_BITRATE_KBPS = 64
        const val SEEK_BITRATE_KBPS = 96
        const val CONF_17_COLD_BITRATE_KBPS = 73
        const val CONF_51_COLD_BITRATE_KBPS = 81
        const val OFFSET_RATIO_TOLERANCE = 0.03
        const val CONF_22_SESSION_TIME = 1_788_220_000_001
        const val CONF_23_REPEATED_SESSION_TIME = 1_788_230_000_001

        val FLAC_CONTENT_TYPES = setOf("audio/flac", "audio/x-flac", "application/octet-stream")

        val DEVICE_PROFILE = PlaybackDeviceProfile(
            name = "Dulcet Conformance",
            platform = "Kotlin Multiplatform",
            maxAudioBitrate = 320,
            maxTranscodingAudioBitrate = 64,
            directPlayProfiles = listOf(
                DirectPlayAudioProfile(
                    containers = listOf(AudioContainer.Ogg),
                    audioCodecs = listOf("vorbis"),
                    maxAudioChannels = 2,
                ),
            ),
            transcodingProfiles = listOf(
                TranscodingAudioProfile(
                    container = AudioContainer.Mp3,
                    audioCodec = "mp3",
                    maxAudioChannels = 2,
                ),
            ),
        )

        val TRANSCODE_CLIENT_INFO = buildJsonObject {
            put("name", "Dulcet Conformance")
            put("platform", "Kotlin Multiplatform")
            put("maxAudioBitrate", 320)
            put("maxTranscodingAudioBitrate", 64)
            put(
                "directPlayProfiles",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("containers", buildJsonArray { add(JsonPrimitive("ogg")) })
                            put("audioCodecs", buildJsonArray { add(JsonPrimitive("vorbis")) })
                            put("protocols", buildJsonArray { add(JsonPrimitive("http")) })
                            put("maxAudioChannels", 2)
                        },
                    )
                },
            )
            put(
                "transcodingProfiles",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("container", "mp3")
                            put("audioCodec", "mp3")
                            put("protocol", "http")
                            put("maxAudioChannels", 2)
                        },
                    )
                },
            )
            put("codecProfiles", JsonArray(emptyList()))
        }
    }
}

private class PlaybackFixture {
    private val credentialEvidence = CredentialEvidence()
    private val logs = mutableListOf<String>()
    private val account = PlaybackEndpointAccount(
        providerInstanceId = CONFORMANCE_PROVIDER_ID,
        normalizedBaseUrl = disposableConformanceBaseUrl(),
        username = conformanceUsername(),
        password = conformancePassword(),
        allowLocalHttp = true,
    )
    val rest = RestProbe(account, credentialEvidence)
    val playback = PlaybackWireClient(
        account = account,
        saltSource = SaltSource(credentialEvidence::nextSalt),
        logSink = LogSink(logs::add),
    )
    val scrobble = ScrobbleEndpointSender(
        account = account,
        saltSource = SaltSource(credentialEvidence::nextSalt),
        logSink = LogSink(logs::add),
    )
    private var networkClientsClosed = false

    fun closeNetworkClients() {
        if (networkClientsClosed) return
        rest.close()
        playback.close()
        scrobble.close()
        networkClientsClosed = true
    }

    fun closeAndAssertRedaction() {
        closeNetworkClients()
        credentialEvidence.assertFreshAndRedacted(
            logs + rest.observations.map(SafeRestObservation::toString),
        )
    }
}

private class CredentialEvidence {
    private val secure = AccountConnectionContract.secureSaltSource()
    private val salts = mutableListOf<String>()
    private val tokens = mutableListOf<String>()

    fun nextSalt(): String = secure.nextSalt().also { salt ->
        check(salt.length == 32 && salt.all { it in '0'..'9' || it in 'a'..'f' })
        check(salt !in salts) { "CSPRNG produced a duplicate conformance salt" }
        salts += salt
        tokens += AccountConnectionContract.saltedToken(conformancePassword(), salt)
    }

    fun token(password: String, salt: String): String =
        AccountConnectionContract.saltedToken(password, salt)

    fun assertFreshAndRedacted(surfaces: List<String>) {
        assertEquals(salts.size, salts.toSet().size, "request salts must be fresh")
        val diagnostic = surfaces.joinToString("\n")
        val canaries = listOf(conformanceUsername(), conformancePassword()) + salts + tokens
        canaries.forEach { canary ->
            assertFalse(
                diagnostic.contains(canary),
                "credential canary escaped whole-query redaction",
            )
        }
        assertTrue(surfaces.all { '?' !in it || "?<redacted>" in it })
    }
}

private class RestProbe(
    private val account: PlaybackEndpointAccount,
    private val credentialEvidence: CredentialEvidence,
) {
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false
    }
    val observations = mutableListOf<SafeRestObservation>()

    suspend fun getJson(
        endpoint: String,
        parameters: Map<String, String> = emptyMap(),
    ): RestResponse = get(endpoint, parameters).also { response ->
        assertTrue(
            response.contentType.mediaType() in setOf("application/json", "text/json"),
            "$endpoint did not return JSON",
        )
    }

    suspend fun get(
        endpoint: String,
        parameters: Map<String, String> = emptyMap(),
        range: String? = null,
    ): RestResponse = execute(endpoint, HttpMethod.Get, parameters, null, range)

    suspend fun postJson(
        endpoint: String,
        parameters: Map<String, String>,
        body: JsonObject,
    ): RestResponse = execute(endpoint, HttpMethod.Post, parameters, body.toString(), null)

    suspend fun getTranscodeStream(
        params: OpaqueTranscodeParams,
        mediaId: String,
        range: String? = null,
        extraParameters: Map<String, String> = emptyMap(),
    ): RestResponse {
        val exactOpaqueValue = params.wireValue()
        return execute(
            endpoint = "getTranscodeStream",
            method = HttpMethod.Get,
            parameters = linkedMapOf(
                "transcodeParams" to exactOpaqueValue,
                "mediaId" to mediaId,
                "mediaType" to "song",
            ) + extraParameters,
            body = null,
            range = range,
            expectedOpaqueParams = params,
        )
    }

    suspend fun playCount(id: String): Long {
        val song = getJson("getSong", mapOf("id" to id)).payload("play count")
            .objectField("song", "play count")
        return (song["playCount"] as? JsonPrimitive)?.longOrNull ?: 0L
    }

    suspend fun awaitPlayCount(id: String, expected: Long): Long {
        repeat(20) {
            val observed = playCount(id)
            if (observed == expected) return observed
            delay(50)
        }
        return playCount(id)
    }

    fun close() = client.close()

    private suspend fun execute(
        endpoint: String,
        method: HttpMethod,
        parameters: Map<String, String>,
        body: String?,
        range: String?,
        expectedOpaqueParams: OpaqueTranscodeParams? = null,
    ): RestResponse {
        val salt = credentialEvidence.nextSalt()
        val token = credentialEvidence.token(account.password, salt)
        val common = linkedMapOf(
            "u" to account.username,
            "t" to token,
            "s" to salt,
            "v" to "1.16.1",
            "c" to "dulcet-conformance",
            "f" to "json",
        )
        val opaqueParamsUnmodified = expectedOpaqueParams?.let { expected ->
            parameters["transcodeParams"] == expected.wireValue()
        }
        val response = try {
            client.request("${account.normalizedBaseUrl}/rest/$endpoint.view") {
                this.method = method
                (common + parameters).forEach { (name, value) -> parameter(name, value) }
                range?.let { header(HttpHeaders.Range, it) }
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "$endpoint request failed before a response: ${failure::class.simpleName}",
            )
        }
        val result = RestResponse(
            status = response.status.value,
            body = try {
                response.bodyAsBytes()
            } catch (failure: Throwable) {
                throw AssertionError(
                    "$endpoint response body failed: ${failure::class.simpleName}",
                )
            },
            contentType = response.headers[HttpHeaders.ContentType],
            contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            acceptRanges = response.headers[HttpHeaders.AcceptRanges],
            contentRange = response.headers[HttpHeaders.ContentRange],
        )
        observations += SafeRestObservation(
            method = method.value,
            endpoint = endpoint,
            status = result.status,
            redactedUrl = "${account.normalizedBaseUrl}/rest/$endpoint.view?<redacted>",
            opaqueParamsUnmodified = opaqueParamsUnmodified,
        )
        return result
    }
}

private data class RestResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String?,
    val contentLength: Long?,
    val acceptRanges: String?,
    val contentRange: String?,
) {
    override fun toString(): String = "RestResponse(status=$status, body=<redacted>)"
}

private data class SafeRestObservation(
    val method: String,
    val endpoint: String,
    val status: Int,
    val redactedUrl: String,
    val opaqueParamsUnmodified: Boolean?,
)

private class OpaqueTranscodeParams private constructor(private val value: String) {
    val length: Int get() = value.length
    fun wireValue(): String = value
    override fun toString(): String = "OpaqueTranscodeParams(<redacted>)"

    companion object {
        fun fromDecision(decision: JsonObject, confId: String): OpaqueTranscodeParams {
            val primitive = decision["transcodeParams"] as? JsonPrimitive
                ?: error("$confId decision omitted transcodeParams")
            check(primitive.isString) { "$confId transcodeParams is not an opaque string" }
            val value = primitive.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: error("$confId decision returned blank transcodeParams")
            return OpaqueTranscodeParams(value)
        }
    }
}

private data class TranscodeDecisionEvidence(
    val params: OpaqueTranscodeParams,
    val method: String,
    val parameterLength: Int,
)

private data class SeedSong(
    val id: String,
    val title: String,
    val container: AudioContainer,
    val durationSeconds: Int,
) {
    val itemId = ProviderItemId(CONFORMANCE_PROVIDER_ID, id)
    override fun toString(): String = "SeedSong(title=$title, id=<redacted>)"
}

private fun RestResponse.payload(confId: String): JsonObject {
    assertEquals(200, status, "$confId JSON endpoint status")
    val root = try {
        Json.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: error("$confId response is not a JSON object")
    val payload = root["subsonic-response"] as? JsonObject
        ?: error("$confId response omitted subsonic-response")
    assertEquals("ok", payload.stringField("status", confId), "$confId server envelope failed")
    return payload
}

private fun JsonObject.objectField(name: String, confId: String): JsonObject =
    this[name] as? JsonObject ?: error("$confId response omitted object $name")

private fun JsonObject.arrayField(name: String, confId: String): JsonArray =
    this[name] as? JsonArray ?: error("$confId response omitted array $name")

private fun JsonObject.stringField(name: String, confId: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: error("$confId response omitted string $name")
    check(primitive.isString) { "$confId response field $name is not a string" }
    return primitive.contentOrNull ?: error("$confId response field $name is null")
}

private fun JsonObject.booleanField(name: String, confId: String): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull
        ?: error("$confId response omitted boolean $name")

private fun JsonObject.intField(name: String, confId: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull
        ?: error("$confId response omitted integer $name")

private fun String?.mediaType(): String? = this?.substringBefore(';')?.trim()?.lowercase()

private fun ByteArray.matchesAscii(offset: Int, expected: String): Boolean =
    size >= offset + expected.length && expected.indices.all { index ->
        this[offset + index].toInt() and 0xff == expected[index].code
    }

private fun ByteArray.hasMp3Signature(): Boolean {
    if (matchesAscii(0, "ID3")) return true
    if (size < 4) return false
    val header = ((this[0].toInt() and 0xff) shl 24) or
        ((this[1].toInt() and 0xff) shl 16) or
        ((this[2].toInt() and 0xff) shl 8) or
        (this[3].toInt() and 0xff)
    return header and 0xffe00000.toInt() == 0xffe00000.toInt()
}

private fun ByteArray.isSubsonicEnvelope(): Boolean = try {
    val root = Json.parseToJsonElement(decodeToString()) as? JsonObject
    root?.get("subsonic-response") is JsonObject
} catch (_: IllegalArgumentException) {
    false
}

private fun conformanceUsername(): String =
    environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: "dulcet-admin"

private fun conformancePassword(): String =
    environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: "dulcet-ci-canary-password"

private const val CONFORMANCE_PROVIDER_ID = "provider:disposable-conformance"
