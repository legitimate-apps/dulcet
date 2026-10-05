#if os(macOS) || os(iOS) || os(tvOS)
import CoreTransferable
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// A track or album being dragged onto the queue. Declared by the iOS app's Info.plist.
    static let dulcetQueueItem = UTType(exportedAs: "com.legitimateapps.dulcet.queue-item")
}

/// What a drag carries: a ticket, not the tracks. The tracks stay in this process, held by
/// ``DulcetQueueDragRegistry`` under the ticket, so nothing about the library is written to the
/// shared drag pasteboard and a drop from anywhere else resolves to nothing.
struct DulcetQueueDragItem: Codable, Transferable, Hashable {
    let ticket: UUID

    static var transferRepresentation: some TransferRepresentation {
        CodableRepresentation(contentType: .dulcetQueueItem)
    }
}

/// Reads the tracks behind a dragged item when it is dropped, for an item whose tracks have not
/// been read yet: a reader album or playlist tile. Completes with nil when there is nothing to
/// add, having said so itself. Never called for a drag that is abandoned.
typealias DulcetDeferredQueueAddition =
    @MainActor (_ completion: @escaping @MainActor (DulcetQueueAddition?) -> Void) -> Void

/// What a ticket resolves to: tracks already in hand, or a read to make when it is dropped.
enum DulcetQueueDragPayload {
    case resolved(DulcetQueueAddition)
    case deferred(DulcetDeferredQueueAddition)
}

/// The additions behind the drags this process has started, newest last. Bounded: a drag that
/// was abandoned leaves its entry behind, and only the most recent few can still be dropped.
@MainActor
enum DulcetQueueDragRegistry {
    static let capacity = 16
    private static var entries: [(ticket: UUID, payload: DulcetQueueDragPayload)] = []
    /// Whether the drag this process started most recently carries anything. A drop target
    /// outlines itself only for one that does, so a drag that can add nothing is never shown as
    /// something the queue would take.
    private(set) static var activeDragCarriesAddition = false

    /// A nil addition still yields a ticket, one that resolves to nothing when dropped.
    static func register(_ addition: DulcetQueueAddition?) -> DulcetQueueDragItem {
        let ticket = UUID()
        activeDragCarriesAddition = addition.map { !$0.tracks.isEmpty } ?? false
        guard let addition else { return DulcetQueueDragItem(ticket: ticket) }
        append(ticket, .resolved(addition))
        return DulcetQueueDragItem(ticket: ticket)
    }

    /// A drag whose tracks are read when it is dropped. A nil `resolve` is a drag that cannot be
    /// added, as a nil addition is.
    static func register(deferred resolve: DulcetDeferredQueueAddition?) -> DulcetQueueDragItem {
        let ticket = UUID()
        activeDragCarriesAddition = resolve != nil
        guard let resolve else { return DulcetQueueDragItem(ticket: ticket) }
        append(ticket, .deferred(resolve))
        return DulcetQueueDragItem(ticket: ticket)
    }

    private static func append(_ ticket: UUID, _ payload: DulcetQueueDragPayload) {
        entries.append((ticket, payload))
        if entries.count > capacity { entries.removeFirst(entries.count - capacity) }
    }

    static func payload(for item: DulcetQueueDragItem) -> DulcetQueueDragPayload? {
        entries.last { $0.ticket == item.ticket }?.payload
    }

    static func addition(for item: DulcetQueueDragItem) -> DulcetQueueAddition? {
        guard case let .resolved(addition) = payload(for: item) else { return nil }
        return addition
    }

    /// Adds every dropped item the process can still resolve to the end of the queue, in the
    /// order dropped. Returns whether anything was added, which is what the drop reports back.
    /// A drop that adds nothing -- a disabled item, or a ticket from somewhere else -- is refused
    /// out loud, as a refused queue edit is: silence would read as a drop that worked.
    @discardableResult
    static func dropOntoQueue(
        _ items: [DulcetQueueDragItem],
        store: DulcetPresentationStore
    ) -> Bool {
        guard store.queueEditingEnabled else { return false }
        let payloads = items.compactMap(payload(for:)).filter {
            if case let .resolved(addition) = $0 { return !addition.tracks.isEmpty }
            return true
        }
        guard !payloads.isEmpty else {
            store.reportRefusedQueueEdit()
            return false
        }
        for payload in payloads {
            switch payload {
            case let .resolved(addition):
                store.editQueue(.playLater(addition))
            case let .deferred(resolve):
                // The drop is accepted now and the tracks arrive when the read settles, as the
                // menu's Add to Queue does; a read with nothing to add has said so already.
                resolve { addition in
                    guard let addition else { return }
                    store.editQueue(.playLater(addition))
                }
            }
        }
        return true
    }

