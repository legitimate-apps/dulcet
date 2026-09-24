import Foundation
import MediaPlayer

public enum DulcetRemotePlaybackCommand: Equatable, Sendable {
    case play(sessionID: DulcetPlaybackSessionID)
    case pause(sessionID: DulcetPlaybackSessionID)
    case toggle(sessionID: DulcetPlaybackSessionID)
    case next(sessionID: DulcetPlaybackSessionID)
    case previous(sessionID: DulcetPlaybackSessionID)
    case seek(sessionID: DulcetPlaybackSessionID, position: TimeInterval)
    case rating(sessionID: DulcetPlaybackSessionID, value: Double)
    case favourite(sessionID: DulcetPlaybackSessionID, isFavourite: Bool)

    public var sessionID: DulcetPlaybackSessionID {
        switch self {
        case let .play(sessionID), let .pause(sessionID), let .toggle(sessionID),
             let .next(sessionID), let .previous(sessionID), let .seek(sessionID, _),
             let .rating(sessionID, _), let .favourite(sessionID, _):
            sessionID
        }
    }
}

public struct DulcetRemoteCommandCapabilities: Equatable, Sendable {
    public let allowsNext: Bool
    public let allowsPrevious: Bool
    public let allowsRating: Bool
    public let allowsFavourite: Bool
    public let isFavourite: Bool

    public init(
        allowsNext: Bool = false,
        allowsPrevious: Bool = false,
        allowsRating: Bool = false,
        allowsFavourite: Bool = false,
        isFavourite: Bool = false
    ) {
        self.allowsNext = allowsNext
        self.allowsPrevious = allowsPrevious
        self.allowsRating = allowsRating
        self.allowsFavourite = allowsFavourite
        self.isFavourite = isFavourite
    }
}

public struct DulcetSystemNowPlayingState: Equatable, Sendable {
    public let sessionID: DulcetPlaybackSessionID
    public let metadata: DulcetNowPlayingMetadata
    public let duration: TimeInterval?
    public let position: TimeInterval
    public let rate: Double
    public let isPlaying: Bool
    public let seekability: DulcetPlaybackSeekability
    public let remoteCapabilities: DulcetRemoteCommandCapabilities
    /// Encoded image bytes that already passed the core's artwork validation. Never a URL: the
    /// only artwork URLs this app has carry credentials in their query string.
    public let artworkImageData: Data?

    public init(
        sessionID: DulcetPlaybackSessionID,
        metadata: DulcetNowPlayingMetadata,
        duration: TimeInterval?,
        position: TimeInterval,
        rate: Double,
        isPlaying: Bool,
        seekability: DulcetPlaybackSeekability,
        remoteCapabilities: DulcetRemoteCommandCapabilities,
        artworkImageData: Data? = nil
    ) {
        self.sessionID = sessionID
        self.metadata = metadata
        self.duration = duration
        self.position = position
        self.rate = rate
        self.isPlaying = isPlaying
        self.seekability = seekability
        self.remoteCapabilities = remoteCapabilities
        self.artworkImageData = artworkImageData
    }
}

public protocol DulcetRemotePlaybackCommandRouting: AnyObject, Sendable {
    func handleRemotePlaybackCommand(_ command: DulcetRemotePlaybackCommand) -> Bool
}

public protocol DulcetSystemMediaControlling: AnyObject, Sendable {
    func setCommandHandler(
        _ handler: (@Sendable (DulcetRemotePlaybackCommand) -> Bool)?
    )
    func publish(_ state: DulcetSystemNowPlayingState)
    func updateTransport(
        sessionID: DulcetPlaybackSessionID,
        position: TimeInterval,
        rate: Double,
        isPlaying: Bool
    )
    func clear()
}

