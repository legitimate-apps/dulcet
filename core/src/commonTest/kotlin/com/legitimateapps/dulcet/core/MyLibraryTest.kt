package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Dulcet Server's `dulcetLibrary` v1 in process (wire contract
 * `docs/dulcet-server/dulcet-library-extension.md`), over [SessionTestServer]'s catalog: detection,
 * `getDulcetLibrary` paging with `since`, add and remove (error 70 for an unknown id, all or
 * nothing), the `changed` clock, and the `dulcetInLibrary` flag on every catalog object it answers.
 */
internal class DulcetLibraryTestServer(val inner: SessionTestServer = SessionTestServer(FakeReaderServer(albumCount = 12))) :
    LibraryEndpointTransport {
    var advertise = true

    /** Overrides extension discovery only, leaving the supported library and writes intact. */
    var detectionResponse: LibraryEndpointResponse? = null

    data class Member(val kind: String, val id: String, val addedAt: Long)

    val members = mutableListOf<Member>()
    var clock: String? = null
    private var now = 1_790_000_000_000L
    private var clockMillis = 0L

    /** Called before a `getDulcetLibrary` page with offset > 0 is answered: a write mid-read. */
    var beforeLaterPage: () -> Unit = {}

    val requests = mutableListOf<SessionTestServer.Request>()

    fun count(endpoint: String) = requests.count { it.endpoint == endpoint }

    private val base get() = inner.base

    fun trackIds(): List<String> = base.albums.flatMap { it.songs }

    private fun known(kind: String, id: String): Boolean = when (kind) {
        "song" -> id in trackIds()
        "album" -> base.albums.any { it.id == id }
        else -> base.artists.any { it.first == id }
    }

    /** A write on the server, as another device or the web would make it. */
    fun serverAdd(kind: String, id: String) {
        if (members.none { it.kind == kind && it.id == id }) members += Member(kind, id, tick())
        bump()
    }

    fun serverRemove(kind: String, id: String) {
        members.removeAll { it.kind == kind && it.id == id }
        bump()
    }

    private fun tick(): Long {
        now += 1_000
        return now
    }

    private fun bump() {
        clockMillis = maxOf(clockMillis + 1, now)
        clock = isoMillis(clockMillis)
    }

    override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
        requests += SessionTestServer.Request(endpoint, parameters)
        return when (endpoint) {
            "getOpenSubsonicExtensions" -> when {
                detectionResponse != null -> detectionResponse!!
                else -> envelope(
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}""" +
                        (if (advertise) """,{"name":"dulcetLibrary","versions":[1]}""" else "") + "]",
                )
            }
            "getDulcetLibrary" -> {
                inner.log += SessionTestServer.Request(endpoint, parameters)
                if (!advertise) return envelope(""""error":{"code":0,"message":"unknown endpoint"}""")
                if ((parameters["offset"]?.toInt() ?: 0) > 0) beforeLaterPage()
                library(parameters)
            }
            "addToDulcetLibrary", "removeFromDulcetLibrary" -> {
                inner.log += SessionTestServer.Request(endpoint, parameters)
                inner.failWithCode[endpoint]?.let { return envelope(""""error":{"code":$it,"message":"refused"}""") }
                inner.failWithError[endpoint]?.let { throw LibraryRequestFailure(it) }
                val targets = listOfNotNull(
                    parameters["id"]?.let { "song" to it },
                    parameters["albumId"]?.let { "album" to it },
                    parameters["artistId"]?.let { "artist" to it },
                )
                if (targets.isEmpty()) return envelope(""""error":{"code":10,"message":"missing id"}""")
                if (endpoint == "addToDulcetLibrary") {
                    if (targets.any { (kind, id) -> !known(kind, id) }) return envelope(""""error":{"code":70,"message":"not found"}""")
                    targets.forEach { (kind, id) -> if (members.none { it.kind == kind && it.id == id }) members += Member(kind, id, tick()) }
                } else {
                    targets.forEach { (kind, id) -> members.removeAll { it.kind == kind && it.id == id } }
                }
                bump()
                envelope(null)
            }
            else -> flag(inner.request(endpoint, parameters))
        }
    }

    private fun library(p: Map<String, String>): LibraryEndpointResponse {
        val size = p["size"]?.toInt() ?: 500
        val offset = p["offset"]?.toInt() ?: 0
        val since = p["since"]?.toLong()
        fun of(kind: String) = members.filter { it.kind == kind && (since == null || it.addedAt > since) }
            .sortedWith(compareByDescending<Member> { it.addedAt }.thenBy { it.id })
            .drop(offset).take(size)
        val artists = of("artist").joinToString(",") { m ->
            val name = base.artists.first { it.first == m.id }.second
            """{"id":"${m.id}","name":"$name","albumCount":1,"dulcetInLibrary":true}"""
        }
        val albums = of("album").joinToString(",") { m ->
            val album = base.albums.first { it.id == m.id }
            """{"id":"${album.id}","name":"${album.name}","artist":"Artist One","artistId":"artist-1","songCount":${album.songs.size},"dulcetInLibrary":true}"""
        }
        val songs = of("song").joinToString(",") { m ->
            val album = base.albums.first { m.id in it.songs }
            """{"id":"${m.id}","title":"Song ${m.id}","album":"${album.name}","albumId":"${album.id}","artist":"Artist One","artistId":"artist-1","duration":60,"dulcetInLibrary":true}"""
        }
        val counts = listOf("artist", "album", "song").map { kind -> members.count { it.kind == kind } }
        return envelope(
            """"dulcetLibrary":{"artistCount":${counts[0]},"albumCount":${counts[1]},"songCount":${counts[2]}""" +
                (clock?.let { ""","changed":"$it"""" } ?: "") +
                ""","artist":[$artists],"album":[$albums],"song":[$songs]}""",
        )
    }

    private fun flag(response: LibraryEndpointResponse): LibraryEndpointResponse {
        if (!advertise) return response
        val root = LIBRARY_JSON.parseToJsonElement(response.body)
        return response.copy(body = walk(root).toString())
    }

    private fun walk(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::walk))
        is JsonObject -> {
            val id = (element["id"] as? JsonPrimitive)?.content
            val fields = element.mapValues { walk(it.value) }.toMutableMap()
            if (id != null && (element.containsKey("name") || element.containsKey("title")) && members.any { it.id == id }) {
                fields["dulcetInLibrary"] = JsonPrimitive(true)
            }
            JsonObject(fields)
        }
        else -> element
    }

    fun envelope(body: String?): LibraryEndpointResponse = LibraryEndpointResponse(
        statusCode = 200,
        body = """{"subsonic-response":{"status":"${if (body?.startsWith("\"error\"") == true) "failed" else "ok"}","version":"1.16.1","openSubsonic":true${body?.let { ",$it" } ?: ""}}}""",
        redactedUrl = "http://fixture.invalid/rest",
    )

    companion object {
        fun isoMillis(millis: Long): String {
            val days = millis.floorDiv(86_400_000L)
            val rest = millis - days * 86_400_000L
            // Civil from days (Howard Hinnant).
            val z = days + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
            fun two(v: Long) = v.toString().padStart(2, '0')
            val h = rest / 3_600_000
            val mi = rest / 60_000 % 60
            val s = rest / 1_000 % 60
            val ms = (rest % 1_000).toString().padStart(3, '0')
            return "$y-${two(m)}-${two(d)}T${two(h)}:${two(mi)}:${two(s)}.${ms}Z"
        }
    }
}

