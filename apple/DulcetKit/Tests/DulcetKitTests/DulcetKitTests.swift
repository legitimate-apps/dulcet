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
}

@Test @MainActor
func tlsFailureIsUserPresentableAndSpecific() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .tlsUntrusted)
    let failure = snapshot.tlsFailure

    #expect(failure?.reason.localizedCaseInsensitiveContains("expired") == true)
    #expect(failure?.technicalDetail.localizedCaseInsensitiveContains("OS-trusted") == true)
    #expect(failure?.technicalDetail.contains("http") == false)
    #expect(snapshot.connectivity == .connectionFailed(serverName: "Listening Room"))
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
func customSemanticColorsMeetWCAGAAInBothAppearances() throws {
    let foregrounds = [
        DulcetContrastColor.accent,
        DulcetContrastColor.offline,
        DulcetContrastColor.danger,
    ]

    for appearanceName in [NSAppearance.Name.aqua, .darkAqua] {
        let appearance = try #require(NSAppearance(named: appearanceName))
        let background = try resolved(NSColor.windowBackgroundColor, appearance: appearance)
        for foreground in foregrounds {
            let resolvedForeground = try resolved(foreground, appearance: appearance)
            #expect(contrastRatio(resolvedForeground, background) >= 4.5)
        }
    }
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
