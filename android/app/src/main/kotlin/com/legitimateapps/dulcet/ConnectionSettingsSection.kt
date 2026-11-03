package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ConnectionSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { ConnectionSettings(context, scope) }
    val state by settings.state.collectAsStateWithLifecycle()
    val owner = state.snapshot?.anchor
    var httpsAddress by remember(owner) { mutableStateOf("") }
    DisposableEffect(settings) { settings.restore(); onDispose { settings.close() } }
    if (owner == null) return
    Column(Modifier.testTag("connections.section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connections")
        state.snapshot?.routes?.filterNot { it.removed }?.forEach { route ->
            Text(route.label ?: route.baseUrl)
            Text(if (route.id == state.snapshot?.selectedRouteId) "Current HTTPS connection"
                else if (route.enabled) "Verified HTTPS connection" else "Unavailable")
            Text(route.baseUrl)
            TextButton(onClick = { settings.remove(owner, route.id) }, enabled = !state.busy,
                modifier = Modifier.testTag("connections.remove.${route.id}")) { Text("Remove connection") }
        }
        OutlinedTextField(value = httpsAddress, onValueChange = { httpsAddress = it },
            label = { Text("HTTPS server address") }, singleLine = true,
            modifier = Modifier.testTag("connections.https-address"))
        TextButton(onClick = { settings.enroll(owner, httpsAddress) },
            enabled = !state.busy && httpsAddress.startsWith("https://"),
            modifier = Modifier.testTag(if (state.canUpgrade && state.snapshot?.enrolled != true) "connections.upgrade" else "connections.add")) { Text(if (state.canUpgrade && state.snapshot?.enrolled != true) "Upgrade connection to HTTPS" else "Add HTTPS connection") }
        if (!state.canUpgrade || state.snapshot?.enrolled == true) {
            TextButton(onClick = { settings.enroll(owner) }, enabled = !state.busy,
                modifier = Modifier.testTag("connections.verify")) { Text("Verify connections") }
        }
        if (state.busy) Text("Verifying connections…")
        if (state.error) Text("Connections could not be verified. Your saved library is unchanged.")
        if (state.snapshot?.enrolled == true && state.snapshot?.selectedRouteId == null)
            Text("Offline · Your downloads are available")
        Text("Connections use HTTPS. A remote connection may terminate encryption at your server administrator's proxy, which can read authentication and content.")
    }
}
