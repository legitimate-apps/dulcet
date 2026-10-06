import Foundation
import Testing
@testable import DulcetKit

// The Swift half of playlist editing (spec §18.6) and lyrics (§18.4): the words for the core's
// records and outcomes, what the session's editor keeps, the positional-edit context a playlist
// page builds, and the lyrics panel's model. The core behind the real adapters is covered by the
// core's own suites (ApplePlaylistLyricsFacadeTest and the playlist/lyrics suites).

// MARK: - Fakes

@MainActor
private final class RecordingPlaylists: DulcetPlaylistEditing {
    private(set) var edits: [DulcetPlaylistEdit] = []
    var completions: [@MainActor (DulcetPlaylistEditResult) -> Void] = []
    var outcomeHandler: (@MainActor (DulcetPlaylistOutcome) -> Void)?
    private(set) var cancelled = false
    /// What the core's outbox holds for `pendingChanges`; nil answers as a failed read.
    var pending: [DulcetPlaylistPendingChange]? = []
    private(set) var pendingReads = 0

    func pendingPlaylistChanges(completion: @escaping @MainActor ([DulcetPlaylistPendingChange]?) -> Void) {
        pendingReads += 1
        completion(pending)
    }

    func editPlaylist(_ edit: DulcetPlaylistEdit, completion: @escaping @MainActor (DulcetPlaylistEditResult) -> Void) {
        edits.append(edit)
        completions.append(completion)
    }

    func subscribePlaylistOutcomes(
        _ handler: @escaping @MainActor (DulcetPlaylistOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        outcomeHandler = handler
        return Cancellation { [weak self] in self?.cancelled = true }
    }

    func complete(_ index: Int, record: String?, localID: String? = nil, errorKind: String? = nil) {
        completions[index](DulcetPlaylistEditResult(record: record, localID: localID, errorKind: errorKind))
    }
}

@MainActor
private final class Cancellation: DulcetLibraryReaderCancellable {
    private let action: () -> Void
    init(_ action: @escaping () -> Void) { self.action = action }
    func cancel() { action() }
}

@MainActor
private final class RecordingLyrics: DulcetLyricsReading {
    private(set) var reads: [(DulcetLyricsRequest, DulcetLyricsReadMode)] = []
    var completions: [@MainActor (DulcetLyricsPublication) -> Void] = []

    func readLyrics(
        _ request: DulcetLyricsRequest,
        mode: DulcetLyricsReadMode,
        completion: @escaping @MainActor (DulcetLyricsPublication) -> Void
    ) {
        reads.append((request, mode))
        completions.append(completion)
    }
}

private func outcome(
    _ kind: String,
    playlist: String = "p1",
    change: String? = "entries",
    localID: String? = nil,
    name: String? = nil,
    candidates: [String] = [],
    fields: [String] = [],
    errorKind: String? = nil
) -> DulcetPlaylistOutcome {
    DulcetPlaylistOutcome(
        kind: kind, playlistID: playlist, change: change, localID: localID, name: name,
        candidates: candidates, fields: fields, errorKind: errorKind
    )!
}

private func playlistItem(
    _ rawID: String = "p1",
    editable: Bool = true,
    pending: Bool = false,
    local: Bool = false,
    owner: String? = "someone",
    kind: String = "playlist"
) -> DulcetReaderItem {
    DulcetReaderItem(
        kind: kind, providerInstanceID: "provider-reader", rawID: rawID, title: "Mix",
        artistName: nil, artistRawID: nil, albumTitle: nil, albumRawID: nil, year: nil, genre: nil,
        durationMilliseconds: nil, songCount: 3, albumCount: nil, discNumber: nil, trackNumber: nil,
        sourceContainer: nil, artworkKey: nil, owner: owner, favourite: nil, rating: nil, playCount: nil,
        playability: nil, detailComplete: true, metadataMissing: false,
        editable: editable, pendingChanges: pending, local: local
    )!
}

private func track(_ rawID: String) -> DulcetReaderItem {
    DulcetReaderItem(
        kind: "track", providerInstanceID: "provider-reader", rawID: rawID, title: rawID,
        artistName: "Artist", artistRawID: nil, albumTitle: nil, albumRawID: nil, year: nil, genre: nil,
        durationMilliseconds: 1_000, songCount: nil, albumCount: nil, discNumber: nil, trackNumber: nil,
        sourceContainer: nil, artworkKey: nil, owner: nil, favourite: nil, rating: nil, playCount: nil,
        playability: "streamable", detailComplete: true, metadataMissing: false
    )!
}

private func playlistWindow(
    header: DulcetReaderItem?,
    tracks: [String],
    freshness: DulcetReaderFreshness = .live,
    coverage: String = "complete",
    leadingOffset: Int = 0
) -> DulcetLibraryWindow {
    DulcetLibraryWindow(
        sequence: 1, freshness: freshness, coverage: coverage, total: tracks.count,
        leadingOffset: leadingOffset, header: header, items: tracks.map(track), itemsState: "present",
        order: "server", anchorRawID: nil, anchorIndex: nil
    )
}

private let playlistID = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "p1")

