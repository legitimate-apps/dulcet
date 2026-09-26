package com.legitimateapps.dulcet.core

import io.ktor.http.URLBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Playlist conformance against a real, disposable server (spec §18.6, §20.4 CONF-88..91). The
 * reader and its playlist editor are internal to the core, so the conformance module reaches them
 * through this contract — the pattern [LibrarySyncContract] set: the PRODUCTION session, over the
 * production transport, returns plain observations, and the tests assert on them.
 *
 * Every edit is read back with a raw `getPlaylist` that shares nothing with the editor but the
 * server. Every playlist a control creates is deleted in `finally`; a control never touches a
 * playlist it did not create.
 */
public data class PlaylistConformanceRequest(
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    /** A second, NON-admin user of the same server, for the permission controls. */
    val otherUsername: String,
    val otherPassword: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "PlaylistConformanceRequest(<redacted>)"
}

/** CONF-88: what the server does with playlist writes, observed over raw `/rest`. */
public data class PlaylistServerFacts(
    /** `createPlaylist` answered with the playlist, and its entries kept a duplicate in order. */
    val createAnsweredWithPlaylist: Boolean,
    val createdEntriesKeptDuplicates: Boolean,
    /** Removing indices 1 and 3 of five in ONE request: the entries left (as indices of the five). */
    val removalOfOneAndThreeLeft: List<Int>,
    /** An index past the end: the answer was `ok`, and the entries were unchanged. */
    val outOfRangeRemovalAnsweredOk: Boolean,
    val outOfRangeRemovalChangedNothing: Boolean,
    /** The stale-index hazard: the song removed by an index computed before another client's removal. */
    val staleIndexIntended: String,
    val staleIndexRemoved: String?,
    /** `createPlaylist` with `playlistId`: the id, name, comment and visibility after a replace. */
    val replaceKeptId: Boolean,
    val replaceKeptHeader: Boolean,
    val replaceEntriesAsSent: Boolean,
    /** The same replace sent as a form body (OpenSubsonic `formPost`). */
    val formPostReplaceEntriesAsSent: Boolean,
    /**
     * A replace of [largeFormPostReplaceSize] entries — duplicates, in an order no sort produces —
     * sent as ONE form body: whether the list read back is exactly that list, in that order.
     */
    val largeFormPostReplaceSize: Int,
    val largeFormPostReplaceKeptOrder: Boolean,
    /** A replace with no `songId`: the entries after it equal the entries before it. */
    val emptyReplaceChangedNothing: Boolean,
    val emptyNameIgnored: Boolean,
    val emptyCommentCleared: Boolean,
    val lastScanUnmovedByEdits: Boolean,
    /** Code of `getPlaylist` after `deletePlaylist`. */
    val codeAfterDelete: Int?,
)

/** One edit made through the production editor and read back raw. */
public data class PlaylistEditObservation(
    val edit: String,
    /** How the editor told it: `Saved`, `Created`, `ChangedElsewhere`, `Diverged`, `NotSaved`, … */
    val outcome: String,
    val expectedEntries: List<String>,
    val readBackEntries: List<String>?,
    val expectedHeader: String?,
    val readBackHeader: String?,
    /** The editor's write requests for this edit, as `endpoint` names in order. */
    val writes: List<String>,
)

/** CONF-89: the production editor's round trip; every edit read back. */
public data class PlaylistRoundTripResult(
    val observations: List<PlaylistEditObservation>,
    /** Edits recorded offline were published before any request and replayed as one whole list. */
    val offlineEditsPublishedWithoutRequests: Boolean,
    val offlineReplayWrites: List<String>,
    val codeAfterDelete: Int?,
)

/**
 * CONF-89 without `formPost`: the production editor on a session told the server does NOT take a
 * form body, over a list too long for one query string. Each observation is read back raw.
 */
public data class PlaylistQueryStringResult(
    /** Removing most entries of a long list: batches, highest positions first, each verified. */
    val removal: PlaylistEditObservation,
    /** Appending many songs: batches, in order. */
    val append: PlaylistEditObservation,
    /** Moving one entry of a list whose whole-list write would not fit: refused, nothing written. */
    val reorder: PlaylistEditObservation,
    /** The parameter bytes of every write the editor sent, and the budget it keeps them within. */
    val writeParameterBytes: List<Int>,
    val budgetBytes: Int,
)

/**
 * CONF-89, a create whose answer is lost (§18.6): the production editor over a transport that
 * delivers the request and then loses the answer. A lost create is identified by the playlists the
 * server listed before its send, never by any clock.
 */
