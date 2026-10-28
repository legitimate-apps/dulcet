#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// How much of the original a stream may carry (spec §12.5). The raw values are the core's stored
/// names for the same choices, so a value written on one platform reads the same on another.
public enum DulcetStreamingQuality: String, CaseIterable, Sendable, Hashable, Identifiable {
    case original
    case kbps320 = "320"
    case kbps256 = "256"
    case kbps192 = "192"
    case kbps128 = "128"
    case kbps96 = "96"

    public var id: String { rawValue }

    /// The cap in kilobits per second; nil for the original.
    public var maxBitRateKbps: Int? { self == .original ? nil : Int(rawValue) }

    var label: String {
        guard let kbps = maxBitRateKbps else { return DulcetStrings.streamingQualityOriginal }
        return DulcetStrings.streamingQualityKbps(kbps)
    }
}

/// One choice for an unmetered network (Wi-Fi, Ethernet), one for a metered one (cellular, or a
/// network the system treats as expensive or constrained).
public struct DulcetStreamingQualityPreference: Sendable, Hashable {
    public var unmetered: DulcetStreamingQuality
    public var metered: DulcetStreamingQuality

    public init(unmetered: DulcetStreamingQuality, metered: DulcetStreamingQuality) {
        self.unmetered = unmetered
        self.metered = metered
    }
}

/// The device's streaming-quality setting, as the production composition supplies it. Saving a
/// choice applies it from the next item; nothing playing restarts for it.
@MainActor
public protocol DulcetStreamingQualitySetting: AnyObject {
    var preference: DulcetStreamingQualityPreference { get }
    func setPreference(_ preference: DulcetStreamingQualityPreference)
}

/// The Streaming Quality section of the settings screen: a choice for Wi-Fi and one for cellular.
struct DulcetStreamingQualitySection: View {
    @Bindable var store: DulcetPresentationStore
    var inForm = false

    var body: some View {
        if let preference = store.streamingQuality {
            if inForm {
                Section {
                    choices(preference)
                } header: {
                    Text(DulcetStrings.streamingQualityTitle)
                } footer: {
                    Text(DulcetStrings.streamingQualityFootnote)
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.streaming-quality")
            } else {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.streamingQualityTitle)
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    choices(preference)
                    Text(DulcetStrings.streamingQualityFootnote)
                        .font(.callout)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // A container of its own: without it the section's identifier is applied to every
                // element inside it, and each choice loses its own (OBSERVED on iOS and tvOS 26.5:
                // both pickers, and every tvOS choice, reported `dulcet.streaming-quality`).
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.streaming-quality")
            }
        }
    }

    @ViewBuilder
    private func choices(_ preference: DulcetStreamingQualityPreference) -> some View {
        choice(
            DulcetStrings.streamingQualityUnmetered,
            identifier: "dulcet.streaming-quality.unmetered",
            selection: preference.unmetered
        ) { chosen in
            var next = preference
            next.unmetered = chosen
            store.setStreamingQuality(next)
        }
        choice(
            DulcetStrings.streamingQualityMetered,
            identifier: "dulcet.streaming-quality.metered",
            selection: preference.metered
        ) { chosen in
            var next = preference
            next.metered = chosen
            store.setStreamingQuality(next)
        }
    }

    @ViewBuilder
    private func choice(
        _ title: String,
        identifier: String,
        selection: DulcetStreamingQuality,
        choose: @escaping (DulcetStreamingQuality) -> Void
    ) -> some View {
#if os(tvOS)
        NavigationLink {
            List(DulcetStreamingQuality.allCases) { quality in
                Button { choose(quality) } label: {
                    HStack {
                        Text(quality.label)
                        Spacer()
                        if quality == selection { Image(systemName: "checkmark") }
                    }
                }
                .accessibilityIdentifier("\(identifier).\(quality.rawValue)")
                .accessibilityAddTraits(quality == selection ? .isSelected : [])
            }
            .navigationTitle(title)
        } label: { LabeledContent(title, value: selection.label) }
        .accessibilityIdentifier(identifier)
#else
        LabeledContent(title) {
            Picker(title, selection: Binding(get: { selection }, set: choose)) {
                ForEach(DulcetStreamingQuality.allCases) { quality in
                    Text(quality.label).tag(quality)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .accessibilityIdentifier(identifier)
        }
#endif
    }
}
#endif

#if os(macOS) || os(iOS) || os(tvOS)
/// The Crossfade section of the settings screen (§12.8): off, or one to twelve seconds.
struct DulcetCrossfadeSection: View {
    @Bindable var store: DulcetPresentationStore
    var inForm = false

    var body: some View {
        if let listening = store.listening {
            if inForm {
                Section {
                    picker(listening.crossfadeSeconds)
                } header: {
                    Text(DulcetStrings.crossfadeTitle)
                } footer: {
                    Text(DulcetStrings.crossfadeFootnote)
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.crossfade")
            } else {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.crossfadeTitle)
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    picker(listening.crossfadeSeconds)
                    Text(DulcetStrings.crossfadeFootnote)
                        .font(.callout)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.crossfade")
            }
        }
    }

    static func label(_ seconds: Int) -> String {
        seconds == 0 ? DulcetStrings.crossfadeOff : DulcetStrings.crossfadeSeconds(seconds)
    }

    private func picker(_ selection: Int) -> some View {
        Picker(DulcetStrings.crossfadeTitle, selection: Binding(
            get: { selection },
            set: { store.setCrossfadeSeconds($0) }
        )) {
            ForEach(0...DulcetListeningPreference.maximumCrossfadeSeconds, id: \.self) { seconds in
                Text(Self.label(seconds)).tag(seconds)
            }
        }
        .pickerStyle(.menu)
        .accessibilityIdentifier("dulcet.crossfade.length")
    }
}
#endif

#if os(macOS)
/// The Mac's Settings window (Dulcet > Settings…): the streaming-quality choice, the same section
/// the Connection screen carries.
public struct DulcetSettingsView: View {
    @Bindable var store: DulcetPresentationStore

    public init(store: DulcetPresentationStore) {
        self.store = store
    }

    public var body: some View {
        TabView {
            NavigationStack { DulcetAccountConnectionView(store: store) }
                .tabItem { Label(DulcetStrings.accountSection, systemImage: "person.crop.circle") }
            DulcetPlaybackSettingsPage(store: store)
                .tabItem { Label(DulcetStrings.playbackSettings, systemImage: "play.circle") }
            DulcetDownloadsStoragePage(store: store)
                .tabItem { Label(DulcetStrings.downloadsStorage, systemImage: "arrow.down.circle") }
        }
        .environment(store)
        .frame(width: 600, height: 560)
    }
}
#endif
