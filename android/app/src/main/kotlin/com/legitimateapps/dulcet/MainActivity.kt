package com.legitimateapps.dulcet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: AccountConnectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DulcetTheme {
                AccountConnectScreen(viewModel)
            }
        }
    }
}

@Composable
internal fun AccountConnectScreen(viewModel: AccountConnectViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountConnectContent(
        state = state,
        onServerUrlChanged = viewModel::updateServerUrl,
        onUsernameChanged = viewModel::updateUsername,
        onPasswordChanged = viewModel::updatePassword,
        onAllowLocalHttpChanged = viewModel::updateAllowLocalHttp,
        onSubmit = viewModel::submitOrCancel,
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
) {
    val connecting = state.status == AccountConnectStatus.Connecting
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.account_connect_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.account_connect_body), style = MaterialTheme.typography.bodyLarge)

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                enabled = !connecting,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("account.server"),
            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.allowLocalHttp,
                    onCheckedChange = onAllowLocalHttpChanged,
                    enabled = !connecting,
                    modifier = Modifier.testTag("account.allow-local-http"),
                )
                Column {
                    Text(stringResource(R.string.allow_local_http))
                    Text(
                        stringResource(R.string.allow_local_http_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth().testTag("account.submit"),
            ) {
                Text(stringResource(if (connecting) R.string.cancel else R.string.connect))
            }

            AccountStatusCard(state.status)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.credential_storage_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AccountStatusCard(status: AccountConnectStatus) {
    when (status) {
        AccountConnectStatus.Idle -> Unit
        AccountConnectStatus.Connecting -> StatusCard(
            title = stringResource(R.string.connecting),
            body = stringResource(R.string.connecting_body),
            tag = "account.status.connecting",
        )
        is AccountConnectStatus.Saved -> StatusCard(
            title = stringResource(R.string.saved_disconnected, status.serverName),
            body = stringResource(R.string.saved_disconnected_body),
            tag = "account.status.saved",
        )
        is AccountConnectStatus.Connected -> StatusCard(
            title = stringResource(R.string.connected_to, status.serverName),
            body = "",
            tag = "account.status.connected",
        )
        is AccountConnectStatus.Failed -> StatusCard(
            title = stringResource(status.presentation.title),
            body = status.presentation.messageArgument?.let {
                stringResource(status.presentation.message, it)
            } ?: stringResource(status.presentation.message),
            recovery = stringResource(status.presentation.recovery),
            tag = "account.status.failed",
        )
        AccountConnectStatus.PersistenceFailed -> StatusCard(
            title = stringResource(R.string.error_persistence_title),
            body = stringResource(R.string.error_persistence_body),
            tag = "account.status.persistence-failed",
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String, tag: String, recovery: String = "") {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium)
            if (recovery.isNotEmpty()) Text(recovery, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DulcetTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
