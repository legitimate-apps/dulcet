#if os(iOS) || os(macOS)
import SwiftUI

public struct DulcetWishlistView: View {
    @State private var model: DulcetDiscoverModel
    @State private var filter = DulcetWishFilter()
    private let openLibrary: (DulcetDiscoverLibraryRef) -> Void
    private let openMetadata: (DulcetDiscoverDestination) -> Void
    public init(reader: any DulcetDiscoverReading, openLibrary: @escaping (DulcetDiscoverLibraryRef) -> Void, openMetadata: @escaping (DulcetDiscoverDestination) -> Void) {
        _model = State(initialValue: DulcetDiscoverModel(reader: reader)); self.openLibrary = openLibrary; self.openMetadata = openMetadata
    }
    public var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            ViewThatFits(in: .horizontal) {
                HStack { filters }
                VStack(alignment: .leading) { filters }
            }.padding(.horizontal, DulcetSpacing.md)
            HStack {
                Toggle(DulcetStrings.wishlistHistory, isOn: $filter.includeFulfilledHistory)
                Spacer()
                Button(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise", action: refresh)
                    .accessibilityIdentifier("wishlist.refresh")
            }.padding(.horizontal, DulcetSpacing.md)
            if let publication = model.publication {
                DulcetDiscoverStatusView(status: .init(age: nil, error: publication.access.error ?? publication.wishlistError, connection: publication.connection), retry: refresh)
                    .padding(.horizontal, DulcetSpacing.md)
                if let error = model.mutationError, error.code != "cancelled" { Text(DulcetStrings.discoverNotSaved).accessibilityIdentifier("wishlist.save-error") }
                if publication.wishlistLoading { ProgressView().accessibilityLabel(DulcetStrings.wishlistLoading) }
                if publication.wishlistSettled && publication.wishlistError == nil && publication.wishes.isEmpty {
                    Text(DulcetStrings.wishlistEmpty).padding(DulcetSpacing.md)
                }
                List {
                    ForEach(publication.wishes, id: \.key) { row in
                        wishRow(row)
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("wishlist.row.\(row.key)")
                            .contextMenu { remove(row) }
#if os(iOS)
                            .swipeActions { remove(row) }
#endif
                    }
                }.listStyle(.plain)
            } else { ProgressView().accessibilityLabel(DulcetStrings.wishlistLoading) }
        }
        .buttonStyle(.borderless)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.wishlist)
        .onAppear { model.open(); refresh() }
        .onDisappear { model.close() }
        .onChange(of: filter) { _, _ in refresh() }
    }
    private var filters: some View {
        Group {
            Picker(DulcetStrings.wishlistKind, selection: Binding(get: { filter.kind ?? "" }, set: { filter.kind = $0.isEmpty ? nil : $0 })) {
                Text(DulcetStrings.wishlistAll).tag("")
                Text(DulcetStrings.artists).tag("artist")
                Text(DulcetStrings.albums).tag("album")
                Text(DulcetStrings.myLibrarySongs).tag("song")
            }.accessibilityIdentifier("wishlist.kind")
            Picker(DulcetStrings.wishlistState, selection: Binding(get: { filter.state ?? "" }, set: { filter.state = $0.isEmpty ? nil : $0 })) {
                Text(DulcetStrings.wishlistAll).tag("")
                Text(DulcetStrings.wishlistWanted).tag("wanted")
                Text(DulcetStrings.wishlistRequested).tag("requested")
                Text(DulcetStrings.wishlistSearching).tag("searching")
                Text(DulcetStrings.wishlistUnavailable).tag("unavailable")
                Text(DulcetStrings.wishlistArrived).tag("fulfilled")
            }.accessibilityIdentifier("wishlist.state-filter")
        }
    }
    private func wishRow(_ row: DulcetWishRow) -> some View {
        let presentation = DulcetWishlistRowPresentation(row: row)
        return VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            HStack(alignment: .top, spacing: DulcetSpacing.sm) {
                DulcetDiscoverArtworkView(reader: model.reader, artworkId: presentation.snapshot?.artworkId)
                VStack(alignment: .leading) {
                    if let destination = presentation.destination, model.publication?.access.discoverAvailable == true {
                        Button { openMetadata(destination) } label: { Text(verbatim: presentation.snapshot?.title ?? "").font(.headline) }
                            .accessibilityIdentifier("wishlist.metadata")
                    } else { Text(verbatim: presentation.snapshot?.title ?? "").font(.headline) }
                    if let artist = presentation.snapshot?.artist { Text(verbatim: artist).font(.subheadline) }
                    Text(verbatim: presentation.stateTitle).accessibilityIdentifier("wishlist.state")
                }
                Spacer(minLength: 0)
            }
            if let note = presentation.note { Text(verbatim: note).fixedSize(horizontal: false, vertical: true) }
            if let noteAt = row.wish?.noteAt { Text(noteAt, format: .dateTime).font(.caption) }
            if let date = row.wish?.addedAt ?? row.pendingAdd?.actionTime { Text(date, format: .dateTime).font(.caption) }
            if let updated = row.wish?.updatedAt { Text(updated, format: .dateTime).font(.caption) }
            if let pending = presentation.pendingText { Text(pending).accessibilityIdentifier("wishlist.pending") }
            if let error = presentation.errorText { Text(error).accessibilityIdentifier("wishlist.error") }
            if let ref = presentation.arrival {
                Button(DulcetStrings.wishlistArrived, systemImage: "play.circle") { openLibrary(ref) }
                    .accessibilityIdentifier("wishlist.arrival")
            }
            if model.publication?.access.canRejectMatch == true, let wish = row.wish, let target = wish.target,
               let item = (model.reader as? any DulcetDiscoverLibraryMetadataProviding)?.item(for: target),
               let ref = wish.libraryItem ?? wish.availability.libraryItems.first {
                Button(DulcetStrings.discoverReject) { model.reject(item, ref: ref, wishId: wish.id) }
                    .accessibilityIdentifier("wishlist.reject")
            }
            remove(row)
        }.padding(.vertical, DulcetSpacing.xs)
    }
    private func remove(_ row: DulcetWishRow) -> some View {
        Button(DulcetStrings.discoverRemoveWish, systemImage: "bookmark.slash") { model.remove(row.key) }
            .disabled(model.publication?.access.canRemoveWish != true)
            .accessibilityIdentifier("wishlist.remove")
    }
    private func refresh() { model.reader.refreshWishlist(filter) }
}

struct DulcetWishlistDestinationView: View {
    @Environment(DulcetPresentationStore.self) private var store
    var body: some View {
        if let session = store.librarySession, let reader = session.discover, session.discoverPublication?.access.showsWishlistDestination == true {
            DulcetWishlistView(reader: reader, openLibrary: store.openDiscoverLibrary, openMetadata: { store.pushReaderPage(.discover($0)) })
                .id(session.readerGeneration)
        }
    }
}
struct DulcetDiscoverReaderDestinationView: View {
    @Environment(DulcetPresentationStore.self) private var store
    let destination: DulcetDiscoverDestination
    var body: some View {
        if let session = store.librarySession, let reader = session.discover {
            DulcetDiscoverDetailView(reader: reader, destination: destination, openLibrary: store.openDiscoverLibrary,
                                     openMetadata: { store.pushReaderPage(.discover($0)) })
                .id(session.readerGeneration)
        }
    }
}
#endif
