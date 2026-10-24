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
        """
        CREATE TABLE track_identity (path TEXT PRIMARY KEY, track_id TEXT NOT NULL, fingerprint TEXT);
        INSERT INTO track_identity(path, track_id) SELECT path, id FROM track;
        CREATE INDEX track_identity_fingerprint ON track_identity(fingerprint);
        """.trimIndent(),
        "ALTER TABLE track ADD COLUMN mbz_release_group_id TEXT;",
    )

    private fun installJournal(s: Db.Session) = with(s) {
        execute("CREATE TABLE IF NOT EXISTS api_journal (seq INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,id TEXT NOT NULL,user_id TEXT,op TEXT NOT NULL,at INTEGER NOT NULL,reason TEXT,replaced_by TEXT,stamp TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))))")
        execute("CREATE INDEX IF NOT EXISTS api_journal_entity ON api_journal(type,id,user_id,seq)")
        // The trigger maintains revisions transactionally. Backfill only when installing it or
        // its table on a legacy database, rather than grouping retained history on every open.
        val revisionBackfill = long("SELECT COUNT(*) FROM sqlite_master WHERE (type='table' AND name='api_revision') OR (type='trigger' AND name='api_journal_revision')") != 2L
        execute("CREATE TABLE IF NOT EXISTS api_revision(type TEXT NOT NULL,id TEXT NOT NULL,user_id TEXT NOT NULL DEFAULT '',seq INTEGER NOT NULL,PRIMARY KEY(type,id,user_id))")
        execute("CREATE TRIGGER IF NOT EXISTS api_journal_revision AFTER INSERT ON api_journal BEGIN INSERT INTO api_revision(type,id,user_id,seq) VALUES(NEW.type,NEW.id,COALESCE(NEW.user_id,''),NEW.seq) ON CONFLICT(type,id,user_id) DO UPDATE SET seq=NEW.seq; INSERT INTO meta(key,value) VALUES('api_journal_stamp',NEW.stamp) ON CONFLICT(key) DO UPDATE SET value=NEW.stamp; END")
        if (revisionBackfill) execute("INSERT INTO api_revision(type,id,user_id,seq) SELECT type,id,COALESCE(user_id,''),MAX(seq) FROM api_journal GROUP BY type,id,COALESCE(user_id,'') ON CONFLICT(type,id,user_id) DO UPDATE SET seq=MAX(api_revision.seq,excluded.seq)")
        execute("INSERT OR IGNORE INTO meta(key,value) SELECT 'api_journal_stamp',stamp FROM api_journal ORDER BY seq DESC LIMIT 1")
        execute("CREATE TABLE IF NOT EXISTS api_identity (type TEXT NOT NULL,id TEXT NOT NULL,reason TEXT,replaced_by TEXT,deleted_at INTEGER,PRIMARY KEY(type,id))")
        execute("CREATE TABLE IF NOT EXISTS api_group_identity (type TEXT NOT NULL,key TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(type,key))")
        val fresh = string("SELECT value FROM meta WHERE key='api_journal_initialized'") == null
        val now = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
        for (table in listOf("track", "album", "artist", "music_folder")) {
            val type = if (table == "music_folder") "source" else table
            for ((action, ref, op) in listOf(Triple("INSERT", "NEW", "upsert"), Triple("UPDATE", "NEW", "upsert"), Triple("DELETE", "OLD", "delete"))) {
                val identity = if (op == "delete") "INSERT INTO api_identity(type,id,reason,deleted_at) VALUES('$type',CAST($ref.id AS TEXT),'deleted',$now) ON CONFLICT(type,id) DO UPDATE SET reason='deleted',replaced_by=NULL,deleted_at=$now;" else "INSERT INTO api_identity(type,id) VALUES('$type',CAST($ref.id AS TEXT)) ON CONFLICT(type,id) DO UPDATE SET reason=NULL,replaced_by=NULL,deleted_at=NULL;"
                execute("CREATE TRIGGER IF NOT EXISTS api_${table}_${action.lowercase()} AFTER $action ON $table BEGIN $identity INSERT INTO api_journal(type,id,op,at,reason) VALUES('$type',CAST($ref.id AS TEXT),'$op',$now,${if (op == "delete") "'deleted'" else "NULL"}); END")
            }
            if (fresh) {
                execute("INSERT INTO api_identity(type,id) SELECT '$type',CAST(id AS TEXT) FROM $table")
                execute("INSERT INTO api_journal(type,id,op,at) SELECT '$type',CAST(id AS TEXT),'upsert',$now FROM $table")
            }
        }
        for ((action,ref) in listOf("INSERT" to "NEW","DELETE" to "OLD")) {
            execute("CREATE TRIGGER IF NOT EXISTS api_source_track_${action.lowercase()} AFTER $action ON track BEGIN INSERT INTO api_journal(type,id,op,at) VALUES('source',CAST($ref.folder_id AS TEXT),'upsert',$now); END")
        }
        execute("CREATE TRIGGER IF NOT EXISTS api_source_track_update AFTER UPDATE OF folder_id ON track WHEN OLD.folder_id<>NEW.folder_id BEGIN INSERT INTO api_journal(type,id,op,at) VALUES('source',CAST(OLD.folder_id AS TEXT),'upsert',$now); INSERT INTO api_journal(type,id,op,at) VALUES('source',CAST(NEW.folder_id AS TEXT),'upsert',$now); END")
        for (table in listOf("annotation", "library_item", "playlist", "playlist_track")) {
            for ((action, ref) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
                val type = if (table in listOf("annotation", "library_item")) "itemState" else "playlist"
                val id = when(table) { "playlist" -> "$ref.id"; "playlist_track" -> "$ref.playlist_id"; else -> "$ref.item_id" }
                val user = if (table == "playlist") "CASE WHEN $ref.public=1 THEN NULL ELSE $ref.owner_id END" else if (table == "playlist_track") "(SELECT CASE WHEN public=1 THEN NULL ELSE owner_id END FROM playlist WHERE id=$ref.playlist_id)" else "$ref.user_id"
                val op = if (table == "playlist" && action == "DELETE") "delete" else "upsert"
                execute("CREATE TRIGGER IF NOT EXISTS api_${table}_${action.lowercase()} AFTER $action ON $table ${if(table == "playlist_track") "WHEN EXISTS(SELECT 1 FROM playlist WHERE id=$ref.playlist_id)" else ""} BEGIN INSERT INTO api_journal(type,id,user_id,op,at,reason) VALUES('$type',$id,$user,'$op',$now,${if(op == "delete") "'deleted'" else "NULL"}); END")
            }
        }
        execute("CREATE TRIGGER IF NOT EXISTS api_playlist_visibility AFTER UPDATE OF public ON playlist WHEN OLD.public=1 AND NEW.public=0 BEGIN INSERT INTO meta(key,value) VALUES('api_visibility_epoch','1') ON CONFLICT(key) DO UPDATE SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT); END")
        execute("INSERT OR IGNORE INTO api_group_identity(type,key,id) SELECT 'source',CAST(id AS TEXT),CAST(id AS TEXT) FROM music_folder")
        execute("INSERT OR IGNORE INTO api_group_identity(type,key,id) SELECT 'genre',genre,lower(hex(randomblob(16))) FROM track_genre GROUP BY genre")
        for ((action,ref) in listOf("INSERT" to "NEW","DELETE" to "OLD")) {
            val op = if(action == "INSERT") "'upsert'" else "CASE WHEN EXISTS(SELECT 1 FROM track_genre WHERE genre=OLD.genre) THEN 'upsert' ELSE 'delete' END"
            execute("CREATE TRIGGER IF NOT EXISTS api_genre_${action.lowercase()} AFTER $action ON track_genre BEGIN INSERT OR IGNORE INTO api_group_identity(type,key,id) VALUES('genre',$ref.genre,lower(hex(randomblob(16)))); INSERT INTO api_journal(type,id,op,at,reason) SELECT 'genre',id,$op,$now,CASE WHEN $op='delete' THEN 'deleted' ELSE NULL END FROM api_group_identity WHERE type='genre' AND key=$ref.genre; INSERT INTO api_identity(type,id,reason,deleted_at) SELECT 'genre',id,CASE WHEN $op='delete' THEN 'deleted' ELSE NULL END,CASE WHEN $op='delete' THEN $now ELSE NULL END FROM api_group_identity WHERE type='genre' AND key=$ref.genre ON CONFLICT(type,id) DO UPDATE SET reason=excluded.reason,deleted_at=excluded.deleted_at; END")
        }
        if (fresh) {
            execute("INSERT INTO api_journal(type,id,op,at) SELECT 'genre',id,'upsert',$now FROM api_group_identity WHERE type='genre'")
            execute("INSERT OR IGNORE INTO api_identity(type,id) SELECT 'genre',id FROM api_group_identity WHERE type='genre'")
        }
        if (fresh) {
            execute("INSERT INTO api_journal(type,id,user_id,op,at) SELECT 'itemState',item_id,user_id,'upsert',$now FROM annotation UNION SELECT 'itemState',item_id,user_id,'upsert',$now FROM library_item")
            execute("INSERT INTO api_journal(type,id,user_id,op,at) SELECT 'playlist',id,CASE WHEN public=1 THEN NULL ELSE owner_id END,'upsert',$now FROM playlist")
            execute("INSERT INTO meta VALUES('api_journal_initialized','1')")
        }
    }

    fun migrate(db: Db) {
        db.write {
            val current = long("PRAGMA user_version").toInt()
            for (version in current until migrations.size) {
                migrations[version].split(";").map(String::trim).filter(String::isNotEmpty).forEach { execute(it) }
                execute("PRAGMA user_version = ${version + 1}")
            }
            installJournal(this)
        }
    }
}
