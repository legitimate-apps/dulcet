package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.shared.R
import com.legitimateapps.dulcet.ui.DulcetIcons

/** The signed-in account as the account control shows it, and the way to begin signing out. */
internal class AccountActions(
    val serverName: String,
    val username: String,
    val requestSignOut: () -> Unit,
)

/** Provided around the signed-in app by [AccountConnectScreen]; null where no account is signed in. */
internal val LocalAccountActions = staticCompositionLocalOf<AccountActions?> { null }

/**
 * The account entry for the phone app's navigation bar: it opens the account dialog, whose Sign
 * out starts §14.7. Renders nothing where no account is provided.
 */
@Composable
internal fun RowScope.AccountNavigationItem() {
    val actions = LocalAccountActions.current ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    NavigationBarItem(
        selected = false,
        onClick = { open = true },
        icon = { Icon(DulcetIcons.Person, null) },
        label = { Text(stringResource(R.string.account_signout_account_label)) },
        modifier = Modifier.testTag("account.open"),
    )
    if (open) AccountDialog(actions) { open = false }
}

@Composable
internal fun AccountDialog(actions: AccountActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("account.dialog"),
        title = { Text(stringResource(R.string.account_signout_account_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.account_signout_account_body, actions.serverName, actions.username))
                StreamingQualitySection()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); actions.requestSignOut() },
                modifier = Modifier.testTag("account.signout"),
            ) { Text(stringResource(R.string.account_signout_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_signout_close)) }
        },
    )
}

/** What each sign-out dialog button does. */
internal class SignOutCallbacks(
    val send: () -> Unit,
    val signOut: () -> Unit,
    val signOutAnyway: () -> Unit,
    val stay: () -> Unit,
    val retry: () -> Unit,
    val dismissFailure: () -> Unit,
)

internal fun AccountSignOut.callbacks() = SignOutCallbacks(
    send = ::send,
    signOut = ::signOut,
    signOutAnyway = ::signOutAnyway,
    stay = ::stay,
    retry = ::request,
    dismissFailure = ::dismissFailure,
)

/**
 * The dialog for each step of §14.7 that needs an answer. Dismissing any of them outside its
 * buttons means Stay signed in: the removal never proceeds without an explicit choice.
 */
@Composable
internal fun SignOutDialogs(state: SignOutState, callbacks: SignOutCallbacks) {
    when (state) {
        SignOutState.Idle, SignOutState.Sending, SignOutState.Removing -> Unit
        SignOutState.Checking -> AlertDialog(
            onDismissRequest = callbacks.stay,
            modifier = Modifier.testTag("signout.checking"),
            text = { Text(stringResource(R.string.account_signout_checking)) },
            confirmButton = { StayButton(callbacks) },
        )
        is SignOutState.Confirm -> AlertDialog(
            onDismissRequest = callbacks.stay,
            modifier = Modifier.testTag("signout.confirm"),
            title = { Text(stringResource(R.string.account_signout_confirm_title, state.serverName)) },
            text = { Text(stringResource(R.string.account_signout_confirm_body)) },
            confirmButton = {
                TextButton(onClick = callbacks.signOut, modifier = Modifier.testTag("signout.signout")) {
                    Text(stringResource(R.string.account_signout_action))
                }
            },
            dismissButton = { StayButton(callbacks) },
        )
        is SignOutState.Offer -> AlertDialog(
            onDismissRequest = callbacks.stay,
            modifier = Modifier.testTag("signout.offer"),
            title = {
                Text(stringResource(
                    if (state.afterSend) R.string.account_signout_offer_after_send_title
                    else R.string.account_signout_offer_title,
                ))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val plays = state.pending.plays.size
                    val edits = state.pending.edits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (plays > 0) Text(
                        pluralStringResource(R.plurals.account_signout_pending_plays, plays, plays),
                        modifier = Modifier.testTag("signout.pending.plays"),
                    )
                    if (edits > 0) Text(
                        pluralStringResource(R.plurals.account_signout_pending_edits, edits, edits),
                        modifier = Modifier.testTag("signout.pending.edits"),
                    )
                    Text(stringResource(
                        if (state.afterSend) R.string.account_signout_offer_after_send_body
                        else R.string.account_signout_offer_body,
                    ))
                }
            },
            // Stay signed in comes first and is the default of §14.7; the destructive choice is last.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    // Plays go through the scrobble outbox, changes through the library reader's flush.
                    if (state.pending.total > 0) TextButton(onClick = callbacks.send, modifier = Modifier.testTag("signout.send")) {
                        Text(stringResource(
                            if (state.afterSend) R.string.account_signout_retry_send else R.string.account_signout_send,
                        ))
                    }
                    StayButton(callbacks)
                    TextButton(onClick = callbacks.signOut, modifier = Modifier.testTag("signout.discard")) {
                        Text(stringResource(R.string.account_signout_discard))
                    }
                }
            },
        )
        is SignOutState.Unknown -> AlertDialog(
            onDismissRequest = callbacks.stay,
            modifier = Modifier.testTag("signout.unknown"),
            title = { Text(stringResource(R.string.account_signout_unknown_title)) },
            text = { Text(stringResource(R.string.account_signout_unknown_body)) },
            confirmButton = { StayButton(callbacks) },
            dismissButton = {
                TextButton(onClick = callbacks.signOutAnyway, modifier = Modifier.testTag("signout.anyway")) {
                    Text(stringResource(R.string.account_signout_anyway))
                }
            },
        )
        SignOutState.Failed -> AlertDialog(
            onDismissRequest = callbacks.dismissFailure,
            modifier = Modifier.testTag("signout.failed"),
            title = { Text(stringResource(R.string.account_signout_failed_title)) },
            text = { Text(stringResource(R.string.account_signout_failed_body)) },
            confirmButton = {
                TextButton(onClick = callbacks.retry, modifier = Modifier.testTag("signout.retry")) {
                    Text(stringResource(R.string.account_signout_try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = callbacks.dismissFailure) { Text(stringResource(R.string.account_signout_close)) }
            },
        )
    }
}

@Composable
private fun StayButton(callbacks: SignOutCallbacks) {
    TextButton(onClick = callbacks.stay, modifier = Modifier.testTag("signout.stay")) {
        Text(stringResource(R.string.account_signout_stay))
    }
}

/**
 * Stands where the account was once playback has been released for a sign-out, so nothing can
 * start new work for the account while it is sent for or removed (spec §14.7 step 1).
 */
@Composable
internal fun SigningOutSurface(state: SignOutState, callbacks: SignOutCallbacks) {
    Surface(Modifier.fillMaxSize().testTag("signout.progress")) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)) {
                if (state == SignOutState.Sending || state == SignOutState.Removing) CircularProgressIndicator()
                Text(
                    stringResource(if (state == SignOutState.Sending) R.string.account_signout_sending
                        else R.string.account_signout_removing),
                    style = MaterialTheme.typography.titleMedium,
                )
                // A send never waits silently on an unreachable server: it can be abandoned here.
                if (state == SignOutState.Sending) StayButton(callbacks)
            }
        }
    }
}
