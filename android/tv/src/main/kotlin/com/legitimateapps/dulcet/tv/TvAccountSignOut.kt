package com.legitimateapps.dulcet.tv

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
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

    val signOut = AccountSignOut(
        scope = viewModelScope,
        credentials = AndroidAccountCredentialStore(application),
        accountData = CoreAccountDataGateway(application),
        playback = ServicePlaybackRelease(application),
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
internal fun TvAccountHost(signOut: AccountSignOut, account: Any?, content: @Composable () -> Unit) {
    val state by signOut.state.collectAsStateWithLifecycle()
    TvSignOutDialogs(state, signOut)
    if (state.hidesAccount) {
        TvSigningOut(state, signOut)
        return
    }
    val saved = remember(account, state) { signOut.savedAccountId() != null }
    CompositionLocalProvider(LocalTvAccountActions provides TvAccountActions(saved, signOut::request)) { content() }
}

/** What the shell's account places show: whether an account is saved, and the way to begin §14.7. */
internal class TvAccountActions(val saved: Boolean, val requestSignOut: () -> Unit)

/** Provided by [TvAccountHost] around the app; null where Sign out cannot be offered at all. */
internal val LocalTvAccountActions = staticCompositionLocalOf<TvAccountActions?> { null }

/**
 * The Sign out entry. It lives in the shell's account places — the library's Account screen, and
 * the connect screen when a saved account cannot be read — never in the navigation bar. It is never
 * a screen's default focus: a remote reaches it only by moving to it.
 */
@Composable
internal fun TvSignOutEntry(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val actions = LocalTvAccountActions.current ?: return
    if (!actions.saved) return
    Button(onClick = actions.requestSignOut, enabled = enabled, modifier = modifier.tvFocus("tv.account.signout")) {
        Text(stringResource(SharedR.string.account_signout_action))
    }
}

/** One dialog per step of §14.7 that needs an answer; dismissing one means Stay signed in. */
@Composable
internal fun TvSignOutDialogs(state: SignOutState, signOut: AccountSignOut) {
    when (state) {
        SignOutState.Idle, SignOutState.Sending, SignOutState.Removing -> Unit
        SignOutState.Checking -> TvDialog("signout.checking", null, stringResource(SharedR.string.account_signout_checking), signOut::stay) {
            TvStay(signOut, focused = true)
        }
        is SignOutState.Confirm -> TvDialog(
            "signout.confirm",
            stringResource(SharedR.string.account_signout_confirm_title, state.serverName),
            stringResource(SharedR.string.account_signout_confirm_body),
            signOut::stay,
        ) {
            TvStay(signOut, focused = true)
            TvChoice("signout.signout", SharedR.string.account_signout_action, signOut::signOut)
        }
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
            TvDialog(
                "signout.offer",
                stringResource(
                    if (state.afterSend) SharedR.string.account_signout_offer_after_send_title
                    else SharedR.string.account_signout_offer_title,
                ),
                body,
                signOut::stay,
            ) {
                if (state.pending.total > 0) TvChoice(
                    "signout.send",
                    if (state.afterSend) SharedR.string.account_signout_retry_send else SharedR.string.account_signout_send,
                    signOut::send,
                )
                TvStay(signOut, focused = true)
                TvChoice("signout.discard", SharedR.string.account_signout_discard, signOut::signOut)
            }
        }
        is SignOutState.Unknown -> TvDialog(
            "signout.unknown",
            stringResource(SharedR.string.account_signout_unknown_title),
            stringResource(SharedR.string.account_signout_unknown_body),
            signOut::stay,
        ) {
            TvStay(signOut, focused = true)
            TvChoice("signout.anyway", SharedR.string.account_signout_anyway, signOut::signOutAnyway)
        }
        SignOutState.Failed -> TvDialog(
            "signout.failed",
            stringResource(SharedR.string.account_signout_failed_title),
            stringResource(SharedR.string.account_signout_failed_body),
            signOut::dismissFailure,
        ) {
            TvChoice("signout.retry", SharedR.string.account_signout_try_again, signOut::request, focused = true)
            TvChoice("signout.close", SharedR.string.account_signout_close, signOut::dismissFailure)
        }
    }
}

@Composable
private fun TvSigningOut(state: SignOutState, signOut: AccountSignOut) {
    Surface(Modifier.fillMaxSize().testTag("signout.progress")) {
        Column(
            Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
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
            if (state == SignOutState.Sending) TvStay(signOut, focused = true)
        }
    }
}

@Composable
private fun TvDialog(tag: String, title: String?, body: String, onDismiss: () -> Unit, buttons: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(640.dp).testTag(tag)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(body, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { buttons() }
            }
        }
    }
}

/** Stay signed in: the default of §14.7, so it takes the D-pad's focus when the dialog opens. */
@Composable
private fun TvStay(signOut: AccountSignOut, focused: Boolean) =
    TvChoice("signout.stay", SharedR.string.account_signout_stay, signOut::stay, focused)

@Composable
private fun TvChoice(tag: String, label: Int, onClick: () -> Unit, focused: Boolean = false) {
    val focus = remember { FocusRequester() }
    if (focused) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Button(onClick = onClick, modifier = Modifier.focusRequester(focus).testTag(tag)) { Text(stringResource(label)) }
}
