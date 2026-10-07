package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.AccountConnectOutcome
import com.legitimateapps.dulcet.AccountFailurePresentation
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.CredentialStoreException
import com.legitimateapps.dulcet.accountFailurePresentation
import com.legitimateapps.dulcet.connectAndSaveAccount
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.statement
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lean-back account connect. The same connect sequence as the phone ([connectAndSaveAccount]): the
 * core connector decides and only an accepted account is saved. Every control is reachable with the
 * D-pad, and Connect becomes Cancel while an attempt runs.
 */
@Composable
internal fun TvConnectScreen(
    connect: suspend (AccountConnectionRequest) -> AccountConnectionResult,
    store: AccountCredentialStore,
    onConnected: () -> Unit,
) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowLocalHttp by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<Int?>(null) }
    var failure by remember { mutableStateOf<AccountFailurePresentation?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val connecting = attempt?.isActive == true
    // A saved account whose record cannot be read (its Keystore key lost, say) is said, not left as
    // an unexplained empty form beside Sign out. Read again whenever the saved account changes, so
    // signing it out takes the line away.
    val saved = LocalTvAccountActions.current?.saved
    val unreadable = remember(saved) { runCatching { store.load() }.exceptionOrNull() is CredentialStoreException }
    // A form that leaves the screen abandons its attempt, so a connect answered after that saves
    // nothing — not even when the connector returns without noticing its scope was cancelled.
    // `stillWanted` reads this. Defence in depth: Sign out, the one way off this form while a
    // connect runs, is disabled meanwhile.
    DisposableEffect(Unit) { onDispose { generation += 1; attempt?.cancel() } }

    // Reads live state, never `connecting` from the composition it was created in: the button may
    // keep an earlier composition's handler, and a stale "not connecting" turned Cancel into a
    // second Connect that saved the credentials the user had just cancelled.
    fun submitOrCancel() {
        generation += 1
        if (attempt?.isActive == true) { attempt?.cancel(); attempt = null; message = null; return }
        val mine = generation
        failure = null
        message = R.string.tv_connecting
        attempt = scope.launch {
            val outcome = connectAndSaveAccount(AccountConnectionRequest(server, username, password, allowLocalHttp),
                connect, store, stillWanted = { mine == generation })
            if (mine != generation) return@launch
            attempt = null
            when (outcome) {
                is AccountConnectOutcome.Connected -> { message = null; onConnected() }
                is AccountConnectOutcome.Failed -> { message = null; failure = outcome.error.accountFailurePresentation() }
                AccountConnectOutcome.PersistenceFailed -> message = R.string.tv_error_persistence
                AccountConnectOutcome.Superseded -> Unit
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.tv_connect_title), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.tv_connect_body), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.tv_credential_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            }
            // The form scrolls: with a status line and Sign out below Connect it is taller than a
            // 1080p screen's 540 dp, and a control the D-pad reaches must also be seen. Each control
            // brings itself into view when focused; a short form stays centred.
            BoxWithConstraints(Modifier.width(560.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .testTag("tv.connect.form"),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                TvField(stringResource(R.string.tv_server_address), server, { server = it }, !connecting, "tv.connect.server",
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    placeholder = stringResource(R.string.tv_server_address_hint), modifier = Modifier.focusRequester(first))
                TvField(stringResource(R.string.tv_username), username, { username = it }, !connecting, "tv.connect.username",
                    KeyboardOptions(imeAction = ImeAction.Next))
                TvField(stringResource(R.string.tv_password), password, { password = it }, !connecting, "tv.connect.password",
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visual = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Switch(checked = allowLocalHttp, onCheckedChange = { if (!connecting) allowLocalHttp = it },
                        modifier = Modifier.testTag("tv.connect.allow-local-http"))
                    Text(stringResource(R.string.tv_allow_local_http), style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = ::submitOrCancel, modifier = Modifier.testTag("tv.connect.submit")) {
                    Text(stringResource(if (connecting) R.string.tv_cancel else R.string.tv_connect))
                }
                // A failure says what every Android shell says (CONF-09c): its title, what happened and
                // what to do, with the decided remedies.
                val resources = LocalContext.current.resources
                (failure?.statement(resources) ?: (message ?: R.string.tv_error_unreadable.takeIf { unreadable })
                    ?.let { stringResource(it) })?.let { said ->
                    Text(said, style = MaterialTheme.typography.bodyLarge,
                        color = if (failure == null && message == R.string.tv_connecting) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("tv.connect.status"))
                }
                // A saved account whose record cannot be read never reaches the app; it is signed out
                // from here (spec §14.7). Renders nothing when no account is saved. Disabled while a
                // connect runs, as the phone's is: a sign-out begun then would race the connect's save.
                TvSignOutEntry(modifier = Modifier.padding(top = 16.dp), enabled = !connecting)
            }
            }
        }
    }
}

@Composable
internal fun TvField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    tag: String,
    keyboard: KeyboardOptions,
    placeholder: String? = null,
    visual: VisualTransformation = VisualTransformation.None,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    // As on the search screen: the field is read-only until it is selected (the centre key, or a
    // tap), so the remote can land on it or pass over it without the keyboard coming up and taking
    // the D-pad. A read-only field opens no input session. Leaving it makes it read-only again.
    val softKeyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { withFrameNanos { }; softKeyboard?.show() }
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (focused) colors.primary else colors.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) colors.primary else colors.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicTextField(
                value = value, onValueChange = onChange, enabled = enabled, singleLine = true, readOnly = !editing,
                keyboardOptions = keyboard, visualTransformation = visual,
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurfaceVariant),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    inner()
                },
                // A text field consumes the D-pad for its cursor, which traps a remote in the first
                // field. Up and Down leave the field; Left and Right still move the cursor.
                modifier = modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused; if (!it.isFocused) editing = false }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            editing = true
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // Selecting the field asks for the keyboard; again, once Back has closed it.
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (editing) softKeyboard?.show() else editing = true
                                true
                            }
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    }.testTag(tag),
            )
        }
    }
}
