import Foundation

public struct DulcetArtworkFetchRequest: Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let reference: DulcetArtworkReference
    public let sizeBucket: DulcetArtworkSizeBucket
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool

    public init(
        reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool
    ) {
        self.reference = reference
        self.sizeBucket = sizeBucket
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
    }

    public var description: String { "DulcetArtworkFetchRequest(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("artworkFetchRequest", "<redacted>" as Any)], displayStyle: .struct)
    }
}

public enum DulcetArtworkFetchOutcome: Sendable {
    case loaded(Data)
    case unavailable
    case failed
    case cancelled
}

@MainActor
public protocol DulcetArtworkFetchOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetArtworkFetching: AnyObject {
    func fetch(
        _ request: DulcetArtworkFetchRequest,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> any DulcetArtworkFetchOperation
}

@MainActor
public protocol DulcetArtworkLoading: AnyObject {
    @discardableResult
    func loadArtwork(
        _ reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> (any DulcetArtworkFetchOperation)?
}

@MainActor
public protocol DulcetArtworkCacheRemoving: AnyObject {
    func removeCachedArtwork(serverID: String) async
}
