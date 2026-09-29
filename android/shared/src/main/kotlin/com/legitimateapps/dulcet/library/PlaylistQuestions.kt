package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidLibraryPlaylists
import com.legitimateapps.dulcet.core.AndroidPlaylistChange
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.core.AndroidPlaylistPendingChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A create in doubt the person has to answer (spec §18.6). [name] is the name the create sent, when
 * this session knows it; the core's pending change does not carry it, so a question rebuilt after a
 * relaunch has none and the screen names it from the playlist it shows under [localId].
 */
public data class PlaylistQuestion(
    val kind: Kind,
    val localId: String,
    val name: String?,
    val candidates: List<String>,
) {
    public enum class Kind {
        /** `PossibleDuplicate`: the create may already be one of [candidates]. */
        WhichIsYours,

        /** `PossiblyCreated`: a create deleted here may have made one of [candidates]. */
        MaybeCreated,
    }

    /**
     * The one candidate the person can be offered, or null. Candidates share the sent name and may
     * include another client's playlist of that name, and the core names them by id only, so two or
     * more cannot be told apart here: adopting one would write every later edit into a playlist
     * chosen by guess. Only a lone candidate is offered.
     */
    val loneCandidate: String? get() = candidates.singleOrNull()
}

/** The part of the core's playlist editor a question needs; [AndroidLibraryPlaylists] in production. */
public interface PlaylistQuestionEditor {
    public fun pendingChanges(completion: (AndroidPlaylistPendingChanges) -> Unit)
    public fun chooseCreated(localId: String, playlistId: String?, completion: (AndroidPlaylistEditResult) -> Unit)
}

internal fun AndroidLibraryPlaylists.asQuestionEditor(): PlaylistQuestionEditor = object : PlaylistQuestionEditor {
    override fun pendingChanges(completion: (AndroidPlaylistPendingChanges) -> Unit) =
        this@asQuestionEditor.pendingChanges(completion)

    override fun chooseCreated(localId: String, playlistId: String?, completion: (AndroidPlaylistEditResult) -> Unit) =
        this@asQuestionEditor.chooseCreated(localId, playlistId, completion)
}

/**
 * The creates in doubt waiting for the person, derived from the core's outbox rather than held only
 * in memory (§18.6). The core emits `PossibleDuplicate` once and then keeps the create waiting, so a
 * question kept only from the outcome stream is lost on a rotation, a relaunch or process death and
 * the playlist stays "not saved" for good. Here every create the core's [PlaylistQuestionEditor.pendingChanges]
 * lists with candidates is asked again when this starts ([refresh]) and after every outcome that can
 * settle or change one.
 *
 * Questions queue, oldest first; a later outcome replaces only the question about the same create.
 * One is dismissed only when the core recorded the person's answer ([AndroidPlaylistEditRecord.Pending]
 * or [AndroidPlaylistEditRecord.CompactedAway]); any other answer keeps it and is returned for the
 * screen to say. "Decide later" [defer]s one: it leaves the queue but stays in [awaitingChoice], so
 * the playlist's page can [reask] it. Every call is on the main thread.
 */
public class PlaylistQuestions(private val editor: PlaylistQuestionEditor) : AutoCloseable {
    private val queue = MutableStateFlow<List<PlaylistQuestion>>(emptyList())
    private val awaiting = MutableStateFlow<Map<String, PlaylistQuestion>>(emptyMap())
    private val answering = MutableStateFlow<Set<String>>(emptySet())

    /** The questions to ask now, oldest first. */
    public val asking: StateFlow<List<PlaylistQuestion>> = queue.asStateFlow()

    /** Every create waiting for the person's choice, deferred ones included, by local id. */
    public val awaitingChoice: StateFlow<Map<String, PlaylistQuestion>> = awaiting.asStateFlow()

    /** Creates whose answer the core has not returned yet: their controls are disabled. */
    public val inFlight: StateFlow<Set<String>> = answering.asStateFlow()

    private val deferred = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()

    /** Counts questions as they arrive, so a read issued before one arrived never drops it as settled. */
    private var arrivals = 0
    private val arrivedAt = mutableMapOf<String, Int>()
    private var closed = false

