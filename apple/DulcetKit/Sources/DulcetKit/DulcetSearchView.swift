#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetSearchView: View {
    @Environment(\.dulcetShellSearchField) private var shellSearchField
#if os(macOS)
    @State private var selectedResultID: MacRow.ID?
    @State private var searchPresented = false
#endif
#if os(iOS) || os(tvOS)
    @FocusState private var searchFieldFocused: Bool
#endif
#if os(iOS) || os(macOS)
    @Environment(DulcetPresentationStore.self) private var store
#endif
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var selectedGroup: DulcetSearchResultKind?
    var recentSearches: [String] = []
    var onRememberSearch: (String) -> Void = { _ in }
    var onClearRecentSearches: () -> Void = {}
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String
    let onLoadMore: (DulcetSearchResultKind) -> Void
    let onRetry: () -> Void
    let onActivateResult: (DulcetSearchResult.ID) -> Void
    /// A search command asked for the field's focus; `onFocusRequestHandled` clears the request.
    var focusRequested = false
    var onFocusRequestHandled: () -> Void = {}
    /// The reader's search, when the reader draws this screen (§16.15): rows from the first
    /// character, labelled with where they came from.
    var reader: DulcetReaderSearchContent?

    private enum Phase {
        case idle
        case loading
        case results
        case empty
        case error
        /// The reader's server search failed and nothing on this device matches (§16.15).
        case readerFailed(DulcetReaderErrorKind)
        /// Offline, and nothing this device has seen matches.
        case readerOffline(DulcetReaderSeenCounts?)
    }

    private var phase: Phase {
        guard let reader else {
            return switch snapshot.state {
            case .searchLoading: .loading
            case .searchResults: .results
            case .searchEmpty: .empty
            case .searchError: .error
            default: .idle
            }
        }
        return switch reader.presentation {
        case .idle: .idle
        case .waiting: .loading
        case .rows: .results
        case .noMatches: .empty
        case let .failed(kind): .readerFailed(kind)
        case let .offlineNoMatches(seen): .readerOffline(seen)
        }
    }

    private var allResults: [DulcetSearchResult] {
        reader.map { $0.publication?.rows.map(\.result) ?? [] } ?? snapshot.searchResults
    }

    private let groupKinds: [DulcetSearchResultKind] = [.artist, .album, .track]

    private var results: [DulcetSearchResult] {
        groupKinds.filter { selectedGroup == nil || selectedGroup == $0 }.flatMap { kind in
            let rows = allResults.filter { $0.kind == kind }
            return selectedGroup == nil ? Array(rows.prefix(5)) : rows
        }
    }

    private func groupTitle(_ kind: DulcetSearchResultKind) -> String {
        switch kind {
        case .artist: DulcetStrings.readerArtists
        case .album: DulcetStrings.readerAlbums
        case .track: DulcetStrings.readerSongs
        }
    }

    private func readerRow(_ id: DulcetSearchResult.ID) -> DulcetReaderSearchRow? {
        reader?.publication?.rows.first { $0.id == id }
    }

    private func activate(_ id: DulcetSearchResult.ID) {
        onRememberSearch(searchQuery)
        if let reader, let row = readerRow(id) {
            reader.onActivate(row)
        } else {
            onActivateResult(id)
#if os(iOS)
            store.dismissSearchKeyboard()
#endif
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
#if os(iOS)
            if !shellSearchField {
                fallbackSearchField
            }
#elseif os(tvOS)
            let prompt = Text(DulcetStrings.searchPrompt)
                .font(.title3)
                .dulcetPromptForeground(searchFieldFocused ? .accountPromptOnTVFocus : .accountPromptOnTVWindow)
            TextField(DulcetStrings.searchPrompt, text: $searchQuery, prompt: prompt)
                .font(.title3)
                .textFieldStyle(.automatic)
                .controlSize(.small)
                .focused($searchFieldFocused)
                .dulcetTVFieldPlate(isFocused: searchFieldFocused)
                .onSubmit { onRememberSearch(searchQuery) }
                .accessibilityLabel(DulcetStrings.searchPrompt)
                .accessibilityIdentifier("dulcet.search.field")
                .padding(.horizontal, DulcetSpacing.lg)
#endif

            searchContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
#if os(tvOS)
                // Results run off the bottom of the screen, as a shelf runs off its side, rather
                // than being cut along a line short of it through whatever heading is passing.
                .ignoresSafeArea(edges: .bottom)
#endif
        }
#if os(tvOS)
        .padding(.top, DulcetSpacing.lg)
#elseif os(iOS)
        // Open at the bottom: results scroll under the player and the tab bar and fade with the
        // system's edge effect, rather than stopping along a hard line above them.
        .padding(.top, DulcetSpacing.lg)
        .dulcetContentMarginPadding()
#else
        .padding(.vertical, DulcetSpacing.lg)
        .dulcetContentMarginPadding()
#endif
        .background(Color.dulcetWindow)
#if !os(macOS)
        .dulcetForeground(.primaryTextOnWindow)
#endif
#if os(tvOS)
        // The title every Apple TV section root uses: the system bar's, under the section bar.
        .dulcetNavigationTitle(DulcetStrings.search, keepsTVNavigationBar: true)
#else
        .dulcetNavigationTitle(DulcetStrings.search)
#endif
        .onChange(of: searchQuery) { _, _ in selectedGroup = nil }
#if os(iOS)
        // A result was used: this page's own field (compact accessibility layouts) lets go of the
        // keyboard too. The system field is released by the shell, which owns its focus.
        .onChange(of: store.searchKeyboardDismissals) { _, _ in searchFieldFocused = false }
#endif
#if os(macOS)
        .searchable(text: $searchQuery, isPresented: $searchPresented, placement: .toolbar,
                    prompt: Text(DulcetStrings.searchPrompt))
        .onSubmit(of: .search) { onRememberSearch(searchQuery) }
        .onAppear(perform: takeRequestedFocus)
        .onChange(of: focusRequested) { _, _ in takeRequestedFocus() }
#endif
    }

