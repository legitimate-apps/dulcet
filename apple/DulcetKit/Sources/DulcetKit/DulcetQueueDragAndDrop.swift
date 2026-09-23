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

/// The additions behind the drags this process has started, newest last. Bounded: a drag that
/// was abandoned leaves its entry behind, and only the most recent few can still be dropped.
@MainActor
enum DulcetQueueDragRegistry {
    static let capacity = 16
    private static var entries: [(ticket: UUID, addition: DulcetQueueAddition)] = []

    /// A nil addition still yields a ticket, one that resolves to nothing when dropped.
    static func register(_ addition: DulcetQueueAddition?) -> DulcetQueueDragItem {
        let ticket = UUID()
        guard let addition else { return DulcetQueueDragItem(ticket: ticket) }
        entries.append((ticket, addition))
        if entries.count > capacity { entries.removeFirst(entries.count - capacity) }
        return DulcetQueueDragItem(ticket: ticket)
    }

    static func addition(for item: DulcetQueueDragItem) -> DulcetQueueAddition? {
        entries.last { $0.ticket == item.ticket }?.addition
    }

    /// Adds every dropped item the process can still resolve to the end of the queue, in the
    /// order dropped. Returns whether anything was added, which is what the drop reports back.
    @discardableResult
    static func dropOntoQueue(
        _ items: [DulcetQueueDragItem],
        store: DulcetPresentationStore
    ) -> Bool {
        guard store.queueEditingEnabled else { return false }
        let additions = items.compactMap(addition(for:)).filter { !$0.tracks.isEmpty }
        for addition in additions {
            store.editQueue(.playLater(addition))
        }
        return !additions.isEmpty
    }

    static func removeAll() { entries.removeAll() }
}

extension View {
    /// Lets a track or album be dragged onto the queue, while the queue can be edited. The
    /// addition is built only when a drag begins.
    @ViewBuilder
    func dulcetQueueDragSource(
        store: DulcetPresentationStore,
        artwork: DulcetArtwork,
        title: String,
        isEnabled: Bool = true,
        addition: @escaping () -> DulcetQueueAddition?
    ) -> some View {
#if os(iOS)
        if isEnabled, store.queueEditingEnabled {
            draggable(DulcetQueueDragRegistry.register(addition())) {
                DulcetQueueDragPreview(store: store, artwork: artwork, title: title)
            }
        } else {
            self
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
                } isTargeted: { isTargeted = $0 }
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

    var body: some View {
        HStack(spacing: DulcetSpacing.sm) {
            DulcetArtworkView(artwork: artwork, size: 44)
            Text(title)
                .font(.callout.weight(.semibold))
                .dulcetForeground(.primaryTextOnRegularMaterial)
                .lineLimit(1)
        }
        .padding(DulcetSpacing.xs)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .environment(store)
    }
}
#endif
#endif