    /** One outcome from the core's stream. */
    public fun receive(outcome: AndroidPlaylistOutcome) {
        if (closed) return
        when (outcome) {
            is AndroidPlaylistOutcome.PossibleDuplicate -> {
                // The core asked afresh (a first ask, or another create settled a candidate): ask now.
                arrivals += 1
                arrivedAt[outcome.localId] = arrivals
                names[outcome.localId] = outcome.name
                deferred -= outcome.localId
                enqueue(PlaylistQuestion(PlaylistQuestion.Kind.WhichIsYours, outcome.localId, outcome.name, outcome.candidates))
            }
            is AndroidPlaylistOutcome.PossiblyCreated ->
                enqueue(PlaylistQuestion(PlaylistQuestion.Kind.MaybeCreated, outcome.localId, outcome.name, outcome.candidates))
            is AndroidPlaylistOutcome.Created, is AndroidPlaylistOutcome.NotSaved -> refresh()
            is AndroidPlaylistOutcome.Saved, is AndroidPlaylistOutcome.ChangedElsewhere, is AndroidPlaylistOutcome.Diverged,
            is AndroidPlaylistOutcome.Held, is AndroidPlaylistOutcome.Superseded, is AndroidPlaylistOutcome.NotRecorded -> Unit
        }
    }

    /**
     * Reads the core's pending changes: asks again about each create still waiting for a choice
     * (except one deferred), and drops a question whose create no longer waits.
     */
    public fun refresh() {
        if (closed) return
        val issuedAt = arrivals
        editor.pendingChanges { pending ->
            if (closed || pending.failure != null) return@pendingChanges
            val waiting = pending.changes.mapNotNull { change ->
                val candidates = change.candidates?.takeIf { it.isNotEmpty() && change.change == AndroidPlaylistChange.Create }
                    ?: return@mapNotNull null
                PlaylistQuestion(PlaylistQuestion.Kind.WhichIsYours, change.playlistId,
                    names[change.playlistId] ?: awaiting.value[change.playlistId]?.name, candidates)
            }
            val waitingIds = waiting.map { it.localId }.toSet()
            fun settled(question: PlaylistQuestion) = question.kind == PlaylistQuestion.Kind.WhichIsYours &&
                question.localId !in waitingIds && (arrivedAt[question.localId] ?: 0) <= issuedAt
            queue.update { questions -> questions.filterNot(::settled) }
            awaiting.update { map -> map.filterValues { !settled(it) } }
            waiting.forEach { question ->
                awaiting.update { it + (question.localId to question) }
                if (question.localId !in deferred) enqueue(question)
            }
        }
    }

    /**
     * The person's answer to [question]: [candidate] is theirs, or null for none of them (the create
     * is sent). A candidate is taken only when it is the question's lone one, and a question already
     * being answered takes nothing: false then, and nothing is sent. [completion] gets the core's answer.
     */
    public fun answer(question: PlaylistQuestion, candidate: String?, completion: (AndroidPlaylistEditResult) -> Unit = {}): Boolean {
        if (closed || question.kind != PlaylistQuestion.Kind.WhichIsYours) return false
        if (candidate != null && candidate != question.loneCandidate) return false
        if (question.localId in answering.value) return false
        answering.update { it + question.localId }
        editor.chooseCreated(question.localId, candidate) { result ->
            answering.update { it - question.localId }
            if (result.failure == null && (result.record == AndroidPlaylistEditRecord.Pending ||
                    result.record == AndroidPlaylistEditRecord.CompactedAway)) {
                remove(question)
                awaiting.update { it - question.localId }
                deferred -= question.localId
            }
            // Whatever the core recorded, what still waits is read back and asked again.
            refresh()
            completion(result)
        }
        return true
    }

    /** "Decide later" (or, for a create that may have been made, "Keep it"): the next question comes. */
    public fun defer(question: PlaylistQuestion) {
        if (closed) return
        remove(question)
        if (question.kind == PlaylistQuestion.Kind.WhichIsYours) deferred += question.localId
    }

    /** Asks again about the create [localId] the person deferred: the playlist page's "Choose…". */
    public fun reask(localId: String) {
        if (closed) return
        val question = awaiting.value[localId] ?: return
        deferred -= localId
        enqueue(question)
    }

    override fun close() {
        closed = true
        queue.value = emptyList()
    }

    private fun enqueue(question: PlaylistQuestion) {
        if (question.kind == PlaylistQuestion.Kind.WhichIsYours) awaiting.update { it + (question.localId to question) }
        queue.update { questions ->
            val index = questions.indexOfFirst { it.localId == question.localId && it.kind == question.kind }
            if (index >= 0) questions.toMutableList().also { it[index] = question } else questions + question
        }
    }

    private fun remove(question: PlaylistQuestion) {
        queue.update { questions -> questions.filterNot { it.localId == question.localId && it.kind == question.kind } }
    }
}
