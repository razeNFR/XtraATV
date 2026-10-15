package com.github.andreyasadchy.xtra.ui.player

import android.os.SystemClock
import com.github.andreyasadchy.xtra.BuildConfig

/**
 * Debug builds only: the time each step of the start of a stream takes (Twitch access, playlists, first picture),
 * shown when the first picture comes, to see what makes a stream slow to start.
 */
object TvLoadTimer {

    private var startedAt = 0L
    private var active = false
    private val steps = mutableListOf<Pair<String, Long>>()

    fun start() {
        if (!BuildConfig.DEBUG) {
            return
        }
        startedAt = SystemClock.elapsedRealtime()
        steps.clear()
        active = true
    }

    /** A step is done (only the first time for each one). */
    fun mark(step: String) {
        if (active && steps.none { it.first == step }) {
            steps += step to SystemClock.elapsedRealtime()
        }
    }

    /** The first picture is shown: the times of the steps, null when no start was measured. */
    fun finish(swapped: Boolean): String? {
        if (!active) {
            return null
        }
        active = false
        val end = SystemClock.elapsedRealtime()
        var previous = startedAt
        val lines = steps.map { (step, at) ->
            "$step : ${at - previous} ms".also { previous = at }
        } + "Première image : ${end - previous} ms" + "Total : ${end - startedAt} ms" +
                listOfNotNull(if (swapped) "(changement de flux anti-pub pendant le chargement)" else null)
        return "Chargement du stream\n" + lines.joinToString("\n")
    }
}