#if os(iOS)
    // Compact accessibility layouts and older shells keep the field in the page.
    private var fallbackSearchField: some View {
        HStack(spacing: DulcetSpacing.xxs) {
            Image(systemName: "magnifyingglass")
                .font(.callout)
                .dulcetForeground(.secondaryTextOnControl)
                .accessibilityHidden(true)
                .padding(.leading, DulcetSpacing.sm)
            TextField(DulcetStrings.searchPrompt, text: $searchQuery,
                      prompt: Text(DulcetStrings.searchPrompt).dulcetPromptForeground(.secondaryTextOnControl))
                .textFieldStyle(.plain)
                .dulcetForeground(.primaryTextOnControl)
                .focused($searchFieldFocused)
                .submitLabel(.search)
                .onSubmit {
                    onRememberSearch(searchQuery)
                    searchFieldFocused = false
                }
                .onAppear(perform: takeRequestedFocus)
                .onChange(of: focusRequested) { _, _ in takeRequestedFocus() }
                .accessibilityLabel(DulcetStrings.searchPrompt)
                .accessibilityIdentifier("dulcet.search.field")
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .dulcetForeground(.secondaryTextOnControl)
                        .frame(minWidth: DulcetMetrics.minimumTouchTarget, minHeight: DulcetMetrics.minimumTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(DulcetStrings.searchClearQuery)
                .accessibilityShowsLargeContentViewer()
                .accessibilityIdentifier("dulcet.search.clearQuery")
            }
        }
        .padding(.trailing, searchQuery.isEmpty ? DulcetSpacing.sm : 0)
        .frame(minHeight: DulcetMetrics.minimumTouchTarget)
        .background(Color.dulcetControl, in: Capsule())
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
#endif

#if os(iOS) || os(macOS)
    private func takeRequestedFocus() {
        guard focusRequested else { return }
#if os(macOS)
        searchPresented = true
#else
        searchFieldFocused = true
#endif
        onFocusRequestHandled()
    }
#endif

    @ViewBuilder
    private var searchContent: some View {
        switch phase {
        case .loading:
            VStack(spacing: DulcetSpacing.sm) {
                ProgressView()
                Text(DulcetStrings.searchLoading)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        case .results:
            resultsTable
        case .empty:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchEmptyTitle,
                body: DulcetStrings.searchEmptyBody
            )
        case .error:
            searchMessage(
                symbol: "exclamationmark.magnifyingglass",
                title: DulcetStrings.searchErrorTitle,
                body: DulcetStrings.searchErrorBody,
                retry: true
            )
        case let .readerFailed(kind):
            // A failure is never drawn as "no matches": the server was not heard from.
            searchMessage(
                symbol: "exclamationmark.magnifyingglass",
                title: DulcetStrings.searchErrorTitle,
                body: DulcetStrings.readerSearchFailedBody(DulcetStrings.readerErrorPhrase(kind)),
                retry: true,
                identifier: "dulcet.search.failed"
            )
        case let .readerOffline(seen):
            searchMessage(
                symbol: "wifi.slash",
                title: DulcetStrings.readerSearchOfflineEmptyTitle,
                body: DulcetReaderCopy.searchScopeLabel(.deviceOffline(seen)) ?? DulcetStrings.readerSearchScopeDevice
            )
        case .idle:
            if recentSearches.isEmpty {
                searchMessage(
                    symbol: "magnifyingglass",
                    title: DulcetStrings.searchIdleTitle,
                    body: reader == nil ? DulcetStrings.searchIdleBody : DulcetStrings.readerSearchIdleBody
                )
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        recentSearchesHeader
#if os(tvOS)
                            // Leave the resting gap clear when the first recent lifts under focus.
                            .padding(.bottom, DulcetSpacing.xs)
#elseif os(iOS)
                            .padding(.bottom, dynamicTypeSize.isAccessibilitySize ? DulcetSpacing.xs : 0)
#endif
                        ForEach(recentSearches, id: \.self) { query in
                            Button { searchQuery = query } label: {
                                Label(query, systemImage: "clock.arrow.circlepath")
#if os(tvOS)
                                    .font(.callout)
                                    .frame(maxWidth: .infinity, minHeight: DulcetMetrics.rowMinHeight(artwork: true), alignment: .leading)
#else
                                    .frame(maxWidth: .infinity, minHeight: DulcetMetrics.minimumTouchTarget, alignment: .leading)
#endif
                            }
                            .dulcetRowButtonStyle()
                            .accessibilityIdentifier("dulcet.search.recent.\(query)")
                        }
                    }
#if os(tvOS)
                    // On the field's edge, and clear of the screen's when a row lifts.
                    .padding(.horizontal, DulcetSpacing.lg)
                    .padding(.top, DulcetSpacing.md)
#endif
                }
            }
        }
    }

    @ViewBuilder
    private var recentSearchesHeader: some View {
        if dynamicTypeSize.isAccessibilitySize {
            stackedRecentSearchesHeader
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: DulcetSpacing.sm) {
                    recentSearchesTitle.fixedSize()
                    Spacer(minLength: 0)
                    clearRecentSearchesButton.fixedSize()
                }
                stackedRecentSearchesHeader
            }
        }
    }

    private var stackedRecentSearchesHeader: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            recentSearchesTitle
            clearRecentSearchesButton
        }
    }

    private var recentSearchesTitle: some View {
        Text(DulcetStrings.searchRecentTitle)
#if os(tvOS)
            .font(.title3.weight(.semibold))
#else
            .font(.title2.weight(.semibold))
#endif
            .accessibilityAddTraits(.isHeader)
    }

    private var clearRecentSearchesButton: some View {
        Button(DulcetStrings.searchRecentClear, action: onClearRecentSearches)
#if os(tvOS)
            .buttonStyle(DulcetTVActionButtonStyle())
#elseif os(iOS)
            .dulcetForeground(.accentTextOnWindow)
#endif
            .accessibilityIdentifier("dulcet.search.recents.clear")
    }

