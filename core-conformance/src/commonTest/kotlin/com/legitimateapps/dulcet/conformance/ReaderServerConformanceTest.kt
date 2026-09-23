package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionContract
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Server facts the reader architecture stands on (design spec §16.8–§16.12, CONF-70..75).
 *
 * These tests drive the disposable reference server directly over `/rest`; no reader code exists yet
 * (phase R0 of §16.18). They pin what the server does, so that a server upgrade that changes one of
 * these behaviours fails here rather than inside the reader.
 *
 * Two servers are involved. The fixture server purges missing files (`PurgeMissing = "always"`); a
 * second server, identical except that `PurgeMissing` is left at Navidrome's default, keeps them
 * (`tools/conformance-env/purge-default-server`). Several tests move an album directory out of a
 * server's music folder and back, so both servers must be disposable.
 *
 * Every mutation asserts that it fired — the directory moved AND the server's scan stamp moved AND
 * the change is visible — and is paired with the same observation made without the mutation.
 * Every absence is paired with a positive control made by the same instrument.
 *
 * The class is abstract because moving a directory is a platform operation. It runs on exactly two
 * legs, JVM and `macosArm64`, through the concrete subclasses in those source sets; the simulator and
 * Android legs compile it and run none of it, which is declared scope, not a skip.
 */
abstract class ReaderServerConformanceTest {
    /** Moves one directory within the same volume; must throw on failure. */
    protected abstract fun moveDirectory(source: String, destination: String)

    protected abstract fun isDirectory(path: String): Boolean

    protected abstract fun isFile(path: String): Boolean

    protected abstract fun createDirectories(path: String)

    // ---- CONF-70 ------------------------------------------------------------------------------