// MARK: - Records and outcomes

@Test
func aRecordOutsideTheVocabularyIsTheDevicesFailureNeverASuccess() {
    let unknown = DulcetPlaylistEditResult(record: "savedProbably", localID: nil, errorKind: nil)
    #expect(unknown.record == nil)
    #expect(unknown.error == .internalFailure)
    #expect(DulcetPlaylistPresentation.notice(for: unknown, edit: .delete(playlistID: "p1")) == DulcetStrings.playlistEditNotRecorded)

    #expect(DulcetPlaylistOutcome(
        kind: "maybeSaved", playlistID: "p1", change: nil, localID: nil, name: nil,
        candidates: [], fields: [], errorKind: nil
    ) == nil)
}

@Test
func everyRefusedEditSaysWhyAndAPendingOneSaysNothingButAnAdd() {
    func notice(_ record: String, _ edit: DulcetPlaylistEdit = .delete(playlistID: "p1")) -> String? {
        DulcetPlaylistPresentation.notice(for: DulcetPlaylistEditResult(record: record, localID: nil, errorKind: nil), edit: edit)
    }
    #expect(notice("pending") == nil)
    #expect(notice("compactedAway") == nil)
    #expect(notice("unchanged") == nil)
    #expect(notice("pending", .append(playlistID: "p1", songs: ["t"])) == DulcetStrings.playlistAdded)
    #expect(notice("pending", .appendAlbum(playlistID: "p1", albumID: "a")) == DulcetStrings.playlistAdded)
    #expect(notice("invalid", .create(name: " ", songs: [])) == DulcetStrings.playlistNameRequired)
    #expect(notice("invalid", .rename(playlistID: "p1", name: "")) == DulcetStrings.playlistNameRequired)
    #expect(notice("invalid") == DulcetStrings.playlistEditInvalid)
    #expect(notice("notEditable") == DulcetStrings.playlistNotEditable)
    #expect(notice("notCached") == DulcetStrings.playlistNotCached)
    #expect(notice("staleView") == DulcetStrings.playlistStaleView)
    #expect(notice("deleted") == DulcetStrings.playlistDeleted)
    #expect(notice("notRecorded") == DulcetStrings.playlistEditNotRecorded)
    #expect(notice("alreadySent") == DulcetStrings.playlistAlreadySent)

    // A closed or cancelled client is the session ending, not something to tell the person.
    let closed = DulcetPlaylistEditResult(record: nil, localID: nil, errorKind: "closed")
    #expect(DulcetPlaylistPresentation.notice(for: closed, edit: .delete(playlistID: "p1")) == nil)
}

@Test
func aHeldChangeCanBeWithdrawnAndOtherFailuresOnlyExplained() {
    let held = DulcetPlaylistPresentation.problem(for: outcome("held", change: "details", errorKind: "forbidden"))
    #expect(held?.withdrawable == .details)
    #expect(held?.message.isEmpty == false)

    let busy = outcome("held", errorKind: "serverBusy")
    #expect(DulcetPlaylistPresentation.notice(for: busy) == DulcetStrings.playlistHeldBusy)

    for kind in ["notSaved", "changedElsewhere", "diverged", "superseded", "notRecorded"] {
        let problem = DulcetPlaylistPresentation.problem(for: outcome(kind, errorKind: "server"))
        #expect(problem != nil, "\(kind) must stay on the page")
        #expect(problem?.withdrawable == nil, "\(kind) has nothing left to withdraw")
    }
    #expect(DulcetPlaylistPresentation.problem(for: outcome("saved")) == nil)
    #expect(DulcetPlaylistPresentation.notice(for: outcome("saved")) == nil)
}

