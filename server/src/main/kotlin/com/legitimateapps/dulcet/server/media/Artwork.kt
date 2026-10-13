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

class Image(val bytes: ByteArray, val contentType: String)

/**
 * Resolves a cover art id (`al-`, `mf-`, `ar-`, `pl-` or a bare catalog id) to image bytes: a
 * folder image (cover.jpg and friends) or the picture embedded in a file. Resized copies are
 * written once to a disk cache keyed by the source and the size. An item with no picture gets a
 * generated placeholder, as other OpenSubsonic servers do, so a client never shows a broken image.
 */
class Artwork(private val db: Db, private val musicFolder: Path, private val cacheFolder: Path, private val ffmpeg: String, private val access: com.legitimateapps.dulcet.server.auth.AccountAccess) {

    private sealed interface Source {
        data class FolderFile(val path: Path) : Source
        data class Embedded(val path: Path) : Source
    }

    /** null means the id names nothing at all (error 70); a known item without art gets a placeholder. */
    fun image(id: String, size: Int?, user: com.legitimateapps.dulcet.server.auth.User? = null): Image? {
        if(user != null && !access.canReadArtwork(user,id)) return null
        val playlistId = if (id.startsWith("pl-")) id.removePrefix("pl-") else id.takeIf { exists("playlist", it) }
        if (playlistId != null) {
            val visible = user != null && db.read { long("SELECT COUNT(*) FROM playlist WHERE id = ? AND (owner_id = ? OR public = 1 OR ? = 1)", playlistId, user.id, user.isAdmin) > 0 }
            if (!visible) return null
        }
        val source = when {
            id.startsWith("al-") -> albumSource(id.removePrefix("al-"), user) ?: if (exists("album", id.removePrefix("al-"))) null else return null
            id.startsWith("mf-") -> trackSource(id.removePrefix("mf-"), user) ?: if (exists("track", id.removePrefix("mf-"))) null else return null
            id.startsWith("ar-") -> artistSource(id.removePrefix("ar-"), user) ?: if (exists("artist", id.removePrefix("ar-"))) null else return null
            id.startsWith("pl-") -> playlistSource(id.removePrefix("pl-"), user!!) ?: if (exists("playlist", id.removePrefix("pl-"))) null else return null
            exists("album", id) -> albumSource(id, user)
            exists("track", id) -> trackSource(id, user)
            exists("artist", id) -> artistSource(id, user)
            playlistId != null -> playlistSource(playlistId, user!!)
            else -> return null
        }
        if (source == null) return placeholder(size)
        val original = runCatching { load(source) }.getOrNull() ?: return placeholder(size)
        if (size == null || size <= 0) return original
        return resized(source, original, size)
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

    private fun artistSource(artistId: String, user: com.legitimateapps.dulcet.server.auth.User? = null): Source? {
        val albums = db.read { query("SELECT album_id FROM album_artist WHERE artist_id = ?", artistId) { it.string("album_id") } }.filter { user == null || access.canReadCatalog(user.id,"album",it) }
        val artistImage = db.read {
            single("SELECT t.parent_dir FROM track t JOIN album_artist aa ON aa.album_id = t.album_id WHERE aa.artist_id = ? AND ${if(user==null) "1" else access.catalogPredicate(user.id,"track","t")} ORDER BY t.id LIMIT 1", artistId) { it.string("parent_dir") }
        }?.takeIf(String::isNotEmpty)?.let { dir -> listOf("artist.jpg", "artist.png").mapNotNull { com.legitimateapps.dulcet.server.musicFile(musicFolder, musicFolder.resolve(dir).parent.resolve(it)) }.firstOrNull() }
        if (artistImage != null) return Source.FolderFile(artistImage)
        return albums.firstNotNullOfOrNull { albumSource(it,user) }
    }

    private fun playlistSource(playlistId: String, user: com.legitimateapps.dulcet.server.auth.User): Source? {
        val tracks = db.read { query("SELECT track_id FROM playlist_track WHERE playlist_id = ? ORDER BY position LIMIT 20", playlistId) { it.string("track_id") } }
        return tracks.filter { access.canReadCatalog(user.id,"track",it) }.firstNotNullOfOrNull { trackSource(it,user) }
    }

    private fun trackPath(trackId: String): Path? = db.read { string("SELECT path FROM track WHERE id = ?", trackId) }?.let(musicFolder::resolve)

    private fun load(source: Source): Image? = when (source) {
        is Source.FolderFile -> com.legitimateapps.dulcet.server.musicFile(musicFolder, source.path)?.let(Files::readAllBytes)?.let { Image(it, sniff(it)) }
        is Source.Embedded -> com.legitimateapps.dulcet.server.musicFile(musicFolder, source.path)?.let(::extractEmbedded)?.let { Image(it, sniff(it)) }
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
        }
        val key = MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
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
