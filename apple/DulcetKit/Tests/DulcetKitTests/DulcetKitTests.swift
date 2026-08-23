import Foundation
import SwiftUI
import Testing
@testable import DulcetKit
#if os(macOS)
import AppKit
#endif

@Test
func localizedLibraryCountsSelectPluralCategoriesForVisibleAndAccessibilityCopy() {
    #expect(DulcetStrings.albumCount(0) == "0 albums")
    #expect(DulcetStrings.albumCount(1) == "1 album")
    #expect(DulcetStrings.albumCount(2) == "2 albums")
    #expect(DulcetStrings.trackCount(0) == "0 tracks")
    #expect(DulcetStrings.trackCount(1) == "1 track")
    #expect(DulcetStrings.trackCount(2) == "2 tracks")

    let accessibility = DulcetStrings.albumAccessibility(
        "One Track",
        artists: "Fixture Artist",
        tracks: DulcetStrings.trackCount(1)
    )
    #expect(accessibility == "One Track, Fixture Artist, 1 track")
    #expect(!accessibility.contains("1 tracks"))
}

@Test
func localizedIdentifierNumbersNeverUseQuantityGrouping() {
    for localeIdentifier in ["en_US", "de_DE", "fr_FR", "hi_IN"] {
        let identifier = DulcetStrings.identifierNumber(
            2026,
            locale: Locale(identifier: localeIdentifier)
        )
        #expect(identifier == "2026", "identifier grouped in \(localeIdentifier)")
    }

    #expect(
        DulcetStrings.albumMetadata(year: 2026, tracks: "4 tracks", duration: "15:04")
            == "2026 · 4 tracks · 15:04"
    )
    #expect(DulcetStrings.discTitle(2026) == "Disc 2026")
}

@Test @MainActor
func fixtureRendersEveryDeclaredDistinctState() {
    let source = DulcetDeterministicFixture()
    let snapshots = DulcetPresentationState.allCases.map(source.snapshot)

    #expect(snapshots.count == 18)
    #expect(Set(snapshots.map(\.state)).count == 18)
    #expect(snapshots.filter(\.accountConnected).count == 7)
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
    #expect(DulcetSearchSource.local.displayTitle == DulcetStrings.local)
    #expect(DulcetSearchSource.server.displayTitle == DulcetStrings.server)
    #expect(DulcetSearchSource.localAndServer.displayTitle == DulcetStrings.localAndServer)
    #expect(Set(DulcetSearchSource.allCases.map(\.symbolName)).count == 3)
    #expect(
        DulcetStrings.searchSummary
            == "Local results appear immediately. Server matches refresh the same row instead of creating a duplicate."
    )
}

@Test @MainActor
func deterministicTLSFixtureCarriesPresentableSpecificFailure() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .tlsUntrusted)
    guard case let .connectionFailed(.tlsUntrusted(failure)) = snapshot.connectivity else {
        Issue.record("TLS fixture did not carry the expected typed connection failure")
        return
    }

    #expect(failure.reason.localizedCaseInsensitiveContains("expired"))
    #expect(failure.technicalDetail.localizedCaseInsensitiveContains("OS-trusted"))
    #expect(!failure.technicalDetail.contains("http"))
    #expect(failure.serverName == "Listening Room")
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
func replaceableSourceControlsRecentlyAddedOrdering() {
    let fixture = DulcetDeterministicFixture()
    let original = fixture.snapshot(for: .libraryBrowse)
    let reversed = Array(original.recentlyAddedTracks.reversed())
    let source = PushingTestDataSource(initialSnapshot: original)
    let store = DulcetPresentationStore(source: source)

    source.publish(DulcetSnapshot(
        state: original.state,
        selectedDestination: original.selectedDestination,
        accountConnected: original.accountConnected,
        connectivity: original.connectivity,
        albums: original.albums,
        looseTracks: original.looseTracks,
        recentlyAddedTracks: reversed,
        selectedAlbum: original.selectedAlbum,
        nowPlaying: original.nowPlaying,
        searchQuery: original.searchQuery,
        searchResults: original.searchResults,
        captureDate: original.captureDate
    ))

    #expect(store.snapshot.recentlyAddedTracks.map(\.id) == reversed.map(\.id))
}

@Test
func localizedPresentationFormattingOwnsFormerViewLiterals() {
    #expect(DulcetStrings.playbackProgress(elapsed: "2:22", duration: "3:45") == "2:22 of 3:45")
    #expect(DulcetStrings.volumeValue(0.68) == "68%")
    #expect(DulcetStrings.audioFormat(codec: "FLAC", sampleRateKilohertz: 44.1) == "FLAC · 44.1 kHz")
    #expect(DulcetStrings.playingOn("Studio Display") == "Playing on Studio Display")
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

#if os(macOS)
@Test @MainActor
func registeredColorPairsMeetWCAGAAInBothAppearances() throws {
    for appearanceName in [NSAppearance.Name.aqua, .darkAqua] {
        for pair in DulcetRegisteredContrastPair.allCases {
            let sample = try renderedContrastSample(
                foreground: pair.foreground,
                backgroundLayers: pair.backgroundLayers,
                appearanceName: appearanceName
            )
            print(
                "WCAG REGISTERED CONTRAST pair=\(pair.rawValue) appearance=\(appearanceName.rawValue) "
                    + "foreground=\(sample.foreground.hexRGB) background=\(sample.background.hexRGB) "
                    + "ratio=\(String(format: "%.3f", sample.ratio)) minimum=\(pair.minimumRatio)"
            )
            #expect(
                sample.ratio >= pair.minimumRatio,
                "\(pair.rawValue) in \(appearanceName.rawValue) is \(sample.ratio):1, below \(pair.minimumRatio):1"
            )
        }
    }
}

