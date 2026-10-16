import Foundation

public enum DulcetMyLibraryAvailability: String, Sendable { case unknown, available, unavailable }
public enum DulcetMyLibrarySection: String, Sendable, CaseIterable, Identifiable {
    case recent, artists, albums, songs, playlists
    public var id: String { rawValue }
}
public enum DulcetMyLibrarySort: String, Sendable { case recentlyAdded, alphabetical }
public enum DulcetMyLibraryMembership: String, Sendable { case unknown, added, partial, notAdded }

/// Copied account publication; the core owns ordering, derived membership and pending overlays.
public struct DulcetMyLibrarySnapshot: Sendable {
    public var availability: DulcetMyLibraryAvailability
    public var syncState: String
    public var error: DulcetReaderErrorKind?
    public var lastReadAt: Date?
    public var albums: [DulcetReaderItem]
    public var artists: [DulcetReaderItem]
    public var songs: [DulcetReaderItem]

    public init(availability: DulcetMyLibraryAvailability = .unknown, syncState: String = "never",
                error: DulcetReaderErrorKind? = nil, lastReadAt: Date? = nil,
                albums: [DulcetReaderItem] = [], artists: [DulcetReaderItem] = [], songs: [DulcetReaderItem] = []) {
        self.availability = availability; self.syncState = syncState
        self.error = error; self.lastReadAt = lastReadAt
        self.albums = albums; self.artists = artists; self.songs = songs
    }
}

@MainActor
public protocol DulcetMyLibraryReading: AnyObject {
    func subscribeMyLibrary(_ handler: @escaping @MainActor (DulcetMyLibrarySnapshot) -> Void) -> any DulcetLibraryReaderCancellable
    func setMyLibrarySort(_ sort: DulcetMyLibrarySort)
    func refreshMyLibrary()
    func myLibraryMembership(_ target: DulcetFavouriteTarget) -> DulcetMyLibraryMembership
    func setMyLibraryMembership(_ target: DulcetFavouriteTarget, added: Bool) -> Bool
}

public extension DulcetMyLibraryReading {
    func setMyLibrarySort(_ sort: DulcetMyLibrarySort) {}
}

public extension DulcetLibrarySession {
    func myLibraryMembership(_ target: DulcetFavouriteTarget) -> DulcetMyLibraryMembership {
        guard account?.providerInstanceID == target.id.providerInstanceID else { return .unknown }
        return (reader as? any DulcetMyLibraryReading)?.myLibraryMembership(target) ?? .unknown
    }

    @discardableResult
    func toggleMyLibrary(_ target: DulcetFavouriteTarget) -> Bool {
        setMyLibraryMembership(target, added: myLibraryMembership(target) != .added)
    }

    @discardableResult
    func setMyLibraryMembership(_ target: DulcetFavouriteTarget, added: Bool) -> Bool {
        guard myLibrary.availability == .available,
              account?.providerInstanceID == target.id.providerInstanceID,
              let library = reader as? any DulcetMyLibraryReading else { return false }
        let accepted = library.setMyLibraryMembership(target, added: added)
        if !accepted { post(DulcetStrings.readerChangeNotRecorded) }
        return accepted
    }
}