    @Test
    fun conf70ScannedDeletionBeforeTheCursorChangesThePostPageScanStatus(): TestResult =
        realTime(4.minutes) {
            val fixture = fixture()
            ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
                fixture.requireIdentity(admin)
                val opening = admin.awaitIdle("CONF-70 opening")
                requireRealStamp("CONF-70", opening)
                val reference = admin.albumIds()
                val target = admin.albumNamed(MUTATED_ALBUM, "CONF-70").id
                val targetIndex = reference.indexOf(target)
                assertTrue(
                    targetIndex in 0 until reference.size - 2 * WINDOW_PAGE_SIZE,
                    "CONF-70 needs $MUTATED_ALBUM early enough that pages follow its page; " +
                        "index=$targetIndex of ${reference.size}",
                )

                // No-mutation control: the same paged read, and the check must NOT fire.
                val control = admin.readWindow()
                assertTrue(control.pages.size >= 3, "CONF-70 control read too few pages: ${control.pages.size}")
                assertEquals(reference, control.ids, "CONF-70 control pages did not reproduce the list in order")
                assertTrue(
                    control.afterReadings.all { it.lastScan == opening.lastScan && !it.scanning },
                    "CONF-70 control: a post-page reading differed with no mutation, so a firing " +
                        "check would prove nothing: ${control.afterReadings}",
                )

                // Mutation: read up to and including the target's page, remove it, let the scan finish.
                val lastPageBeforeRemoval = targetIndex / WINDOW_PAGE_SIZE
                val cursor = (lastPageBeforeRemoval + 1) * WINDOW_PAGE_SIZE
                val returned = mutableListOf<String>()
                val afterReadings = mutableListOf<ScanReading>()
                for (page in 0..lastPageBeforeRemoval) {
                    val ids = admin.albumPage(page * WINDOW_PAGE_SIZE, WINDOW_PAGE_SIZE)
                    returned += ids
                    afterReadings += admin.scanStatus()
                }
                assertTrue(target in returned, "CONF-70 target was not on a page read before the removal")
                assertTrue(
                    afterReadings.all { it.lastScan == opening.lastScan && !it.scanning },
                    "CONF-70 pages before the removal already disagreed with the opening reading",
                )
                val album = fixture.albumDirectory(MUTATED_ALBUM)
                val removalScan = moveAndAwaitScan(admin, album, fixture.holdingDirectory(MUTATED_ALBUM), "CONF-70 removal")
                try {
                    val afterRemoval = admin.albumIds()
                    assertEquals(
                        reference - target,
                        afterRemoval,
                        "CONF-70 mutation did not fire: the removed album is still listed",
                    )
                    var offset = cursor
                    val pagesAfterRemoval = mutableListOf<ScanReading>()
                    while (true) {
                        val ids = admin.albumPage(offset, WINDOW_PAGE_SIZE)
                        pagesAfterRemoval += admin.scanStatus()
                        if (ids.isEmpty()) break
                        returned += ids
                        offset += ids.size
                    }
                    assertTrue(pagesAfterRemoval.size >= 2, "CONF-70 read no page after the removal")
                    assertTrue(
                        pagesAfterRemoval.all { it.lastScan != opening.lastScan && !it.scanning },
                        "CONF-70: a page read after the scanned removal was accepted under the " +
                            "opening stamp: $pagesAfterRemoval",
                    )
                    assertEquals(
                        removalScan.lastScan,
                        pagesAfterRemoval.first().lastScan,
                        "CONF-70 the first page after the removal was not read under the removal's scan stamp",
                    )
                    val survivors = reference - target
                    val skipped = survivors.filter { it !in returned }
                    assertEquals(
                        listOf(reference[cursor]),
                        skipped,
                        "CONF-70: offset paging across the deletion should silently skip exactly the " +
                            "album that slid behind the cursor",
                    )
                    assertEquals(returned.size, returned.toSet().size, "CONF-70 returned a duplicate id")
                    println(
                        "CONF-70 OBSERVED control_pages=${control.pages.size} control_stamp_changes=0 " +
                            "target_index=$targetIndex cursor=$cursor pages_after_removal=" +
                            "${pagesAfterRemoval.size} post_page_stamp_changed=true " +
                            "silently_skipped=${skipped.size} scan_polls=${removalScan.polls}",
                    )
                } finally {
                    restoreAndAwaitScan(admin, fixture.holdingDirectory(MUTATED_ALBUM), album, "CONF-70 restore")
                }
                assertEquals(reference, admin.albumIds(), "CONF-70 restore did not return the fixture list")
            }
        }

    @Test
    fun conf70RaceProbeAcceptsNoPageUnderAStampItDoesNotBelongTo(): TestResult = realTime(4.minutes) {
        // §16.12 accepts a page only when the reading issued BEFORE it and the reading issued AFTER
        // its response both show one stamp and no scan. The detector must be able to fire before
        // its silence means anything (a negative control that cannot fire proves nothing).
        val a = listOf("a")
        val b = listOf("b")
        val idle = ScanReading("s1", scanning = false, count = null)
        val busy = ScanReading("s1", scanning = true, count = null)
        val moved = ScanReading("s2", scanning = false, count = null)
        assertEquals(
            setOf("s1"),
            raceViolations(listOf(RaceSample(a, idle, idle), RaceSample(b, idle, idle))),
            "CONF-70 race detector did not fire on two accepted contents under one idle stamp",
        )
        assertEquals(
            emptySet(),
            raceViolations(listOf(RaceSample(a, idle, idle), RaceSample(b, idle, busy))),
            "CONF-70 race detector accepted a page whose after-reading showed a scan",
        )
        assertEquals(
            emptySet(),
            raceViolations(listOf(RaceSample(a, idle, idle), RaceSample(b, busy, idle))),
            "CONF-70 race detector accepted a page whose before-reading showed a scan",
        )
        assertEquals(
            emptySet(),
            raceViolations(listOf(RaceSample(a, idle, idle), RaceSample(b, idle, moved))),
            "CONF-70 race detector accepted a page across a stamp change",
        )
        // The after-only rule is weaker, and is measured beside the real one rather than trusted.
        assertEquals(
            setOf("s1"),
            afterOnlyViolations(listOf(RaceSample(a, idle, idle), RaceSample(b, busy, idle))),
            "CONF-70 after-only counter did not fire",
        )

        val fixture = fixture()
        ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
            fixture.requireIdentity(admin)
            val reference = admin.albumIds()
            admin.awaitIdle("CONF-70 race opening")
            val inside = fixture.albumDirectory(MUTATED_ALBUM)
            val outside = fixture.holdingDirectory(MUTATED_ALBUM)
            val samples = mutableListOf<RaceSample>()
            var toggles = 0
            try {
                coroutineScope {
                    val toggler = launch {
                        while (isActive) {
                            delay(RACE_TOGGLE_INTERVAL)
                            if (isDirectory(inside)) moveDirectory(inside, outside) else moveDirectory(outside, inside)
                            toggles += 1
                        }
                    }
                    val started = TimeSource.Monotonic.markNow()
                    var before = admin.scanStatus()
                    while (started.elapsedNow() < RACE_DURATION) {
                        val ids = admin.albumPage(0, 100).sorted()
                        val after = admin.scanStatus()
                        samples += RaceSample(ids, before, after)
                        before = after
                        // Paced: an unpaced loop exhausted the host's ephemeral ports on the JVM
                        // leg (BindException), which measures the client, not the server.
                        delay(RACE_SAMPLE_PAUSE)
                    }
                    toggler.cancel()
                }
            } finally {
                if (!isDirectory(inside)) moveDirectory(outside, inside)
                // The watcher scans ~5 s after the last change it saw (OBSERVED 5.07-5.16 s on
                // 0.63.2). A fixed wait would let a slow runner's late scan land inside the next
                // test, so wait for a quiet window longer than that delay instead.
                admin.awaitQuiet("CONF-70 race restore", WATCHER_QUIET_WINDOW)
            }
            assertEquals(reference, admin.albumIds(), "CONF-70 race did not leave the fixture list restored")
            val acceptedStamps = samples.filter(RaceSample::accepted).map { it.after.lastScan }.toSet()
            val scanningSamples = samples.count { it.after.scanning }
            val violations = raceViolations(samples)
            val afterOnly = afterOnlyViolations(samples)
            println(
                "CONF-70 OBSERVED race samples=${samples.size} scanning_samples=$scanningSamples " +
                    "toggles=$toggles accepted_stamps=${acceptedStamps.size} " +
                    "accepted=${samples.count(RaceSample::accepted)} violations=${violations.size} " +
                    "after_only_violations=${afterOnly.size}",
            )
            assertTrue(
                toggles >= 2 && acceptedStamps.size >= 2,
                "CONF-70 the race did not happen: toggles=$toggles accepted_stamps=${acceptedStamps.size}",
            )
            assertTrue(
                scanningSamples >= 1,
                "CONF-70 the race did not happen: no page was read while a scan was running",
            )
            assertEquals(
                emptySet(),
                violations,
                "CONF-70 one stamp accepted two different pages under the bracketed check: " +
                    violations.joinToString { stamp -> describeRace(samples, stamp) },
            )
        }
    }

    // ---- CONF-71 ------------------------------------------------------------------------------

    @Test
    fun conf71ScanStampIsReadableByANonAdminAndUnmovedByEffectiveUserStateWrites(): TestResult =
        realTime(3.minutes) {
            val fixture = fixture()
            ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
                ReaderProbe(fixture.baseUrl, RESTRICTED_USER, RESTRICTED_PASSWORD).use { restricted ->
                    fixture.requireIdentity(admin)
                    val opening = admin.awaitIdle("CONF-71 opening")
                    requireRealStamp("CONF-71", opening)
                    val asRestricted = restricted.scanStatus()
                    assertEquals(
                        opening,
                        asRestricted.copy(polls = opening.polls),
                        "CONF-71 a non-admin user read a different scan status",
                    )
                    assertTrue(
                        restricted.albumIds().isNotEmpty(),
                        "CONF-71 the restricted fixture user cannot read the library",
                    )

                    val album = restricted.albumNamed(USER_STATE_ALBUM_CONF71, "CONF-71")
                    val track = restricted.firstTrackId(album.id, "CONF-71")
                    val playsBefore = restricted.albumState(album.id).playCount
                    try {
                        restricted.requireOk("star", mapOf("albumId" to album.id), "CONF-71")
                        assertNotNull(restricted.albumState(album.id).starred, "CONF-71 star did not take effect")
                        assertEquals(opening.lastScan, admin.scanStatus().lastScan, "CONF-71 star moved lastScan")

                        restricted.requireOk("setRating", mapOf("id" to album.id, "rating" to "4"), "CONF-71")
                        assertEquals(4, restricted.albumState(album.id).userRating, "CONF-71 setRating did not take effect")
                        assertEquals(opening.lastScan, admin.scanStatus().lastScan, "CONF-71 setRating moved lastScan")

                        restricted.requireOk("scrobble", mapOf("id" to track, "submission" to "true"), "CONF-71")
                        assertEquals(
                            playsBefore + 1,
                            restricted.albumState(album.id).playCount,
                            "CONF-71 scrobble did not take effect",
                        )
                        val afterWrites = admin.scanStatus()
                        assertEquals(opening.lastScan, afterWrites.lastScan, "CONF-71 scrobble moved lastScan")
                        assertFalse(afterWrites.scanning, "CONF-71 a user-state write started a scan")
                    } finally {
                        restricted.requireOk("unstar", mapOf("albumId" to album.id), "CONF-71 cleanup")
                        restricted.requireOk("setRating", mapOf("id" to album.id, "rating" to "0"), "CONF-71 cleanup")
                    }

                    // Positive control: the same instrument sees the stamp move when a scan runs, so the
                    // unchanged readings above are a finding and not a blind probe. It also pins that a
                    // no-op scan moves the stamp (§16.11: the failure direction is a needless revalidation).
                    admin.requireOk("startScan", emptyMap(), "CONF-71")
                    val moved = admin.awaitScanAfter(opening, "CONF-71 no-op startScan")
                    assertNotEquals(opening.lastScan, moved.lastScan)
                    println(
                        "CONF-71 OBSERVED non_admin_status_equal=true star_effective=true " +
                            "rating_effective=true scrobble_effective=true stamp_moved_by_user_state=false " +
                            "stamp_moved_by_noop_scan=true scan_polls=${moved.polls}",
                    )
                }
            }
        }

    // ---- CONF-72 ------------------------------------------------------------------------------

    @Test
    fun conf72PerUserStateInCatalogPayloadsReflectsTheReadingUserOnly(): TestResult = realTime(2.minutes) {
        val fixture = fixture()
        ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
            ReaderProbe(fixture.baseUrl, RESTRICTED_USER, RESTRICTED_PASSWORD).use { restricted ->
                fixture.requireIdentity(admin)
                val album = restricted.albumNamed(USER_STATE_ALBUM_CONF72, "CONF-72")
                val track = restricted.firstTrackId(album.id, "CONF-72")
                // Control: before the writes, neither user carries a star or a rating.
                val restrictedBefore = restricted.albumState(album.id)
                val adminBefore = admin.albumState(album.id)
                for ((who, state) in listOf("restricted" to restrictedBefore, "admin" to adminBefore)) {
                    assertNull(state.starred, "CONF-72 control: $who already had the album starred")
                    assertTrue(state.userRating in setOf(null, 0), "CONF-72 control: $who already rated it")
                }
                try {
                    restricted.requireOk("star", mapOf("albumId" to album.id), "CONF-72")
                    restricted.requireOk("setRating", mapOf("id" to album.id, "rating" to "4"), "CONF-72")
                    restricted.requireOk("scrobble", mapOf("id" to track, "submission" to "true"), "CONF-72")

                    val restrictedAfter = restricted.albumState(album.id)
                    assertNotNull(restrictedAfter.starred, "CONF-72 mutation did not fire: star absent for its user")
                    assertEquals(4, restrictedAfter.userRating, "CONF-72 mutation did not fire: rating")
                    assertEquals(restrictedBefore.playCount + 1, restrictedAfter.playCount, "CONF-72 mutation did not fire: play")
                    assertNotNull(restrictedAfter.played, "CONF-72 mutation did not fire: played")

                    val adminAfter = admin.albumState(album.id)
                    assertEquals(
                        adminBefore,
                        adminAfter,
                        "CONF-72 another user's star, rating or play leaked into the admin's payload",
                    )
                    println(
                        "CONF-72 OBSERVED writer_starred=true writer_rating=4 writer_play_delta=1 " +
                            "other_user_unchanged=true surfaces=getAlbumList2,getAlbum",
                    )
                } finally {
                    restricted.requireOk("unstar", mapOf("albumId" to album.id), "CONF-72 cleanup")
                    restricted.requireOk("setRating", mapOf("id" to album.id, "rating" to "0"), "CONF-72 cleanup")
                }
                val restored = restricted.albumState(album.id)
                assertNull(restored.starred, "CONF-72 cleanup left the album starred")
                assertTrue(restored.userRating in setOf(null, 0), "CONF-72 cleanup left a rating")
            }
        }
    }

    // ---- CONF-73 ------------------------------------------------------------------------------

    @Test
    fun conf73JsonReadsCarryNoValidatorsAndAConditionalRequestReturnsAFull200(): TestResult =
        realTime(2.minutes) {
            val fixture = fixture()
            ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
                fixture.requireIdentity(admin)
                val referenceCount = admin.albumIds().size
                val someAlbum = admin.albumNamed(UNTOUCHED_ALBUM, "CONF-73").id
                val reads = listOf(
                    "getAlbumList2" to mapOf("type" to "alphabeticalByName", "size" to "500"),
                    "getAlbum" to mapOf("id" to someAlbum),
                    "getArtists" to emptyMap(),
                    "search3" to mapOf("query" to "Dulcet"),
                    "getGenres" to emptyMap(),
                    "getStarred2" to emptyMap(),
                    "getPlaylists" to emptyMap(),
                )
                val conditional = mapOf(
                    "If-None-Match" to "\"dulcet-conformance\"",
                    "If-Modified-Since" to "Fri, 01 Jan 2100 00:00:00 GMT",
                )
                for ((endpoint, parameters) in reads) {
                    val plain = admin.get(endpoint, parameters)
                    val conditioned = admin.get(endpoint, parameters, conditional)
                    for ((label, wire) in listOf("plain" to plain, "conditional" to conditioned)) {
                        assertEquals(200, wire.status, "CONF-73 $endpoint $label status")
                        // Header positive control on every response: the instrument reads headers.
                        assertNotNull(wire.header("Content-Type"), "CONF-73 $endpoint $label: instrument saw no headers")
                        for (validator in listOf("ETag", "Last-Modified", "Cache-Control")) {
                            assertNull(wire.header(validator), "CONF-73 $endpoint $label carried $validator")
                        }
                    }
                    assertEquals(
                        canonical(plain.envelope(endpoint)),
                        canonical(conditioned.envelope(endpoint)),
                        "CONF-73 $endpoint: the conditional request did not return the full payload",
                    )
                    assertEquals("ok", conditioned.envelope(endpoint).string("status"))
                    if (endpoint == "getAlbumList2") {
                        // The named positive control: the same responses DO carry X-Total-Count.
                        assertEquals(referenceCount.toString(), plain.header("X-Total-Count"), "CONF-73 positive control")
                        assertEquals(referenceCount.toString(), conditioned.header("X-Total-Count"), "CONF-73 positive control")
                    }
                }
                println(
                    "CONF-73 OBSERVED endpoints=${reads.size} validators_seen=0 conditional_status=200 " +
                        "conditional_payload_equal=true x_total_count_seen=true",
                )
            }
        }

    // ---- CONF-74 ------------------------------------------------------------------------------

    @Test
    fun conf74AlbumRemovalUnderPurgeMissingAlways(): TestResult = realTime(3.minutes) {
        val fixture = fixture()
        ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
            fixture.requireIdentity(admin)
            removalTable(admin, fixture, purges = true)
        }
    }

    @Test
    fun conf74AlbumRemovalUnderPurgeMissingServerDefault(): TestResult = realTime(3.minutes) {
        val server = purgeDefaultServer()
        ReaderProbe(server.baseUrl, adminUser(), adminPassword()).use { admin ->
            server.requireIdentity(admin)
            removalTable(admin, server, purges = false)
        }
    }

    private suspend fun removalTable(admin: ReaderProbe, server: DisposableServer, purges: Boolean) {
        val id = if (purges) "CONF-74[always]" else "CONF-74[server-default]"
        admin.awaitIdle(id)
        val album = admin.albumNamed(MUTATED_ALBUM, id)
        val untouched = admin.albumNamed(UNTOUCHED_ALBUM, id).id
        val before = admin.requireOk("getAlbum", mapOf("id" to album.id), id).obj("album")
        val tracks = before.array("song").map { (it as JsonObject).string("id") }
        assertTrue(tracks.isNotEmpty(), "$id control: the album has no songs")
        assertEquals(tracks.size, before.int("songCount"), "$id control: songCount disagrees with the song list")
        val track = tracks.first()
        val trackTitle = admin.requireOk("getSong", mapOf("id" to track), id).obj("song").string("title")
        val streamBefore = admin.get("stream", mapOf("id" to track))
        assertEquals(200, streamBefore.status, "$id control: stream status")
        assertNull(streamBefore.subsonicEnvelopeOrNull(), "$id control: stream of a present track was an envelope")
        assertTrue(streamBefore.body.isNotEmpty(), "$id control: stream returned no bytes")
        assertTrue(album.id in admin.albumIds(), "$id control: album absent from getAlbumList2")
        assertTrue(album.id in admin.searchAlbumIds(MUTATED_ALBUM), "$id control: album absent from search3")

        val inside = server.albumDirectory(MUTATED_ALBUM)
        val outside = server.holdingDirectory(MUTATED_ALBUM)
        val removal = moveAndAwaitScan(admin, inside, outside, "$id removal")
        val observed = mutableListOf<String>()
        try {
            // Lists and search3 drop the album under both settings; the untouched album is the
            // positive control that the same list and search instruments still return rows.
            val listed = admin.albumIds()
            assertFalse(album.id in listed, "$id mutation did not fire: album still in getAlbumList2")
            assertTrue(untouched in listed, "$id positive control: untouched album vanished from getAlbumList2")
            assertFalse(album.id in admin.searchAlbumIds(MUTATED_ALBUM), "$id album still in search3")
            assertTrue(untouched in admin.searchAlbumIds(UNTOUCHED_ALBUM), "$id positive control: search3 found nothing")

            val albumAfter = admin.envelope("getAlbum", mapOf("id" to album.id))
            val songAfter = admin.envelope("getSong", mapOf("id" to track))
            val streamAfter = admin.get("stream", mapOf("id" to track))
            assertEquals(200, streamAfter.status, "$id removed-track stream status")
            val streamEnvelope = assertNotNull(streamAfter.subsonicEnvelopeOrNull(), "$id removed-track stream was not an envelope")
            assertEquals("failed", streamEnvelope.string("status"), "$id removed-track stream envelope status")
            val streamCode = streamEnvelope.obj("error").int("code")
            if (purges) {
                assertEquals(70, albumAfter.errorCode(), "$id getAlbum of a removed album")
                assertEquals(70, songAfter.errorCode(), "$id getSong of a removed track")
                assertEquals(70, streamCode, "$id stream of a removed track")
            } else {
                assertEquals("ok", albumAfter.string("status"), "$id getAlbum of a removed album")
                val kept = albumAfter.obj("album")
                assertEquals(tracks.size, kept.int("songCount"), "$id songCount after removal")
                assertEquals(0, (kept["song"] as? JsonArray)?.size ?: 0, "$id removed album still lists songs")
                assertEquals("ok", songAfter.string("status"), "$id getSong of a removed track")
                assertEquals(trackTitle, songAfter.obj("song").string("title"), "$id getSong metadata")
                assertEquals(0, streamCode, "$id stream of a removed track: expected the generic code")
            }
            observed += "getAlbum=${albumAfter.describe()} getSong=${songAfter.describe()} stream=envelope:$streamCode"
        } finally {
            restoreAndAwaitScan(admin, outside, inside, "$id restore")
        }

        val restoredAlbum = admin.albumNamed(MUTATED_ALBUM, "$id restore")
        assertEquals(album.id, restoredAlbum.id, "$id the restored album has a different id")
        val restoredTracks = admin.requireOk("getAlbum", mapOf("id" to album.id), id).obj("album")
            .array("song").map { (it as JsonObject).string("id") }
        assertEquals(tracks.size, restoredTracks.size, "$id restored album song count")
        val oldTrack = admin.envelope("getSong", mapOf("id" to track))
        if (purges) {
            assertEquals(70, oldTrack.errorCode(), "$id the old track id still resolves after purge and restore")
            assertTrue(restoredTracks.none { it in tracks }, "$id restore reused a purged track id")
        } else {
            assertEquals("ok", oldTrack.string("status"), "$id the old track id no longer resolves")
            assertEquals(tracks.toSet(), restoredTracks.toSet(), "$id restore changed track ids")
        }
        println(
            "$id OBSERVED songs=${tracks.size} removal_scan_polls=${removal.polls} after_removal: " +
                "${observed.joinToString()} lists_and_search_dropped=true restored_album_id_same=true " +
                "old_track_resolves=${oldTrack.string("status") == "ok"}",
        )
    }

    // ---- CONF-75 ------------------------------------------------------------------------------

    @Test
    fun conf75TotalCountHeaderPresencePerAlbumListType(): TestResult = realTime(2.minutes) {
        val fixture = fixture()
        ReaderProbe(fixture.baseUrl, adminUser(), adminPassword()).use { admin ->
            fixture.requireIdentity(admin)
            val albums = admin.albumIds().size
            assertTrue(albums > 3, "CONF-75 needs more albums than the short page it reads")
            // The corpus is tagged without genres, which decides what byGenre can show here.
            assertTrue(
                (admin.requireOk("getGenres", emptyMap(), "CONF-75").obj("genres")["genre"] as? JsonArray)
                    .isNullOrEmpty(),
                "CONF-75 the corpus now carries genres; pin byGenre against a real genre's albumCount",
            )
            val wholeLibrary = listOf(
                mapOf("type" to "alphabeticalByName"),
                mapOf("type" to "alphabeticalByArtist"),
                mapOf("type" to "newest"),
                mapOf("type" to "random"),
                mapOf("type" to "byYear", "fromYear" to "0", "toYear" to "9999"),
            )
            val observed = mutableListOf<String>()
            for (list in wholeLibrary) {
                val short = admin.get("getAlbumList2", list + mapOf("size" to SHORT_PAGE.toString()))
                assertEquals(SHORT_PAGE, short.envelope("getAlbumList2").albumRows().size, "CONF-75 ${list["type"]} short page")
                assertEquals(
                    albums.toString(),
                    short.header("X-Total-Count"),
                    "CONF-75 ${list["type"]}: X-Total-Count must be the whole list's total, not the page's length",
                )
                observed += "${list["type"]}=${short.header("X-Total-Count")}"
            }
            val filtered = listOf(
                mapOf("type" to "byYear", "fromYear" to "1000", "toYear" to "1001"),
                mapOf("type" to "byGenre", "genre" to "Dulcet Conformance Absent Genre"),
                mapOf("type" to "recent"),
                mapOf("type" to "frequent"),
                mapOf("type" to "highest"),
                mapOf("type" to "starred"),
            )
            for (list in filtered) {
                val wire = admin.get("getAlbumList2", list + mapOf("size" to "500"))
                val rows = wire.envelope("getAlbumList2").albumRows().size
                assertEquals(
                    rows.toString(),
                    wire.header("X-Total-Count"),
                    "CONF-75 ${list["type"]}: X-Total-Count must be present and count this user's list",
                )
                observed += "${list["type"]}=${wire.header("X-Total-Count")}"
            }
            // Absence on search3, with the positive control that the same lookup saw it above.
            val search = admin.get("search3", mapOf("query" to "Dulcet"))
            assertEquals(200, search.status)
            assertNotNull(search.header("Content-Type"), "CONF-75 instrument saw no search3 headers")
            assertNull(search.header("X-Total-Count"), "CONF-75 search3 unexpectedly carries X-Total-Count")
            println("CONF-75 OBSERVED albums=$albums ${observed.joinToString(" ")} search3=absent")
        }
    }

    // ---- shared machinery -----------------------------------------------------------------------

    private fun realTime(timeout: Duration, block: suspend () -> Unit): TestResult =
        runTest(timeout = timeout) {
            // Scans and the watcher run in real time; the test dispatcher's virtual clock would
            // turn every wait below into a no-op.
            withContext(Dispatchers.Default) { block() }
        }

    private fun fixture(): DisposableServer = DisposableServer(
        label = "fixture",
        baseUrl = disposableConformanceBaseUrl(),
        musicDirectory = requiredEnvironment("DULCET_CONFORMANCE_MUSIC_DIR"),
        requiredAlbums = setOf(MUTATED_ALBUM, UNTOUCHED_ALBUM, "Paging Atlas", USER_STATE_ALBUM_CONF71),
        exactAlbums = false,
    ).also { server ->
        assertTrue(
            isFile("${server.musicDirectory}/corpus-manifest.json"),
            "DULCET_CONFORMANCE_MUSIC_DIR is not the generated corpus the fixture server scans",
        )
        server.prepareHolding()
    }

    private fun purgeDefaultServer(): DisposableServer {
        disposableConformanceBaseUrl()
        val baseUrl = requiredEnvironment("DULCET_CONFORMANCE_PURGE_DEFAULT_BASE_URL")
        check(Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}").matches(baseUrl)) {
            "the purge-default server is restricted to a loopback HTTP server"
        }
        check(baseUrl != disposableConformanceBaseUrl()) {
            "the purge-default server must not be the fixture server"
        }
        return DisposableServer(
            label = "purge-default",
            baseUrl = baseUrl,
            musicDirectory = requiredEnvironment("DULCET_CONFORMANCE_PURGE_DEFAULT_MUSIC_DIR"),
            requiredAlbums = setOf(MUTATED_ALBUM, UNTOUCHED_ALBUM),
            exactAlbums = true,
        ).also { it.prepareHolding() }
    }

    private inner class DisposableServer(
        val label: String,
        val baseUrl: String,
        val musicDirectory: String,
        private val requiredAlbums: Set<String>,
        private val exactAlbums: Boolean,
    ) {
        private val holding = "$musicDirectory-reader-holding"

        fun albumDirectory(album: String) = "$musicDirectory/$album"
        fun holdingDirectory(album: String) = "$holding/$album"

        fun prepareHolding() {
            createDirectories(holding)
            for (album in listOf(MUTATED_ALBUM)) {
                // A previous run that died between its move and its restore leaves the album out.
                if (!isDirectory(albumDirectory(album)) && isDirectory(holdingDirectory(album))) {
                    moveDirectory(holdingDirectory(album), albumDirectory(album))
                }
                assertTrue(isDirectory(albumDirectory(album)), "$label music folder has no '$album' directory")
            }
        }

        /** Which server this is: a failure here names a wrong setup, not a wrong server fact. */
        suspend fun requireIdentity(probe: ReaderProbe) {
            val names = probe.albumNames().toSet()
            if (exactAlbums) {
                assertEquals(requiredAlbums, names, "$label server is not the one this test expects")
            } else {
                assertTrue(names.containsAll(requiredAlbums), "$label server lacks ${requiredAlbums - names}")
            }
        }
    }

    private suspend fun moveAndAwaitScan(
        probe: ReaderProbe,
        source: String,
        destination: String,
        what: String,
    ): ScanReading {
        val before = probe.awaitIdle("$what (before)")
        moveDirectory(source, destination)
        assertTrue(isDirectory(destination) && !isDirectory(source), "$what: the directory did not move")
        return probe.awaitScanAfter(before, what)
    }

    private suspend fun restoreAndAwaitScan(probe: ReaderProbe, source: String, destination: String, what: String) {
        if (isDirectory(source) && !isDirectory(destination)) {
            moveAndAwaitScan(probe, source, destination, what)
        }
    }

    private fun requireRealStamp(id: String, reading: ScanReading) {
        assertNotNull(reading.lastScan, "$id precondition: the fixture server reports no scan stamp")
        assertNotEquals(SENTINEL_LAST_SCAN, reading.lastScan, "$id precondition: the fixture server has not scanned")
    }

    private suspend fun ReaderProbe.readWindow(): WindowRead {
        val pages = mutableListOf<List<String>>()
        val after = mutableListOf<ScanReading>()
        var offset = 0
        while (true) {
            val ids = albumPage(offset, WINDOW_PAGE_SIZE)
            after += scanStatus()
            if (ids.isEmpty()) break
            pages += ids
            offset += ids.size
            check(pages.size < 1_000) { "the paged read never reached an empty page" }
        }
        return WindowRead(pages, after)
    }

    private class WindowRead(val pages: List<List<String>>, val afterReadings: List<ScanReading>) {
        val ids: List<String> get() = pages.flatten()
    }

    /** One page read with the scan status read before its request and after its response. */
    internal data class RaceSample(val contents: List<String>, val before: ScanReading, val after: ScanReading) {
        /** §16.12's acceptance: both readings idle and showing one stamp. */
        val accepted: Boolean
            get() = !before.scanning && !after.scanning && before.lastScan == after.lastScan
    }

    private fun raceViolations(samples: List<RaceSample>): Set<String?> =
        stampsWithTwoContents(samples.filter(RaceSample::accepted))

    // OBSERVED 2026-09-22 under PurgeMissing = "always": a page read while a purging scan was
    // running returned the pre-purge list, and the getScanStatus issued after it blocked ~650 ms
    // and then reported the scan finished under the NEW stamp. Judged by its after-reading alone,
    // that page belongs to a stamp it predates; its before-reading showed the scan (spec §16.12).
    private fun afterOnlyViolations(samples: List<RaceSample>): Set<String?> =
        stampsWithTwoContents(samples.filterNot { it.after.scanning })

    private fun stampsWithTwoContents(samples: List<RaceSample>): Set<String?> =
        samples.groupBy { it.after.lastScan }
            .filterValues { group -> group.map(RaceSample::contents).toSet().size > 1 }
            .keys

    private fun describeRace(samples: List<RaceSample>, stamp: String?): String =
        samples.withIndex().filter { (_, sample) -> sample.accepted && sample.after.lastScan == stamp }
            .groupBy({ it.value.contents.size }, { it.index })
            .entries.joinToString(prefix = "[", postfix = "]") { (rows, indices) ->
                "rows=$rows samples=${indices.first()}..${indices.last()} (${indices.size})"
            }

    internal companion object {
        const val RESTRICTED_USER = "dulcet-restricted"
        const val RESTRICTED_PASSWORD = "dulcet-ci-restricted-password"
        const val SENTINEL_LAST_SCAN = "0001-01-01T00:00:00Z"
        const val MUTATED_ALBUM = "Double Lines"
        const val UNTOUCHED_ALBUM = "Long Titles"
        const val USER_STATE_ALBUM_CONF71 = "Several Album Artists"
        const val USER_STATE_ALBUM_CONF72 = "Long Titles"
        const val WINDOW_PAGE_SIZE = 2
        const val SHORT_PAGE = 3
        val RACE_DURATION = 30.seconds
        val RACE_TOGGLE_INTERVAL = 6.seconds
        val WATCHER_QUIET_WINDOW = 8.seconds
        val RACE_SAMPLE_PAUSE = 20.milliseconds

        fun adminUser(): String = environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: "dulcet-admin"
        fun adminPassword(): String =
            environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: "dulcet-ci-canary-password"
    }
}

