package com.legitimateapps.dulcet.tv

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.downloads.rememberOfflineStorage
import com.legitimateapps.dulcet.shared.R

@Composable
internal fun TvOfflineStorage() {
    val storage = rememberOfflineStorage() ?: return
    val context = LocalContext.current
    Column(Modifier.testTag("offline.storage"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.offline_storage_title), style = MaterialTheme.typography.headlineSmall)
        TvAction(stringResource(R.string.offline_automatic_downloads) + ": " + if (storage.settings.automaticDownloadsEnabled) "On" else "Off", "offline.automatic") {
            storage.actions.automatic(!storage.settings.automaticDownloadsEnabled)
        }
        Text(stringResource(R.string.offline_automatic_hint))
        Text(stringResource(R.string.offline_download_usage, Formatter.formatFileSize(context, storage.stats.downloadBytes)), Modifier.testTag("offline.download.usage"))
        if (storage.settings.automaticDownloadsEnabled) Text(stringResource(R.string.offline_automatic_progress,
            storage.stats.completedAutomaticCount, storage.stats.completedAutomaticCount + storage.stats.queuedAutomaticCount), Modifier.testTag("offline.automatic.progress"))
        Text(stringResource(R.string.offline_cache_usage, Formatter.formatFileSize(context, storage.stats.totalCacheBytes)), Modifier.testTag("offline.cache.usage"))
        Text(stringResource(R.string.offline_cache_limit, Formatter.formatFileSize(context, storage.settings.cacheBudgetBytes)))
        listOf(0L, 256L * 1024 * 1024, 1024L * 1024 * 1024).forEach { bytes ->
            TvAction(Formatter.formatFileSize(context, bytes), "offline.cache.budget.$bytes") { storage.actions.cacheBudget(bytes) }
        }
        TvAction(stringResource(R.string.offline_clear_cache), "offline.cache.clear", onClick = storage.actions::clearCache)
    }
}
