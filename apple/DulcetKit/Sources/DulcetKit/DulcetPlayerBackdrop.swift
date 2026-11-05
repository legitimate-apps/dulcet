import SwiftUI
import CoreGraphics

/// An opaque field of distinct cover colours, shared by artwork, queue and lyrics modes.
/// White controls keep their contrast in both appearances without a grey or pastel scrim.
struct DulcetPlayerBackdrop: View {
    let artwork: DulcetArtwork
    @Environment(DulcetPresentationStore.self) private var store
    @State private var colours: [Color] = []
    @State private var operation: (any DulcetArtworkFetchOperation)?
    @State private var generation = 0

    var body: some View {
        let palette = colours.isEmpty
            ? [Color(red: 0.08, green: 0.16, blue: 0.25), Color(red: 0.18, green: 0.09, blue: 0.24)]
            : colours
        LinearGradient(colors: palette, startPoint: .topLeading, endPoint: .bottomTrailing)
            .overlay {
                RadialGradient(colors: [palette[0], .clear], center: .topTrailing,
                    startRadius: 0, endRadius: 700)
            }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear(perform: load)
        .onChange(of: artwork) { _, _ in load() }
        .onDisappear {
            generation += 1
            operation?.cancel()
            operation = nil
        }
    }

    private func load() {
        generation += 1
        let requestedGeneration = generation
        operation?.cancel()
        operation = nil
        colours = []
        guard let reference = artwork.remoteReference else { return }
        let bucket = DulcetArtworkSizeBucket.containing(pixelSize: 64)
        if let image = store.cachedArtwork(reference, sizeBucket: bucket) {
            colours = Self.sample(image)
            return
        }
        operation = store.loadArtwork(reference, sizeBucket: bucket) { result in
            guard requestedGeneration == generation else { return }
            operation = nil
            if case let .loaded(data) = result,
               let image = DulcetArtworkMemoryCache.decode(data, maximumPixelSize: 64) {
                colours = Self.sample(image)
            }
        }
    }

    private static func sample(_ image: CGImage) -> [Color] {
        var pixels = [UInt8](repeating: 0, count: 256)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: 8, height: 8,
                bitsPerComponent: 8, bytesPerRow: 32,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: 8, height: 8))
            return true
        }
        guard drawn else { return [] }
        // Start with a colourful pixel, then choose the most distant colour rather than
        // several neighbouring pixels from the same hue. Ignore transparent and neutral pixels.
        let candidates = stride(from: 0, to: 256, by: 4).compactMap { offset -> [Double]? in
            let alpha = Double(pixels[offset + 3])
            guard alpha > 127 else { return nil }
            let rgb = (0..<3).map { min(1, Double(pixels[offset + $0]) / alpha) }
            guard rgb.max()! - rgb.min()! > 0.08 else { return nil }
            return rgb
        }
        guard let first = candidates.max(by: { $0.max()! - $0.min()! < $1.max()! - $1.min()! }) else { return [] }
        let second = candidates.max { lhs, rhs in
            zip(lhs, first).reduce(0) { $0 + pow($1.0 - $1.1, 2) }
                < zip(rhs, first).reduce(0) { $0 + pow($1.0 - $1.1, 2) }
        } ?? first
        return [first, second].map { rgb in
            // Preserve chroma and hue while limiting relative luminance for white text.
            // The colours are opaque, including under Reduce Transparency.
            var values = rgb
            func linear(_ value: Double) -> Double {
                value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
            }
            while 0.2126 * linear(values[0]) + 0.7152 * linear(values[1]) + 0.0722 * linear(values[2]) > 0.14 {
                values = values.map { $0 * 0.97 }
            }
            return Color(.sRGB, red: values[0], green: values[1], blue: values[2], opacity: 1)
        }
    }
}