#if os(tvOS)
    /// Results as tvOS Music draws them: a plain heading per kind over a shelf of focusable
    /// artwork that runs off the screen edge. `rank` stays the row's place in `results`.
    private var resultsTable: some View {
        ScrollView {
            // Tight enough that the first row under a second heading shows on the first screen.
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                if let label = DulcetReaderCopy.searchScopeLabel(reader?.publication?.scope) {
                    Label(label, systemImage: "wifi.slash")
                        .font(.caption.weight(.medium))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                        .accessibilityIdentifier("dulcet.search.scope")
                }
                if selectedGroup != nil {
                    Button(DulcetStrings.searchAllResults) { selectedGroup = nil }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("dulcet.search.group.all")
                }
                ForEach(groupKinds, id: \.rawValue) { kind in
                    let ranked = Array(results.enumerated()).filter { $0.element.kind == kind }
                    if !ranked.isEmpty {
                        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                            if selectedGroup == nil {
                                DulcetShelfHeader(title: groupTitle(kind),
                                                  identifier: "dulcet.search.group.\(kind.rawValue)") {
                                    selectedGroup = kind
                                }
                            } else {
                                Text(groupTitle(kind))
                                    .font(.title3.weight(.semibold))
                                    .accessibilityAddTraits(.isHeader)
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(alignment: .top, spacing: DulcetSpacing.lg) {
                                    ForEach(ranked, id: \.element.id) { rank, result in
                                        resultTile(result, rank: rank)
                                    }
                                }
                                .padding(.top, DulcetSpacing.md)
                                .padding(.bottom, DulcetSpacing.xs)
                            }
                            .modifier(DulcetEdgeToEdgeShelf(inset: DulcetSpacing.lg))
                            .focusSection()
                        }
                    }
                }
                loadMoreRow
            }
            // The page's side inset lives inside the scroll view, so a shelf can reach past it.
            .padding(.horizontal, DulcetSpacing.lg)
            .padding(.bottom, DulcetSpacing.lg)
        }
    }

    /// The width of an artist page's album tiles: several shelves share the screen here too.
    private static let tvTileWidth: CGFloat = 260

    private func resultTile(_ result: DulcetSearchResult, rank: Int) -> some View {
        let row = readerRow(result.id)
        let muted = row?.isUnavailableOffline == true
        return Button {
            activate(result.id)
        } label: {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                DulcetArtworkView(artwork: result.artwork, size: Self.tvTileWidth, muted: muted, role: result.kind == .artist ? .artist : .cover)
                    .clipShape(result.kind == .artist
                        ? AnyShape(Circle())
                        : AnyShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: Self.tvTileWidth), style: .continuous)))
                    .dulcetTVArtworkFocusEffect(circular: result.kind == .artist)
                VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                    Text(result.displayTitle)
                        .font(.subheadline.weight(.medium))
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(1)
                    if !result.subtitle.isEmpty {
                        Text(result.subtitle)
                            .font(.caption)
                            .dulcetForeground(.secondaryTextOnWindow)
                            .lineLimit(1)
                    }
                    if let row, row.source == .device || muted {
                        Text(muted ? DulcetStrings.readerNotAvailableOffline : DulcetStrings.readerSearchRowDevice)
                            .font(.caption2.weight(.medium))
                            .dulcetForeground(.secondaryTextOnWindow)
                            .lineLimit(1)
                    }
                }
                .dulcetTVClearsLiftedArtwork(width: Self.tvTileWidth)
            }
            .frame(width: Self.tvTileWidth, alignment: .leading)
        }
        .dulcetMediaButtonStyle(hover: .lift)
        .accessibilityIdentifier("dulcet.search.result.\(rank)")
        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
            title: result.displayTitle,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle
        ))
    }