public final class DulcetPlatformSystemMediaControls: DulcetSystemMediaControlling,
    @unchecked Sendable {
    private let commandCenter: MPRemoteCommandCenter
    private let nowPlayingCenter: MPNowPlayingInfoCenter
    private let lock = NSLock()
    private var handler: (@Sendable (DulcetRemotePlaybackCommand) -> Bool)?
    private var state: DulcetSystemNowPlayingState?
    private var transportAnchor: TransportAnchor?
    private var decodedArtwork: (data: Data, artwork: MPMediaItemArtwork)?
    private var targets: [(MPRemoteCommand, Any)] = []
    private let uptime: @Sendable () -> TimeInterval

    private struct TransportAnchor {
        let sessionID: DulcetPlaybackSessionID
        let position: TimeInterval
        let rate: Double
        let isPlaying: Bool
        let uptime: TimeInterval
    }

    static let extrapolationTolerance: TimeInterval = 0.75

    public init(
        commandCenter: MPRemoteCommandCenter = .shared(),
        nowPlayingCenter: MPNowPlayingInfoCenter = .default(),
        uptime: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }
    ) {
        self.commandCenter = commandCenter
        self.nowPlayingCenter = nowPlayingCenter
        self.uptime = uptime
        disableUnsupportedCommands()
        applyCommandAvailability(nil)
    }

    deinit {
        targets.forEach { command, target in command.removeTarget(target) }
    }

    public func setCommandHandler(
        _ handler: (@Sendable (DulcetRemotePlaybackCommand) -> Bool)?
    ) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }

    public func publish(_ state: DulcetSystemNowPlayingState) {
        lock.lock()
        self.state = state
        transportAnchor = TransportAnchor(
            sessionID: state.sessionID,
            position: state.position,
            rate: state.isPlaying ? state.rate : 0,
            isPlaying: state.isPlaying,
            uptime: uptime()
        )
        lock.unlock()
        installTargets(for: state.sessionID)
        applyCommandAvailability(state)
        nowPlayingCenter.nowPlayingInfo = nowPlayingInfo(for: state)
        nowPlayingCenter.playbackState = state.isPlaying ? .playing : .paused
    }

    public func updateTransport(
        sessionID: DulcetPlaybackSessionID,
        position: TimeInterval,
        rate: Double,
        isPlaying: Bool
    ) {
        lock.lock()
        guard let previous = state, previous.sessionID == sessionID else {
            lock.unlock()
            return
        }
        let updated = DulcetSystemNowPlayingState(
            sessionID: previous.sessionID,
            metadata: previous.metadata,
            duration: previous.duration,
            position: position,
            rate: rate,
            isPlaying: isPlaying,
            seekability: previous.seekability,
            remoteCapabilities: previous.remoteCapabilities,
            artworkImageData: previous.artworkImageData
        )
        state = updated
        let now = uptime()
        let effectiveRate = isPlaying ? rate : 0
        // The system extrapolates elapsed time from the last write and its rate, so rewriting
        // the dictionary on every 0.5 s sample is redundant work for every observer of the
        // Now Playing centre. Write when the transport changed or the extrapolation drifted.
        if let anchor = transportAnchor,
           anchor.sessionID == sessionID,
           anchor.isPlaying == isPlaying,
           anchor.rate == effectiveRate,
           abs(anchor.position + (now - anchor.uptime) * effectiveRate - position)
            < Self.extrapolationTolerance {
            lock.unlock()
            return
        }
        transportAnchor = TransportAnchor(
            sessionID: sessionID,
            position: position,
            rate: effectiveRate,
            isPlaying: isPlaying,
            uptime: now
        )
        lock.unlock()
        var information = nowPlayingCenter.nowPlayingInfo ?? nowPlayingInfo(for: updated)
        information[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        information[MPNowPlayingInfoPropertyPlaybackRate] = effectiveRate
        information[MPNowPlayingInfoPropertyDefaultPlaybackRate] = rate > 0 ? rate : 1
        nowPlayingCenter.nowPlayingInfo = information
        nowPlayingCenter.playbackState = isPlaying ? .playing : .paused
    }

    public func clear() {
        lock.lock()
        state = nil
        transportAnchor = nil
        lock.unlock()
        removeTargets()
        applyCommandAvailability(nil)
        nowPlayingCenter.nowPlayingInfo = nil
        nowPlayingCenter.playbackState = .stopped
    }

    private func installTargets(for sessionID: DulcetPlaybackSessionID) {
        removeTargets()
        addTarget(commandCenter.playCommand, sessionID: sessionID) { sessionID, _ in .play(sessionID: sessionID) }
        addTarget(commandCenter.pauseCommand, sessionID: sessionID) { sessionID, _ in .pause(sessionID: sessionID) }
        addTarget(commandCenter.togglePlayPauseCommand, sessionID: sessionID) { sessionID, _ in
            .toggle(sessionID: sessionID)
        }
        addTarget(commandCenter.nextTrackCommand, sessionID: sessionID) { sessionID, _ in .next(sessionID: sessionID) }
        addTarget(commandCenter.previousTrackCommand, sessionID: sessionID) { sessionID, _ in
            .previous(sessionID: sessionID)
        }
        addTarget(commandCenter.changePlaybackPositionCommand, sessionID: sessionID) { sessionID, event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return nil }
            return .seek(sessionID: sessionID, position: event.positionTime)
        }
        // Rating and like are deliberately NOT registered. Favourites do not exist yet (spec
        // §18.3's outbox is unbuilt), and a registered command that answers "failed" puts a
        // heart on the lock screen that does nothing. They come back with the feature.
    }

    private func addTarget(
        _ command: MPRemoteCommand,
        sessionID: DulcetPlaybackSessionID,
        makeCommand: @escaping @Sendable (
            DulcetPlaybackSessionID,
            MPRemoteCommandEvent
        ) -> DulcetRemotePlaybackCommand?
    ) {
        let target = command.addTarget { [weak self] event in
            guard let self else { return .commandFailed }
            self.lock.lock()
            let handler = self.handler
            self.lock.unlock()
            guard let handler, let routed = makeCommand(sessionID, event) else {
                return .noActionableNowPlayingItem
            }
            return handler(routed) ? .success : .commandFailed
        }
        targets.append((command, target))
    }

    private func removeTargets() {
        targets.forEach { command, target in command.removeTarget(target) }
        targets.removeAll()
    }

    private func applyCommandAvailability(_ state: DulcetSystemNowPlayingState?) {
        let hasItem = state != nil
        commandCenter.playCommand.isEnabled = hasItem
        commandCenter.pauseCommand.isEnabled = hasItem
        commandCenter.togglePlayPauseCommand.isEnabled = hasItem
        commandCenter.nextTrackCommand.isEnabled = state?.remoteCapabilities.allowsNext == true
        commandCenter.previousTrackCommand.isEnabled = state?.remoteCapabilities.allowsPrevious == true
        commandCenter.changePlaybackPositionCommand.isEnabled = state?.seekability == .seekable
        commandCenter.ratingCommand.isEnabled = false
        commandCenter.likeCommand.isEnabled = false
    }

    private func disableUnsupportedCommands() {
        commandCenter.stopCommand.isEnabled = false
        commandCenter.changePlaybackRateCommand.isEnabled = false
        commandCenter.changeRepeatModeCommand.isEnabled = false
        commandCenter.changeShuffleModeCommand.isEnabled = false
        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
        commandCenter.seekForwardCommand.isEnabled = false
        commandCenter.seekBackwardCommand.isEnabled = false
        commandCenter.enableLanguageOptionCommand.isEnabled = false
        commandCenter.disableLanguageOptionCommand.isEnabled = false
        commandCenter.dislikeCommand.isEnabled = false
        commandCenter.bookmarkCommand.isEnabled = false
    }

    private func nowPlayingInfo(for state: DulcetSystemNowPlayingState) -> [String: Any] {
        var information: [String: Any] = [
            MPMediaItemPropertyTitle: state.metadata.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: state.position,
            MPNowPlayingInfoPropertyPlaybackRate: state.isPlaying ? state.rate : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: state.rate,
        ]
        if let artist = state.metadata.artist { information[MPMediaItemPropertyArtist] = artist }
        if let album = state.metadata.albumTitle { information[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = state.duration { information[MPMediaItemPropertyPlaybackDuration] = duration }
        if let artwork = artwork(for: state.artworkImageData) {
            information[MPMediaItemPropertyArtwork] = artwork
        }
        return information
    }

    /// Decodes once per distinct image. Returning nil for bytes that do not decode keeps a
    /// corrupt cache entry off the lock screen rather than showing a broken tile.
    private func artwork(for data: Data?) -> MPMediaItemArtwork? {
        guard let data else { return nil }
        lock.lock()
        if let cached = decodedArtwork, cached.data == data {
            lock.unlock()
            return cached.artwork
        }
        lock.unlock()
        guard let artwork = DulcetNowPlayingArtworkDecoder.artwork(from: data) else { return nil }
        lock.lock()
        decodedArtwork = (data, artwork)
        lock.unlock()
        return artwork
    }
}