internal data class ScanReading(
    val lastScan: String?,
    val scanning: Boolean,
    val count: Int?,
    val polls: Int = 0,
)

internal data class AlbumSummary(val id: String, val name: String)

internal data class AlbumUserState(
    val starred: String?,
    val userRating: Int?,
    val playCount: Int,
    val played: String?,
)

internal class Wire(val status: Int, private val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun subsonicEnvelopeOrNull(): JsonObject? {
        val text = body.decodeToString().trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!text.startsWith("{")) return null
        return try {
            (Json.parseToJsonElement(text) as? JsonObject)?.get("subsonic-response") as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun envelope(endpoint: String): JsonObject {
        assertEquals(200, status, "$endpoint HTTP status")
        return subsonicEnvelopeOrNull() ?: fail("$endpoint did not return a Subsonic JSON envelope")
    }

    override fun toString(): String = "Wire(status=$status, bytes=${body.size})"
}

/** A minimal `/rest` client. Query strings carry credentials, so nothing here ever renders a URL. */
internal class ReaderProbe(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) : AutoCloseable {
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false
    }
    private val salts = AccountConnectionContract.secureSaltSource()

    suspend fun get(
        endpoint: String,
        parameters: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Wire {
        val salt = salts.nextSalt()
        val response = try {
            client.get("$baseUrl/rest/$endpoint.view") {
                parameter("u", username)
                parameter("t", AccountConnectionContract.saltedToken(password, salt))
                parameter("s", salt)
                parameter("v", "1.16.1")
                parameter("c", "dulcet-conformance")
                parameter("f", "json")
                parameters.forEach { (name, value) -> parameter(name, value) }
                headers.forEach { (name, value) -> header(name, value) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            throw AssertionError("$endpoint request failed before a response: ${failure::class.simpleName}")
        }
        val collected = response.headers.entries().associate { (name, values) ->
            name.lowercase() to values.joinToString(",")
        }
        return Wire(response.status.value, collected, response.bodyAsBytes())
    }

    suspend fun envelope(endpoint: String, parameters: Map<String, String>): JsonObject =
        get(endpoint, parameters).envelope(endpoint)

    suspend fun requireOk(endpoint: String, parameters: Map<String, String>, id: String): JsonObject {
        val envelope = envelope(endpoint, parameters)
        assertEquals("ok", envelope.string("status"), "$id $endpoint failed: ${envelope.describe()}")
        return envelope
    }

    suspend fun scanStatus(): ScanReading {
        val status = requireOk("getScanStatus", emptyMap(), "scan status").obj("scanStatus")
        return ScanReading(
            lastScan = (status["lastScan"] as? JsonPrimitive)?.contentOrNull,
            scanning = (status["scanning"] as? JsonPrimitive)?.booleanOrNull
                ?: fail("getScanStatus omitted scanning"),
            count = (status["count"] as? JsonPrimitive)?.intOrNull,
        )
    }

    suspend fun awaitIdle(what: String): ScanReading = poll(what) { !it.scanning }

    /** Idle with one unchanged stamp for [window]; any scan inside the window restarts it. */
    suspend fun awaitQuiet(what: String, window: Duration): ScanReading {
        var stable = awaitIdle(what)
        var since = TimeSource.Monotonic.markNow()
        val overall = TimeSource.Monotonic.markNow()
        while (since.elapsedNow() < window) {
            if (overall.elapsedNow() > SCAN_TIMEOUT) fail("$what: the server never went quiet; last=$stable")
            delay(SCAN_POLL * 5)
            val reading = scanStatus()
            if (reading.scanning || reading.lastScan != stable.lastScan) {
                stable = if (reading.scanning) awaitIdle(what) else reading
                since = TimeSource.Monotonic.markNow()
            }
        }
        return stable
    }

    /** A scan has run since [before] and finished: the stamp moved and nothing is scanning. */
    suspend fun awaitScanAfter(before: ScanReading, what: String): ScanReading =
        poll("$what: the server's scan never ran or never finished") {
            !it.scanning && it.lastScan != before.lastScan
        }

    private suspend fun poll(what: String, done: (ScanReading) -> Boolean): ScanReading {
        val mark = TimeSource.Monotonic.markNow()
        var polls = 0
        while (true) {
            polls += 1
            val reading = scanStatus()
            if (done(reading)) return reading.copy(polls = polls)
            if (mark.elapsedNow() > SCAN_TIMEOUT) fail("$what: polls=$polls elapsed=${mark.elapsedNow()} last=$reading")
            delay(SCAN_POLL)
        }
    }

    suspend fun albumPage(offset: Int, size: Int): List<String> =
        requireOk(
            "getAlbumList2",
            mapOf("type" to "alphabeticalByName", "size" to size.toString(), "offset" to offset.toString()),
            "album page",
        ).albumRows().map { it.string("id") }

    suspend fun albumIds(): List<String> = albumPage(0, 500)

    suspend fun albumNames(): List<String> =
        requireOk("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"), "album names")
            .albumRows().map { it.string("name") }

    suspend fun albumNamed(name: String, id: String): AlbumSummary {
        val rows = requireOk("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"), id)
            .albumRows().filter { it.string("name") == name }
        assertEquals(1, rows.size, "$id expected exactly one album named '$name'")
        return AlbumSummary(rows.single().string("id"), name)
    }

    suspend fun firstTrackId(albumId: String, id: String): String =
        requireOk("getAlbum", mapOf("id" to albumId), id).obj("album").array("song")
            .map { (it as JsonObject).string("id") }.firstOrNull() ?: fail("$id album has no songs")

    suspend fun searchAlbumIds(query: String): List<String> =
        ((requireOk("search3", mapOf("query" to query), "search3").obj("searchResult3")["album"] as? JsonArray)
            ?: JsonArray(emptyList())).map { (it as JsonObject).string("id") }

    /** The per-user fields as both the list and the detail carry them; they must agree. */
    suspend fun albumState(albumId: String): AlbumUserState {
        val listed = requireOk("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"), "state")
            .albumRows().single { it.string("id") == albumId }
        val detail = requireOk("getAlbum", mapOf("id" to albumId), "state").obj("album")
        val fromList = listed.userState()
        val fromDetail = detail.userState()
        assertEquals(fromList, fromDetail, "getAlbumList2 and getAlbum disagree on per-user state")
        return fromList
    }

    override fun close() = client.close()
}

