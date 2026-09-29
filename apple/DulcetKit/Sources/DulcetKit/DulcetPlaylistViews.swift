#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

// Playlists in the library (spec §18.6): the list with New Playlist, a playlist's page you can play
// and -- for a playlist the person may edit -- rename, delete, reorder and remove from, and "Add to
// Playlist" from a track's or album's context menu. Every edit is in the next publication of every
// screen showing the playlist before any request (the core's overlay); what is still to reach the
// server, and what could not, is said on the playlist itself. Another user's playlist is drawn
// read-only with its owner, with no edit affordance at all. tvOS lists and plays playlists only.

private extension DulcetPresentationStore {
    var playlistEditor: DulcetPlaylistEditor? { librarySession?.playlists }

    /// Whether this item belongs to the account the session reads as (ids are per server).
    func isSessionItem(_ id: DulcetProviderItemID) -> Bool {
        librarySession?.account?.providerInstanceID == id.providerInstanceID
    }
}

// MARK: - The Playlists section

struct DulcetPlaylistsView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @State private var naming = false

    var body: some View {
        DulcetReaderScreen(query: .playlists) { model in
            DulcetReaderPage(title: DulcetStrings.readerPlaylists) { _ in
#if !os(tvOS)
                if store.playlistEditor != nil {
                    Button(DulcetStrings.playlistNewEllipsis, systemImage: "plus") { naming = true }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.playlists.new")
                }
#endif
                DulcetReaderListBody(model: model) { window in
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                            DulcetReaderListRow(item: item)
                                .dulcetReaderRow(index, in: model)
                            Divider()
                        }
                    }
                }
            }
        }
        .dulcetPlaylistNamePrompt(isPresented: $naming, title: DulcetStrings.playlistNew, initial: "", confirm: DulcetStrings.playlistCreate) { name in
            store.playlistEditor?.createPlaylist(named: name, with: nil)
        }
    }
}

// MARK: - A playlist's page

struct DulcetPlaylistPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID

    var body: some View {
        // A playlist made here and since created on the server reopens under the server's id.
        let rawID = store.playlistEditor?.resolvedID(id.rawID) ?? id.rawID
        DulcetPlaylistPageContent(id: DulcetProviderItemID(providerInstanceID: id.providerInstanceID, rawID: rawID))
            .id(rawID)
    }
}

