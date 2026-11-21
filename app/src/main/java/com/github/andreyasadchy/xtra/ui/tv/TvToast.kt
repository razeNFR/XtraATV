package com.github.andreyasadchy.xtra.ui.tv

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.view.TvUi
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Unified Twitch-style bottom-center OSD pill replacing standard system [Toast]s across the entire TV UI.
 * - Inside the full-screen player (when no dialog window is on top), delegates to [PlayerFragment.showTvPlayerOsd]
 *   so the pill stays centered on the video frame and automatically lifts above the HUD when controls are shown.
 * - On top-level screens or inside Compose/Fragment dialogs, attaches directly to the active top window's decorView.
 * - Falls back to standard [Toast] on mobile or when no foreground window is attached.
 */
object TvToast {

    private const val SHORT_MS = 1850L
    private const val LONG_MS = 3100L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fastOutSlowIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val overlays = WeakHashMap<ViewGroup, OverlayHolder>()
    private val dialogStack = ArrayList<WeakReference<Dialog>>()
    private var resumedActivityRef: WeakReference<Activity>? = null
    private var startedActivityRef: WeakReference<Activity>? = null
    private var initialized = false

    private class OverlayHolder(
        val container: LinearLayout,
        val iconView: ImageView,
        val textView: TextView,
        val hideRunnable: Runnable,
    )

