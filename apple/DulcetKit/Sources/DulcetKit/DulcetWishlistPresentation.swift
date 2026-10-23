import Foundation

public extension DulcetDiscoverFeatureAccess {
    /// A failed redetection disables requests, while the advertised feature keeps its cached page.
    var showsWishlistDestination: Bool { wishlistAvailable }
}

public struct DulcetWishlistPresentation: Sendable {
    public let access: DulcetDiscoverFeatureAccess
    public init(access: DulcetDiscoverFeatureAccess) { self.access = access }
    public var showsDestination: Bool { access.showsWishlistDestination }
    public var canRead: Bool { access.canReadWishlist }
    public var canRemove: Bool { access.canRemoveWish }
    public var canAdd: Bool { access.canAddWish }
}

public struct DulcetWishlistRowPresentation: Sendable {
    public let row: DulcetWishRow
    public init(row: DulcetWishRow) { self.row = row }
    public var snapshot: DulcetWishSnapshot? { row.wish?.snapshot ?? row.pendingAdd?.snapshot }
    public var note: String? { row.wish?.note }
    public var pendingText: String? { row.pending ? DulcetStrings.discoverWaiting : nil }
    public var errorText: String? { row.error.map { $0.code == "cancelled" ? nil : DulcetStrings.discoverNotSaved } ?? nil }
    public var stateTitle: String {
        switch row.wish?.state ?? "wanted" {
        case "wanted": DulcetStrings.wishlistWanted
        case "requested": DulcetStrings.wishlistRequested
        case "searching": DulcetStrings.wishlistSearching
        case "unavailable": DulcetStrings.wishlistUnavailable
        case "fulfilled": DulcetStrings.wishlistArrived
        case let state: state
        }
    }
    public var arrival: DulcetDiscoverLibraryRef? {
        row.wish?.state == "fulfilled" ? row.wish?.libraryItem : nil
    }
    public var destination: DulcetDiscoverDestination? {
        guard let target = row.wish?.target ?? row.pendingAdd?.target else { return nil }
        switch target.kind {
        case "artist": return .artist(mbid: target.mbid)
        case "album": return .album(mbid: target.mbid, releaseId: nil)
        default: return nil
        }
    }
}
