import AppKit
import Testing
@testable import DulcetKit

@Test @MainActor
func fixtureRendersExactlySevenDistinctStates() {
    let source = DulcetDeterministicFixture()
    let snapshots = DulcetPresentationState.allCases.map(source.snapshot)

    #expect(snapshots.count == 7)
    #expect(Set(snapshots.map(\.state)).count == 7)
    #expect(snapshots.filter(\.accountConnected).count == 6)
}

@Test @MainActor
func fixtureCarriesEveryAwkwardSeedCorpusCase() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .libraryBrowse)
    let tracks = snapshot.albums.flatMap(\.tracks) + snapshot.looseTracks

    #expect(tracks.contains { $0.title == "Étude 東京" })
    #expect(snapshot.albums.first { $0.title == "Double Lines" }?.discNumbers == [1, 2])
    #expect(snapshot.albums.first { $0.title == "Several Album Artists" }?.albumArtists.count == 3)
    #expect(tracks.contains { $0.albumTitle == nil })
    #expect(tracks.contains { $0.title.count > 300 })
    #expect(snapshot.albums.first { $0.title == "Paging Atlas" }?.tracks.count == 300)
    #expect(tracks.contains { $0.durationSeconds == 29 })
    #expect(tracks.contains { $0.durationSeconds == 31 })
}

@Test @MainActor
func offlineFixtureKeepsMetadataAndDisablesPlayback() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .offlineMetadataOnly)
    let tracks = snapshot.albums.flatMap(\.tracks) + snapshot.looseTracks

    #expect(!snapshot.albums.isEmpty)
    #expect(!tracks.isEmpty)
    #expect(tracks.allSatisfy { $0.availability == .metadataOnly })
}

@Test @MainActor
func searchFixtureMakesMergedSourcesExplicitWithoutDuplicates() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .searchMixedSources)

    #expect(Set(snapshot.searchResults.map(\.source)) == Set(DulcetSearchSource.allCases))
    #expect(Set(snapshot.searchResults.map(\.id)).count == snapshot.searchResults.count)
    #expect(snapshot.searchResults.contains { $0.refreshedFromServer })
    #expect(snapshot.searchResults.count == 10)
}

@Test @MainActor
func tlsFailureIsUserPresentableAndSpecific() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .tlsUntrusted)
    let failure = snapshot.tlsFailure

    #expect(failure?.reason.localizedCaseInsensitiveContains("expired") == true)
    #expect(failure?.technicalDetail.localizedCaseInsensitiveContains("OS-trusted") == true)
    #expect(failure?.technicalDetail.contains("http") == false)
    #expect(snapshot.connectivity == .connectionFailed(serverName: "Listening Room"))
    #expect(DulcetStrings.tlsRemedyBody.contains("System keychain"))
    #expect(DulcetLinks.certificateInstallationGuide.host == "support.apple.com")
    #expect(DulcetLinks.certificateInstallationGuide.path.contains("add-certificates-to-a-keychain"))
}

@Test @MainActor
func storeRoutesSemanticActionsWithoutExposingFixtureSelection() {
    let source = DulcetDeterministicDataSource(initialState: .libraryBrowse)
    let store = DulcetPresentationStore(source: source)

    store.selectDestination(.search)
    #expect(store.snapshot.state == .searchMixedSources)

    store.searchQuery = "東京"
    #expect(store.snapshot.searchQuery == "東京")
}

@Test @MainActor
func storeAcceptsSnapshotsPushedByADataSource() {
    let fixture = DulcetDeterministicFixture()
    let source = PushingTestDataSource(
        initialSnapshot: fixture.snapshot(for: .libraryBrowse)
    )
    let store = DulcetPresentationStore(source: source)

    source.publish(fixture.snapshot(for: .offlineMetadataOnly))

    #expect(store.snapshot.state == .offlineMetadataOnly)
    #expect(store.selectedDestination == .library)
    #expect(store.snapshot.connectivity == .offline(lastSyncedDescription: "Today at 14:28 UTC"))
}

@Test @MainActor
func reselectingLibraryFromAlbumDetailReturnsToLibraryRoot() {
    let source = DulcetDeterministicDataSource(initialState: .albumDetailMultiDisc)
    let store = DulcetPresentationStore(source: source)

    #expect(store.selectedDestination == .library)
    #expect(store.snapshot.state == .albumDetailMultiDisc)

    store.selectDestination(.library)

    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.selectedDestination == .library)
}