    class Handle(
        private val context: Context?,
        private val text: CharSequence,
        private val duration: Int,
        private val iconRes: Int?,
    ) {
        fun show() {
            val ctx = context ?: currentActivity() ?: return
            this@TvToast.show(ctx, text, duration, iconRes)
        }
    }

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedActivityRef = WeakReference(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                resumedActivityRef = WeakReference(activity)
                startedActivityRef = WeakReference(activity)
                TvBackup.onActivityResumed(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivityRef?.get() === activity) {
                    resumedActivityRef = null
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (startedActivityRef?.get() === activity) {
                    startedActivityRef = null
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivityRef?.get() === activity) resumedActivityRef = null
                if (startedActivityRef?.get() === activity) startedActivityRef = null
            }
        })
    }

    fun onDialogShown(dialog: Dialog) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onDialogShown(dialog) }
            return
        }
        dialogStack.removeAll { it.get() == null || it.get() === dialog }
        dialogStack.add(WeakReference(dialog))
    }

    fun onDialogDismissed(dialog: Dialog) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onDialogDismissed(dialog) }
            return
        }
        dialogStack.removeAll { it.get() == null || it.get() === dialog }
    }

    fun currentActivity(fallbackContext: Context? = null): Activity? {
        resumedActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { return it }
        startedActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { return it }
        return fallbackContext.findActivity()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private fun Context?.findActivity(): Activity? {
        var cur = this
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    fun makeText(context: Context?, resId: Int, duration: Int): Handle {
        val ctx = context ?: currentActivity()
        val text = if (ctx != null) runCatching { ctx.getString(resId) }.getOrDefault("") else ""
        return Handle(ctx, text, duration, iconForResId(resId))
    }

    fun makeText(context: Context?, text: CharSequence?, duration: Int): Handle {
        return Handle(context ?: currentActivity(), text ?: "", duration, null)
    }

    fun makeText(context: Context?, text: CharSequence?, duration: Int, iconRes: Int?): Handle {
        return Handle(context ?: currentActivity(), text ?: "", duration, iconRes)
    }

    fun show(
        context: Context,
        resId: Int,
        duration: Int = Toast.LENGTH_SHORT,
        iconRes: Int? = iconForResId(resId),
    ) {
        val text = runCatching { context.getString(resId) }.getOrDefault("")
        show(context, text, duration, iconRes)
    }

    fun show(
        context: Context,
        text: CharSequence,
        duration: Int = Toast.LENGTH_SHORT,
        iconRes: Int? = null,
    ) {
        if (text.isBlank()) return
        if (!TvUi.isTv(context)) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, text, duration).show()
            } else {
                val appCtx = context.applicationContext
                mainHandler.post { Toast.makeText(appCtx, text, duration).show() }
            }
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val appCtx = context.applicationContext
            val msg = text.toString()
            mainHandler.post { showOnMain(appCtx, msg, duration, iconRes) }
        } else {
            showOnMain(context, text.toString(), duration, iconRes)
        }
    }

    private fun durationToMs(duration: Int): Long =
        if (duration == Toast.LENGTH_LONG) LONG_MS else SHORT_MS

    private fun findTopDialogDecor(activity: Activity?): FrameLayout? {
        for (i in dialogStack.indices.reversed()) {
            val d = dialogStack[i].get()
            if (d == null || !d.isShowing) {
                dialogStack.removeAt(i)
                continue
            }
            val decor = d.window?.decorView as? FrameLayout
            if (decor != null && decor.isAttachedToWindow) {
                return decor
            }
        }
        if (activity is FragmentActivity) {
            findVisibleDialogFragmentDecor(activity.supportFragmentManager.fragments)?.let { return it }
        }
        return null
    }

    private fun findVisibleDialogFragmentDecor(fragments: List<Fragment>): FrameLayout? {
        for (i in fragments.indices.reversed()) {
            val f = fragments[i]
            if (!f.isAdded) continue
            findVisibleDialogFragmentDecor(f.childFragmentManager.fragments)?.let { return it }
            if (f is DialogFragment) {
                val d = f.dialog
                val decor = d?.window?.decorView as? FrameLayout
                if (d != null && d.isShowing && decor != null && decor.isAttachedToWindow) {
                    return decor
                }
            }
        }
        return null
    }

    private fun showOnMain(context: Context, message: String, duration: Int, explicitIcon: Int?) {
        val durationMs = durationToMs(duration)
        val iconRes = explicitIcon ?: inferIconFromText(context, message)
        val activity = context.findActivity()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: currentActivity(context)

        // 1. Top-most open Dialog window (Compose SidePanel, QR modal, DialogFragment...)
        val dialogDecor = findTopDialogDecor(activity)
        if (dialogDecor != null) {
            showOnContainer(dialogDecor, message, iconRes, durationMs)
            return
        }

        // 2. Full-screen Player inside MainActivity (HUD-aware & centered on video area)
        if (activity is MainActivity && !activity.isTvMultiStreamOpen()) {
            val player = activity.playerFragment as? PlayerFragment
            if (player != null && player.isMaximizedPlayer() && !player.isRemoving && !player.isDetached) {
                player.showTvPlayerOsd(message, iconRes, durationMs)
                return
            }
        }

        // 3. Foreground Activity decorView (Home, Grids, Settings, MultiStream, Login...)
        val actDecor = activity?.window?.decorView as? FrameLayout
        if (actDecor != null && actDecor.isAttachedToWindow) {
            showOnContainer(actDecor, message, iconRes, durationMs)
            return
        }

        // 4. Fallback when app is backgrounded or restarting
        runCatching {
            Toast.makeText(context.applicationContext, message, duration).show()
        }
    }

    private fun showOnContainer(root: FrameLayout, message: String, iconRes: Int?, durationMs: Long) {
        val ctx = root.context
        val density = ctx.resources.displayMetrics.density
        val borderColor = if (TvAccent.current != null) {
            ColorUtils.setAlphaComponent(TvAccent.onDark, 0x59)
        } else {
            0x38FFFFFF
        }
        val iconTint = if (TvAccent.current != null) TvAccent.onDark else Color.WHITE

        val holder = overlays[root]?.takeIf { it.container.parent === root } ?: run {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(0xEB18181B.toInt())
                setStroke((1f * density).toInt().coerceAtLeast(1), borderColor)
            }
            val iv = ImageView(ctx).apply {
                val size = (18f * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8f * density).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(iconTint)
                visibility = View.GONE
            }
            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (680f * density).toInt()
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = bg
                isFocusable = false
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = 48f * density
                val padH = (18f * density).toInt()
                val padV = (10f * density).toInt()
                setPadding(padH, padV, padH, padV)
                visibility = View.GONE
                addView(iv)
                addView(tv)
            }
            val bottomMarginPx = runCatching {
                ctx.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
            }.getOrDefault((27f * density).toInt()).coerceAtLeast((24f * density).toInt())
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = bottomMarginPx
                marginStart = (32f * density).toInt()
                marginEnd = (32f * density).toInt()
            }
            root.addView(box, lp)
            lateinit var created: OverlayHolder
            val hideRunnable = Runnable {
                val boxRef = created.container
                boxRef.animate().cancel()
                if (!boxRef.isVisible) {
                    boxRef.visibility = View.GONE
                    return@Runnable
                }
                boxRef.animate()
                    .alpha(0f)
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(160L)
                    .setInterpolator(fastOutSlowIn)
                    .withEndAction { boxRef.visibility = View.GONE }
                    .start()
            }
            created = OverlayHolder(box, iv, tv, hideRunnable)
            overlays[root] = created
            created
        }

        (holder.container.background as? GradientDrawable)?.setStroke(
            (1f * density).toInt().coerceAtLeast(1),
            borderColor,
        )
        holder.iconView.imageTintList = ColorStateList.valueOf(iconTint)
        if (iconRes != null) {
            holder.iconView.setImageResource(iconRes)
            holder.iconView.visibility = View.VISIBLE
        } else {
            holder.iconView.visibility = View.GONE
        }
        holder.textView.text = message

        val osd = holder.container
        osd.removeCallbacks(holder.hideRunnable)
        if (!osd.isVisible) {
            osd.animate().cancel()
            osd.visibility = View.VISIBLE
            osd.alpha = 0f
            osd.scaleX = 0.96f
            osd.scaleY = 0.96f
            osd.translationY = 10f * density
            osd.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(170L)
                .setInterpolator(fastOutSlowIn)
                .withEndAction(null)
                .start()
        } else {
            osd.animate().cancel()
            osd.alpha = 1f
            osd.scaleX = 0.98f
            osd.scaleY = 0.98f
            osd.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(130L)
                .setInterpolator(fastOutSlowIn)
                .withEndAction(null)
                .start()
        }
        osd.postDelayed(holder.hideRunnable, durationMs)
    }

    fun iconForResId(resId: Int): Int? = when (resId) {
        R.string.starting_video_swap,
        R.string.stopping_video_swap,
        R.string.waiting_ads,
        R.string.tv_adblock_exp_switched,
        R.string.tv_adblock_exp_proxies_disabled,
        R.string.tv_adblock_exp_disabled_by_proxy,
        R.string.tv_adblock_exp_chat_auto_locked -> R.drawable.tv_ic_shield

        R.string.starting_proxy,
        R.string.stopping_proxy,
        R.string.proxy_error -> R.drawable.tv_ic_proxy

        R.string.now_following -> R.drawable.tv_ic_heart
        R.string.unfollowed -> R.drawable.tv_ic_heart_outline

        R.string.tv_favorite_added,
        R.string.tv_favorite_removed,
        R.string.tv_favorites_how -> R.drawable.tv_ic_star

        R.string.tv_blacklist_channel_hidden,
        R.string.tv_blacklist_channel_unhidden,
        R.string.tv_blacklist_category_hidden,
        R.string.tv_blacklist_category_unhidden,
        R.string.tv_blacklist_keyword_added -> R.drawable.tv_ic_block

        R.string.timer_canceled -> R.drawable.tv_ic_timer

        R.string.tv_backup_saved,
        R.string.tv_backup_restored,
        R.string.tv_poll_vote_sent,
        R.string.tv_prediction_bet_sent,
        R.string.tv_free_move_saved,
        R.string.connection_restored -> R.drawable.tv_ic_check

        R.string.no_connection,
        R.string.tv_backup_no_network -> R.drawable.tv_ic_wifi

        R.string.tv_looking_for_vod -> R.drawable.tv_ic_video_library
        R.string.tv_refreshed -> R.drawable.tv_ic_refresh
        R.string.tv_no_last_stream -> R.drawable.tv_ic_swap

        R.string.tv_pip_already_shown,
        R.string.tv_multistream_group_saved,
        R.string.tv_multistream_group_removed,
        R.string.tv_multistream_group_offline,
        R.string.tv_multistream_none -> R.drawable.tv_ic_multistream

        R.string.enabled_notifications,
        R.string.tv_no_live_alert -> R.drawable.tv_ic_bell
        R.string.disabled_notifications -> R.drawable.tv_ic_bell_outline

        R.string.tv_accounts_switched_guest,
        R.string.tv_login_logged_out -> R.drawable.tv_ic_person

        R.string.stream_ended,
        R.string.player_error,
        R.string.video_subscribers_only,
        R.string.video_not_found,
        R.string.tv_backup_not_found,
        R.string.tv_backup_save_failed -> R.drawable.tv_ic_info

        else -> null
    }

    private val formattedIconTemplates = intArrayOf(
        R.string.proxy_error, R.drawable.tv_ic_proxy,
        R.string.now_following, R.drawable.tv_ic_heart,
        R.string.unfollowed, R.drawable.tv_ic_heart_outline,
        R.string.tv_favorite_added, R.drawable.tv_ic_star,
        R.string.tv_favorite_removed, R.drawable.tv_ic_star,
        R.string.tv_blacklist_channel_hidden, R.drawable.tv_ic_block,
        R.string.tv_blacklist_channel_unhidden, R.drawable.tv_ic_block,
        R.string.tv_blacklist_category_hidden, R.drawable.tv_ic_block,
        R.string.tv_blacklist_category_unhidden, R.drawable.tv_ic_block,
        R.string.tv_blacklist_keyword_added, R.drawable.tv_ic_block,
        R.string.playback_will_stop, R.drawable.tv_ic_timer,
        R.string.tv_backup_saved, R.drawable.tv_ic_check,
        R.string.tv_multistream_stream_ended, R.drawable.tv_ic_multistream,
        R.string.tv_multistream_origin_offline, R.drawable.tv_ic_multistream,
    )

    private fun inferIconFromText(context: Context, message: String): Int? {
        val marker = "\uFFF0"
        var i = 0
        while (i < formattedIconTemplates.size) {
            val strRes = formattedIconTemplates[i]
            val iconRes = formattedIconTemplates[i + 1]
            val matched = runCatching {
                val tpl = context.getString(strRes, marker)
                if (!tpl.contains(marker)) {
                    message == tpl
                } else {
                    val prefix = tpl.substringBefore(marker)
                    val suffix = tpl.substringAfter(marker, "")
                    (prefix.isNotEmpty() || suffix.isNotEmpty()) &&
                        message.startsWith(prefix) &&
                        message.endsWith(suffix)
                }
            }.getOrDefault(false)
            if (matched) return iconRes
            i += 2
        }
        return null
    }
}
