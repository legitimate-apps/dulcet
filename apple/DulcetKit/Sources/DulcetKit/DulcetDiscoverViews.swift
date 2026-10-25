#if os(iOS) || os(macOS)
import SwiftUI

public struct DulcetDiscoverSearchView: View {
    @State private var model: DulcetDiscoverModel
    @State private var query = ""
    private let openLibrary: (DulcetDiscoverLibraryRef) -> Void
    public init(reader: any DulcetDiscoverReading, openLibrary: @escaping (DulcetDiscoverLibraryRef) -> Void) {
        _model = State(initialValue: DulcetDiscoverModel(reader: reader)); self.openLibrary = openLibrary
    }
    public var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                TextField(DulcetStrings.discoverSearchPrompt, text: $query)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("discover.query")
                    .onChange(of: query) { _, query in model.reader.setQuery(query) }
                Text(DulcetStrings.discoverCredit).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: DulcetSpacing.md) {
                        if let publication = model.publication {
                            ForEach(publication.groups, id: \.kind) { group in
                                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                                    Text(groupTitle(group.kind)).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                                        .accessibilityIdentifier("discover.group.\(group.kind)")
                                    DulcetDiscoverStatusView(status: .init(age: group.page?.age, error: group.error, connection: publication.connection)) {
                                        model.reader.setQuery(query)
                                    }
                                    if let page = group.page {
                                        ForEach(page.items, id: \.id) { item in
                                            metadataLink(item)
                                            .buttonStyle(.plain)
                                            .accessibilityIdentifier("discover.item.\(item.id)")
                                            .accessibilityLabel(DulcetStrings.readerRowAccessibility(item.title, DulcetDiscoverItemPresentation(item: item).availabilityText))
                                        }
                                        if page.nextCursor != nil {
                                            Button(DulcetStrings.libraryLoadMore) { model.reader.loadMore(kind: group.kind) }
                                                .disabled(group.loading).accessibilityIdentifier("discover.more.\(group.kind)")
                                        }
                                    }
                                    if group.loading { ProgressView().accessibilityLabel(DulcetStrings.loadingMore) }
                                }
                            }
                            if !query.isEmpty, !publication.groups.isEmpty, publication.query == query.trimmingCharacters(in: .whitespacesAndNewlines), publication.groups.allSatisfy({ !$0.loading && $0.error == nil && $0.page != nil && $0.page?.items.isEmpty == true }) {
                                Text(DulcetStrings.discoverNoResults).dulcetForeground(.secondaryTextOnWindow)
                            }
                        } else { ProgressView() }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
#if os(iOS)
                .scrollDismissesKeyboard(.interactively)
#endif
            }
            .padding(DulcetSpacing.md)
#if os(macOS)
            .navigationDestination(for: DulcetDiscoverDestination.self) { destination in
                DulcetDiscoverDetailView(reader: model.reader, destination: destination, openLibrary: openLibrary)
            }
#endif
        }
        .dulcetForeground(.primaryTextOnWindow)
        .onAppear { model.open(); model.reader.setQuery(query) }
        .onDisappear { model.close(); model.reader.setQuery("") }
    }
    @ViewBuilder
    private func metadataLink(_ item: DulcetDiscoverItem) -> some View {
#if os(iOS)
        // Search is embedded in the shell's navigation owner. Supply the destination
        // here instead of relying on an inner value registration.
        NavigationLink {
            DulcetDiscoverDetailView(reader: model.reader, destination: DulcetDiscoverDestination(item: item), openLibrary: openLibrary)
        } label: {
            DulcetDiscoverItemRow(item: item, reader: model.reader)
        }
#else
        NavigationLink(value: DulcetDiscoverDestination(item: item)) {
            DulcetDiscoverItemRow(item: item, reader: model.reader)
        }
#endif
    }

    private func groupTitle(_ kind: String) -> String {
        switch kind { case "artist": DulcetStrings.artists; case "album": DulcetStrings.albums; case "song": DulcetStrings.myLibrarySongs; default: kind }
    }
}