public data class PlaylistLostCreateResult(
    /**
     * Lost, then flushed again, beside an OLDER playlist of the same name and songs made just before
     * the send: the outcome, the creates sent, the playlists of that name (the older one and the
     * create's), whether the adopted id is the one that was not listed before the send, and whether
     * the older one is untouched.
     */
    val adoptedOutcome: String,
    val adoptedCreateWrites: Int,
    val adoptedPlaylistsNamed: Int,
    val adoptedIdIsTheNewOne: Boolean,
    val adoptedOlderUntouched: Boolean,
    /**
     * Lost, then another client makes a playlist of the same name before the next flush: two
     * candidates, so nothing is sent again on a guess — the outcomes told, the creates sent, and
     * whether the candidates named are exactly the two; then the person chooses the one the send made,
     * and the outcome of that and the playlists of that name.
     */
    val ambiguousOutcomes: List<String>,
    val ambiguousCreateWrites: Int,
    val ambiguousCandidatesAreBoth: Boolean,
    val chosenOutcome: String,
    val chosenIsTheLanded: Boolean,
    val ambiguousPlaylistsNamed: Int,
    /**
     * Lost, then deleted here: the outcomes told, the deletes the flush sent (none: nothing is
     * deleted on inference), and whether the candidates named are exactly the server's playlists of
     * that name — then, after the person confirms a delete of the candidate by its id, that delete's
     * outcome and the playlists of that name left.
     */
    val cancelledOutcomes: List<String>,
    val cancelledDeleteWrites: Int,
    val cancelledCandidatesAreTheServers: Boolean,
    val confirmedDeleteOutcome: String,
    val cancelledPlaylistsNamedAfterConfirm: Int,
    /**
     * A create that never arrived, deleted here, beside an OLDER playlist of that name and songs made
     * just before the send: the outcomes told, whether the older one is named as a candidate (it must
     * not be: it was listed before the send), and that it survived.
     */
    val olderOutcomes: List<String>,
    val olderNamedAsCandidate: Boolean,
    val olderSurvived: Boolean,
    val olderDeleteWrites: Int,
)

/** CONF-90: the hazard demonstrated raw, then the editor refusing it; and the positive control. */
public data class PlaylistStaleIndexResult(
    val rawStaleRemovalRemovedTheWrongSong: Boolean,
    val editorOutcome: String,
    val editorWritesAfterConcurrentChange: List<String>,
    val serverEntriesAfterRefusal: List<String>,
    val entriesAfterConcurrentChange: List<String>,
    val controlOutcome: String,
    val controlExpected: List<String>,
    val controlReadBack: List<String>,
)

/** CONF-91: another user's playlists. */
public data class PlaylistPermissionResult(
    /** As the non-admin user: the admin's public playlist as the reader publishes it. */
    val othersPublicEditable: Boolean?,
    val othersPublicReadonlyOnServer: Boolean?,
    val editorRecordForOthersPublic: String,
    /** Raw writes by the non-admin to the admin's public playlist: the codes answered. */
    val rawRenameCode: Int?,
    val rawReplaceCode: Int?,
    val rawDeleteCode: Int?,
    val othersPublicUnchanged: Boolean,
    /** The admin's PRIVATE playlist, read by the non-admin: the code answered. */
    val othersPrivateReadCode: Int?,
    /** The non-admin's own playlist, renamed through the editor and read back. */
    val ownRenameOutcome: String,
    val ownRenameReadBack: String?,
    /** As the admin: another user's playlist as published, and whether the server lets the admin edit it. */
    val adminSeesOthersEditable: Boolean?,
    val adminRawRenameOfOthersCode: Int?,
)

public object PlaylistConformanceContract {
    public suspend fun serverFacts(request: PlaylistConformanceRequest): PlaylistServerFacts = withRaw(request) { raw, songs, cleanup ->
        val scanBefore = raw.lastScan()
        val (createBody, created) = raw.create("CONF-88 facts", listOf(songs[0], songs[1], songs[0], songs[2]))
        cleanup += created
        val createAnswered = createdPlaylistIdOf(createBody) == created
        val keptDuplicates = raw.entries(created) == listOf(songs[0], songs[1], songs[0], songs[2])

        raw.replace(created, songs.take(5))
        raw.write("updatePlaylist", listOf("playlistId" to created, "songIndexToRemove" to "1", "songIndexToRemove" to "3"))
        val afterRemoval = raw.entries(created).orEmpty().map { songs.indexOf(it) }

        val beforeOutOfRange = raw.entries(created)
        val outOfRange = raw.write("updatePlaylist", listOf("playlistId" to created, "songIndexToRemove" to "99"))
        val outOfRangeUnchanged = raw.entries(created) == beforeOutOfRange

        // The hazard: a view read, another client's removal, then a removal by the stale index.
        raw.replace(created, songs.take(4))
        val view = raw.entries(created).orEmpty()
        raw.write("updatePlaylist", listOf("playlistId" to created, "songIndexToRemove" to "0"))
        val staleBefore = raw.entries(created).orEmpty()
        raw.write("updatePlaylist", listOf("playlistId" to created, "songIndexToRemove" to "2"))
        val staleAfter = raw.entries(created).orEmpty()
        val removed = staleBefore.toMutableList().also { list -> staleAfter.forEach { list.remove(it) } }.singleOrNull()

        raw.write("updatePlaylist", listOf("playlistId" to created, "name" to "CONF-88 kept", "comment" to "kept comment", "public" to "true"))
        val headerBefore = raw.header(created)
        val (replaceBody, _) = raw.writeBody("createPlaylist", listOf("playlistId" to created, "songId" to songs[3], "songId" to songs[1]))
        val replaceKeptId = createdPlaylistIdOf(replaceBody) == created
        val replaceKeptHeader = raw.header(created) == headerBefore
        val replaceEntries = raw.entries(created) == listOf(songs[3], songs[1])
        raw.writeBody("createPlaylist", listOf("playlistId" to created, "songId" to songs[4], "songId" to songs[0]), formPost = true)
        val formPostEntries = raw.entries(created) == listOf(songs[4], songs[0])
        // What the editor's whole-list write relies on for a large playlist: one form body, in order.
        val large = scrambled(songs, LARGE_REPLACE_SIZE)
        raw.writeBody("createPlaylist", listOf("playlistId" to created) + large.map { "songId" to it }, formPost = true)
        val largeKeptOrder = raw.entries(created) == large
        val beforeEmpty = raw.entries(created)
        raw.write("createPlaylist", listOf("playlistId" to created))
        val emptyReplaceUnchanged = raw.entries(created) == beforeEmpty
        raw.write("updatePlaylist", listOf("playlistId" to created, "name" to ""))
        val emptyNameIgnored = raw.header(created)?.name == "CONF-88 kept"
        raw.write("updatePlaylist", listOf("playlistId" to created, "comment" to ""))
        val commentCleared = raw.header(created)?.comment == null
        val scanAfter = raw.lastScan()
        raw.write("deletePlaylist", listOf("id" to created))
        val afterDelete = raw.code("getPlaylist", listOf("id" to created))
        cleanup -= created

        PlaylistServerFacts(
            createAnsweredWithPlaylist = createAnswered,
            createdEntriesKeptDuplicates = keptDuplicates,
            removalOfOneAndThreeLeft = afterRemoval,
            outOfRangeRemovalAnsweredOk = outOfRange == null,
            outOfRangeRemovalChangedNothing = outOfRangeUnchanged,
            staleIndexIntended = view[2],
            staleIndexRemoved = removed,
            replaceKeptId = replaceKeptId,
            replaceKeptHeader = replaceKeptHeader,
            replaceEntriesAsSent = replaceEntries,
            formPostReplaceEntriesAsSent = formPostEntries,
            largeFormPostReplaceSize = large.size,
            largeFormPostReplaceKeptOrder = largeKeptOrder,
            emptyReplaceChangedNothing = emptyReplaceUnchanged,
            emptyNameIgnored = emptyNameIgnored,
            emptyCommentCleared = commentCleared,
            lastScanUnmovedByEdits = scanBefore != null && scanBefore == scanAfter,
            codeAfterDelete = afterDelete,
        )
    }