@Test
func aCreateInDoubtAsksAndIsNeverAnsweredForThePerson() {
    let duplicate = outcome("possibleDuplicate", change: "create", localID: "local-1", name: "Mix", candidates: ["s1", "s2"])
    let question = DulcetPlaylistPresentation.question(for: duplicate)
    #expect(question?.kind == .whichIsYours)
    #expect(question?.candidates == ["s1", "s2"])
    #expect(question?.localID == "local-1")

    let maybe = outcome("possiblyCreated", change: "create", localID: "local-2", name: "Mix", candidates: ["s3"])
    #expect(DulcetPlaylistPresentation.question(for: maybe)?.kind == .deleteMaybeCreated)
    #expect(DulcetPlaylistPresentation.question(for: outcome("saved")) == nil)
}

@Test
func aPlaylistsStatusSaysWhoseItIsAndWhetherItIsOnTheServer() {
    #expect(DulcetPlaylistPresentation.status(of: playlistItem()) == nil)
    #expect(DulcetPlaylistPresentation.status(of: playlistItem(pending: true)) == DulcetStrings.playlistChangesPending)
    #expect(DulcetPlaylistPresentation.status(of: playlistItem(pending: true, local: true)) == DulcetStrings.playlistNotYetOnServer)
    #expect(DulcetPlaylistPresentation.status(of: playlistItem(editable: false)) == DulcetStrings.playlistOwnedBy("someone"))
    // The playlist-only flags never leak onto another kind.
    let album = playlistItem(editable: true, pending: true, local: true, kind: "album")
    #expect(!album.isEditable && !album.hasPendingChanges && !album.isLocalPlaylist)
}

// MARK: - The editor

@Test @MainActor
func theEditorKeepsProblemsQuestionsAndCreatedIDsFromOutcomes() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)

    fake.outcomeHandler?(outcome("held", errorKind: "forbidden"))
    #expect(editor.problems["p1"]?.withdrawable == .entries)
    fake.outcomeHandler?(outcome("saved"))
    #expect(editor.problems["p1"] == nil)

    fake.outcomeHandler?(outcome("created", playlist: "server-9", change: "create", localID: "local-1"))
    #expect(editor.created == ["local-1": "server-9"])
    #expect(editor.resolvedID("local-1") == "server-9")
    #expect(editor.resolvedID("other") == "other")

    fake.outcomeHandler?(outcome("possibleDuplicate", change: "create", localID: "local-2", name: "Mix", candidates: ["s1"]))
    let question = try? #require(editor.question)
    editor.answer(question!, choosing: "s1")
    #expect(editor.question == nil)
    guard case let .chooseCreated(localID, chosen) = fake.edits.last else {
        Issue.record("answering must send chooseCreated, got \(String(describing: fake.edits.last))")
        return
    }
    #expect(localID == "local-2" && chosen == "s1")

    editor.close()
    #expect(fake.cancelled)
}

private func waiting(_ localID: String, name: String = "Mix", candidates: [String]) -> DulcetPlaylistPendingChange {
    DulcetPlaylistPendingChange(playlistID: localID, change: "create", name: name, candidates: candidates)
}

// Review blocker 1: a playlist row's context-menu Delete records nothing until the person
// confirms, through the same dialog as the playlist's page.
@Test @MainActor
func aContextMenuDeleteIsRecordedOnlyOnceThePersonConfirms() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)

    editor.requestDeletion(of: "p1")
    #expect(editor.deletionToConfirm == "p1")
    #expect(fake.edits.isEmpty, "asking to delete must not record a delete")
    editor.cancelDeletion()
    #expect(editor.deletionToConfirm == nil)
    editor.confirmDeletion()
    #expect(fake.edits.isEmpty, "a cancelled request cannot be confirmed afterwards")

    editor.requestDeletion(of: "p2")
    editor.confirmDeletion()
    #expect(fake.edits == [.delete(playlistID: "p2")])
    #expect(editor.deletionToConfirm == nil)
}

