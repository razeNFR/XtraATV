package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ui.LocalChannelFollow
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs

/**
 * TV: follow a channel from the actions of a card, like the follow button of the channel page:
 * with the Twitch account when logged in and the setting says so, else in the local follows.
 */
object TvFollow {

    private fun useAccount(context: Context, channelId: String): Boolean {
        val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        return setting == 0 && !TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank() &&
                context.tokenPrefs().getString(C.USER_ID, null) != channelId
    }

    /** False when the follow button is hidden in the settings. */
    fun isEnabled(context: Context) = (context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0) != 2

    suspend fun isFollowing(context: Context, channelId: String): Boolean? {
        val module = (context.applicationContext as XtraApp).xtraModule
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
        val userId = context.tokenPrefs().getString(C.USER_ID, null)
        val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        val accountFollows = setting == 0 && userId != channelId &&
                (useAccount(context, channelId) || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank())
        if (!accountFollows) {
            return try { module.localChannelFollowsRepository.getById(channelId) != null } catch (e: Exception) { null }
        }
        // the follows of the account: GQL first, the Helix follow list when GQL gives no answer
        if (useAccount(context, channelId)) {
            try {
                val self = module.graphQLRepository.loadQueryFollowingUser(
                    networkLibrary = networkLibrary,
                    headers = TwitchApiHelper.getGQLHeaders(context, true),
                    id = channelId,
                    login = null,
                ).data?.user?.self
                if (self != null) {
                    return self.follower?.followedAt != null
                }
            } catch (e: Exception) {

            }
        }
        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank() && !userId.isNullOrBlank()) {
            try {
                return module.helixRepository.getUserFollows(networkLibrary, helixHeaders, userId, channelId).data.isNotEmpty()
            } catch (e: Exception) {

            }
        }
        return null
    }

    /** Follows or unfollows, returns false when it failed. */
    suspend fun setFollowing(context: Context, channelId: String, channelLogin: String?, channelName: String?, follow: Boolean): Boolean {
        val module = (context.applicationContext as XtraApp).xtraModule
        val prefs = context.prefs()
        return try {
            if (useAccount(context, channelId)) {
                val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                val response = if (follow) {
                    module.graphQLRepository.loadFollowUser(networkLibrary, headers, channelId, !prefs.getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true))
                } else {
                    module.graphQLRepository.loadUnfollowUser(networkLibrary, headers, channelId)
                }
                response.errors.isNullOrEmpty()
            } else {
                if (follow) {
                    module.localChannelFollowsRepository.save(LocalChannelFollow(channelId, channelLogin, channelName))
                    if (prefs.getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true)) {
                        module.notificationsRepository.saveUser(NotificationUser(channelId))
                    }
                } else {
                    module.localChannelFollowsRepository.getById(channelId)?.let { module.localChannelFollowsRepository.delete(it) }
                    module.notificationsRepository.deleteUser(NotificationUser(channelId))
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
