#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetSearchView: View {
#if os(macOS)
    @State private var selectedResultID: DulcetSearchResult.ID?
#endif
#if os(iOS)
    @FocusState private var searchFieldFocused: Bool
    @Environment(DulcetPresentationStore.self) private var store
#endif
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String
    let onLoadMore: (DulcetSearchResultKind) -> Void
    let onRetry: () -> Void
    let onActivateResult: (DulcetSearchResult.ID) -> Void
    /// A search command asked for the field's focus; `onFocusRequestHandled` clears the request.
    var focusRequested = false
    var onFocusRequestHandled: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
#if !os(iOS)
                // On iOS the navigation bar's title already says it.
                Text(DulcetStrings.searchTitle)
                    .font(.title.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
#endif
                TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                    .dulcetSearchFieldStyle()
#if os(macOS)
                    // The small AppKit field's SwiftUI ideal height differs from the hosted
                    // NSTextField's runtime intrinsic height. Use the native regular metric so
                    // the header priority below can preserve the control's actual ideal size.
                    .controlSize(.regular)
#else
                    .controlSize(.small)
#endif
#if os(iOS)
                    // A search field takes the platform's search submit key and resigns on
                    // submit, so the keyboard clears the results without a second gesture.
                    .focused($searchFieldFocused)
                    .submitLabel(.search)
                    .onSubmit { searchFieldFocused = false }
                    .onAppear(perform: takeRequestedFocus)
                    .onChange(of: focusRequested) { _, _ in takeRequestedFocus() }
#endif
                    // Deliberately no `.focused` binding on the Mac. MEASURED in the hosted
                    // search proof: with one attached -- even never set -- Return on a selected
                    // result row stopped reaching the table's primary action, so activating a
                    // result from the keyboard silently did nothing.
                    .accessibilityLabel(DulcetStrings.searchPrompt)
                    .accessibilityIdentifier("dulcet.search.field")
                Text(DulcetStrings.searchSummary)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
#if os(macOS)
            // Allocate the header before the flexible result surface, preserving its native field.
            // A vertical fixedSize here also preserves the header's wrapped ideal height during
            // NavigationSplitView's zero-width minimum-size probe. That inflated the navigation
            // minimum above the window height and clipped the first table row out of existence.
            .layoutPriority(1)
#endif

            searchContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(DulcetSpacing.lg)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.search)
    }

#if os(iOS)
    private func takeRequestedFocus() {
        guard focusRequested else { return }
        searchFieldFocused = true
        onFocusRequestHandled()
    }
#endif

    @ViewBuilder
    private var searchContent: some View {
        switch snapshot.state {
        case .searchLoading:
            VStack(spacing: DulcetSpacing.sm) {
                ProgressView()
                Text(DulcetStrings.searchLoading)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        case .searchResults:
            resultsTable
        case .searchEmpty:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchEmptyTitle,
                body: DulcetStrings.searchEmptyBody
            )
        case .searchError:
            VStack(spacing: DulcetSpacing.md) {
                searchMessage(
                    symbol: "exclamationmark.magnifyingglass",
                    title: DulcetStrings.searchErrorTitle,
                    body: DulcetStrings.searchErrorBody
                )
                Button(DulcetStrings.searchRetry, action: onRetry)
                    .buttonStyle(.borderedProminent)
            }
        default:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchIdleTitle,
                body: DulcetStrings.searchIdleBody
            )
        }
    }

    private var resultsTable: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.bestMatches)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(DulcetStrings.searchResultCount(snapshot.searchResults.count))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }

#if os(macOS)
            Table(snapshot.searchResults, selection: $selectedResultID) {
                TableColumn(DulcetStrings.resultColumn) { result in
                    DulcetSearchResultIdentity(
                        result: result,
                        rank: snapshot.searchResults.firstIndex(of: result)
                    )
                }
                .width(min: 320, ideal: 620)

                TableColumn(DulcetStrings.typeColumn) { result in
                    Text(result.kind.displayTitle)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
                .width(min: 72, ideal: 90, max: 120)
            }
            .alternatingRowBackgrounds(.disabled)
            .contextMenu(forSelectionType: DulcetSearchResult.ID.self) { selection in
                if let id = selection.first,
                   let result = snapshot.searchResults.first(where: { $0.id == id }) {
                    DulcetSearchResultMenuItems(result: result) {
                        selectedResultID = id
                        onActivateResult(id)
                    }
                }
            } primaryAction: { selection in
                guard let id = selection.first else { return }
                selectedResultID = id
                onActivateResult(id)
            }
#else
            // Selection is the macOS Table's idiom; touch and the remote share the library's
            // single-activation idiom instead -- one press on a row routes its semantic action
            // (play a track, open an album or artist) through onActivateResult.
            ScrollView {
                LazyVStack(spacing: DulcetSpacing.sm) {
                    ForEach(Array(snapshot.searchResults.enumerated()), id: \.element.id) { index, result in
                        Button {
                            onActivateResult(result.id)
                        } label: {
                            HStack(spacing: DulcetSpacing.md) {
                                DulcetSearchResultIdentity(result: result, rank: index)
                                Spacer(minLength: DulcetSpacing.md)
                                Text(result.kind.displayTitle)
                                    .font(.callout.weight(.semibold))
                                    .dulcetForeground(.secondaryTextOnWindow)
                            }
                            .padding(DulcetSpacing.sm)
                            .contentShape(Rectangle())
                        }
                        .dulcetMediaButtonStyle()
                        // A button flattens its children into one accessibility element, so the
                        // stable rank identifier lives on the button here; on macOS it stays on
                        // the row's title text inside the Table.
                        .accessibilityIdentifier("dulcet.search.result.\(index)")
                        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
                            title: result.title,
                            subtitle: result.subtitle,
                            kind: result.kind.displayTitle
                        ))
#if os(iOS)
                        .contextMenu {
                            DulcetSearchResultMenuItems(result: result) {
                                onActivateResult(result.id)
                            }
                        } preview: {
                            DulcetContextMenuPreview(
                                store: store,
                                artwork: result.artwork,
                                title: result.title,
                                subtitle: result.subtitle
                            )
                        }
                        .dulcetQueueDragSource(
                            store: store,
                            artwork: result.artwork,
                            title: result.title,
                            isEnabled: result.playableTrack != nil
                        ) { result.playableTrack.map(DulcetQueueAddition.searchResult) }
#endif
                    }
                }
            }
