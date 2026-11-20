package com.legitimateapps.dulcet.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.AccountConnectOutcome
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.CredentialStoreException
import com.legitimateapps.dulcet.accountFailurePresentation
import com.legitimateapps.dulcet.connectAndSaveAccount
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lean-back account connect. The same connect sequence as the phone ([connectAndSaveAccount]): the
 * core connector decides and only an accepted account is saved. Every control is reachable with the
 * D-pad. While an attempt runs, the disabled Connect button keeps its focus and Cancel is beside it.
 */
@Composable
internal fun TvConnectScreen(
    connect: suspend (AccountConnectionRequest) -> AccountConnectionResult,
    store: AccountCredentialStore,
    onCancelAdding: (() -> Unit)? = null,
    signInAccount: SearchAccount? = null,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val replacement = remember(store) { com.legitimateapps.dulcet.AndroidAccountReplacement(context, store) }
    val replacementOffer by replacement.offer.collectAsStateWithLifecycle()
    replacementOffer?.let { offer ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { replacement.answer(false) }) {
            Surface(Modifier.width(640.dp).testTag("account.replacement")) {
                Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(com.legitimateapps.dulcet.shared.R.string.account_replacement_title))
                    Text(stringResource(com.legitimateapps.dulcet.shared.R.string.account_replacement_body, offer.downloadCount))
                    Button(onClick = { replacement.answer(false) }, modifier = Modifier.testTag("account.replacement.cancel")) {
                        Text(stringResource(R.string.tv_cancel)) }
                    Button(onClick = { replacement.answer(true) }, modifier = Modifier.testTag("account.replacement.confirm")) {
                        Text(stringResource(com.legitimateapps.dulcet.shared.R.string.account_replacement_confirm)) }
                }
            }
        }
    }
    var server by remember(signInAccount) { mutableStateOf(signInAccount?.normalizedBaseUrl.orEmpty()) }
    var username by remember(signInAccount) { mutableStateOf(signInAccount?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var allowLocalHttp by remember(signInAccount) { mutableStateOf(signInAccount?.allowLocalHttp == true) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0L) }
    var persistenceFailed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<DomainError?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    var attemptCancelFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val connecting = attempt != null
    val colors = MaterialTheme.colorScheme
    // Read again whenever the saved-account flag changes, so signing out clears the notice.
    val accountActions = LocalTvAccountActions.current
    val unreadable = remember(accountActions?.saved) {
        runCatching { store.load() }.exceptionOrNull() is CredentialStoreException
    }

    // A connector that returns after the screen leaves must never save an abandoned attempt.
    DisposableEffect(Unit) { onDispose { generation += 1; attempt?.cancel() } }

    fun finishAttempt() {
        // The in-flight Cancel is going away. Restore Connect before removing that focus target.
        if (attemptCancelFocused) submitFocus.requestFocus()
        attempt = null
    }

    fun submit() {
        // A queued click can still have the previous composition's handler.
        if (attempt != null) return
        val mine = ++generation
        failure = null
        persistenceFailed = false
        val request = AccountConnectionRequest(server, username, password, allowLocalHttp)
        // Publish the job first, even when a validation failure completes without suspending.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = connectAndSaveAccount(request, connect, store, stillWanted = { mine == generation },
                replacement = replacement)
            if (mine != generation) return@launch
            finishAttempt()
            when (outcome) {
                is AccountConnectOutcome.Connected -> onConnected()
                is AccountConnectOutcome.Failed -> failure = outcome.error
                AccountConnectOutcome.PersistenceFailed -> persistenceFailed = true
                AccountConnectOutcome.Superseded -> Unit
            }
        }
        attempt = job
        job.start()
    }

    fun cancelAttempt() {
        val job = attempt ?: return
        generation += 1
        job.cancel()
        finishAttempt()
    }

    val errorPresentation = failure?.accountFailurePresentation()
    val addressError = if (failure is DomainError.Input.InvalidServerUrl) {
        errorPresentation?.let { stringResource(it.title) }
    } else null

    Surface(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = TV_PAGE_GUTTER)
                .padding(top = TV_CONNECT_TOP, bottom = TV_PAGE_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.xxxl),
        ) {
            Column(Modifier.weight(3f).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.tv_connect_title),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("tv.connect.title").semantics { heading() },
                )
                Text(stringResource(R.string.tv_connect_body), style = tvMetaLineStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = TvSpacing.m))
                Text(stringResource(R.string.tv_credential_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = TvSpacing.xl))
            }
            // Status grows downwards. Keep the form scrollable so remote focus can reveal rows at
            // the bottom, including a saved account's Sign out action.
            Column(
                Modifier.weight(7f).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("tv.connect.form"),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.l),
            ) {
                TvField(
                    stringResource(R.string.tv_server_address), server,
                    { server = it; if (addressError != null) failure = null },
                    !connecting, "tv.connect.server",
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    placeholder = stringResource(R.string.tv_server_address_hint), modifier = Modifier.focusRequester(first),
                    errorMessage = addressError,
                )
                TvField(stringResource(R.string.tv_username), username, { username = it }, !connecting, "tv.connect.username",
                    KeyboardOptions(imeAction = ImeAction.Next))
                TvField(stringResource(R.string.tv_password), password, { password = it }, !connecting, "tv.connect.password",
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visual = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpacing.l)) {
                    Switch(
                        checked = allowLocalHttp, onCheckedChange = { if (attempt == null) allowLocalHttp = it },
                        enabled = !connecting, modifier = Modifier.testTag("tv.connect.allow-local-http"),
                        colors = SwitchDefaults.colors(
                            disabledCheckedTrackColor = colors.secondaryContainer,
                            disabledCheckedThumbColor = colors.onSecondaryContainer,
                        ),
                    )
                    Text(
                        stringResource(R.string.tv_allow_local_http),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (connecting) colors.onSurfaceVariant.copy(alpha = TV_CONNECT_DISABLED_INK_ALPHA)
                            else colors.onSurface,
                    )
                }
                Row(
                    Modifier.widthIn(max = TV_CONNECT_ACTIONS_WIDTH).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val action = Modifier.weight(1f).heightIn(min = TV_CONTROL_HEIGHT)
                    Button(
                        onClick = ::submit, enabled = !connecting,
                        modifier = action.focusRequester(submitFocus).testTag("tv.connect.submit")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer,
                            disabledContainerColor = colors.surfaceVariant,
                            disabledContentColor = colors.onSurface,
                        ),
                        // TV Material keeps focus on the disabled button. Show its location without
                        // setting up a second Centre press to activate Cancel.
                        border = ButtonDefaults.border(
                            focusedDisabledBorder = Border(BorderStroke(TV_FORM_FOCUS_STROKE, colors.onSurface)),
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(TvSpacing.s, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (connecting) TvConnectProgress()
                            Text(
                                stringResource(if (connecting) R.string.tv_connect_busy else R.string.tv_connect),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    when {
                        connecting -> TvConnectCancel("tv.connect.cancel",
                            action.onFocusChanged { attemptCancelFocused = it.isFocused }, ::cancelAttempt)
                        onCancelAdding != null -> TvConnectCancel("tv.account.add.cancel", action) {
                            if (attempt == null) onCancelAdding()
                        }
                        else -> Box(Modifier.weight(1f))
                    }
                }
                // CONF-09c: keep the shared title, explanation and remedy intact.
                when {
                    errorPresentation != null -> {
                        val explanation = errorPresentation.messageArgument?.let {
                            stringResource(errorPresentation.message, it)
                        } ?: stringResource(errorPresentation.message)
                        TvConnectError(
                            stringResource(errorPresentation.title),
                            explanation + " " + stringResource(errorPresentation.recovery),
                        )
                    }
                    persistenceFailed -> TvConnectError(
                        stringResource(R.string.tv_error_persistence_title), stringResource(R.string.tv_error_persistence))
                    unreadable && !connecting -> TvConnectError(
                        stringResource(R.string.tv_error_unreadable_title), stringResource(R.string.tv_error_unreadable))
                }
                if (accountActions?.saved == true) {
                    Column {
                        if (accountActions.savedServers.isNotEmpty() || accountActions.addServer != null) {
                            TvSettingsHeader(
                                stringResource(R.string.tv_saved_servers),
                                Modifier.padding(bottom = TvSpacing.xl - TvSpacing.xs).semantics { heading() },
                                alignWithRows = false,
                            )
                        }
                        TvSignOutEntry(enabled = !connecting)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvConnectCancel(tag: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.testTag(tag)) {
        Text(stringResource(R.string.tv_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

private const val TV_CONNECT_DISABLED_INK_ALPHA = 0.72f

@Composable
private fun TvConnectProgress() {
    val transition = rememberInfiniteTransition(label = "connect progress")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "connect progress rotation")
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(TV_CONTROL_ICON).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        val stroke = TV_CONNECT_PROGRESS_STROKE.toPx()
        val inset = stroke / 2
        drawArc(color, startAngle = angle, sweepAngle = 240f, useCenter = false,
            topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun TvConnectError(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().testTag("tv.connect.status")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.s),
    ) {
        Icon(DulcetIcons.Warning, contentDescription = null, Modifier.size(TV_CONTROL_ICON), tint = colors.error)
        Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.error)
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Balanced),
                ),
                color = colors.onSurfaceVariant,
            )
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
    errorMessage: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val fieldInk = if (enabled) colors.onSurfaceVariant
        else colors.onSurfaceVariant.copy(alpha = TV_CONNECT_DISABLED_INK_ALPHA)
    val fieldAccent = when {
        errorMessage != null -> colors.error
        focused -> colors.primary
        else -> fieldInk
    }
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
        Text(label, style = MaterialTheme.typography.labelLarge, color = fieldAccent)
        Box(Modifier.fillMaxWidth().padding(top = TV_FIELD_LABEL_GAP)
            .background(colors.surfaceVariant, RoundedCornerShape(TV_CONTROL_RADIUS))
            .border(TV_FORM_FOCUS_STROKE, if (focused || errorMessage != null) fieldAccent else colors.surfaceVariant,
                RoundedCornerShape(TV_CONTROL_RADIUS))
            .padding(horizontal = TvSpacing.l, vertical = TvSpacing.m)) {
            BasicTextField(
                value = value, onValueChange = onChange, enabled = enabled, singleLine = true, readOnly = !editing,
                keyboardOptions = keyboard, visualTransformation = visual,
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                textStyle = tvMetaLineStyle().copy(color = fieldInk),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    inner()
                },
                // A text field consumes the D-pad for its cursor, which traps a remote in the first
                // field. Up and Down leave the field; Left and Right still move the cursor.
                modifier = modifier.fillMaxWidth()
                    .semantics { if (errorMessage != null) error(errorMessage) }
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
