package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks lifetime usage count of every emote (Twitch, 7TV, BTTV, FFZ) actually sent in chat.
 * Persisted in [C.TV_EMOTE_USAGE_COUNTS] inside SharedPreferences so stats survive app restarts
 * and are automatically included in Xtra TV backups.
 */
object TvEmoteUsage {

    private val WHITESPACE = Regex("\\s+")
    private val counts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var loaded = false

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> get() = _revision

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.applicationContext.prefs().getString(C.TV_EMOTE_USAGE_COUNTS, null)
            if (!raw.isNullOrBlank()) {
                runCatching {
                    val obj = JSONObject(raw)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.optInt(k, 0)
                        if (k.isNotBlank() && v > 0) {
                            counts[k] = v
                        }
                    }
                }
            }
            loaded = true
        }
    }

    /** Reloads counts from SharedPreferences (e.g., after restoring a TvBackup). */
    fun reload(context: Context) {
        synchronized(this) {
            counts.clear()
            loaded = false
        }
        ensureLoaded(context)
        _revision.value++
    }

    /**
     * Records emotes from a message that was **successfully sent** to chat.
     * Never call on selection or preview — only after Twitch accepts the sent message.
     */
    fun recordSentMessage(context: Context, message: CharSequence, knownEmoteNames: Set<String>) {
        if (knownEmoteNames.isEmpty()) return
        val trimmed = message.toString().trim()
        if (trimmed.isEmpty()) return
        ensureLoaded(context)

        var changed = false
        for (token in trimmed.split(WHITESPACE)) {
            if (token.isNotEmpty() && token in knownEmoteNames) {
                counts.compute(token) { _, prev -> (prev ?: 0) + 1 }
                changed = true
            }
        }
        if (!changed) return

        synchronized(this) {
            val obj = JSONObject()
            for ((k, v) in counts) {
                if (v > 0) obj.put(k, v)
            }
            context.applicationContext.prefs()
                .edit()
                .putString(C.TV_EMOTE_USAGE_COUNTS, obj.toString())
                .apply()
        }
        _revision.value++
    }

    fun countOf(context: Context, emoteName: String?): Int {
        if (emoteName.isNullOrBlank()) return 0
        ensureLoaded(context)
        return counts[emoteName] ?: 0
    }

    fun sortMostUsed(context: Context, emotes: List<Emote>): List<Emote> {
        ensureLoaded(context)
        if (counts.isEmpty()) return emptyList()
        return emotes
            .filter { !it.name.isNullOrBlank() && (counts[it.name] ?: 0) > 0 }
            .sortedWith(compareByDescending<Emote> { counts[it.name] ?: 0 }.thenBy { it.name })
    }

    fun formatCount(count: Int): String =
        NumberFormat.getIntegerInstance().format(count.coerceAtLeast(0))

    fun providerLabel(emote: Emote): String = when (emote.source) {
        Emote.PERSONAL_STV, Emote.CHANNEL_STV, Emote.GLOBAL_STV -> "7TV"
        Emote.CHANNEL_BTTV, Emote.GLOBAL_BTTV -> "BTTV"
        Emote.CHANNEL_FFZ, Emote.GLOBAL_FFZ -> "FFZ"
        else -> "Twitch"
    }

    fun providerColorArgb(emote: Emote): Long = when (emote.source) {
        Emote.PERSONAL_STV, Emote.CHANNEL_STV, Emote.GLOBAL_STV -> 0xFF29B6F6L
        Emote.CHANNEL_BTTV, Emote.GLOBAL_BTTV -> 0xFFEF4444L
        Emote.CHANNEL_FFZ, Emote.GLOBAL_FFZ -> 0xFFA855F7L
        else -> 0xFF9147FFL
    }
}
