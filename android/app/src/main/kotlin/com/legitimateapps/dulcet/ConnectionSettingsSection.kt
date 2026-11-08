package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.ConnectionRoute
import com.legitimateapps.dulcet.core.ConnectionSnapshot
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons

/**
 * The Connections page body: the server's HTTPS routes as list rows, then the add form, in the
 * same Material list language as the other Account pages. Every proof tag sits on the node that
 * carries its text or action.
 */
@Composable
internal fun ConnectionSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { ConnectionSettings(context, scope) }
    val state by settings.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val owner = snapshot?.anchor
    var httpsAddress by remember(owner) { mutableStateOf("") }
    DisposableEffect(settings) { settings.restore(); onDispose { settings.close() } }
    if (snapshot == null || owner == null) return
    val upgrade = state.canUpgrade && !snapshot.enrolled
    val routes = snapshot.routes.filterNot { it.removed }
    Column(Modifier.fillMaxWidth().testTag("connections.section")) {
        if (snapshot.enrolled) {
            Text(stringResource(SharedR.string.connections_automatic),
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("connections.automatic"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (snapshot.enrolled && snapshot.selectedRouteId == null) {
            ListItem(
                headlineContent = { Text(stringResource(SharedR.string.connections_offline), Modifier.testTag("connections.offline")) },
                leadingContent = { Icon(DulcetIcons.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        if (routes.isNotEmpty()) {
            ConnectionsHeading(stringResource(SharedR.string.connections_routes_heading))
            routes.forEachIndexed { index, route ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp))
                ConnectionRouteRow(snapshot, route, enabled = !state.busy) { settings.remove(owner, route.id) }
            }
        }
        ConnectionsHeading(stringResource(SharedR.string.connections_add_heading))
        OutlinedTextField(
            value = httpsAddress, onValueChange = { httpsAddress = it },
            label = { Text(stringResource(SharedR.string.connections_https_address)) },
            placeholder = { Text(stringResource(SharedR.string.connections_https_address_hint)) },
            singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("connections.https-address"),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { settings.enroll(owner, httpsAddress) },
                enabled = !state.busy && httpsAddress.startsWith("https://"),
                modifier = Modifier.testTag(if (upgrade) "connections.upgrade" else "connections.add")) {
                Text(stringResource(if (upgrade) SharedR.string.connections_upgrade else SharedR.string.connections_add))
            }
            if (!state.canUpgrade || snapshot.enrolled) {
                OutlinedButton(onClick = { settings.enroll(owner) }, enabled = !state.busy,
                    modifier = Modifier.testTag("connections.verify")) {
                    Text(stringResource(SharedR.string.connections_verify))
                }
            }
        }
        if (state.busy) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(SharedR.string.connections_verifying), Modifier.testTag("connections.busy"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.error) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(DulcetIcons.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(SharedR.string.connections_error), Modifier.testTag("connections.error"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Text(stringResource(SharedR.string.connections_privacy),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionsHeading(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** One route: its name, state, address and last check, with Remove as the trailing action. */
@Composable
private fun ConnectionRouteRow(snapshot: ConnectionSnapshot, route: ConnectionRoute, enabled: Boolean, onRemove: () -> Unit) {
    val status = snapshot.status(route)
    val name = route.label ?: route.baseUrl
    val colors = MaterialTheme.colorScheme
    val statusColor = when (status) {
        ConnectionRouteStatus.Current -> colors.primary
        ConnectionRouteStatus.DifferentServer -> colors.error
        else -> colors.onSurfaceVariant
    }
    ListItem(
        headlineContent = {
            Text(name, Modifier.testTag("connections.label.${route.id}"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(status.text), Modifier.testTag("connections.status.${route.id}"),
                    style = MaterialTheme.typography.bodyMedium, color = statusColor)
                Text(route.baseUrl, Modifier.testTag("connections.address.${route.id}"),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                snapshot.checks[route.id]?.let { check ->
                    val checkedAt = remember(check.checkedAtEpochMilliseconds) {
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                            .format(java.util.Date(check.checkedAtEpochMilliseconds))
                    }
                    Text(stringResource(SharedR.string.connections_last_checked, checkedAt), Modifier.testTag("connections.checked.${route.id}"),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        },
        leadingContent = {
            Icon(if (status == ConnectionRouteStatus.DifferentServer) DulcetIcons.Warning else DulcetIcons.Lock, null,
                Modifier.size(24.dp), tint = statusColor)
        },
        trailingContent = {
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("connections.remove.${route.id}")) {
                Icon(DulcetIcons.Delete, stringResource(SharedR.string.connections_remove_named, name))
            }
        },
    )
}
