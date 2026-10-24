package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Rollback scanners may keep writing legacy stamp formats while stronger identities survive. */
class ArtworkStampRollbackRegressionTest {
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") {"%02x".format(it)}

    @Test fun legacyScannerStampWritesDoNotInvalidateInitializedStrongerArtworkState() {
        val root=Files.createTempDirectory("generated-art-stamp-rollback")
        val ctx=Fixtures.context(root)
        try {
            val embedded=root.resolve("embedded.jpg").also {Fixtures.jpeg(it,64)}
            val trackFile=ctx.config.musicFolder.resolve("artist/album/song.flac")
            Fixtures.track(trackFile,0.2,mapOf("title" to "Generated song","artist" to "Generated Artist",
                "album_artist" to "Generated Artist","album" to "Generated Album"),embedded)
            Fixtures.jpeg(trackFile.resolveSibling("cover.jpg"),64)
            val standalone=trackFile.parent.parent.resolve("artist.jpg").also {Fixtures.jpeg(it,64)}
            assertEquals(1,ctx.scanner.scan()!!.added)
            val ids=ctx.db.read {single("SELECT id,album_id,artist_id,has_embedded_art FROM track") {
                assertTrue(it.bool("has_embedded_art"),"Fixture must initialize a known embedded source")
                Triple(it.string("id"),it.string("album_id"),it.string("artist_id"))
            }!!}
            assertEquals("artist/album/cover.jpg",ctx.db.read {string("SELECT folder_art FROM album WHERE id=?",ids.second)})
            assertContentEquals(Files.readAllBytes(standalone),ctx.artwork.image("ar-${ids.third}",null)!!.bytes,
                "Fixture must initialize a selected standalone artist image")

            // Reproduce the path/size/millisecond-mtime digest written by the 9c6 scanner.
            // These are all visited directories and selected covers in this generated tree.
            val folderArt=sortedMapOf("" to "","artist" to "","artist/album" to "cover.jpg")
            val oldFolderStamp=digest(folderArt.entries.joinToString("\n") { (dir,name)->
                val file=if(name.isEmpty())null else ctx.config.musicFolder.resolve(dir).resolve(name)
                val attributes=file?.let {Files.readAttributes(it,BasicFileAttributes::class.java)}
                "$dir/$name:${attributes?.size()}:${attributes?.lastModifiedTime()?.toMillis()}"
            })
            val oldArtistStamp=digest(folderArt.keys.flatMap {dir->
                listOf("artist.jpg","artist.png").mapNotNull {name->
                    val file=ctx.config.musicFolder.resolve(dir).resolve(name)
                    if(!Files.isRegularFile(file))null else {
                        val attributes=Files.readAttributes(file,BasicFileAttributes::class.java)
                        "$dir/$name:${attributes.size()}:${attributes.lastModifiedTime().toMillis()}"
                    }
                }
            }.joinToString("\n"))
            // Older unversioned map contents must likewise be independent of initialized state.
            val oldEmbedded=JsonObject(mapOf(ids.first to JsonPrimitive("legacy-embedded-identity"))).toString()
            val oldArtist=JsonObject(mapOf(ids.third to JsonPrimitive("legacy-artist-identity"))).toString()
            val legacy=mapOf("folder_art_stamp" to oldFolderStamp,"artist_art_stamp" to oldArtistStamp,
                "embedded_art_sources" to oldEmbedded,"artist_art_sources" to oldArtist)
            ctx.db.write {for((key,value) in legacy)
                execute("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",key,value)
            }
            val head=ctx.journal.head()
            val scan=ctx.scanner.scan(force=true)!!
            assertEquals(0,scan.added);assertEquals(0,scan.updated);assertEquals(0,scan.removed)
            assertEquals(1,scan.unchanged);assertEquals(0,scan.failed)
            val events=ctx.db.read {query("SELECT type,op,COUNT(*) AS n FROM api_journal WHERE seq>? GROUP BY type,op",head) {
                "${it.string("type")}:${it.string("op")}" to it.long("n")
            }.toMap()}
            println("GENERATED_ART_STAMP_ROLLBACK unchanged=${scan.unchanged} rereads=${scan.updated} events=$events")
            assertTrue(events.isEmpty(),"Legacy scanner stamp writes must not manufacture artwork changes: $events")
            assertEquals(oldFolderStamp,ctx.db.read {string("SELECT value FROM meta WHERE key='folder_art_stamp'")},
                "New scans must leave the legacy folder stamp unchanged")
            for((key,value) in legacy)assertEquals(value,ctx.db.read {string("SELECT value FROM meta WHERE key=?",key)},
                "New scans must preserve legacy stamp namespace $key")
        } finally {ctx.close();ctx.db.close();root.toFile().deleteRecursively()}
    }
}
