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

data class ScanResult(val added: Int, val updated: Int, val removed: Int, val unchanged: Int, val failed: Int, val refused: String? = null)

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
    private val lifecycle = Any()
    private var rescanRequested = false
    private var active: Run? = null

    /** One scan operation, including any rescan requested by the legacy scanner interface. */
    data class Run(val id:String,val startedAt:Long,val force:Boolean,val completion:java.util.concurrent.CompletableFuture<ScanResult>)

    val isScanning: Boolean get() = running.get()
    val scannedCount: Int get() = progress.get()
    fun currentRun():Run? = synchronized(lifecycle) { active }

    /** Join the actual scanner operation, regardless of which interface started it. */
    fun startOrJoin(force:Boolean=true,launch:(() -> Unit) -> Unit):Run {
        val (run,created)=reserve(force,false)
        if(created) {
            try { launch {
                try { executeRun(run) }
                catch (_:Exception) { /* The asynchronous caller receives failure through completion. */ }
            } }
            catch(error:Throwable) { finish(run,null,error); throw error }
        }
        return run
    }

    /**
     * The legacy synchronous interface retains its behavior: a concurrent call requests a rescan
     * and returns null. API callers instead use [startOrJoin], which joins without another scan.
     */
    fun scan(force: Boolean = true): ScanResult? {
        val (run,created)=reserve(force,true)
        return if(created) executeRun(run) else null
    }

    private fun reserve(force:Boolean,rescan:Boolean):Pair<Run,Boolean> = synchronized(lifecycle) {
        active?.let { if(rescan) rescanRequested=true; return@synchronized it to false }
        val run=Run(java.util.UUID.randomUUID().toString(),clock(),force,java.util.concurrent.CompletableFuture())
        rescanRequested=false
        active=run
        running.set(true)
        run to true
    }

    private fun executeRun(run:Run):ScanResult {
        try {
            while(true) {
                val result=try { scanOnce(run.force) } catch (_:java.io.IOException) {
                    log.warn("scan refused: music folder could not be read; catalog retained")
                    ScanResult(0,0,0,0,1,"Music folder could not be read; catalog retained")
                }
                val repeat=synchronized(lifecycle) {
                    if(rescanRequested) { rescanRequested=false; true }
                    else { active=null; running.set(false); false }
                }
                if(!repeat) { run.completion.complete(result); return result }
            }
        } catch(error:Throwable) { finish(run,null,error); throw error }
    }

    private fun finish(run:Run,result:ScanResult?,error:Throwable?) {
        synchronized(lifecycle) {
            if(active === run) { active=null; running.set(false); rescanRequested=false }
        }
        if(error!=null) run.completion.completeExceptionally(error) else run.completion.complete(result!!)
    }

    private fun scanOnce(force: Boolean): ScanResult {
        progress.set(0)
        val started = clock()
        val root = musicFolder.toRealPath()
        val files = ArrayList<FileEntry>()
        val folderArt = HashMap<String, String>()
        walk(root, root, files, folderArt)

        data class Existing(val id: String, val mtime: Long, val size: Long, val sidecar: String?, val created: Long)
        val existing = db.read {
            query("SELECT id, path, mtime, size, lyrics_sidecar, created FROM track") { row ->
                row.string("path") to Existing(row.string("id"), row.long("mtime"), row.long("size"), row.stringOrNull("lyrics_sidecar"), row.long("created"))
            }.toMap()
        }
        if (files.isEmpty() && existing.isNotEmpty()) {
            log.warn("scan refused: no music files found; catalog retained")
            return ScanResult(0, 0, 0, 0, 1, "No music files found; catalog retained")
        }
        val present = files.associateBy { it.relative }
        val removed = existing.keys.filter { it !in present }
        // Migration seeds old ids without fingerprints; fill them before an unchanged file moves.
        val missingFingerprints = db.read { query("SELECT path FROM track_identity WHERE fingerprint IS NULL OR fingerprint NOT LIKE 'packet-sha256:%'") { it.string("path") }.toSet() }
        val changed = files.filter { file ->
            val old = existing[file.relative]
            old == null || file.relative in missingFingerprints || old.mtime != file.mtime || old.size != file.size || old.sidecar != file.sidecar
        }
        val unchanged = files.size - changed.size
        val artStamp = folderArt.toSortedMap().entries.joinToString("\n") { (dir,name) ->
            val file = if(name.isEmpty()) null else com.legitimateapps.dulcet.server.musicFile(root, root.resolve(dir).resolve(name))
            val attributes = file?.let { Files.readAttributes(it,java.nio.file.attribute.BasicFileAttributes::class.java) }
            "$dir/$name:${attributes?.size()}:${attributes?.lastModifiedTime()?.toMillis()}"
        }.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }
        val artChanged = db.read { string("SELECT value FROM meta WHERE key='folder_art_stamp'") } != artStamp
        if (!force && changed.isEmpty() && removed.isEmpty() && db.read { string("SELECT value FROM meta WHERE key = 'folder_art_stamp'") } == artStamp) {
            return ScanResult(added = 0, updated = 0, removed = 0, unchanged = unchanged, failed = 0)
        }
        // ffprobe is a process per file; a few in parallel keep a first scan short.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PROBE_THREADS)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val fingerprints = java.util.concurrent.ConcurrentHashMap<String, String>()
        val probed = try {
            changed.map { file ->
                pool.submit<TrackInfo?> {
                    try {
                        val tags = probe.probe(com.legitimateapps.dulcet.server.musicFile(root, root.resolve(file.relative)) ?: error("File outside music folder or missing"))
                        val source = com.legitimateapps.dulcet.server.musicFile(root, root.resolve(file.relative)) ?: error("Missing file")
                        fingerprints[file.relative] = probe.fingerprint(source,tags.sampleRate,tags.channels)
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
        if (existing.isNotEmpty() && removed.size == existing.size && probed.isEmpty()) {
            log.warn("scan refused: no usable music files found; catalog retained")
            return ScanResult(0, 0, 0, unchanged, failed, "No usable music files found; catalog retained")
        }
        progress.addAndGet(unchanged)
        val now = clock()
        db.write {
            // Issuance order must outlive journal retention and cannot be inferred from UUID order.
            execute("CREATE TABLE IF NOT EXISTS api_album_issued (ordinal INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE)")
            execute("INSERT OR IGNORE INTO api_album_issued(id) SELECT id FROM album ORDER BY created,rowid")
            fun issued(id: String): Long = long("SELECT ordinal FROM api_album_issued WHERE id=?",id)
            // Seed legacy group ids unchanged before minting any new random identities.
            query("SELECT id FROM album") { it.string("id") }.forEach { execute("INSERT OR IGNORE INTO api_group_identity VALUES('album',?,?)", it, it) }
            query("SELECT id,name FROM artist") { it.string("id") to it.string("name") }.forEach { (id,name) -> execute("INSERT OR IGNORE INTO api_group_identity VALUES('artist',?,?)", TrackInfo.artistId(name), id) }
            val oldGroups = query("SELECT id, album_id, album_artist_id FROM track") { Triple(it.string("id"), it.string("album_id"), it.string("album_artist_id")) }
            data class OldCredit(val track:String,val role:String,val position:Int,val artist:String)
            val oldCredits=query("SELECT track_id,role,position,artist_id FROM track_artist") { OldCredit(it.string("track_id"),it.string("role"),it.int("position"),it.string("artist_id")) }
            val oldArtistMembers=oldCredits.groupBy { it.artist }
            fun artistSuccessors(members:List<OldCredit>):List<String> = members.mapNotNull { string("SELECT artist_id FROM track_artist WHERE track_id=? AND role=? AND position=?",it.track,it.role,it.position) }.distinct()

            removed.forEach { path ->
                val id = existing.getValue(path).id
                execute("DELETE FROM track WHERE path = ?", path)
                execute("DELETE FROM track_artist WHERE track_id = ?", id)
                execute("DELETE FROM track_genre WHERE track_id = ?", id)
            }
            val claimed = HashMap<String, String>()
            query("SELECT id, path FROM track") { it.string("id") to it.string("path") }.forEach { (id, path) -> claimed[id] = path }
            for (rawTrack in probed) {
                fun credit(c: Credit): Credit = c.copy(id = groupId("artist", c.id))
                val track = rawTrack.copy(artists=rawTrack.artists.map(::credit), albumArtists=rawTrack.albumArtists.map(::credit), composers=rawTrack.composers.map(::credit), albumId=string("SELECT id FROM api_group_identity WHERE type='album' AND key=?",rawTrack.albumId) ?: rawTrack.albumId)
                val previous = existing[track.path]
                if (previous != null) {
                    execute("DELETE FROM track WHERE path = ?", track.path)
                    execute("DELETE FROM track_artist WHERE track_id = ?", previous.id)
                    execute("DELETE FROM track_genre WHERE track_id = ?", previous.id)
                    claimed.remove(previous.id)
                }
                val fingerprint = fingerprints.getValue(track.path)
                val priorFingerprint=string("SELECT fingerprint FROM track_identity WHERE path=?",track.path)
                val sameAudio=priorFingerprint == null || !priorFingerprint.startsWith("packet-sha256:") || priorFingerprint == fingerprint
                val stored = if(sameAudio) string("SELECT track_id FROM track_identity WHERE path = ?", track.path) else null
                val moved = query("SELECT path, track_id FROM track_identity WHERE fingerprint = ?", fingerprint) { it.string("path") to it.string("track_id") }
                    .filter { it.first !in present && it.second !in claimed }.map { it.second }.distinct().singleOrNull()
                val legacyCandidate = rawTrack.candidateId()
                val legacy = query("SELECT path FROM track_identity WHERE track_id = ? AND fingerprint IS NULL", legacyCandidate) { it.string("path") }
                    .takeIf { paths -> paths.isNotEmpty() && paths.none { it in present } && legacyCandidate !in claimed }?.let { legacyCandidate }
                var id = previous?.id?.takeIf { sameAudio } ?: stored ?: moved ?: legacy ?: java.util.UUID.randomUUID().toString()
                val holder = claimed[id]
                if (holder != null && holder != track.path) id = java.util.UUID.randomUUID().toString()
                claimed[id] = track.path
                insertTrack(id, track, created = previous?.created?.takeIf { previous.id == id } ?: now, updated = now)
                execute("INSERT INTO track_identity VALUES (?,?,?) ON CONFLICT(path) DO UPDATE SET track_id = excluded.track_id, fingerprint = excluded.fingerprint", track.path, id, fingerprint)
            }
            // A full retag keeps the album identity. Many-to-one changes merge onto a
            // deterministic survivor; a split gets separate random successors.
            val oldAlbums = oldGroups.groupBy { it.second }
            // Reconciliation compares each former group's members with the current catalog.
            // Read that catalog once: querying SQLite inside the group comparisons makes a
            // small incremental scan perform millions of statements on a large library.
            val currentTracks = query("SELECT id,album_id,mbz_album_id FROM track") {
                Triple(it.string("id"), it.string("album_id"), it.stringOrNull("mbz_album_id"))
            }
            val albumByTrack = currentTracks.associate { it.first to it.second }.toMutableMap()
            val releaseByTrack = currentTracks.associate { it.first to it.third }
            val unresolved = query("SELECT DISTINCT album_id FROM track") { it.string("album_id") }
                .filter { id -> long("SELECT COUNT(*) FROM api_group_identity WHERE type='album' AND id=?",id) == 0L }
            for (candidate in unresolved) {
                val complete = oldAlbums.filter { (_, members) ->
                    val next = members.mapNotNull { albumByTrack[it.first] }.distinct()
                    next == listOf(candidate)
                }.keys.sortedWith(compareBy(::issued).thenBy { it })
                val id = complete.firstOrNull() ?: java.util.UUID.randomUUID().toString()
                execute("INSERT OR IGNORE INTO api_album_issued(id) VALUES(?)",id)
                execute("INSERT INTO api_group_identity(type,key,id) VALUES('album',?,?) ON CONFLICT(type,key) DO UPDATE SET id=excluded.id",candidate,id)
                execute("UPDATE track SET album_id=? WHERE album_id=?",id,candidate)
                albumByTrack.replaceAll { _, album -> if (album == candidate) id else album }
            }
            // A release can acquire an existing key before a complete retag is considered above.
            // Include the former complete groups too, so that the oldest issued id still wins.
            val releaseGroups = query("SELECT DISTINCT album_id,mbz_album_id FROM track WHERE mbz_album_id IS NOT NULL AND mbz_album_id<>''") {
                it.string("mbz_album_id") to it.string("album_id")
            }.groupBy({ it.first }, { it.second })
            val completeAlbumsByRelease = oldAlbums.mapNotNull { (id, members) ->
                val release = releaseByTrack[members.first().first]?.takeIf { it.isNotEmpty() }
                if (release != null && members.all { releaseByTrack[it.first] == release }) release to id else null
            }.groupBy({ it.first }, { it.second })
            val releaseMerges = LinkedHashMap<String,String>()
            for ((release, current) in releaseGroups) {
                val complete = completeAlbumsByRelease[release].orEmpty()
                val candidates = (current + complete).distinct()
                candidates.forEach { execute("INSERT OR IGNORE INTO api_album_issued(id) VALUES(?)",it) }
                val survivor = candidates.minWithOrNull(compareBy(::issued).thenBy { it }) ?: continue
                for (loser in candidates.filter { it != survivor }) {
                    execute("UPDATE track SET album_id=? WHERE album_id=?",survivor,loser)
                    execute("UPDATE api_group_identity SET id=? WHERE type='album' AND id=?",survivor,loser)
                    releaseMerges[loser] = survivor
                }
            }
            // Preserve a completely renamed artist group, including all credit roles.
            for ((oldId,members) in oldArtistMembers) {
                if (long("SELECT COUNT(*) FROM track_artist WHERE artist_id=?",oldId)>0) continue
                val next=artistSuccessors(members)
                if(next.size == 1 && next.single() !in oldArtistMembers) {
                    val replacement=next.single()
                    execute("UPDATE track SET artist_id=? WHERE artist_id=?",oldId,replacement)
                    execute("UPDATE track SET album_artist_id=? WHERE album_artist_id=?",oldId,replacement)
                    execute("UPDATE track_artist SET artist_id=? WHERE artist_id=?",oldId,replacement)
                    execute("UPDATE api_group_identity SET id=? WHERE type='artist' AND id=?",oldId,replacement)
                }
            }
            regroup(folderArt)
            // Track artwork can inherit a folder image, so image changes must invalidate the
            // track representation as well as the album and artist representations.
            if(artChanged) execute("INSERT INTO api_journal(type,id,op,at) SELECT 'track',id,'upsert',? FROM track",now)
            for ((oldId,survivor) in releaseMerges) {
                remapUserItem("album",oldId,survivor)
                recordReplacement("album",oldId,listOf(survivor),"merged",now)
            }
            for ((oldId,members) in oldAlbums) {
                if (oldId in releaseMerges) continue
                if (long("SELECT COUNT(*) FROM album WHERE id=?",oldId)>0) continue
                val next=members.mapNotNull { string("SELECT album_id FROM track WHERE id=?",it.first) }.distinct()
                if(next.size == 1) { remapUserItem("album",oldId,next.single()); recordReplacement("album",oldId,next,"merged",now) }
                else if(next.size > 1) recordReplacement("album",oldId,next,"split",now)
            }
            for ((oldId,members) in oldArtistMembers) {
                if (long("SELECT COUNT(*) FROM artist WHERE id=?",oldId)>0) continue
                val next=artistSuccessors(members)
                if(next.size == 1) { remapUserItem("artist",oldId,next.single()); recordReplacement("artist",oldId,next,"merged",now) }
                else if(next.size > 1) recordReplacement("artist",oldId,next,"split",now)
            }
            execute("INSERT INTO meta(key, value) VALUES('last_scan', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", Instant.ofEpochMilli(now).toString())
            execute("INSERT INTO meta(key, value) VALUES('folder_count', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", folderArt.size.toString())
            execute("INSERT INTO meta(key, value) VALUES('folder_art_stamp', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", artStamp)
        }
        val result = ScanResult(added = probed.count { existing[it.path] == null }, updated = probed.count { existing[it.path] != null }, removed = removed.size, unchanged = unchanged, failed = failed)
        log.info("scan finished in {} ms: {}", clock() - started, result)
        return result
    }

    private fun Db.Session.remapUserItem(type: String, old: String, replacement: String) {
        val clocksSuppressed = if(long("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_state_mutation'")>0) {
            query("SELECT user_id FROM annotation WHERE item_id IN (?,?) UNION SELECT user_id FROM library_item WHERE item_id IN (?,?)",old,replacement,old,replacement) { it.string("user_id") }.flatMap { user ->
                listOf(old,replacement).mapNotNull { item ->
                    if(execute("INSERT OR IGNORE INTO api_state_mutation(user_id,item_id) VALUES(?,?)",user,item)>0) user to item else null
                }
            }
        } else emptyList()
        val ratings = if (long("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_rating'") > 0) {
            query("""SELECT r.user_id,r.item_id,r.value,r.legacy_value,COALESCE(a.rating,0) AS survivor_rating
                FROM api_rating r LEFT JOIN annotation a ON a.user_id=r.user_id AND a.item_id=? AND a.item_type=?
                WHERE r.item_id IN (?,?) ORDER BY (r.item_id=?) DESC""",replacement,type,old,replacement,replacement) { r ->
                if (r.string("item_id") == replacement || r.int("survivor_rating") == 0) listOf<Any?>(r.string("user_id"),replacement,r.int("value"),r.int("legacy_value")) else null
            }.filterNotNull().distinctBy { it[0] }
        } else emptyList()
        if (long("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_state_clock'") > 0) {
            execute("""INSERT INTO api_state_clock(user_id,item_id,field,at)
                SELECT user_id,?,field,at FROM api_state_clock WHERE item_id=?
                ON CONFLICT(user_id,item_id,field) DO UPDATE SET at=MAX(api_state_clock.at,excluded.at)""",replacement,old)
            execute("DELETE FROM api_state_clock WHERE item_id=?",old)
        }
        execute("""INSERT INTO annotation SELECT user_id, item_type, ?, starred, rating, play_count, played FROM annotation
            WHERE item_type = ? AND item_id = ?
            ON CONFLICT(user_id,item_type,item_id) DO UPDATE SET
            starred = CASE WHEN annotation.starred IS NULL THEN excluded.starred WHEN excluded.starred IS NULL THEN annotation.starred ELSE MIN(annotation.starred,excluded.starred) END,
            rating = CASE WHEN annotation.rating > 0 THEN annotation.rating ELSE excluded.rating END,
            play_count = annotation.play_count + excluded.play_count,
            played = MAX(COALESCE(annotation.played,0), COALESCE(excluded.played,0))""", replacement, type, old)
        execute("INSERT INTO library_item SELECT user_id, item_type, ?, added FROM library_item WHERE item_type = ? AND item_id = ? ON CONFLICT(user_id,item_type,item_id) DO UPDATE SET added=MIN(library_item.added,excluded.added)", replacement, type, old)
        execute("UPDATE api_group_identity SET id=? WHERE type=? AND id=?",replacement,type,old)
        execute("DELETE FROM annotation WHERE item_type = ? AND item_id = ?", type, old)
        execute("DELETE FROM library_item WHERE item_type = ? AND item_id = ?", type, old)
        if (long("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_rating'") > 0) {
            execute("DELETE FROM api_rating WHERE item_id=?",old)
            ratings.forEach { row -> execute("INSERT INTO api_rating(user_id,item_id,value,legacy_value) VALUES(?,?,?,?) ON CONFLICT(user_id,item_id) DO UPDATE SET value=excluded.value,legacy_value=excluded.legacy_value",*row.toTypedArray()) }
        }
        clocksSuppressed.forEach { (user,item) -> execute("DELETE FROM api_state_mutation WHERE user_id=? AND item_id=?",user,item) }
    }

    private fun Db.Session.groupId(type: String, key: String): String {
        val known = string("SELECT id FROM api_group_identity WHERE type=? AND key=?",type,key)
        if (known != null) return known
        val id = java.util.UUID.randomUUID().toString()
        execute("INSERT INTO api_group_identity VALUES(?,?,?)",type,key,id)
        return id
    }

    private fun Db.Session.recordReplacement(type: String,id: String,next: List<String>,reason: String,at:Long) {
        if (reason == "split") execute("DELETE FROM api_group_identity WHERE type=? AND id=?",type,id)
        val replacements = kotlinx.serialization.json.JsonArray(next.map(::JsonPrimitive)).toString()
        execute("INSERT INTO api_identity(type,id,reason,replaced_by,deleted_at) VALUES(?,?,?,?,?) ON CONFLICT(type,id) DO UPDATE SET reason=excluded.reason,replaced_by=excluded.replaced_by,deleted_at=excluded.deleted_at",type,id,reason,replacements,at)
        execute("INSERT INTO api_journal(type,id,op,at,reason,replaced_by) VALUES(?,?,'delete',?,?,?)",type,id,at,reason,replacements)
    }

    private data class FileEntry(val relative: String, val parent: String, val name: String, val mtime: Long, val size: Long, val sidecar: String?)

    private fun walk(root: Path, directory: Path, files: MutableList<FileEntry>, folderArt: MutableMap<String, String>) {
        val children = Files.list(directory).use { stream -> stream.toList() }
        val relativeDir = root.relativize(directory).toString().replace('\\', '/')
        val names = children.filter { com.legitimateapps.dulcet.server.musicFile(root, it) != null }.associateBy { it.name.lowercase() }
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
                Files.readAttributes(com.legitimateapps.dulcet.server.musicFile(root, child) ?: continue, java.nio.file.attribute.BasicFileAttributes::class.java)
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