    public suspend fun editorRoundTrip(request: PlaylistConformanceRequest): PlaylistRoundTripResult = withSession(request) { env ->
        val songs = env.songs
        val observations = mutableListOf<PlaylistEditObservation>()
        suspend fun step(edit: String, expectedEntries: List<String>, expectedHeader: String?, id: () -> String, act: () -> Unit) {
            val before = env.writes.size
            env.outcomes.clear()
            act()
            env.session.playlists.flush()
            val readBack = env.raw.entries(id())
            val header = env.raw.header(id())
            observations += PlaylistEditObservation(
                edit, env.outcomes.lastOrNull()?.let(::outcomeName) ?: "none", expectedEntries, readBack,
                expectedHeader, header?.describe(), env.writes.drop(before),
            )
        }
        // Online edits: each recorded then flushed at once (the flush a tap would start).
        env.session.setOnline(false)
        var localId = ""
        var id = ""
        step("create", listOf(songs[0], songs[1], songs[0]), "CONF-89 round trip|made here|public", { env.session.reader.playlistOverlay.resolve(localId) }) {
            localId = env.session.playlists.create("CONF-89 round trip", listOf(songs[0], songs[1], songs[0]), "made here", true).localId!!
            env.session.setOnline(true)
        }
        id = env.session.reader.playlistOverlay.resolve(localId)
        env.cleanup += id
        val detail = env.open(LibraryQuery.Playlist(id))
        env.awaitLive(detail)
        fun view() = detail().items.map { it.rawId }
        step("rename", listOf(songs[0], songs[1], songs[0]), "CONF-89 renamed|made here|public", { id }) { env.session.playlists.rename(id, "CONF-89 renamed") }
        step("comment", listOf(songs[0], songs[1], songs[0]), "CONF-89 renamed|changed|public", { id }) { env.session.playlists.setComment(id, "changed") }
        step("private", listOf(songs[0], songs[1], songs[0]), "CONF-89 renamed|changed|private", { id }) { env.session.playlists.setPublic(id, false) }
        step("append", listOf(songs[0], songs[1], songs[0], songs[2], songs[2]), null, { id }) { env.session.playlists.append(id, listOf(songs[2], songs[2])) }
        step("insert", listOf(songs[0], songs[3], songs[1], songs[0], songs[2], songs[2]), null, { id }) {
            env.session.playlists.insert(id, listOf(songs[3]), 1, view())
        }
        step("move", listOf(songs[2], songs[0], songs[3], songs[1], songs[0], songs[2]), null, { id }) {
            env.session.playlists.move(id, 5, 0, view())
        }
        step("remove", listOf(songs[2], songs[3], songs[1], songs[2]), null, { id }) {
            env.session.playlists.remove(id, setOf(1, 4), view())
        }
        // Offline: three edits, shown with no request, replayed on reconnect as one whole list.
        env.session.setOnline(false)
        val requestsBefore = env.requests.size
        env.session.playlists.move(id, 0, 3, view())
        env.session.playlists.remove(id, setOf(0), view())
        env.session.playlists.append(id, listOf(songs[4]))
        val offlineView = view()
        val publishedOffline = env.requests.size == requestsBefore && offlineView == listOf(songs[1], songs[2], songs[2], songs[4])
        val writesBefore = env.writes.size
        env.outcomes.clear()
        env.session.setOnline(true)
        env.session.reader.reconnect()
        env.session.playlists.flush()
        observations += PlaylistEditObservation(
            "offline replay", env.outcomes.lastOrNull()?.let(::outcomeName) ?: "none", offlineView, env.raw.entries(id), null, null,
            env.writes.drop(writesBefore),
        )
        val offlineWrites = env.writes.drop(writesBefore)
        step("empty", emptyList(), null, { id }) { env.session.playlists.remove(id, view().indices.toSet(), view()) }
        step("delete", emptyList(), null, { id }) { env.session.playlists.delete(id) }
        env.cleanup -= id
        PlaylistRoundTripResult(observations, publishedOffline, offlineWrites, env.raw.code("getPlaylist", listOf("id" to id)))
    }

