package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * TV multi-stream video quality profiles & custom per-count / per-stream caps.
 *
 * Profiles adapt maximum video height and frame rate (30fps vs 60fps hardware decode load)
 * according to the number of active streams (2, 3, or 4) and Focus mode.
 */
object TvMultiStreamQuality {

    const val PROFILE_POTATO = "potato"
    const val PROFILE_LOW = "low"
    const val PROFILE_MEDIUM = "medium"
    const val PROFILE_BALANCED = "balanced"
    const val PROFILE_HIGH = "high"
    const val PROFILE_MAX = "max"
    const val PROFILE_CUSTOM = "custom"

    const val CAP_720P30 = 72030
    const val CAP_MAX = Int.MAX_VALUE

    data class CapSpec(val maxHeight: Int, val maxFrameRate: Int)

    private val PROFILES = listOf(
        PROFILE_POTATO,
        PROFILE_LOW,
        PROFILE_MEDIUM,
        PROFILE_BALANCED,
        PROFILE_HIGH,
        PROFILE_MAX,
        PROFILE_CUSTOM,
    )

    /** Ordered ascending for UI selectors. */
    val CAPS = listOf(160, 360, 480, CAP_720P30, 720, 1080, CAP_MAX)

    /** Ordered descending for auto step-down on buffering stalls. */
    val LADDER_DESC = CAPS.asReversed()

    fun specOf(cap: Int): CapSpec = when (cap) {
        CAP_720P30 -> CapSpec(maxHeight = 720, maxFrameRate = 32)
        CAP_MAX -> CapSpec(maxHeight = Int.MAX_VALUE, maxFrameRate = Int.MAX_VALUE)
        else -> {
            val h = if (cap <= 0 || cap > 1080) Int.MAX_VALUE else cap
            CapSpec(maxHeight = h, maxFrameRate = Int.MAX_VALUE)
        }
    }

    fun profile(context: Context): String {
        val raw = context.prefs().getString(C.TV_MULTISTREAM_QUALITY, PROFILE_HIGH) ?: PROFILE_HIGH
        return if (raw in PROFILES) raw else PROFILE_HIGH
    }

    private fun normalizeCap(raw: Int): Int {
        if (raw == CAP_720P30) return CAP_720P30
        if (raw <= 0 || raw > 1080) return CAP_MAX
        return CAPS.firstOrNull { it == raw } ?: 720
    }

    fun customCap2(context: Context): Int =
        normalizeCap(context.prefs().getInt(C.TV_MULTISTREAM_Q_2, CAP_MAX))

    fun customCap3(context: Context): Int =
        normalizeCap(context.prefs().getInt(C.TV_MULTISTREAM_Q_3, 720))

    fun customCap4(context: Context): Int =
        normalizeCap(context.prefs().getInt(C.TV_MULTISTREAM_Q_4, 720))

    /** Returns the `(2 streams, 3 streams, 4 streams)` cap tokens for [prof]. */
    fun capsForProfile(context: Context, prof: String = profile(context)): Triple<Int, Int, Int> = when (prof) {
        PROFILE_POTATO -> Triple(720, 480, 360)
        PROFILE_LOW -> Triple(1080, 720, 360)
        PROFILE_MEDIUM -> Triple(1080, 720, 480)
        PROFILE_BALANCED -> Triple(CAP_MAX, 720, CAP_720P30)
        PROFILE_MAX -> Triple(CAP_MAX, CAP_MAX, CAP_MAX)
        PROFILE_CUSTOM -> Triple(customCap2(context), customCap3(context), customCap4(context))
        else -> Triple(CAP_MAX, 720, 720) // PROFILE_HIGH (default)
    }

    /** Target cap token for a tile in `MultiStreamFragment`. */
    fun capFor(
        context: Context,
        streamCount: Int,
        isFocusLayout: Boolean,
        isBigTile: Boolean,
    ): Int {
        val prof = profile(context)
        if (isFocusLayout) {
            return when (prof) {
                PROFILE_POTATO -> if (isBigTile) 720 else 360
                PROFILE_LOW -> if (isBigTile) 1080 else 360
                PROFILE_MEDIUM -> if (isBigTile) 1080 else 480
                PROFILE_BALANCED -> if (isBigTile) CAP_MAX else 480
                PROFILE_MAX -> if (isBigTile) CAP_MAX else 720
                PROFILE_CUSTOM -> if (isBigTile) customCap2(context) else customCap4(context)
                else -> if (isBigTile) CAP_MAX else 480 // PROFILE_HIGH
            }
        }
        val (c2, c3, c4) = capsForProfile(context, prof)
        return when {
            streamCount <= 2 -> c2
            streamCount == 3 -> c3
            else -> c4
        }
    }

    fun capLabel(context: Context, cap: Int): String = when {
        cap == CAP_720P30 -> "720p30"
        cap == 720 -> "720p60"
        cap == CAP_MAX || cap > 1080 || cap <= 0 -> context.getString(R.string.tv_multistream_quality_cap_max)
        else -> "${cap}p"
    }

    fun profileLabel(context: Context, prof: String): String = when (prof) {
        PROFILE_POTATO -> context.getString(R.string.tv_multistream_quality_potato)
        PROFILE_LOW -> context.getString(R.string.tv_multistream_quality_low)
        PROFILE_MEDIUM -> context.getString(R.string.tv_multistream_quality_medium)
        PROFILE_BALANCED -> context.getString(R.string.tv_multistream_quality_balanced)
        PROFILE_MAX -> context.getString(R.string.tv_multistream_quality_max)
        PROFILE_CUSTOM -> context.getString(R.string.tv_multistream_quality_custom)
        else -> context.getString(R.string.tv_multistream_quality_high)
    }