#elseif os(macOS)
    private enum MacRow: Identifiable {
        enum ID: Hashable {
            case result(DulcetSearchResult.ID)
            case paging
        }

        case result(Int, DulcetSearchResult)
        case paging

        var id: ID {
            switch self {
            case let .result(_, result): .result(result.id)
            case .paging: .paging
            }
        }
    }

    private var macRows: [MacRow] {
        var rows = results.enumerated().map(MacRow.result)
        if !pagedKinds.isEmpty || snapshot.searchLoadingMoreKind != nil {
            rows.append(.paging)
        }
        return rows
    }

    private var resultsTable: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                Text(selectedGroup.map(groupTitle) ?? DulcetStrings.bestMatches)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(DulcetStrings.searchResultCount(selectedGroup.map { kind in
                    allResults.filter { $0.kind == kind }.count
                } ?? allResults.count))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityIdentifier("dulcet.search.resultCount")
            }
            if let label = DulcetReaderCopy.searchScopeLabel(reader?.publication?.scope) {
                Label(label, systemImage: "iphone")
                    .font(.caption.weight(.medium))
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
                    .accessibilityIdentifier("dulcet.search.scope")
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: DulcetSpacing.sm) {
                    if selectedGroup != nil {
                        Button(DulcetStrings.searchAllResults) { selectedGroup = nil }
                            .accessibilityIdentifier("dulcet.search.group.all")
                    }
                    ForEach(groupKinds, id: \.rawValue) { kind in
                        let count = allResults.filter { $0.kind == kind }.count
                        if count > 0 {
                            Button(DulcetStrings.librarySearchSeeAll(groupTitle(kind), count: count)) { selectedGroup = kind }
                                .accessibilityIdentifier("dulcet.search.group.\(kind.rawValue)")
                                .accessibilityAddTraits(selectedGroup == kind ? .isSelected : [])
                        }
                    }
                }
            }
            GeometryReader { geometry in
                macResultsTable(availableWidth: geometry.size.width)
            }
        }
    }

    private func macResultsTable(availableWidth: CGFloat) -> some View {
        let minimumResultWidth: CGFloat = 320 // design-token: allow existing Mac Search Result column minimum
        let typeWidth: CGFloat = 90 // design-token: allow existing Mac Search Type column ideal
        // The native table adds space around its columns. Leave that space and the Type
        // column visible when Search opens in a compact window.
        let resultWidth = max(minimumResultWidth, min(620, availableWidth - typeWidth - DulcetSpacing.xxl))
        return Table(of: MacRow.self, selection: $selectedResultID) {
            TableColumn(DulcetStrings.resultColumn) { row in
                switch row {
                case let .result(rank, result):
                    if let track = result.playableTrack {
                        songRow(
                            track, result: result, rank: rank, tableSelection: selectedResultID == row.id
                        )
                        .padding(.trailing, DulcetSpacing.md)
                        .environment(store)
                    } else {
                        DulcetSearchResultIdentity(
                            result: result, rank: rank, readerRow: readerRow(result.id),
                            selected: selectedResultID == row.id
                        ) {
                            activate(result.id)
                        }
                        .padding(.trailing, DulcetSpacing.md)
                        .environment(store)
                    }
                case .paging:
                    loadMoreRow
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .padding(.leading, DulcetSongRowMetrics().artworkSize + DulcetSpacing.xs)
                        .frame(minHeight: DulcetMetrics.rowMinHeight(artwork: true))
                }
            }
            .width(min: minimumResultWidth, ideal: resultWidth)

            TableColumn(DulcetStrings.typeColumn) { row in
                if case let .result(_, result) = row {
                    Text(result.kind == .track ? DulcetStrings.searchSongType : result.kind.displayTitle)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
            }
            .width(min: 72, ideal: typeWidth, max: 120)
        } rows: {
            ForEach(macRows) { row in
                TableRow(row).selectionDisabled(row.id == .paging)
            }
        }
        .tableStyle(.bordered)
        .alternatingRowBackgrounds(.disabled)
        .contextMenu(forSelectionType: MacRow.ID.self) { selection in
            if case let .result(id)? = selection.first,
               let result = results.first(where: { $0.id == id }) {
                DulcetSearchResultMenuItems(result: result, readerRow: readerRow(id)) {
                    selectedResultID = .result(id)
                    activate(id)
                }
                .environment(store)
            }
        } primaryAction: { selection in
            guard case let .result(id)? = selection.first else { return }
            selectedResultID = .result(id)
            activate(id)
        }
    }
#else
    /// Results as Music lists them on a phone or tablet: a heading per kind that opens the whole
    /// kind, over artwork rows. The opened kind says how many it holds and leads back to all.
    private var resultsTable: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if let selectedGroup {
                Button {
                    self.selectedGroup = nil
                } label: {
                    Label(DulcetStrings.searchAllResults, systemImage: "chevron.backward")
                }
                .accessibilityIdentifier("dulcet.search.group.all")
                HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                    Text(groupTitle(selectedGroup))
                        .font(.title2.bold())
                        .accessibilityAddTraits(.isHeader)
                    Text(DulcetStrings.searchResultCount(allResults.filter { $0.kind == selectedGroup }.count))
                        .font(.footnote)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityIdentifier("dulcet.search.resultCount")
                }
            }
            if let label = DulcetReaderCopy.searchScopeLabel(reader?.publication?.scope) {
                Label(label, systemImage: "iphone")
                    .font(.caption.weight(.medium))
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
                    .accessibilityIdentifier("dulcet.search.scope")
            }
            // Selection is the macOS Table's idiom; touch shares the library's single-activation
            // idiom instead -- one press on a row routes its semantic action (play a track, open
            // an album or artist) through onActivateResult. A plain List hosts the rows on iOS so
            // song rows carry their leading Favorite and trailing queue swipe actions.
            List {
                topResultCard
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                resultRows
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .contentMargins(.zero, for: .scrollContent)
            // `automatic` would already dismiss on scroll here - it only keeps the keyboard for
            // a TextEditor, and this is plain scrollable content. What `interactively` adds is that
            // the keyboard tracks the drag itself, so a partial drag can be reversed to cancel the
            // dismissal instead of committing to it the moment the scroll begins.
            .scrollDismissesKeyboard(.interactively)
            // An inset rather than a sibling, which would part the scroll view from the screen's
            // bottom edge; it stays reachable however far the rows have scrolled.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !pagedKinds.isEmpty || snapshot.searchLoadingMoreKind != nil {
                    loadMoreRow
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, DulcetSpacing.xs)
                        .background(Color.dulcetWindow)
                }
            }
        }
    }

    /// The core's best match leads the page, over the groups. Not drawn inside one kind's list, and
    /// not when the core named no row that leads.
    @ViewBuilder
    private var topResultCard: some View {
        if selectedGroup == nil, let reader, let publication = reader.publication, let row = publication.topResult {
            DulcetSearchTopResultCard(
                row: row,
                accessibilityLabel: resultAccessibilityLabel(row.result),
                onOpen: { activate(row.id) },
                onPlay: {
                    if !store.playsTopResult(row) { onRememberSearch(searchQuery) }
                    store.playSearchTopResult(row, in: publication)
                },
                isPlaying: store.playsTopResult(row)
            )
        }
    }

    @ViewBuilder
    private var resultRows: some View {
        ForEach(Array(results.enumerated()), id: \.element.id) { index, result in
            let startsKind = index == 0 || results[index - 1].kind != result.kind
            if startsKind, selectedGroup == nil {
                DulcetShelfHeader(
                    title: groupTitle(result.kind),
                    identifier: "dulcet.search.group.\(result.kind.rawValue)"
                ) {
                    selectedGroup = result.kind
                }
                .padding(.top, index == 0 ? 0 : DulcetSpacing.lg)
                .padding(.bottom, DulcetSpacing.xxs)
            }
            if let track = result.playableTrack {
                songRow(track, result: result, rank: index)
                    .dulcetSongRowSeparator(above: !startsKind, songRowShowingIndex: false, artwork: true)
            } else {
                Button {
                    activate(result.id)
                } label: {
                    DulcetSearchResultRowLabel(result: result, readerRow: readerRow(result.id))
                }
                .dulcetRowButtonStyle()
                .accessibilityIdentifier("dulcet.search.result.\(index)")
                .accessibilityLabel(resultAccessibilityLabel(result))
                // The system preview lets album results lift out of the open menu for queue drag.
                .contextMenu {
                    DulcetSearchResultMenuItems(result: result, readerRow: readerRow(result.id)) {
                        activate(result.id)
                    }
                }
                .modifier(DulcetSearchRowQueueDrag(
                    result: result,
                    isUnavailableOffline: readerRow(result.id)?.isUnavailableOffline == true
                ))
                .dulcetSongRowSeparator(above: !startsKind, songRowShowingIndex: false, artwork: true)
            }
        }
    }