    public suspend fun editorWithoutFormPost(request: PlaylistConformanceRequest): PlaylistQueryStringResult = withSession(request, formPost = false) { env ->
        val songs = env.songs
        val (_, id) = env.raw.create("CONF-89 query string", scrambled(songs, QUERY_STRING_LIST_SIZE), formPost = true)
        env.cleanup += id
        val detail = env.open(LibraryQuery.Playlist(id))
        env.awaitLive(detail)
        fun view() = detail().items.map { it.rawId }
        suspend fun step(edit: String, expected: List<String>, act: () -> Unit): PlaylistEditObservation {
            val before = env.writes.size
            env.outcomes.clear()
            act()
            env.session.playlists.flush()
            return PlaylistEditObservation(
                edit, env.outcomes.lastOrNull()?.let(::outcomeName) ?: "none", expected, env.raw.entries(id), null, null,
                env.writes.drop(before),
            )
        }
        val removeAt = view().indices.filter { it % 6 != 0 }.toSet()
        val kept = view().filterIndexed { index, _ -> index !in removeAt }
        val removal = step("remove", kept) { env.session.playlists.remove(id, removeAt, view()) }
        val added = scrambled(songs.reversed(), QUERY_STRING_APPEND_SIZE)
        val append = step("append", kept + added) { env.session.playlists.append(id, added) }
        val unchanged = env.raw.entries(id).orEmpty()
        val reorder = step("move", unchanged) { env.session.playlists.move(id, 0, view().lastIndex, view()) }
        PlaylistQueryStringResult(removal, append, reorder, env.writeParameterBytes.toList(), PlaylistEditor.QUERY_BUDGET_BYTES)
    }

