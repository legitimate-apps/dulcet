#if os(iOS) || os(macOS)
import SwiftUI

public struct DulcetWishlistView: View {
    @State private var model: DulcetDiscoverModel
    @State private var filter = DulcetWishFilter()
#if DEBUG && os(iOS)
    @State private var controlRemovedOnce = false
#endif
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
            ViewThatFits(in: .horizontal) {
                HStack {
                    historyToggle
                    Spacer()
                    refreshButton
                }.fixedSize(horizontal: true, vertical: true)
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    historyToggle
                    refreshButton
                }
            }.padding(.horizontal, DulcetSpacing.md)
            if let publication = model.publication {
                DulcetDiscoverStatusView(status: .init(age: nil, error: publication.access.error ?? publication.wishlistError, connection: publication.connection), retry: refresh)
                    .padding(.horizontal, DulcetSpacing.md)
                if let error = model.mutationError, error.code != "cancelled" { Text(DulcetStrings.discoverNotSaved).accessibilityIdentifier("wishlist.save-error") }
                if publication.wishlistLoading { ProgressView().accessibilityLabel(DulcetStrings.wishlistLoading).accessibilityIdentifier("wishlist.loading").padding(.horizontal, DulcetSpacing.md) }
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
            } else { ProgressView().accessibilityLabel(DulcetStrings.wishlistLoading).accessibilityIdentifier("wishlist.loading").padding(.horizontal, DulcetSpacing.md) }
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
                        Button { openMetadata(destination) } label: {
                            Text(verbatim: presentation.snapshot?.title ?? "").font(.headline)
                                .multilineTextAlignment(.leading)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                            .accessibilityIdentifier("wishlist.metadata")
                    } else { Text(verbatim: presentation.snapshot?.title ?? "").font(.headline) }
                    if let artist = presentation.snapshot?.artist { Text(verbatim: artist).font(.subheadline) }
                    Text(verbatim: presentation.stateTitle).accessibilityIdentifier("wishlist.state")
                }
                Spacer(minLength: 0)
            }
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                if let note = presentation.note { Text(verbatim: note).fixedSize(horizontal: false, vertical: true) }
                if let noteAt = row.wish?.noteAt { timestamp(DulcetStrings.wishlistNoteUpdated, noteAt) }
                if let date = row.wish?.addedAt ?? row.pendingAdd?.actionTime { timestamp(DulcetStrings.wishlistAdded, date) }
                if let wish = row.wish, wish.updatedAt.formatted(.dateTime) != wish.addedAt.formatted(.dateTime) { timestamp(DulcetStrings.wishlistUpdated, wish.updatedAt) }
                if let pending = presentation.pendingText { Text(pending).accessibilityIdentifier("wishlist.pending") }
                if let error = presentation.errorText { Text(error).accessibilityIdentifier("wishlist.error") }
                if let ref = presentation.arrival {
                    Button(DulcetStrings.wishlistArrived, systemImage: "play.circle") { openLibrary(ref) }
                        .accessibilityIdentifier("wishlist.arrival")
                }
                if model.publication?.access.canRejectMatch == true, let wish = row.wish, wish.target != nil,
                   let item = wish.matchCorrectionItem,
                   let ref = wish.libraryItem ?? wish.availability.libraryItems.first {
                    Button(DulcetStrings.discoverReject) { model.reject(item, ref: ref, wishId: wish.id) }
                        .accessibilityIdentifier("wishlist.reject")
                }
                remove(row)
            }.padding(.leading, 48 + DulcetSpacing.sm)
        }.padding(.vertical, DulcetSpacing.xs)
    }
    private var historyToggle: some View {
        Toggle(DulcetStrings.wishlistHistory, isOn: $filter.includeFulfilledHistory)
            .fixedSize(horizontal: false, vertical: true)
    }
    private var refreshButton: some View {
        Button(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise", action: refresh)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("wishlist.refresh")
    }
    private func timestamp(_ label: String, _ date: Date) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                Text(label)
                Text(date, format: .dateTime)
            }.fixedSize(horizontal: true, vertical: true)
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(label)
                Text(date, format: .dateTime).fixedSize(horizontal: false, vertical: true)
            }
        }.font(.caption).accessibilityElement(children: .combine)
    }
    private func remove(_ row: DulcetWishRow) -> some View {
        Button(DulcetStrings.discoverRemoveWish, systemImage: "bookmark.slash") {
#if DEBUG && os(iOS)
            if ProcessInfo.processInfo.arguments.contains("-dulcet-control-remove-swallowed"), row.wish?.target != nil {
                DulcetUIProofMarkers.detail("wishlist.remove.control.swallowed", context: "key=\(row.key)")
                return
            }
#endif
            DulcetUIProofMarkers.detail("wishlist.remove", context: "key=\(row.key)")
#if DEBUG && os(iOS)
            if ProcessInfo.processInfo.arguments.contains("-dulcet-control-remove-delivered"), row.wish?.target != nil {
                if !controlRemovedOnce {
                    controlRemovedOnce = true
                    DulcetUIProofMarkers.detail("wishlist.remove.control.delivered-noop", context: "key=\(row.key)")
                    return
                }
                DulcetUIProofMarkers.detail("wishlist.remove.control.SECOND-WOULD-WORK", context: "key=\(row.key)")
            }
#endif
            model.remove(row.key)
        }
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