// Review blocker 2: a create in doubt is never left waiting with no way to answer. Questions
// queue rather than overwrite; Decide Later leaves the page able to ask; a new editor asks
// again from the core's pending changes.
@Test @MainActor
func aSecondQuestionQueuesBehindTheFirstAndDecideLaterLeavesAWayBack() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    fake.pending = [waiting("local-1", candidates: ["s1"]), waiting("local-2", name: "Other", candidates: ["s2"])]

    fake.outcomeHandler?(outcome("possibleDuplicate", playlist: "local-1", change: "create", localID: "local-1", name: "Mix", candidates: ["s1"]))
    fake.outcomeHandler?(outcome("possibleDuplicate", playlist: "local-2", change: "create", localID: "local-2", name: "Other", candidates: ["s2"]))
    fake.outcomeHandler?(outcome("possiblyCreated", playlist: "local-3", change: "create", localID: "local-3", name: "Gone", candidates: ["s3"]))
    #expect(editor.questions.map(\.localID) == ["local-1", "local-2", "local-3"], "a later outcome overwrote an earlier question")

    let first = try! #require(editor.question)
    editor.dismissQuestion(first)
    #expect(editor.question?.localID == "local-2", "Decide Later must move on to the next question")
    #expect(!editor.questions.contains { $0.localID == "local-1" }, "a deferred question is not re-asked at once")
    #expect(editor.awaitingChoice["local-1"] != nil, "the page of a deferred create must be able to ask again")

    editor.reask("local-1")
    #expect(editor.questions.last?.localID == "local-1")

    // Keep It is an answer: the maybe-created question goes and deletes nothing.
    let maybe = try! #require(editor.questions.first { $0.kind == .deleteMaybeCreated })
    editor.dismissQuestion(maybe)
    #expect(!editor.questions.contains { $0.kind == .deleteMaybeCreated })
    #expect(fake.edits.isEmpty)

    // A create the core no longer keeps waiting is not asked about.
    fake.pending = [waiting("local-1", candidates: ["s1"])]
    editor.refreshQuestions()
    #expect(editor.questions.map(\.localID) == ["local-1"])
    #expect(editor.awaitingChoice["local-2"] == nil)

    // A failed read drops nothing.
    fake.pending = nil
    editor.refreshQuestions()
    #expect(editor.questions.map(\.localID) == ["local-1"])
}

@Test @MainActor
func aNewEditorAsksAgainWhatTheCoreStillKeepsWaitingAndWhatItsPredecessorLeft() {
    let fake = RecordingPlaylists()
    fake.pending = [
        DulcetPlaylistPendingChange(playlistID: "p9", change: "details", name: nil, candidates: nil),
        waiting("local-1", candidates: ["s1", "s2"]),
    ]
    let carried = DulcetPlaylistQuestion(kind: .deleteMaybeCreated, localID: "local-3", name: "Gone", candidates: ["s3"])
    let editor = DulcetPlaylistEditor(editing: fake, session: nil, carried: [carried])
    #expect(fake.pendingReads == 1, "the editor must read the core's pending changes when it starts")
    #expect(editor.questions.map(\.localID) == ["local-3", "local-1"])
    let asked = try! #require(editor.questions.last)
    #expect(asked.kind == .whichIsYours && asked.name == "Mix" && asked.candidates == ["s1", "s2"])
    #expect(editor.unansweredDeletionQuestions == [carried], "a maybe-created question must survive to the next editor")

    // A question the core asks afresh after a deferral is asked at once.
    editor.dismissQuestion(asked)
    #expect(!editor.questions.contains { $0.localID == "local-1" })
    fake.outcomeHandler?(outcome("possibleDuplicate", playlist: "local-1", change: "create", localID: "local-1", name: "Mix", candidates: ["s2"]))
    #expect(editor.questions.last?.candidates == ["s2"])

    editor.close()
    #expect(editor.questions.isEmpty)
    editor.refreshQuestions()
    #expect(fake.pendingReads == 2, "a closed editor reads nothing")
}

