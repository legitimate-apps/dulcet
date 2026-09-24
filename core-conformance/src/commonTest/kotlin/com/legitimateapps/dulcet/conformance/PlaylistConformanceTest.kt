package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.PlaylistConformanceContract
import com.legitimateapps.dulcet.core.PlaylistConformanceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Playlists against the disposable reference server (spec §18.6). CONF-88 pins what the server
 * does with playlist writes — including the positional removal that makes a stale index remove the
 * wrong song; CONF-89..91 drive the PRODUCTION editor and read every edit back over raw `/rest`.
 */
class PlaylistConformanceTest {
    @Test
    fun conf88PlaylistWritesOnTheReferenceServer() = runTest(timeout = 5.minutes) {
        val facts = PlaylistConformanceContract.serverFacts(request())
        assertTrue(facts.createAnsweredWithPlaylist, "CONF-88: createPlaylist must answer with the playlist: $facts")
        assertTrue(facts.createdEntriesKeptDuplicates, "CONF-88: a duplicate entry must be kept in order: $facts")
        assertEquals(listOf(0, 2, 4), facts.removalOfOneAndThreeLeft, "CONF-88: indices apply to the list as the request found it")
        assertTrue(facts.outOfRangeRemovalAnsweredOk && facts.outOfRangeRemovalChangedNothing, "CONF-88: an index past the end is silently ignored: $facts")
        // The hazard itself, and the proof the probe could see it: it removed SOMETHING, and the wrong thing.
        assertTrue(facts.staleIndexRemoved != null, "CONF-88: the stale removal must have removed an entry: $facts")
        assertTrue(facts.staleIndexRemoved != facts.staleIndexIntended, "CONF-88: a stale index must remove a song other than the one aimed at: $facts")
        assertTrue(facts.replaceKeptId && facts.replaceKeptHeader && facts.replaceEntriesAsSent, "CONF-88: createPlaylist with playlistId replaces entries and keeps the playlist: $facts")
        assertTrue(facts.formPostReplaceEntriesAsSent, "CONF-88: the same replace as a form body: $facts")
        assertTrue(facts.largeFormPostReplaceSize >= 2_000, "CONF-88: the large replace must be large: $facts")
        assertTrue(facts.largeFormPostReplaceKeptOrder, "CONF-88: a ${facts.largeFormPostReplaceSize}-entry replace as one form body keeps its order: $facts")
        assertTrue(facts.emptyReplaceChangedNothing, "CONF-88: a replace with no songId is ignored: $facts")
        assertTrue(facts.emptyNameIgnored && facts.emptyCommentCleared, "CONF-88: empty name ignored, empty comment clears: $facts")
        assertTrue(facts.lastScanUnmovedByEdits, "CONF-88: playlist edits do not move lastScan: $facts")
        assertEquals(70, facts.codeAfterDelete)
    }

    @Test
    fun conf89EveryEditThroughTheEditorIsReadBack() = runTest(timeout = 5.minutes) {
        val result = PlaylistConformanceContract.editorRoundTrip(request())
        val byEdit = result.observations.associateBy { it.edit }
        assertEquals(
            listOf("create", "rename", "comment", "private", "append", "insert", "move", "remove", "offline replay", "empty", "delete"),
            result.observations.map { it.edit },
        )
        result.observations.filter { it.edit != "delete" }.forEach { observed ->
            assertEquals(observed.expectedEntries, observed.readBackEntries, "CONF-89 ${observed.edit}: read back $observed")
            observed.expectedHeader?.let { assertEquals(it, observed.readBackHeader, "CONF-89 ${observed.edit}: header $observed") }
            val expectedOutcome = if (observed.edit == "create") "Created" else "Saved"
            assertEquals(expectedOutcome, observed.outcome, "CONF-89 ${observed.edit}: $observed")
            assertTrue(observed.writes.isNotEmpty(), "CONF-89 ${observed.edit}: the edit must have been written: $observed")
        }
        // Appends are appends; removals are verified positions; a move or insert is one whole-list write.
        assertEquals(listOf("updatePlaylist"), byEdit.getValue("append").writes)
        listOf("remove", "empty").forEach { assertEquals(listOf("updatePlaylist"), byEdit.getValue(it).writes, "CONF-89 $it") }
        listOf("insert", "move").forEach { assertEquals(listOf("createPlaylist"), byEdit.getValue(it).writes, "CONF-89 $it") }
        assertTrue(result.offlineEditsPublishedWithoutRequests, "CONF-89: offline edits are published with no request")
        assertEquals(listOf("createPlaylist"), result.offlineReplayWrites, "CONF-89: three offline edits replay as one whole list")
        assertEquals("Saved", byEdit.getValue("delete").outcome)
        assertEquals(70, result.codeAfterDelete)
    }