@Test @MainActor
func renderedFixtureStatesExerciseEveryRegisteredContrastPair() {
    var observed: Set<DulcetRegisteredContrastPair> = []

    for state in DulcetPresentationState.allCases {
        let store = DulcetPresentationStore(
            source: DulcetDeterministicDataSource(initialState: state)
        )
        let view = NSHostingView(rootView: DulcetCaptureView(store: store)
            .onDulcetRegisteredContrastPairs { observed.formUnion($0) })
        view.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        view.layoutSubtreeIfNeeded()
        view.displayIfNeeded()
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
    }

    #expect(observed == Set(DulcetRegisteredContrastPair.allCases))
}

@Test @MainActor
func contrastInstrumentRejectsNativeSecondaryOverWhite() throws {
    let sample = try renderedContrastSample(
        foreground: .secondary,
        backgroundLayers: [AnyShapeStyle(Color.white)],
        appearanceName: .aqua
    )

    #expect(sample.ratio < 4.5)
    print(
        "WCAG CONTRAST INSTRUMENT NEGATIVE CONTROL PASS pair=native-secondary/white "
            + "foreground=\(sample.foreground.hexRGB) background=\(sample.background.hexRGB) "
            + "ratio=\(String(format: "%.3f", sample.ratio)) minimum=4.5 rejected=true"
    )
}

@Test @MainActor
func contrastInstrumentRejectsLegacyCombinedSourceTagPairInBothAppearances() throws {
    for appearanceName in [NSAppearance.Name.aqua, .darkAqua] {
        let sample = try renderedContrastSample(
            foreground: .purple,
            backgroundLayers: [
                AnyShapeStyle(Color.dulcetWindow),
                AnyShapeStyle(Color.dulcetControl.opacity(0.52)),
                AnyShapeStyle(Color.purple.opacity(0.11)),
            ],
            appearanceName: appearanceName
        )

        #expect(sample.ratio < 4.5)
        print(
            "WCAG CONTRAST INSTRUMENT LEGACY TAG NEGATIVE CONTROL PASS "
                + "pair=combined-source-label/combined-source-tint "
                + "appearance=\(appearanceName.rawValue) "
                + "foreground=\(sample.foreground.hexRGB) background=\(sample.background.hexRGB) "
                + "ratio=\(String(format: "%.3f", sample.ratio)) minimum=4.5 rejected=true"
        )
    }
}
#endif

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

#if os(macOS)
private struct RenderedContrastSample {
    let foreground: NSColor
    let background: NSColor

    var ratio: Double {
        contrastRatio(foreground, background)
    }
}

private struct RenderedContrastProbe: View {
    let foreground: Color
    let backgroundLayers: [AnyShapeStyle]

    var body: some View {
        ZStack {
            ForEach(backgroundLayers.indices, id: \.self) { index in
                Rectangle().fill(backgroundLayers[index])
            }
            Rectangle()
                .foregroundStyle(foreground)
                .frame(width: 24, height: 24)
        }
        .frame(width: 64, height: 64)
    }
}

@MainActor
private func renderedContrastSample(
    foreground: Color,
    backgroundLayers: [AnyShapeStyle],
    appearanceName: NSAppearance.Name
) throws -> RenderedContrastSample {
    let colorScheme: ColorScheme = appearanceName == .darkAqua ? .dark : .light
    let view = NSHostingView(rootView: RenderedContrastProbe(
        foreground: foreground,
        backgroundLayers: backgroundLayers
    ).environment(\.colorScheme, colorScheme))
    view.frame = NSRect(x: 0, y: 0, width: 64, height: 64)
    view.appearance = NSAppearance(named: appearanceName)
    view.layoutSubtreeIfNeeded()
    view.displayIfNeeded()

    let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
    view.cacheDisplay(in: view.bounds, to: bitmap)
    let renderedForeground = try #require(bitmap.colorAt(
        x: bitmap.pixelsWide / 2,
        y: bitmap.pixelsHigh / 2
    ))
    let renderedBackground = try #require(bitmap.colorAt(x: 4, y: 4))
    return RenderedContrastSample(
        foreground: renderedForeground,
        background: renderedBackground
    )
}

private func contrastRatio(_ foreground: NSColor, _ background: NSColor) -> Double {
    let opaqueBackground = composite(background, over: .white)
    let opaqueForeground = composite(foreground, over: opaqueBackground)
    let lighter = max(relativeLuminance(opaqueForeground), relativeLuminance(opaqueBackground))
    let darker = min(relativeLuminance(opaqueForeground), relativeLuminance(opaqueBackground))
    return (lighter + 0.05) / (darker + 0.05)
}

private func composite(_ foreground: NSColor, over background: NSColor) -> NSColor {
    guard let foreground = foreground.usingColorSpace(.sRGB),
          let background = background.usingColorSpace(.sRGB) else {
        return foreground
    }
    let alpha = foreground.alphaComponent
    return NSColor(
        srgbRed: foreground.redComponent * alpha + background.redComponent * (1 - alpha),
        green: foreground.greenComponent * alpha + background.greenComponent * (1 - alpha),
        blue: foreground.blueComponent * alpha + background.blueComponent * (1 - alpha),
        alpha: 1
    )
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

private extension NSColor {
    var hexRGB: String {
        guard let rgb = usingColorSpace(.sRGB) else { return "unavailable" }
        return String(
            format: "#%02X%02X%02X",
            Int((rgb.redComponent * 255).rounded()),
            Int((rgb.greenComponent * 255).rounded()),
            Int((rgb.blueComponent * 255).rounded())
        )
    }
}
#endif
