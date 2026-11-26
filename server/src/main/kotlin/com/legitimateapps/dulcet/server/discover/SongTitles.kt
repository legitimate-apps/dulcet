package com.legitimateapps.dulcet.server.discover

import java.text.Normalizer
import java.util.Locale

/** How Discover compares names and song versions; shared by library matching and artist popularity. */
object SongTitles {
    private val versionWords = setOf("live", "remix", "demo", "acoustic", "instrumental")
    fun normalizeName(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim().replace(Regex("\\s+"), " ")
    /** A live take, remix, demo, acoustic or instrumental version is a different song from the studio one. */
    fun sameVersion(a: String, b: String): Boolean {
        fun versions(title: String) = normalizeName(title).split(' ').filter { it in versionWords }.toSet()
        return versions(a) == versions(b)
    }
}
