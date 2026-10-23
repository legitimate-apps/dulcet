import CoreGraphics
import Foundation
import ImageIO

public struct DulcetArtworkFetchRequest: Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let reference: DulcetArtworkReference
    public let sizeBucket: DulcetArtworkSizeBucket
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool
    /// Read only the device cache until account negotiation has succeeded.
    public let cachedOnly: Bool

    public init(
        reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool,
        cachedOnly: Bool = false
    ) {
        self.reference = reference
        self.sizeBucket = sizeBucket
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
        self.cachedOnly = cachedOnly
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
    func cachedArtwork(
        _ reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        allowingOtherSizes: Bool
    ) -> CGImage?

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

public extension DulcetArtworkLoading {
    func cachedArtwork(
        _ reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        allowingOtherSizes: Bool
    ) -> CGImage? { nil }
}

/// A device-session LRU shared by artwork views. Admission accounts for both encoded bytes and
/// decoded pixels; an exact entry supplies bytes without another disk read, and another bucket
/// can paint immediately while the requested bucket loads.
@MainActor
final class DulcetArtworkMemoryCache {
    private struct Entry {
        let data: Data
        let image: CGImage
        let cost: Int
        var access: UInt64
    }
    private var entries: [DulcetArtworkCacheKey: Entry] = [:]
    private var clock: UInt64 = 0
    private(set) var byteCount = 0
    var entryCount: Int { entries.count }
    private let maximumByteCount: Int
    private let maximumEntryCount: Int

    init(maximumByteCount: Int = 32 * 1024 * 1024, maximumEntryCount: Int = 128) {
        precondition(maximumByteCount > 0 && maximumEntryCount > 0)
        self.maximumByteCount = maximumByteCount
        self.maximumEntryCount = maximumEntryCount
    }

    static func decode(
        _ data: Data,
        maximumByteCount: Int = 32 * 1024 * 1024,
        maximumPixelSize: Int = 1024
    ) -> CGImage? {
        guard data.count < maximumByteCount, maximumPixelSize > 0,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let width = (properties[kCGImagePropertyPixelWidth as String] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight as String] as? NSNumber)?.intValue,
              width > 0, height > 0,
              // Reserve up to four 32-bit channels before asking ImageIO to allocate pixels.
              width <= (maximumByteCount - data.count) / 16 / height else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maximumPixelSize,
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary)
    }

    func insert(_ data: Data, for key: DulcetArtworkCacheKey) {
        guard let image = Self.decode(data, maximumByteCount: maximumByteCount,
            maximumPixelSize: key.sizeBucket.rawValue) else { return }
        let cost = data.count + image.bytesPerRow * image.height
        guard cost <= maximumByteCount else { return }
        remove(key)
        clock &+= 1
        entries[key] = Entry(data: data, image: image, cost: cost, access: clock)
        byteCount += cost
        while entries.count > maximumEntryCount || byteCount > maximumByteCount {
            guard let oldest = entries.min(by: { $0.value.access < $1.value.access })?.key else { break }
            remove(oldest)
        }
    }

    func data(for key: DulcetArtworkCacheKey) -> Data? {
        touch(key)?.data
    }

    func image(for key: DulcetArtworkCacheKey, allowingOtherSizes: Bool = true) -> CGImage? {
        if let exact = touch(key) { return exact.image }
        guard allowingOtherSizes,
              let fallback = entries.keys.filter({
                  $0.serverID == key.serverID && $0.artworkKey == key.artworkKey
              }).min(by: {
                  abs($0.sizeBucket.rawValue - key.sizeBucket.rawValue)
                    < abs($1.sizeBucket.rawValue - key.sizeBucket.rawValue)
              }) else { return nil }
        return touch(fallback)?.image
    }

    func remove(serverID: String) {
        for key in entries.keys.filter({ $0.serverID == serverID }) { remove(key) }
    }

    private func touch(_ key: DulcetArtworkCacheKey) -> Entry? {
        guard var entry = entries[key] else { return nil }
        clock &+= 1
        entry.access = clock
        entries[key] = entry
        return entry
    }

    private func remove(_ key: DulcetArtworkCacheKey) {
        if let old = entries.removeValue(forKey: key) { byteCount -= old.cost }
    }
}

/// Revokes remote requests without removing artwork already accepted into the local cache.
@MainActor
public protocol DulcetArtworkRemoteAccessControlling: AnyObject {
    func rejectServerIdentity(serverID: String)
    func acceptServerIdentity(serverID: String)
}