    public suspend fun lostCreate(request: PlaylistConformanceRequest): PlaylistLostCreateResult = withSession(request) { env ->
        val songs = env.songs
        // Every name is this run's own: a playlist an earlier run left on a reused server — a run that
        // failed before its cleanup — shares no name with this one, so no count below includes it.
        val run = Random.nextLong().toULong().toString(16).takeLast(8)
        suspend fun named(name: String) = parseReaderPlaylists(env.raw.body("getPlaylists")).filter { it.name == name }
        // Recorded offline, so no flush starts on its own: each flush below is the one named.
        fun createOffline(name: String, songs: List<String>): String {
            env.session.setOnline(false)
            val localId = env.session.playlists.create(name, songs).localId!!
            env.session.setOnline(true)
            return localId
        }

        // Adopted: an older playlist of the same name and songs is made first; then the create lands,
        // its answer is lost, and the next flush finds it — the one playlist of that name the server
        // did not list before the send, holding the songs sent. No clock is compared.
        val adoptedName = "CONF-89 lost create $run"
        val adoptedSongs = listOf(songs[0], songs[1], songs[0])
        val (_, adoptedOlder) = env.raw.create(adoptedName, adoptedSongs)
        env.cleanup += adoptedOlder
        env.loseAnswer["createPlaylist"] = 1
        env.outcomes.clear()
        val writesBefore = env.writes.size
        val adoptedLocal = createOffline(adoptedName, adoptedSongs)
        env.session.playlists.flush() // delivered; the answer lost
        env.session.playlists.flush() // found: the only playlist of that name not listed before the send
        val adopted = named(adoptedName)
        adopted.forEach { env.cleanup += it.rawId }
        val adoptedOutcome = env.outcomes.lastOrNull()?.let(::outcomeName) ?: "none"
        val adoptedCreates = env.writes.drop(writesBefore).count { it == "createPlaylist" }
        val adoptedId = env.session.reader.playlistOverlay.resolve(adoptedLocal)
        val adoptedOlderEntries = env.raw.entries(adoptedOlder)

        // Ambiguous: the create lands, its answer is lost, and another client makes a playlist of the
        // same name before the next flush. Two candidates: never sent again on a guess, the person
        // is told both and chooses.
        val ambiguousName = "CONF-89 lost create, retried elsewhere $run"
        env.loseAnswer["createPlaylist"] = 1
        env.outcomes.clear()
        val ambiguousWritesBefore = env.writes.size
        val ambiguousLocal = createOffline(ambiguousName, listOf(songs[4]))
        env.session.playlists.flush() // delivered; the answer lost
        val landed = named(ambiguousName).map { it.rawId }
        landed.forEach { env.cleanup += it }
        val (_, elsewhere) = env.raw.create(ambiguousName, listOf(songs[4]))
        env.cleanup += elsewhere
        env.session.playlists.flush() // two candidates: told, nothing sent
        val ambiguousOutcomes = env.outcomes.map(::outcomeName)
        val ambiguousCreates = env.writes.drop(ambiguousWritesBefore).count { it == "createPlaylist" }
        val named = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossibleDuplicate>().singleOrNull()?.candidates.orEmpty()
        env.outcomes.clear()
        landed.singleOrNull()?.let { env.session.setOnline(false); env.session.playlists.chooseCreated(ambiguousLocal, it); env.session.setOnline(true) }
        env.session.playlists.flush() // adopts the one chosen
        val chosenOutcome = env.outcomes.map(::outcomeName).joinToString(",").ifEmpty { "none" }
        val chosenId = env.session.reader.playlistOverlay.resolve(ambiguousLocal)
        val ambiguousAfter = named(ambiguousName)
        // A regression that sent the create again leaves a third; it goes with the rest.
        ambiguousAfter.forEach { env.cleanup += it.rawId }

        // Cancelled: the create lands, its answer is lost, then the person deletes it here. Nothing
        // is deleted on inference: the candidate is named, and the person confirms its delete by id.
        val cancelledName = "CONF-89 lost then deleted $run"
        env.loseAnswer["createPlaylist"] = 1
        env.outcomes.clear()
        val cancelledLocal = createOffline(cancelledName, listOf(songs[2]))
        env.session.playlists.flush() // delivered; the answer lost
        val cancelledDeletesBefore = env.writes.count { it == "deletePlaylist" }
        env.session.playlists.delete(cancelledLocal)
        env.session.playlists.flush()
        val cancelledDeleteWrites = env.writes.count { it == "deletePlaylist" } - cancelledDeletesBefore
        val cancelledOutcomes = env.outcomes.map(::outcomeName)
        val cancelledBefore = named(cancelledName)
        cancelledBefore.forEach { env.cleanup += it.rawId }
        val candidates = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossiblyCreated>().singleOrNull()?.candidates.orEmpty()
        env.outcomes.clear()
        candidates.forEach { env.session.setOnline(false); env.session.playlists.delete(it); env.session.setOnline(true) }
        env.session.playlists.flush()
        val confirmedDeleteOutcome = env.outcomes.map(::outcomeName).joinToString(",").ifEmpty { "none" }
        val cancelledAfterConfirm = named(cancelledName)
        // A regression whose confirmed delete did not land leaves it; cleanup deletes it.
        cancelledAfterConfirm.forEach { env.cleanup += it.rawId }

        // Older: a playlist of the same name and songs made BEFORE the attempt, which never arrived: it
        // was listed before the send, so it is no candidate, however close in time.
        val olderName = "CONF-89 older namesake $run"
        val (_, older) = env.raw.create(olderName, listOf(songs[3]))
        env.cleanup += older
        env.dropRequest["createPlaylist"] = 1
        env.outcomes.clear()
        val olderLocal = createOffline(olderName, listOf(songs[3]))
        env.session.playlists.flush() // never delivered, and not provably so
        val deletesBefore = env.writes.count { it == "deletePlaylist" }
        env.session.playlists.delete(olderLocal)
        env.session.playlists.flush()
        val olderOutcomes = env.outcomes.map(::outcomeName)
        val olderNamed = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossiblyCreated>().any { older in it.candidates }
        val olderLeft = named(olderName)
        olderLeft.forEach { env.cleanup += it.rawId }

        PlaylistLostCreateResult(
            adoptedOutcome = adoptedOutcome,
            adoptedCreateWrites = adoptedCreates,
            adoptedPlaylistsNamed = adopted.size,
            adoptedIdIsTheNewOne = adopted.map { it.rawId }.filter { it != adoptedOlder }.singleOrNull() == adoptedId,
            adoptedOlderUntouched = adoptedOlderEntries == adoptedSongs,
            ambiguousOutcomes = ambiguousOutcomes,
            ambiguousCreateWrites = ambiguousCreates,
            ambiguousCandidatesAreBoth = named.sorted() == (landed + elsewhere).sorted() && named.size == 2,
            chosenOutcome = chosenOutcome,
            chosenIsTheLanded = landed.singleOrNull() == chosenId,
            ambiguousPlaylistsNamed = ambiguousAfter.size,
            cancelledOutcomes = cancelledOutcomes,
            cancelledDeleteWrites = cancelledDeleteWrites,
            cancelledCandidatesAreTheServers = candidates.isNotEmpty() && candidates.sorted() == cancelledBefore.map { it.rawId }.sorted(),
            confirmedDeleteOutcome = confirmedDeleteOutcome,
            cancelledPlaylistsNamedAfterConfirm = cancelledAfterConfirm.size,
            olderOutcomes = olderOutcomes,
            olderNamedAsCandidate = olderNamed,
            olderSurvived = olderLeft.map { it.rawId } == listOf(older),
            olderDeleteWrites = env.writes.count { it == "deletePlaylist" } - deletesBefore,
        )
    }

    public suspend fun staleIndex(request: PlaylistConformanceRequest): PlaylistStaleIndexResult = withSession(request) { env ->
        val songs = env.songs
        // The hazard, raw, as CONF-88 pins it.
        val (_, rawId) = env.raw.create("CONF-90 raw", songs.take(4))
        env.cleanup += rawId
        env.raw.write("updatePlaylist", listOf("playlistId" to rawId, "songIndexToRemove" to "0"))
        env.raw.write("updatePlaylist", listOf("playlistId" to rawId, "songIndexToRemove" to "2"))
        val rawWrong = env.raw.entries(rawId) == listOf(songs[1], songs[2])

        // The editor: a removal of songs[2] recorded on a view, then another client removes index 0.
        val (_, id) = env.raw.create("CONF-90 editor", songs.take(4))
        env.cleanup += id
        val detail = env.open(LibraryQuery.Playlist(id))
        env.awaitLive(detail)
        env.session.setOnline(false)
        val view = detail().items.map { it.rawId }
        env.session.playlists.remove(id, setOf(view.indexOf(songs[2])), view)
        env.raw.write("updatePlaylist", listOf("playlistId" to id, "songIndexToRemove" to "0"))
        val afterConcurrent = env.raw.entries(id).orEmpty()
        val writesBefore = env.writes.size
        env.outcomes.clear()
        env.session.setOnline(true)
        env.session.playlists.flush()
        val refusal = env.outcomes.lastOrNull()?.let(::outcomeName) ?: "none"
        val editorWrites = env.writes.drop(writesBefore)
        val serverAfter = env.raw.entries(id).orEmpty()

        // Positive control: the same edit on an unchanged list is written and read back.
        val (_, control) = env.raw.create("CONF-90 control", songs.take(4))
        env.cleanup += control
        val controlDetail = env.open(LibraryQuery.Playlist(control))
        env.awaitLive(controlDetail)
        val controlView = controlDetail().items.map { it.rawId }
        env.outcomes.clear()
        env.session.playlists.remove(control, setOf(controlView.indexOf(songs[2])), controlView)
        env.session.playlists.flush()
        PlaylistStaleIndexResult(
            rawStaleRemovalRemovedTheWrongSong = rawWrong,
            editorOutcome = refusal,
            editorWritesAfterConcurrentChange = editorWrites,
            serverEntriesAfterRefusal = serverAfter,
            entriesAfterConcurrentChange = afterConcurrent,
            controlOutcome = env.outcomes.lastOrNull()?.let(::outcomeName) ?: "none",
            controlExpected = controlView.filter { it != songs[2] },
            controlReadBack = env.raw.entries(control).orEmpty(),
        )
    }

