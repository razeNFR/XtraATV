package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import com.github.andreyasadchy.xtra.R
import kotlin.time.Clock
import kotlin.time.Instant

/** TV: how long a stream is live, short for its badge ("2 h 39", "39 min"), null when not known. */
object TvUptime {

    fun format(context: Context, createdAt: String?): String? {
        val start = createdAt?.let { Instant.parseOrNull(it) }?.takeIf { it.toEpochMilliseconds() > 0 } ?: return null
        val minutes = (Clock.System.now() - start).inWholeMinutes
        if (minutes < 0) {
            return null
        }
        return if (minutes >= 60) {
            context.getString(R.string.tv_uptime_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
        } else {
            context.getString(R.string.tv_uptime_m, minutes.toInt())
        }
    }
}
