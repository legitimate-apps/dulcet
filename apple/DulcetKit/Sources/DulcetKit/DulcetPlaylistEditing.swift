import Foundation
import Observation

// Playlist editing for the shells (spec §18.6).
//
// `DulcetPlaylistEditing` is what a reader offers when it can edit playlists; the app target adapts
// the core's `AppleLibraryPlaylistClient` to it, and tests adapt a fake. Every edit is shown in the
// next publication of every open screen that shows the playlist -- the core's overlay, not
// anything here -- so this file owns only what a person is TOLD: what an edit did, how a queued
// change ended, and the questions a create in doubt asks. `DulcetPlaylistEditor` holds that for the
// session's current reader.

/// Which queued change of a playlist an outcome or a withdrawal names.
public enum DulcetPlaylistChangeKind: String, Sendable, Hashable, CaseIterable {
    case create
    case details
    case entries
    case delete
}

/// One edit, as the person made it. Positions are into `expected`, the entry ids of the
/// publication the person acted on -- never a server position (§18.6).
public enum DulcetPlaylistEdit: Sendable, Hashable {
    case create(name: String, songs: [String])
    case rename(playlistID: String, name: String)
    case append(playlistID: String, songs: [String])
    case appendAlbum(playlistID: String, albumID: String)
    case remove(playlistID: String, indices: [Int], expected: [String])
    case move(playlistID: String, from: Int, to: Int, expected: [String])
    case delete(playlistID: String)
    case withdraw(playlistID: String, change: DulcetPlaylistChangeKind)
    case chooseCreated(localID: String, playlistID: String?)
}

/// What an edit did to the outbox (§18.6).
public enum DulcetPlaylistEditRecord: String, Sendable, Hashable, CaseIterable {
    /// Shown now, queued, sent when the server can be reached.
    case pending
    /// It undid what was queued, and nothing reaches the server.
    case compactedAway
    case unchanged
    case invalid
    /// Another user's or a read-only playlist.
    case notEditable
    /// The playlist's or album's entries were never read on this device.
    case notCached
    /// The list moved under the tap; nothing was changed.
    case staleView
    case deleted
    case notRecorded
    /// A withdrawal too late to undo: the server may already hold it.
    case alreadySent
}

public struct DulcetPlaylistEditResult: Sendable, Hashable {
    /// Nil when the edit never ran; `error` says why.
    public let record: DulcetPlaylistEditRecord?
    /// For a pending create, the local id the new playlist is shown under.
    public let localID: String?
    public let error: DulcetReaderErrorKind?

    public init(record: String?, localID: String?, errorKind: String?) {
        let known = record.flatMap(DulcetPlaylistEditRecord.init(rawValue:))
        self.record = known
        self.localID = localID
        // A word outside the vocabulary is the device's own failure, never a guessed success.
        error = errorKind.map { DulcetReaderErrorKind(coreName: $0) } ?? (known == nil ? .internalFailure : nil)
    }
}

/// How a queued playlist change ended, or why it waits (§18.6).
public enum DulcetPlaylistOutcomeKind: Sendable, Hashable {
    case saved
    case created(localID: String)
    case notSaved(DulcetReaderErrorKind)
    /// Not written: the entries changed on the server after the edit was made.
    case changedElsewhere
    /// Written, and the read-back is not what was meant.
    case diverged
    case held(DulcetReaderErrorKind)
    case superseded(fields: [String])
    case notRecorded
    /// A create in doubt was deleted here: these playlists may be what it made.
    case possiblyCreated(localID: String, name: String, candidates: [String])
    /// A create in doubt may already be one of these: the person chooses.
    case possibleDuplicate(localID: String, name: String, candidates: [String])
}

public struct DulcetPlaylistOutcome: Sendable, Hashable {
    public let kind: DulcetPlaylistOutcomeKind
    public let playlistID: String
    public let change: DulcetPlaylistChangeKind?

