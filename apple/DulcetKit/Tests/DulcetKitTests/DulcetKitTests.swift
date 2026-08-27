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

@Test
func responsiveLibraryGridColumnsFollowExplicitContainerWidth() {
    // Geometry is spelled out rather than taken from DulcetSpacing, because those tokens are
    // deliberately larger on tvOS (xs 12 vs 8, lg 36 vs 24). Feeding them in made the expected
    // column counts platform-dependent: at a 700-point detail width with a 150-point minimum item,
    // the same call yields 4 columns on macOS and 3 on tvOS, so this test failed the moment the
    // package was compiled for tvOS. Getting fewer columns on a TV is correct behaviour, not a
    // defect — what this test exists to pin is the arithmetic in columnCount, which must not depend
    // on the platform. The values below are the non-tvOS ones the expectations were authored
    // against.
    let captureDetailWidth = DulcetMetrics.captureWidth - 233
    let contentInsets: CGFloat = 48
    let itemSpacing: CGFloat = 8
    let wideItemSpacing: CGFloat = 16

    #expect(DulcetResponsiveGridLayout.columns(
        containerWidth: captureDetailWidth,
        horizontalInsets: contentInsets,
        minimumItemWidth: 180,
        spacing: itemSpacing,
        alignment: .leading
    ).count == 4)
    #expect(DulcetResponsiveGridLayout.columns(
        containerWidth: captureDetailWidth,
        horizontalInsets: contentInsets,
        minimumItemWidth: 150,
        spacing: itemSpacing,
        alignment: .top
    ).count == 5)

    let narrowerDetailWidth: CGFloat = 700
    #expect(DulcetResponsiveGridLayout.columns(
        containerWidth: narrowerDetailWidth,
        horizontalInsets: contentInsets,
        minimumItemWidth: 180,
        spacing: itemSpacing,
        alignment: .leading
    ).count == 3)
    #expect(DulcetResponsiveGridLayout.columns(
        containerWidth: narrowerDetailWidth,
        horizontalInsets: contentInsets,
        minimumItemWidth: 150,
        spacing: itemSpacing,
        alignment: .top
    ).count == 4)
    #expect(DulcetResponsiveGridLayout.columns(
        containerWidth: 280,
        horizontalInsets: contentInsets,
        minimumItemWidth: 190,
        spacing: wideItemSpacing,
        alignment: .top
    ).count == 1)
}

@Test @MainActor
func fixtureRendersEveryDeclaredDistinctState() {
    let source = DulcetDeterministicFixture()
    let snapshots = DulcetPresentationState.allCases.map(source.snapshot)

    #expect(snapshots.count == DulcetPresentationState.allCases.count)
    #expect(Set(snapshots.map(\.state)) == Set(DulcetPresentationState.allCases))
    #expect(snapshots.filter(\.accountConnected).count == 17)
}

@Test @MainActor
func savedAccountFixtureIsConfiguredButDisconnected() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .accountSavedDisconnected)

    #expect(snapshot.state == .accountSavedDisconnected)
    #expect(snapshot.selectedDestination == .library)
    #expect(!snapshot.accountConnected)
    #expect(snapshot.accountConnection == .saved(serverName: "music.example.invalid"))
    #expect(snapshot.connectivity == .disconnected(serverName: "music.example.invalid"))
    #expect(snapshot.albums.isEmpty)
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
    #expect(tracks.contains { $0.duration == .seconds(29) })
    #expect(tracks.contains { $0.duration == .seconds(31) })
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
func searchFixtureIsServerOnlyStructuredAndProviderScoped() {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .searchResults)

    #expect(Set(snapshot.searchResults.map(\.id)).count == snapshot.searchResults.count)
    #expect(snapshot.searchResults.count == 6)
    #expect(snapshot.searchResults.allSatisfy { $0.id.providerInstanceID == "deterministic-fixture" })
    #expect(snapshot.searchResults.contains { !$0.credits.isEmpty })
    #expect(snapshot.searchResults.contains { $0.duration == .seconds(188) })
    #expect(snapshot.searchHasMoreKinds == [.track, .album])
    #expect(DulcetStrings.searchResultCount(1) == "1 result")
    #expect(DulcetStrings.searchResultCount(snapshot.searchResults.count) == "6 results")
    #expect(
        DulcetStrings.searchSummary
            == "Results come from the connected server. Search begins after two characters."
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
    #expect(DulcetStrings.tlsRemedyBody.localizedCaseInsensitiveContains("operating-system"))
    #expect(DulcetLinks.certificateInstallationGuide.host == "support.apple.com")
    #expect(DulcetLinks.certificateInstallationGuide.path.contains("add-certificates-to-a-keychain"))
}

@Test @MainActor
func storeRoutesSemanticActionsWithoutExposingFixtureSelection() {
    let source = DulcetDeterministicDataSource(initialState: .libraryBrowse)
    let store = DulcetPresentationStore(source: source)

    store.selectDestination(.search)
    #expect(store.snapshot.state == .searchResults)

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
func everyPresentationStatePublishesItsDestinationWindowTitle() {
    for state in DulcetPresentationState.allCases {
        let source = DulcetDeterministicDataSource(initialState: state)
        let store = DulcetPresentationStore(source: source)
        let hostingView = NSHostingView(rootView: DulcetRootView(store: store))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1_180, height: 760),
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        hostingView.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.02))

        let expectedTitle = if state == .albumDetailMultiDisc {
            store.snapshot.selectedAlbum?.title ?? DulcetSidebarDestination.library.windowTitle
        } else {
            store.snapshot.selectedDestination.windowTitle
        }
        #expect(
            window.title == expectedTitle,
            "\(state.rawValue) published \(window.title) instead of \(expectedTitle)"
        )
    }
}

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