    static func removeAll() {
        entries.removeAll()
        activeDragCarriesAddition = false
    }
}

extension View {
    /// Lets a track or album be dragged onto the queue. The addition is built only when a drag
    /// begins, and a drag while the queue cannot take it carries nothing a drop can resolve.
    ///
    /// The drag interaction is attached unconditionally, so the view it wraps keeps one identity
    /// for its whole life: attached through an `if`, an album tile becomes a different view once
    /// its track list arrives -- every tile in the grid at once, when a library read commits.
    /// ASSUMED, not observed: that a tap in flight across that replacement is lost. It was never
    /// reproduced; the simulator tile test passed with the `if` as well. The cost of attaching it
    /// always is that a disabled item lifts too, so its preview says it cannot be added, no drop
    /// target outlines itself for it, and a drop of it is refused out loud.
    func dulcetQueueDragSource(
        store: DulcetPresentationStore,
        artwork: DulcetArtwork,
        title: String,
        isEnabled: Bool = true,
        addition: @escaping () -> DulcetQueueAddition?
    ) -> some View {
#if os(iOS)
        draggable(DulcetQueueDragRegistry.register(
            isEnabled && store.queueEditingEnabled ? addition() : nil
        )) {
            DulcetQueueDragPreview(
                store: store,
                artwork: artwork,
                title: title,
                refused: !(isEnabled && store.queueEditingEnabled)
            )
        }
#else
        self
#endif
    }

    /// Lets an album or a playlist whose tracks have not been read be dragged onto the queue: the
    /// read is made when it is dropped, not when it lifts, so a tile costs nothing until used.
    /// The refusal and the identity rules are `dulcetQueueDragSource`'s.
    func dulcetDeferredQueueDragSource(
        store: DulcetPresentationStore,
        artwork: DulcetArtwork,
        title: String,
        isEnabled: Bool = true,
        resolve: @escaping DulcetDeferredQueueAddition
    ) -> some View {
#if os(iOS)
        draggable(DulcetQueueDragRegistry.register(
            deferred: isEnabled && store.queueEditingEnabled ? resolve : nil
        )) {
            DulcetQueueDragPreview(
                store: store,
                artwork: artwork,
                title: title,
                refused: !(isEnabled && store.queueEditingEnabled)
            )
        }
#else
        self
#endif
    }

    /// Accepts tracks and albums dropped here onto the end of the queue, outlining itself while
    /// a drop is over it. Inert while the queue cannot be edited.
    func dulcetQueueDropTarget(store: DulcetPresentationStore, cornerRadius: CGFloat = 16) -> some View {
        modifier(DulcetQueueDropTarget(store: store, cornerRadius: cornerRadius))
    }
}

private struct DulcetQueueDropTarget: ViewModifier {
    let store: DulcetPresentationStore
    let cornerRadius: CGFloat
    @State private var isTargeted = false

    func body(content: Content) -> some View {
#if os(iOS)
        if store.queueEditingEnabled {
            content
                .dropDestination(for: DulcetQueueDragItem.self) { items, _ in
                    DulcetQueueDragRegistry.dropOntoQueue(items, store: store)
                } isTargeted: { isTargeted = $0 && DulcetQueueDragRegistry.activeDragCarriesAddition }
                .overlay {
                    if isTargeted {
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .stroke(Color.dulcetAccent, lineWidth: 3)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
        } else {
            content
        }
#else
        content
#endif
    }
}

#if os(iOS)
/// The card under the finger while a track or album is dragged.
private struct DulcetQueueDragPreview: View {
    let store: DulcetPresentationStore
    let artwork: DulcetArtwork
    let title: String
    /// The item cannot be added -- offline, its tracks not read yet, or the queue not editable --
    /// and the card says so before it is dropped anywhere.
    let refused: Bool

    var body: some View {
        HStack(spacing: DulcetSpacing.sm) {
            DulcetArtworkView(artwork: artwork, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.callout.weight(.semibold))
                    .dulcetForeground(.primaryTextOnRegularMaterial)
                    .lineLimit(1)
                if refused {
                    Label(DulcetStrings.queueDragRefused, systemImage: "nosign")
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnRegularMaterial)
                        .lineLimit(1)
                }
            }
        }
        .padding(DulcetSpacing.xs)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .environment(store)
    }
}
#endif
#endif
