package com.legitimateapps.dulcet

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.StreamingQuality
import com.legitimateapps.dulcet.playback.StreamingQualitySettings
import com.legitimateapps.dulcet.playback.label
import com.legitimateapps.dulcet.ui.DulcetIcons

/**
 * The phone's Playback settings page (spec §12.5): one streaming quality for Wi-Fi, one for mobile
 * data, each a row whose value opens a radio-choice dialog. A choice is saved at once and applies
 * from the next song; nothing playing restarts for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettingsPage(
    back: () -> Unit,
    settings: StreamingQualitySettings = StreamingQualitySettings.get(LocalContext.current),
) {
    val preference by settings.preference.collectAsStateWithLifecycle()
    val metered = rememberNetworkMetered()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_playback)) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("account.playback.back")) {
                        Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).testTag("quality.section")) {
            QualityRow(
                title = stringResource(R.string.settings_wifi_streaming),
                tag = "quality.unmetered",
                selected = preference.unmetered,
                hint = null,
                active = false,
            ) { settings.set(preference.copy(unmetered = it)) }
            QualityRow(
                title = stringResource(R.string.settings_mobile_data),
                tag = "quality.metered",
                selected = preference.metered,
                hint = stringResource(R.string.settings_mobile_data_hint),
                active = metered == true,
            ) { settings.set(preference.copy(metered = it)) }
        }
    }
}

@Composable
private fun QualityRow(
    title: String,
    tag: String,
    selected: StreamingQuality,
    hint: String?,
    active: Boolean,
    choose: (StreamingQuality) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val supporting = listOfNotNull(
        selected.label(context),
        if (active) stringResource(R.string.settings_mobile_data_active) else hint,
    ).joinToString(" · ")
    SettingRow(title = title, supporting = supporting, tag = tag, onClick = { open = true })
    if (open) {
        ChoiceDialog(
            title = title,
            note = stringResource(R.string.settings_quality_dialog_note),
            choices = StreamingQuality.entries.map { SettingChoice("$tag.${it.wireName}", it.label(context)) },
            selected = "$tag.${selected.wireName}",
            onChoose = { index -> open = false; choose(StreamingQuality.entries[index]) },
            onDismiss = { open = false },
        )
    }
}

/**
 * Whether the current network bills or limits data, live — the same NOT_METERED rule the playback
 * service applies; null while the platform has classified no network.
 */
@Composable
private fun rememberNetworkMetered(): Boolean? {
    val context = LocalContext.current
    var metered by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) return@DisposableEffect onDispose {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            override fun onLost(network: Network) { metered = null }
        }
        metered = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            ?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    return metered
}
