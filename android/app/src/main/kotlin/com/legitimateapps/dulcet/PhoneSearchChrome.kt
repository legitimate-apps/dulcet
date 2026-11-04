package com.legitimateapps.dulcet

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.DulcetIcons

/** Scope belongs to the search journey, while each presenter keeps its own query and results. */
internal val LocalPhoneSearchScopes = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

internal object PhoneSearchIcons {
    val Clear = ImageVector.Builder("Clear", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"), fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
    val History = ImageVector.Builder("History", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M13 3a9 9 0 0 0-9 9H1l4 4 4-4H6a7 7 0 1 1 2.05 4.95l-1.42 1.42A9 9 0 1 0 13 3zm-1 5v5l4.3 2.55.7-1.2-3.5-2.1V8z"), fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}

/** The destination itself is the search view: one Material search input for every scope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSearchField(query: String, change: (String) -> Unit, search: () -> Unit,
    tag: String, hint: String = stringResource(R.string.restyle_search_hint), avatar: Boolean = true, submitTag: String? = null) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(query = query, onQueryChange = change,
                onSearch = { search() }, expanded = false, onExpandedChange = {},
                modifier = Modifier.fillMaxWidth().testTag(tag),
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { IconButton(search, submitTag?.let { Modifier.testTag(it) } ?: Modifier) {
                    Icon(DulcetIcons.Search, stringResource(R.string.discover_playlists_submit))
                } },
                trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) IconButton(onClick = { change("") }, modifier = Modifier.testTag("$tag.clear")) {
                        Icon(PhoneSearchIcons.Clear, stringResource(R.string.restyle_search_clear))
                    }
                    if (avatar) AccountAvatarAction()
                } },
            )
        },
        expanded = false, onExpandedChange = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), windowInsets = SearchBarDefaults.windowInsets,
    ) {}
}

@Composable
internal fun PhoneSearchEmpty(title: String, tag: String, suggestion: String = stringResource(R.string.restyle_search_suggestion)) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(DulcetIcons.Search, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

@Composable
internal fun discoverRelativeAge(seconds: Long): String = if (seconds < 60) stringResource(R.string.discover_just_now) else
    DateUtils.getRelativeTimeSpanString(System.currentTimeMillis() - seconds.coerceAtLeast(0) * 1_000L,
        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
internal fun DiscoverLoadingRows(tag: String, cards: Boolean = false, cardWidth: androidx.compose.ui.unit.Dp = 160.dp) {
    if (cards) Row(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(Modifier.fillMaxWidth().aspectRatio(1f), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
                Surface(Modifier.fillMaxWidth(0.76f).height(16.dp), shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
                Surface(Modifier.fillMaxWidth(0.52f).height(12.dp), shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {}
            }
        }
    } else Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { ListItem(headlineContent = {
            Surface(Modifier.fillMaxWidth(0.7f).height(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
        }, supportingContent = { Surface(Modifier.fillMaxWidth(0.45f).height(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {} },
            leadingContent = { Surface(Modifier.size(56.dp), shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest) {} }) }
    }
}
