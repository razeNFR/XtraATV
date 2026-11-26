package com.github.andreyasadchy.xtra.ui.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvBlacklist
import com.github.andreyasadchy.xtra.ui.tv.TvFavorites
import com.github.andreyasadchy.xtra.ui.tv.TvFollow
import com.github.andreyasadchy.xtra.ui.tv.TvToast
import com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mobile-only UI helpers: official XtraTV brand header for top-level mobile toolbars,
 * native share/clipboard helpers, and Material 3 long-press action sheets for streams,
 * channels, categories, VODs, and clips. Never modifies Android TV UI.
 */
object MobileUi {

    private const val BRAND_TAG = "mobile_xtratv_brand_header"

    /**
     * Installs the official XtraTV app icon + "XtraTV" wordmark (and optional subtitle)
     * inside a mobile [MaterialToolbar]. Strictly no-op on Android TV.
     */
    fun applyBrandHeader(toolbar: MaterialToolbar, subtitle: String? = null) {
        val context = toolbar.context
        if (TvUi.isTv(context)) return

        val existing = toolbar.findViewWithTag<View>(BRAND_TAG)
        if (existing != null) {
            val subtitleView = existing.findViewWithTag<TextView>("mobile_brand_subtitle")
            if (subtitleView != null) {
                if (!subtitle.isNullOrBlank()) {
                    subtitleView.text = subtitle
                    subtitleView.visibility = View.VISIBLE
                } else {
                    subtitleView.visibility = View.GONE
                }
            }
            return
        }

        val density = context.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            0xFFFFFFFF.toInt(),
        )
        val secondary = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xB3FFFFFF.toInt(),
        )

        val container = LinearLayout(context).apply {
            tag = BRAND_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
                androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }

        val iconSize = (28 * density).toInt()
        val iconRadius = 7f * density
        val iconView = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (10 * density).toInt()
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, iconRadius)
                }
            }
            clipToOutline = true
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brandTitle = TextView(context).apply {
            text = context.getString(R.string.app_name)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(onSurface)
            maxLines = 1
        }
        textColumn.addView(brandTitle)

        val subtitleView = TextView(context).apply {
            tag = "mobile_brand_subtitle"
            text = subtitle.orEmpty()
            textSize = 11.5f
            setTextColor(secondary)
            maxLines = 1
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        textColumn.addView(subtitleView)

        container.addView(iconView)
        container.addView(textColumn)

        toolbar.title = null
        toolbar.addView(container)
    }

    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            TvToast.makeText(context, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, text: String, title: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, title ?: context.getString(R.string.share)))
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String? = null) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        TvToast.makeText(
            context,
            toastMessage ?: context.getString(R.string.mobile_copied_clipboard),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun findMainActivity(context: Context): MainActivity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is MainActivity) return cur
            cur = cur.baseContext
        }
        return cur as? MainActivity
    }

    /** Long-press Material 3 Bottom Sheet for stream and followed-channel cards on mobile. */
    fun showStreamActions(activity: MainActivity, stream: Stream) {
        val channelId = stream.channelId
        val name = stream.channelName ?: stream.channelLogin ?: ""
        activity.lifecycleScope.launch {
            val following = if (channelId != null && TvFollow.isEnabled(activity)) {
                withTimeoutOrNull(1500L) { TvFollow.isFollowing(activity, channelId) }
            } else {
                null
            }
            val actions = buildList {
                stream.channelLogin?.let { login ->
                    val favorite = TvFavorites.isFavorite(login)
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(if (favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                            iconRes = if (favorite) R.drawable.tv_ic_star else R.drawable.tv_ic_star_outline,
                            highlighted = favorite,
                        ) {
                            if (favorite) {
                                TvFavorites.remove(activity, login)
                                TvToast.makeText(activity, activity.getString(R.string.tv_favorite_removed, name), Toast.LENGTH_SHORT).show()
                            } else {
                                TvFavorites.add(activity, login, stream.channelName)
                                TvToast.makeText(activity, activity.getString(R.string.tv_favorite_added, name), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                if (channelId != null && following != null) {
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(if (following) R.string.unfollow else R.string.follow),
                            iconRes = if (following) R.drawable.tv_ic_heart else R.drawable.tv_ic_heart_outline,
                        ) {
                            activity.lifecycleScope.launch {
                                val err = TvFollow.setFollowingWithMessage(activity, channelId, stream.channelLogin, stream.channelName, !following)
                                TvToast.makeText(
                                    activity,
                                    when {
                                        err != null -> err
                                        following -> activity.getString(R.string.unfollowed, name)
                                        else -> activity.getString(R.string.now_following, name)
                                    },
                                    if (err != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    )
                }
                add(
                    MobileBottomSheets.SheetAction(
                        label = activity.getString(R.string.tv_open_channel, name),
                        iconRes = R.drawable.tv_ic_person,
                    ) {
                        activity.openTvChannel(stream, null)
                    }
                )
                add(
                    MobileBottomSheets.SheetAction(
                        label = activity.getString(R.string.tv_channel_videos),
                        iconRes = R.drawable.tv_ic_video_library,
                    ) {
                        activity.openTvChannel(stream, "1")
                    }
                )
                if (!stream.gameName.isNullOrBlank()) {
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(R.string.tv_open_category, stream.gameName),
                            iconRes = R.drawable.tv_ic_gamepad,
                        ) {
                            activity.openTvGame(stream.gameId, stream.gameSlug, stream.gameName)
                        }
                    )
                }
                if (!stream.channelLogin.isNullOrBlank()) {
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(R.string.share),
                            iconRes = R.drawable.tv_ic_share,
                        ) {
                            shareText(activity, "https://www.twitch.tv/${stream.channelLogin}", name)
                        }
                    )
                }
                if (!stream.channelId.isNullOrBlank() || !stream.channelLogin.isNullOrBlank()) {
                    val chBlocked = TvBlacklist.isChannelBlocked(stream.channelId, stream.channelLogin)
                    val label = name.ifBlank { stream.channelLogin.orEmpty() }
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(
                                if (chBlocked) R.string.tv_blacklist_unhide_channel else R.string.tv_blacklist_hide_channel,
                                label,
                            ),
                            iconRes = R.drawable.tv_ic_block,
                            destructive = !chBlocked,
                        ) {
                            if (chBlocked) {
                                TvBlacklist.unblockChannel(activity, stream.channelId, stream.channelLogin)
                                TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_channel_unhidden, label), Toast.LENGTH_SHORT).show()
                            } else {
                                TvBlacklist.blockChannel(activity, stream.channelId, stream.channelLogin, stream.channelName, stream.channelImageURL)
                                TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_channel_hidden, label), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                if (!stream.gameName.isNullOrBlank()) {
                    val gName = stream.gameName!!
                    val catBlocked = TvBlacklist.isCategoryBlocked(stream.gameId, stream.gameSlug, gName)
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(
                                if (catBlocked) R.string.tv_blacklist_unhide_category else R.string.tv_blacklist_hide_category,
                                gName,
                            ),
                            iconRes = R.drawable.tv_ic_block,
                            destructive = !catBlocked,
                        ) {
                            if (catBlocked) {
                                TvBlacklist.unblockCategory(activity, stream.gameId, stream.gameSlug, gName)
                                TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_category_unhidden, gName), Toast.LENGTH_SHORT).show()
                            } else {
                                TvBlacklist.blockCategory(activity, stream.gameId, stream.gameSlug, gName, null)
                                TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_category_hidden, gName), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
            MobileBottomSheets.showActionSheet(
                context = activity,
                title = name.ifBlank { stream.title.orEmpty() },
                subtitle = stream.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name, true) },
                actions = actions,
            )
        }
    }

    /** Long-press Material 3 Bottom Sheet for category / game cards on mobile. */
    fun showGameActions(
        context: Context,
        game: Game,
        onOpenCategory: () -> Unit,
        onFollowChanged: (() -> Unit)? = null,
    ) {
        val activity = findMainActivity(context) ?: return
        activity.lifecycleScope.launch {
            val canFollow = TvFollow.isEnabled(activity) &&
                (!game.id.isNullOrBlank() || !game.slug.isNullOrBlank() || !game.name.isNullOrBlank())
            val following = if (canFollow) {
                withTimeoutOrNull(1500L) { TvFollow.isFollowingGame(activity, game) } ?: (game.accountFollow || game.localFollow)
            } else {
                null
            }
            val catName = game.name ?: game.slug ?: ""
            val actions = buildList {
                add(
                    MobileBottomSheets.SheetAction(
                        label = activity.getString(R.string.tv_open_this_category),
                        iconRes = R.drawable.tv_ic_gamepad,
                    ) { onOpenCategory() }
                )
                if (following != null) {
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(if (following) R.string.unfollow else R.string.follow),
                            iconRes = if (following) R.drawable.tv_ic_heart else R.drawable.tv_ic_heart_outline,
                        ) {
                            activity.lifecycleScope.launch {
                                val err = TvFollow.setFollowingGameWithMessage(activity, game, !following)
                                TvToast.makeText(
                                    activity,
                                    when {
                                        err != null -> err
                                        following -> activity.getString(R.string.unfollowed, catName)
                                        else -> activity.getString(R.string.now_following, catName)
                                    },
                                    if (err != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                                ).show()
                                if (err == null) onFollowChanged?.invoke()
                            }
                        }
                    )
                }
                if (!game.id.isNullOrBlank() || !game.slug.isNullOrBlank() || !game.name.isNullOrBlank()) {
                    val catBlocked = TvBlacklist.isCategoryBlocked(game.id, game.slug, game.name)
                    add(
                        MobileBottomSheets.SheetAction(
                            label = activity.getString(
                                if (catBlocked) R.string.tv_blacklist_unhide_category else R.string.tv_blacklist_hide_category,
                                catName,
                            ),
                            iconRes = R.drawable.tv_ic_block,
                            destructive = !catBlocked,
                        ) {
                            if (catBlocked) {
                                TvBlacklist.unblockCategory(activity, game.id, game.slug, game.name)
                                TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_category_unhidden, catName), Toast.LENGTH_SHORT).show()
                            } else {
                                TvBlacklist.blockCategory(activity, game.id, game.slug, game.name, game.boxArtURL)
                                TvToast.makeText(activity, activity.getString(R.string.tv_blacklist_category_hidden, catName), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
            MobileBottomSheets.showActionSheet(
                context = activity,
                title = catName,
                subtitle = null,
                actions = actions,
            )
        }
    }

    /** Long-press Material 3 Bottom Sheet for VOD cards on mobile. */
    fun showVideoActions(
        activity: MainActivity,
        video: Video,
        positionMs: Long?,
        finished: Boolean,
        isBookmarked: Boolean,
        showChannel: Boolean,
        showGame: Boolean,
        onBookmark: () -> Unit,
        onDownload: () -> Unit,
        onHistoryRemoved: (() -> Unit)? = null,
    ) {
        val name = video.channelName ?: video.channelLogin ?: ""
        val started = positionMs != null && positionMs > 0L
        val actions = buildList {
            if (started && !finished) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_resume), R.drawable.tv_ic_play) {
                        activity.startVideo(video, positionMs)
                    }
                )
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_watch_from_start), R.drawable.tv_ic_replay) {
                        activity.startVideo(video, 0, ignoreSavedPosition = true)
                    }
                )
            } else {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_multistream_watch), R.drawable.tv_ic_play) {
                        activity.startVideo(video, if (finished) 0L else null, ignoreSavedPosition = finished)
                    }
                )
            }
            add(
                MobileBottomSheets.SheetAction(
                    label = activity.getString(if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark),
                    iconRes = if (isBookmarked) R.drawable.tv_ic_star else R.drawable.tv_ic_star_outline,
                ) { onBookmark() }
            )
            add(
                MobileBottomSheets.SheetAction(activity.getString(R.string.download), R.drawable.tv_ic_download) {
                    onDownload()
                }
            )
            if (!video.id.isNullOrBlank()) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.share), R.drawable.tv_ic_share) {
                        shareText(activity, "https://www.twitch.tv/videos/${video.id}", video.title ?: name)
                    }
                )
            }
            val id = video.id
            if (started && !id.isNullOrBlank()) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_remove_from_history), R.drawable.tv_ic_delete, destructive = true) {
                        TvVodHistorySync.removeFromHistory(
                            context = activity,
                            xtraModule = (activity.application as XtraApp).xtraModule,
                            videoId = id,
                            onLocalDone = onHistoryRemoved,
                        )
                    }
                )
            }
            if (showChannel && (!video.channelLogin.isNullOrBlank() || !video.channelId.isNullOrBlank())) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_open_channel, name), R.drawable.tv_ic_person) {
                        activity.openTvChannel(
                            Stream(
                                channelId = video.channelId,
                                channelLogin = video.channelLogin,
                                channelName = video.channelName,
                                channelImageURL = video.channelImageURL,
                            ),
                            null,
                        )
                    }
                )
            }
            if (showGame && !video.gameName.isNullOrBlank()) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_open_category, video.gameName), R.drawable.tv_ic_gamepad) {
                        activity.openTvGame(video.gameId, video.gameSlug, video.gameName)
                    }
                )
            }
        }
        MobileBottomSheets.showActionSheet(
            context = activity,
            title = video.title?.trim()?.takeIf { it.isNotEmpty() } ?: name,
            subtitle = name.takeIf { it.isNotBlank() },
            actions = actions,
        )
    }

    /** Long-press Material 3 Bottom Sheet for Clip cards on mobile. */
    fun showClipActions(
        activity: MainActivity,
        clip: Clip,
        allClips: List<Clip>,
        showChannel: Boolean,
        showGame: Boolean,
        onDownload: () -> Unit,
    ) {
        val channelLabel = clip.channelName ?: clip.channelLogin.orEmpty()
        val actions = buildList {
            add(
                MobileBottomSheets.SheetAction(activity.getString(R.string.tv_multistream_watch), R.drawable.tv_ic_play) {
                    activity.startClip(clip, allClips)
                }
            )
            if (!clip.videoId.isNullOrBlank()) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.watch_video), R.drawable.tv_ic_video_library) {
                        val offsetMs = clip.videoOffsetSeconds?.toLong()?.times(1000L)
                        activity.startVideo(
                            Video(
                                id = clip.videoId,
                                channelId = clip.channelId,
                                channelLogin = clip.channelLogin,
                                channelName = clip.channelName,
                                channelImageURL = clip.channelImageURL,
                                gameId = clip.gameId,
                                gameSlug = clip.gameSlug,
                                gameName = clip.gameName,
                                title = clip.title,
                                createdAt = clip.videoCreatedAt ?: clip.createdAt,
                                animatedPreviewURL = clip.videoAnimatedPreviewURL,
                            ),
                            offset = offsetMs,
                            ignoreSavedPosition = offsetMs != null,
                        )
                    }
                )
            }
            add(
                MobileBottomSheets.SheetAction(activity.getString(R.string.download), R.drawable.tv_ic_download) {
                    onDownload()
                }
            )
            val clipUrl = clip.id?.takeIf { it.isNotBlank() }?.let { id ->
                clip.channelLogin?.takeIf { it.isNotBlank() }?.let { login -> "https://www.twitch.tv/$login/clip/$id" }
                    ?: "https://clips.twitch.tv/$id"
            }
            if (clipUrl != null) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.share), R.drawable.tv_ic_share) {
                        shareText(activity, clipUrl, clip.title ?: channelLabel)
                    }
                )
            }
            if (showChannel && (!clip.channelLogin.isNullOrBlank() || !clip.channelId.isNullOrBlank())) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_open_channel, channelLabel), R.drawable.tv_ic_person) {
                        activity.openTvChannel(
                            Stream(
                                channelId = clip.channelId,
                                channelLogin = clip.channelLogin,
                                channelName = clip.channelName,
                                channelImageURL = clip.channelImageURL,
                            ),
                            null,
                        )
                    }
                )
            }
            if (showGame && !clip.gameName.isNullOrBlank()) {
                add(
                    MobileBottomSheets.SheetAction(activity.getString(R.string.tv_open_category, clip.gameName), R.drawable.tv_ic_gamepad) {
                        activity.openTvGame(clip.gameId, clip.gameSlug, clip.gameName)
                    }
                )
            }
        }
        MobileBottomSheets.showActionSheet(
            context = activity,
            title = clip.title?.trim()?.takeIf { it.isNotEmpty() } ?: channelLabel,
            subtitle = channelLabel.takeIf { it.isNotBlank() },
            actions = actions,
        )
    }
}
