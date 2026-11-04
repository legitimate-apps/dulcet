package com.legitimateapps.dulcet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons

/**
 * The phone's Account page: identity, the saved servers with the active one checked, and the
 * entries to the Playback and Downloads & storage pages, with Sign out last (spec §14.7's dialogs
 * stay untouched). A pushed page, not a dialog. [back] pops the route; a sub-page's Back returns
 * to the account root first.
 */
@Composable
internal fun AccountPage(back: () -> Unit) {
    val actions = LocalAccountActions.current ?: return
    var section by rememberSaveable { mutableStateOf("root") }
    BackHandler(enabled = section != "root") { section = "root" }
    when (section) {
        "playback" -> PlaybackSettingsPage(back = { section = "root" })
        "connections" -> ConnectionSettingsPage(back = { section = "root" })
        "storage" -> DownloadsStoragePage(back = { section = "root" })
        else -> AccountHome(actions, back,
            openConnections = { section = "connections" }, openPlayback = { section = "playback" }, openStorage = { section = "storage" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountHome(actions: AccountActions, back: () -> Unit, openConnections: () -> Unit, openPlayback: () -> Unit, openStorage: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier.testTag("account.page"),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(SharedR.string.account_signout_account_title)) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("account.back")) {
                        Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .testTag("account.list"),
        ) {
            SettingRow(
                title = actions.username,
                supporting = actions.serverName,
                leading = { AccountMonogram(actions.username) },
            )
            AccountHeading(stringResource(R.string.settings_servers))
            actions.savedServers.forEach { server ->
                val active = actions.isActiveServer(server)
                SettingRow(
                    title = server.serverName,
                    leading = { Icon(DulcetIcons.LibraryMusic, null) },
                    supporting = "${server.username}\n${server.serverUrl}",
                    trailing = if (active) {
                        { Icon(DulcetIcons.Check, contentDescription = stringResource(R.string.settings_server_active)) }
                    } else null,
                    tag = "account.server.${server.id}",
                    onClick = if (active) null else { { actions.selectServer(server.id) } },
                )
            }
            actions.addServer?.let { addServer ->
                SettingRow(
                    title = stringResource(R.string.settings_add_server),
                    leading = { Icon(DulcetIcons.Add, contentDescription = null) },
                    tag = "account.add-server",
                    onClick = addServer,
                )
            }
            SettingRow(
                title = stringResource(R.string.restyle_connections),
                leading = { Icon(DulcetIcons.Dns, null) },
                trailing = { Icon(DulcetIcons.ChevronRight, null) },
                tag = "account.connections", onClick = openConnections,
            )
            AccountHeading(stringResource(R.string.settings_section))
            SettingRow(
                title = stringResource(R.string.settings_playback),
                leading = { Icon(DulcetIcons.Tune, null) },
                trailing = { Icon(DulcetIcons.ChevronRight, contentDescription = null) },
                tag = "account.playback",
                onClick = openPlayback,
            )
            SettingRow(
                title = stringResource(R.string.settings_downloads_storage),
                leading = { Icon(DulcetIcons.Download, null) },
                trailing = { Icon(DulcetIcons.ChevronRight, contentDescription = null) },
                tag = "account.storage",
                onClick = openStorage,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingRow(
                title = stringResource(SharedR.string.account_signout_action),
                titleColor = MaterialTheme.colorScheme.error,
                leading = { Icon(DulcetIcons.Logout, null, tint = MaterialTheme.colorScheme.error) },
                tag = "account.signout",
                onClick = actions.requestSignOut,
            )
        }
    }
}

/** A section heading on the Account page, on the same edge as the text of the rows under it. */
@Composable
private fun AccountHeading(text: String) {
    Text(
        text,
        Modifier.padding(start = 72.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The saved server the app is signed in to: the same user on the same host the account holds. */
private fun AccountActions.isActiveServer(server: StoredAccount): Boolean =
    server.username == username &&
        (runCatching { java.net.URI(server.serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: server.serverUrl) == serverName

@Composable
private fun AccountMonogram(username: String) {
    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                username.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * One settings line: a headline, at most one supporting line, and optional leading and trailing
 * content. With [onClick] the whole row activates. A row without [leading] starts its text on the
 * 16 dp keyline, so a group gives every row an icon or none of them.
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    tag: String? = null,
    supportingTag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let {
            {
                Text(
                    it,
                    modifier = if (supportingTag != null) Modifier.testTag(supportingTag) else Modifier,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = leading?.let { icon ->
            {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            }
        },
        trailingContent = trailing,
        modifier = modifier
            .let { if (tag != null) it.testTag(tag) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    )
}

/** One choice of a single-choice settings dialog: its row tag and its label. */
internal class SettingChoice(val tag: String, val label: String)

/**
 * The single-choice dialog for a setting: radio rows with the current value marked, [note] as the
 * one supporting line, and choosing applies at once and dismisses.
 */
@Composable
internal fun ChoiceDialog(
    title: String,
    choices: List<SettingChoice>,
    selected: String?,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (note != null) {
                    Text(
                        note,
                        Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                choices.forEachIndexed { index, choice ->
                    val isSelected = choice.tag == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(choice.tag)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onChoose(index) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(choice.label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Connection controls keep their own behavior inside the account settings hierarchy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSettingsPage(back: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        androidx.compose.material3.TopAppBar(title = { Text(stringResource(R.string.restyle_connections)) },
            navigationIcon = { IconButton(back, Modifier.testTag("account.connections.back")) {
                Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back))
            } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ConnectionSettingsSection()
        }
    }
}
