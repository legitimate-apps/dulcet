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

/// Artwork executors apply this shared-policy plan before the media cache is mutated.
public enum DulcetArtworkCacheIntent: Sendable, Equatable {
    case reconcile(reservationBytes: Int64, budgetBytesOverride: Int64?)
    case clear
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
            if inForm {
                // A Form gives each control its own row. Wrapping them in one VStack lets
                // automatic picker/button hit areas intercept the neighboring switch and button.
                Section {
                    controls
                } header: {
                    Text(DulcetStrings.offlineMusic)
                }
                .accessibilityElement(children: .contain)
            } else {
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    Text(DulcetStrings.offlineMusic).font(.headline)
                    controls
                }
                .accessibilityElement(children: .contain)
            }
        }
    }

    @ViewBuilder
    private var controls: some View {
        Toggle(DulcetStrings.offlineAutomaticDownloads, isOn: Binding(
            get: { storage.automaticDownloadsEnabled },
            set: { store.setAutomaticDownloadsEnabled($0) }
        )).accessibilityIdentifier("dulcet.offline.automaticDownloads")
        Text(DulcetStrings.offlineAutomaticHint)
            .font(.caption).dulcetForeground(inForm ? .secondaryTextOnControl : .secondaryTextOnWindow)
        if storage.automaticDownloadsEnabled {
            Text(DulcetStrings.offlineDownloadProgress(completed: storage.completedAutomaticCount, remaining: storage.queuedAutomaticCount))
                .font(.caption).accessibilityIdentifier("dulcet.offline.progress")
        }
        LabeledContent(DulcetStrings.offlineDownloads, value: bytes(storage.downloadBytes))
        LabeledContent(DulcetStrings.offlineStreamingCache, value: bytes(storage.cacheBytes))
        LabeledContent(DulcetStrings.offlineArtworkCache, value: bytes(storage.artworkBytes))
        Picker(DulcetStrings.offlineCacheSize, selection: Binding(get: { storage.cacheBudgetBytes },
                                                set: { store.setStreamingCacheBudgetBytes($0) })) {
            Text(DulcetStrings.offlineCacheOff).tag(Int64(0))
            Text(DulcetStrings.offlineCache256MB).tag(Int64(256 * 1_024 * 1_024))
            Text(DulcetStrings.offlineCache512MB).tag(Int64(512 * 1_024 * 1_024))
            Text(DulcetStrings.offlineCache1GB).tag(Int64(1_024 * 1_024 * 1_024))
            Text(DulcetStrings.offlineCache2GB).tag(Int64(2 * 1_024 * 1_024 * 1_024))
        }.pickerStyle(.menu).accessibilityIdentifier("dulcet.offline.cacheSize")
        Text(DulcetStrings.offlineCacheHint)
            .font(.caption).dulcetForeground(inForm ? .secondaryTextOnControl : .secondaryTextOnWindow)
        Button { store.clearStreamingCache() } label: {
            Text(DulcetStrings.offlineClearCache)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
            .accessibilityIdentifier("dulcet.offline.clearCache")
    }
    private func bytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }
}
