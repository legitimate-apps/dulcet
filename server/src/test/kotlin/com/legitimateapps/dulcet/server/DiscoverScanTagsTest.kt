package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.DiscoverSettings
import com.legitimateapps.dulcet.server.scan.*
import java.nio.file.Files
import kotlin.test.*

class DiscoverScanTagsTest {
    private val group="11111111-1111-4111-8111-111111111111"
    @Test fun tagSurvivesRealProbeScanAndRestart() {
        val root=Files.createTempDirectory("discover-tags"); val file=root.resolve("music/one.flac")
        Fixtures.track(file,tags=mapOf("title" to "One","album" to "Album","artist" to "Artist","MUSICBRAINZ_RELEASEGROUPID" to group))
        val probe=Probe(Fixtures.ffprobe); val tags=probe.probe(file)
        val info=TrackInfo.from("one.flac","","one.flac",1,Files.size(file),tags,null)
        assertEquals(group,info.mbzReleaseGroupId)
        val config=Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music"),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,scanOnStart=false,lrclibUrl=null)
        val dbFile=root.resolve("catalog.db")
        val original=Db.open(dbFile).use { db ->
            val ctx=ServerContext(config,db); ctx.scanner.scan()
            db.read { single("SELECT id,album_id,mbz_release_group_id FROM track") { Triple(it.string("id"),it.string("album_id"),it.string("mbz_release_group_id")) } }!!
        }
        Db.open(dbFile).use { db ->
            val ctx=ServerContext(config,db)
            Fixtures.track(file,tags=mapOf("title" to "One","album" to "Album","artist" to "Artist","MUSICBRAINZ_RELEASEGROUPID" to group.uppercase()))
            ctx.scanner.scan(force=true)
            assertEquals(original.first,db.read { string("SELECT id FROM track") })
            assertEquals(original.second,db.read { string("SELECT album_id FROM track") })
            assertEquals(group,db.read { string("SELECT lower(mbz_release_group_id) FROM track") })
        }
    }
    @Test fun migrationPreservesCatalogAndUserState() {
        val file=Files.createTempDirectory("discover-migration").resolve("catalog.db")
        Db.open(file).use { db ->
            db.write {
                execute("INSERT INTO user(id,name,password_enc,is_admin,created) VALUES('user','user',?,0,1)",byteArrayOf())
                execute("INSERT INTO annotation(user_id,item_type,item_id,starred) VALUES('user','album','album',1)")
                execute("INSERT INTO library_item(user_id,item_type,item_id,added) VALUES('user','album','album',1)")
                execute("ALTER TABLE track DROP COLUMN mbz_release_group_id")
                execute("PRAGMA user_version=2")
            }
        }
        Db.open(file).use { db ->
            assertEquals(1,db.read { long("SELECT count(*) FROM annotation") })
            assertEquals(1,db.read { long("SELECT count(*) FROM library_item") })
            assertTrue(db.read { query("PRAGMA table_info(track)") { it.string("name") }.contains("mbz_release_group_id") })
        }
    }
    @Test fun untaggedScanDoesNotSweepUpstream() {
        val root=Files.createTempDirectory("discover-untagged")
        FixtureDiscoverUpstream().use { fixture -> Db.inMemory().use { db ->
            val config=Config(port=0,bind="127.0.0.1",musicFolder=root.resolve("music"),dataFolder=root.resolve("data"),ffmpeg=Fixtures.ffmpeg,ffprobe=Fixtures.ffprobe,scanOnStart=false,lrclibUrl=null,discover=DiscoverSettings(musicBrainzUrl=fixture.baseUrl,listenBrainzUrl=fixture.baseUrl,listenBrainzLabsUrl=fixture.baseUrl,coverArtArchiveUrl=fixture.baseUrl))
            val ctx=ServerContext(config,db)
            repeat(50) { Fixtures.track(config.musicFolder.resolve("$it.flac"),tags=mapOf("album" to "Album $it","artist" to "Artist")) }
            ctx.scanner.scan();assertEquals(50,db.read { long("SELECT count(*) FROM album") })
            assertEquals(0,fixture.requests.size)
        } }
    }
}