#if os(iOS)
            // `automatic` would already dismiss on scroll here - it only keeps the keyboard for
            // a TextEditor, and this is plain scrollable content. What `interactively` adds is that
            // the keyboard tracks the drag itself, so a partial drag can be reversed to cancel the
            // dismissal instead of committing to it the moment the scroll begins.
            .scrollDismissesKeyboard(.interactively)
#endif
#endif

            HStack(spacing: DulcetSpacing.xs) {
                ForEach(pagedKinds, id: \.rawValue) { kind in
                    Button(loadMoreTitle(for: kind)) {
                        onLoadMore(kind)
                    }
                    .disabled(snapshot.searchLoadingMoreKind != nil)
                }
                if snapshot.searchLoadingMoreKind != nil {
                    ProgressView()
                        .controlSize(.small)
                    Text(DulcetStrings.loadingMore)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
            }
        }
    }

    private var pagedKinds: [DulcetSearchResultKind] {
        [.track, .album, .artist].filter(snapshot.searchHasMoreKinds.contains)
    }

    private func loadMoreTitle(for kind: DulcetSearchResultKind) -> String {
        switch kind {
        case .track: DulcetStrings.loadMoreTracks
        case .album: DulcetStrings.loadMoreAlbums
        case .artist: DulcetStrings.loadMoreArtists
        }
    }

    private func searchMessage(symbol: String, title: String, body: String) -> some View {
        VStack(spacing: DulcetSpacing.sm) {
            Image(systemName: symbol)
                .font(.system(size: 36, weight: .medium))
                .dulcetForeground(.secondaryTextOnWindow)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text(body)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
        }
        .frame(maxWidth: 440)
    }
}

#if os(iOS) || os(macOS)
/// A search result's context menu: its own activation (play a track, open an album or artist),
/// then Go to Album and Go to Artist where the library has those pages.
private struct DulcetSearchResultMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    let onActivate: () -> Void

    var body: some View {
        switch result.kind {
        case .track:
            Button(DulcetStrings.play, systemImage: "play", action: onActivate)
            if let track = result.playableTrack {
                DulcetQueueInsertionMenuItems(addition: .searchResult(track))
            }
            if let track = result.playableTrack, let albumID = store.libraryAlbumID(for: track) {
                Button(DulcetStrings.goToAlbum, systemImage: "square.stack") {
                    store.showAlbum(albumID)
                }
            }
        case .album:
            Button(DulcetStrings.goToAlbum, systemImage: "square.stack", action: onActivate)
        case .artist:
            Button(DulcetStrings.goToArtist, systemImage: "music.mic", action: onActivate)
        }
        if result.kind != .artist {
            ForEach(artistTargets, id: \.id) { target in
                Button(
                    artistTargets.count == 1 ? DulcetStrings.goToArtist : target.name,
                    systemImage: "music.mic"
                ) {
                    store.showArtist(target.id)
                }
            }
        }
    }

    private var artistTargets: [(name: String, id: DulcetProviderItemID)] {
        var seen = Set<DulcetProviderItemID>()
        return result.credits.compactMap { credit in
            guard let id = store.libraryArtistID(for: credit), seen.insert(id).inserted else {
                return nil
            }
            return (credit.name, id)
        }
    }
}
#endif

private extension View {
    @ViewBuilder
    func dulcetSearchFieldStyle() -> some View {
#if os(tvOS)
        textFieldStyle(.plain)
            .padding(.horizontal, DulcetSpacing.md)
            .padding(.vertical, DulcetSpacing.sm)
            .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: 14))
#else
        textFieldStyle(.roundedBorder)
#endif
    }
}

private struct DulcetSearchResultIdentity: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let result: DulcetSearchResult
    let rank: Int?

    var body: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            DulcetArtworkView(artwork: result.artwork, size: DulcetMetrics.denseRowArtworkSize)

            VStack(alignment: .leading, spacing: 0) {
                Text(result.title)
                    .font(.callout.weight(.medium))
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
#if os(macOS)
                    // iOS/tvOS rows are buttons, which absorb child identifiers into the row's
                    // own accessibility element; there the button carries this identifier.
                    .accessibilityIdentifier(rank.map { "dulcet.search.result.\($0)" } ?? "")
#endif
                if !result.subtitle.isEmpty {
                    Text(result.subtitle)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }
            }
        }
        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
            title: result.title,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle
        ))
    }
}

extension DulcetSearchResultKind {
    var displayTitle: String {
        switch self {
        case .track: DulcetStrings.track
        case .album: DulcetStrings.album
        case .artist: DulcetStrings.artist
        }
    }
}
#endif
