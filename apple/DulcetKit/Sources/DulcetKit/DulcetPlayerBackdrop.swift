import SwiftUI
import CoreGraphics

/// A quiet field of sampled cover colours shared by artwork, queue and lyrics modes.
/// Sampling removes recognisable shapes, so a cover's central motif never becomes a grey blob.
/// The window-colour scrim keeps semantic labels on a predictable light or dark surface.
struct DulcetPlayerBackdrop: View {
    let artwork: DulcetArtwork
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.dulcetReduceTransparencyOverride) private var reduceTransparencyOverride
    @State private var colours: [Color] = []
    @State private var operation: (any DulcetArtworkFetchOperation)?
    @State private var generation = 0

    var body: some View {
        ZStack {
            Color.dulcetWindow
            if !(reduceTransparencyOverride ?? reduceTransparency) {
                LinearGradient(
                    colors: colours.isEmpty ? artwork.palette.colors : colours,
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Color.dulcetWindow.opacity(0.84)
            }
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
        var pixels = [UInt8](repeating: 0, count: 16)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: 2, height: 2,
                bitsPerComponent: 8, bytesPerRow: 8,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: 2, height: 2))
            return true
        }
        guard drawn else { return [] }
        return stride(from: 0, to: 16, by: 4).map { offset in
            let alpha = Double(pixels[offset + 3])
            guard alpha > 0 else { return Color.dulcetWindow }
            return Color(
                .sRGB,
                red: Double(pixels[offset]) / alpha,
                green: Double(pixels[offset + 1]) / alpha,
                blue: Double(pixels[offset + 2]) / alpha,
                opacity: 1
            )
        }
    }
}
