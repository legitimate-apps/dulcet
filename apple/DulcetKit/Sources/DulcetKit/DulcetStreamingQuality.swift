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

    var body: some View {
        if let preference = store.streamingQuality {
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Text(DulcetStrings.streamingQualityTitle)
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
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
                Text(DulcetStrings.streamingQualityFootnote)
                    .font(.callout)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("dulcet.streaming-quality")
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
        // A row of buttons a remote moves along; the chosen one carries a check.
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(title).font(.headline)
            HStack(spacing: DulcetSpacing.sm) {
                ForEach(DulcetStreamingQuality.allCases) { quality in
                    Button {
                        choose(quality)
                    } label: {
                        if quality == selection {
                            Label(quality.label, systemImage: "checkmark")
                        } else {
                            Text(quality.label)
                        }
                    }
                    .accessibilityIdentifier("\(identifier).\(quality.rawValue)")
                    .accessibilityAddTraits(quality == selection ? .isSelected : [])
                }
            }
        }
#else
        Picker(title, selection: Binding(get: { selection }, set: choose)) {
            ForEach(DulcetStreamingQuality.allCases) { quality in
                Text(quality.label).tag(quality)
            }
        }
        .pickerStyle(.menu)
        .accessibilityIdentifier(identifier)
#endif
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
        DulcetStreamingQualitySection(store: store)
            .padding(DulcetSpacing.xl)
            .frame(width: 480)
    }
}
#endif
