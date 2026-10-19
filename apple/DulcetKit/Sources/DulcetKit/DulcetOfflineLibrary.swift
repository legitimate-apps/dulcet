import Foundation
import SwiftUI

public struct DulcetOfflineLibraryStorage: Sendable, Equatable {
    public var automaticDownloadsEnabled: Bool
    public var cacheBudgetBytes: Int64
    public var downloadBytes: Int64
    public var cacheBytes: Int64
    public var artworkBytes: Int64
    public var queuedAutomaticCount: Int
    public var completedAutomaticCount: Int

    public init(automaticDownloadsEnabled: Bool = false, cacheBudgetBytes: Int64 = 512 * 1_024 * 1_024,
                downloadBytes: Int64 = 0, cacheBytes: Int64 = 0, artworkBytes: Int64 = 0,
                queuedAutomaticCount: Int = 0, completedAutomaticCount: Int = 0) {
        self.automaticDownloadsEnabled = automaticDownloadsEnabled
        self.cacheBudgetBytes = cacheBudgetBytes
        self.downloadBytes = downloadBytes
        self.cacheBytes = cacheBytes
        self.artworkBytes = artworkBytes
        self.queuedAutomaticCount = queuedAutomaticCount
        self.completedAutomaticCount = completedAutomaticCount
    }
}

@MainActor
public protocol DulcetOfflineLibraryControlling: AnyObject {
    var offlineLibraryStorage: DulcetOfflineLibraryStorage { get }
    func setAutomaticDownloadsEnabled(_ enabled: Bool)
    func setStreamingCacheBudgetBytes(_ bytes: Int64)
    func clearStreamingCache()
}

/// Observes the already validated engine response. It neither creates a request nor changes the
/// result, range, completion timing, or cancellation delivered to the player.
public final class DulcetCachingPlaybackResource: DulcetPlaybackResourceLoading, @unchecked Sendable {
    private let source: any DulcetPlaybackResourceLoading
    private let observe: @Sendable (DulcetPlaybackResourceLoadRequest, Data, DulcetPlaybackContentInformation) -> Void
    private let release: @Sendable () -> Void

    public init(source: any DulcetPlaybackResourceLoading,
                observe: @escaping @Sendable (DulcetPlaybackResourceLoadRequest, Data, DulcetPlaybackContentInformation) -> Void,
                release: @escaping @Sendable () -> Void = {}) {
        self.source = source
        self.observe = observe
        self.release = release
    }
    deinit { release() }
    public var description: String { "DulcetCachingPlaybackResource(<redacted>)" }
    public func load(_ request: DulcetPlaybackResourceLoadRequest,
                     completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void) -> any DulcetPlaybackResourceLoadOperation {
        source.load(request) { [observe] outcome in
            if case let .loaded(data, information) = outcome { observe(request, data, information) }
            completion(outcome)
        }
    }
}

struct DulcetOfflineLibrarySection: View {
    @Bindable var store: DulcetPresentationStore
    var inForm = false
    private var storage: DulcetOfflineLibraryStorage { store.offlineLibraryStorage ?? .init() }
    var body: some View {
        if store.offlineLibraryStorage != nil {
            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                Text("Offline Music").font(.headline)
                Toggle("Automatic Downloads", isOn: Binding(
                    get: { storage.automaticDownloadsEnabled },
                    set: { store.setAutomaticDownloadsEnabled($0) }
                )).accessibilityIdentifier("dulcet.offline.automaticDownloads")
                Text("Keep the music you add to My Library on this device. Explicit downloads are always kept.")
                    .font(.caption).foregroundStyle(.secondary)
                if storage.automaticDownloadsEnabled {
                    Text("\(storage.completedAutomaticCount) downloaded · \(storage.queuedAutomaticCount) remaining")
                        .font(.caption).accessibilityIdentifier("dulcet.offline.progress")
                }
                LabeledContent("Downloads", value: bytes(storage.downloadBytes))
                LabeledContent("Streaming Cache", value: bytes(storage.cacheBytes))
                LabeledContent("Artwork Cache", value: bytes(storage.artworkBytes))
                Picker("Cache Size", selection: Binding(get: { storage.cacheBudgetBytes },
                                                        set: { store.setStreamingCacheBudgetBytes($0) })) {
                    Text("Off").tag(Int64(0))
                    Text("256 MB").tag(Int64(256 * 1_024 * 1_024))
                    Text("512 MB").tag(Int64(512 * 1_024 * 1_024))
                    Text("1 GB").tag(Int64(1_024 * 1_024 * 1_024))
                    Text("2 GB").tag(Int64(2 * 1_024 * 1_024 * 1_024))
                }.accessibilityIdentifier("dulcet.offline.cacheSize")
                Text("Recently streamed songs stay available offline until the cache needs space. Downloads stay protected.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Clear Streaming Cache") { store.clearStreamingCache() }
                    .accessibilityIdentifier("dulcet.offline.clearCache")
            }
            .accessibilityElement(children: .contain)
        }
    }
    private func bytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }
}
