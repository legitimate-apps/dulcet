package com.legitimateapps.dulcet.server

import java.security.MessageDigest
import java.text.Normalizer

/** Case- and accent-insensitive key used for search and for grouping names that differ only in case. */
fun fold(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
    val builder = StringBuilder(decomposed.length)
    for (character in decomposed) {
        if (Character.getType(character) == Character.NON_SPACING_MARK.toInt()) continue
        builder.append(character.lowercaseChar())
    }
    return builder.toString().replace(Regex("\\s+"), " ").trim()
}

/**
 * Opaque, deterministic identifiers. They are derived from content (MusicBrainz ids or tags), not
 * from the file path, so moving a folder keeps every id and with it every star, rating, play count,
 * playlist entry and My Library membership. Clients must treat them as opaque strings.
 */
object Ids {
    fun of(kind: String, vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(kind.toByteArray())
        for (part in parts) {
            digest.update(0)
            digest.update((part ?: "").toByteArray())
        }
        return digest.digest().take(11).joinToString("") { "%02x".format(it) }.let { hex ->
            // Base36 keeps ids short and URL-safe without looking like an integer.
            java.math.BigInteger(hex, 16).toString(36).padStart(17, '0')
        }
    }
}

const val UNKNOWN_ARTIST = "[Unknown Artist]"
const val UNKNOWN_ALBUM = "[Unknown Album]"
const val VARIOUS_ARTISTS = "Various Artists"

private val ARTICLES = listOf("The ", "El ", "La ", "Los ", "Las ", "Le ", "Les ")
const val IGNORED_ARTICLES = "The El La Los Las Le Les"

fun sortName(name: String): String {
    for (article in ARTICLES) {
        if (name.length > article.length && name.startsWith(article, ignoreCase = true)) {
            return name.substring(article.length)
        }
    }
    return name
}

fun indexLetter(name: String): String {
    val first = fold(sortName(name)).firstOrNull() ?: return "#"
    return if (first in 'a'..'z') first.uppercase() else "#"
}
