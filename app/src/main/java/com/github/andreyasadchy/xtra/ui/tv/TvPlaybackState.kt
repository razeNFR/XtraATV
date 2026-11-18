package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks the currently playing main stream and active TV PiP slots so stream cards across
 * TV Home, stream grids, and the Stream Switcher can display a discreet "Playing" / "PiP" badge.
 */
object TvPlaybackState {

    const val PAYLOAD_PLAYBACK = "payload_playback"

    enum class Kind {
        PLAYING,
        PIP,
    }

    data class Badge(
        val kind: Kind,
        val label: String,
    )

    data class Snapshot(
        val playingLoginLower: String? = null,
        val pipLoginsLower: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun update(playingLogin: String?, pipLogins: List<String?>) {
        val next = Snapshot(
            playingLoginLower = playingLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            pipLoginsLower = pipLogins.mapNotNull { it?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() } },
        )
        if (_state.value != next) {
            _state.value = next
            mainHandler.post {
                listeners.forEach { it() }
            }
        }
    }

    fun attachToRecyclerView(recyclerView: RecyclerView, onChanged: () -> Unit) {
        val callback: () -> Unit = {
            if (recyclerView.isAttachedToWindow && !recyclerView.isComputingLayout) {
                onChanged()
            } else if (recyclerView.isAttachedToWindow) {
                recyclerView.post { if (recyclerView.isAttachedToWindow) onChanged() }
            }
        }
        if (recyclerView.isAttachedToWindow) {
            listeners.add(callback)
        }
        recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                listeners.add(callback)
                onChanged()
            }

            override fun onViewDetachedFromWindow(v: View) {
                listeners.remove(callback)
            }
        })
    }

    fun badgeFor(context: Context, channelLogin: String?, snapshot: Snapshot = _state.value): Badge? {
        val login = channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (login == snapshot.playingLoginLower) {
            return Badge(Kind.PLAYING, context.getString(R.string.tv_badge_playing))
        }
        val pipIndex = snapshot.pipLoginsLower.indexOf(login)
        if (pipIndex >= 0) {
            val label = if (snapshot.pipLoginsLower.size >= 2) "PiP ${pipIndex + 1}" else "PiP"
            return Badge(Kind.PIP, label)
        }
        return null
    }

    fun bindView(badgeView: TextView?, channelLogin: String?) {
        if (badgeView == null) return
        val context = badgeView.context
        val badge = badgeFor(context, channelLogin)
        if (badge == null) {
            badgeView.visibility = View.GONE
            return
        }
        val density = context.resources.displayMetrics.density
        val padH = (6 * density).toInt()
        val padV = (2 * density).toInt()
        badgeView.setPadding(padH, padV, padH, padV)
        badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        badgeView.setTypeface(badgeView.typeface, Typeface.BOLD)
        when (badge.kind) {
            Kind.PLAYING -> {
                badgeView.text = "▶ ${badge.label}"
                badgeView.setTextColor(TvAccent.onAccent)
                badgeView.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 5f * density
                    setColor(TvAccent.color)
                }
            }
            Kind.PIP -> {
                badgeView.text = "• ${badge.label}"
                badgeView.setTextColor(Color.WHITE)
                badgeView.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 5f * density
                    setColor(0xE618181B.toInt())
                    setStroke(
                        (1f * density).toInt().coerceAtLeast(1),
                        (TvAccent.color and 0x00FFFFFF) or 0xCC000000.toInt(),
                    )
                }
            }
        }
        badgeView.visibility = View.VISIBLE
    }
}
