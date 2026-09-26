package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The I/O half of §18.4: the endpoint gate, the parser, and the seen-cache round trip. */
class LibraryLyricsTest {
    // ---- The gate (CORPUS §4 line 7: a conjunction) --------------------------------------------------

    @Test
    fun theStructuredEndpointNeedsOpenSubsonicAndAnImplementedVersion() {
        assertEquals(LyricsEndpoint.SongLyricsEnhanced, lyricsEndpointFor(capabilities(setOf(1, 2))))
        assertEquals(LyricsEndpoint.SongLyrics, lyricsEndpointFor(capabilities(setOf(1))))
        assertEquals(LyricsEndpoint.SongLyricsEnhanced, lyricsEndpointFor(capabilities(setOf(2))))
        // Each conjunct false on its own sends the legacy endpoint.
        assertEquals(LyricsEndpoint.Legacy, lyricsEndpointFor(capabilities(null)))
        assertEquals(LyricsEndpoint.Legacy, lyricsEndpointFor(capabilities(setOf(3))))
        assertEquals(LyricsEndpoint.Legacy, lyricsEndpointFor(capabilities(setOf(1, 2), legacySubsonic = true)))
        assertEquals(
            LyricsEndpoint.Legacy,
            lyricsEndpointFor(capabilities(setOf(1, 2)), knownQuirkBlocksSongLyrics = true),
        )
    }

    @Test
    fun parametersFollowTheEndpoint() {
        val track = LyricsTrack("tr-9f", "Dulcet Fixtures", "Thirty One Seconds")
        assertEquals(mapOf("id" to "tr-9f"), lyricsParameters(LyricsEndpoint.SongLyrics, track))
        assertEquals(mapOf("id" to "tr-9f", "enhanced" to "true"), lyricsParameters(LyricsEndpoint.SongLyricsEnhanced, track))
        assertEquals(
            mapOf("artist" to "Dulcet Fixtures", "title" to "Thirty One Seconds"),
            lyricsParameters(LyricsEndpoint.Legacy, track),
        )
        assertEquals(mapOf("title" to "T"), lyricsParameters(LyricsEndpoint.Legacy, LyricsTrack("x", " ", "T")))
    }

    // ---- The parser ---------------------------------------------------------------------------------

    @Test
    fun theReferenceServersStructuredShapeParses() {
        val (source, layers) = parseLyricsResponse(LyricsEndpoint.SongLyricsEnhanced, OBSERVED_SIDECAR_LRC)
        assertEquals(LyricsSource.SongLyricsExtension, source)
        val layer = layers.single()
        assertNull(layer.language, "xxx is the unspecified-language code and must read as unknown")
        assertTrue(layer.synced)
        assertEquals(250.milliseconds, layer.offset)
        assertEquals("main", layer.kind)
        assertEquals(listOf(500L, 2_000L, 3_000L, 4_250L), layer.lines.map { it.startMilliseconds })
        assertEquals("", layer.lines[2].text, "a blank LRC line is kept: it is the instrumental gap")
        assertEquals("Dulcet Fixtures", layer.displayArtist)
    }

    @Test
    fun severalLanguagesParseInServerOrderAndSyncedLinesAreSorted() {
        val body = envelope(
            """"lyricsList":{"structuredLyrics":[
              {"lang":"deu","synced":true,"line":[{"start":2000,"value":"zwei"},{"start":1000,"value":"eins"}]},
              {"lang":"eng","synced":false,"line":[{"value":"plain"}],"unknownField":{"x":1}}
            ]}""",
        )
        val (_, layers) = parseLyricsResponse(LyricsEndpoint.SongLyrics, body)
        assertEquals(listOf("deu", "eng"), layers.map { it.language })
        assertEquals(listOf("eins", "zwei"), layers[0].lines.map { it.text })
        assertEquals(0.milliseconds, layers[1].offset, "an absent offset MUST be read as 0")
        assertNull(layers[1].lines.single().start)
    }

    @Test
    fun noStructuredLyricsIsNoneAndAMalformedEntryIsAFailure() {
        assertEquals(emptyList(), parseLyricsResponse(LyricsEndpoint.SongLyrics, envelope(""""lyricsList":{}""")).second)
        val syncedWithoutStart = envelope(""""lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"line":[{"value":"x"}]}]}""")
        val failure = runCatching { parseLyricsResponse(LyricsEndpoint.SongLyrics, syncedWithoutStart) }.exceptionOrNull()
        assertEquals(DomainError.Protocol.MalformedEnvelope, assertIs<LibraryRequestFailure>(failure).error)
        val noList = runCatching { parseLyricsResponse(LyricsEndpoint.SongLyrics, envelope(null)) }.exceptionOrNull()
        assertIs<LibraryRequestFailure>(noList)
    }

    @Test
    fun legacyTextSplitsIntoLinesAndAnEmptyValueIsNone() {
        val (source, layers) = parseLyricsResponse(
            LyricsEndpoint.Legacy,
            envelope(""""lyrics":{"artist":"A","title":"T","value":"\none\n\ntwo\r\nthree\n\n"}"""),
        )
        assertEquals(LyricsSource.LegacyGetLyrics, source)
        val layer = layers.single()
        assertFalse(layer.synced)
        assertNull(layer.language)
        assertEquals(listOf("one", "", "two", "three"), layer.lines.map { it.text })
        // OBSERVED on the reference server for an unmatched artist/title.
        assertEquals(emptyList(), parseLyricsResponse(LyricsEndpoint.Legacy, envelope(""""lyrics":{"value":""}""")).second)
        // An explicit null is the same statement as an absent value (the reviewer's N8).
        assertEquals(emptyList(), parseLyricsResponse(LyricsEndpoint.Legacy, envelope(""""lyrics":{"value":null}""")).second)
    }

    // ---- Read, write-through, offline ---------------------------------------------------------------

