import AppIntents
import DulcetKit

// Compiled into the app and its widget extension. AudioPlaybackIntent runs in the app process.
enum DulcetMusicKind: String, AppEnum {
    case album, artist, playlist, song
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Music type"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .album: "Album", .artist: "Artist", .playlist: "Playlist", .song: "Song"
    ]
    var readerKind: DulcetReaderItemKind {
        switch self { case .album: .album; case .artist: .artist; case .playlist: .playlist; case .song: .track }
    }
}
struct DulcetPlayMusicIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Music"
    static let description = IntentDescription("Play an album, artist, playlist or song from your connected server by its exact name.")
    @Parameter(title: "Music type", default: .album) var kind: DulcetMusicKind
    @Parameter(title: "Name") var name: String
    static var parameterSummary: some ParameterSummary { Summary("Play \(\.$kind) named \(\.$name)") }
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.play(kind: kind.readerKind, name: name)
        return .result()
    }
}
struct DulcetPlayAlbumIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Album"
    @Parameter(title: "Album name") var name: String
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.play(kind: .album, name: name)
        return .result()
    }
}
struct DulcetPlayArtistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Artist"
    @Parameter(title: "Artist name") var name: String
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.play(kind: .artist, name: name)
        return .result()
    }
}
struct DulcetPlayPlaylistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Playlist"
    @Parameter(title: "Playlist name") var name: String
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.play(kind: .playlist, name: name)
        return .result()
    }
}
struct DulcetPlaySongIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Song"
    @Parameter(title: "Song name") var name: String
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.play(kind: .track, name: name)
        return .result()
    }
}
struct DulcetResumeIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Resume Dulcet"
    @MainActor func perform() async throws -> some IntentResult { try await DulcetSystemActions.shared.transport(.play); return .result() }
}
struct DulcetTogglePlaybackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play or Pause Dulcet"
    @MainActor func perform() async throws -> some IntentResult { try await DulcetSystemActions.shared.transport(.toggle); return .result() }
}
struct DulcetFavouritesIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play My Favourites"
    @MainActor func perform() async throws -> some IntentResult { try await DulcetSystemActions.shared.playFavourites(); return .result() }
}
struct DulcetShuffleLibraryIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Shuffle My Library"
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.shuffleLibrary()
        return .result()
    }
}
struct DulcetFavouriteCurrentIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Favourite Current Song"
    @MainActor func perform() async throws -> some IntentResult { try await DulcetSystemActions.shared.favouriteCurrent(); return .result() }
}
struct DulcetAddCurrentToPlaylistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Add Current Song to Playlist"
    @Parameter(title: "Playlist name") var name: String
    @MainActor func perform() async throws -> some IntentResult { try await DulcetSystemActions.shared.addCurrent(toPlaylist: name); return .result() }
}
struct DulcetShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: DulcetPlayAlbumIntent(), phrases: ["Play an album in \(.applicationName)"], shortTitle: "Play Album", systemImageName: "square.stack")
        AppShortcut(intent: DulcetPlayArtistIntent(), phrases: ["Play an artist in \(.applicationName)"], shortTitle: "Play Artist", systemImageName: "person")
        AppShortcut(intent: DulcetPlayPlaylistIntent(), phrases: ["Play a playlist in \(.applicationName)"], shortTitle: "Play Playlist", systemImageName: "music.note.list")
        AppShortcut(intent: DulcetPlaySongIntent(), phrases: ["Play a song in \(.applicationName)"], shortTitle: "Play Song", systemImageName: "music.note")
        AppShortcut(intent: DulcetResumeIntent(), phrases: ["Resume \(.applicationName)"], shortTitle: "Resume", systemImageName: "play.fill")
        AppShortcut(intent: DulcetShuffleLibraryIntent(), phrases: ["Shuffle my library in \(.applicationName)"], shortTitle: "Shuffle Library", systemImageName: "shuffle")
        AppShortcut(intent: DulcetFavouritesIntent(), phrases: ["Play my favourites in \(.applicationName)"], shortTitle: "Play Favourites", systemImageName: "heart.fill")
        AppShortcut(intent: DulcetAddCurrentToPlaylistIntent(), phrases: ["Add this song to a playlist in \(.applicationName)"], shortTitle: "Add to Playlist", systemImageName: "text.badge.plus")
        AppShortcut(intent: DulcetFavouriteCurrentIntent(), phrases: ["Favourite this song in \(.applicationName)"], shortTitle: "Favourite Song", systemImageName: "heart")
    }
}

struct DulcetPlayRecentIntent: AudioPlaybackIntent {
    static var isDiscoverable: Bool { false }
    static let title: LocalizedStringResource = "Play Recent Song"
    @Parameter(title: "Provider") var provider: String
    @Parameter(title: "Song ID") var songID: String
    @Parameter(title: "Song name") var name: String
    init() {}
    init(provider: String, songID: String, name: String) {
        self.provider = provider; self.songID = songID; self.name = name
    }
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.playRecent(provider: provider, rawID: songID, name: name)
        return .result()
    }
}

struct DulcetPlayPinnedIntent: AudioPlaybackIntent {
    static var isDiscoverable: Bool { false }
    static let title: LocalizedStringResource = "Play Pinned Music"
    @Parameter(title: "Provider") var provider: String
    @Parameter(title: "Item ID") var itemID: String
    @Parameter(title: "Item kind") var kind: String
    init() {}
    init(provider: String, itemID: String, kind: String) {
        self.provider = provider; self.itemID = itemID; self.kind = kind
    }
    @MainActor func perform() async throws -> some IntentResult {
        try await DulcetSystemActions.shared.playPinned(provider: provider, rawID: itemID, kind: kind)
        return .result()
    }
}
