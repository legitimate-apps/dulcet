package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.ConnectionRouteStatus
import com.legitimateapps.dulcet.ConnectionSettings
import com.legitimateapps.dulcet.status
import androidx.compose.ui.res.stringResource
import com.legitimateapps.dulcet.shared.R as SharedR

@Composable
internal fun TvConnectionSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { ConnectionSettings(context, scope) }
    val state by settings.state.collectAsStateWithLifecycle()
    val owner = state.snapshot?.anchor
    var httpsAddress by remember(owner) { mutableStateOf("") }
    DisposableEffect(settings) { settings.restore(); onDispose { settings.close() } }
    if (owner == null) return
    val routes = state.snapshot?.routes.orEmpty().filterNot { it.removed }
    val routeFocus = remember(routes.map { it.id }) { routes.associate { it.id to FocusRequester() } }
    val addressFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val verifyFocus = remember { FocusRequester() }
    val canAdd = !state.busy && httpsAddress.startsWith("https://")
    val showVerify = !state.canUpgrade || state.snapshot?.enrolled == true
    Column(Modifier.testTag("connections.section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val colors = MaterialTheme.colorScheme
        val secondary = colors.onSurfaceVariant
        if (state.snapshot?.enrolled == true) Text(stringResource(SharedR.string.connections_automatic), Modifier.testTag("connections.automatic"),
            style = MaterialTheme.typography.bodyMedium, color = secondary)
        if (state.snapshot?.enrolled == true && state.snapshot?.selectedRouteId == null)
            Text(stringResource(SharedR.string.connections_offline), Modifier.testTag("connections.offline"), style = MaterialTheme.typography.titleSmall)
        if (routes.isNotEmpty()) TvConnectionsHeading(stringResource(SharedR.string.connections_routes_heading))
        routes.forEachIndexed { index, route ->
          // Each route reads as one unit: its texts close together, Remove under them.
          Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val status = requireNotNull(state.snapshot).status(route)
            val removeLabel = stringResource(SharedR.string.connections_remove_named, route.label ?: route.baseUrl)
            // A route without a label is named by its address, shown once.
            Text(route.label ?: route.baseUrl, Modifier.testTag(if (route.label != null) "connections.label.${route.id}" else "connections.address.${route.id}"),
                style = MaterialTheme.typography.titleMedium)
            val check = state.snapshot?.checks?.get(route.id)
            Text(stringResource(status.text), Modifier.testTag("connections.status.${route.id}"), style = MaterialTheme.typography.bodyMedium,
                color = when (status) { ConnectionRouteStatus.Current -> colors.primary; ConnectionRouteStatus.DifferentServer -> colors.error; else -> secondary })
            if (route.label != null) Text(route.baseUrl, Modifier.testTag("connections.address.${route.id}"), style = MaterialTheme.typography.bodySmall, color = secondary)
            check?.let {
                val checkedAt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it.checkedAtEpochMilliseconds))
                Text(stringResource(SharedR.string.connections_last_checked, checkedAt), Modifier.testTag("connections.checked.${route.id}"),
                    style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            Button(onClick = { settings.remove(owner, route.id) }, enabled = !state.busy,
                modifier = Modifier.focusRequester(routeFocus.getValue(route.id)).focusProperties {
                    up = routes.getOrNull(index - 1)?.let { routeFocus.getValue(it.id) } ?: FocusRequester.Default
                    down = routes.getOrNull(index + 1)?.let { routeFocus.getValue(it.id) } ?: addressFocus
                }.padding(top = 4.dp).testTag("connections.remove.${route.id}").semantics { contentDescription = removeLabel }) { Text(stringResource(SharedR.string.connections_remove)) }
          }
        }
        TvConnectionsHeading(stringResource(SharedR.string.connections_add_heading))
        // Match Connect: remote focus can pass the editor without opening an IME session.
        TvField(label = stringResource(SharedR.string.connections_https_address), value = httpsAddress,
            onChange = { httpsAddress = it }, enabled = !state.busy,
            tag = "connections.https-address", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.focusRequester(addressFocus).focusProperties {
                // Explicit vertical order remains reachable when long route rows scroll and
                // the full-width editor's centre lies far from the compact action buttons.
                up = routes.lastOrNull()?.let { routeFocus.getValue(it.id) } ?: FocusRequester.Default
                down = if (canAdd) addFocus else if (showVerify && !state.busy) verifyFocus else FocusRequester.Default
            })
        Button(onClick = { settings.enroll(owner, httpsAddress) },
            enabled = canAdd,
            modifier = Modifier.focusRequester(addFocus).focusProperties {
                up = addressFocus
                down = if (showVerify) verifyFocus else FocusRequester.Default
            }.testTag(if (state.canUpgrade && state.snapshot?.enrolled != true) "connections.upgrade" else "connections.add")) { Text(stringResource(if (state.canUpgrade && state.snapshot?.enrolled != true) SharedR.string.connections_upgrade else SharedR.string.connections_add)) }
        if (showVerify) {
            Button(onClick = { settings.enroll(owner) }, enabled = !state.busy,
                modifier = Modifier.focusRequester(verifyFocus).focusProperties {
                    up = if (canAdd) addFocus else addressFocus
                }.testTag("connections.verify")) { Text(stringResource(SharedR.string.connections_verify)) }
        }
        if (state.busy) Text(stringResource(SharedR.string.connections_verifying), Modifier.testTag("connections.busy"), color = secondary)
        if (state.error) Text(stringResource(SharedR.string.connections_error), Modifier.testTag("connections.error"), color = colors.error)
        Text(stringResource(SharedR.string.connections_privacy), style = MaterialTheme.typography.bodySmall, color = secondary)
    }
}

/** A group heading in the settings pages' header style, flush with this page's unpadded texts. */
@Composable
private fun TvConnectionsHeading(text: String) {
    Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
