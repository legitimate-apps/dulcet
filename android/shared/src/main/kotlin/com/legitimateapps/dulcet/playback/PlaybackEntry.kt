package com.legitimateapps.dulcet.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Shared production entry; the Android/TV search detail surfaces supply only opaque identity. */
@Composable
fun PlaybackEntry(provider: String, rawId: String, title: String) {
    val context = LocalContext.current
    Box(Modifier.testTag("playback.open").clickable(role = Role.Button) {
        context.startActivity(PlaybackActivity.intent(context, provider, rawId, title))
    }.padding(16.dp)) { BasicText("Play") }
}
