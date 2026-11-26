package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.integrations.*
import com.legitimateapps.dulcet.server.v1.ApiProblem
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Fake Lidarr, Jellyfin and image hosts on loopback; nothing here reaches a real service. */
class ArtistImagesTest {
    private val mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val other = "b74b1b7f-71a5-4011-9441-d0b5e4122712"
    private val lidarrKey = "canary-lidarr-7f3e9a1c"
    private val jellyfinKey = "canary-jellyfin-52d8b0e4"
    private val png = artworkFixture("png")
    private val hash = "ab".repeat(32)
    private val lines = mutableListOf<String>()
    private fun http() = PoliteHttp(diagnostics = DiscoverDiagnostics { synchronized(lines) { lines += it } })
    private fun lidarr(f: FixtureDiscoverUpstream) = ServiceEndpoint(f.baseUrl, lidarrKey)
    private fun jellyfin(f: FixtureDiscoverUpstream) = ServiceEndpoint(f.baseUrl, jellyfinKey)
    private fun libraryArtist(id: Int, artist: String = mbid, lastWrite: String = "638700000000000000") =
        """[{"id":$id,"artistName":"Artist","foreignArtistId":"$artist","images":[{"coverType":"banner","url":"/MediaCover/$id/banner.jpg"},{"coverType":"poster","url":"/MediaCover/$id/poster.jpg?lastWrite=$lastWrite","remoteUrl":"https://images.lidarr.audio/cache/x.jpg","extension":".jpg"}]}]"""
    private fun lookupArtist(artist: String, remoteUrl: String, proxy: Boolean = true) =
        """[{"id":0,"artistName":"Someone","foreignArtistId":"$artist","images":[{"coverType":"poster",${if (proxy) "\"url\":\"/MediaCoverProxy/$hash/a.jpg\"," else ""}"remoteUrl":"$remoteUrl","extension":".jpg"}]}]"""
    private fun jpeg(edge: Int): ByteArray {
        val file = Files.createTempDirectory("artist-jpeg").resolve("a.jpg"); Fixtures.jpeg(file, edge); return Files.readAllBytes(file)
    }
    private fun assertNoCanary(text: String, where: String) {
        assertFalse(lidarrKey in text, "Lidarr key leaked into $where"); assertFalse(jellyfinKey in text, "Jellyfin key leaked into $where")
    }

