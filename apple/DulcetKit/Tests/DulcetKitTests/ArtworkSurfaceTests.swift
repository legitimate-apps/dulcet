#if os(macOS)
import AppKit
import CoreGraphics
import SwiftUI
import Testing
@testable import DulcetKit

/// A server cover with transparent parts -- Navidrome's own record-without-a-sleeve placeholder
/// has clear corners -- was drawn over Dulcet's coloured placeholder, which showed through and
/// read as a broken load. A loaded cover's transparent parts now show the destination surface,
/// without a backing card or the placeholder palette.
@Test @MainActor
func aLoadedCoverWithTransparentCornersDoesNotShowThePlaceholderThrough() throws {
    let artwork = DulcetArtwork(seed: "clear-corners", palette: .indigoCoral)
    let clearCover = try transparentImage(side: 32)

    let placeholder = try surfaceCorner(artwork: artwork, image: nil)
    let loaded = try surfaceCorner(artwork: artwork, image: clearCover)
    let backing = try backingCorner()

    // Control: the placeholder's corner is the palette's saturated colour.
    #expect(placeholder.saturation > 0.3, "control: placeholder corner \(placeholder)")
    #expect(loaded.isClose(to: backing), "loaded corner \(loaded) is not the backing \(backing)")
    #expect(!loaded.isClose(to: placeholder), "the placeholder shows through the loaded cover")
}

private struct Pixel: CustomStringConvertible {
    var r: Double, g: Double, b: Double
    var saturation: Double {
        let high = max(r, g, b), low = min(r, g, b)
        return high == 0 ? 0 : (high - low) / high
    }
    func isClose(to other: Pixel) -> Bool {
        abs(r - other.r) < 0.03 && abs(g - other.g) < 0.03 && abs(b - other.b) < 0.03
    }
    var description: String { String(format: "(%.2f, %.2f, %.2f)", r, g, b) }
}

@MainActor
private func surfaceCorner(artwork: DulcetArtwork, image: Image?) throws -> Pixel {
    try cornerPixel(of: DulcetArtworkSurface(artwork: artwork, size: 64, image: image).background(Color.white))
}

@MainActor
private func backingCorner() throws -> Pixel {
    try cornerPixel(of: Rectangle().fill(Color.white).frame(width: 64, height: 64))
}

@MainActor
private func cornerPixel(of view: some View) throws -> Pixel {
    let renderer = ImageRenderer(content: view.environment(\.colorScheme, .light))
    renderer.scale = 1
    let image = try #require(renderer.cgImage)
    let width = image.width, height = image.height
    var bytes = [UInt8](repeating: 0, count: width * height * 4)
    let context = try #require(CGContext(
        data: &bytes, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ))
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    // Top-leading corner, inside by a few points (the placeholder's own corner is rounded).
    let offset = (6 * width + 6) * 4
    return Pixel(r: Double(bytes[offset]) / 255, g: Double(bytes[offset + 1]) / 255,
                 b: Double(bytes[offset + 2]) / 255)
}

private func transparentImage(side: Int) throws -> Image {
    let context = try #require(CGContext(
        data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ))
    context.clear(CGRect(x: 0, y: 0, width: side, height: side))
    // A small opaque disc in the middle, as a record without a sleeve.
    context.setFillColor(CGColor(gray: 0.1, alpha: 1))
    context.fillEllipse(in: CGRect(x: side / 4, y: side / 4, width: side / 2, height: side / 2))
    let cgImage = try #require(context.makeImage())
    return Image(decorative: cgImage, scale: 1)
}
#endif
