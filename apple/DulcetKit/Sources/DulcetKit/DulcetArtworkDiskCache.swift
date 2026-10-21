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

    public struct AccountStatistics: Sendable, Equatable {
        public let evictableByteCount: Int
        public let protectedByteCount: Int
    }

    private struct Entry {
        let url: URL
        let byteCount: Int
        var lastAccess: Date
    }

    private let rootDirectory: URL
    private let maximumByteCount: Int
    private let maximumEntryCount: Int
    private let fileManager: FileManager
    private let now: @Sendable () -> Date
    private var indexedEntries: [String: Entry]?
    private var indexedByteCount = 0
    private var protectedPathsByServer: [String: Set<String>] = [:]
    private(set) var directoryEnumerationCount = 0

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
        self.rootDirectory = (rootDirectory
            ?? fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("DulcetArtwork", isDirectory: true))
            .standardizedFileURL.resolvingSymlinksInPath()
        self.maximumByteCount = maximumByteCount
        self.maximumEntryCount = maximumEntryCount
        self.now = now
    }

    public func data(for key: DulcetArtworkCacheKey) -> Data? {
        loadIndexIfNeeded()
        let url = fileURL(for: key)
        guard let data = try? Data(contentsOf: url) else {
            forget(url)
            return nil
        }
        let access = now()
        try? fileManager.setAttributes([.modificationDate: access], ofItemAtPath: url.path)
        remember(Entry(url: url, byteCount: data.count, lastAccess: access))
        return data
    }

    public func insert(_ data: Data, for key: DulcetArtworkCacheKey) {
        guard !Task.isCancelled, !data.isEmpty, data.count <= maximumByteCount else { return }
        loadIndexIfNeeded()
        let url = fileURL(for: key)
        do {
            try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: .atomic)
            let access = now()
            try fileManager.setAttributes([.modificationDate: access], ofItemAtPath: url.path)
            remember(Entry(url: url, byteCount: data.count, lastAccess: access))
            evictIfNeeded()
        } catch {
            try? fileManager.removeItem(at: url)
            forget(url)
        }
    }

    public func remove(serverID: String) {
        loadIndexIfNeeded()
        let directory = serverDirectory(for: serverID)
        protectedPathsByServer[serverID] = nil
        try? fileManager.removeItem(at: directory)
        for entry in (indexedEntries ?? [:]).values.filter({ $0.url.deletingLastPathComponent().path == directory.path }) {
            forget(entry.url)
        }
    }

    public func remove(_ key: DulcetArtworkCacheKey) {
        loadIndexIfNeeded()
        let url = fileURL(for: key)
        try? fileManager.removeItem(at: url)
        forget(url)
    }

    public func statistics() -> Statistics {
        loadIndexIfNeeded()
        return Statistics(entryCount: indexedEntries?.count ?? 0, byteCount: indexedByteCount)
    }

    /// The shared core supplies downloaded album cover identities. Only the required 256 px
    /// representation is pinned; larger grid images remain ordinary LRU entries.
    public func protectDownloadedArtwork(serverID: String, artworkKeys: [String]) {
        protectedPathsByServer[serverID] = Set(artworkKeys.map {
            fileURL(for: .init(serverID: serverID, artworkKey: $0, sizeBucket: .pixels256)).path
        })
        loadIndexIfNeeded()
    }

    public func statistics(serverID: String) -> AccountStatistics {
        loadIndexIfNeeded()
        let directory = serverDirectory(for: serverID).path
        let protected = protectedPathsByServer[serverID] ?? []
        var evictableBytes = 0
        var protectedBytes = 0
        for entry in (indexedEntries ?? [:]).values where entry.url.deletingLastPathComponent().path == directory {
            if protected.contains(entry.url.path) { protectedBytes += entry.byteCount }
            else { evictableBytes += entry.byteCount }
        }
        return AccountStatistics(evictableByteCount: evictableBytes, protectedByteCount: protectedBytes)
    }

    /// Executes the core's account target. The adapter never decides which media class gives
    /// up space, and cannot cross into another account or a downloaded album's pinned cover.
    public func trim(serverID: String, evictableByteTarget: Int64) {
        loadIndexIfNeeded()
        let directory = serverDirectory(for: serverID).path
        let protected = protectedPathsByServer[serverID] ?? []
        let candidates = (indexedEntries ?? [:]).values.filter {
            $0.url.deletingLastPathComponent().path == directory && !protected.contains($0.url.path)
        }.sorted {
            $0.lastAccess == $1.lastAccess ? $0.url.path < $1.url.path : $0.lastAccess < $1.lastAccess
        }
        var bytes = Int64(candidates.reduce(0) { $0 + $1.byteCount })
        for entry in candidates {
            guard bytes > max(0, evictableByteTarget) else { break }
            do {
                try fileManager.removeItem(at: entry.url)
                forget(entry.url)
                bytes -= Int64(entry.byteCount)
            } catch { continue }
        }
    }

    /// Each cache owner inventories disk once. Inserts and hits update the index in constant
    /// time; only crossing a bound sorts it, evicting a small batch to avoid sorting per cover.
    private func loadIndexIfNeeded() {
        guard indexedEntries == nil else { return }
        let entries = entriesByLastAccess()
        indexedEntries = Dictionary(uniqueKeysWithValues: entries.map { ($0.url.path, $0) })
        indexedByteCount = entries.reduce(0) { $0 + $1.byteCount }
        // Inventory is read-only: the shared core installs downloaded/current artwork pins
        // after reading usage. Trimming here could erase those covers during cold launch.
    }

    private func remember(_ entry: Entry) {
        forget(entry.url)
        indexedEntries?[entry.url.path] = entry
        indexedByteCount += entry.byteCount
    }

    private func forget(_ url: URL) {
        if let entry = indexedEntries?.removeValue(forKey: url.path) { indexedByteCount -= entry.byteCount }
    }

    private func evictIfNeeded() {
        guard (indexedEntries?.count ?? 0) > maximumEntryCount || indexedByteCount > maximumByteCount else { return }
        let entryTarget = maximumEntryCount - maximumEntryCount / 10
        let byteTarget = maximumByteCount - maximumByteCount / 10
        let oldest = (indexedEntries ?? [:]).values.sorted {
            if $0.lastAccess == $1.lastAccess { return $0.url.path < $1.url.path }
            return $0.lastAccess < $1.lastAccess
        }
        let protected = protectedPathsByServer.values.reduce(into: Set<String>()) { $0.formUnion($1) }
        for entry in oldest {
            if (indexedEntries?.count ?? 0) <= entryTarget && indexedByteCount <= byteTarget { break }
            if protected.contains(entry.url.path) { continue }
            do {
                try fileManager.removeItem(at: entry.url)
                forget(entry.url)
            } catch { break }
        }
    }

    private func entriesByLastAccess() -> [Entry] {
        directoryEnumerationCount += 1
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
                // Enumeration can spell a system symlink differently from URL construction.
                url: url.standardizedFileURL.resolvingSymlinksInPath(),
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
