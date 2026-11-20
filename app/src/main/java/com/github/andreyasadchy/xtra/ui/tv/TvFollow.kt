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

    /** Follows or unfollows; returns null on success, or a localized error message when it failed. */
    suspend fun setFollowingWithMessage(context: Context, channelId: String, channelLogin: String?, channelName: String?, follow: Boolean): String? {
        val module = (context.applicationContext as XtraApp).xtraModule
        val prefs = context.prefs()
        return try {
            if (useAccount(context, channelId)) {
                val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                val err = if (follow) {
                    module.graphQLRepository.loadFollowUser(
                        networkLibrary,
                        headers,
                        channelId,
                        !prefs.getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
                    ).extractErrorOrRejection()
                } else {
                    module.graphQLRepository.loadUnfollowUser(networkLibrary, headers, channelId).errors?.firstOrNull()?.message
                }
                when {
                    err.isNullOrBlank() -> null
                    err.startsWith(C.FOLLOW_REJECTED_PREFIX) -> context.getString(com.github.andreyasadchy.xtra.R.string.tv_follow_rejected_verification)
                    else -> err
                }
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
                null
            }
        } catch (e: Exception) {
            context.getString(com.github.andreyasadchy.xtra.R.string.tv_follow_failed)
        }
    }

    /** Follows or unfollows, returns false when it failed. */
    suspend fun setFollowing(context: Context, channelId: String, channelLogin: String?, channelName: String?, follow: Boolean): Boolean =
        setFollowingWithMessage(context, channelId, channelLogin, channelName, follow) == null

    private val _gameChanges = kotlinx.coroutines.flow.MutableStateFlow(0)
    val gameChanges: kotlinx.coroutines.flow.StateFlow<Int> = _gameChanges

    private fun useAccountForGame(context: Context): Boolean {
        val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        return setting == 0 && !TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank()
    }

    private suspend fun resolveGameId(context: Context, game: com.github.andreyasadchy.xtra.model.ui.Game): String? {
        game.id?.takeIf { it.isNotBlank() }?.let { return it }
        if (game.slug.isNullOrBlank() && game.name.isNullOrBlank()) return null
        return try {
            val module = (context.applicationContext as XtraApp).xtraModule
            val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            module.graphQLRepository.loadQueryGame(
                networkLibrary = networkLibrary,
                headers = TwitchApiHelper.getGQLHeaders(context),
                id = null,
                slug = game.slug?.takeIf { it.isNotBlank() },
                name = game.name?.takeIf { game.slug.isNullOrBlank() && it.isNotBlank() },
            ).data?.game?.id?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun isFollowingGame(context: Context, game: com.github.andreyasadchy.xtra.model.ui.Game): Boolean? {
        val gameId = resolveGameId(context, game) ?: return if (game.accountFollow || game.localFollow) true else null
        val module = (context.applicationContext as XtraApp).xtraModule
        val localFollowed = try {
            module.localGameFollowsRepository.getById(gameId) != null
        } catch (_: Exception) {
            game.localFollow
        }
        if (!useAccountForGame(context)) {
            return localFollowed
        }
        if (localFollowed) {
            return true
        }
        return try {
            val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val followedAt = module.graphQLRepository.loadQueryFollowingGame(
                networkLibrary = networkLibrary,
                headers = TwitchApiHelper.getGQLHeaders(context, true),
                id = gameId,
            ).data?.game?.self?.follow?.followedAt
            followedAt != null
        } catch (_: Exception) {
            if (game.accountFollow) true else localFollowed
        }
    }

    /**
     * Follows or unfollows a category: uses the Twitch account when logged in with GQL token and [C.UI_FOLLOW_BUTTON] == 0,
     * otherwise stores/removes a local category follow in [com.github.andreyasadchy.xtra.repository.LocalGameFollowsRepository]
     * (with cached box art on disk) so logged-out and browser-token users can always follow categories too.
     */
    suspend fun setFollowingGameWithMessage(
        context: Context,
        game: com.github.andreyasadchy.xtra.model.ui.Game,
        follow: Boolean,
    ): String? {
        val appContext = context.applicationContext
        val module = (appContext as XtraApp).xtraModule
        val prefs = appContext.prefs()
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gameId = resolveGameId(appContext, game)
            ?: return appContext.getString(com.github.andreyasadchy.xtra.R.string.tv_follow_failed)
        return try {
            if (follow) {
                if (useAccountForGame(appContext)) {
                    val headers = TwitchApiHelper.getGQLHeaders(appContext, true)
                    val err = module.graphQLRepository.loadFollowGame(networkLibrary, headers, gameId)
                        .errors?.firstOrNull()?.message
                    if (!err.isNullOrBlank()) {
                        return if (err.startsWith(C.FOLLOW_REJECTED_PREFIX)) {
                            appContext.getString(com.github.andreyasadchy.xtra.R.string.tv_follow_rejected_verification)
                        } else {
                            err
                        }
                    }
                } else {
                    if (module.localGameFollowsRepository.getById(gameId) == null) {
                        val boxArtDir = java.io.File(appContext.filesDir, "box_art").apply { mkdirs() }
                        val file = java.io.File(boxArtDir, gameId)
                        val remoteUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val candidate = game.boxArtURL?.takeIf { it.startsWith("http") }
                                ?: runCatching {
                                    module.graphQLRepository.loadQueryGameBoxArt(
                                        networkLibrary,
                                        TwitchApiHelper.getGQLHeaders(appContext),
                                        gameId,
                                    ).data?.game?.boxArtURL
                                }.getOrNull()
                            candidate?.takeIf { it.isNotBlank() }?.let { TwitchApiHelper.getGameBoxArt(it) }
                        }
                        val savedLocalFile = if (!remoteUrl.isNullOrBlank()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    module.okHttpClient.value.newCall(
                                        okhttp3.Request.Builder().url(remoteUrl).build()
                                    ).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            java.io.FileOutputStream(file).use { out ->
                                                resp.body.byteStream().use { inp -> inp.copyTo(out) }
                                            }
                                            file.exists() && file.length() > 0L
                                        } else false
                                    }
                                }.getOrDefault(false)
                            }
                        } else false
                        val storedArt = when {
                            savedLocalFile -> file.path
                            !remoteUrl.isNullOrBlank() -> remoteUrl
                            else -> file.path
                        }
                        module.localGameFollowsRepository.save(
                            com.github.andreyasadchy.xtra.model.ui.LocalGameFollow(
                                gameId = gameId,
                                gameSlug = game.slug,
                                gameName = game.name,
                                boxArt = storedArt,
                            )
                        )
                    }
                }
            } else {
                var hadLocal = false
                module.localGameFollowsRepository.getById(gameId)?.let { existing ->
                    module.localGameFollowsRepository.delete(existing)
                    hadLocal = true
                }
                if (useAccountForGame(appContext)) {
                    val headers = TwitchApiHelper.getGQLHeaders(appContext, true)
                    val err = module.graphQLRepository.loadUnfollowGame(networkLibrary, headers, gameId)
                        .errors?.firstOrNull()?.message
                    if (!err.isNullOrBlank() && !hadLocal) {
                        return err
                    }
                }
            }
            _gameChanges.value++
            null
        } catch (_: Exception) {
            appContext.getString(com.github.andreyasadchy.xtra.R.string.tv_follow_failed)
        }
    }
}
