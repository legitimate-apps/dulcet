package com.legitimateapps.dulcet.tv

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.AccountRemovalJournal
import com.legitimateapps.dulcet.AccountSignOut
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.CoreAccountDataGateway
import com.legitimateapps.dulcet.ServicePlaybackRelease
import com.legitimateapps.dulcet.SignOutState
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The TV's sign-out (spec §14.7): the same [AccountSignOut] as the phone, owned by the activity's
 * view model so it survives recomposition. Created before the activity reads the saved account, so
 * a sign-out the previous process began has deleted its credential first.
 */
internal class TvAccountModel(application: Application) : AndroidViewModel(application) {
    private val finished = MutableStateFlow(0)

    /** Counts finished sign-outs; the activity reads the saved account again on each. */
    val signedOut: StateFlow<Int> = finished.asStateFlow()

    private val credentials = AndroidAccountCredentialStore(application)
    private val playback = ServicePlaybackRelease(application)
    fun savedAccounts() = runCatching { credentials.savedAccounts() }.getOrDefault(emptyList())
    fun switchServer(id: String) { viewModelScope.launch {
        if (com.legitimateapps.dulcet.selectSavedServer(id, credentials, playback)) finished.value += 1
    } }
    val signOut = AccountSignOut(
        scope = viewModelScope,
        credentials = credentials,
        accountData = CoreAccountDataGateway(application),
        playback = playback,
        journal = AccountRemovalJournal(application),
        onSignedOut = { finished.value += 1 },
    )

    init {
        signOut.resumeInterrupted()
    }
}

/**
 * Wraps the TV app. While an account is saved — readable or not, so an account whose record cannot
 * be read can be signed out from the connect screen too — the shell's account places offer Sign out
 * through [LocalTvAccountActions]; each step that needs an answer is a dialog; and while the account
 * is sent for or removed, a signing-out screen stands in for the app, so nothing can start new work
 * for it.
 *
 * [account] is whatever the activity last read as the signed-in account; it only keys when the
 * saved account is looked up again, so connecting shows the entry without waiting for a restart.
 */
@Composable
internal fun TvAccountHost(signOut: AccountSignOut, account: Any?,
    savedServers: List<com.legitimateapps.dulcet.StoredAccount> = emptyList(),
    selectServer: (String) -> Unit = {}, addServer: (() -> Unit)? = null,
    content: @Composable () -> Unit) {
    val state by signOut.state.collectAsStateWithLifecycle()
    TvSignOutDialogs(state, signOut)
    if (state.hidesAccount) {
        TvSigningOut(state, signOut)
        return
    }
    val saved = remember(account, state) { signOut.savedAccountId() != null }
    val active = remember(account, state) { signOut.savedAccountId() }
    CompositionLocalProvider(LocalTvAccountActions provides
        TvAccountActions(saved, signOut::request, savedServers, selectServer, addServer, active)) { content() }
}

/** What the shell's account places show: whether an account is saved, and the way to begin §14.7. */
internal class TvAccountActions(val saved: Boolean, val requestSignOut: () -> Unit,
    val savedServers: List<com.legitimateapps.dulcet.StoredAccount> = emptyList(),
    val selectServer: (String) -> Unit = {}, val addServer: (() -> Unit)? = null,
    /** The saved account's own id: the current server, marked in the list. */
    val activeServerId: String? = null)

/** Provided by [TvAccountHost] around the app; null where Sign out cannot be offered at all. */
internal val LocalTvAccountActions = staticCompositionLocalOf<TvAccountActions?> { null }

/**
 * The Sign out entry. It lives in the shell's account places — the library's Account screen, and
 * the connect screen when a saved account cannot be read — never in the navigation bar. It is never
 * a screen's default focus: a remote reaches it only by moving to it.
 */
@Composable
internal fun TvSignOutEntry(modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.xs)) {
        TvSavedServerList(enabled = enabled)
        TvSignOutAction(modifier, enabled)
    }
}

/** The saved servers, each a row that switches to it, the current one checked; then Add server. */
@Composable
internal fun TvSavedServerList(enabled: Boolean = true, defaultFocus: Boolean = false) {
    val actions = LocalTvAccountActions.current ?: return
    if (!actions.saved) return
    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.xs)) {
        actions.savedServers.forEachIndexed { index, server ->
            val current = server.id == actions.activeServerId
            androidx.tv.material3.ListItem(
                scale = TvRowScale,
                selected = current,
                onClick = { actions.selectServer(server.id) },
                enabled = enabled,
                modifier = Modifier.tvFocus("tv.account.server.${server.id}", default = defaultFocus && index == 0),
                supportingContent = { androidx.tv.material3.Text(server.serverUrl, color = tvRowLineColor()) },
                trailingContent = {
                    if (current) {
                        androidx.tv.material3.Icon(DulcetIcons.Check,
                            stringResource(R.string.tv_current_server))
                    }
                },
                headlineContent = { androidx.tv.material3.Text("${server.serverName} · ${server.username}") },
            )
        }
        actions.addServer?.let { add ->
            androidx.tv.material3.ListItem(
                scale = TvRowScale,
                selected = false,
                onClick = add,
                enabled = enabled,
                modifier = Modifier.tvFocus("tv.account.add-server", default = defaultFocus && actions.savedServers.isEmpty()),
                headlineContent = { androidx.tv.material3.Text(stringResource(SharedR.string.account_add_server)) },
            )
        }
    }
}

