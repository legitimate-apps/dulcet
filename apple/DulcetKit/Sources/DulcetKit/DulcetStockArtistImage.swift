#if os(macOS) || os(iOS) || os(tvOS)
import CoreGraphics
import Foundation

/// Recognizes the picture a server sends for an artist it has no photo of.
///
/// OpenSubsonic gives every artist a `coverArt` id, and a Navidrome answers it for an artist with no
/// image with one stock file: a pale grey square with a white star, at every requested size
/// (`resources/artist-placeholder.webp`, OBSERVED in 0.63.2, 300 x 300). Nothing in the response says
/// so, and ids are opaque, so the only evidence is the picture. Each is compared by its brightness on
/// a coarse grid, and only one with no colour at all can match.
enum DulcetStockArtistImage {
    /// The stock picture's brightness (0-255) on an 8 x 8 grid, read from the file above.
    static let brightness: [UInt8] = [
        235, 235, 235, 235, 235, 235, 235, 235,
        235, 235, 235, 237, 237, 235, 235, 235,
        235, 235, 235, 244, 244, 235, 235, 235,
        235, 239, 251, 254, 254, 251, 239, 235,
        235, 235, 241, 255, 255, 241, 235, 235,
        235, 235, 244, 248, 248, 243, 235, 235,
        235, 235, 237, 235, 235, 237, 235, 235,
        235, 235, 235, 235, 235, 235, 235, 235,
    ]
    private static let side = 8
    /// Per cell, in 0-255: room for a resize and a re-encode, not for a different picture.
    private static let brightnessTolerance = 4
    private static let colourTolerance = 6

    /// NSCache is safe across threads.
    nonisolated(unsafe) private static let verdicts: NSCache<AnyObject, NSNumber> = {
        let cache = NSCache<AnyObject, NSNumber>()
        cache.countLimit = 512
        return cache
    }()

    /// Asked each time an avatar draws, so a decoded image is measured once.
    static func isStock(_ image: CGImage) -> Bool {
        if let known = verdicts.object(forKey: image) { return known.boolValue }
        let verdict = measure(image)
        verdicts.setObject(NSNumber(value: verdict), forKey: image)
        return verdict
    }

    static func measure(_ image: CGImage) -> Bool {
        guard image.width == image.height else { return false }
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(
                data: bytes.baseAddress, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return false }
        for cell in 0..<(side * side) {
            let red = Int(pixels[cell * 4]), green = Int(pixels[cell * 4 + 1]), blue = Int(pixels[cell * 4 + 2])
            if max(red, green, blue) - min(red, green, blue) > colourTolerance { return false }
            if abs(green - Int(brightness[cell])) > brightnessTolerance { return false }
        }
        return true
    }
}
#endif