// Review blocker 3: candidates are named by id only and share the sent name, so two or more
// cannot be told apart -- neither adopting nor deleting one is offered, and a guessed answer is
// refused.
@Test @MainActor
func aCandidateIsOfferedOnlyWhenItIsTheOnlyOne() {
    let many = DulcetPlaylistQuestion(kind: .whichIsYours, localID: "local-1", name: "Mix", candidates: ["s1", "s2"])
    #expect(DulcetPlaylistPresentation.choices(for: many) == [.createAgain, .decideLater])
    let manyDeletions = DulcetPlaylistQuestion(kind: .deleteMaybeCreated, localID: "local-2", name: "Mix", candidates: ["s1", "s2"])
    #expect(DulcetPlaylistPresentation.choices(for: manyDeletions) == [.keep])
    #expect(DulcetPlaylistPresentation.message(for: many) == DulcetStrings.playlistWhichIsYoursManyMessage("Mix", 2))

    let one = DulcetPlaylistQuestion(kind: .whichIsYours, localID: "local-1", name: "Mix", candidates: ["s1"])
    #expect(DulcetPlaylistPresentation.choices(for: one) == [.adopt("s1"), .createAgain, .decideLater])
    let oneDeletion = DulcetPlaylistQuestion(kind: .deleteMaybeCreated, localID: "local-2", name: "Mix", candidates: ["s1"])
    #expect(DulcetPlaylistPresentation.choices(for: oneDeletion) == [.delete("s1"), .keep])

    let fake = RecordingPlaylists()
    fake.pending = [waiting("local-1", candidates: ["s1", "s2"])]
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    fake.outcomeHandler?(outcome("possiblyCreated", playlist: "local-2", change: "create", localID: "local-2", name: "Mix", candidates: ["s1", "s2"]))
    editor.answer(many, choosing: "s2")
    editor.answer(manyDeletions, choosing: "s1")
    #expect(fake.edits.isEmpty, "one of several candidates was adopted or deleted on a guess")
    #expect(editor.questions.count == 2, "a refused answer must leave its question asked")

    editor.answer(many, choosing: nil)
    #expect(fake.edits == [.chooseCreated(localID: "local-1", playlistID: nil)])
}

@Test @MainActor
func aWithdrawnChangeClearsItsProblemOnlyOnceTheCoreRecordedIt() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    fake.outcomeHandler?(outcome("held", errorKind: "forbidden"))

    editor.perform(.withdraw(playlistID: "p1", change: .entries))
    #expect(editor.problems["p1"] != nil, "cleared before the core answered")
    fake.complete(0, record: nil, errorKind: "internalFailure")
    #expect(editor.problems["p1"] != nil, "a withdrawal that never ran leaves the problem")

    editor.perform(.withdraw(playlistID: "p1", change: .entries))
    fake.complete(1, record: "compactedAway")
    #expect(editor.problems["p1"] == nil)
}

@Test @MainActor
func aRenameRefusedBecauseThePlaylistIsGoneIsSaidOnItsPage() {
    // The page learned the playlist is gone while the rename prompt was open: the core refuses
    // the rename before anything is sent, and the page must keep saying so after the notice.
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    editor.perform(.rename(playlistID: "p1", name: "New"))
    #expect(editor.problems["p1"] == nil, "said before the core answered")
    fake.complete(0, record: "deleted")
    #expect(editor.problems["p1"] == DulcetPlaylistProblem(message: DulcetStrings.playlistDeleted, withdrawable: nil))

    // Any other edit naming the playlist says it the same way; a refusal about the tap does not.
    editor.perform(.append(playlistID: "p2", songs: ["t1"]))
    fake.complete(1, record: "deleted")
    #expect(editor.problems["p2"]?.message == DulcetStrings.playlistDeleted)
    for (index, record) in ["staleView", "notCached", "notEditable", "invalid", "pending"].enumerated() {
        editor.perform(.rename(playlistID: "p3", name: "New"))
        fake.complete(2 + index, record: record)
    }
    editor.perform(.rename(playlistID: "p3", name: "New"))
    fake.complete(7, record: nil, errorKind: "internalFailure")
    #expect(editor.problems["p3"] == nil, "only a gone playlist is a state of the page")
}

