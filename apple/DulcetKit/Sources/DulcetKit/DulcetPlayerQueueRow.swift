import SwiftUI
import CoreGraphics

/// A queue row has different space constraints from a library row: its transport stays visible.
/// At accessibility sizes metadata gets the whole column and controls get a separate line.
struct DulcetPlayerQueueRow<MenuActions: View, EditingActions: View>: View {
    let track: DulcetTrack
    let accessibilityLayout: Bool
    let isEditing: Bool
    let activationIdentifier: String
    let moreIdentifier: String
    let onActivate: () -> Void
    @ViewBuilder let menuActions: () -> MenuActions
    @ViewBuilder let editingActions: () -> EditingActions
    @State private var hovered = false
    @FocusState private var menuFocused: Bool

    var body: some View {
        Group {
            if accessibilityLayout {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    activation
                    if isEditing {
                        editingActions()
                    } else {
                        HStack {
                            DulcetPlayerQueueArtwork(artwork: track.artwork, size: 36)
                            Text(track.duration.dulcetDuration).font(.caption).dulcetForeground(.primaryTextOnWindow)
                            Spacer(minLength: 0)
                            more
                        }
                    }
                }
            } else {
                HStack(spacing: DulcetSpacing.sm) {
                    DulcetPlayerQueueArtwork(artwork: track.artwork, size: 48)
                    activation
                    if !isEditing {
                        Text(track.duration.dulcetDuration).font(.caption.monospacedDigit()).dulcetForeground(.primaryTextOnWindow)
                        more
                    }
                }
            }
        }
        .padding(.vertical, DulcetSpacing.xs)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
#if os(macOS)
        .onHover { hovered = $0 }
#endif
        .contextMenu { actions }
    }

    private var activation: some View {
        Button(action: onActivate) {
            VStack(alignment: .leading, spacing: 4) {
                Text(track.title)
                    .font(.headline)
                    .lineLimit(accessibilityLayout ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(DulcetStrings.artistNames(track.artistNames))
                    .font(.subheadline)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(accessibilityLayout ? nil : 1)
                if let album = track.albumTitle {
                    Text(album)
                        .font(.caption)
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(accessibilityLayout ? nil : 1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(activationIdentifier)
    }

    private var more: some View {
        Menu { actions } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
#if os(macOS)
                .opacity(hovered || menuFocused ? 1 : 0)
#endif
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .focused($menuFocused)
        .accessibilityLabel(DulcetStrings.readerRowAccessibility(DulcetStrings.more, track.title))
        .accessibilityIdentifier(moreIdentifier)
    }

    @ViewBuilder private var actions: some View {
        DulcetTrackMenuItems(track: track, onPlay: onActivate, queueAddition: nil,
            offersAlbum: true, publishedRating: nil, onDownload: nil, onNavigate: {})
        menuActions()
    }
}

/// Queue covers use the shared fetch/cache pipeline with a neutral loading and failure surface.
struct DulcetPlayerQueueArtwork: View {
    let artwork: DulcetArtwork
    let size: CGFloat
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.displayScale) private var displayScale
    @State private var decodedImage: CGImage?
    @State private var decodedReference: DulcetArtworkReference?
    @State private var operation: (any DulcetArtworkFetchOperation)?
    @State private var generation = 0
    @State private var retryTask: Task<Void, Never>?

    private var bucket: DulcetArtworkSizeBucket { .containing(pixelSize: size * displayScale) }
    private var image: CGImage? {
        guard let reference = artwork.remoteReference else { return nil }
        return (decodedReference == reference ? decodedImage : nil)
            ?? store.cachedArtwork(reference, sizeBucket: bucket)
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6).fill(.white.opacity(0.12))
            if let image {
                Image(decorative: image, scale: displayScale, orientation: .up)
                    .resizable().scaledToFill()
            } else {
                Image(systemName: "music.note")
                    .font(.system(size: size * 0.45))
                    .dulcetForeground(.primaryTextOnWindow)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .accessibilityHidden(true)
        .onAppear(perform: start)
        .onChange(of: artwork.remoteReference) { _, _ in start() }
        .onChange(of: bucket) { _, _ in start() }
        .onChange(of: store.snapshot.accountConnected) { _, _ in start() }
        .onDisappear(perform: cancel)
    }

    private func start() {
        cancel()
        decodedImage = nil
        decodedReference = artwork.remoteReference
        guard let reference = artwork.remoteReference else { return }
        if let cached = store.cachedArtwork(reference, sizeBucket: bucket, allowingOtherSizes: false) {
            decodedImage = cached
        } else {
            load(reference, attempt: 0)
        }
    }

    private func load(_ reference: DulcetArtworkReference, attempt: Int) {
        generation += 1
        let requestGeneration = generation
        operation = store.loadArtwork(reference, sizeBucket: bucket) { outcome in
            guard requestGeneration == generation else { return }
            operation = nil
            switch outcome {
            case let .loaded(data):
                decodedReference = reference
                decodedImage = store.cachedArtwork(reference, sizeBucket: bucket, allowingOtherSizes: false)
                    ?? DulcetArtworkMemoryCache.decode(data, maximumPixelSize: bucket.rawValue)
            case .failed where attempt < DulcetArtworkView.transientRetryDelays.count:
                retryTask = Task { @MainActor in
                    try? await Task.sleep(for: DulcetArtworkView.transientRetryDelays[attempt])
                    guard !Task.isCancelled else { return }
                    load(reference, attempt: attempt + 1)
                }
            case .failed, .unavailable, .cancelled:
                break
            }
        }
    }

    private func cancel() {
        generation += 1
        retryTask?.cancel()
        retryTask = nil
        operation?.cancel()
        operation = nil
    }
}
