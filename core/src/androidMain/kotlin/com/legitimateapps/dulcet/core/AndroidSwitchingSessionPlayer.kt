package com.legitimateapps.dulcet.core

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player

/** Keeps the media session stable while the incoming overlap player becomes current. */
internal class AndroidSwitchingSessionPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    fun switchTo(incoming: Player) { setPlayer(incoming) }
    fun refresh() { invalidateState() }
}
