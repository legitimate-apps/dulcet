import CoreGraphics
import Foundation
import ImageIO
import MediaPlayer
import Testing
import UniformTypeIdentifiers
@testable import DulcetKit

/// The system Now Playing entry: artwork from validated bytes, and transport writes the system
/// can extrapolate. Serialized because every instance writes the one process-wide info centre.
@Suite(.serialized)
@MainActor
struct SystemNowPlayingTests {
    @Test
    func validatedImageBytesBecomeSystemArtworkAndUndecodableBytesDoNot() throws {
        let controls = DulcetPlatformSystemMediaControls()
        let center = MPNowPlayingInfoCenter.default()
        defer { controls.clear() }

        controls.publish(state(artwork: try pngData(width: 64, height: 48)))
        let artwork = try #require(center.nowPlayingInfo?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork)
        #expect(artwork.bounds.size == CGSize(width: 64, height: 48))
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == "Fixture")
        // Nothing URL-shaped reaches the info dictionary: artwork arrives as decoded bytes only.
        #expect(!(center.nowPlayingInfo ?? [:]).values.contains { $0 is URL })

        controls.publish(state(artwork: Data("not an image".utf8)))
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyArtwork] == nil)
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == "Fixture")
    }

    @Test
    func transportUpdatesKeepArtworkAndSkipWritesTheSystemCanExtrapolate() throws {
        let clock = ManualUptime()
        let controls = DulcetPlatformSystemMediaControls(uptime: { clock.now })
        let center = MPNowPlayingInfoCenter.default()
        defer { controls.clear() }
        controls.publish(state(position: 10, artwork: try pngData(width: 8, height: 8)))

        // One second later, one second further on, at rate 1: what the system already shows.
        clock.now = 1
        center.nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 999.0 // sentinel
        controls.updateTransport(sessionID: .init("session"), position: 11, rate: 1, isPlaying: true)
        #expect(center.nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double == 999,
                "an on-track sample must not rewrite the dictionary")

        // A seek: the extrapolation is now wrong by 20 s, so it must be written.
        clock.now = 2
        controls.updateTransport(sessionID: .init("session"), position: 32, rate: 1, isPlaying: true)
        #expect(center.nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double == 32)
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyArtwork] != nil, "artwork survives")

        // Buffering: still playing to the listener, but the rate the system extrapolates is 0.
        controls.updateTransport(sessionID: .init("session"), position: 32, rate: 0, isPlaying: true)
        #expect(center.nowPlayingInfo?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 0)
        #expect(center.playbackState == .playing)

        // Pause always writes, even at an unchanged position.
        controls.updateTransport(sessionID: .init("session"), position: 32, rate: 1, isPlaying: false)
        #expect(center.nowPlayingInfo?[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 0)
        #expect(center.playbackState == .paused)

        // A stale session never writes.
        controls.updateTransport(sessionID: .init("stale"), position: 99, rate: 1, isPlaying: true)
        #expect(center.nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double == 32)
    }

    private func state(position: TimeInterval = 10, artwork: Data?) -> DulcetSystemNowPlayingState {
        DulcetSystemNowPlayingState(
            sessionID: .init("session"),
            metadata: .init(title: "Fixture", artist: "Artist", albumTitle: "Album"),
            duration: 60,
            position: position,
            rate: 1,
            isPlaying: true,
            seekability: .seekable,
            remoteCapabilities: .init(allowsNext: true, allowsPrevious: true),
            artworkImageData: artwork
        )
    }

    private func pngData(width: Int, height: Int) throws -> Data {
        let context = try #require(CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.8, green: 0.2, blue: 0.4, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try #require(context.makeImage())
        let data = NSMutableData()
        let destination = try #require(CGImageDestinationCreateWithData(
            data, UTType.png.identifier as CFString, 1, nil
        ))
        CGImageDestinationAddImage(destination, image, nil)
        #expect(CGImageDestinationFinalize(destination))
        return data as Data
    }
}

private final class ManualUptime: @unchecked Sendable {
    private let lock = NSLock()
    private var value: TimeInterval = 0
    var now: TimeInterval {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}