private struct DulcetPlaylistPageContent: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID
    @State private var editing = false
    @State private var renaming = false
    @State private var confirmingDelete = false

    var body: some View {
        DulcetReaderScreen(query: .playlist(rawID: id.rawID)) { model in
            Group {
                if editing, let window = model.window, let edit = DulcetPlaylistEditContext(window: window, id: id) {
                    editList(window, context: edit)
                } else {
                    page(model)
                }
            }
            .dulcetPlaylistNamePrompt(
                isPresented: $renaming,
                title: DulcetStrings.playlistRename,
                initial: model.window?.header?.displayTitle ?? "",
                confirm: DulcetStrings.playlistRename
            ) { name in
                store.playlistEditor?.perform(.rename(playlistID: id.rawID, name: name))
            }
            .dulcetPlaylistDeleteConfirmation(isPresented: $confirmingDelete) {
                store.playlistEditor?.perform(.delete(playlistID: id.rawID)) { result in
                    guard result.record == .pending || result.record == .compactedAway else { return }
                    store.popReader(to: Array(store.readerPath.dropLast()))
                }
            }
        }
    }

    private func page(_ model: DulcetLibraryWindowModel) -> some View {
        DulcetReaderPage(title: model.window?.header?.displayTitle ?? "") { width in
            if let window = model.window {
                header(window, width: width)
                awaitingChoiceBanner
                problemBanner
                DulcetReaderListBody(model: model, subject: window.header == nil ? .list : .albumTracks) { window in
                    tracks(window, model: model)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
        }
    }

    // MARK: Header

    @ViewBuilder
    private func header(_ window: DulcetLibraryWindow, width: CGFloat) -> some View {
        let compact = width < DulcetLibraryMetrics.compactWidthThreshold
        let artworkSize: CGFloat = compact ? min(260, width - 64) : 220
        let item = window.header
        let playable = window.playableTracks
        let layout = compact
            ? AnyLayout(VStackLayout(alignment: .center, spacing: DulcetSpacing.sm))
            : AnyLayout(HStackLayout(alignment: .bottom, spacing: DulcetSpacing.md))
        layout {
            if let item {
                DulcetArtworkView(artwork: item.artwork, size: artworkSize)
            }
            VStack(alignment: compact ? .center : .leading, spacing: DulcetSpacing.xxs) {
                Text(item?.displayTitle ?? DulcetStrings.playlistUntitledName)
                    .font(.title.weight(.bold))
                    .multilineTextAlignment(compact ? .center : .leading)
                    .lineLimit(nil)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("dulcet.playlist.title")
                if let detail = detailLine(item, window: window) {
                    Text(detail)
                        .font(.subheadline)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
                if let item, let status = DulcetPlaylistPresentation.status(of: item) {
                    Label(status, systemImage: item.isEditable ? "arrow.triangle.2.circlepath" : "person")
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityIdentifier("dulcet.playlist.status")
                }
                HStack(spacing: DulcetSpacing.xs) {
                    Button(DulcetStrings.play, systemImage: "play.fill") {
                        store.playReaderTracks(playable, sourceKind: .playlist, sourceID: id, sourceName: item?.displayTitle ?? "")
                    }
                    .dulcetProminentActionStyle()
                    .disabled(playable.isEmpty)
                    .accessibilityIdentifier("dulcet.playlist.play")
                    Button(DulcetStrings.shuffle, systemImage: "shuffle") {
                        store.playReaderTracks(playable, shuffle: true, sourceKind: .playlist, sourceID: id, sourceName: item?.displayTitle ?? "")
                    }
                    .buttonStyle(.bordered)
                    .disabled(playable.isEmpty)
                    .accessibilityIdentifier("dulcet.playlist.shuffle")
#if !os(tvOS)
                    if let item, item.isEditable, store.playlistEditor != nil {
                        Button(DulcetStrings.playlistEdit, systemImage: "pencil") { editing = true }
                            .buttonStyle(.bordered)
                            .disabled(DulcetPlaylistEditContext(window: window, id: id) == nil)
                            .accessibilityIdentifier("dulcet.playlist.edit")
                        Menu {
                            Button(DulcetStrings.playlistRenameEllipsis, systemImage: "character.cursor.ibeam") { renaming = true }
                            Button(DulcetStrings.playlistDelete, systemImage: "trash", role: .destructive) { confirmingDelete = true }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .font(.title3)
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel(DulcetStrings.playlistEdit)
                        .accessibilityIdentifier("dulcet.playlist.more")
                    }
#endif
                }
                .padding(.top, DulcetSpacing.xxs)
            }
            .frame(maxWidth: compact ? .infinity : nil, alignment: compact ? .center : .leading)
        }
        .frame(maxWidth: .infinity, alignment: compact ? .center : .leading)
    }

    private func detailLine(_ item: DulcetReaderItem?, window: DulcetLibraryWindow) -> String? {
        var parts: [String] = []
        if let count = item?.songCount ?? window.total { parts.append(DulcetStrings.readerCount(.tracks, count)) }
        if let comment = item?.comment, !comment.isEmpty { parts.append(comment) }
        return parts.isEmpty ? nil : parts.joined(separator: " \u{00B7} ")
    }

    /// A create in doubt the person said to decide later: its question, asked again from here.
    @ViewBuilder
    private var awaitingChoiceBanner: some View {
        if let editor = store.playlistEditor, editor.awaitingChoice[id.rawID] != nil {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.playlistAwaitingChoice)
                    .font(.callout)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(nil)
                Button(DulcetStrings.playlistChoose) { editor.reask(id.rawID) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("dulcet.playlist.choose")
            }
            .padding(DulcetSpacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.playlist.awaitingChoice")
        }
    }

    @ViewBuilder
    private var problemBanner: some View {
        if let editor = store.playlistEditor, let problem = editor.problems[id.rawID] {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(problem.message)
                    .font(.callout)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(nil)
                HStack(spacing: DulcetSpacing.xs) {
                    if let change = problem.withdrawable {
                        Button(DulcetStrings.playlistWithdraw) {
                            editor.perform(.withdraw(playlistID: id.rawID, change: change))
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.playlist.withdraw")
                    }
                    Button(DulcetStrings.playlistDismiss) { editor.dismissProblem(id.rawID) }
                        .buttonStyle(.bordered)
                }
            }
            .padding(DulcetSpacing.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.playlist.problem")
        }
    }

    // MARK: Tracks

    @ViewBuilder
    private func tracks(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        let playable = window.playableTracks
        LazyVStack(alignment: .leading, spacing: 0) {
            // By position: a playlist may hold one song twice (§18.6).
            ForEach(Array(window.items.enumerated()), id: \.offset) { index, item in
                DulcetReaderTrackRow(item: item, index: window.leadingOffset + index + 1) { track in
                    store.playReaderTracks(
                        playable,
                        startingAt: track,
                        sourceKind: .playlist,
                        sourceID: id,
                        sourceName: window.header?.displayTitle ?? ""
                    )
                }
                .dulcetReaderRow(index, in: model)
                Divider()
            }
        }
    }

    // MARK: Editing

#if os(tvOS)
    private func editList(_ window: DulcetLibraryWindow, context: DulcetPlaylistEditContext) -> some View {
        EmptyView()
    }
#else
    private func editList(_ window: DulcetLibraryWindow, context: DulcetPlaylistEditContext) -> some View {
        List {
            ForEach(Array(window.items.enumerated()), id: \.offset) { index, item in
                HStack(spacing: DulcetSpacing.xs) {
#if os(macOS)
                    Button {
                        store.playlistEditor?.perform(context.remove(index))
                    } label: {
                        Image(systemName: "minus.circle.fill").symbolRenderingMode(.multicolor)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(DulcetStrings.playlistRemove)
                    .accessibilityIdentifier("dulcet.playlist.entry.remove")
#endif
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.displayTitle)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        if let artist = item.artistName, !artist.isEmpty {
                            Text(artist)
                                .font(.caption)
                                .dulcetForeground(.secondaryTextOnWindow)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .accessibilityIdentifier("dulcet.playlist.entry")
                .accessibilityActions {
                    if index > 0 {
                        Button(DulcetStrings.playlistMoveUp) { store.playlistEditor?.perform(context.move(index, to: index - 1)) }
                    }
                    if index < window.items.count - 1 {
                        Button(DulcetStrings.playlistMoveDown) { store.playlistEditor?.perform(context.move(index, to: index + 1)) }
                    }
                    Button(DulcetStrings.playlistRemove) { store.playlistEditor?.perform(context.remove(index)) }
                }
            }
            .onMove { source, destination in
                guard let edit = context.move(source, toOffset: destination) else { return }
                store.playlistEditor?.perform(edit)
            }
            .onDelete { offsets in
                store.playlistEditor?.perform(context.remove(offsets))
            }
        }
#if os(iOS)
        .environment(\.editMode, .constant(.active))
#endif
        .navigationTitle(window.header?.displayTitle ?? DulcetStrings.playlistUntitledName)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(DulcetStrings.playlistDone) { editing = false }
                    .accessibilityIdentifier("dulcet.playlist.done")
            }
        }
    }
#endif
}

/// The view a positional edit is made against: the entry ids of the publication on screen, whole
/// and in order (§18.6). Nil when the list on screen cannot be edited by position: not the
/// person's playlist, its entries not all present, or not a list that starts at the top.
struct DulcetPlaylistEditContext: Equatable {
    let playlistID: String
    let expected: [String]

    init?(window: DulcetLibraryWindow, id: DulcetProviderItemID) {
        guard let header = window.header, header.isEditable,
              window.itemsState == .present, window.leadingOffset == 0,
              window.coverage != .open else { return nil }
        switch window.freshness {
        case .live, .cached: break
        case .loading, .unavailable: return nil
        }
        playlistID = id.rawID
        expected = window.items.map(\.id.rawID)
    }

    func remove(_ index: Int) -> DulcetPlaylistEdit {
        .remove(playlistID: playlistID, indices: [index], expected: expected)
    }

    func remove(_ offsets: IndexSet) -> DulcetPlaylistEdit {
        .remove(playlistID: playlistID, indices: Array(offsets), expected: expected)
    }

    func move(_ from: Int, to: Int) -> DulcetPlaylistEdit {
        .move(playlistID: playlistID, from: from, to: to, expected: expected)
    }

    /// `List.onMove` names the gap to drop into, counted before the row is taken out; the core
    /// names the row's final position. One row moves at a time.
    func move(_ source: IndexSet, toOffset destination: Int) -> DulcetPlaylistEdit? {
        guard source.count == 1, let from = source.first else { return nil }
        let to = destination > from ? destination - 1 : destination
        guard to != from else { return nil }
        return move(from, to: to)
    }
}

// MARK: - Context menus

/// Delete for the person's own playlist -- confirmed first, by the same dialog as its page -- and
/// Add to Playlist for an album. Rename is on the playlist's page. Nothing for another user's
/// playlist, which has no edit affordance at all (§18.6).
struct DulcetPlaylistItemMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem

    var body: some View {
        if let editor = store.playlistEditor, store.isSessionItem(item.id) {
            switch item.kind {
            case .album:
                Button(DulcetStrings.playlistAddToEllipsis, systemImage: "text.badge.plus") {
                    editor.addition = .album(item.id.rawID, title: item.displayTitle)
                }
            case .playlist where item.isEditable:
                Button(DulcetStrings.playlistDelete, systemImage: "trash", role: .destructive) {
                    // Recorded only once the person confirms (dulcetPlaylistSheets).
                    editor.requestDeletion(of: item.id.rawID)
                }
            default:
                EmptyView()
            }
        }
    }
}

/// "Add to Playlist…" for one track.
struct DulcetAddTrackToPlaylistMenuItem: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack

    var body: some View {
        if let editor = store.playlistEditor, store.isSessionItem(track.id) {
            Button(DulcetStrings.playlistAddToEllipsis, systemImage: "text.badge.plus") {
                editor.addition = .songs([track.id.rawID], title: track.title)
            }
            .accessibilityIdentifier("dulcet.track.addToPlaylist")
        }
    }
}

// MARK: - The chooser and the questions

extension View {
    /// The Add to Playlist chooser and a create-in-doubt's question, wherever they are asked from.
    func dulcetPlaylistSheets(store: DulcetPresentationStore) -> some View {
        modifier(DulcetPlaylistSheets(store: store))
    }

    /// The one confirmation every playlist deletion goes through: its page's and its context menu's.
    /// An alert, not a confirmation dialog: the context menu's is asked from the root, where a
    /// confirmation dialog on macOS traps on the store's environment (the window-title test).
    func dulcetPlaylistDeleteConfirmation(isPresented: Binding<Bool>, onConfirm: @escaping () -> Void) -> some View {
        alert(DulcetStrings.playlistDeleteConfirm, isPresented: isPresented) {
            Button(DulcetStrings.playlistDelete, role: .destructive, action: onConfirm)
                .accessibilityIdentifier("dulcet.playlist.delete.confirm")
            Button(DulcetStrings.playlistCancel, role: .cancel) {}
        }
    }

    /// A name prompt: New Playlist, Rename.
    func dulcetPlaylistNamePrompt(
        isPresented: Binding<Bool>,
        title: String,
        initial: String,
        confirm: String,
        onSubmit: @escaping (String) -> Void
    ) -> some View {
        modifier(DulcetPlaylistNamePrompt(isPresented: isPresented, title: title, initial: initial, confirm: confirm, onSubmit: onSubmit))
    }
}

private struct DulcetPlaylistNamePrompt: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let initial: String
    let confirm: String
    let onSubmit: (String) -> Void
    @State private var name = ""

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: $isPresented) {
                TextField(DulcetStrings.playlistName, text: $name)
                    .accessibilityIdentifier("dulcet.playlist.name")
                Button(confirm) {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    onSubmit(trimmed)
                }
                .accessibilityIdentifier("dulcet.playlist.name.confirm")
                Button(DulcetStrings.playlistCancel, role: .cancel) {}
            }
            .onChange(of: isPresented) { _, presented in
                if presented { name = initial }
            }
    }
}

