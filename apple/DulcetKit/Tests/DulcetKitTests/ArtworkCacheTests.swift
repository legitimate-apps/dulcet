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


@Test @MainActor
func decodedArtworkMemoryCacheReusesPixelsAcrossSizesAndStaysBounded() throws {
    let cache = DulcetArtworkMemoryCache(maximumByteCount: 1_000, maximumEntryCount: 2)
    let png = try artworkMemoryFixturePNG()
    let first = DulcetArtworkCacheKey(serverID: "art-A", artworkKey: "cover-A", sizeBucket: .pixels256)
    let second = DulcetArtworkCacheKey(serverID: "art-A", artworkKey: "cover-B", sizeBucket: .pixels96)
    let third = DulcetArtworkCacheKey(serverID: "art-B", artworkKey: "cover-C", sizeBucket: .pixels96)
    cache.insert(png, for: first)
    cache.insert(png, for: second)
    let image = try #require(cache.image(for: first))
    let fallback = try #require(cache.image(for: .init(serverID: first.serverID,
        artworkKey: first.artworkKey, sizeBucket: .pixels512)))
    #expect(image === fallback, "Another size reuses the decoded pixels for the first paint")
    cache.insert(png, for: third)
    #expect(cache.data(for: second) == nil)
    #expect(cache.data(for: first) == png)
    #expect(cache.entryCount == 2)
    #expect(cache.byteCount <= 1_000)
    #expect(cache.image(for: .init(serverID: "art-A", artworkKey: "cover-C", sizeBucket: .pixels512)) == nil)
    cache.remove(serverID: "art-A")
    #expect(cache.data(for: first) == nil)
    #expect(cache.data(for: third) == png)
    let tooSmall = DulcetArtworkMemoryCache(maximumByteCount: png.count, maximumEntryCount: 2)
    #expect(DulcetArtworkMemoryCache.decode(png, maximumByteCount: png.count) == nil)
    tooSmall.insert(png, for: first)
    #expect(tooSmall.entryCount == 0, "Encoded bytes plus decoded pixels must fit the byte bound")
}

@Test
func artworkDiskCacheInventoriesOnceAndTracksReplacementRemovalAndRelaunch() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let cache = DulcetArtworkDiskCache(rootDirectory: root, maximumByteCount: 50, maximumEntryCount: 10)
    let keys = (0..<20).map {
        DulcetArtworkCacheKey(serverID: "art-provider", artworkKey: "cover-\($0)", sizeBucket: .pixels96)
    }
    for key in keys { await cache.insert(Data([1, 2, 3]), for: key) }
    let last = try #require(keys.last)
    await cache.insert(Data(repeating: 7, count: 15), for: last)
    #expect(await cache.data(for: last) == Data(repeating: 7, count: 15))
    let stats = await cache.statistics()
    #expect(stats.entryCount <= 10 && stats.byteCount <= 50)
    #expect(await cache.directoryEnumerationCount == 1)
    let reopened = DulcetArtworkDiskCache(rootDirectory: root, maximumByteCount: 50, maximumEntryCount: 10)
    #expect(await reopened.statistics() == stats)
    #expect(await reopened.data(for: last) == Data(repeating: 7, count: 15))
    await reopened.remove(last)
    #expect(await reopened.statistics().entryCount == stats.entryCount - 1)
    await reopened.remove(serverID: "art-provider")
    #expect(await reopened.statistics() == .init(entryCount: 0, byteCount: 0))
    #expect(await reopened.directoryEnumerationCount == 1)
}


@Test
func cancelledArtworkWriteCannotRecreateADeletedServerCache() async {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let cache = DulcetArtworkDiskCache(rootDirectory: root)
    let key = DulcetArtworkCacheKey(serverID: "art-provider", artworkKey: "late-cover", sizeBucket: .pixels96)
    let gate = ArtworkCacheWriteGate()
    let write = Task {
        await gate.wait() // Intentionally resumes even after cancellation, like a late adapter.
        await cache.insert(Data([1, 2, 3]), for: key)
    }
    while !(await gate.isWaiting) { await Task.yield() }
    write.cancel()
    await cache.remove(serverID: key.serverID)
    await gate.release()
    await write.value
    #expect(await cache.data(for: key) == nil)
    #expect(await cache.statistics() == .init(entryCount: 0, byteCount: 0))
}

private actor ArtworkCacheWriteGate {
    private var continuation: CheckedContinuation<Void, Never>?
    var isWaiting: Bool { continuation != nil }
    func wait() async { await withCheckedContinuation { continuation = $0 } }
    func release() { continuation?.resume(); continuation = nil }
}