    /// Nil for a word outside the vocabulary: it is not drawn as some other outcome.
    public init?(
        kind: String,
        playlistID: String,
        change: String?,
        localID: String?,
        name: String?,
        candidates: [String],
        fields: [String],
        errorKind: String?
    ) {
        let error = DulcetReaderErrorKind(coreName: errorKind)
        switch kind {
        case "saved": self.kind = .saved
        case "created":
            guard let localID else { return nil }
            self.kind = .created(localID: localID)
        case "notSaved": self.kind = .notSaved(error)
        case "changedElsewhere": self.kind = .changedElsewhere
        case "diverged": self.kind = .diverged
        case "held": self.kind = .held(error)
        case "superseded": self.kind = .superseded(fields: fields)
        case "notRecorded": self.kind = .notRecorded
        case "possiblyCreated":
            guard let localID, !candidates.isEmpty else { return nil }
            self.kind = .possiblyCreated(localID: localID, name: name ?? "", candidates: candidates)
        case "possibleDuplicate":
            guard let localID, !candidates.isEmpty else { return nil }
            self.kind = .possibleDuplicate(localID: localID, name: name ?? "", candidates: candidates)
        default: return nil
        }
        self.playlistID = playlistID
        self.change = change.flatMap(DulcetPlaylistChangeKind.init(rawValue:))
    }
}

/// One playlist change not yet on the server, as the core lists it (§18.6).
public struct DulcetPlaylistPendingChange: Sendable, Hashable {
    public let playlistID: String
    /// Nil for a word outside the vocabulary.
    public let change: DulcetPlaylistChangeKind?
    /// For a create waiting for the person's choice: the name its candidates share.
    public let name: String?
    /// For a create waiting for the person's choice: the playlists it may already be.
    public let candidates: [String]?

    public init(playlistID: String, change: String, name: String?, candidates: [String]?) {
        self.playlistID = playlistID
        self.change = DulcetPlaylistChangeKind(rawValue: change)
        self.name = name
        self.candidates = candidates
    }
}