struct DulcetDiscoverItemRow: View {
    let item: DulcetDiscoverItem
    let reader: any DulcetDiscoverReading
    var body: some View {
        HStack(spacing: DulcetSpacing.sm) {
            DulcetDiscoverArtworkView(reader: reader, artworkId: item.artworkId)
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                Text(verbatim: item.title).font(.headline).dulcetForeground(.primaryTextOnWindow)
                if let artist = item.artist { Text(verbatim: artist).font(.subheadline).dulcetForeground(.secondaryTextOnWindow) }
                Text(DulcetDiscoverItemPresentation(item: item).availabilityText).font(.caption).dulcetForeground(.secondaryTextOnWindow)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).dulcetForeground(.secondaryTextOnWindow).accessibilityHidden(true)
        }
        .padding(.vertical, DulcetSpacing.xs).contentShape(Rectangle())
    }
}

struct DulcetDiscoverStatusView: View {
    let status: DulcetDiscoverStatus
    var retry: () -> Void
    @State private var clock = Date.distantPast
    private var current: DulcetDiscoverStatus {
        .init(age: status.age, error: status.error, connection: status.connection, now: max(clock, status.now))
    }
    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            if let age = current.ageText { Text(age).font(.caption).accessibilityIdentifier("discover.age") }
            if let notice = current.notice { Text(notice).font(.callout).accessibilityIdentifier("discover.notice") }
            if status.error?.retryable == true {
                Button(DulcetStrings.readerTryAgain, action: retry).disabled(!current.canRetry).accessibilityIdentifier("discover.retry")
            }
        }
        .dulcetForeground(.secondaryTextOnWindow)
        .fixedSize(horizontal: false, vertical: true)
        .task(id: status.error?.retryAt) {
            guard let deadline = status.error?.retryAt else { return }
            // Wake only at the server deadline. No animation/polling keeps native input idle.
            try? await Task.sleep(for: .seconds(max(0, deadline.timeIntervalSinceNow)))
            if !Task.isCancelled { clock = .now }
        }
    }
}

