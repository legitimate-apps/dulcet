package com.legitimateapps.dulcet.playback

import android.content.Context
import android.content.SharedPreferences
import android.net.NetworkCapabilities
import com.legitimateapps.dulcet.shared.R
import com.legitimateapps.dulcet.core.NetworkCostClass
import com.legitimateapps.dulcet.core.StreamingQuality
import com.legitimateapps.dulcet.core.StreamingQualityPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The device's streaming-quality choice (spec §12.5): one value for an unmetered network and one
 * for a metered one, stored in the core's encoded form so it means what it means on Apple. It is a
 * device setting, not account data, so signing out leaves it. The settings screens write it; the
 * playback service reads it and hands it to the controller, which applies it from the next item.
 */
public class StreamingQualitySettings private constructor(
    private val context: Context,
    private val preferences: SharedPreferences,
) {
    private val state = MutableStateFlow(StreamingQualityPreference.decode(preferences.getString(KEY, null)))

    public val preference: StateFlow<StreamingQualityPreference> = state.asStateFlow()

    public fun set(preference: StreamingQualityPreference) {
        preferences.edit().putString(KEY, preference.encoded()).apply()
        state.value = preference
    }

    public companion object {
        private const val PREFERENCES_NAME = "dulcet.streaming-quality"
        private const val KEY = "preference"
        private var instance: StreamingQualitySettings? = null

        /** One per application, so every screen and the service see one value as it changes. */
        @Synchronized
        public fun get(context: Context): StreamingQualitySettings {
            val application = context.applicationContext
            instance?.takeIf { it.context === application }?.let { return it }
            return StreamingQualitySettings(
                application,
                application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

/**
 * What the OS says the network costs. Without `NOT_METERED` the platform bills or limits it
 * (cellular, a hotspot, a Wi-Fi network marked metered), so the metered choice applies.
 */
internal fun NetworkCapabilities.costClass(): NetworkCostClass =
    if (hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkCostClass.Unmetered
    else NetworkCostClass.Metered

/** The words a settings screen shows for [quality]. */
public fun StreamingQuality.label(context: Context): String = when (val kbps = maxBitRateKbps) {
    null -> context.getString(R.string.streaming_quality_original)
    else -> context.getString(R.string.streaming_quality_kbps, kbps)
}
