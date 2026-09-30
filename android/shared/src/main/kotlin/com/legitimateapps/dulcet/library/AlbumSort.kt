package com.legitimateapps.dulcet.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.shared.R

/**
 * The orders the Albums screen offers, one implementation for the phone and the TV, at parity with the
 * Apple shells' sort picker: each is a `getAlbumList2` type the server orders (§16.9), so choosing one
 * opens that list from its start. The choice is remembered on this device, as Apple remembers it,
 * whatever account is signed in.
 */
public object AlbumSort {
    /** In the order the picker lists them: the Apple shells' `sortChoices`. */
    public val CHOICES: List<AndroidAlbumListType> = listOf(
        AndroidAlbumListType.AlphabeticalByName,
        AndroidAlbumListType.AlphabeticalByArtist,
        AndroidAlbumListType.Newest,
        AndroidAlbumListType.Recent,
        AndroidAlbumListType.Frequent,
        AndroidAlbumListType.Highest,
        AndroidAlbumListType.Random,
    )

    public val DEFAULT: AndroidAlbumListType = AndroidAlbumListType.AlphabeticalByName

    internal const val PREFERENCES = "dulcet.ui"
    internal const val KEY = "library.albumSort"

    /** The order last chosen on this device; the default when none was, or what was stored is not a choice. */
    public fun load(context: Context): AndroidAlbumListType {
        val stored = runCatching { context.getSharedPreferences(PREFERENCES, 0).getString(KEY, null) }.getOrNull()
        return CHOICES.firstOrNull { it.name == stored } ?: DEFAULT
    }

    public fun save(context: Context, type: AndroidAlbumListType) {
        require(type in CHOICES) { "not an album sort" }
        runCatching { context.getSharedPreferences(PREFERENCES, 0).edit().putString(KEY, type.name).apply() }
    }

    /** The order's name in the picker, in the shared words. */
    public fun titleResource(type: AndroidAlbumListType): Int = when (type) {
        AndroidAlbumListType.AlphabeticalByName -> R.string.library_sort_title
        AndroidAlbumListType.AlphabeticalByArtist -> R.string.library_sort_artist
        AndroidAlbumListType.Newest -> R.string.library_sort_recently_added
        AndroidAlbumListType.Recent -> R.string.library_sort_recently_played
        AndroidAlbumListType.Frequent -> R.string.library_sort_most_played
        AndroidAlbumListType.Highest -> R.string.library_sort_top_rated
        AndroidAlbumListType.Random -> R.string.library_sort_random
        else -> throw IllegalArgumentException("not an album sort")
    }

    /** The tag suffix a picker's entry for [type] carries: `alphabeticalbyname`, `newest`, … */
    public fun tag(type: AndroidAlbumListType): String = type.name.lowercase()
}

/** The order the Albums screen shows, read once from this device and written back on every change. */
public class AlbumSortState internal constructor(private val context: Context, initial: AndroidAlbumListType) {
    public var value: AndroidAlbumListType by mutableStateOf(initial)
        private set

    public fun choose(type: AndroidAlbumListType) {
        if (type == value) return
        AlbumSort.save(context, type)
        value = type
    }
}

@Composable
public fun rememberAlbumSort(): AlbumSortState {
    val context = LocalContext.current
    return remember(context) { AlbumSortState(context.applicationContext ?: context, AlbumSort.load(context)) }
}