/** Sign out: the last row of the account page, in the error color, asking first (§14.7). */
@Composable
internal fun TvSignOutAction(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val actions = LocalTvAccountActions.current ?: return
    if (!actions.saved) return
    androidx.tv.material3.ListItem(
        scale = TvRowScale,
        selected = false,
        onClick = actions.requestSignOut,
        enabled = enabled,
        modifier = modifier.tvFocus("tv.account.signout"),
        colors = tvDestructiveRowColors(),
        headlineContent = { androidx.tv.material3.Text(stringResource(SharedR.string.account_signout_action)) },
    )
}

/** One dialog per step of §14.7 that needs an answer; dismissing one means Stay signed in. */
@Composable
internal fun TvSignOutDialogs(state: SignOutState, signOut: AccountSignOut) {
    val stay = stringResource(SharedR.string.account_signout_stay)
    when (state) {
        SignOutState.Idle, SignOutState.Sending, SignOutState.Removing -> Unit
        SignOutState.Checking -> TvMessageDialog(
            "signout.checking", null, stringResource(SharedR.string.account_signout_checking),
            signOut::stay, width = TvDialogWidth.List,
        ) {
            TvDialogButton(stay, "signout.stay", signOut::stay, initialFocus = true)
        }
        is SignOutState.Confirm -> TvConfirmDialog(
            tag = "signout.confirm",
            title = stringResource(SharedR.string.account_signout_confirm_title, state.serverName),
            body = stringResource(SharedR.string.account_signout_confirm_body),
            safeLabel = stay,
            safeTag = "signout.stay",
            destructiveLabel = stringResource(SharedR.string.account_signout_action),
            destructiveTag = "signout.signout",
            onSafe = signOut::stay,
            onDestructive = signOut::signOut,
        )
        is SignOutState.Offer -> {
            val plays = state.pending.plays.size
            val edits = state.pending.edits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val body = listOfNotNull(
                pluralStringResource(SharedR.plurals.account_signout_pending_plays, plays, plays).takeIf { plays > 0 },
                pluralStringResource(SharedR.plurals.account_signout_pending_edits, edits, edits).takeIf { edits > 0 },
                stringResource(
                    if (state.afterSend) SharedR.string.account_signout_offer_after_send_body
                    else SharedR.string.account_signout_offer_body,
                ),
            ).joinToString("\n")
            TvMessageDialog(
                "signout.offer",
                stringResource(
                    if (state.afterSend) SharedR.string.account_signout_offer_after_send_title
                    else SharedR.string.account_signout_offer_title,
                ),
                body,
                signOut::stay,
            ) {
                if (state.pending.total > 0) TvDialogButton(
                    stringResource(if (state.afterSend) SharedR.string.account_signout_retry_send else SharedR.string.account_signout_send),
                    "signout.send",
                    signOut::send,
                )
                TvDialogButton(stay, "signout.stay", signOut::stay, initialFocus = true)
                TvDialogButton(stringResource(SharedR.string.account_signout_discard), "signout.discard",
                    signOut::signOut, destructive = true)
            }
        }
        is SignOutState.Unknown -> TvConfirmDialog(
            tag = "signout.unknown",
            title = stringResource(SharedR.string.account_signout_unknown_title),
            body = stringResource(SharedR.string.account_signout_unknown_body),
            safeLabel = stay,
            safeTag = "signout.stay",
            destructiveLabel = stringResource(SharedR.string.account_signout_anyway),
            destructiveTag = "signout.anyway",
            onSafe = signOut::stay,
            onDestructive = signOut::signOutAnyway,
        )
        SignOutState.Failed -> TvMessageDialog(
            "signout.failed",
            stringResource(SharedR.string.account_signout_failed_title),
            stringResource(SharedR.string.account_signout_failed_body),
            signOut::dismissFailure,
            width = TvDialogWidth.List,
        ) {
            TvDialogButton(stringResource(SharedR.string.account_signout_try_again), "signout.retry",
                signOut::request, initialFocus = true)
            TvDialogButton(stringResource(SharedR.string.account_signout_close), "signout.close", signOut::dismissFailure)
        }
    }
}

@Composable
private fun TvSigningOut(state: SignOutState, signOut: AccountSignOut) {
    Surface(Modifier.fillMaxSize().testTag("signout.progress")) {
        Column(
            Modifier.fillMaxSize().padding(TV_SAFE_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.xl, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    if (state == SignOutState.Sending) SharedR.string.account_signout_sending
                    else SharedR.string.account_signout_removing,
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            // A send never waits silently on an unreachable server: it can be abandoned here.
            if (state == SignOutState.Sending) TvDialogButton(
                stringResource(SharedR.string.account_signout_stay), "signout.stay", signOut::stay, initialFocus = true,
            )
        }
    }
}
