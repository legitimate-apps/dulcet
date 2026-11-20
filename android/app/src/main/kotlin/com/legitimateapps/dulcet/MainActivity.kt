package com.legitimateapps.dulcet

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlinx.coroutines.flow.first
import com.legitimateapps.dulcet.shared.R as SharedR

class MainActivity : ComponentActivity() {
    private val viewModel: AccountConnectViewModel by viewModels()
    private val searchDependencies: SearchHostDependencies by lazy {
        (application as? SearchHostDependencyOwner)?.searchHostDependencies
            ?: ProductionSearchHostDependencies
    }
    private val requests = PhonePlaybackRequests()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already acted on its launch intent; only a fresh one reads it.
        if (savedInstanceState == null) requests.accept(intent)
        setContent {
            DulcetTheme {
                AccountConnectScreen(viewModel, searchDependencies, requests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requests.accept(intent)
    }
}

@Composable
internal fun AccountConnectScreen(
    viewModel: AccountConnectViewModel,
    searchDependencies: SearchHostDependencies,
    requests: PhonePlaybackRequests = PhonePlaybackRequests(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signOut by viewModel.signOut.state.collectAsStateWithLifecycle()
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    val replacement by viewModel.replacement.offer.collectAsStateWithLifecycle()
    replacement?.let { offer ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { viewModel.replacement.answer(false) },
            modifier = Modifier.testTag("account.replacement"),
            title = { Text(stringResource(SharedR.string.account_replacement_title)) },
            text = { Text(stringResource(SharedR.string.account_replacement_body, offer.downloadCount)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.replacement.answer(true) },
                modifier = Modifier.testTag("account.replacement.confirm")) { Text(stringResource(SharedR.string.account_replacement_confirm)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { viewModel.replacement.answer(false) },
                modifier = Modifier.testTag("account.replacement.cancel")) { Text(stringResource(R.string.cancel)) } })
    }
    val signOutCallbacks = remember(viewModel) { viewModel.signOut.callbacks() }
    val context = LocalContext.current
    // Re-read on every sign-out step too: a finished sign-out may leave the form state unchanged.
    val storedAccount = remember(state, signOut, adding) {
        runCatching { searchDependencies.loadAccount(context) }.getOrNull()
    }
    SignOutDialogs(signOut, signOutCallbacks)
    if (signOut.hidesAccount) {
        SigningOutSurface(signOut, signOutCallbacks)
        return
    }
    if (storedAccount != null && !adding) {
        val actions = remember(storedAccount) {
            AccountActions(
                serverName = runCatching { java.net.URI(storedAccount.normalizedBaseUrl).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: storedAccount.normalizedBaseUrl,
                username = storedAccount.username,
                requestSignOut = viewModel.signOut::request,
                savedServers = viewModel.savedAccounts(), selectServer = viewModel::switchServer,
                addServer = viewModel::addServer,
            )
        }
        CompositionLocalProvider(LocalAccountActions provides actions) {
            androidx.compose.runtime.key(storedAccount.providerInstanceId) {
                PhoneApp(storedAccount, searchDependencies, requests, restoredAccount = true)
            }
        }
        return
    }
    // A saved account whose record cannot be read never reaches the app, so it is signed out from here.
    val unreadable = remember(state.status, signOut) { viewModel.signOut.unreadableAccountId() != null }
    AccountConnectContent(
        state = state,
        onServerUrlChanged = viewModel::updateServerUrl,
        onUsernameChanged = viewModel::updateUsername,
        onPasswordChanged = viewModel::updatePassword,
        onAllowLocalHttpChanged = viewModel::updateAllowLocalHttp,
        onSubmit = {
            if (viewModel.state.value.status != AccountConnectStatus.Connecting) viewModel.submitOrCancel()
        },
        onCancelConnect = {
            if (viewModel.state.value.status == AccountConnectStatus.Connecting) viewModel.submitOrCancel()
        },
        onSignOutUnreadable = if (unreadable) signOutCallbacks.retry else null,
        onCancelAdding = if (adding) viewModel::cancelAdding else null,
    )
}

@Composable
private fun AccountConnectContent(
    state: AccountConnectUiState,
    onServerUrlChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onAllowLocalHttpChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onCancelConnect: () -> Unit,
    onSignOutUnreadable: (() -> Unit)? = null,
    onCancelAdding: (() -> Unit)? = null,
) {
    val connecting = state.status == AccountConnectStatus.Connecting
    val statusLocation = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // StateFlow can conflate a fast retry that produces the same validation error.
    var submissions by remember { mutableIntStateOf(0) }
    var submittedAddress by remember { mutableStateOf(state.serverUrl) }
    val addressFailure = (state.status as? AccountConnectStatus.Failed)?.presentation?.takeIf {
        it.title == SharedR.string.error_invalid_url_title || it.title == SharedR.string.error_internationalized_url_title
    }
    val addressError = addressFailure?.takeIf { state.serverUrl == submittedAddress }
    val status = if (addressFailure != null && addressError == null) AccountConnectStatus.Idle else state.status
    val failed = status is AccountConnectStatus.Failed || status == AccountConnectStatus.PersistenceFailed
    val noticeSemantics = if (failed) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    LaunchedEffect(status, submissions) {
        if (failed) {
            keyboard?.hide()
            focus.clearFocus()
            snapshotFlow { ime.getBottom(density) }.first { it == 0 }
            statusLocation.bringIntoView()
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DulcetSpacing.pageGutter, vertical = DulcetSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(DulcetSpacing.l),
        ) {
            Text(stringResource(R.string.account_connect_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.account_connect_body), style = MaterialTheme.typography.bodyLarge)

            Column(
                modifier = if (addressError != null) Modifier.bringIntoViewRequester(statusLocation) else Modifier,
                verticalArrangement = Arrangement.spacedBy(DulcetSpacing.s),
            ) {
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = onServerUrlChanged,
                    label = { Text(stringResource(R.string.server_address)) },
                    placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                    isError = addressError != null,
                    supportingText = addressError?.let { presentation ->
                        {
                            val hint = if (presentation.title == SharedR.string.error_invalid_url_title) {
                                R.string.server_address_error_hint
                            } else presentation.recovery
                            Text(stringResource(hint), Modifier.testTag("account.server.error"))
                        }
                    },
                    enabled = !connecting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("account.server"),
                )
                if (addressError != null) AccountStatusCard(status, noticeSemantics, showRecovery = false)
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                label = { Text(stringResource(R.string.username)) },
                enabled = !connecting,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("account.username"),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChanged,
                label = { Text(stringResource(R.string.password)) },
                enabled = !connecting,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("account.password"),
            )
            AllowLocalHttpRow(state.allowLocalHttp, enabled = !connecting, onAllowLocalHttpChanged)
            Row(horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.s)) {
                Button(
                    onClick = {
                        submittedAddress = state.serverUrl
                        submissions++
                        onSubmit()
                    },
                    enabled = !connecting,
                    modifier = Modifier.weight(1f).heightIn(min = DulcetSizes.touchTarget).testTag("account.submit"),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(DulcetSizes.progressIndicator),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            strokeWidth = 2.dp, // design-token: allow progress indicator stroke
                        )
                        Spacer(Modifier.width(DulcetSpacing.m))
                    }
                    Text(stringResource(R.string.connect))
                }
                if (connecting) {
                    OutlinedButton(
                        onClick = onCancelConnect,
                        modifier = Modifier.weight(1f).heightIn(min = DulcetSizes.touchTarget).testTag("account.connect.cancel"),
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (!connecting && onCancelAdding != null) OutlinedButton(onClick = onCancelAdding,
                modifier = Modifier.fillMaxWidth().testTag("account.add.cancel")) { Text(stringResource(R.string.cancel)) }
            if (addressError == null) AccountStatusCard(
                status,
                Modifier.bringIntoViewRequester(statusLocation).then(noticeSemantics),
            )
            if (onSignOutUnreadable != null) OutlinedButton(
                onClick = onSignOutUnreadable,
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth().testTag("account.signout-unreadable"),
            ) {
                Text(stringResource(SharedR.string.account_signout_unreadable_action))
            }
            Spacer(Modifier.height(DulcetSpacing.xs))
            Text(
                stringResource(R.string.credential_storage_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AllowLocalHttpRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val firstLine = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = DulcetSizes.touchTarget)
            .testTag("account.allow-local-http")
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.heightIn(min = firstLine), contentAlignment = Alignment.CenterStart) {
            // The row supplies the touch target. Reclaim the passive checkbox's 2 dp drawing inset.
            Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.offset(x = -DulcetSpacing.xs / 2))
        }
        Column {
            Text(stringResource(R.string.allow_local_http), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.allow_local_http_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AccountStatusCard(status: AccountConnectStatus, modifier: Modifier, showRecovery: Boolean = true) {
    when (status) {
        AccountConnectStatus.Idle -> Unit
        AccountConnectStatus.Connecting -> DulcetNotice(
            tone = NoticeTone.Progress,
            title = stringResource(R.string.connecting),
            body = stringResource(R.string.connecting_body),
            showProgressIndicator = false,
            modifier = modifier.testTag("account.status.connecting"),
        )
        is AccountConnectStatus.Saved -> DulcetNotice(
            tone = NoticeTone.Info,
            title = stringResource(R.string.saved_disconnected, status.serverName),
            body = stringResource(R.string.saved_disconnected_body),
            modifier = modifier.testTag("account.status.saved"),
        )
        is AccountConnectStatus.Connected -> DulcetNotice(
            tone = NoticeTone.Info,
            title = stringResource(R.string.connected_to, status.serverName),
            icon = DulcetIcons.Check,
            modifier = modifier.testTag("account.status.connected"),
        )
        is AccountConnectStatus.Failed -> {
            val body = status.presentation.messageArgument?.let {
                stringResource(status.presentation.message, it)
            } ?: stringResource(status.presentation.message)
            DulcetNotice(
                tone = NoticeTone.Error,
                title = stringResource(status.presentation.title),
                body = body,
                detail = if (showRecovery) stringResource(status.presentation.recovery) else null,
                modifier = modifier.testTag("account.status.failed"),
            )
        }
        AccountConnectStatus.PersistenceFailed -> DulcetNotice(
            tone = NoticeTone.Error,
            title = stringResource(R.string.error_persistence_title),
            body = stringResource(R.string.error_persistence_body),
            modifier = modifier.testTag("account.status.persistence-failed"),
        )
        AccountConnectStatus.Unreadable -> DulcetNotice(
            tone = NoticeTone.Error,
            title = stringResource(R.string.error_unreadable_title),
            body = stringResource(R.string.error_unreadable_body),
            modifier = modifier.testTag("account.status.persistence-failed"),
        )
    }
}