struct DulcetDiscoverDetailView: View {
    @State private var model: DulcetDiscoverModel
    @State private var detail: DulcetDiscoverDetailModel
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let destination: DulcetDiscoverDestination
    let openLibrary: (DulcetDiscoverLibraryRef) -> Void
    let openMetadata: ((DulcetDiscoverDestination) -> Void)?
    init(reader: any DulcetDiscoverReading, destination: DulcetDiscoverDestination, openLibrary: @escaping (DulcetDiscoverLibraryRef) -> Void, openMetadata: ((DulcetDiscoverDestination) -> Void)? = nil) {
        _model = State(initialValue: DulcetDiscoverModel(reader: reader))
        _detail = State(initialValue: DulcetDiscoverDetailModel(reader: reader))
        self.destination = destination; self.openLibrary = openLibrary; self.openMetadata = openMetadata
    }
    private var item: DulcetDiscoverItem? {
        switch destination { case .artist: detail.artist?.item; case .album: detail.album?.item; case let .item(item, _): item }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                if let item {
                    DulcetDiscoverArtworkView(reader: model.reader, artworkId: item.artworkId, size: 160)
                    Text(verbatim: item.title).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier(item.kind == "artist" ? "discover.artist.title" : "discover.album.title")
                    if let artist = item.artist { Text(verbatim: artist).font(.title3).dulcetForeground(.secondaryTextOnWindow) }
                    if let year = item.year { Text(year.formatted(.number.grouping(.never))).font(.subheadline).dulcetForeground(.secondaryTextOnWindow) }
                    Text(DulcetDiscoverItemPresentation(item: item).availabilityText).font(.callout)
                    DulcetDiscoverWishActions(model: model, item: item, source: wishSource(item), openLibrary: openLibrary)
                        .disabled(detail.loading)
                    if let error = model.mutationError, error.code != "cancelled" { Text(DulcetStrings.discoverNotSaved).accessibilityIdentifier("discover.save-error") }
                    ForEach(safeLinks(item.links), id: \.url) { link in
                        Button(DulcetStrings.discoverOpenLink(link.relation)) { if let url = URL(string: link.url) { openURL(url) } }
                    }
                }
                if let artist = detail.artist {
                    DulcetDiscoverStatusView(status: .init(age: artist.albums.age, error: detail.error, connection: model.publication?.connection ?? "online")) { detail.load(destination) }
                    ForEach(artist.albums.items, id: \.id) { album in detailLink(album) }
                    if artist.albums.nextCursor != nil, case let .artist(mbid) = destination {
                        Button(DulcetStrings.libraryLoadMore) { detail.loadMoreArtist(mbid) }.disabled(detail.loading)
                    }
                }
                if let album = detail.album {
                    if !album.editions.isEmpty {
                        editionControl(album)
                            .accessibilityIdentifier("discover.edition")
                            .accessibilityValue(editionTitle(album, releaseId: album.selectedReleaseId))
                    }
                    DulcetDiscoverStatusView(status: .init(age: album.tracks.age, error: detail.error, connection: model.publication?.connection ?? "online")) { detail.load(destination) }
                    if detail.requestedReleaseId == nil || album.selectedReleaseId == detail.requestedReleaseId {
                        ForEach(album.tracks.items, id: \.id) { track in detailLink(track) }
                        if album.tracks.nextCursor != nil, case let .album(mbid, _) = destination {
                            Button(DulcetStrings.libraryLoadMore) { detail.loadMoreTracks(mbid) }.disabled(detail.loading)
                        }
                    }
                }
                if detail.loading { ProgressView() }
                if item == nil, let error = detail.error {
                    DulcetDiscoverStatusView(status: .init(age: nil, error: error, connection: model.publication?.connection ?? "online")) { detail.load(destination) }
                }
                Text(DulcetStrings.discoverCredit).font(.caption).dulcetForeground(.secondaryTextOnWindow)
            }
            .padding(DulcetSpacing.md).frame(maxWidth: .infinity, alignment: .leading)
        }
        .dulcetForeground(.primaryTextOnWindow)
        .onAppear { model.open(); detail.load(destination) }
        .onDisappear { model.close(); detail.close() }
    }
    @ViewBuilder
    private func editionControl(_ album: DulcetDiscoverAlbum) -> some View {
#if os(iOS)
        if dynamicTypeSize.isAccessibilitySize {
            // The system menu picker's fixed-height label clips wrapped accessibility text.
            // Keep its selection semantics in the menu and let the visible label grow vertically.
            Menu {
                editionPicker(album).pickerStyle(.inline)
            } label: {
                HStack(alignment: .firstTextBaseline) {
                    Text(editionTitle(album, releaseId: detail.requestedReleaseId ?? album.selectedReleaseId))
                        .fixedSize(horizontal: false, vertical: true)
                    Image(systemName: "chevron.up.chevron.down")
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 44, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(DulcetStrings.discoverEdition)
        } else {
            editionPicker(album)
        }
#else
        editionPicker(album)
#endif
    }

    private func editionTitle(_ album: DulcetDiscoverAlbum, releaseId: String?) -> String {
        guard let edition = album.editions.first(where: { $0.releaseId == releaseId }) else {
            return DulcetStrings.discoverUnknownDate
        }
        return DulcetStrings.discoverEditionTitle(edition.releaseDate ?? DulcetStrings.discoverUnknownDate, edition.trackCount)
    }

    private func editionPicker(_ album: DulcetDiscoverAlbum) -> some View {
        Picker(DulcetStrings.discoverEdition, selection: Binding(get: {
            detail.requestedReleaseId ?? album.selectedReleaseId ?? ""
        }, set: { releaseId in
            if case let .album(mbid, _) = destination { detail.loadAlbum(mbid, releaseId: releaseId) }
        })) {
            ForEach(album.editions, id: \.releaseId) { edition in
                Text(DulcetStrings.discoverEditionTitle(edition.releaseDate ?? DulcetStrings.discoverUnknownDate, edition.trackCount)).tag(edition.releaseId)
            }
        }
    }

    private var sourceType: String {
        switch destination { case .artist: "artist"; case .album: "album"; case let .item(_, source): source.type }
    }
    private func wishSource(_ item: DulcetDiscoverItem) -> DulcetWishSource {
        if case let .item(_, source) = destination { return source }
        return .init(type: sourceType, id: item.id)
    }
    private func safeLinks(_ links: [DulcetDiscoverLink]) -> [DulcetDiscoverLink] {
        links.filter { URL(string: $0.url).map { $0.scheme?.lowercased() == "https" && $0.host != nil && $0.user == nil && $0.password == nil } ?? false }
    }
    private func detailLink(_ item: DulcetDiscoverItem) -> some View {
        let destination = DulcetDiscoverDestination(item: item, source: .init(type: sourceType, id: self.item?.id))
        return Group {
            if let openMetadata {
                Button { openMetadata(destination) } label: { DulcetDiscoverItemRow(item: item, reader: model.reader) }
            } else {
#if os(iOS)
                NavigationLink {
                    DulcetDiscoverDetailView(reader: model.reader, destination: destination, openLibrary: openLibrary)
                } label: {
                    DulcetDiscoverItemRow(item: item, reader: model.reader)
                }
#else
                NavigationLink(value: destination) { DulcetDiscoverItemRow(item: item, reader: model.reader) }
#endif
            }
        }
        .buttonStyle(.plain).accessibilityIdentifier("discover.item.\(item.id)")
        .accessibilityLabel(DulcetStrings.readerRowAccessibility(item.title, DulcetDiscoverItemPresentation(item: item).availabilityText))
    }
}

struct DulcetDiscoverWishActions: View {
    let model: DulcetDiscoverModel
    let item: DulcetDiscoverItem
    let source: DulcetWishSource
    let openLibrary: (DulcetDiscoverLibraryRef) -> Void
    var body: some View {
        let row = model.row(for: item)
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if let row {
                if row.pending { Text(DulcetStrings.discoverWaiting).accessibilityIdentifier("wishlist.pending") }
                Button(DulcetStrings.discoverRemoveWish, systemImage: "bookmark.slash") { model.remove(row.key) }
                    .disabled(model.publication?.access.canRemoveWish != true)
                    .accessibilityIdentifier("discover.wish")
            } else if DulcetDiscoverItemPresentation(item: item).canWish && model.publication?.access.canAddWish == true {
                Button(DulcetStrings.discoverAddWish, systemImage: "bookmark") { model.add(item, source: source) }
                    .accessibilityIdentifier("discover.wish")
            }
            ForEach(Array(DulcetDiscoverItemPresentation(item: item).libraryReferences.enumerated()), id: \.offset) { _, ref in
                let actionTitle = ref.type == "song" ? DulcetStrings.play : DulcetStrings.discoverOpenLibrary
                Button(actionTitle) { openLibrary(ref) }
                    .accessibilityIdentifier("discover.openLibrary")
                if model.publication?.access.canRejectMatch == true {
                    Button(DulcetStrings.discoverReject) { model.reject(item, ref: ref, wishId: row?.wish?.id) }.accessibilityIdentifier("discover.reject")
                }
            }
        }
    }
}

struct DulcetDiscoverArtworkView: View {
    let reader: any DulcetDiscoverReading
    let artworkId: String?
    var size: CGFloat = 48
    @State private var data: Data?
    @State private var operation: (any DulcetLibraryReaderCancellable)?
    var body: some View {
        Group {
#if os(iOS)
            if let data, let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFill() }
            else { placeholder }
#else
            if let data, let image = NSImage(data: data) { Image(nsImage: image).resizable().scaledToFill() }
            else { placeholder }
#endif
        }
        .frame(width: size, height: size).clipped().clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityHidden(true)
        .onAppear {
            guard data == nil, operation == nil, let artworkId else { return }
            operation = reader.artwork(artworkId: artworkId) { result in
                if case let .success(value) = result { data = value }
            }
        }
        .onDisappear { operation?.cancel(); operation = nil }
    }
    private var placeholder: some View {
        RoundedRectangle(cornerRadius: 8).fill(Color.dulcetControl).overlay { Image(systemName: "music.note").dulcetForeground(.secondaryTextOnControl) }
    }
}
#endif
