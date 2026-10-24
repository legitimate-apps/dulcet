package com.legitimateapps.dulcet.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Metadata identity includes inode change time: preserving mtime cannot hide an in-place edit.
 * Providers without Unix change time fall back to a digest, preserving correctness at greater IO cost.
 * The caller must first validate the path against the music root.
 */
internal fun artworkFileIdentity(file: Path): String {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
    val changeTime = try { Files.getAttribute(file, "unix:ctime") }
        catch (_: UnsupportedOperationException) { null }
        catch (_: IllegalArgumentException) { null }
    val content = if (changeTime == null) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        java.util.HexFormat.of().formatHex(digest.digest())
    } else ""
    val stamp = "$file|${attributes.size()}|${attributes.lastModifiedTime()}|$changeTime|${attributes.fileKey()}|$content"
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()))
}
