package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import com.github.andreyasadchy.xtra.util.prefs

/**
 * TV: what the system and the launchers see of a playback, as the usual players of Android show it (Nuvio, YouTube...):
 * the sound is the one of a film and the audio focus is held by the app for as long as something plays, whichever player
 * plays it (the main one, the backup one of the ad blocker while an ad plays, the streams of the multi-stream). The
 * screensavers of some launchers (Monet) only stay away while an app holds it.
 *
 * The players themselves do not ask for it (two players of the app would take it from each other: the main one paused
 * while the backup one played an ad). Another app taking it pauses the playback only with the setting "Pause when other
 * apps play audio".
 */
object TvPlaybackSignals {

    /** Sound of the players: a film, without audio focus of their own. */
    val playerAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    val systemAttributes: android.media.AudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    /** The app was just left (Home): the stream of the player is silent while it is kept a few seconds (MainActivity). */
    @Volatile
    var leaveMuted = false

    /** What plays now, with what pauses it when another app takes the sound. */
    private val owners = LinkedHashMap<Any, () -> Unit>()
    private var held = false
    /** Another app took the sound: not taken back until the playback starts again (play pressed). */
    private var lostToOtherApp = false
    private var request: AudioFocusRequest? = null
    private var appContext: Context? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                held = false
                lostToOtherApp = true
                if (appContext?.prefs()?.getBoolean(com.github.andreyasadchy.xtra.util.C.PLAYER_AUDIO_FOCUS, false) == true) {
                    owners.values.toList().forEach { it() }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                lostToOtherApp = false
            }
        }
    }

    /** [owner] plays: the app holds the audio focus. [onLost] pauses it when another app takes the sound (setting on). */
    fun hold(context: Context, owner: Any, onLost: () -> Unit) {
        if (owners.isEmpty()) {
            lostToOtherApp = false
        }
        owners[owner] = onLost
        if (!held && !lostToOtherApp) {
            requestFocus(context.applicationContext)
        }
    }

    /** [owner] stopped (paused, closed): the audio focus is given back when nothing else of the app plays. */
    fun release(context: Context, owner: Any) {
        if (owners.remove(owner) != null && owners.isEmpty()) {
            abandonFocus(context.applicationContext)
        }
    }

    private fun requestFocus(context: Context) {
        appContext = context
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(systemAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
                .also { request = it }
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus(context: Context) {
        if (!held) {
            return
        }
        held = false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }
}
