package com.github.andreyasadchy.xtra.ui.tv

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
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
    private var showing = false

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
        banner?.let { container.removeView(it) }
        banner = null
        showing = false
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
        val view = createBanner(streams)
        banner = view
        container.addView(view)
        view.alpha = 0f
        view.translationY = -24 * activity.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(250L).start()
        val seconds = activity.prefs().getString(C.TV_LIVE_ALERTS_DURATION, "5")?.toLongOrNull()?.coerceIn(3L, 10L) ?: 5L
        view.postDelayed({
            view.animate().alpha(0f).translationY(-24 * activity.resources.displayMetrics.density).setDuration(250L).withEndAction {
                container.removeView(view)
                if (banner === view) {
                    banner = null
                }
                showing = false
                showNext()
            }.start()
        }, seconds * 1000L)
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
            subtitle = streams.drop(1).take(4).joinToString(", ") { it.channelName ?: it.channelLogin ?: "" }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padding = (12 * density).toInt()
            setPadding(padding, padding, (20 * density).toInt(), padding)
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 24, 24, 28))
                cornerRadius = 18 * density
                setStroke((1 * density).toInt(), Color.argb(60, 255, 255, 255))
            }
            elevation = 32 * density
            isFocusable = false
            isClickable = false
        }
        val size = (48 * density).toInt()
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
        }, LinearLayout.LayoutParams(size, size).apply { marginEnd = (14 * density).toInt() })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.tv_live_alert_badge)
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val h = (6 * density).toInt()
                setPadding(h, (1 * density).toInt(), h, (1 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.rgb(235, 4, 0))
                    cornerRadius = 4 * density
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * density).toInt() })
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        if (subtitle != null) {
            texts.addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
        }
        row.addView(texts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.5f).toInt()
        return FrameLayout(context).apply {
            isFocusable = false
            isClickable = false
            elevation = 32 * density
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            row.minimumWidth = (280 * density).toInt()
            texts.layoutParams = (texts.layoutParams as LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
            }
            // never wider than half of the screen, long titles are cut
            row.post {
                if (row.width > maxWidth) {
                    row.layoutParams = (row.layoutParams as FrameLayout.LayoutParams).apply { width = maxWidth }
                }
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
