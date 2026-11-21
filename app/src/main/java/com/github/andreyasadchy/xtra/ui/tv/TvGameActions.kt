package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TV: long-press action panel for category / game cards across Home, Categories, Search, and Followed Categories.
 * Supports opening the category, following/unfollowing (via Twitch account when logged in, or local follows when logged out),
 * pinning/unpinning the category row on Home, and hiding/unhiding the category.
 */
object TvGameActions {

    private fun findMainActivity(context: Context): MainActivity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is MainActivity) return cur
            cur = cur.baseContext
        }
        return cur as? MainActivity
    }

    fun show(
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
            val pinned = TvHomeRows.isCategoryPinned(activity, game.id)
            val catName = game.name ?: game.slug ?: ""

            val actions = buildList {
                add(
                    TvActionPanel.Action(
                        activity.getString(R.string.tv_open_this_category),
                        R.drawable.tv_ic_gamepad,
                    ) {
                        onOpenCategory()
                    }
                )
                if (following != null) {
                    add(
                        TvActionPanel.Action(
                            activity.getString(if (following) R.string.unfollow else R.string.follow),
                            if (following) R.drawable.tv_ic_heart else R.drawable.tv_ic_heart_outline,
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
                                if (err == null) {
                                    onFollowChanged?.invoke()
                                }
                            }
                        }
                    )
                }
                if (!game.id.isNullOrBlank()) {
                    add(
                        TvActionPanel.Action(
                            activity.getString(if (pinned) R.string.tv_home_unpin_category else R.string.tv_home_pin_category),
                            if (pinned) R.drawable.tv_ic_delete else R.drawable.tv_ic_pin,
                        ) {
                            val nowPinned = TvHomeRows.togglePinCategory(activity, game)
                            TvToast.makeText(
                                activity,
                                activity.getString(
                                    if (nowPinned) R.string.tv_home_category_pinned else R.string.tv_home_category_unpinned,
                                    game.name ?: "",
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    )
                }
                if (!game.id.isNullOrBlank() || !game.slug.isNullOrBlank() || !game.name.isNullOrBlank()) {
                    val catBlocked = TvBlacklist.isCategoryBlocked(game.id, game.slug, game.name)
                    add(
                        TvActionPanel.Action(
                            activity.getString(
                                if (catBlocked) R.string.tv_blacklist_unhide_category else R.string.tv_blacklist_hide_category,
                                catName,
                            ),
                            R.drawable.tv_ic_block,
                        ) {
                            if (catBlocked) {
                                TvBlacklist.unblockCategory(activity, game.id, game.slug, game.name)
                                TvToast.makeText(
                                    activity,
                                    activity.getString(R.string.tv_blacklist_category_unhidden, catName),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                TvBlacklist.blockCategory(activity, game.id, game.slug, game.name, game.boxArtURL)
                                TvToast.makeText(
                                    activity,
                                    activity.getString(R.string.tv_blacklist_category_hidden, catName),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    )
                }
            }
            TvActionPanel.show(activity, game.name ?: "", actions)
        }
    }
}