#endif

#if os(iOS) || os(macOS)
    private func songRow(
        _ track: DulcetTrack,
        result: DulcetSearchResult,
        rank: Int,
        tableSelection: Bool? = nil
    ) -> some View {
        let row = readerRow(result.id)
        let downloadState = store.downloadState(for: track)
        return DulcetTrackRow(
            track: track.replacingDownloadState(downloadState),
            showAlbum: true,
            index: rank + 1,
            offline: row?.isUnavailableOffline == true,
            isCurrent: store.isNowPlaying(track),
            showsIndex: false,
            queueAddition: .searchResult(track),
            onActivate: { activate(result.id) },
            onDownload: store.downloadsEnabled ? { store.requestDownload(track) } : nil,
            activationIdentifier: "dulcet.search.result.\(rank)"
        ) {
            if let row {
                DulcetFavouriteIndicator(id: row.id, published: row.isFavourite)
            }
        }
        .searchPresentation(
            deviceOnly: row?.source == .device,
            label: resultAccessibilityLabel(result),
            tableSelection: tableSelection
        ) {
            DulcetSearchResultMenuItems(result: result, readerRow: row) { activate(result.id) }
                .environment(store)
        }
    }

    private func resultAccessibilityLabel(_ result: DulcetSearchResult) -> String {
        var label = DulcetStrings.searchResultAccessibility(
            title: result.displayTitle,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle
        )
        // What the row shows under its title, said too: never availability by dimming alone.
        let row = readerRow(result.id)
        if result.kind != .track, row?.isUnavailableOffline == true {
            label += ", " + DulcetStrings.readerNotAvailableOffline
        } else if result.kind != .track, row?.source == .device {
            label += ", " + DulcetStrings.readerSearchRowDevice
        }
        return label
    }
