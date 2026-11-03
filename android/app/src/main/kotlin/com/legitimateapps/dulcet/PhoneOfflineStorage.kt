package com.legitimateapps.dulcet

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.downloads.OfflineStoragePresentation
import com.legitimateapps.dulcet.downloads.rememberOfflineStorage
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The phone's Downloads & storage settings page: a usage bar with its legend, the automatic
 * downloads switch, usage rows, the cache limit as a single-choice dialog, and Clear streaming
 * cache with its result in the row (the app has no snackbar surface, so the row reports it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsStoragePage(back: () -> Unit) {
    val storage = rememberOfflineStorage()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_downloads_storage)) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("account.storage.back")) {
                        Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val current = storage
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        DownloadsStorageContent(current, Modifier.padding(padding))
    }
}

@Composable
private fun DownloadsStorageContent(storage: OfflineStoragePresentation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val freeBytes = remember { runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L) }
    var budgetOpen by remember { mutableStateOf(false) }
    var clearBaseline by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cacheBytes = storage.stats.totalCacheBytes
    LaunchedEffect(cacheBytes) {
        val baseline = clearBaseline
        if (baseline != null && cacheBytes < baseline) {
            clearBaseline = null
            snackbar.showSnackbar(context.getString(R.string.settings_cache_cleared, formatStorageSize(baseline - cacheBytes)))
        }
    }
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("offline.storage")) {
        Text(stringResource(R.string.settings_usage_free) + " · " + formatStorageSize(freeBytes),
            Modifier.padding(16.dp).testTag("offline.usage"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        StorageSectionTitle(stringResource(R.string.restyle_downloads_section))
        val queued = storage.stats.queuedAutomaticCount
        val automaticTotal = storage.stats.completedAutomaticCount + queued
        SettingRow(
            title = stringResource(R.string.settings_automatic_downloads),
            supporting = if (storage.settings.automaticDownloadsEnabled)
                stringResource(com.legitimateapps.dulcet.shared.R.string.offline_automatic_progress, storage.stats.completedAutomaticCount, automaticTotal)
            else stringResource(R.string.settings_automatic_downloads_hint),
            supportingTag = if (storage.settings.automaticDownloadsEnabled) "offline.automatic.progress" else null,
            trailing = {
                Switch(
                    storage.settings.automaticDownloadsEnabled,
                    storage.actions::automatic,
                    Modifier.testTag("offline.automatic"),
                )
            },
            onClick = { storage.actions.automatic(!storage.settings.automaticDownloadsEnabled) },
        )
        SettingRow(
            title = stringResource(R.string.settings_downloaded_music),
            trailing = { StorageValue(formatStorageSize(storage.stats.downloadBytes)) },
            tag = "offline.download.usage",
        )
        StorageSectionTitle(stringResource(R.string.restyle_cache_section))
        SettingRow(
            title = stringResource(R.string.settings_streaming_cache),
            trailing = { StorageValue(formatStorageSize(cacheBytes)) },
            tag = "offline.cache.usage",
        )
        SettingRow(
            title = stringResource(R.string.settings_cache_limit),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageValue(cacheBudgetLabel(storage.settings.cacheBudgetBytes))
                    Icon(DulcetIcons.ChevronRight, contentDescription = null)
                }
            },
            tag = "offline.cache.limit",
            onClick = { budgetOpen = true },
        )
        SettingRow(
            title = stringResource(R.string.settings_clear_cache),
            supporting = stringResource(R.string.restyle_cache_frees, formatStorageSize(cacheBytes)),
            tag = "offline.cache.clear",
            onClick = {
                clearBaseline = cacheBytes
                storage.actions.clearCache {
                    if (clearBaseline == 0L) {
                        clearBaseline = null
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_cache_cleared, formatStorageSize(0))) }
                    }
                }
            },
        )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("offline.cache.cleared"))
    }
    if (budgetOpen) {
        val budgets = listOf(0L, 256L * 1024 * 1024, 1024L * 1024 * 1024)
        ChoiceDialog(
            title = stringResource(R.string.settings_cache_limit),
            choices = budgets.map { SettingChoice("offline.cache.budget.$it", cacheBudgetLabel(it)) },
            selected = "offline.cache.budget.${storage.settings.cacheBudgetBytes}",
            onChoose = { index -> budgetOpen = false; storage.actions.cacheBudget(budgets[index]) },
            onDismiss = { budgetOpen = false },
        )
    }
}

@Composable
private fun StorageValue(value: String) {
    Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The budget choices' human labels; the stored setting keeps its real byte value. */
@Composable
private fun cacheBudgetLabel(bytes: Long): String = when (bytes) {
    0L -> stringResource(R.string.restyle_no_cache)
    256L * 1024 * 1024 -> stringResource(R.string.settings_cache_limit_256mb)
    1024L * 1024 * 1024 -> stringResource(R.string.settings_cache_limit_1gb)
    else -> formatStorageSize(bytes)
}

@Composable
private fun StorageSectionTitle(title: String) {
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** A byte count at two to three significant figures with decimal units: "11.9 kB", "1.0 MB". */
internal fun formatStorageSize(bytes: Long): String {
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
