import Foundation
import MediaPlayer

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Turns artwork bytes that already passed the core's image validation into the system's Now
/// Playing artwork. The input is bytes, never a URL: every artwork URL this app can build carries
/// the account's credentials in its query string, and the system would fetch and log it.
enum DulcetNowPlayingArtworkDecoder {
    static func artwork(from data: Data) -> MPMediaItemArtwork? {
        #if canImport(UIKit)
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else {
            return nil
        }
        let box = DulcetArtworkImageBox(image)
        return MPMediaItemArtwork(boundsSize: image.size) { _ in box.image }
        #elseif canImport(AppKit)
        guard let image = NSImage(data: data), image.size.width > 0, image.size.height > 0 else {
            return nil
        }
        let box = DulcetArtworkImageBox(image)
        return MPMediaItemArtwork(boundsSize: image.size) { _ in box.image }
        #else
        return nil
        #endif
    }
}

/// The request handler runs on a system queue. The image is immutable after decoding, so sharing
/// it across that boundary is safe even where the platform type is not declared `Sendable`.
private final class DulcetArtworkImageBox: @unchecked Sendable {
    #if canImport(UIKit)
    let image: UIImage
    init(_ image: UIImage) { self.image = image }
    #elseif canImport(AppKit)
    let image: NSImage
    init(_ image: NSImage) { self.image = image }
    #endif
}
