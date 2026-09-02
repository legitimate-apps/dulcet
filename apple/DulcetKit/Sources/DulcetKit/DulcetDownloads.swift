import Foundation

public enum DulcetDownloadState: String, Sendable, Hashable {
    case notDownloaded
    case queued
    case downloading
    case interrupted
    case downloaded
    case stale
    case failed

    public var isLocallyPlayable: Bool {
        self == .downloaded || self == .stale
    }
}

public struct DulcetOfflinePlaybackAsset: Sendable {
    public let expectedContainer: DulcetAudioContainer
    public let exactByteLength: Int64
    public let resource: any DulcetPlaybackResourceLoading

    public init(
        expectedContainer: DulcetAudioContainer,
        exactByteLength: Int64,
        resource: any DulcetPlaybackResourceLoading
    ) {
        precondition(exactByteLength >= 0)
        self.expectedContainer = expectedContainer
        self.exactByteLength = exactByteLength
        self.resource = resource
    }
}

@MainActor
public protocol DulcetDownloadControlling: AnyObject {
    var downloadsEnabled: Bool { get }
    func setStatusHandler(
        _ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void
    )
    func configure(account: DulcetPlaybackAccount)
    func requestDownload(_ track: DulcetTrack)
    func status(for id: DulcetProviderItemID) -> DulcetDownloadState
    func offlinePlaybackAsset(for track: DulcetTrack) -> DulcetOfflinePlaybackAsset?
    func disconnect()
}

/// A range-readable local file. Its description deliberately omits the container path.
public final class DulcetLocalFilePlaybackResource: DulcetPlaybackResourceLoading,
    @unchecked Sendable {
    private let fileURL: URL
    private let exactByteLength: Int64
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.local-playback")

    public init(fileURL: URL, exactByteLength: Int64) {
        precondition(fileURL.isFileURL)
        precondition(exactByteLength >= 0)
        self.fileURL = fileURL
        self.exactByteLength = exactByteLength
    }

    public var description: String { "DulcetLocalFilePlaybackResource(<local-file>)" }

    public func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        let operation = DulcetLocalFileLoadOperation()
        queue.async { [fileURL, exactByteLength] in
            guard !operation.isCancelled else {
                completion(.cancelled)
                return
            }
            guard request.range.start < exactByteLength else {
                completion(.failed(error: .sourceUnavailable, refreshReason: nil))
                return
            }
            let readableEnd = min(request.range.endInclusive, exactByteLength - 1)
            let readableLength = readableEnd - request.range.start + 1
            do {
                let handle = try FileHandle(forReadingFrom: fileURL)
                defer { try? handle.close() }
                try handle.seek(toOffset: UInt64(request.range.start))
                let data = try handle.read(upToCount: Int(readableLength)) ?? Data()
                guard !operation.isCancelled else {
                    completion(.cancelled)
                    return
                }
                guard data.count == Int(readableLength) else {
                    completion(.failed(error: .sourceUnavailable, refreshReason: nil))
                    return
                }
                completion(.loaded(
                    data: data,
                    contentInformation: DulcetPlaybackContentInformation(
                        contentLength: exactByteLength,
                        supportsByteRanges: true
                    )
                ))
            } catch {
                completion(.failed(error: .sourceUnavailable, refreshReason: nil))
            }
        }
        return operation
    }
}

private final class DulcetLocalFileLoadOperation: DulcetPlaybackResourceLoadOperation,
    @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
    }
}
