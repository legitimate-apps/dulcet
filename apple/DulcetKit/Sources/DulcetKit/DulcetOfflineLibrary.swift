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
    @State private var confirmsClearCache = false
    private var storage: DulcetOfflineLibraryStorage { store.offlineLibraryStorage ?? .init() }
    var body: some View {
        if store.offlineLibraryStorage != nil {
            if inForm {
                // Three groups, as the system's storage pages have: what is kept offline, what it
                // occupies, and the streaming cache with its own action. A Form gives each control
                // its own row; one shared row would let a picker's hit area take a neighbour's tap.
                Section {
                    automaticDownloadsToggle
                } header: {
                    Text(DulcetStrings.offlineMusic)
                } footer: {
                    offlineMusicFooter
                }
                .accessibilityElement(children: .contain)
                Section(DulcetStrings.offlineStorage) {
                    storageSizes
                }
                Section {
                    cacheSizePicker
                    clearCacheAction
                } header: {
                    Text(DulcetStrings.offlineStreamingCache)
                } footer: {
                    Text(DulcetStrings.offlineCacheHint)
                }
            } else {
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    Text(DulcetStrings.offlineMusic).font(.headline)
                    automaticDownloadsToggle
                    offlineMusicFooter.font(.footnote).dulcetForeground(.secondaryTextOnWindow)
                    storageSizes
                    cacheSizePicker
                    Text(DulcetStrings.offlineCacheHint).font(.footnote).dulcetForeground(.secondaryTextOnWindow)
                    clearCacheAction
                }
                .accessibilityElement(children: .contain)
            }
        }
    }

    private var automaticDownloadsToggle: some View {
        Toggle(DulcetStrings.offlineAutomaticDownloads, isOn: Binding(
            get: { storage.automaticDownloadsEnabled },
            set: { store.setAutomaticDownloadsEnabled($0) }
        )).accessibilityIdentifier("dulcet.offline.automaticDownloads")
    }

    @ViewBuilder
    private var offlineMusicFooter: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            Text(DulcetStrings.offlineAutomaticHint)
            if storage.automaticDownloadsEnabled {
                Text(DulcetStrings.offlineDownloadProgress(completed: storage.completedAutomaticCount, remaining: storage.queuedAutomaticCount))
                    .accessibilityIdentifier("dulcet.offline.progress")
            }
        }
    }

    @ViewBuilder
    private var storageSizes: some View {
        LabeledContent(DulcetStrings.offlineDownloads, value: bytes(storage.downloadBytes))
        LabeledContent(DulcetStrings.offlineStreamingCache, value: bytes(storage.cacheBytes))
        LabeledContent(DulcetStrings.offlineArtworkCache, value: bytes(storage.artworkBytes))
    }

    private var cacheSizePicker: some View {
        Picker(DulcetStrings.offlineCacheSize, selection: Binding(get: { storage.cacheBudgetBytes },
                                                set: { store.setStreamingCacheBudgetBytes($0) })) {
            Text(DulcetStrings.offlineCacheOff).tag(Int64(0))
            Text(DulcetStrings.offlineCache256MB).tag(Int64(256 * 1_024 * 1_024))
            Text(DulcetStrings.offlineCache512MB).tag(Int64(512 * 1_024 * 1_024))
            Text(DulcetStrings.offlineCache1GB).tag(Int64(1_024 * 1_024 * 1_024))
            Text(DulcetStrings.offlineCache2GB).tag(Int64(2 * 1_024 * 1_024 * 1_024))
        }.pickerStyle(.menu).accessibilityIdentifier("dulcet.offline.cacheSize")
    }

    /// A Mac form carries an ordinary push button at the row's trailing edge; a phone, tablet or
    /// TV form carries a destructive row. Both confirm, naming the cache and the space it frees.
    @ViewBuilder
    private var clearCacheAction: some View {
#if os(macOS)
        HStack {
            Spacer()
            Button(DulcetStrings.offlineClearCache) { confirmsClearCache = true }
                .accessibilityIdentifier("dulcet.offline.clearCache")
        }
        .modifier(clearCacheConfirmation)
#else
        Button(role: .destructive) { confirmsClearCache = true } label: {
            Text(DulcetStrings.offlineClearCache)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .accessibilityIdentifier("dulcet.offline.clearCache")
        .modifier(clearCacheConfirmation)
#endif
    }

    private var clearCacheConfirmation: DulcetClearCacheConfirmation {
        DulcetClearCacheConfirmation(
            isPresented: $confirmsClearCache,
            message: storage.cacheBytes > 0
                ? DulcetStrings.clearCacheBody(size: bytes(storage.cacheBytes))
                : DulcetStrings.clearCacheBody,
            clear: { store.clearStreamingCache() }
        )
    }

    private func bytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }
}

private struct DulcetClearCacheConfirmation: ViewModifier {
    @Binding var isPresented: Bool
    let message: String
    let clear: () -> Void

    func body(content: Content) -> some View {
        content.confirmationDialog(DulcetStrings.clearCacheTitle, isPresented: $isPresented, titleVisibility: .visible) {
            Button(DulcetStrings.offlineClearCache, role: .destructive, action: clear)
            Button(DulcetStrings.cancel, role: .cancel) {}.dulcetDefaultActionShortcut()
        } message: { Text(message) }
    }
}
