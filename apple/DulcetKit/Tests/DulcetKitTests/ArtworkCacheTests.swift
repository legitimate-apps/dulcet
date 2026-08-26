import Foundation
import Testing
@testable import DulcetKit

@Test
func credentialBearingArtworkRequestCannotPrintCredentials() {
    let canaries = [
        "https://listener:secret@music.example.invalid",
        "artwork-print-username",
        "artwork-print-password",
    ]
    let request = DulcetArtworkFetchRequest(
        reference: DulcetArtworkReference(
            serverID: "provider-instance-fixture",
            artworkKey: "cover:opaque"
        ),
        sizeBucket: .pixels256,
        normalizedServerURL: canaries[0],
        username: canaries[1],
        password: canaries[2],
        allowLocalHTTP: false
    )
    var dumpValue = ""
    dump(request, to: &dumpValue)
    let rendered = [
        String(describing: request),
        String(reflecting: request),
        dumpValue,
    ]

    for canary in canaries {
        #expect(rendered.allSatisfy { !$0.contains(canary) })
    }
    #expect(rendered.allSatisfy { $0.contains("<redacted>") })
}

@Test
func artworkDiskCacheUsesTheExactTripleAndEvictsLeastRecentlyUsedEntries() async throws {
    let root = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString, isDirectory: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let dates = TestDateSource()
    let cache = DulcetArtworkDiskCache(
        rootDirectory: root,
        maximumByteCount: 100,
        maximumEntryCount: 2,
        now: dates.next
    )
    let first = DulcetArtworkCacheKey(
        serverID: "server:opaque-A",
        artworkKey: "artwork:opaque",
        sizeBucket: .pixels96
    )
    let secondBucket = DulcetArtworkCacheKey(
        serverID: "server:opaque-A",
        artworkKey: "artwork:opaque",
        sizeBucket: .pixels256
    )
    let otherServer = DulcetArtworkCacheKey(
        serverID: "server:opaque-B",
        artworkKey: "artwork:opaque",
        sizeBucket: .pixels96
    )

    await cache.insert(Data([1]), for: first)
    await cache.insert(Data([2]), for: secondBucket)
    #expect(await cache.data(for: first) == Data([1]))
    await cache.insert(Data([3]), for: otherServer)

    #expect(await cache.data(for: first) == Data([1]))
    #expect(await cache.data(for: secondBucket) == nil)
    #expect(await cache.data(for: otherServer) == Data([3]))
    #expect(await cache.statistics() == .init(entryCount: 2, byteCount: 2))

    await cache.remove(serverID: "server:opaque-A")
    #expect(await cache.data(for: first) == nil)
    #expect(await cache.data(for: otherServer) == Data([3]))
}

@Test
func artworkDiskCacheEnforcesItsByteBound() async {
    let root = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString, isDirectory: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let dates = TestDateSource()
    let cache = DulcetArtworkDiskCache(
        rootDirectory: root,
        maximumByteCount: 5,
        maximumEntryCount: 10,
        now: dates.next
    )
    let keys = ["first", "second"].map {
        DulcetArtworkCacheKey(
            serverID: "server:opaque",
            artworkKey: $0,
            sizeBucket: .pixels512
        )
    }

    await cache.insert(Data(repeating: 1, count: 4), for: keys[0])
    await cache.insert(Data(repeating: 2, count: 4), for: keys[1])

    #expect(await cache.data(for: keys[0]) == nil)
    #expect(await cache.data(for: keys[1]) == Data(repeating: 2, count: 4))
    #expect(await cache.statistics() == .init(entryCount: 1, byteCount: 4))
}

private final class TestDateSource: @unchecked Sendable {
    private let lock = NSLock()
    private var tick: TimeInterval = 0

    func next() -> Date {
        lock.lock()
        defer { lock.unlock() }
        tick += 1
        return Date(timeIntervalSince1970: tick)
    }
}
