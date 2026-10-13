package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.db.Db
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Ordered, transactionally written changes, with continuity evidence kept outside SQLite. */
class Journal(private val db: Db, dataFolder: Path) {
    private val marker: Path
    private val key: ByteArray
    @Volatile var instanceId: String
        private set
    @Volatile var retentionMillis: Long = DEFAULT_RETENTION
        set(value) { require(value >= 0) { "Retention cannot be negative" }; field = value }
    private val databaseBinding: String
    private var lastProof: String? = null
    private var visibilityHead = 0L

    init {
        Files.createDirectories(dataFolder)
        marker = dataFolder.resolve("api-timeline")
        val keyFile = dataFolder.resolve("api-cursor-key")
        if (!Files.exists(keyFile)) durableWrite(keyFile, ByteArray(32).also { SecureRandom().nextBytes(it) })
        key = Files.readAllBytes(keyFile)
        require(key.size == 32) { "Invalid API cursor signing key" }
        installVisibility()
        val identity = db.write {
            string("SELECT value FROM meta WHERE key='api_database_id'") ?: UUID.randomUUID().toString().also {
                execute("INSERT INTO meta VALUES('api_database_id',?)", it)
            }
        }
        // A copied database or data directory cannot assert continuity on a different filesystem object.
        val location = db.file?.let { "${it.toRealPath()}|${Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()}" }
            ?: "memory:$identity"
        databaseBinding = hash("$identity|$location|${dataFolder.toRealPath()}")
        instanceId = db.read {
            val saved = if (Files.exists(marker)) Files.readString(marker).split('\n') else emptyList()
            val continuous = saved.size >= 4 && saved[1] == databaseBinding && saved[2].toLongOrNull() == head(this) && saved[3] == stamp(this)
            (if (continuous) saved[0] else UUID.randomUUID().toString()).also { saveMarker(this, it) }
        }
        // Persist the next committed head before committing SQLite. A crash between these two steps
        // leaves an unprovable timeline and therefore rotates the instance rather than accepting it.
        db.beforeCommit {
            recordVisibility(it)
            if (retentionMillis != DEFAULT_RETENTION) prune(it, System.currentTimeMillis())
            saveMarker(it, instanceId)
        }
        prune()
    }

    fun head(): Long = db.read { head(this) }
    /** A restore starts a new timeline even when its restored journal happens to have the same head. */
    fun rotateInstance() = db.exclusive {
        db.read {
            val next = UUID.randomUUID().toString()
            saveMarker(this, next)
            instanceId = next
        }
    }
    fun visibilityEpoch(): Long = db.read { string("SELECT value FROM meta WHERE key='api_visibility_epoch'")?.toLongOrNull() ?: 0L }
    fun floor(): Long = db.read { string("SELECT value FROM meta WHERE key='api_journal_floor'")?.toLongOrNull() ?: 0L }
    fun revision(type: String, id: String, userId: String? = null): String = db.read {
        "r_${instanceId}_${long("SELECT COALESCE(MAX(seq),0) FROM api_revision WHERE type=? AND id=? AND (user_id=? OR type='playlist')", type, id, userId ?: "")}"
    }

    fun cursor(userId: String, types: String, position: Long = head()): String {
        val payload = listOf(instanceId, userId, canonicalTypes(types), position.toString(), visibilityEpoch().toString()).joinToString("\n")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "sc_$encoded.${sign(encoded)}"
    }

    class CursorException(val expired: Boolean, message: String) : IllegalArgumentException(message)
    fun decodeCursor(userId: String, types: String, cursor: String): Long {
        val pieces = cursor.removePrefix("sc_").split('.')
        if (!cursor.startsWith("sc_") || pieces.size != 2) throw CursorException(false, "Invalid sync cursor")
        val fields = runCatching { String(Base64.getUrlDecoder().decode(pieces[0])).split('\n') }.getOrNull()
            ?: throw CursorException(false, "Invalid sync cursor")
        if (fields.size != 5) throw CursorException(false, "Invalid sync cursor")
        if (fields[0] != instanceId) throw CursorException(true, "Sync cursor belongs to another timeline")
        // Another server has a different signing key. Classify its timeline as expired, while
        // accepting no position or local cursor until this server's MAC has been checked.
        if (!MessageDigest.isEqual(sign(pieces[0]).toByteArray(), pieces[1].toByteArray())) throw CursorException(false, "Invalid sync cursor")
        if (fields[4].toLongOrNull() != visibilityEpoch()) throw CursorException(true, "Visibility changed since this cursor")
        if (fields[1] != userId || fields[2] != canonicalTypes(types)) throw CursorException(false, "Sync cursor does not match this user or type filter")
        val position = fields[3].toLongOrNull() ?: throw CursorException(false, "Invalid sync cursor")
        if (position < floor() || position > head()) throw CursorException(true, "Sync cursor is outside retained history")
        return position
    }

