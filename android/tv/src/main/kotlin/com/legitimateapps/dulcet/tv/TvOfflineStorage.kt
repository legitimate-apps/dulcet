package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.core.OfflineLibraryStats
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Free space, said as a quiet aside on the page title's own line: a row of its own cost the page the
 * height that kept Clear and what it frees on screen.
 */
@Composable
internal fun TvStorageTitle() {
    val context = LocalContext.current
    val freeBytes = remember { runCatching { android.os.StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.tv_downloads_storage), style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.weight(1f).alignByBaseline())
        Text(listOf(stringResource(R.string.tv_storage_free), tvStorageSize(freeBytes)).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline().padding(end = 16.dp).testTag("offline.usage"))
    }
}

/**
 * Downloads & storage, its own TV destination opened from Account (spec §12.4), on the phone's row
 * model: a Downloads section (the Automatic downloads switch row, downloaded music as a trailing
 * value), a Streaming cache section (usage as a trailing value, the cache limit as a row whose
 * value and chevron open a radio choice, Clear streaming cache saying what it frees and then what
 * it did). Read-only figures are not focus stops. A TV has no cellular link, so nothing here
 * mentions one.
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
    val cacheBytes = storage.stats.totalCacheBytes
    Column(Modifier.testTag("offline.storage"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TvSettingsHeader(stringResource(R.string.tv_storage_downloads_section))
        androidx.tv.material3.ListItem(
            scale = TvRowScale,
            selected = false,
            onClick = { storage.actions.automatic(!storage.settings.automaticDownloadsEnabled) },
            modifier = Modifier.tvFocus("offline.automatic", default = true),
            supportingContent = {
                if (storage.settings.automaticDownloadsEnabled) {
                    Text(
                        stringResource(SharedR.string.offline_automatic_progress,
                            storage.stats.completedAutomaticCount,
                            storage.stats.completedAutomaticCount + storage.stats.queuedAutomaticCount),
                        color = tvRowLineColor(),
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
        TvInfoRow(stringResource(R.string.tv_downloaded_music), "offline.download.usage", tvStorageSize(storage.stats.downloadBytes))
        TvSettingsHeader(stringResource(R.string.tv_streaming_cache))
        TvInfoRow(stringResource(R.string.tv_cache_used), "offline.cache.usage", tvStorageSize(cacheBytes))
        TvSettingRow(
            stringResource(R.string.tv_cache_limit), "offline.cache.limit",
            value = budgetLabel(context, storage.settings.cacheBudgetBytes),
        ) { choosingBudget = true }
        TvClearCacheRow(storage.stats, clearing, cleared, clearFailed) {
            clearing = true
            cleared = false
            clearFailed = false
            storage.actions.clearCache(
                onFailure = { clearing = false; clearFailed = true },
                onComplete = { clearing = false; cleared = true },
            )
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

private fun budgetLabel(context: android.content.Context, bytes: Long): String = when (bytes) {
    0L -> context.getString(R.string.tv_cache_limit_off)
    256L * 1024 * 1024 -> context.getString(R.string.tv_cache_limit_256mb)
    1024L * 1024 * 1024 -> context.getString(R.string.tv_cache_limit_1gb)
    else -> tvStorageSize(bytes)
}

/**
 * A byte count at two to three significant figures with decimal units ("11.9 kB", "1.0 MB"), the
 * phone's own formatting, so both surfaces state the same size the same way.
 */
internal fun tvStorageSize(bytes: Long): String {
    val units = arrayOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    var rounded = BigDecimal.valueOf(value).round(MathContext(3, RoundingMode.HALF_UP))
    if (unit < units.lastIndex && rounded.compareTo(BigDecimal(1000)) >= 0) {
        unit++
        value /= 1000.0
        rounded = BigDecimal.valueOf(value).round(MathContext(3, RoundingMode.HALF_UP))
    }
    var number = rounded.stripTrailingZeros().toPlainString()
    if (unit > 0 && !number.contains('.')) {
        val integral = number.toLongOrNull()
        if (integral != null && integral < 10) number = "$number.0"
    }
    return "$number ${units[unit]}"
}

/**
 * Clear streaming cache, stating only what Clear can really free: a playing or queued song's bytes stay after it.
 * A row with nothing to clear stays focusable and does nothing, since the remote is on it the moment a clear finishes.
 */
@Composable
internal fun TvClearCacheRow(stats: OfflineLibraryStats, clearing: Boolean, cleared: Boolean, clearFailed: Boolean, onClear: () -> Unit) {
    TvSettingRow(
        stringResource(R.string.tv_clear_streaming_cache), "offline.cache.clear",
        supporting = when {
            clearing -> stringResource(R.string.tv_clearing_streaming_cache)
            clearFailed -> stringResource(SharedR.string.offline_cache_clear_failed)
            cleared -> stringResource(SharedR.string.offline_cache_cleared)
            stats.clearableCacheBytes > 0 -> stringResource(R.string.tv_cache_frees, tvStorageSize(stats.clearableCacheBytes))
            // The total sits right above this row, so a cache that still holds something says what it is.
            stats.totalCacheBytes > 0 -> stringResource(R.string.tv_cache_only_in_use)
            else -> stringResource(R.string.tv_cache_nothing_to_clear)
        },
    ) { if (!clearing && stats.clearableCacheBytes > 0) onClear() }
}
