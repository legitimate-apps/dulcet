package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.ConnectionSettings

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
    Column(Modifier.testTag("connections.section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connections", style = MaterialTheme.typography.headlineSmall)
        state.snapshot?.routes?.filterNot { it.removed }?.forEach { route ->
            Text(route.label ?: route.baseUrl)
            Text(if (route.id == state.snapshot?.selectedRouteId) "Current HTTPS connection"
                else if (route.enabled) "Verified HTTPS connection" else "Unavailable")
            Text(route.baseUrl)
            Button(onClick = { settings.remove(owner, route.id) }, enabled = !state.busy,
                modifier = Modifier.testTag("connections.remove.${route.id}")) { Text("Remove connection") }
        }
        if (state.canUpgrade && state.snapshot?.enrolled != true) {
            Text("HTTPS server address")
            BasicTextField(value = httpsAddress, onValueChange = { httpsAddress = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.testTag("connections.https-address").semantics { contentDescription = "HTTPS server address" })
            Button(onClick = { settings.enroll(owner, httpsAddress) },
                enabled = !state.busy && httpsAddress.startsWith("https://"),
                modifier = Modifier.testTag("connections.upgrade")) { Text("Upgrade connection to HTTPS") }
        } else {
            Button(onClick = { settings.enroll(owner) }, enabled = !state.busy,
                modifier = Modifier.testTag("connections.verify")) { Text("Verify connections") }
        }
        if (state.busy) Text("Verifying connections…")
        if (state.error) Text("Connections could not be verified. Your saved library is unchanged.")
        if (state.snapshot?.enrolled == true && state.snapshot?.selectedRouteId == null)
            Text("Offline · Your downloads are available")
        Text("Connections use HTTPS. A remote connection may terminate encryption at your server administrator's proxy, which can read authentication and content.")
    }
}
