package com.legitimateapps.dulcet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidPlaylistChange
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.core.AndroidPlaylistPendingChange
import com.legitimateapps.dulcet.core.AndroidPlaylistPendingChanges
import com.legitimateapps.dulcet.library.PlaylistQuestion
import com.legitimateapps.dulcet.library.PlaylistQuestionEditor
import com.legitimateapps.dulcet.library.PlaylistQuestions
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A create in doubt (spec §18.6) on the phone: never adopted by guess, and never lost. The fake core
 * keeps its outbox across [PlaylistQuestions] instances the way the core's database does across
 * sessions, so a new instance over it is a reopened session on the same database.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistCreateInDoubtTest {
    @get:Rule val compose = createComposeRule()

    /** The core's outbox and the answers it gives, shared by every editor made over it. */
    private class FakeCore {
        val pending = mutableListOf<AndroidPlaylistPendingChange>()
        val chosen = mutableListOf<Pair<String, String?>>()
        var record = AndroidPlaylistEditRecord.Pending
        /** Held reads, when a test answers them itself. */
        val heldReads = mutableListOf<() -> Unit>()
        var holdReads = false

        fun waiting(localId: String, vararg candidates: String) {
            pending += AndroidPlaylistPendingChange(localId, AndroidPlaylistChange.Create, inDoubt = true, failures = 0,
                candidates = candidates.toList())
        }

        fun editor() = object : PlaylistQuestionEditor {
            override fun pendingChanges(completion: (AndroidPlaylistPendingChanges) -> Unit) {
                val read = { completion(AndroidPlaylistPendingChanges(pending.toList(), null)) }
                if (holdReads) heldReads += read else read()
            }

            override fun chooseCreated(localId: String, playlistId: String?, completion: (AndroidPlaylistEditResult) -> Unit) {
                chosen += localId to playlistId
                if (record == AndroidPlaylistEditRecord.Pending) {
                    // Recorded: the create no longer waits for a choice.
                    pending.replaceAll { if (it.playlistId == localId) it.copy(candidates = null) else it }
                }
                completion(AndroidPlaylistEditResult(record, null, null))
            }
        }
    }

    private val core = FakeCore()

    private fun show(questions: PlaylistQuestions) {
        compose.setContent {
            MaterialTheme {
                val asking by questions.asking.collectAsState()
                asking.firstOrNull()?.let { CreateInDoubt(questions, it, it.name, "q") }
            }
        }
    }

    @Test
    fun twoCandidatesAreNeverAdoptedAndNoneIsOfferedAsTheirs() {
        core.waiting("local-1", "pl-a", "pl-b")
        val questions = PlaylistQuestions(core.editor())
        questions.receive(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-a", "pl-b")))
        show(questions)

        compose.onNodeWithTag("q").assertExists()
        compose.onNodeWithTag("q.keep").assertDoesNotExist()
        compose.onNodeWithTag("q.another").assertIsEnabled()
        compose.onNodeWithTag("q.later").assertIsEnabled()
        val question = questions.asking.value.single()
        assertFalse(questions.answer(question, "pl-a"), "one of several is refused")
        assertFalse(questions.answer(question, "pl-b"), "either of them")
        assertEquals(emptyList(), core.chosen, "nothing was adopted")

        compose.onNodeWithTag("q.another").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Pair<String, String?>>("local-1" to null), core.chosen, "none of them: the create is sent")
        assertEquals(emptyList(), questions.asking.value, "and the question is answered")
    }

    @Test
    fun aLoneCandidateIsAdoptedByTheId() {
        core.waiting("local-1", "pl-a")
        val questions = PlaylistQuestions(core.editor())
        questions.receive(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-a")))
        show(questions)

        compose.onNodeWithTag("q.keep").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Pair<String, String?>>("local-1" to "pl-a"), core.chosen)
        assertEquals(emptyList(), questions.asking.value)
        assertEquals(emptyMap(), questions.awaitingChoice.value)
    }

    @Test
    fun aQuestionLeftUnansweredIsAskedAgainByTheNextSessionOnTheSameOutbox() {
        core.waiting("local-1", "pl-a", "pl-b")
        val first = PlaylistQuestions(core.editor())
        first.receive(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-a", "pl-b")))
        assertEquals(1, first.asking.value.size)
        first.close() // a rotation, a relaunch, process death: the outcome is never emitted again

        val reopened = PlaylistQuestions(core.editor())
        reopened.refresh()
        val asked = reopened.asking.value.single()
        assertEquals(PlaylistQuestion(PlaylistQuestion.Kind.WhichIsYours, "local-1", null, listOf("pl-a", "pl-b")), asked)
        show(reopened)
        compose.onNodeWithTag("q").assertExists()
        compose.onNodeWithTag("q.another").assertIsEnabled()
        compose.onNodeWithTag("q.keep").assertDoesNotExist()
    }

    @Test
    fun anAnswerTheCoreRefusesKeepsTheQuestionAndSaysSo() {
        core.waiting("local-1", "pl-a")
        core.record = AndroidPlaylistEditRecord.Invalid
        val questions = PlaylistQuestions(core.editor())
        questions.refresh()
        show(questions)

        compose.onNodeWithTag("q.keep").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Pair<String, String?>>("local-1" to "pl-a"), core.chosen)
        assertEquals(1, questions.asking.value.size, "a refused answer does not dismiss the question")
        compose.onNodeWithTag("q.note").assertExists()
    }

    @Test
    fun questionsQueueAndDecideLaterIsReaskedFromThePage() {
        core.waiting("local-1", "pl-a")
        core.waiting("local-2", "pl-c", "pl-d")
        val questions = PlaylistQuestions(core.editor())
        questions.refresh()
        questions.receive(AndroidPlaylistOutcome.PossibleDuplicate("local-2", "Night", listOf("pl-c", "pl-d")))
        assertEquals(listOf("local-1", "local-2"), questions.asking.value.map { it.localId }, "a later outcome queues; nothing is overwritten")
        assertEquals("Night", questions.asking.value[1].name)

        questions.defer(questions.asking.value.first())
        assertEquals(listOf("local-2"), questions.asking.value.map { it.localId })
        assertTrue("local-1" in questions.awaitingChoice.value, "a deferred create still waits, for its page")
        questions.refresh()
        assertEquals(listOf("local-2"), questions.asking.value.map { it.localId }, "a read does not re-ask what was deferred")

        questions.reask("local-1")
        assertEquals(listOf("local-2", "local-1"), questions.asking.value.map { it.localId })
    }

    @Test
    fun aReadIssuedBeforeAQuestionArrivedDoesNotDropIt() {
        val questions = PlaylistQuestions(core.editor())
        core.holdReads = true
        questions.refresh() // reads an outbox with nothing waiting yet
        core.waiting("local-1", "pl-a", "pl-b")
        questions.receive(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-a", "pl-b")))
        core.heldReads.removeAt(0).invoke()
        assertEquals(listOf("local-1"), questions.asking.value.map { it.localId })
    }

    @Test
    fun whileAnAnswerIsInFlightNoSecondIsSent() {
        core.waiting("local-1", "pl-a")
        var complete: (() -> Unit)? = null
        val slow = object : PlaylistQuestionEditor by core.editor() {
            override fun chooseCreated(localId: String, playlistId: String?, completion: (AndroidPlaylistEditResult) -> Unit) {
                core.chosen += localId to playlistId
                complete = {
                    core.pending.replaceAll { if (it.playlistId == localId) it.copy(candidates = null) else it }
                    completion(AndroidPlaylistEditResult(AndroidPlaylistEditRecord.Pending, null, null))
                }
            }
        }
        val questions = PlaylistQuestions(slow)
        questions.refresh()
        show(questions)
        compose.onNodeWithTag("q.keep").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("q.keep").assertIsNotEnabled()
        assertFalse(questions.answer(questions.asking.value.single(), null))
        assertEquals(1, core.chosen.size)
        compose.runOnIdle { complete!!.invoke() }
        compose.waitForIdle()
        assertEquals(emptyList(), questions.asking.value, "answered once the core recorded it")
    }
}
