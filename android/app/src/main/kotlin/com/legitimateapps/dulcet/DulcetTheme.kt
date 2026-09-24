package com.legitimateapps.dulcet

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DulcetLight = lightColorScheme(
    primary = Color(0xFF8B3A62), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E7), onPrimaryContainer = Color(0xFF3A0020),
    secondary = Color(0xFF725763), tertiary = Color(0xFF7F5539),
)
private val DulcetDark = darkColorScheme(
    primary = Color(0xFFFFB0CF), onPrimary = Color(0xFF560234),
    primaryContainer = Color(0xFF702249), onPrimaryContainer = Color(0xFFFFD8E7),
    secondary = Color(0xFFE0BDCA), tertiary = Color(0xFFF4BA93),
)

/** Material You on Android 12 and later; the Dulcet palette before that. Follows the system theme. */
@Composable
internal fun DulcetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DulcetDark
        else -> DulcetLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
