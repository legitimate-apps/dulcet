package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.integrations.*
import com.legitimateapps.dulcet.server.v1.ApiProblem
import io.ktor.client.request.get
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
            assertFailsWith<UpstreamUnavailable> { chain.artistImage(ArtistRef(mbid, ""), Reach.Catalogue) }.also { assertNoCanary(it.stackTraceToString(), "the outage error") }
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

    private val oneArtist = mapOf("Artist/Album/01.flac" to mapOf("title" to "One", "album" to "Album", "artist" to "Artist", "album_artist" to "Artist", "MUSICBRAINZ_ARTISTID" to mbid))
    private fun scannedContext(root: Path, settings: IntegrationSettings, tracks: Map<String, Map<String, String>> = oneArtist): ServerContext {
        val config = Config(testMode = true, port = 0, bind = "127.0.0.1", musicFolder = root.resolve("music").also(Files::createDirectories), dataFolder = root.resolve("data"),
            ffmpeg = Fixtures.ffmpeg, ffprobe = Fixtures.ffprobe, scanOnStart = false, lrclibUrl = null, discover = DiscoverSettings(enabled = false), integrations = settings)
        tracks.forEach { (path, tags) -> Fixtures.track(config.musicFolder.resolve(path), tags = tags) }
        return ServerContext(config, Db.inMemory()).also { it.scanner.scan() }
    }
    private fun ServerContext.artistId(name: String) = db.read { string("SELECT id FROM artist WHERE name=?", name) } ?: error("no artist $name")
    private fun ServerContext.artistMbid(name: String) = db.read { string("SELECT mbz_artist_id FROM artist WHERE name=?", name) }

    @Test fun featuredAndCollaboratingArtistsNeverBorrowAnotherArtistsId() {
        val alpha = "11111111-1111-4111-8111-111111111111"; val epsilon = "55555555-5555-4555-8555-555555555555"; val zeta = "66666666-6666-4666-8666-666666666666"
        val gamma = "33333333-3333-4333-8333-333333333333"; val delta = "44444444-4444-4444-8444-444444444444"
        FixtureDiscoverUpstream().use { l ->
            l.json("/api/v1/artist", libraryArtist(7, artist = alpha)); l.respond("/MediaCover/7/poster.jpg", body = png)
            val root = Files.createTempDirectory("artist-images-credits")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = lidarr(l)), mapOf(
                // One id for two credits: it cannot be told whose it is, so neither credit takes it from this track.
                "Alpha/Album/01.flac" to mapOf("title" to "One", "album" to "Album", "artist" to "Alpha feat. Beta", "album_artist" to "Alpha",
                    "MUSICBRAINZ_ARTISTID" to alpha, "MUSICBRAINZ_ALBUMARTISTID" to alpha),
                // One id per credit, in credit order: each credit takes its own.
                "Epsilon/Album/01.flac" to mapOf("title" to "Two", "album" to "Second", "artist" to "Epsilon feat. Zeta", "album_artist" to "Epsilon",
                    "MUSICBRAINZ_ARTISTID" to "$epsilon;$zeta", "MUSICBRAINZ_ALBUMARTISTID" to epsilon),
                // One album-artist credit carrying two ids is a collaboration, not either member.
                "Pair/Album/01.flac" to mapOf("title" to "Three", "album" to "Third", "artist" to "Gamma", "album_artist" to "Gamma & Delta",
                    "MUSICBRAINZ_ARTISTID" to gamma, "MUSICBRAINZ_ALBUMARTISTID" to "$gamma;$delta"),
            ))
            try {
                assertEquals(alpha, ctx.artistMbid("Alpha"), "the album-artist credit is Alpha's alone")
                assertNull(ctx.artistMbid("Beta"), "a featured artist never takes the main artist's id")
                assertEquals(epsilon, ctx.artistMbid("Epsilon")); assertEquals(zeta, ctx.artistMbid("Zeta"))
                assertNull(ctx.artistMbid("Gamma & Delta")); assertEquals(gamma, ctx.artistMbid("Gamma"))
                assertContentEquals(png, ctx.artwork.image("ar-${ctx.artistId("Alpha")}", null)!!.bytes)
                val asked = l.requests.size
                assertFalse(ctx.artwork.image("ar-${ctx.artistId("Beta")}", null)!!.bytes.contentEquals(png), "Beta never shows Alpha's photo")
                assertEquals(asked, l.requests.size, "an artist without its own id asks no connection")
            } finally { ctx.close(); ctx.db.close() }
        }
        assertNull(com.legitimateapps.dulcet.server.scan.TrackInfo.creditMbid(null, alpha, 1, 2), "a row from an earlier scan is trusted for a single credit only")
        assertEquals(alpha, com.legitimateapps.dulcet.server.scan.TrackInfo.creditMbid(null, alpha, 0, 1))
        assertEquals(alpha, com.legitimateapps.dulcet.server.scan.TrackInfo.creditMbid(null, alpha, 0, 2), "an earlier row's id stays with the lead credit")
    }

    @Test fun anUpgradedLibraryKeepsTheLeadArtistsIdAndStillDeniesTheFeaturedOne() {
        val alpha = "11111111-1111-4111-8111-111111111111"
        val root = Files.createTempDirectory("artist-images-legacy")
        val ctx = scannedContext(root, IntegrationSettings(), mapOf(
            "Alpha/Album/01.flac" to mapOf("title" to "One", "album" to "Album", "artist" to "Alpha feat. Beta", "album_artist" to "Alpha feat. Beta",
                "MUSICBRAINZ_ARTISTID" to alpha, "MUSICBRAINZ_ALBUMARTISTID" to alpha)))
        try {
            // The shape of a row scanned before the id lists existed: the first id only, the lists NULL.
            ctx.db.write {
                execute("UPDATE track SET mbz_artist_ids=NULL, mbz_album_artist_ids=NULL")
                execute("UPDATE artist SET mbz_artist_id=NULL")
            }
            assertEquals(alpha, ctx.db.read { string("SELECT mbz_artist_id FROM track") })
            ctx.scanner.scan()
            assertNull(ctx.db.read { string("SELECT mbz_artist_ids FROM track") }, "the unchanged file was not re-read, so this is the legacy path")
            assertEquals(alpha, ctx.artistMbid("Alpha"), "the lead credit keeps the id it had before the upgrade")
            assertNull(ctx.artistMbid("Beta"))
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun closingStopsBackgroundRefreshesFromWriting() {
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { blackHole ->
            val root = Files.createTempDirectory("artist-images-close")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = ServiceEndpoint("http://127.0.0.1:${blackHole.localPort}", lidarrKey)))
            try {
                val artist = ctx.artistId("Artist"); val head = ctx.journal.head()
                val cached = ctx.db.read { long("SELECT COUNT(*) FROM discover_cache") }
                ctx.db.read { ctx.artwork.image("ar-$artist", null) }
                val started = System.nanoTime(); ctx.integrations.close()
                assertTrue((System.nanoTime() - started) / 1_000_000 < Integrations.CLOSE_WAIT_MILLIS + 1_000, "close waits only briefly")
                ctx.db.read { ctx.artwork.image("ar-$artist", null) }
                runBlocking { ctx.integrations.awaitRefreshes() }
                assertEquals(cached, ctx.db.read { long("SELECT COUNT(*) FROM discover_cache") }, "nothing is cached after close")
                assertEquals(0, ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='artist'", head) })
            } finally { ctx.close(); ctx.db.close() }
        }
    }

    @Test fun aSlowConnectionNeverHoldsTheDatabaseLock() {
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { blackHole ->
            val root = Files.createTempDirectory("artist-images-blackhole")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = ServiceEndpoint("http://127.0.0.1:${blackHole.localPort}", lidarrKey)))
            try {
                val artist = ctx.artistId("Artist")
                val started = System.nanoTime()
                // Catalog pages render artwork references inside a read transaction; this is that path.
                val image = assertNotNull(ctx.db.read { ctx.artwork.image("ar-$artist", null) })
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 2_000, "a locked render waited ${elapsedMs} ms on an unanswering connection")
                assertEquals(com.legitimateapps.dulcet.server.media.Artwork.STAND_IN_CACHE_SECONDS, image.cacheSeconds)
                val other = System.nanoTime(); ctx.db.write { execute("UPDATE meta SET value=value WHERE key='nothing'") }
                assertTrue((System.nanoTime() - other) / 1_000_000 < 1_000, "another writer was not held up by the queued refresh")
            } finally { ctx.close(); ctx.db.close() }
        }
    }

    @Test fun aLockedRenderWarmsInTheBackgroundAndTellsClientsToReReadTheArtist() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l ->
            l.json("/api/v1/artist", libraryArtist(7)); l.respond("/MediaCover/7/poster.jpg", body = png)
            val root = Files.createTempDirectory("artist-images-warm")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = lidarr(l)))
            try {
                val artist = ctx.artistId("Artist"); val head = ctx.journal.head()
                val first = ctx.db.read { ctx.artwork.image("ar-$artist", null) }!!
                assertFalse(first.bytes.contentEquals(png), "the first locked render answers from the library without waiting")
                ctx.integrations.awaitRefreshes()
                assertEquals(listOf("/api/v1/artist", "/MediaCover/7/poster.jpg"), l.requests.map { it.path })
                val journalled = ctx.db.read { query("SELECT id FROM api_journal WHERE seq>? AND type='artist'", head) { it.string("id") } }
                assertEquals(listOf(artist), journalled, "clients are told the artist's artwork changed")
                val second = ctx.db.read { ctx.artwork.image("ar-$artist", null) }!!
                assertContentEquals(png, second.bytes); assertEquals(com.legitimateapps.dulcet.server.media.Artwork.REMOTE_CACHE_SECONDS, second.cacheSeconds)
                assertEquals(2, l.requests.size, "the second render came from the cache")
                ctx.integrations.artistImage(ArtistRef(mbid, ""), Reach.Library)
                assertEquals(1, ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type='artist'", head) }, "an unchanged answer is not journalled again")
            } finally { ctx.close(); ctx.db.close() }
        }
    }

    @Test fun aStandInIsCachedBrieflyOnRestWhileConnectionsAreConfigured() = io.ktor.server.testing.testApplication {
        FixtureDiscoverUpstream().use { l ->
            l.json("/api/v1/artist", "[]")
            val root = Files.createTempDirectory("artist-images-rest")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = lidarr(l)))
            try {
                ctx.users.create("reader", "reader-password", false)
                val artist = ctx.artistId("Artist")
                val album = ctx.db.read { string("SELECT id FROM album WHERE name='Album'") }!!
                application { dulcetModule(ctx) }
                fun header(id: String) = runBlocking { client.get("/rest/getCoverArt?u=reader&p=reader-password&v=1.16.1&c=test&id=$id").headers[io.ktor.http.HttpHeaders.CacheControl] }
                assertEquals("private, max-age=300", header("ar-$artist"))
                assertEquals("private, max-age=315360000", header("al-$album"), "other artwork keeps its long lifetime")
            } finally { ctx.close(); ctx.db.close() }
        }
    }

    @Test fun anAlbumsOwnPicturesNeverBecomeTheArtistPhotoInAMultiDiscLayout() {
        FixtureDiscoverUpstream().use { l ->
            l.json("/api/v1/artist", libraryArtist(7)); l.respond("/MediaCover/7/poster.jpg", body = png)
            val root = Files.createTempDirectory("artist-images-discs")
            val ctx = scannedContext(root, IntegrationSettings(lidarr = lidarr(l)), mapOf("Artist/Album/CD1/01.flac" to oneArtist.values.single()))
            try {
                for (name in listOf("poster.jpg", "thumb.jpg", "folder.jpg", "cover.jpg")) Fixtures.jpeg(ctx.config.musicFolder.resolve("Artist/Album/$name"), 24)
                assertContentEquals(png, ctx.artwork.image("ar-${ctx.artistId("Artist")}", null)!!.bytes, "the connection's photo, not the album folder's pictures")
            } finally { ctx.close(); ctx.db.close() }
        }
    }

    @Test fun everySourceInAnOutageIsUnavailableNotNoPhoto() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { l -> Db.inMemory().use { db -> http().use { h ->
            l.json("/api/v1/artist", "{}", 500)
            val chain = Integrations.from(IntegrationSettings(lidarr = lidarr(l)), h, DiscoverCache(db))
            assertFailsWith<UpstreamUnavailable> { chain.artistImage(ArtistRef(mbid, ""), Reach.Library) }
            assertFailsWith<UpstreamUnavailable> { chain.artistImage(ArtistRef(mbid, ""), Reach.Library) }
            assertEquals(1, l.requests.size, "the failed connection is skipped, not asked again")
            assertNull(chain.cachedArtistImage(ArtistRef(mbid, ""), Reach.Library), "an outage stores no 'no photo' answer")
        } } }
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
                val folder = ctx.config.musicFolder.resolve("Artist/Artist.JPG"); Fixtures.jpeg(folder, 32)
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
