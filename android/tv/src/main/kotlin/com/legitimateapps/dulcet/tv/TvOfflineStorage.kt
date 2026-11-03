package com.legitimateapps.dulcet.tv

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.downloads.rememberOfflineStorage
import com.legitimateapps.dulcet.shared.R as SharedR

/**
 * Downloads & storage, as settings rows (spec §12.4): an Automatic downloads switch row, usage as
 * trailing values, the cache limit as a row opening a radio choice, and Clear cache as one action
 * whose result is said. A TV has no cellular link, so nothing here mentions one.
 */
@Composable
internal fun TvOfflineStorage() {
    val storage = rememberOfflineStorage() ?: return
    val context = LocalContext.current
    var choosingBudget by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }
    val budgets = listOf(0L, 256L * 1024 * 1024, 1024L * 1024 * 1024)
    Column(Modifier.testTag("offline.storage"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(SharedR.string.offline_storage_title), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineSmall)
        androidx.tv.material3.ListItem(
            selected = false,
            onClick = { storage.actions.automatic(!storage.settings.automaticDownloadsEnabled) },
            modifier = Modifier.tvFocus("offline.automatic"),
            supportingContent = {
                if (storage.settings.automaticDownloadsEnabled) {
                    Text(
                        stringResource(SharedR.string.offline_automatic_progress,
                            storage.stats.completedAutomaticCount,
                            storage.stats.completedAutomaticCount + storage.stats.queuedAutomaticCount),
                        modifier = Modifier.testTag("offline.automatic.progress"),
                    )
                } else {
                    Text(stringResource(R.string.tv_automatic_downloads_hint))
                }
            },
            trailingContent = {
                Switch(checked = storage.settings.automaticDownloadsEnabled, onCheckedChange = null)
            },
            headlineContent = { Text(stringResource(SharedR.string.offline_automatic_downloads)) },
        )
        TvInfoRow(
            stringResource(R.string.tv_downloaded_music), "offline.download.usage",
            Formatter.formatFileSize(context, storage.stats.downloadBytes),
        )
        TvInfoRow(
            stringResource(R.string.tv_streaming_cache), "offline.cache.usage",
            Formatter.formatFileSize(context, storage.stats.totalCacheBytes),
        )
        TvSettingRow(
            stringResource(R.string.tv_cache_limit), "offline.cache.limit",
            supporting = budgetLabel(context, storage.settings.cacheBudgetBytes),
        ) { choosingBudget = true }
        TvSettingRow(
            stringResource(SharedR.string.offline_clear_cache), "offline.cache.clear",
            supporting = when {
                clearing -> "Clearing streaming cache…"
                clearFailed -> stringResource(SharedR.string.offline_cache_clear_failed)
                cleared -> stringResource(SharedR.string.offline_cache_cleared)
                else -> Formatter.formatFileSize(context, storage.stats.totalCacheBytes)
            },
        ) {
            if (!clearing) {
                clearing = true
                cleared = false
                clearFailed = false
                storage.actions.clearCache(
                    onFailure = { clearing = false; clearFailed = true },
                    onComplete = { clearing = false; cleared = true },
                )
            }
        }
    }
    if (choosingBudget) {
        TvChoiceDialog(
            tag = "offline.cache.budget", surfaceTag = "offline.cache.budget.menu",
            title = stringResource(R.string.tv_cache_limit),
            choices = budgets,
            choiceTag = { it.toString() },
            choiceLabel = { budgetLabel(context, it) },
            selected = budgets.firstOrNull { it == storage.settings.cacheBudgetBytes },
            onChoose = { storage.actions.cacheBudget(it); choosingBudget = false },
            onDismiss = { choosingBudget = false },
        )
    }
}

private fun budgetLabel(context: android.content.Context, bytes: Long): String =
    if (bytes <= 0L) context.getString(R.string.tv_cache_limit_off) else Formatter.formatFileSize(context, bytes)
