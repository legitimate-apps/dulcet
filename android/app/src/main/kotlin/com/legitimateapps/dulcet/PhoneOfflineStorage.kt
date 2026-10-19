package com.legitimateapps.dulcet

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.downloads.rememberOfflineStorage
import com.legitimateapps.dulcet.shared.R

@Composable
internal fun OfflineStorageSection() {
    val storage = rememberOfflineStorage() ?: return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag("offline.storage"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.offline_storage_title), style = MaterialTheme.typography.titleMedium)
        Row {
            Text(stringResource(R.string.offline_automatic_downloads), Modifier.weight(1f))
            Switch(storage.settings.automaticDownloadsEnabled, storage.actions::automatic, Modifier.testTag("offline.automatic"))
        }
        Text(stringResource(R.string.offline_automatic_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.offline_download_usage, Formatter.formatFileSize(context, storage.stats.downloadBytes)), Modifier.testTag("offline.download.usage"))
        if (storage.settings.automaticDownloadsEnabled) Text(stringResource(R.string.offline_automatic_progress,
            storage.stats.completedAutomaticCount, storage.stats.completedAutomaticCount + storage.stats.queuedAutomaticCount), Modifier.testTag("offline.automatic.progress"))
        Text(stringResource(R.string.offline_cache_usage, Formatter.formatFileSize(context, storage.stats.totalCacheBytes)), Modifier.testTag("offline.cache.usage"))
        Text(stringResource(R.string.offline_cache_limit, Formatter.formatFileSize(context, storage.settings.cacheBudgetBytes)))
        Row {
            listOf(0L, 256L * 1024 * 1024, 1024L * 1024 * 1024).forEach { bytes ->
                TextButton(onClick = { storage.actions.cacheBudget(bytes) }, modifier = Modifier.testTag("offline.cache.budget.$bytes")) {
                    Text(Formatter.formatFileSize(context, bytes))
                }
            }
        }
        TextButton(onClick = storage.actions::clearCache, modifier = Modifier.testTag("offline.cache.clear")) { Text(stringResource(R.string.offline_clear_cache)) }
    }
}