@Test @MainActor
func aNewPlaylistFromAnAlbumAddsTheAlbumUnderTheLocalIDOnlyOnceCreated() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    editor.addition = .album("album-7", title: "Album")

    editor.createPlaylist(named: "Mix", with: editor.addition)
    #expect(editor.addition == nil)
    #expect(fake.edits == [.create(name: "Mix", songs: [])])

    fake.complete(0, record: "pending", localID: "local-1")
    #expect(fake.edits.last == .appendAlbum(playlistID: "local-1", albumID: "album-7"))

    // A refused create adds nothing.
    editor.addition = .album("album-8", title: "Other")
    editor.createPlaylist(named: "", with: editor.addition)
    fake.complete(2, record: "invalid")
    #expect(fake.edits.count == 3)
}

@Test @MainActor
func addingSongsAppendsInOrderAndClosesTheChooser() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    editor.addition = .songs(["t2", "t1", "t2"], title: "Songs")
    editor.add(editor.addition!, to: "p1")
    #expect(editor.addition == nil)
    #expect(fake.edits == [.append(playlistID: "p1", songs: ["t2", "t1", "t2"])])
}

@Test @MainActor
func theChooserTakesOneChoiceAndASecondTapWhileItClosesAddsNothing() {
    let fake = RecordingPlaylists()
    let editor = DulcetPlaylistEditor(editing: fake, session: nil)
    let songs = DulcetPlaylistAddition.songs(["t1"], title: "Song")
    editor.addition = songs
    // The sheet holds its own copy of the addition until it has left the screen, so a quick
    // second tap, on the same row or another, arrives with it after the first closed the chooser.
    editor.add(songs, to: "p1")
    editor.add(songs, to: "p1")
    editor.add(songs, to: "p2")
    editor.createPlaylist(named: "Mix", with: songs)
    #expect(fake.edits == [.append(playlistID: "p1", songs: ["t1"])])

    // Presented again, the same songs can be added again: that is a new choice.
    editor.addition = songs
    editor.add(songs, to: "p1")
    #expect(fake.edits == [.append(playlistID: "p1", songs: ["t1"]), .append(playlistID: "p1", songs: ["t1"])])

    // New Playlist from the playlists screen carries no addition and is never refused.
    editor.createPlaylist(named: "Empty", with: nil)
    #expect(fake.edits.last == .create(name: "Empty", songs: []))
}

// MARK: - The positional-edit context

@Test
func positionalEditsCarryTheViewThePersonActedOn() throws {
    let window = playlistWindow(header: playlistItem(), tracks: ["a", "b", "b", "c"])
    let context = try #require(DulcetPlaylistEditContext(window: window, id: playlistID))
    #expect(context.expected == ["a", "b", "b", "c"])
    #expect(context.remove(2) == .remove(playlistID: "p1", indices: [2], expected: ["a", "b", "b", "c"]))

    // List.onMove names the gap before the row is taken out; the core names the final index.
    #expect(context.move(IndexSet(integer: 0), toOffset: 4) == .move(playlistID: "p1", from: 0, to: 3, expected: context.expected))
    #expect(context.move(IndexSet(integer: 3), toOffset: 0) == .move(playlistID: "p1", from: 3, to: 0, expected: context.expected))
    #expect(context.move(IndexSet(integer: 1), toOffset: 2) == nil, "dropping a row into its own gap moves nothing")
    #expect(context.move(IndexSet(integer: 1), toOffset: 1) == nil)
    #expect(context.move(IndexSet([0, 1]), toOffset: 4) == nil, "one row moves at a time")
}

