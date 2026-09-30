package com.legitimateapps.dulcet.core

import kotlin.concurrent.Volatile

/**
 * How much of the original a stream may carry (spec §12.5): the original file, or a bitrate cap.
 * One preference, two encodings — the `ClientInfo` bitrate limits on the transcoding extension's
 * path and the `maxBitRate` hint on the legacy `stream` path ([PlaybackResolveRequest.withStreamingQuality]).
 * Never applied to a download, which is always the original file (spec §14.5).
 */
public enum class StreamingQuality(
    /** The cap in kilobits per second; null for the original. */
    public val maxBitRateKbps: Int?,
    /** The stable name a preference is stored under. Never renamed: a stored value must decode. */
    public val wireName: String,
) {
    Original(null, "original"),
    Kbps320(320, "320"),
    Kbps256(256, "256"),
    Kbps192(192, "192"),
    Kbps128(128, "128"),
    Kbps96(96, "96"),
    ;

    public companion object {
        /** Null for a name this build does not know, so a caller keeps its own default. */
        public fun fromWireName(name: String?): StreamingQuality? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * What the platform says the current network costs. The adapter decides it from the OS, never the
 * core: [Metered] is a cellular or otherwise metered path, or one the person has asked to spend
 * less on (Low Data Mode, Data Saver); [Unmetered] is every other usable path.
 */
public enum class NetworkCostClass {
    Unmetered,
    Metered,
}

/**
 * The person's streaming-quality choice: one value for an unmetered network (Wi-Fi, Ethernet) and
 * one for a metered one (cellular). Held per device, not per account — the network belongs to the
 * device, and there is one active account (spec §12.5).
 */
public data class StreamingQualityPreference(
    val unmetered: StreamingQuality,
    val metered: StreamingQuality,
) {
    /**
     * The quality for [network]. A network the adapter has not yet classified is treated as
     * metered: until the platform says otherwise, the person's data is not spent on their behalf.
     */
    public fun qualityFor(network: NetworkCostClass?): StreamingQuality = when (network) {
        NetworkCostClass.Unmetered -> unmetered
        NetworkCostClass.Metered, null -> metered
    }

    /** The stored form both platforms write, so a value means the same everywhere. */
    public fun encoded(): String = "$UNMETERED_KEY=${unmetered.wireName};$METERED_KEY=${metered.wireName}"

    public companion object {
        private const val UNMETERED_KEY = "unmetered"
        private const val METERED_KEY = "metered"

        /**
         * Original on both. Choosing nothing changes nothing: until the person picks a cap, every
         * stream is what it was before the setting existed.
         */
        public val Default: StreamingQualityPreference =
            StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Original)

        /**
         * Reads [encoded]. A missing, unreadable or unknown value falls back to [Default] field by
         * field, so a value written by a later build never fails the read.
         */
        public fun decode(stored: String?): StreamingQualityPreference {
            val fields = stored.orEmpty().split(';').mapNotNull { field ->
                val parts = field.split('=', limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
            return StreamingQualityPreference(
                unmetered = StreamingQuality.fromWireName(fields[UNMETERED_KEY]) ?: Default.unmetered,
                metered = StreamingQuality.fromWireName(fields[METERED_KEY]) ?: Default.metered,
            )
        }
    }
}

/**
 * The adapter's live view of the preference and the network, answering which quality the NEXT
 * resolve uses. Nothing already resolved is touched: a change applies from the next item played
 * and never restarts the current one (spec §12.5). Each setter says whether the answer changed, so
 * an adapter holding a gapless preload resolved under the old answer knows to drop it.
 */
public class StreamingQualityPolicy(preference: StreamingQualityPreference = StreamingQualityPreference.Default) {
    private class State(val preference: StreamingQualityPreference, val network: NetworkCostClass?)

    @Volatile
    private var state = State(preference, null)

    public val preference: StreamingQualityPreference get() = state.preference

    /** Null until the adapter has classified a network. */
    public val network: NetworkCostClass? get() = state.network

    /** The quality the next resolve applies. */
    public val currentQuality: StreamingQuality get() = state.let { it.preference.qualityFor(it.network) }

    /** @return whether [currentQuality] changed. */
    public fun setPreference(preference: StreamingQualityPreference): Boolean = update(State(preference, state.network))

    /** @return whether [currentQuality] changed. */
    public fun setNetwork(network: NetworkCostClass): Boolean = update(State(state.preference, network))

    private fun update(next: State): Boolean {
        val before = currentQuality
        state = next
        return currentQuality != before
    }
}