#endif

    private var loadMoreRow: some View {
#if os(macOS)
        let spacing = DulcetSpacing.md
#else
        let spacing = DulcetSpacing.xs
#endif
        return HStack(spacing: spacing) {
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

    private var pagedKinds: [DulcetSearchResultKind] {
        // The reader's search has no per-kind paging (§16.15): what it holds is what it shows.
        guard reader == nil else { return [] }
        return [.track, .album, .artist].filter(snapshot.searchHasMoreKinds.contains)
    }

    private func loadMoreTitle(for kind: DulcetSearchResultKind) -> String {
        switch kind {
        case .track: DulcetStrings.searchShowMoreSongs
#if os(macOS)
        case .album: DulcetStrings.searchShowMoreAlbums
        case .artist: DulcetStrings.searchShowMoreArtists
#else
        case .album: DulcetStrings.loadMoreAlbums
        case .artist: DulcetStrings.loadMoreArtists
#endif
        }
    }

    /// Every search state without results is the shared state message, so the field's page reads
    /// like every other empty page and scrolls when the keyboard and a large text size leave it
    /// no room.
    private func searchMessage(
        symbol: String,
        title: String,
        body: String,
        retry: Bool = false,
        identifier: String? = nil
    ) -> some View {
        DulcetStateMessage(
            symbol: symbol,
            title: title,
            message: body,
            accessibilityIdentifier: identifier,
            action: retry ? DulcetStateAction(DulcetStrings.searchRetry, isDefaultAction: false, handler: onRetry) : nil
        )
    }
}

#if os(iOS) || os(macOS)
/// A track in the results drags as itself; an album drags whole, its tracks read when it is
/// dropped. An artist has no tracks of its own and lifts a card that says so. Chosen by the
/// result's kind, which a row never changes.
private struct DulcetSearchRowQueueDrag: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    let isUnavailableOffline: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if result.kind == .album {
            content.dulcetDeferredQueueDragSource(
                store: store,
                artwork: result.artwork,
                title: result.displayTitle,
                isEnabled: !isUnavailableOffline
            ) { completion in
                store.resolveReaderAddition(
                    query: .album(rawID: result.id.rawID),
                    kind: .album,
                    id: result.id,
                    title: result.displayTitle,
                    completion: completion
                )
            }
        } else {
            content.dulcetQueueDragSource(
                store: store,
                artwork: result.artwork,
                title: result.displayTitle,
                isEnabled: result.playableTrack != nil && !isUnavailableOffline
            ) { result.playableTrack.map(DulcetQueueAddition.searchResult) }
        }
    }
}