@Test
func noPositionalEditIsOfferedOnAViewThatIsNotTheWholePlaylist() {
    #expect(DulcetPlaylistEditContext(window: playlistWindow(header: playlistItem(editable: false), tracks: ["a"]), id: playlistID) == nil)
    #expect(DulcetPlaylistEditContext(window: playlistWindow(header: nil, tracks: ["a"]), id: playlistID) == nil)
    #expect(DulcetPlaylistEditContext(window: playlistWindow(header: playlistItem(), tracks: ["a"], coverage: "open"), id: playlistID) == nil)
    #expect(DulcetPlaylistEditContext(window: playlistWindow(header: playlistItem(), tracks: ["a"], leadingOffset: 50), id: playlistID) == nil)
    #expect(DulcetPlaylistEditContext(
        window: playlistWindow(header: playlistItem(), tracks: [], freshness: .unavailable(.notCachedOffline)), id: playlistID
    ) == nil)
    // Offline edits of a cached playlist queue (§18.6).
    #expect(DulcetPlaylistEditContext(
        window: playlistWindow(header: playlistItem(), tracks: ["a"], freshness: .cached(.offline, asOf: nil)), id: playlistID
    ) != nil)
}

// MARK: - Lyrics

private func lyricsPublication(
    _ state: String,
    synced: Bool = true,
    freshness: DulcetReaderFreshness = .live,
    errorKind: String? = nil,
    cursor: @escaping @Sendable (Int64) -> DulcetLyricsCursor = { _ in .none }
) -> DulcetLyricsPublication {
    let document = DulcetLyricsDocument(
        synced: synced,
        lines: [DulcetLyricsLine(text: "one", startMilliseconds: 1_000), DulcetLyricsLine(text: "two", startMilliseconds: 2_000)],
        language: "en", trimmed: false, cursor: cursor
    )
    return DulcetLyricsPublication(
        trackRawID: "t1", state: state, document: state == "lyrics" ? document : nil,
        freshness: freshness, breakerOpen: false, errorKind: errorKind
    )
}

@Test
func theLyricsPanelDrawsWhatTheCorePublishedAndNeverASpinnerForNone() {
    guard case .synced = DulcetLyricsPresentation.state(lyricsPublication("lyrics"), loading: false) else {
        Issue.record("synced lyrics must draw synced"); return
    }
    guard case .plain = DulcetLyricsPresentation.state(lyricsPublication("lyrics", synced: false), loading: true) else {
        Issue.record("plain lyrics must draw plain"); return
    }
    #expect(DulcetLyricsPresentation.state(lyricsPublication("none"), loading: true) == .none)
    #expect(DulcetLyricsPresentation.state(nil, loading: false) == .loading)
    #expect(DulcetLyricsPresentation.state(lyricsPublication("unavailable"), loading: true) == .loading)
    #expect(DulcetLyricsPresentation.state(
        lyricsPublication("unavailable", freshness: .unavailable(.notCachedOffline)), loading: false
    ) == .unavailable(message: DulcetStrings.lyricsOffline, offersRetry: false))
    guard case let .unavailable(_, retry) = DulcetLyricsPresentation.state(
        lyricsPublication("unavailable", freshness: .unavailable(.failed(.server))), loading: false
    ) else { Issue.record("a failure must be unavailable"); return }
    #expect(retry)
    // A state word outside the vocabulary is unavailable, never lyrics.
    #expect(DulcetLyricsPublication(
        trackRawID: "t1", state: "maybe", document: nil, freshness: .live, breakerOpen: false, errorKind: nil
    ).content == .unavailable)
    #expect(DulcetLyricsPresentation.status(lyricsPublication("lyrics", freshness: .cached(.offline, asOf: nil))) == DulcetStrings.lyricsShownOffline)
    #expect(DulcetLyricsPresentation.status(lyricsPublication("lyrics")) == nil)
}

