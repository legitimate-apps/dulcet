package com.legitimateapps.dulcet.server

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * getTopSongs feeds an artist's Play button, so its first rows are what a listener hears first.
 * Titles here are chosen so that alphabetical order is the whole of one album, then the next:
 * the old ordering (this user's plays, then title) plays "Alpha" songs before anything else.
 */
class TopSongsRankingTest {
    private data class Song(val album: String, val year: Int, val track: Int, val title: String, val seconds: Double = 90.0)

    private val catalog = listOf(
        Song("Alpha", 2018, 1, "Aardvark"), Song("Alpha", 2018, 2, "Abacus"), Song("Alpha", 2018, 3, "Acorn"),
        Song("Beta", 2020, 1, "Bamboo"), Song("Beta", 2020, 2, "Banjo"), Song("Beta", 2020, 3, "Barn"),
        Song("Gamma", 2023, 1, "Cactus"), Song("Gamma", 2023, 2, "Cedar"), Song("Gamma", 2023, 3, "Cobalt"),
    )

    private fun fixture(name: String, songs: List<Song> = catalog, block: suspend ApplicationTestBuilder.(ServerContext) -> Unit) = testApplication {
        val ctx = Fixtures.context(Files.createTempDirectory(name))
        songs.forEachIndexed { i, s ->
            Fixtures.track(
                ctx.config.musicFolder.resolve("${s.album}/${s.track}-$i.flac"), seconds = s.seconds,
                tags = mapOf("title" to s.title, "album" to s.album, "artist" to "Test Artist", "album_artist" to "Test Artist", "track" to s.track.toString(), "date" to s.year.toString()),
            )
        }
        ctx.scanner.scan()
        application { dulcetModule(ctx) }
        try {
            block(ctx)
        } finally {
            ctx.db.close()
        }
    }

    private fun play(ctx: ServerContext, user: String, title: String, plays: Int = 0, starred: Boolean = false, rating: Int = 0) = ctx.db.write {
        val id = string("SELECT id FROM track WHERE title = ?", title)!!
        execute(
            "INSERT OR REPLACE INTO annotation(user_id, item_type, item_id, starred, rating, play_count, played) VALUES(?, 'song', ?, ?, ?, ?, ?)",
            user, id, if (starred) 1_000L else null, rating, plays, if (plays > 0) 1_000L else null,
        )
    }

    private suspend fun ApplicationTestBuilder.top(user: String, count: Int = 50): List<String> {
        val reply = client.rest("getTopSongs", user, "password", "artist" to "Test Artist", "count" to count.toString())
        assertEquals("ok", reply["status"]!!.jsonPrimitive.content)
        return reply["topSongs"]!!.jsonObject["song"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
    }

    private fun albumOf(title: String) = catalog.first { it.title == title }.album

    @Test fun `a listener with no plays gets a cross-album list, not alphabetical`() = fixture("top-none") { ctx ->
        ctx.users.create("alice", "password", false)
        val titles = top("alice")
        assertEquals(catalog.size, titles.size)
        assertNotEquals(titles.sortedBy { it.lowercase() }, titles, "alphabetical order is the old fallback")
        // The first three songs are one per album, newest record first, each a record's opening track.
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), titles.take(3))
        assertEquals(3, titles.take(3).map { albumOf(it) }.toSet().size)
        // Second pass over the records, still newest first.
        assertEquals(listOf("Cedar", "Banjo", "Abacus"), titles.drop(3).take(3))
    }

    @Test fun `count limits the list after ranking, so the spread survives a short list`() = fixture("top-count") { ctx ->
        ctx.users.create("alice", "password", false)
        val titles = top("alice", count = 4)
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark", "Cedar"), titles)
    }

    @Test fun `this listener's plays move a song up, most played first`() = fixture("top-own") { ctx ->
        val alice = ctx.users.create("alice", "password", false)
        play(ctx, alice.id, "Abacus", plays = 2)
        play(ctx, alice.id, "Cobalt", plays = 9)
        val titles = top("alice")
        assertEquals(listOf("Cobalt", "Abacus"), titles.take(2))
        // Unplayed songs follow, spread across records rather than alphabetical.
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), titles.drop(2).take(3))
    }

    @Test fun `plays by other listeners on the server move a song up for someone with no history`() = fixture("top-household") { ctx ->
        val alice = ctx.users.create("alice", "password", false)
        val bob = ctx.users.create("bob", "password", false)
        val carol = ctx.users.create("carol", "password", false)
        play(ctx, bob.id, "Barn", plays = 6)
        play(ctx, carol.id, "Barn", plays = 4)
        play(ctx, carol.id, "Acorn", starred = true)
        val titles = top("alice")
        assertEquals(listOf("Barn", "Acorn"), titles.take(2))
        assertEquals(listOf("Cactus", "Bamboo", "Aardvark"), titles.drop(2).take(3))
    }

    @Test fun `this listener's own plays outweigh a single other listener's plays`() = fixture("top-weight") { ctx ->
        val alice = ctx.users.create("alice", "password", false)
        val bob = ctx.users.create("bob", "password", false)
        play(ctx, bob.id, "Barn", plays = 3)
        play(ctx, alice.id, "Acorn", plays = 3)
        assertEquals(listOf("Acorn", "Barn"), top("alice").take(2))
        assertEquals(listOf("Barn", "Acorn"), top("bob").take(2))
    }

    @Test fun `a song this listener rated one star is not offered first`() = fixture("top-veto") { ctx ->
        val alice = ctx.users.create("alice", "password", false)
        val bob = ctx.users.create("bob", "password", false)
        play(ctx, bob.id, "Barn", plays = 30)
        play(ctx, alice.id, "Barn", rating = 1)
        val titles = top("alice")
        assertEquals("Barn", titles.last())
    }

    @Test fun `the same song on two records appears once`() = fixture(
        "top-dedupe", catalog + Song("Single", 2024, 1, "Aardvark"),
    ) { ctx ->
        ctx.users.create("alice", "password", false)
        val titles = top("alice")
        assertEquals(1, titles.count { it == "Aardvark" })
        assertEquals(catalog.size, titles.size)
    }

    @Test fun `skits and intros under a minute come after the full songs`() = fixture(
        "top-short", catalog.map { if (it.title == "Cactus") it.copy(seconds = 20.0) else it },
    ) { ctx ->
        ctx.users.create("alice", "password", false)
        val titles = top("alice")
        assertEquals("Cactus", titles.last())
        assertEquals("Cedar", titles.first(), "the next song on that record leads instead of the 20 s opener")
    }
}