    public suspend fun permissions(request: PlaylistConformanceRequest): PlaylistPermissionResult {
        val other = request.copy(username = request.otherUsername, password = request.otherPassword)
        // The non-admin's own playlist outlives that user's session: the admin reads it afterwards.
        val otherRaw = RawPlaylistClient(other)
        var ownPlaylist: String? = null
        try {
            return permissions(request, other) { ownPlaylist = it }
        } finally {
            ownPlaylist?.let { runCatching { otherRaw.write("deletePlaylist", listOf("id" to it)) } }
            otherRaw.close()
        }
    }

    private suspend fun permissions(
        request: PlaylistConformanceRequest,
        other: PlaylistConformanceRequest,
        created: (String) -> Unit,
    ): PlaylistPermissionResult {
        return withSession(request) { admin ->
            val (_, publicId) = admin.raw.create("CONF-91 admin public", admin.songs.take(2))
            admin.cleanup += publicId
            admin.raw.write("updatePlaylist", listOf("playlistId" to publicId, "public" to "true"))
            val (_, privateId) = admin.raw.create("CONF-91 admin private", admin.songs.take(1))
            admin.cleanup += privateId
            withSession(other) { user ->
                val list = user.open(LibraryQuery.Playlists)
                user.awaitLive(list)
                val published = list().items.filterIsInstance<LibraryItem.Playlist>().firstOrNull { it.rawId == publicId }
                val detail = user.open(LibraryQuery.Playlist(publicId))
                user.awaitLive(detail)
                val record = user.session.playlists.rename(publicId, "hijacked")
                val renameCode = user.raw.code("updatePlaylist", listOf("playlistId" to publicId, "name" to "hijacked"))
                val replaceCode = user.raw.code("createPlaylist", listOf("playlistId" to publicId, "songId" to user.songs[3]))
                val deleteCode = user.raw.code("deletePlaylist", listOf("id" to publicId))
                val unchanged = admin.raw.header(publicId)?.name == "CONF-91 admin public" &&
                    admin.raw.entries(publicId) == admin.songs.take(2)
                val privateCode = user.raw.code("getPlaylist", listOf("id" to privateId))
                // The non-admin's own playlist is editable, through the editor, read back.
                val (_, ownId) = user.raw.create("CONF-91 own", user.songs.take(1))
                created(ownId)
                val ownDetail = user.open(LibraryQuery.Playlist(ownId))
                user.awaitLive(ownDetail)
                user.outcomes.clear()
                user.session.playlists.rename(ownId, "CONF-91 own renamed")
                user.session.playlists.flush()
                val ownOutcome = user.outcomes.lastOrNull()?.let(::outcomeName) ?: "none"
                val ownReadBack = user.raw.header(ownId)?.name
                Triple(ownId, ownOutcome, ownReadBack).let { (own, outcome, readBack) ->
                    PermissionsSeenByUser(published?.editable, user.raw.header(publicId)?.readonly, record.name, renameCode, replaceCode, deleteCode, unchanged, privateCode, own, outcome, readBack)
                }
            }.let { seen ->
                // As the admin, on the admin's own reader thread: that playlist is another user's.
                val adminList = admin.open(LibraryQuery.Playlists)
                admin.awaitLive(adminList)
                val adminView = adminList().items.filterIsInstance<LibraryItem.Playlist>().firstOrNull { it.rawId == seen.ownId }
                val adminRenameCode = admin.raw.code("updatePlaylist", listOf("playlistId" to seen.ownId, "comment" to "admin was here"))
                PlaylistPermissionResult(
                    othersPublicEditable = seen.othersPublicEditable,
                    othersPublicReadonlyOnServer = seen.othersPublicReadonly,
                    editorRecordForOthersPublic = seen.record,
                    rawRenameCode = seen.renameCode,
                    rawReplaceCode = seen.replaceCode,
                    rawDeleteCode = seen.deleteCode,
                    othersPublicUnchanged = seen.unchanged,
                    othersPrivateReadCode = seen.privateCode,
                    ownRenameOutcome = seen.ownOutcome,
                    ownRenameReadBack = seen.ownReadBack,
                    adminSeesOthersEditable = adminView?.editable,
                    adminRawRenameOfOthersCode = adminRenameCode,
                )
            }
        }
    }

