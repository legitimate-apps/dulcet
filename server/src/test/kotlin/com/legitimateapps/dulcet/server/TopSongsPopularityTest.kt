package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.DiscoverSettings
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An artist's Play (getTopSongs) leads with the artist's popular songs held in this library, from
 * a fake ListenBrainz. The catalog is the one [TopSongsRankingTest] uses, where the order without
 * popularity is Cactus, Bamboo, Aardvark (each record's opener, newest first) — so a popularity
 * order that differs from it shows popularity was used.
 */
class TopSongsPopularityTest {
    private data class Song(val album: String, val year: Int, val track: Int, val title: String, val recording: String?)

    private val artistMbid = "11111111-2222-4333-8444-555555555555"
    private fun rec(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val catalog = listOf(
        Song("Alpha", 2018, 1, "Aardvark", rec(1)), Song("Alpha", 2018, 2, "Abacus", rec(2)), Song("Alpha", 2018, 3, "Acorn", rec(3)),
        Song("Beta", 2020, 1, "Bamboo", rec(4)), Song("Beta", 2020, 2, "Banjo", null), Song("Beta", 2020, 3, "Barn", rec(6)),
        Song("Gamma", 2023, 1, "Cactus", rec(7)), Song("Gamma", 2023, 2, "Cedar", rec(8)), Song("Gamma", 2023, 3, "Cobalt", rec(9)),
    )
    private val canaryToken = "lb-canary-token-7f3e"
    private val popularityPath = "/1/popularity/top-recordings-for-artist/$artistMbid"

    /** Most popular first: a song this library lacks, Cedar by MBID, Banjo by title (untagged here), then Abacus. */
    private val popularity = """[
        {"recording_mbid":"${rec(99)}","recording_name":"Not In This Library","total_listen_count":900000},
        {"recording_mbid":"${rec(8)}","recording_name":"Cedar","total_listen_count":500000},
        {"recording_mbid":"${rec(55)}","recording_name":"Banjo","total_listen_count":400000},
        {"recording_mbid":"${rec(2)}","recording_name":"Abacus","total_listen_count":300000},
        {"recording_mbid":"${rec(77)}","recording_name":"Cobalt (Live)","total_listen_count":200000}
    ]"""

    /**
     * A server whose scan queues the artist's popularity fetch in the background, as production
     * does. [answer] is what the fake ListenBrainz returns; unless [hold], the fixture waits for that
     * background fetch before [block] runs, so requests see the stored answer.
     */
    private fun fixture(
        name: String, token: String? = canaryToken, enabled: Boolean = true, answer: Pair<Int, String> = 200 to popularity, hold: CountDownLatch? = null,
        block: suspend ApplicationTestBuilder.(ServerContext, FixtureDiscoverUpstream, CountDownLatch) -> Unit,
    ) =
        FixtureDiscoverUpstream().use { lb ->
            testApplication {
                val root = Files.createTempDirectory(name)
                val ctx = ServerContext(
                    Config(
                        testMode = true, port = 0, bind = "127.0.0.1", musicFolder = root.resolve("music").also(Files::createDirectories), dataFolder = root.resolve("data"),
                        ffmpeg = Fixtures.ffmpeg, ffprobe = Fixtures.ffprobe, scanOnStart = false, lrclibUrl = null,
                        discover = DiscoverSettings(
                            enabled = enabled, musicBrainzUrl = lb.baseUrl, musicBrainzMinGapMillis = 0, coverArtArchiveUrl = lb.baseUrl,
                            listenBrainzUrl = lb.baseUrl, listenBrainzLabsUrl = lb.baseUrl, listenBrainzToken = token,
                        ),
                    ),
                    Db.inMemory(),
                )
                ctx.discoverHttp.configureOrigin(lb.baseUrl, 0)
                lb.json(popularityPath, answer.second, status = answer.first)
                lb.release = hold
                val refreshed = CountDownLatch(1)
                ctx.artistPopularity.afterRefresh = { refreshed.countDown() }
                catalog.forEachIndexed { i, s ->
                    val tags = mutableMapOf(
                        "title" to s.title, "album" to s.album, "artist" to "Test Artist", "album_artist" to "Test Artist",
                        "track" to s.track.toString(), "date" to s.year.toString(), "MUSICBRAINZ_ARTISTID" to artistMbid,
                    )
                    s.recording?.let { tags["MUSICBRAINZ_TRACKID"] = it }
                    Fixtures.track(ctx.config.musicFolder.resolve("${s.album}/${s.track}-$i.flac"), seconds = 90.0, tags = tags)
                }
                ctx.scanner.scan()
                if (hold == null && token != null && enabled) assertTrue(refreshed.await(10, TimeUnit.SECONDS), "the scan's background popularity fetch")
                application { dulcetModule(ctx) }
                try { block(ctx, lb, refreshed) } finally { hold?.countDown(); ctx.close(); ctx.db.close() }
            }
        }

    private suspend fun ApplicationTestBuilder.top(user: String): List<String> {
        val reply = client.rest("getTopSongs", user, "password", "artist" to "Test Artist", "count" to "50")
        assertEquals("ok", reply["status"]!!.jsonPrimitive.content, reply.toString())
        return reply["topSongs"]!!.jsonObject["song"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
    }

    private fun play(ctx: ServerContext, user: String, title: String, plays: Int) = ctx.db.write {
        val id = string("SELECT id FROM track WHERE title = ?", title)!!
        execute("INSERT OR REPLACE INTO annotation(user_id, item_type, item_id, play_count, played) VALUES(?, 'song', ?, ?, 1000)", user, id, plays)
    }

    @Test fun `an unplayed artist opens on its popular songs held here, skipping the ones it lacks`() = fixture("pop-unplayed") { ctx, lb, _ ->
        ctx.users.create("alice", "password", false)
        val titles = top("alice")
        // Cedar by recording MBID, Banjo by title (no MBID tagged), Abacus by MBID; "Not In This
        // Library" is skipped and "Cobalt (Live)" is a live take, not the studio Cobalt.
        assertEquals(listOf("Cedar", "Banjo", "Abacus"), titles.take(3))
        // The rest keep today's spread: each record's opener, newest first.
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), titles.drop(3).take(3))
        assertEquals(catalog.size, titles.size)
    }

    @Test fun `only the artist id and the operator's token go upstream, never the listener`() = fixture("pop-privacy") { ctx, lb, _ ->
        ctx.users.create("alice", "password", false)
        top("alice"); top("alice")
        val request = lb.requests.single() // the scan's background fetch; requests read the cache
        assertEquals(popularityPath, request.path)
        assertTrue(request.query.isEmpty(), request.query.toString())
        assertEquals("Token $canaryToken", request.headers["authorization"])
        val everything = request.path + request.query + request.headers.filterKeys { it != "authorization" }
        assertFalse("alice" in everything || "password" in everything || canaryToken in everything, everything)
    }

    @Test fun `ListenBrainz down gives today's order`() = fixture("pop-down", answer = 500 to "unavailable") { ctx, lb, _ ->
        ctx.users.create("alice", "password", false)
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark", "Cedar", "Banjo", "Abacus"), top("alice").take(6))
    }

    @Test fun `without a token ListenBrainz popularity is not asked and the order is today's`() = fixture("pop-notoken", token = null) { ctx, lb, _ ->
        ctx.users.create("alice", "password", false)
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), top("alice").take(3))
        assertTrue(lb.requests.isEmpty(), lb.requests.toString())
    }

    @Test fun `with Discover off popularity is not asked`() = fixture("pop-off", enabled = false) { ctx, lb, _ ->
        ctx.users.create("alice", "password", false)
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), top("alice").take(3))
        assertTrue(lb.requests.isEmpty(), lb.requests.toString())
    }

    @Test fun `a listener's heavy plays can rise past the hits, light ones do not`() = fixture("pop-own") { ctx, lb, _ ->
        val alice = ctx.users.create("alice", "password", false)
        play(ctx, alice.id, "Barn", 15)  // 45 points beats the top hit's 40
        play(ctx, alice.id, "Acorn", 2)  // 6 points stays below the hits
        assertEquals(listOf("Barn", "Cedar", "Banjo", "Abacus", "Acorn"), top("alice").take(5))
    }

    @Test fun `a cold request answers at once with today's order and never waits on ListenBrainz`() {
        val hold = CountDownLatch(1) // ListenBrainz answers nothing until released
        fixture("pop-cold", hold = hold) { ctx, lb, refreshed ->
            ctx.users.create("alice", "password", false)
            val started = System.nanoTime()
            val cold = top("alice")
            val millis = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), cold.take(3))
            assertTrue(millis < 1_500, "a cold getTopSongs took $millis ms while ListenBrainz was held")
            // The only upstream call is the background one, still held: the request made none.
            assertEquals(1, lb.requests.size, lb.requests.toString())
            hold.countDown()
            assertTrue(refreshed.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("Cedar", "Banjo", "Abacus"), top("alice").take(3))
            assertEquals(1, lb.requests.size, lb.requests.toString())
        }
    }
}
