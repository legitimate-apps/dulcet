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

/// The Streaming Quality section of the settings screen: a choice for unmetered networks and one
/// for metered ones. A phone or tablet names them Wi-Fi and cellular. Mac and Apple TV have no cellular radio,
/// but their network monitors classify a personal hotspot (expensive) and Low Data Mode
/// (constrained) as metered, so it names the two choices for those networks instead.
struct DulcetStreamingQualitySection: View {
    @Bindable var store: DulcetPresentationStore
    var inForm = false

#if os(macOS) || os(tvOS)
    private let unmeteredTitle = DulcetStrings.streamingQualityStandardNetworks
    private let meteredTitle = DulcetStrings.streamingQualityLimitedNetworks
    private let footnote = DulcetStrings.streamingQualityLimitedFootnote
#else
    private let unmeteredTitle = DulcetStrings.streamingQualityUnmetered
    private let meteredTitle = DulcetStrings.streamingQualityMetered
    private let footnote = DulcetStrings.streamingQualityFootnote
#endif

    var body: some View {
        if let preference = store.streamingQuality {
            if inForm {
                Section {
                    choices(preference)
                } header: {
                    Text(DulcetStrings.streamingQualityTitle)
                } footer: {
                    Text(footnote)
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("dulcet.streaming-quality")
            } else {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.streamingQualityTitle)
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    choices(preference)
                    Text(footnote)
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
            unmeteredTitle,
            identifier: "dulcet.streaming-quality.unmetered",
            selection: preference.unmetered
        ) { chosen in
            var next = preference
            next.unmetered = chosen
            store.setStreamingQuality(next)
        }
        choice(
            meteredTitle,
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
                // The row is already labelled Crossfade; a header repeating it is redundant.
                Section {
                    picker(listening.crossfadeSeconds)
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
/// A pane of the Mac's Settings window.
public enum DulcetSettingsPane: String, CaseIterable, Hashable, Sendable {
    case account
    case playback
    case downloadsStorage
}

/// The Mac's Settings window (Dulcet > Settings…): Account, Playback and Downloads & Storage as
/// the window's own panes. Each pane is a toolbar item that shows itself selected; none pushes
/// another, so the window never shows a back button.
public struct DulcetSettingsView: View {
    @Bindable var store: DulcetPresentationStore
    @State private var selectedPane: DulcetSettingsPane

    public init(store: DulcetPresentationStore) {
        self.init(store: store, selectedPane: .account)
    }

    public init(store: DulcetPresentationStore, selectedPane: DulcetSettingsPane) {
        self.store = store
        _selectedPane = State(initialValue: selectedPane)
    }

    public var body: some View {
        TabView(selection: $selectedPane) {
            DulcetAccountConnectionView(store: store)
                .frame(width: Self.paneWidth)
                .frame(minHeight: 320, idealHeight: 460)
                .tabItem { Label(DulcetStrings.accountSection, systemImage: "person.crop.circle") }
                .tag(DulcetSettingsPane.account)
            DulcetPlaybackSettingsPage(store: store)
                .frame(width: Self.paneWidth)
                .frame(minHeight: 220, idealHeight: 260)
                .tabItem { Label(DulcetStrings.playbackSettings, systemImage: "play.circle") }
                .tag(DulcetSettingsPane.playback)
            DulcetDownloadsStoragePage(store: store)
                .frame(width: Self.paneWidth)
                .frame(minHeight: 360, idealHeight: 470)
                .tabItem { Label(DulcetStrings.downloadsStorage, systemImage: "arrow.down.circle") }
                .tag(DulcetSettingsPane.downloadsStorage)
        }
        .environment(store)
    }

    /// Wide enough for a labelled field and its value side by side, as System Settings panes are.
    private static let paneWidth: CGFloat = 560
}
#endif