    @Test
    fun aLiveReadIsWrittenThroughAndServedOfflineWithNoRequest() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val lyrics = env.lyrics(capabilities(setOf(1, 2)))
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.NotCachedOffline), lyrics.cached(TRACK).freshness)

        val live = lyrics.read(TRACK)
        assertEquals(LibraryFreshness.Live, live.freshness)
        assertEquals(listOf("getLyricsBySongId"), env.transport.endpoints())
        assertEquals(mapOf("id" to TRACK.rawId, "enhanced" to "true"), env.transport.log.single().second)
        assertEquals(4, live.lyrics?.selected?.lines?.size)

        env.session.setOnline(false)
        val offline = lyrics.read(TRACK)
        assertEquals(1, env.transport.log.size, "an offline read must send nothing")
        assertEquals(LibraryFreshness.Cached(env.clock.now, LibraryCachedReason.Offline), offline.freshness)
        assertEquals(live.lyrics, offline.lyrics)
        assertEquals(live.lyrics, lyrics.cached(TRACK).lyrics)
        assertEquals(1, env.transport.log.size)
    }

    @Test
    fun aFailedStructuredReadNeverFallsBackToTheLegacyEndpoint() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val lyrics = env.lyrics(capabilities(setOf(1)))
        lyrics.read(TRACK)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        val failed = lyrics.read(TRACK)
        assertEquals(listOf("getLyricsBySongId", "getLyricsBySongId"), env.transport.endpoints())
        val freshness = assertIs<LibraryFreshness.Cached>(failed.freshness)
        assertEquals(LibraryCachedReason.Failed(DomainError.Transport.Timeout), freshness.reason)
        assertNotNull(failed.lyrics?.selected)

        val other = LyricsTrack("never-read", "A", "B")
        val nothing = lyrics.read(other)
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Transport.Timeout)), nothing.freshness)
        assertNull(nothing.lyrics)
        assertEquals(0, env.transport.endpoints().count { it == "getLyrics" })
    }

    @Test
    fun aServerWithoutTheExtensionIsAskedByArtistAndTitle() = lyricsTest { env ->
        env.transport.bodies["getLyrics"] = envelope(""""lyrics":{"artist":"A","title":"T","value":"one\ntwo"}""")
        val lyrics = env.lyrics(capabilities(null, legacySubsonic = true))
        val read = lyrics.read(TRACK)
        assertEquals(listOf("getLyrics"), env.transport.endpoints())
        assertEquals(mapOf("artist" to TRACK.artist, "title" to TRACK.title), env.transport.log.single().second)
        assertEquals(LyricsSource.LegacyGetLyrics, read.lyrics?.source)
        assertEquals(listOf("one", "two"), read.lyrics?.selected?.lines?.map { it.text })
    }

    @Test
    fun aTrackTheServerDoesNotKnowIsStoredAsHavingNoLyrics() = lyricsTest { env ->
        env.transport.codes["getLyricsBySongId"] = 70
        val read = env.lyrics(capabilities(setOf(1))).read(TRACK)
        assertEquals(LibraryFreshness.Live, read.freshness)
        assertEquals(emptyList(), read.lyrics?.layers)
        assertNull(read.lyrics?.selected)
        assertEquals(emptyList(), env.cache.lyrics(TRACK.rawId)?.layers)
    }

    @Test
    fun theSelectionUsesThePersonsLanguages() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = envelope(
            """"lyricsList":{"structuredLyrics":[
              {"lang":"deu","synced":true,"line":[{"start":1000,"value":"deutsch"}]},
              {"lang":"eng","synced":true,"line":[{"start":1000,"value":"english"}]},
              {"lang":"por","synced":false,"line":[{"value":"portugues"}]}
            ]}""",
        )
        assertEquals("english", env.lyrics(capabilities(setOf(1)), listOf("en-US")).read(TRACK).lyrics?.selected?.lines?.single()?.text)
        assertEquals("deutsch", env.lyrics(capabilities(setOf(1)), listOf("de")).cached(TRACK).lyrics?.selected?.lines?.single()?.text)
    }

    // ---- The §10.4 breaker, through the real read path -------------------------------------------------

    @Test
    fun threeFailuresStopCallsWithoutRevokingTheCapability() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val lyrics = env.lyrics(capabilities(setOf(1, 2)))
        lyrics.read(TRACK)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        repeat(3) { assertFalse(lyrics.read(TRACK).breakerOpen) }
        assertEquals(4, env.transport.log.size)
        assertTrue(lyrics.breakerOpen)

        val refused = lyrics.read(TRACK)
        assertEquals(4, env.transport.log.size, "an open breaker must send nothing")
        assertTrue(refused.breakerOpen)
        assertEquals(LibraryCachedReason.Failed(DomainError.Transport.Unreachable), assertIs<LibraryFreshness.Cached>(refused.freshness).reason)
        assertNotNull(refused.lyrics?.selected, "the stored document is still shown")
        // Not revoked: the gate still chooses the structured endpoint, and nothing fell back.
        assertEquals(LyricsEndpoint.SongLyricsEnhanced, lyrics.endpoint)
        assertEquals(LyricsEndpoint.SongLyricsEnhanced, env.lyrics(capabilities(setOf(1, 2))).endpoint)
        assertEquals(0, env.transport.endpoints().count { it == "getLyrics" })
        val nothingStored = lyrics.read(LyricsTrack("never-read", "A", "B"))
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Transport.Unreachable)), nothingStored.freshness)
        assertTrue(nothingStored.breakerOpen)
        assertNull(env.cache.lyrics("never-read"), "a refused read is not \"no lyrics\"")
        assertEquals(4, env.transport.log.size)
    }

    @Test
    fun theBreakerBelongsToTheSessionNotToOneLyricsObject() = lyricsTest { env ->
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        repeat(3) { env.lyrics(capabilities(setOf(1))).read(TRACK) }
        val again = env.lyrics(capabilities(setOf(1)))
        assertTrue(again.read(TRACK).breakerOpen)
        assertEquals(3, env.transport.log.size)
    }

    @Test
    fun afterThePeriodOneTrialIsSentAndItsResultDecides() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        val lyrics = env.lyrics(capabilities(setOf(1)))
        repeat(3) { lyrics.read(TRACK) }
        env.monotonic += BREAKER_OPEN_MILLIS - 1
        assertTrue(lyrics.read(TRACK).breakerOpen)
        assertEquals(3, env.transport.log.size)

        env.monotonic += 1
        assertFalse(lyrics.read(TRACK).breakerOpen, "the trial is sent")
        assertEquals(4, env.transport.log.size)
        assertTrue(lyrics.read(TRACK).breakerOpen, "a failed trial reopens at once")
        assertEquals(4, env.transport.log.size)

        env.monotonic += BREAKER_OPEN_MILLIS
        env.transport.failWith.clear()
        val recovered = lyrics.read(TRACK)
        assertEquals(LibraryFreshness.Live, recovered.freshness)
        assertFalse(lyrics.breakerOpen)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        repeat(2) { lyrics.read(TRACK) }
        assertFalse(lyrics.read(TRACK).breakerOpen, "closed again: a full threshold is needed")
        assertEquals(8, env.transport.log.size)
        assertEquals(setOf("getLyricsBySongId"), env.transport.endpoints().toSet(), "the trial is the same endpoint")
    }

    @Test
    fun aCancelledTrialGivesItsSlotBackAndCountsForNothing() = lyricsTest { env ->
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        val lyrics = env.lyrics(capabilities(setOf(1)))
        repeat(3) { lyrics.read(TRACK) }
        env.monotonic += BREAKER_OPEN_MILLIS
        env.transport.hold = CompletableDeferred()
        val trial = launch { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(4, env.transport.log.size, "the trial was sent and is waiting for its answer")
        assertTrue(lyrics.breakerOpen, "while the trial is out every other call is refused")
        trial.cancelAndJoin()

        env.transport.hold = null
        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertFalse(lyrics.breakerOpen, "a cancelled trial gives its slot back, and is not a failure")
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(5, env.transport.log.size)
    }

    @Test
    fun malformedAnswersOpenTheBreakerAndAreNeverStored() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] =
            envelope(""""lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"line":[{"value":"no start"}]}]}""")
        val lyrics = env.lyrics(capabilities(setOf(1)))
        repeat(3) {
            val read = lyrics.read(TRACK)
            assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.MalformedEnvelope)), read.freshness)
        }
        assertNull(env.cache.lyrics(TRACK.rawId), "a malformed answer is not \"no lyrics\"")
        assertTrue(lyrics.breakerOpen, "an ok envelope with a malformed body is a failure of the endpoint")
        assertEquals(3, env.transport.log.size)
    }

    @Test
    fun onlyNotFoundIsStoredAsNoLyrics() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val failures: List<LyricsTransport.() -> Unit> = listOf(
            { failWith["getLyricsBySongId"] = DomainError.Transport.Timeout },
            { failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable },
            { codes["getLyricsBySongId"] = 0 },
            { codes["getLyricsBySongId"] = 50 },
            { bodies["getLyricsBySongId"] = "<html><body>502 Bad Gateway</body></html>" },
        )
        failures.forEachIndexed { index, arrange ->
            env.transport.failWith.clear()
            env.transport.codes.clear()
            env.transport.arrange()
            val id = "transient-$index"
            val read = lyrics.read(LyricsTrack(id, "A", "T"))
            assertIs<LibraryUnavailableReason.Failed>(assertIs<LibraryFreshness.Unavailable>(read.freshness).reason)
            assertNull(env.cache.lyrics(id), "failure $index was stored as a document")
            // A new observation window, so the breaker (which counts every class) lets the next one out.
            env.session.setOnline(false)
            env.session.setOnline(true)
        }
        assertEquals(failures.size, env.transport.log.size)
        // The positive control: the same instrument does see a stored "no lyrics".
        env.transport.codes["getLyricsBySongId"] = 70
        assertEquals(LibraryFreshness.Live, lyrics.read(LyricsTrack("gone", "A", "T")).freshness)
        assertEquals(emptyList(), env.cache.lyrics("gone")?.layers)
    }

    @Test
    fun aReconnectClosesTheBreakerAndNothingElseDoes() = lyricsTest { env ->
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        val lyrics = env.lyrics(capabilities(setOf(1)))
        repeat(3) { lyrics.read(TRACK) }
        assertTrue(lyrics.breakerOpen)
        env.session.setOnline(true)
        assertTrue(lyrics.breakerOpen, "already online: that is not a reconnect")
        env.session.setOnline(false)
        assertTrue(lyrics.breakerOpen, "losing the network is not a reconnect")
        env.session.setOnline(true)
        assertFalse(lyrics.breakerOpen)

        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(4, env.transport.log.size)
    }

    @Test
    fun notFoundIsItemScopedAndNeverCounts() = lyricsTest { env ->
        env.transport.codes["getLyricsBySongId"] = 70
        val lyrics = env.lyrics(capabilities(setOf(1)))
        repeat(5) { lyrics.read(LyricsTrack("gone-$it", null, null)) }
        assertFalse(lyrics.breakerOpen)
        assertEquals(5, env.transport.log.size)
    }

    @Test
    fun aNotFoundAnswerBetweenFailuresResetsTheCount() = lyricsTest { env ->
        // A track per read: a remembered timeout would answer a repeated track without a request,
        // and the test would then assert the breaker's state having sent nothing (fifth review).
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(LyricsTrack("t1", "A", "B"))
        lyrics.read(LyricsTrack("t2", "A", "B"))
        env.transport.failWith.clear()
        env.transport.codes["getLyricsBySongId"] = 70
        assertTrue(lyrics.read(LyricsTrack("t3", "A", "B")).notFound, "precondition: code 70 was answered")
        env.transport.codes.clear()
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(LyricsTrack("t4", "A", "B"))
        lyrics.read(LyricsTrack("t5", "A", "B"))
        assertEquals(5, env.transport.log.size, "precondition: every read was sent")
        assertFalse(lyrics.breakerOpen, "code 70 did not reset the count of failures")
        // The control: a third consecutive failure after the reset does open it.
        lyrics.read(LyricsTrack("t6", "A", "B"))
        assertEquals(6, env.transport.log.size)
        assertTrue(lyrics.breakerOpen)
    }

    // ---- First review: each of these failed, or let a surviving mutant live, before its fix ----

    @Test
    fun anAbsurdServerTimeIsNeverTrustedAndCannotCrashTheCursor() = lyricsTest { env ->
        // 5e18 is a valid JSON long; as a Duration it is infinite, and infinite minus infinite throws.
        env.transport.bodies["getLyricsBySongId"] = envelope(
            """"lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"offset":5000000000000000000,"line":[{"start":5000000000000000000,"value":"x"},{"start":1000,"value":"y"}]}]}""",
        )
        val read = env.lyrics(capabilities(setOf(1))).read(TRACK)
        val layer = assertNotNull(read.lyrics?.selected)
        assertFalse(layer.synced, "timing outside ±24 h is not trusted: the text is kept, unsynced")
        assertEquals(listOf("x", "y"), layer.lines.map { it.text })
        assertTrue(layer.lines.all { it.start == null })
        assertEquals(0L, layer.offsetMilliseconds)
        assertEquals(-1, layer.cursorAtMilliseconds(1_000).index)
        assertEquals(layer, env.cache.lyrics(TRACK.rawId)?.layers?.single(), "what was stored is what was shown")
        // One bad offset alone is enough, and so is a start below -24 h.
        for (bad in listOf(""""offset":86400001,"line":[{"start":1,"value":"x"}]""", """"line":[{"start":-86400001,"value":"x"}]""")) {
            env.transport.bodies["getLyricsBySongId"] = envelope(""""lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,$bad}]}""")
            assertEquals(false, env.lyrics(capabilities(setOf(1))).read(TRACK).lyrics?.selected?.synced, bad)
        }
        // The edge itself is trusted.
        env.transport.bodies["getLyricsBySongId"] = envelope(
            """"lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"offset":-86400000,"line":[{"start":86400000,"value":"x"}]}]}""",
        )
        assertEquals(true, env.lyrics(capabilities(setOf(1))).read(TRACK).lyrics?.selected?.synced)
    }

    @Test
    fun aStoredInfiniteTimeCannotCrashTheCursor() = lyricsTest { env ->
        // A value that never came through the parser: an older store, or a direct write.
        val infinite = LyricsLayer("eng", true, Duration.INFINITE, listOf(LyricsLine("x", Duration.INFINITE), LyricsLine("y", 1_000.milliseconds)))
        env.cache.writeLyrics(1, TRACK.rawId, LyricsSource.SongLyricsExtension, listOf(infinite))
        val stored = assertNotNull(env.lyrics(capabilities(setOf(1))).cached(TRACK).lyrics?.selected)
        for (position in listOf(Long.MIN_VALUE, -1L, 0L, 1_000L, Long.MAX_VALUE)) stored.cursorAtMilliseconds(position)
    }

    @Test
    fun aMalformedLegacyBodyIsAFailureAndLeavesTheStoreAlone() = lyricsTest { env ->
        env.transport.bodies["getLyrics"] = envelope(""""lyrics":{"artist":"A","title":"T","value":"one\ntwo"}""")
        val lyrics = env.lyrics(capabilities(null, legacySubsonic = true))
        lyrics.read(TRACK)
        val stored = env.cache.lyrics(TRACK.rawId)
        assertEquals(1, stored?.layers?.size)
        for (body in listOf(envelope(""""lyrics":"not an object""""), envelope(null), envelope(""""lyrics":{"value":7}"""))) {
            env.transport.bodies["getLyrics"] = body
            val read = lyrics.read(TRACK)
            assertEquals(
                LibraryCachedReason.Failed(DomainError.Protocol.MalformedEnvelope),
                assertIs<LibraryFreshness.Cached>(read.freshness).reason,
                body,
            )
            assertEquals(stored, env.cache.lyrics(TRACK.rawId), "a malformed body must not replace stored lyrics")
        }
        assertTrue(lyrics.breakerOpen, "three malformed answers are three failures")
        // An object with no value is the server saying it has none.
        env.session.setOnline(false)
        env.session.setOnline(true)
        env.transport.bodies["getLyrics"] = envelope(""""lyrics":{"artist":"A","title":"T"}""")
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(emptyList(), env.cache.lyrics(TRACK.rawId)?.layers)
    }

    @Test
    fun everyPathBackOnlineResetsTheBreaker() = lyricsTest { env ->
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        val lyrics = env.lyrics(capabilities(setOf(1)))
        repeat(3) { lyrics.read(TRACK) }
        assertTrue(lyrics.breakerOpen)
        env.session.setOnline(false)
        env.session.reader.reconnect()
        assertFalse(lyrics.breakerOpen, "the reconnect path is a way back online too")
        repeat(3) { lyrics.read(TRACK) }
        assertTrue(lyrics.breakerOpen)
        // The reviewer's sequence: offline, reconnect, then the platform reporting online again.
        env.session.setOnline(false)
        env.session.reader.reconnect()
        env.session.setOnline(true)
        assertFalse(lyrics.breakerOpen)
        // The reconnect sequence also runs on a return to the foreground while online (§16.14):
        // that is no transition, and it resets nothing.
        repeat(3) { lyrics.read(TRACK) }
        assertTrue(lyrics.breakerOpen)
        env.session.reader.reconnect()
        assertTrue(lyrics.breakerOpen, "a reconnect while already online is not a way back online")
    }

    @Test
    fun cachedIsPublishedWithItsAgeAndNeverAsLive() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val lyrics = env.lyrics(capabilities(setOf(1)))
        lyrics.read(TRACK)
        env.clock.now += 60_000
        val first = lyrics.cached(TRACK)
        assertEquals(LibraryFreshness.Cached(env.clock.now - 60_000, LibraryCachedReason.Stale), first.freshness)
        assertEquals(1, env.transport.log.size, "cached() sends nothing")
    }

    @Test
    fun showingTheStoredDocumentIsNotAnAccess() = lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 3)) { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        for ((seq, id) in listOf("a", "b", "c").withIndex()) {
            env.cache.writeLyrics(seq + 1L, id, LyricsSource.SongLyricsExtension, listOf(layer(id)))
            env.clock.now += 1_000
        }
        // The first frame of a panel; the read that follows is what counts (and touches).
        assertEquals("a", lyrics.cached(LyricsTrack("a", null, null)).lyrics?.selected?.lines?.single()?.text)
        env.clock.now += 1_000
        env.cache.writeLyrics(10, "d", LyricsSource.SongLyricsExtension, listOf(layer("d")))
        assertNull(env.cache.lyrics("a"), "a is still the least recently accessed")
        assertNull(env.cache.lyrics("b"))
        assertNotNull(env.cache.lyrics("c"))
        assertNotNull(env.cache.lyrics("d"))
    }

    @Test
    fun anExplicitRetryIsAdmittedAtOnceWhileTheBreakerIsOpen() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        repeat(3) { lyrics.read(TRACK) }
        assertTrue(lyrics.read(TRACK).breakerOpen)
        assertEquals(3, env.transport.log.size)
        // The person asks: the trial goes out now, not when the period ends.
        val failedRetry = lyrics.retry(TRACK)
        assertFalse(failedRetry.breakerOpen)
        assertEquals(4, env.transport.log.size)
        // It failed, so a full period starts again at that failure; automatic reads wait it out.
        env.monotonic += BREAKER_OPEN_MILLIS - 1
        assertTrue(lyrics.read(TRACK).breakerOpen)
        assertEquals(4, env.transport.log.size)
        // One trial at a time, even at the person's request.
        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val gate = CompletableDeferred<Unit>()
        env.transport.holdNext = gate
        val first = async { lyrics.retry(TRACK) }
        runCurrent()
        assertEquals(5, env.transport.log.size)
        assertTrue(lyrics.retry(TRACK).breakerOpen, "a second retry while the first is out is refused")
        assertEquals(5, env.transport.log.size)
        gate.complete(Unit)
        assertEquals(LibraryFreshness.Live, first.await().freshness)
        assertFalse(lyrics.breakerOpen, "the retry succeeded: the breaker is closed")
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
    }

    @Test
    fun codeSeventyIsMarkedSoAReaderCanProveItSawIt() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.codes["getLyricsBySongId"] = 70
        val unknown = lyrics.read(TRACK)
        assertTrue(unknown.notFound)
        assertEquals(LibraryFreshness.Live, unknown.freshness)
        assertEquals(emptyList(), assertNotNull(unknown.lyrics).layers)
        // Live with no layers reached any other way is not that marker.
        env.transport.codes.clear()
        env.transport.bodies["getLyricsBySongId"] = envelope(""""lyricsList":{}""")
        val none = lyrics.read(TRACK)
        assertEquals(LibraryFreshness.Live, none.freshness)
        assertEquals(emptyList(), assertNotNull(none.lyrics).layers)
        assertFalse(none.notFound)
        assertFalse(lyrics.cached(TRACK).notFound, "the stored answer served again is not a code-70 answer")
        // A failure publication is not one either.
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        assertFalse(lyrics.read(TRACK).notFound)
    }

    @Test
    fun aLegacyCodeSeventyIsMarkedAndLeavesTheStoreAlone() = lyricsTest { env ->
        // §18.4: only getLyricsBySongId's code 70 is stored as "no lyrics"; getLyrics' is not stored,
        // and THIS read was still answered with code 70, so its publication says so (spec: notFound
        // describes the answer, not where the document came from).
        val lyrics = env.lyrics(capabilities(null, legacySubsonic = true))
        env.transport.bodies["getLyrics"] = envelope(""""lyrics":{"artist":"A","title":"T","value":"one\ntwo"}""")
        lyrics.read(TRACK)
        val stored = assertNotNull(env.cache.lyrics(TRACK.rawId))
        env.transport.codes["getLyrics"] = 70
        val unknown = lyrics.read(TRACK)
        assertTrue(unknown.notFound)
        val freshness = assertIs<LibraryFreshness.Cached>(unknown.freshness)
        assertTrue(assertIs<LibraryCachedReason.Failed>(freshness.reason).error.isNotFound())
        assertEquals(stored.layers, unknown.lyrics?.layers)
        assertEquals(stored, env.cache.lyrics(TRACK.rawId), "a legacy code 70 is not stored")
        assertFalse(lyrics.cached(TRACK).notFound)
        val never = lyrics.read(LyricsTrack("never-read", "A", "B"))
        assertTrue(never.notFound)
        assertIs<LibraryFreshness.Unavailable>(never.freshness)
        assertFalse(lyrics.breakerOpen)
        env.transport.codes.clear()
        assertFalse(lyrics.read(TRACK).notFound)
    }

    @Test
    fun aStragglersFailureWhileTheTrialIsOutAdmitsNoSecondTrial() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        // A read admitted while the breaker was closed, still waiting for its answer (of another
        // track: a read of the same track would join it rather than send, §18.4)...
        val straggling = CompletableDeferred<Unit>()
        env.transport.holdNext = straggling
        val straggler = launch { lyrics.read(STRAGGLER) }
        runCurrent()
        // ...while three others fail and open it, and the period passes and the trial goes out.
        repeat(3) { lyrics.read(TRACK) }
        env.monotonic += BREAKER_OPEN_MILLIS
        env.transport.holdNext = CompletableDeferred()
        val trial = launch { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(5, env.transport.log.size)
        // The straggler's failure lands while the trial is out.
        straggling.complete(Unit)
        straggler.join()
        assertTrue(lyrics.breakerOpen)
        assertTrue(lyrics.retry(TRACK).breakerOpen, "the person's Retry is the trial too, and one is out")
        assertTrue(lyrics.read(OTHER_TRACK).breakerOpen)
        assertEquals(5, env.transport.log.size, "a second concurrent trial must not be sent")
        trial.cancelAndJoin()
        // The positive control: with the trial's slot given back, Retry is admitted at once.
        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
        assertEquals(6, env.transport.log.size)
    }

    @Test
    fun everyEntryPointIsConfinedToTheReadersThread() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val calls: List<Pair<String, suspend () -> Unit>> = listOf(
            "breakerOpen" to { lyrics.breakerOpen },
            "cached" to { lyrics.cached(TRACK) },
            "read" to { lyrics.read(TRACK) },
            "retry" to { lyrics.retry(TRACK) },
        )
        val refused = mutableListOf<String>()
        for ((name, call) in calls) {
            val thrown = withContext(Dispatchers.Default) { runCatching { call() }.exceptionOrNull() }
            if (thrown is IllegalStateException) refused += name
        }
        assertEquals(calls.map { it.first }, refused)
        assertEquals(0, env.transport.log.size, "a refused read sent nothing")
        // The positive control: on the reader's own thread every one is accepted.
        for ((_, call) in calls) call()
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun aStragglersCancellationDoesNotFreeTheTrialSlot() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        // A read admitted while the breaker was closed, still waiting for its answer (of another
        // track: a read of the same track would join it rather than send, §18.4)...
        env.transport.holdNext = CompletableDeferred()
        val straggler = launch { lyrics.read(STRAGGLER) }
        runCurrent()
        // ...while three others fail and open it, and the period passes and the trial goes out.
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        repeat(3) { lyrics.read(TRACK) }
        env.monotonic += BREAKER_OPEN_MILLIS
        env.transport.holdNext = CompletableDeferred()
        val trial = launch { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(5, env.transport.log.size)
        straggler.cancelAndJoin()
        assertTrue(lyrics.breakerOpen, "the straggler was never the trial; its cancellation frees nothing")
        assertTrue(lyrics.read(OTHER_TRACK).breakerOpen)
        assertEquals(5, env.transport.log.size, "a second concurrent trial must not be sent")
        trial.cancelAndJoin()
    }

    @Test
    fun aReadServedFromTheStoreCountsAsAnAccess() = lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 3)) { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        for ((seq, id) in listOf("a", "b", "c").withIndex()) {
            env.cache.writeLyrics(seq + 1L, id, LyricsSource.SongLyricsExtension, listOf(layer(id)))
            env.clock.now += 1_000
        }
        // a is the oldest write; serving it from the store makes it the most recently accessed.
        env.session.setOnline(false)
        assertEquals("a", lyrics.read(LyricsTrack("a", null, null)).lyrics?.selected?.lines?.single()?.text)
        env.clock.now += 1_000
        env.cache.writeLyrics(10, "d", LyricsSource.SongLyricsExtension, listOf(layer("d")))
        // Four over a ceiling of three evicts down to two: the two least recently accessed.
        assertNotNull(env.cache.lyrics("a"), "a read served from the store is an access")
        assertNull(env.cache.lyrics("b"))
        assertNull(env.cache.lyrics("c"))
        assertNotNull(env.cache.lyrics("d"))
    }

    @Test
    fun aLegacyTrackWithoutATitleSendsNothing() = lyricsTest { env ->
        env.transport.bodies["getLyrics"] = envelope(""""lyrics":{"artist":"A","title":"T","value":"one"}""")
        val lyrics = env.lyrics(capabilities(null, legacySubsonic = true))
        for (title in listOf(null, "", "   ")) {
            val read = lyrics.read(LyricsTrack("t", "A", title))
            assertNull(read.lyrics?.selected, "no title, nothing to ask for")
        }
        assertEquals(0, env.transport.log.size)
    }

    @Test
    fun anUnsyncedLayerKeepsNoServerStart() {
        val (_, layers) = parseLyricsResponse(
            LyricsEndpoint.SongLyrics,
            envelope(""""lyricsList":{"structuredLyrics":[{"lang":"eng","synced":false,"line":[{"start":1000,"value":"a"},{"value":"b"}]}]}"""),
        )
        assertEquals(listOf(null, null), layers.single().lines.map { it.start })
    }

    @Test
    fun timesAreWholeMillisecondsFromANumberOrANumericString() {
        fun parse(value: String) = parseLyricsResponse(
            LyricsEndpoint.SongLyrics,
            envelope(""""lyricsList":{"structuredLyrics":[{"synced":true,"line":[{"start":$value,"value":"a"}]}]}"""),
        ).second.single()
        fun startOf(value: String): Long? = parse(value).lines.single().start?.inWholeMilliseconds
        assertEquals(1_500L, startOf("1500"))
        assertEquals(1_500L, startOf("\"1500\""))
        assertEquals(1_500L, startOf("1500.9"), "a fraction is truncated to whole milliseconds")
        assertEquals(-1_500L, startOf("-1500.9"), "toward zero")
        assertEquals(1_500L, startOf("\"1.5e3\""))
        assertEquals(1_500L, startOf("1.5E3"))
        // A number beyond ±24 h is a time, just not a trusted one: the layer is kept, unsynced.
        assertEquals(false, parse("1e12").synced)
        for (notATime in listOf("\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "1e400", "\"soon\"", "true", "\"\"", "null", "\" 1500\"")) {
            val failure = runCatching { parse(notATime) }.exceptionOrNull()
            assertEquals(
                DomainError.Protocol.MalformedEnvelope,
                assertIs<LibraryRequestFailure>(failure, "$notATime is not a time; a synced line without one is malformed").error,
            )
        }
    }

    @Test
    fun failuresOfAnyClassAddUpToOpenTheBreaker() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // A proxy alternating a 502 page with a refused connection is one unhealthy endpoint. (Not a
        // timeout on one track: a track whose request timed out is not asked again within the
        // period, §18.4. Timeouts are charged too: [timeoutsOnThreeTracksOpenTheBreaker].)
        env.transport.bodies["getLyricsBySongId"] = "<html><body>502 Bad Gateway</body></html>"
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        lyrics.read(TRACK)
        env.transport.failWith.clear()
        lyrics.read(TRACK)
        assertFalse(lyrics.breakerOpen)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        val third = lyrics.read(TRACK)
        assertEquals(3, env.transport.log.size, "precondition: every failure was sent")
        assertTrue(lyrics.breakerOpen)
        assertEquals(LibraryUnavailableReason.Failed(DomainError.Transport.Unreachable), assertIs<LibraryFreshness.Unavailable>(third.freshness).reason)
        val refused = lyrics.read(TRACK)
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Transport.Unreachable)), refused.freshness, "the diagnostic names the latest failure")
    }

    @Test
    fun aLocalStorageFailureIsNeverCountedAgainstTheEndpoint() = lyricsTest { env ->
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.driver.execute(null, "DROP TABLE cache_lyrics_line", 0)
        repeat(4) {
            val read = lyrics.read(TRACK)
            assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure), read.freshness)
        }
        assertEquals(4, env.transport.log.size, "the server answered every time; the device could not store it")
        assertFalse(lyrics.breakerOpen)
    }

    // ---- Answers beyond the caps (§18.4): trimmed, and refused only for their main layer ----------

    @Test
    fun anAnswerBeyondTheCapsKeepsItsMainLayerAndTheExtrasThatFit() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1, 2)))
        // The reviewer's probe: a short main layer and sixteen translations, seventeen layers.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(mainLayer("tiny main")) + (0 until 16).map { translation("l$it", 1, "t$it") },
        )
        val read = lyrics.read(TRACK)
        assertEquals(LibraryFreshness.Live, read.freshness)
        val kept = assertNotNull(read.lyrics).layers
        assertEquals(MAX_LYRICS_LAYERS, kept.size)
        // Extras rank by language like the selection (none is a language the person reads, so by
        // code: l0, l1, l10 … l15, l2 … l9); the lowest-ranked is dropped, and the kept layers
        // stay in the server's order.
        assertEquals(listOf(null) + (0 until 16).filter { it != 9 }.map { "l$it" }, kept.map { it.language })
        assertEquals("tiny main", read.lyrics?.selected?.lines?.single()?.text)
        assertEquals(1, read.lyrics?.droppedLayers, "a trimmed document says so")
        assertEquals(kept, env.cache.lyrics(TRACK.rawId)?.layers)
        // ~150 lines in each of fourteen layers: 2,100 lines, so the thirteenth extra does not fit.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(mainLayer("m", lines = 150)) + (0 until 14).map { translation("l$it", 150, "t") },
        )
        val linesRead = assertNotNull(lyrics.read(LyricsTrack("lines", null, null)).lyrics)
        val lines = linesRead.layers
        assertEquals(13, lines.size)
        assertEquals(1_950, lines.sumOf { it.lines.size })
        assertEquals(listOf(null) + listOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13).map { "l$it" }, lines.map { it.language })
        assertEquals(2, linesRead.droppedLayers)
        // Main layers are considered first, wherever the server lists them: an extra listed before
        // the main layer never crowds it out.
        // Fits alone (65,530 bytes with its language and kind), not beside the 8-byte main layer.
        val big = "x".repeat(MAX_LYRICS_TEXT_BYTES - 20)
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(translation("jpn", 1, big), mainLayer("the song")))
        val first = assertNotNull(lyrics.read(LyricsTrack("extra-first", null, null)).lyrics)
        assertEquals(listOf("the song"), first.layers.map { it.lines.single().text })
        // A large extra does not take the small ones after it down with it.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(mainLayer("the song"), translation("jpn", 1, big), translation("kor", 1, "small")),
        )
        val skipped = assertNotNull(lyrics.read(LyricsTrack("skip", null, null)).lyrics)
        assertEquals(listOf(null, "kor"), skipped.layers.map { it.language })
        assertFalse(lyrics.breakerOpen)
        assertEquals(4, env.transport.log.size)
    }

    @Test
    fun onlyAMainLayerBeyondTheCapsOnItsOwnIsRefusedAsTooLarge() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        lyrics.read(TRACK)
        val stored = assertNotNull(env.cache.lyrics(TRACK.rawId))
        // U+1F3B5 is one character, two UTF-16 units and FOUR UTF-8 bytes (the reviewer's N10).
        val note = "\uD83C\uDFB5"
        val oversized = listOf(
            "too many lines" to structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))),
            "too much text" to structured(listOf(mainLayer("\u00e9".repeat(MAX_LYRICS_TEXT_BYTES / 4 + 1), lines = 2))),
            "too many four-byte characters" to structured(listOf(mainLayer(note.repeat(MAX_LYRICS_TEXT_BYTES / 8 + 1), lines = 2))),
            "every main layer too large" to structured(
                listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1), mainLayer("y", lines = MAX_LYRICS_LINES + 1), translation("eng", 1, "fits")),
            ),
        )
        for ((label, body) in oversized) {
            env.transport.bodies["getLyricsBySongId"] = body
            val read = lyrics.retry(TRACK)
            assertEquals(
                LibraryCachedReason.Failed(DomainError.Protocol.TooLarge),
                assertIs<LibraryFreshness.Cached>(read.freshness).reason,
                label,
            )
            assertEquals(stored, env.cache.lyrics(TRACK.rawId), "$label must not replace the stored document")
            val fresh = lyrics.retry(LyricsTrack("never-stored", null, null))
            assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge)), fresh.freshness, label)
            assertNull(env.cache.lyrics("never-stored"), label)
        }
        // Eight refusals in a row: each one item's property, not the endpoint's health.
        assertFalse(lyrics.breakerOpen)
        assertEquals(9, env.transport.log.size)
        // One main layer too large beside one that fits: the one that fits is kept.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1), mainLayer("fits")),
        )
        assertEquals(listOf("fits"), lyrics.read(LyricsTrack("one-fits", null, null)).lyrics?.layers?.map { it.lines.single().text })
        // At every limit exactly, the main layer is kept: lines, and UTF-8 bytes of both widths.
        for ((label, body) in listOf(
            "lines" to structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES))),
            "two-byte text" to structured(listOf(mainLayer("\u00e9".repeat(MAX_LYRICS_TEXT_BYTES / 4), lines = 2))),
            "four-byte text" to structured(listOf(mainLayer(note.repeat(MAX_LYRICS_TEXT_BYTES / 8), lines = 2))),
        )) {
            env.transport.bodies["getLyricsBySongId"] = body
            assertEquals(LibraryFreshness.Live, lyrics.read(LyricsTrack("at-limit-$label", null, null)).freshness, label)
        }
    }

    @Test
    fun aTooLargeVerdictIsRememberedForTheSession() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        val tooLarge = LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge))
        assertEquals(tooLarge, lyrics.read(TRACK).freshness)
        assertEquals(1, env.transport.log.size)
        // The same track again sends nothing: not from this object, not from a new one, and not
        // after a reconnect, since the size is the file's and not the network's.
        assertEquals(tooLarge, lyrics.read(TRACK).freshness)
        assertEquals(tooLarge, env.lyrics(capabilities(setOf(1))).read(TRACK).freshness)
        env.session.setOnline(false)
        env.session.setOnline(true)
        assertEquals(tooLarge, lyrics.read(TRACK).freshness)
        assertEquals(1, env.transport.log.size, "an oversized answer is downloaded once per session")
        // The verdict is the track's: another track is still asked.
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.read(LyricsTrack("other", null, null)).freshness)
        assertEquals(2, env.transport.log.size)
        // The person's Retry asks again; an answer that now fits replaces the verdict...
        assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
        assertEquals(3, env.transport.log.size)
        lyrics.read(TRACK)
        assertEquals(4, env.transport.log.size, "a fitting answer forgot the verdict")
        // ...and a failure neither sets nor clears it.
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        lyrics.read(TRACK)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.retry(TRACK)
        assertEquals(6, env.transport.log.size)
        env.transport.failWith.clear()
        assertEquals(LibraryCachedReason.Failed(DomainError.Protocol.TooLarge), assertIs<LibraryFreshness.Cached>(lyrics.read(TRACK).freshness).reason)
        assertEquals(6, env.transport.log.size, "the timeout did not clear the verdict")
    }

    @Test
    fun aLyricsBodyBeyondTheResponseLimitIsRefusedAsTooLarge() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        lyrics.read(TRACK)
        // Every lyrics request goes out with the limit, through the transport that can enforce it.
        assertEquals(listOf("getLyricsBySongId" to MAX_LYRICS_RESPONSE_BYTES), env.transport.limited)
        val stored = env.cache.lyrics(TRACK.rawId)
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x".repeat(MAX_LYRICS_RESPONSE_BYTES))))
        val read = lyrics.read(TRACK)
        assertEquals(LibraryCachedReason.Failed(DomainError.Protocol.TooLarge), assertIs<LibraryFreshness.Cached>(read.freshness).reason)
        assertEquals(stored, env.cache.lyrics(TRACK.rawId))
        assertFalse(lyrics.breakerOpen)
        lyrics.read(TRACK)
        assertEquals(2, env.transport.log.size, "remembered like any other too-large answer")
        // The same bytes one short of the limit are read, and trimmed like any answer.
        val body = structured(listOf(mainLayer("m"), translation("eng", 1, "y".repeat(MAX_LYRICS_RESPONSE_BYTES / 2))))
        assertTrue(body.encodeToByteArray().size < MAX_LYRICS_RESPONSE_BYTES)
        env.transport.bodies["getLyricsBySongId"] = body
        assertEquals(listOf("m"), lyrics.read(LyricsTrack("under", null, null)).lyrics?.layers?.map { it.lines.single().text })
    }

    @Test
    fun aBodyBeyondALimitIsTooLargeOnlyWhenTheStatusIsASuccess() = runTest {
        val body = "z".repeat(11)
        for ((status, expected) in listOf(200 to DomainError.Protocol.TooLarge, 204 to DomainError.Protocol.TooLarge, 502 to DomainError.Protocol.MalformedEnvelope)) {
            val transport = LibraryEndpointTransport { _, _ -> LibraryEndpointResponse(status, body, "http://fixture.invalid/rest") }
            val refused = assertIs<LibraryRequestFailure>(runCatching { transport.request("e", emptyMap(), 10) }.exceptionOrNull())
            assertEquals(expected, refused.error, "status $status")
            assertEquals(body, transport.request("e", emptyMap(), 11).body, "at the limit, status $status")
        }
        // Bytes, not characters: eleven characters of two UTF-8 bytes each are 22 bytes.
        val wide = LibraryEndpointTransport { _, _ -> LibraryEndpointResponse(200, "\u00e9".repeat(11), "http://fixture.invalid/rest") }
        assertIs<LibraryRequestFailure>(runCatching { wide.request("e", emptyMap(), 21) }.exceptionOrNull())
        assertEquals(11, wide.request("e", emptyMap(), 22).body.length)
    }

    // ---- Third review: trimming in the selection's order, verdicts in issue order, one flight ------

    @Test
    fun trimmingKeepsTheLayerTheSelectionWouldShow() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)), listOf("en"))
        // The reviewer's first probe: an unsynced main listed before a synced one, too large together.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(unsyncedMainLayer("eng", "u".repeat(40_000)), syncedMainLayer("eng", "s".repeat(30_000))),
        )
        val syncedKept = assertNotNull(lyrics.read(LyricsTrack("synced-second", null, null)).lyrics)
        assertEquals(listOf(true), syncedKept.layers.map { it.synced }, "rule 2: synced beats unsynced")
        assertEquals(true, syncedKept.selected?.synced)
        assertEquals(1, syncedKept.droppedLayers)
        assertTrue(syncedKept.isTrimmed)
        // The second: German listed before English, for a person who reads English.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(syncedMainLayer("deu", "d".repeat(40_000)), syncedMainLayer("eng", "e".repeat(30_000))),
        )
        val english = assertNotNull(lyrics.read(LyricsTrack("german-first", null, null)).lyrics)
        assertEquals(listOf("eng"), english.layers.map { it.language })
        assertEquals("eng", english.selected?.language)
        assertEquals(1, english.droppedLayers)
        // The best-ranked main layer does not fit even alone, and another with text does: that one
        // is shown, and the answer is not refused.
        env.transport.bodies["getLyricsBySongId"] = structured(
            listOf(syncedMainLayer("eng", "x", lines = MAX_LYRICS_LINES + 1), unsyncedMainLayer("eng", "fits")),
        )
        val fits = lyrics.read(LyricsTrack("best-too-large", null, null))
        assertEquals(LibraryFreshness.Live, fits.freshness)
        assertEquals("fits", fits.lyrics?.selected?.lines?.single()?.text)
        assertEquals(1, fits.lyrics?.droppedLayers)
        // The marker is stored: it survives the store, offline, and the panel's first frame.
        env.session.setOnline(false)
        assertEquals(1, lyrics.read(LyricsTrack("german-first", null, null)).lyrics?.droppedLayers)
        assertEquals(1, lyrics.cached(LyricsTrack("synced-second", null, null)).lyrics?.droppedLayers)
        assertEquals(1, env.cache.lyrics("german-first")?.droppedLayers)
        // The negative control: a document within the caps is complete.
        env.session.setOnline(true)
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val whole = assertNotNull(lyrics.read(TRACK).lyrics)
        assertEquals(0, whole.droppedLayers)
        assertFalse(whole.isTrimmed)
    }

    @Test
    fun aBlankMainLayerNeverHidesOneThatIsTooLarge() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val tooLarge = LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge))
        // The reviewer's probe, in both orders: a blank main layer beside one of 2,001 lines.
        for ((label, layers) in listOf(
            "blank first" to listOf(mainLayer(""), mainLayer("x", lines = MAX_LYRICS_LINES + 1)),
            "blank last" to listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1), mainLayer("   ")),
        )) {
            env.transport.bodies["getLyricsBySongId"] = structured(layers)
            val read = lyrics.retry(LyricsTrack(label, null, null))
            assertEquals(tooLarge, read.freshness, "$label: \"cannot fit\" is not \"no lyrics\"")
            assertNull(env.cache.lyrics(label), label)
        }
        // The positive control: beside a main layer that fits, the blank one changes nothing.
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer(""), mainLayer("fits")))
        val read = lyrics.read(LyricsTrack("blank-and-fits", null, null))
        assertEquals(LibraryFreshness.Live, read.freshness)
        assertEquals("fits", read.lyrics?.selected?.lines?.single()?.text)
        // And an answer whose only main layer is blank is a document with nothing to show, not a refusal.
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("")))
        val empty = lyrics.read(LyricsTrack("only-blank", null, null))
        assertEquals(LibraryFreshness.Live, empty.freshness)
        assertNull(empty.lyrics?.selected)
    }

    @Test
    fun anOlderReadsRefusalNeverOverridesANewerRetrysDocument() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // An automatic read goes out and waits for its answer...
        val older = CompletableDeferred<String>()
        env.transport.answers += older
        val automatic = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(1, env.transport.log.size)
        // ...the person's Retry sends a newer request, and its answer fits...
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val newer = lyrics.retry(TRACK)
        assertEquals(LibraryFreshness.Live, newer.freshness)
        assertEquals(2, env.transport.log.size, "a Retry sends its own request")
        // ...and then the older request's answer lands, too large.
        older.complete(structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))))
        val late = automatic.await()
        assertEquals(LibraryFreshness.Live, late.freshness, "what the newer answer stored is what is shown")
        assertEquals(newer.lyrics, late.lyrics)
        // No verdict: the next automatic read asks.
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(3, env.transport.log.size, "an older refusal set the verdict over a newer document")
    }

    @Test
    fun anOlderDocumentNeverUndoesANewerRefusal() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // A Retry goes out and waits for its answer; a second Retry is answered first, and the
        // transport refuses it for its size — an answer with no response, ordered by its issue.
        val older = CompletableDeferred<String>()
        env.transport.answers += older
        val first = async { lyrics.retry(TRACK) }
        runCurrent()
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x".repeat(MAX_LYRICS_RESPONSE_BYTES))))
        val refused = lyrics.retry(TRACK)
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge)), refused.freshness)
        assertEquals(2, env.transport.log.size)
        // The older request's answer lands afterwards, and fits: it is stored, but the verdict
        // belongs to the newer answer.
        older.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, first.await().freshness)
        assertEquals(
            LibraryCachedReason.Failed(DomainError.Protocol.TooLarge),
            assertIs<LibraryFreshness.Cached>(lyrics.read(TRACK).freshness).reason,
        )
        assertEquals(2, env.transport.log.size, "an older document undid a newer refusal")
    }

    @Test
    fun concurrentAutomaticReadsOfOneTrackSendOneRequest() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)), listOf("en"))
        val answer = CompletableDeferred<String>()
        env.transport.answers += answer
        val first = async { lyrics.read(TRACK) }
        // Another lyrics object of the same session, reading in another language.
        val second = async { env.lyrics(capabilities(setOf(1)), listOf("de")).read(TRACK) }
        runCurrent()
        assertEquals(1, env.transport.log.size, "two concurrent reads of one track sent two requests")
        answer.complete(
            envelope(
                """"lyricsList":{"structuredLyrics":[
                  {"lang":"deu","synced":true,"line":[{"start":1000,"value":"deutsch"}]},
                  {"lang":"eng","synced":true,"line":[{"start":1000,"value":"english"}]}
                ]}""",
            ),
        )
        assertEquals("english", first.await().lyrics?.selected?.lines?.single()?.text)
        assertEquals("deutsch", second.await().lyrics?.selected?.lines?.single()?.text, "each reader's own selection")
        assertEquals(LibraryFreshness.Live, second.await().freshness)
        assertEquals(1, env.transport.log.size)
        // The person's Retry is never joined: it asks for itself.
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val held = CompletableDeferred<String>()
        env.transport.answers += held
        val automatic = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
        assertEquals(3, env.transport.log.size)
        held.complete(OBSERVED_SIDECAR_LRC)
        automatic.await()
        // Nor is another track.
        val otherHeld = CompletableDeferred<String>()
        env.transport.answers += otherHeld
        val one = async { lyrics.read(TRACK) }
        runCurrent()
        val other = async { lyrics.read(OTHER_TRACK) }
        runCurrent()
        assertEquals(5, env.transport.log.size)
        otherHeld.complete(OBSERVED_SIDECAR_LRC)
        one.await()
        other.await()
    }

    @Test
    fun aReadThatJoinedATrimmedAnswerSaysItWasTrimmed() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)), listOf("en"))
        val answer = CompletableDeferred<String>()
        env.transport.answers += answer
        val owner = async { lyrics.read(TRACK) }
        runCurrent()
        val joined = async { env.lyrics(capabilities(setOf(1)), listOf("de")).read(TRACK) }
        runCurrent()
        assertEquals(1, env.transport.log.size, "the second read did not join the first")
        // Too large together: the owner's order keeps English and drops German.
        answer.complete(structured(listOf(syncedMainLayer("deu", "d".repeat(30_000)), syncedMainLayer("eng", "e".repeat(40_000)))))
        val kept = assertNotNull(owner.await().lyrics)
        assertEquals(listOf("eng"), kept.layers.map { it.language })
        assertEquals(1, kept.droppedLayers)
        // The read that joined selects from the same kept layers, and knows they are not all there is.
        val shared = assertNotNull(joined.await().lyrics)
        assertEquals(listOf("eng"), shared.layers.map { it.language })
        assertEquals(1, shared.droppedLayers)
        assertTrue(shared.isTrimmed)
        assertEquals(1, env.transport.log.size)
    }

    @Test
    fun aReadThatJoinedACancelledReadAsksAgain() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.answers += CompletableDeferred()
        val owner = launch { lyrics.read(TRACK) }
        runCurrent()
        val joined = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(1, env.transport.log.size)
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        owner.cancelAndJoin()
        assertEquals(LibraryFreshness.Live, joined.await().freshness, "a read that joined is not cancelled with it")
        assertEquals(2, env.transport.log.size, "it asked again for itself")
        assertFalse(lyrics.breakerOpen, "and the cancellation counted for nothing")
    }

    // ---- Sixth review: a remembered answer comes before joining a request in flight ------------

    @Test
    fun aRememberedTimeoutAnswersBeforeJoiningARetryInFlight() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(TRACK)
        env.transport.failWith.clear()
        val answer = CompletableDeferred<String>()
        env.transport.answers += answer
        val retry = async { lyrics.retry(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "precondition: the Retry is in flight")
        val automatic = async { lyrics.read(TRACK) }
        runCurrent()
        // Joining would make it wait for the Retry, which may be another full timeout.
        assertTrue(automatic.isCompleted, "the automatic read waited for the Retry instead of returning the remembered timeout")
        assertEquals(
            LibraryUnavailableReason.Failed(DomainError.Transport.Timeout),
            assertIs<LibraryFreshness.Unavailable>(automatic.await().freshness).reason,
        )
        assertEquals(2, env.transport.log.size, "the automatic read sent nothing")
        answer.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, retry.await().freshness)
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun aTooLargeVerdictAnswersBeforeJoiningARetryInFlight() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        assertEquals(
            LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge),
            assertIs<LibraryFreshness.Unavailable>(lyrics.read(TRACK).freshness).reason,
            "precondition: the first answer was refused as too large",
        )
        val answer = CompletableDeferred<String>()
        env.transport.answers += answer
        val retry = async { lyrics.retry(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "precondition: the Retry is in flight")
        val automatic = async { lyrics.read(TRACK) }
        runCurrent()
        assertTrue(automatic.isCompleted, "the automatic read waited for the Retry instead of returning the verdict")
        assertEquals(
            LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge),
            assertIs<LibraryFreshness.Unavailable>(automatic.await().freshness).reason,
        )
        assertEquals(2, env.transport.log.size, "the automatic read sent nothing")
        answer.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, retry.await().freshness)
        assertEquals(2, env.transport.log.size)
    }

    // ---- Fifth review: timeouts charged, flights per observation window, timeout-memory order ----

    @Test
    fun timeoutsOnThreeTracksOpenTheBreaker() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(LyricsTrack("t1", "A", "B"))
        lyrics.read(LyricsTrack("t2", "A", "B"))
        assertFalse(lyrics.breakerOpen, "precondition: two failures do not open it")
        lyrics.read(LyricsTrack("t3", "A", "B"))
        assertEquals(3, env.transport.log.size, "precondition: every read was sent")
        assertTrue(lyrics.breakerOpen, "three timeouts on three tracks did not open the breaker")
    }

    @Test
    fun aReadAfterAReconnectDoesNotJoinARequestFromBeforeIt() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val gate = CompletableDeferred<Unit>()
        env.transport.holdNext = gate
        val before = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(1, env.transport.log.size, "precondition: the request before the reconnect is out")
        env.session.setOnline(false)
        env.session.setOnline(true)
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val after = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "a read after the reconnect joined a request from before it")
        // The old request fails on the network that went away: its own caller sees that failure,
        // over the document the newer read stored, and no read after the reconnect does.
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Unreachable
        gate.complete(Unit)
        assertEquals(
            LibraryCachedReason.Failed(DomainError.Transport.Unreachable),
            assertIs<LibraryFreshness.Cached>(before.await().freshness).reason,
        )
        env.transport.failWith.clear()
        val published = after.await()
        assertEquals(LibraryFreshness.Live, published.freshness)
        assertEquals("first line", published.lyrics?.selected?.lines?.first()?.text)
        assertFalse(lyrics.breakerOpen)
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun aReadWithinOneWindowStillJoinsARequestInFlight() = lyricsTest { env ->
        // The positive side of the rule above: within one window the second read joins — in a
        // window after a reconnect too, so the stamp is the window the request was sent in.
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.session.setOnline(false)
        env.session.setOnline(true)
        val answer = CompletableDeferred<String>()
        env.transport.answers += answer
        val first = async { lyrics.read(TRACK) }
        runCurrent()
        val second = async { lyrics.read(TRACK) }
        runCurrent()
        answer.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, first.await().freshness)
        assertEquals(LibraryFreshness.Live, second.await().freshness)
        assertEquals(1, env.transport.log.size)
    }

    @Test
    fun aTimeoutLandingAfterAReconnectIsNotRemembered() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val gate = CompletableDeferred<Unit>()
        env.transport.holdNext = gate
        val before = async { lyrics.read(TRACK) }
        runCurrent()
        env.session.setOnline(false)
        env.session.setOnline(true)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        gate.complete(Unit)
        assertEquals(
            LibraryUnavailableReason.Failed(DomainError.Transport.Timeout),
            assertIs<LibraryFreshness.Unavailable>(before.await().freshness).reason,
            "precondition: the request from before the reconnect timed out after it",
        )
        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness, "a timeout from before the reconnect silenced a read after it")
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun anOlderTimeoutAfterANewerDocumentIsNotRemembered() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val gate = CompletableDeferred<Unit>()
        env.transport.holdNext = gate
        val older = async { lyrics.read(TRACK) }
        runCurrent()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        gate.complete(Unit)
        assertEquals(
            LibraryCachedReason.Failed(DomainError.Transport.Timeout),
            assertIs<LibraryFreshness.Cached>(older.await().freshness).reason,
            "precondition: the older request timed out after the newer document",
        )
        env.transport.failWith.clear()
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness, "an older timeout silenced a track whose newer answer fit")
        assertEquals(3, env.transport.log.size)
    }

    @Test
    fun anOlderDocumentDoesNotEndANewerTimeout() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val older = CompletableDeferred<String>()
        env.transport.answers += older
        val automatic = async { lyrics.read(TRACK) }
        runCurrent()
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        assertEquals(
            LibraryUnavailableReason.Failed(DomainError.Transport.Timeout),
            assertIs<LibraryFreshness.Unavailable>(lyrics.retry(TRACK).freshness).reason,
            "precondition: the newer request timed out",
        )
        env.transport.failWith.clear()
        older.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, automatic.await().freshness)
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val published = lyrics.read(TRACK)
        assertEquals(2, env.transport.log.size, "an older document ended a newer timeout")
        assertEquals(LibraryCachedReason.Failed(DomainError.Transport.Timeout), assertIs<LibraryFreshness.Cached>(published.freshness).reason)
    }

    @Test
    fun aJoinerWhoseFlightEndsInAMissAsksAgain() =
        lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 1)) { env ->
            val lyrics = env.lyrics(capabilities(setOf(1)))
            val older = CompletableDeferred<String>()
            env.transport.answers += older
            val automatic = async { lyrics.read(TRACK) }
            runCurrent()
            val joiner = async { lyrics.read(TRACK) }
            runCurrent()
            assertEquals(1, env.transport.log.size, "precondition: the second read joined")
            // A newer Retry fits and is stored; another track's document evicts it (ceiling 1).
            env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
            assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
            env.clock.now += 1_000
            lyrics.read(OTHER_TRACK)
            assertNull(env.cache.lyrics(TRACK.rawId), "precondition: the newer document is evicted")
            // The joined request's refusal lands: a miss for the owner AND the joiner. The owner's
            // re-ask is held in flight, so the joiner joins it rather than sending a fifth.
            val reask = CompletableDeferred<String>()
            env.transport.answers += reask
            older.complete(structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))))
            runCurrent()
            assertEquals(4, env.transport.log.size, "precondition: the owner asked again")
            reask.complete(OBSERVED_SIDECAR_LRC)
            assertEquals(LibraryFreshness.Live, automatic.await().freshness)
            val joined = joiner.await()
            assertEquals(LibraryFreshness.Live, joined.freshness, "the joiner of a miss did not ask again")
            assertEquals("first line", joined.lyrics?.selected?.lines?.first()?.text)
            assertEquals(4, env.transport.log.size, "the owner's re-ask is joined, not repeated")
        }

    @Test
    fun aRememberedAnswerWhileTheBreakerIsOpenSaysSo() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // A too-large verdict for one track, and a timeout for another...
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        lyrics.read(LyricsTrack("big", "A", "B"))
        env.transport.bodies.clear()
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(LyricsTrack("slow", "A", "B"))
        // ...then two more timeouts open the breaker.
        lyrics.read(LyricsTrack("t2", "A", "B"))
        lyrics.read(LyricsTrack("t3", "A", "B"))
        assertTrue(lyrics.breakerOpen, "precondition: the breaker is open")
        assertEquals(4, env.transport.log.size)
        val timedOut = lyrics.read(LyricsTrack("slow", "A", "B"))
        assertEquals(LibraryUnavailableReason.Failed(DomainError.Transport.Timeout), assertIs<LibraryFreshness.Unavailable>(timedOut.freshness).reason)
        assertTrue(timedOut.breakerOpen, "a remembered timeout while the breaker is open did not say so")
        val tooLarge = lyrics.read(LyricsTrack("big", "A", "B"))
        assertEquals(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge), assertIs<LibraryFreshness.Unavailable>(tooLarge.freshness).reason)
        assertTrue(tooLarge.breakerOpen, "a remembered verdict while the breaker is open did not say so")
        assertEquals(4, env.transport.log.size, "nothing was sent")
    }

    @Test
    fun aRememberedAnswerWhileTheBreakerIsClosedDoesNotClaimItIsOpen() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(TRACK)
        val remembered = lyrics.read(TRACK)
        assertEquals(1, env.transport.log.size, "precondition: the timeout memory answered")
        assertFalse(lyrics.breakerOpen)
        assertFalse(remembered.breakerOpen)
    }

    // ---- Fourth review: every flight joinable, v1 and v2 apart, misses asked again, timeouts --------

    @Test
    fun anAutomaticReadJoinsAnOlderFlightStillInFlight() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // The reviewer's A/B/C: an automatic read A goes out and waits...
        val older = CompletableDeferred<String>()
        env.transport.answers += older
        val a = async { lyrics.read(TRACK) }
        runCurrent()
        // ...the person's Retry B sends its own request and is answered...
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
        assertEquals(2, env.transport.log.size)
        // ...and an automatic read C, while A is still in flight, joins A: it downloads nothing.
        val c = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "an automatic read sent a request while one of the track was in flight")
        older.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, a.await().freshness)
        assertEquals(LibraryFreshness.Live, c.await().freshness)
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun anOlderFlightEndingKeepsTheNewerRetryJoinable() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val olderAnswer = CompletableDeferred<String>()
        val retryAnswer = CompletableDeferred<String>()
        env.transport.answers += olderAnswer
        env.transport.answers += retryAnswer
        val older = async { lyrics.read(TRACK) }
        runCurrent()
        val retried = async { lyrics.retry(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "precondition: the older read and the Retry are both in flight")
        olderAnswer.complete(OBSERVED_SIDECAR_LRC)
        older.await()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val third = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "an automatic read while the Retry is in flight joins it")
        retryAnswer.complete(OBSERVED_SIDECAR_LRC)
        retried.await()
        assertEquals(LibraryFreshness.Live, third.await().freshness)
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun anAutomaticReadJoinsTheNewestFlightNotAnOlderOne() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val olderAnswer = CompletableDeferred<String>()
        val retryAnswer = CompletableDeferred<String>()
        env.transport.answers += olderAnswer
        env.transport.answers += retryAnswer
        val older = async { lyrics.read(TRACK) }
        runCurrent()
        val retried = async { lyrics.retry(TRACK) }
        runCurrent()
        val joiner = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "precondition: the joiner sent nothing")
        // The older request is refused as too large while the Retry is still out: that refusal is
        // the older read's answer, not the joiner's.
        olderAnswer.complete(structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))))
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge)), older.await().freshness)
        assertFalse(joiner.isCompleted, "the joiner took the older request's answer: it joined the oldest flight")
        retryAnswer.complete(OBSERVED_SIDECAR_LRC)
        assertEquals(LibraryFreshness.Live, retried.await().freshness)
        val joined = joiner.await()
        assertEquals(LibraryFreshness.Live, joined.freshness)
        assertTrue(joined.lyrics?.layers?.isNotEmpty() == true)
        assertEquals(2, env.transport.log.size)
    }

    @Test
    fun aJoinerWhoseFlightIsCancelledHonoursAVerdictThatLandedMeanwhile() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val olderAnswer = CompletableDeferred<String>()
        val retryAnswer = CompletableDeferred<String>()
        env.transport.answers += olderAnswer
        env.transport.answers += retryAnswer
        val older = async { lyrics.read(TRACK) }
        runCurrent()
        val retried = async { lyrics.retry(TRACK) }
        runCurrent()
        val joiner = async { lyrics.read(TRACK) }
        runCurrent()
        assertEquals(2, env.transport.log.size, "precondition: the joiner joined the Retry")
        olderAnswer.complete(structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))))
        older.await()
        retried.cancel()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        val published = joiner.await()
        assertEquals(2, env.transport.log.size, "a known-too-large track is not asked for again by an automatic read")
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Protocol.TooLarge)), published.freshness)
    }

    @Test
    fun anOlderRefusalWhoseNewerDocumentWasEvictedIsAskedAgain() =
        lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 1)) { env ->
            val lyrics = env.lyrics(capabilities(setOf(1)))
            val older = CompletableDeferred<String>()
            env.transport.answers += older
            val automatic = async { lyrics.read(TRACK) }
            runCurrent()
            // A newer Retry fits and is stored; with a ceiling of one, the next document evicts it.
            env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
            assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
            env.clock.now += 1_000
            assertEquals(LibraryFreshness.Live, lyrics.read(OTHER_TRACK).freshness)
            assertNull(env.cache.lyrics(TRACK.rawId), "precondition: the newer document is evicted")
            assertEquals(3, env.transport.log.size)
            // The older request's refusal lands: the verdict is the newer answer's, so this is a
            // miss, asked again — never the older answer's "too large".
            older.complete(structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))))
            val read = automatic.await()
            assertEquals(LibraryFreshness.Live, read.freshness)
            assertEquals("first line", read.lyrics?.selected?.lines?.first()?.text)
            assertEquals(4, env.transport.log.size, "the miss was asked again")
            assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        }

    @Test
    fun anOlderRefusalServedFromTheNewerDocumentCountsAsARead() =
        lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 3)) { env ->
            val lyrics = env.lyrics(capabilities(setOf(1)))
            val older = CompletableDeferred<String>()
            env.transport.answers += older
            val automatic = async { lyrics.read(TRACK) }
            runCurrent()
            env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
            lyrics.retry(TRACK)
            for ((seq, id) in listOf("x", "y").withIndex()) {
                env.clock.now += 1_000
                env.cache.writeLyrics(100L + seq, id, LyricsSource.SongLyricsExtension, listOf(layer(id)))
            }
            env.clock.now += 1_000
            // The older refusal is published from the newer document: a read of it, so it is now
            // the most recently read.
            older.complete(structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1))))
            assertEquals(LibraryFreshness.Live, automatic.await().freshness)
            env.clock.now += 1_000
            // Four over a ceiling of three evicts down to two: the two least recently read.
            env.cache.writeLyrics(200, "z", LyricsSource.SongLyricsExtension, listOf(layer("z")))
            assertNotNull(env.cache.lyrics(TRACK.rawId), "the document it was served from was not counted as read")
            assertNull(env.cache.lyrics("x"))
            assertNull(env.cache.lyrics("y"))
        }

    @Test
    fun aNotFoundTheTransportRaisesBeforeAnyAnswerIsAFailure() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // Code 70 that no envelope of this request carried: it says nothing about the track.
        env.transport.failWith["getLyricsBySongId"] = DomainError.Server.Known(70)
        val read = lyrics.read(TRACK)
        assertFalse(read.notFound)
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Server.Known(70))), read.freshness)
        assertNull(env.cache.lyrics(TRACK.rawId), "stored as having no lyrics")
        repeat(2) { lyrics.read(TRACK) }
        assertTrue(lyrics.breakerOpen, "it counts against the endpoint")
        // The positive control: the same code in the server's own envelope is item-scoped.
        env.transport.failWith.clear()
        env.transport.codes["getLyricsBySongId"] = 70
        val other = env.lyrics(capabilities(setOf(1)))
        env.session.setOnline(false)
        env.session.setOnline(true)
        assertTrue(other.read(OTHER_TRACK).notFound)
        assertNotNull(env.cache.lyrics(OTHER_TRACK.rawId))
    }

    @Test
    fun aDroppedLayerWithoutTextCountsAsDropped() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        // Sixteen layers with text fill the layer cap; the blank one is considered last, and dropped.
        env.transport.bodies["getLyricsBySongId"] =
            structured(List(MAX_LYRICS_LAYERS) { mainLayer("l$it") } + mainLayer(""))
        val read = assertNotNull(lyrics.read(TRACK).lyrics)
        assertEquals(MAX_LYRICS_LAYERS, read.layers.size)
        assertTrue(read.layers.all { layer -> layer.lines.any { it.text.isNotBlank() } })
        assertEquals(1, read.droppedLayers, "every layer of the answer that was not kept is counted")
        assertTrue(read.isTrimmed)
    }

    @Test
    fun songLyricsVersionOneAndTwoAreSeparateReads() = lyricsTest { env ->
        val v2 = env.lyrics(capabilities(setOf(1, 2)))
        val v1 = env.lyrics(capabilities(setOf(1)))
        assertEquals(LyricsEndpoint.SongLyricsEnhanced, v2.endpoint)
        assertEquals(LyricsEndpoint.SongLyrics, v1.endpoint)
        // A v2 answer refused as too large...
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        v2.read(TRACK)
        v2.read(TRACK)
        assertEquals(1, env.transport.log.size, "precondition: the v2 verdict holds")
        // ...does not silence v1, which asks for less.
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, v1.read(TRACK).freshness)
        assertEquals(2, env.transport.log.size)
        assertEquals(null, env.transport.log.last().second["enhanced"])
        // And a v1 read never receives a v2 answer: with a v2 request in flight it sends its own.
        val held = CompletableDeferred<String>()
        env.transport.answers += held
        val enhanced = async { v2.retry(OTHER_TRACK) }
        runCurrent()
        assertEquals("true", env.transport.log.last().second["enhanced"])
        assertEquals(LibraryFreshness.Live, v1.read(OTHER_TRACK).freshness)
        assertEquals(4, env.transport.log.size, "a v1 read joined a v2 flight")
        assertEquals(null, env.transport.log.last().second["enhanced"])
        held.complete(OBSERVED_SIDECAR_LRC)
        enhanced.await()
    }

    @Test
    fun aTimeoutIsNotPaidAgainByAutomaticReadsWithinThePeriod() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        val timeout = LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Transport.Timeout))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        assertEquals(timeout, lyrics.read(TRACK).freshness)
        assertEquals(1, env.transport.log.size)
        // Within the period an automatic read sends nothing, and says it timed out — never too large.
        env.monotonic += BREAKER_OPEN_MILLIS - 1
        assertEquals(timeout, lyrics.read(TRACK).freshness)
        assertEquals(1, env.transport.log.size, "an automatic read paid the timeout again")
        assertFalse(lyrics.breakerOpen, "and the skipped reads were not charged to the breaker")
        // Another track, and the same track through the other version, are other reads.
        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.read(OTHER_TRACK).freshness)
        assertEquals(LibraryFreshness.Live, env.lyrics(capabilities(setOf(1, 2))).read(TRACK).freshness)
        assertEquals(3, env.transport.log.size)
        assertEquals(LibraryCachedReason.Failed(DomainError.Transport.Timeout), assertIs<LibraryFreshness.Cached>(lyrics.read(TRACK).freshness).reason)
        assertEquals(3, env.transport.log.size, "the v2 document did not end the v1 read's timeout")
        // After the period it is asked again.
        env.monotonic += 1
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(4, env.transport.log.size)
    }

    @Test
    fun aRetryOrAReconnectAsksATimedOutTrackAgain() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(TRACK)
        // The person's Retry is sent...
        env.transport.failWith.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.retry(TRACK).freshness)
        assertEquals(2, env.transport.log.size)
        // ...and its answer forgets the timeout: the next automatic read is sent.
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(3, env.transport.log.size)
        // A reconnect resets the breaker, and forgets a timeout with it.
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.read(OTHER_TRACK)
        lyrics.read(OTHER_TRACK)
        assertEquals(4, env.transport.log.size, "precondition: the timeout is remembered")
        env.session.setOnline(false)
        env.session.setOnline(true)
        lyrics.read(OTHER_TRACK)
        assertEquals(5, env.transport.log.size, "a reconnect kept the timeout")
    }

    @Test
    fun aTimeoutIsNeverATooLargeVerdict() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        lyrics.read(TRACK)
        env.transport.failWith["getLyricsBySongId"] = DomainError.Transport.Timeout
        lyrics.retry(TRACK)
        val skipped = lyrics.read(TRACK)
        assertEquals(2, env.transport.log.size)
        assertEquals(LibraryCachedReason.Failed(DomainError.Transport.Timeout), assertIs<LibraryFreshness.Cached>(skipped.freshness).reason)
        assertNotNull(skipped.lyrics?.selected, "the stored document is still shown")
        assertFalse(env.session.reader.lyricsReads.isTooLarge(LyricsReadKey(LyricsEndpoint.SongLyrics, TRACK.rawId)))
        // A verdict would outlast the period; a timeout does not.
        env.monotonic += BREAKER_OPEN_MILLIS
        env.transport.failWith.clear()
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(3, env.transport.log.size)
    }

    @Test
    fun theReadMemoryForgetsTheLeastRecentlyUsedTrackFirst() {
        assertEquals(1_000, MAX_LYRICS_READ_MEMORY)
        val reads = LyricsReads(capacity = 3)
        fun key(id: String) = LyricsReadKey(LyricsEndpoint.SongLyrics, id)
        for ((seq, id) in listOf("a", "b", "c").withIndex()) reads.decide(key(id), seq + 1L, tooLarge = true)
        // Consulting a is a use: b is now the least recently used.
        assertTrue(reads.isTooLarge(key("a")))
        reads.decide(key("d"), 4, tooLarge = true)
        assertEquals(listOf(true, false, true, true), listOf("a", "b", "c", "d").map { reads.isTooLarge(key(it)) })
        // A track with a request in flight is never forgotten, however old its verdict.
        val flight = reads.begin(key("c"), generation = 0)
        reads.isTooLarge(key("a"))
        reads.isTooLarge(key("d"))
        reads.decide(key("e"), 5, tooLarge = true)
        assertTrue(reads.isTooLarge(key("c")), "the verdict of a track in flight was forgotten")
        reads.end(key("c"), flight)
        // Timeouts are bounded the same way, separately.
        val breaker = EndpointCircuitBreaker()
        val admission = breaker.admit("getLyricsBySongId") as EndpointCircuitBreaker.Admission.Allowed
        for ((seq, id) in listOf("p", "q", "r", "s").withIndex()) reads.recordTimeout(key(id), 10L + seq, breaker.periodMark(admission))
        assertEquals(listOf(false, true, true, true), listOf("p", "q", "r", "s").map { reads.hasTimedOut(key(it), breaker) })
    }

    @Test
    fun evictionReadsItsCandidatesInBoundedBatchesAndStillReachesTheBound(): TestResult {
        // Seventy small documents, then one that fills the budget alone: every other one must go,
        // more than one batch of candidates.
        fun document(id: String, lines: Int) = listOf(LyricsLayer("eng", false, 0.milliseconds, List(lines) { LyricsLine("$id line", start = null) }))
        val small = lyricsStoredBytes(SERVER_ID, "s00", LyricsSource.SongLyricsExtension, document("s00", 1))
        val budget = 70 * small
        val ids = List(70) { "s${it.toString().padStart(2, '0')}" } + "big"
        fun storedTracks(env: Env) = ids.filter { env.cache.lyrics(it) != null }
        return lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 1_000, lyricsBytes = budget)) { env ->
            for (index in 0 until 70) {
                env.cache.writeLyrics(index + 1L, "s${index.toString().padStart(2, '0')}", LyricsSource.SongLyricsExtension, document("s00", 1))
                env.clock.now += 1_000
            }
            assertEquals(70, storedTracks(env).size, "precondition: the seventy fit")
            env.cache.writeLyrics(1_000, "big", LyricsSource.SongLyricsExtension, document("big", 400))
            assertEquals(listOf("big"), storedTracks(env))
        }
    }

    @Test
    fun codeSeventyClearsTheVerdict() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        lyrics.read(TRACK)
        lyrics.read(TRACK)
        assertEquals(1, env.transport.log.size, "the verdict holds")
        // The person's Retry is answered with code 70: the track has no lyrics, so nothing is too large.
        env.transport.codes["getLyricsBySongId"] = 70
        assertTrue(lyrics.retry(TRACK).notFound)
        env.transport.codes.clear()
        env.transport.bodies["getLyricsBySongId"] = OBSERVED_SIDECAR_LRC
        assertEquals(LibraryFreshness.Live, lyrics.read(TRACK).freshness)
        assertEquals(3, env.transport.log.size, "code 70 did not clear the verdict")
    }

    @Test
    fun theVerdictIsKeptPerEndpoint() = lyricsTest { env ->
        val structured = env.lyrics(capabilities(setOf(1)))
        env.transport.bodies["getLyricsBySongId"] = structured(listOf(mainLayer("x", lines = MAX_LYRICS_LINES + 1)))
        structured.read(TRACK)
        structured.read(TRACK)
        assertEquals(1, env.transport.log.size)
        // The same track through the other endpoint is another answer, and is asked for.
        env.transport.bodies["getLyrics"] = envelope(""""lyrics":{"artist":"A","title":"T","value":"one"}""")
        val legacy = env.lyrics(capabilities(null, legacySubsonic = true))
        assertEquals(LibraryFreshness.Live, legacy.read(TRACK).freshness)
        assertEquals(listOf("getLyricsBySongId", "getLyrics"), env.transport.endpoints())
    }

    @Test
    fun aWordTimedDocumentAtEveryCapIsAcceptedAndOnePastTheLimitIsTooLarge() = lyricsTest { env ->
        val lyrics = env.lyrics(capabilities(setOf(1, 2)))
        // Every cap reached at once; and the most cues the text cap admits, the shape §18.4
        // measured as the largest answer (one-byte cues of `&`, which JSON escapes as six bytes).
        // The second's nine-digit times are past the ±24 h bound, so its layer is kept as text
        // (§18.4 bounds): the largest body, not a synced one.
        val cases = listOf(
            Triple("every cap at once", wordTimedDocumentAtEveryCap(), Triple(MAX_LYRICS_LAYERS, MAX_LYRICS_LINES, true)),
            Triple("the most cues", mostCuesTheTextCapAdmits(), Triple(1, 16, false)),
        )
        for ((name, body, shape) in cases) {
            val size = body.encodeToByteArray().size
            assertTrue(size in 5_900_000 until MAX_LYRICS_RESPONSE_BYTES, "$name: the worst case is $size bytes")
            // Padded to the limit exactly, it is read, and kept whole: every cap reached, none passed.
            env.transport.bodies["getLyricsBySongId"] = padded(body, MAX_LYRICS_RESPONSE_BYTES)
            val read = lyrics.retry(TRACK)
            assertEquals(LibraryFreshness.Live, read.freshness, name)
            val document = assertNotNull(read.lyrics, name)
            assertEquals(0, document.droppedLayers, name)
            assertEquals(shape.first, document.layers.size, name)
            assertEquals(shape.second, document.layers.sumOf { it.lines.size }, name)
            assertEquals(MAX_LYRICS_TEXT_BYTES.toLong(), document.layers.sumOf { it.storedTextBytes() }, name)
            assertTrue(document.layers.all { it.synced == shape.third }, "$name: synced should be ${shape.third}")
            // One byte past the limit is refused, and the stored document stays.
            env.transport.bodies["getLyricsBySongId"] = padded(body, MAX_LYRICS_RESPONSE_BYTES + 1)
            val refused = lyrics.retry(TRACK)
            assertEquals(
                LibraryCachedReason.Failed(DomainError.Protocol.TooLarge),
                assertIs<LibraryFreshness.Cached>(refused.freshness, name).reason,
                name,
            )
            assertEquals(document.layers, env.cache.lyrics(TRACK.rawId)?.layers, name)
        }
    }

    // ---- The store ----------------------------------------------------------------------------------

    @Test
    fun aSlowerAnswerNeverOverwritesANewerOne() = lyricsTest { env ->
        val newer = listOf(layer("newer"))
        assertTrue(env.cache.writeLyrics(10, "t", LyricsSource.SongLyricsExtension, newer))
        assertFalse(env.cache.writeLyrics(9, "t", LyricsSource.SongLyricsExtension, listOf(layer("older"))))
        assertFalse(env.cache.writeLyrics(10, "t", LyricsSource.SongLyricsExtension, listOf(layer("same"))))
        assertEquals(newer, env.cache.lyrics("t")?.layers)
        assertTrue(env.cache.writeLyrics(11, "t", LyricsSource.LegacyGetLyrics, emptyList()))
        assertEquals(emptyList(), env.cache.lyrics("t")?.layers)
        assertEquals(LyricsSource.LegacyGetLyrics, env.cache.lyrics("t")?.source)
    }

    @Test
    fun layersAndLinesRoundTripExactly() = lyricsTest { env ->
        val (_, layers) = parseLyricsResponse(LyricsEndpoint.SongLyricsEnhanced, OBSERVED_SIDECAR_LRC)
        val two = layers + LyricsLayer("jpn", false, (-40).milliseconds, listOf(LyricsLine("東京", start = null)), "translation", null, "Title")
        env.cache.writeLyrics(1, "t", LyricsSource.SongLyricsExtension, two)
        assertEquals(two, env.cache.lyrics("t")?.layers)
    }

    @Test
    fun aRebindingToAnotherAccountPurgesLyricsWithTheNamespace() = lyricsTest { env ->
        env.cache.writeLyrics(1, "t", LyricsSource.SongLyricsExtension, listOf(layer("mine")))
        val other = env.store.bind(env.binding.copy(username = "someone-else"))
        assertTrue(other.purgedOnBind)
        assertNull(other.lyrics("t"))
        val remaining = env.database.database.seenCacheQueries.countNamespaceRows(env.binding.serverId).executeAsOne().sum
        assertEquals(0L, remaining)
    }

    @Test
    fun accountRemovalDeletesLyrics() = lyricsTest { env ->
        env.cache.writeLyrics(1, "t", LyricsSource.SongLyricsExtension, listOf(layer("mine")))
        val queries = env.database.database.serverDataQueries
        assertTrue(queries.countRowsForServer(env.binding.serverId).executeAsOne().sum!! > 0L)
        queries.deleteSeenCacheForServer(env.binding.serverId)
        assertEquals(0L, queries.countRowsForServer(env.binding.serverId).executeAsOne().sum)
    }

    @Test
    fun evictionTakesTheLeastRecentlyAccessedUnpinnedLyricsFirst() = lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 3)) { env ->
        env.cache.pin(CacheItemKind.Track, "pinned", CachePinReason.Download)
        var seq = 1L
        for (id in listOf("pinned", "old", "middle")) {
            env.cache.writeLyrics(seq++, id, LyricsSource.SongLyricsExtension, listOf(layer(id)))
            env.clock.now += 1_000
        }
        env.cache.touchLyrics("old")
        env.clock.now += 1_000
        env.cache.writeLyrics(seq++, "new", LyricsSource.SongLyricsExtension, listOf(layer("new")))
        // Four over a ceiling of three: target 2, so two go — never the pinned one, oldest first.
        assertNotNull(env.cache.lyrics("pinned"))
        assertNull(env.cache.lyrics("middle"))
        assertNull(env.cache.lyrics("old"))
        assertNotNull(env.cache.lyrics("new"))
    }

    @Test
    fun theByteBudgetEvictsTheLeastRecentlyReadDocumentsFirst(): TestResult {
        // Every id is three bytes, so every 80-line document holds the same bytes.
        fun document(id: String, lines: Int = 80) =
            listOf(LyricsLayer("eng", false, 0.milliseconds, List(lines) { LyricsLine("$id ${"w".repeat(40)}", start = null) }))
        val each = lyricsStoredBytes(SERVER_ID, "aaa", LyricsSource.SongLyricsExtension, document("aaa"))
        // Four documents and half of a fifth; past it, the store goes back to one per cent below.
        val budget = 4 * each + each / 2
        return lyricsTest(SeenCacheCeilings(10, 10, 10, 10, lyrics = 100, lyricsBytes = budget)) { env ->
            env.cache.pin(CacheItemKind.Track, "pin", CachePinReason.Download)
            var seq = 1L
            for (id in listOf("pin", "aaa", "bbb", "ccc")) {
                env.cache.writeLyrics(seq++, id, LyricsSource.SongLyricsExtension, document(id))
                env.clock.now += 1_000
            }
            assertEquals(each, env.cache.lyrics("aaa")?.storedBytes)
            assertEquals(4 * each, storedBytes(env), "the four fit")
            // Reading aaa makes it more recent than bbb and ccc; the fifth write passes the budget.
            env.cache.touchLyrics("aaa")
            env.clock.now += 1_000
            env.cache.writeLyrics(seq++, "ddd", LyricsSource.SongLyricsExtension, document("ddd"))
            assertEquals(listOf("aaa", "ccc", "ddd", "pin"), stored(env), "the least recently read unpinned document goes")
            assertEquals(4 * each, storedBytes(env))
            // Counted in bytes, not documents: a document half again as large takes the room of two.
            env.clock.now += 1_000
            env.cache.writeLyrics(seq++, "eee", LyricsSource.SongLyricsExtension, document("eee", lines = 120))
            assertEquals(listOf("ddd", "eee", "pin"), stored(env), "ccc, then aaa, least recently read first")
            assertTrue(storedBytes(env) <= budget - budget / 100, "the store holds ${storedBytes(env)} of $budget bytes")
            // A pinned track's lyrics are never evicted, and neither is the document just written,
            // which its read publishes next: together they may pass the budget.
            env.cache.pin(CacheItemKind.Track, "ddd", CachePinReason.Download)
            env.cache.pin(CacheItemKind.Track, "eee", CachePinReason.Download)
            env.clock.now += 1_000
            env.cache.writeLyrics(seq++, "fff", LyricsSource.SongLyricsExtension, document("fff", lines = 240))
            assertEquals(listOf("ddd", "eee", "fff", "pin"), stored(env))
            assertTrue(storedBytes(env) > budget)
            // Unpinned again, they go at the next write, least recently read first, until the
            // store is back under: ddd and eee are not enough, so fff goes too.
            env.cache.unpin(CacheItemKind.Track, "ddd", CachePinReason.Download)
            env.cache.unpin(CacheItemKind.Track, "eee", CachePinReason.Download)
            env.clock.now += 1_000
            env.cache.writeLyrics(seq++, "aaa", LyricsSource.SongLyricsExtension, document("aaa"))
            assertEquals(listOf("aaa", "pin"), stored(env))
            assertTrue(storedBytes(env) <= budget - budget / 100)
        }
    }

    @Test
    fun aDocumentsStoredBytesCountWhatItsRowsHold() = lyricsTest { env ->
        val layers = listOf(
            LyricsLayer("eng", true, 250.milliseconds, listOf(LyricsLine("é", 100.milliseconds), LyricsLine("", 200.milliseconds)), "main", "Artist", null),
        )
        env.cache.writeLyrics(1, "t", LyricsSource.SongLyricsExtension, layers)
        // Keys ("server:lyrics" 13 + "t" 1) twice in every row, eight bytes an integer.
        val keys = 2L * (13 + 1)
        val header = keys + "songLyrics".length + 5 * 8
        val layer = keys + 2 * 8 + 2 * 8 + "eng".length + "main".length + "Artist".length
        val lines = 2 * (keys + 4 * 8 + 8) + 2 /* é is two bytes */
        assertEquals(header + layer + lines, env.cache.lyrics("t")?.storedBytes)
        assertEquals(32L * 1_048_576, SeenCacheCeilings.DEFAULT.lyricsBytes, "the budget the maintainer chose")
    }

    @Test
    fun aTrackEvictedFromTheCacheTakesItsLyricsWithIt() = lyricsTest(SeenCacheCeilings(albums = 10, tracks = 1, artists = 10, lists = 10)) { env ->
        val reader = env.session.reader
        env.cache.writeEntities(
            CacheWriteStamp(1, env.clock.now, null),
            CacheEntitySource.ListPage,
            CacheEntities(
                tracks = listOf(track("gone-soon"), track("kept")),
            ),
        )
        env.cache.writeLyrics(2, "gone-soon", LyricsSource.SongLyricsExtension, listOf(layer("x")))
        env.cache.pin(CacheItemKind.Track, "kept", CachePinReason.Queue)
        reader.cache.evictIfNeeded()
        assertNull(env.cache.track("gone-soon"))
        assertNull(env.cache.lyrics("gone-soon"))
        assertNotNull(env.cache.track("kept"))
    }

    // ---- Harness ------------------------------------------------------------------------------------

    private fun layer(text: String) =
        LyricsLayer("eng", true, 0.milliseconds, listOf(LyricsLine(text, 100.milliseconds)))

    private fun track(rawId: String) = CacheTrackRecord(rawId = rawId, albumRawId = null, title = "Song $rawId")

    private fun storedBytes(env: Env): Long =
        env.database.database.lyricsCacheQueries.sumLyricsBytes(env.binding.serverId).executeAsOne()

    /** The tracks whose lyrics are stored, sorted. */
    private fun stored(env: Env): List<String> =
        listOf("pin", "aaa", "bbb", "ccc", "ddd", "eee", "fff").filter { env.cache.lyrics(it) != null }.sorted()

    /** Every string of the layer the text cap counts, in UTF-8 bytes. */
    private fun LyricsLayer.storedTextBytes(): Long =
        listOfNotNull(language, kind, displayArtist, displayTitle).sumOf { it.encodeToByteArray().size.toLong() } +
            lines.sumOf { it.text.encodeToByteArray().size.toLong() }

    private fun capabilities(songLyrics: Set<Int>?, legacySubsonic: Boolean = false) = CapabilitySet(
        extensions = songLyrics?.let { mapOf(SONG_LYRICS_EXTENSION to it) }.orEmpty(),
        permissions = UserPermissions(download = true, playlist = true, share = false, jukebox = false, admin = false),
        legacySubsonic = legacySubsonic,
    )

    private class LyricsTransport : LibraryEndpointTransport {
        val log = mutableListOf<Pair<String, Map<String, String>>>()
        val bodies = mutableMapOf<String, String>()
        val codes = mutableMapOf<String, Int>()
        val failWith = mutableMapOf<String, DomainError>()

        /** When set, a request is logged and then waits here: a request in flight. */
        var hold: CompletableDeferred<Unit>? = null

        /** Like [hold], for the next request only. */
        var holdNext: CompletableDeferred<Unit>? = null

        /** Every request sent through the size-limited overload, with its limit. */
        val limited = mutableListOf<Pair<String, Int>>()

        /**
         * When not empty, the next request takes the first of these and answers, with status 200,
         * the body it is completed with: a request in flight whose answer the test chooses later.
         */
        val answers = ArrayDeque<CompletableDeferred<String>>()

        fun endpoints() = log.map { it.first }

        override suspend fun request(endpoint: String, parameters: Map<String, String>, maxBodyBytes: Int): LibraryEndpointResponse {
            limited += endpoint to maxBodyBytes
            return super.request(endpoint, parameters, maxBodyBytes)
        }

        override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
            log += endpoint to parameters
            answers.removeFirstOrNull()?.let { return LibraryEndpointResponse(200, it.await(), "http://fixture.invalid/rest") }
            holdNext?.also { holdNext = null }?.await()
            hold?.await()
            failWith[endpoint]?.let { throw LibraryRequestFailure(it) }
            val body = codes[endpoint]?.let {
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$it,"message":"data not found"}}}"""
            } ?: bodies[endpoint] ?: error("no fixture for $endpoint")
            return LibraryEndpointResponse(200, body, "http://fixture.invalid/rest")
        }
    }

    private class Env(
        val transport: LyricsTransport,
        val database: DulcetDatabaseStore,
        val store: SeenCacheStore,
        val clock: ManualWallClock,
        val binding: CacheBinding,
        val session: LibraryReaderSession,
        private val monotonicClock: LongArray,
        val driver: SqlDriver,
    ) {
        /** The breaker's monotonic clock, in milliseconds. */
        var monotonic: Long
            get() = monotonicClock[0]
            set(value) {
                monotonicClock[0] = value
            }

        val cache: BoundSeenCache get() = session.reader.cache

        fun lyrics(capabilities: CapabilitySet, languages: List<String> = listOf("en")) =
            session.lyrics(capabilities, languages)
    }

    private fun lyricsTest(
        ceilings: SeenCacheCeilings = SeenCacheCeilings.DEFAULT,
        block: suspend TestScope.(Env) -> Unit,
    ) = runTest {
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val database = DulcetDatabaseStore.open(driver)
            val clock = ManualWallClock(now = 3_000_000)
            val store = SeenCacheStore(database, clock, ceilings)
            val binding = CacheBinding(SERVER_ID, "https://music.example", "listener")
            val transport = LyricsTransport()
            val monotonic = longArrayOf(50_000)
            val breaker = EndpointCircuitBreaker(openMillis = BREAKER_OPEN_MILLIS, monotonicMillis = { monotonic[0] })
            val session = LibraryReaderSession(database.database, store.bind(binding), transport, scope, formPost = false, breaker = breaker)
            block(Env(transport, database, store, clock, binding, session, monotonic, driver))
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private companion object {
        const val BREAKER_OPEN_MILLIS = 120_000L
        const val SERVER_ID = "server:lyrics"
        val TRACK = LyricsTrack("tr-31/opaque:not-an-int", "Dulcet Fixtures", "Thirty One Seconds")
        val OTHER_TRACK = LyricsTrack("other-track", "Dulcet Fixtures", "Another")
        val STRAGGLER = LyricsTrack("straggler-track", "Dulcet Fixtures", "Late")

        fun structured(layers: List<String>) = envelope(""""lyricsList":{"structuredLyrics":[${layers.joinToString(",")}]}""")

        /** A main layer (no `kind`, no language) of [lines] lines of [text]. */
        fun mainLayer(text: String, lines: Int = 1) =
            """{"synced":false,"line":[${(0 until lines).joinToString(",") { """{"value":"$text"}""" }}]}"""

        fun unsyncedMainLayer(lang: String, text: String) =
            """{"lang":"$lang","synced":false,"line":[{"value":"$text"}]}"""

        fun syncedMainLayer(lang: String, text: String, lines: Int = 1) =
            """{"lang":"$lang","synced":true,"line":[${(0 until lines).joinToString(",") { """{"start":${1_000 * it},"value":"$text"}""" }}]}"""

        fun translation(lang: String, lines: Int, text: String) =
            """{"kind":"translation","lang":"$lang","synced":false,"line":[${(0 until lines).joinToString(",") { """{"value":"$text"}""" }}]}"""

        /**
         * The worst document the caps admit, as the reference server serializes word-timed lyrics
         * with `enhanced=true` (Navidrome 0.63.2, `server/subsonic/lyrics.go`): sixteen layers,
         * 2,000 lines, 65,536 bytes of stored text, and a cue for every byte of line text — `&`,
         * which JSON escapes to six bytes — with eight-digit times, still trusted. Each line's
         * text goes out three times: in `line`, in its `cueLine`, and across its cues.
         */
        fun wordTimedDocumentAtEveryCap(): String {
            val cuesPerLine = 32
            val linesPerLayer = MAX_LYRICS_LINES / MAX_LYRICS_LAYERS
            val escaped = "\\u0026"
            val lineValue = escaped.repeat(cuesPerLine)
            val strings = (0 until MAX_LYRICS_LAYERS).sumOf { "eng".length + (if (it == 0) "main" else "translation").length }
            val title = "t".repeat(MAX_LYRICS_TEXT_BYTES - MAX_LYRICS_LINES * cuesPerLine - strings)
            val layers = (0 until MAX_LYRICS_LAYERS).joinToString(",") { layer ->
                val kind = if (layer == 0) "main" else "translation"
                val starts = (0 until linesPerLayer).map { 10_800_000L + 1_000L * it }
                val lines = starts.joinToString(",") { """{"start":$it,"value":"$lineValue"}""" }
                val cueLines = starts.withIndex().joinToString(",") { (index, start) ->
                    val cues = (0 until cuesPerLine).joinToString(",") { cue ->
                        """{"start":${start + 10 * cue},"end":${start + 10 * cue + 10},"byteStart":$cue,"byteEnd":$cue,"value":"$escaped"}"""
                    }
                    """{"index":$index,"start":$start,"end":${start + 10 * cuesPerLine},"value":"$lineValue","cue":[$cues]}"""
                }
                val display = if (layer == 0) """"displayTitle":"$title",""" else ""
                """{$display"kind":"$kind","lang":"eng","line":[$lines],"cueLine":[$cueLines],"synced":true}"""
            }
            return envelope(""""lyricsList":{"structuredLyrics":[$layers]}""")
        }

        /**
         * One main layer whose line text is the text cap exactly, as one-byte cues of `&` in 16
         * lines of up to 4,096, with nine-digit times: the shape that §18.4 measured as the largest
         * answer on the reference server (OBSERVED 2026-09-24), rebuilt in its response format.
         */
        fun mostCuesTheTextCapAdmits(): String {
            val escaped = "\\u0026"
            val strings = "eng".length + "main".length + "A".length + "T".length
            val cues = MAX_LYRICS_TEXT_BYTES - strings
            val perLine = List(16) { minOf(4_096, cues - 4_096 * it) }
            check(perLine.all { it > 0 } && perLine.sum() == cues)
            val starts = List(16) { 356_400_000L + 60_000L * it }
            val lines = perLine.indices.joinToString(",") {
                """{"start":${starts[it]},"value":"${escaped.repeat(perLine[it])}"}"""
            }
            val cueLines = perLine.indices.joinToString(",") { index ->
                val start = starts[index]
                val each = (0 until perLine[index]).joinToString(",") { cue ->
                    """{"start":${start + 10 * cue},"end":${start + 10 * cue + 10},"byteStart":$cue,"byteEnd":$cue,"value":"$escaped"}"""
                }
                """{"index":$index,"start":$start,"end":${start + 60_000},"value":"${escaped.repeat(perLine[index])}","cue":[$each]}"""
            }
            val layer = """{"displayArtist":"A","displayTitle":"T","kind":"main","lang":"eng","line":[$lines],"cueLine":[$cueLines],"synced":true}"""
            return envelope(""""lyricsList":{"structuredLyrics":[$layer]}""")
        }

        /** [body] with spaces before its last brace, to exactly [bytes] UTF-8 bytes. */
        fun padded(body: String, bytes: Int): String {
            val missing = bytes - body.encodeToByteArray().size
            check(missing >= 0)
            return body.dropLast(1) + " ".repeat(missing) + "}"
        }

        fun envelope(body: String?) =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true${body?.let { ",$it" } ?: ""}}}"""

        /** OBSERVED 2026-09-23 from a disposable Navidrome 0.63.2: a sidecar LRC with `[offset:+250]`. */
        val OBSERVED_SIDECAR_LRC = envelope(
            """"lyricsList":{"structuredLyrics":[{"displayArtist":"Dulcet Fixtures","displayTitle":"Side Lrc","kind":"main","lang":"xxx","line":[{"start":500,"value":"first line"},{"start":2000,"value":"second line"},{"start":3000,"value":""},{"start":4250,"value":"fourth line"}],"offset":250,"synced":true}]}""",
        )
    }
}
