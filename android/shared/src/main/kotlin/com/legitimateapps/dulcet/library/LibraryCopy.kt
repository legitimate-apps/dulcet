package com.legitimateapps.dulcet.library

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryChangeField
import com.legitimateapps.dulcet.core.AndroidLibraryChangeOutcome
import com.legitimateapps.dulcet.core.AndroidLibraryCoverage
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsOrder
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.shared.R
import java.text.NumberFormat

/*
 * The words for what the core published (spec §16.14, §16.15). One place, used by the phone and the
 * TV app alike, so the two can never say different things about the same state. Nothing here decides
 * anything: every input is a value the core computed.
 */

/** What an unavailable screen is, for its statement of fact. */
public enum class LibrarySubject { Album, List }

/**
 * The one line shown with a publication's content (§16.14), or null for `live` content and for
 * `loading`, which shows an indicator instead. Cached content always says how old it is.
 */
public fun Resources.freshnessLine(freshness: AndroidLibraryFreshness, now: Long = System.currentTimeMillis()): String? =
    when (freshness) {
        AndroidLibraryFreshness.Live, AndroidLibraryFreshness.Loading -> null
        is AndroidLibraryFreshness.Unavailable -> null
        is AndroidLibraryFreshness.Cached -> {
            val seen = freshness.asOfEpochMillis?.let { getString(R.string.library_seen_at, age(it, now)) }
                ?: getString(R.string.library_seen_unknown_age)
            getString(R.string.library_seen_with_reason, seen, reasonPhrase(freshness.reason))
        }
    }

/** The statement shown instead of content when there is none (§16.14): never a spinner. */
public fun Resources.unavailableLine(reason: AndroidLibraryUnavailableReason, subject: LibrarySubject): String = when (reason) {
    AndroidLibraryUnavailableReason.NotCachedOffline -> getString(
        if (subject == LibrarySubject.Album) R.string.library_unavailable_album else R.string.library_unavailable_list,
    )
    AndroidLibraryUnavailableReason.Gone -> getString(R.string.library_unavailable_gone)
    is AndroidLibraryUnavailableReason.Failed -> getString(R.string.library_unavailable_failed, errorPhrase(reason.error))
    AndroidLibraryUnavailableReason.InternalFailure -> getString(R.string.library_unavailable_internal)
    AndroidLibraryUnavailableReason.Closed -> getString(R.string.library_unavailable_closed)
}

/**
 * A list's coverage, stated above the list (§16.14): an open window only while it cannot be extended
 * (offline), a scanning or changing server always. Null when there is nothing to say. Both numbers
 * and the noun are stated: "Showing 120 of 2,950 albums — the rest need a connection".
 */
public fun Resources.coverageLine(publication: AndroidLibraryPublication): String? = when (publication.coverage) {
    AndroidLibraryCoverage.Open -> if (publication.freshness.isOffline() || publication.order == AndroidLibraryItemsOrder.LocalView) {
        val shown = publication.items.size
        val plural = countPlural(publication.items)
        publication.total?.takeIf { it > shown }?.let { total ->
            getString(R.string.library_coverage_open_total, formatCount(shown), getQuantityString(plural, total, formatCount(total)))
        } ?: getString(R.string.library_coverage_open, getQuantityString(plural, shown, formatCount(shown)))
    } else {
        null
    }
    AndroidLibraryCoverage.UnverifiedScanning -> getString(R.string.library_coverage_scanning)
    AndroidLibraryCoverage.UnverifiedChanging -> getString(R.string.library_coverage_changing)
    // Stated once per account, never on every list (§16.12): see noEpochLine.
    AndroidLibraryCoverage.UnverifiedNoEpoch, AndroidLibraryCoverage.Complete, null -> null
}