class MyLibraryTest {
    private val album3 = LibraryEntityRef(LibraryEntityKind.Album, albumId(3))
    private val album5 = LibraryEntityRef(LibraryEntityKind.Album, albumId(5))
    private val track7 = LibraryEntityRef(LibraryEntityKind.Track, "${albumId(7)}-track-0")

    private fun session(
        env: SessionEnv,
        server: DulcetLibraryTestServer,
        config: MyLibraryConfig = MyLibraryConfig(),
        binding: CacheBinding = SessionEnv.BINDING,
    ): LibraryReaderSession = LibraryReaderSession(
        database = env.database.database,
        cache = env.store.bind(binding),
        transport = server,
        scope = env.scope,
        config = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
        formPost = false,
        foreground = false,
        myLibraryConfig = config,
    )

    private suspend fun TestScope.online(session: LibraryReaderSession): LibraryReaderSession {
        session.reader.connect()
        advanceUntilIdle()
        return session
    }

    private fun MyLibrarySnapshot.albumIds() = albums.map { it.record.rawId }

    // ---- Detection --------------------------------------------------------------------------------

    @Test
    fun capabilitySetReportsTheVersionOnlyWhenAdvertised() {
        val on = CapabilitySet(extensions = mapOf("dulcetLibrary" to setOf(1)), permissions = UserPermissions(download = true, playlist = true, share = false, jukebox = false, admin = false), legacySubsonic = false)
        val newer = CapabilitySet(extensions = mapOf("dulcetLibrary" to setOf(2)), permissions = UserPermissions(download = true, playlist = true, share = false, jukebox = false, admin = false), legacySubsonic = false)
        val off = CapabilitySet(extensions = mapOf("songLyrics" to setOf(1)), permissions = UserPermissions(download = true, playlist = true, share = false, jukebox = false, admin = false), legacySubsonic = false)
        assertEquals(1, on.dulcetLibraryVersion())
        assertNull(newer.dulcetLibraryVersion(), "a version this client does not implement is not offered")
        assertNull(off.dulcetLibraryVersion())
        assertTrue(on.supportsMyLibrary())
    }