/// A reader that can edit playlists.
@MainActor
public protocol DulcetPlaylistEditing: AnyObject {
    /// The completion is called exactly once.
    func editPlaylist(_ edit: DulcetPlaylistEdit, completion: @escaping @MainActor (DulcetPlaylistEditResult) -> Void)
    /// Every change not yet on the server, oldest first; nil when they could not be read. The
    /// completion is called exactly once. It is how a question the person has not answered is
    /// asked again: the core emits `possibleDuplicate` once, and keeps the create waiting.
    func pendingPlaylistChanges(completion: @escaping @MainActor ([DulcetPlaylistPendingChange]?) -> Void)
    func subscribePlaylistOutcomes(
        _ handler: @escaping @MainActor (DulcetPlaylistOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable
}

// MARK: - What the person is told

/// Something about one playlist the person should see on its page until they dismiss it.
public struct DulcetPlaylistProblem: Sendable, Hashable {
    public let message: String
    /// The queued change the person may take back, when one is still waiting.
    public let withdrawable: DulcetPlaylistChangeKind?
}

/// A create in doubt asks the person (§18.6): which of these playlists is theirs, or whether to
/// delete one a withdrawn create may have made. Never answered on the person's behalf.
public struct DulcetPlaylistQuestion: Sendable, Hashable, Identifiable {
    public enum Kind: Sendable, Hashable {
        case whichIsYours
        case deleteMaybeCreated
    }

    public let kind: Kind
    public let localID: String
    public let name: String
    public let candidates: [String]
    public var id: String { localID + "\u{0}" + candidates.joined(separator: "\u{0}") }

    /// Whether the person can be offered to adopt or delete a candidate from the question itself.
    /// Candidates share the sent name and may include another client's playlist of that name,
    /// and the core names them by id only, so two or more cannot be told apart here: picking one
    /// would be deleting, or writing into, a playlist on a guess. Only a lone candidate is offered.
    public var offersCandidate: Bool { candidates.count == 1 }
}

/// One answer a question offers, in the order it is drawn.
public enum DulcetPlaylistQuestionChoice: Sendable, Hashable {
    /// "Yes, it's mine": adopt the lone candidate.
    case adopt(String)
    /// Delete the lone candidate from the server; confirmed by being chosen here.
    case delete(String)
    /// None of the candidates: the create is sent again.
    case createAgain
    /// Leave it waiting; the playlist's page asks again.
    case decideLater
    /// Delete nothing.
    case keep
}

/// What "Add to Playlist" is adding: songs in order, or an album's tracks.
public enum DulcetPlaylistAddition: Sendable, Hashable, Identifiable {
    case songs([String], title: String)
    case album(String, title: String)

    public var id: String {
        switch self {
        case let .songs(songs, _): "songs:" + songs.joined(separator: ",")
        case let .album(album, _): "album:" + album
        }
    }

    public var title: String {
        switch self {
        case let .songs(_, title), let .album(_, title): title
        }
    }

    func edit(into playlistID: String) -> DulcetPlaylistEdit {
        switch self {
        case let .songs(songs, _): .append(playlistID: playlistID, songs: songs)
        case let .album(album, _): .appendAlbum(playlistID: playlistID, albumID: album)
        }
    }
}

public enum DulcetPlaylistPresentation {
    /// What to say about an edit the moment it returns; nil when the screen already says it.
    public static func notice(for result: DulcetPlaylistEditResult, edit: DulcetPlaylistEdit) -> String? {
        if let error = result.error {
            return error == .cancelled || error == .closed ? nil : DulcetStrings.playlistEditNotRecorded
        }
        switch result.record {
        case .pending, .compactedAway, .unchanged, .none:
            if case .append = edit, result.record == .pending { return DulcetStrings.playlistAdded }
            if case .appendAlbum = edit, result.record == .pending { return DulcetStrings.playlistAdded }
            return nil
        case .invalid:
            if case .create = edit { return DulcetStrings.playlistNameRequired }
            if case .rename = edit { return DulcetStrings.playlistNameRequired }
            return DulcetStrings.playlistEditInvalid
        case .notEditable: return DulcetStrings.playlistNotEditable
        case .notCached: return DulcetStrings.playlistNotCached
        case .staleView: return DulcetStrings.playlistStaleView
        case .deleted: return DulcetStrings.playlistDeleted
        case .notRecorded: return DulcetStrings.playlistEditNotRecorded
        case .alreadySent: return DulcetStrings.playlistAlreadySent
        }
    }

    /// What to say when a queued change ends; nil for a quiet success.
    public static func notice(for outcome: DulcetPlaylistOutcome) -> String? {
        switch outcome.kind {
        case .saved, .created: nil
        case let .notSaved(error): DulcetStrings.playlistNotSaved(DulcetStrings.readerErrorPhrase(error))
        case .changedElsewhere: DulcetStrings.playlistChangedElsewhere
        case .diverged: DulcetStrings.playlistDiverged
        case let .held(error):
            error == .serverBusy
                ? DulcetStrings.playlistHeldBusy
                : DulcetStrings.playlistHeldRefused(DulcetStrings.readerHeldPhrase(error))
        case .superseded: DulcetStrings.playlistSuperseded
        case .notRecorded: DulcetStrings.playlistEditNotRecorded
        case .possiblyCreated, .possibleDuplicate: nil
        }
    }

    /// What a playlist's page keeps saying about an edit the core refused at once, after the notice
    /// has gone; nil when the refusal is about the tap, not the playlist. Only `deleted` is a
    /// state of the playlist: its page may have learned the playlist is gone while the person was
    /// typing a new name, and then the core refuses the rename before anything is sent -- the same
    /// failed edit a send would have reported as an outcome, so the page says it the same way.
    public static func problem(for result: DulcetPlaylistEditResult, edit: DulcetPlaylistEdit) -> (playlistID: String, problem: DulcetPlaylistProblem)? {
        guard result.error == nil, result.record == .deleted else { return nil }
        let playlistID: String
        switch edit {
        case let .rename(id, _), let .append(id, _), let .appendAlbum(id, _), let .remove(id, _, _),
             let .move(id, _, _, _), let .delete(id):
            playlistID = id
        case .create, .withdraw, .chooseCreated:
            return nil
        }
        return (playlistID, DulcetPlaylistProblem(message: DulcetStrings.playlistDeleted, withdrawable: nil))
    }

    /// What a playlist's page keeps saying after the notice has gone; nil clears it.
    public static func problem(for outcome: DulcetPlaylistOutcome) -> DulcetPlaylistProblem? {
        switch outcome.kind {
        case .saved, .created, .possiblyCreated, .possibleDuplicate: nil
        case .held:
            DulcetPlaylistProblem(message: notice(for: outcome) ?? "", withdrawable: outcome.change)
        case .notSaved, .changedElsewhere, .diverged, .superseded, .notRecorded:
            DulcetPlaylistProblem(message: notice(for: outcome) ?? "", withdrawable: nil)
        }
    }

    /// What a question lets the person answer. A candidate is offered only when it is the only
    /// one (see `DulcetPlaylistQuestion.offersCandidate`).
    public static func choices(for question: DulcetPlaylistQuestion) -> [DulcetPlaylistQuestionChoice] {
        let lone = question.offersCandidate ? question.candidates.first : nil
        switch question.kind {
        case .whichIsYours:
            return (lone.map { [.adopt($0)] } ?? []) + [.createAgain, .decideLater]
        case .deleteMaybeCreated:
            return (lone.map { [.delete($0)] } ?? []) + [.keep]
        }
    }

    public static func message(for question: DulcetPlaylistQuestion) -> String {
        switch (question.kind, question.offersCandidate) {
        case (.whichIsYours, true): DulcetStrings.playlistWhichIsYoursMessage(question.name)
        case (.whichIsYours, false): DulcetStrings.playlistWhichIsYoursManyMessage(question.name, question.candidates.count)
        case (.deleteMaybeCreated, true): DulcetStrings.playlistMaybeCreatedMessage(question.name)
        case (.deleteMaybeCreated, false): DulcetStrings.playlistMaybeCreatedManyMessage(question.name, question.candidates.count)
        }
    }

    public static func question(for outcome: DulcetPlaylistOutcome) -> DulcetPlaylistQuestion? {
        switch outcome.kind {
        case let .possibleDuplicate(localID, name, candidates):
            DulcetPlaylistQuestion(kind: .whichIsYours, localID: localID, name: name, candidates: candidates)
        case let .possiblyCreated(localID, name, candidates):
            DulcetPlaylistQuestion(kind: .deleteMaybeCreated, localID: localID, name: name, candidates: candidates)
        default: nil
        }
    }

    /// The words under a playlist's name: whose it is when not the person's, and whether an edit
    /// has yet to reach the server (§18.6 requires both).
    public static func status(of item: DulcetReaderItem) -> String? {
        var parts: [String] = []
        if item.isLocalPlaylist {
            parts.append(DulcetStrings.playlistNotYetOnServer)
        } else if item.hasPendingChanges {
            parts.append(DulcetStrings.playlistChangesPending)
        }
        if !item.isEditable, let owner = item.owner, !owner.isEmpty {
            parts.append(DulcetStrings.playlistOwnedBy(owner))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " \u{00B7} ")
    }
}

// MARK: - The session's editor

/// Playlist editing for the session's current reader, and what the person has been told.
@MainActor
@Observable
public final class DulcetPlaylistEditor {
    @ObservationIgnored private let editing: any DulcetPlaylistEditing
    @ObservationIgnored private weak var session: DulcetLibrarySession?
    @ObservationIgnored private var outcomes: (any DulcetLibraryReaderCancellable)?

    /// Creates the person said to decide later: not asked again until their page asks, or the
    /// core asks afresh, or the editor starts again.
    @ObservationIgnored private var deferred: Set<String> = []
    /// Counts questions as they arrive, so a pending-changes read issued before one arrived never
    /// drops it as settled.
    @ObservationIgnored private var arrivals = 0
    @ObservationIgnored private var arrivedAt: [String: Int] = [:]
    @ObservationIgnored private var closed = false

    /// What each playlist's page keeps saying, by raw id.
    public private(set) var problems: [String: DulcetPlaylistProblem] = [:]
    /// Questions waiting for the person, oldest first. One outcome never overwrites another's
    /// question: a second create in doubt queues behind the first.
    public private(set) var questions: [DulcetPlaylistQuestion] = []
    /// Creates waiting for the person's choice, by local id -- asked again from the playlist's page.
    public private(set) var awaitingChoice: [String: DulcetPlaylistQuestion] = [:]
    /// Local ids of playlists made here, mapped to the server's id once created.
    public private(set) var created: [String: String] = [:]
    /// What "Add to Playlist" is adding, while its chooser is up.
    public var addition: DulcetPlaylistAddition?
    /// A playlist whose deletion waits for the person to confirm it.
    public private(set) var deletionToConfirm: String?

    /// The question being asked now.
    public var question: DulcetPlaylistQuestion? { questions.first }

    /// Questions only this session knows: a create in doubt deleted here is gone from the core's
    /// outbox once it is named, so its question cannot be read back from `pendingChanges`.
    public var unansweredDeletionQuestions: [DulcetPlaylistQuestion] {
        questions.filter { $0.kind == .deleteMaybeCreated }
    }

    /// `carried` are questions a previous editor for the same account left unanswered.
    public init(
        editing: any DulcetPlaylistEditing,
        session: DulcetLibrarySession?,
        carried: [DulcetPlaylistQuestion] = []
    ) {
        self.editing = editing
        self.session = session
        outcomes = editing.subscribePlaylistOutcomes { [weak self] outcome in
            self?.receive(outcome)
        }
        carried.forEach(enqueue)
        refreshQuestions()
    }

    public func close() {
        closed = true
        outcomes?.cancel()
        outcomes = nil
        addition = nil
        deletionToConfirm = nil
        questions = []
    }

    /// Asks again every create the core still keeps waiting for a choice, except those the person
    /// deferred; drops a question whose create no longer waits.
    public func refreshQuestions() {
        guard !closed else { return }
        let issuedAt = arrivals
        editing.pendingPlaylistChanges { [weak self] changes in
            guard let self, !self.closed, let changes else { return }
            var waiting: [DulcetPlaylistQuestion] = []
            for change in changes where change.change == .create {
                guard let candidates = change.candidates, !candidates.isEmpty else { continue }
                waiting.append(DulcetPlaylistQuestion(
                    kind: .whichIsYours,
                    localID: change.playlistID,
                    name: change.name ?? "",
                    candidates: candidates
                ))
            }
            let waitingIDs = Set(waiting.map(\.localID))
            let settled: (DulcetPlaylistQuestion) -> Bool = { question in
                question.kind == .whichIsYours && !waitingIDs.contains(question.localID)
                    && (self.arrivedAt[question.localID] ?? 0) <= issuedAt
            }
            questions.removeAll(where: settled)
            awaitingChoice = awaitingChoice.filter { !settled($0.value) }
            for question in waiting {
                awaitingChoice[question.localID] = question
                if !deferred.contains(question.localID) { enqueue(question) }
            }
        }
    }

    /// Asks the question of the create `localID` again: the page's "Choose...".
    public func reask(_ localID: String) {
        guard let question = awaitingChoice[localID] else { return }
        deferred.remove(localID)
        enqueue(question)
    }

    private func enqueue(_ question: DulcetPlaylistQuestion) {
        if question.kind == .whichIsYours { awaitingChoice[question.localID] = question }
        if let index = questions.firstIndex(where: { $0.localID == question.localID && $0.kind == question.kind }) {
            questions[index] = question
        } else {
            questions.append(question)
        }
    }

    private func remove(_ question: DulcetPlaylistQuestion) {
        questions.removeAll { $0.localID == question.localID && $0.kind == question.kind }
    }

    /// Asks the person to confirm deleting `playlistID`; nothing is recorded until they do.
    public func requestDeletion(of playlistID: String) {
        deletionToConfirm = playlistID
    }

    /// The person confirmed: the deletion is recorded.
    public func confirmDeletion(completion: (@MainActor (DulcetPlaylistEditResult) -> Void)? = nil) {
        guard let playlistID = deletionToConfirm else { return }
        deletionToConfirm = nil
        perform(.delete(playlistID: playlistID), completion: completion)
    }

    public func cancelDeletion() {
        deletionToConfirm = nil
    }

    /// Makes an edit; `completion` sees what it did after the person has been told.
    public func perform(
        _ edit: DulcetPlaylistEdit,
        completion: (@MainActor (DulcetPlaylistEditResult) -> Void)? = nil
    ) {
        editing.editPlaylist(edit) { [weak self] result in
            if let message = DulcetPlaylistPresentation.notice(for: result, edit: edit) {
                self?.session?.post(message)
            }
            if let refused = DulcetPlaylistPresentation.problem(for: result, edit: edit) {
                self?.problems[refused.playlistID] = refused.problem
            }
            if case let .withdraw(playlistID, _) = edit, result.record != nil {
                self?.problems[playlistID] = nil
            }
            completion?(result)
        }
    }

    /// Adds what the chooser is adding to `playlistID`, and closes the chooser.
    public func add(_ addition: DulcetPlaylistAddition, to playlistID: String) {
        self.addition = nil
        perform(addition.edit(into: playlistID))
    }

    /// Makes a playlist holding what the chooser is adding, and closes the chooser.
    public func createPlaylist(named name: String, with addition: DulcetPlaylistAddition?) {
        self.addition = nil
        switch addition {
        case let .songs(songs, _):
            perform(.create(name: name, songs: songs))
        case let .album(album, _):
            perform(.create(name: name, songs: [])) { [weak self] result in
                guard result.record == .pending, let localID = result.localID else { return }
                self?.perform(.appendAlbum(playlistID: localID, albumID: album))
            }
        case nil:
            perform(.create(name: name, songs: []))
        }
    }

    public func dismissProblem(_ playlistID: String) {
        problems[playlistID] = nil
    }

    /// The person's answer to the question: a candidate, or nil for none of them. A candidate is
    /// taken only when it is the question's lone one -- one of several cannot have been told apart.
    public func answer(_ question: DulcetPlaylistQuestion, choosing candidate: String?) {
        if let candidate, question.candidates != [candidate] { return }
        remove(question)
        switch question.kind {
        case .whichIsYours:
            awaitingChoice[question.localID] = nil
            deferred.remove(question.localID)
            perform(.chooseCreated(localID: question.localID, playlistID: candidate)) { [weak self] _ in
                // Whatever the core recorded, what still waits is read back and asked again.
                self?.refreshQuestions()
            }
        case .deleteMaybeCreated:
            if let candidate { perform(.delete(playlistID: candidate)) }
            refreshQuestions()
        }
    }

    /// Decide Later, or Keep It: a create left waiting is asked again from its page, and on the
    /// next start; the other questions come next.
    public func dismissQuestion(_ question: DulcetPlaylistQuestion) {
        remove(question)
        if question.kind == .whichIsYours { deferred.insert(question.localID) }
        refreshQuestions()
    }

    /// The server id a playlist created here now has, or its own id.
    public func resolvedID(_ rawID: String) -> String {
        created[rawID] ?? rawID
    }

    func receive(_ outcome: DulcetPlaylistOutcome) {
        if case let .created(localID) = outcome.kind {
            created[localID] = outcome.playlistID
            problems[localID] = nil
        }
        if let question = DulcetPlaylistPresentation.question(for: outcome) {
            // The core asked afresh (another create settled a candidate): ask now.
            deferred.remove(question.localID)
            arrivals += 1
            arrivedAt[question.localID] = arrivals
            enqueue(question)
        }
        problems[outcome.playlistID] = DulcetPlaylistPresentation.problem(for: outcome)
        if let message = DulcetPlaylistPresentation.notice(for: outcome) {
            session?.post(message)
        }
    }
}

extension DulcetReaderItem {
    /// A playlist the person can edit: their own, or one the server says is not read-only.
    public var isEditable: Bool { kind == .playlist && editable }
    public var isLocalPlaylist: Bool { kind == .playlist && local }
    public var hasPendingChanges: Bool { kind == .playlist && pendingChanges }
}

// MARK: - Words

extension DulcetStrings {
    static let playlistNew = dynamicText("playlist.new", fallback: "New Playlist")
    static let playlistNewEllipsis = dynamicText("playlist.new.ellipsis", fallback: "New Playlist\u{2026}")
    static let playlistName = dynamicText("playlist.name", fallback: "Playlist Name")
    static let playlistCreate = dynamicText("playlist.create", fallback: "Create")
    static let playlistRename = dynamicText("playlist.rename", fallback: "Rename")
    static let playlistRenameEllipsis = dynamicText("playlist.rename.ellipsis", fallback: "Rename\u{2026}")
    static let playlistDelete = dynamicText("playlist.delete", fallback: "Delete Playlist")
    static let playlistDeleteConfirm = dynamicText("playlist.delete.confirm", fallback: "Delete this playlist? It is deleted from your server too.")
    static let playlistAddTo = dynamicText("playlist.addTo", fallback: "Add to Playlist")
    static let playlistAddToEllipsis = dynamicText("playlist.addTo.ellipsis", fallback: "Add to Playlist\u{2026}")
    static let playlistRemove = dynamicText("playlist.remove", fallback: "Remove from Playlist")
    static let playlistMoveUp = dynamicText("playlist.moveUp", fallback: "Move Up")
    static let playlistMoveDown = dynamicText("playlist.moveDown", fallback: "Move Down")
    static let playlistEdit = dynamicText("playlist.edit", fallback: "Edit")
    static let playlistDone = dynamicText("playlist.done", fallback: "Done")
    static let playlistCancel = dynamicText("playlist.cancel", fallback: "Cancel")
    static let playlistWithdraw = dynamicText("playlist.withdraw", fallback: "Don\u{2019}t Send")
    static let playlistDismiss = dynamicText("playlist.dismiss", fallback: "OK")
    static let playlistNoneEditable = dynamicText("playlist.noneEditable", fallback: "You have no playlists you can add to yet.")
    static let playlistAdded = dynamicText("playlist.added", fallback: "Added to the playlist.")
    static let playlistNameRequired = dynamicText("playlist.nameRequired", fallback: "A playlist needs a name.")
    static let playlistEditInvalid = dynamicText("playlist.invalid", fallback: "That change can\u{2019}t be made to this playlist.")
    static let playlistNotEditable = dynamicText("playlist.notEditable", fallback: "This playlist belongs to someone else, so it can\u{2019}t be changed.")
    static let playlistNotCached = dynamicText("playlist.notCached", fallback: "This hasn\u{2019}t been loaded on this device yet. Connect to your server and try again.")
    static let playlistStaleView = dynamicText("playlist.staleView", fallback: "The playlist changed as you tapped, so nothing was changed. Try again.")
    static let playlistDeleted = dynamicText("playlist.deleted", fallback: "That playlist has been deleted.")
    static let playlistEditNotRecorded = dynamicText("playlist.notRecorded", fallback: "That change couldn\u{2019}t be recorded on this device.")
    static let playlistAlreadySent = dynamicText("playlist.alreadySent", fallback: "That change may already have reached your server, so it can\u{2019}t be taken back.")
    static let playlistChangedElsewhere = dynamicText("playlist.changedElsewhere", fallback: "This playlist was changed somewhere else, so your change wasn\u{2019}t made. It now shows your server\u{2019}s list.")
    static let playlistDiverged = dynamicText("playlist.diverged", fallback: "Your server\u{2019}s playlist isn\u{2019}t quite what you changed it to \u{2014} it may have been edited somewhere else at the same time. It now shows your server\u{2019}s list.")
    static let playlistHeldBusy = dynamicText("playlist.heldBusy", fallback: "A playlist change isn\u{2019}t sent yet \u{2014} your server is busy. It will be sent later.")
    static let playlistSuperseded = dynamicText("playlist.superseded", fallback: "This playlist\u{2019}s details were changed somewhere else, and those changes were kept.")
    static let playlistChangesPending = dynamicText("playlist.pending", fallback: "Changes not yet on your server")
    static let playlistNotYetOnServer = dynamicText("playlist.local", fallback: "Not yet on your server")
    /// The page's name before the reader has a header for it, in the header and in the iOS
    /// navigation title. Deliberately empty, and it must stay empty: the page draws no name
    /// rather than naming a playlist the server has not named. It is a plain constant and NOT a
    /// `dynamicText` row -- measured, an empty fallback there does not resolve to "" but to the
    /// key itself, which would draw "playlist.untitledName" as the page's title.
    static let playlistUntitledName = ""
    static let playlistWhichIsYoursTitle = dynamicText("playlist.question.which.title", fallback: "Was this playlist already created?")
    static let playlistNoneOfThese = dynamicText("playlist.question.none", fallback: "None of These \u{2014} Create It")
    static let playlistDecideLater = dynamicText("playlist.question.later", fallback: "Decide Later")
    static let playlistMaybeCreatedTitle = dynamicText("playlist.question.maybe.title", fallback: "A playlist may have been created")
    static let playlistKeepIt = dynamicText("playlist.question.keep", fallback: "Keep It")
    static let playlistItIsMine = dynamicText("playlist.question.mine", fallback: "Yes, It\u{2019}s Mine")
    static let playlistDeleteFromServer = dynamicText("playlist.question.deleteIt", fallback: "Delete It from Your Server")
    static let playlistAwaitingChoice = dynamicText("playlist.question.awaiting", fallback: "Dulcet is waiting for you to say whether this playlist was already created on your server.")
    static let playlistChoose = dynamicText("playlist.question.choose", fallback: "Choose\u{2026}")

    static func playlistNotSaved(_ phrase: String) -> String {
        dynamicFormatted("playlist.notSaved", fallback: "Couldn\u{2019}t save a playlist change \u{2014} %@", phrase)
    }

    static func playlistHeldRefused(_ phrase: String) -> String {
        dynamicFormatted(
            "playlist.heldRefused",
            fallback: "A playlist change isn\u{2019}t sent yet \u{2014} %@. It\u{2019}s kept, and will be sent once your server accepts it.",
            phrase
        )
    }

    static func playlistOwnedBy(_ owner: String) -> String {
        dynamicFormatted("playlist.ownedBy", fallback: "By %@", owner)
    }

    static func playlistWhichIsYoursMessage(_ name: String) -> String {
        dynamicFormatted(
            "playlist.question.which.message",
            fallback: "Dulcet couldn\u{2019}t confirm that \u{201C}%@\u{201D} was created, and your server now has a playlist with that name. Is one of these yours?",
            name
        )
    }

    static func playlistMaybeCreatedMessage(_ name: String) -> String {
        dynamicFormatted(
            "playlist.question.maybe.message",
            fallback: "You deleted \u{201C}%@\u{201D} before Dulcet could confirm it was created. Your server may have it. Delete it from your server?",
            name
        )
    }

    static func playlistWhichIsYoursManyMessage(_ name: String, _ count: Int) -> String {
        dynamicFormatted(
            "playlist.question.which.many",
            fallback: "Dulcet couldn\u{2019}t confirm that \u{201C}%@\u{201D} was created, and your server now has %d playlists with that name, which Dulcet can\u{2019}t tell apart. Look at them in Playlists. Decide Later keeps this one waiting; you can choose from its page.",
            name,
            count
        )
    }

    static func playlistMaybeCreatedManyMessage(_ name: String, _ count: Int) -> String {
        dynamicFormatted(
            "playlist.question.maybe.many",
            fallback: "You deleted \u{201C}%@\u{201D} before Dulcet could confirm it was created. Your server has %d playlists with that name, and Dulcet can\u{2019}t tell which one it made, so none is deleted. You can delete the one you don\u{2019}t want from Playlists.",
            name,
            count
        )
    }
}