/// A search result's context menu: its own activation (play a track, open an album or artist),
/// then Go to Album and Go to Artist where the library has those pages.
private struct DulcetSearchResultMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    var readerRow: DulcetReaderSearchRow?
    let onActivate: () -> Void

    var body: some View {
        switch result.kind {
        case .track:
            Button(DulcetStrings.play, systemImage: "play", action: onActivate)
            if let track = result.playableTrack, readerRow?.isUnavailableOffline != true {
                DulcetQueueInsertionMenuItems(addition: .searchResult(track))
                if store.downloadsEnabled {
                    switch store.downloadState(for: track) {
                    case .notDownloaded, .cached:
                        Button(DulcetStrings.download, systemImage: "arrow.down.circle") {
                            store.requestDownload(track)
                        }
                    case .interrupted, .failed:
                        Button(DulcetStrings.retryDownload, systemImage: "arrow.clockwise") {
                            store.requestDownload(track)
                        }
                    case .downloaded, .stale:
                        Button(DulcetStrings.keepDownload, systemImage: "arrow.down.circle.fill") { store.requestDownload(track) }
                    case .queued, .downloading:
                        EmptyView()
                    }
                }
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
        if let readerRow, let target = readerRow.favouriteTarget {
            DulcetMyLibraryButton(target: target, menu: true)
            DulcetFavouriteMenuItem(target: target, published: readerRow.isFavourite)
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

#if os(iOS)
/// One search result on a phone or tablet: artwork the size of the library's rows, a circle for an
/// artist, and the favourite at the trailing edge. At accessibility sizes the art sits over the
/// words, so a title keeps the full width and never breaks inside a word.
private struct DulcetSearchResultRowLabel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private var metrics = DulcetSongRowMetrics()
    let result: DulcetSearchResult
    var readerRow: DulcetReaderSearchRow?

    private var unavailableOffline: Bool { readerRow?.isUnavailableOffline == true }

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: DulcetSpacing.xs))
            : AnyLayout(HStackLayout(spacing: DulcetSpacing.xs))
        HStack(spacing: DulcetSpacing.xs) {
            layout {
                // The song row's artwork column, so search rows and song rows share one anatomy.
                DulcetArtworkView(artwork: result.artwork, size: metrics.artworkSize, muted: unavailableOffline,
                                  role: result.kind == .artist ? .artist : .cover)
                    .clipShape(result.kind == .artist
                        ? AnyShape(Circle())
                        : AnyShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: metrics.artworkSize), style: .continuous)))
                VStack(alignment: .leading, spacing: 2) {
                    Text(result.displayTitle)
                        .font(.body)
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    if !result.subtitle.isEmpty {
                        Text(result.subtitle)
                            .font(.footnote)
                            .dulcetForeground(.secondaryTextOnWindow)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    }
                    if let readerRow, readerRow.source == .device || unavailableOffline {
                        Text(unavailableOffline
                            ? DulcetStrings.readerNotAvailableOffline
                            : DulcetStrings.readerSearchRowDevice)
                            .font(.caption2.weight(.medium))
                            .dulcetForeground(.secondaryTextOnWindow)
                            .lineLimit(nil)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let readerRow, readerRow.favouriteTarget != nil {
                DulcetFavouriteIndicator(id: readerRow.id, published: readerRow.isFavourite)
            }
        }
        .padding(.vertical, DulcetSpacing.xs)
        .contentShape(Rectangle())
    }
}
#endif

