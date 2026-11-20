package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingSource
import coil3.load
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV: banner at the top middle of the screen when a followed streamer goes live, only while the app is open (nothing
 * runs in the background). The followed streams are checked every 2 minutes; the first check is only the reference
 * (no banner for the streams already live when the app opens or comes back after a while).
 *
 * Holding D-pad Up (or pressing Menu / Info) while the banner is visible opens an interactive action panel without
 * stealing focus when ignored.
 */
class TvLiveAlerts(private val activity: MainActivity, private val container: ViewGroup) {

    private var job: Job? = null
    /** Streams live at the last check, by login. */
    private var live: Set<String>? = null
    private var lastCheck = 0L
    /** Last time each streamer was seen live: one who comes back after a short cut is not announced again. */
    private val seen = HashMap<String, Long>()
    private val queue = ArrayDeque<List<Stream>>()
    private var banner: View? = null
    private var currentStreams: List<Stream> = emptyList()
    private var lastAnnouncedStreams: List<Stream> = emptyList()
    private var lastAnnouncedAt = 0L
    private var dismissRunnable: Runnable? = null
    private var showing = false
    private var heldAlertKeyDown = 0
    private var heldAlertOpened = false
    private var replayingShortKey = false
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val holdAlertRunnable = Runnable {
        val key = heldAlertKeyDown
        if (!showing || currentStreams.isEmpty() || key == 0 || heldAlertOpened) return@Runnable
        heldAlertOpened = true
        heldAlertKeyDown = 0
        com.github.andreyasadchy.xtra.ui.player.PlayerFragment.markHeldRemoteKey(key)
        openAlertActions()
    }

    val isBannerShowing: Boolean
        get() = showing && currentStreams.isNotEmpty()

    fun start() {
        if (job != null) {
            return
        }
        job = activity.lifecycleScope.launch {
            while (true) {
                if (activity.prefs().getBoolean(C.TV_LIVE_ALERTS, true)) {
                    check()
                } else {
                    live = null
                }
                delay(CHECK_INTERVAL)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        queue.clear()
        holdHandler.removeCallbacks(holdAlertRunnable)
        dismissRunnable?.let { banner?.removeCallbacks(it) }
        dismissRunnable = null
        banner?.let { container.removeView(it) }
        banner = null
        currentStreams = emptyList()
        showing = false
        heldAlertKeyDown = 0
        heldAlertOpened = false
    }

    private suspend fun check() {
        val streams = loadFollowedStreams() ?: return
        val now = SystemClock.elapsedRealtime()
        val logins = streams.mapNotNull { it.channelLogin?.lowercase() }.toSet()
        val previous = live
        // the app came back after a while: the streams live now are the new reference
        val fresh = previous != null && now - lastCheck <= MAX_GAP
        live = logins
        lastCheck = now
        val watched = activity.currentPlayingStream()?.channelLogin?.lowercase()
        val started = if (fresh) {
            streams.filter { stream ->
                val login = stream.channelLogin?.lowercase() ?: return@filter false
                login !in previous!! && login != watched && seen[login]?.let { now - it < REANNOUNCE_AFTER } != true
            }
        } else emptyList()
        logins.forEach { seen[it] = now }
        if (started.isNotEmpty()) {
            queue.addLast(started)
            showNext()
        }
    }

    private suspend fun loadFollowedStreams(): List<Stream>? {
        val module = (activity.application as XtraApp).xtraModule
        val prefs = activity.prefs()
        return try {
            (FollowedStreamsDataSource(
                userId = activity.tokenPrefs().getString(C.USER_ID, null),
                localChannelFollowsRepository = module.localChannelFollowsRepository,
                gqlHeaders = TwitchApiHelper.getGQLHeaders(activity, true),
                graphQLRepository = module.graphQLRepository,
                helixHeaders = TwitchApiHelper.getHelixHeaders(activity),
                helixRepository = module.helixRepository,
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
            ).load(PagingSource.LoadParams.Refresh(null, 100, false)) as? PagingSource.LoadResult.Page)?.data
        } catch (e: Exception) {
            null
        }
    }

    private fun showNext() {
        if (showing) {
            return
        }
        val streams = queue.removeFirstOrNull() ?: return
        showing = true
        currentStreams = streams
        lastAnnouncedStreams = streams
        lastAnnouncedAt = SystemClock.elapsedRealtime()
        heldAlertKeyDown = 0
        heldAlertOpened = false
        val view = createBanner(streams)
        banner = view
        container.addView(view)
        view.alpha = 0f
        view.translationY = -24 * activity.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(250L).start()
        val seconds = activity.prefs().getString(C.TV_LIVE_ALERTS_DURATION, "5")?.toLongOrNull()?.coerceIn(3L, 10L) ?: 5L
        val dismiss = Runnable { dismissCurrent(showNextAfter = true) }
        dismissRunnable = dismiss
        view.postDelayed(dismiss, seconds * 1000L)
    }

    private fun dismissCurrent(showNextAfter: Boolean) {
        val view = banner ?: return
        holdHandler.removeCallbacks(holdAlertRunnable)
        dismissRunnable?.let { view.removeCallbacks(it) }
        dismissRunnable = null
        banner = null
        currentStreams = emptyList()
        showing = false
        heldAlertKeyDown = 0
        heldAlertOpened = false
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-24 * activity.resources.displayMetrics.density).setDuration(200L).withEndAction {
            container.removeView(view)
            if (showNextAfter) {
                showNext()
            }
        }.start()
    }

    private fun isShortBoundToAlert(keyCode: Int): Boolean {
        val act = TvRemoteKeys.ACTION_LIVE_ALERT
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> TvRemoteKeys.centerAction(activity) == act
            KeyEvent.KEYCODE_DPAD_LEFT -> TvRemoteKeys.leftAction(activity) == act
            KeyEvent.KEYCODE_DPAD_RIGHT -> TvRemoteKeys.rightAction(activity) == act
            KeyEvent.KEYCODE_DPAD_UP -> TvRemoteKeys.upAction(activity) == act
            KeyEvent.KEYCODE_DPAD_DOWN -> TvRemoteKeys.downAction(activity) == act
            else -> TvRemoteKeys.customShortActionForKey(activity, keyCode) == act
        }
    }