@Test @MainActor
func renderedColorPairsMeetWCAGAAInBothAppearances() throws {
    for appearanceName in [NSAppearance.Name.aqua, .darkAqua] {
        let appearance = try #require(NSAppearance(named: appearanceName))
        let window = try resolved(.windowBackgroundColor, appearance: appearance)
        let accent = try resolved(DulcetContrastColor.accent, appearance: appearance)
        let onAccent = try resolved(DulcetContrastColor.onAccent, appearance: appearance)
        let selectionBackground = try resolved(
            DulcetContrastColor.selectionBackground,
            appearance: appearance
        )
        let offline = try resolved(DulcetContrastColor.offline, appearance: appearance)
        let danger = try resolved(DulcetContrastColor.danger, appearance: appearance)
        let label = try resolved(.labelColor, appearance: appearance)

        let pairs = [
            ContrastRequirement(
                name: "primary-button-label/accent-fill",
                foreground: onAccent,
                background: accent,
                minimum: 4.5
            ),
            ContrastRequirement(
                name: "primary-button-label/offline-fill",
                foreground: onAccent,
                background: offline,
                minimum: 4.5
            ),
            ContrastRequirement(
                name: "selected-sidebar-label/selection-fill",
                foreground: label,
                background: selectionBackground,
                minimum: 4.5
            ),
            ContrastRequirement(
                name: "offline-label/window",
                foreground: offline,
                background: window,
                minimum: 4.5
            ),
            ContrastRequirement(
                name: "danger-icon/window",
                foreground: danger,
                background: window,
                minimum: 3.0
            ),
        ]

        for pair in pairs {
            let ratio = contrastRatio(pair.foreground, pair.background)
            print(
                "WCAG CONTRAST pair=\(pair.name) appearance=\(appearanceName.rawValue) "
                    + "ratio=\(String(format: "%.3f", ratio)) minimum=\(pair.minimum)"
            )
            #expect(
                ratio >= pair.minimum,
                "\(pair.name) in \(appearanceName.rawValue) is \(ratio):1, below \(pair.minimum):1"
            )
        }
    }
}

@Test
func contrastGateRejectsKnownFailure() {
    let lowContrastText = NSColor(srgbRed: 0.72, green: 0.72, blue: 0.72, alpha: 1)
    let ratio = contrastRatio(lowContrastText, .white)

    #expect(ratio < 4.5)
    #expect(!meetsContrastMinimum(lowContrastText, .white, minimum: 4.5))
    print(
        "WCAG CONTRAST NEGATIVE CONTROL PASS pair=gray-text/white "
            + "ratio=\(String(format: "%.3f", ratio)) minimum=4.5 rejected=true"
    )
}

@MainActor
private final class PushingTestDataSource: DulcetDataSource {
    private var handler: (@MainActor (DulcetSnapshot) -> Void)?
    private(set) var currentSnapshot: DulcetSnapshot

    init(initialSnapshot: DulcetSnapshot) {
        currentSnapshot = initialSnapshot
    }

    func setSnapshotHandler(_ handler: @escaping @MainActor (DulcetSnapshot) -> Void) {
        self.handler = handler
    }

    func send(_ action: DulcetPresentationAction) {}

    func publish(_ snapshot: DulcetSnapshot) {
        currentSnapshot = snapshot
        handler?(snapshot)
    }
}

private struct ContrastRequirement {
    let name: String
    let foreground: NSColor
    let background: NSColor
    let minimum: Double
}

private func meetsContrastMinimum(
    _ foreground: NSColor,
    _ background: NSColor,
    minimum: Double
) -> Bool {
    contrastRatio(foreground, background) >= minimum
}

private func contrastRatio(_ first: NSColor, _ second: NSColor) -> Double {
    let lighter = max(relativeLuminance(first), relativeLuminance(second))
    let darker = min(relativeLuminance(first), relativeLuminance(second))
    return (lighter + 0.05) / (darker + 0.05)
}

private func resolved(_ color: NSColor, appearance: NSAppearance) throws -> NSColor {
    var resolvedColor: NSColor?
    appearance.performAsCurrentDrawingAppearance {
        resolvedColor = color.usingColorSpace(.sRGB)
    }
    return try #require(resolvedColor)
}

private func relativeLuminance(_ color: NSColor) -> Double {
    guard let rgb = color.usingColorSpace(.sRGB) else { return 0 }
    func linear(_ component: CGFloat) -> Double {
        let value = Double(component)
        return value <= 0.04045
            ? value / 12.92
            : pow((value + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear(rgb.redComponent)
        + 0.7152 * linear(rgb.greenComponent)
        + 0.0722 * linear(rgb.blueComponent)
}