#if os(macOS)
private struct DulcetSearchResultIdentity: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    let rank: Int
    let readerRow: DulcetReaderSearchRow?
    let selected: Bool
    let onActivate: () -> Void
    @State private var hovering = false
    private var metrics = DulcetSongRowMetrics()

    private var unavailableOffline: Bool { readerRow?.isUnavailableOffline == true }

    var body: some View {
        HStack(spacing: DulcetSpacing.xxs) {
            identity
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel(accessibilityLabel)
            DulcetMacRowMore(
                title: result.displayTitle, visible: hovering || selected, pair: .secondaryTextOnWindow,
                identifier: "dulcet.search.more.\(rank)"
            ) {
                DulcetSearchResultMenuItems(result: result, readerRow: readerRow, onActivate: onActivate)
                    .environment(store)
            }
        }
        .frame(minHeight: DulcetMetrics.rowMinHeight(artwork: true))
        .onHover { hovering = $0 }
    }

    private var identity: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            DulcetArtworkView(
                artwork: result.artwork,
                size: metrics.artworkSize,
                muted: unavailableOffline,
                role: result.kind == .artist ? .artist : .cover
            )

            VStack(alignment: .leading, spacing: 0) {
                Text(result.displayTitle)
                    .font(.callout.weight(.medium))
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    .accessibilityIdentifier("dulcet.search.result.\(rank)")
                if !result.subtitle.isEmpty {
                    Text(result.subtitle)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }
                if let readerRow, readerRow.source == .device || unavailableOffline {
                    Text(unavailableOffline
                        ? DulcetStrings.readerNotAvailableOffline
                        : DulcetStrings.readerSearchRowDevice)
                        .font(.caption2.weight(.medium))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
            }
            if let readerRow, readerRow.favouriteTarget != nil {
                DulcetFavouriteIndicator(id: readerRow.id, published: readerRow.isFavourite)
            }
        }
    }

    private var accessibilityLabel: String {
        var label = DulcetStrings.searchResultAccessibility(
            title: result.displayTitle,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle
        )
        if unavailableOffline {
            label += ", " + DulcetStrings.readerNotAvailableOffline
        } else if readerRow?.source == .device {
            label += ", " + DulcetStrings.readerSearchRowDevice
        }
        return label
    }
}

#endif

/// What the reader's search shows, and what activating a row does.
struct DulcetReaderSearchContent {
    let query: String
    let publication: DulcetReaderSearchPublication?
    let onActivate: (DulcetReaderSearchRow) -> Void

    /// What the screen says for the reader's search (§16.15).
    enum Presentation: Equatable {
        /// Nothing typed.
        case idle
        /// No row yet, and the server's answer is still coming.
        case waiting
        /// Rows to draw; their scope label says where they came from.
        case rows
        /// The server answered, and nothing matches.
        case noMatches
        /// The server's search failed (a timeout included) and nothing on this device matches.
        case failed(DulcetReaderErrorKind)
        /// Offline, and nothing this device has seen matches.
        case offlineNoMatches(DulcetReaderSeenCounts?)
    }

    var presentation: Presentation {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .idle }
        guard let publication else { return .waiting }
        if !publication.rows.isEmpty { return .rows }
        switch publication.scope {
        case .deviceWhileServerPending:
            // Nothing on the device yet; the server's answer is still coming for a query it is
            // asked. The core bounds this wait (LibrarySearchConfig.serverAnswerDeadlineMillis).
            return trimmed.count >= 2 ? .waiting : .noMatches
        case .serverAndDevice:
            return .noMatches
        case let .deviceServerFailed(kind, _):
            return .failed(kind)
        case let .deviceOffline(seen):
            return .offlineNoMatches(seen)
        }
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