private struct DulcetPlaylistSheets: ViewModifier {
    @Bindable var store: DulcetPresentationStore

    func body(content: Content) -> some View {
        content
            .sheet(item: Binding(
                get: { store.librarySession?.playlists?.addition },
                set: { store.librarySession?.playlists?.addition = $0 }
            )) { addition in
                DulcetAddToPlaylistSheet(addition: addition)
                    .environment(store)
            }
            .dulcetPlaylistDeleteConfirmation(isPresented: Binding(
                get: { store.librarySession?.playlists?.deletionToConfirm != nil },
                set: { if !$0 { store.librarySession?.playlists?.cancelDeletion() } }
            )) {
                store.librarySession?.playlists?.confirmDeletion()
            }
            .alert(
                questionTitle,
                isPresented: Binding(
                    get: { store.librarySession?.playlists?.question != nil },
                    // Every button answers or dismisses its own question; dismissing here too
                    // would drop the next queued one, which is already current by then.
                    set: { _ in }
                ),
                presenting: store.librarySession?.playlists?.question
            ) { question in
                ForEach(DulcetPlaylistPresentation.choices(for: question), id: \.self) { choice in
                    button(for: choice, of: question)
                }
            } message: { question in
                Text(DulcetPlaylistPresentation.message(for: question))
            }
    }

