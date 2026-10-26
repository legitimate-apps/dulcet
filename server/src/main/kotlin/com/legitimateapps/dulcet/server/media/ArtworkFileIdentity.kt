package com.legitimateapps.dulcet.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** A metadata-only identity is not trusted to detect edits when Unix ctime is unavailable.
 * Callers must first validate the path against the music root.
 */
internal data class FileMetadataIdentity(val stamp: String, val hasChangeTime: Boolean)

/** Track scans never read audio bytes just to establish an unchanged-file identity. */
internal fun trackFileIdentity(file: Path): FileMetadataIdentity = fileIdentity(file, contentFallback = false)

/** Artwork keeps the content-digest fallback for providers without Unix ctime. */
internal fun artworkFileIdentity(file: Path): String = fileIdentity(file, contentFallback = true).stamp

private fun fileIdentity(file: Path, contentFallback: Boolean): FileMetadataIdentity {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
    val changeTime = try { Files.getAttribute(file, "unix:ctime") }
        catch (_: UnsupportedOperationException) { null }
        catch (_: IllegalArgumentException) { null }
    val content = if (changeTime == null && contentFallback) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        java.util.HexFormat.of().formatHex(digest.digest())
    } else ""
    val stamp = "$file|${attributes.size()}|${attributes.lastModifiedTime()}|$changeTime|${attributes.fileKey()}|$content"
    val digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()))
    // Keep trusted stamps byte-for-byte compatible with existing baselines. A metadata-only
    // baseline is distinguishable so gaining ctime later can adopt it without a mass probe.
    return FileMetadataIdentity(if (changeTime == null && !contentFallback) "metadata-only:$digest" else digest, changeTime != null)
}