    private data class PermissionsSeenByUser(
        val othersPublicEditable: Boolean?,
        val othersPublicReadonly: Boolean?,
        val record: String,
        val renameCode: Int?,
        val replaceCode: Int?,
        val deleteCode: Int?,
        val unchanged: Boolean,
        val privateCode: Int?,
        val ownId: String,
        val ownOutcome: String,
        val ownReadBack: String?,
    )

    // ---- Machinery ------------------------------------------------------------------------------

    private fun outcomeName(outcome: PlaylistEditOutcome): String = when (outcome) {
        is PlaylistEditOutcome.Saved -> "Saved"
        is PlaylistEditOutcome.Created -> "Created"
        is PlaylistEditOutcome.NotSaved -> "NotSaved(${outcome.error})"
        is PlaylistEditOutcome.ChangedElsewhere -> "ChangedElsewhere"
        is PlaylistEditOutcome.Diverged -> "Diverged"
        is PlaylistEditOutcome.Superseded -> "Superseded"
        is PlaylistEditOutcome.NotRecorded -> "NotRecorded"
        is PlaylistEditOutcome.PossiblyCreated -> "PossiblyCreated"
        is PlaylistEditOutcome.PossibleDuplicate -> "PossibleDuplicate"
        is PlaylistEditOutcome.Held -> "Held(${outcome.error})"
    }

    private suspend fun <T> withRaw(
        request: PlaylistConformanceRequest,
        block: suspend (RawPlaylistClient, List<String>, MutableSet<String>) -> T,
    ): T {
        val raw = RawPlaylistClient(request)
        val cleanup = mutableSetOf<String>()
        try {
            return block(raw, raw.songIds(), cleanup)
        } finally {
            cleanup.forEach { runCatching { raw.write("deletePlaylist", listOf("id" to it)) } }
            raw.close()
        }
    }

    private class SessionEnv(
        val session: LibraryReaderSession,
        val raw: RawPlaylistClient,
        val songs: List<String>,
        val requests: List<String>,
        val writes: List<String>,
        val outcomes: MutableList<PlaylistEditOutcome>,
        val cleanup: MutableSet<String>,
        val writeParameterBytes: List<Int>,
        /** Per endpoint: how many of its next writes are delivered and then have their answer lost. */
        val loseAnswer: MutableMap<String, Int>,
        /** Per endpoint: how many of its next writes fail before reaching the server. */
        val dropRequest: MutableMap<String, Int>,
    ) {
        private val publications = mutableListOf<MutableList<LibraryPublication>>()

        fun open(query: LibraryQuery): () -> LibraryPublication {
            val seen = mutableListOf<LibraryPublication>()
            publications += seen
            session.reader.open(query) { seen += it }
            return { seen.last() }
        }

        suspend fun awaitLive(last: () -> LibraryPublication) {
            repeat(AWAIT_POLLS) {
                if (last().freshness == LibraryFreshness.Live) return
                delay(AWAIT_POLL_MILLIS)
            }
            error("the screen never published live: ${last().freshness}")
        }
    }

    /**
     * A production session on its own dedicated reader thread (the production dispatcher, so the
     * confinement is real), over the production transport and a fresh disposable database.
     */
    private suspend fun <T> withSession(request: PlaylistConformanceRequest, formPost: Boolean = true, block: suspend (SessionEnv) -> T): T {
        val dispatcher = newLibraryReaderDispatcher()
        val database = createLibrarySyncControlDatabase()
        val raw = RawPlaylistClient(request)
        val cleanup = mutableSetOf<String>()
        val transport = KtorLibraryEndpointTransport(
            LibraryBrowseRequest("conformance-playlists", request.normalizedBaseUrl, request.username, request.password, request.allowLocalHttp),
            null, null, systemHostResolver(),
        )
        val requests = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val writeParameterBytes = mutableListOf<Int>()
        val loseAnswer = mutableMapOf<String, Int>()
        val dropRequest = mutableMapOf<String, Int>()
        fun take(counts: MutableMap<String, Int>, endpoint: String): Boolean {
            val left = counts[endpoint] ?: 0
            if (left > 0) counts[endpoint] = left - 1
            return left > 0
        }
        val recording = object : LibraryEndpointTransport {
            override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
                requests += endpoint
                if (endpoint in WRITES) writes += endpoint
                return transport.request(endpoint, parameters)
            }

            override suspend fun requestRepeated(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): LibraryEndpointResponse {
                requests += endpoint
                if (endpoint in WRITES) {
                    writes += endpoint
                    // Measured by the HTTP client's own query encoding — independent of the editor's
                    // estimate, which is what it checks.
                    writeParameterBytes += queryStringBytes(parameters)
                }
                // A timeout proves nothing about delivery: the editor must treat both alike.
                if (take(dropRequest, endpoint)) throw LibraryRequestFailure(DomainError.Transport.Timeout)
                val response = transport.requestRepeated(endpoint, parameters, formPost)
                if (take(loseAnswer, endpoint)) throw LibraryRequestFailure(DomainError.Transport.Timeout)
                return response
            }
        }
        try {
            return withContext(dispatcher) {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                try {
                    val store = SeenCacheStore(database.primary, SeenCacheWallClock { Clock.System.now().toEpochMilliseconds() })
                    val session = LibraryReaderSession(
                        database.primary.database,
                        store.bind(CacheBinding("conformance:${request.username}", request.normalizedBaseUrl, request.username)),
                        recording,
                        scope,
                        LibraryReaderConfig(lookAheadMaxPerViewport = 0),
                        formPost = formPost,
                    )
                    val outcomes = mutableListOf<PlaylistEditOutcome>()
                    session.playlists.addOutcomeListener(outcomes::add)
                    session.reader.connect()
                    block(SessionEnv(session, raw, raw.songIds(), requests, writes, outcomes, cleanup, writeParameterBytes, loseAnswer, dropRequest))
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            cleanup.forEach { runCatching { raw.write("deletePlaylist", listOf("id" to it)) } }
            raw.close()
            transport.close()
            database.close()
            dispatcher.close()
        }
    }

    private val WRITES = setOf("createPlaylist", "updatePlaylist", "deletePlaylist")
    private const val LARGE_REPLACE_SIZE = 2_400
    private const val QUERY_STRING_LIST_SIZE = 600
    private const val QUERY_STRING_APPEND_SIZE = 450
    private const val AWAIT_POLLS = 200
    private const val AWAIT_POLL_MILLIS = 50L
}

/**
 * [size] entries drawn from [songs] by a fixed pseudo-random sequence: duplicates throughout and an
 * order no sort or de-duplication preserves, so a server that reorders or collapses is seen.
 */
private fun scrambled(songs: List<String>, size: Int): List<String> {
    var state = 12_345L
    return List(size) {
        state = (state * 1_103_515_245L + 12_345L) and 0x7fff_ffffL
        songs[((state ushr 16) % songs.size).toInt()]
    }
}

private fun createdPlaylistIdOf(body: String): String? {
    val playlist = parseLibraryEnvelope(body)?.payload?.get("playlist") as? JsonObject ?: return null
    return (playlist["id"] as? JsonPrimitive)?.content
}

/** A playlist's header as a raw read reports it. */
private data class RawPlaylistHeader(val name: String, val comment: String?, val isPublic: Boolean, val readonly: Boolean?) {
    fun describe(): String = "$name|${comment.orEmpty()}|${if (isPublic) "public" else "private"}"
}

/**
 * Raw `/rest`, sharing nothing with the editor but the server: the independent witness every edit
 * is read back through, and the "other client" of the concurrency controls.
 */
private class RawPlaylistClient(request: PlaylistConformanceRequest) {
    private val transport = KtorLibraryEndpointTransport(
        LibraryBrowseRequest("conformance-raw", request.normalizedBaseUrl, request.username, request.password, request.allowLocalHttp),
        null, null, systemHostResolver(),
    )

