package com.legitimateapps.dulcet.server.db

/**
 * Forward-only migrations keyed by `PRAGMA user_version`. Catalog tables (track, album, artist and
 * their joins) are derived from the files and can be rebuilt by a full scan; user tables
 * (annotation, library_item, playlist*, user) are not, so they never cascade from catalog rows: an
 * item that disappears and comes back keeps its stars, ratings, play counts and membership.
 */
object Schema {
    private val migrations: List<String> = listOf(
        """
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

        CREATE TABLE music_folder (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            path TEXT NOT NULL
        );

        CREATE TABLE track (
            id TEXT PRIMARY KEY,
            path TEXT NOT NULL UNIQUE,
            folder_id INTEGER NOT NULL,
            parent_dir TEXT NOT NULL,
            mtime INTEGER NOT NULL,
            size INTEGER NOT NULL,
            title TEXT NOT NULL,
            sort_title TEXT,
            album TEXT NOT NULL,
            album_id TEXT NOT NULL,
            artist TEXT NOT NULL,
            artist_id TEXT NOT NULL,
            album_artist TEXT NOT NULL,
            album_artist_id TEXT NOT NULL,
            track_no INTEGER,
            disc_no INTEGER,
            disc_subtitle TEXT,
            year INTEGER,
            release_date TEXT,
            genre TEXT,
            duration REAL NOT NULL,
            bitrate INTEGER NOT NULL,
            sample_rate INTEGER,
            channels INTEGER,
            bit_depth INTEGER,
            codec TEXT,
            suffix TEXT NOT NULL,
            content_type TEXT NOT NULL,
            compilation INTEGER NOT NULL DEFAULT 0,
            mbz_track_id TEXT,
            mbz_recording_id TEXT,
            mbz_album_id TEXT,
            mbz_artist_id TEXT,
            mbz_album_artist_id TEXT,
            has_embedded_art INTEGER NOT NULL DEFAULT 0,
            lyrics_sidecar TEXT,
            embedded_lyrics TEXT,
            comment TEXT,
            bpm INTEGER,
            rg_track_gain REAL,
            rg_track_peak REAL,
            rg_album_gain REAL,
            rg_album_peak REAL,
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL,
            search TEXT NOT NULL
        );
        CREATE INDEX track_album ON track(album_id, disc_no, track_no);
        CREATE INDEX track_by_artist ON track(artist_id);
        CREATE INDEX track_mbz ON track(mbz_recording_id);

        CREATE TABLE track_artist (
            track_id TEXT NOT NULL,
            role TEXT NOT NULL,
            artist_id TEXT NOT NULL,
            name TEXT NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY (track_id, role, artist_id)
        );
        CREATE INDEX track_artist_artist ON track_artist(artist_id, role);

        CREATE TABLE track_genre (
            track_id TEXT NOT NULL,
            genre TEXT NOT NULL,
            PRIMARY KEY (track_id, genre)
        );

        CREATE TABLE album (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            sort_name TEXT NOT NULL,
            album_artist TEXT NOT NULL,
            artist_id TEXT NOT NULL,
            year INTEGER,
            genre TEXT,
            compilation INTEGER NOT NULL,
            song_count INTEGER NOT NULL,
            duration REAL NOT NULL,
            size INTEGER NOT NULL,
            created INTEGER NOT NULL,
            mbz_album_id TEXT,
            art_track_id TEXT,
            folder_art TEXT,
            search TEXT NOT NULL
        );

        CREATE TABLE album_artist (
            album_id TEXT NOT NULL,
            artist_id TEXT NOT NULL,
            name TEXT NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY (album_id, artist_id)
        );
        CREATE INDEX album_artist_artist ON album_artist(artist_id);

        CREATE TABLE artist (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            sort_name TEXT NOT NULL,
            mbz_artist_id TEXT,
            album_count INTEGER NOT NULL,
            song_count INTEGER NOT NULL,
            search TEXT NOT NULL
        );

        CREATE TABLE user (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            password_enc BLOB NOT NULL,
            is_admin INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL
        );

        CREATE TABLE annotation (
            user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
            item_type TEXT NOT NULL,
            item_id TEXT NOT NULL,
            starred INTEGER,
            rating INTEGER NOT NULL DEFAULT 0,
            play_count INTEGER NOT NULL DEFAULT 0,
            played INTEGER,
            PRIMARY KEY (user_id, item_type, item_id)
        );

        CREATE TABLE library_item (
            user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
            item_type TEXT NOT NULL,
            item_id TEXT NOT NULL,
            added INTEGER NOT NULL,
            PRIMARY KEY (user_id, item_type, item_id)
        );
        CREATE INDEX library_item_added ON library_item(user_id, item_type, added);

        CREATE TABLE playlist (
            id TEXT PRIMARY KEY,
            owner_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            comment TEXT,
            public INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL,
            changed INTEGER NOT NULL
        );

        CREATE TABLE playlist_track (
            playlist_id TEXT NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            track_id TEXT NOT NULL,
            PRIMARY KEY (playlist_id, position)
        );

        CREATE TABLE lyrics_cache (
            track_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            status TEXT NOT NULL,
            synced_lrc TEXT,
            plain TEXT,
            fetched INTEGER NOT NULL
        );

        CREATE TABLE now_playing (
            user_id TEXT NOT NULL,
            client TEXT NOT NULL,
            track_id TEXT NOT NULL,
            at INTEGER NOT NULL,
            PRIMARY KEY (user_id, client)
        );
        """.trimIndent(),
    )

    fun migrate(db: Db) {
        db.write {
            val current = long("PRAGMA user_version").toInt()
            for (version in current until migrations.size) {
                migrations[version].split(";").map(String::trim).filter(String::isNotEmpty).forEach { execute(it) }
                execute("PRAGMA user_version = ${version + 1}")
            }
        }
    }
}
