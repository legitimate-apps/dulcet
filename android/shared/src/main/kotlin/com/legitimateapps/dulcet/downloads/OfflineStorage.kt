package com.legitimateapps.dulcet.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.OfflineLibrarySettings
import com.legitimateapps.dulcet.core.OfflineLibraryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI adapter only: all retention, ownership, ordering and eviction decisions stay in the core. */
public class OfflineStorageActions internal constructor(
    private val controller: AndroidDownloadController,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    public fun automatic(enabled: Boolean) { launch { controller.setAutomaticDownloadsEnabled(enabled) } }
    public fun cacheBudget(bytes: Long) { launch { controller.setCacheBudgetBytes(bytes) } }
    public fun clearCache() { launch { controller.clearStreamCache() } }
}

public data class OfflineStoragePresentation(
    val settings: OfflineLibrarySettings,
    val stats: OfflineLibraryStats,
    val actions: OfflineStorageActions,
)

@Composable
public fun rememberOfflineStorage(): OfflineStoragePresentation? {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var controller by remember(context) { mutableStateOf<AndroidDownloadController?>(null) }
    LaunchedEffect(context) { controller = withContext(Dispatchers.IO) { AndroidDownloads.controller(context) } }
    val current = controller ?: return null
    val settings by current.offlineSettings.collectAsState()
    val stats by current.offlineStats.collectAsState()
    val actions = remember(current, scope) { OfflineStorageActions(current) { block -> scope.launch(Dispatchers.IO) { block() } } }
    return OfflineStoragePresentation(settings, stats, actions)
}
