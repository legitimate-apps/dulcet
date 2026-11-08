package com.legitimateapps.dulcet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.legitimateapps.dulcet.shared.R

/**
 * A sort control as TalkBack hears it. The phone chip shows the order short ("Title") and the TV one as "Sort: Title (A–Z)";
 * both are named with the whole choice in words, "Sort by Title, A to Z", with a "(Z–A)" order spoken "Z to A". The control
 * opens a list of orders, so it says so, and whether that list is open. [order] is the order's full label, as its menu
 * names it. Nothing drawn changes.
 */
@Composable
public fun Modifier.sortControlSemantics(order: String, open: Boolean): Modifier {
    val spokenOrder = when {
        order.endsWith(" (A–Z)") -> stringResource(R.string.library_sort_spoken_ascending, order.removeSuffix(" (A–Z)"))
        order.endsWith(" (Z–A)") -> stringResource(R.string.library_sort_spoken_descending, order.removeSuffix(" (Z–A)"))
        else -> order
    }
    val name = stringResource(R.string.library_sort_spoken, spokenOrder)
    val state = stringResource(if (open) R.string.library_sort_list_open else R.string.library_sort_list_closed)
    return semantics {
        contentDescription = name
        role = Role.DropdownList
        stateDescription = state
    }
}