    @Test
    fun conf89WithoutFormPostEveryWriteFitsOrIsRefused() = runTest(timeout = 5.minutes) {
        val result = PlaylistConformanceContract.editorWithoutFormPost(request())
        println(
            "CONF-89 without formPost: removal writes=${result.removal.writes.size} append writes=${result.append.writes.size} " +
                "reorder=${result.reorder.outcome} largest write=${result.writeParameterBytes.maxOrNull()} of ${result.budgetBytes} bytes",
        )
        listOf(result.removal, result.append).forEach { observed ->
            assertEquals("Saved", observed.outcome, "CONF-89 ${observed.edit} without formPost: $observed")
            assertEquals(observed.expectedEntries, observed.readBackEntries, "CONF-89 ${observed.edit} without formPost: read back")
            // The proof the batching ran: more than one write, every one of them an update.
            assertTrue(observed.writes.size > 1, "CONF-89 ${observed.edit} must have needed batches: ${observed.writes}")
            assertTrue(observed.writes.all { it == "updatePlaylist" }, "CONF-89 ${observed.edit}: ${observed.writes}")
        }
        assertTrue(result.reorder.outcome.startsWith("NotSaved") && "PlaylistWholeListWrite" in result.reorder.outcome, "CONF-89 reorder: ${result.reorder}")
        assertEquals(emptyList(), result.reorder.writes, "CONF-89: a whole list that does not fit is refused with nothing written")
        assertEquals(result.reorder.expectedEntries, result.reorder.readBackEntries, "CONF-89: the refused reorder changed nothing")
        assertTrue(result.writeParameterBytes.isNotEmpty() && result.writeParameterBytes.all { it <= result.budgetBytes }, "CONF-89: every write within ${result.budgetBytes}: ${result.writeParameterBytes}")
    }

    @Test
    fun conf89ALostCreateIsAdoptedOrDeletedOnlyOnProof() = runTest(timeout = 5.minutes) {
        val result = PlaylistConformanceContract.lostCreate(request())
        // Adopted by proof, against the server's real `created` times: one create, one playlist.
        assertEquals("Created", result.adoptedOutcome, "CONF-89 lost create: $result")
        assertEquals(1, result.adoptedCreateWrites, "CONF-89: a proven lost create is never sent again: $result")
        assertEquals(1, result.adoptedPlaylistsNamed, "CONF-89: $result")
        assertTrue(result.adoptedIdIsTheServers, "CONF-89: $result")
        // Deleted here after its answer was lost: proven, so deleted; nothing to tell.
        assertEquals(0, result.cancelledPlaylistsNamedAfter, "CONF-89 cancelled lost create: $result")
        assertTrue(result.cancelledOutcomes.none { it == "PossiblyCreated" }, "CONF-89: $result")
        // The negative beside it: an older namesake with the same songs is never deleted, and is told.
        assertTrue(result.olderSurvived, "CONF-89: an older playlist of that name must survive: $result")
        assertEquals(0, result.olderDeleteWrites, "CONF-89: $result")
        assertEquals("PossiblyCreated", result.olderOutcomes.lastOrNull(), "CONF-89: $result")
    }

    @Test
    fun conf90AStaleIndexIsPreventedNotSent() = runTest(timeout = 5.minutes) {
        val result = PlaylistConformanceContract.staleIndex(request())
        assertTrue(result.rawStaleRemovalRemovedTheWrongSong, "CONF-90: the raw hazard must reproduce first: $result")
        assertEquals("ChangedElsewhere", result.editorOutcome, "CONF-90: $result")
        assertEquals(emptyList(), result.editorWritesAfterConcurrentChange, "CONF-90: nothing may be written over a moved list")
        assertEquals(result.entriesAfterConcurrentChange, result.serverEntriesAfterRefusal, "CONF-90: no song removed")
        assertEquals("Saved", result.controlOutcome, "CONF-90 control: $result")
        assertEquals(result.controlExpected, result.controlReadBack, "CONF-90 control: the intended song, and only it, removed")
    }

    @Test
    fun conf91AnotherUsersPlaylistIsNotEditable() = runTest(timeout = 5.minutes) {
        val result = PlaylistConformanceContract.permissions(request())
        assertEquals(true, result.othersPublicReadonlyOnServer, "CONF-91: $result")
        assertEquals(false, result.othersPublicEditable, "CONF-91: the reader must not offer editing: $result")
        assertEquals("NotEditable", result.editorRecordForOthersPublic)
        assertEquals(50, result.rawRenameCode, "CONF-91: the server refuses too: $result")
        assertEquals(50, result.rawReplaceCode)
        assertEquals(50, result.rawDeleteCode)
        assertTrue(result.othersPublicUnchanged, "CONF-91: $result")
        assertEquals(70, result.othersPrivateReadCode, "CONF-91: another user's private playlist is not found: $result")
        // The positive control: the same user CAN edit its own playlist through the editor.
        assertEquals("Saved", result.ownRenameOutcome, "CONF-91 control: $result")
        assertEquals("CONF-91 own renamed", result.ownRenameReadBack)
        // The admin sees another user's playlist as read-only, though the server would take its edit.
        assertEquals(false, result.adminSeesOthersEditable, "CONF-91: $result")
        assertEquals(null, result.adminRawRenameOfOthersCode, "CONF-91: the server's admin override, recorded: $result")
        assertFalse(result.adminSeesOthersEditable == true)
    }

    private fun request() = PlaylistConformanceRequest(
        normalizedBaseUrl = disposableConformanceBaseUrl(),
        username = environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: ADMIN_USER,
        password = environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: ADMIN_PASSWORD,
        otherUsername = RESTRICTED_USER,
        otherPassword = RESTRICTED_PASSWORD,
        allowLocalHttp = true,
    )

    private companion object {
        // The disposable fixture's published constants (tools/conformance-env/health-check).
        const val ADMIN_USER = "dulcet-admin"
        const val ADMIN_PASSWORD = "dulcet-ci-canary-password"
        const val RESTRICTED_USER = "dulcet-restricted"
        const val RESTRICTED_PASSWORD = "dulcet-ci-restricted-password"
    }
}