private fun JsonObject.userState() = AlbumUserState(
    starred = (this["starred"] as? JsonPrimitive)?.contentOrNull,
    userRating = (this["userRating"] as? JsonPrimitive)?.intOrNull,
    playCount = (this["playCount"] as? JsonPrimitive)?.intOrNull ?: 0,
    played = (this["played"] as? JsonPrimitive)?.contentOrNull,
)

private fun JsonObject.albumRows(): List<JsonObject> =
    ((obj("albumList2")["album"] as? JsonArray) ?: JsonArray(emptyList())).map { it as JsonObject }

private fun JsonObject.obj(name: String): JsonObject =
    this[name] as? JsonObject ?: fail("response omitted object $name")

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: fail("response omitted array $name")

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: fail("response omitted string $name")

private fun JsonObject.int(name: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull ?: fail("response omitted integer $name")

private fun JsonObject.errorCode(): Int? =
    ((this["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull

private fun JsonObject.describe(): String {
    val status = (this["status"] as? JsonPrimitive)?.contentOrNull
    return if (status == "ok") "ok" else "$status:${errorCode()}"
}

/**
 * A parsed value with object keys and array elements put in a canonical order. Compared instead of
 * bytes because the server varies array order between identical reads (OBSERVED: the `roles` of an
 * artist in `getArtists` and `search3`), which is not a content change.
 */
private fun canonical(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) -> "\"$key\":${canonical(value)}" }
    is JsonArray -> element.map(::canonical).sorted().joinToString(",", "[", "]")
    is JsonPrimitive -> element.toString()
}

private val SCAN_TIMEOUT = 90.seconds
private val SCAN_POLL = 100.milliseconds
