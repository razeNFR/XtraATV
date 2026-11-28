package com.github.andreyasadchy.xtra.ui.player

import coil3.imageLoader
import coil3.asDrawable
import coil3.request.target
import coil3.request.transformations
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.trackPipAnimationHintView
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TimeBar
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel.Companion.PlayerViewModelFactory
import com.github.andreyasadchy.xtra.ui.tv.FooterActionChip
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.isKeyboardShown
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(UnstableApi::class)
abstract class PlayerFragment : BaseNetworkFragment(), RadioButtonDialogFragment.OnSortOptionChanged, IntegrityDialog.Listener {

    private var _binding: FragmentPlayerBinding? = null
    protected val binding get() = _binding!!
    protected val viewModel: PlayerViewModel by viewModels { PlayerViewModelFactory }
    protected var chatFragment: ChatFragment? = null
    protected open val playbackService: BasePlaybackService? = null
    protected var started = false

    private var isPortrait = false
    // read from the app, not from the screen: the activity asks a new player screen (a switch) before it is attached
    protected val isTv by lazy { com.github.andreyasadchy.xtra.XtraApp.INSTANCE.resources.getBoolean(R.bool.isTv) }
    /** Controls stay longer on TV, moving between them with a remote takes more time than tapping. */
    protected val controllerHideDelay by lazy { if (isTv) 6000L else 3000L }
    protected var isMaximized = true
    private var isChatOpen = true
    private var isKeyboardShown = false
    private var resizeMode = 0
    private var chatWidthLandscape = 0

    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var isTap = false
    private var tapEventTime = 0L
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var statusBarSwipe = false
    private var chatStatusBarSwipe = false
    private var isAnimating = false
    private var moveAnimation: ViewPropertyAnimator? = null
    protected var useController = true
    protected var controllerAutoHide = true
    private var controllerHideOnTouch = true
    private val controllerHideAction = Runnable { if (view != null) hideController() }

    /**
     * TV: the HUD is shown for a moment after a switch of stream, without the focus: left / right still switch streams,
     * OK (or up / down without their shortcuts) gives it the focus as usual.
     */
    private var hudPeek = false
    /**
     * TV: the HUD was opened by the user (OK, up / down): left / right move in the timeline. Otherwise (hidden, info of a
     * switch, HUD left on screen by a quick series of switches...) left / right always change the streamer.
     */
    private var hudOpenedByUser = false
        set(value) {
            field = value
            // TV: the timeline only takes the focus in a HUD opened on purpose, it never catches left / right of a switch
            if (isTv) {
                _binding?.let { binding ->
                    val progressBar = binding.playerControls.progressBar
                    if (!value && progressBar.isFocused) {
                        // the timeline had the focus (HUD hidden by back or by itself): the player gets it first, else
                        // nothing would have it any more and OK / up / down could not show the HUD again
                        binding.dragView.requestFocus()
                    }
                    progressBar.isFocusable = value
                }
            }
        }

    private var hlsMutedSegments: List<Pair<Long, Long>> = emptyList()

    private fun combineMutedSegments(
        gqlSegments: List<Pair<Long, Long>>,
        manifestSegments: List<Pair<Long, Long>>,
    ): List<Pair<Long, Long>> {
        if (gqlSegments.isEmpty()) return manifestSegments
        if (manifestSegments.isEmpty()) return gqlSegments
        val combined = ArrayList<Pair<Long, Long>>(gqlSegments.size + manifestSegments.size)
        combined.addAll(gqlSegments)
        combined.addAll(manifestSegments)
        combined.sortBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>(combined.size)
        for ((startMs, endMs) in combined) {
            val last = merged.lastOrNull()
            if (last != null && startMs <= last.second + 2_000L) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, endMs)
            } else {
                merged.add(startMs to endMs)
            }
        }
        return merged
    }

    private var vodAudioToastShownSessionId: Long = -1L

    protected fun applyVodAudioBarRanges() {
        val bar = _binding?.playerControls?.progressBar ?: return
        val isVodLike = playbackService?.type == BasePlaybackService.VIDEO || isExperimentalVodActive()
        if (!isVodLike) {
            bar.setRecoveredSegments(emptyList())
            bar.setMutedSegments(emptyList())
            return
        }
        val recState = com.github.andreyasadchy.xtra.ui.tv.TvVodAudioRecovery.activeState.value
        val recovered = recState?.recoveredRanges.orEmpty()
        val rawMuted = if (playbackService?.type == BasePlaybackService.VIDEO) {
            val fromRecovery = recState?.mutedRanges.orEmpty()
            val gqlMuted = viewModel.mutedSegments.value
            if (recovered.isNotEmpty()) {
                // Exclude GraphQL ranges that were successfully recovered (allow 12s boundary margin for 10s HLS rounding)
                val unrecoveredGql = gqlMuted.filter { (gs, ge) ->
                    recovered.none { (rs, re) -> gs >= rs - 12_000L && ge <= re + 12_000L }
                }
                combineMutedSegments(unrecoveredGql, fromRecovery)
            } else {
                combineMutedSegments(gqlMuted, if (fromRecovery.isNotEmpty()) fromRecovery else hlsMutedSegments)
            }
        } else {
            recState?.mutedRanges.orEmpty()
        }
        bar.setRecoveredSegments(recovered)
        bar.setMutedSegments(rawMuted)
    }

    protected fun updateHlsMutedSegments(segments: List<Pair<Long, Long>>) {
        hlsMutedSegments = segments
        applyVodAudioBarRanges()
    }

    /** A live stream: from the service, or from the screen itself while its service connects (a switch just made it). */
    private fun isStreamScreen(): Boolean {
        return playbackService?.type == BasePlaybackService.STREAM || (playbackService == null && arguments?.getBoolean(KEY_STREAM) == true)
    }

    /** A VOD (classic or live-rewind DVR): from the service, or from the screen arguments before its service connects. */
    private fun isVodScreen(): Boolean {
        return playbackService?.type == BasePlaybackService.VIDEO || (playbackService == null && arguments?.getBoolean(KEY_VIDEO) == true)
    }

    /** Live-rewind DVR VOD opened by rewinding a live broadcast timeline (never a classic standalone VOD). */
    private fun isDvrScreen(): Boolean {
        return getTvDvrStream() != null || (playbackService == null && arguments?.getBoolean(KEY_DVR) == true)
    }

    /** Live broadcast screen (excluding DVR/VOD). */
    fun isLiveStreamScreen(): Boolean = isStreamScreen()

    /** Live broadcast or live-rewind DVR broadcast (where channel +/- and favorites stay enabled). */
    fun isLiveOrDvrScreen(): Boolean = isStreamScreen() || isDvrScreen()

    /** True only when watching a live broadcast at its live edge (false when rewound into DVR VOD or experimental/RAM timeshift). */
    private fun isAtLiveEdgeForStreamSwitch(): Boolean {
        if (!isStreamScreen() || isDvrScreen()) return false
        val exo = playbackService as? ExoPlayerService
        if (exo != null) {
            if (exo.experimentalVodActive && exo.experimentalVodUserRewound) return false
            if (!exo.experimentalVodActive && exo.getLiveTimeShiftBehindMs() > 15_000L) return false
        }
        return true
    }

    private fun effectiveDpadAction(action: String, pipOpen: Boolean): String {
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        if (!pipOpen && Keys.isPipOnlyAction(action)) {
            return Keys.ACTION_DEFAULT
        }
        if (action == Keys.ACTION_LIVE_ALERT && (activity as? MainActivity)?.isTvLiveAlertShowing() != true) {
            return Keys.ACTION_DEFAULT
        }
        return action
    }

    /**
     * TV: returns -1 or +1 when [keyCode] is configured to switch to the previous/next streamer while controls are
     * hidden on a live broadcast at the live edge (also before the new player view is inflated during quick switches).
     */
    fun handlesDpadSwitchDirection(keyCode: Int): Int? {
        if (!isTv || !isMaximized || !isAtLiveEdgeForStreamSwitch() || hudOpenedByUser) {
            return null
        }
        if (_binding != null && binding.chatLayout.hasFocus()) {
            return null
        }
        val appCtx = com.github.andreyasadchy.xtra.XtraApp.INSTANCE
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        val pipOpen = (activity as? MainActivity)?.isTvPipOpen() == true
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val shortAct = effectiveDpadAction(Keys.upAction(appCtx), pipOpen)
            val longAct = effectiveDpadAction(Keys.upLongAction(appCtx), pipOpen)
            if (longAct != Keys.ACTION_DEFAULT && longAct != shortAct) {
                // let onPlayerKey distinguish short vs long press on Up
                return null
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val shortAct = effectiveDpadAction(Keys.downAction(appCtx), pipOpen)
            val longAct = effectiveDpadAction(Keys.downLongAction(appCtx), pipOpen)
            if (longAct != shortAct) {
                // let onPlayerKey distinguish short vs long press on Down
                return null
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            val shortAct = effectiveDpadAction(Keys.centerAction(appCtx), pipOpen)
            val longAct = effectiveDpadAction(Keys.centerLongAction(appCtx), pipOpen)
            if (longAct != shortAct) {
                // let onPlayerKey distinguish short vs long press on OK
                return null
            }
        }
        return when (effectiveDpadAction(Keys.dpadForKeyCode(appCtx, keyCode), pipOpen)) {
            Keys.ACTION_SWITCH_PREV -> -1
            Keys.ACTION_SWITCH_NEXT -> 1
            else -> null
        }
    }

    /** The player screen is in full screen (also before its view is made). */
    fun isMaximizedScreen() = isMaximized && !isRemoving
    private val hudPeekHideAction = Runnable {
        if (view != null && hudPeek) {
            hudPeek = false
            TvSwitchOverlay.hide(activity)
            if (!hudOpenedByUser) {
                // hidden even while it was still fading in or out (quick switches), it never stays on screen
                hideController(force = true)
            }
        }
    }

    /** True once the current live stream has rendered its first video frame (starts HUD/overlay auto-hide countdown). */
    protected var streamFirstFrameRendered = false

    private fun isWaitingForStreamStart(): Boolean =
        isTv && isStreamScreen() && !streamFirstFrameRendered && !tvOfflineScreenActive && loadingPicture != null

    private fun peekHud(immediate: Boolean = false) {
        if (binding.playerControls.root.hasFocus()) {
            return
        }
        hudPeek = true
        // only the info of the stream (title, channel, category, viewers, quality...), no buttons nor timeline
        setHudInfoOnly(true)
        if (immediate) {
            restoreTvOledProtection()
            controllerAnimation?.cancel()
            controllerIsAnimating = false
            controllerHiding = false
            binding.playerControls.root.removeCallbacks(controllerHideAction)
            onTvControllerVisibility(true)
            binding.playerControls.root.alpha = 1f
            binding.playerControls.root.visibility = View.VISIBLE
            updateProgress()
        } else {
            showController()
        }
        binding.root.removeCallbacks(hudPeekHideAction)
        if (isWaitingForStreamStart()) {
            binding.playerControls.root.removeCallbacks(controllerHideAction)
            // Keep HUD & left/right overlays visible until first frame renders (12 s safety fallback)
            binding.root.postDelayed(hudPeekHideAction, 12_000L)
        } else {
            binding.root.postDelayed(hudPeekHideAction, 2500L)
            TvSwitchOverlay.startAutoHideTimer(activity, 2500L)
        }
    }

    /** TV: the buttons and the timeline of the HUD hidden (the info of the stream after a switch) or shown again. */
    private fun setHudInfoOnly(infoOnly: Boolean) {
        val controls = _binding?.playerControls?.root ?: return
        val visibility = if (infoOnly) View.GONE else View.VISIBLE
        controls.findViewById<View>(R.id.bottomLayout)?.visibility = visibility
        (controls.findViewById<View>(R.id.bottomLeftLayout)?.parent as? View)?.visibility = visibility
    }

    /** The HUD gets the focus (or goes): it is no longer a short look. */
    private fun endHudPeek() {
        if (hudPeek) {
            TvSwitchOverlay.hide(activity)
        }
        hudPeek = false
        _binding?.root?.removeCallbacks(hudPeekHideAction)
    }
    private var controllerIsAnimating = false
    private var controllerHiding = false
    private var controllerAnimation: ViewPropertyAnimator? = null
    private var backgroundColor: Int? = null
    private var backgroundVisible = false

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isTv && cancelTvScrub()) {
                return
            }
            if (isTv && cancelTvStreamEndCountdown()) {
                return
            }
            if (_binding != null && hudPeek && areControlsShown()) {
                // the info of the stream shown after a switch: back only hides it (and the streamers left and right)
                TvSwitchOverlay.hide(activity, force = true)
                hideController()
            } else if (_binding != null && controllerAutoHide && controllerHideOnTouch && areControlsShown() && (isTv || binding.playerControls.root.hasFocus())) {
                hideController()
            } else if (_binding != null && binding.chatLayout.hasFocus()) {
                binding.dragView.requestFocus()
            } else if ((activity as? MainActivity)?.isTvPipOpen() == true) {
                // TV: once the HUD is hidden, back closes the latest small window first and keeps the main stream
                (activity as? MainActivity)?.closeLatestTvPip()
            } else if (isTv) {
                // TV: back stops the stream, or keeps it in the mini player or as audio only (setting)
                leaveForBrowsing()
            } else {
                minimize()
            }
        }
    }

    private val minimizedBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            close()
            (activity as? MainActivity)?.closePlayer()
        }
    }

    open fun getCurrentPosition(): Long? = null
    /** Live: the time of Twitch of the picture shown (the delay of the chat follows it), null when not known. */
    open fun getLivePictureTime(): Long? = null

    fun getStreamWatchMetadata(): com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.StreamWatchMetadata? {
        val svc = playbackService ?: return null
        if (svc.type != BasePlaybackService.STREAM) return null
        return com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.StreamWatchMetadata(
            streamId = svc.streamId,
            gameId = svc.gameId,
            gameName = svc.gameName,
            needsPresencePing = (svc as? ExoPlayerService)?.isIndirectStreamPlayback() == true,
        )
    }

    open fun getCurrentSpeed(): Float? = null
    open fun getCurrentVolume(): Float? = null

    /** TV: the sound of the small window changed, the volume of this stream follows it. */
    open fun refreshVolume() {}
    open fun getTotalDuration(): Long? = null
    open fun playPause() {}
    open fun rewind() {}
    open fun fastForward() {}
    open fun seek(position: Long) {}
    open fun seekToLivePosition() {}
    open fun setPlaybackSpeed(speed: Float) {}
    open fun changeVolume(volume: Float) {}
    open fun updateProgress() {}
    open fun restartPlayer() {}
    open fun toggleAudioCompressor() {}
    open fun setSubtitlesButton() {}
    open fun toggleSubtitles(enabled: Boolean) {}
    open fun getUnavailableQualities(): List<VideoQuality> = emptyList()
    open fun showPlaylistTags(mediaPlaylist: Boolean) {}
    open fun changeQuality(selectedQuality: VideoQuality?) {}
    open fun startAudioOnly() {}
    open fun close(deleteStates: Boolean = true) {}
    open fun retry(item: String) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        if (arguments?.getBoolean(KEY_OFFLINE) == true) {
            enableNetworkCheck = false
        }
        super.onCreate(savedInstanceState)
        isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showLoadingPicture()
        if (isTv) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        kotlinx.coroutines.delay(1000L)
                        checkTvDvrCaughtUp()
                    }
                }
            }
        }
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.integrity.collect {
                        (requireActivity() as? MainActivity)?.getNewIntegrityToken(it, childFragmentManager)
                    }
                }
            }
            val ignoreCutouts = requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
            val cornerPadding = requireContext().prefs().getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = if (!isPortrait && ignoreCutouts) {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                } else {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
                }
                if (isPortrait) {
                    slidingLayout.updatePadding(left = 0, top = insets.top, right = 0)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cornerPadding) {
                        val rootWindowInsets = view.rootView.rootWindowInsets
                        val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                        val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                        val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                        val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                        val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                        val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                        if (ignoreCutouts) {
                            slidingLayout.updatePadding(left = leftRadius, top = 0, right = rightRadius)
                        } else {
                            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                            slidingLayout.updatePadding(left = max(cutoutInsets.left, leftRadius), top = 0, right = max(cutoutInsets.right, rightRadius))
                        }
                    } else {
                        if (ignoreCutouts) {
                            slidingLayout.updatePadding(left = 0, top = 0, right = 0)
                        } else {
                            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                            slidingLayout.updatePadding(left = cutoutInsets.left, top = 0, right = cutoutInsets.right)
                        }
                    }
                }
                chatLayout.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        requireActivity().trackPipAnimationHintView(playerLayout)
                    }
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false)) {
                view.keepScreenOn = true
            }
            if (isMaximized) {
                enableBackground()
            } else {
                disableBackground()
            }
            isChatOpen = requireContext().prefs().getBoolean(C.KEY_CHAT_OPENED, true) && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
            chatWidthLandscape = requireContext().prefs().getInt(C.LANDSCAPE_CHAT_WIDTH, 0)
            resizeMode = requireContext().prefs().getInt(C.ASPECT_RATIO_LANDSCAPE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
            aspectRatioFrameLayout.setAspectRatio(16f / 9f)
            initLayout()
            changePlayerMode()
            dragView.setOnKeyListener { _, keyCode, event ->
                onPlayerKey(keyCode, event)
            }
            dragView.setOnFocusChangeListener { _, hasFocus ->
                minimizedBackPressedCallback.isEnabled = hasFocus && !isMaximized
            }
            if (isMaximized) {
                (activity as? MainActivity)?.setMainContentFocusable(false)
                dragView.post {
                    if (view != null && isMaximized) {
                        binding.dragView.requestFocus()
                    }
                }
            }
            val viewConfiguration = ViewConfiguration.get(requireContext())
            val touchSlop = viewConfiguration.scaledTouchSlop
            val touchSlopRange = -touchSlop.toFloat()..touchSlop.toFloat()
            val longPressTimeout = ViewConfiguration.getLongPressTimeout()
            val moveFreely = requireContext().prefs().getBoolean(C.PLAYER_MOVE_FREELY, false)
            val doubleTap = requireContext().prefs().getBoolean(C.PLAYER_DOUBLE_TAP, true) && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
            val controllerTapDetector = GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        return if (!doubleTap || isPortrait) {
                            val visible = playerControls.root.isVisible
                            if (visible) {
                                if (controllerHideOnTouch) {
                                    hideController()
                                }
                            } else {
                                showController()
                            }
                            if (!visible) {
                                updateProgress()
                            }
                            true
                        } else {
                            false
                        }
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        return if (doubleTap && !isPortrait) {
                            val visible = playerControls.root.isVisible
                            if (visible) {
                                if (controllerHideOnTouch) {
                                    hideController()
                                }
                            } else {
                                showController()
                            }
                            if (!visible) {
                                updateProgress()
                            }
                            true
                        } else {
                            false
                        }
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        return if (doubleTap && !isPortrait && isMaximized) {
                            if (chatLayout.isVisible) {
                                hideChat()
                            } else {
                                showChat()
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
            )

            fun downAction(event: MotionEvent) {
                moveAnimation?.cancel()
                isTap = true
                tapEventTime = event.eventTime
                if (isMaximized) {
                    if (playerControls.root.isVisible) {
                        playerControls.root.dispatchTouchEvent(event)
                    } else {
                        controllerTapDetector.onTouchEvent(event)
                    }
                } else {
                    velocityTracker?.clear()
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain()
                    }
                    velocityTracker?.addMovement(
                        MotionEvent.obtain(
                            event.downTime,
                            event.eventTime,
                            event.action,
                            slidingLayout.translationX,
                            slidingLayout.translationY,
                            event.metaState
                        )
                    )
                    startTranslationX = slidingLayout.translationX
                    startTranslationY = slidingLayout.translationY
                }
            }

            fun upAction(event: MotionEvent) {
                if (isMaximized) {
                    if (playerControls.progressBar.isPressed) {
                        playerControls.root.dispatchTouchEvent(event)
                    } else {
                        if (slidingLayout.translationY in touchSlopRange) {
                            if (playerControls.root.isVisible) {
                                playerControls.root.dispatchTouchEvent(event)
                            } else {
                                controllerTapDetector.onTouchEvent(event)
                            }
                        }
                        val minimizeThreshold = slidingLayout.height / 5
                        if (slidingLayout.translationY < minimizeThreshold) {
                            moveAnimation = slidingLayout.animate().apply {
                                translationX(0f)
                                translationY(0f)
                                setDuration(250L)
                                setListener(
                                    object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            setListener(null)
                                            if (this@PlayerFragment.view != null && slidingLayout.translationY < touchSlop) {
                                                enableBackground()
                                            }
                                        }
                                    }
                                )
                                start()
                            }
                        } else {
                            minimize()
                        }
                    }
                } else {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    when {
                        xVelocity > 1500 -> {
                            isAnimating = true
                            slidingLayout.animate().apply {
                                translationX(slidingLayout.translationX + (slidingLayout.width * slidingLayout.scaleX))
                                setDuration(250L)
                                start()
                            }
                            close()
                            (activity as? MainActivity)?.closePlayer()
                        }
                        xVelocity < -1500 -> {
                            isAnimating = true
                            slidingLayout.animate().apply {
                                translationX(slidingLayout.translationX - (slidingLayout.width * slidingLayout.scaleX))
                                setDuration(250L)
                                start()
                            }
                            close()
                            (activity as? MainActivity)?.closePlayer()
                        }
                        else -> {
                            if (isTap && (event.eventTime - tapEventTime) < longPressTimeout) {
                                maximize()
                            } else {
                                if (moveFreely) {
                                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                    val scaledXDiff = (slidingLayout.width * (1f - slidingLayout.scaleX)) / 2
                                    val scaledYDiff = (slidingLayout.height * (1f - slidingLayout.scaleY)) / 2
                                    val minX = 0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + (insets?.left ?: 0)
                                    val minY = 0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + (insets?.top ?: 0)
                                    val maxX = 0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + slidingLayout.width - (playerLayout.width * slidingLayout.scaleX) - (insets?.right ?: 0)
                                    val maxY = 0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + slidingLayout.height - (playerLayout.height * slidingLayout.scaleY) - (insets?.bottom ?: 0)
                                    val newX = when {
                                        slidingLayout.translationX < minX -> minX
                                        slidingLayout.translationX > maxX -> maxX
                                        else -> null
                                    }
                                    val newY = when {
                                        slidingLayout.translationY < minY -> minY
                                        slidingLayout.translationY > maxY -> maxY
                                        else -> null
                                    }
                                    if (newX != null || newY != null) {
                                        moveAnimation = slidingLayout.animate().apply {
                                            newX?.let { translationX(it) }
                                            newY?.let { translationY(it) }
                                            setDuration(250L)
                                            start()
                                        }
                                    }
                                } else {
                                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                    val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                                    val scaledXDiff = (slidingLayout.width * (1f - slidingLayout.scaleX)) / 2
                                    val scaledYDiff = (slidingLayout.height * (1f - slidingLayout.scaleY)) / 2
                                    val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                                    val newX = slidingLayout.width - (insets?.right ?: 0) - (playerLayout.width * slidingLayout.scaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * slidingLayout.scaleX)
                                    val newY = slidingLayout.height - navBarHeight - (playerLayout.height * slidingLayout.scaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * slidingLayout.scaleY)
                                    moveAnimation = slidingLayout.animate().apply {
                                        translationX(0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + newX)
                                        translationY(0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + newY)
                                        setDuration(250L)
                                        start()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            dragView.setOnTouchListener { _, event ->
                if (!isAnimating) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointerId = event.getPointerId(0)
                            val x = event.x
                            val y = event.y
                            lastX = x * slidingLayout.scaleX
                            lastY = y * slidingLayout.scaleY
                            statusBarSwipe = !isPortrait && y <= 100
                            downAction(event)
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            if (activePointerId == -1) {
                                val pointerIndex = event.actionIndex
                                val pointerId = event.getPointerId(pointerIndex)
                                val x = event.getX(pointerIndex)
                                val y = event.getY(pointerIndex)
                                if (x in 0f..playerLayout.width.toFloat() && y in 0f..playerLayout.height.toFloat()) {
                                    activePointerId = pointerId
                                    lastX = x * slidingLayout.scaleX
                                    lastY = y * slidingLayout.scaleY
                                    statusBarSwipe = !isPortrait && y <= 100
                                    downAction(event)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isMaximized) {
                                playerControls.root.dispatchTouchEvent(event)
                                if (!playerControls.progressBar.isPressed && !statusBarSwipe && activePointerId != -1) {
                                    val pointerIndex = event.findPointerIndex(activePointerId)
                                    if (pointerIndex != -1) {
                                        val y = event.getY(pointerIndex)
                                        val translationY = y - lastY
                                        if (slidingLayout.translationY + translationY < 0) {
                                            slidingLayout.translationY = 0f
                                            lastY = y
                                        } else {
                                            slidingLayout.translationY += translationY
                                            lastY = y - translationY
                                        }
                                        if (slidingLayout.translationY < touchSlop) {
                                            if (!backgroundVisible) {
                                                enableBackground()
                                            }
                                        } else {
                                            if (backgroundVisible) {
                                                disableBackground()
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (activePointerId != -1) {
                                    val pointerIndex = event.findPointerIndex(activePointerId)
                                    if (pointerIndex != -1) {
                                        val x = event.getX(pointerIndex) * slidingLayout.scaleX
                                        val y = event.getY(pointerIndex) * slidingLayout.scaleY
                                        val translationX = x - lastX
                                        val translationY = y - lastY
                                        slidingLayout.translationX += translationX
                                        if (moveFreely) {
                                            slidingLayout.translationY += translationY
                                        }
                                        lastX = x - translationX
                                        lastY = y - translationY
                                        velocityTracker?.addMovement(
                                            MotionEvent.obtain(
                                                event.downTime,
                                                event.eventTime,
                                                event.action,
                                                slidingLayout.translationX,
                                                slidingLayout.translationY,
                                                event.metaState
                                            )
                                        )
                                        if (isTap && ((startTranslationX - slidingLayout.translationX) !in touchSlopRange || (startTranslationY - slidingLayout.translationY) !in touchSlopRange)) {
                                            isTap = false
                                        }
                                    }
                                }
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val pointerIndex = event.actionIndex
                            val pointerId = event.getPointerId(pointerIndex)
                            if (pointerId == activePointerId) {
                                var newId = -1
                                for (i in 0 until event.pointerCount) {
                                    val id = event.getPointerId(i)
                                    if (id != activePointerId) {
                                        val x = event.getX(i)
                                        val y = event.getY(i)
                                        if (x in 0f..playerLayout.width.toFloat() && y in 0f..playerLayout.height.toFloat()) {
                                            newId = id
                                            lastX = x * slidingLayout.scaleX
                                            lastY = y * slidingLayout.scaleY
                                            break
                                        }
                                    }
                                }
                                if (newId == -1) {
                                    upAction(event)
                                }
                                activePointerId = newId
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> upAction(event)
                    }
                }
                true
            }
            chatTouchView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        chatStatusBarSwipe = !isPortrait && event.y <= 100
                        chatLinearLayout.dispatchTouchEvent(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (chatStatusBarSwipe) {
                            chatLinearLayout.dispatchTouchEvent(
                                MotionEvent.obtain(event).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                }
                            )
                        } else {
                            chatLinearLayout.dispatchTouchEvent(event)
                        }
                    }
                    else -> chatLinearLayout.dispatchTouchEvent(event)
                }
                true
            }
            with(playerControls) {
                root.setOnTouchListener { _, event ->
                    controllerTapDetector.onTouchEvent(event)
                }
                playPause.setOnClickListener {
                    showController(force = true)
                    playPause()
                }
                rewind.text = (requireContext().prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10).toString()
                rewind.setOnClickListener {
                    showController(force = true)
                    rewind()
                }
                fastForward.text = (requireContext().prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10).toString()
                fastForward.setOnClickListener {
                    showController(force = true)
                    fastForward()
                }
                if (isTv) {
                    progressBar.isFocusable = hudOpenedByUser
                    progressBar.setOnKeyListener { _, keyCode, event ->
                        // the timeline focused without a HUD opened on purpose: left / right still run their hidden-HUD shortcut
                        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && _binding != null && !hudOpenedByUser) {
                            val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
                            val pipOpen = (activity as? MainActivity)?.isTvPipOpen() == true
                            val dpadAction = effectiveDpadAction(Keys.dpadForKeyCode(requireContext(), keyCode), pipOpen)
                            val isSwitch = dpadAction == Keys.ACTION_SWITCH_PREV || dpadAction == Keys.ACTION_SWITCH_NEXT
                            if ((isSwitch && isAtLiveEdgeForStreamSwitch()) || (!isSwitch && dpadAction != Keys.ACTION_DEFAULT)) {
                                binding.dragView.requestFocus()
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    executeTvRemoteAction(dpadAction)
                                }
                                return@setOnKeyListener true
                            }
                        }
                        // down from the timeline: back to the button the focus was on, not the nearest one
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && _binding != null) {
                            if (tvScrubPosition >= 0L) {
                                commitTvScrub()
                            }
                            val last = findLastControl()
                            if (last != null && last.isShown && last.isFocusable && last.requestFocus()) {
                                return@setOnKeyListener true
                            }
                        }
                        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && tvScrubPosition >= 0L) {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                commitTvScrub()
                            }
                            return@setOnKeyListener true
                        }
                        onTimeBarKey(keyCode, event)
                    }
                    // discreet timeline: the scrubber and the accent color only show while it has the focus
                    // the color of accent: the ambiance color of the Xtra TV settings
                    val playedColor = if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
                        com.github.andreyasadchy.xtra.ui.tv.TvAccent.color
                    } else {
                        MaterialColors.getColor(progressBar, androidx.appcompat.R.attr.colorPrimary)
                    }
                    progressBar.hideScrubber(false)
                    progressBar.setPlayedColor(0xE6FFFFFF.toInt())
                    progressBar.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            progressBar.showScrubber()
                            progressBar.setPlayedColor(playedColor)
                        } else {
                            if (tvScrubPosition >= 0L) {
                                commitTvScrub()
                            }
                            progressBar.hideScrubber(false)
                            progressBar.setPlayedColor(0xE6FFFFFF.toInt())
                        }
                    }
                }
                progressBar.addListener(
                    object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                            binding.playerControls.position.text = DateUtils.formatElapsedTime(position / 1000)
                            binding.playerControls.root.removeCallbacks(controllerHideAction)
                        }

                        override fun onScrubMove(timeBar: TimeBar, position: Long) {
                            binding.playerControls.position.text = DateUtils.formatElapsedTime(position / 1000)
                        }

                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                            if (!canceled) {
                                val dvrStream = getTvDvrStream()
                                if (isTvLiveTimeline()) {
                                    commitMobileLiveSeek(position)
                                } else if (dvrStream != null) {
                                    val duration = getTotalDuration() ?: 0L
                                    if (duration > 0L && position >= duration - LIVE_EDGE_MS) {
                                        (activity as? MainActivity)?.startStream(dvrStream)
                                    } else {
                                        seek(position)
                                    }
                                } else {
                                    seek(position)
                                }
                            } else {
                                if (controllerAutoHide && controllerHideOnTouch) {
                                    binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                                }
                            }
                        }
                    }
                )
                position.text = DateUtils.formatElapsedTime(0)
                duration.text = DateUtils.formatElapsedTime(0)
                subtitleView.setUserDefaultStyle()
                subtitleView.setUserDefaultTextSize()
            }
            if (isTv) {
                prefillTvStreamUiBeforeService()
                if (peekHudOnStart) {
                    peekHudOnStart = false
                    val immediate = peekHudImmediateSwitch
                    peekHudImmediateSwitch = false
                    if (isMaximized) {
                        peekHud(immediate = immediate)
                    }
                }
            }
        }
    }

    private var dvrInitialHudShown = false

    private fun openHudOnTimelineForDvr() {
        if (!isTv || !isAdded || _binding == null || !isMaximized) return
        hudOpenedByUser = true
        endHudPeek()
        showController(force = true)
        binding.playerControls.root.post {
            val b = _binding ?: return@post
            if (!isAdded || !isMaximized || !b.playerControls.root.isVisible) return@post
            b.playerControls.progressBar.isFocusable = true
            if (!(b.playerControls.progressBar.isShown && b.playerControls.progressBar.requestFocus())) {
                focusPrimaryControl()
            }
        }
    }

    fun start() {
        setupTvHud()
        setupSeekPreview()
        if (isTv && !dvrInitialHudShown && arguments?.getBoolean(KEY_DVR) == true && isMaximized) {
            dvrInitialHudShown = true
            peekHudOnStart = false
            peekHudImmediateSwitch = false
            openHudOnTimelineForDvr()
        } else if (isTv && peekHudOnStart) {
            peekHudOnStart = false
            val immediate = peekHudImmediateSwitch
            peekHudImmediateSwitch = false
            if (isMaximized) {
                peekHud(immediate = immediate)
            }
        } else if (isTv && hudPeek) {
            setHudInfoOnly(true)
        }
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    playbackService?.integrity?.collect {
                        (requireActivity() as? MainActivity)?.getNewIntegrityToken(it, childFragmentManager)
                    }
                }
            }
            if (isTv && playbackService?.type == BasePlaybackService.STREAM) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch {
                            viewModel.accountFollowedAtMs.collect { updateTvChatLockUi() }
                        }
                        launch {
                            viewModel.accountFollowKnown.collect { updateTvChatLockUi() }
                        }
                    }
                }
            }
            if (playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                viewModel.isFollowingChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    playbackService?.channelLogin,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    TwitchApiHelper.getHelixHeaders(requireContext()),
                )
                when (playbackService?.type) {
                    BasePlaybackService.STREAM -> {
                        viewModel.loadMutedSegments(null, null, emptyMap(), emptyMap())
                        playbackService?.channelLogin?.let {
                            viewModel.loadStreamInfo(
                                channelId = playbackService?.channelId,
                                channelLogin = it,
                                // TV: a stream opened without its start (multi-stream...) loads its details for the timeline
                                viewerCount = playbackService?.viewerCount?.takeUnless { isTv && playbackService?.createdAt.isNullOrBlank() },
                                loop = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) || !requireContext().prefs().getBoolean(C.CHAT_PUB_SUB_ENABLED, true),
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                        }
                    }
                    BasePlaybackService.VIDEO -> {
                        val videoId = playbackService?.videoId
                        if (!videoId.isNullOrBlank()) {
                            val includeVideoToken = requireContext().prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)
                            viewModel.loadMutedSegments(
                                videoId,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                TwitchApiHelper.getGQLWebHeaders(requireContext(), includeVideoToken),
                                TwitchApiHelper.getGQLHeaders(requireContext(), includeVideoToken),
                            )
                        } else {
                            viewModel.loadMutedSegments(null, null, emptyMap(), emptyMap())
                        }
                        if (!videoId.isNullOrBlank() && (isTv || requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true) || requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false))) {
                            viewModel.loadGamesList(
                                videoId,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                TwitchApiHelper.getGQLHeaders(requireContext()),
                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                        }
                    }
                    else -> {
                        viewModel.loadMutedSegments(null, null, emptyMap(), emptyMap())
                    }
                }
            }
            if (playbackService?.restoreQuality == true) {
                playbackService?.restoreQuality = false
                changeQuality(playbackService?.previousQuality)
            }
            with(playerControls) {
                val channelLogin = playbackService?.channelLogin
                val channelName = playbackService?.channelName
                val displayName = if (channelLogin != null && !channelLogin.equals(channelName, true)) {
                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "$channelName ($channelLogin)"
                        "1" -> channelName
                        else -> channelLogin
                    }
                } else {
                    channelName
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_CHANNEL, true)) {
                    channel.visibility = View.VISIBLE
                    channel.text = displayName
                    channel.setOnClickListener {
                        findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = playbackService?.channelId,
                                channelLogin = playbackService?.channelLogin,
                                channelName = playbackService?.channelName,
                            )
                        )
                        leaveForBrowsing()
                    }
                }
                val titleText = playbackService?.title
                if (!titleText.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_TITLE, true)) {
                    title.visibility = View.VISIBLE
                    title.text = titleText.trim()
                }
                val gameName = playbackService?.gameName
                if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
                    val openCategory = View.OnClickListener {
                        findNavController().navigate(
                            if (requireContext().prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = playbackService?.gameId,
                                    gameSlug = playbackService?.gameSlug,
                                    gameName = gameName
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = playbackService?.gameId,
                                    gameSlug = playbackService?.gameSlug,
                                    gameName = gameName
                                )
                            }
                        )
                        leaveForBrowsing()
                    }
                    category.visibility = View.VISIBLE
                    category.text = gameName
                    category.setOnClickListener(openCategory)
                    tvCategoryChip?.setOnClickListener(openCategory)
                }
                updateTvAvatars()
                // no minimize button on TV: back does it, as the setting says
                if (!isTv && requireContext().prefs().getBoolean(C.PLAYER_MINIMIZE, true)) {
                    minimize.visibility = View.VISIBLE
                    minimize.setOnClickListener { minimize() }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_VOLUME_BUTTON, true)) {
                    volume.visibility = View.VISIBLE
                    updateTvVolumeButtonIcon()
                    volume.setOnClickListener {
                        showController(force = true)
                        showVolumeDialog()
                    }
                    volume.setOnLongClickListener {
                        showController(force = true)
                        hideTooltip()
                        if (tvVolumePill?.isShowing == true) {
                            tvVolumePill?.dismiss(restoreAnchorFocus = true, immediate = true)
                        }
                        togglePlayerMute()
                        true
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_SETTINGS, true)) {
                    quality.visibility = View.VISIBLE
                    quality.setOnClickListener {
                        showController(force = true)
                        showQualityDialog()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MODE, false)) {
                    audioOnly.visibility = View.VISIBLE
                    audioOnly.setOnClickListener {
                        showController(force = true)
                        if (playbackService?.quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                            changeQuality(playbackService?.previousQuality)
                        } else {
                            changeQuality(playbackService?.qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY })
                        }
                        changePlayerMode()
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && requireContext().prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR_BUTTON, true)) {
                    audioCompressor.visibility = View.VISIBLE
                    if (requireContext().prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                    audioCompressor.setOnClickListener {
                        showController(force = true)
                        toggleAudioCompressor()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU, true)) {
                    menu.visibility = View.VISIBLE
                    menu.setOnClickListener {
                        showController(force = true)
                        PlayerSettingsDialog.newInstance(
                            type = playbackService?.type,
                            speedText = getCurrentSpeed()?.let { speed ->
                                requireContext().prefs().getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")
                                    ?.split("\n")?.find { it == speed.toString() }
                            },
                            vodGames = !viewModel.gamesList.value.isNullOrEmpty()
                        ).show(childFragmentManager, "closeOnPip")
                    }
                }
                if (playbackService?.type == BasePlaybackService.STREAM) {
                    if (!requireContext().tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                    ) {
                        if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_BAR_TOGGLE, false) && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && !isTv) {
                            toggleChatInput.visibility = View.VISIBLE
                            toggleChatInput.setOnClickListener {
                                showController(force = true)
                                toggleChatBar()
                            }
                        }
                        slidingLayout.viewTreeObserver.addOnGlobalLayoutListener {
                            if (slidingLayout.isKeyboardShown) {
                                if (!isKeyboardShown) {
                                    isKeyboardShown = true
                                    if (!isPortrait) {
                                        chatLayout.updateLayoutParams { width = (slidingLayout.width / 1.8f).toInt() }
                                        showStatusBar()
                                    }
                                }
                            } else {
                                if (isKeyboardShown) {
                                    isKeyboardShown = false
                                    chatLayout.clearFocus()
                                    if (!isPortrait) {
                                        chatLayout.updateLayoutParams { width = chatWidthLandscape }
                                        if (isMaximized) {
                                            hideStatusBar()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.stream.collectLatest { stream ->
                                if (stream != null) {
                                    stream.id?.let {
                                        playbackService?.streamId = it
                                        chatFragment?.updateStreamId(it)
                                    }
                                    chatFragment?.updateStreamMetadata(stream.id, stream.gameId, stream.gameName)
                                    if (requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) ||
                                        !requireContext().prefs().getBoolean(C.CHAT_PUB_SUB_ENABLED, true) ||
                                        viewersText.text.isNullOrBlank()
                                    ) {
                                        updateViewerCount(stream.viewerCount)
                                    }
                                    if (requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) ||
                                        !requireContext().prefs().getBoolean(C.CHAT_PUB_SUB_ENABLED, true) ||
                                        title.text.isNullOrBlank() ||
                                        category.text.isNullOrBlank()
                                    ) {
                                        updateStreamInfo(stream.title, stream.gameId, stream.gameSlug, stream.gameName)
                                    }
                                    if (playbackService?.createdAt.isNullOrBlank() && !stream.createdAt.isNullOrBlank()) {
                                        // a stream opened without its start (from the multi-stream, a link...): the
                                        // timeline to go back into its VOD needs it
                                        playbackService?.createdAt = stream.createdAt
                                        if (isTvLiveTimeline()) {
                                            updateTvLiveTimeline()
                                        }
                                    }
                                    if (requireContext().prefs().getBoolean(C.PLAYER_SHOW_UPTIME, true) &&
                                        !uptimeLayout.isVisible
                                    ) {
                                        stream.createdAt?.let { date ->
                                            Instant.parseOrNull(date)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { createdAt ->
                                                updateUptime(createdAt)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.unlistedVideoQualities.collectLatest { qualities ->
                                if (qualities != null) {
                                    if (qualities.isNotBlank()) {
                                        if (pendingDvr) {
                                            // go back in the live: open the VOD of the stream being broadcast at the chosen position
                                            pendingDvr = false
                                            pendingDvrResumeFromPause = false
                                            val offset = pendingDvrPosition.coerceAtLeast(0L)
                                            val video = Video(
                                                channelId = playbackService?.channelId,
                                                channelLogin = playbackService?.channelLogin,
                                                channelName = playbackService?.channelName,
                                                gameId = playbackService?.gameId,
                                                gameSlug = playbackService?.gameSlug,
                                                gameName = playbackService?.gameName,
                                                title = playbackService?.title,
                                                createdAt = playbackService?.createdAt,
                                            )
                                            // the VOD being recorded gives the chat of the broadcast (its replay), found
                                            // while the video opens; none when the channel keeps no VOD
                                            val activity = requireActivity() as MainActivity
                                            if (activity.dvrChatVideoId == null) {
                                                activity.dvrChatVideoId = withTimeoutOrNull(2500L) {
                                                    viewModel.findArchiveVideoId(
                                                        requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                                        TwitchApiHelper.getGQLHeaders(requireContext()),
                                                        playbackService?.channelId,
                                                        playbackService?.channelLogin,
                                                        playbackService?.createdAt,
                                                    )
                                                }
                                            }
                                            activity.dvrChatVideoId?.takeIf { it.isNotBlank() }?.let { archiveVid ->
                                                com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
                                                    context = activity,
                                                    channelId = playbackService?.channelId,
                                                    channelLogin = playbackService?.channelLogin,
                                                    streamCreatedAt = playbackService?.createdAt,
                                                    archiveVideoId = archiveVid,
                                                )
                                            }
                                            activity.startVideo(video, offset, qualities = qualities)
                                        } else {
                                            pendingDvrResumeFromPause = false
                                            (requireActivity() as MainActivity).startVideo(Video(), 0, qualities = qualities)
                                        }
                                    } else {
                                        val resumeLive = pendingDvrResumeFromPause
                                        pendingDvr = false
                                        pendingDvrResumeFromPause = false
                                        (activity as? MainActivity)?.dvrStream = null
                                        if (resumeLive) {
                                            seekToLivePosition()
                                        }
                                        if (isTvLiveTimeline()) {
                                            updateTvLiveTimeline()
                                        }
                                        if (!resumeLive) {
                                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.video_not_found, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    viewModel.unlistedVideoQualities.value = null
                                }
                            }
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_RESTART, true)) {
                        restart.visibility = View.VISIBLE
                        restart.setOnClickListener {
                            showController(force = true)
                            restartPlayer()
                        }
                    }
                    if (!isTv && requireContext().prefs().getBoolean(C.PLAYER_SEEK_LIVE, false)) {
                        seekLive.visibility = View.VISIBLE
                        seekLive.setOnClickListener {
                            showController(force = true)
                            seekToLivePosition()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_VIEWER_LIST, false)) {
                        viewersLayout.setOnClickListener {
                            showController(force = true)
                            openViewerList()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
                        playbackService?.createdAt?.let {
                            Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { createdAt ->
                                updateUptime(createdAt)
                            }
                        }
                    }
                    // TV: always there for a stream, the row has tabs (followed, popular...) even when the stream came alone
                    if (isTv && requireContext().prefs().getBoolean(C.TV_BUTTON_STREAM_LIST, true)) {
                        streamList?.visibility = View.VISIBLE
                        streamList?.setOnClickListener {
                            hideController()
                            (activity as? MainActivity)?.showStreamSwitcher()
                        }
                    }
                    rewind.visibility = View.GONE
                    fastForward.visibility = View.GONE
                    if (isTvLiveTimeline()) {
                        // Live timeline of the whole broadcast: scrubbing back opens the live DVR VOD (or seeks Adblock VOD)
                        updateTvLiveTimeline()
                    } else {
                        position.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        duration.visibility = View.GONE
                    }
                    updateStreamInfo(
                        playbackService?.title,
                        playbackService?.gameId,
                        playbackService?.gameSlug,
                        playbackService?.gameName
                    )
                    updateViewerCount(playbackService?.viewerCount)
                } else {
                    if (requireContext().prefs().getBoolean(C.PLAYER_SPEED_BUTTON, true)) {
                        speed.visibility = View.VISIBLE
                        speed.setOnClickListener {
                            showController(force = true)
                            showSpeedDialog()
                        }
                    }
                }
                progressBar.setAdGroupTimesMs(null, null, 0)
                hlsMutedSegments = emptyList()
                progressBar.setMutedSegments(emptyList())
                progressBar.setRecoveredSegments(emptyList())
                seekPreview?.setChapters(null)
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        com.github.andreyasadchy.xtra.ui.tv.TvVodAudioRecovery.activeState.collectLatest { state ->
                            applyVodAudioBarRanges()
                            if (state != null && state.recoveredDurationMs > 0L && state.sessionId != vodAudioToastShownSessionId) {
                                vodAudioToastShownSessionId = state.sessionId
                                val mins = ((state.recoveredDurationMs + 30_000L) / 60_000L).coerceAtLeast(1L)
                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                                    requireContext(),
                                    getString(R.string.tv_vod_audio_recovered_toast, mins.toInt()),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
                if (playbackService?.type == BasePlaybackService.VIDEO) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.mutedSegments.collectLatest { _ ->
                                applyVodAudioBarRanges()
                            }
                        }
                    }
                    if (isTv && requireContext().prefs().getBoolean(C.TV_BUTTON_STREAM_LIST, true)) {
                        streamList?.visibility = View.VISIBLE
                        streamList?.setOnClickListener {
                            hideController()
                            (activity as? MainActivity)?.showStreamSwitcher()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.isBookmarked.collectLatest {
                                    if (it != null) {
                                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setBookmarkText(it)
                                        viewModel.isBookmarked.value = null
                                    }
                                }
                            }
                        }
                    }
                    if (!playbackService?.videoId.isNullOrBlank() && (isTv || requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true) || requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false))) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.gamesList.collectLatest { list ->
                                    if (!list.isNullOrEmpty()) {
                                        if (isTv) {
                                            val markers = list.mapNotNull { it.vodPosition?.toLong()?.takeIf { pos -> pos > 0L } }
                                                .distinct()
                                                .sorted()
                                                .toLongArray()
                                            if (markers.isNotEmpty()) {
                                                progressBar.setAdGroupTimesMs(markers, BooleanArray(markers.size), markers.size)
                                            }
                                            seekPreview?.setChapters(list)
                                        }
                                        if (requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true)) {
                                            vodGames.visibility = View.VISIBLE
                                            vodGames.setOnClickListener {
                                                showController(force = true)
                                                showVodGames()
                                            }
                                        }
                                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setVodGames()
                                    }
                                }
                            }
                        }
                    }
                }
                if (playbackService?.type == BasePlaybackService.CLIP) {
                    val videoId = playbackService?.videoId
                    if (!videoId.isNullOrBlank()) {
                        binding.watchVideo.visibility = View.VISIBLE
                        binding.watchVideo.setOnClickListener {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val offset = playbackService?.videoOffsetSeconds?.let {
                                    (it * 1000) + (getCurrentPosition() ?: 0)
                                } ?: 0
                                (requireActivity() as MainActivity).startVideo(
                                    Video(
                                        id = videoId,
                                        channelId = playbackService?.channelId,
                                        channelLogin = playbackService?.channelLogin,
                                        channelName = playbackService?.channelName,
                                        channelImageURL = playbackService?.channelImage,
                                        createdAt = playbackService?.videoCreatedAt,
                                        animatedPreviewURL = playbackService?.videoAnimatedPreviewURL,
                                    ),
                                    offset,
                                    true
                                )
                            }
                        }
                    }
                } else {
                    if (requireContext().prefs().getBoolean(C.PLAYER_SLEEP, false)) {
                        sleepTimer.visibility = View.VISIBLE
                        sleepTimer.setOnClickListener {
                            showController(force = true)
                            showSleepTimerDialog()
                        }
                    }
                }
                if (playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    if (playbackService?.loaded == true) {
                        quality.isEnabled = true
                        quality.setColorFilter(Color.WHITE)
                        download.isEnabled = true
                        download.setColorFilter(Color.WHITE)
                        audioOnly.isEnabled = true
                        audioOnly.setColorFilter(Color.WHITE)
                        setQualityText()
                    } else {
                        quality.isEnabled = false
                        quality.setColorFilter(Color.GRAY)
                        download.isEnabled = false
                        download.setColorFilter(Color.GRAY)
                        audioOnly.isEnabled = false
                        audioOnly.setColorFilter(Color.GRAY)
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_DOWNLOAD, false)) {
                        download.visibility = View.VISIBLE
                        download.setOnClickListener {
                            showController(force = true)
                            showDownloadDialog()
                        }
                    }
                    val setting = requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
                    if (requireContext().prefs().getBoolean(C.PLAYER_FOLLOW, false) && (setting == 0 || setting == 1)) {
                        follow.visibility = View.VISIBLE
                        follow.setOnClickListener {
                            showController(force = true)
                            viewModel.isFollowing.value?.let {
                                if (it) {
                                    requireContext().getAlertDialogBuilder()
                                        .setMessage(getString(R.string.unfollow_channel, displayName))
                                        .setNegativeButton(getString(R.string.no), null)
                                        .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                            viewModel.deleteFollowChannel(
                                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                                playbackService?.channelId,
                                                setting,
                                                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                            )
                                        }
                                        .show()
                                } else {
                                    viewModel.saveFollowChannel(
                                        requireContext().tokenPrefs().getString(C.USER_ID, null),
                                        playbackService?.channelId,
                                        playbackService?.channelLogin,
                                        playbackService?.channelName,
                                        setting,
                                        requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                        !requireContext().prefs().getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
                                        playbackService?.createdAt,
                                        requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                    )
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.isFollowing.collectLatest {
                                    if (it != null) {
                                        if (it) {
                                            follow.setImageResource(R.drawable.baseline_favorite_black_24)
                                        } else {
                                            follow.setImageResource(R.drawable.baseline_favorite_border_black_24)
                                        }
                                    }
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.follow.collectLatest { pair ->
                                    if (pair != null) {
                                        val following = pair.first
                                        val errorMessage = pair.second
                                        if (!errorMessage.isNullOrBlank()) {
                                            val rejected = errorMessage.startsWith(C.FOLLOW_REJECTED_PREFIX)
                                            val msg = if (rejected) getString(R.string.tv_follow_rejected_verification) else errorMessage
                                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), msg, if (rejected) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                                        } else {
                                            if (following) {
                                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), getString(R.string.now_following, displayName), Toast.LENGTH_SHORT).show()
                                            } else {
                                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), getString(R.string.unfollowed, displayName), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        viewModel.follow.value = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val currentChatFragment = (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) as? ChatFragment)
            if (currentChatFragment != null) {
                chatFragment = currentChatFragment
                onChatFragmentReady(currentChatFragment)
            } else {
                val fragment = createChatFragmentForCurrentPlayback()
                if (fragment != null) {
                    childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, fragment).commit()
                }
                chatFragment = fragment
            }
        }
    }

    private fun createChatFragmentForCurrentPlayback(): ChatFragment? = when (playbackService?.type) {
        BasePlaybackService.STREAM -> ChatFragment.newInstance(
            playbackService?.channelId,
            playbackService?.channelLogin,
            playbackService?.channelName,
            playbackService?.streamId,
        )
        BasePlaybackService.VIDEO -> ChatFragment.newInstance(
            playbackService?.channelId,
            playbackService?.channelLogin,
            // a live watched back in time: the chat of the VOD being recorded
            playbackService?.videoId ?: (activity as? MainActivity)?.takeIf { it.dvrStream != null }?.dvrChatVideoId,
            playbackService?.createdAt,
            0,
        )
        BasePlaybackService.CLIP -> ChatFragment.newInstance(
            playbackService?.channelId,
            playbackService?.channelLogin,
            playbackService?.videoId,
            playbackService?.videoCreatedAt,
            playbackService?.videoOffsetSeconds.takeIf { it != -1 },
        )
        BasePlaybackService.OFFLINE_VIDEO -> ChatFragment.newLocalInstance(
            playbackService?.channelId,
            playbackService?.channelLogin,
            playbackService?.videoCreatedAt ?: playbackService?.createdAt?.takeIf { playbackService?.clipId == null },
            playbackService?.chatUrl,
        )
        else -> null
    }

    fun restartTvChat() {
        val ctx = context ?: return
        if (ctx.prefs().getBoolean(C.CHAT_DISABLE, false) || !isAdded || _binding == null) return
        val fragment = createChatFragmentForCurrentPlayback() ?: return
        childFragmentManager.beginTransaction()
            .replace(R.id.chatFragmentContainer, fragment)
            .commitAllowingStateLoss()
        chatFragment = fragment
        binding.root.post {
            if (chatFragment === fragment && fragment.isAdded) {
                if (playbackService?.type != BasePlaybackService.STREAM) {
                    fragment.startReplayChatLoad()
                }
                fragment.onChatShown()
            }
        }
        showTvPlayerOsd(getString(R.string.tv_chat_restarted), R.drawable.tv_ic_refresh)
    }

    fun restartTvPlayer() {
        if (context == null) return
        restartPlayer()
        showTvPlayerOsd(getString(R.string.tv_player_restarted), R.drawable.tv_ic_refresh)
    }

    private fun initLayout() {
        with(binding) {
            if (isPortrait) {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener(null)
                showStatusBar()
                playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    marginEnd = 0
                }
                chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    gravity = Gravity.BOTTOM
                }
                if (isMaximized) {
                    chatLayout.visibility = View.VISIBLE
                } else {
                    chatLayout.visibility = View.GONE
                    val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                    slidingLayout.scaleX = minimizedScaleX
                    slidingLayout.scaleY = minimizedScaleY
                    slidingLayout.doOnPreDraw {
                        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                        val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                        val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                        val playerHeight = (slidingLayout.width / (16f / 9f)).toInt()
                        val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                        val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                        val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                        val newX = slidingLayout.width - (insets?.right ?: 0) - (slidingLayout.width * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                        val newY = slidingLayout.height - navBarHeight - (playerHeight * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                        slidingLayout.translationX = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                        slidingLayout.translationY = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                    }
                }
                aspectRatioFrameLayout.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                playerLayout.isPortrait = true
                chatLayout.isPortrait = true
                with(playerControls) {
                    if (requireContext().prefs().getBoolean(C.PLAYER_FULLSCREEN, true) && !isTv) {
                        fullscreen.visibility = View.VISIBLE
                        fullscreen.setImageResource(R.drawable.baseline_fullscreen_black_24)
                        fullscreen.setOnClickListener {
                            showController(force = true)
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                    aspectRatio.visibility = View.GONE
                    toggleChat.visibility = View.GONE
                }
            } else {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener {
                    if (!isKeyboardShown && isMaximized && activity != null) {
                        hideStatusBar()
                    }
                }
                if (isMaximized) {
                    hideStatusBar()
                    val chatWidth = if (isChatOpen) chatWidthLandscape else 0
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        marginEnd = chatWidth
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidthLandscape
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.END
                    }
                    if (isChatOpen) {
                        chatLayout.visibility = View.VISIBLE
                        if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
                            requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                                recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
                            }
                        }
                    } else {
                        chatLayout.visibility = View.GONE
                    }
                    if (isTv) {
                        // full screen video, chat as an overlay
                        if (isChatOpen) {
                            showChatLayout()
                        } else {
                            hideChatLayout()
                        }
                    }
                } else {
                    showStatusBar()
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        marginEnd = 0
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidthLandscape
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.END
                    }
                    chatLayout.visibility = View.GONE
                    chatFragment?.onChatHidden()
                    val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                    slidingLayout.scaleX = minimizedScaleX
                    slidingLayout.scaleY = minimizedScaleY
                    if (isTv) {
                        applyTvMinimizedFrame(true)
                    }
                    slidingLayout.doOnPreDraw {
                        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                        val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                        val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                        if (isTv) {
                            val tvMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)
                            val newY = slidingLayout.height - (slidingLayout.height * minimizedScaleY) - tvMargin
                            slidingLayout.translationX = computeTvMinimizedTranslationX(slidingLayout.width, minimizedScaleX)
                            slidingLayout.translationY = -scaledYDiff + newY
                        } else {
                            val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                            val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                            val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                            val playerWidth = slidingLayout.width - getHorizontalInsets(windowInsets)
                            val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                            val newX = slidingLayout.width - (insets?.right ?: 0) - (playerWidth * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                            val newY = slidingLayout.height - navBarHeight - (slidingLayout.height * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                            slidingLayout.translationX = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                            slidingLayout.translationY = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                        }
                    }
                }
                aspectRatioFrameLayout.resizeMode = resizeMode
                playerLayout.isPortrait = false
                chatLayout.isPortrait = false
                with(playerControls) {
                    if (requireContext().prefs().getBoolean(C.PLAYER_FULLSCREEN, true) && !isTv) {
                        fullscreen.visibility = View.VISIBLE
                        fullscreen.setImageResource(R.drawable.baseline_fullscreen_exit_black_24)
                        fullscreen.setOnClickListener {
                            showController(force = true)
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_ASPECT, true) && !isTv) {
                        aspectRatio.visibility = View.VISIBLE
                        aspectRatio.setOnClickListener {
                            showController(force = true)
                            setResizeMode()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true) && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
                        toggleChat.visibility = View.VISIBLE
                        if (isChatOpen) {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                            toggleChat.setOnClickListener {
                                showController(force = true)
                                hideChat()
                            }
                        } else {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_black_24)
                            toggleChat.setOnClickListener {
                                showController(force = true)
                                showChat()
                            }
                        }
                    }
                }
            }
        }
    }

    fun setResizeMode() {
        resizeMode = (resizeMode + 1).let { if (it < 5) it else 0 }
        binding.aspectRatioFrameLayout.resizeMode = resizeMode
        requireContext().prefs().edit { putInt(C.ASPECT_RATIO_LANDSCAPE, resizeMode) }
    }

    fun showSleepTimerDialog() {
        if (requireContext().prefs().getBoolean(C.SLEEP_TIMER_USE_TIME_PICKER, false)) {
            if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
                requireContext().getAlertDialogBuilder()
                    .setMessage(getString(R.string.stop_sleep_timer_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        onSleepTimerChanged(-1L, 0, 0, requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false))
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
            } else {
                val savedValue = requireContext().prefs().getInt(C.SLEEP_TIMER_TIME, 15)
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                    .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                    .setHour(savedValue / 60)
                    .setMinute(savedValue % 60)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    val minutes = TwitchApiHelper.getMinutesLeft(picker.hour, picker.minute)
                    onSleepTimerChanged(minutes * 60_000L, minutes / 60, minutes % 60, requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false))
                    requireContext().prefs().edit {
                        putInt(C.SLEEP_TIMER_TIME, picker.hour * 60 + picker.minute)
                    }
                }
                picker.show(childFragmentManager, null)
            }
        } else {
            SleepTimerDialog.newInstance((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0).show(childFragmentManager, null)
        }
    }

    fun getQualities(): List<Pair<String, VideoQuality>>? {
        val qualities = playbackService?.qualities
        return if (!qualities.isNullOrEmpty()) {
            val hideCodecs = qualities.all {
                val codec = it.codecs?.substringBefore('.')
                codec == "avc1" || codec == "mp4a" || codec.isNullOrBlank()
            }
            qualities.map { quality ->
                when (quality.name) {
                    VideoQuality.AUTO_QUALITY -> getString(R.string.auto)
                    VideoQuality.SOURCE_QUALITY -> getString(R.string.source)
                    VideoQuality.AUDIO_ONLY_QUALITY -> getString(R.string.audio_only)
                    VideoQuality.CHAT_ONLY_QUALITY -> getString(R.string.chat_only)
                    else -> {
                        val frameRate = quality.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        val qualityName = if (frameRate != null && frameRate <= 30) {
                            quality.name.substring(0, quality.name.indexOf('p') + 1)
                        } else {
                            quality.name.toString()
                        }
                        if (hideCodecs) {
                            qualityName
                        } else {
                            val codec = quality.codecs?.substringBefore('.')
                            val codecName = when {
                                codec == "av01" -> "AV1"
                                codec == "hev1" || codec == "hvc1" -> "H.265"
                                codec == "avc1" || codec.isNullOrBlank() -> "H.264"
                                else -> codec
                            }
                            "$qualityName $codecName"
                        }
                    }
                } to quality
            }
        } else null
    }

    fun showQualityDialog() {
        val qualities = getQualities()
        if (!qualities.isNullOrEmpty()) {
            RadioButtonDialogFragment.newInstance(
                REQUEST_CODE_QUALITY,
                qualities.map { it.first },
                qualities.map { it.second.name.toString() }.toTypedArray(),
                qualities.map { it.second.url.toString() }.toTypedArray(),
                qualities.indexOf(qualities.find { it.second.name == playbackService?.quality?.name && it.second.url == playbackService?.quality?.url })
            ).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showSpeedDialog() {
        val speed = getCurrentSpeed()
        if (speed != null) {
            val speedList = requireContext().prefs().getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")
            if (speedList != null) {
                RadioButtonDialogFragment.newInstance(
                    REQUEST_CODE_SPEED,
                    speedList,
                    checkedIndex = speedList.indexOf(speed.toString())
                ).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    private var tvVolumePill: TvVolumePill? = null

    private fun currentVolumePercent(): Int {
        val ctx = context ?: return 100
        val vol = (getCurrentVolume() ?: (ctx.prefs().getInt(C.PLAYER_VOLUME, 100) / 100f)).coerceIn(0f, 1f)
        return (vol * 100f + 0.5f).toInt().coerceIn(0, 100)
    }

    private fun updateTvVolumeButtonIcon(percentOverride: Int? = null) {
        if (!isTv || _binding == null) return
        val pct = percentOverride ?: currentVolumePercent()
        binding.playerControls.volume.setImageResource(
            if (pct <= 0) R.drawable.baseline_volume_off_black_24 else R.drawable.tv_ic_volume
        )
    }

    /**
     * While [TvVolumePill] is open above the Volume button, replace the button's solid white focus disc with a subtle
     * translucent accent ring so the user's eye sees unmistakable active focus on the pill above rather than the button below.
     */
    private fun applyTvVolumeButtonPillState(pillOpen: Boolean) {
        if (!isTv || _binding == null) return
        val btn = binding.playerControls.volume
        if (pillOpen) {
            val density = btn.resources.displayMetrics.density
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x59181822)
                setStroke((1.8f * density + 0.5f).toInt(), com.github.andreyasadchy.xtra.ui.tv.TvAccent.onDark)
            }
            btn.setColorFilter(Color.WHITE)
        } else {
            btn.setBackgroundResource(R.drawable.tv_player_button_background)
            btn.refreshDrawableState()
            btn.jumpDrawablesToCurrentState()
            btn.setColorFilter(
                when {
                    btn.hasFocus() -> com.github.andreyasadchy.xtra.ui.tv.TvAccent.onLight
                    btn.isEnabled -> Color.WHITE
                    else -> Color.GRAY
                }
            )
        }
    }

    private fun ensureTvVolumePill(): TvVolumePill {
        tvVolumePill?.let { return it }
        val created = TvVolumePill(
            hostRoot = binding.playerControls.root as android.widget.RelativeLayout,
            onVolumeChanged = { next ->
                val ctx = context ?: return@TvVolumePill
                if (next > 0) unmutedVolume = next
                ctx.prefs().edit { putInt(C.PLAYER_VOLUME, next) }
                changeVolume(next / 100f)
                updateTvVolumeButtonIcon(next)
            },
            onToggleMute = {
                togglePlayerMute()
                currentVolumePercent()
            },
            onKeepHudAlive = {
                if (_binding != null && areControlsShown()) {
                    binding.playerControls.root.removeCallbacks(controllerHideAction)
                    if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                        binding.playerControls.root.postDelayed(controllerHideAction, maxOf(controllerHideDelay, 4_500L))
                    }
                }
            },
            onClosed = { restoreAnchorFocus ->
                if (_binding != null && isAdded && !isRemoving) {
                    if (restoreAnchorFocus && areControlsShown()) {
                        val target = binding.playerControls.volume.takeIf { it.isVisible && it.isFocusable }
                            ?: binding.playerControls.menu.takeIf { it.isVisible && it.isFocusable }
                        target?.requestFocus()
                    }
                    applyTvVolumeButtonPillState(pillOpen = false)
                    if (areControlsShown() && controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                        binding.playerControls.root.removeCallbacks(controllerHideAction)
                        binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                    }
                }
            },
        )
        tvVolumePill = created
        return created
    }

    fun onTvVolumePillKey(event: KeyEvent): Boolean {
        return tvVolumePill?.onKeyEvent(event) == true
    }

    fun showVolumeDialog() {
        if (isTv && _binding != null && binding.playerControls.root is android.widget.RelativeLayout) {
            if (tvVolumePill?.isShowing == true) {
                tvVolumePill?.dismiss(restoreAnchorFocus = true)
                return
            }
            hideTooltip()
            showController(force = true)
            val anchor = binding.playerControls.volume.takeIf { it.isVisible }
                ?: binding.playerControls.menu.takeIf { it.isVisible }
                ?: binding.playerControls.playPause
            if (anchor.isFocusable && !anchor.hasFocus()) {
                anchor.requestFocus()
            }
            val pct = currentVolumePercent()
            if (pct > 0) unmutedVolume = pct
            updateTvVolumeButtonIcon(pct)
            ensureTvVolumePill().show(
                anchor = anchor,
                timeline = binding.playerControls.progressBar,
                initialPercent = pct,
            )
            applyTvVolumeButtonPillState(pillOpen = true)
            return
        }
        PlayerVolumeDialog.newInstance(getCurrentVolume()).show(childFragmentManager, "closeOnPip")
    }

    fun getTranslateAllMessages(): Boolean? {
        return if (!playbackService?.channelId.isNullOrBlank()) {
            chatFragment?.getTranslateAllMessages()
        } else null
    }

    fun saveTranslatedChannel() {
        playbackService?.channelId?.let {
            chatFragment?.saveTranslatedChannel(it)
        }
    }

    fun deleteTranslatedChannel() {
        playbackService?.channelId?.let {
            chatFragment?.deleteTranslatedChannel(it)
        }
    }

    fun toggleChatBar() {
        with(binding) {
            requireView().findViewById<LinearLayout>(R.id.messageView)?.let {
                if (it.isVisible) {
                    (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatLayout.windowToken, 0)
                    chatLayout.clearFocus()
                    if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleEmoteMenu(false)
                    }
                    it.visibility = View.GONE
                    requireContext().prefs().edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, false) }
                } else {
                    it.visibility = View.VISIBLE
                    requireContext().prefs().edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, true) }
                }
            }
        }
    }

    fun hideChat() {
        if (isTv) exitTvChatScrollMode()
        isChatOpen = false
        hideChatLayout()
        if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true)) {
            binding.playerControls.toggleChat.apply {
                visibility = View.VISIBLE
                setImageResource(R.drawable.baseline_speaker_notes_black_24)
                setOnClickListener {
                    showController(force = true)
                    showChat()
                }
            }
        }
        requireContext().prefs().edit { putBoolean(C.KEY_CHAT_OPENED, false) }
        if (isTv && isMaximized) {
            if (C.getTvPredictionsPopupMode(requireContext().prefs()) == 2) {
                tvActivePrediction.value?.takeIf { it.status == "ACTIVE" }?.let { onTvPredictionEvent(it) }
            }
            if (C.getTvPollsPopupMode(requireContext().prefs()) == 2) {
                tvActivePoll.value?.takeIf { it.status == "ACTIVE" }?.let { onTvPollEvent(it) }
            }
        }
    }

    fun showChat() {
        isChatOpen = true
        hideTvPredictionPopup()
        hideTvPollPopup()
        showChatLayout()
        if (requireContext().prefs().getBoolean(C.PLAYER_CHAT_TOGGLE, true)) {
            binding.playerControls.toggleChat.apply {
                visibility = View.VISIBLE
                setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                setOnClickListener {
                    showController(force = true)
                    hideChat()
                }
            }
        }
        requireContext().prefs().edit { putBoolean(C.KEY_CHAT_OPENED, true) }
        if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
            requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
            }
        }
    }

    private fun hideChatLayout() {
        with(binding) {
            playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.NO_GRAVITY
                marginEnd = 0
            }
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatLayout.windowToken, 0)
            chatLayout.clearFocus()
            chatLayout.visibility = View.GONE
            chatFragment?.onChatHidden()
        }
    }

    private fun showChatLayout() {
        with(binding) {
            if (isTv) {
                applyTvChatLayout()
                chatLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                chatLayout.visibility = View.VISIBLE
                chatFragment?.onChatShown()
                return
            }
            playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                marginEnd = chatWidthLandscape
            }
            chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = chatWidthLandscape
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.END
            }
            chatLayout.visibility = View.VISIBLE
            chatFragment?.onChatShown()
        }
    }

    fun setQualityText() {
        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.let { dialog ->
            val label = getQualities()?.find { it.second == playbackService?.quality }?.first
            dialog.setQuality(label)
        }
    }

    fun updateViewerCount(viewerCount: Int?) {
        with(binding.playerControls) {
            if (viewerCount != null) {
                viewersText.text = TwitchApiHelper.formatCount(viewerCount, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true))
                if (requireContext().prefs().getBoolean(C.PLAYER_VIEWER_ICON, true)) {
                    viewersIcon.visibility = View.VISIBLE
                }
            } else {
                viewersText.text = null
                viewersIcon.visibility = View.GONE
            }
            if (isTv) {
                // TV: the badge has a background, an empty one stays hidden (videos, clips)
                viewersLayout.visibility = if (viewerCount != null) View.VISIBLE else View.GONE
            } else {
                val showViewers = requireContext().prefs().getBoolean(C.MOBILE_HUD_SHOW_VIEWERS, true)
                viewersLayout.visibility = if (viewerCount != null && showViewers) View.VISIBLE else View.GONE
            }
        }
    }

    private var streamOfflineReceived = false

    private fun syncUptimeBadgeElapsedMs(elapsedMs: Long) {
        if (_binding == null || streamOfflineReceived) {
            return
        }
        with(binding.playerControls) {
            if (!requireContext().prefs().getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
                if (uptimeLayout.isVisible) {
                    uptimeTimer.stop()
                    uptimeLayout.visibility = View.GONE
                }
                return
            }
            val safeElapsedMs = elapsedMs.coerceAtLeast(0L)
            val wasVisible = uptimeLayout.isVisible
            uptimeLayout.visibility = View.VISIBLE
            uptimeIcon.visibility = if (requireContext().prefs().getBoolean(C.PLAYER_VIEWER_ICON, true)) View.VISIBLE else View.GONE
            val nowElapsed = SystemClock.elapsedRealtime()
            val desiredBase = nowElapsed - safeElapsedMs
            val currentDisplayedSec = (nowElapsed - uptimeTimer.base) / 1000L
            val targetSec = safeElapsedMs / 1000L
            if (!wasVisible || currentDisplayedSec != targetSec || kotlin.math.abs(uptimeTimer.base - desiredBase) > 250L) {
                uptimeTimer.base = desiredBase
                uptimeTimer.start()
            }
        }
    }

    fun updateLiveStatus(live: Boolean, serverTime: Long?, channelLogin: String?) {
        if (channelLogin == playbackService?.channelLogin) {
            streamOfflineReceived = !live
            if (live) {
                streamEndJob?.cancel()
                streamEndJob = null
                hideTvStreamEndBanner()
                hideTvOfflineOverlay()
                restartPlayer()
            } else if (isTv && playbackService?.type == BasePlaybackService.STREAM && !isPlayingNow()) {
                val stream = (activity as? MainActivity)?.currentPlayingStream() ?: getPlayingStream()
                val login = channelLogin ?: stream?.channelLogin
                if (!login.isNullOrBlank()) {
                    showTvOfflineOverlay(
                        login = login,
                        fallbackName = stream?.channelName ?: playbackService?.channelName ?: login,
                        fallbackAvatar = stream?.channelImage ?: playbackService?.channelImage,
                        showWaitingHint = (requireContext().prefs().getString(C.TV_STREAM_END_ACTION, "home") ?: "home") == "stay",
                    )
                }
            }
            updateUptime(serverTime?.times(1000))
        }
    }

    private fun updateUptime(uptimeMs: Long?) {
        with(binding.playerControls) {
            if (uptimeMs != null && !streamOfflineReceived && requireContext().prefs().getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
                val rawElapsedMs = (com.github.andreyasadchy.xtra.util.TwitchClock.nowMs() - uptimeMs).coerceAtLeast(0L)
                val effectiveElapsedMs = if (isExperimentalVodActive()) {
                    (playbackService as? ExoPlayerService)?.getExperimentalDisplayEdgeMs()?.takeIf { it > 0L }
                        ?: (rawElapsedMs - com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.TARGET_OFFSET_MS).coerceAtLeast(0L)
                } else {
                    rawElapsedMs
                }
                syncUptimeBadgeElapsedMs(effectiveElapsedMs)
            } else {
                uptimeTimer.stop()
                uptimeLayout.visibility = View.GONE
            }
        }
    }

    fun updateStreamInfo(title: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        playbackService?.title = title
        playbackService?.gameId = gameId
        playbackService?.gameSlug = gameSlug
        playbackService?.gameName = gameName
        binding.playerControls.title.apply {
            if (!title.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_TITLE, true)) {
                text = title.trim()
                visibility = View.VISIBLE
            } else {
                text = null
                visibility = View.GONE
            }
        }
        if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
            val openCategory = View.OnClickListener {
                findNavController().navigate(
                    if (requireContext().prefs().getBoolean(C.UI_GAME_PAGER, true)) {
                        GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                            gameId = gameId,
                            gameSlug = gameSlug,
                            gameName = gameName
                        )
                    } else {
                        GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                            gameId = gameId,
                            gameSlug = gameSlug,
                            gameName = gameName
                        )
                    }
                )
                if (isTv) leaveForBrowsing() else minimize()
            }
            binding.playerControls.category.text = gameName
            binding.playerControls.category.visibility = View.VISIBLE
            binding.playerControls.category.setOnClickListener(openCategory)
            binding.playerControls.tvCategoryChip?.setOnClickListener(openCategory)
        } else {
            binding.playerControls.category.text = null
            binding.playerControls.category.visibility = View.GONE
            binding.playerControls.category.setOnClickListener(null)
            binding.playerControls.tvCategoryChip?.setOnClickListener(null)
        }
        updateTvAvatars()
    }

    fun openViewerList() {
        playbackService?.channelLogin?.let { login ->
            PlayerViewerListDialog.newInstance(login).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showVodGames() {
        viewModel.gamesList.value?.let { list ->
            if (isTv) {
                TvSeekPreview.showChaptersPanel(
                    context = requireContext(),
                    title = getString(R.string.games),
                    chapters = list,
                    currentPositionMs = getCurrentPosition() ?: 0L,
                ) { targetMs ->
                    seek(targetMs)
                }
            } else {
                PlayerGamesDialog.newInstance(list).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    fun checkBookmark() {
        playbackService?.videoId?.let { viewModel.checkBookmark(it) }
    }

    fun saveBookmark() {
        viewModel.saveBookmark(
            filesDir = requireContext().filesDir.path,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            videoId = playbackService?.videoId,
            title = playbackService?.title,
            uploadDate = playbackService?.createdAt,
            durationSeconds = playbackService?.durationSeconds,
            type = playbackService?.videoType,
            animatedPreviewUrl = playbackService?.videoAnimatedPreviewURL,
            channelId = playbackService?.channelId,
            channelLogin = playbackService?.channelLogin,
            channelName = playbackService?.channelName,
            channelImage = playbackService?.channelImage,
            thumbnail = playbackService?.thumbnail,
            gameId = playbackService?.gameId,
            gameSlug = playbackService?.gameSlug,
            gameName = playbackService?.gameName,
        )
    }

    fun share() {
        if (isTv) {
            showTvShareQr()
            return
        }
        when (playbackService?.type) {
            BasePlaybackService.STREAM -> {
                playbackService?.channelLogin?.let { channelLogin ->
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${channelLogin}")
                        playbackService?.channelName?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                        type = "text/plain"
                    }, null))
                }
            }
            BasePlaybackService.VIDEO -> {
                playbackService?.videoId?.let { videoId ->
                    val position = getCurrentPosition()?.let { position ->
                        val totalSeconds = position / 1000
                        val hours = (totalSeconds / 3600).let { if (it < 10) "0$it" else "$it" }
                        val minutes = ((totalSeconds % 3600) / 60).let { if (it < 10) "0$it" else "$it" }
                        val seconds = (totalSeconds % 60).let { if (it < 10) "0$it" else "$it" }
                        "?t=${hours}h${minutes}m${seconds}s"
                    } ?: ""
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/videos/${videoId}${position}")
                        playbackService?.title?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                        type = "text/plain"
                    }, null))
                }
            }
            BasePlaybackService.CLIP -> {
                playbackService?.clipId?.let { clipId ->
                    playbackService?.channelLogin?.let { channelLogin ->
                        startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${channelLogin}/clip/${clipId}")
                            playbackService?.title?.let {
                                putExtra(Intent.EXTRA_TITLE, it)
                            }
                            type = "text/plain"
                        }, null))
                    }
                }
            }
            BasePlaybackService.OFFLINE_VIDEO -> {
                playbackService?.quality?.url?.let { videoUrl ->
                    val uri = if (videoUrl.endsWith(".m3u8")) {
                        videoUrl.substringBefore("%2F").toUri()
                    } else {
                        videoUrl.toUri()
                    }
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        setDataAndType(uri, requireContext().contentResolver.getType(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        playbackService?.title?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                    }, null))
                }
            }
        }
    }

    fun changePlayerMode() {
        with(binding) {
            if (canEnterPictureInPicture()) {
                if (!controllerHideOnTouch && !controllerIsAnimating && controllerAutoHide && !binding.playerControls.progressBar.isPressed) {
                    playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                }
                controllerHideOnTouch = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    requireContext().prefs().getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
                }
            } else {
                controllerHideOnTouch = false
                showController(force = true)
                updateProgress()
                requireView().keepScreenOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
                }
            }
        }
    }

    fun isMaximizedPlayer() = _binding != null && isMaximized

    /** TV: the controls (buttons, timeline) are on screen. */
    fun areControlsShown() = _binding?.playerControls?.root?.isVisible == true && !controllerHiding

    fun isChatFocused() = _binding?.chatLayout?.hasFocus() == true

    fun isPlayingStreamMaximized() = _binding != null && isMaximized && playbackService?.type == BasePlaybackService.STREAM

    /** True when a valid target inside the maximized TV player currently holds focus. */
    fun hasValidTvFocus(): Boolean {
        val b = _binding ?: return false
        if (!isMaximized || !isAdded || isRemoving || isDetached) {
            return false
        }
        if (b.dragView.hasFocus()) {
            return true
        }
        if (areControlsShown() && !hudPeek && b.playerControls.root.hasFocus()) {
            return true
        }
        if (b.chatLayout.isVisible && b.chatLayout.hasFocus()) {
            return true
        }
        return false
    }

    /** Restores focus inside the maximized TV player when focus drifted behind or was cleared. */
    fun restoreTvFocusIfLost(): Boolean {
        val b = _binding ?: return false
        if (!isTv || !isAdded || isRemoving || isDetached || !isMaximized || isAnimating) {
            return false
        }
        if (hasValidTvFocus()) {
            return false
        }
        if (areControlsShown() && !hudPeek) {
            if (b.playerControls.root.alpha < 1f) {
                b.playerControls.root.alpha = 1f
            }
            focusPrimaryControl()
            if (b.playerControls.root.hasFocus()) {
                return true
            }
        }
        if (!b.dragView.isFocusable) {
            b.dragView.isFocusable = true
        }
        return b.dragView.isAttachedToWindow && b.dragView.isShown && b.dragView.requestFocus()
    }

    /** Gives the focus to the player when it is maximized, returns false if it is not. */
    fun requestPlayerFocus(): Boolean {
        if (_binding == null || !isMaximized) {
            return false
        }
        return binding.dragView.requestFocus()
    }

    private var tvScrubPosition = -1L
    private var tvScrubOriginPosition = -1L
    private var tvScrubDuration = 0L
    private val tvScrubCommitRunnable = Runnable { commitTvScrub() }
    private var pendingDvr = false
    private var pendingDvrPosition = 0L
    private var pendingDvrResumeFromPause = false

    protected fun isTvLiveTimeline(): Boolean {
        if (playbackService?.type != BasePlaybackService.STREAM) return false
        return isTv || context?.prefs()?.getBoolean(C.MOBILE_LIVE_DVR, true) == true
    }

    fun isExperimentalVodActive(): Boolean =
        (playbackService as? ExoPlayerService)?.experimentalVodActive == true

    /**
     * True while live IRC messages should be buffered until video frame timestamp sync is ready:
     * either Experimental Adblock VOD playback is active, or Experimental Adblock is enabled while
     * the live stream is still loading its initial picture (preventing early "future" chat messages).
     */
    fun shouldHoldExperimentalChat(): Boolean {
        if (isExperimentalVodActive()) return true
        val ctx = context ?: return false
        if (!com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(ctx)) {
            return false
        }
        val service = playbackService
        return if (service == null) {
            arguments?.getBoolean(KEY_STREAM) == true
        } else {
            service.type == BasePlaybackService.STREAM &&
                ((service as? ExoPlayerService)?.let { !it.streamPictureShown && !it.isIntentionallyPausedOrStopped } ?: !service.loaded)
        }
    }

    private fun styleTvGoLiveButton(button: android.widget.TextView) {
        val density = resources.displayMetrics.density
        val dot = requireContext().getDrawable(R.drawable.baseline_circle_24)?.mutate()?.apply {
            setTint(requireContext().getColor(R.color.liveStreamRed))
            val size = (8 * density).toInt()
            setBounds(0, 0, size, size)
        }
        button.setCompoundDrawablesRelative(dot, null, null, null)
        button.compoundDrawablePadding = (6 * density).toInt()
    }

    /** VOD of a live stream opened from its timeline, the end of its timeline goes back to the live. */
    private fun getTvDvrStream(): Stream? {
        return (activity as? MainActivity)?.dvrStream?.takeIf {
            playbackService?.type == BasePlaybackService.VIDEO && playbackService?.videoId == null && it.channelLogin == playbackService?.channelLogin
        }
    }

    /**
     * VOD of a live opened from its timeline: once it catches up with the live (played faster, x2 or x4), the live
     * starts again instead of waiting for the next parts of the VOD being recorded.
     */
    private fun checkTvDvrCaughtUp() {
        val dvrStream = getTvDvrStream() ?: return
        if (_binding == null || tvScrubPosition >= 0 || !isPlayingOrLoading()) {
            return
        }
        val exoService = playbackService as? ExoPlayerService
        if (exoService?.isDvrInitialSeekPending == true) {
            return
        }
        val speed = getCurrentSpeed() ?: 1f
        if (speed <= 1.02f) {
            return
        }
        val duration = getTotalDuration()?.takeIf { it > LIVE_EDGE_MS * 2 } ?: return
        val position = getCurrentPosition() ?: return
        if (position >= duration - DVR_CAUGHT_UP_MS) {
            (activity as? MainActivity)?.startStream(dvrStream)
        }
    }

    private fun getStreamUptime(): Long? {
        return playbackService?.createdAt
            ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
            ?.let { com.github.andreyasadchy.xtra.util.TwitchClock.nowMs() - it }
            ?.takeIf { it > 0 }
    }

    protected open fun syncPlayPauseButtonVisibility() {}

    internal fun isRewoundBehindLiveOnStream(): Boolean {
        val service = playbackService ?: return false
        if (service.type != BasePlaybackService.STREAM) {
            return false
        }
        if (isExperimentalVodActive()) {
            val exoService = service as? ExoPlayerService ?: return false
            if (exoService.isLivePaused) {
                return true
            }
            val vodEdge = exoService.getExperimentalLiveEdgeMs().takeIf { it > 0L }
                ?: return exoService.experimentalVodUserRewound
            val displayEdge = exoService.getExperimentalDisplayEdgeMs().takeIf { it > 0L } ?: vodEdge
            val currentPos = (getCurrentPosition() ?: vodEdge).coerceIn(0L, displayEdge)
            val behindMs = (vodEdge - currentPos).coerceAtLeast(0L)
            val displayBehindMs = (displayEdge - currentPos).coerceAtLeast(0L)
            val userRewound = exoService.experimentalVodUserRewound
            val atAdFreeEdge = if (userRewound) {
                behindMs <= 4_500L && displayBehindMs <= 4_500L
            } else {
                behindMs <= 32_000L
            }
            return !atAdFreeEdge
        }
        val exoService = service as? ExoPlayerService
        if (exoService?.isLivePaused == true) {
            return true
        }
        val shiftBehindMs = exoService?.getLiveTimeShiftBehindMs() ?: 0L
        if (shiftBehindMs >= 4_500L) {
            return true
        }
        val pausedUptime = exoService?.livePausedUptimeMs
        val uptime = getStreamUptime()
        if (pausedUptime != null && uptime != null && uptime - pausedUptime > 8_000L) {
            return true
        }
        return pendingDvr
    }

    protected fun syncStreamSpeedButtonVisibility() {
        val binding = _binding ?: return
        val ctx = context ?: return
        if (playbackService?.type != BasePlaybackService.STREAM) {
            return
        }
        val speedBtn = binding.playerControls.speed
        val enabledInPrefs = ctx.prefs().getBoolean(C.PLAYER_SPEED_BUTTON, true)
        val rewound = isRewoundBehindLiveOnStream()
        if (!rewound && (getCurrentSpeed() ?: 1f) != 1f) {
            setPlaybackSpeed(1f)
        }
        val shouldShow = enabledInPrefs && rewound
        if (shouldShow) {
            if (!speedBtn.isVisible) {
                speedBtn.visibility = View.VISIBLE
            }
            speedBtn.setOnClickListener {
                showController(force = true)
                showSpeedDialog()
            }
        } else if (speedBtn.isVisible) {
            val hadFocus = speedBtn.hasFocus()
            speedBtn.visibility = View.GONE
            if (hadFocus) {
                focusAfterHiddenControl()
            }
        }
    }

    protected fun shouldHideLivePlayPauseWhenPlaying(): Boolean {
        val ctx = context ?: return false
        if (ctx.prefs().getBoolean(C.PLAYER_PAUSE, false)) {
            return false
        }
        val isStream = playbackService?.type == BasePlaybackService.STREAM ||
            (playbackService == null && arguments?.getBoolean(KEY_STREAM) == true)
        if (!isStream) {
            return false
        }
        return !isRewoundBehindLiveOnStream()
    }

    protected fun updateTvLiveTimeline() {
        if (_binding == null || tvScrubPosition >= 0) {
            return
        }
        with(binding.playerControls) {
            if (isExperimentalVodActive()) {
                val exoService = playbackService as? ExoPlayerService
                val vodEdge = exoService?.getExperimentalLiveEdgeMs()?.takeIf { it > 0L }
                    ?: getStreamUptime()?.let { (it - com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.TARGET_OFFSET_MS).coerceAtLeast(0L) }
                if (vodEdge == null || vodEdge <= 0L) {
                    syncPlayPauseButtonVisibility()
                    syncStreamSpeedButtonVisibility()
                    progressBar.visibility = View.GONE
                    position.visibility = View.GONE
                    duration.visibility = View.GONE
                    return
                }
                val displayEdge = exoService?.getExperimentalDisplayEdgeMs()?.takeIf { it > 0L } ?: vodEdge
                val isPaused = exoService?.isLivePaused == true
                progressBar.visibility = View.VISIBLE
                position.visibility = View.VISIBLE
                duration.visibility = View.VISIBLE
                val currentPos = (getCurrentPosition() ?: vodEdge).coerceIn(0L, displayEdge)
                val behindMs = (vodEdge - currentPos).coerceAtLeast(0L)
                val displayBehindMs = (displayEdge - currentPos).coerceAtLeast(0L)
                val userRewound = exoService?.experimentalVodUserRewound == true
                // Immediately exit "Direct" state while paused or time-shifted; while playing normally without
                // rewinding/pausing, tolerate natural 10s segment fluctuations up to 32s.
                val atAdFreeEdge = if (isPaused) {
                    false
                } else if (userRewound) {
                    behindMs <= 4_500L && displayBehindMs <= 4_500L
                } else {
                    behindMs <= 32_000L
                }
                if (atAdFreeEdge) {
                    exoService?.clearExperimentalRewoundIfAtEdge()
                }
                syncPlayPauseButtonVisibility()
                syncStreamSpeedButtonVisibility()
                syncExperimentalChatReplay(!atAdFreeEdge && ((!isPaused && userRewound && behindMs > 15_000L) || behindMs > 45_000L))
                progressBar.setDuration(displayEdge)
                progressBar.setPosition(if (atAdFreeEdge) displayEdge else currentPos)
                progressBar.setBufferedPosition(
                    if (atAdFreeEdge) displayEdge else (exoService?.player?.bufferedPosition ?: currentPos).coerceIn(currentPos, displayEdge)
                )
                position.text = if (atAdFreeEdge) getString(R.string.tv_live) else DateUtils.formatElapsedTime(currentPos / 1000)
                duration.text = DateUtils.formatElapsedTime(displayEdge / 1000)
                syncUptimeBadgeElapsedMs(displayEdge)
                tvGoLive?.let { button ->
                    if (!atAdFreeEdge) {
                        styleTvGoLiveButton(button)
                        button.visibility = View.VISIBLE
                        button.setOnClickListener {
                            hideTooltip()
                            seekToLivePosition()
                            updateTvLiveTimeline()
                            showController()
                        }
                    } else if (getTvDvrStream() == null) {
                        val hadButtonFocus = button.hasFocus()
                        button.visibility = View.GONE
                        if (hadButtonFocus && !(playPause.isVisible && playPause.requestFocus())) {
                            focusAfterHiddenControl()
                        }
                    }
                }
                return
            }
            if (chatFragment?.isHybridReplayActive() == true) {
                chatFragment?.stopHybridReplay()
            }
            syncPlayPauseButtonVisibility()
            syncStreamSpeedButtonVisibility()
            val uptime = getStreamUptime()
            if (uptime == null) {
                progressBar.visibility = View.GONE
                position.visibility = View.GONE
                duration.visibility = View.GONE
                return
            }
            progressBar.visibility = View.VISIBLE
            position.visibility = View.VISIBLE
            duration.visibility = View.VISIBLE
            progressBar.setDuration(uptime)
            val exoService = playbackService as? ExoPlayerService
            val shiftBehindMs = exoService?.getLiveTimeShiftBehindMs() ?: 0L
            val pausedUptime = exoService?.livePausedUptimeMs
            val isPaused = exoService?.isLivePaused == true
            if (pendingDvr) {
                val target = pendingDvrPosition.coerceIn(0L, uptime)
                progressBar.setPosition(target)
                progressBar.setBufferedPosition(target)
                position.text = DateUtils.formatElapsedTime(target / 1000)
            } else if (shiftBehindMs >= 4_500L || pausedUptime != null || isPaused) {
                val target = if (shiftBehindMs > 0L) {
                    (uptime - shiftBehindMs).coerceIn(0L, uptime)
                } else {
                    (pausedUptime ?: uptime).coerceIn(0L, uptime)
                }
                val behindMs = (uptime - target).coerceAtLeast(0L)
                val showTimeShiftUi = behindMs >= 4_500L || pausedUptime != null || isPaused
                progressBar.setPosition(target)
                progressBar.setBufferedPosition(uptime)
                position.text = if (!showTimeShiftUi) getString(R.string.tv_live) else DateUtils.formatElapsedTime(target / 1000)
                tvGoLive?.let { button ->
                    if (showTimeShiftUi) {
                        styleTvGoLiveButton(button)
                        button.visibility = View.VISIBLE
                        button.setOnClickListener {
                            hideTooltip()
                            seekToLivePosition()
                            updateTvLiveTimeline()
                            showController()
                        }
                    } else if (getTvDvrStream() == null) {
                        val hadButtonFocus = button.hasFocus()
                        button.visibility = View.GONE
                        if (hadButtonFocus && !(playPause.isVisible && playPause.requestFocus())) {
                            focusAfterHiddenControl()
                        }
                    }
                }
            } else {
                progressBar.setPosition(uptime)
                progressBar.setBufferedPosition(uptime)
                position.text = getString(R.string.tv_live)
                tvGoLive?.let { button ->
                    if (getTvDvrStream() == null) {
                        val hadButtonFocus = button.hasFocus()
                        button.visibility = View.GONE
                        if (hadButtonFocus && !(playPause.isVisible && playPause.requestFocus())) {
                            focusAfterHiddenControl()
                        }
                    }
                }
            }
            duration.text = DateUtils.formatElapsedTime(uptime / 1000)
            syncUptimeBadgeElapsedMs(uptime)
            maybeScheduleTvDvrPrefetch()
        }
    }

    private var expArchiveLookupJob: Job? = null
    private var expArchiveLookupKey: String? = null
    private var expArchiveLookupFailedAt = 0L

    private fun syncExperimentalChatState() {
        if (!isExperimentalVodActive()) {
            if (chatFragment?.isHybridReplayActive() == true) {
                chatFragment?.stopHybridReplay()
            }
            return
        }
        val exoService = playbackService as? ExoPlayerService ?: return
        val vodEdge = exoService.getExperimentalLiveEdgeMs().takeIf { it > 0L } ?: return
        val displayEdge = exoService.getExperimentalDisplayEdgeMs().takeIf { it > 0L } ?: vodEdge
        val isPaused = exoService.isLivePaused
        val currentPos = (getCurrentPosition() ?: vodEdge).coerceIn(0L, displayEdge)
        val behindMs = (vodEdge - currentPos).coerceAtLeast(0L)
        val displayBehindMs = (displayEdge - currentPos).coerceAtLeast(0L)
        val userRewound = exoService.experimentalVodUserRewound
        val atAdFreeEdge = if (isPaused) {
            false
        } else if (userRewound) {
            behindMs <= 4_500L && displayBehindMs <= 4_500L
        } else {
            behindMs <= 32_000L
        }
        if (atAdFreeEdge) {
            exoService.clearExperimentalRewoundIfAtEdge()
        }
        syncExperimentalChatReplay(!atAdFreeEdge && ((!isPaused && userRewound && behindMs > 15_000L) || behindMs > 45_000L))
    }

    private fun syncExperimentalChatReplay(wantReplay: Boolean) {
        val chat = chatFragment ?: return
        if (!wantReplay) {
            expArchiveLookupJob?.cancel()
            expArchiveLookupJob = null
            if (chat.isHybridReplayActive()) {
                chat.stopHybridReplay()
            }
            return
        }
        val service = playbackService ?: return
        val main = activity as? MainActivity ?: return
        val login = service.channelLogin ?: return
        val createdAt = service.createdAt ?: return
        val key = "$login|$createdAt"
        if (expArchiveLookupKey != key) {
            expArchiveLookupKey = key
            expArchiveLookupFailedAt = 0L
        }
        val cachedVideoId = main.dvrChatVideoId
        if (!cachedVideoId.isNullOrBlank()) {
            if (!chat.isHybridReplayActive()) {
                chat.startHybridReplay(cachedVideoId, createdAt)
                getCurrentPosition()?.let { chat.updatePosition(it) }
            }
            return
        }
        if (expArchiveLookupJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (expArchiveLookupFailedAt > 0L && now - expArchiveLookupFailedAt < 30_000L) return
        expArchiveLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val found = withTimeoutOrNull(4_000L) {
                viewModel.findArchiveVideoId(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext()),
                    service.channelId,
                    login,
                    createdAt,
                )
            }
            if (!found.isNullOrBlank()) {
                main.dvrChatVideoId = found
                com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
                    context = main,
                    channelId = service.channelId,
                    channelLogin = login,
                    streamCreatedAt = service.createdAt,
                    archiveVideoId = found,
                )
                if (view != null && isExperimentalVodActive()) {
                    val exo = playbackService as? ExoPlayerService
                    val edge = exo?.getExperimentalLiveEdgeMs()?.takeIf { it > 0L }
                    val pos = getCurrentPosition()
                    if (edge != null && pos != null && edge - pos > 8_000L) {
                        chatFragment?.startHybridReplay(found, service.createdAt)
                        chatFragment?.updatePosition(pos)
                    }
                }
            } else {
                expArchiveLookupFailedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    protected fun startTvDvrFromPause(position: Long): Boolean {
        if (!isAdded || _binding == null || playbackService?.type != BasePlaybackService.STREAM) {
            return false
        }
        pendingDvrResumeFromPause = true
        startTvDvr(position)
        updateTvLiveTimeline()
        return true
    }

    private fun commitMobileLiveSeek(newPosition: Long) {
        val expVod = isExperimentalVodActive()
        val duration = if (expVod) {
            (playbackService as? ExoPlayerService)?.getExperimentalDisplayEdgeMs()?.takeIf { it > 0L }
        } else {
            getStreamUptime()?.takeIf { it > 0L }
        } ?: return
        if (expVod) {
            if (newPosition >= duration - 8_000L) {
                seekToLivePosition()
            } else {
                seek(newPosition.coerceIn(0L, duration))
            }
            updateTvLiveTimeline()
            showController()
            return
        }
        val wasShifted = ((playbackService as? ExoPlayerService)?.getLiveTimeShiftBehindMs() ?: 0L) > 0L
        if (newPosition < duration - LIVE_EDGE_MS) {
            startTvDvr(newPosition)
            updateTvLiveTimeline()
            showController()
        } else {
            if (wasShifted && newPosition >= duration - 8_000L) {
                seekToLivePosition()
            }
            updateTvLiveTimeline()
            showController()
        }
    }

    private fun maybeScheduleTvDvrPrefetch() {
        val ctx = context ?: return
        val service = playbackService ?: return
        val dvrEnabled = isTv || ctx.prefs().getBoolean(C.MOBILE_LIVE_DVR, true)
        if (!dvrEnabled || !isMaximized || service.type != BasePlaybackService.STREAM || isExperimentalVodActive()) {
            return
        }
        val qualities = collectUnlistedVideoQualities()
        if (qualities.isNullOrEmpty()) return
        viewModel.prefetchDvrForLive(
            networkLibrary = ctx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(ctx),
            qualities = qualities,
            channelId = service.channelId,
            streamId = service.streamId,
            channelLogin = service.channelLogin,
            streamCreatedAt = service.createdAt,
        )
    }

    private fun collectUnlistedVideoQualities(): List<VideoQuality>? {
        val unavailableQualities = getUnavailableQualities()
        return if (unavailableQualities.isNotEmpty()) {
            mutableListOf<VideoQuality>().apply {
                playbackService?.qualities?.filter { !it.url.isNullOrBlank() }?.let { addAll(it) }
                addAll(unavailableQualities)
            }
                .sortedByDescending { it.bitrate }
                .sortedByDescending { it.frameRate }
                .sortedByDescending { it.resolution }
                .toMutableList().apply {
                    find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                        remove(audio)
                        add(audio)
                    }
                }
        } else {
            playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
        }
    }

    private fun startTvDvr(position: Long) {
        val service = playbackService
        val mainActivity = activity as? MainActivity
        mainActivity?.dvrStream = Stream(
            id = service?.streamId,
            channelId = service?.channelId,
            channelLogin = service?.channelLogin,
            channelName = service?.channelName,
            gameId = service?.gameId,
            gameSlug = service?.gameSlug,
            gameName = service?.gameName,
            title = service?.title,
            createdAt = service?.createdAt,
            viewerCount = service?.viewerCount,
        )
        val prefetched = viewModel.getPrefetchedDvr(service?.channelLogin, service?.streamId, service?.createdAt)
        if (prefetched != null && mainActivity != null) {
            pendingDvr = false
            pendingDvrResumeFromPause = false
            val offset = position.coerceAtLeast(0L)
            val video = Video(
                channelId = service?.channelId,
                channelLogin = service?.channelLogin,
                channelName = service?.channelName,
                gameId = service?.gameId,
                gameSlug = service?.gameSlug,
                gameName = service?.gameName,
                title = service?.title,
                createdAt = service?.createdAt,
            )
            if (!prefetched.archiveVideoId.isNullOrBlank()) {
                mainActivity.dvrChatVideoId = prefetched.archiveVideoId
                com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
                    context = mainActivity,
                    channelId = service?.channelId,
                    channelLogin = service?.channelLogin,
                    streamCreatedAt = service?.createdAt,
                    archiveVideoId = prefetched.archiveVideoId,
                )
            }
            mainActivity.startVideo(video, offset, qualities = prefetched.qualitiesJson)
            return
        }
        pendingDvr = true
        pendingDvrPosition = position
        com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.tv_looking_for_vod, Toast.LENGTH_SHORT).show()
        findUnlistedVideo()
    }

    /**
     * D-pad seeking on the time bar: a short press moves 10 seconds (a minute back from the live), holding the key
     * ramps up smoothly in predictable human-friendly steps, and releasing leaves a brief 700ms grace window (like Kodi)
     * so the user can tap Left/Right to fine-tune the exact skip amount before committing a single seek.
     */
    private fun onTimeBarKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false
        }
        val isLive = isTvLiveTimeline()
        val expVod = isLive && isExperimentalVodActive()
        val duration = when {
            expVod -> (playbackService as? ExoPlayerService)?.getExperimentalDisplayEdgeMs()?.takeIf { it > 0L }
            isLive -> getStreamUptime()?.takeIf { it > 0 }
            else -> getTotalDuration()?.takeIf { it > 0 }
        } ?: return false
        with(binding.playerControls) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    root.removeCallbacks(tvScrubCommitRunnable)
                    if (tvScrubPosition < 0) {
                        val shiftBehindMs = if (isLive && !expVod) {
                            (playbackService as? ExoPlayerService)?.getLiveTimeShiftBehindMs() ?: 0L
                        } else {
                            0L
                        }
                        val initialPos = when {
                            expVod -> (getCurrentPosition() ?: duration).coerceIn(0L, duration)
                            isLive -> (duration - shiftBehindMs).coerceIn(0L, duration)
                            else -> (getCurrentPosition() ?: 0L).coerceIn(0L, duration)
                        }
                        tvScrubOriginPosition = initialPos
                        tvScrubPosition = initialPos
                        progressBar.isPressed = true
                    }
                    tvScrubDuration = duration
                    val stepCap = maxOf(30_000L, duration / 30L)
                    val step = when {
                        event.repeatCount == 0 -> 10_000L
                        event.repeatCount < 6 -> 15_000L
                        event.repeatCount < 14 -> 30_000L
                        event.repeatCount < 26 -> 60_000L
                        event.repeatCount < 42 -> 120_000L
                        else -> 180_000L
                    }.coerceAtMost(stepCap).coerceAtLeast(10_000L).let {
                        // on the live: the first step back goes a minute back, right into the VOD, small steps never get past its edge
                        if (isLive && !expVod && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && tvScrubPosition >= duration - LIVE_EDGE_MS) maxOf(it, LIVE_FIRST_STEP_MS) else it
                    }
                    tvScrubPosition = (tvScrubPosition + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step).coerceIn(0, duration)
                    progressBar.setDuration(duration)
                    progressBar.setPosition(tvScrubPosition)
                    val isAtExpLiveEdge = expVod && tvScrubPosition >= duration - 8_000L
                    val targetLabel = if (isAtExpLiveEdge) getString(R.string.tv_live) else null
                    position.text = targetLabel ?: DateUtils.formatElapsedTime(tvScrubPosition / 1000)
                    val originPos = if (tvScrubOriginPosition >= 0L) tvScrubOriginPosition else tvScrubPosition
                    (seekPreview ?: createSeekPreview()).apply {
                        setStartEpochMs(resolveSeekStartEpochMs())
                        (playbackService as? ExoPlayerService)?.getExperimentalSeekPreviewUrl()?.let { expUrl ->
                            updateVideoFallback(expUrl)
                        }
                    }.show(
                        position = tvScrubPosition,
                        duration = duration,
                        deltaMs = tvScrubPosition - originPos,
                        targetLabel = targetLabel,
                    )
                    root.removeCallbacks(controllerHideAction)
                }
                KeyEvent.ACTION_UP -> {
                    if (tvScrubPosition >= 0) {
                        seekPreview?.onScrubGestureIdle()
                        root.removeCallbacks(tvScrubCommitRunnable)
                        root.postDelayed(tvScrubCommitRunnable, 700L)
                    }
                }
            }
        }
        return true
    }

    private fun commitTvScrub() {
        val controls = _binding?.playerControls ?: return
        controls.root.removeCallbacks(tvScrubCommitRunnable)
        val newPosition = tvScrubPosition
        if (newPosition < 0L) return
        val isLive = isTvLiveTimeline()
        val expVod = isLive && isExperimentalVodActive()
        val duration = tvScrubDuration.takeIf { it > 0L } ?: when {
            expVod -> (playbackService as? ExoPlayerService)?.getExperimentalDisplayEdgeMs()?.takeIf { it > 0L }
            isLive -> getStreamUptime()?.takeIf { it > 0 }
            else -> getTotalDuration()?.takeIf { it > 0 }
        } ?: 0L
        tvScrubPosition = -1L
        tvScrubOriginPosition = -1L
        tvScrubDuration = 0L
        controls.progressBar.isPressed = false
        seekPreview?.hideLater(900L)
        if (duration <= 0L) return
        val dvrStream = getTvDvrStream()
        when {
            expVod -> {
                if (newPosition >= duration - 8_000L) {
                    seekToLivePosition()
                } else {
                    seek(newPosition.coerceIn(0L, duration))
                }
                updateTvLiveTimeline()
                showController()
            }
            isLive -> {
                val wasShifted = ((playbackService as? ExoPlayerService)?.getLiveTimeShiftBehindMs() ?: 0L) > 0L
                if (newPosition < duration - LIVE_EDGE_MS) {
                    startTvDvr(newPosition)
                    updateTvLiveTimeline()
                    showController()
                } else {
                    if (wasShifted && newPosition >= duration - 8_000L) {
                        seekToLivePosition()
                    }
                    updateTvLiveTimeline()
                    showController()
                }
            }
            dvrStream != null && newPosition >= duration - LIVE_EDGE_MS -> (activity as? MainActivity)?.startStream(dvrStream)
            else -> {
                seek(newPosition)
                showController()
            }
        }
    }

    private fun cancelTvScrub(): Boolean {
        val controls = _binding?.playerControls ?: return false
        if (tvScrubPosition < 0L) return false
        controls.root.removeCallbacks(tvScrubCommitRunnable)
        tvScrubPosition = -1L
        tvScrubOriginPosition = -1L
        tvScrubDuration = 0L
        controls.progressBar.isPressed = false
        seekPreview?.hide()
        updateProgress()
        if (isTvLiveTimeline()) {
            updateTvLiveTimeline()
        }
        showController()
        return true
    }

    private var tvStatsView: TextView? = null
    private var seekPreview: TvSeekPreview? = null
    private var tvSeekStartEpochMs: Long? = null

    private fun resolveSeekStartEpochMs(): Long? {
        tvSeekStartEpochMs?.takeIf { it > 0L }?.let { return it }
        if (playbackService?.clipId != null || playbackService?.type == BasePlaybackService.OFFLINE_VIDEO) {
            return null
        }
        val raw = playbackService?.createdAt
            ?: getTvDvrStream()?.createdAt
            ?: playbackService?.videoCreatedAt
        return raw?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }?.takeIf { it > 0L }
    }

    /** Storyboard description of a video: the seekPreviewsURL of the video, else next to its animated preview. */
    private suspend fun getStoryboardUrl(videoId: String, animatedPreviewUrl: String?): String? {
        val seekPreviews = try {
            val video = (requireContext().applicationContext as com.github.andreyasadchy.xtra.XtraApp).xtraModule.graphQLRepository.loadQueryVideo(
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                videoId
            ).data?.video
            video?.createdAt?.toString()?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }?.takeIf { it > 0L }?.let { startMs ->
                tvSeekStartEpochMs = startMs
                seekPreview?.setStartEpochMs(startMs)
            }
            video?.let { it.seekPreviewsURL ?: it.animatedPreviewURL?.let { preview -> "${preview.substringBeforeLast("/")}/$videoId-info.json" } }
        } catch (e: Exception) {
            null
        }
        return seekPreviews ?: animatedPreviewUrl?.let { "${it.substringBeforeLast("/")}/$videoId-info.json" }
    }

    /** Live: thumbnails come from the storyboards of the VOD being recorded (or Experimental Adblock's ongoing DVR playlist). */
    private fun setupLiveSeekPreview() {
        val login = playbackService?.channelLogin ?: return
        val preview = createSeekPreview()
        viewLifecycleOwner.lifecycleScope.launch {
            val url = getLiveStoryboardUrl(login, playbackService?.createdAt)
            val expPlaylist = (playbackService as? ExoPlayerService)?.getExperimentalSeekPreviewUrl()
            if ((url != null || expPlaylist != null) && view != null) {
                preview.load(url, refresh = true, videoPlaylist = expPlaylist)
            }
        }
    }

    private fun createSeekPreview(): TvSeekPreview {
        return TvSeekPreview(
            viewLifecycleOwner.lifecycleScope,
            (requireContext().applicationContext as com.github.andreyasadchy.xtra.XtraApp).xtraModule.okHttpClient.value,
            binding.playerControls.root,
            binding.playerControls.progressBar,
        ).also {
            it.setChapters(viewModel.gamesList.value)
            it.setStartEpochMs(resolveSeekStartEpochMs())
            seekPreview = it
        }
    }

    /** Storyboards of the VOD being recorded of a live stream: the last archive of the channel, started with the stream. */
    private suspend fun getLiveStoryboardUrl(login: String, streamCreatedAt: String?): String? {
        val video = try {
            (requireContext().applicationContext as com.github.andreyasadchy.xtra.XtraApp).xtraModule.graphQLRepository.loadQueryUserVideos(
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                null,
                login,
                com.github.andreyasadchy.xtra.graphql.type.VideoSort.TIME,
                listOf(com.github.andreyasadchy.xtra.graphql.type.BroadcastType.ARCHIVE),
                1,
                null
            ).data?.user?.videos?.edges?.firstOrNull()?.node
        } catch (e: Exception) {
            null
        }
        val streamStart = streamCreatedAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        val videoStart = video?.createdAt?.toString()?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("XtraTv", "seekPreview live video=${video?.id} videoStart=$videoStart streamStart=$streamStart")
        }
        // only the VOD of the current broadcast
        if (video?.id == null || view == null || (streamStart != null && videoStart != null && kotlin.math.abs(videoStart - streamStart) > 15 * 60_000L)) {
            streamStart?.takeIf { it > 0L }?.let {
                tvSeekStartEpochMs = it
                seekPreview?.setStartEpochMs(it)
            }
            return null
        }
        (videoStart ?: streamStart)?.takeIf { it > 0L }?.let {
            tvSeekStartEpochMs = it
            seekPreview?.setStartEpochMs(it)
        }
        (activity as? MainActivity)?.dvrChatVideoId = video.id
        context?.let { ctx ->
            com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
                context = ctx,
                channelId = playbackService?.channelId,
                channelLogin = playbackService?.channelLogin,
                streamCreatedAt = streamCreatedAt,
                archiveVideoId = video.id,
            )
        }
        val url = getStoryboardUrl(video.id, video.animatedPreviewURL)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("XtraTv", "seekPreview live storyboard=$url")
        }
        return url
    }

    /** TV: storyboard thumbnails above the timeline of VODs. */
    private fun setupSeekPreview() {
        if (!isTv || seekPreview != null) {
            return
        }
        if (playbackService?.type == BasePlaybackService.STREAM) {
            setupLiveSeekPreview()
            return
        }
        if (playbackService?.type != BasePlaybackService.VIDEO) {
            return
        }
        val videoId = playbackService?.videoId?.takeIf { it.isNotBlank() }
        val dvrStream = getTvDvrStream()
        // the VOD of a live opened from its timeline is found by its address, without its id: it is looked up like on the live
        val login = dvrStream?.channelLogin ?: playbackService?.channelLogin
        if (videoId == null && (dvrStream == null || login == null)) {
            return
        }
        val preview = createSeekPreview()
        viewLifecycleOwner.lifecycleScope.launch {
            val url = if (videoId != null) {
                getStoryboardUrl(videoId, playbackService?.videoAnimatedPreviewURL)
            } else {
                getLiveStoryboardUrl(login!!, dvrStream?.createdAt)
            }
            // the lowest quality of the VOD being played, for its frames when there is no storyboard
            val playlist = playbackService?.qualities?.filter { !it.url.isNullOrBlank() && it.name?.startsWith("audio", true) != true }
                ?.minByOrNull { it.resolution ?: Int.MAX_VALUE }?.url
            if ((url != null || playlist != null) && view != null) {
                // a VOD of a stream still live keeps growing
                preview.load(url, refresh = dvrStream != null || url == null, videoPlaylist = playlist)
            }
        }
    }

    private fun tvChatModePosKey(mode: Int): String = when (mode) {
        1 -> C.TV_CHAT_POS_SIDE
        2 -> C.TV_CHAT_POS_BAND
        else -> C.TV_CHAT_POS_OVERLAY
    }

    private fun getTvChatPosForMode(prefs: android.content.SharedPreferences, mode: Int): Int = when (mode) {
        1 -> if (prefs.contains(C.TV_CHAT_POS_SIDE)) {
            prefs.getInt(C.TV_CHAT_POS_SIDE, 0)
        } else {
            val raw = prefs.getInt(C.TV_CHAT_POSITION, 0)
            if (raw == 2 || raw == 3 || raw == 5) 2 else 0
        }
        2 -> if (prefs.contains(C.TV_CHAT_POS_BAND)) {
            prefs.getInt(C.TV_CHAT_POS_BAND, 0)
        } else {
            0
        }
        else -> if (prefs.contains(C.TV_CHAT_POS_OVERLAY)) {
            prefs.getInt(C.TV_CHAT_POS_OVERLAY, 0).coerceIn(0, 7)
        } else {
            prefs.getInt(C.TV_CHAT_POSITION, 0).coerceIn(0, 7)
        }
    }

    private fun saveTvChatPosForMode(prefs: android.content.SharedPreferences, mode: Int, pos: Int) {
        prefs.edit {
            putInt(tvChatModePosKey(mode), pos)
            putInt(C.TV_CHAT_POSITION, pos)
        }
    }

    private var tvChatFreeMoving = false
    private var tvChatFreePrevMode = 0
    private var tvChatFreePrevPos = 0
    private var tvChatFreePrevActive = false
    private var tvChatFreePrevX = 0.7f
    private var tvChatFreePrevY = 0.5f
    private var tvChatFreeCenterDown = false
    private var tvChatFreeBackDown = false
    private var tvChatFreeHintView: TextView? = null

    fun isTvChatFreeMoving(): Boolean = isTv && isMaximized && tvChatFreeMoving

    fun hideControlsForFreeMove() {
        if (_binding != null) {
            hideController(force = true)
        }
    }

    fun startTvChatFreeMove() {
        val b = _binding ?: return
        if (!isTv || !isMaximized) return
        val prefs = requireContext().prefs()
        if (!isChatOpen) {
            showChat()
        }
        tvChatFreePrevMode = prefs.getInt(C.TV_CHAT_MODE, 0)
        tvChatFreePrevPos = getTvChatPosForMode(prefs, tvChatFreePrevMode)
        tvChatFreePrevActive = prefs.getBoolean(C.TV_CHAT_FREE_ACTIVE, false)
        tvChatFreePrevX = prefs.getFloat(C.TV_CHAT_FREE_X, 0.7f)
        tvChatFreePrevY = prefs.getFloat(C.TV_CHAT_FREE_Y, 0.5f)

        val metrics = resources.displayMetrics
        val screenW = b.root.width.takeIf { it > 0 } ?: metrics.widthPixels
        val screenH = b.root.height.takeIf { it > 0 } ?: metrics.heightPixels
        val chatW = (screenW * prefs.getInt(C.TV_CHAT_WIDTH, 30) / 100f).toInt()
        val chatH = (screenH * prefs.getInt(C.TV_CHAT_HEIGHT, 50) / 100f).toInt()
        val maxLeft = (screenW - chatW).coerceAtLeast(1)
        val maxTop = (screenH - chatH).coerceAtLeast(1)

        val (initX, initY) = if (tvChatFreePrevMode == 0 && b.chatLayout.width > 0 && b.root.width > 0) {
            (b.chatLayout.left.coerceIn(0, maxLeft).toFloat() / maxLeft.toFloat()) to
                (b.chatLayout.top.coerceIn(0, maxTop).toFloat() / maxTop.toFloat())
        } else if (tvChatFreePrevActive) {
            tvChatFreePrevX.coerceIn(0f, 1f) to tvChatFreePrevY.coerceIn(0f, 1f)
        } else {
            val left = tvChatFreePrevPos == 2 || tvChatFreePrevPos == 3 || tvChatFreePrevPos == 5
            val top = tvChatFreePrevPos == 1 || tvChatFreePrevPos == 3 || tvChatFreePrevPos == 6
            val hCenter = tvChatFreePrevPos == 6 || tvChatFreePrevPos == 7
            val vCenter = tvChatFreePrevPos == 4 || tvChatFreePrevPos == 5
            val rx = when {
                hCenter -> 0.5f
                left -> 0.02f
                else -> 0.98f
            }
            val ry = when {
                vCenter -> 0.5f
                top -> 0.02f
                else -> 0.98f
            }
            rx to ry
        }

        prefs.edit {
            putInt(C.TV_CHAT_MODE, 0)
            putBoolean(C.TV_CHAT_FREE_ACTIVE, true)
            putFloat(C.TV_CHAT_FREE_X, initX.coerceIn(0f, 1f))
            putFloat(C.TV_CHAT_FREE_Y, initY.coerceIn(0f, 1f))
        }
        hideControlsForFreeMove()
        tvChatFreeMoving = true
        tvChatFreeCenterDown = false
        tvChatFreeBackDown = false
        applyTvChatLayout()
        showTvChatFreeMoveHint()
    }

    private fun showTvChatFreeMoveHint() {
        val b = _binding ?: return
        val density = resources.displayMetrics.density
        val vertical = resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        val pill = tvChatFreeHintView ?: TextView(requireContext()).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6141318.toInt())
                cornerRadius = 999 * density
                setStroke((2 * density).toInt(), com.github.andreyasadchy.xtra.ui.tv.TvAccent.color)
            }
            val padH = (16 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 28 * density
            isFocusable = false
            b.slidingLayout.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    topMargin = vertical
                }
            )
            tvChatFreeHintView = this
        }
        pill.text = getString(R.string.tv_free_move_hint)
        pill.visibility = View.VISIBLE
    }

    private fun exitTvChatFreeMove(restorePrevious: Boolean) {
        if (!tvChatFreeMoving && tvChatFreeHintView == null) return
        if (restorePrevious && context != null) {
            requireContext().prefs().edit {
                putInt(C.TV_CHAT_MODE, tvChatFreePrevMode)
                putInt(C.TV_CHAT_POSITION, tvChatFreePrevPos)
                putBoolean(C.TV_CHAT_FREE_ACTIVE, tvChatFreePrevActive)
                putFloat(C.TV_CHAT_FREE_X, tvChatFreePrevX)
                putFloat(C.TV_CHAT_FREE_Y, tvChatFreePrevY)
            }
        }
        tvChatFreeMoving = false
        tvChatFreeCenterDown = false
        tvChatFreeBackDown = false
        tvChatFreeHintView?.let { _binding?.slidingLayout?.removeView(it) }
        tvChatFreeHintView = null
        if (_binding != null) {
            binding.chatLayout.foreground = null
            applyTvChatLayout()
        }
    }

    fun onTvChatFreeMoveKey(event: KeyEvent): Boolean {
        if (!isTvChatFreeMoving()) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    nudgeTvChatFreeMove(event.keyCode, event.repeatCount)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    tvChatFreeCenterDown = true
                } else if (event.action == KeyEvent.ACTION_UP && tvChatFreeCenterDown) {
                    exitTvChatFreeMove(restorePrevious = false)
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.tv_free_move_saved, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    tvChatFreeBackDown = true
                } else if (event.action == KeyEvent.ACTION_UP && tvChatFreeBackDown) {
                    exitTvChatFreeMove(restorePrevious = true)
                }
                return true
            }
            else -> return true
        }
    }

    private var tvChatScrolling = false
    private var tvChatScrollBackDown = false
    private var tvChatScrollCenterDown = false
    private var tvChatScrollExitKeyDown = false
    private var tvChatScrollHintView: TextView? = null

    fun isTvChatScrolling(): Boolean = isTv && isMaximized && tvChatScrolling

    fun enterTvChatScrollMode() {
        val ctx = context ?: return
        val b = _binding ?: return
        if (!isTv || !isMaximized) return
        if (ctx.prefs().getBoolean(C.CHAT_DISABLE, false) || playbackService?.type == BasePlaybackService.OFFLINE_VIDEO) {
            return
        }
        if (tvChatFreeMoving) {
            exitTvChatFreeMove(restorePrevious = false)
        }
        if (!isChatOpen) {
            showChat()
        }
        hideControlsForFreeMove()
        tvChatScrolling = true
        tvChatScrollBackDown = false
        tvChatScrollCenterDown = false
        tvChatScrollExitKeyDown = false
        val density = resources.displayMetrics.density
        b.chatLayout.foreground = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((2.5f * density).toInt(), com.github.andreyasadchy.xtra.ui.tv.TvAccent.color)
            cornerRadius = 10f * density
        }
        chatFragment?.setTvScrollPaused(true)
        updateTvChatScrollHint(paused = true)
    }

    fun exitTvChatScrollMode() {
        if (!tvChatScrolling && tvChatScrollHintView == null) return
        tvChatScrolling = false
        tvChatScrollBackDown = false
        tvChatScrollCenterDown = false
        tvChatScrollExitKeyDown = false
        tvChatScrollHintView?.let { _binding?.slidingLayout?.removeView(it) }
        tvChatScrollHintView = null
        if (_binding != null && !tvChatFreeMoving) {
            binding.chatLayout.foreground = null
        }
        chatFragment?.setTvScrollPaused(false)
    }

    private fun updateTvChatScrollHint(paused: Boolean) {
        val b = _binding ?: return
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val vertical = resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        val pill = tvChatScrollHintView ?: TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6141318.toInt())
                cornerRadius = 999 * density
                setStroke((2 * density).toInt(), com.github.andreyasadchy.xtra.ui.tv.TvAccent.color)
            }
            val padH = (16 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 28 * density
            isFocusable = false
            b.slidingLayout.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    topMargin = vertical
                }
            )
            tvChatScrollHintView = this
        }
        pill.text = getString(
            if (paused) R.string.tv_chat_scroll_hint_paused else R.string.tv_chat_scroll_hint_live
        )
        pill.visibility = View.VISIBLE
    }

    fun onTvChatScrollKey(event: KeyEvent): Boolean {
        if (!isTvChatScrolling()) return false
        val ctx = context ?: return false
        val customAction = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.customShortActionForKey(ctx, event.keyCode)
        if (customAction == com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.ACTION_SCROLL_CHAT &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_UP &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_PAGE_UP &&
            event.keyCode != KeyEvent.KEYCODE_PAGE_DOWN
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                tvChatScrollExitKeyDown = true
            } else if (event.action == KeyEvent.ACTION_UP && tvChatScrollExitKeyDown) {
                exitTvChatScrollMode()
            }
            return true
        }
        val density = resources.displayMetrics.density
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val stepDp = when {
                        event.repeatCount <= 0 -> 48f
                        event.repeatCount <= 4 -> 72f
                        event.repeatCount <= 12 -> 104f
                        else -> 140f
                    }
                    val stepPx = maxOf(1, kotlin.math.round(stepDp * density).toInt())
                    val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -stepPx else stepPx
                    val atBottom = chatFragment?.scrollTvChatBy(delta) ?: true
                    updateTvChatScrollHint(paused = !atBottom)
                }
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val vh = chatFragment?.tvChatViewportHeight()?.takeIf { it > 0 }
                        ?: kotlin.math.round(240f * density).toInt()
                    val stepPx = maxOf(1, (vh * 0.75f).toInt())
                    val up = event.keyCode == KeyEvent.KEYCODE_PAGE_UP || event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                    val atBottom = chatFragment?.scrollTvChatBy(if (up) -stepPx else stepPx) ?: true
                    updateTvChatScrollHint(paused = !atBottom)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    tvChatScrollCenterDown = true
                } else if (event.action == KeyEvent.ACTION_UP && tvChatScrollCenterDown) {
                    tvChatScrollCenterDown = false
                    if (chatFragment?.isTvChatAtBottom() == false) {
                        chatFragment?.scrollTvChatToBottom()
                        updateTvChatScrollHint(paused = false)
                    } else {
                        exitTvChatScrollMode()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    tvChatScrollBackDown = true
                } else if (event.action == KeyEvent.ACTION_UP && tvChatScrollBackDown) {
                    exitTvChatScrollMode()
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            else -> return true
        }
    }

    private fun nudgeTvChatFreeMove(keyCode: Int, repeatCount: Int) {
        val b = _binding ?: return
        val prefs = requireContext().prefs()
        val metrics = resources.displayMetrics
        val density = metrics.density
        val stepDp = when {
            repeatCount <= 0 -> 4f
            repeatCount <= 4 -> 10f
            repeatCount <= 12 -> 20f
            else -> 32f
        }
        val stepPx = maxOf(1, kotlin.math.round(stepDp * density).toInt())
        val screenW = b.root.width.takeIf { it > 0 } ?: metrics.widthPixels
        val screenH = b.root.height.takeIf { it > 0 } ?: metrics.heightPixels
        val chatW = (screenW * prefs.getInt(C.TV_CHAT_WIDTH, 30) / 100f).toInt()
        val chatH = (screenH * prefs.getInt(C.TV_CHAT_HEIGHT, 50) / 100f).toInt()
        val maxLeft = (screenW - chatW).coerceAtLeast(1)
        val maxTop = (screenH - chatH).coerceAtLeast(1)

        val curLeft = kotlin.math.round(prefs.getFloat(C.TV_CHAT_FREE_X, 0.7f).coerceIn(0f, 1f) * maxLeft).toInt().coerceIn(0, maxLeft)
        val curTop = kotlin.math.round(prefs.getFloat(C.TV_CHAT_FREE_Y, 0.5f).coerceIn(0f, 1f) * maxTop).toInt().coerceIn(0, maxTop)
        val newLeft = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> (curLeft - stepPx).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> (curLeft + stepPx).coerceAtMost(maxLeft)
            else -> curLeft
        }
        val newTop = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> (curTop - stepPx).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> (curTop + stepPx).coerceAtMost(maxTop)
            else -> curTop
        }
        prefs.edit {
            putFloat(C.TV_CHAT_FREE_X, (newLeft.toFloat() / maxLeft.toFloat()).coerceIn(0f, 1f))
            putFloat(C.TV_CHAT_FREE_Y, (newTop.toFloat() / maxTop.toFloat()).coerceIn(0f, 1f))
        }
        applyTvChatLayout()
    }

    /** TV chat overlay: size, corner and transparency from the chat layout panel, flush with the screen edges by default. */
    private fun applyTvChatLayout() {
        with(binding) {
            val prefs = requireContext().prefs()
            val metrics = resources.displayMetrics
            val mode = prefs.getInt(C.TV_CHAT_MODE, 0)
            val freeActive = mode == 0 && prefs.getBoolean(C.TV_CHAT_FREE_ACTIVE, false)
            val position = getTvChatPosForMode(prefs, mode)
            val margin = if (mode == 0 && !freeActive) (prefs.getInt(C.TV_CHAT_MARGIN, 0) * metrics.density).toInt() else 0
            val screenWidth = root.width.takeIf { it > 0 } ?: metrics.widthPixels
            val screenHeight = root.height.takeIf { it > 0 } ?: metrics.heightPixels
            val chatWidth = (screenWidth * prefs.getInt(C.TV_CHAT_WIDTH, 30) / 100f).toInt()
            val chatHeight = (screenHeight * prefs.getInt(C.TV_CHAT_HEIGHT, 50) / 100f).toInt()
            // corners 0..3, middle of a side 4..7 (right, left, top, bottom)
            val left = position == 2 || position == 3 || position == 5
            val top = position == 1 || position == 3 || position == 6
            var freeRounded = false
            when {
                !isChatOpen -> {
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.NO_GRAVITY
                        marginEnd = 0
                    }
                }
                // next to the stream: the picture gets smaller and the chat takes a column
                mode == 1 -> {
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = screenWidth - chatWidth
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = if (left) Gravity.END else Gravity.START
                        marginEnd = 0
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidth
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = if (left) Gravity.START else Gravity.END
                        setMargins(0, 0, 0, 0)
                        marginStart = 0
                        marginEnd = 0
                    }
                }
                // under (or above) the stream: the chat takes a band the whole width of the screen
                mode == 2 -> {
                    // as many lines of chat as chosen: the height of a line follows the size of the text and of the emotes
                    val sizeModifier = prefs.getInt(C.CHAT_SIZE_MODIFIER, 100) / 100f
                    val textHeight = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, (prefs.getString(C.CHAT_TEXT_SIZE, "14")?.toFloatOrNull() ?: 14f) * sizeModifier, metrics
                    ) * 1.35f
                    val emoteHeight = (prefs.getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * sizeModifier * metrics.density
                    val lineHeight = maxOf(textHeight, emoteHeight) + 2 * metrics.density
                    val lines = prefs.getInt(C.TV_CHAT_LINES, 3)
                    val bandHeight = (lineHeight * lines + 8 * metrics.density).toInt().coerceAtMost((screenHeight * 0.6f).toInt())
                    // option: the band over the stream, the picture stays full screen
                    val overStream = prefs.getBoolean(C.TV_CHAT_BAND_OVERLAY, false)
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = if (overStream) ViewGroup.LayoutParams.MATCH_PARENT else screenHeight - bandHeight
                        gravity = if (overStream) Gravity.NO_GRAVITY else if (top) Gravity.BOTTOM else Gravity.TOP
                        marginEnd = 0
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = bandHeight
                        gravity = if (top) Gravity.TOP else Gravity.BOTTOM
                        setMargins(0, 0, 0, 0)
                        marginStart = 0
                        marginEnd = 0
                    }
                }
                freeActive -> {
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.NO_GRAVITY
                        marginEnd = 0
                    }
                    val maxLeft = (screenWidth - chatWidth).coerceAtLeast(0)
                    val maxTop = (screenHeight - chatHeight).coerceAtLeast(0)
                    val leftPx = kotlin.math.round(prefs.getFloat(C.TV_CHAT_FREE_X, 0.7f).coerceIn(0f, 1f) * maxLeft).toInt().coerceIn(0, maxLeft)
                    val topPx = kotlin.math.round(prefs.getFloat(C.TV_CHAT_FREE_Y, 0.5f).coerceIn(0f, 1f) * maxTop).toInt().coerceIn(0, maxTop)
                    freeRounded = leftPx > 0 && leftPx < maxLeft && topPx > 0 && topPx < maxTop
                    chatLayout.layoutParams = FrameLayout.LayoutParams(chatWidth, chatHeight, Gravity.TOP or Gravity.LEFT).apply {
                        setMargins(leftPx, topPx, 0, 0)
                    }
                }
                else -> {
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.NO_GRAVITY
                        marginEnd = 0
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidth
                        height = chatHeight
                        gravity = when (position) {
                            1 -> Gravity.END or Gravity.TOP
                            2 -> Gravity.START or Gravity.BOTTOM
                            3 -> Gravity.START or Gravity.TOP
                            4 -> Gravity.END or Gravity.CENTER_VERTICAL
                            5 -> Gravity.START or Gravity.CENTER_VERTICAL
                            6 -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
                            7 -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                            else -> Gravity.END or Gravity.BOTTOM
                        }
                        setMargins(margin, margin, margin, margin)
                        marginStart = margin
                        marginEnd = margin
                    }
                }
            }
            chatFragment?.setSingleLine(mode == 2 && prefs.getInt(C.TV_CHAT_LINES, 3) == 1)
            chatLayout.animate().cancel()
            chatLayout.translationY = if (playerControls.root.isVisible && !controllerHiding && !hudPeek && !tvChatFreeMoving) getTvChatLift() else 0f
            // both backgrounds are opaque: the opacity chosen is the only one applied (the rounded one was already see-through,
            // 100 % gave 55 % as soon as the chat left the edges)
            val rounded = margin != 0 || freeRounded
            chatLayout.setBackgroundResource(if (!rounded) R.drawable.tv_chat_overlay_flush else R.drawable.tv_chat_overlay_rounded)
            val savedAlpha = (prefs.getInt(C.TV_CHAT_OPACITY, 55) * 255 / 100).coerceIn(0, 255)
            chatLayout.background?.mutate()?.alpha = if (tvChatFreeMoving) maxOf(savedAlpha, 90) else savedAlpha
            chatLayout.clipToOutline = rounded
            if (tvChatFreeMoving) {
                chatLayout.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = if (rounded) 12f * metrics.density else 0f
                    setStroke((3f * metrics.density).toInt().coerceAtLeast(2), com.github.andreyasadchy.xtra.ui.tv.TvAccent.color)
                }
            } else if (chatLayout.foreground != null) {
                chatLayout.foreground = null
            }
            syncTvOsdLift(animate = false)
        }
    }

    /** The chat goes up over the controls only when it is at the bottom of the screen. */
    private fun getTvChatLift(): Float {
        if (tvChatFreeMoving) return 0f
        val prefs = requireContext().prefs()
        val mode = prefs.getInt(C.TV_CHAT_MODE, 0)
        val position = getTvChatPosForMode(prefs, mode)
        // the band at the bottom over the stream: it goes up over the controls while they are shown
        if (mode == 2 && prefs.getBoolean(C.TV_CHAT_BAND_OVERLAY, false)) {
            val top = position == 1 || position == 3 || position == 6
            return if (top) 0f else -resources.getDimension(R.dimen.tv_chat_lift)
        }
        // next to or under the stream, the controls are on the picture and the chat stays where it is
        if (mode != 0) {
            return 0f
        }
        if (prefs.getBoolean(C.TV_CHAT_FREE_ACTIVE, false)) {
            val metrics = resources.displayMetrics
            val screenH = _binding?.root?.height?.takeIf { it > 0 } ?: metrics.heightPixels
            val chatH = (screenH * prefs.getInt(C.TV_CHAT_HEIGHT, 50) / 100f).toInt()
            val maxTop = (screenH - chatH).coerceAtLeast(0)
            val topPx = kotlin.math.round(prefs.getFloat(C.TV_CHAT_FREE_Y, 0.5f).coerceIn(0f, 1f) * maxTop).toInt().coerceIn(0, maxTop)
            val liftPx = resources.getDimension(R.dimen.tv_chat_lift)
            val overlap = (topPx + chatH) - (screenH - liftPx.toInt())
            return if (overlap > 0) -overlap.toFloat().coerceAtMost(liftPx) else 0f
        }
        return if (position == 0 || position == 2 || position == 7) -resources.getDimension(R.dimen.tv_chat_lift) else 0f
    }

    private fun androidx.compose.ui.Modifier.panelFirstRow(
        requester: androidx.compose.ui.focus.FocusRequester,
        upTarget: () -> androidx.compose.ui.focus.FocusRequester,
    ): androidx.compose.ui.Modifier = this
        .focusRequester(requester)
        .onPreviewKeyEvent { ev ->
            if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionUp) {
                runCatching { upTarget().requestFocus() }
                true
            } else {
                false
            }
        }

    private fun androidx.compose.ui.Modifier.panelLastRow(
        requester: androidx.compose.ui.focus.FocusRequester?,
        downTarget: androidx.compose.ui.focus.FocusRequester,
    ): androidx.compose.ui.Modifier = this
        .then(if (requester != null) androidx.compose.ui.Modifier.focusRequester(requester) else androidx.compose.ui.Modifier)
        .onPreviewKeyEvent { ev ->
            if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                runCatching { downTarget.requestFocus() }
                true
            } else {
                false
            }
        }

    /**
     * Tabbed Chat panel on the opposite side of the chat so changes are visible live without vertical scroll:
     * Placement · Appearance · Options + pinned horizontal action footer.
     */
    private fun showChatLayoutPanel() {
        val context = requireContext()
        val prefs = context.prefs()
        val corners = listOf(
            getString(R.string.tv_chat_bottom_right),
            getString(R.string.tv_chat_top_right),
            getString(R.string.tv_chat_bottom_left),
            getString(R.string.tv_chat_top_left),
            getString(R.string.tv_chat_middle_right),
            getString(R.string.tv_chat_middle_left),
            getString(R.string.tv_chat_middle_top),
            getString(R.string.tv_chat_middle_bottom),
        )
        val sides = listOf(getString(R.string.tv_chat_right), getString(R.string.tv_chat_left))
        val bands = listOf(getString(R.string.tv_chat_under), getString(R.string.tv_chat_above))
        val modes = listOf(
            getString(R.string.tv_chat_mode_overlay),
            getString(R.string.tv_chat_mode_side),
            getString(R.string.tv_chat_mode_below),
        )
        val tabs = listOf(
            R.drawable.tv_ic_chat_layout to getString(R.string.tv_panel_tab_placement),
            R.drawable.tv_ic_palette to getString(R.string.tv_panel_tab_appearance),
            R.drawable.tv_ic_tune to getString(R.string.tv_panel_tab_options),
        )
        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(context) { dismiss ->
            val initialMode = prefs.getInt(C.TV_CHAT_MODE, 0)
            val mode = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(initialMode) }
            val position = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(getTvChatPosForMode(prefs, initialMode)) }
            val freePosState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(initialMode == 0 && prefs.getBoolean(C.TV_CHAT_FREE_ACTIVE, false))
            }
            val selectedTab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val tabRequesters = androidx.compose.runtime.remember { List(3) { androidx.compose.ui.focus.FocusRequester() } }
            val firstRowReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val lastRowReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footerScrollReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footerUsersReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footerRestartReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footerResetReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }

            val chatOnLeft = if (mode.intValue == 0 && freePosState.value) {
                prefs.getFloat(C.TV_CHAT_FREE_X, 0.7f) < 0.5f
            } else {
                position.intValue == 2 || position.intValue == 3 || position.intValue == 5
            }
            val alignEnd = chatOnLeft && mode.intValue != 2

            com.github.andreyasadchy.xtra.ui.tv.SidePanel(
                title = getString(R.string.tv_chat_layout),
                width = 392.dp,
                compactHeader = true,
                scrollable = true,
                alignEnd = alignEnd,
                headerExtra = {
                    com.github.andreyasadchy.xtra.ui.tv.PanelTabs(
                        tabs = tabs,
                        selectedTab = selectedTab.intValue,
                        tabRequesters = tabRequesters,
                        downTarget = firstRowReq,
                        onSelect = { selectedTab.intValue = it },
                    )
                },
                footer = {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        ) {
                            FooterActionChip(
                                label = getString(R.string.tv_remote_action_scroll_chat),
                                icon = R.drawable.tv_ic_history,
                                requester = footerScrollReq,
                                upTarget = lastRowReq,
                                downTarget = footerUsersReq,
                            ) {
                                dismiss()
                                enterTvChatScrollMode()
                            }
                        }
                        androidx.compose.foundation.layout.Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            FooterActionChip(
                                label = getString(R.string.tv_chat_manage_users),
                                icon = R.drawable.tv_ic_person,
                                requester = footerUsersReq,
                                rightTarget = footerRestartReq,
                                upTarget = footerScrollReq,
                            ) {
                                com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.showPlayerBlockPanel(
                                    context = context,
                                    alignEnd = alignEnd,
                                    recentChatters = chatFragment?.getRecentChatters().orEmpty(),
                                )
                            }
                            FooterActionChip(
                                label = getString(R.string.tv_restart_chat),
                                icon = R.drawable.tv_ic_refresh,
                                requester = footerRestartReq,
                                leftTarget = footerUsersReq,
                                rightTarget = footerResetReq,
                                upTarget = footerScrollReq,
                            ) {
                                restartTvChat()
                                dismiss()
                            }
                            FooterActionChip(
                                label = getString(R.string.tv_reset),
                                icon = R.drawable.tv_ic_replay,
                                requester = footerResetReq,
                                leftTarget = footerRestartReq,
                                upTarget = footerScrollReq,
                            ) {
                            prefs.edit {
                                remove(C.TV_CHAT_POSITION)
                                remove(C.TV_CHAT_POS_OVERLAY)
                                remove(C.TV_CHAT_POS_SIDE)
                                remove(C.TV_CHAT_POS_BAND)
                                remove(C.TV_CHAT_FREE_ACTIVE)
                                remove(C.TV_CHAT_FREE_X)
                                remove(C.TV_CHAT_FREE_Y)
                                remove(C.TV_CHAT_WIDTH)
                                remove(C.TV_CHAT_HEIGHT)
                                remove(C.TV_CHAT_MARGIN)
                                remove(C.TV_CHAT_OPACITY)
                                remove(C.TV_CHAT_MODE)
                                remove(C.TV_CHAT_LINES)
                                remove(C.CHAT_SIZE_MODIFIER)
                                remove(C.CHAT_SLOW_RATE)
                                remove(C.CHAT_DELAY_MODE)
                                remove(C.TV_CHAT_BAND_OVERLAY)
                                remove(C.CHAT_DELAY_SECONDS)
                                remove(C.TV_CHAT_PREDICTIONS_POPUP)
                                remove(C.TV_CHAT_PREDICTIONS_MODE)
                                remove(C.TV_CHAT_PINNED_MODE)
                                remove(C.CHAT_SHARED_BADGES)
                            }
                            hideTvPredictionPopup()
                            chatFragment?.refreshPinnedBanner()
                            chatFragment?.applySizeModifier()
                            if (_binding != null) {
                                applyTvChatLayout()
                            }
                            dismiss()
                        }
                    }
                    }
                },
            ) {
                val update: (String, Int) -> Unit = { key, value ->
                    prefs.edit { putInt(key, value) }
                    if (_binding != null) {
                        applyTvChatLayout()
                    }
                }
                val updatePosition: (Int) -> Unit = { newPos ->
                    position.intValue = newPos
                    freePosState.value = false
                    prefs.edit { putBoolean(C.TV_CHAT_FREE_ACTIVE, false) }
                    saveTvChatPosForMode(prefs, mode.intValue, newPos)
                    if (_binding != null) {
                        applyTvChatLayout()
                    }
                }

                when (selectedTab.intValue) {
                    0 -> {
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_mode),
                            options = modes,
                            selected = mode.intValue,
                            icon = R.drawable.tv_ic_view_quilt,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] },
                        ) { newMode ->
                            if (!prefs.contains(tvChatModePosKey(mode.intValue))) {
                                saveTvChatPosForMode(prefs, mode.intValue, position.intValue)
                            }
                            mode.intValue = newMode
                            freePosState.value = newMode == 0 && prefs.getBoolean(C.TV_CHAT_FREE_ACTIVE, false)
                            val nextPos = getTvChatPosForMode(prefs, newMode)
                            position.intValue = nextPos
                            prefs.edit {
                                putInt(C.TV_CHAT_MODE, newMode)
                                putInt(C.TV_CHAT_POSITION, nextPos)
                            }
                            if (_binding != null) {
                                applyTvChatLayout()
                            }
                        }
                        androidx.compose.runtime.key(mode.intValue) {
                            when (mode.intValue) {
                                1 -> {
                                    val sideLeft = position.intValue == 2 || position.intValue == 3 || position.intValue == 5
                                    com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                        label = getString(R.string.tv_chat_position),
                                        options = sides,
                                        selected = if (sideLeft) 1 else 0,
                                        icon = R.drawable.tv_ic_open_full,
                                        compact = true,
                                    ) {
                                        updatePosition(if (it == 1) 2 else 0)
                                    }
                                }
                                2 -> {
                                    val top = position.intValue == 1 || position.intValue == 3 || position.intValue == 6
                                    com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                        label = getString(R.string.tv_chat_position),
                                        options = bands,
                                        selected = if (top) 1 else 0,
                                        icon = R.drawable.tv_ic_open_full,
                                        compact = true,
                                    ) {
                                        updatePosition(if (it == 1) 1 else 0)
                                    }
                                }
                                else -> {
                                    val posOptions = if (freePosState.value) corners + getString(R.string.tv_position_free) else corners
                                    val posSelected = if (freePosState.value) corners.size else position.intValue.coerceIn(0, corners.lastIndex)
                                    com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                        label = getString(R.string.tv_chat_position),
                                        options = posOptions,
                                        selected = posSelected,
                                        icon = R.drawable.tv_ic_open_full,
                                        compact = true,
                                    ) { chosen ->
                                        if (chosen in corners.indices) {
                                            updatePosition(chosen)
                                        }
                                    }
                                }
                            }
                        }
                        val hasFourthPlacementRow = (mode.intValue == 0 && !freePosState.value) || mode.intValue == 2
                        com.github.andreyasadchy.xtra.ui.tv.ButtonRow(
                            label = getString(R.string.tv_free_move_button),
                            icon = R.drawable.tv_ic_move,
                            compact = true,
                            modifier = if (!hasFourthPlacementRow) androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footerScrollReq) else androidx.compose.ui.Modifier,
                        ) {
                            dismiss()
                            startTvChatFreeMove()
                        }
                        if (mode.intValue == 0 && !freePosState.value) {
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                label = getString(R.string.tv_chat_margin),
                                value = prefs.getInt(C.TV_CHAT_MARGIN, 0),
                                range = 0..64,
                                step = 4,
                                valueText = { "$it dp" },
                                icon = R.drawable.tv_ic_fullscreen,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footerScrollReq),
                            ) {
                                update(C.TV_CHAT_MARGIN, it)
                            }
                        } else if (mode.intValue == 2) {
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                label = getString(R.string.tv_chat_band),
                                options = listOf(getString(R.string.tv_chat_band_separate), getString(R.string.tv_chat_band_overlay)),
                                selected = if (prefs.getBoolean(C.TV_CHAT_BAND_OVERLAY, false)) 1 else 0,
                                icon = R.drawable.tv_ic_chat_layout,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footerScrollReq),
                            ) {
                                prefs.edit { putBoolean(C.TV_CHAT_BAND_OVERLAY, it == 1) }
                                if (_binding != null) {
                                    applyTvChatLayout()
                                }
                            }
                        }
                    }

                    1 -> {
                        textSizeRow(
                            prefs = prefs,
                            modifier = androidx.compose.ui.Modifier.panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] },
                        )
                        when (mode.intValue) {
                            1 -> {
                                com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                    label = getString(R.string.tv_chat_width),
                                    value = prefs.getInt(C.TV_CHAT_WIDTH, 30),
                                    range = 5..60,
                                    valueText = { "$it %" },
                                    icon = R.drawable.tv_ic_open_full,
                                    compact = true,
                                ) {
                                    update(C.TV_CHAT_WIDTH, it)
                                }
                            }
                            2 -> {
                                com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                    label = getString(R.string.tv_chat_height),
                                    value = prefs.getInt(C.TV_CHAT_LINES, 3),
                                    range = 1..12,
                                    valueText = { if (it == 1) getString(R.string.tv_chat_one_line) else getString(R.string.tv_chat_lines, it) },
                                    icon = R.drawable.tv_ic_list,
                                    compact = true,
                                ) {
                                    update(C.TV_CHAT_LINES, it)
                                }
                            }
                            else -> {
                                com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                    label = getString(R.string.tv_chat_width),
                                    value = prefs.getInt(C.TV_CHAT_WIDTH, 30),
                                    range = 5..60,
                                    valueText = { "$it %" },
                                    icon = R.drawable.tv_ic_open_full,
                                    compact = true,
                                ) {
                                    update(C.TV_CHAT_WIDTH, it)
                                }
                                com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                    label = getString(R.string.tv_chat_height),
                                    value = prefs.getInt(C.TV_CHAT_HEIGHT, 50),
                                    range = 20..100,
                                    step = 5,
                                    valueText = { "$it %" },
                                    icon = R.drawable.tv_ic_fullscreen,
                                    compact = true,
                                ) {
                                    update(C.TV_CHAT_HEIGHT, it)
                                }
                            }
                        }
                        com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                            label = getString(R.string.tv_chat_opacity),
                            value = prefs.getInt(C.TV_CHAT_OPACITY, 55),
                            range = 0..100,
                            step = 5,
                            valueText = { "$it %" },
                            icon = R.drawable.tv_ic_opacity,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footerScrollReq),
                        ) {
                            update(C.TV_CHAT_OPACITY, it)
                        }
                    }

                    else -> {
                        val showQrChatRow = playbackService?.type == BasePlaybackService.STREAM
                        val qrConnected by com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.connectedClients.collectAsState()
                        if (showQrChatRow) {
                            val qrLabel = if (qrConnected > 0) {
                                "${getString(R.string.tv_chat_qr_title)} · ${getString(R.string.tv_chat_qr_connected, qrConnected)}"
                            } else {
                                getString(R.string.tv_chat_qr_title)
                            }
                            com.github.andreyasadchy.xtra.ui.tv.ButtonRow(
                                label = qrLabel,
                                icon = R.drawable.tv_ic_qr,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] },
                            ) {
                                dismiss()
                                openTvChatQrPanel(alignEnd = alignEnd)
                            }
                        }
                        val activePin = chatFragment?.getPinnedMessage()
                        if (activePin != null) {
                            val pinSender = activePin.senderName ?: activePin.senderLogin
                            val pinPreview = if (!pinSender.isNullOrBlank()) "$pinSender: ${activePin.messageText}" else activePin.messageText
                            com.github.andreyasadchy.xtra.ui.tv.ButtonRow(
                                label = "${getString(R.string.tv_chat_pinned_active)} — ${pinPreview.take(32)}",
                                icon = R.drawable.tv_ic_pin,
                                compact = true,
                                modifier = if (!showQrChatRow) androidx.compose.ui.Modifier.panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] } else androidx.compose.ui.Modifier,
                            ) {
                                showPinnedMessageDetailPanel(activePin, alignEnd = alignEnd)
                            }
                        }
                        val expVodChat = isExperimentalVodActive()
                        val savedDelayMode = prefs.getString(C.CHAT_DELAY_MODE, "1")?.toIntOrNull()?.coerceIn(0, 2) ?: 1
                        val delayMode = androidx.compose.runtime.remember(expVodChat) {
                            androidx.compose.runtime.mutableIntStateOf(if (expVodChat) 1 else savedDelayMode)
                        }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_delay),
                            options = listOf(getString(R.string.tv_chat_delay_off), getString(R.string.tv_chat_delay_auto), getString(R.string.tv_chat_delay_manual)),
                            selected = delayMode.intValue,
                            icon = R.drawable.tv_ic_timer,
                            compact = true,
                            modifier = if (!showQrChatRow && activePin == null) androidx.compose.ui.Modifier.panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] } else androidx.compose.ui.Modifier,
                        ) {
                            if (isExperimentalVodActive()) {
                                delayMode.intValue = 1
                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.tv_adblock_exp_chat_auto_locked, Toast.LENGTH_SHORT).show()
                            } else {
                                delayMode.intValue = it
                                prefs.edit { putString(C.CHAT_DELAY_MODE, it.toString()) }
                            }
                        }
                        androidx.compose.runtime.key(delayMode.intValue, expVodChat) {
                            when (delayMode.intValue) {
                                1 -> {
                                    val measuredState by androidx.compose.runtime.produceState(
                                        (chatFragment?.getAutoDelayMs() ?: 0L) to (chatFragment?.isAutoDelayMeasured() == true)
                                    ) {
                                        while (true) {
                                            value = (chatFragment?.getAutoDelayMs() ?: 0L) to (chatFragment?.isAutoDelayMeasured() == true)
                                            kotlinx.coroutines.delay(1000)
                                        }
                                    }
                                    val (measured, isMeasured) = measuredState
                                    val now = if (measured > 0L || isMeasured) {
                                        getString(R.string.tv_chat_delay_auto_now, "%.1f".format(measured / 1000f))
                                    } else {
                                        getString(R.string.tv_chat_delay_auto_measuring)
                                    }
                                    val infoText = if (expVodChat) {
                                        getString(R.string.tv_adblock_exp_chat_auto_info, now)
                                    } else {
                                        getString(R.string.tv_chat_delay_auto_info, now)
                                    }
                                    androidx.tv.material3.Text(
                                        infoText,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = com.github.andreyasadchy.xtra.ui.tv.TvColors.secondaryText,
                                        modifier = androidx.compose.ui.Modifier.padding(start = 15.dp, end = 15.dp, top = 1.dp, bottom = 3.dp),
                                    )
                                }
                                2 -> {
                                    com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                        label = getString(R.string.tv_chat_delay_seconds),
                                        value = prefs.getInt(C.CHAT_DELAY_SECONDS, 5).coerceIn(1, 60),
                                        range = 1..60,
                                        valueText = { "$it s" },
                                        icon = R.drawable.tv_ic_schedule,
                                        compact = true,
                                    ) {
                                        prefs.edit { putInt(C.CHAT_DELAY_SECONDS, it) }
                                    }
                                }
                            }
                        }
                        val slowRateValues = listOf("0", "1", "2", "4", "6")
                        val slowRateLabels = listOf(
                            getString(R.string.tv_chat_delay_off),
                            getString(R.string.tv_chat_slow_rate, 1),
                            getString(R.string.tv_chat_slow_rate, 2),
                            getString(R.string.tv_chat_slow_rate, 4),
                            getString(R.string.tv_chat_slow_rate, 6),
                        )
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_slow),
                            options = slowRateLabels,
                            selected = slowRateValues.indexOf(prefs.getString(C.CHAT_SLOW_RATE, "0")).coerceAtLeast(0),
                            icon = R.drawable.tv_ic_speed,
                            compact = true,
                        ) {
                            prefs.edit { putString(C.CHAT_SLOW_RATE, slowRateValues[it]) }
                        }
                        val pinnedMode = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableIntStateOf(prefs.getInt(C.TV_CHAT_PINNED_MODE, 1).coerceIn(0, 2))
                        }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_pinned),
                            options = listOf(
                                getString(R.string.tv_chat_pinned_hidden),
                                getString(R.string.tv_chat_pinned_temp),
                                getString(R.string.tv_chat_pinned_always),
                            ),
                            selected = pinnedMode.intValue,
                            icon = R.drawable.tv_ic_pin,
                            compact = true,
                        ) {
                            pinnedMode.intValue = it
                            prefs.edit { putInt(C.TV_CHAT_PINNED_MODE, it) }
                            chatFragment?.refreshPinnedBanner()
                        }
                        val pollPopup = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableIntStateOf(C.getTvPollsPopupMode(prefs))
                        }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_polls_popup),
                            options = listOf(
                                getString(R.string.tv_chat_delay_off),
                                getString(R.string.tv_chat_predictions_30s),
                                getString(R.string.tv_chat_predictions_full),
                            ),
                            selected = pollPopup.intValue,
                            icon = R.drawable.tv_ic_bar_chart,
                            compact = true,
                        ) {
                            pollPopup.intValue = it
                            prefs.edit { putInt(C.TV_CHAT_POLLS_MODE, it) }
                            if (it == 0) {
                                hideTvPollPopup()
                            } else if (!isChatOpen) {
                                if (it == 2) {
                                    tvActivePoll.value?.takeIf { p -> p.status == "ACTIVE" }?.let { p -> onTvPollEvent(p) }
                                } else if (it == 1 && tvPollView?.isVisible == true && tvPollCurrent?.status == "ACTIVE") {
                                    showTvPollPopup(autoHideMs = 30_000L)
                                }
                            }
                        }
                        val predPopup = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableIntStateOf(C.getTvPredictionsPopupMode(prefs))
                        }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_predictions_popup),
                            options = listOf(
                                getString(R.string.tv_chat_delay_off),
                                getString(R.string.tv_chat_predictions_30s),
                                getString(R.string.tv_chat_predictions_full),
                            ),
                            selected = predPopup.intValue,
                            icon = R.drawable.tv_ic_trending,
                            compact = true,
                        ) {
                            predPopup.intValue = it
                            prefs.edit {
                                putInt(C.TV_CHAT_PREDICTIONS_MODE, it)
                                putBoolean(C.TV_CHAT_PREDICTIONS_POPUP, it != 0)
                            }
                            if (it == 0) {
                                hideTvPredictionPopup()
                            } else if (!isChatOpen) {
                                if (it == 2) {
                                    tvActivePrediction.value?.takeIf { p -> p.status == "ACTIVE" }?.let { p -> onTvPredictionEvent(p) }
                                } else if (it == 1 && tvPredictionView?.isVisible == true && tvPredictionCurrent?.status == "ACTIVE") {
                                    showTvPredictionPopup(autoHideMs = 30_000L)
                                }
                            }
                        }
                        val sharedBadges = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableIntStateOf(if (prefs.getBoolean(C.CHAT_SHARED_BADGES, true)) 1 else 0)
                        }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_chat_shared_badges),
                            options = listOf(getString(R.string.tv_chat_delay_off), getString(R.string.tv_option_on)),
                            selected = sharedBadges.intValue,
                            icon = R.drawable.tv_ic_shield,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footerScrollReq),
                        ) {
                            sharedBadges.intValue = it
                            prefs.edit { putBoolean(C.CHAT_SHARED_BADGES, it == 1) }
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos {}
                    runCatching { tabRequesters.getOrNull(selectedTab.intValue)?.requestFocus() }
                }
            }
        }
    }

    private fun showPinnedMessageDetailPanel(
        pin: com.github.andreyasadchy.xtra.util.chat.PubSubUtils.PinnedChatData,
        alignEnd: Boolean,
    ) {
        val context = context ?: return
        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(context) { dismiss ->
            val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val sender = pin.senderName ?: pin.senderLogin
            val subtitle = pin.pinnedByName?.let { getString(R.string.tv_chat_pinned_by, it) }
            com.github.andreyasadchy.xtra.ui.tv.SidePanel(
                title = "📌 ${sender ?: getString(R.string.tv_chat_pinned_active)}",
                subtitle = subtitle,
                alignEnd = alignEnd,
                scrollable = true,
            ) {
                androidx.tv.material3.Text(
                    text = pin.messageText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val hidden = chatFragment?.isCurrentPinnedHidden() == true
                com.github.andreyasadchy.xtra.ui.tv.ButtonRow(
                    if (hidden) getString(R.string.tv_chat_pinned_show_now) else getString(R.string.tv_chat_pinned_hide_now),
                    androidx.compose.ui.Modifier.focusRequester(requester),
                ) {
                    chatFragment?.toggleCurrentPinnedBanner()
                    dismiss()
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
    }

    /**
     * TV: a switch of stream starts: a copy of the picture on the screen is taken (in the background, the switch does not
     * wait for it), the next player screen shows it until the picture of the next stream is there.
     */
    fun captureSwitchFrame() {
        if (!isTv || !isMaximized) {
            return
        }
        // the stream left was still loading: its loading picture is what is on the screen
        ((loadingPicture?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)?.let {
            putSwitchFrame(it)
            return
        }
        val binding = _binding ?: return
        val surface = binding.secondaryPlayerSurface.takeIf {
            it.visibility == View.VISIBLE && it.holder.surface.isValid && it.width > 0 && it.height > 0
        } ?: binding.playerSurface
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) {
            return
        }
        val width = 640
        val height = (640f * surface.height / surface.width).toInt().coerceAtLeast(1)
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            return
        }
        try {
            android.view.PixelCopy.request(surface, bitmap, { result ->
                if (result == android.view.PixelCopy.SUCCESS) {
                    putSwitchFrame(bitmap)
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            // the surface went away meanwhile: no copy, the loading picture of the next stream as before
        }
    }

    /** TV: the stream and its chat as dark/warm as the filter says, the icon of its button shows if it is on. */
    private val tvDimListener: () -> Unit = { applyTvDim() }

    private fun applyTvDim() {
        val binding = _binding ?: return
        if (!isTv) {
            return
        }
        val context = context ?: return
        com.github.andreyasadchy.xtra.ui.tv.TvDim.apply(binding.aspectRatioFrameLayout)
        com.github.andreyasadchy.xtra.ui.tv.TvDim.apply(binding.chatLayout)
        binding.playerControls.tvDim?.setImageResource(
            if (com.github.andreyasadchy.xtra.ui.tv.TvDim.isEffectivelyOn(context)) R.drawable.tv_ic_dim_on else R.drawable.tv_ic_dim
        )
    }

    /** TV: the icon of the adblock & proxy button lights up when Experimental Adblock, Custom Proxy, or HTTP Proxy is enabled. */
    private fun updateTvProxyIcon() {
        val context = context ?: return
        val expOn = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(context)
        val swapPlusOn = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapPlusEnabled(context)
        val customOn = !expOn && !swapPlusOn && context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
        val streamProxyOn = !expOn && !swapPlusOn && context.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
        _binding?.playerControls?.tvProxy?.apply {
            setImageResource(if (expOn || swapPlusOn || customOn || streamProxyOn) R.drawable.tv_ic_proxy_on else R.drawable.tv_ic_proxy)
            alpha = 1f
        }
    }

    /** TV: unified Ad blocker & proxies panel, restarting the stream when closed if settings changed. */
    private fun showTvProxyPanel() {
        val activity = activity as? MainActivity ?: return
        val repository = (activity.application as com.github.andreyasadchy.xtra.XtraApp).xtraModule.playerRepository
        viewLifecycleOwner.lifecycleScope.launch {
            val proxies = repository.getCustomProxies()
            val streamProxies = repository.getStreamProxies()
            if (_binding == null) {
                return@launch
            }
            val swapBefore = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapAdblockEnabled(activity)
            val swapPlusBefore = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapPlusEnabled(activity)
            val expBefore = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(activity)
            val useBefore = activity.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
            val useStreamBefore = activity.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
            val modeBefore = activity.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0")
            var streamListChanged = false
            if (!isTv) {
                com.github.andreyasadchy.xtra.ui.mobile.MobileBottomSheets.showAdblockAndProxies(
                    context = activity,
                    customProxies = proxies,
                    streamProxies = streamProxies,
                    openCustomSettings = { activity.openCustomProxySettings() },
                    openStreamSettings = { activity.openStreamProxySettings() },
                ) { changed ->
                    if (_binding != null) {
                        updateTvProxyIcon()
                    }
                    if (changed) {
                        activity.restartCurrentStream()
                    }
                }
                return@launch
            }
            com.github.andreyasadchy.xtra.ui.tv.TvProxyPanel.show(
                activity,
                proxies,
                openSettings = { activity.openCustomProxySettings() },
                streamProxies = streamProxies,
                openStreamProxySettings = { activity.openStreamProxySettings() },
                onStreamProxiesClosed = { updatedStream ->
                    streamListChanged = true
                    activity.lifecycleScope.launch {
                        repository.updateStreamProxies(updatedStream)
                    }
                },
            ) { changed ->
                if (_binding != null) {
                    updateTvProxyIcon()
                }
                if (changed != null) {
                    activity.lifecycleScope.launch {
                        repository.updateCustomProxies(changed)
                        val swapNow = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapAdblockEnabled(activity)
                        val swapPlusNow = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapPlusEnabled(activity)
                        val expNow = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(activity)
                        val useNow = activity.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                        val useStreamNow = activity.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                        val modeNow = activity.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0")
                        if (swapNow != swapBefore || swapPlusNow != swapPlusBefore || expNow != expBefore ||
                            useNow || useNow != useBefore || useStreamNow || useStreamNow != useStreamBefore ||
                            streamListChanged || modeNow != modeBefore
                        ) {
                            activity.restartCurrentStream()
                        }
                    }
                }
            }
        }
    }

    /** TV: dimming and Night Shift (warm light) levels (OK held on its button), seen live while adjusted. */
    private fun showTvDimPanel() {
        val context = requireContext()
        // set while choosing it: seen at once
        com.github.andreyasadchy.xtra.ui.tv.TvDim.setActive(true)
        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(context) { dismiss ->
            com.github.andreyasadchy.xtra.ui.tv.SidePanel(title = getString(R.string.tv_dim), scrollable = true) {
                val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                    getString(R.string.tv_dim_level),
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.level(context),
                    0..90,
                    step = 10,
                    valueText = { "$it %" },
                    modifier = androidx.compose.ui.Modifier.focusRequester(requester),
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.setLevel(context, it)
                }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
                com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                    getString(R.string.tv_night_shift_level),
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.warmLevel(context),
                    0..80,
                    step = 10,
                    valueText = { "$it %" },
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.setWarmLevel(context, it)
                }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_dim_off)) {
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.setActive(false)
                    dismiss()
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
    }

    fun onTvPipOpened() {
        _binding?.playerControls?.tvPipSettings?.visibility = View.VISIBLE
    }

    /** TV: all small windows closed: its button goes away, the focus moves to another button of the controls if it was on it. */
    fun onTvPipClosed() {
        val button = _binding?.playerControls?.tvPipSettings ?: return
        val hadFocus = button.hasFocus()
        button.visibility = View.GONE
        if (hadFocus && areControlsShown()) {
            focusPrimaryControl()
        } else if (isTv && isMaximized && !areControlsShown() && !binding.chatLayout.hasFocus()) {
            binding.dragView.requestFocus()
        }
    }

    /**
     * TV: tabbed panel of the small window(s): Display (Position, Free move, Size, Edge margin, Opacity, Streamer name)
     * and Audio (Sound, Balance / Mix) + pinned horizontal action footer, with zero vertical scroll.
     */
    private fun showTvPipPanel() {
        val activity = activity as? MainActivity ?: return
        val activeIndices = activity.tvPipActiveSlotIndices()
        if (activeIndices.isEmpty()) {
            return
        }
        val prefs = activity.prefs()
        val cornerValues = listOf("tl", "tc", "tr", "ml", "c", "mr", "bl", "bc", "br")
        val corners = listOf(
            getString(R.string.tv_pip_corner_tl),
            getString(R.string.tv_pip_pos_tc),
            getString(R.string.tv_pip_corner_tr),
            getString(R.string.tv_pip_pos_ml),
            getString(R.string.tv_pip_pos_c),
            getString(R.string.tv_pip_pos_mr),
            getString(R.string.tv_pip_corner_bl),
            getString(R.string.tv_pip_pos_bc),
            getString(R.string.tv_pip_corner_br),
        )
        val sizes = listOf(getString(R.string.tv_pip_size_small), getString(R.string.tv_pip_size_medium), getString(R.string.tv_pip_size_large))
        val twoOpen = activeIndices.size >= 2
        val name0 = activity.tvPipStreamAt(0)?.let { it.channelName ?: it.channelLogin }.orEmpty()
        val name1 = activity.tvPipStreamAt(1)?.let { it.channelName ?: it.channelLogin }.orEmpty()
        val windowOptions = listOf(
            if (name0.isNotBlank()) "${getString(R.string.tv_pip_window_1)} ($name0)" else getString(R.string.tv_pip_window_1),
            if (name1.isNotBlank()) "${getString(R.string.tv_pip_window_2)} ($name1)" else getString(R.string.tv_pip_window_2),
        )
        val tabs = listOf(
            R.drawable.tv_ic_pip to getString(R.string.tv_panel_tab_display),
            R.drawable.tv_ic_volume to getString(R.string.tv_panel_tab_audio),
        )

        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(activity) { dismiss ->
            val selectedSlotState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableIntStateOf(if (twoOpen) 0 else activeIndices.first())
            }
            val currentSlot = selectedSlotState.intValue
            val cornerKey = if (currentSlot == 1) C.TV_PIP2_CORNER else C.TV_PIP_CORNER
            val cornerDefault = if (currentSlot == 1) "tl" else "tr"
            val sizeKey = if (currentSlot == 1) C.TV_PIP2_SIZE else C.TV_PIP_SIZE
            val opacityKey = if (currentSlot == 1) C.TV_PIP2_OPACITY else C.TV_PIP_OPACITY
            val freeXKey = if (currentSlot == 1) C.TV_PIP2_FREE_X else C.TV_PIP_FREE_X

            val slotCornerState = androidx.compose.runtime.remember(currentSlot) {
                androidx.compose.runtime.mutableStateOf(prefs.getString(cornerKey, cornerDefault) ?: cornerDefault)
            }
            val slotCornerPos = slotCornerState.value
            val pipOnLeft = if (slotCornerPos == "free") {
                prefs.getFloat(freeXKey, if (currentSlot == 1) 0.03f else 0.97f) < 0.5f
            } else {
                slotCornerPos == "tl" || slotCornerPos == "ml" || slotCornerPos == "bl"
            }

            val soundValues = if (twoOpen) {
                listOf("main", "pip1", "pip2", "mix")
            } else {
                listOf("main", "pip", "both")
            }
            val sounds = if (twoOpen) {
                listOf(
                    getString(R.string.tv_pip_sound_main),
                    getString(R.string.tv_pip_sound_pip1),
                    getString(R.string.tv_pip_sound_pip2),
                    getString(R.string.tv_pip_sound_mix),
                )
            } else {
                listOf(
                    getString(R.string.tv_pip_sound_main),
                    getString(R.string.tv_pip_sound_pip),
                    getString(R.string.tv_pip_sound_both),
                )
            }
            val rawSavedSound = prefs.getString(C.TV_PIP_SOUND, "main") ?: "main"
            val normalizedSound = when {
                twoOpen && rawSavedSound == "pip" -> "pip1"
                twoOpen && rawSavedSound == "both" -> "mix"
                !twoOpen && (rawSavedSound == "pip1" || rawSavedSound == "pip2") -> "pip"
                !twoOpen && rawSavedSound == "mix" -> "both"
                else -> rawSavedSound
            }
            val sound = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableIntStateOf(soundValues.indexOf(normalizedSound).coerceAtLeast(0))
            }

            val selectedTab = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val windowSelectorReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val tabRequesters = androidx.compose.runtime.remember { List(2) { androidx.compose.ui.focus.FocusRequester() } }
            val firstRowReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val lastRowReq = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footer0Req = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footer1Req = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            val footer2Req = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }

            val hasExtraAudioRows = (!twoOpen && soundValues.getOrNull(sound.intValue) == "both") ||
                (twoOpen && soundValues.getOrNull(sound.intValue) == "mix")
            val footerUpTarget = if (selectedTab.intValue == 1 && !hasExtraAudioRows) firstRowReq else lastRowReq

            com.github.andreyasadchy.xtra.ui.tv.SidePanel(
                title = getString(R.string.tv_pip_settings),
                width = 392.dp,
                compactHeader = true,
                alignEnd = pipOnLeft,
                scrollable = true,
                headerExtra = {
                    if (twoOpen) {
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_pip_settings),
                            options = windowOptions,
                            selected = currentSlot.coerceIn(0, 1),
                            icon = R.drawable.tv_ic_multistream,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier
                                .focusRequester(windowSelectorReq)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                        when (ev.key) {
                                            androidx.compose.ui.input.key.Key.DirectionDown -> {
                                                runCatching { tabRequesters[selectedTab.intValue].requestFocus() }
                                                true
                                            }
                                            androidx.compose.ui.input.key.Key.DirectionUp -> true
                                            else -> false
                                        }
                                    } else false
                                },
                        ) {
                            selectedSlotState.intValue = it
                        }
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                    }
                    com.github.andreyasadchy.xtra.ui.tv.PanelTabs(
                        tabs = tabs,
                        selectedTab = selectedTab.intValue,
                        tabRequesters = tabRequesters,
                        downTarget = firstRowReq,
                        upTarget = if (twoOpen) windowSelectorReq else null,
                        onSelect = { selectedTab.intValue = it },
                    )
                },
                footer = {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        if (!twoOpen) {
                            FooterActionChip(
                                label = getString(R.string.tv_pip_add_second),
                                icon = R.drawable.tv_ic_add,
                                requester = footer0Req,
                                rightTarget = footer1Req,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                val mainStream = activity.dvrStream ?: activity.currentPlayingStream()
                                val excluded = buildSet {
                                    mainStream?.channelLogin?.let { add(it) }
                                    activity.tvPipActiveStreams().forEach { s -> s.channelLogin?.let { add(it) } }
                                }
                                TvMultiStreamPicker.pickOne(
                                    activity,
                                    excluded,
                                    getString(R.string.tv_pip_add_second),
                                    mainStream,
                                ) { picked ->
                                    activity.openTvPip(picked)
                                }
                            }
                            FooterActionChip(
                                label = getString(R.string.tv_pip_swap),
                                icon = R.drawable.tv_ic_swap,
                                requester = footer1Req,
                                leftTarget = footer0Req,
                                rightTarget = footer2Req,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                activity.swapTvPip(currentSlot)
                            }
                            FooterActionChip(
                                label = getString(R.string.tv_pip_close),
                                icon = R.drawable.tv_ic_close,
                                requester = footer2Req,
                                leftTarget = footer1Req,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                activity.closeTvPipSlot(currentSlot)
                            }
                        } else {
                            val slotTitle = getString(if (currentSlot == 1) R.string.tv_pip_window_2 else R.string.tv_pip_window_1)
                            FooterActionChip(
                                label = getString(R.string.tv_pip_swap),
                                icon = R.drawable.tv_ic_swap,
                                requester = footer0Req,
                                rightTarget = footer1Req,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                activity.swapTvPip(currentSlot)
                            }
                            FooterActionChip(
                                label = slotTitle,
                                icon = R.drawable.tv_ic_close,
                                requester = footer1Req,
                                leftTarget = footer0Req,
                                rightTarget = footer2Req,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                activity.closeTvPipSlot(currentSlot)
                            }
                            FooterActionChip(
                                label = getString(R.string.tv_pip_close_all),
                                icon = R.drawable.tv_ic_delete,
                                requester = footer2Req,
                                leftTarget = footer1Req,
                                upTarget = footerUpTarget,
                            ) {
                                dismiss()
                                activity.closeTvPip()
                            }
                        }
                    }
                },
            ) {
                when (selectedTab.intValue) {
                    0 -> {
                        androidx.compose.runtime.key(currentSlot) {
                            val isFree = slotCornerPos == "free"
                            val posOptions = if (isFree) corners + getString(R.string.tv_position_free) else corners
                            val posSelected = if (isFree) {
                                corners.size
                            } else {
                                cornerValues.indexOf(slotCornerPos).let { if (it >= 0) it else if (currentSlot == 1) 0 else 2 }
                            }
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                label = getString(R.string.tv_pip_position),
                                options = posOptions,
                                selected = posSelected,
                                icon = R.drawable.tv_ic_open_full,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] },
                            ) { chosen ->
                                if (chosen in cornerValues.indices) {
                                    val nextCorner = cornerValues[chosen]
                                    slotCornerState.value = nextCorner
                                    prefs.edit { putString(cornerKey, nextCorner) }
                                    activity.applyTvPipSettings()
                                }
                            }
                            com.github.andreyasadchy.xtra.ui.tv.ButtonRow(
                                label = getString(R.string.tv_free_move_button),
                                icon = R.drawable.tv_ic_move,
                                compact = true,
                            ) {
                                dismiss()
                                activity.startTvPipFreeMove(currentSlot)
                            }
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                label = getString(R.string.tv_pip_size),
                                options = sizes,
                                selected = prefs.getInt(sizeKey, 1).coerceIn(0, 2),
                                icon = R.drawable.tv_ic_fullscreen,
                                compact = true,
                            ) {
                                prefs.edit { putInt(sizeKey, it) }
                                activity.applyTvPipSettings()
                            }
                        }
                        val marginValues = listOf("normal", "reduced", "flush")
                        val marginLabels = listOf(
                            getString(R.string.tv_pip_margin_normal),
                            getString(R.string.tv_pip_margin_reduced),
                            getString(R.string.tv_pip_margin_flush),
                        )
                        val currentMarginIdx = marginValues.indexOf(prefs.getString(C.TV_PIP_EDGE_MARGIN, "reduced")).let { if (it >= 0) it else 1 }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_pip_edge_margin),
                            options = marginLabels,
                            selected = currentMarginIdx,
                            icon = R.drawable.tv_ic_view_quilt,
                            compact = true,
                        ) {
                            prefs.edit { putString(C.TV_PIP_EDGE_MARGIN, marginValues[it]) }
                            activity.applyTvPipSettings()
                        }
                        androidx.compose.runtime.key("op_$currentSlot") {
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                label = getString(R.string.tv_pip_opacity),
                                value = prefs.getInt(opacityKey, 100).coerceIn(5, 100),
                                range = 5..100,
                                step = 5,
                                valueText = { "$it %" },
                                icon = R.drawable.tv_ic_opacity,
                                compact = true,
                            ) {
                                prefs.edit { putInt(opacityKey, it) }
                                activity.applyTvPipSettings()
                            }
                        }
                        val labelModeValues = listOf("auto", "always", "hidden")
                        val labelModeLabels = listOf(
                            getString(R.string.tv_pip_label_auto),
                            getString(R.string.tv_pip_label_always),
                            getString(R.string.tv_pip_label_hidden),
                        )
                        val currentLabelModeIdx = labelModeValues.indexOf(prefs.getString(C.TV_PIP_LABEL_MODE, "auto")).let { if (it >= 0) it else 0 }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_pip_streamer_name),
                            options = labelModeLabels,
                            selected = currentLabelModeIdx,
                            icon = R.drawable.tv_ic_person,
                            compact = true,
                            modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footer0Req),
                        ) {
                            prefs.edit { putString(C.TV_PIP_LABEL_MODE, labelModeValues[it]) }
                            activity.applyTvPipSettings()
                        }
                    }

                    else -> {
                        val soundMod = androidx.compose.ui.Modifier
                            .panelFirstRow(firstRowReq) { tabRequesters[selectedTab.intValue] }
                            .let { if (!hasExtraAudioRows) it.panelLastRow(null, footer0Req) else it }
                        com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                            label = getString(R.string.tv_pip_sound),
                            options = sounds,
                            selected = sound.intValue,
                            icon = R.drawable.tv_ic_volume,
                            compact = true,
                            modifier = soundMod,
                        ) {
                            sound.intValue = it
                            prefs.edit { putString(C.TV_PIP_SOUND, soundValues[it]) }
                            activity.applyTvPipSettings()
                        }
                        if (!twoOpen && soundValues.getOrNull(sound.intValue) == "both") {
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                label = getString(R.string.tv_pip_balance),
                                value = prefs.getInt(C.TV_PIP_BALANCE, 0).coerceIn(-100, 100),
                                range = -100..100,
                                step = 10,
                                valueText = { value ->
                                    val main = if (value > 0) 100 - value else 100
                                    val small = if (value < 0) 100 + value else 100
                                    getString(R.string.tv_pip_balance_value, main, small)
                                },
                                icon = R.drawable.tv_ic_tune,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footer0Req),
                            ) {
                                prefs.edit { putInt(C.TV_PIP_BALANCE, it) }
                                activity.applyTvPipSettings()
                            }
                        } else if (twoOpen && soundValues.getOrNull(sound.intValue) == "mix") {
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                label = getString(R.string.tv_pip_vol_main),
                                value = prefs.getInt(C.TV_PIP_VOL_MAIN, 100).coerceIn(0, 100),
                                range = 0..100,
                                step = 10,
                                valueText = { "$it %" },
                                icon = R.drawable.tv_ic_volume,
                                compact = true,
                            ) {
                                prefs.edit { putInt(C.TV_PIP_VOL_MAIN, it) }
                                activity.applyTvPipSettings()
                            }
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                label = getString(R.string.tv_pip_vol_1),
                                value = prefs.getInt(C.TV_PIP_VOL_1, 80).coerceIn(0, 100),
                                range = 0..100,
                                step = 10,
                                valueText = { "$it %" },
                                icon = R.drawable.tv_ic_volume,
                                compact = true,
                            ) {
                                prefs.edit { putInt(C.TV_PIP_VOL_1, it) }
                                activity.applyTvPipSettings()
                            }
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                label = getString(R.string.tv_pip_vol_2),
                                value = prefs.getInt(C.TV_PIP_VOL_2, 80).coerceIn(0, 100),
                                range = 0..100,
                                step = 10,
                                valueText = { "$it %" },
                                icon = R.drawable.tv_ic_volume,
                                compact = true,
                                modifier = androidx.compose.ui.Modifier.panelLastRow(lastRowReq, footer0Req),
                            ) {
                                prefs.edit { putInt(C.TV_PIP_VOL_2, it) }
                                activity.applyTvPipSettings()
                            }
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos {}
                    runCatching { tabRequesters.getOrNull(selectedTab.intValue)?.requestFocus() }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun textSizeRow(
        prefs: android.content.SharedPreferences,
        modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    ) {
        com.github.andreyasadchy.xtra.ui.tv.SliderRow(
            label = getString(R.string.tv_chat_text_size),
            value = prefs.getInt(C.CHAT_SIZE_MODIFIER, 100),
            range = 60..200,
            step = 10,
            valueText = { "$it %" },
            icon = R.drawable.tv_ic_text_size,
            compact = true,
            modifier = modifier,
        ) {
            prefs.edit { putInt(C.CHAT_SIZE_MODIFIER, it) }
            chatFragment?.applySizeModifier()
            // the band under the stream is as high as its lines
            if (_binding != null) {
                applyTvChatLayout()
            }
        }
    }

    private fun applyTvChatButtonPillState(pillOpen: Boolean) {
        if (!isTv || _binding == null) return
        val btn = binding.playerControls.chatMessage ?: return
        if (pillOpen) {
            val density = btn.resources.displayMetrics.density
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x59181822)
                setStroke((1.8f * density + 0.5f).toInt(), com.github.andreyasadchy.xtra.ui.tv.TvAccent.onDark)
            }
            btn.setColorFilter(Color.WHITE)
        } else {
            btn.setBackgroundResource(R.drawable.tv_player_button_background)
            btn.refreshDrawableState()
            btn.jumpDrawablesToCurrentState()
            btn.setColorFilter(
                when {
                    btn.hasFocus() -> com.github.andreyasadchy.xtra.ui.tv.TvAccent.onLight
                    btn.isEnabled -> Color.WHITE
                    else -> Color.GRAY
                }
            )
        }
    }

    /** TV: write a chat message in a compact smoked-glass pill anchored directly above the Chat button (matching TvVolumePill). */
    private fun showChatComposer() {
        val emoteGroupsState = androidx.compose.runtime.mutableStateOf(chatFragment?.getTvEmoteGroups().orEmpty())
        val density = resources.displayMetrics.density
        var anchorCenterX: Float? = null
        var anchorBottomMargin: Float? = null
        if (_binding != null && areControlsShown()) {
            val hostRoot = binding.playerControls.root
            val anchor = binding.playerControls.chatMessage
            val timeline = binding.playerControls.progressBar
            val rootLoc = IntArray(2)
            hostRoot.getLocationInWindow(rootLoc)
            val windowBottom = rootLoc[1] + hostRoot.height
            if (anchor != null && anchor.visibility == View.VISIBLE && anchor.width > 0) {
                val anchorLoc = IntArray(2)
                anchor.getLocationInWindow(anchorLoc)
                anchorCenterX = anchorLoc[0] + anchor.width / 2f
                val anchorTop = anchorLoc[1].toFloat()
                val bottomTipY = if (timeline.visibility == View.VISIBLE && timeline.height > 0) {
                    val tlLoc = IntArray(2)
                    timeline.getLocationInWindow(tlLoc)
                    val tlTop = tlLoc[1].toFloat()
                    minOf(tlTop + 8f * density, anchorTop - 6f * density)
                } else {
                    anchorTop - 8f * density
                }
                if (windowBottom > 0) {
                    anchorBottomMargin = (windowBottom - bottomTipY).coerceAtLeast(88f * density)
                }
                applyTvChatButtonPillState(true)
            }
            // Keep the bottom HUD visible behind the anchored pill until the composer closes
            binding.playerControls.root.removeCallbacks(controllerHideAction)
        }
        val hostWindow = activity?.window
        val prevHostSoftInputMode = hostWindow?.attributes?.softInputMode
        hostWindow?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val dialog = com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(requireContext()) { dismiss ->
            androidx.compose.runtime.LaunchedEffect(Unit) {
                emoteGroupsState.value = chatFragment?.getTvEmoteGroups().orEmpty()
                chatFragment?.observeEmoteUpdates()?.collect {
                    emoteGroupsState.value = chatFragment?.getTvEmoteGroups().orEmpty()
                }
            }
            val qrConnected by com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.connectedClients.collectAsState()
            val canShowQr = playbackService?.type == BasePlaybackService.STREAM
            com.github.andreyasadchy.xtra.ui.tv.ChatComposer(
                title = getString(R.string.tv_write_message),
                hint = getString(R.string.tv_message_hint),
                sendLabel = getString(R.string.tv_send),
                emoteGroups = emoteGroupsState.value,
                pillStyle = true,
                anchorCenterXPx = anchorCenterX,
                anchorBottomMarginPx = anchorBottomMargin,
                onDismiss = dismiss,
                onOpenQr = if (canShowQr) {
                    {
                        dismiss()
                        openTvChatQrPanel()
                    }
                } else null,
                qrConnectedCount = qrConnected,
            ) { text ->
                if (text.isNotBlank()) {
                    chatFragment?.sendExternalMessage(text)
                }
                dismiss()
            }
        }
        dialog.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener {
            if (prevHostSoftInputMode != null) {
                hostWindow?.setSoftInputMode(prevHostSoftInputMode)
            }
            if (_binding != null) {
                applyTvChatButtonPillState(false)
                if (areControlsShown()) {
                    binding.playerControls.chatMessage?.requestFocus()
                    if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                        binding.playerControls.root.removeCallbacks(controllerHideAction)
                        binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                    }
                }
            }
        }
    }

    private fun currentTvChannelAvatarUrl(): String? {
        val curStream = getTvDvrStream() ?: (activity as? MainActivity)?.currentPlayingStream()
        return playbackService?.channelImage?.takeIf { it.isNotBlank() }
            ?: curStream?.channelImage?.takeIf { it.isNotBlank() }
    }

    private fun openTvChatQrPanel(alignEnd: Boolean = true) {
        val ctx = context ?: return
        com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.openQrPanel(
            context = ctx,
            uiScope = viewLifecycleOwner.lifecycleScope,
            chatFragment = chatFragment,
            channelName = playbackService?.channelName,
            channelLogin = playbackService?.channelLogin,
            channelAvatar = currentTvChannelAvatarUrl(),
            alignEnd = alignEnd,
            owner = this,
        )
    }

    private var tooltipAnchor: View? = null
    private val tooltipShowAction = Runnable { showTooltipNow() }
    private val tvStatsAction = object : Runnable {
        override fun run() {
            val stats = tvStatsView ?: return
            if (view == null || !stats.isVisible) {
                return
            }
            stats.text = getStatsForNerds() ?: ""
            stats.postDelayed(this, 1000)
        }
    }

    /** Label of the quality being played, shown in the TV HUD. */
    open fun getCurrentQualityLabel(): String? {
        return playbackService?.quality?.name?.let {
            when (it) {
                VideoQuality.AUTO_QUALITY -> getString(R.string.auto)
                VideoQuality.SOURCE_QUALITY -> getString(R.string.source)
                VideoQuality.AUDIO_ONLY_QUALITY -> getString(R.string.audio_only)
                else -> it
            }
        }
    }

    open fun getStatsForNerds(): String? = null

    private var unmutedVolume = 100

    private fun updateTvClockVisibility(hudVisible: Boolean = _binding?.let { it.playerControls.root.isVisible && !controllerHiding } == true) {
        if (!isTv || _binding == null) {
            return
        }
        com.github.andreyasadchy.xtra.ui.tv.TvClockSettings.apply(
            context = requireContext(),
            hudClock = binding.playerControls.tvClock,
            alwaysClock = binding.tvAlwaysClock,
            isMaximized = isMaximized,
            fullHudVisible = hudVisible,
        )
    }

    private val tvFastOutSlowIn = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private var tvOsdContainer: LinearLayout? = null
    private var tvOsdIcon: ImageView? = null
    private var tvOsdText: TextView? = null
    private val tvOsdHideRunnable = Runnable { hideTvPlayerOsd(animate = true) }

    private fun computeTvOsdTargetTranslationY(hudLiftOverride: Boolean? = null): Float {
        val b = _binding ?: return 0f
        val density = resources.displayMetrics.density
        val overscanBottom = resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical).toFloat()
        val hudVisible = b.playerControls.root.isVisible && !controllerHiding
        val fullHudActive = hudLiftOverride ?: (hudVisible && !hudPeek)
        var shift = when {
            fullHudActive -> {
                val bottomHudView = b.playerControls.streamInfoLayout?.parent as? View
                val hudBlockH = bottomHudView?.height?.takeIf { it > 0 }?.toFloat() ?: (116f * density)
                hudBlockH + 14f * density
            }
            hudVisible && hudPeek -> 38f * density
            else -> 0f
        }
        val prefs = context?.prefs()
        if (prefs != null && isChatOpen && prefs.getInt(C.TV_CHAT_MODE, 0) == 2 && prefs.getBoolean(C.TV_CHAT_BAND_OVERLAY, false)) {
            val pos = getTvChatPosForMode(prefs, 2)
            val top = pos == 1 || pos == 3 || pos == 6
            if (!top) {
                val bandH = b.chatLayout.height.takeIf { it > 0 }?.toFloat() ?: 0f
                if (bandH > 0f) {
                    val chatLift = if (fullHudActive) resources.getDimension(R.dimen.tv_chat_lift) else 0f
                    val needed = (bandH + chatLift - overscanBottom + 12f * density).coerceAtLeast(0f)
                    if (needed > shift) {
                        shift = needed
                    }
                }
            }
        }
        return -shift
    }

    private fun ensureTvOsdView(): LinearLayout? {
        val b = _binding ?: return null
        val ctx = context ?: return null
        val density = resources.displayMetrics.density
        val existing = tvOsdContainer
        val borderColor = if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
            androidx.core.graphics.ColorUtils.setAlphaComponent(com.github.andreyasadchy.xtra.ui.tv.TvAccent.onDark, 0x59)
        } else {
            0x38FFFFFF
        }
        val iconTint = if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
            com.github.andreyasadchy.xtra.ui.tv.TvAccent.onDark
        } else {
            Color.WHITE
        }
        if (existing != null && existing.parent === b.playerLayout) {
            (existing.background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                borderColor,
            )
            tvOsdIcon?.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
            return existing
        }
        existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(0xEB18181B.toInt())
            setStroke((1f * density).toInt().coerceAtLeast(1), borderColor)
        }
        val iconView = ImageView(ctx).apply {
            val size = (18f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8f * density).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
            visibility = View.GONE
        }
        val textView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (680f * density).toInt()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 14f * density
            val padH = (18f * density).toInt()
            val padV = (10f * density).toInt()
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
            addView(iconView)
            addView(textView)
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
            marginStart = (32f * density).toInt()
            marginEnd = (32f * density).toInt()
        }
        b.playerLayout.addView(container, lp)
        tvOsdContainer = container
        tvOsdIcon = iconView
        tvOsdText = textView
        return container
    }

    private fun syncTvOsdLift(animate: Boolean, hudLiftOverride: Boolean? = null) {
        val osd = tvOsdContainer ?: return
        if (!osd.isVisible || _binding == null) return
        val targetTy = computeTvOsdTargetTranslationY(hudLiftOverride)
        if (animate) {
            osd.animate()
                .translationY(targetTy)
                .setDuration(200L)
                .setInterpolator(tvFastOutSlowIn)
                .withEndAction(null)
                .start()
        } else {
            osd.animate().cancel()
            osd.translationY = targetTy
        }
    }

    private fun hideTvPlayerOsd(animate: Boolean = false) {
        val osd = tvOsdContainer ?: return
        osd.removeCallbacks(tvOsdHideRunnable)
        osd.animate().cancel()
        if (!animate || !osd.isVisible) {
            osd.visibility = View.GONE
            osd.alpha = 0f
            return
        }
        osd.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(160L)
            .setInterpolator(tvFastOutSlowIn)
            .withEndAction {
                if (tvOsdContainer === osd) {
                    osd.visibility = View.GONE
                }
            }
            .start()
    }

    fun showTvPlayerOsd(message: String, iconRes: Int? = null, durationMs: Long = 1850L) {
        val ctx = context ?: return
        if (!isTv) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isMaximized || _binding == null) {
            val dur = if (durationMs > 2400L) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            com.github.andreyasadchy.xtra.ui.tv.TvToast.show(ctx, message, dur, iconRes)
            return
        }
        val osd = ensureTvOsdView() ?: return
        val density = resources.displayMetrics.density
        tvOsdIcon?.let { icon ->
            if (iconRes != null) {
                icon.setImageResource(iconRes)
                icon.visibility = View.VISIBLE
            } else {
                icon.visibility = View.GONE
            }
        }
        tvOsdText?.text = message
        osd.removeCallbacks(tvOsdHideRunnable)
        val targetTy = computeTvOsdTargetTranslationY()
        if (!osd.isVisible) {
            osd.animate().cancel()
            osd.visibility = View.VISIBLE
            osd.alpha = 0f
            osd.scaleX = 0.96f
            osd.scaleY = 0.96f
            osd.translationY = targetTy + 10f * density
            osd.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(targetTy)
                .setDuration(170L)
                .setInterpolator(tvFastOutSlowIn)
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
                .translationY(targetTy)
                .setDuration(130L)
                .setInterpolator(tvFastOutSlowIn)
                .withEndAction(null)
                .start()
        }
        osd.postDelayed(tvOsdHideRunnable, durationMs)
    }

    private fun tvChatModeLabel(mode: Int): String = when (mode) {
        1 -> getString(R.string.tv_chat_mode_side)
        2 -> getString(R.string.tv_chat_mode_below)
        else -> getString(R.string.tv_chat_mode_overlay)
    }

    private fun tvChatPositionLabel(mode: Int, pos: Int): String = when (mode) {
        1 -> if (pos == 2 || pos == 3 || pos == 5) getString(R.string.tv_chat_left) else getString(R.string.tv_chat_right)
        2 -> if (pos == 1 || pos == 3 || pos == 6) getString(R.string.tv_chat_above) else getString(R.string.tv_chat_under)
        else -> when (pos) {
            1 -> getString(R.string.tv_chat_top_right)
            2 -> getString(R.string.tv_chat_bottom_left)
            3 -> getString(R.string.tv_chat_top_left)
            4 -> getString(R.string.tv_chat_middle_right)
            5 -> getString(R.string.tv_chat_middle_left)
            6 -> getString(R.string.tv_chat_middle_top)
            7 -> getString(R.string.tv_chat_middle_bottom)
            else -> getString(R.string.tv_chat_bottom_right)
        }
    }

    private fun cycleTvChatMode() {
        val ctx = context ?: return
        val prefs = ctx.prefs()
        if (prefs.getBoolean(C.CHAT_DISABLE, false) || playbackService?.type == BasePlaybackService.OFFLINE_VIDEO) {
            return
        }
        val curMode = prefs.getInt(C.TV_CHAT_MODE, 0).coerceIn(0, 2)
        if (!isChatOpen) {
            showChat()
            showTvPlayerOsd("${getString(R.string.tv_chat_mode)} : ${tvChatModeLabel(curMode)}", R.drawable.tv_ic_chat_layout)
            return
        }
        if (!prefs.contains(tvChatModePosKey(curMode))) {
            saveTvChatPosForMode(prefs, curMode, getTvChatPosForMode(prefs, curMode))
        }
        val nextMode = (curMode + 1) % 3
        val nextPos = getTvChatPosForMode(prefs, nextMode)
        prefs.edit {
            putInt(C.TV_CHAT_MODE, nextMode)
            putInt(C.TV_CHAT_POSITION, nextPos)
        }
        if (_binding != null) {
            applyTvChatLayout()
        }
        showTvPlayerOsd("${getString(R.string.tv_chat_mode)} : ${tvChatModeLabel(nextMode)}", R.drawable.tv_ic_chat_layout)
    }

    private fun cycleTvChatPosition(delta: Int) {
        val ctx = context ?: return
        val prefs = ctx.prefs()
        if (prefs.getBoolean(C.CHAT_DISABLE, false) || playbackService?.type == BasePlaybackService.OFFLINE_VIDEO) {
            return
        }
        if (!isChatOpen) {
            showChat()
            return
        }
        val mode = prefs.getInt(C.TV_CHAT_MODE, 0)
        val pos = getTvChatPosForMode(prefs, mode)
        val next = when (mode) {
            1 -> if (pos == 2 || pos == 3 || pos == 5) 0 else 2
            2 -> if (pos == 1 || pos == 3 || pos == 6) 0 else 1
            else -> {
                // Clockwise around the screen: bottom-right, middle-bottom, bottom-left, middle-left, top-left, middle-top, top-right, middle-right
                val ring = listOf(0, 7, 2, 5, 3, 6, 1, 4)
                val idx = ring.indexOf(pos)
                if (idx >= 0) ring[(idx + delta + ring.size) % ring.size] else 0
            }
        }
        saveTvChatPosForMode(prefs, mode, next)
        if (_binding != null) {
            applyTvChatLayout()
        }
        showTvPlayerOsd("${getString(R.string.tv_chat_position)} : ${tvChatPositionLabel(mode, next)}", R.drawable.tv_ic_chat_layout)
    }

    fun togglePlayerMute() {
        val ctx = context ?: return
        val prefs = ctx.prefs()
        val current = currentVolumePercent()
        if (current > 0) {
            unmutedVolume = current
            prefs.edit { putInt(C.PLAYER_VOLUME, 0) }
            changeVolume(0f)
            updateTvVolumeButtonIcon(0)
            tvVolumePill?.setVolumePercent(0)
            showTvPlayerOsd(getString(R.string.tv_muted), R.drawable.tv_ic_volume)
        } else {
            val restored = unmutedVolume.takeIf { it > 0 } ?: 100
            prefs.edit { putInt(C.PLAYER_VOLUME, restored) }
            changeVolume(restored / 100f)
            updateTvVolumeButtonIcon(restored)
            tvVolumePill?.setVolumePercent(restored)
            showTvPlayerOsd(getString(R.string.tv_unmuted, restored), R.drawable.tv_ic_volume)
        }
    }

    fun canRunHiddenHudRemoteShortcuts(): Boolean {
        if (!isTv || !isMaximized || isRemoving || _binding == null || !useController) {
            return false
        }
        if (binding.chatLayout.hasFocus() || tvStreamEndNextTarget != null || tvScrubPosition >= 0L) {
            return false
        }
        return !binding.playerControls.root.isVisible || hudPeek
    }

    fun effectiveRemoteAction(action: String): String =
        effectiveDpadAction(action, (activity as? MainActivity)?.isTvPipOpen() == true)

    fun executeTvBackgroundRemoteAction(action: String): Boolean {
        if (!isTv || isMaximized || isRemoving || playbackService == null) {
            return false
        }
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        return when (action) {
            Keys.ACTION_OPEN_MINI_PLAYER -> {
                (activity as? MainActivity)?.reopenTvMiniPlayer()
                true
            }
            Keys.ACTION_STOP_STREAM -> {
                (activity as? MainActivity)?.stopTvPlayback() ?: run {
                    close()
                    (activity as? MainActivity)?.closePlayer()
                }
                true
            }
            Keys.ACTION_LAST_STREAM -> {
                (activity as? MainActivity)?.switchToLastWatchedStream() ?: false
            }
            Keys.ACTION_LIVE_ALERT -> {
                (activity as? MainActivity)?.openTvLiveAlert() ?: false
            }
            Keys.ACTION_MUTE -> {
                togglePlayerMute()
                true
            }
            Keys.ACTION_PLAY_PAUSE -> {
                playPause()
                true
            }
            else -> false
        }
    }

    fun executeTvRemoteAction(action: String): Boolean {
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        if (hudPeek && action != Keys.ACTION_SWITCH_PREV && action != Keys.ACTION_SWITCH_NEXT && action != Keys.ACTION_LAST_STREAM) {
            endHudPeek()
            hideController()
        }
        when (action) {
            Keys.ACTION_OPEN_MINI_PLAYER -> {
                if (!isMaximized) {
                    (activity as? MainActivity)?.reopenTvMiniPlayer()
                } else {
                    return false
                }
            }
            Keys.ACTION_STOP_STREAM -> {
                (activity as? MainActivity)?.stopTvPlayback() ?: run {
                    close()
                    (activity as? MainActivity)?.closePlayer()
                }
            }
            Keys.ACTION_LAST_STREAM -> {
                val main = activity as? MainActivity ?: return false
                if (_binding?.playerControls?.root?.hasFocus() == true) {
                    binding.dragView.requestFocus()
                }
                main.switchToLastWatchedStream()
            }
            Keys.ACTION_LIVE_ALERT -> {
                (activity as? MainActivity)?.openTvLiveAlert() ?: return false
            }
            Keys.ACTION_SWITCH_PREV, Keys.ACTION_SWITCH_NEXT -> {
                val delta = if (action == Keys.ACTION_SWITCH_NEXT) 1 else -1
                val main = activity as? MainActivity
                if (isLiveOrDvrScreen() && (main?.streamQueue?.size ?: 0) > 1) {
                    if (_binding?.playerControls?.root?.hasFocus() == true) {
                        binding.dragView.requestFocus()
                    }
                    main?.switchStream(delta)
                } else {
                    openHudFromDpad()
                }
            }
            Keys.ACTION_CYCLE_CHAT_MODE -> cycleTvChatMode()
            Keys.ACTION_CYCLE_CHAT_NEXT -> cycleTvChatPosition(1)
            Keys.ACTION_CYCLE_CHAT_PREV -> cycleTvChatPosition(-1)
            Keys.ACTION_TOGGLE_CHAT -> {
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    if (isChatOpen) {
                        hideChat()
                    } else {
                        showChat()
                    }
                }
            }
            Keys.ACTION_CHAT_PANEL -> {
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    showChatLayoutPanel()
                }
            }
            Keys.ACTION_SCROLL_CHAT -> {
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    if (isTvChatScrolling()) {
                        exitTvChatScrollMode()
                    } else {
                        enterTvChatScrollMode()
                    }
                }
            }
            Keys.ACTION_STREAM_SWITCHER -> (activity as? MainActivity)?.showStreamSwitcher()
            Keys.ACTION_GO_LIVE -> {
                getTvDvrStream()?.let { stream ->
                    (activity as? MainActivity)?.startStream(stream)
                    return true
                }
                if (playbackService?.type == BasePlaybackService.STREAM) {
                    seekToLivePosition()
                    updateTvLiveTimeline()
                }
            }
            Keys.ACTION_PLAY_PAUSE -> playPause()
            Keys.ACTION_RESTART_PLAYER -> restartTvPlayer()
            Keys.ACTION_RESTART_CHAT -> {
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    restartTvChat()
                }
            }
            Keys.ACTION_MUTE -> togglePlayerMute()
            Keys.ACTION_QUALITY -> showQualityDialog()
            Keys.ACTION_STATS -> toggleStatsForNerds()
            Keys.ACTION_POINTS -> showTvRewardsPanel()
            Keys.ACTION_CLIP -> triggerTvCreateClip()
            Keys.ACTION_SHARE_QR -> showTvShareQr()
            Keys.ACTION_PIP_SWAP -> {
                val main = activity as? MainActivity
                if (main?.isTvPipOpen() == true) {
                    main.swapTvPip()
                } else {
                    return false
                }
            }
            Keys.ACTION_PIP_SETTINGS -> {
                val main = activity as? MainActivity
                if (main?.isTvPipOpen() == true) {
                    showTvPipPanel()
                } else {
                    return false
                }
            }
            Keys.ACTION_PIP_CLOSE -> {
                val main = activity as? MainActivity
                if (main?.isTvPipOpen() == true) {
                    main.closeLatestTvPip()
                } else {
                    return false
                }
            }
            else -> return false
        }
        return true
    }

    fun showTvRewardsPanel() {
        val ctx = context ?: return
        val dvrStream = getTvDvrStream()
        val current = (activity as? MainActivity)?.currentPlayingStream()
        val chId = playbackService?.channelId ?: dvrStream?.channelId ?: current?.channelId
        val chLogin = playbackService?.channelLogin ?: dvrStream?.channelLogin ?: current?.channelLogin
        val gId = playbackService?.gameId ?: dvrStream?.gameId ?: current?.gameId
        val gName = playbackService?.gameName ?: dvrStream?.gameName ?: current?.gameName
        if (!isTv) {
            com.github.andreyasadchy.xtra.ui.mobile.MobileBottomSheets.showRewards(
                context = ctx,
                channelId = chId,
                channelLogin = chLogin,
                gameId = gId,
                gameName = gName,
            )
            return
        }
        com.github.andreyasadchy.xtra.ui.tv.TvRewardsPanel.show(
            context = ctx,
            channelId = chId,
            channelLogin = chLogin,
            gameId = gId,
            gameName = gName,
        )
    }

    fun showTvShareQr() {
        val ctx = context ?: return
        val dvrStream = getTvDvrStream()
        val archiveVid = (activity as? MainActivity)?.dvrChatVideoId?.takeIf { it.isNotBlank() }
        val url = when (playbackService?.type) {
            BasePlaybackService.STREAM -> {
                val exo = playbackService as? ExoPlayerService
                val expVod = isExperimentalVodActive()
                val archiveShareUrl = if (expVod) {
                    val edge = exo?.getExperimentalLiveEdgeMs() ?: 0L
                    val pos = getCurrentPosition() ?: edge
                    if (edge > 0L && edge - pos >= 12_000L && archiveVid != null) {
                        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.buildVideoUrl(archiveVid, pos)
                    } else null
                } else {
                    val behind = exo?.getLiveTimeShiftBehindMs() ?: 0L
                    val uptime = getStreamUptime()
                    if (behind >= 12_000L && uptime != null && archiveVid != null) {
                        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.buildVideoUrl(archiveVid, (uptime - behind).coerceAtLeast(0L))
                    } else null
                }
                archiveShareUrl ?: (playbackService?.channelLogin ?: dvrStream?.channelLogin)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "https://www.twitch.tv/$it" }
            }
            BasePlaybackService.VIDEO -> {
                val vid = playbackService?.videoId?.takeIf { it.isNotBlank() } ?: archiveVid
                if (vid != null) {
                    com.github.andreyasadchy.xtra.ui.tv.TvQrShare.buildVideoUrl(vid, getCurrentPosition())
                } else {
                    (playbackService?.channelLogin ?: dvrStream?.channelLogin)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "https://www.twitch.tv/$it" }
                }
            }
            BasePlaybackService.CLIP -> {
                val cid = playbackService?.clipId?.takeIf { it.isNotBlank() }
                if (cid != null) {
                    playbackService?.channelLogin?.takeIf { it.isNotBlank() }
                        ?.let { "https://www.twitch.tv/$it/clip/$cid" }
                        ?: "https://clips.twitch.tv/$cid"
                } else null
            }
            else -> null
        } ?: return
        val title = playbackService?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: playbackService?.channelName?.takeIf { it.isNotBlank() }
            ?: playbackService?.channelLogin
            ?: getString(R.string.tv_share_qr)
        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.showQrModal(
            context = ctx,
            title = title,
            subtitle = getString(R.string.tv_share_qr_subtitle),
            url = url,
        )
    }

    fun triggerTvCreateClip() {
        val ctx = context ?: return
        val dvrStream = getTvDvrStream()
        val archiveVid = (activity as? MainActivity)?.dvrChatVideoId?.takeIf { it.isNotBlank() }
        val broadcasterId = playbackService?.channelId ?: dvrStream?.channelId
        val channelLogin = playbackService?.channelLogin ?: dvrStream?.channelLogin
        fun launchClip(broadcastId: String?, videoId: String?, offsetSeconds: Double?) {
            if (!isTv) {
                com.github.andreyasadchy.xtra.ui.mobile.MobileBottomSheets.showCreateClip(
                    context = ctx,
                    broadcasterId = broadcasterId,
                    channelLogin = channelLogin,
                    broadcastId = broadcastId,
                    videoId = videoId,
                    offsetSeconds = offsetSeconds,
                )
            } else {
                com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                    context = ctx,
                    broadcasterId = broadcasterId,
                    channelLogin = channelLogin,
                    broadcastId = broadcastId,
                    videoId = videoId,
                    offsetSeconds = offsetSeconds,
                )
            }
        }
        when (playbackService?.type) {
            BasePlaybackService.STREAM -> {
                val exo = playbackService as? ExoPlayerService
                if (isExperimentalVodActive()) {
                    val edge = exo?.getExperimentalLiveEdgeMs() ?: 0L
                    val pos = getCurrentPosition() ?: edge
                    if (edge > 0L && edge - pos >= 12_000L && archiveVid != null) {
                        launchClip(null, archiveVid, (pos / 1000.0).coerceAtLeast(1.0))
                    } else {
                        launchClip(playbackService?.streamId, null, getStreamUptime()?.let { (it / 1000.0).coerceAtLeast(1.0) })
                    }
                } else {
                    val behind = exo?.getLiveTimeShiftBehindMs() ?: 0L
                    val uptime = getStreamUptime()
                    if (behind >= 12_000L && uptime != null && archiveVid != null) {
                        launchClip(null, archiveVid, ((uptime - behind) / 1000.0).coerceAtLeast(1.0))
                    } else {
                        launchClip(playbackService?.streamId, null, uptime?.let { ((it - behind) / 1000.0).coerceAtLeast(1.0) })
                    }
                }
            }
            BasePlaybackService.VIDEO -> {
                val vid = playbackService?.videoId?.takeIf { it.isNotBlank() } ?: archiveVid
                val posSec = getCurrentPosition()?.let { (it / 1000.0).coerceAtLeast(1.0) }
                launchClip(if (vid == null) dvrStream?.id else null, vid, posSec)
            }
            else -> {
                if (!channelLogin.isNullOrBlank()) {
                    launchClip(playbackService?.streamId ?: dvrStream?.id, null, null)
                }
            }
        }
    }

    private var customKeyDownSeen = 0
    private var customKeyPendingLongAction: String? = null
    private val customKeyLongRunnable = Runnable {
        val key = customKeyDownSeen
        val act = customKeyPendingLongAction
        if (key == 0 || act == null || !isAdded || isRemoving || _binding == null) return@Runnable
        customKeyDownSeen = 0
        customKeyPendingLongAction = null
        heldLongKeyCode = key
        if (isMaximized) {
            executeTvRemoteAction(act)
        } else {
            executeTvBackgroundRemoteAction(act)
        }
    }

    fun dispatchCustomRemoteKey(event: KeyEvent): Boolean {
        if (!isTv || isRemoving || _binding == null) {
            return false
        }
        val ctx = context ?: return false
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        if (isMaximized && binding.chatLayout.hasFocus()) {
            return false
        }
        val mainAct = activity as? MainActivity
        val pipOpen = isMaximized && mainAct?.isTvPipOpen() == true
        val rawShort = Keys.customShortActionForKey(ctx, event.keyCode)
        val rawLong = Keys.customLongActionForKey(ctx, event.keyCode)
        if (rawShort == null && rawLong == null) {
            return false
        }
        val shortAction = rawShort?.takeIf { act ->
            if (isMaximized) {
                act != Keys.ACTION_OPEN_MINI_PLAYER && (pipOpen || !Keys.isPipOnlyAction(act))
            } else {
                act == Keys.ACTION_LIVE_ALERT ||
                    (playbackService != null && Keys.isBackgroundCapableAction(act)) ||
                    (act == Keys.ACTION_LAST_STREAM && mainAct?.hasLastWatchedStream() == true)
            }
        }
        val longAction = rawLong?.takeIf { act ->
            if (isMaximized) {
                act != Keys.ACTION_OPEN_MINI_PLAYER && (pipOpen || !Keys.isPipOnlyAction(act))
            } else {
                act == Keys.ACTION_LIVE_ALERT ||
                    (playbackService != null && Keys.isBackgroundCapableAction(act)) ||
                    (act == Keys.ACTION_LAST_STREAM && mainAct?.hasLastWatchedStream() == true)
            }
        }
        if (shortAction == null && longAction == null) {
            return false
        }
        fun runAction(act: String) {
            if (isMaximized) {
                executeTvRemoteAction(act)
            } else {
                executeTvBackgroundRemoteAction(act)
            }
        }
        if (longAction == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && shortAction != null) {
                runAction(shortAction)
            }
            return true
        }
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 550L)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    customKeyDownSeen = event.keyCode
                    customKeyPendingLongAction = longAction
                    binding.root.removeCallbacks(customKeyLongRunnable)
                    binding.root.postDelayed(customKeyLongRunnable, longPressMs)
                } else if (customKeyDownSeen == event.keyCode && heldLongKeyCode != event.keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                    binding.root.removeCallbacks(customKeyLongRunnable)
                    heldLongKeyCode = event.keyCode
                    customKeyDownSeen = 0
                    customKeyPendingLongAction = null
                    runAction(longAction)
                }
            }
            KeyEvent.ACTION_UP -> {
                binding.root.removeCallbacks(customKeyLongRunnable)
                val wasDownSeen = customKeyDownSeen == event.keyCode
                customKeyDownSeen = 0
                customKeyPendingLongAction = null
                if (wasDownSeen && heldLongKeyCode != event.keyCode && !event.isCanceled) {
                    val heldMs = if (event.downTime > 0L) event.eventTime - event.downTime else 0L
                    if (heldMs >= longPressMs) {
                        runAction(longAction)
                    } else if (shortAction != null) {
                        runAction(shortAction)
                    }
                }
                if (heldLongKeyCode == event.keyCode) {
                    heldLongKeyCode = 0
                }
            }
            else -> {
                binding.root.removeCallbacks(customKeyLongRunnable)
                customKeyDownSeen = 0
                customKeyPendingLongAction = null
            }
        }
        return true
    }

    private fun openHudFromDpad() {
        hudOpenedByUser = true
        endHudPeek()
        showController(force = true)
        binding.playerControls.root.post {
            if (view != null && isMaximized && binding.playerControls.root.isVisible && !binding.playerControls.root.hasFocus()) {
                focusPrimaryControl()
            }
        }
    }

    private fun onTvControllerVisibility(visible: Boolean) {
        if (!isTv || _binding == null) {
            return
        }
        // the small window in a bottom corner goes up over the controls while they are shown (not for the info only of a
        // switch: no bar of buttons to make room for)
        val lift = visible && !hudPeek
        (activity as? MainActivity)?.onTvControlsVisibility(lift)
        updateTvClockVisibility(visible)
        with(binding) {
            if (chatLayout.isVisible) {
                chatLayout.animate().translationY(if (lift) getTvChatLift() else 0f).setDuration(200L).start()
            }
            syncTvOsdLift(animate = true, hudLiftOverride = lift)
            if (lift && (playerControls.streamInfoLayout?.parent as? View)?.height == 0) {
                playerControls.root.post {
                    if (_binding != null && playerControls.root.isVisible && !controllerHiding && !hudPeek) {
                        syncTvOsdLift(animate = true, hudLiftOverride = true)
                    }
                }
            }
            if (visible) {
                applyTvHudHeaderSize()
                updateTvFavoriteChip()
                syncTvHeaderBounds()
                syncPlayPauseButtonVisibility()
                // the button of the small window, only while it is open
                playerControls.tvPipSettings?.visibility = if ((activity as? MainActivity)?.isTvPipOpen() == true) View.VISIBLE else View.GONE
                syncTvPollsPredictionsButton()
                playerControls.tvQualityInfo?.let { info ->
                    val label = getCurrentQualityLabel()
                    info.text = label
                    info.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            } else {
                hideTooltip()
                seekPreview?.hide()
            }
        }
    }

    /** TV HUD: labels and tooltips on the player buttons, stats for nerds. */
    private fun setupTvHud() {
        binding.root.removeCallbacks(tvHudTicker)
        binding.root.post(tvHudTicker)
        if (!isTv) {
            setupMobileExtraControls()
            return
        }
        binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(hudFocusListener)
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(hudFocusListener)
        // loading circle in the color of accent
        if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
            (binding.bufferingIndicator as? android.widget.ProgressBar)?.indeterminateTintList = com.github.andreyasadchy.xtra.ui.tv.TvAccent.colorList
        }
        // the dimming of the streams, as it is now and when it changes
        com.github.andreyasadchy.xtra.ui.tv.TvDim.removeListener(tvDimListener)
        com.github.andreyasadchy.xtra.ui.tv.TvDim.addListener(tvDimListener)
        binding.root.post { applyTvDim() }
        with(binding.playerControls) {
            // seeking is done on the timeline on TV
            rewind.visibility = View.GONE
            fastForward.visibility = View.GONE
            val labels = listOf(
                playPause to R.string.pause,
                restart to R.string.tv_reload,
                seekLive to R.string.tv_go_to_live,
                vodGames to R.string.games,
                volume to R.string.volume,
                audioCompressor to R.string.audio_compressor,
                audioOnly to R.string.audio_only,
                quality to R.string.quality,
                speed to R.string.playback_speed,
                subtitles to R.string.subtitles,
                toggleChat to R.string.toggle_chat,
                toggleChatInput to R.string.toggle_chat_bar,
                follow to R.string.follow,
                download to R.string.download,
                sleepTimer to R.string.sleep_timer,
                aspectRatio to R.string.tv_aspect_ratio,
                menu to R.string.settings,
                minimize to R.string.minimize,
            )
            val buttons = labels.map { (button, label) ->
                if (button.contentDescription.isNullOrBlank()) {
                    button.contentDescription = getString(label)
                }
                button
            } + listOfNotNull(tvGoLive, streamList, statsForNerds, chatMessage, multiStream, tvDim, tvProxy, tvPipSettings, chatLayoutSettings, tvPollsPredictions, tvPoints, tvClip)
            buttons.forEach { button ->
                button.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        tooltipAnchor = v
                        tvTooltip?.removeCallbacks(tooltipShowAction)
                        tvTooltip?.visibility = View.INVISIBLE
                        tvTooltip?.postDelayed(tooltipShowAction, 600L)
                    } else if (tooltipAnchor == v) {
                        hideTooltip()
                    }
                }
            }
            // the TV buttons can be hidden in the settings (player buttons)
            val prefs = requireContext().prefs()
            tvGoLive?.let { button ->
                val dvrStream = getTvDvrStream()
                if (dvrStream != null) {
                    styleTvGoLiveButton(button)
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        hideTooltip()
                        (activity as? MainActivity)?.startStream(dvrStream)
                    }
                } else if (!isExperimentalVodActive()) {
                    button.visibility = View.GONE
                }
            }
            statsForNerds?.visibility = if (prefs.getBoolean(C.TV_BUTTON_STATS, true)) View.VISIBLE else View.GONE
            statsForNerds?.setOnClickListener {
                showController(force = true)
                toggleStatsForNerds()
            }
            if (!prefs.getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO && prefs.getBoolean(C.TV_BUTTON_CHAT_LAYOUT, true)) {
                chatLayoutSettings?.visibility = View.VISIBLE
                chatLayoutSettings?.setOnClickListener {
                    showController(force = true)
                    showChatLayoutPanel()
                }
            }
            if (isStreamScreen() && prefs.getBoolean(C.TV_BUTTON_MULTI_STREAM, true)) {
                multiStream?.let { btn ->
                    btn.visibility = View.VISIBLE
                    val badge = tvMultiStreamBadge
                        ?: com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.MultiStreamBadgeDrawable(resources.displayMetrics.density).also { d ->
                            tvMultiStreamBadge = d
                            btn.overlay.add(d)
                            btn.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                d.setBounds(0, 0, v.width, v.height)
                            }
                        }
                    if (btn.width > 0 && btn.height > 0) {
                        badge.setBounds(0, 0, btn.width, btn.height)
                    }
                    btn.setOnClickListener {
                        hideController()
                        (activity as? MainActivity)?.let { activity ->
                            val cur = activity.currentPlayingStream()
                            TvMultiStreamPicker.show(activity, Stream(
                                id = playbackService?.streamId ?: cur?.id,
                                channelId = playbackService?.channelId ?: cur?.channelId,
                                channelLogin = playbackService?.channelLogin ?: cur?.channelLogin,
                                channelName = playbackService?.channelName ?: cur?.channelName,
                                channelImageURL = cur?.channelImageURL,
                                gameId = cur?.gameId,
                                gameSlug = cur?.gameSlug,
                                gameName = playbackService?.gameName ?: cur?.gameName,
                                title = playbackService?.title ?: cur?.title,
                                thumbnailURL = cur?.thumbnailURL,
                                viewerCount = cur?.viewerCount,
                            ))
                        }
                    }
                }
                ensurePlayerCoStreamDetection()
            } else {
                multiStream?.visibility = View.GONE
                tvMultiStreamBadge?.count = 0
            }
            // dimming / Night Shift of the stream: OK turns it on / off, OK held opens its sliders
            tvDim?.let { button ->
                button.visibility = if (prefs.getBoolean(C.TV_BUTTON_DIM, false)) View.VISIBLE else View.GONE
                button.setOnClickListener {
                    showController(force = true)
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.toggle(requireContext())
                }
                button.setOnLongClickListener {
                    showController(force = true)
                    showTvDimPanel()
                    true
                }
            }
            // adblock & custom proxies: OK (short or long) opens the unified Ad blocker & Proxies panel
            tvProxy?.let { button ->
                if (playbackService?.type == BasePlaybackService.STREAM && prefs.getBoolean(C.TV_BUTTON_PROXY, true)) {
                    button.visibility = View.VISIBLE
                    updateTvProxyIcon()
                } else {
                    button.visibility = View.GONE
                }
                button.setOnClickListener {
                    showController(force = true)
                    showTvProxyPanel()
                }
                button.setOnLongClickListener {
                    showController(force = true)
                    showTvProxyPanel()
                    true
                }
            }
            // the small window: its button is there while it is open (see onTvControllerVisibility)
            tvPipSettings?.setOnClickListener {
                showController(force = true)
                showTvPipPanel()
            }
            tvPollsPredictions?.setOnClickListener {
                showController(force = true)
                showTvPollsPredictionsPanel()
            }
            ensurePlayerLiveEventsLoaded()
            syncTvPollsPredictionsButton()
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            if (playbackService?.type == BasePlaybackService.STREAM && isLoggedIn && !prefs.getBoolean(C.CHAT_DISABLE, false) && prefs.getBoolean(C.TV_BUTTON_CHAT_MESSAGE, true)) {
                chatMessage?.visibility = View.VISIBLE
                chatMessage?.setOnClickListener {
                    showController(force = true)
                    val lock = computeTvChatLockState()
                    if (lock != null) {
                        com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), lock.message, Toast.LENGTH_SHORT).show()
                    } else {
                        showChatComposer()
                    }
                }
            }
            if ((playbackService?.type == BasePlaybackService.STREAM || playbackService?.type == BasePlaybackService.VIDEO) &&
                isLoggedIn && prefs.getBoolean(C.TV_BUTTON_POINTS, true)
            ) {
                tvPoints?.visibility = View.VISIBLE
                tvPoints?.setOnClickListener {
                    showController(force = true)
                    showTvRewardsPanel()
                }
            } else {
                tvPoints?.visibility = View.GONE
            }
            if ((playbackService?.type == BasePlaybackService.STREAM || playbackService?.type == BasePlaybackService.VIDEO) &&
                prefs.getBoolean(C.TV_BUTTON_CLIP, false)
            ) {
                tvClip?.visibility = View.VISIBLE
                tvClip?.setOnClickListener {
                    showController(force = true)
                    triggerTvCreateClip()
                }
            } else {
                tvClip?.visibility = View.GONE
            }
            // player clock (#61): hidden, with controls only, or always shown on stream
            updateTvClockVisibility()
            tvClockObserverJob?.cancel()
            tvClockObserverJob = viewLifecycleOwner.lifecycleScope.launch {
                com.github.andreyasadchy.xtra.ui.tv.TvClockSettings.changes.collect {
                    updateTvClockVisibility()
                }
            }
            // "position / duration": the slash only when both are shown
            tvTimeSeparator?.let { separator ->
                val updateSeparator = {
                    if (_binding != null) {
                        separator.visibility = if (position.isVisible && duration.isVisible) View.VISIBLE else View.GONE
                    }
                }
                updateSeparator()
                if (!tvTimeSeparatorListenerAdded) {
                    tvTimeSeparatorListenerAdded = true
                    duration.viewTreeObserver.addOnGlobalLayoutListener { updateSeparator() }
                }
            }
            // Nuvio style: the focused button is a white disc, its icon takes the color of accent (ambiance color)
            val focusedIcon = com.github.andreyasadchy.xtra.ui.tv.TvAccent.onLight
            buttons.forEach { button ->
                val previous = button.onFocusChangeListener
                button.setOnFocusChangeListener { v, hasFocus ->
                    previous?.onFocusChange(v, hasFocus)
                    when (v) {
                        is android.widget.ImageView -> v.setColorFilter(
                            when {
                                v === volume && tvVolumePill?.isShowing == true -> Color.WHITE
                                hasFocus -> focusedIcon
                                v.isEnabled -> Color.WHITE
                                else -> Color.GRAY
                            }
                        )
                        is android.widget.Button -> {
                            v.foregroundTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) focusedIcon else Color.WHITE)
                            v.setTextColor(if (hasFocus) focusedIcon else Color.WHITE)
                        }
                        is android.widget.TextView -> {
                            v.setTextColor(if (hasFocus) focusedIcon else Color.WHITE)
                        }
                    }
                }
            }
            applyTvHudHeaderSize()
            updateTvAvatars()
            updateTvChatLockUi()
        }
    }

    private class TvChatLock(val message: String, val unlockAtMs: Long? = null)
    private var chatUnlockTimerJob: kotlinx.coroutines.Job? = null
    private var isTvChatLocked: Boolean? = null
    private var chatLockObservedFragment: ChatFragment? = null

    fun onChatFragmentReady(fragment: ChatFragment) {
        if (!isTv || playbackService?.type != BasePlaybackService.STREAM) return
        chatFragment = fragment
        context?.let { ctx ->
            com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.onLiveChatAttached(
                context = ctx,
                chatFragment = fragment,
                channelName = playbackService?.channelName,
                channelLogin = playbackService?.channelLogin,
                channelAvatar = currentTvChannelAvatarUrl(),
                owner = this,
            )
        }
        if (chatLockObservedFragment === fragment || fragment.view == null) {
            updateTvChatLockUi()
            return
        }
        chatLockObservedFragment = fragment
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    fragment.getEffectiveRoomState()?.collect { updateTvChatLockUi() }
                }
                launch {
                    fragment.getUserRoomBadges()?.collect { updateTvChatLockUi() }
                }
            }
        }
        updateTvChatLockUi()
    }

    private fun computeTvChatLockState(): TvChatLock? {
        if (!isTv || playbackService?.type != BasePlaybackService.STREAM) return null
        val ctx = context ?: return null
        val userId = ctx.tokenPrefs().getString(C.USER_ID, null)
        if (!userId.isNullOrBlank() && userId == playbackService?.channelId) return null
        val badges = chatFragment?.getUserRoomBadges()?.value
        if (badges != null && badges.any {
                it == "broadcaster" || it == "moderator" || it == "lead_moderator" ||
                    it == "vip" || it == "staff" || it == "admin" || it == "global_mod"
            }) {
            return null
        }
        val roomState = chatFragment?.getEffectiveRoomState()?.value ?: return null
        if (roomState.subs == "1" && badges != null && "subscriber" !in badges && "founder" !in badges) {
            return TvChatLock(com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(ctx, "SUBSCRIBERS_ONLY"))
        }
        val followersMin = roomState.followers?.toIntOrNull()
        if (followersMin != null && followersMin >= 0 && viewModel.accountFollowKnown.value) {
            val followedAt = viewModel.accountFollowedAtMs.value
            if (followedAt == null) {
                return TvChatLock(
                    com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(
                        ctx,
                        "FOLLOWERS_ONLY",
                        followersMinutes = followersMin
                    )
                )
            }
            if (followersMin > 0) {
                val unlockAt = followedAt + followersMin * 60_000L
                val now = System.currentTimeMillis()
                if (now < unlockAt) {
                    val remSec = (unlockAt - now + 999L) / 1000L
                    return TvChatLock(
                        com.github.andreyasadchy.xtra.util.chat.TvChatNotices.restrictionMessage(
                            ctx,
                            "msg_followersonly_followed",
                            followersMinutes = followersMin,
                            remainingSeconds = remSec
                        ),
                        unlockAtMs = unlockAt
                    )
                }
            }
        }
        return null
    }

    private fun updateTvChatLockUi() {
        if (!isTv || _binding == null) return
        val lock = computeTvChatLockState()
        val locked = lock != null
        if (isTvChatLocked != locked) {
            isTvChatLocked = locked
            binding.playerControls.chatMessage?.let { button ->
                button.setImageResource(if (locked) R.drawable.tv_ic_chat_send_locked else R.drawable.tv_ic_chat_send)
                button.setColorFilter(
                    when {
                        button.hasFocus() -> com.github.andreyasadchy.xtra.ui.tv.TvAccent.onLight
                        button.isEnabled -> Color.WHITE
                        else -> Color.GRAY
                    }
                )
            }
        }
        chatUnlockTimerJob?.cancel()
        chatUnlockTimerJob = if (lock?.unlockAtMs != null && view != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                delay((lock.unlockAtMs - System.currentTimeMillis() + 50L).coerceAtLeast(50L))
                updateTvChatLockUi()
            }
        } else {
            null
        }
    }

    private var tvFavoritesObserverJob: Job? = null
    private var tvHeaderLayoutListenerAdded = false
    private var tvTimeSeparatorListenerAdded = false
    private var appliedTvHudHeaderScale = 0f
    private var lastLoadedChannelAvatarUrl: String? = null
    private var lastLoadedCategoryGameId: String? = null
    private val tvHeaderLayoutListener = View.OnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            syncTvHeaderBounds()
        }
    }

    private fun tvHudHeaderScale(): Float {
        val ctx = context ?: return 1f
        return when (ctx.prefs().getString(C.TV_HUD_HEADER_SIZE, "normal")) {
            "medium" -> 1.15f
            "large" -> 1.30f
            "xlarge" -> 1.45f
            else -> 1f
        }
    }

    private fun applyTvHudHeaderSize() {
        if (!isTv || _binding == null) return
        val scale = tvHudHeaderScale()
        if (scale == appliedTvHudHeaderScale) return
        appliedTvHudHeaderScale = scale
        val density = resources.displayMetrics.density
        val chipH = (28f * scale * density + 0.5f).toInt()
        val avatarSize = (42f * scale * density + 0.5f).toInt()
        val avatarMarginEnd = (11f * scale.coerceAtMost(1.25f) * density + 0.5f).toInt()
        val channelPadH = (11f * scale * density + 0.5f).toInt()
        val favPad = (5.5f * scale * density + 0.5f).toInt()
        val catPadStart = (8f * scale * density + 0.5f).toInt()
        val catPadEnd = (11f * scale * density + 0.5f).toInt()
        val catAvatarW = (15f * scale * density + 0.5f).toInt()
        val catAvatarH = (20f * scale * density + 0.5f).toInt()
        val catAvatarEnd = (6f * scale.coerceAtMost(1.25f) * density + 0.5f).toInt()
        val titleTopMargin = (5f * scale.coerceAtMost(1.25f) * density + 0.5f).toInt()
        with(binding.playerControls) {
            tvChannelAvatarFrame?.let { frame ->
                (frame.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.width != avatarSize || lp.height != avatarSize || lp.marginEnd != avatarMarginEnd) {
                        lp.width = avatarSize
                        lp.height = avatarSize
                        lp.marginEnd = avatarMarginEnd
                        frame.layoutParams = lp
                    }
                }
            }
            channel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f * scale)
            channel.setPaddingRelative(channelPadH, 0, channelPadH, 0)
            channel.layoutParams?.let { lp ->
                if (lp.height != chipH) {
                    lp.height = chipH
                    channel.layoutParams = lp
                }
            }
            tvFavoriteChip?.let { chip ->
                chip.setPadding(favPad, favPad, favPad, favPad)
                chip.layoutParams?.let { lp ->
                    if (lp.width != chipH || lp.height != chipH) {
                        lp.width = chipH
                        lp.height = chipH
                        chip.layoutParams = lp
                    }
                }
            }
            tvCategoryChip?.let { chip ->
                chip.setPaddingRelative(catPadStart, 0, catPadEnd, 0)
                chip.layoutParams?.let { lp ->
                    if (lp.height != chipH) {
                        lp.height = chipH
                        chip.layoutParams = lp
                    }
                }
            }
            tvCategoryAvatar?.let { avatar ->
                (avatar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.width != catAvatarW || lp.height != catAvatarH || lp.marginEnd != catAvatarEnd) {
                        lp.width = catAvatarW
                        lp.height = catAvatarH
                        lp.marginEnd = catAvatarEnd
                        avatar.layoutParams = lp
                    }
                }
            }
            category.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * scale)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * scale)
            (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.topMargin != titleTopMargin) {
                    lp.topMargin = titleTopMargin
                    title.layoutParams = lp
                }
            }
        }
    }

    private fun prefillTvStreamUiBeforeService() {
        if (!isTv || _binding == null) return
        applyTvHudHeaderSize()
        if (!isStreamScreen()) return
        val ctx = context ?: return
        val prefs = ctx.prefs()
        val stream = (activity as? MainActivity)?.currentPlayingStream() ?: return
        with(binding.playerControls) {
            val channelLogin = stream.channelLogin
            val channelName = stream.channelName
            val displayName = if (channelLogin != null && !channelLogin.equals(channelName, true)) {
                when (prefs.getString(C.UI_NAME_DISPLAY, "0")) {
                    "0" -> "$channelName ($channelLogin)"
                    "1" -> channelName
                    else -> channelLogin
                }
            } else {
                channelName ?: channelLogin
            }
            if (prefs.getBoolean(C.PLAYER_CHANNEL, true) && !displayName.isNullOrBlank()) {
                channel.visibility = View.VISIBLE
                channel.text = displayName
                channel.setOnClickListener {
                    findNavController().navigate(
                        ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                            channelId = playbackService?.channelId ?: stream.channelId,
                            channelLogin = playbackService?.channelLogin ?: stream.channelLogin,
                            channelName = playbackService?.channelName ?: stream.channelName,
                        )
                    )
                    leaveForBrowsing()
                }
            } else {
                channel.visibility = View.GONE
            }
            val titleText = stream.title
            if (!titleText.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
                title.visibility = View.VISIBLE
                title.text = titleText.trim()
            } else {
                title.visibility = View.GONE
            }
            val gameName = stream.gameName
            if (!gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
                val openCategory = View.OnClickListener {
                    findNavController().navigate(
                        if (prefs.getBoolean(C.UI_GAME_PAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = playbackService?.gameId ?: stream.gameId,
                                gameSlug = playbackService?.gameSlug ?: stream.gameSlug,
                                gameName = gameName,
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = playbackService?.gameId ?: stream.gameId,
                                gameSlug = playbackService?.gameSlug ?: stream.gameSlug,
                                gameName = gameName,
                            )
                        }
                    )
                    leaveForBrowsing()
                }
                category.visibility = View.VISIBLE
                category.text = gameName
                category.setOnClickListener(openCategory)
                tvCategoryChip?.setOnClickListener(openCategory)
            } else {
                category.visibility = View.GONE
            }
            if (prefs.getBoolean(C.TV_BUTTON_STREAM_LIST, true)) {
                streamList?.visibility = View.VISIBLE
                streamList?.setOnClickListener {
                    hideController()
                    (activity as? MainActivity)?.showStreamSwitcher()
                }
            }
            if (prefs.getBoolean(C.PLAYER_RESTART, true)) {
                restart.visibility = View.VISIBLE
                restart.setOnClickListener {
                    showController(force = true)
                    restartPlayer()
                }
            }
        }
        updateViewerCount(stream.viewerCount)
        if (prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
            stream.createdAt?.let { created ->
                Instant.parseOrNull(created)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 }?.let { createdMs ->
                    updateUptime(createdMs)
                }
            }
        }
        setupTvHud()
    }

    private fun updateTvFavoriteChip() {
        if (!isTv || _binding == null) return
        val chip = binding.playerControls.tvFavoriteChip ?: return
        val ctx = context ?: return
        val curStream = getTvDvrStream() ?: (activity as? MainActivity)?.currentPlayingStream()
        val login = playbackService?.channelLogin?.takeIf { it.isNotBlank() }
            ?: curStream?.channelLogin?.takeIf { it.isNotBlank() }
        val chName = playbackService?.channelName?.takeIf { it.isNotBlank() }
            ?: curStream?.channelName?.takeIf { it.isNotBlank() }
        if (!isLiveOrDvrScreen() || login == null || !ctx.prefs().getBoolean(C.TV_PLAYER_FAVORITE_STAR, true)) {
            chip.visibility = View.GONE
            return
        }
        com.github.andreyasadchy.xtra.ui.tv.TvFavorites.load(ctx)
        val isFav = com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(login)
        val density = resources.displayMetrics.density
        chip.visibility = View.VISIBLE
        (chip.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val wantMargin = if (binding.playerControls.channel.isVisible) (6f * density).toInt() else 0
            if (lp.marginStart != wantMargin) {
                lp.marginStart = wantMargin
                chip.layoutParams = lp
            }
        }
        chip.setImageResource(if (isFav) R.drawable.tv_ic_star else R.drawable.tv_ic_star_outline)
        fun applyStarTint(focused: Boolean, fav: Boolean) {
            val tint = when {
                focused && fav -> 0xFFB45309.toInt()
                focused -> 0xFF111014.toInt()
                fav -> 0xFFFFCA28.toInt()
                else -> 0xEBFFFFFF.toInt()
            }
            chip.setColorFilter(tint)
        }
        applyStarTint(chip.hasFocus(), isFav)
        chip.contentDescription = getString(if (isFav) R.string.tv_favorite_remove else R.string.tv_favorite_add)
        chip.setOnFocusChangeListener { _, hasFocus ->
            val nowFav = com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(login)
            applyStarTint(hasFocus, nowFav)
        }
        chip.setOnClickListener {
            val label = chName ?: login
            val currentlyFav = com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(login)
            if (currentlyFav) {
                com.github.andreyasadchy.xtra.ui.tv.TvFavorites.remove(ctx, login)
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                    ctx,
                    getString(R.string.tv_favorite_removed, label),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                com.github.andreyasadchy.xtra.ui.tv.TvFavorites.add(ctx, login, chName)
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                    ctx,
                    getString(R.string.tv_favorite_added, label),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            showController(force = true)
            updateTvFavoriteChip()
        }
    }

    private fun syncTvHeaderBounds() {
        if (!isTv || _binding == null) return
        val b = binding
        val playerW = b.playerLayout.width
        if (playerW <= 0) return
        val density = resources.displayMetrics.density
        val scale = tvHudHeaderScale()
        val overscanH = resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val rightReserved = overscanH + (188f * density).toInt()
        val avatarW = if (b.playerControls.tvChannelAvatarFrame?.isVisible == true) {
            ((42f * scale + 11f * scale.coerceAtMost(1.25f)) * density).toInt()
        } else {
            0
        }
        val availableByRight = (playerW - overscanH - rightReserved - avatarW).coerceAtLeast((180f * density).toInt())
        val widthRatio = (0.64f + (scale - 1f) * 0.22f).coerceAtMost(0.74f)
        val comfortableCap = ((playerW * widthRatio).toInt() - avatarW).coerceAtLeast((180f * density).toInt())
        val maxBlockW = minOf(availableByRight, comfortableCap)
        if (b.playerControls.title.maxWidth != maxBlockW) {
            b.playerControls.title.maxWidth = maxBlockW
        }
        val favReserved = if (b.playerControls.tvFavoriteChip?.isVisible == true) ((28f * scale + 6f) * density).toInt() else 0
        val usableRowW = (maxBlockW - favReserved).coerceAtLeast((150f * density).toInt())
        val catIconAllowance = ((36f * scale) * density).toInt()
        val bothChips = b.playerControls.channel.isVisible && b.playerControls.tvCategoryChip?.isVisible == true
        val (targetChMax, targetCatMax) = if (bothChips) {
            val chipBudget = (usableRowW - (8f * density).toInt()).coerceAtLeast((150f * density).toInt())
            val chMax = (chipBudget * 0.50f).toInt().coerceIn((105f * density).toInt(), ((280f * scale) * density).toInt())
            val catMax = (chipBudget - chMax - catIconAllowance).coerceIn((85f * density).toInt(), ((260f * scale) * density).toInt())
            chMax to catMax
        } else {
            val chMax = minOf(usableRowW, ((320f * scale) * density).toInt())
            val catMax = minOf((usableRowW - catIconAllowance).coerceAtLeast((105f * density).toInt()), ((300f * scale) * density).toInt())
            chMax to catMax
        }
        if (b.playerControls.channel.maxWidth != targetChMax) {
            b.playerControls.channel.maxWidth = targetChMax
        }
        if (b.playerControls.category.maxWidth != targetCatMax) {
            b.playerControls.category.maxWidth = targetCatMax
        }
    }

    /** TV: picture of the channel next to its name, favorite star on live streams, box art of the category inside its capsule, and safe width bounds. */
    private fun updateTvAvatars() {
        if (!isTv || _binding == null) {
            return
        }
        applyTvHudHeaderSize()
        if (!tvHeaderLayoutListenerAdded) {
            tvHeaderLayoutListenerAdded = true
            binding.playerLayout.addOnLayoutChangeListener(tvHeaderLayoutListener)
        }
        if (tvFavoritesObserverJob?.isActive != true && view != null) {
            tvFavoritesObserverJob = viewLifecycleOwner.lifecycleScope.launch {
                com.github.andreyasadchy.xtra.ui.tv.TvFavorites.items.collectLatest {
                    if (_binding != null) {
                        updateTvFavoriteChip()
                        syncTvHeaderBounds()
                    }
                }
            }
        }
        updateTvFavoriteChip()
        val curStream = getTvDvrStream() ?: (activity as? MainActivity)?.currentPlayingStream()
        with(binding.playerControls) {
            val context = requireContext()
            val density = resources.displayMetrics.density
            tvChannelAvatar?.let { avatar ->
                val image = playbackService?.channelImage?.takeIf { it.isNotBlank() }
                    ?: curStream?.channelImage?.takeIf { it.isNotBlank() }
                if (!image.isNullOrBlank()) {
                    com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.updateChannelAvatar(
                        channelLogin = playbackService?.channelLogin,
                        avatarUrl = image,
                    )
                }
                if (!image.isNullOrBlank() && channel.isVisible) {
                    avatar.visibility = View.VISIBLE
                    tvChannelAvatarFrame?.visibility = View.VISIBLE
                    if (image != lastLoadedChannelAvatarUrl || avatar.drawable == null) {
                        lastLoadedChannelAvatarUrl = image
                        context.imageLoader.enqueue(
                            coil3.request.ImageRequest.Builder(context).apply {
                                data(image)
                                transformations(coil3.transform.CircleCropTransformation())
                                target(avatar)
                            }.build()
                        )
                    }
                } else {
                    lastLoadedChannelAvatarUrl = null
                    avatar.visibility = View.GONE
                    tvChannelAvatarFrame?.visibility = View.GONE
                }
            }
            val showCatChip = category.isVisible && !category.text.isNullOrBlank()
            val hasBeforeCat = channel.isVisible || tvFavoriteChip?.isVisible == true
            tvCategoryChip?.let { chip ->
                chip.visibility = if (showCatChip) View.VISIBLE else View.GONE
                (chip.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    val wantMargin = if (hasBeforeCat) (6f * density).toInt() else 0
                    if (lp.marginStart != wantMargin) {
                        lp.marginStart = wantMargin
                        chip.layoutParams = lp
                    }
                }
            }
            val headerChips = listOfNotNull(
                channel.takeIf { it.isVisible },
                tvFavoriteChip?.takeIf { it.isVisible },
                tvCategoryChip?.takeIf { it.isVisible },
            )
            headerChips.forEachIndexed { idx, v ->
                v.nextFocusLeftId = headerChips.getOrElse(idx - 1) { v }.id
                v.nextFocusRightId = headerChips.getOrElse(idx + 1) { v }.id
                v.nextFocusUpId = v.id
            }
            val gameId = playbackService?.gameId?.takeIf { it.isNotBlank() }
                ?: curStream?.gameId?.takeIf { it.isNotBlank() }
            tvCategoryAvatar?.let { avatar ->
                if (!gameId.isNullOrBlank() && showCatChip) {
                    avatar.visibility = View.VISIBLE
                    if (gameId != lastLoadedCategoryGameId || avatar.drawable == null) {
                        lastLoadedCategoryGameId = gameId
                        context.imageLoader.enqueue(
                            coil3.request.ImageRequest.Builder(context).apply {
                                data("https://static-cdn.jtvnw.net/ttv-boxart/$gameId-52x72.jpg")
                                transformations(coil3.transform.RoundedCornersTransformation(4f * density))
                                target(avatar)
                            }.build()
                        )
                    }
                } else {
                    lastLoadedCategoryGameId = null
                    avatar.visibility = View.GONE
                }
            }
        }
        syncTvHeaderBounds()
        binding.playerLayout.post { syncTvHeaderBounds() }
    }

    private fun showTooltipNow() {
        if (tvVolumePill?.isShowing == true) return
        val tooltip = _binding?.playerControls?.tvTooltip ?: return
        val anchor = tooltipAnchor ?: return
        if (!anchor.hasFocus() || activity?.hasWindowFocus() == false || anchor.contentDescription.isNullOrBlank()) {
            return
        }
        val root = binding.playerControls.root
        tooltip.text = anchor.contentDescription
        // the name of the button in the color of accent, like its icon
        tooltip.setTextColor(com.github.andreyasadchy.xtra.ui.tv.TvAccent.onLight)
        tooltip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val rootLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        anchor.getLocationInWindow(anchorLocation)
        val margin = 8 * resources.displayMetrics.density
        val x = anchorLocation[0] - rootLocation[0] + anchor.width / 2f - tooltip.measuredWidth / 2f
        tooltip.translationX = x.coerceIn(margin, (root.width - tooltip.measuredWidth - margin).coerceAtLeast(margin))
        tooltip.translationY = anchorLocation[1] - rootLocation[1] - tooltip.measuredHeight - margin
        tooltip.alpha = 0f
        tooltip.visibility = View.VISIBLE
        tooltip.animate().alpha(1f).setDuration(150L).start()
    }

    private fun hideTooltip() {
        val tooltip = _binding?.playerControls?.tvTooltip ?: return
        tooltip.removeCallbacks(tooltipShowAction)
        tooltip.animate().cancel()
        tooltip.visibility = View.INVISIBLE
        tooltipAnchor = null
    }

    private var tvTopRightStack: LinearLayout? = null
    private var tvAdblockView: TextView? = null
    private var tvPredictionView: TextView? = null
    private var tvPredictionCurrent: com.github.andreyasadchy.xtra.model.chat.Prediction? = null
    private var tvPredictionSeconds: Int? = null
    private var tvPredictionStartShownId: String? = null
    private var tvPredictionEndShownId: String? = null
    private val tvPredictionHideRunnable = Runnable { hideTvPredictionPopup() }
    private var tvPollView: TextView? = null
    private var tvPollCurrent: com.github.andreyasadchy.xtra.model.chat.Poll? = null
    private var tvPollSeconds: Int? = null
    private var tvPollStartShownId: String? = null
    private var tvPollEndShownId: String? = null
    private val tvPollHideRunnable = Runnable { hideTvPollPopup() }
    private var frameRateModeCandidate = 0
    private var frameRateModeApplied = 0
    private var frameRateModeChecks = 0

    private var tvOledProtectionActive = false
    private var tvOledShiftStep = 0
    private var tvOledLastShiftElapsed = 0L
    private val tvOledOffsets = arrayOf(
        0 to 0,
        2 to 1,
        3 to 0,
        2 to -2,
        0 to -3,
        -2 to -2,
        -3 to 0,
        -2 to 2,
    )

    fun isActivelyPlayingVideo(): Boolean =
        isAdded && view != null && isMaximized && isPlayingNow()

    private fun updateTvOledProtection() {
        val ctx = context ?: return
        val b = _binding ?: return
        val active = isTv &&
            isMaximized &&
            isPlayingNow() &&
            !areControlsShown() &&
            !hudPeek &&
            ctx.prefs().getBoolean(C.TV_OLED_PROTECTION, false) &&
            ((activity as? MainActivity)?.idleDurationMs() ?: 0L) >= 180_000L
        if (!active) {
            restoreTvOledProtection()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!tvOledProtectionActive) {
            tvOledProtectionActive = true
            tvOledLastShiftElapsed = now
            b.tvAlwaysClock?.let { clock ->
                if (clock.isVisible) {
                    clock.animate().alpha(minOf(clock.alpha, 0.38f)).setDuration(800L).start()
                }
            }
            if (b.chatLayout.isVisible) {
                b.chatLayout.animate().alpha(0.38f).setDuration(800L).start()
            }
            tvTopRightStack?.animate()?.alpha(0.38f)?.setDuration(800L)?.start()
        }
        if (now - tvOledLastShiftElapsed >= 45_000L) {
            tvOledLastShiftElapsed = now
            tvOledShiftStep = (tvOledShiftStep + 1) % tvOledOffsets.size
            val (ox, oy) = tvOledOffsets[tvOledShiftStep]
            val density = resources.displayMetrics.density
            val dx = ox * density
            val dy = oy * density
            b.tvAlwaysClock?.apply {
                translationX = dx
                translationY = dy
            }
            tvTopRightStack?.apply {
                translationX = dx
                translationY = dy
            }
            if (b.chatLayout.isVisible) {
                b.chatLayout.translationX = dx
                b.chatLayout.translationY = dy
            }
        }
    }

    private fun restoreTvOledProtection() {
        if (!tvOledProtectionActive) {
            return
        }
        tvOledProtectionActive = false
        val b = _binding ?: return
        b.tvAlwaysClock?.let { clock ->
            clock.animate().cancel()
            clock.translationX = 0f
            clock.translationY = 0f
        }
        updateTvClockVisibility()
        b.chatLayout.animate().cancel()
        b.chatLayout.alpha = 1f
        b.chatLayout.translationX = 0f
        b.chatLayout.translationY = if (b.playerControls.root.isVisible && !controllerHiding && !hudPeek) getTvChatLift() else 0f
        tvTopRightStack?.let { stack ->
            stack.animate().cancel()
            stack.alpha = 1f
            stack.translationX = 0f
            stack.translationY = 0f
        }
    }

    /** Mobile: extra XtraTV player buttons (Go Live pill, Adblock & Proxies, Points, Clip, Polls, Stats). */
    private fun setupMobileExtraControls() {
        val ctx = context ?: return
        val prefs = ctx.prefs()
        val isLoggedIn = !TwitchApiHelper.getGQLHeaders(ctx, true)[C.HEADER_TOKEN].isNullOrBlank() ||
            !TwitchApiHelper.getHelixHeaders(ctx)[C.HEADER_TOKEN].isNullOrBlank()
        with(binding.playerControls) {
            tvGoLive?.let { button ->
                val dvrStream = getTvDvrStream()
                if (dvrStream != null) {
                    styleTvGoLiveButton(button)
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        (activity as? MainActivity)?.startStream(dvrStream)
                    }
                } else if (!isExperimentalVodActive()) {
                    button.visibility = View.GONE
                }
            }
            statsForNerds?.let { btn ->
                btn.visibility = if (prefs.getBoolean(C.TV_BUTTON_STATS, true)) View.VISIBLE else View.GONE
                btn.setOnClickListener {
                    showController(force = true)
                    toggleStatsForNerds()
                }
            }
            tvProxy?.let { btn ->
                if (playbackService?.type == BasePlaybackService.STREAM && prefs.getBoolean(C.TV_BUTTON_PROXY, true)) {
                    btn.visibility = View.VISIBLE
                    updateTvProxyIcon()
                } else {
                    btn.visibility = View.GONE
                }
                btn.setOnClickListener {
                    showController(force = true)
                    showTvProxyPanel()
                }
            }
            tvPoints?.let { btn ->
                if ((playbackService?.type == BasePlaybackService.STREAM || playbackService?.type == BasePlaybackService.VIDEO) &&
                    isLoggedIn && prefs.getBoolean(C.TV_BUTTON_POINTS, true)
                ) {
                    btn.visibility = View.VISIBLE
                    btn.setOnClickListener {
                        showController(force = true)
                        showTvRewardsPanel()
                    }
                } else {
                    btn.visibility = View.GONE
                }
            }
            tvClip?.let { btn ->
                if ((playbackService?.type == BasePlaybackService.STREAM || playbackService?.type == BasePlaybackService.VIDEO) &&
                    prefs.getBoolean(C.TV_BUTTON_CLIP, false)
                ) {
                    btn.visibility = View.VISIBLE
                    btn.setOnClickListener {
                        showController(force = true)
                        triggerTvCreateClip()
                    }
                } else {
                    btn.visibility = View.GONE
                }
            }
            tvPollsPredictions?.setOnClickListener {
                showController(force = true)
                showTvPollsPredictionsPanel()
            }
            ensurePlayerLiveEventsLoaded()
            syncTvPollsPredictionsButton()
        }
    }

    /** Every second: ad blocker status, quality badge, frame rate of the display. */
    private val tvHudTicker = object : Runnable {
        override fun run() {
            if (_binding == null || view == null) {
                return
            }
            if (isTv) {
                // the TV never goes to sleep while a stream plays, whichever player shows it
                activity?.window?.let { window ->
                    if (isPlayingOrLoading()) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                updateTvOledProtection()
                if (binding.playerControls.root.isVisible) {
                    binding.playerControls.tvQualityInfo?.let { info ->
                        val label = getCurrentQualityLabel()
                        if (info.text?.toString() != label) {
                            info.text = label
                        }
                        info.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                }
            } else if (binding.playerControls.root.isVisible) {
                val prefs = requireContext().prefs()
                binding.playerControls.tvQualityInfo?.let { info ->
                    val showQuality = prefs.getBoolean(C.MOBILE_HUD_SHOW_QUALITY, true)
                    val label = if (showQuality) getCurrentQualityLabel() else null
                    if (info.text?.toString() != label) {
                        info.text = label
                    }
                    info.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                val showViewers = prefs.getBoolean(C.MOBILE_HUD_SHOW_VIEWERS, true)
                val hasViewers = !binding.playerControls.viewersText.text.isNullOrBlank()
                binding.playerControls.viewersLayout.visibility = if (showViewers && hasViewers) View.VISIBLE else View.GONE
                if (!prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true) && binding.playerControls.uptimeLayout.isVisible) {
                    binding.playerControls.uptimeTimer.stop()
                    binding.playerControls.uptimeLayout.visibility = View.GONE
                }
            }
            updateAdblockBadge()
            syncExperimentalChatState()
            syncStreamSpeedButtonVisibility()
            updateFrameRateMatching()
            binding.root.postDelayed(this, 1000)
        }
    }

    /** What the badge of the ad blocker shows, null while it does not work. */
    open fun getAdblockInfo(): ExoPlayerService.AdblockInfo? = null

    open fun getVideoFrameRate(): Float? = null

    open fun isPlayingOrLoading(): Boolean = false

    /** The picture moves (not loading, not stopped). */
    open fun isPlayingNow(): Boolean = false

    open fun resumePlayback() {}

    /** Silences the stream while a preview with sound plays over it. */
    open fun setDucked(ducked: Boolean) {}

    /** True while the ad blocker plays the backup stream: its frame rate is not the one of the stream. */
    open fun isAdblockSwapping(): Boolean = false

    private fun getTopRightStack(): LinearLayout {
        return tvTopRightStack ?: LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            val density = resources.displayMetrics.density
            binding.playerLayout.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = if (isTv) resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical) else (10 * density).toInt()
                marginEnd = if (isTv) resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal) else (10 * density).toInt()
            })
            tvTopRightStack = this
        }
    }

    private val tvActivePoll = kotlinx.coroutines.flow.MutableStateFlow<com.github.andreyasadchy.xtra.model.chat.Poll?>(null)
    private val tvActivePollSeconds = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    private val tvActivePrediction = kotlinx.coroutines.flow.MutableStateFlow<com.github.andreyasadchy.xtra.model.chat.Prediction?>(null)
    private val tvActivePredictionSeconds = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    private val tvSelfBetOutcomeId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val tvSelfBetPoints = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val tvSelfVotedPollChoices = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    private var tvLiveEventsLookupKey: String? = null
    private var tvLiveEventsLookupJob: Job? = null
    private var tvLiveEventsTickerJob: Job? = null

    private fun resetTvLiveEventsState() {
        tvActivePoll.value = null
        tvActivePollSeconds.value = null
        tvActivePrediction.value = null
        tvActivePredictionSeconds.value = null
        tvSelfBetOutcomeId.value = null
        tvSelfBetPoints.value = 0
        tvLiveEventsTickerJob?.cancel()
        tvLiveEventsTickerJob = null
        syncTvPollsPredictionsButton()
    }

    private fun startTvLiveEventsLocalTickerIfNeeded() {
        if (tvLiveEventsTickerJob?.isActive == true || !isAdded) return
        tvLiveEventsTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                val poll = tvActivePoll.value
                val pSec = tvActivePollSeconds.value
                if (poll != null && poll.status == "ACTIVE" && pSec != null) {
                    val next = pSec - 1
                    if (next <= 0) {
                        tvActivePoll.value = null
                        tvActivePollSeconds.value = null
                        tvPollSeconds = null
                        syncTvPollsPredictionsButton()
                    } else {
                        tvActivePollSeconds.value = next
                        tvPollSeconds = next
                        if (tvPollView?.isVisible == true) {
                            renderTvPollPopup()
                        }
                    }
                }
                val pred = tvActivePrediction.value
                val prSec = tvActivePredictionSeconds.value
                if (pred != null && pred.status == "ACTIVE" && prSec != null) {
                    val next = prSec - 1
                    val updated = if (next > 0) next else null
                    tvActivePredictionSeconds.value = updated
                    tvPredictionSeconds = updated
                    if (tvPredictionView?.isVisible == true) {
                        renderTvPredictionPopup()
                    }
                }
                if (tvActivePoll.value == null && tvActivePrediction.value == null) {
                    break
                }
            }
        }
    }

    private fun ensurePlayerLiveEventsLoaded() {
        if (!isAdded || _binding == null) return
        if (!isStreamScreen()) {
            tvLiveEventsLookupKey = null
            resetTvLiveEventsState()
            return
        }
        val (_, clogin, _) = currentCoStreamIdentity()
        if (clogin.isNullOrBlank()) return
        val key = clogin.lowercase()
        if (key == tvLiveEventsLookupKey) {
            syncTvPollsPredictionsButton()
            return
        }
        tvLiveEventsLookupKey = key
        resetTvLiveEventsState()
        tvLiveEventsLookupJob?.cancel()
        val ctx = requireContext().applicationContext
        val app = ctx as? com.github.andreyasadchy.xtra.XtraApp ?: return
        tvLiveEventsLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val netLib = ctx.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val headers = TwitchApiHelper.getGQLHeaders(ctx, true)
            val loaded = app.xtraModule.graphQLRepository.loadActivePollAndPrediction(netLib, headers, clogin)
            if (tvLiveEventsLookupKey != key) return@launch
            if (loaded.poll != null && tvActivePoll.value == null) {
                tvActivePoll.value = loaded.poll
                val remSec = loaded.poll.remainingMilliseconds?.let { (it / 1000).coerceAtLeast(1) }
                tvActivePollSeconds.value = remSec
                tvPollSeconds = remSec
                if (!isChatOpen) {
                    onTvPollEvent(loaded.poll)
                }
            }
            if (loaded.prediction != null && tvActivePrediction.value == null) {
                tvActivePrediction.value = loaded.prediction
                val created = loaded.prediction.createdAt
                val win = loaded.prediction.predictionWindowSeconds
                if (loaded.prediction.status == "ACTIVE" && created != null && win != null && win > 0) {
                    val remSec = (((created + win * 1000L) - System.currentTimeMillis()) / 1000L).toInt()
                    if (remSec > 0) {
                        tvActivePredictionSeconds.value = remSec
                        tvPredictionSeconds = remSec
                    }
                }
                if (!isChatOpen) {
                    onTvPredictionEvent(loaded.prediction)
                }
            }
            if (loaded.selfBetOutcomeId != null && tvSelfBetOutcomeId.value == null) {
                tvSelfBetOutcomeId.value = loaded.selfBetOutcomeId
                tvSelfBetPoints.value = loaded.selfBetPoints
            }
            if (tvActivePoll.value != null || tvActivePrediction.value != null) {
                startTvLiveEventsLocalTickerIfNeeded()
            }
            syncTvPollsPredictionsButton()
        }
    }

    private fun syncTvPollsPredictionsButton() {
        if (!isTv || _binding == null) return
        val btn = _binding?.playerControls?.tvPollsPredictions ?: return
        val poll = tvActivePoll.value
        val pred = tvActivePrediction.value
        val shouldShow = isStreamScreen() && (
            (poll != null && poll.status == "ACTIVE") ||
                (pred != null && (pred.status == "ACTIVE" || pred.status == "LOCKED"))
            )
        val wasFocused = btn.hasFocus()
        btn.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow && wasFocused) {
            focusAfterHiddenControl()
        }
    }

    fun showTvPollsPredictionsPanel() {
        val ctx = context ?: return
        val (_, clogin, _) = currentCoStreamIdentity()
        val onPoll: (com.github.andreyasadchy.xtra.model.chat.Poll) -> Unit = { updated ->
            tvActivePoll.value = updated
            if (tvPollView?.isVisible == true) {
                tvPollCurrent = updated
                renderTvPollPopup()
            }
        }
        val onPred: (com.github.andreyasadchy.xtra.model.chat.Prediction) -> Unit = { updated ->
            tvActivePrediction.value = updated
        }
        if (!isTv) {
            com.github.andreyasadchy.xtra.ui.mobile.MobileBottomSheets.showPollsAndPredictions(
                context = ctx,
                channelLogin = clogin,
                pollFlow = tvActivePoll,
                pollSecondsFlow = tvActivePollSeconds,
                predictionFlow = tvActivePrediction,
                predictionSecondsFlow = tvActivePredictionSeconds,
                selfBetOutcomeIdFlow = tvSelfBetOutcomeId,
                selfBetPointsFlow = tvSelfBetPoints,
                selfVotedPollChoicesFlow = tvSelfVotedPollChoices,
                onOptimisticPollUpdate = onPoll,
                onOptimisticPredictionUpdate = onPred,
            )
            return
        }
        com.github.andreyasadchy.xtra.ui.tv.TvPollsPredictionsPanel.show(
            context = ctx,
            channelLogin = clogin,
            pollFlow = tvActivePoll,
            pollSecondsFlow = tvActivePollSeconds,
            predictionFlow = tvActivePrediction,
            predictionSecondsFlow = tvActivePredictionSeconds,
            selfBetOutcomeIdFlow = tvSelfBetOutcomeId,
            selfBetPointsFlow = tvSelfBetPoints,
            selfVotedPollChoicesFlow = tvSelfVotedPollChoices,
            onOptimisticPollUpdate = onPoll,
            onOptimisticPredictionUpdate = onPred,
        )
    }

    fun onTvPollEvent(poll: com.github.andreyasadchy.xtra.model.chat.Poll) {
        val ctx = context ?: return
        if (poll.status == "ACTIVE") {
            tvActivePoll.value = poll
            if (poll.remainingMilliseconds != null && poll.remainingMilliseconds > 0) {
                val sec = (poll.remainingMilliseconds / 1000).coerceAtLeast(1)
                tvActivePollSeconds.value = sec
                tvPollSeconds = sec
            }
            startTvLiveEventsLocalTickerIfNeeded()
        } else {
            tvActivePoll.value = null
            tvActivePollSeconds.value = null
            tvPollSeconds = null
        }
        syncTvPollsPredictionsButton()
        val pollMode = C.getTvPollsPopupMode(ctx.prefs())
        if (!isTv || _binding == null || isChatOpen || !isMaximized || pollMode == 0) {
            hideTvPollPopup()
            return
        }
        val pollId = poll.id ?: poll.title ?: return
        when (poll.status) {
            "ACTIVE" -> {
                if (pollMode == 2) {
                    tvPollStartShownId = pollId
                    tvPollEndShownId = null
                    tvPollCurrent = poll
                    showTvPollPopup(autoHideMs = 0L)
                } else if (tvPollStartShownId != pollId) {
                    tvPollStartShownId = pollId
                    tvPollEndShownId = null
                    tvPollCurrent = poll
                    showTvPollPopup(autoHideMs = 30_000L)
                } else if (tvPollView?.isVisible == true) {
                    tvPollCurrent = poll
                    renderTvPollPopup()
                }
            }
            "COMPLETED", "TERMINATED", "ARCHIVED" -> {
                if (tvPollEndShownId != pollId) {
                    tvPollEndShownId = pollId
                    tvPollCurrent = poll
                    tvPollSeconds = null
                    showTvPollPopup(autoHideMs = 15_000L)
                } else if (tvPollView?.isVisible == true) {
                    tvPollCurrent = poll
                    renderTvPollPopup()
                }
            }
            else -> hideTvPollPopup()
        }
    }

    fun onTvPollSecondsLeft(seconds: Int?) {
        if (!isTv) return
        tvPollSeconds = seconds
        tvActivePollSeconds.value = seconds
        if (tvPollView?.isVisible == true) {
            renderTvPollPopup()
        }
    }

    fun onTvPollHidden() {
        if (!isTv) return
        tvActivePoll.value = null
        tvActivePollSeconds.value = null
        tvPollSeconds = null
        syncTvPollsPredictionsButton()
        hideTvPollPopup()
    }

    private fun showTvPollPopup(autoHideMs: Long) {
        val ctx = context ?: return
        val card = tvPollView ?: TextView(ctx).apply {
            setBackgroundResource(R.drawable.tv_chat_overlay_background)
            setTextColor(Color.WHITE)
            textSize = 13f
            isFocusable = false
            isClickable = false
            val density = resources.displayMetrics.density
            maxWidth = (360 * density).toInt()
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            getTopRightStack().addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
            tvPollView = this
        }
        renderTvPollPopup()
        card.visibility = View.VISIBLE
        card.removeCallbacks(tvPollHideRunnable)
        if (autoHideMs > 0L) {
            card.postDelayed(tvPollHideRunnable, autoHideMs)
        }
    }

    private fun renderTvPollPopup() {
        val card = tvPollView ?: return
        val poll = tvPollCurrent ?: return
        val ended = poll.status in setOf("COMPLETED", "TERMINATED", "ARCHIVED")
        val header = buildString {
            append(getString(if (ended) R.string.tv_poll_ended else R.string.tv_poll_started))
            val sec = tvPollSeconds
            if (!ended && poll.status == "ACTIVE" && sec != null && sec > 0) {
                append(" · ")
                append(DateUtils.formatElapsedTime(sec.toLong()))
            }
        }
        val sb = android.text.SpannableStringBuilder()
        sb.append(header)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(com.github.andreyasadchy.xtra.ui.tv.TvAccent.color), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!poll.title.isNullOrBlank()) {
            sb.append("\n")
            val titleStart = sb.length
            sb.append(poll.title)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), titleStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val choices = poll.choices.orEmpty()
        if (choices.isNotEmpty()) {
            val totalVotes = (poll.totalVotes?.toLong() ?: choices.sumOf { (it.totalVotes ?: 0).toLong() }).coerceAtLeast(1L)
            val winningVotes = if (ended) (choices.maxOfOrNull { it.totalVotes ?: 0 } ?: 0) else -1
            val nf = java.text.NumberFormat.getInstance()
            choices.forEach { choice ->
                sb.append("\n")
                val lineStart = sb.length
                val votes = choice.totalVotes ?: 0
                val isWinner = ended && winningVotes > 0 && votes == winningVotes
                val pct = kotlin.math.round((votes.toLong() * 100.0) / totalVotes).toInt()
                sb.append(
                    getString(
                        if (isWinner) R.string.poll_choice_winner else R.string.poll_choice,
                        pct,
                        nf.format(votes),
                        choice.title ?: "",
                    )
                )
                sb.setSpan(android.text.style.RelativeSizeSpan(0.9f), lineStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (isWinner) {
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), lineStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sb.setSpan(android.text.style.ForegroundColorSpan(0xD9FFFFFF.toInt()), lineStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (card.text?.toString() != sb.toString()) {
            card.text = sb
        }
    }

    fun hideTvPollPopup() {
        tvPollView?.let {
            it.removeCallbacks(tvPollHideRunnable)
            it.visibility = View.GONE
        }
    }

    fun onTvPredictionEvent(prediction: com.github.andreyasadchy.xtra.model.chat.Prediction) {
        val ctx = context ?: return
        when (prediction.status) {
            "ACTIVE", "LOCKED" -> {
                val prevId = tvActivePrediction.value?.id
                if (prevId != null && prevId != prediction.id) {
                    tvSelfBetOutcomeId.value = null
                    tvSelfBetPoints.value = 0
                }
                tvActivePrediction.value = prediction
                if (prediction.status == "LOCKED") {
                    tvActivePredictionSeconds.value = null
                }
                startTvLiveEventsLocalTickerIfNeeded()
            }
            else -> {
                tvActivePrediction.value = null
                tvActivePredictionSeconds.value = null
                tvSelfBetOutcomeId.value = null
                tvSelfBetPoints.value = 0
            }
        }
        syncTvPollsPredictionsButton()
        val predMode = C.getTvPredictionsPopupMode(ctx.prefs())
        if (!isTv || _binding == null || isChatOpen || !isMaximized || predMode == 0) {
            hideTvPredictionPopup()
            return
        }
        val predId = prediction.id ?: prediction.title ?: return
        when (prediction.status) {
            "ACTIVE" -> {
                if (predMode == 2) {
                    tvPredictionStartShownId = predId
                    tvPredictionEndShownId = null
                    tvPredictionCurrent = prediction
                    showTvPredictionPopup(autoHideMs = 0L)
                } else if (tvPredictionStartShownId != predId) {
                    tvPredictionStartShownId = predId
                    tvPredictionEndShownId = null
                    tvPredictionCurrent = prediction
                    showTvPredictionPopup(autoHideMs = 30_000L)
                } else if (tvPredictionView?.isVisible == true) {
                    tvPredictionCurrent = prediction
                    renderTvPredictionPopup()
                }
            }
            "LOCKED" -> {
                tvPredictionSeconds = null
                hideTvPredictionPopup()
            }
            "RESOLVED", "RESOLVE_PENDING", "CANCELED", "CANCEL_PENDING" -> {
                if (tvPredictionEndShownId != predId) {
                    tvPredictionEndShownId = predId
                    tvPredictionCurrent = prediction
                    tvPredictionSeconds = null
                    showTvPredictionPopup(autoHideMs = 15_000L)
                } else if (tvPredictionView?.isVisible == true) {
                    tvPredictionCurrent = prediction
                    renderTvPredictionPopup()
                }
            }
            else -> hideTvPredictionPopup()
        }
    }

    fun onTvPredictionSecondsLeft(seconds: Int?) {
        tvPredictionSeconds = seconds
        tvActivePredictionSeconds.value = seconds
        if (tvPredictionView?.isVisible == true) {
            renderTvPredictionPopup()
        }
    }

    fun onTvPredictionHidden(clearActive: Boolean = true) {
        if (clearActive) {
            tvActivePrediction.value = null
            tvActivePredictionSeconds.value = null
            syncTvPollsPredictionsButton()
        }
        hideTvPredictionPopup()
    }

    private fun showTvPredictionPopup(autoHideMs: Long) {
        val ctx = context ?: return
        val card = tvPredictionView ?: TextView(ctx).apply {
            setBackgroundResource(R.drawable.tv_chat_overlay_background)
            setTextColor(Color.WHITE)
            textSize = 13f
            isFocusable = false
            isClickable = false
            val density = resources.displayMetrics.density
            maxWidth = (360 * density).toInt()
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            getTopRightStack().addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
            tvPredictionView = this
        }
        renderTvPredictionPopup()
        card.visibility = View.VISIBLE
        card.removeCallbacks(tvPredictionHideRunnable)
        if (autoHideMs > 0L) {
            card.postDelayed(tvPredictionHideRunnable, autoHideMs)
        }
    }

    private fun renderTvPredictionPopup() {
        val card = tvPredictionView ?: return
        val prediction = tvPredictionCurrent ?: return
        val ended = prediction.status in setOf("RESOLVED", "RESOLVE_PENDING", "CANCELED", "CANCEL_PENDING")
        val resolved = prediction.status == "RESOLVED" || prediction.status == "RESOLVE_PENDING"
        val canceled = prediction.status == "CANCELED" || prediction.status == "CANCEL_PENDING"
        val header = buildString {
            append(getString(if (ended) R.string.tv_prediction_ended else R.string.tv_prediction_started))
            val sec = tvPredictionSeconds
            if (!ended && prediction.status == "ACTIVE" && sec != null && sec > 0) {
                append(" · ")
                append(DateUtils.formatElapsedTime(sec.toLong()))
            } else if (prediction.status == "LOCKED") {
                append(" · ")
                append(getString(R.string.prediction_locked))
            } else if (canceled) {
                append(" · ")
                append(getString(R.string.prediction_refunded))
            }
        }
        val sb = android.text.SpannableStringBuilder()
        sb.append(header)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(com.github.andreyasadchy.xtra.ui.tv.TvAccent.color), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!prediction.title.isNullOrBlank()) {
            sb.append("\n")
            val titleStart = sb.length
            sb.append(prediction.title)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), titleStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val outcomes = prediction.outcomes.orEmpty()
        if (outcomes.isNotEmpty()) {
            val totalPoints = outcomes.sumOf { it.totalPoints?.toLong() ?: 0L }
            val nf = java.text.NumberFormat.getInstance()
            outcomes.forEach { outcome ->
                sb.append("\n")
                val lineStart = sb.length
                val isWinner = resolved && prediction.winningOutcomeId != null && prediction.winningOutcomeId == outcome.id
                val pct = kotlin.math.round(((outcome.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1L)).toInt()
                sb.append(
                    getString(
                        if (isWinner) R.string.prediction_outcome_winner else R.string.prediction_outcome,
                        pct,
                        outcome.totalPoints?.let { nf.format(it) } ?: "0",
                        outcome.totalUsers?.let { nf.format(it) } ?: "0",
                        outcome.title ?: "",
                    )
                )
                sb.setSpan(android.text.style.RelativeSizeSpan(0.9f), lineStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (isWinner) {
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), lineStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sb.setSpan(android.text.style.ForegroundColorSpan(0xD9FFFFFF.toInt()), lineStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (card.text?.toString() != sb.toString()) {
            card.text = sb
        }
    }

    fun hideTvPredictionPopup() {
        tvPredictionView?.let {
            it.removeCallbacks(tvPredictionHideRunnable)
            it.visibility = View.GONE
        }
    }

    /** The box of the ad blocker, with a thin bar at its bottom: how far the ad break is (estimated), when it is known. */
    private class AdblockBadge(context: android.content.Context) : TextView(context) {
        private var progress: Float? = null
        private val density = context.resources.displayMetrics.density
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        fun setProgress(value: Float?) {
            if (value != progress) {
                progress = value
                invalidate()
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val value = progress ?: return
            val left = paddingLeft.toFloat()
            val right = (width - paddingRight).toFloat()
            val bottom = height - 3 * density
            val top = bottom - 2.5f * density
            val radius = 1.25f * density
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, track)
            bar.color = com.github.andreyasadchy.xtra.ui.tv.TvAccent.color
            canvas.drawRoundRect(left, top, left + (right - left) * value, bottom, radius, radius, bar)
        }
    }

    /** The badge drawn again 4 times a second while its bar and its seconds move. */
    private val adblockBadgeRefresh = Runnable { if (_binding != null) updateAdblockBadge() }

    /**
     * Small box at the top right while the ad blocker works, on two lines like the proxyivshook userscript: the shield,
     * "Mid-roll ad 2/3 · 12 s", the source of the stream without the ad under it, smaller, and the bar of the ad.
     */
    private fun updateAdblockBadge() {
        val prefs = requireContext().prefs()
        val badgeEnabled = if (isTv) {
            prefs.getBoolean(C.TV_SHOW_ADBLOCK_BADGE, true)
        } else {
            prefs.getBoolean(C.MOBILE_SHOW_ADBLOCK_BADGE, false)
        }
        val mobileControlsVisible = !isTv && _binding?.let { it.playerControls.root.isVisible && !controllerHiding } == true
        if (!badgeEnabled || mobileControlsVisible) {
            tvAdblockView?.let {
                it.removeCallbacks(adblockBadgeRefresh)
                it.visibility = View.GONE
            }
            return
        }
        val info = getAdblockInfo()
        val badge = tvAdblockView ?: if (info == null) return else AdblockBadge(requireContext()).apply {
            setBackgroundResource(R.drawable.tv_chat_overlay_background)
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (isTv) 13f else 11f
            val density = resources.displayMetrics.density
            if (isTv) {
                // room under the text for the bar
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            } else {
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }
            getTopRightStack().addView(this, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ((if (isTv) 8 else 4) * density).toInt()
            })
            tvAdblockView = this
        }
        badge.removeCallbacks(adblockBadgeRefresh)
        if (info != null) {
            val density = resources.displayMetrics.density
            if (!isTv) {
                val padH = (8 * density).toInt()
                val padV = (4 * density).toInt()
                if (badge.paddingLeft != padH || badge.paddingBottom != padV) {
                    badge.setPadding(padH, padV, padH, padV)
                }
                val accent = com.github.andreyasadchy.xtra.ui.mobile.MobileAccent.color(requireContext())
                    ?: com.github.andreyasadchy.xtra.ui.tv.TvAccent.color
                val text = android.text.SpannableStringBuilder()
                com.github.andreyasadchy.xtra.util.chat.TvChatNotices.appendIcon(requireContext(), text, R.drawable.tv_ic_shield, accent, badge.textSize * 1.05f)
                val titleStart = text.length
                text.append(getString(R.string.tv_adblock_ad))
                text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), titleStart, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (badge.text?.toString() != text.toString()) {
                    badge.text = text
                }
                (badge as? AdblockBadge)?.setProgress(null)
                badge.visibility = View.VISIBLE
                return
            }
            val padBottom = ((if (info.progress != null) 10 else 6) * density).toInt()
            if (badge.paddingBottom != padBottom) {
                badge.setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), padBottom)
            }
            val text = android.text.SpannableStringBuilder()
            com.github.andreyasadchy.xtra.util.chat.TvChatNotices.appendIcon(requireContext(), text, R.drawable.tv_ic_shield, com.github.andreyasadchy.xtra.ui.tv.TvAccent.color, badge.textSize * 1.1f)
            val titleStart = text.length
            text.append(info.title)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), titleStart, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!info.source.isNullOrBlank()) {
                text.append("\n")
                val sourceStart = text.length
                text.append(info.source)
                text.setSpan(android.text.style.RelativeSizeSpan(0.85f), sourceStart, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(android.text.style.ForegroundColorSpan(0xB3FFFFFF.toInt()), sourceStart, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (badge.text?.toString() != text.toString()) {
                badge.text = text
            }
            (badge as? AdblockBadge)?.setProgress(info.progress)
            badge.visibility = View.VISIBLE
            if (info.progress != null) {
                badge.postDelayed(adblockBadgeRefresh, 250L)
            }
        } else {
            badge.visibility = View.GONE
        }
    }

    /**
     * Frame rate matching: switches the display to a refresh rate that is a multiple of the frame rate of the stream,
     * and keeps checking it while playing (the backup player of the ad blocker can have another frame rate).
     */
    private fun updateFrameRateMatching() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val activity = activity ?: return
        if (!requireContext().prefs().getBoolean(C.PLAYER_FRAME_RATE_MATCHING, false)) {
            resetFrameRateMatching()
            return
        }
        // no switch while the ad blocker plays the backup stream, the display waits for the main stream to come back
        if (isAdblockSwapping()) {
            frameRateModeCandidate = 0
            return
        }
        val frameRate = getVideoFrameRate() ?: return
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay) ?: return
        val current = display.mode
        val best = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.mapNotNull { mode ->
            val ratio = mode.refreshRate / frameRate
            val multiple = kotlin.math.round(ratio)
            val error = kotlin.math.abs(ratio - multiple) / multiple
            if (multiple >= 1f && error < 0.0015f) Triple(mode, multiple, error) else null
        }.minWithOrNull(compareBy<Triple<android.view.Display.Mode, Float, Float>> { it.second }.thenBy { it.third })?.first ?: return
        if (best.modeId == current.modeId && frameRateModeApplied != 0) {
            frameRateModeCandidate = 0
            return
        }
        // the mode has to be wanted for 3 checks in a row, a short change of the picture does not switch the display
        if (frameRateModeCandidate != best.modeId) {
            frameRateModeCandidate = best.modeId
            frameRateModeChecks = 1
            return
        }
        if (++frameRateModeChecks < 3) {
            return
        }
        frameRateModeCandidate = 0
        if (frameRateModeApplied != best.modeId) {
            frameRateModeApplied = best.modeId
            val wasPlaying = isPlayingOrLoading()
            activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = best.modeId }
            // the TV resyncs HDMI: the audio output goes away for a moment and the player pauses, playback goes on by itself
            for (delay in longArrayOf(1500L, 3500L, 6000L, 9000L)) {
                binding.root.postDelayed({
                    if (_binding != null) {
                        if (wasPlaying) {
                            resumePlayback()
                        }
                        // the window is laid out again: the focus can be lost or go to the lists behind the player
                        val focused = activity.currentFocus
                        if (isMaximized && (focused == null || !focused.isShown || !isDescendant(binding.root, focused))) {
                            requestPlayerFocus()
                        }
                    }
                }, delay)
            }
        }
    }

    private fun isDescendant(parent: View, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == parent) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun resetFrameRateMatching() {
        if (frameRateModeApplied != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            frameRateModeApplied = 0
            frameRateModeCandidate = 0
            activity?.window?.let { window ->
                window.attributes = window.attributes.also { it.preferredDisplayModeId = 0 }
            }
        }
    }

    /** Debug builds only: panels of the player for the screenshots of the emulator test. */
    fun runTvTest(action: String?) {
        when (action) {
            "stats" -> {
                showController(force = true)
                toggleStatsForNerds()
            }
            "chat_layout" -> showChatLayoutPanel()
            "hud" -> showController(force = true)
        }
    }

    private fun toggleStatsForNerds() {
        val stats = tvStatsView ?: TextView(requireContext()).apply {
            setBackgroundResource(R.drawable.tv_chat_overlay_background)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            visibility = View.GONE
            getTopRightStack().addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tvStatsView = this
        }
        if (stats.isVisible) {
            stats.visibility = View.GONE
            stats.removeCallbacks(tvStatsAction)
        } else {
            stats.visibility = View.VISIBLE
            stats.removeCallbacks(tvStatsAction)
            tvStatsAction.run()
        }
    }

    /** Focus goes to play/pause, else to the timeline, else to the first button of the bottom row. */
    /** Last control used in the HUD, the focus goes back to it when the HUD shows again. */
    private var lastHudControl: java.lang.ref.WeakReference<View>? = null
    private val hudFocusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val controls = _binding?.playerControls ?: return@OnGlobalFocusChangeListener
        if (newFocus != null && newFocus != controls.progressBar && (newFocus.parent == controls.bottomLeftLayout || newFocus.parent == controls.bottomRightLayout || newFocus == controls.playPause)) {
            lastHudControl = java.lang.ref.WeakReference(newFocus)
            // kept by the app (id, else description), not by this screen: the next player finds it again
            lastControlId = newFocus.id
            lastControlLabel = newFocus.contentDescription?.toString()
        }
    }

    /** A control with the focus went away: the timeline, else the first control on screen. */
    protected fun focusAfterHiddenControl() {
        val controls = _binding?.playerControls ?: return
        if (isTv && !hudOpenedByUser) {
            // a button hidden by a switch (the HUD shows only the info): the focus leaves the HUD, not to the timeline
            binding.dragView.requestFocus()
            return
        }
        if (!(controls.progressBar.isShown && controls.progressBar.requestFocus())) {
            lastHudControl = null
            focusPrimaryControl()
        }
    }

    private fun focusPrimaryControl() {
        // the HUD given the focus on purpose (OK, back from an option, end of a stream...): left / right move in it
        hudOpenedByUser = true
        with(binding.playerControls) {
            // the button pressed last, kept from one stream to the next (left / right, other streams)
            findLastControl()?.let { last ->
                if (last.isShown && last.isFocusable && last.requestFocus()) {
                    return
                }
            }
            lastHudControl?.get()?.let { last ->
                if (last.isShown && last.isFocusable && last.requestFocus()) {
                    return
                }
            }
            val candidates = mutableListOf<View>(playPause, progressBar)
            for (layout in listOf(bottomLeftLayout, bottomRightLayout)) {
                for (i in 0 until layout.childCount) {
                    candidates.add(layout.getChildAt(i))
                }
            }
            if (candidates.none { it.isShown && it.isFocusable && it.requestFocus() }) {
                root.requestFocus()
            }
        }
    }

    private var loadingPicture: android.widget.ImageView? = null

    private fun showLoadingPicture() {
        val picture = pendingLoadingPicture ?: return
        pendingLoadingPicture = null
        streamFirstFrameRendered = false
        val context = requireContext()
        val image = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        // over the video, under the loading circle and the controls
        val index = binding.playerLayout.indexOfChild(binding.aspectRatioFrameLayout) + 1
        binding.playerLayout.addView(image, index, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadingPicture = image
        // a switch of stream: the last picture of the stream left until the picture of this one comes, never a black screen
        // (put first, the picture of this stream replaces it as soon as it is there)
        takeSwitchFrame()?.let { image.setImageBitmap(it) } ?: run {
            switchFrameListener = { frame -> if (loadingPicture === image && image.drawable == null) image.setImageBitmap(frame) }
        }
        com.github.andreyasadchy.xtra.ui.tv.TvLoadingPicture.show(image, picture.channelLogin, picture.thumbnail, picture.fallback, viewLifecycleOwner.lifecycleScope) {
            loadingPicture === image && view != null
        }
        // never kept for long: an error or the sound only mode has no first picture
        image.postDelayed({ hideLoadingPicture() }, 20_000L)
    }

    /** The first picture of the video is on screen: the picture of the stream fades away and HUD auto-hide timers start. */
    protected fun hideLoadingPicture() {
        val wasFirst = !streamFirstFrameRendered
        streamFirstFrameRendered = true
        switchFrameListener = null
        if (tvOfflineScreenActive) {
            hideTvOfflineOverlay()
        }
        loadingPicture?.let { image ->
            loadingPicture = null
            image.animate().alpha(0f).setDuration(250L).withEndAction { (image.parent as? ViewGroup)?.removeView(image) }.start()
        }
        if (wasFirst && isTv && _binding != null) {
            if (hudPeek) {
                binding.root.removeCallbacks(hudPeekHideAction)
                binding.root.postDelayed(hudPeekHideAction, 2500L)
                TvSwitchOverlay.startAutoHideTimer(activity, 2500L)
            } else {
                TvSwitchOverlay.startAutoHideTimer(activity, 2500L)
                if (binding.playerControls.root.isVisible && controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                    binding.playerControls.root.removeCallbacks(controllerHideAction)
                    binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                }
            }
        }
    }

    /** The button of the controls focused last, in this player or a previous one; null when this one has none like it. */
    private fun findLastControl(): View? {
        val controls = _binding?.playerControls ?: return null
        if (lastControlId != View.NO_ID) {
            controls.root.findViewById<View>(lastControlId)?.let { return it }
        }
        val label = lastControlLabel ?: return null
        return listOf(controls.bottomLeftLayout, controls.bottomRightLayout).flatMap { layout -> (0 until layout.childCount).map { layout.getChildAt(it) } }
            .firstOrNull { it.contentDescription?.toString() == label }
    }

    /** Called by the activity for every key event, keeps the controls visible while they are used with a D-pad. */
    fun onKeyEventDispatched(event: KeyEvent) {
        if (isTv && event.action == KeyEvent.ACTION_DOWN) {
            restoreTvOledProtection()
        }
        if (_binding != null && isMaximized && event.action == KeyEvent.ACTION_DOWN &&
            binding.playerControls.root.isVisible && binding.playerControls.root.hasFocus()
        ) {
            showController()
            // OK on a button of the HUD: if it opens a panel, the HUD comes back when that panel closes
            val isSelect = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            val focus = activity?.currentFocus
            if (isTv && isSelect && event.repeatCount == 0 && focus != null &&
                (focus.parent == binding.playerControls.bottomLeftLayout || focus.parent == binding.playerControls.bottomRightLayout || focus == binding.playerControls.playPause)
            ) {
                hideTooltip()
                hudOptionPressedAt = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    /** TV: OK was pressed on a button of the HUD (time), a panel it opens closing gives the HUD back. */
    private var hudOptionPressedAt = 0L
    private var reopenHudAfterOption = false

    /**
     * TV: the window of the app lost or got back the focus (a panel, a row or a dialog over it opened or closed). A panel
     * opened from a button of the HUD and closed without changing the stream (a switch makes a new player screen, which
     * shows the info of its stream): the whole HUD again, the focus on the button that opened it.
     */
    fun onTvWindowFocusChanged(hasFocus: Boolean) {
        if (!isTv || _binding == null) {
            return
        }
        if (!hasFocus) {
            hideTooltip()
            if ((areControlsShown() && !hudPeek) || android.os.SystemClock.elapsedRealtime() - hudOptionPressedAt < 2000L) {
                reopenHudAfterOption = true
                binding.playerControls.root.removeCallbacks(controllerHideAction)
            }
            return
        }
        if (reopenHudAfterOption) {
            reopenHudAfterOption = false
            hudOptionPressedAt = 0L
            if (view != null && isAdded && !isRemoving && isMaximized) {
                endHudPeek()
                showController(force = true)
                binding.playerControls.root.post {
                    if (view != null && isAdded && !isRemoving && isMaximized && activity?.hasWindowFocus() == true && !binding.playerControls.root.hasFocus()) {
                        focusPrimaryControl()
                    }
                }
            }
        } else if (view != null && isAdded && !isRemoving && isMaximized) {
            binding.dragView.post {
                if (view != null && isAdded && !isRemoving && isMaximized && activity?.hasWindowFocus() == true) {
                    restoreTvFocusIfLost()
                    if (areControlsShown() && controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                        binding.playerControls.root.removeCallbacks(controllerHideAction)
                        binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                    }
                }
            }
        }
    }

    private var tvClockObserverJob: Job? = null
    private var dpadKeyDownSeen = 0
    private var dpadPendingLongAction: String? = null
    private val dpadLongRunnable = Runnable {
        val key = dpadKeyDownSeen
        val act = dpadPendingLongAction
        if (key == 0 || act == null || !isAdded || isRemoving || _binding == null) return@Runnable
        dpadKeyDownSeen = 0
        dpadPendingLongAction = null
        heldLongKeyCode = key
        if (act != com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.ACTION_DEFAULT) {
            executeTvRemoteAction(act)
        } else {
            openHudFromDpad()
        }
    }

    private fun handleDpadShortOrLongKey(keyCode: Int, event: KeyEvent, shortAction: String, longAction: String): Boolean {
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 550L)
        fun runDpadAction(act: String) {
            if (act != Keys.ACTION_DEFAULT) {
                executeTvRemoteAction(act)
            } else {
                openHudFromDpad()
            }
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    dpadKeyDownSeen = keyCode
                    dpadPendingLongAction = longAction
                    binding.root.removeCallbacks(dpadLongRunnable)
                    binding.root.postDelayed(dpadLongRunnable, longPressMs)
                } else if (dpadKeyDownSeen == keyCode && heldLongKeyCode != keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                    binding.root.removeCallbacks(dpadLongRunnable)
                    heldLongKeyCode = keyCode
                    dpadKeyDownSeen = 0
                    dpadPendingLongAction = null
                    runDpadAction(longAction)
                }
            }
            KeyEvent.ACTION_UP -> {
                binding.root.removeCallbacks(dpadLongRunnable)
                val wasDownSeen = dpadKeyDownSeen == keyCode
                dpadKeyDownSeen = 0
                dpadPendingLongAction = null
                if (wasDownSeen && heldLongKeyCode != keyCode && !event.isCanceled) {
                    val heldMs = if (event.downTime > 0L) event.eventTime - event.downTime else 0L
                    runDpadAction(if (heldMs >= longPressMs) longAction else shortAction)
                }
                if (heldLongKeyCode == keyCode) {
                    heldLongKeyCode = 0
                }
            }
            else -> {
                binding.root.removeCallbacks(dpadLongRunnable)
                dpadKeyDownSeen = 0
                dpadPendingLongAction = null
            }
        }
        return true
    }

    /** Handles D-pad keys while the player itself (and not one of its controls) has focus. */
    private fun onPlayerKey(keyCode: Int, event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("XtraTv", "playerKey ${KeyEvent.keyCodeToString(keyCode)} action=${event.action} animating=$isAnimating maximized=$isMaximized useController=$useController controlsVisible=${_binding?.playerControls?.root?.isVisible} controllerAnimating=$controllerIsAnimating")
        }
        if (_binding == null || isAnimating) {
            return false
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN) {
            restoreTvOledProtection()
        }
        val isSelect = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isTv && isMaximized && isSelect && tvStreamEndNextTarget != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                confirmTvStreamEndSwitchNow()
            }
            return true
        }
        if (!isMaximized) {
            if (isSelect) {
                if (event.action == KeyEvent.ACTION_UP) {
                    maximize()
                }
                return true
            }
            return false
        }
        if (!useController) {
            return false
        }
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        val hudHiddenOrPeek = !binding.playerControls.root.isVisible || hudPeek
        val pipOpen = (activity as? MainActivity)?.isTvPipOpen() == true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isTv && hudHiddenOrPeek) {
                    val action = effectiveDpadAction(Keys.dpadForKeyCode(requireContext(), keyCode), pipOpen)
                    if (action == Keys.ACTION_SWITCH_PREV || action == Keys.ACTION_SWITCH_NEXT) {
                        if (isAtLiveEdgeForStreamSwitch()) {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                executeTvRemoteAction(action)
                            }
                            return true
                        }
                    } else if (action != Keys.ACTION_DEFAULT) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            executeTvRemoteAction(action)
                        }
                        return true
                    }
                }
                if (binding.playerControls.progressBar.isVisible) {
                    // seek with the timeline: the following key events go to the focused time bar
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        hudOpenedByUser = true
                        showController(force = true)
                        binding.playerControls.progressBar.requestFocus()
                    }
                    onTimeBarKey(keyCode, event)
                } else if (isTv && !isAtLiveEdgeForStreamSwitch()) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        openHudOnTimelineForDvr()
                    }
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isTv && hudHiddenOrPeek) {
                    val shortAction = effectiveDpadAction(Keys.upAction(requireContext()), pipOpen)
                    val longAction = effectiveDpadAction(Keys.upLongAction(requireContext()), pipOpen)
                    if (longAction != Keys.ACTION_DEFAULT && shortAction != longAction) {
                        return handleDpadShortOrLongKey(keyCode, event, shortAction, longAction)
                    }
                    if (shortAction != Keys.ACTION_DEFAULT) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            executeTvRemoteAction(shortAction)
                        }
                        return true
                    }
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openHudFromDpad()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isTv && hudHiddenOrPeek) {
                    val shortAction = effectiveDpadAction(Keys.downAction(requireContext()), pipOpen)
                    val longAction = effectiveDpadAction(Keys.downLongAction(requireContext()), pipOpen)
                    if (shortAction != longAction) {
                        return handleDpadShortOrLongKey(keyCode, event, shortAction, longAction)
                    }
                    if (shortAction != Keys.ACTION_DEFAULT) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            executeTvRemoteAction(shortAction)
                        }
                        return true
                    }
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openHudFromDpad()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // only the D-pad center is remappable, Enter keeps opening the HUD
                if (isTv && hudHiddenOrPeek && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    val shortAction = effectiveDpadAction(Keys.centerAction(requireContext()), pipOpen)
                    val longAction = effectiveDpadAction(Keys.centerLongAction(requireContext()), pipOpen)
                    if (shortAction != longAction) {
                        return handleDpadShortOrLongKey(keyCode, event, shortAction, longAction)
                    }
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (shortAction != Keys.ACTION_DEFAULT) {
                            executeTvRemoteAction(shortAction)
                        } else {
                            openHudFromDpad()
                        }
                    }
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openHudFromDpad()
                }
                true
            }
            else -> false
        }
    }

    protected fun showController(show: Boolean = true, force: Boolean = false) {
        if (show && isTv) {
            restoreTvOledProtection()
        }
        if (show) {
            if (controllerIsAnimating && controllerHiding && !force) {
                controllerAnimation?.cancel()
                controllerIsAnimating = false
            }
            controllerHiding = false
        }
        if (show && isTv && !hudPeek) {
            // the whole HUD again (after the info only of a switch)
            setHudInfoOnly(false)
        }
        if (show) {
            onTvControllerVisibility(true)
        }
        if (show && force) {
            controllerAnimation?.cancel()
            controllerIsAnimating = false
            binding.playerControls.root.removeCallbacks(controllerHideAction)
            binding.playerControls.root.alpha = 1f
            binding.playerControls.root.visibility = View.VISIBLE
            if (!isTv) updateAdblockBadge()
            updateProgress()
            if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
            }
            return
        }
        if (!controllerIsAnimating) {
            if (!binding.playerControls.root.isVisible) {
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                if (show) {
                    binding.playerControls.root.alpha = 0f
                    binding.playerControls.root.visibility = View.VISIBLE
                    if (!isTv) updateAdblockBadge()
                    updateProgress()
                    controllerAnimation = binding.playerControls.root.animate().apply {
                        alpha(1f)
                        setDuration(250L)
                        setListener(
                            object : AnimatorListenerAdapter() {
                                override fun onAnimationStart(animation: Animator) {
                                    controllerIsAnimating = true
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    controllerIsAnimating = false
                                    setListener(null)
                                    if (view != null && binding.playerControls.root.isVisible && !controllerHiding) {
                                        binding.playerControls.root.alpha = 1f
                                    }
                                    if (view != null && controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                                        val delayMs = if (isWaitingForStreamStart()) 12_000L else controllerHideDelay
                                        binding.playerControls.root.postDelayed(controllerHideAction, delayMs)
                                    }
                                }
                            }
                        )
                        start()
                    }
                }
            } else {
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                if (show) {
                    if (binding.playerControls.root.alpha < 1f) {
                        binding.playerControls.root.alpha = 1f
                    }
                    updateProgress()
                }
                if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                    val delayMs = if (isWaitingForStreamStart()) 12_000L else controllerHideDelay
                    binding.playerControls.root.postDelayed(controllerHideAction, delayMs)
                }
            }
        }
    }

    private fun hideController(force: Boolean = false) {
        tvVolumePill?.dismiss(restoreAnchorFocus = false, immediate = true)
        hudOpenedByUser = false
        endHudPeek()
        onTvControllerVisibility(false)
        if (binding.playerControls.root.hasFocus()) {
            binding.dragView.requestFocus()
        }
        if (controllerIsAnimating && !controllerHiding && !force) {
            controllerAnimation?.cancel()
            controllerIsAnimating = false
        }
        if (!controllerIsAnimating && binding.playerControls.root.isVisible) {
            controllerHiding = true
            controllerAnimation = binding.playerControls.root.animate().apply {
                alpha(0f)
                setDuration(250L)
                setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            controllerIsAnimating = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            controllerIsAnimating = false
                            controllerHiding = false
                            setListener(null)
                            if (view != null) {
                                if (binding.playerControls.root.hasFocus()) {
                                    binding.dragView.requestFocus()
                                }
                                binding.playerControls.root.visibility = View.GONE
                                if (!isTv) updateAdblockBadge()
                            }
                        }
                    }
                )
                start()
            }
        } else {
            if (force) {
                controllerAnimation?.cancel()
                controllerIsAnimating = false
                controllerHiding = false
                binding.playerControls.root.alpha = 0f
                binding.playerControls.root.visibility = View.GONE
                if (!isTv) updateAdblockBadge()
            }
        }
    }

    private fun showStatusBar() {
        if (isAdded) {
            WindowCompat.getInsetsController(
                requireActivity().window,
                requireActivity().window.decorView
            ).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideStatusBar() {
        if (isAdded) {
            WindowCompat.getInsetsController(
                requireActivity().window,
                requireActivity().window.decorView
            ).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun enableBackground() {
        backgroundVisible = true
        binding.playerBackground.setBackgroundColor(
            if (isPortrait) {
                backgroundColor ?: MaterialColors.getColor(binding.playerBackground, com.google.android.material.R.attr.colorSurface).also { backgroundColor = it }
            } else {
                Color.BLACK
            }
        )
        binding.playerBackground.isClickable = true
    }

    private fun disableBackground() {
        backgroundVisible = false
        binding.playerBackground.setBackgroundColor(Color.TRANSPARENT)
        binding.playerBackground.isClickable = false
    }

    private fun getHorizontalInsets(windowInsets: WindowInsetsCompat?): Int {
        return if (windowInsets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && requireContext().prefs().getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)) {
                val rootWindowInsets = requireView().rootWindowInsets
                val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                if (requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)) {
                    leftRadius + rightRadius
                } else {
                    val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    max(cutoutInsets.left, leftRadius) + max(cutoutInsets.right, rightRadius)
                }
            } else {
                if (requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)) {
                    0
                } else {
                    val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    cutoutInsets.left + cutoutInsets.right
                }
            }
        } else 0
    }

    private fun getScaleValues(): Pair<Float, Float> {
        return when {
            isPortrait -> 0.5f to 0.5f
            isTv -> 0.3f to 0.3f
            else -> 0.3f to 0.325f
        }
    }

    private var tvMinimizedBorderDrawable: android.graphics.drawable.GradientDrawable? = null
    private var tvMinimizedBorderStrokePx: Int = 0
    private var tvMinimizedBorderColor: Int = Color.WHITE
    private var tvMinimizedBorderAnimator: android.animation.ValueAnimator? = null

    private fun currentTvMinimizedBorderColor(): Int {
        val baseColor = if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
            com.github.andreyasadchy.xtra.ui.tv.TvAccent.onDark
        } else {
            Color.WHITE
        }
        return androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, 0xD9)
    }

    fun refreshTvMinimizedBorderColor(animate: Boolean = true) {
        if (!isTv || _binding == null) return
        tvMultiStreamBadge?.invalidateSelf()
        _binding?.playerControls?.multiStream?.invalidate()
        if (isMaximized) return
        val drawable = tvMinimizedBorderDrawable ?: return
        val strokePx = tvMinimizedBorderStrokePx.takeIf { it > 0 } ?: return
        val targetColor = currentTvMinimizedBorderColor()
        tvMinimizedBorderAnimator?.cancel()
        tvMinimizedBorderAnimator = null
        val startColor = tvMinimizedBorderColor
        if (startColor == targetColor) return
        if (!animate) {
            tvMinimizedBorderColor = targetColor
            drawable.setStroke(strokePx, targetColor)
            return
        }
        tvMinimizedBorderAnimator = android.animation.ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = 420L
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                tvMinimizedBorderColor = c
                drawable.setStroke(strokePx, c)
            }
            start()
        }
    }

    protected open fun onTvMinimizedChanged(minimized: Boolean) {}

    private var tvMiniPlayerOnLeft = false
    private var tvMiniGhostListenersAttached = false
    private var tvMiniDodgeAnimator: android.animation.ObjectAnimator? = null
    private val tvMiniDodgeInterpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
    private val tvMiniDodgeCheckRunnable = Runnable { refreshTvMinimizedPosition() }
    private val tvMiniGhostFocusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        scheduleTvMinimizedGhostCheck()
    }
    private val tvMiniGhostComposeCallback: () -> Unit = {
        scheduleTvMinimizedGhostCheck()
    }

    private fun tvMinimizedLeftMarginPx(): Float {
        val density = resources.displayMetrics.density
        return if (requireContext().prefs().getBoolean(C.TV_COMPACT_RAIL, true)) {
            84f * density
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)
        }
    }

    private fun computeTvMinimizedTranslationX(fullW: Int, scaleX: Float, onLeft: Boolean = tvMiniPlayerOnLeft): Float {
        val scaledXDiff = (fullW * (1f - scaleX)) / 2f
        val tvMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)
        val newX = if (onLeft) {
            tvMinimizedLeftMarginPx()
        } else {
            fullW - (fullW * scaleX) - tvMargin
        }
        return -scaledXDiff + newX
    }

    private fun attachTvMinimizedGhostListeners() {
        if (tvMiniGhostListenersAttached) return
        val vto = activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive } ?: return
        vto.addOnGlobalFocusChangeListener(tvMiniGhostFocusListener)
        com.github.andreyasadchy.xtra.ui.tv.TvMiniPlayerFocus.onChanged = tvMiniGhostComposeCallback
        tvMiniGhostListenersAttached = true
    }

    private fun detachTvMinimizedGhostListeners() {
        _binding?.root?.removeCallbacks(tvMiniDodgeCheckRunnable)
        if (tvMiniGhostListenersAttached) {
            activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let { vto ->
                vto.removeOnGlobalFocusChangeListener(tvMiniGhostFocusListener)
            }
            tvMiniGhostListenersAttached = false
        }
        if (com.github.andreyasadchy.xtra.ui.tv.TvMiniPlayerFocus.onChanged === tvMiniGhostComposeCallback) {
            com.github.andreyasadchy.xtra.ui.tv.TvMiniPlayerFocus.onChanged = null
        }
    }

    fun scheduleTvMinimizedGhostCheck(immediate: Boolean = false) {
        val b = _binding ?: return
        if (!isTv || isMaximized) return
        b.root.removeCallbacks(tvMiniDodgeCheckRunnable)
        val act = activity as? MainActivity
        if (immediate || act?.isTvSideMenuOpen() == true) {
            refreshTvMinimizedPosition(forceRealign = immediate)
        } else {
            b.root.postDelayed(tvMiniDodgeCheckRunnable, 180L)
        }
    }

    private fun isComposeBackedView(view: View): Boolean {
        var cur: android.view.ViewParent? = if (view is android.view.ViewGroup) view else view.parent
        while (cur != null) {
            if (cur is androidx.compose.ui.platform.ComposeView || cur.javaClass.name.contains("AndroidComposeView")) {
                return true
            }
            cur = cur.parent
        }
        return view.javaClass.name.contains("AndroidComposeView")
    }

    private fun focusedElementWindowRect(act: MainActivity, fullW: Int, fullH: Int): android.graphics.Rect? {
        val focus = act.currentFocus ?: return null
        if (isComposeBackedView(focus)) {
            if (!com.github.andreyasadchy.xtra.ui.tv.TvMiniPlayerFocus.hasFocusedCard) return null
            return com.github.andreyasadchy.xtra.ui.tv.TvMiniPlayerFocus.focusedCardRect
        }
        if (focus.width <= 0 || focus.height <= 0 ||
            focus.width > (fullW * 0.85f).toInt() || focus.height > (fullH * 0.75f).toInt()
        ) {
            return null
        }
        val fLoc = IntArray(2)
        focus.getLocationInWindow(fLoc)
        return android.graphics.Rect(fLoc[0], fLoc[1], fLoc[0] + focus.width, fLoc[1] + focus.height)
    }

    private fun refreshTvMinimizedPosition(forceRealign: Boolean = false) {
        val b = _binding ?: return
        val act = activity as? MainActivity ?: return
        if (!isTv || isMaximized || tvAudioOnly) {
            tvMiniDodgeAnimator?.cancel()
            tvMiniDodgeAnimator = null
            tvMiniPlayerOnLeft = false
            return
        }
        if (isAnimating) {
            b.root.removeCallbacks(tvMiniDodgeCheckRunnable)
            b.root.postDelayed(tvMiniDodgeCheckRunnable, 120L)
            return
        }
        val fullW = b.slidingLayout.width.takeIf { it > 0 } ?: return
        val fullH = b.slidingLayout.height.takeIf { it > 0 } ?: return
        val (scaleX, scaleY) = getScaleValues()
        val autoSlide = requireContext().prefs().getBoolean(C.TV_MINI_PLAYER_AUTO_SLIDE, true)

        val desiredOnLeft = when {
            !autoSlide || act.isTvSideMenuOpen() -> false
            act.isTvSettingsScreen() -> act.isTvSettingsRightColumnFocused()
            else -> {
                val fRect = focusedElementWindowRect(act, fullW, fullH)
                if (fRect == null) {
                    tvMiniPlayerOnLeft
                } else {
                    val density = resources.displayMetrics.density
                    val pad = (8f * density).toInt()
                    val tvMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics).toInt()
                    val leftMargin = tvMinimizedLeftMarginPx().toInt()
                    val miniW = (fullW * scaleX).toInt()
                    val miniH = (fullH * scaleY).toInt()
                    val rootLoc = IntArray(2)
                    b.root.getLocationInWindow(rootLoc)
                    val miniBottom = rootLoc[1] + fullH - tvMargin
                    val miniRight = rootLoc[0] + fullW - tvMargin
                    val miniLeft = rootLoc[0] + leftMargin
                    val rightRect = android.graphics.Rect(miniRight - miniW - pad, miniBottom - miniH - pad, miniRight + pad, miniBottom + pad)
                    val leftRect = android.graphics.Rect(miniLeft - pad, miniBottom - miniH - pad, miniLeft + miniW + pad, miniBottom + pad)
                    if (!tvMiniPlayerOnLeft) {
                        android.graphics.Rect.intersects(fRect, rightRect) && !android.graphics.Rect.intersects(fRect, leftRect)
                    } else {
                        !(android.graphics.Rect.intersects(fRect, leftRect) && !android.graphics.Rect.intersects(fRect, rightRect))
                    }
                }
            }
        }

        val targetTx = computeTvMinimizedTranslationX(fullW, scaleX, desiredOnLeft)
        if (desiredOnLeft == tvMiniPlayerOnLeft && (!forceRealign || kotlin.math.abs(b.slidingLayout.translationX - targetTx) < 0.5f)) {
            return
        }
        tvMiniPlayerOnLeft = desiredOnLeft
        tvMiniDodgeAnimator?.cancel()
        tvMiniDodgeAnimator = null
        if (kotlin.math.abs(b.slidingLayout.translationX - targetTx) < 1f) {
            b.slidingLayout.translationX = targetTx
            return
        }
        tvMiniDodgeAnimator = android.animation.ObjectAnimator.ofFloat(b.slidingLayout, View.TRANSLATION_X, targetTx).apply {
            duration = 240L
            interpolator = tvMiniDodgeInterpolator
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (tvMiniDodgeAnimator === animation) {
                        tvMiniDodgeAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun applyTvMinimizedFrame(minimized: Boolean) {
        val b = _binding ?: return
        if (!isTv) return
        tvMinimizedBorderAnimator?.cancel()
        tvMinimizedBorderAnimator = null
        tvMiniDodgeAnimator?.cancel()
        tvMiniDodgeAnimator = null
        if (!minimized) {
            detachTvMinimizedGhostListeners()
            tvMiniPlayerOnLeft = false
            b.slidingLayout.alpha = 1f
            b.dragView.alpha = 1f
            b.playerLayout.setBackgroundColor(Color.BLACK)
            tvMinimizedBorderDrawable = null
            b.slidingLayout.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            b.slidingLayout.elevation = 0f
            b.playerLayout.clipToOutline = false
            b.playerLayout.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            b.dragView.background = null
            onTvMinimizedChanged(false)
            return
        }
        val density = resources.displayMetrics.density
        val (scaleX, _) = getScaleValues()
        val cornerPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (12f * density) / scaleX else 0f
        val strokePx = ((2.5f * density) / scaleX).toInt().coerceAtLeast(2)
        val outline = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
        b.playerBackground.clipChildren = false
        b.slidingLayout.clipChildren = false
        b.slidingLayout.outlineProvider = outline
        b.slidingLayout.elevation = 20f * density
        b.playerLayout.outlineProvider = outline
        b.playerLayout.clipToOutline = cornerPx > 0f
        val borderColor = currentTvMinimizedBorderColor()
        tvMinimizedBorderStrokePx = strokePx
        tvMinimizedBorderColor = borderColor
        val border = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = cornerPx
            setStroke(strokePx, borderColor)
        }
        tvMinimizedBorderDrawable = border
        b.dragView.background = border
        attachTvMinimizedGhostListeners()
        onTvMinimizedChanged(true)
        scheduleTvMinimizedGhostCheck()
    }

    fun getIsPortrait() = isPortrait

    fun reloadEmotes() = chatFragment?.reloadEmotes()

    fun isActive() = chatFragment?.isActive()

    fun disconnect() = chatFragment?.disconnect()

    fun reconnect() = chatFragment?.reconnect()

    fun secondViewIsHidden() = _binding?.chatLayout?.isVisible != true && isMaximized

    fun canEnterPictureInPicture(): Boolean {
        val quality = playbackService?.quality
        return quality?.name != VideoQuality.AUDIO_ONLY_QUALITY &&  quality?.name != VideoQuality.CHAT_ONLY_QUALITY
    }

    protected fun setPipActions(playing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            requireContext().prefs().getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            requireActivity().setPictureInPictureParams(
                PictureInPictureParams.Builder().apply {
                    setActions(listOf(
                        RemoteAction(
                            Icon.createWithResource(requireContext(), R.drawable.baseline_audiotrack_black_24),
                            getString(R.string.audio_only),
                            getString(R.string.audio_only),
                            PendingIntent.getBroadcast(
                                requireContext(),
                                REQUEST_CODE_AUDIO_ONLY,
                                Intent(MainActivity.INTENT_START_AUDIO_ONLY).setPackage(requireContext().packageName),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ),
                        RemoteAction(
                            Icon.createWithResource(requireContext(), R.drawable.baseline_close_black_36),
                            getString(R.string.close_player),
                            getString(R.string.close_player),
                            PendingIntent.getBroadcast(
                                requireContext(),
                                REQUEST_CODE_CLOSE,
                                Intent(MainActivity.INTENT_CLOSE_PLAYER).setPackage(requireContext().packageName),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ),
                        if (playing) {
                            RemoteAction(
                                Icon.createWithResource(requireContext(), R.drawable.baseline_pause_black_48),
                                getString(R.string.pause),
                                getString(R.string.pause),
                                PendingIntent.getBroadcast(
                                    requireContext(),
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        } else {
                            RemoteAction(
                                Icon.createWithResource(requireContext(), R.drawable.baseline_play_arrow_black_48),
                                getString(R.string.resume),
                                getString(R.string.resume),
                                PendingIntent.getBroadcast(
                                    requireContext(),
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        }
                    ))
                }.build()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val isInPIPMode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
            else -> false
        }
        if (isInPIPMode) {
            if (isPortrait) {
                binding.chatLayout.visibility = View.GONE
            } else {
                hideChatLayout()
            }
            useController = false
        } else if (isTv && _binding != null && isMaximized) {
            binding.dragView.post {
                if (view != null && isAdded && !isRemoving && isMaximized && activity?.hasWindowFocus() == true) {
                    restoreTvFocusIfLost()
                }
            }
        }
    }

    override fun initialize() {
        if (!started && playbackService?.started == true) {
            started = true
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        with(binding) {
            isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
            if (isMaximized) {
                enableBackground()
            } else {
                disableBackground()
            }
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            if (!isInPIPMode) {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(chatLayout.windowToken, 0)
                chatLayout.clearFocus()
                initLayout()
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.dismiss()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        with(binding) {
            if (isInPictureInPictureMode) {
                if (!isMaximized) {
                    isMaximized = true
                    requireActivity().onBackPressedDispatcher.addCallback(this@PlayerFragment, backPressedCallback)
                    if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleBackPressedCallback(true)
                    }
                    slidingLayout.translationX = 0f
                    slidingLayout.translationY = 0f
                    slidingLayout.scaleX = 1f
                    slidingLayout.scaleY = 1f
                }
                if (isPortrait) {
                    chatLayout.visibility = View.GONE
                } else {
                    hideChatLayout()
                }
                useController = false
                controllerAnimation?.cancel()
                binding.playerControls.root.alpha = 0f
                binding.playerControls.root.visibility = View.GONE
                // player dialog
                (childFragmentManager.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment)?.dismiss()
                // player chat message dialog
                (chatFragment?.childFragmentManager?.findFragmentByTag("messageDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("replyDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("imageDialog") as? BottomSheetDialogFragment)?.dismiss()
            } else {
                useController = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        _binding?.let { b ->
            b.playerControls.root.removeCallbacks(controllerHideAction)
            if (controllerIsAnimating) {
                val wasHiding = controllerHiding
                controllerAnimation?.cancel()
                controllerAnimation = null
                controllerIsAnimating = false
                controllerHiding = false
                if (wasHiding) {
                    b.playerControls.root.alpha = 0f
                    b.playerControls.root.visibility = View.GONE
                } else {
                    b.playerControls.root.alpha = 1f
                    b.playerControls.root.visibility = View.VISIBLE
                }
            }
        }
    }

    /** TV: what back does with the stream: "stop", "mini" (mini player) or "audio" (sound only). */
    private fun tvLeaveAction(): String {
        val prefs = requireContext().prefs()
        return prefs.getString(C.TV_BACK_ACTION, null) ?: if (prefs.getBoolean(C.TV_BACK_MINIMIZES_PLAYER, false)) "mini" else "stop"
    }

    private var streamEndJob: kotlinx.coroutines.Job? = null
    private var tvStreamEndNextTarget: Stream? = null
    private var tvStreamEndBannerView: TextView? = null
    protected var tvOfflineScreenActive = false
    private var tvOfflineOverlayView: FrameLayout? = null
    private var tvOfflineChannelLogin: String? = null
    private var tvOfflineFetchJob: kotlinx.coroutines.Job? = null
    private var tvOfflinePollJob: kotlinx.coroutines.Job? = null

    protected fun hideTvOfflineOverlay(immediate: Boolean = false) {
        tvOfflineFetchJob?.cancel()
        tvOfflineFetchJob = null
        tvOfflinePollJob?.cancel()
        tvOfflinePollJob = null
        tvOfflineScreenActive = false
        tvOfflineChannelLogin = null
        val overlay = tvOfflineOverlayView ?: return
        tvOfflineOverlayView = null
        overlay.animate().cancel()
        if (immediate) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        } else {
            overlay.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                .start()
        }
    }

    protected fun showTvOfflineOverlay(
        login: String,
        fallbackName: String?,
        fallbackAvatar: String?,
        showWaitingHint: Boolean = true,
    ) {
        if (!isTv || _binding == null || !isAdded || playbackService?.type != BasePlaybackService.STREAM) {
            return
        }
        val act = activity as? MainActivity ?: return
        if (!act.currentPlayingStream()?.channelLogin.equals(login, ignoreCase = true) &&
            !playbackService?.channelLogin.equals(login, ignoreCase = true)
        ) {
            return
        }
        if (tvOfflineScreenActive && tvOfflineChannelLogin.equals(login, ignoreCase = true)) {
            _binding?.bufferingIndicator?.isVisible = false
            return
        }
        tvOfflineFetchJob?.cancel()
        tvOfflineFetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val info = TvOfflineOverlay.fetchInfo(ctx, login)
            if (_binding == null || !isAdded) return@launch
            val currentLogin = act.currentPlayingStream()?.channelLogin ?: playbackService?.channelLogin
            if (!currentLogin.equals(login, ignoreCase = true)) return@launch
            // If Twitch confirms the streamer is actually live, don't show the offline screen
            if (info?.isLive == true) {
                if (!isPlayingNow()) {
                    streamOfflineReceived = false
                    restartPlayer()
                }
                return@launch
            }
            // Confirmed offline: remove loading spinner and display the channel's Twitch offline screen
            tvOfflineScreenActive = true
            tvOfflineChannelLogin = login
            streamOfflineReceived = true
            _binding?.bufferingIndicator?.isVisible = false
            loadingPicture?.let { img ->
                loadingPicture = null
                switchFrameListener = null
                (img.parent as? ViewGroup)?.removeView(img)
            }
            val displayName = info?.displayName ?: fallbackName?.takeIf { it.isNotBlank() } ?: login
            val avatar = info?.profileImageUrl ?: fallbackAvatar
            val overlay = TvOfflineOverlay.createView(
                context = ctx,
                displayName = displayName,
                profileImageUrl = avatar,
                offlineImageUrl = info?.offlineImageUrl,
                bannerImageUrl = info?.bannerImageUrl,
                compact = false,
                showWaitingHint = showWaitingHint,
            ).apply {
                alpha = 0f
            }
            tvOfflineOverlayView?.let { prev -> (prev.parent as? ViewGroup)?.removeView(prev) }
            val index = binding.playerLayout.indexOfChild(binding.aspectRatioFrameLayout) + 1
            binding.playerLayout.addView(
                overlay,
                index,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            tvOfflineOverlayView = overlay
            overlay.animate().alpha(1f).setDuration(240L).start()
            startTvOfflineLiveMonitor(login)
        }
    }

    private fun startTvOfflineLiveMonitor(login: String) {
        tvOfflinePollJob?.cancel()
        tvOfflinePollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (tvOfflineScreenActive && tvOfflineChannelLogin.equals(login, ignoreCase = true) && view != null && isAdded) {
                kotlinx.coroutines.delay(15_000L)
                if (!tvOfflineScreenActive || !tvOfflineChannelLogin.equals(login, ignoreCase = true) || view == null || !isAdded) {
                    return@launch
                }
                val ctx = context ?: return@launch
                val act = activity as? MainActivity ?: return@launch
                val currentLogin = act.currentPlayingStream()?.channelLogin ?: playbackService?.channelLogin
                if (!currentLogin.equals(login, ignoreCase = true)) {
                    hideTvOfflineOverlay(immediate = true)
                    return@launch
                }
                if (isPlayingNow()) {
                    hideTvOfflineOverlay()
                    return@launch
                }
                val info = TvOfflineOverlay.fetchInfo(ctx, login)
                if (info?.isLive == true && tvOfflineScreenActive && tvOfflineChannelLogin.equals(login, ignoreCase = true)) {
                    streamEndJob?.cancel()
                    streamEndJob = null
                    hideTvStreamEndBanner()
                    hideTvOfflineOverlay()
                    streamOfflineReceived = false
                    restartPlayer()
                    return@launch
                }
            }
        }
    }

    private fun hideTvStreamEndBanner() {
        tvStreamEndNextTarget = null
        tvStreamEndBannerView?.visibility = View.GONE
    }

    private fun cancelTvStreamEndCountdown(): Boolean {
        if (tvStreamEndNextTarget == null) return false
        streamEndJob?.cancel()
        streamEndJob = null
        hideTvStreamEndBanner()
        val act = activity as? MainActivity
        val stream = act?.currentPlayingStream() ?: getPlayingStream()
        val login = stream?.channelLogin ?: playbackService?.channelLogin
        if (!login.isNullOrBlank() && !isPlayingNow()) {
            // User chose to stay: keep/show the channel offline screen and auto-reconnect when back live
            tvOfflineOverlayView?.let { prev -> (prev.parent as? ViewGroup)?.removeView(prev) }
            tvOfflineOverlayView = null
            tvOfflineScreenActive = false
            showTvOfflineOverlay(
                login = login,
                fallbackName = stream?.channelName ?: playbackService?.channelName ?: login,
                fallbackAvatar = stream?.channelImage ?: playbackService?.channelImage,
                showWaitingHint = true,
            )
        }
        return true
    }

    private fun confirmTvStreamEndSwitchNow() {
        val target = tvStreamEndNextTarget ?: return
        streamEndJob?.cancel()
        streamEndJob = null
        hideTvStreamEndBanner()
        hideTvOfflineOverlay(immediate = true)
        (activity as? MainActivity)?.startStream(target, peekHud = true)
    }

    private fun showTvStreamEndBanner(targetName: String, secondsLeft: Int) {
        val ctx = context ?: return
        if (_binding == null) return
        val banner = tvStreamEndBannerView ?: TextView(ctx).apply {
            setBackgroundResource(R.drawable.tv_chat_overlay_background)
            setTextColor(Color.WHITE)
            textSize = 14f
            isFocusable = false
            isClickable = false
            val density = resources.displayMetrics.density
            maxWidth = (460 * density).toInt()
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            getTopRightStack().addView(this, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
            tvStreamEndBannerView = this
        }
        val msg = getString(R.string.tv_stream_end_switching, targetName, secondsLeft)
        val sb = android.text.SpannableStringBuilder(msg)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        banner.text = sb
        banner.visibility = View.VISIBLE
    }

    private suspend fun pickNextLiveFollowedStream(activity: MainActivity, currentLogin: String?): Stream? {
        val pipLogins = activity.tvPipActiveStreams().mapNotNull { it.channelLogin?.lowercase() }.toSet()
        fun List<Stream>.validCandidates(): List<Stream> = filter { s ->
            val lg = s.channelLogin?.takeIf { it.isNotBlank() } ?: return@filter false
            !lg.equals(currentLogin, ignoreCase = true) && lg.lowercase() !in pipLogins
        }.distinctBy { it.channelLogin?.lowercase() }

        val followed = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(4_500L) {
                com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.loadPages(
                    { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.followed(activity) },
                    24,
                )
            }
        }.getOrNull().orEmpty().validCandidates()
        if (followed.isNotEmpty()) {
            return followed.sortedWith(
                compareByDescending<Stream> { s ->
                    s.channelLogin?.let { com.github.andreyasadchy.xtra.ui.tv.TvFavorites.isFavorite(it) } == true
                }.thenByDescending { it.viewerCount ?: 0 }
            ).first()
        }

        // No followed channel is live: fall back to a live stream in the same category
        val current = activity.currentPlayingStream() ?: getPlayingStream()
        val gameId = current?.gameId?.takeIf { it.isNotBlank() } ?: playbackService?.gameId?.takeIf { it.isNotBlank() }
        val gameSlug = current?.gameSlug?.takeIf { it.isNotBlank() } ?: playbackService?.gameSlug?.takeIf { it.isNotBlank() }
        val gameName = current?.gameName?.takeIf { it.isNotBlank() } ?: playbackService?.gameName?.takeIf { it.isNotBlank() }
        if (gameId == null && gameSlug == null && gameName == null) {
            return null
        }
        val homeLangs = activity.prefs().getStringSet(C.TV_HOME_LANGUAGES, null)?.filter { it.isNotBlank() }?.ifEmpty { null }
        val preferred = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(4_500L) {
                com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.loadPages(
                    { com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.game(activity, gameId, gameSlug, gameName, fallbackLanguages = homeLangs) },
                    24,
                )
            }
        }.getOrNull().orEmpty().validCandidates()
        if (preferred.isNotEmpty()) {
            return preferred.maxByOrNull { it.viewerCount ?: 0 }
        }
        val anyLang = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(4_500L) {
                com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.loadPages(
                    {
                        com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.game(
                            activity,
                            gameId,
                            gameSlug,
                            gameName,
                            langMode = com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows.LANG_MODE_ALL,
                        )
                    },
                    24,
                )
            }
        }.getOrNull().orEmpty().validCandidates()
        return anyLang.maxByOrNull { it.viewerCount ?: 0 }
    }

    /**
     * TV: the stream stopped (Twitch ended it). Immediately displays the channel's Twitch offline screen
     * once confirmed offline, then after waiting for a potential raid performs the configured end-of-stream
     * behavior ("home": close player, "next": 10s countdown to switch to another live followed channel or
     * same-category stream, "stay": keep the offline screen visible and auto-reconnect when live again).
     */
    protected fun onTvStreamEnded() {
        if (view == null || !isAdded || !isTv || streamEndJob?.isActive == true) {
            return
        }
        val activity = activity as? MainActivity ?: return
        val currentStream = activity.currentPlayingStream() ?: getPlayingStream()
        val login = currentStream?.channelLogin ?: playbackService?.channelLogin
        // just back from a multi-stream: the next stream of it instead of closing
        activity.takeMultiStreamFallback(login)?.let { next ->
            hideTvOfflineOverlay(immediate = true)
            activity.dropTvPipForEndedStream(login)
            activity.startStream(next, peekHud = true)
            return
        }
        val action = requireContext().prefs().getString(C.TV_STREAM_END_ACTION, "home") ?: "home"
        if (!login.isNullOrBlank()) {
            showTvOfflineOverlay(
                login = login,
                fallbackName = currentStream?.channelName ?: playbackService?.channelName ?: login,
                fallbackAvatar = currentStream?.channelImage ?: playbackService?.channelImage,
                showWaitingHint = action == "stay",
            )
        }
        streamEndJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(6000L)
            // A raid announced in chat: wait for raid_go (up to 20s), then fall back directly to the raid target if the broadcaster cut the stream early.
            var waited = 0
            while (chatFragment?.isRaidPending() == true && waited++ < 20) {
                kotlinx.coroutines.delay(1000L)
                if (view == null || activity.currentPlayingStream()?.channelLogin != login || isPlayingNow()) {
                    if (isPlayingNow()) hideTvOfflineOverlay()
                    return@launch
                }
            }
            if (view == null || activity.currentPlayingStream()?.channelLogin != login || isPlayingNow()) {
                if (isPlayingNow()) hideTvOfflineOverlay()
                return@launch
            }
            chatFragment?.consumePendingRaidStream()?.let { raidTarget ->
                hideTvOfflineOverlay(immediate = true)
                activity.dropTvPipForEndedStream(login)
                activity.startStream(raidTarget, peekHud = true)
                return@launch
            }
            restoreTvOledProtection()
            when (requireContext().prefs().getString(C.TV_STREAM_END_ACTION, "home") ?: "home") {
                "stay" -> {
                    if (!login.isNullOrBlank() && !tvOfflineScreenActive) {
                        showTvOfflineOverlay(
                            login = login,
                            fallbackName = currentStream?.channelName ?: playbackService?.channelName ?: login,
                            fallbackAvatar = currentStream?.channelImage ?: playbackService?.channelImage,
                            showWaitingHint = true,
                        )
                    }
                    return@launch
                }
                "next" -> {
                    val next = pickNextLiveFollowedStream(activity, login)
                    if (next == null) {
                        hideTvOfflineOverlay(immediate = true)
                        close()
                        activity.closePlayer()
                        return@launch
                    }
                    tvStreamEndNextTarget = next
                    val nextName = next.channelName?.takeIf { it.isNotBlank() } ?: next.channelLogin ?: ""
                    for (sec in 10 downTo 1) {
                        if (view == null || activity.currentPlayingStream()?.channelLogin != login || isPlayingNow()) {
                            hideTvStreamEndBanner()
                            if (isPlayingNow()) hideTvOfflineOverlay()
                            return@launch
                        }
                        showTvStreamEndBanner(nextName, sec)
                        kotlinx.coroutines.delay(1000L)
                    }
                    val target = tvStreamEndNextTarget
                    hideTvStreamEndBanner()
                    if (target != null && view != null && activity.currentPlayingStream()?.channelLogin == login && !isPlayingNow()) {
                        hideTvOfflineOverlay(immediate = true)
                        activity.dropTvPipForEndedStream(login)
                        activity.startStream(target, peekHud = true)
                    }
                }
                else -> {
                    hideTvOfflineOverlay(immediate = true)
                    close()
                    activity.closePlayer()
                }
            }
        }
    }

    /** Leaves the player to browse (back, channel or category opened from the player). */
    fun leaveForBrowsing() {
        if (!isTv) {
            minimize()
            return
        }
        when (tvLeaveAction()) {
            "mini" -> minimize()
            "audio" -> {
                minimize()
                setTvAudioOnly(true)
            }
            else -> {
                close()
                (activity as? MainActivity)?.closePlayer()
            }
        }
    }

    private var tvAudioOnly = false

    /**
     * TV: leaving the app (Home, or Back on the first screen) keeps the sound of the mini player and of the sound only
     * mode ("minimized"), and also of the full screen player ("always"), as chosen in the Xtra TV settings.
     */
    fun keepsPlayingWhenAppLeft(): Boolean {
        if (!isTv || _binding == null || playbackService == null) {
            return false
        }
        return when (requireContext().prefs().getString(C.TV_KEEP_SOUND_ON_LEAVE, "never")) {
            "never" -> false
            "minimized" -> !isMaximized
            else -> true
        }
    }

    /** TV audio only: the mini player is hidden and the picture is not decoded, the sound goes on. */
    private fun setTvAudioOnly(enabled: Boolean) {
        tvAudioOnly = enabled
        _binding?.slidingLayout?.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        setVideoHidden(enabled)
        (activity as? MainActivity)?.updateTvNowPlaying()
    }

    open fun setVideoHidden(hidden: Boolean) {}

    /** The stream of the player, for the "Now playing" entry of the side menu. */
    fun getTvNowPlaying(): com.github.andreyasadchy.xtra.ui.tv.TvNowPlaying? {
        val service = playbackService ?: return null
        return com.github.andreyasadchy.xtra.ui.tv.TvNowPlaying(
            channelName = service.channelName ?: service.channelLogin,
            title = service.title,
            image = service.thumbnail?.takeIf { it.isNotBlank() } ?: service.channelImage,
            audioOnly = tvAudioOnly,
        )
    }

    fun getPlayingStream(): Stream? {
        val service = playbackService ?: return null
        return Stream(
            id = service.streamId,
            channelId = service.channelId,
            channelLogin = service.channelLogin,
            channelName = service.channelName,
            gameId = service.gameId,
            gameSlug = service.gameSlug,
            gameName = service.gameName,
            title = service.title,
            createdAt = service.createdAt,
            viewerCount = service.viewerCount,
        )
    }

    fun minimize() {
        with(binding) {
            isMaximized = false
            hideTvPlayerOsd(animate = false)
            if (isTv) {
                exitTvChatFreeMove(restorePrevious = false)
                exitTvChatScrollMode()
                restoreTvOledProtection()
                cancelTvStreamEndCountdown()
                // the mini player is not reached with the D-pad, "Now playing" in the side menu brings it back
                dragView.isFocusable = false
                hideTvPredictionPopup()
                hideTvPollPopup()
                updateTvClockVisibility(false)
                (activity as? MainActivity)?.updateTvNowPlaying()
            }
            if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(false)
            }
            backPressedCallback.remove()
            minimizedBackPressedCallback.remove()
            requireActivity().onBackPressedDispatcher.addCallback(this@PlayerFragment, minimizedBackPressedCallback)
            useController = false
            hideController(true)
            if (isTv) {
                tvMiniPlayerOnLeft = false
                applyTvMinimizedFrame(true)
            } else {
                dragView.setBackgroundResource(R.drawable.player_minimized_focus)
                val radius = 14f * resources.displayMetrics.density
                playerLayout.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                playerLayout.clipToOutline = true
                playerLayout.elevation = 8f * resources.displayMetrics.density
            }
            (activity as? MainActivity)?.setMainContentFocusable(true)
            (activity as? MainActivity)?.onPlayerMinimized()
            minimizedBackPressedCallback.isEnabled = dragView.hasFocus()
            fun animate() {
                val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                val targetTx: Float
                val targetTy: Float
                if (isTv) {
                    val tvMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)
                    val newY = slidingLayout.height - (slidingLayout.height * minimizedScaleY) - tvMargin
                    targetTx = computeTvMinimizedTranslationX(slidingLayout.width, minimizedScaleX)
                    targetTy = -scaledYDiff + newY
                } else {
                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                    val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                    val playerWidth = if (isPortrait) {
                        playerLayout.width
                    } else {
                        slidingLayout.width - getHorizontalInsets(windowInsets)
                    }
                    val newX = slidingLayout.width - (insets?.right ?: 0) - (playerWidth * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                    val newY = slidingLayout.height - navBarHeight - (playerLayout.height * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                    targetTx = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                    targetTy = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                }
                slidingLayout.animate().apply {
                    translationX(targetTx)
                    translationY(targetTy)
                    scaleX(minimizedScaleX)
                    scaleY(minimizedScaleY)
                    setDuration(250L)
                    setListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: Animator) {
                                isAnimating = true
                                if (view != null) {
                                    disableBackground()
                                }
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                                setListener(null)
                                activePointerId = -1
                                if (isTv && !isMaximized) {
                                    scheduleTvMinimizedGhostCheck()
                                }
                            }
                        }
                    )
                    start()
                }
            }
            if (isPortrait) {
                chatLayout.visibility = View.GONE
                chatFragment?.onChatHidden()
                slidingLayout.doOnLayout {
                    animate()
                }
            } else {
                showStatusBar()
                hideChatLayout()
                slidingLayout.doOnPreDraw {
                    animate()
                }
                val activity = requireActivity()
                activity.lifecycleScope.launch {
                    delay(500.milliseconds)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    fun maximize() {
        with(binding) {
            isMaximized = true
            if (isTv) {
                applyTvMinimizedFrame(false)
                dragView.isFocusable = true
                if (tvAudioOnly) {
                    setTvAudioOnly(false)
                }
                (activity as? MainActivity)?.updateTvNowPlaying()
            } else {
                playerLayout.clipToOutline = false
                playerLayout.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                playerLayout.elevation = 0f
            }
            requireActivity().onBackPressedDispatcher.addCallback(this@PlayerFragment, backPressedCallback)
            minimizedBackPressedCallback.remove()
            minimizedBackPressedCallback.isEnabled = false
            dragView.background = null
            (activity as? MainActivity)?.setMainContentFocusable(false)
            dragView.requestFocus()
            if (playbackService?.type == BasePlaybackService.STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(true)
            }
            useController = true
            if (!controllerHideOnTouch) {
                showController(force = true)
                updateProgress()
            } else if (isTv) {
                updateTvClockVisibility()
            }
            if (isPortrait) {
                chatLayout.visibility = View.VISIBLE
            } else {
                hideStatusBar()
                if (isChatOpen) {
                    showChatLayout()
                }
            }
            slidingLayout.animate().apply {
                translationX(0f)
                translationY(0f)
                scaleX(1f)
                scaleY(1f)
                setDuration(250L)
                setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            isAnimating = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            isAnimating = false
                            setListener(null)
                            if (view != null) {
                                enableBackground()
                            }
                            activePointerId = -1
                        }
                    }
                )
                start()
            }
        }
    }

    fun findUnlistedVideo() {
        val unavailableQualities = getUnavailableQualities()
        val qualities = if (unavailableQualities.isNotEmpty()) {
            mutableListOf<VideoQuality>().apply {
                playbackService?.qualities?.filter { !it.url.isNullOrBlank() }?.let { addAll(it) }
                addAll(unavailableQualities)
            }
                .sortedByDescending { it.bitrate }
                .sortedByDescending { it.frameRate }
                .sortedByDescending { it.resolution }
                .toMutableList().apply {
                    find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                        remove(audio)
                        add(audio)
                    }
                }
        } else {
            playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
        }
        viewModel.findUnlistedVideo(
            requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            qualities,
            playbackService?.streamId,
            playbackService?.channelLogin,
            playbackService?.createdAt,
        )
    }

    fun showDownloadDialog() {
        if (playbackService?.loaded == true) {
            when (playbackService?.type) {
                BasePlaybackService.STREAM -> {
                    val qualities = playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newStreamInstance(
                        id = playbackService?.streamId,
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        channelName = playbackService?.channelName,
                        channelImage = playbackService?.channelImage,
                        gameId = playbackService?.gameId,
                        gameSlug = playbackService?.gameSlug,
                        gameName = playbackService?.gameName,
                        title = playbackService?.title,
                        thumbnail = playbackService?.thumbnail,
                        createdAt = playbackService?.createdAt,
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityResolutions = qualities?.map { it.resolution.toString() }?.toTypedArray(),
                        qualityFrameRates = qualities?.map { it.frameRate.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
                BasePlaybackService.VIDEO -> {
                    val qualities = playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newVideoInstance(
                        id = playbackService?.videoId,
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        channelName = playbackService?.channelName,
                        channelImage = playbackService?.channelImage,
                        gameId = playbackService?.gameId,
                        gameSlug = playbackService?.gameSlug,
                        gameName = playbackService?.gameName,
                        title = playbackService?.title,
                        thumbnail = playbackService?.thumbnail,
                        createdAt = playbackService?.createdAt,
                        durationSeconds = playbackService?.durationSeconds,
                        type = playbackService?.videoType,
                        animatedPreviewUrl = playbackService?.videoAnimatedPreviewURL,
                        totalDuration = getTotalDuration(),
                        currentPosition = getCurrentPosition(),
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityResolutions = qualities?.map { it.resolution.toString() }?.toTypedArray(),
                        qualityFrameRates = qualities?.map { it.frameRate.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
                BasePlaybackService.CLIP -> {
                    val qualities = playbackService?.qualities?.filter { !it.url.isNullOrBlank() }
                    DownloadDialog.newClipInstance(
                        id = playbackService?.clipId,
                        channelId = playbackService?.channelId,
                        channelLogin = playbackService?.channelLogin,
                        channelName = playbackService?.channelName,
                        channelImage = playbackService?.channelImage,
                        gameId = playbackService?.gameId,
                        gameSlug = playbackService?.gameSlug,
                        gameName = playbackService?.gameName,
                        title = playbackService?.title,
                        thumbnail = playbackService?.thumbnail,
                        createdAt = playbackService?.createdAt,
                        durationSeconds = playbackService?.durationSeconds,
                        videoId = playbackService?.videoId,
                        videoOffsetSeconds = playbackService?.videoOffsetSeconds,
                        videoCreatedAt = playbackService?.videoCreatedAt,
                        qualityNames = qualities?.map { it.name.toString() }?.toTypedArray(),
                        qualityResolutions = qualities?.map { it.resolution.toString() }?.toTypedArray(),
                        qualityFrameRates = qualities?.map { it.frameRate.toString() }?.toTypedArray(),
                        qualityBitrates = qualities?.map { it.bitrate.toString() }?.toTypedArray(),
                        qualityCodecs = qualities?.map { it.codecs.toString() }?.toTypedArray(),
                        qualityUrls = qualities?.map { it.url.toString() }?.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
            }
        }
    }

    fun onSleepTimerChanged(durationMs: Long, hours: Int, minutes: Int, lockScreen: Boolean) {
        if (durationMs > 0L) {
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                requireContext(),
                when {
                    hours == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                    minutes == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.hours, hours, hours)
                    )
                    else -> getString(
                        R.string.playback_will_stop_hours_minutes,
                        resources.getQuantityString(R.plurals.hours, hours, hours),
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                },
                Toast.LENGTH_LONG
            ).show()
        } else if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.timer_canceled, Toast.LENGTH_LONG).show()
        }
        if (lockScreen != requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false)) {
            requireContext().prefs().edit { putBoolean(C.SLEEP_TIMER_LOCK, lockScreen) }
        }
        (activity as? MainActivity)?.setSleepTimer(durationMs)
    }

    override fun onChange(requestCode: Int, index: Int, text: CharSequence, tag: String?, tag2: String?) {
        when (requestCode) {
            REQUEST_CODE_QUALITY -> {
                changeQuality(playbackService?.qualities?.find { it.name == tag && it.url == tag2 })
                changePlayerMode()
                setQualityText()
            }
            REQUEST_CODE_SPEED -> {
                requireContext().prefs().getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")?.let { speeds ->
                    speeds.getOrNull(index)?.toFloatOrNull()?.let { speed ->
                        setPlaybackSpeed(speed)
                        if (playbackService?.type != BasePlaybackService.STREAM && getTvDvrStream() == null) {
                            requireContext().prefs().edit { putFloat(C.PLAYER_SPEED, speed) }
                        }
                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSpeed(speed.toString())
                    }
                }
            }
        }
    }

    override fun onIntegrityTokenLoaded(callback: String?) {
        when (callback) {
            "refreshStream" -> {
                retry(callback)
                viewModel.isFollowingChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    playbackService?.channelLogin,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    TwitchApiHelper.getHelixHeaders(requireContext()),
                )
            }
            "refreshVideo" -> {
                retry(callback)
                val videoId = playbackService?.videoId
                viewModel.isFollowingChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    playbackService?.channelLogin,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    TwitchApiHelper.getHelixHeaders(requireContext()),
                )
                if (!videoId.isNullOrBlank() && (isTv || requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true) || requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false))) {
                    viewModel.loadGamesList(
                        videoId,
                        requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        TwitchApiHelper.getGQLHeaders(requireContext()),
                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
            }
            "refreshClip" -> {
                retry(callback)
                viewModel.isFollowingChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    playbackService?.channelLogin,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    TwitchApiHelper.getHelixHeaders(requireContext()),
                )
            }
            "follow" -> {
                viewModel.saveFollowChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    playbackService?.channelLogin,
                    playbackService?.channelName,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                    !requireContext().prefs().getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
                    playbackService?.createdAt,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            "unfollow" -> {
                viewModel.deleteFollowChannel(
                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                    playbackService?.channelId,
                    requireContext().prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
        }
    }

    private var tvMultiStreamBadge: com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.MultiStreamBadgeDrawable? = null
    private var tvCoStreamLookupKey: String? = null
    private var tvCoStreamLookupJob: Job? = null
    private var tvCoStreamObserveJob: Job? = null
    private var playerCoStreamers: List<Stream> = emptyList()

    private fun currentCoStreamIdentity(): Triple<String?, String?, String?> {
        val cur = (activity as? MainActivity)?.currentPlayingStream()
        val cid = playbackService?.channelId?.takeIf { it.isNotBlank() } ?: cur?.channelId?.takeIf { it.isNotBlank() }
        val clogin = playbackService?.channelLogin?.takeIf { it.isNotBlank() } ?: cur?.channelLogin?.takeIf { it.isNotBlank() }
        val ctitle = playbackService?.title?.takeIf { it.isNotBlank() } ?: cur?.title?.takeIf { it.isNotBlank() }
        return Triple(cid, clogin, ctitle)
    }

    private fun ensurePlayerCoStreamDetection() {
        if (!isTv || !isAdded || _binding == null) return
        if (!isStreamScreen()) {
            playerCoStreamers = emptyList()
            tvMultiStreamBadge?.count = 0
            return
        }
        val (cid, clogin, ctitle) = currentCoStreamIdentity()
        if (cid.isNullOrBlank() && clogin.isNullOrBlank()) return

        if (tvCoStreamObserveJob?.isActive != true) {
            tvCoStreamObserveJob = viewLifecycleOwner.lifecycleScope.launch {
                com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.version.collectLatest {
                    updateTvMultiStreamBadge()
                }
            }
        }

        val key = "${cid.orEmpty()}|${clogin?.lowercase().orEmpty()}"
        if (key != tvCoStreamLookupKey) {
            tvCoStreamLookupKey = key
            playerCoStreamers = emptyList()
            updateTvMultiStreamBadge()
            tvCoStreamLookupJob?.cancel()
            val ctx = requireContext().applicationContext
            tvCoStreamLookupJob = viewLifecycleOwner.lifecycleScope.launch {
                val found = com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.loadForChannel(ctx, cid, clogin, ctitle)
                if (tvCoStreamLookupKey == key) {
                    playerCoStreamers = found
                    updateTvMultiStreamBadge()
                }
            }
        } else {
            updateTvMultiStreamBadge()
        }
    }

    fun updateTvMultiStreamBadge() {
        if (!isTv || _binding == null) return
        val badge = tvMultiStreamBadge ?: return
        if (!isStreamScreen()) {
            badge.count = 0
            _binding?.playerControls?.multiStream?.invalidate()
            return
        }
        val (cid, clogin, _) = currentCoStreamIdentity()
        val partnersCount = getCoStreamers().size
        val cachedCount = com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.getCount(cid, clogin)
        badge.count = max(partnersCount, cachedCount)
        _binding?.playerControls?.multiStream?.invalidate()
    }

    override fun onDestroyView() {
        com.github.andreyasadchy.xtra.ui.tv.TvChatQrCompanion.scheduleDetach(this)
        tvVolumePill?.destroy()
        tvVolumePill = null
        tvLiveEventsLookupJob?.cancel()
        tvLiveEventsLookupJob = null
        tvLiveEventsTickerJob?.cancel()
        tvLiveEventsTickerJob = null
        tvLiveEventsLookupKey = null
        tvCoStreamLookupJob?.cancel()
        tvCoStreamLookupJob = null
        tvCoStreamObserveJob?.cancel()
        tvCoStreamObserveJob = null
        tvCoStreamLookupKey = null
        playerCoStreamers = emptyList()
        tvMultiStreamBadge?.let { d -> _binding?.playerControls?.multiStream?.overlay?.remove(d) }
        tvMultiStreamBadge = null
        detachTvMinimizedGhostListeners()
        tvMiniDodgeAnimator?.cancel()
        tvMiniDodgeAnimator = null
        tvMiniPlayerOnLeft = false
        tvChatFreeMoving = false
        tvChatFreeHintView = null
        tvMinimizedBorderAnimator?.cancel()
        tvMinimizedBorderAnimator = null
        tvMinimizedBorderDrawable = null
        tvOsdContainer?.removeCallbacks(tvOsdHideRunnable)
        tvOsdContainer?.animate()?.cancel()
        tvOsdContainer = null
        tvOsdIcon = null
        tvOsdText = null
        chatUnlockTimerJob?.cancel()
        chatUnlockTimerJob = null
        tvFavoritesObserverJob?.cancel()
        tvFavoritesObserverJob = null
        isTvChatLocked = null
        chatLockObservedFragment = null
        com.github.andreyasadchy.xtra.ui.tv.TvDim.removeListener(tvDimListener)
        _binding?.playerLayout?.removeOnLayoutChangeListener(tvHeaderLayoutListener)
        tvHeaderLayoutListenerAdded = false
        tvTimeSeparatorListenerAdded = false
        appliedTvHudHeaderScale = 0f
        lastLoadedChannelAvatarUrl = null
        lastLoadedCategoryGameId = null
        _binding?.root?.removeCallbacks(tvHudTicker)
        _binding?.root?.removeCallbacks(customKeyLongRunnable)
        customKeyDownSeen = 0
        customKeyPendingLongAction = null
        _binding?.root?.removeCallbacks(dpadLongRunnable)
        dpadKeyDownSeen = 0
        dpadPendingLongAction = null
        _binding?.root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(hudFocusListener)
        if (isTv) {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        expArchiveLookupJob?.cancel()
        expArchiveLookupJob = null
        resetFrameRateMatching()
        _binding?.playerControls?.root?.removeCallbacks(tvScrubCommitRunnable)
        tvScrubPosition = -1L
        tvScrubOriginPosition = -1L
        tvScrubDuration = 0L
        seekPreview?.release()
        seekPreview = null
        tvSeekStartEpochMs = null
        tvPredictionView?.removeCallbacks(tvPredictionHideRunnable)
        tvPredictionView = null
        tvPollView?.removeCallbacks(tvPollHideRunnable)
        tvPollView = null
        tvStreamEndNextTarget = null
        tvStreamEndBannerView = null
        hideTvOfflineOverlay(immediate = true)
        tvOledProtectionActive = false
        tvStatsView = null
        tvTopRightStack = null
        tvAdblockView = null
        (activity as? MainActivity)?.let {
            if (it.playerFragment == null || it.playerFragment === this) {
                it.setMainContentFocusable(true)
            }
        }
        super.onDestroyView()
        _binding = null
    }

    fun getCoStreamers(): List<Stream> {
        val (cid, clogin, _) = currentCoStreamIdentity()
        val merged = LinkedHashMap<String, Stream>()
        fun addAllFrom(list: List<Stream>) {
            list.forEach { s ->
                val k = s.channelLogin?.lowercase()?.takeIf { it.isNotBlank() } ?: return@forEach
                if (s.channelId != cid && !k.equals(clogin, ignoreCase = true)) {
                    merged.putIfAbsent(k, s)
                }
            }
        }
        addAllFrom(playerCoStreamers)
        addAllFrom(chatFragment?.getCoStreamers().orEmpty())
        addAllFrom(com.github.andreyasadchy.xtra.ui.tv.TvCoStreamCache.getPartners(cid, clogin))
        return merged.values.toList()
    }

    companion object {
        private const val REQUEST_CODE_QUALITY = 0
        private const val REQUEST_CODE_SPEED = 1
        private const val REQUEST_CODE_AUDIO_ONLY = 2
        private const val REQUEST_CODE_PLAY_PAUSE = 3
        private const val REQUEST_CODE_CLOSE = 4
        private const val LIVE_EDGE_MS = 20_000L
        private const val DVR_CAUGHT_UP_MS = 10_000L
        private const val LIVE_FIRST_STEP_MS = 60_000L
        const val KEY_OFFLINE = "offline"
        /** Set by the app when the player screen opens a live stream: known before its playback service is connected. */
        const val KEY_STREAM = "tvStream"
        /** Set by the app when the player screen opens a VOD: known before its playback service is connected. */
        const val KEY_VIDEO = "tvVideo"
        /** Set by the app when the player screen opens a live-rewind DVR VOD from a live stream timeline. */
        const val KEY_DVR = "tvDvr"

        /**
         * TV: picture of the stream that is opening (the picture of its card, then the latest one of Twitch), shown in place
         * of a black screen until the first picture of the video.
         */
        var pendingLoadingPicture: LoadingPicture? = null

        /** TV: the next player screen comes from a switch of stream, it shows its HUD for a moment (without the focus). */
        var peekHudOnStart = false
        /** TV: the switch came from an already full-screen player, so the HUD info appears immediately at full opacity without fading from zero. */
        var peekHudImmediateSwitch = false

        /** TV: last picture of the stream left by a switch of stream (left / right...), for a second or two. */
        private var switchFrame: android.graphics.Bitmap? = null
        private var switchFrameAt = 0L
        /** The loading picture of the next stream waits for that picture (taken a little after the switch started). */
        private var switchFrameListener: ((android.graphics.Bitmap) -> Unit)? = null

        private fun takeSwitchFrame(): android.graphics.Bitmap? {
            val frame = switchFrame?.takeIf { android.os.SystemClock.elapsedRealtime() - switchFrameAt < 3000L }
            switchFrame = null
            return frame
        }

        private fun putSwitchFrame(frame: android.graphics.Bitmap) {
            val listener = switchFrameListener
            if (listener != null) {
                switchFrameListener = null
                listener(frame)
            } else {
                switchFrame = frame
                switchFrameAt = android.os.SystemClock.elapsedRealtime()
            }
        }

        class LoadingPicture(val channelLogin: String?, val thumbnail: String?, val fallback: String?)

        /** Button of the controls focused last (its id, else its description), kept from one player to the next. */
        private var lastControlId = View.NO_ID
        private var lastControlLabel: String? = null

        private var heldLongKeyCode = 0

        fun markHeldRemoteKey(keyCode: Int) {
            heldLongKeyCode = keyCode
        }

        /** Swallows trailing auto-repeat DOWN events and final UP event of a remote long-press across fragment switches. */
        fun consumeHeldRemoteKeyTail(event: KeyEvent): Boolean {
            if (heldLongKeyCode == 0 || event.keyCode != heldLongKeyCode) {
                return false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                heldLongKeyCode = 0
                return false
            }
            if (event.action == KeyEvent.ACTION_UP || event.isCanceled) {
                heldLongKeyCode = 0
            }
            return true
        }
    }
}
