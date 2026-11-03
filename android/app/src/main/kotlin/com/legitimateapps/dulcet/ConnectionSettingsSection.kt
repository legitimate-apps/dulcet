package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        if (state.snapshot?.enrolled == true) Text("Automatic · Uses the server administrator’s connection order", Modifier.testTag("connections.automatic"))
        state.snapshot?.routes?.filterNot { it.removed }?.forEach { route ->
            Text(route.label ?: route.baseUrl, Modifier.testTag("connections.label.${route.id}"))
            val check = state.snapshot?.checks?.get(route.id)
            Text(if (check?.result == com.legitimateapps.dulcet.core.ConnectionCheckResult.DifferentServer) "Different server · Connection refused"
                else if (check?.result == com.legitimateapps.dulcet.core.ConnectionCheckResult.Unavailable) "Unavailable"
                else if (route.id == state.snapshot?.selectedRouteId) "Current HTTPS connection"
                else if (route.verifiedAtEnrollment || check?.result == com.legitimateapps.dulcet.core.ConnectionCheckResult.Ready) "Verified HTTPS connection" else "Unavailable", Modifier.testTag("connections.status.${route.id}"))
            Text(route.baseUrl, Modifier.testTag("connections.address.${route.id}"))
            check?.let { Text("Last checked: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.checkedAtEpochMilliseconds))}", Modifier.testTag("connections.checked.${route.id}")) }
            TextButton(onClick = { settings.remove(owner, route.id) }, enabled = !state.busy,
                modifier = Modifier.testTag("connections.remove.${route.id}").semantics { contentDescription = "Remove connection: ${route.label ?: route.baseUrl}" }) { Text("Remove connection") }
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
        if (state.busy) Text("Verifying connections…", Modifier.testTag("connections.busy"))
        if (state.error) Text("Connections could not be verified. Your saved library is unchanged.", Modifier.testTag("connections.error"))
        if (state.snapshot?.enrolled == true && state.snapshot?.selectedRouteId == null)
            Text("Offline · Your downloads are available", Modifier.testTag("connections.offline"))
        Text("Connections use HTTPS. A remote connection may terminate encryption at your server administrator's proxy, which can read authentication and content.")
    }
}
