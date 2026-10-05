package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONF-82 and CONF-87 (spec §16.11–§16.13), each proven by ONE test through the production
 * [LibraryReader] against the scripted [FakeReaderServer], so a platform's evidence can cite one
 * test per id. The disposable server's corpus cannot reach these rules — eight albums never fill a
 * second page of a window paged at 100, and its scans cannot be stopped at the sentinel — so a
 * scripted server drives them, as spec R1b requires: request counts and orders asserted, never wall
 * time.
 *
 * Every clause runs in its own fresh server, database and reader ([withReaderEnv]), so one clause
 * can never set up another. Every clause runs even when an earlier one fails, and the test's single
 * failure names each failing clause.
 *
 * | CONF-82 clause (spec) | scenario in [conf82ReaderTearsRebasesTheViewportAppendsWhileScanningKeepsTheAnchorReadsNoStampAsNoEpochAFailedReadAsUnreadAndNoTotalAsUnknown] |
 * |---|---|
 * | tears on a fired check (§16.12 rule 1) | `82.1` |
 * | tears on a stored-epoch mismatch at the first live read (rule 2) | `82.2a` (extend first), `82.2b` (open), `82.2c` (the extend is the first live read) |
 * | tears on a folder-set change (rule 3) | `82.3`, control `82.3c` |
 * | rebases only the viewport's pages | `82.4` |
 * | appends unguarded while scanning, rebases when the scan ends | `82.5a` (by an extend, stamp unchanged), `82.5b` (by a reconnect), `82.5c` (the scan ends inside the first page's bracket), `82.5d` (another list saw the scan end, its re-read failed), `82.5e` (another list saw the scan end: the grid and an album re-read at once) |
 * | keeps the anchor by id | `82.4` (by id), `82.6` (nearest surviving predecessor) |
 * | the sentinel and an absent `lastScan` are no epoch whatever `scanning` says | `82.7` × 4, control `82.7c` |
 * | a failed `getScanStatus` is unread — never no epoch, never unchanged | `82.8a` (extend), `82.8b` (scanning window), `82.8c` (connect), `82.8d` (rebase) |
 * | a window without `X-Total-Count` has an unknown total | `82.9`, control `82.9c` |
 *
 * | CONF-87 clause (spec §16.13) | scenario in [conf87LookAheadStaysWithin24PerSettledViewportAnd2InFlightFetchesNothingWhileFlingingOrConstrainedAndALookedAheadAlbumOpensWithZeroRequests] |
 * |---|---|
 * | never more than 24 per settled viewport, nor 2 in flight | `87.1`; overlapping settles `87.2` |
 * | nothing during an unsettled fling | `87.3` (with its settle control) |
 * | nothing on a constrained network | `87.4a` (before a settle, with control), `87.4b` (reads in flight) |
 * | a looked-ahead album opens with zero requests | `87.5` |
 */
class ReaderWindowConformanceTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    // ==== CONF-82 =====================================================================================

    @Test
    fun conf82ReaderTearsRebasesTheViewportAppendsWhileScanningKeepsTheAnchorReadsNoStampAsNoEpochAFailedReadAsUnreadAndNoTotalAsUnknown() = conformance("CONF-82") {
        // ---- 82.1 Tear rule 1: a fired check -------------------------------------------------------
        // A scan removes album-0005 (before the cursor) while page 1 is read, so the server's page 1
        // is shifted by one: stitched to the old page 0 it would silently skip album-0100 (§16.1).
        clause("82.1 a fired check tears the window") { env ->
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            handle.setViewport(90, 99)
            env.server.beforeRespond = scanRemovingAlbumFiveDuringPage(env.server, offset = "100")

            val before = env.server.log.size
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(
                listOf(PAGE_100, STATUS, PAGE_0, STATUS), // fired -> the viewport's page re-read, never appended
                env.server.log.drop(before).map(Any::toString),
            )
            val survivors = env.server.albums.map { it.id }
            assertEquals(survivors.take(100), pubs.ids(), "the torn page was appended")
            assertEquals(catalogEpochKey(STAMP_AFTER_SCAN, setOf("folder-1")), listState(env)?.windowEpoch)

            // Paging on from the rebased window yields every surviving album exactly once.
            handle.setViewport(90, 99)
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(survivors.take(200), pubs.ids(), "a window read across a scan skipped or duplicated an album")
            pubs.all.forEach { assertConsistent(it.publication) }
        }

        // ---- 82.2 Tear rule 2: the stored window epoch differs at the first live read --------------
        clause("82.2a a stale stored window is rebased before it is extended") { env ->
            loadTwoPages(env)
            env.server.lastScan = STAMP_AFTER_SCAN
            env.clock.now += 3_600_000

            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            assertEquals(200, pubs.last.items.size, "the cached window paints first")
            handle.setViewport(90, 99)
            val before = env.server.log.size
            handle.loadMore() // before the first live read: must rebase, never stitch today's page 2 on
            advanceUntilIdle()
            assertEquals(
                listOf(PAGE_0, STATUS, PAGE_100, STATUS), // the rebase, then the extension
                env.server.log.drop(before).map(Any::toString),
                "a stale window was extended before it was rebased",
            )
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP_AFTER_SCAN, setOf("folder-1")), window.windowEpoch)
            assertEquals(0, window.firstLoadedOffset)
            assertEquals(200, window.endLoadedOffset)
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        }
        clause("82.2b a stale stored window opened is rebased to its viewport page") { env ->
            loadTwoPages(env)
            env.server.lastScan = STAMP_AFTER_SCAN
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val before = env.server.log.size
            val pubs = Publications(env.server)
            reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(listOf(PAGE_0, STATUS), env.server.log.drop(before).map(Any::toString))
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP_AFTER_SCAN, setOf("folder-1")), window.windowEpoch)
            assertEquals(100, window.endLoadedOffset, "the page read under the old epoch was kept")
            assertEquals(100, pubs.last.items.size)
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        }

        clause("82.2c an extend that is the window's first live read (its open's read failed) rebases first") { env ->
            loadTwoPages(env)
            env.server.lastScan = STAMP_AFTER_SCAN
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            env.server.failing += "getAlbumList2" // the open's read fails: nothing is read live yet
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(catalogEpochKey(STAMP, setOf("folder-1")), listState(env)?.windowEpoch, "fixture: still last session's window")
            env.server.failing.clear()
            handle.setViewport(190, 199)
            val before = env.server.log.size
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(
                listOf(PAGE_100, STATUS, PAGE_200, STATUS), // the rebase around the viewport, then the extension
                env.server.log.drop(before).map(Any::toString),
                "a stale window was extended at its first live read",
            )
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP_AFTER_SCAN, setOf("folder-1")), window.windowEpoch)
            assertEquals(100, window.firstLoadedOffset)
            assertEquals(250, window.endLoadedOffset)
        }

        // ---- 82.3 Tear rule 3: the folder set changed, the stamp did not ---------------------------
        clause("82.3 a folder-set change tears the window") { env ->
            loadTwoPages(env)
            env.server.folders = listOf("folder-1", "folder-2")
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val before = env.server.log.size
            reader.open(grid, Publications(env.server))
            advanceUntilIdle()
            assertEquals(listOf(PAGE_0, STATUS), env.server.log.drop(before).map(Any::toString))
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP, setOf("folder-1", "folder-2")), window.windowEpoch)
            assertEquals(100, window.endLoadedOffset, "a folder-set change must rebase, dropping page 1")
        }
        clause("82.3c control: an unchanged epoch revalidates in place and keeps every page") { env ->
            loadTwoPages(env)
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            reader.open(grid, Publications(env.server))
            advanceUntilIdle()
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP, setOf("folder-1")), window.windowEpoch)
            assertEquals(200, window.endLoadedOffset)
        }

        // ---- 82.4 A rebase re-reads only the viewport's pages, and the anchor is kept by id --------
        clause("82.4 a rebase reads only the viewport's pages and keeps the anchor by id") { env ->
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            repeat(2) {
                handle.setViewport(pubs.last.items.size - 10, pubs.last.items.size - 1)
                handle.loadMore()
                advanceUntilIdle()
            }
            assertEquals(250, pubs.last.items.size, "fixture: three pages loaded")
            handle.setViewport(150, 160) // album-0150..album-0160, inside page 1 only
            env.server.albums.removeAt(5)
            env.server.lastScan = STAMP_AFTER_SCAN

            val before = env.server.log.size
            reader.reconnect()
            advanceUntilIdle()
            val pageReads = env.server.log.drop(before).filter { it.endpoint == "getAlbumList2" }.map(Any::toString)
            assertEquals(listOf(PAGE_100), pageReads, "a rebase read more than the viewport's page")
            val window = assertNotNull(listState(env))
            assertEquals(100, window.firstLoadedOffset, "pages outside the viewport were kept")
            assertEquals(200, window.endLoadedOffset, "pages outside the viewport were kept")
            val published = pubs.last
            assertEquals(env.server.albums.map { it.id }.subList(100, 200), pubs.ids(published))
            assertEquals(100, published.leadingOffset)
            val anchor = assertNotNull(published.anchor, "a rebase published no anchor")
            assertEquals(albumId(150), anchor.itemRawId, "the first visible item must stay first")
            assertEquals(published.items.indexOfFirst { it.rawId == albumId(150) }, anchor.index)
        }

        // ---- 82.5 Scanning mode --------------------------------------------------------------------
        // The server publishes the new stamp BEFORE it clears `scanning` (§16.11), so the reading
        // that ends a scan can show an UNCHANGED stamp: only "scanning cleared" can trigger the rebase.
        clause("82.5a pages append unguarded while scanning; the scan's end rebases under an unchanged stamp") { env ->
            env.server.scanning = true
            env.server.lastScan = STAMP_AFTER_SCAN // already published, the scan still running
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage, "a window opened mid-scan was presented as guarded")
            handle.setViewport(90, 99)
            val scanningBefore = env.server.log.size
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(PAGE_100, STATUS), env.server.log.drop(scanningBefore).map(Any::toString))
            assertEquals((0 until 200).map(::albumId), pubs.ids(), "scanning must not freeze scrolling")
            assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)

            env.server.scanning = false // the stamp does not move again
            handle.setViewport(190, 199)
            val before = env.server.log.size
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(
                listOf(PAGE_200, STATUS, PAGE_100, STATUS),
                env.server.log.drop(before).map(Any::toString),
                "the scan's end must rebase around the viewport, not append",
            )
            assertEquals(LibraryCoverage.Open, pubs.last.coverage)
            assertEquals((100 until 200).map(::albumId), pubs.ids())
            assertEquals(100, pubs.last.leadingOffset)
            // Open on both sides: the top is reachable again under the same check.
            val beforeTop = env.server.log.size
            handle.loadBefore()
            advanceUntilIdle()
            assertEquals(listOf(PAGE_0, STATUS), env.server.log.drop(beforeTop).map(Any::toString))
            assertEquals((0 until 200).map(::albumId), pubs.ids())
            assertEquals(LibraryCoverage.Open, pubs.last.coverage)
        }
        clause("82.5b a reconnect that sees the scan ended rebases a window read while scanning") { env ->
            env.server.scanning = true
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)

            env.server.scanning = false
            // Within the 60-second interval: it never spares a still-unverified window this rebase.
            env.clock.now += 1_000
            val before = env.server.log.size
            reader.reconnect()
            advanceUntilIdle()
            assertEquals(listOf(PAGE_0), env.server.log.drop(before).filter { it.endpoint == "getAlbumList2" }.map(Any::toString))
            assertEquals(LibraryCoverage.Open, pubs.last.coverage, "the label must clear once no scan is running")
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        }

        clause("82.5c a scan that ends while the first page is read re-reads it, even under an unchanged stamp") { env ->
            // The stamp is already the scan's (published before `scanning` clears): the page's
            // BEFORE showed a scan and its AFTER does not, so the page may predate the scan's change.
            env.server.scanning = true
            env.server.lastScan = STAMP_AFTER_SCAN
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            var ended = false
            env.server.beforeRespond = { request ->
                if (!ended && request.endpoint == "getAlbumList2") {
                    ended = true
                    env.server.albums.removeAll { it.id == albumId(5) } // the scan's change
                    env.server.scanning = false
                }
            }
            val before = env.server.log.size
            val pubs = Publications(env.server)
            reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(listOf(PAGE_0, STATUS, PAGE_0, STATUS), env.server.log.drop(before).map(Any::toString), "a page whose scan ended mid-read was kept")
            assertEquals(env.server.albums.map { it.id }.take(100), pubs.ids())
            assertEquals(LibraryCoverage.Open, pubs.last.coverage)
        }

        clause("82.5d a window read while scanning is rebased by its next page even when another list saw the scan end") { env ->
            env.server.scanning = true
            env.server.lastScan = STAMP_AFTER_SCAN
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)
            // Another list's read observes the scan's end, so this window's next page is bracketed
            // by two idle readings under its own stamp: guarded on its own, yet the window holds
            // unguarded pages, so it must rebase rather than append. The re-read that end starts
            // (82.5e) fails here, so this window still holds them when the person scrolls.
            env.server.scanning = false
            env.server.failing += "getAlbumList2[type=alphabeticalByName]"
            reader.open(LibraryQuery.AlbumList(AlbumListType.Newest), Publications(env.server))
            advanceUntilIdle()
            env.server.failing.clear()
            handle.setViewport(90, 99)
            val before = env.server.log.size
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(
                listOf(PAGE_100, STATUS, PAGE_0, STATUS),
                env.server.log.drop(before).map(Any::toString),
                "a guarded page was appended to a window of unguarded ones",
            )
            assertEquals((0 until 100).map(::albumId), pubs.ids())
            assertEquals(LibraryCoverage.Open, pubs.last.coverage)
        }

        clause("82.5e when another list's read is the first to see the scan end, what was read during the scan is re-read at once") { env ->
            env.server.scanning = true
            env.server.lastScan = STAMP_AFTER_SCAN
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            reader.open(grid, pubs)
            val album = Publications(env.server)
            reader.open(LibraryQuery.Album(albumId(3)), album)
            advanceUntilIdle()
            assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage, "fixture: the grid was read during the scan")
            assertEquals(LibraryCoverage.UnverifiedScanning, album.last.coverage, "fixture: the album was read during the scan")

            env.server.scanning = false
            val before = env.server.log.size
            reader.open(LibraryQuery.AlbumList(AlbumListType.Newest), Publications(env.server))
            advanceUntilIdle()
            // Newest reads its page twice: its first page's bracket opened during the scan, so it
            // rebases itself (82.5c). The grid and the album are re-read once each, unprompted; the
            // two screens' reads interleave as the scheduler runs them, so they are compared as a set.
            val newest = "getAlbumList2[offset=0][type=newest]"
            assertEquals(
                listOf(newest, STATUS, newest, STATUS, PAGE_0, STATUS, "getAlbum[id=${albumId(3)}]").sorted(),
                env.server.log.drop(before).map(Any::toString).sorted(),
                "the screens read during the scan waited for a reading that sees no scanning change",
            )
            assertEquals(LibraryCoverage.Open, pubs.last.coverage)
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
            assertNull(album.last.coverage, "the album still says the server is updating")

            // Nothing is left for the epoch cadence: its reading finds both screens current.
            env.clock.now += reader.config.epochIntervalMillis
            val cadence = env.server.log.size
            reader.refreshEpoch()
            advanceUntilIdle()
            assertEquals(listOf("getMusicFolders", "getScanStatus"), env.server.log.drop(cadence).map { it.endpoint }.sorted())
        }

        // ---- 82.6 Anchor: the first visible item is gone -------------------------------------------
        clause("82.6 when the first visible item is gone the nearest surviving predecessor anchors") { env ->
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            handle.setViewport(40, 50)
            env.server.albums.removeAll { it.id == albumId(40) }
            env.server.lastScan = STAMP_AFTER_SCAN
            reader.reconnect()
            advanceUntilIdle()
            assertEquals(LibraryAnchor(albumId(39), 39), pubs.last.anchor)
        }

        // ---- 82.7 The sentinel and an absent lastScan are no epoch, whatever `scanning` says -------
        for (lastScan in listOf(SENTINEL, null)) {
            for (scanning in listOf(false, true)) {
                clause("82.7 lastScan=${lastScan ?: "absent"} scanning=$scanning reads as no epoch") { env ->
                    noStampReadsAsNoEpoch(env, lastScan, scanning, expectNoEpoch = true)
                }
            }
        }
        clause("82.7c control: a real stamp is an epoch") { env ->
            noStampReadsAsNoEpoch(env, STAMP, scanning = false, expectNoEpoch = false)
        }

        // ---- 82.8 A failed getScanStatus is UNREAD: never no epoch, never unchanged ----------------
        clause("82.8a a failed after-reading on an extend of a guarded window concludes nothing") { env ->
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            handle.setViewport(90, 99)
            env.server.failing += "getScanStatus"
            val before = env.server.log.size
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(PAGE_100, STATUS), env.server.log.drop(before).map(Any::toString))
            assertEquals(100, pubs.last.items.size, "a page whose after-reading failed was used (read as unchanged)")
            assertEquals(LibraryCoverage.Open, pubs.last.coverage, "a failed reading relabelled the window")
            assertFalse(reader.serverReportsNoEpoch, "a failed reading was taken for a server with no stamp")
            val cached = assertIs<LibraryFreshness.Cached>(pubs.last.freshness, "the failure is not shown")
            assertIs<LibraryCachedReason.Failed>(cached.reason)
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP, setOf("folder-1")), window.windowEpoch)
            assertEquals(100, window.endLoadedOffset)

            // The next successful reading concludes normally.
            env.server.failing.clear()
            handle.setViewport(90, 99)
            handle.loadMore()
            advanceUntilIdle()
            assertEquals((0 until 200).map(::albumId), pubs.ids())
            assertEquals(LibraryCoverage.Open, pubs.last.coverage)
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        }
        clause("82.8b a failed after-reading on a scanning window neither appends nor relabels") { env ->
            env.server.scanning = true
            val reader = env.reader(NO_LOOK_AHEAD)
            reader.connect()
            val pubs = Publications(env.server)
            val handle = reader.open(grid, pubs)
            advanceUntilIdle()
            handle.setViewport(90, 99)
            env.server.failing += "getScanStatus"
            handle.loadMore()
            advanceUntilIdle()
            assertEquals(100, pubs.last.items.size, "a page whose after-reading failed was appended")
            assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage, "a failed reading relabelled the window")
            assertIs<LibraryCachedReason.Failed>(assertIs<LibraryFreshness.Cached>(pubs.last.freshness).reason)
        }
        clause("82.8c a failed connect-time reading reads no page and relabels nothing") { env ->
            loadTwoPages(env)
            env.server.failing += "getScanStatus"
            val reader = env.reader(NO_LOOK_AHEAD)
            assertNull(reader.connect(), "a failed reading was returned as an epoch")
            assertFalse(reader.serverReportsNoEpoch, "a failed reading was taken for a server with no stamp")
            val before = env.server.log.size
            val pubs = Publications(env.server)
            reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(0, env.server.log.drop(before).count { it.endpoint == "getAlbumList2" }, "a page was read with no epoch reading")
            assertEquals(200, pubs.last.items.size, "the cached content must stay")
            assertEquals(LibraryCoverage.Open, pubs.last.coverage, "a failed reading relabelled the window")
            assertIs<LibraryFreshness.Cached>(pubs.last.freshness, "a window with no reading this session was presented live (unchanged)")
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP, setOf("folder-1")), window.windowEpoch)
            assertEquals(200, window.endLoadedOffset)
        }
        clause("82.8d a failed after-reading inside a rebase writes nothing") { env ->
            loadTwoPages(env)
            env.server.lastScan = STAMP_AFTER_SCAN
            val reader = env.reader(NO_LOOK_AHEAD)
            assertNotNull(reader.connect())
            env.server.beforeRespond = { request ->
                if (request.endpoint == "getAlbumList2") env.server.failing += "getScanStatus"
            }
            val before = env.server.log.size
            val pubs = Publications(env.server)
            reader.open(grid, pubs)
            advanceUntilIdle()
            assertEquals(listOf(PAGE_0, STATUS), env.server.log.drop(before).map(Any::toString))
            val window = assertNotNull(listState(env))
            assertEquals(catalogEpochKey(STAMP, setOf("folder-1")), window.windowEpoch, "an unread rebase was written under the new epoch")
            assertEquals(200, window.endLoadedOffset, "an unread rebase dropped the cached pages")
            assertEquals(200, pubs.last.items.size)
            assertEquals(LibraryCoverage.Open, pubs.last.coverage, "a failed reading relabelled the window")
            assertFalse(reader.serverReportsNoEpoch)
            assertIs<LibraryFreshness.Cached>(pubs.last.freshness)

            // Once a reading succeeds, the rebase happens.
            env.server.beforeRespond = {}
            env.server.failing.clear()
            reader.reconnect()
            advanceUntilIdle()
            assertEquals(catalogEpochKey(STAMP_AFTER_SCAN, setOf("folder-1")), listState(env)?.windowEpoch)
            assertEquals(100, listState(env)?.endLoadedOffset)
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        }

        // ---- 82.9 No X-Total-Count: the total is unknown -------------------------------------------
        for (sendTotal in listOf(false, true)) {
            val name = if (sendTotal) "82.9c control: with X-Total-Count the total is the server's" else "82.9 a window whose responses lack X-Total-Count has an unknown total"
            clause(name) { env ->
                env.server.sendTotalCount = sendTotal
                val expected = if (sendTotal) 250 else null
                val reader = env.reader(NO_LOOK_AHEAD)
                reader.connect()
                val pubs = Publications(env.server)
                val handle = reader.open(grid, pubs)
                advanceUntilIdle()
                assertEquals(expected, pubs.last.total, "first page")
                assertEquals(LibraryCoverage.Open, pubs.last.coverage)
                handle.setViewport(90, 99)
                handle.loadMore()
                advanceUntilIdle()
                assertEquals(200, pubs.last.items.size)
                assertEquals(expected, pubs.last.total, "after an extend")
                // And through a rebase.
                env.server.lastScan = STAMP_AFTER_SCAN
                reader.reconnect()
                advanceUntilIdle()
                assertEquals(100, pubs.last.items.size, "fixture: the window was rebased")
                assertEquals(expected, pubs.last.total, "after a rebase")
                assertEquals(expected, listState(env)?.total)
            }
        }
    }

    /**
     * 82.7: a successful reading whose `lastScan` is [lastScan] is no epoch exactly when
     * [expectNoEpoch], whatever [scanning] says: the account says so, the window stores no epoch and
     * is never presented as guarded (not when it opens, and not once the scan ends), and two such
     * readings are never "unchanged" — a reopen inside the 60-second interval reads again.
     */
    private suspend fun TestScope.noStampReadsAsNoEpoch(env: ReaderEnv, lastScan: String?, scanning: Boolean, expectNoEpoch: Boolean) {
        env.server.lastScan = lastScan
        env.server.scanning = scanning
        val reader = env.reader(NO_LOOK_AHEAD)
        assertNotNull(reader.connect(), "fixture: the reading succeeded")
        assertEquals(expectNoEpoch, reader.serverReportsNoEpoch, "at connect")
        val before = env.server.log.size
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        // A stamp-less reading taken for a stamp would fire against the window and re-read.
        assertEquals(listOf(PAGE_0, STATUS), env.server.log.drop(before).map(Any::toString))
        val opened = when {
            scanning -> LibraryCoverage.UnverifiedScanning
            expectNoEpoch -> LibraryCoverage.UnverifiedNoEpoch
            else -> LibraryCoverage.Open
        }
        assertEquals(opened, pubs.last.coverage, "at open")
        val window = assertNotNull(listState(env))
        if (expectNoEpoch) assertNull(window.windowEpoch, "a window was stored under a stamp-less reading") else assertNotNull(window.windowEpoch)

        // The scan, if any, ends — still without a stamp.
        env.server.scanning = false
        env.clock.now += 1_000
        reader.reconnect()
        advanceUntilIdle()
        val settled = if (expectNoEpoch) LibraryCoverage.UnverifiedNoEpoch else LibraryCoverage.Open
        assertEquals(settled, pubs.last.coverage, "after the scan ended")
        assertEquals(expectNoEpoch, reader.serverReportsNoEpoch, "after the scan ended")

        // Two no-epoch readings are never "unchanged": the 60-second rule spares nothing.
        handle.close()
        env.clock.now += LibraryReaderConfig().revalidateWithinMillis / 6
        val pagesBefore = env.server.count("getAlbumList2")
        reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        val reread = env.server.count("getAlbumList2") - pagesBefore
        assertEquals(if (expectNoEpoch) 1 else 0, reread, "a reopen within the interval")
    }

    // ==== CONF-87 =====================================================================================

    @Test
    fun conf87LookAheadStaysWithin24PerSettledViewportAnd2InFlightFetchesNothingWhileFlingingOrConstrainedAndALookedAheadAlbumOpensWithZeroRequests() = conformance("CONF-87") {
        clause("87.1 a settled viewport fetches at most 24, never more than 2 in flight, most central first") { env ->
            val (reader, handle) = openGrid(env)
            env.server.holdMatching = { it.endpoint == "getAlbum" }
            handle.setViewport(0, 29) // 30 visible + the next 30: 60 candidates
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            runCurrent()
            assertEquals(2, env.server.heldCount, "look-ahead reads in flight")
            while (env.server.heldCount > 0) {
                env.server.release(1)
                runCurrent()
                assertTrue(env.server.heldCount <= 2, "more than two look-ahead reads were in flight")
            }
            advanceUntilIdle()
            // Connect and open are sequential, so this maximum is the look-ahead's alone.
            assertEquals(2, env.server.maxInFlight, "more than two requests were ever in flight")
            val fetched = env.server.log.filter { it.endpoint == "getAlbum" }.map { it.parameters.getValue("id") }
            assertEquals(24, fetched.size, "look-ahead exceeded 24 per settled viewport")
            assertEquals(24, fetched.distinct().size)
            assertEquals(setOf(albumId(14), albumId(15)), fetched.take(2).toSet(), "not most-central first")
        }
        clause("87.2 overlapping settles never have more than 24 outstanding") { env ->
            val (reader, handle) = openGrid(env)
            env.server.holdMatching = { it.endpoint == "getAlbum" }
            handle.setViewport(0, 29)
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            runCurrent()
            // A small scroll that keeps every outstanding read in the region, then settles again:
            // the 24 still running count against the new viewport's budget.
            handle.setViewport(1, 30)
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            runCurrent()
            while (env.server.heldCount > 0) {
                env.server.release(1)
                runCurrent()
            }
            advanceUntilIdle()
            assertEquals(0, env.server.cancelled, "fixture: no read left the region")
            assertEquals(24, env.server.count("getAlbum"), "a second, overlapping settle exceeded 24 outstanding")
        }
        clause("87.3 a fling that never settles fetches nothing") { env ->
            val (reader, handle) = openGrid(env)
            repeat(20) { step ->
                handle.setViewport(step * 3, step * 3 + 9)
                advanceTimeBy(reader.config.lookAheadSettleMillis - 100)
            }
            runCurrent()
            assertEquals(0, env.server.count("getAlbum"), "an unsettled fling prefetched")
            // Control: the same grid, once still, does prefetch.
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            advanceUntilIdle()
            assertTrue(env.server.count("getAlbum") > 0, "the settle never fired, so the fling assertion proves nothing")
        }
        clause("87.4a a constrained network fetches nothing") { env ->
            val (reader, handle) = openGrid(env)
            reader.setNetworkConstrained(true)
            handle.setViewport(0, 9)
            advanceTimeBy(10_000)
            advanceUntilIdle()
            assertEquals(0, env.server.count("getAlbum"), "look-ahead read on a constrained network")
            // Control: the same viewport, unconstrained, does prefetch.
            reader.setNetworkConstrained(false)
            handle.setViewport(0, 9)
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            advanceUntilIdle()
            assertTrue(env.server.count("getAlbum") > 0, "the settle never fired, so the constrained assertion proves nothing")
        }
        clause("87.4b a network becoming constrained stops the reads in flight and every one queued") { env ->
            val (reader, handle) = openGrid(env)
            env.server.holdMatching = { it.endpoint == "getAlbum" }
            handle.setViewport(0, 29)
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            runCurrent()
            assertEquals(2, env.server.heldCount, "fixture: two reads in flight")
            reader.setNetworkConstrained(true)
            runCurrent()
            assertEquals(2, env.server.cancelled, "reads in flight kept running on a constrained network")
            env.server.holdMatching = null
            env.server.release()
            advanceTimeBy(10_000)
            advanceUntilIdle()
            assertEquals(2, env.server.count("getAlbum"), "queued look-ahead reads went out on a constrained network")
        }
        clause("87.5 a looked-ahead album opens with zero requests") { env ->
            val (reader, handle) = openGrid(env)
            handle.setViewport(0, 5)
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            advanceUntilIdle()
            assertTrue(env.server.log.any { it.endpoint == "getAlbum" && it.parameters["id"] == albumId(2) }, "fixture: album-0002 was looked ahead")

            val before = env.server.log.size
            val pubs = Publications(env.server)
            reader.open(LibraryQuery.Album(albumId(2)), pubs)
            advanceUntilIdle()
            assertEquals(before, env.server.log.size, "opening a looked-ahead album issued a request")
            assertEquals(1, pubs.all.size, "a fresh album publishes once, complete")
            assertEquals(LibraryFreshness.Live, pubs.last.freshness)
            assertEquals(LibraryItemsState.Present, pubs.last.itemsState)
            assertEquals(listOf("${albumId(2)}-track-0", "${albumId(2)}-track-1"), pubs.ids())
        }
    }

    // ---- Fixtures -----------------------------------------------------------------------------------

    /**
     * Runs every clause of [row], each in a fresh environment, and fails once at the end naming
     * EVERY clause that failed — so one broken rule cannot hide whether the others still hold.
     */
    private fun conformance(row: String, body: suspend Clauses.() -> Unit) = runTest {
        val clauses = Clauses(this)
        clauses.body()
        assertTrue(clauses.ran > 0, "fixture: no clause ran")
        assertTrue(clauses.failed.isEmpty(), "$row: ${clauses.failed.size} of ${clauses.ran} clauses failed:\n" + clauses.failed.joinToString("\n"))
    }

    private class Clauses(private val scope: TestScope) {
        val failed = mutableListOf<String>()
        var ran = 0
            private set

        /** One clause in its own server, database and reader; nothing may escape the reader's scope. */
        suspend fun clause(name: String, block: suspend TestScope.(ReaderEnv) -> Unit) {
            ran += 1
            scope.withReaderEnv { env ->
                try {
                    block(env)
                    assertEquals(emptyList(), env.uncaught, "an exception escaped the reader's scope")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failed += "[$name] $failure"
                }
            }
        }
    }

    private fun listState(env: ReaderEnv): CachedListState? = env.store.bind(ReaderEnv.BINDING).listState(GRID_KEY)

    private suspend fun TestScope.openGrid(env: ReaderEnv): Pair<LibraryReader, LibraryWindowHandle> {
        val reader = env.reader()
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        assertEquals(0, env.server.count("getAlbum"), "fixture: nothing looked ahead before a viewport")
        return reader to handle
    }

    /** A previous session that read two pages under [STAMP] and closed. */
    private suspend fun TestScope.loadTwoPages(env: ReaderEnv) {
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(200, pubs.last.items.size, "fixture: two pages")
        assertEquals(catalogEpochKey(STAMP, setOf("folder-1")), listState(env)?.windowEpoch, "fixture: stored under STAMP")
        handle.close()
    }

    /** A scan that removes album-0005 between a page's arrival at the server and its answer. */
    private fun scanRemovingAlbumFiveDuringPage(server: FakeReaderServer, offset: String): (FakeReaderServer.Request) -> Unit {
        var done = false
        return { request ->
            if (!done && request.endpoint == "getAlbumList2" && request.parameters["offset"] == offset) {
                done = true
                server.albums.removeAll { it.id == albumId(5) }
                server.lastScan = STAMP_AFTER_SCAN
            }
        }
    }

    /**
     * A published list is consistent when it could have been read from one server state: it never
     * holds album-0005 (only in the pre-scan catalog) together with album-0200 at position 199 (only
     * in the post-scan catalog's second page), and never repeats an id.
     */
    private fun assertConsistent(publication: LibraryPublication) {
        val ids = publication.items.map { it.rawId }
        assertEquals(ids.distinct(), ids, "a published window repeated an id")
        assertFalse(albumId(5) in ids && ids.indexOf(albumId(200)) == 199, "a page read under one stamp was stitched to a page read under another")
        assertTrue(ids.size <= 250)
    }

    private companion object {
        const val STAMP = "2026-09-22T10:00:00.123+02:00"
        const val STAMP_AFTER_SCAN = "2026-09-22T10:05:00.5+02:00"
        const val SENTINEL = "0001-01-01T00:00:00Z"
        const val GRID_KEY = "getAlbumList2?type=alphabeticalByName"
        const val STATUS = "getScanStatus"
        const val PAGE_0 = "getAlbumList2[offset=0][type=alphabeticalByName]"
        const val PAGE_100 = "getAlbumList2[offset=100][type=alphabeticalByName]"
        const val PAGE_200 = "getAlbumList2[offset=200][type=alphabeticalByName]"

        /** Look-ahead is CONF-87's; in CONF-82's clauses it would add getAlbum reads to every count. */
        val NO_LOOK_AHEAD = LibraryReaderConfig(lookAheadMaxPerViewport = 0)
    }
}