/** The plural naming what a list holds, from the core's own item kinds. */
private fun countPlural(items: List<AndroidLibraryItem>): Int = when {
    items.isEmpty() -> R.plurals.library_count_items
    items.all { it is AndroidLibraryItem.Album } -> R.plurals.library_count_albums
    items.all { it is AndroidLibraryItem.Artist } -> R.plurals.library_count_artists
    items.all { it is AndroidLibraryItem.Track } -> R.plurals.library_count_tracks
    items.all { it is AndroidLibraryItem.Playlist } -> R.plurals.library_count_playlists
    else -> R.plurals.library_count_items
}

private fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

/**
 * The connection's own failure, stated once above the screen (§16.14), when a reconnect that did not
 * read the epoch — a timeout, credentials, TLS, the server's own error — left the reader offline.
 * Each screen's own line says `offline` when the reader retries that failure by itself, and the
 * failure itself when it does not (§16.14); this one says why either way. Null otherwise, including
 * for a failed reconnect of a reader that is still online, whose screens keep reading.
 */
public fun Resources.connectionLine(state: LibraryConnectionState): String? =
    if (state is LibraryConnectionState.Failed && state.readerOffline) {
        getString(R.string.library_connection_failed, state.error?.let { errorPhrase(it) } ?: getString(R.string.library_reason_internal))
    } else {
        null
    }

/** `unverified(noEpoch)`, stated once per account rather than on every list (§16.12, §16.14). */
public fun Resources.noEpochLine(state: LibraryConnectionState): String? =
    if ((state as? LibraryConnectionState.Online)?.serverReportsNoEpoch == true) getString(R.string.library_no_epoch) else null

/** Unsent changes discarded when the account's user changed, told once (§16.10). */
public fun Resources.discardedChangesLine(count: Long): String? =
    if (count > 0) getQuantityString(R.plurals.library_discarded_changes, count.toQuantity(), count.toQuantity()) else null

/** "Available offline" above a list sorted on this device while offline (§16.14). */
public fun Resources.orderLine(publication: AndroidLibraryPublication): String? =
    if (publication.order == AndroidLibraryItemsOrder.LocalView) getString(R.string.library_local_view) else null

/** The label of a search's scope (§16.15); null when the server answered and nothing needs saying. */
public fun Resources.searchScopeLabel(scope: AndroidLibrarySearchScope?): String? = when (scope) {
    null, AndroidLibrarySearchScope.ServerAndDevice -> null
    AndroidLibrarySearchScope.DeviceWhileServerPending -> getString(R.string.search_scope_device)
    is AndroidLibrarySearchScope.DeviceOffline -> getString(
        R.string.search_scope_offline,
        getQuantityString(R.plurals.search_scope_albums, scope.seen.albums.toQuantity(), scope.seen.albums),
        getQuantityString(R.plurals.search_scope_tracks, scope.seen.tracks.toQuantity(), scope.seen.tracks),
    )
    is AndroidLibrarySearchScope.DeviceServerFailed -> getString(R.string.search_scope_failed, errorPhrase(scope.error))
    AndroidLibrarySearchScope.ReaderFailed -> getString(R.string.search_scope_reader_failed)
}

/** A favourite or rating outcome that needs words; null for one that was saved. */
public fun Resources.outcomeLine(outcome: AndroidLibraryChangeOutcome?): String? = when (outcome) {
    null, is AndroidLibraryChangeOutcome.Saved -> null
    is AndroidLibraryChangeOutcome.NotSaved -> getString(R.string.library_change_not_saved, errorPhrase(outcome.error))
    is AndroidLibraryChangeOutcome.Held -> when (val error = outcome.error) {
        is DomainError.Server.Busy -> getString(R.string.library_change_held_busy)
        else -> getString(R.string.library_change_held_refused, heldPhrase(error))
    }
    is AndroidLibraryChangeOutcome.Superseded -> getString(R.string.library_change_superseded)
    is AndroidLibraryChangeOutcome.NotRecorded -> getString(R.string.library_change_not_recorded)
}