    fun prune(now: Long = System.currentTimeMillis()) = db.write { prune(this, now) }

    /** The last known source membership survives catalog deletion, including deleted joins. */
    private val liveVisibility: String get() = """
        SELECT 'track' AS type,id,CAST(folder_id AS TEXT) AS source_id FROM track
        UNION SELECT 'album',album_id,CAST(folder_id AS TEXT) FROM track
        UNION SELECT 'artist',artist_id,CAST(folder_id AS TEXT) FROM track
        UNION SELECT 'artist',album_artist_id,CAST(folder_id AS TEXT) FROM track
        UNION SELECT 'artist',a.artist_id,CAST(t.folder_id AS TEXT) FROM track_artist a JOIN track t ON t.id=a.track_id
        UNION SELECT 'artist',a.artist_id,CAST(t.folder_id AS TEXT) FROM album_artist a JOIN track t ON t.album_id=a.album_id
        UNION SELECT 'genre',i.id,CAST(t.folder_id AS TEXT) FROM track_genre g JOIN track t ON t.id=g.track_id JOIN api_group_identity i ON i.type='genre' AND i.key=g.genre
        UNION SELECT 'source',CAST(id AS TEXT),CAST(id AS TEXT) FROM music_folder
    """.trimIndent()

    private fun installVisibility() = db.write {
        val fresh = long("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_journal_visibility'") == 0L
        execute("CREATE TABLE IF NOT EXISTS api_catalog_visibility(type TEXT NOT NULL,id TEXT NOT NULL,source_id TEXT NOT NULL,PRIMARY KEY(type,id,source_id))")
        execute("CREATE TABLE IF NOT EXISTS api_journal_visibility(seq INTEGER PRIMARY KEY REFERENCES api_journal(seq) ON DELETE CASCADE,source_ids TEXT NOT NULL)")
        if (fresh) {
            execute("INSERT OR IGNORE INTO api_catalog_visibility $liveVisibility")
            // Historical deleted entities have no provable membership. Existing cursors must restart;
            // never reconstruct a deleted item's sources from another item or from present policy.
            if (long("SELECT COUNT(*) FROM api_journal WHERE op='delete' AND type IN ('track','album','artist','genre','source')") > 0L)
                execute("INSERT INTO meta(key,value) VALUES('api_visibility_epoch','1') ON CONFLICT(key) DO UPDATE SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT)")
        }
        val capture = mapOf(
            "track" to """VALUES('track',OLD.id,CAST(OLD.folder_id AS TEXT)),('album',OLD.album_id,CAST(OLD.folder_id AS TEXT)),('artist',OLD.artist_id,CAST(OLD.folder_id AS TEXT)),('artist',OLD.album_artist_id,CAST(OLD.folder_id AS TEXT))""",
            "track_artist" to "SELECT 'artist',OLD.artist_id,CAST(folder_id AS TEXT) FROM track WHERE id=OLD.track_id",
            "album_artist" to "SELECT 'artist',OLD.artist_id,CAST(folder_id AS TEXT) FROM track WHERE album_id=OLD.album_id",
            "track_genre" to "SELECT 'genre',i.id,CAST(t.folder_id AS TEXT) FROM track t JOIN api_group_identity i ON i.type='genre' AND i.key=OLD.genre WHERE t.id=OLD.track_id",
        )
        for ((table, sources) in capture) {
            // BEFORE is essential: the scanner removes joins before their parent catalog rows.
            execute("CREATE TRIGGER IF NOT EXISTS api_capture_${table}_visibility BEFORE DELETE ON $table BEGIN INSERT OR IGNORE INTO api_catalog_visibility $sources; END")
        }
        execute("CREATE TRIGGER IF NOT EXISTS api_track_source_visibility BEFORE UPDATE OF folder_id ON track WHEN OLD.folder_id<>NEW.folder_id BEGIN INSERT INTO meta(key,value) VALUES('api_visibility_epoch','1') ON CONFLICT(key) DO UPDATE SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT); END")
        visibilityHead = head(this)
        // Only sources are intrinsically identifiable in historical rows. All other unprovable
        // tombstones remain absent and cause the affected feed read to expire its cursor.
        execute("INSERT OR IGNORE INTO api_journal_visibility SELECT seq,json_array(id) FROM api_journal WHERE type='source'")
    }

