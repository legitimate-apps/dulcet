package com.legitimateapps.dulcet.tv

import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidPlaylistChange
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.core.AndroidPlaylistPendingChange
import com.legitimateapps.dulcet.core.AndroidPlaylistPendingChanges
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.PlaylistQuestionEditor
import com.legitimateapps.dulcet.library.PlaylistQuestions
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A create in doubt on the TV (spec §18.6), answered with the remote: the question is a dialog over
 * the screen with its first control focused, a lone candidate can be kept, several are never offered,
 * Decide later leaves it waiting on the playlist's page, whose Choose… asks again. The fake core keeps
 * an outbox the way the core's database does, as the phone's PlaylistCreateInDoubtTest's does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvPlaylistQuestionTest {
    @get:Rule val compose = createComposeRule()

    private class FakeCore : PlaylistQuestionEditor {
        val pending = mutableListOf<AndroidPlaylistPendingChange>()
        val chosen = mutableListOf<Pair<String, String?>>()
        var record = AndroidPlaylistEditRecord.Pending
        /** An answer the test completes itself, when [hold] is set. */
        var held: (() -> Unit)? = null
        var hold = false

        fun waiting(localId: String, vararg candidates: String) {
            pending += AndroidPlaylistPendingChange(localId, AndroidPlaylistChange.Create, inDoubt = true, failures = 0,
                candidates = candidates.toList())
        }

        override fun pendingChanges(completion: (AndroidPlaylistPendingChanges) -> Unit) =
            completion(AndroidPlaylistPendingChanges(pending.toList(), null))

        override fun chooseCreated(localId: String, playlistId: String?, completion: (AndroidPlaylistEditResult) -> Unit) {
            chosen += localId to playlistId
            val answer = {
                if (record == AndroidPlaylistEditRecord.Pending) {
                    pending.replaceAll { if (it.playlistId == localId) it.copy(candidates = null) else it }
                }
                completion(AndroidPlaylistEditResult(record, null, null))
            }
            if (hold) held = answer else answer()
        }
    }

    private val core = FakeCore()

    /** The page's notices for [rawId] and the app-wide question, as the TV composes them. */
    private fun show(questions: PlaylistQuestions, rawId: String = "local-1", outcome: () -> AndroidPlaylistOutcome? = { null },
                     dismissOutcome: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Column { TvPlaylistNotices(questions, rawId, outcome(), dismissOutcome) }
                TvPlaylistQuestion(questions)
            }
        }
        compose.waitForIdle()
    }

    @Test fun aLoneCandidateIsKeptWithTheRemote() {
        core.waiting("local-1", "pl-a")
        val questions = PlaylistQuestions(core)
        questions.receive(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-a")))
        show(questions)

        awaitFocused("playlists.question.keep")
        compose.onNodeWithTag("playlists.question.line").assertTextContains("Road", substring = true)
        walk(Key.DirectionDown, "playlists.question.another", "playlists.question.later")
        walk(Key.DirectionUp, "playlists.question.another", "playlists.question.keep")
        press(Key.DirectionCenter)
        await("the question answered") { !exists("playlists.question") }
        assertEquals(listOf<Pair<String, String?>>("local-1" to "pl-a"), core.chosen)
        assertEquals(emptyMap(), questions.awaitingChoice.value)
        assertTrue(!exists("playlist.question.waiting"), "nothing waits on the page")
    }

    @Test fun severalCandidatesAreNeverOfferedAndDecideLaterIsAskedAgainFromThePage() {
        core.waiting("local-1", "pl-a", "pl-b")
        val questions = PlaylistQuestions(core)
        questions.refresh() // a relaunch: the question is rebuilt from the outbox, without a name
        show(questions)

        awaitFocused("playlists.question.another")
        assertTrue(!exists("playlists.question.keep"), "one of several is never offered as theirs")
        assertTrue(!exists("playlist.question.waiting"), "the page does not say it waits while the dialog asks")
        walk(Key.DirectionDown, "playlists.question.later")
        press(Key.DirectionCenter)
        await("the dialog closed") { !exists("playlists.question") }
        assertEquals(emptyList(), core.chosen, "Decide later sends nothing")
        assertTrue("local-1" in questions.awaitingChoice.value)

        compose.onNodeWithTag("playlist.question.waiting").assertExists()
        focus("playlist.question.choose")
        press(Key.DirectionCenter)
        awaitFocused("playlists.question.another")
        press(Key.DirectionCenter)
        await("the question answered") { !exists("playlists.question") }
        assertEquals(listOf<Pair<String, String?>>("local-1" to null), core.chosen, "none of them: the create is sent")
        await("the page no longer waits") { !exists("playlist.question.waiting") }
    }

    /** A disabled tv-material button keeps focus, so focus is still on Keep after the refusal. */
    @Test fun whileAnAnswerIsOutBackDoesNothingAndARefusalIsSaidWithFocusKept() {
        core.waiting("local-1", "pl-a")
        core.hold = true
        core.record = AndroidPlaylistEditRecord.Invalid
        val questions = PlaylistQuestions(core)
        questions.refresh()
        show(questions)

        awaitFocused("playlists.question.keep")
        press(Key.DirectionCenter)
        await("the answer is out") { core.held != null }
        compose.onNodeWithTag("playlists.question.keep").assertIsNotEnabled()
        compose.onNodeWithTag("playlists.question.later").assertIsNotEnabled()
        back()
        assertTrue(exists("playlists.question"), "Back does not drop a question whose answer is out")
        assertEquals(1, questions.asking.value.size)

        compose.runOnIdle { core.held!!.invoke() }
        await("the refusal said") { exists("playlists.question.note") }
        compose.onNodeWithTag("playlists.question.keep").assertIsEnabled()
        awaitFocused("playlists.question.keep")
        assertEquals(1, questions.asking.value.size, "a refused answer keeps the question")
        assertEquals(1, core.chosen.size)

        back()
        await("Back is Decide later") { !exists("playlists.question") }
        assertTrue("local-1" in questions.awaitingChoice.value)
        assertEquals(1, core.chosen.size, "Back sends nothing")
    }

    @Test fun aCreateThatMayHaveBeenMadeIsDismissed() {
        val questions = PlaylistQuestions(core)
        questions.receive(AndroidPlaylistOutcome.PossiblyCreated("local-9", "Old Road", listOf("pl-x")))
        show(questions, rawId = "local-9")

        awaitFocused("playlists.question.dismiss")
        compose.onNodeWithTag("playlists.question.line").assertTextContains("Old Road", substring = true)
        assertTrue(!exists("playlists.question.keep") && !exists("playlists.question.another"))
        press(Key.DirectionCenter)
        await("dismissed") { !exists("playlists.question") }
        assertEquals(emptyList(), core.chosen)
    }

    @Test fun aChangeThatDidNotLandIsSaidOnThePageAndDismissed() {
        val questions = PlaylistQuestions(core)
        var outcome by mutableStateOf<AndroidPlaylistOutcome?>(
            AndroidPlaylistOutcome.NotSaved("pl-1", AndroidPlaylistChange.Entries, DomainError.Auth.InvalidCredentials))
        show(questions, rawId = "pl-1", outcome = { outcome }, dismissOutcome = { outcome = null })

        compose.onNodeWithTag("playlist.outcome").assertTextContains("Couldn't save", substring = true)
        focus("playlist.outcome.dismiss")
        press(Key.DirectionCenter)
        await("the line dismissed") { !exists("playlist.outcome") }

        outcome = AndroidPlaylistOutcome.Saved("pl-1", AndroidPlaylistChange.Entries)
        compose.waitForIdle()
        assertTrue(!exists("playlist.outcome"), "a change that landed says nothing")
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String) = runCatching {
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    private fun await(what: String, condition: () -> Boolean) {
        runCatching { compose.waitUntil(5_000, condition) }.onFailure { throw AssertionError("Timed out waiting for $what", it) }
    }

    private fun awaitFocused(tag: String) = await("$tag focused") { focused(tag) }

    /** A key to whatever holds focus, in whichever window, as the remote sends it. */
    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun walk(key: Key, vararg tags: String) = tags.forEach { tag ->
        press(key)
        awaitFocused(tag)
    }

    /** Puts focus on a page control, where the remote's walk to it would land. */
    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        awaitFocused(tag)
    }

    /** The remote's Back, to the dialog window that holds the question. */
    private fun back() {
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
