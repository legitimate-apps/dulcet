import CoreGraphics
import Testing
@testable import DulcetKit

// The iPhone artist page's hero (DulcetArtistHero.swift): when the bar takes the name, how dark
// the scrim under the name is, which part of the picture that is read from, and which cover
// stands in for an artist with no photo. Each would look plausible on screen when wrong.

/// The name moves into the bar only once it has scrolled up under the bar, never at rest.
@Test
func theBarTakesTheArtistsNameOnlyOnceTheHeroNameIsUnderIt() {
    // iPhone 18 Pro: status bar and navigation bar end 116 pt down; the name rests near 380.
    let barBottom: CGFloat = 116
    #expect(!DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: 380, barBottom: barBottom))
    #expect(!DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: barBottom, barBottom: barBottom))
    #expect(DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: barBottom - 1, barBottom: barBottom))
    #expect(DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: -40, barBottom: barBottom))
    // Pulled down, the name moves further from the bar, not into it.
    #expect(!DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: 520, barBottom: barBottom))
    // Before anything reports a title, the preference's default is "no title on screen yet".
    #expect(!DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: DulcetDetailTitleBottom.defaultValue, barBottom: barBottom))
}

/// The name over the hero fades only in the last stretch before the bar, never at rest.
@Test
func theHeroNameFadesOnlyAsItReachesTheBar() {
    let barBottom: CGFloat = 116
    #expect(DulcetArtistHeroLayout.identityOpacity(identityTop: 300, barBottom: barBottom) == 1)
    #expect(DulcetArtistHeroLayout.identityOpacity(identityTop: barBottom + DulcetArtistHeroLayout.fadeDistance, barBottom: barBottom) == 1)
    #expect(abs(DulcetArtistHeroLayout.identityOpacity(identityTop: barBottom + DulcetArtistHeroLayout.fadeDistance / 2, barBottom: barBottom) - 0.5) < 0.001)
    #expect(DulcetArtistHeroLayout.identityOpacity(identityTop: barBottom, barBottom: barBottom) == 0)
    #expect(DulcetArtistHeroLayout.identityOpacity(identityTop: -200, barBottom: barBottom) == 0)
}

/// White text keeps 4.5:1 over the brightest picture the scrim is sized for, and a dark picture
/// is not blacked out.
@Test
func theScrimIsAsDarkAsThePictureUnderTheNameNeeds() {
    func contrastOfWhite(overLuminance luminance: Double) -> Double {
        let alpha = DulcetArtistHeroLayout.scrimOpacity(luminance: luminance)
        return 1.05 / ((1 - alpha) * luminance + 0.05)
    }
    for luminance in stride(from: 0.0, through: 1.0, by: 0.05) {
        #expect(contrastOfWhite(overLuminance: luminance) >= 4.5, "luminance \(luminance)")
    }
    // Brighter pictures get a darker scrim; a white one gets just enough, not black.
    #expect(DulcetArtistHeroLayout.scrimOpacity(luminance: 0.6) > DulcetArtistHeroLayout.scrimOpacity(luminance: 0.3))
    #expect(abs(DulcetArtistHeroLayout.scrimOpacity(luminance: 1) - 0.82) < 0.0001)
    #expect(DulcetArtistHeroLayout.scrimOpacity(luminance: 0.05) == 0.25)
}

/// The bottom band is read from the bottom of the picture: a picture white at the top and black
/// at the bottom has a bright top band and a dark bottom one, and the reverse the other way up.
@Test
func theNameScrimReadsTheBottomOfThePicture() throws {
    let whiteTop = try #require(ArtistHeroFixture.halves(topWhite: true))
    let blackTop = try #require(ArtistHeroFixture.halves(topWhite: false))
    let a = try #require(DulcetArtistHeroLayout.bandLuminance(whiteTop))
    let b = try #require(DulcetArtistHeroLayout.bandLuminance(blackTop))
    #expect(a.top > 0.9 && a.bottom < 0.05)
    #expect(b.top < 0.05 && b.bottom > 0.9)
}

/// An artist with no photo is drawn from its newest dated album that has a cover.
@Test
func anArtistWithNoPhotoBorrowsItsNewestCoveredAlbum() {
    let albums = [
        ArtistHeroFixture.album("old", year: 1999, cover: true),
        ArtistHeroFixture.album("newest-uncovered", year: 2024, cover: false),
        ArtistHeroFixture.album("new", year: 2021, cover: true),
        ArtistHeroFixture.album("undated", year: nil, cover: true),
    ]
    #expect(DulcetArtistHeroLayout.newestAlbum(albums)?.id.rawID == "new")
    #expect(DulcetArtistHeroLayout.newestAlbum([ArtistHeroFixture.album("bare", year: 2020, cover: false)]) == nil)
}

/// As tall as the phone is wide in portrait; in landscape no more than half the screen and more.
@Test
func theHeroIsSquareOnAPhoneAndShortInLandscape() {
    #expect(DulcetArtistHeroLayout.heroHeight(width: 402, screenHeight: 874) == 402)
    #expect(DulcetArtistHeroLayout.heroHeight(width: 874, screenHeight: 402) == 260)
}

private enum ArtistHeroFixture {
    /// 16 x 16, its top half one colour and its bottom half the other, drawn the right way up.
    static func halves(topWhite: Bool) -> CGImage? {
        guard let context = CGContext(
            data: nil, width: 16, height: 16, bitsPerComponent: 8, bytesPerRow: 64,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        // Core Graphics puts y = 0 at the bottom: the first fill is the bottom half.
        context.setFillColor(gray: topWhite ? 0 : 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: 16, height: 8))
        context.setFillColor(gray: topWhite ? 1 : 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 8, width: 16, height: 8))
        return context.makeImage()
    }

    static func album(_ rawID: String, year: Int?, cover: Bool) -> DulcetReaderItem {
        DulcetReaderItem(
            kind: "album", providerInstanceID: "provider-hero", rawID: rawID, title: rawID,
            artistName: "Artist", artistRawID: "artist-1", albumTitle: nil, albumRawID: nil,
            year: year, genre: nil, durationMilliseconds: nil, songCount: 10, albumCount: nil,
            discNumber: nil, trackNumber: nil, sourceContainer: nil,
            artworkKey: cover ? "al-\(rawID)" : nil, owner: nil, favourite: nil, rating: nil,
            playCount: nil, playability: nil, detailComplete: true, metadataMissing: false
        )!
    }
}