@Test
func theCurrentLineIsTheCoresCursorInWholeMillisecondsAndNoneWhenUnsynced() {
    final class Positions: @unchecked Sendable { var seen: [Int64] = [] }
    let positions = Positions()
    let publication = lyricsPublication("lyrics") { milliseconds in
        positions.seen.append(milliseconds)
        return DulcetLyricsCursor(index: 1, lastIndex: 1, isInterlude: false)
    }
    guard case let .lyrics(document) = publication.content else { Issue.record("expected lyrics"); return }
    let cursor = document.cursor(at: .milliseconds(2_345) + .nanoseconds(900_000))
    #expect(positions.seen == [2_345])
    #expect(cursor.contains(1) && !cursor.contains(0))

    guard case let .lyrics(plain) = lyricsPublication("lyrics", synced: false) { _ in
        Issue.record("an unsynced document must not ask the core"); return .none
    }.content else { return }
    #expect(plain.cursor(at: .seconds(5)) == .none)

    #expect(!DulcetLyricsCursor(index: 0, lastIndex: 0, isInterlude: true).contains(0))
    #expect(!DulcetLyricsCursor.none.contains(0))
}

@Test
func theClockMovesLyricsOnBetweenPlayerTicksButNeverFarAhead() {
    let start = Date(timeIntervalSince1970: 1_000)
    let anchor = DulcetLyricsClockAnchor(elapsed: .seconds(10), at: start)
    #expect(anchor.position(at: start.addingTimeInterval(0.5), playing: true) == .milliseconds(10_500))
    #expect(anchor.position(at: start.addingTimeInterval(30), playing: true) == .seconds(12))
    #expect(anchor.position(at: start.addingTimeInterval(0.5), playing: false) == .seconds(10))
    #expect(anchor.position(at: start.addingTimeInterval(-3), playing: true) == .seconds(10))
}

@Test @MainActor
func theModelPaintsTheStoredDocumentThenTheLiveOneAndDropsALateAnswer() {
    let reader = RecordingLyrics()
    let model = DulcetLyricsModel()
    let first = DulcetLyricsRequest(trackRawID: "t1", artist: "A", title: "One")
    model.load(first, from: reader)
    #expect(reader.reads.map(\.1) == [.cached, .read])
    #expect(model.state == .loading)

    reader.completions[0](lyricsPublication("lyrics", freshness: .cached(.offline, asOf: nil)))
    guard case .synced = model.state else { Issue.record("the stored document paints at once"); return }
    reader.completions[1](lyricsPublication("none"))
    #expect(model.state == .none)

    // The same request again reads nothing; another track drops the first one's answers.
    model.load(first, from: reader)
    #expect(reader.reads.count == 2)
    model.load(DulcetLyricsRequest(trackRawID: "t2", artist: nil, title: nil), from: reader)
    #expect(model.state == .loading)
    reader.completions[1](lyricsPublication("lyrics"))
    #expect(model.state == .loading, "a late answer for the previous track must not paint")

    model.retry(from: reader)
    #expect(reader.reads.last?.1 == .retry)
    reader.completions.last?(lyricsPublication("none"))
    #expect(model.state == .none)
}

@Test @MainActor
func aReloadKeepsWhatIsShownUntilItsAnswerAndDropsEveryEarlierRead() {
    let reader = RecordingLyrics()
    let model = DulcetLyricsModel()
    model.load(DulcetLyricsRequest(trackRawID: "t1", artist: nil, title: nil), from: reader)
    reader.completions[1](lyricsPublication("unavailable", freshness: .unavailable(.notCachedOffline)))
    let offline = model.state

    // A reconnect reads again; the offline answer stays until the new one arrives.
    model.reload(from: reader)
    #expect(reader.reads.last?.1 == .read)
    #expect(reader.reads.count == 3)
    #expect(model.publication?.freshness == .unavailable(.notCachedOffline))
    guard case .unavailable = offline else { Issue.record("expected the offline state first"); return }

    // The first load's stored read answering late must not repaint over the reload.
    reader.completions[0](lyricsPublication("none"))
    #expect(model.publication?.freshness == .unavailable(.notCachedOffline))
    reader.completions[2](lyricsPublication("lyrics"))
    guard case .synced = model.state else { Issue.record("the reload's answer must paint, got \(model.state)"); return }
}

@Test @MainActor
func withNoReaderTheModelSaysUnavailableRatherThanSpinning() {
    let model = DulcetLyricsModel()
    model.load(DulcetLyricsRequest(trackRawID: "t1", artist: nil, title: nil), from: nil)
    guard case .unavailable = model.state else { Issue.record("expected unavailable, got \(model.state)"); return }
}
