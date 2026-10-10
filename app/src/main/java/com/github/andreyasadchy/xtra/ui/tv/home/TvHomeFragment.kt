package com.github.andreyasadchy.xtra.ui.tv.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvTheme
import com.github.andreyasadchy.xtra.ui.tv.home.TvHomeViewModel.Companion.TvHomeViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** TV home written with Compose: rows of cards like the YouTube app, the focus is handled by Compose. */
class TvHomeFragment : Fragment() {

    private val viewModel: TvHomeViewModel by viewModels { TvHomeViewModelFactory }
    private val previewLogin = mutableStateOf<String?>(null)
    private val previewPlayer = mutableStateOf<ExoPlayer?>(null)
    private var previewJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF9147FF.toInt())
        val truncate = context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)
        val actions = TvHomeActions(
            openStream = { stream, list ->
                stopPreview()
                (activity as? MainActivity)?.startStream(stream, list)
            },
            openChannel = { stream ->
                stopPreview()
                findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = stream.channelId,
                        channelLogin = stream.channelLogin,
                        channelName = stream.channelName,
                        channelImage = stream.channelImage,
                        streamId = stream.id
                    )
                )
            },
            openGame = { game ->
                stopPreview()
                findNavController().navigate(
                    if (context.prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                        GamePagerFragmentDirections.actionGlobalGamePagerFragment(gameId = game.id, gameSlug = game.slug, gameName = game.name)
                    } else {
                        GameMediaFragmentDirections.actionGlobalGameMediaFragment(gameId = game.id, gameSlug = game.slug, gameName = game.name)
                    }
                )
            },
            seeAll = { id ->
                stopPreview()
                (activity as? MainActivity)?.openTvSection(id)
            },
            openMenu = {
                stopPreview()
                (activity as? MainActivity)?.openTvMenu()
            },
            onStreamFocused = { schedulePreview(it) },
            formatViewers = { count -> resources.getQuantityString(R.plurals.viewers, count, TwitchApiHelper.formatCount(count, truncate)) },
            refresh = {
                stopPreview()
                viewModel.load(force = true)
            },
        )
        val followedId = if (context.prefs().getBoolean(C.UI_FOLLOW_PAGER, true)) R.id.followPagerFragment else R.id.followMediaFragment
        return ComposeView(context).apply {
            id = R.id.tvHome
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val followed by viewModel.followed.collectAsState()
                val popular by viewModel.popular.collectAsState()
                val games by viewModel.games.collectAsState()
                TvTheme(accent) {
                    TvHomeScreen(
                        followed = followed?.let { TvHomeRow(getString(R.string.tv_followed_live), it, followedId) },
                        popular = popular?.let { TvHomeRow(getString(R.string.popular), it, R.id.rootTopFragment) },
                        games = games?.let { TvHomeRow(getString(R.string.games), it, R.id.rootGamesFragment) },
                        previewLogin = { previewLogin.value },
                        previewPlayer = { previewPlayer.value },
                        actions = actions,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    /** Muted live preview in the focused card after a short delay, like on the other lists. */
    private fun schedulePreview(stream: Stream?) {
        val login = stream?.channelLogin
        if (login == previewLogin.value && login != null) {
            return
        }
        stopPreview()
        val context = context ?: return
        if (login.isNullOrBlank() || !context.prefs().getBoolean(C.UI_TV_PREVIEW, true)) {
            return
        }
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            val url = com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.getPlaylistUrl(context, login) ?: return@launch
            val player = previewPlayer.value ?: ExoPlayer.Builder(context).build().also {
                it.volume = 0f
                it.repeatMode = Player.REPEAT_MODE_OFF
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setMaxVideoSize(854, 480).build()
                previewPlayer.value = it
            }
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            player.playWhenReady = true
            previewLogin.value = login
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewLogin.value = null
        previewPlayer.value?.stop()
    }

    override fun onDestroyView() {
        stopPreview()
        previewPlayer.value?.release()
        previewPlayer.value = null
        super.onDestroyView()
    }
}