/**
 * What [outcomes] say about [target]: its heart's outcome, then its stars', each [outcomeLine] that
 * needs words, joined; null when neither does.
 */
public fun Resources.outcomeLines(outcomes: Map<LibraryOutcomeKey, AndroidLibraryChangeOutcome>, target: AndroidLibraryEntity): String? =
    AndroidLibraryChangeField.entries.mapNotNull { outcomeLine(outcomes[LibraryOutcomeKey(target, it)]) }
        .takeIf { it.isNotEmpty() }?.joinToString("\n")

/**
 * Why a held change is waiting: the error of the `ping` that checked the account, which can be
 * almost anything but a network failure. It says nothing about the item — an error code such as 70
 * answers the ping, not this change — so it is only ever sign-in, a refusal of access, or the server.
 */
internal fun Resources.heldPhrase(error: DomainError): String = getString(
    when {
        error == DomainError.Auth.InvalidCredentials -> R.string.library_error_credentials
        error is DomainError.Auth -> R.string.library_error_access_refused
        error is DomainError.Server.HttpStatus && (error.status == 403 || error.status == 407) ->
            R.string.library_error_access_refused
        else -> R.string.library_error_server
    },
)

public fun Resources.errorPhrase(error: DomainError): String = getString(
    when (error) {
        DomainError.Transport.Unreachable, DomainError.Transport.Cancelled -> R.string.library_error_unreachable
        DomainError.Transport.Timeout -> R.string.library_error_timeout
        DomainError.Auth.InvalidCredentials -> R.string.library_error_credentials
        DomainError.Auth.Forbidden -> R.string.library_error_forbidden
        is DomainError.Server.Busy -> R.string.library_error_busy
        is DomainError.Security.TlsUntrusted -> R.string.library_error_tls
        is DomainError.Server.Known -> if (error.code == 70) R.string.library_error_not_found else R.string.library_error_server
        is DomainError.Server.Unknown -> if (error.code == 70) R.string.library_error_not_found else R.string.library_error_server
        else -> R.string.library_error_server
    },
)

/**
 * Whether the screen offers "Try again", a reconnect: offline, or a failure. A failure the reader
 * does not retry by itself — credentials, a security or protocol failure, the device's own — waits
 * for the person to ask (§16.14), and the screens may be the only place it shows: a reconnect the
 * reader started itself, such as its own retry, reports its outcome to no session.
 */
public fun AndroidLibraryFreshness.offersRetry(): Boolean = isOffline() || when (this) {
    is AndroidLibraryFreshness.Cached ->
        reason is AndroidLibraryCachedReason.Failed || reason == AndroidLibraryCachedReason.InternalFailure
    is AndroidLibraryFreshness.Unavailable ->
        reason is AndroidLibraryUnavailableReason.Failed || reason == AndroidLibraryUnavailableReason.InternalFailure
    else -> false
}

private fun Resources.reasonPhrase(reason: AndroidLibraryCachedReason): String = when (reason) {
    AndroidLibraryCachedReason.Offline -> getString(R.string.library_reason_offline)
    AndroidLibraryCachedReason.Revalidating -> getString(R.string.library_reason_revalidating)
    AndroidLibraryCachedReason.Stale -> getString(R.string.library_reason_stale)
    AndroidLibraryCachedReason.Owed -> getString(R.string.library_reason_owed)
    AndroidLibraryCachedReason.InternalFailure -> getString(R.string.library_reason_internal)
    is AndroidLibraryCachedReason.Failed -> errorPhrase(reason.error)
}

private fun Resources.age(asOf: Long, now: Long): String =
    if (now - asOf < DateUtils.MINUTE_IN_MILLIS) {
        getString(R.string.library_age_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(asOf, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

private fun Long.toQuantity(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

/** Composable access to the app's resources, for the functions above. */
@Composable
public fun libraryResources(): Resources = LocalContext.current.resources
