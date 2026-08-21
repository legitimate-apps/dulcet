import Testing
@testable import DulcetKit

@Test @MainActor
func fixtureRendersExactlySevenDistinctStates() {
    let source = DulcetDeterministicFixture()
    let snapshots = DulcetFixtureState.allCases.map(source.snapshot)

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
    let failure = DulcetDeterministicFixture().snapshot(for: .tlsUntrusted).tlsFailure

    #expect(failure?.reason.localizedCaseInsensitiveContains("expired") == true)
    #expect(failure?.technicalDetail.localizedCaseInsensitiveContains("OS-trusted") == true)
    #expect(failure?.technicalDetail.contains("http") == false)
}