    fun summary(context: Context): String {
        val prof = profile(context)
        val (c2, c3, c4) = capsForProfile(context, prof)
        return "${profileLabel(context, prof)} (${capLabel(context, c2)} · ${capLabel(context, c3)} · ${capLabel(context, c4)})"
    }

    fun showPanel(
        context: Context,
        streamTitle: String? = null,
        initialStreamOverride: Int? = null,
        onStreamOverride: ((Int?) -> Unit)? = null,
        onChanged: () -> Unit = {},
    ) {
        val prefs = context.prefs()
        val profileOptions = PROFILES.map { profileLabel(context, it) }
        val capOptions = CAPS.map { capLabel(context, it) }
        val overrideCaps: List<Int?> = listOf(null) + CAPS
        val overrideOptions = listOf(context.getString(R.string.tv_multistream_quality_follow_profile)) + capOptions

        val s2Label = context.getString(R.string.tv_multistream_quality_streams_2)
        val s3Label = context.getString(R.string.tv_multistream_quality_streams_3)
        val s4Label = context.getString(R.string.tv_multistream_quality_streams_4)

        TvDialogs.show(context) {
            val firstFocus = remember { FocusRequester() }
            var currentProfile by remember { mutableStateOf(profile(context)) }
            var c2 by remember { mutableIntStateOf(customCap2(context)) }
            var c3 by remember { mutableIntStateOf(customCap3(context)) }
            var c4 by remember { mutableIntStateOf(customCap4(context)) }
            var streamOverride by remember { mutableStateOf(initialStreamOverride) }
            var resetTick by remember { mutableIntStateOf(0) }

            val activeTriple = if (currentProfile == PROFILE_CUSTOM) {
                Triple(c2, c3, c4)
            } else {
                capsForProfile(context, currentProfile)
            }
            val breakdownText = "$s2Label: ${capLabel(context, activeTriple.first)}  ·  " +
                "$s3Label: ${capLabel(context, activeTriple.second)}  ·  " +
                "$s4Label: ${capLabel(context, activeTriple.third)}"

            SidePanel(
                title = context.getString(R.string.tv_multistream_quality),
                subtitle = context.getString(R.string.tv_multistream_quality_summary),
                width = 500.dp,
                scrollable = true,
            ) {
                Text(
                    text = breakdownText,
                    fontSize = 14.sp,
                    color = TvColors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                key(resetTick) {
                    SelectorRow(
                        label = context.getString(R.string.tv_multistream_quality_profile),
                        options = profileOptions,
                        selected = PROFILES.indexOf(currentProfile).coerceAtLeast(0),
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { idx ->
                        val chosen = PROFILES.getOrElse(idx) { PROFILE_HIGH }
                        currentProfile = chosen
                        prefs.edit { putString(C.TV_MULTISTREAM_QUALITY, chosen) }
                        onChanged()
                    }
                    if (currentProfile == PROFILE_CUSTOM) {
                        SelectorRow(
                            label = s2Label,
                            options = capOptions,
                            selected = CAPS.indexOf(c2).coerceAtLeast(0),
                        ) { idx ->
                            val chosen = CAPS.getOrElse(idx) { CAP_MAX }
                            c2 = chosen
                            prefs.edit { putInt(C.TV_MULTISTREAM_Q_2, chosen) }
                            onChanged()
                        }
                        SelectorRow(
                            label = s3Label,
                            options = capOptions,
                            selected = CAPS.indexOf(c3).coerceAtLeast(0),
                        ) { idx ->
                            val chosen = CAPS.getOrElse(idx) { 720 }
                            c3 = chosen
                            prefs.edit { putInt(C.TV_MULTISTREAM_Q_3, chosen) }
                            onChanged()
                        }
                        SelectorRow(
                            label = s4Label,
                            options = capOptions,
                            selected = CAPS.indexOf(c4).coerceAtLeast(0),
                        ) { idx ->
                            val chosen = CAPS.getOrElse(idx) { 720 }
                            c4 = chosen
                            prefs.edit { putInt(C.TV_MULTISTREAM_Q_4, chosen) }
                            onChanged()
                        }
                    }
                    if (!streamTitle.isNullOrBlank() && onStreamOverride != null) {
                        SelectorRow(
                            label = context.getString(R.string.tv_multistream_quality_this_stream, streamTitle),
                            options = overrideOptions,
                            selected = overrideCaps.indexOf(streamOverride).coerceAtLeast(0),
                        ) { idx ->
                            val chosen = overrideCaps.getOrNull(idx)
                            streamOverride = chosen
                            onStreamOverride(chosen)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ButtonRow(label = context.getString(R.string.tv_reset)) {
                        prefs.edit {
                            putString(C.TV_MULTISTREAM_QUALITY, PROFILE_HIGH)
                            putInt(C.TV_MULTISTREAM_Q_2, CAP_MAX)
                            putInt(C.TV_MULTISTREAM_Q_3, 720)
                            putInt(C.TV_MULTISTREAM_Q_4, 720)
                        }
                        currentProfile = PROFILE_HIGH
                        c2 = CAP_MAX
                        c3 = 720
                        c4 = 720
                        if (streamOverride != null && onStreamOverride != null) {
                            streamOverride = null
                            onStreamOverride(null)
                        }
                        resetTick++
                        onChanged()
                    }
                }
            }
            LaunchedEffect(resetTick) {
                runCatching { firstFocus.requestFocus() }
            }
        }
    }
}
