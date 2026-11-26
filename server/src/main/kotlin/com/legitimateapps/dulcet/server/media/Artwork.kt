package com.legitimateapps.dulcet.server.media

import com.legitimateapps.dulcet.server.db.Db
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** [cacheSeconds] is how long a client may keep the bytes for a stable id; null means indefinitely. */
class Image(val bytes: ByteArray, val contentType: String, val cacheSeconds: Long? = null) {
    fun cachedFor(seconds: Long?) = if (seconds == cacheSeconds) this else Image(bytes, contentType, seconds)
}

/**
 * Resolves a cover art id (`al-`, `mf-`, `ar-`, `pl-` or a bare catalog id) to image bytes: a
 * folder image (cover.jpg and friends) or the picture embedded in a file. Resized copies are
 * written once to a disk cache keyed by the source and the size. Successful original embedded
 * pictures are cached by validated source metadata identity as well. An item with no picture gets a
 * generated placeholder, as other OpenSubsonic servers do, so a client never shows a broken image.
 */
class Artwork(private val db: Db, private val musicFolder: Path, private val cacheFolder: Path, private val ffmpeg: String, private val access: com.legitimateapps.dulcet.server.auth.AccountAccess,
              /** An artist photo from the Connections layer, asked only after the library's own files. */
              private val artistImages: com.legitimateapps.dulcet.server.integrations.Integrations? = null) {

    private sealed interface Source {
        data class FolderFile(val path: Path) : Source
        data class Embedded(val path: Path) : Source
        class Remote(val key: String, val image: Image) : Source
    }

    /** null means the id names nothing at all (error 70); a known item without art gets a placeholder. */
    fun image(id: String, size: Int?, user: com.legitimateapps.dulcet.server.auth.User? = null): Image? {
        if(user != null && !access.canReadArtwork(user,id)) return null
        val playlistId = if (id.startsWith("pl-")) id.removePrefix("pl-") else id.takeIf { exists("playlist", it) }
        if (playlistId != null) {
            val visible = user != null && db.read { long("SELECT COUNT(*) FROM playlist WHERE id = ? AND (owner_id = ? OR public = 1 OR ? = 1)", playlistId, user.id, user.isAdmin) > 0 }
            if (!visible) return null
        }
        var cacheSeconds: Long? = null
        val source = when {
            id.startsWith("al-") -> albumSource(id.removePrefix("al-"), user) ?: if (exists("album", id.removePrefix("al-"))) null else return null
            id.startsWith("mf-") -> trackSource(id.removePrefix("mf-"), user) ?: if (exists("track", id.removePrefix("mf-"))) null else return null
            id.startsWith("ar-") -> artistSource(id.removePrefix("ar-"), user).also { cacheSeconds = it.second }.first ?: if (exists("artist", id.removePrefix("ar-"))) null else return null
            id.startsWith("pl-") -> playlistSource(id.removePrefix("pl-"), user!!) ?: if (exists("playlist", id.removePrefix("pl-"))) null else return null
            exists("album", id) -> albumSource(id, user)
            exists("track", id) -> trackSource(id, user)
            exists("artist", id) -> artistSource(id, user).also { cacheSeconds = it.second }.first
            playlistId != null -> playlistSource(playlistId, user!!)
            else -> return null
        }
        if (source == null) return placeholder(size).cachedFor(cacheSeconds)
        val original = runCatching { load(source) }.getOrNull() ?: return placeholder(size).cachedFor(cacheSeconds)
        if (size == null || size <= 0) return original.cachedFor(cacheSeconds)
        return resized(source, original, size).cachedFor(cacheSeconds)
    }

    private fun exists(table: String, id: String): Boolean = db.read { long("SELECT COUNT(*) FROM $table WHERE id = ?", id) > 0 }

    private fun albumSource(albumId: String, user: com.legitimateapps.dulcet.server.auth.User? = null): Source? {
        val row = db.read { single("SELECT folder_art, art_track_id FROM album WHERE id = ?", albumId) { it.stringOrNull("folder_art") to it.stringOrNull("art_track_id") } } ?: return null
        row.first?.let { art ->
            val directory = Path.of(art).parent?.toString().orEmpty()
            if(user == null || db.read { long("SELECT COUNT(*) FROM track t WHERE t.album_id=? AND t.parent_dir=? AND ${access.catalogPredicate(user.id,"track","t")}",albumId,directory)>0 })
                return Source.FolderFile(musicFolder.resolve(art))
        }
        row.second?.let { trackId -> if(user == null || access.canReadCatalog(user.id,"track",trackId)) trackPath(trackId)?.let { return Source.Embedded(it) } }
        if(user != null) db.read { query("SELECT t.id FROM track t WHERE t.album_id=? AND t.has_embedded_art=1 AND ${access.catalogPredicate(user.id,"track","t")} ORDER BY t.id",albumId) { it.string("id") } }
            .firstNotNullOfOrNull { trackPath(it)?.let(Source::Embedded) }?.let { return it }
        return null
    }

    private fun trackSource(trackId: String, user: com.legitimateapps.dulcet.server.auth.User? = null): Source? {
        val row = db.read { single("SELECT path, has_embedded_art, album_id FROM track WHERE id = ?", trackId) { Triple(it.string("path"), it.bool("has_embedded_art"), it.string("album_id")) } } ?: return null
        if (row.second) return Source.Embedded(musicFolder.resolve(row.first))
        return albumSource(row.third,user)
    }

    /** The artist's source and how long a client may keep it: a library file indefinitely, a Connections photo a
     * day (the tool may replace it), and a stand-in (album cover or placeholder) briefly while Connections are
     * configured, so a photo that arrives later is not hidden behind a cached stand-in. */
    private fun artistSource(artistId: String, user: com.legitimateapps.dulcet.server.auth.User? = null): Pair<Source?, Long?> {
        val albums = db.read { query("SELECT album_id FROM album_artist WHERE artist_id = ?", artistId) { it.string("album_id") } }.filter { user == null || access.canReadCatalog(user.id,"album",it) }
        val artistImage = db.read {
            single("SELECT t.parent_dir FROM track t JOIN album_artist aa ON aa.album_id = t.album_id WHERE aa.artist_id = ? AND ${if(user==null) "1" else access.catalogPredicate(user.id,"track","t")} ORDER BY t.id LIMIT 1", artistId) { it.string("parent_dir") }
        }?.takeIf(String::isNotEmpty)?.let { dir -> artistFolderImage(musicFolder.resolve(dir).parent) }
        if (artistImage != null) return Source.FolderFile(artistImage) to null
        remoteArtistImage(artistId)?.let { return it to REMOTE_CACHE_SECONDS }
        return albums.firstNotNullOfOrNull { albumSource(it,user) } to (if (artistImages != null) STAND_IN_CACHE_SECONDS else null)
    }

    private fun artistFolderImage(folder: Path?): Path? = artistFolderImage(musicFolder, folder)

    private fun remoteArtistImage(artistId: String): Source? {
        val source = artistImages ?: return null
        val row = db.read { single("SELECT name, mbz_artist_id FROM artist WHERE id = ?", artistId) { it.string("name") to it.stringOrNull("mbz_artist_id") } } ?: return null
        if (row.second.isNullOrBlank()) return null
        // Library pages ask only the operator's own tools; a failure falls back to the album cover. Inside a database
        // transaction (catalog pages render artwork references there) only the cache is read, and a refresh is queued:
        // a slow tool must never hold the lock every other request and the scanner wait on.
        val ref = com.legitimateapps.dulcet.server.integrations.ArtistRef(row.second, row.first)
        val reach = com.legitimateapps.dulcet.server.integrations.Reach.Library
        val image = if (db.heldByCurrentThread()) source.cachedArtistImage(ref, reach)
            else runCatching { kotlinx.coroutines.runBlocking { source.artistImage(ref, reach) } }.getOrNull()
        image ?: return null
        return Source.Remote(image.key, Image(image.bytes, image.contentType))
    }

    /** A sized copy of an image from outside the library, through the same resize cache as library art. */
    fun sized(key: String, image: Image, size: Int): Image = resized(Source.Remote(key, image), image, size)

    private fun playlistSource(playlistId: String, user: com.legitimateapps.dulcet.server.auth.User): Source? {
        val tracks = db.read { query("SELECT track_id FROM playlist_track WHERE playlist_id = ? ORDER BY position LIMIT 20", playlistId) { it.string("track_id") } }
        return tracks.filter { access.canReadCatalog(user.id,"track",it) }.firstNotNullOfOrNull { trackSource(it,user) }
    }

    private fun trackPath(trackId: String): Path? = db.read { string("SELECT path FROM track WHERE id = ?", trackId) }?.let(musicFolder::resolve)

    private fun load(source: Source): Image? = when (source) {
        is Source.FolderFile -> com.legitimateapps.dulcet.server.musicFile(musicFolder, source.path)?.let(Files::readAllBytes)?.let { Image(it, sniff(it)) }
        is Source.Embedded -> com.legitimateapps.dulcet.server.musicFile(musicFolder, source.path)?.let(::embeddedImage)
        is Source.Remote -> source.image
    }

    // Fixed stripes avoid an unbounded path-to-lock map and duplicate concurrent extraction.
    private val embeddedLocks = Array(64) { Any() }

    /** Source resolution and account access run before this cache is consulted. */
    private fun embeddedImage(file: Path): Image? = synchronized(embeddedLocks[(file.hashCode() and Int.MAX_VALUE) % embeddedLocks.size]) {
        val key = artworkFileIdentity(file)
        val cached = cacheFolder.resolve("art/embedded").resolve(key.take(2)).resolve(key)
        if (Files.isRegularFile(cached)) return@synchronized Files.readAllBytes(cached).let { Image(it, sniff(it)) }
        val bytes = extractEmbedded(file) ?: return@synchronized null
        // Do not persist an extraction that raced a retag or file replacement.
        if (key == artworkFileIdentity(file)) {
            Files.createDirectories(cached.parent)
            val temporary = Files.createTempFile(cached.parent, "embedded-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
        }
        Image(bytes, sniff(bytes))
    }

    private fun extractEmbedded(file: Path): ByteArray? {
        val process = ProcessBuilder(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin") + AUDIO_INPUT_ARGS + listOf("-i", file.toString(), "-an", "-map", "0:v:0", "-c:v", "copy", "-frames:v", "1", "-f", "image2pipe", "-"))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = process.inputStream.readBytes()
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        return bytes.takeIf { it.isNotEmpty() && process.exitValue() == 0 }
    }

    private fun resized(source: Source, original: Image, size: Int): Image {
        val clamped = size.coerceIn(16, 2048)
        val stamp = when (source) {
            is Source.FolderFile -> "${source.path}|${runCatching { Files.getLastModifiedTime(source.path).toMillis() }.getOrDefault(0)}"
            is Source.Embedded -> "${source.path}|${runCatching { Files.getLastModifiedTime(source.path).toMillis() }.getOrDefault(0)}|embedded"
            is Source.Remote -> "remote|${source.key}"
        }
        val key = MessageDigest.getInstance("SHA-256").run { update(stamp.toByteArray()); digest(original.bytes) }.take(16).joinToString("") { "%02x".format(it) }
        val cached = cacheFolder.resolve("art").resolve(key.take(2)).resolve("${key}_$clamped.jpg")
        if (Files.isRegularFile(cached)) return Image(Files.readAllBytes(cached), "image/jpeg")
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(original.bytes)) }.getOrNull() ?: return original
        if (decoded.width <= clamped && decoded.height <= clamped) return original
        val bytes = encodeJpeg(scale(decoded, clamped))
        Files.createDirectories(cached.parent)
        val temporary = Files.createTempFile(cached.parent, "art", ".tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return Image(bytes, "image/jpeg")
    }

    private fun placeholder(size: Int?): Image {
        val edge = (size ?: 600).coerceIn(16, 1200)
        val cached = cacheFolder.resolve("art").resolve("placeholder_$edge.png")
        if (Files.isRegularFile(cached)) return Image(Files.readAllBytes(cached), "image/png")
        val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(0x2B, 0x2D, 0x33)
        g.fillRect(0, 0, edge, edge)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x6C, 0x70, 0x7A)
        // A simple eighth note: head and stem.
        val unit = edge / 10
        g.fillOval(3 * unit, 6 * unit, 3 * unit, 2 * unit)
        g.fillRect(5 * unit + unit / 2, 2 * unit, unit / 2, 5 * unit)
        g.fillRect(5 * unit + unit / 2, 2 * unit, 2 * unit, unit / 2)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        val bytes = out.toByteArray()
        Files.createDirectories(cached.parent)
        Files.write(cached, bytes)
        return Image(bytes, "image/png")
    }

    companion object {
        const val REMOTE_CACHE_SECONDS = 86_400L
        const val STAND_IN_CACHE_SECONDS = 300L
        /** Artist pictures in an artist folder, in preference order, matched in any letter case. Only `artist`: in a
         * multi-disc layout (Artist/Album/CD1) the folder above the tracks is the album, whose `folder`, `cover`,
         * `poster` or `thumb` picture is the album's own. */
        val ARTIST_FILE_NAMES = listOf("artist.jpg", "artist.jpeg", "artist.png")
        /** The library root is shared by every artist of a flat layout, so it keeps only the original names. */
        val ROOT_ARTIST_FILE_NAMES = listOf("artist.jpg", "artist.png")

        /** The picture in [folder] (the parent of an album folder) that stands for its artist, if any. The scanner
         * uses the same choice to invalidate artists when that picture changes. */
        fun artistFolderImage(root: Path, folder: Path?): Path? {
            val dir = folder?.normalize() ?: return null
            val top = root.normalize()
            if (!dir.startsWith(top)) return null
            if (dir == top) return ROOT_ARTIST_FILE_NAMES.firstNotNullOfOrNull { com.legitimateapps.dulcet.server.musicFile(root, dir.resolve(it)) }
            val names = runCatching { Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() } }.getOrNull() ?: return null
            val byName = names.groupBy { it.lowercase() }.mapValues { (_, same) -> same.min() }
            return ARTIST_FILE_NAMES.firstNotNullOfOrNull { name -> byName[name]?.let { com.legitimateapps.dulcet.server.musicFile(root, dir.resolve(it)) } }
        }

        fun sniff(bytes: ByteArray): String = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size >= 12 && String(bytes, 8, 4) == "WEBP" -> "image/webp"
            bytes.size >= 6 && String(bytes, 0, 3) == "GIF" -> "image/gif"
            else -> "application/octet-stream"
        }

        fun scale(source: BufferedImage, edge: Int): BufferedImage {
            val ratio = minOf(edge.toDouble() / source.width, edge.toDouble() / source.height)
            val width = maxOf(1, (source.width * ratio).toInt())
            val height = maxOf(1, (source.height * ratio).toInt())
            val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = target.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
            g.dispose()
            return target
        }

        fun encodeJpeg(image: BufferedImage): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.85f
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
            writer.dispose()
            return out.toByteArray()
        }
    }
}