    @ViewBuilder
    private func button(for choice: DulcetPlaylistQuestionChoice, of question: DulcetPlaylistQuestion) -> some View {
        let editor = store.librarySession?.playlists
        switch choice {
        case let .adopt(candidate):
            Button(DulcetStrings.playlistItIsMine) { editor?.answer(question, choosing: candidate) }
        case let .delete(candidate):
            Button(DulcetStrings.playlistDeleteFromServer, role: .destructive) { editor?.answer(question, choosing: candidate) }
        case .createAgain:
            Button(DulcetStrings.playlistNoneOfThese) { editor?.answer(question, choosing: nil) }
        case .decideLater:
            Button(DulcetStrings.playlistDecideLater, role: .cancel) { editor?.dismissQuestion(question) }
        case .keep:
            Button(DulcetStrings.playlistKeepIt, role: .cancel) { editor?.dismissQuestion(question) }
        }
    }

    private var questionTitle: String {
        switch store.librarySession?.playlists?.question?.kind {
        case .deleteMaybeCreated: DulcetStrings.playlistMaybeCreatedTitle
        default: DulcetStrings.playlistWhichIsYoursTitle
        }
    }
}

/// The playlists the person can add to, and New Playlist, for one addition.
private struct DulcetAddToPlaylistSheet: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var naming = false
    let addition: DulcetPlaylistAddition

    var body: some View {
        NavigationStack {
            DulcetReaderScreen(query: .playlists) { model in
                List {
                    Button(DulcetStrings.playlistNewEllipsis, systemImage: "plus") { naming = true }
                        .accessibilityIdentifier("dulcet.addToPlaylist.new")
                    let editable = (model.window?.items ?? []).filter(\.isEditable)
                    if let window = model.window, editable.isEmpty, window.freshness != .loading {
                        Text(DulcetStrings.playlistNoneEditable).dulcetForeground(.secondaryTextOnWindow)
                    }
                    ForEach(editable) { item in
                        Button {
                            store.playlistEditor?.add(addition, to: item.id.rawID)
                        } label: {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(item.displayTitle)
                                if let count = item.songCount {
                                    Text(DulcetStrings.readerCount(.tracks, count)).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                                }
                            }
                        }
                        .accessibilityIdentifier("dulcet.addToPlaylist.playlist")
                    }
                }
            }
            .navigationTitle(DulcetStrings.playlistAddTo)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(DulcetStrings.playlistCancel) {
                        store.playlistEditor?.addition = nil
                        dismiss()
                    }
                }
            }
        }
#if os(macOS)
        .frame(minWidth: 360, minHeight: 420)
#endif
        .dulcetPlaylistNamePrompt(isPresented: $naming, title: DulcetStrings.playlistNew, initial: "", confirm: DulcetStrings.playlistCreate) { name in
            store.playlistEditor?.createPlaylist(named: name, with: addition)
        }
    }
}
#endif
