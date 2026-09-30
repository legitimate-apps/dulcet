import Testing
@testable import DulcetKit

/// Spec §12.5: the settings screen saves through the installed setting and shows what was saved.
@MainActor
private final class RecordingStreamingQualitySetting: DulcetStreamingQualitySetting {
    private(set) var preference: DulcetStreamingQualityPreference
    private(set) var saved: [DulcetStreamingQualityPreference] = []

    init(_ preference: DulcetStreamingQualityPreference) {
        self.preference = preference
    }

    func setPreference(_ preference: DulcetStreamingQualityPreference) {
        saved.append(preference)
        self.preference = preference
    }
}

@Test @MainActor
func aStreamingQualityChoiceIsSavedThroughTheInstalledSettingAndShown() {
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .libraryBrowse))
    #expect(store.streamingQuality == nil, "no setting installed, no choice offered")
    store.setStreamingQuality(.init(unmetered: .kbps320, metered: .kbps96))
    #expect(store.streamingQuality == nil, "a choice without a setting goes nowhere")

    let setting = RecordingStreamingQualitySetting(.init(unmetered: .original, metered: .kbps192))
    store.streamingQualitySetting = setting
    #expect(store.streamingQuality == .init(unmetered: .original, metered: .kbps192))

    let chosen = DulcetStreamingQualityPreference(unmetered: .original, metered: .kbps128)
    store.setStreamingQuality(chosen)
    #expect(setting.saved == [chosen])
    #expect(store.streamingQuality == chosen)
}

@Test
func streamingQualityRawValuesAreTheCoresStoredNamesAndCapsInKbps() {
    #expect(DulcetStreamingQuality.allCases.map(\.rawValue) == ["original", "320", "256", "192", "128", "96"])
    #expect(DulcetStreamingQuality.allCases.map(\.maxBitRateKbps) == [nil, 320, 256, 192, 128, 96])
}