    @Test
    fun aServerWithoutTheExtensionOffersNoLibraryAndIsNeverPaged() = sessionTest { env ->
        val server = DulcetLibraryTestServer().apply { advertise = false }
        val session = online(session(env, server))
        assertEquals(MyLibraryRefreshOutcome.Unavailable, session.myLibrary.refresh())
        assertEquals(MyLibraryAvailability.Unavailable, session.myLibrary.availability())
        assertEquals(0, server.count("getDulcetLibrary"))
        assertEquals(MyLibraryMembership.Unknown, session.myLibrary.membership(album3))
    }

    @Test
    fun anUnavailableExtensionEndpointIsRememberedWithoutFurtherRequests() {
        for (status in listOf(200, 404, 501)) sessionTest { env ->
            val server = DulcetLibraryTestServer().apply {
                detectionResponse = if (status == 200) envelope(""""error":{"code":70,"message":"not found"}""")
                    else LibraryEndpointResponse(status, "endpoint unavailable", "http://fixture.invalid/rest")
            }
            val session = online(session(env, server))
            session.myLibrary.add(album3)
            advanceUntilIdle()
            val outcomes = mutableListOf<MutationOutcome>()
            session.favourites.addOutcomeListener { outcomes += it }
            assertEquals(MyLibraryRefreshOutcome.Unavailable, session.myLibrary.refresh(), "HTTP $status")
            advanceUntilIdle()
            assertEquals(MyLibraryAvailability.Unavailable, session.myLibrary.availability())
            assertEquals(0L, session.favourites.pendingCount(), "a definitive absence resolves queued changes")
            assertEquals(DomainError.CapabilityUnsupported(CapabilityFeature.MyLibrary), assertIs<MutationOutcome.NotSaved>(outcomes.single()).error)
            val requests = server.requests.size
            session.myLibrary.refresh()
            assertEquals(requests, server.requests.size, "remembered absence sends nothing on refresh")
            session.reader.reconnect()
            advanceUntilIdle()
            assertEquals(1, server.count("getOpenSubsonicExtensions"))
            assertEquals(0, server.count("getDulcetLibrary"))
            assertEquals(0, server.count("addToDulcetLibrary"))
        }
    }

    @Test
    fun authAndPermissionDetectionErrorsKeepTheQueuedAddUntilDetectionSucceeds() {
        for (code in listOf(40, 41, 42, 43, 44, 50)) detectionErrorKeepsQueuedAdd(code)
    }

