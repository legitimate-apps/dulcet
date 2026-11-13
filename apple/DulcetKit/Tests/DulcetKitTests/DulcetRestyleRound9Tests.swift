#if !os(tvOS)
import CoreGraphics
import Foundation
import Testing
@testable import DulcetKit

// MARK: - The sidebar lights one row for every page

@Test
func sidebarKeepsBrowseLitOnEveryBrowseSection() {
    // Albums and Artists have no sidebar rows, and Recently Added's page is an album list: all three
    // sit under Browse and must light it, as the iPad's sidebar does.
    for route in [DulcetReaderRoute.section(.albums), .section(.artists), .albumList(.newest)] {
        #expect(DulcetSidebarSelection.current(destination: .browse, firstRoute: route) == .destination(.browse))
    }
    #expect(DulcetSidebarSelection.current(destination: .browse, firstRoute: nil) == .destination(.browse))
}

@Test
func sidebarKeepsTheRowsThatHaveTheirOwnPage() {
    #expect(DulcetSidebarSelection.current(destination: .library, firstRoute: .wishlist) == .wishlist)
    #expect(DulcetSidebarSelection.current(destination: .library, firstRoute: nil) == .destination(.library))
}

// MARK: - A server's stock "no photo" picture

/// A square image, drawn by `fill` for each pixel's grid cell in `side` x `side`.
private func square(_ pixels: Int, side: Int, _ value: (_ column: Int, _ row: Int) -> (UInt8, UInt8, UInt8)) -> CGImage {
    let context = CGContext(
        data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    )!
    let cell = CGFloat(pixels) / CGFloat(side)
    for row in 0..<side {
        for column in 0..<side {
            let (red, green, blue) = value(column, row)
            context.setFillColor(red: CGFloat(red) / 255, green: CGFloat(green) / 255, blue: CGFloat(blue) / 255, alpha: 1)
            // The context's origin is bottom left; the grid's first row is the picture's top.
            context.fill(CGRect(x: CGFloat(column) * cell, y: CGFloat(side - 1 - row) * cell, width: cell, height: cell))
        }
    }
    return context.makeImage()!
}

private func stockLike(_ pixels: Int) -> CGImage {
    square(pixels, side: 8) { column, row in
        let value = DulcetStockArtistImage.brightness[row * 8 + column]
        return (value, value, value)
    }
}

@Test
func recognizesTheStockPictureAtEverySizeItIsDecodedTo() {
    for pixels in [64, 128, 256, 300, 512] {
        #expect(DulcetStockArtistImage.measure(stockLike(pixels)), "at \(pixels)")
    }
}

@Test
func leavesRealPicturesAlone() {
    let stripes = square(256, side: 8) { column, row in (column + row) % 2 == 0 ? (130, 90, 200) : (90, 140, 110) }
    #expect(!DulcetStockArtistImage.measure(stripes), "colour")
    let paleGrey = square(256, side: 8) { _, _ in (235, 235, 235) }
    #expect(!DulcetStockArtistImage.measure(paleGrey), "flat grey has no star")
    let serverPlaceholder = square(256, side: 8) { _, _ in (0x2B, 0x2D, 0x33) }
    #expect(!DulcetStockArtistImage.measure(serverPlaceholder), "another server's dark placeholder")
    let portrait = square(256, side: 8) { column, row in
        let value = UInt8(30 + (column * 20 + row * 7) % 200)
        return (value, value, value)
    }
    #expect(!DulcetStockArtistImage.measure(portrait), "a black and white photograph")
    let tinted = square(256, side: 8) { column, row in
        let value = DulcetStockArtistImage.brightness[row * 8 + column]
        return (value, value, value &- 40)
    }
    #expect(!DulcetStockArtistImage.measure(tinted), "same shape, with colour")
    let wide = CGContext(data: nil, width: 300, height: 200, bitsPerComponent: 8, bytesPerRow: 0,
                         space: CGColorSpace(name: CGColorSpace.sRGB)!,
                         bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!.makeImage()!
    #expect(!DulcetStockArtistImage.measure(wide), "the stock picture is square")
}

// MARK: - Copy

@Test
func storageSizesAreDigitsAtZero() {
    #expect(DulcetOfflineLibrarySection.sizeText(0).contains("0"))
    #expect(!DulcetOfflineLibrarySection.sizeText(0).contains("Zero"))
}

@Test
func deleteConfirmationNamesThePlaylistAndDoesNotRepeatItself() {
    #expect(DulcetStrings.playlistDeleteQuestion("Road Trip") == "Delete \u{201C}Road Trip\u{201D}?")
    #expect(DulcetStrings.playlistDeleteQuestion(nil) == DulcetStrings.playlistDelete)
    #expect(DulcetStrings.playlistDeleteConfirm == "This also deletes it from your server.")
}
#endif