    @Test fun libraryArtistComesFromLidarrMediaCoverWithKeyOnlyInHeader() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> Db.inMemory().use { db -> http().use { h ->
            l.json("/api/v1/artist", libraryArtist(7)); l.respond("/MediaCover/7/poster.jpg", body = png)
            val chain = Integrations.from(IntegrationSettings(lidarr = lidarr(l)), h, DiscoverCache(db))
            val image = assertNotNull(chain.artistImage(ArtistRef(mbid.uppercase(), "Artist"), Reach.Library))
            assertContentEquals(png, image.bytes); assertEquals("image/png", image.contentType)
            assertEquals(listOf("/api/v1/artist", "/MediaCover/7/poster.jpg"), l.requests.map { it.path })
            assertEquals(mapOf("mbId" to mbid), l.requests[0].query); assertEquals(mapOf("lastWrite" to "638700000000000000"), l.requests[1].query)
            assertTrue(l.requests.all { it.headers["x-api-key"] == lidarrKey })
            l.requests.forEach { assertNoCanary(it.path + it.query, "a request URL") }
            assertNotNull(chain.artistImage(ArtistRef(mbid, "Artist"), Reach.Library)); assertEquals(2, l.requests.size, "the second view is answered from cache")
        } } }
    }

    @Test fun libraryReachNeverAsksLidarrsOutsideFeed() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> Db.inMemory().use { db -> http().use { h ->
            l.json("/api/v1/artist", "[]"); l.json("/api/v1/artist/lookup", lookupArtist(mbid, "https://images.lidarr.audio/cache/a.jpg"))
            val chain = Integrations.from(IntegrationSettings(lidarr = lidarr(l)), h, DiscoverCache(db))
            assertNull(chain.artistImage(ArtistRef(mbid, "Artist"), Reach.Library))
            assertNull(chain.artistImage(ArtistRef(mbid, "Artist"), Reach.Library))
            assertEquals(listOf("/api/v1/artist"), l.requests.map { it.path }, "an artist Lidarr lacks is a cached negative, and Library reach never looks up")
        } } }
    }

    @Test fun discoverArtistComesFromLookupThroughLidarrsProxyThenAnAllowlistedHost() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> FixtureDiscoverUpstream().use { remote -> Db.inMemory().use { db -> http().use { h ->
            h.configureOrigin(remote.baseUrl, 0)
            l.json("/api/v1/artist", "[]")
            l.respond("/api/v1/artist/lookup", query = mapOf("term" to "lidarr:$mbid"), body = lookupArtist(mbid, "${remote.baseUrl}/cache/a.jpg").toByteArray())
            l.respond("/MediaCoverProxy/$hash/a.jpg", body = png)
            val settings = IntegrationSettings(lidarr = lidarr(l), lidarrImageHosts = setOf("127.0.0.1"))
            val chain = Integrations.from(settings, h, DiscoverCache(db), testingOverride = { true })
            assertContentEquals(png, assertNotNull(chain.artistImage(ArtistRef(mbid, ""), Reach.Catalogue)).bytes)
            assertEquals(listOf("/api/v1/artist", "/api/v1/artist/lookup", "/MediaCoverProxy/$hash/a.jpg"), l.requests.map { it.path })
            assertTrue(remote.requests.isEmpty(), "Lidarr's own proxy is asked first")

            // Lidarr restarted and forgot the proxy registration: the poster's allowlisted remote URL is fetched, without the key.
            val gif = artworkFixture("gif")
            l.respond("/api/v1/artist/lookup", query = mapOf("term" to "lidarr:$other"), body = lookupArtist(other, "${remote.baseUrl}/cache/b.gif").toByteArray())
            l.respond("/MediaCoverProxy/$hash/a.jpg", status = 404, body = ByteArray(0))
            remote.respond("/cache/b.gif", body = gif)
            assertContentEquals(gif, assertNotNull(chain.artistImage(ArtistRef(other, ""), Reach.Catalogue)).bytes)
            assertEquals(listOf("/cache/b.gif"), remote.requests.map { it.path })
            assertTrue(remote.requests.all { it.headers["x-api-key"] == null && it.headers["x-emby-token"] == null && it.headers["authorization"] == null })
        } } } }
    }

    @Test fun aRemoteHostOffTheAllowlistOrOverPlainHttpIsNeverContacted() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> FixtureDiscoverUpstream().use { remote -> Db.inMemory().use { db -> http().use { h ->
            l.json("/api/v1/artist", "[]"); remote.respond("/cache/a.jpg", body = png)
            l.respond("/api/v1/artist/lookup", query = mapOf("term" to "lidarr:$mbid"), body = lookupArtist(mbid, "${remote.baseUrl}/cache/a.jpg", proxy = false).toByteArray())
            // Default allowlist (images.lidarr.audio only).
            assertNull(Integrations.from(IntegrationSettings(lidarr = lidarr(l)), h, DiscoverCache(db), testingOverride = { true }).artistImage(ArtistRef(mbid, ""), Reach.Catalogue))
            // Allowlisted by name, but plain HTTP and loopback outside the Testing profile.
            Db.inMemory().use { fresh ->
                assertNull(Integrations.from(IntegrationSettings(lidarr = lidarr(l), lidarrImageHosts = setOf("127.0.0.1")), h, DiscoverCache(fresh)).artistImage(ArtistRef(mbid, ""), Reach.Catalogue))
            }
            // An answer naming a proxy path on another host is not followed either.
            l.respond("/api/v1/artist/lookup", query = mapOf("term" to "lidarr:$other"),
                body = """[{"id":0,"foreignArtistId":"$other","images":[{"coverType":"poster","url":"${remote.baseUrl}/elsewhere","remoteUrl":"${remote.baseUrl}/cache/a.jpg"}]}]""".toByteArray())
            assertNull(Integrations.from(IntegrationSettings(lidarr = lidarr(l)), h, DiscoverCache(db), testingOverride = { true }).artistImage(ArtistRef(other, ""), Reach.Catalogue))
            assertTrue(remote.requests.isEmpty(), "a disallowed host saw ${remote.requests.map { it.path }}")
            assertTrue(l.requests.none { it.path.contains("elsewhere") })
        } } } }
    }

    @Test fun jellyfinAnswersWhenLidarrHasNothingAndAFailingLidarrIsSkippedForAMinute() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> FixtureDiscoverUpstream().use { j -> Db.inMemory().use { db -> http().use { h ->
            val item = "0123456789abcdef0123456789abcdef"
            j.json("/Artists/AlbumArtists", """{"Items":[{"Name":"Artist","Id":"$item","ProviderIds":{"MusicBrainzArtist":"${mbid.uppercase()}"},"ImageTags":{"Primary":"f00d"}},{"Name":"Bare","Id":"fedcba9876543210fedcba9876543210","ProviderIds":{"MusicBrainzArtist":"$other"},"ImageTags":{}}]}""")
            j.respond("/Items/$item/Images/Primary", body = png)
            l.json("/api/v1/artist", "[]")
            val chain = Integrations.from(IntegrationSettings(lidarr = lidarr(l), jellyfin = jellyfin(j)), h, DiscoverCache(db))
            assertContentEquals(png, assertNotNull(chain.artistImage(ArtistRef(mbid, "Artist"), Reach.Library)).bytes)
            assertEquals(listOf("/api/v1/artist"), l.requests.map { it.path }, "Lidarr is asked first")
            assertEquals(listOf("/Artists/AlbumArtists", "/Items/$item/Images/Primary"), j.requests.map { it.path })
            assertEquals(mapOf("maxWidth" to "1200", "quality" to "90", "tag" to "f00d"), j.requests[1].query)
            assertTrue(j.requests.all { it.headers["x-emby-token"] == jellyfinKey && it.headers["x-api-key"] == null })
            assertNull(chain.artistImage(ArtistRef(other, "Bare"), Reach.Library), "an artist without a Primary image has none")
            assertEquals(2, j.requests.size, "the artist list is read once and held")

            // A Lidarr that refuses the key is skipped rather than asked once per artist.
            Db.inMemory().use { fresh -> FixtureDiscoverUpstream().use { refused ->
                refused.json("/api/v1/artist", """{"message":"Unauthorized"}""", 401)
                val second = Integrations.from(IntegrationSettings(lidarr = lidarr(refused), jellyfin = jellyfin(j)), h, DiscoverCache(fresh))
                assertNotNull(second.artistImage(ArtistRef(mbid, ""), Reach.Library)); assertNull(second.artistImage(ArtistRef(other, ""), Reach.Library))
                assertEquals(1, refused.requests.size)
            } }
        } } } }
    }

    @Test fun artistImageOrderIsChosenPerCapability() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> FixtureDiscoverUpstream().use { j -> Db.inMemory().use { db -> http().use { h ->
            val item = "0123456789abcdef0123456789abcdef"
            j.json("/Artists/AlbumArtists", """{"Items":[{"Id":"$item","ProviderIds":{"MusicBrainzArtist":"$mbid"},"ImageTags":{"Primary":"f00d"}}]}""")
            j.respond("/Items/$item/Images/Primary", body = png)
            l.json("/api/v1/artist", libraryArtist(7)); l.respond("/MediaCover/7/poster.jpg", body = artworkFixture("gif"))
            val jellyfinFirst = Integrations.from(IntegrationSettings(lidarr = lidarr(l), jellyfin = jellyfin(j), artistImageOrder = listOf("jellyfin", "lidarr")), h, DiscoverCache(db))
            assertContentEquals(png, assertNotNull(jellyfinFirst.artistImage(ArtistRef(mbid, ""), Reach.Library)).bytes)
            assertTrue(l.requests.isEmpty())
            val off = Integrations.from(IntegrationSettings(lidarr = lidarr(l), artistImageOrder = emptyList()), h, DiscoverCache(db))
            assertFalse(off.configured); assertNull(off.artistImage(ArtistRef(mbid, ""), Reach.Catalogue)); assertTrue(l.requests.isEmpty())
            assertFailsWith<IllegalArgumentException> { Integrations.from(IntegrationSettings(lidarr = lidarr(l), artistImageOrder = listOf("emby")), h, DiscoverCache(db)) }
            val loaded = IntegrationSettings.load { name, default -> mapOf("integration-artist-images" to " Jellyfin, lidarr-feed ").getOrElse(name) { default } }
            assertEquals(listOf("jellyfin", "lidarr-feed"), loaded.artistImageOrder)
        } } } }
    }

    @Test fun bytesThatAreNotAWholeImageAreRefusedAndNotCached() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> Db.inMemory().use { db -> http().use { h ->
            l.json("/api/v1/artist", libraryArtist(7))
            l.respond("/MediaCover/7/poster.jpg", body = "<html>login</html>".toByteArray()); l.respond("/MediaCover/7/poster-500.jpg", body = png.copyOf(png.size - 4))
            val chain = Integrations.from(IntegrationSettings(lidarr = lidarr(l)), h, DiscoverCache(db))
            assertNull(chain.artistImage(ArtistRef(mbid, ""), Reach.Library))
            assertEquals(listOf("/api/v1/artist", "/MediaCover/7/poster.jpg", "/MediaCover/7/poster-500.jpg"), l.requests.map { it.path })
            l.respond("/MediaCover/7/poster.jpg", body = png)
            assertContentEquals(png, assertNotNull(chain.artistImage(ArtistRef(mbid, ""), Reach.Library)).bytes, "a refused body was not cached")
        } } }
    }

    @Test fun apiKeysNeverReachLogsErrorsDiagnosticsOrSettingsText() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> FixtureDiscoverUpstream().use { j -> Db.inMemory().use { db -> http().use { h ->
            l.json("/api/v1/artist", "[]", 401); j.json("/Artists/AlbumArtists", "boom", 500)
            val args = mapOf("integration-lidarr-url" to l.baseUrl, "integration-lidarr-api-key" to lidarrKey, "integration-jellyfin-url" to j.baseUrl, "integration-jellyfin-api-key" to jellyfinKey)
            val config = Config.load(args, emptyMap())
            assertNoCanary(config.toString(), "Config.toString"); assertNoCanary(config.integrations.toString(), "settings toString")
            assertNoCanary(config.integrations.lidarr.toString(), "endpoint toString")
            val chain = Integrations.from(config.integrations, h, DiscoverCache(db))
            assertNull(chain.artistImage(ArtistRef(mbid, ""), Reach.Catalogue))
            for (failure in listOf(
                runCatching { h.get(l.baseUrl, "/api/v1/artist", emptyMap(), credentials = mapOf("X-Api-Key" to lidarrKey)).also { if (it.status != 200) throw UpstreamUnavailable(reason = UpstreamFailure.HttpStatus) } },
                runCatching { h.get("http://127.0.0.1:1", "/api/v1/artist", emptyMap(), credentials = mapOf("X-Api-Key" to lidarrKey)) },
                runCatching { h.get(l.baseUrl, "/x", emptyMap(), credentials = mapOf("X-Api-Key" to "$lidarrKey\r\nX: y")) },
                runCatching { IntegrationSettings.load { name, _ -> if (name == "integration-lidarr-api-key") lidarrKey else null } },
            )) {
                val e = assertNotNull(failure.exceptionOrNull())
                assertNoCanary(e.stackTraceToString(), "an error")
            }
            assertTrue(lines.isNotEmpty()); lines.forEach { assertNoCanary(it, "a diagnostic line") }
            (l.requests + j.requests).forEach { assertNoCanary(it.path + it.query, "a request URL") }
        } } } }
    }

    private fun scannedContext(root: Path, settings: IntegrationSettings): ServerContext {
        val config = Config(testMode = true, port = 0, bind = "127.0.0.1", musicFolder = root.resolve("music").also(Files::createDirectories), dataFolder = root.resolve("data"),
            ffmpeg = Fixtures.ffmpeg, ffprobe = Fixtures.ffprobe, scanOnStart = false, lrclibUrl = null, discover = DiscoverSettings(enabled = false), integrations = settings)
        Fixtures.track(config.musicFolder.resolve("Artist/Album/01.flac"), tags = mapOf("title" to "One", "album" to "Album", "artist" to "Artist", "album_artist" to "Artist", "MUSICBRAINZ_ARTISTID" to mbid))
        return ServerContext(config, Db.inMemory()).also { it.scanner.scan() }
    }

    @Test fun libraryArtistPrefersItsFolderFileThenTheConnectionsThenAnAlbumCover() {
        FixtureDiscoverUpstream().use { l ->
            l.json("/api/v1/artist", libraryArtist(7)); l.respond("/MediaCover/7/poster.jpg", body = png)
            val root = Files.createTempDirectory("artist-images-library")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = lidarr(l)))
            try {
                val artist = ctx.db.read { string("SELECT id FROM artist WHERE name='Artist'") }!!
                assertEquals(mbid, ctx.db.read { string("SELECT mbz_artist_id FROM artist WHERE id=?", artist) })
                assertContentEquals(png, ctx.artwork.image("ar-$artist", null)!!.bytes)
                val asked = l.requests.size
                val folder = ctx.config.musicFolder.resolve("Artist/Poster.JPG"); Fixtures.jpeg(folder, 32)
                assertContentEquals(Files.readAllBytes(folder), ctx.artwork.image("ar-$artist", null)!!.bytes)
                assertEquals(asked, l.requests.size, "a folder file is used without asking any connection")
            } finally { ctx.close(); ctx.db.close() }
        }
    }

    @Test fun withNothingConfiguredArtistsKeepTheirMonogramAndNoServiceIsAsked() {
        val root = Files.createTempDirectory("artist-images-none")
        val ctx = scannedContext(root, IntegrationSettings())
        try {
            assertFalse(ctx.integrations.configured)
            val artist = ctx.db.read { string("SELECT id FROM artist WHERE name='Artist'") }!!
            assertNotNull(ctx.artwork.image("ar-$artist", null), "the existing album-cover or placeholder answer stands")
            val item = DiscoverIdentity(ctx.db, artistImages = ctx.integrations.configured).register(DiscoverItem(kind = DiscoverKind.Artist, title = "Artist", externalIds = mapOf(DiscoverKind.Artist.registryKey to mbid)))
            assertNull(item.artworkId, "no artwork id, so the client draws its monogram")
            // The library root, shared by every artist of a flat layout, keeps only the original artist.jpg/png names.
            Fixtures.jpeg(ctx.config.musicFolder.resolve("poster.jpg"), 16)
            val flat = root.resolve("music/Flat Album/02.flac"); Fixtures.track(flat, tags = mapOf("title" to "Two", "album" to "Flat", "artist" to "Flat Artist")); ctx.scanner.scan()
            val flatArtist = ctx.db.read { string("SELECT id FROM artist WHERE name='Flat Artist'") }!!
            assertFalse(ctx.artwork.image("ar-$flatArtist", null)!!.bytes.contentEquals(Files.readAllBytes(ctx.config.musicFolder.resolve("poster.jpg"))))
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun discoverArtistTilesGetAnArtworkIdThatResolvesThroughTheChainAtTileSize() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l ->
            l.json("/api/v1/artist", libraryArtist(7)); l.respond("/MediaCover/7/poster.jpg", body = jpeg(900))
            l.respond("/api/v1/artist/lookup", query = mapOf("term" to "lidarr:$other"), body = "[]".toByteArray())
            val root = Files.createTempDirectory("artist-images-discover")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = lidarr(l)))
            try {
                val user = ctx.users.create("user", "password", false)
                val ids = DiscoverIdentity(ctx.db, artistImages = true)
                val held = ids.register(DiscoverItem(kind = DiscoverKind.Artist, title = "Artist", externalIds = mapOf(DiscoverKind.Artist.registryKey to mbid)))
                val art = assertNotNull(held.artworkId); assertTrue(art.startsWith("da_"))
                val tile = ctx.discover.artwork(user.id, art)
                assertFalse(tile.placeholder); assertEquals("image/jpeg", tile.contentType)
                val decoded = ImageIO.read(ByteArrayInputStream(tile.bytes)); assertEquals(DiscoverService.ARTIST_TILE_EDGE, maxOf(decoded.width, decoded.height))
                val missing = assertNotNull(ids.register(DiscoverItem(kind = DiscoverKind.Artist, title = "Nobody", externalIds = mapOf(DiscoverKind.Artist.registryKey to other))).artworkId)
                assertEquals(404, assertFailsWith<ApiProblem> { ctx.discover.artwork(user.id, missing) }.status)
            } finally { ctx.close(); ctx.db.close() }
        }
    }
}
