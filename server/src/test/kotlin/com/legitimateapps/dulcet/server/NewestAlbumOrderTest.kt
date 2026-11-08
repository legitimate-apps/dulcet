package com.legitimateapps.dulcet.server

import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Albums one scan found share an added date, and album ids are random on a fresh server, so a tie
 * settled by id alone reads differently on every new server. A tie reads by name, then id.
 */
class NewestAlbumOrderTest {
    @Test fun `albums added by one scan read by name on both newest lists, and a later album still leads`() = testApplication {
        val ctx = Fixtures.context(Files.createTempDirectory("newest"))
        // Six albums: random ids that happen to sort by name are a 1 in 720 chance, so a sort by id cannot pass this.
        val names = listOf("Album A", "Album B", "Album C", "Album D", "Album E", "Album F")
        names.reversed().forEachIndexed { i, name ->
            Fixtures.track(ctx.config.musicFolder.resolve("$i.flac"), tags = mapOf("title" to "Track $i", "album" to name, "artist" to "Artist"))
        }
        ctx.scanner.scan()
        val alice = ctx.users.create("alice", "password", false)
        ctx.db.write { execute("UPDATE album SET created = created + 1000 WHERE name = 'Album D'") }
        val expected = listOf("Album D", "Album A", "Album B", "Album C", "Album E", "Album F")
        application { dulcetModule(ctx) }
        try {
            val idsToNames = ctx.db.read { query("SELECT id, name FROM album") { it.string("id") to it.string("name") } }.toMap()
            assertEquals(expected, ctx.home.shelves(alice.id).first { it.id == "newest" }.ids.map(idsToNames::getValue))
            val rest = client.rest("getAlbumList2", "alice", "password", "type" to "newest", "size" to "10")
            assertEquals(expected, rest["albumList2"]!!.jsonObject["album"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        } finally {
            ctx.db.close()
        }
    }
}