    fun close() = transport.close()

    /** Six song ids from the server's first albums, in album order. */
    suspend fun songIds(): List<String> {
        val albums = parseReaderAlbumList(transport.checkedRequest("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "20")))
        val songs = mutableListOf<String>()
        for (album in albums) {
            songs += parseReaderAlbum(transport.checkedRequest("getAlbum", mapOf("id" to album.rawId)), album.rawId).second.map { it.rawId }
            if (songs.distinct().size >= 6) break
        }
        val distinct = songs.distinct()
        check(distinct.size >= 6) { "the conformance corpus must hold at least six songs; found ${distinct.size}" }
        return distinct.take(6)
    }

    suspend fun lastScan(): String? {
        val payload = parseLibraryEnvelope(transport.checkedRequest("getScanStatus"))?.payload
        return ((payload?.get("scanStatus") as? JsonObject)?.get("lastScan") as? JsonPrimitive)?.content
    }

    suspend fun create(name: String, songs: List<String>, formPost: Boolean = false): Pair<String, String> {
        val (body, _) = writeBody("createPlaylist", listOf("name" to name) + songs.map { "songId" to it }, formPost)
        return body to (createdPlaylistIdOf(body) ?: error("createPlaylist answered without a playlist"))
    }

    suspend fun replace(id: String, songs: List<String>) {
        writeBody("createPlaylist", listOf("playlistId" to id) + songs.map { "songId" to it })
    }

    /** A write; returns the error code, or null for `ok`. */
    suspend fun write(endpoint: String, parameters: List<Pair<String, String>>): Int? = code(endpoint, parameters)

    suspend fun writeBody(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean = false): Pair<String, Int?> {
        val body = transport.requestRepeated(endpoint, parameters, formPost).body
        return body to errorCode(body)
    }

    suspend fun code(endpoint: String, parameters: List<Pair<String, String>>): Int? =
        errorCode(transport.requestRepeated(endpoint, parameters, false).body)

    suspend fun body(endpoint: String): String = transport.request(endpoint, emptyMap()).body

    suspend fun entries(id: String): List<String>? {
        val body = transport.request("getPlaylist", mapOf("id" to id)).body
        if (errorCode(body) != null) return null
        return parseReaderPlaylist(body, id).second.map { it.rawId }
    }

    suspend fun header(id: String): RawPlaylistHeader? {
        val body = transport.request("getPlaylist", mapOf("id" to id)).body
        if (errorCode(body) != null) return null
        val record = parseReaderPlaylist(body, id).first
        return RawPlaylistHeader(record.name, record.comment, record.isPublic == true, record.readonly)
    }

    private fun errorCode(body: String): Int? {
        val envelope = parseLibraryEnvelope(body) ?: return -1
        if (envelope.status == "ok") return null
        return ((envelope.payload["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
    }
}

/** The bytes [parameters] take as a query string, encoded by the HTTP client the requests go out on. */
internal fun queryStringBytes(parameters: List<Pair<String, String>>): Int =
    URLBuilder("http://query.invalid/").apply { parameters.forEach { (name, value) -> this.parameters.append(name, value) } }
        .build().encodedQuery.length