    private fun isLongBoundToAlert(keyCode: Int): Boolean {
        val act = TvRemoteKeys.ACTION_LIVE_ALERT
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> TvRemoteKeys.centerLongAction(activity) == act
            KeyEvent.KEYCODE_DPAD_UP -> TvRemoteKeys.upLongAction(activity) == act
            KeyEvent.KEYCODE_DPAD_DOWN -> TvRemoteKeys.downLongAction(activity) == act
            KeyEvent.KEYCODE_BACK -> TvRemoteKeys.backLongAction(activity) == act
            else -> TvRemoteKeys.customLongActionForKey(activity, keyCode) == act
        }
    }

    /**
     * Handles remote keys while the live alert banner is visible without ever stealing focus:
     * - Triggers on whichever D-pad or custom remote key is bound to [TvRemoteKeys.ACTION_LIVE_ALERT]
     *   (plus unbound Menu / Info keys).
     * - Short taps on long-bound keys replay transparently so normal navigation/HUD/Back is unaffected.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (replayingShortKey) {
            return false
        }
        if (!showing || currentStreams.isEmpty()) {
            holdHandler.removeCallbacks(holdAlertRunnable)
            heldAlertKeyDown = 0
            heldAlertOpened = false
            return false
        }
        val code = event.keyCode
        val unboundMenuOrInfo = (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) &&
            TvRemoteKeys.customShortActionForKey(activity, code) == null &&
            TvRemoteKeys.customLongActionForKey(activity, code) == null
        val shortBound = isShortBoundToAlert(code) || unboundMenuOrInfo
        val longBound = isLongBoundToAlert(code)
        if (!shortBound && !longBound) {
            return false
        }
        if (shortBound && !longBound) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                openAlertActions()
            }
            return true
        }
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 550L)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    heldAlertKeyDown = code
                    heldAlertOpened = false
                    holdHandler.removeCallbacks(holdAlertRunnable)
                    holdHandler.postDelayed(holdAlertRunnable, longPressMs)
                    return true
                }
                if (heldAlertKeyDown != code) {
                    // Key was already held before the banner appeared: let normal scrolling continue
                    return false
                }
                if (!heldAlertOpened && (event.repeatCount >= 1 || event.isLongPress)) {
                    holdHandler.removeCallbacks(holdAlertRunnable)
                    heldAlertOpened = true
                    heldAlertKeyDown = 0
                    com.github.andreyasadchy.xtra.ui.player.PlayerFragment.markHeldRemoteKey(code)
                    openAlertActions()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                holdHandler.removeCallbacks(holdAlertRunnable)
                if (heldAlertKeyDown != code && !heldAlertOpened) {
                    return false
                }
                val wasShort = heldAlertKeyDown == code && !heldAlertOpened
                heldAlertKeyDown = 0
                heldAlertOpened = false
                if (wasShort && !event.isCanceled) {
                    val heldMs = if (event.downTime > 0L) event.eventTime - event.downTime else 0L
                    if (heldMs >= longPressMs) {
                        openAlertActions()
                    } else if (shortBound) {
                        openAlertActions()
                    } else {
                        replayingShortKey = true
                        try {
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                        } finally {
                            replayingShortKey = false
                        }
                    }
                }
                return true
            }
            else -> {
                holdHandler.removeCallbacks(holdAlertRunnable)
                heldAlertKeyDown = 0
                heldAlertOpened = false
                return true
            }
        }
    }

    fun openCurrentOrRecent(): Boolean {
        if (showing && currentStreams.isNotEmpty()) {
            openAlertActions()
            return true
        }
        val recent = lastAnnouncedStreams.takeIf {
            it.isNotEmpty() && SystemClock.elapsedRealtime() - lastAnnouncedAt <= 5 * 60_000L
        }
        if (recent != null) {
            showStreamsActionPanel(recent)
            return true
        }
        TvToast.makeText(activity, R.string.tv_no_live_alert, android.widget.Toast.LENGTH_SHORT).show()
        return false
    }

    private fun openAlertActions() {
        val streams = currentStreams
        if (streams.isEmpty()) return
        dismissCurrent(showNextAfter = false)
        showStreamsActionPanel(streams)
    }

    private fun showStreamsActionPanel(streams: List<Stream>) {
        if (streams.size == 1) {
            showActionsForStream(streams.first())
        } else {
            val actions = streams.map { stream ->
                val name = stream.channelName ?: stream.channelLogin ?: ""
                val detail = stream.gameName?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name
                TvActionPanel.Action(detail, R.drawable.tv_ic_live) {
                    showActionsForStream(stream)
                }
            }
            TvActionPanel.show(activity, activity.getString(R.string.tv_live_alerts), actions)
        }
    }

    private fun showActionsForStream(stream: Stream) {
        val name = stream.channelName ?: stream.channelLogin ?: return
        val actions = mutableListOf<TvActionPanel.Action>()
        actions += TvActionPanel.Action(activity.getString(R.string.tv_watch_live), R.drawable.tv_ic_play) {
            if (activity.isMultiStreamOpen()) {
                activity.closeMultiStream()
            }
            activity.startStream(stream)
        }
        val current = activity.currentPlayingStream()
        if (activity.isPlayerMaximized() && current != null && current.channelLogin != stream.channelLogin) {
            actions += TvActionPanel.Action(activity.getString(R.string.tv_pip_open), R.drawable.tv_ic_pip) {
                activity.openTvPip(stream)
            }
            actions += TvActionPanel.Action(activity.getString(R.string.tv_multistream), R.drawable.tv_ic_multistream) {
                activity.startMultiStream(listOf(current, stream), current)
            }
        } else if (activity.isMultiStreamOpen()) {
            actions += TvActionPanel.Action(activity.getString(R.string.tv_multistream_add), R.drawable.tv_ic_add) {
                activity.addStreamToMultiStream(stream)
            }
        }
        actions += TvActionPanel.Action(activity.getString(R.string.tv_open_channel, name), R.drawable.tv_ic_person) {
            if (activity.isMultiStreamOpen()) {
                activity.closeMultiStream()
            } else if (activity.isPlayerMaximized()) {
                (activity.playerFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment)?.leaveForBrowsing()
            }
            activity.openTvChannel(stream, null)
        }
        TvActionPanel.show(activity, name, actions)
    }

    private fun createBanner(streams: List<Stream>): View {
        val context = activity
        val density = context.resources.displayMetrics.density
        val first = streams.first()
        val firstName = first.channelName ?: first.channelLogin ?: ""
        val title: String
        val subtitle: String?
        if (streams.size == 1) {
            title = context.getString(R.string.tv_live_alert_one, firstName)
            subtitle = listOfNotNull(first.gameName?.takeIf { it.isNotBlank() }, first.title?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
        } else {
            // several at the same time: one banner for all of them
            title = context.getString(R.string.tv_live_alert_many, firstName, streams.size - 1)
            subtitle = streams.drop(1).take(4).joinToString(", ") { it.channelName ?: it.channelLogin ?: "" }.ifBlank { null }
        }
        val padV = (12 * density).toInt()
        val padStart = (12 * density).toInt()
        val padEnd = (16 * density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padStart, padV, padEnd, padV)
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 24, 24, 28))
                cornerRadius = 18 * density
                setStroke((1 * density).toInt(), Color.argb(60, 255, 255, 255))
            }
            elevation = 32 * density
            isFocusable = false
            isClickable = false
        }

        val avatarGapEnd = (14 * density).toInt()
        val avatarBlockWidth: Int
        if (streams.size == 1) {
            val size = (48 * density).toInt()
            avatarBlockWidth = size
            row.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                setBackgroundColor(Color.DKGRAY)
                first.channelImage?.let { load(it) }
            }, LinearLayout.LayoutParams(size, size).apply { marginEnd = avatarGapEnd })
        } else {
            val shownAvatars = streams.take(3)
            val ringPad = (2 * density).toInt()
            val outerSize = (42 * density).toInt()
            val stepPx = (26 * density).toInt()
            avatarBlockWidth = outerSize + (shownAvatars.size - 1) * stepPx
            val stack = FrameLayout(context)
            shownAvatars.forEachIndexed { idx, stream ->
                val holder = FrameLayout(context).apply {
                    setPadding(ringPad, ringPad, ringPad, ringPad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.rgb(24, 24, 28))
                    }
                }
                val img = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                    clipToOutline = true
                    setBackgroundColor(Color.DKGRAY)
                    stream.channelImage?.let { load(it) }
                }
                holder.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                stack.addView(holder, FrameLayout.LayoutParams(outerSize, outerSize, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                    marginStart = idx * stepPx
                })
            }
            row.addView(stack, LinearLayout.LayoutParams(avatarBlockWidth, outerSize).apply { marginEnd = avatarGapEnd })
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val overscanH = context.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val maxBannerW = (screenW - overscanH * 2).coerceAtMost((780 * density).toInt())
        val hintText = TvRemoteKeys.liveAlertBannerHint(context)
        val hintGapStart = (14 * density).toInt()
        val hintView = hintText?.let { label ->
            TextView(context).apply {
                text = label
                setTextColor(Color.argb(230, 255, 255, 255))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isSingleLine = true
                maxLines = 1
                maxWidth = (220 * density).toInt()
                ellipsize = TextUtils.TruncateAt.END
                val h = (9 * density).toInt()
                val v = (4 * density).toInt()
                setPadding(h, v, h, v)
                background = GradientDrawable().apply {
                    setColor(Color.argb(40, 255, 255, 255))
                    cornerRadius = 999 * density
                    setStroke((1 * density).toInt(), Color.argb(70, 255, 255, 255))
                }
                measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }
        }
        val hintReserved = if (hintView != null) hintGapStart + hintView.measuredWidth else 0

        val badgeView = TextView(context).apply {
            text = context.getString(R.string.tv_live_alert_badge)
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isSingleLine = true
            maxLines = 1
            includeFontPadding = false
            val h = (6 * density).toInt()
            val v = (2 * density).toInt()
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                setColor(Color.rgb(235, 4, 0))
                cornerRadius = 4 * density
            }
        }
        badgeView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val badgeGapEnd = (8 * density).toInt()

        val textMax = (maxBannerW - padStart - padEnd - avatarBlockWidth - avatarGapEnd - hintReserved)
            .coerceAtLeast((200 * density).toInt())
        val titleMax = (textMax - badgeView.measuredWidth - badgeGapEnd).coerceAtLeast((140 * density).toInt())

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badgeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = badgeGapEnd
            })
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isSingleLine = true
                maxLines = 1
                maxWidth = titleMax
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (subtitle != null) {
            texts.addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.argb(205, 255, 255, 255))
                textSize = 13.5f
                isSingleLine = true
                maxLines = 1
                maxWidth = textMax
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
        }
        row.addView(texts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (hintView != null) {
            row.addView(hintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = hintGapStart
            })
        }
        return FrameLayout(context).apply {
            isFocusable = false
            isClickable = false
            elevation = 32 * density
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            row.minimumWidth = (300 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
            }
        }
    }

    companion object {
        private const val CHECK_INTERVAL = 2 * 60_000L
        /** More than this between two checks (the app was left): no banner, the streams live now are the reference. */
        private const val MAX_GAP = 5 * 60_000L
        private const val REANNOUNCE_AFTER = 15 * 60_000L
    }
}