    private fun recordVisibility(session: Db.Session) = with(session) {
        val recorded = string("SELECT value FROM meta WHERE key='api_visibility_head'")?.toLongOrNull() ?: visibilityHead
        val nextHead = head(this)
        if (long("SELECT COUNT(*) FROM api_journal WHERE seq>? AND type IN ('track','album','artist','genre','source')",recorded) == 0L) return@with
        execute("CREATE TEMP TABLE IF NOT EXISTS api_live_visibility(type TEXT NOT NULL,id TEXT NOT NULL,source_id TEXT NOT NULL,PRIMARY KEY(type,id,source_id))")
        execute("DELETE FROM api_live_visibility")
        execute("INSERT OR IGNORE INTO api_live_visibility $liveVisibility")
        // A still-live entity changing its source membership changes visibility. Restart feeds
        // rather than silently dropping a previously cached entity or exposing its later source.
        if (long("""SELECT COUNT(*) FROM api_catalog_visibility old
            WHERE EXISTS(SELECT 1 FROM api_live_visibility live WHERE live.type=old.type AND live.id=old.id)
            AND NOT EXISTS(SELECT 1 FROM api_live_visibility live WHERE live.type=old.type AND live.id=old.id AND live.source_id=old.source_id)""") > 0L)
            execute("INSERT INTO meta(key,value) VALUES('api_visibility_epoch','1') ON CONFLICT(key) DO UPDATE SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT)")
        execute("DELETE FROM api_catalog_visibility WHERE EXISTS(SELECT 1 FROM api_live_visibility live WHERE live.type=api_catalog_visibility.type AND live.id=api_catalog_visibility.id)")
        execute("INSERT OR IGNORE INTO api_catalog_visibility SELECT * FROM api_live_visibility")
        execute("""INSERT OR IGNORE INTO api_journal_visibility(seq,source_ids)
            SELECT j.seq,(SELECT json_group_array(source_id) FROM api_catalog_visibility v WHERE v.type=j.type AND v.id=j.id)
            FROM api_journal j WHERE j.seq>? AND j.type IN ('track','album','artist','genre','source')
            AND EXISTS(SELECT 1 FROM api_catalog_visibility v WHERE v.type=j.type AND v.id=j.id)""",recorded)
        execute("INSERT OR IGNORE INTO api_journal_visibility SELECT seq,json_array(id) FROM api_journal WHERE seq>? AND type='source'",recorded)
        // Use a persisted watermark: updating an in-memory one here would survive a failed commit.
        execute("INSERT INTO meta(key,value) VALUES('api_visibility_head',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",nextHead.toString())
    }

    private fun prune(session: Db.Session, now: Long) = with(session) {
        val cutoff = if (retentionMillis > 0 && now < Long.MIN_VALUE + retentionMillis) Long.MIN_VALUE else now - retentionMillis
        val removed = long("SELECT COALESCE(MAX(seq),0) FROM api_journal WHERE seq < COALESCE((SELECT MIN(seq) FROM api_journal WHERE at >= ?),9223372036854775807)", cutoff)
        val floor = maxOf(removed, string("SELECT value FROM meta WHERE key='api_journal_floor'")?.toLongOrNull() ?: 0L)
        execute("INSERT INTO meta(key,value) VALUES('api_journal_floor',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", floor.toString())
        execute("DELETE FROM api_journal WHERE seq <= ?", floor)
        execute("DELETE FROM api_journal_visibility WHERE seq <= ?", floor)
    }

    private fun head(s: Db.Session): Long = maxOf(s.long("SELECT COALESCE(MAX(seq),0) FROM api_journal"), s.long("SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence WHERE name='api_journal'"))
    private fun stamp(s: Db.Session): String = s.string("SELECT value FROM meta WHERE key='api_journal_stamp'") ?: "empty:${head(s)}"
    private fun saveMarker(s: Db.Session, instance: String) {
        val proof="$instance\n$databaseBinding\n${head(s)}\n${stamp(s)}\n"
        if(proof != lastProof) { durableWrite(marker,proof.toByteArray()); lastProof=proof }
    }
    private fun sign(payload: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(payload.toByteArray()) })
    private fun canonicalTypes(types: String): String = types.split(',').filter(String::isNotEmpty).distinct().sorted().joinToString(",")
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun durableWrite(path: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(path.parent, ".api-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object { private const val DEFAULT_RETENTION = 90L * 24 * 60 * 60 * 1000 }
}
