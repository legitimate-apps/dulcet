import CryptoKit
import Foundation

public struct DulcetArtworkCacheKey: Sendable, Hashable {
    public let serverID: String
    public let artworkKey: String
    public let sizeBucket: DulcetArtworkSizeBucket

    public init(
        serverID: String,
        artworkKey: String,
        sizeBucket: DulcetArtworkSizeBucket
    ) {
        self.serverID = serverID
        self.artworkKey = artworkKey
        self.sizeBucket = sizeBucket
    }
}

/// A read-through artwork cache with an explicit global byte and entry bound.
///
/// Server and artwork identifiers are hashed only for filesystem-safe paths. They remain opaque
/// strings in the cache key and are never parsed, normalized, or reconstructed from item identity.
public actor DulcetArtworkDiskCache {
    public struct Statistics: Sendable, Equatable {
        public let entryCount: Int
        public let byteCount: Int
    }

    private struct Entry {
        let url: URL
        let byteCount: Int
        let lastAccess: Date
    }

    private let rootDirectory: URL
    private let maximumByteCount: Int
    private let maximumEntryCount: Int
    private let fileManager: FileManager
    private let now: @Sendable () -> Date

    public init(
        rootDirectory: URL? = nil,
        maximumByteCount: Int = 128 * 1024 * 1024,
        maximumEntryCount: Int = 2_048,
        fileManager: FileManager = .default,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        precondition(maximumByteCount > 0)
        precondition(maximumEntryCount > 0)
        self.fileManager = fileManager
        self.rootDirectory = rootDirectory
            ?? fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("DulcetArtwork", isDirectory: true)
        self.maximumByteCount = maximumByteCount
        self.maximumEntryCount = maximumEntryCount
        self.now = now
    }

    public func data(for key: DulcetArtworkCacheKey) -> Data? {
        let url = fileURL(for: key)
        guard let data = try? Data(contentsOf: url) else { return nil }
        try? fileManager.setAttributes(
            [.modificationDate: now()],
            ofItemAtPath: url.path
        )
        return data
    }

    public func insert(_ data: Data, for key: DulcetArtworkCacheKey) {
        guard !data.isEmpty else { return }
        let url = fileURL(for: key)
        do {
            try fileManager.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: url, options: .atomic)
            try fileManager.setAttributes(
                [.modificationDate: now()],
                ofItemAtPath: url.path
            )
            evictIfNeeded()
        } catch {
            try? fileManager.removeItem(at: url)
        }
    }

    public func remove(serverID: String) {
        try? fileManager.removeItem(at: serverDirectory(for: serverID))
    }

    public func remove(_ key: DulcetArtworkCacheKey) {
        try? fileManager.removeItem(at: fileURL(for: key))
    }

    public func statistics() -> Statistics {
        let entries = entriesByLastAccess()
        return Statistics(
            entryCount: entries.count,
            byteCount: entries.reduce(0) { $0 + $1.byteCount }
        )
    }

    private func evictIfNeeded() {
        var entries = entriesByLastAccess()
        var byteCount = entries.reduce(0) { $0 + $1.byteCount }
        while entries.count > maximumEntryCount || byteCount > maximumByteCount {
            let entry = entries.removeFirst()
            do {
                try fileManager.removeItem(at: entry.url)
                byteCount -= entry.byteCount
            } catch {
                break
            }
        }
    }

    private func entriesByLastAccess() -> [Entry] {
        guard let enumerator = fileManager.enumerator(
            at: rootDirectory,
            includingPropertiesForKeys: [
                .isRegularFileKey,
                .contentModificationDateKey,
                .fileSizeKey,
            ],
            options: [.skipsHiddenFiles]
        ) else { return [] }
        return enumerator.compactMap { element -> Entry? in
            guard let url = element as? URL,
                  let values = try? url.resourceValues(forKeys: [
                      .isRegularFileKey,
                      .contentModificationDateKey,
                      .fileSizeKey,
                  ]),
                  values.isRegularFile == true else { return nil }
            return Entry(
                url: url,
                byteCount: values.fileSize ?? 0,
                lastAccess: values.contentModificationDate ?? .distantPast
            )
        }.sorted { lhs, rhs in
            if lhs.lastAccess == rhs.lastAccess {
                return lhs.url.path < rhs.url.path
            }
            return lhs.lastAccess < rhs.lastAccess
        }
    }

    private func fileURL(for key: DulcetArtworkCacheKey) -> URL {
        serverDirectory(for: key.serverID).appendingPathComponent(
            "\(Self.digest(key.artworkKey + "\0" + String(key.sizeBucket.rawValue))).image",
            isDirectory: false
        )
    }

    private func serverDirectory(for serverID: String) -> URL {
        rootDirectory.appendingPathComponent(Self.digest(serverID), isDirectory: true)
    }

    private static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
