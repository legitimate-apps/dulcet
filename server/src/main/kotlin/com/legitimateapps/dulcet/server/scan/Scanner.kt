package com.legitimateapps.dulcet.server.scan

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.fold
import com.legitimateapps.dulcet.server.sortName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class ScanResult(val added: Int, val updated: Int, val removed: Int, val unchanged: Int, val failed: Int)

/**
 * Walks the music folder and keeps the catalog in step with it. A file is probed only when it is
 * new or its size or modification time changed; a vanished file's row goes. Albums and artists are
 * then regrouped from the tracks, which is cheap and keeps grouping rules in one place.
 */
class Scanner(
    private val db: Db,
    private val musicFolder: Path,
    private val probe: Probe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(Scanner::class.java)
    private val running = AtomicBoolean(false)
    private val progress = AtomicInteger(0)
    @Volatile private var rescanRequested = false

    val isScanning: Boolean get() = running.get()
    val scannedCount: Int get() = progress.get()

    /** Runs a scan unless one is running, in which case the running one rescans once it ends. */
    /**
     * [force] false is the periodic check: it writes nothing, and leaves `lastScan` where it is,
     * when no file, sidecar or folder image changed, because `lastScan` is the clients' scan clock.
     */
    fun scan(force: Boolean = true): ScanResult? {
        if (!running.compareAndSet(false, true)) {
            rescanRequested = true
            return null
        }
        try {
            var result: ScanResult
            do {
                rescanRequested = false
                result = scanOnce(force)
            } while (rescanRequested)
            return result
        } finally {
            running.set(false)
        }
    }

    private fun scanOnce(force: Boolean): ScanResult {
        progress.set(0)
        val started = clock()
        val root = musicFolder.toAbsolutePath().normalize()
        val files = ArrayList<FileEntry>()
        val folderArt = HashMap<String, String>()
        walk(root, root, files, folderArt)

        data class Existing(val id: String, val mtime: Long, val size: Long, val sidecar: String?, val created: Long)
        val existing = db.read {
            query("SELECT id, path, mtime, size, lyrics_sidecar, created FROM track") { row ->
                row.string("path") to Existing(row.string("id"), row.long("mtime"), row.long("size"), row.stringOrNull("lyrics_sidecar"), row.long("created"))
            }.toMap()
        }
        val present = files.associateBy { it.relative }
        val removed = existing.keys.filter { it !in present }
        val changed = files.filter { file ->
            val old = existing[file.relative]
            old == null || old.mtime != file.mtime || old.size != file.size || old.sidecar != file.sidecar
        }
        val unchanged = files.size - changed.size
        val artStamp = folderArt.toSortedMap().entries.joinToString("\n").hashCode().toString()
        if (!force && changed.isEmpty() && removed.isEmpty() && db.read { string("SELECT value FROM meta WHERE key = 'folder_art_stamp'") } == artStamp) {
            return ScanResult(added = 0, updated = 0, removed = 0, unchanged = unchanged, failed = 0)
        }
        // ffprobe is a process per file; a few in parallel keep a first scan short.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PROBE_THREADS)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val probed = try {
            changed.map { file ->
                pool.submit<TrackInfo?> {
                    try {
                        val tags = probe.probe(root.resolve(file.relative))
                        TrackInfo.from(file.relative, file.parent, file.name, file.mtime, file.size, tags, file.sidecar)
                    } catch (error: Exception) {
                        failures.incrementAndGet()
                        log.warn("could not read tags of a file ({}): {}", file.name, error.message)
                        null
                    } finally {
                        progress.incrementAndGet()
                    }
                }
            }.mapNotNull { it.get() }
        } finally {
            pool.shutdown()
        }
        val failed = failures.get()
        progress.addAndGet(unchanged)
        val now = clock()
        db.write {
            removed.forEach { path ->
                val id = existing.getValue(path).id
                execute("DELETE FROM track WHERE path = ?", path)
                execute("DELETE FROM track_artist WHERE track_id = ?", id)
                execute("DELETE FROM track_genre WHERE track_id = ?", id)
            }
            val claimed = HashMap<String, String>()
            query("SELECT id, path FROM track") { it.string("id") to it.string("path") }.forEach { (id, path) -> claimed[id] = path }
            for (track in probed) {
                val previous = existing[track.path]
                if (previous != null) {
                    execute("DELETE FROM track WHERE path = ?", track.path)
                    execute("DELETE FROM track_artist WHERE track_id = ?", previous.id)
                    execute("DELETE FROM track_genre WHERE track_id = ?", previous.id)
                    claimed.remove(previous.id)
                }
                var id = track.candidateId()
                val holder = claimed[id]
                if (holder != null && holder != track.path) id = com.legitimateapps.dulcet.server.Ids.of("track", id, track.path)
                claimed[id] = track.path
                insertTrack(id, track, created = previous?.created ?: now, updated = now)
            }
            regroup(folderArt)
            execute("INSERT INTO meta(key, value) VALUES('last_scan', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", Instant.ofEpochMilli(now).toString())
            execute("INSERT INTO meta(key, value) VALUES('folder_count', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", folderArt.size.toString())
            execute("INSERT INTO meta(key, value) VALUES('folder_art_stamp', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", artStamp)
        }
        val result = ScanResult(added = probed.count { existing[it.path] == null }, updated = probed.count { existing[it.path] != null }, removed = removed.size, unchanged = unchanged, failed = failed)
        log.info("scan finished in {} ms: {}", clock() - started, result)
        return result
    }

    private data class FileEntry(val relative: String, val parent: String, val name: String, val mtime: Long, val size: Long, val sidecar: String?)

    private fun walk(root: Path, directory: Path, files: MutableList<FileEntry>, folderArt: MutableMap<String, String>) {
        val children = try {
            Files.list(directory).use { stream -> stream.toList() }
        } catch (error: Exception) {
            log.warn("could not list a folder: {}", error.message)
            return
        }
        val relativeDir = root.relativize(directory).toString().replace('\\', '/')
        val names = children.associateBy { it.name.lowercase() }
        ART_NAMES.firstNotNullOfOrNull { names[it] }?.let { folderArt[relativeDir] = it.name }
            ?: run { folderArt.putIfAbsent(relativeDir, "") }
        for (child in children.sortedBy { it.name }) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory()) {
                if (!Files.isSymbolicLink(child)) walk(root, child, files, folderArt)
                continue
            }
            val suffix = child.name.substringAfterLast('.', "").lowercase()
            if (suffix !in TrackInfo.AUDIO_SUFFIXES) continue
            val attributes = try {
                Files.readAttributes(child, java.nio.file.attribute.BasicFileAttributes::class.java)
            } catch (_: Exception) {
                continue
            }
            val base = child.name.substringBeforeLast('.').lowercase()
            val sidecar = LYRICS_SIDECARS.firstNotNullOfOrNull { names["$base.$it"] }?.let { root.relativize(it).toString().replace('\\', '/') }
            files.add(
                FileEntry(
                    relative = root.relativize(child).toString().replace('\\', '/'),
                    parent = relativeDir,
                    name = child.name,
                    mtime = attributes.lastModifiedTime().toMillis(),
                    size = attributes.size(),
                    sidecar = sidecar,
                ),
            )
        }
    }

    private fun Db.Session.insertTrack(id: String, t: TrackInfo, created: Long, updated: Long) {
        execute(
            """INSERT INTO track(id, path, folder_id, parent_dir, mtime, size, title, sort_title, album, album_id, artist, artist_id,
               album_artist, album_artist_id, track_no, disc_no, disc_subtitle, year, release_date, genre, duration, bitrate,
               sample_rate, channels, bit_depth, codec, suffix, content_type, compilation, mbz_track_id, mbz_recording_id,
               mbz_album_id, mbz_artist_id, mbz_album_artist_id, has_embedded_art, lyrics_sidecar, embedded_lyrics, comment, bpm,
               rg_track_gain, rg_track_peak, rg_album_gain, rg_album_peak, created, updated, search)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            id, t.path, 1, t.parentDir, t.mtime, t.size, t.title, t.sortTitle, t.album, t.albumId, t.artistDisplay, t.artists.first().id,
            t.albumArtistDisplay, t.albumArtists.first().id, t.trackNo, t.discNo, t.discSubtitle, t.year, t.releaseDate, t.genres.firstOrNull(),
            t.duration, t.bitRate, t.sampleRate, t.channels, t.bitDepth, t.codec, t.suffix, t.contentType, t.compilation, t.mbzTrackId,
            t.mbzRecordingId, t.mbzAlbumId, t.mbzArtistId, t.mbzAlbumArtistId, t.hasEmbeddedArt, t.lyricsSidecar,
            t.embeddedLyrics.takeIf { it.isNotEmpty() }?.let { map -> JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString() },
            t.comment, t.bpm, t.rgTrackGain, t.rgTrackPeak, t.rgAlbumGain, t.rgAlbumPeak, created, updated, t.searchText(),
        )
        t.artists.forEachIndexed { index, credit -> execute("INSERT OR IGNORE INTO track_artist VALUES (?,?,?,?,?)", id, "artist", credit.id, credit.name, index) }
        t.albumArtists.forEachIndexed { index, credit -> execute("INSERT OR IGNORE INTO track_artist VALUES (?,?,?,?,?)", id, "albumartist", credit.id, credit.name, index) }
        t.composers.forEachIndexed { index, credit -> execute("INSERT OR IGNORE INTO track_artist VALUES (?,?,?,?,?)", id, "composer", credit.id, credit.name, index) }
        t.genres.forEach { execute("INSERT OR IGNORE INTO track_genre VALUES (?,?)", id, it) }
    }

    /** Rebuilds album, album_artist and artist from the tracks. */
    private fun Db.Session.regroup(folderArt: Map<String, String>) {
        execute("DELETE FROM album")
        execute("DELETE FROM album_artist")
        execute("DELETE FROM artist")
        data class AlbumAcc(
            val id: String, val name: String, val albumArtist: String, val artistId: String, var year: Int?, var genre: String?,
            var compilation: Boolean, var count: Int, var duration: Double, var size: Long, var created: Long, var mbz: String?,
            var artTrack: String?, var folderArt: String?, val dirs: MutableSet<String>,
        )
        val albums = LinkedHashMap<String, AlbumAcc>()
        query("SELECT id, album_id, album, album_artist, album_artist_id, year, genre, compilation, duration, size, created, mbz_album_id, has_embedded_art, parent_dir FROM track ORDER BY disc_no, track_no, path") { row ->
            val albumId = row.string("album_id")
            val acc = albums.getOrPut(albumId) {
                AlbumAcc(albumId, row.string("album"), row.string("album_artist"), row.string("album_artist_id"), null, null, false, 0, 0.0, 0, Long.MAX_VALUE, null, null, null, LinkedHashSet())
            }
            acc.count++
            acc.duration += row.double("duration")
            acc.size += row.long("size")
            acc.created = minOf(acc.created, row.long("created"))
            acc.year = acc.year ?: row.intOrNull("year")
            acc.genre = acc.genre ?: row.stringOrNull("genre")
            acc.compilation = acc.compilation || row.bool("compilation")
            acc.mbz = acc.mbz ?: row.stringOrNull("mbz_album_id")
            if (acc.artTrack == null && row.bool("has_embedded_art")) acc.artTrack = row.string("id")
            acc.dirs.add(row.string("parent_dir"))
        }
        for (album in albums.values) {
            fun artIn(dir: String): String? = folderArt[dir]?.takeIf(String::isNotEmpty)?.let { if (dir.isEmpty()) it else "$dir/$it" }
            // A multi-disc album keeps its cover one level up ("Album/cover.jpg" over "Album/Disc 1").
            album.folderArt = album.dirs.firstNotNullOfOrNull { dir -> artIn(dir) ?: dir.takeIf { '/' in it }?.let { artIn(it.substringBeforeLast('/')) } }
            execute(
                "INSERT INTO album VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                album.id, album.name, sortName(album.name), album.albumArtist, album.artistId, album.year, album.genre, album.compilation,
                album.count, album.duration, album.size, album.created, album.mbz, album.artTrack, album.folderArt,
                fold("${album.name} ${album.albumArtist}"),
            )
        }
        execute(
            """INSERT OR IGNORE INTO album_artist(album_id, artist_id, name, position)
               SELECT t.album_id, ta.artist_id, ta.name, MIN(ta.position) FROM track_artist ta JOIN track t ON t.id = ta.track_id
               WHERE ta.role = 'albumartist' GROUP BY t.album_id, ta.artist_id""",
        )
        // Artists are everyone credited as an album artist or a track artist.
        data class ArtistAcc(val id: String, var name: String, var mbz: String?, val albums: MutableSet<String>, var songs: Int)
        val artists = LinkedHashMap<String, ArtistAcc>()
        query("SELECT ta.artist_id, ta.name, ta.role, t.album_id, t.id AS track_id, t.mbz_artist_id, t.mbz_album_artist_id FROM track_artist ta JOIN track t ON t.id = ta.track_id WHERE ta.role IN ('artist', 'albumartist')") { row ->
            val acc = artists.getOrPut(row.string("artist_id")) { ArtistAcc(row.string("artist_id"), row.string("name"), null, HashSet(), 0) }
            if (row.string("role") == "albumartist") {
                acc.albums.add(row.string("album_id"))
                acc.mbz = acc.mbz ?: row.stringOrNull("mbz_album_artist_id")
            } else {
                acc.songs++
                acc.mbz = acc.mbz ?: row.stringOrNull("mbz_artist_id")
            }
        }
        for (artist in artists.values) {
            execute(
                "INSERT INTO artist VALUES (?,?,?,?,?,?,?)",
                artist.id, artist.name, sortName(artist.name), artist.mbz, artist.albums.size, artist.songs, fold(artist.name),
            )
        }
    }

    companion object {
        private val PROBE_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val ART_NAMES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png", "front.jpg", "front.jpeg", "front.png", "album.jpg", "album.png", "albumart.jpg", "albumart.png")
        val LYRICS_SIDECARS = listOf("lrc", "txt")
    }
}