    @Test
    fun genericAndUnknownDetectionErrorsKeepTheQueuedAddUntilDetectionSucceeds() {
        for (code in listOf(0, 999, 10, 20, 30, 60)) detectionErrorKeepsQueuedAdd(code)
    }

    private fun detectionErrorKeepsQueuedAdd(code: Int) = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        val error = AccountConnectionContract.mapSubsonicError(code, "", "")
        assertDetectionFailureKeepsQueuedAdd(env, server, server.envelope(""""error":{"code":$code,"message":"refused"}"""), error)
    }

    @Test
    fun httpDetectionFailuresKeepTheQueuedAddUntilDetectionSucceeds() {
        for (status in listOf(401, 403, 407, 500, 503)) sessionTest { env ->
            val error = if (status == 401) DomainError.Auth.InvalidCredentials else DomainError.Server.HttpStatus(status)
            assertDetectionFailureKeepsQueuedAdd(env, DulcetLibraryTestServer(), LibraryEndpointResponse(status, "refused", "http://fixture.invalid/rest"), error)
        }
    }

    @Test
    fun anErrorEnvelopeAtANotFoundStatusDoesNotEstablishEndpointAbsence() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        assertDetectionFailureKeepsQueuedAdd(
            env, server, server.envelope(""""error":{"code":42,"message":"refused"}""").copy(statusCode = 404),
            DomainError.Server.Unknown(42),
        )
    }

    @Test
    fun failedRedetectionRetainsTheAdvertisementAndQueuedRemove() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", album3.rawId)
        val first = online(session(env, server))
        first.myLibrary.refresh()
        first.setOnline(false)
        first.myLibrary.remove(album3)
        val queries = env.database.database.myLibraryQueries
        val before = queries.selectState(SessionEnv.BINDING.serverId).executeAsOne()
        val second = online(session(env, server))
        server.detectionResponse = server.envelope(""""error":{"code":0,"message":"refused"}""")
        assertEquals(MyLibraryRefreshOutcome.Failed(DomainError.Server.Known(0)), second.myLibrary.refresh())
        assertEquals(before, queries.selectState(SessionEnv.BINDING.serverId).executeAsOne(), "failed redetection writes nothing")
        assertEquals(MyLibraryAvailability.Available, second.myLibrary.availability())
        assertEquals(1L, second.favourites.pendingCount())
        assertEquals(0, server.count("removeFromDulcetLibrary"))
        server.detectionResponse = null
        second.myLibrary.refresh()
        second.favourites.flush()
        assertEquals(1, server.count("removeFromDulcetLibrary"))
        assertTrue(server.members.isEmpty())
        assertEquals(0L, second.favourites.pendingCount())
    }

    @Test
    fun malformedExtensionListsKeepTheQueuedAddUntilDetectionSucceeds() {
        for (body in listOf(
            null,
            """"openSubsonicExtensions":{}""",
            """"openSubsonicExtensions":[{"name":"dulcetLibrary","versions":["1"]}]""",
            """"openSubsonicExtensions":[{"name":"dulcetLibrary","versions":[1]},null]""",
        )) sessionTest { env ->
            val server = DulcetLibraryTestServer()
            assertDetectionFailureKeepsQueuedAdd(env, server, server.envelope(body), DomainError.Protocol.MalformedEnvelope)
        }
    }

    private suspend fun TestScope.assertDetectionFailureKeepsQueuedAdd(
        env: SessionEnv,
        server: DulcetLibraryTestServer,
        response: LibraryEndpointResponse,
        error: DomainError,
    ) {
        server.detectionResponse = response
        val session = online(session(env, server))
        assertEquals(MyLibraryRefreshOutcome.Failed(error), session.myLibrary.refresh())
        assertEquals(MyLibraryAvailability.Unknown, session.myLibrary.availability())
        assertNull(env.database.database.myLibraryQueries.selectState(SessionEnv.BINDING.serverId).executeAsOneOrNull(), "failed detection persists no answer")
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        assertEquals(MutationRecord.Pending, session.myLibrary.add(album3))
        advanceUntilIdle()
        session.favourites.flush()
        assertEquals(1L, session.favourites.pendingCount())
        assertTrue(outcomes.isEmpty())
        assertEquals(0, server.count("addToDulcetLibrary"))
        assertEquals(0, server.count("getDulcetLibrary"))
        // The failed attempt must not suppress detection on the next sync of this connection.
        assertEquals(MyLibraryRefreshOutcome.Failed(error), session.myLibrary.refresh())
        assertEquals(2, server.count("getOpenSubsonicExtensions"))
        assertEquals(1L, session.favourites.pendingCount())
        server.detectionResponse = null
        assertIs<MyLibraryRefreshOutcome.Full>(session.myLibrary.refresh())
        advanceUntilIdle()
        assertEquals(3, server.count("getOpenSubsonicExtensions"))
        assertEquals(1, server.count("addToDulcetLibrary"))
        assertTrue(server.members.any { it.id == album3.rawId })
        assertEquals(0L, session.favourites.pendingCount())
        assertEquals(MutationOutcome.Saved(album3, MutationField.InLibrary, 1), outcomes.single())
    }

    @Test
    fun aWithdrawnCapabilityResolvesQueuedAddsAndRemovesWithoutSendingThem() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", album3.rawId)
        val first = online(session(env, server))
        first.myLibrary.refresh()
        first.setOnline(false)
        first.myLibrary.remove(album3)
        first.myLibrary.add(album5)
        assertEquals(2L, first.favourites.pendingCount())
        // A new connection detects the server's explicit withdrawal before a flush.
        server.advertise = false
        val second = online(session(env, server))
        val outcomes = mutableListOf<MutationOutcome>()
        second.favourites.addOutcomeListener { outcomes += it }
        assertEquals(MyLibraryRefreshOutcome.Unavailable, second.myLibrary.refresh())
        advanceUntilIdle()
        assertEquals(0L, second.favourites.pendingCount())
        assertEquals(setOf(album3, album5), outcomes.map { assertIs<MutationOutcome.NotSaved>(it).target }.toSet())
        outcomes.forEach { assertEquals(DomainError.CapabilityUnsupported(CapabilityFeature.MyLibrary), assertIs<MutationOutcome.NotSaved>(it).error) }
        assertEquals(0, server.count("addToDulcetLibrary"))
        assertEquals(0, server.count("removeFromDulcetLibrary"))
    }

    @Test
    fun anAddOnAServerWithoutTheExtensionIsRefusedAndRolledBack() = sessionTest { env ->
        val server = DulcetLibraryTestServer().apply { advertise = false }
        val session = online(session(env, server))
        session.myLibrary.refresh()
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        session.myLibrary.add(album3)
        advanceUntilIdle()
        val refused = assertIs<MutationOutcome.NotSaved>(outcomes.single())
        assertEquals(DomainError.CapabilityUnsupported(CapabilityFeature.MyLibrary), refused.error)
        assertEquals(0, server.count("addToDulcetLibrary"))
        assertEquals(0L, session.favourites.pendingCount())
    }

    // ---- Full read, paging and presentation -------------------------------------------------------

    @Test
    fun aFullReadPagesEveryKindAndPresentsTheLibrary() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        listOf(1, 2, 3, 4, 5).forEach { server.serverAdd("album", albumId(it)) }
        server.serverAdd("song", "${albumId(7)}-track-0")
        server.serverAdd("artist", "artist-2")
        val session = online(session(env, server, MyLibraryConfig(pageSize = 2)))

        val outcome = assertIs<MyLibraryRefreshOutcome.Full>(session.myLibrary.refresh())
        assertEquals(7, outcome.members)
        assertTrue(outcome.stable)
        // probe + pages at offsets 0, 2, 4 (the last short on every kind)
        assertEquals(listOf("0", "0", "2", "4"), server.requests.filter { it.endpoint == "getDulcetLibrary" }.map { it.parameters["offset"] })

        val recent = session.myLibrary.snapshot(MyLibrarySort.RecentlyAdded)
        assertEquals(listOf(albumId(5), albumId(4), albumId(3), albumId(2), albumId(1)), recent.albumIds().filter { it != albumId(7) }, "newest first")
        assertTrue(albumId(7) in recent.albumIds(), "album 7 is shown for its member song")
        assertEquals(listOf("${albumId(7)}-track-0"), recent.songs.map { it.record.rawId })
        assertEquals(setOf("artist-1", "artist-2"), recent.artists.map { it.record.rawId }.toSet(), "explicit plus the artist of member albums")
        assertEquals(false, recent.albums.first { it.record.rawId == albumId(7) }.explicitlyAdded)
        assertEquals(1, recent.albums.first { it.record.rawId == albumId(7) }.memberSongCount)

        val alphabetical = session.myLibrary.snapshot(MyLibrarySort.Alphabetical)
        assertEquals(listOf(albumId(1), albumId(2), albumId(3), albumId(4), albumId(5), albumId(7)), alphabetical.albumIds())

        assertEquals(MyLibraryMembership.Added, session.myLibrary.membership(album3))
        assertEquals(MyLibraryMembership.Partial, session.myLibrary.membership(LibraryEntityRef(LibraryEntityKind.Album, albumId(7))))
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(LibraryEntityRef(LibraryEntityKind.Album, albumId(9))))
        assertEquals(MyLibrarySyncState.Current, recent.syncState)
    }

    @Test
    fun anUnchangedClockCostsOneProbe() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", albumId(3))
        val session = online(session(env, server))
        session.myLibrary.refresh()
        val before = server.count("getDulcetLibrary")
        assertEquals(MyLibraryRefreshOutcome.Unchanged, session.myLibrary.refresh())
        assertEquals(before + 1, server.count("getDulcetLibrary"))
    }

    @Test
    fun additionsOnlyAreReadWithSinceAndRemovalsForceAFullRead() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", albumId(3))
        val session = online(session(env, server))
        session.myLibrary.refresh()

        server.serverAdd("album", albumId(5))
        val incremental = assertIs<MyLibraryRefreshOutcome.Incremental>(session.myLibrary.refresh())
        assertTrue(incremental.added >= 1)
        assertTrue(server.requests.last { it.endpoint == "getDulcetLibrary" }.parameters.containsKey("since"))
        assertEquals(listOf(albumId(5), albumId(3)), session.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).albumIds())

        server.serverRemove("album", albumId(3))
        assertIs<MyLibraryRefreshOutcome.Full>(session.myLibrary.refresh(), "a since read cannot see a removal; the totals send it to a full read")
        assertEquals(listOf(albumId(5)), session.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).albumIds())
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(album3))
    }

    @Test
    fun aLibraryThatChangesUnderEveryReadIsNeverMarkedComplete() = sessionTest { env ->
        val server = DulcetLibraryTestServer(SessionTestServer(FakeReaderServer(albumCount = 60)))
        listOf(1, 2, 3).forEach { server.serverAdd("album", albumId(it)) }
        var next = 4
        server.beforeLaterPage = { server.serverAdd("album", albumId(next++)) }
        val session = online(session(env, server, MyLibraryConfig(pageSize = 2)))
        val first = session.myLibrary.refresh()
        val outcome = assertIs<MyLibraryRefreshOutcome.Full>(first)
        assertEquals(false, outcome.stable)
        assertEquals(MyLibrarySyncState.Never, session.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).syncState)
        assertEquals(MyLibraryMembership.Unknown, session.myLibrary.membership(LibraryEntityRef(LibraryEntityKind.Album, albumId(59))))

        server.beforeLaterPage = {}
        assertEquals(true, assertIs<MyLibraryRefreshOutcome.Full>(session.myLibrary.refresh()).stable)
        assertEquals(MyLibrarySyncState.Current, session.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).syncState)
    }

    // ---- The flag on ordinary responses -----------------------------------------------------------

    @Test
    fun theFlagOnAnAlbumResponseSetsAndClearsMembership() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        val session = online(session(env, server))
        session.myLibrary.refresh() // detects; the library is empty
        server.members += DulcetLibraryTestServer.Member("album", albumId(3), 1) // added elsewhere, clock not read

        val pubs = Recorder<LibraryPublication>(server.inner)
        session.reader.open(LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName), pubs)
        advanceUntilIdle()
        assertEquals(MyLibraryMembership.Added, session.myLibrary.membership(album3), "flag on the list page")

        server.members.clear()
        session.reader.open(LibraryQuery.Album(albumId(3)), Recorder(server.inner))
        advanceUntilIdle()
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(album3), "a live response without the flag")
    }

    @Test
    fun theFlagIsIgnoredOnAServerWithoutTheExtension() = sessionTest { env ->
        val server = DulcetLibraryTestServer().apply { advertise = false }
        val session = online(session(env, server))
        session.myLibrary.refresh()
        session.reader.open(LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName), Recorder(server.inner))
        advanceUntilIdle()
        assertEquals(0L, env.database.database.myLibraryQueries.selectMembers(SessionEnv.BINDING.serverId).executeAsList().size.toLong())
    }

    // ---- Writes -----------------------------------------------------------------------------------

    @Test
    fun anOfflineAddSurvivesARelaunchAndIsSentOnReconnect() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        val first = online(session(env, server))
        first.reader.open(LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName), Recorder(server.inner))
        advanceUntilIdle()
        first.myLibrary.refresh()
        first.setOnline(false)
        assertEquals(MutationRecord.Pending, first.myLibrary.add(album5))
        val shown = first.myLibrary.snapshot(MyLibrarySort.RecentlyAdded)
        assertEquals(listOf(albumId(5)), shown.albumIds(), "shown at once")
        assertTrue(shown.albums.single().pending)
        assertEquals(0, server.count("addToDulcetLibrary"))

        // Relaunch: a new session over the same device database.
        val second = session(env, server)
        val outcomes = mutableListOf<MutationOutcome>()
        second.favourites.addOutcomeListener { outcomes += it }
        assertEquals(1L, second.favourites.pendingCount())
        assertEquals(listOf(albumId(5)), second.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).albumIds())
        second.reader.connect()
        second.reader.reconnect()
        advanceUntilIdle()

        assertEquals(listOf(mapOf("albumId" to albumId(5))), server.requests.filter { it.endpoint == "addToDulcetLibrary" }.map { it.parameters })
        assertTrue(server.members.any { it.id == albumId(5) })
        assertEquals(MutationOutcome.Saved(album5, MutationField.InLibrary, 1), outcomes.single())
        assertEquals(0L, second.favourites.pendingCount())
        val after = second.myLibrary.snapshot(MyLibrarySort.RecentlyAdded)
        assertEquals(listOf(albumId(5)), after.albumIds())
        assertEquals(false, after.albums.single().pending)
    }

    @Test
    fun anAddMadeBeforeDetectionWaitsAndIsSentOnceTheExtensionIsKnown() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        val session = online(session(env, server))
        session.myLibrary.add(track7)
        advanceUntilIdle()
        assertEquals(0, server.count("addToDulcetLibrary"), "availability unknown: kept, not sent")
        assertEquals(1L, session.favourites.pendingCount())
        session.myLibrary.refresh()
        advanceUntilIdle()
        assertEquals(listOf(mapOf("id" to track7.rawId)), server.requests.filter { it.endpoint == "addToDulcetLibrary" }.map { it.parameters })
        assertEquals(0L, session.favourites.pendingCount())
    }

    @Test
    fun errorSeventyIsPermanentAndRollsTheAddBack() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        val session = online(session(env, server))
        session.myLibrary.refresh()
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        val unknown = LibraryEntityRef(LibraryEntityKind.Album, "album-gone")
        session.myLibrary.add(unknown)
        assertEquals(MyLibraryMembership.Added, session.myLibrary.membership(unknown))
        advanceUntilIdle()
        assertEquals(1, server.count("addToDulcetLibrary"))
        assertIs<MutationOutcome.NotSaved>(outcomes.single())
        assertEquals(0L, session.favourites.pendingCount())
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(unknown), "rolled back to the server's state")
    }

    @Test
    fun removingAnAlbumAlsoRemovesItsMemberSongs() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("song", track7.rawId)
        val session = online(session(env, server))
        session.myLibrary.refresh()
        val album7 = LibraryEntityRef(LibraryEntityKind.Album, albumId(7))
        assertEquals(MyLibraryMembership.Partial, session.myLibrary.membership(album7))
        session.myLibrary.remove(album7)
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(album7))
        advanceUntilIdle()
        assertTrue(server.members.isEmpty())
        assertEquals(emptyList(), session.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).albumIds())
    }

    @Test
    fun aRemoveIsShownAtOnceAndSent() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", albumId(3))
        val session = online(session(env, server))
        session.myLibrary.refresh()
        session.myLibrary.remove(album3)
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(album3))
        advanceUntilIdle()
        assertEquals(listOf(mapOf("albumId" to albumId(3))), server.requests.filter { it.endpoint == "removeFromDulcetLibrary" }.map { it.parameters })
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(album3))
        // The remove moved the server's clock; the next refresh proves by the totals that nothing else changed.
        assertEquals(MyLibraryRefreshOutcome.Incremental(0), session.myLibrary.refresh())
        assertEquals(MyLibraryMembership.NotAdded, session.myLibrary.membership(album3))
    }

    // ---- Accounts ---------------------------------------------------------------------------------

    @Test
    fun signOutRemovesTheAccountsLibrary() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", albumId(3))
        val session = online(session(env, server))
        session.myLibrary.refresh()
        val queries = env.database.database.serverDataQueries
        val serverId = SessionEnv.BINDING.serverId
        assertTrue(env.database.database.myLibraryQueries.selectMembers(serverId).executeAsList().isNotEmpty())
        queries.deleteSeenCacheForServer(serverId)
        assertEquals(0L, queries.countRowsForServer(serverId).executeAsOne().sum)
        assertTrue(env.database.database.myLibraryQueries.selectMembers(serverId).executeAsList().isEmpty())
        assertNull(env.database.database.myLibraryQueries.selectState(serverId).executeAsOneOrNull())
    }

    @Test
    fun anotherUserOnTheSameServerStartsWithNoLibrary() = sessionTest { env ->
        val server = DulcetLibraryTestServer()
        server.serverAdd("album", albumId(3))
        val first = online(session(env, server))
        first.myLibrary.refresh()
        assertEquals(MyLibraryMembership.Added, first.myLibrary.membership(album3))

        val other = session(env, server, binding = CacheBinding(SessionEnv.BINDING.serverId, SessionEnv.BINDING.normalizedBaseUrl, "someone-else"))
        assertEquals(MyLibraryAvailability.Unknown, other.myLibrary.availability())
        assertEquals(MyLibraryMembership.Unknown, other.myLibrary.membership(album3))
        assertTrue(other.myLibrary.snapshot(MyLibrarySort.RecentlyAdded).albums.isEmpty())
    }

    // ---- Wire helpers -----------------------------------------------------------------------------

    @Test
    fun theClockParsesAsEpochMillis() {
        assertEquals(1_790_000_001_532L, parseIsoInstantMillis(DulcetLibraryTestServer.isoMillis(1_790_000_001_532L)))
        assertEquals(0L, parseIsoInstantMillis("1970-01-01T00:00:00Z"))
        assertNull(parseIsoInstantMillis("2026-10-12 18:04:11"))
    }

    @Test
    fun extensionVersionsParseFromTheList() {
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonicExtensions":[{"name":"dulcetLibrary","versions":[1,2]},{"name":"x","versions":[1]}]}}"""
        assertEquals(setOf(1, 2), parseOpenSubsonicExtensionVersions(body, "dulcetLibrary"))
        assertEquals(emptySet(), parseOpenSubsonicExtensionVersions(body, "absent"))
    }
}

