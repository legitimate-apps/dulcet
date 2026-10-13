package com.legitimateapps.dulcet.server.scan

import com.legitimateapps.dulcet.server.Ids
import com.legitimateapps.dulcet.server.UNKNOWN_ALBUM
import com.legitimateapps.dulcet.server.UNKNOWN_ARTIST
import com.legitimateapps.dulcet.server.VARIOUS_ARTISTS
import com.legitimateapps.dulcet.server.fold

data class Credit(val id: String, val name: String)

/** One track as the catalog stores it, derived from a probe and the file's place in the folder. */
data class TrackInfo(
    val path: String,
    val parentDir: String,
    val mtime: Long,
    val size: Long,
    val title: String,
    val sortTitle: String?,
    val album: String,
    val albumId: String,
    val artists: List<Credit>,
    val albumArtists: List<Credit>,
    val composers: List<Credit>,
    val trackNo: Int?,
    val discNo: Int?,
    val discSubtitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val genres: List<String>,
    val duration: Double,
    val bitRate: Int,
    val sampleRate: Int?,
    val channels: Int?,
    val bitDepth: Int?,
    val codec: String?,
    val suffix: String,
    val contentType: String,
    val compilation: Boolean,
    val mbzTrackId: String?,
    val mbzRecordingId: String?,
    val mbzAlbumId: String?,
    val mbzArtistId: String?,
    val mbzAlbumArtistId: String?,
    val hasEmbeddedArt: Boolean,
    val lyricsSidecar: String?,
    val embeddedLyrics: Map<String, String>,
    val comment: String?,
    val bpm: Int?,
    val rgTrackGain: Double?,
    val rgTrackPeak: Double?,
    val rgAlbumGain: Double?,
    val rgAlbumPeak: Double?,
) {
    val artistDisplay: String get() = artists.joinToString(DISPLAY_JOIN) { it.name }
    val albumArtistDisplay: String get() = albumArtists.joinToString(DISPLAY_JOIN) { it.name }

    /** Content-derived id: the MusicBrainz release track id when tagged, else album + position + title. */
    fun candidateId(): String =
        if (mbzTrackId != null) Ids.of("track", "mbz", mbzTrackId)
        else Ids.of("track", albumId, discNo?.toString(), trackNo?.toString(), fold(title))

    fun searchText(): String = fold(listOf(title, album, artistDisplay, albumArtistDisplay).joinToString(" "))

    companion object {
        const val DISPLAY_JOIN = " • "

        fun artistId(name: String): String = Ids.of("artist", fold(name))

        fun from(path: String, parentDir: String, fileName: String, mtime: Long, size: Long, tags: ProbedTags, lyricsSidecar: String?): TrackInfo {
            val suffix = fileName.substringAfterLast('.', "").lowercase()
            val title = tags.tag("title") ?: fileName.substringBeforeLast('.')
            val compilation = tags.tag("compilation", "cpil", "tcmp")?.let { it == "1" || it.equals("true", true) } == true
            val artistNames = splitArtists(tags.tag("artists") ?: tags.tag("artist"), artistSeparators = true)
                .ifEmpty { listOf(UNKNOWN_ARTIST) }
            val albumArtistNames = splitArtists(tags.tag("albumartists") ?: tags.tag("albumartist"), artistSeparators = false)
                .ifEmpty { if (compilation) listOf(VARIOUS_ARTISTS) else artistNames }
            val albumName = tags.tag("album") ?: UNKNOWN_ALBUM
            val mbzAlbumId = tags.tag("musicbrainzalbumid")
            val albumArtistKey = albumArtistNames.joinToString(";") { fold(it) }
            val albumId = if (mbzAlbumId != null) Ids.of("album", "mbz", mbzAlbumId)
            else Ids.of("album", albumArtistKey, fold(albumName))
            val date = tags.tag("date", "originaldate", "year", "tyer", "tdrc")
            val rg = { key: String -> tags.tag(key)?.replace(Regex("(?i)\\s*db$"), "")?.trim()?.toDoubleOrNull() }
            return TrackInfo(
                path = path,
                parentDir = parentDir,
                mtime = mtime,
                size = size,
                title = title,
                sortTitle = tags.tag("titlesort", "sorttitle"),
                album = albumName,
                albumId = albumId,
                artists = artistNames.map { Credit(artistId(it), it) },
                albumArtists = albumArtistNames.map { Credit(artistId(it), it) },
                composers = splitArtists(tags.tag("composer"), artistSeparators = false).map { Credit(artistId(it), it) },
                trackNo = leadingInt(tags.tag("track", "tracknumber", "trck")),
                discNo = leadingInt(tags.tag("disc", "discnumber", "tpos")),
                discSubtitle = tags.tag("discsubtitle", "setsubtitle", "tsst"),
                year = date?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
                releaseDate = date?.takeIf { Regex("\\d{4}(-\\d{2}(-\\d{2})?)?").matches(it) },
                genres = (tags.tag("genre") ?: "").split(';').map(String::trim).filter(String::isNotEmpty).distinct(),
                duration = tags.duration,
                bitRate = tags.bitRateKbps,
                sampleRate = tags.sampleRate,
                channels = tags.channels,
                bitDepth = tags.bitDepth,
                codec = tags.codec,
                suffix = suffix,
                contentType = contentTypeFor(suffix),
                compilation = compilation,
                mbzTrackId = tags.tag("musicbrainzreleasetrackid"),
                mbzRecordingId = tags.tag("musicbrainztrackid", "musicbrainzrecordingid"),
                mbzAlbumId = mbzAlbumId,
                mbzArtistId = tags.tag("musicbrainzartistid")?.substringBefore(';'),
                mbzAlbumArtistId = tags.tag("musicbrainzalbumartistid")?.substringBefore(';'),
                hasEmbeddedArt = tags.hasEmbeddedArt,
                lyricsSidecar = lyricsSidecar,
                embeddedLyrics = tags.lyrics,
                comment = tags.tag("comment"),
                bpm = leadingInt(tags.tag("bpm", "tbpm", "tmpo")),
                rgTrackGain = rg("replaygaintrackgain"),
                rgTrackPeak = rg("replaygaintrackpeak"),
                rgAlbumGain = rg("replaygainalbumgain"),
                rgAlbumPeak = rg("replaygainalbumpeak"),
            )
        }

        private val FEATURING = Regex("\\s+(?:feat\\.|ft\\.|featuring)\\s+", RegexOption.IGNORE_CASE)

        /**
         * Splits a credit on `;` (how ffprobe joins repeated Vorbis fields and how multi-value
         * ID3v2.4 frames read). Track artists also split on "feat."; a `/` never splits, so
         * "AC/DC" stays one artist.
         */
        fun splitArtists(value: String?, artistSeparators: Boolean): List<String> {
            if (value.isNullOrBlank()) return emptyList()
            var parts = value.split(';', '\u0000').map(String::trim).filter(String::isNotEmpty)
            if (artistSeparators) parts = parts.flatMap { it.split(FEATURING) }.map(String::trim).filter(String::isNotEmpty)
            return parts.distinctBy(::fold)
        }

        private fun leadingInt(value: String?): Int? = value?.trim()?.let { Regex("^\\d+").find(it)?.value?.toIntOrNull() }

        fun contentTypeFor(suffix: String): String = when (suffix.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "m4b", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aif", "aiff" -> "audio/aiff"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "wv" -> "audio/x-wavpack"
            "dsf" -> "audio/dsd"
            "mka" -> "audio/x-matroska"
            else -> "application/octet-stream"
        }

        val AUDIO_SUFFIXES = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "ape", "wv", "dsf", "mka")
    }
}
