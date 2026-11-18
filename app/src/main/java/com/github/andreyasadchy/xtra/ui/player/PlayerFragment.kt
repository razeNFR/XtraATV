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
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.trackPipAnimationHintView
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.focusRequester
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

    /** Live broadcast or live-rewind DVR broadcast (where left/right and channel +/- switch streamers). */
    fun isLiveOrDvrScreen(): Boolean = isStreamScreen() || isDvrScreen()

    private fun effectiveDpadAction(action: String, pipOpen: Boolean): String =
        if (!pipOpen && com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.isPipOnlyAction(action)) {
            com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.ACTION_DEFAULT
        } else {
            action
        }

    /**
     * TV: returns -1 or +1 when [keyCode] is configured to switch to the previous/next streamer while controls are
     * hidden on a live or live-rewind DVR screen (also before the new player view is inflated during quick switches).
     */
    fun handlesDpadSwitchDirection(keyCode: Int): Int? {
        if (!isTv || !isMaximized || !isLiveOrDvrScreen() || hudOpenedByUser) {
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

    private fun peekHud() {
        if (binding.playerControls.root.hasFocus()) {
            return
        }
        hudPeek = true
        // only the info of the stream (title, channel, category, viewers, quality...), no buttons nor timeline
        setHudInfoOnly(true)
        showController()
        binding.root.removeCallbacks(hudPeekHideAction)
        binding.root.postDelayed(hudPeekHideAction, 2500L)
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
        if (isTv) {
            showLoadingPicture()
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
                            if ((isSwitch && isLiveOrDvrScreen()) || (!isSwitch && dpadAction != Keys.ACTION_DEFAULT)) {
                                binding.dragView.requestFocus()
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    executeTvRemoteAction(dpadAction)
                                }
                                return@setOnKeyListener true
                            }
                        }
                        // down from the timeline: back to the button the focus was on, not the nearest one
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && _binding != null) {
                            val last = findLastControl()
                            if (last != null && last.isShown && last.isFocusable && last.requestFocus()) {
                                return@setOnKeyListener true
                            }
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
                                seek(position)
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
        }
    }

    fun start() {
        setupTvHud()
        setupSeekPreview()
        // a switch of stream (left / right): the HUD of the stream reached for a moment, without the focus
        if (isTv && peekHudOnStart) {
            peekHudOnStart = false
            binding.root.postDelayed({ if (view != null && isMaximized) peekHud() }, 300L)
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
                        if (!videoId.isNullOrBlank() && (isTv || requireContext().prefs().getBoolean(C.PLAYER_GAMES_BUTTON, true) || requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false))) {
                            viewModel.loadGamesList(
                                videoId,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                TwitchApiHelper.getGQLHeaders(requireContext()),
                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                        }
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
                        "0" -> "${channelName}(${channelLogin})"
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
                    title.text = titleText
                }
                val gameName = playbackService?.gameName
                if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
                    category.visibility = View.VISIBLE
                    category.text = gameName
                    category.setOnClickListener {
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
                }
                updateTvAvatars()
                // no minimize button on TV: back does it, as the setting says
                if (!isTv && requireContext().prefs().getBoolean(C.PLAYER_MINIMIZE, true)) {
                    minimize.visibility = View.VISIBLE
                    minimize.setOnClickListener { minimize() }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_VOLUME_BUTTON, true)) {
                    volume.visibility = View.VISIBLE
                    volume.setOnClickListener {
                        showController(force = true)
                        showVolumeDialog()
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
                                            Toast.makeText(requireContext(), R.string.video_not_found, Toast.LENGTH_SHORT).show()
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
                    if (isTv) {
                        // TV: timeline of the whole broadcast, going back in it plays the VOD of the stream
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
                seekPreview?.setChapters(null)
                if (playbackService?.type == BasePlaybackService.VIDEO) {
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
                                            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                                        } else {
                                            if (following) {
                                                Toast.makeText(requireContext(), getString(R.string.now_following, displayName), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(requireContext(), getString(R.string.unfollowed, displayName), Toast.LENGTH_SHORT).show()
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
                val fragment = when (playbackService?.type) {
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
                if (fragment != null) {
                    childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, fragment).commit()
                }
                chatFragment = fragment
            }
        }
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
                            val newX = slidingLayout.width - (slidingLayout.width * minimizedScaleX) - tvMargin
                            val newY = slidingLayout.height - (slidingLayout.height * minimizedScaleY) - tvMargin
                            slidingLayout.translationX = -scaledXDiff + newX
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

    fun showVolumeDialog() {
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
    }

    fun showChat() {
        isChatOpen = true
        hideTvPredictionPopup()
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
        }
    }

    private fun showChatLayout() {
        with(binding) {
            if (isTv) {
                applyTvChatLayout()
                chatLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                chatLayout.visibility = View.VISIBLE
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
                restartPlayer()
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
        binding.playerControls.category.apply {
            if (!gameName.isNullOrBlank() && requireContext().prefs().getBoolean(C.PLAYER_CATEGORY, true)) {
                text = gameName
                visibility = View.VISIBLE
                setOnClickListener {
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
                    minimize()
                }
            } else {
                text = null
                visibility = View.GONE
            }
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
    private var pendingDvr = false
    private var pendingDvrPosition = 0L
    private var pendingDvrResumeFromPause = false

    protected fun isTvLiveTimeline() = isTv && playbackService?.type == BasePlaybackService.STREAM

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
        if (!isTv || !com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(ctx)) {
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
            isTv && playbackService?.type == BasePlaybackService.VIDEO && playbackService?.videoId == null && it.channelLogin == playbackService?.channelLogin
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

    private fun startTvDvr(position: Long) {
        pendingDvr = true
        pendingDvrPosition = position
        (activity as? MainActivity)?.dvrStream = Stream(
            id = playbackService?.streamId,
            channelId = playbackService?.channelId,
            channelLogin = playbackService?.channelLogin,
            channelName = playbackService?.channelName,
            gameId = playbackService?.gameId,
            gameSlug = playbackService?.gameSlug,
            gameName = playbackService?.gameName,
            title = playbackService?.title,
            createdAt = playbackService?.createdAt,
            viewerCount = playbackService?.viewerCount,
        )
        Toast.makeText(requireContext(), R.string.tv_looking_for_vod, Toast.LENGTH_SHORT).show()
        findUnlistedVideo()
    }

    /**
     * D-pad seeking on the time bar: a short press moves 10 seconds (a minute back from the live), holding the key moves faster and faster.
     * The player seeks once, when the key is released.
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
                    if (tvScrubPosition < 0) {
                        val shiftBehindMs = if (isLive && !expVod) {
                            (playbackService as? ExoPlayerService)?.getLiveTimeShiftBehindMs() ?: 0L
                        } else {
                            0L
                        }
                        tvScrubPosition = when {
                            expVod -> (getCurrentPosition() ?: duration).coerceIn(0L, duration)
                            isLive -> (duration - shiftBehindMs).coerceIn(0L, duration)
                            else -> getCurrentPosition() ?: 0
                        }
                        progressBar.isPressed = true
                    }
                    // the longer the key is held, the faster it goes, relative to the length of the video
                    val step = when {
                        event.repeatCount < 4 -> 10_000L
                        event.repeatCount < 12 -> maxOf(30_000L, duration / 300)
                        event.repeatCount < 24 -> maxOf(60_000L, duration / 120)
                        else -> maxOf(120_000L, duration / 50)
                    }.let {
                        // on the live: the first step back goes a minute back, right into the VOD, small steps never get past its edge
                        if (isLive && !expVod && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && tvScrubPosition >= duration - LIVE_EDGE_MS) maxOf(it, LIVE_FIRST_STEP_MS) else it
                    }
                    tvScrubPosition = (tvScrubPosition + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step).coerceIn(0, duration)
                    progressBar.setDuration(duration)
                    progressBar.setPosition(tvScrubPosition)
                    position.text = if (expVod && tvScrubPosition >= duration - 8_000L) {
                        getString(R.string.tv_live)
                    } else {
                        DateUtils.formatElapsedTime(tvScrubPosition / 1000)
                    }
                    seekPreview?.show(tvScrubPosition, duration)
                    root.removeCallbacks(controllerHideAction)
                }
                KeyEvent.ACTION_UP -> {
                    if (tvScrubPosition >= 0) {
                        val newPosition = tvScrubPosition
                        tvScrubPosition = -1
                        progressBar.isPressed = false
                        seekPreview?.hideLater()
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
                }
            }
        }
        return true
    }

    private var tvStatsView: TextView? = null
    private var seekPreview: TvSeekPreview? = null

    /** Storyboard description of a video: the seekPreviewsURL of the video, else next to its animated preview. */
    private suspend fun getStoryboardUrl(videoId: String, animatedPreviewUrl: String?): String? {
        val seekPreviews = try {
            (requireContext().applicationContext as com.github.andreyasadchy.xtra.XtraApp).xtraModule.graphQLRepository.loadQueryVideo(
                requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                videoId
            ).data?.video?.let { it.seekPreviewsURL ?: it.animatedPreviewURL?.let { preview -> "${preview.substringBeforeLast("/")}/$videoId-info.json" } }
        } catch (e: Exception) {
            null
        }
        return seekPreviews ?: animatedPreviewUrl?.let { "${it.substringBeforeLast("/")}/$videoId-info.json" }
    }

    /** Live: thumbnails come from the storyboards of the VOD being recorded. */
    private fun setupLiveSeekPreview() {
        val login = playbackService?.channelLogin ?: return
        val preview = createSeekPreview()
        viewLifecycleOwner.lifecycleScope.launch {
            val url = getLiveStoryboardUrl(login, playbackService?.createdAt)
            if (url != null && view != null) {
                preview.load(url, refresh = true)
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
            return null
        }
        (activity as? MainActivity)?.dvrChatVideoId = video.id
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

    /** TV chat overlay: size, corner and transparency from the chat layout panel, flush with the screen edges by default. */
    private fun applyTvChatLayout() {
        with(binding) {
            val prefs = requireContext().prefs()
            val metrics = resources.displayMetrics
            val position = prefs.getInt(C.TV_CHAT_POSITION, 0)
            val mode = prefs.getInt(C.TV_CHAT_MODE, 0)
            val margin = if (mode == 0) (prefs.getInt(C.TV_CHAT_MARGIN, 0) * metrics.density).toInt() else 0
            val screenWidth = root.width.takeIf { it > 0 } ?: metrics.widthPixels
            val screenHeight = root.height.takeIf { it > 0 } ?: metrics.heightPixels
            val chatWidth = (screenWidth * prefs.getInt(C.TV_CHAT_WIDTH, 30) / 100f).toInt()
            val chatHeight = (screenHeight * prefs.getInt(C.TV_CHAT_HEIGHT, 50) / 100f).toInt()
            // corners 0..3, middle of a side 4..7 (right, left, top, bottom)
            val left = position == 2 || position == 3 || position == 5
            val top = position == 1 || position == 3 || position == 6
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
                        marginStart = margin
                        marginEnd = margin
                        topMargin = margin
                        bottomMargin = margin
                    }
                }
            }
            chatFragment?.setSingleLine(mode == 2 && prefs.getInt(C.TV_CHAT_LINES, 3) == 1)
            chatLayout.animate().cancel()
            chatLayout.translationY = if (playerControls.root.isVisible && !controllerHiding && !hudPeek) getTvChatLift() else 0f
            // both backgrounds are opaque: the opacity chosen is the only one applied (the rounded one was already see-through,
            // 100 % gave 55 % as soon as the chat left the edges)
            chatLayout.setBackgroundResource(if (margin == 0) R.drawable.tv_chat_overlay_flush else R.drawable.tv_chat_overlay_rounded)
            chatLayout.background?.mutate()?.alpha = (prefs.getInt(C.TV_CHAT_OPACITY, 55) * 255 / 100).coerceIn(0, 255)
            chatLayout.clipToOutline = margin != 0
        }
    }

    /** The chat goes up over the controls only when it is at the bottom of the screen. */
    private fun getTvChatLift(): Float {
        val position = requireContext().prefs().getInt(C.TV_CHAT_POSITION, 0)
        val mode = requireContext().prefs().getInt(C.TV_CHAT_MODE, 0)
        // the band at the bottom over the stream: it goes up over the controls while they are shown
        if (mode == 2 && requireContext().prefs().getBoolean(C.TV_CHAT_BAND_OVERLAY, false)) {
            val top = position == 1 || position == 3 || position == 6
            return if (top) 0f else -resources.getDimension(R.dimen.tv_chat_lift)
        }
        // next to or under the stream, the controls are on the picture and the chat stays where it is
        if (mode != 0) {
            return 0f
        }
        return if (position == 0 || position == 2 || position == 7) -resources.getDimension(R.dimen.tv_chat_lift) else 0f
    }

    /**
     * Panel with sliders to place the chat, on the opposite side so the chat can be seen while it changes.
     * Its rows follow the display: corners over the stream, sides next to it, above or under it.
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
        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(context) { dismiss ->
            val position = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(prefs.getInt(C.TV_CHAT_POSITION, 0)) }
            val mode = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(prefs.getInt(C.TV_CHAT_MODE, 0)) }
            val left = position.intValue == 2 || position.intValue == 3 || position.intValue == 5
            com.github.andreyasadchy.xtra.ui.tv.SidePanel(
                title = getString(R.string.tv_chat_layout),
                scrollable = true,
                alignEnd = left && mode.intValue != 2,
            ) {
                val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                val update: (String, Int) -> Unit = { key, value ->
                    prefs.edit { putInt(key, value) }
                    if (_binding != null) {
                        applyTvChatLayout()
                    }
                }
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(getString(R.string.tv_chat_mode), modes, mode.intValue, androidx.compose.ui.Modifier.focusRequester(requester)) {
                    mode.intValue = it
                    update(C.TV_CHAT_MODE, it)
                }
                androidx.compose.runtime.key(mode.intValue) {
                    when (mode.intValue) {
                        1 -> {
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(getString(R.string.tv_chat_position), sides, if (left) 1 else 0) {
                                position.intValue = if (it == 1) 2 else 0
                                update(C.TV_CHAT_POSITION, position.intValue)
                            }
                        }
                        2 -> {
                            val top = position.intValue == 1 || position.intValue == 3 || position.intValue == 6
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(getString(R.string.tv_chat_position), bands, if (top) 1 else 0) {
                                position.intValue = if (it == 1) 1 else 0
                                update(C.TV_CHAT_POSITION, position.intValue)
                            }
                        }
                        else -> {
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(getString(R.string.tv_chat_position), corners, position.intValue.coerceIn(0, corners.lastIndex)) {
                                position.intValue = it
                                update(C.TV_CHAT_POSITION, it)
                            }
                        }
                    }
                }
                com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_chat_manage_users)) {
                    com.github.andreyasadchy.xtra.util.chat.ChatBlocklist.showPlayerBlockPanel(
                        context = context,
                        alignEnd = left && mode.intValue != 2,
                        recentChatters = chatFragment?.getRecentChatters().orEmpty(),
                    )
                }
                // slow chat: off or max normal messages per second (1, 2, 4, 6), priority messages stay shown
                val slowRateValues = listOf("0", "1", "2", "4", "6")
                val slowRateLabels = listOf(
                    getString(R.string.tv_chat_delay_off),
                    getString(R.string.tv_chat_slow_rate, 1),
                    getString(R.string.tv_chat_slow_rate, 2),
                    getString(R.string.tv_chat_slow_rate, 4),
                    getString(R.string.tv_chat_slow_rate, 6),
                )
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                    getString(R.string.tv_chat_slow),
                    slowRateLabels,
                    slowRateValues.indexOf(prefs.getString(C.CHAT_SLOW_RATE, "0")).coerceAtLeast(0),
                ) {
                    prefs.edit { putString(C.CHAT_SLOW_RATE, slowRateValues[it]) }
                }
                val activePin = chatFragment?.getPinnedMessage()
                if (activePin != null) {
                    val pinSender = activePin.senderName ?: activePin.senderLogin
                    val pinPreview = if (!pinSender.isNullOrBlank()) "$pinSender: ${activePin.messageText}" else activePin.messageText
                    com.github.andreyasadchy.xtra.ui.tv.ButtonRow("📌 ${getString(R.string.tv_chat_pinned_active)} — ${pinPreview.take(36)}") {
                        showPinnedMessageDetailPanel(activePin, alignEnd = left && mode.intValue != 2)
                    }
                }
                val pinnedMode = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableIntStateOf(prefs.getInt(C.TV_CHAT_PINNED_MODE, 1).coerceIn(0, 2))
                }
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                    getString(R.string.tv_chat_pinned),
                    listOf(
                        getString(R.string.tv_chat_pinned_hidden),
                        getString(R.string.tv_chat_pinned_temp),
                        getString(R.string.tv_chat_pinned_always),
                    ),
                    pinnedMode.intValue,
                ) {
                    pinnedMode.intValue = it
                    prefs.edit { putInt(C.TV_CHAT_PINNED_MODE, it) }
                    chatFragment?.refreshPinnedBanner()
                }
                val predPopup = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableIntStateOf(if (prefs.getBoolean(C.TV_CHAT_PREDICTIONS_POPUP, false)) 1 else 0)
                }
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                    getString(R.string.tv_chat_predictions_popup),
                    listOf(getString(R.string.tv_chat_delay_off), getString(R.string.tv_option_on)),
                    predPopup.intValue,
                ) {
                    predPopup.intValue = it
                    val enabled = it == 1
                    prefs.edit { putBoolean(C.TV_CHAT_PREDICTIONS_POPUP, enabled) }
                    if (!enabled) {
                        hideTvPredictionPopup()
                    }
                }
                // delay of the live chat: off, auto (the latency of the stream) or 1 to 60 s
                val expVodChat = isExperimentalVodActive()
                val savedDelayMode = prefs.getString(C.CHAT_DELAY_MODE, "1")?.toIntOrNull()?.coerceIn(0, 2) ?: 1
                val delayMode = androidx.compose.runtime.remember(expVodChat) {
                    androidx.compose.runtime.mutableIntStateOf(if (expVodChat) 1 else savedDelayMode)
                }
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                    getString(R.string.tv_chat_delay),
                    listOf(getString(R.string.tv_chat_delay_off), getString(R.string.tv_chat_delay_auto), getString(R.string.tv_chat_delay_manual)),
                    delayMode.intValue,
                ) {
                    if (isExperimentalVodActive()) {
                        delayMode.intValue = 1
                        Toast.makeText(requireContext(), R.string.tv_adblock_exp_chat_auto_locked, Toast.LENGTH_SHORT).show()
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
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = com.github.andreyasadchy.xtra.ui.tv.TvColors.secondaryText,
                                modifier = androidx.compose.ui.Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp),
                            )
                        }
                        2 -> {
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_delay_seconds), prefs.getInt(C.CHAT_DELAY_SECONDS, 5).coerceIn(1, 60), 1..60, valueText = { "$it s" }) {
                                prefs.edit { putInt(C.CHAT_DELAY_SECONDS, it) }
                            }
                        }
                    }
                }
                androidx.compose.runtime.key("size_${mode.intValue}") {
                    when (mode.intValue) {
                        1 -> {
                            textSizeRow(prefs)
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_width), prefs.getInt(C.TV_CHAT_WIDTH, 30), 5..60, valueText = { "$it %" }) {
                                update(C.TV_CHAT_WIDTH, it)
                            }
                        }
                        2 -> {
                            // the band apart (the picture a bit smaller) or over the stream (the picture full screen)
                            com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                                getString(R.string.tv_chat_band),
                                listOf(getString(R.string.tv_chat_band_separate), getString(R.string.tv_chat_band_overlay)),
                                if (prefs.getBoolean(C.TV_CHAT_BAND_OVERLAY, false)) 1 else 0,
                            ) {
                                prefs.edit { putBoolean(C.TV_CHAT_BAND_OVERLAY, it == 1) }
                                if (_binding != null) {
                                    applyTvChatLayout()
                                }
                            }
                            textSizeRow(prefs)
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                                getString(R.string.tv_chat_height),
                                prefs.getInt(C.TV_CHAT_LINES, 3),
                                1..12,
                                valueText = { if (it == 1) getString(R.string.tv_chat_one_line) else getString(R.string.tv_chat_lines, it) }
                            ) {
                                update(C.TV_CHAT_LINES, it)
                            }
                        }
                        else -> {
                            textSizeRow(prefs)
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_width), prefs.getInt(C.TV_CHAT_WIDTH, 30), 5..60, valueText = { "$it %" }) {
                                update(C.TV_CHAT_WIDTH, it)
                            }
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_height), prefs.getInt(C.TV_CHAT_HEIGHT, 50), 20..100, step = 5, valueText = { "$it %" }) {
                                update(C.TV_CHAT_HEIGHT, it)
                            }
                            com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_margin), prefs.getInt(C.TV_CHAT_MARGIN, 0), 0..64, step = 4, valueText = { "$it dp" }) {
                                update(C.TV_CHAT_MARGIN, it)
                            }
                        }
                    }
                }
                com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_opacity), prefs.getInt(C.TV_CHAT_OPACITY, 55), 0..100, step = 5, valueText = { "$it %" }) {
                    update(C.TV_CHAT_OPACITY, it)
                }
                val sharedBadges = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableIntStateOf(if (prefs.getBoolean(C.CHAT_SHARED_BADGES, true)) 1 else 0)
                }
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                    getString(R.string.tv_chat_shared_badges),
                    listOf(getString(R.string.tv_chat_delay_off), getString(R.string.tv_option_on)),
                    sharedBadges.intValue,
                ) {
                    sharedBadges.intValue = it
                    prefs.edit { putBoolean(C.CHAT_SHARED_BADGES, it == 1) }
                }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_reset)) {
                    prefs.edit {
                        remove(C.TV_CHAT_POSITION)
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
                androidx.compose.runtime.LaunchedEffect(Unit) { requester.requestFocus() }
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

    /** TV: the stream and its chat as dark as the dimming says, the icon of its button shows if it is on. */
    private val tvDimListener: () -> Unit = { applyTvDim() }

    private fun applyTvDim() {
        val binding = _binding ?: return
        if (!isTv) {
            return
        }
        com.github.andreyasadchy.xtra.ui.tv.TvDim.apply(binding.aspectRatioFrameLayout)
        com.github.andreyasadchy.xtra.ui.tv.TvDim.apply(binding.chatLayout)
        binding.playerControls.tvDim?.setImageResource(
            if (com.github.andreyasadchy.xtra.ui.tv.TvDim.active) R.drawable.tv_ic_dim_on else R.drawable.tv_ic_dim
        )
    }

    /** TV: the icon of the adblock & proxy button lights up when Experimental Adblock, Custom Proxy, or HTTP Proxy is enabled. */
    private fun updateTvProxyIcon() {
        val context = context ?: return
        val expOn = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(context)
        val customOn = !expOn && context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
        val streamProxyOn = !expOn && context.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
        _binding?.playerControls?.tvProxy?.apply {
            setImageResource(if (expOn || customOn || streamProxyOn) R.drawable.tv_ic_proxy_on else R.drawable.tv_ic_proxy)
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
            val expBefore = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(activity)
            val useBefore = activity.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
            val useStreamBefore = activity.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
            val modeBefore = activity.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0")
            var streamListChanged = false
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
                        val expNow = com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(activity)
                        val useNow = activity.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                        val useStreamNow = activity.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                        val modeNow = activity.prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0")
                        if (swapNow != swapBefore || expNow != expBefore || useNow || useNow != useBefore ||
                            useStreamNow || useStreamNow != useStreamBefore || streamListChanged || modeNow != modeBefore
                        ) {
                            activity.restartCurrentStream()
                        }
                    }
                }
            }
        }
    }

    /** TV: level of the dimming (OK held on its button), the stream gets darker while it changes. */
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
                    10..90,
                    step = 10,
                    valueText = { "$it %" },
                    modifier = androidx.compose.ui.Modifier.focusRequester(requester),
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.setLevel(context, it)
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
     * TV: panel of the small window(s): manages Window 1 & Window 2 position, size, sound modes, adding a 2nd window,
     * swapping, and closing.
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

        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(activity) { dismiss ->
            val selectedSlotState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableIntStateOf(if (twoOpen) 0 else activeIndices.first())
            }
            val currentSlot = selectedSlotState.intValue
            val cornerKey = if (currentSlot == 1) C.TV_PIP2_CORNER else C.TV_PIP_CORNER
            val cornerDefault = if (currentSlot == 1) "tl" else "tr"
            val sizeKey = if (currentSlot == 1) C.TV_PIP2_SIZE else C.TV_PIP_SIZE

            val slotCornerPos = prefs.getString(cornerKey, cornerDefault) ?: cornerDefault
            val pipOnLeft = slotCornerPos == "tl" || slotCornerPos == "ml" || slotCornerPos == "bl"

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

            com.github.andreyasadchy.xtra.ui.tv.SidePanel(
                title = getString(R.string.tv_pip_settings),
                alignEnd = pipOnLeft,
                scrollable = true,
            ) {
                val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                if (twoOpen) {
                    com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                        getString(R.string.tv_pip_settings),
                        windowOptions,
                        currentSlot.coerceIn(0, 1),
                        androidx.compose.ui.Modifier.focusRequester(requester),
                    ) {
                        selectedSlotState.intValue = it
                    }
                }
                androidx.compose.runtime.key(currentSlot) {
                    val cIdx = cornerValues.indexOf(prefs.getString(cornerKey, cornerDefault)).let { if (it >= 0) it else if (currentSlot == 1) 0 else 2 }
                    com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                        getString(R.string.tv_pip_position),
                        corners,
                        cIdx,
                        if (!twoOpen) androidx.compose.ui.Modifier.focusRequester(requester) else androidx.compose.ui.Modifier,
                    ) {
                        prefs.edit { putString(cornerKey, cornerValues[it]) }
                        activity.applyTvPipSettings()
                    }
                    com.github.andreyasadchy.xtra.ui.tv.SelectorRow(
                        getString(R.string.tv_pip_size),
                        sizes,
                        prefs.getInt(sizeKey, 1).coerceIn(0, 2),
                    ) {
                        prefs.edit { putInt(sizeKey, it) }
                        activity.applyTvPipSettings()
                    }
                }
                com.github.andreyasadchy.xtra.ui.tv.SelectorRow(getString(R.string.tv_pip_sound), sounds, sound.intValue) {
                    sound.intValue = it
                    prefs.edit { putString(C.TV_PIP_SOUND, soundValues[it]) }
                    activity.applyTvPipSettings()
                }
                if (!twoOpen && soundValues.getOrNull(sound.intValue) == "both") {
                    com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                        getString(R.string.tv_pip_balance),
                        prefs.getInt(C.TV_PIP_BALANCE, 0).coerceIn(-100, 100),
                        -100..100,
                        step = 10,
                        valueText = { value ->
                            val main = if (value > 0) 100 - value else 100
                            val small = if (value < 0) 100 + value else 100
                            getString(R.string.tv_pip_balance_value, main, small)
                        }
                    ) {
                        prefs.edit { putInt(C.TV_PIP_BALANCE, it) }
                        activity.applyTvPipSettings()
                    }
                } else if (twoOpen && soundValues.getOrNull(sound.intValue) == "mix") {
                    com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                        getString(R.string.tv_pip_vol_main),
                        prefs.getInt(C.TV_PIP_VOL_MAIN, 100).coerceIn(0, 100),
                        0..100,
                        step = 10,
                        valueText = { "$it %" },
                    ) {
                        prefs.edit { putInt(C.TV_PIP_VOL_MAIN, it) }
                        activity.applyTvPipSettings()
                    }
                    com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                        getString(R.string.tv_pip_vol_1),
                        prefs.getInt(C.TV_PIP_VOL_1, 80).coerceIn(0, 100),
                        0..100,
                        step = 10,
                        valueText = { "$it %" },
                    ) {
                        prefs.edit { putInt(C.TV_PIP_VOL_1, it) }
                        activity.applyTvPipSettings()
                    }
                    com.github.andreyasadchy.xtra.ui.tv.SliderRow(
                        getString(R.string.tv_pip_vol_2),
                        prefs.getInt(C.TV_PIP_VOL_2, 80).coerceIn(0, 100),
                        0..100,
                        step = 10,
                        valueText = { "$it %" },
                    ) {
                        prefs.edit { putInt(C.TV_PIP_VOL_2, it) }
                        activity.applyTvPipSettings()
                    }
                }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                if (!twoOpen) {
                    com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_pip_add_second)) {
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
                }
                com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_pip_swap)) {
                    dismiss()
                    activity.swapTvPip(currentSlot)
                }
                if (twoOpen) {
                    val slotTitle = getString(if (currentSlot == 1) R.string.tv_pip_window_2 else R.string.tv_pip_window_1)
                    com.github.andreyasadchy.xtra.ui.tv.ButtonRow("${getString(R.string.tv_pip_close)} ($slotTitle)") {
                        dismiss()
                        activity.closeTvPipSlot(currentSlot)
                    }
                    com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_pip_close_all)) {
                        dismiss()
                        activity.closeTvPip()
                    }
                } else {
                    com.github.andreyasadchy.xtra.ui.tv.ButtonRow(getString(R.string.tv_pip_close)) {
                        dismiss()
                        activity.closeTvPip()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { requester.requestFocus() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun textSizeRow(prefs: android.content.SharedPreferences) {
        com.github.andreyasadchy.xtra.ui.tv.SliderRow(getString(R.string.tv_chat_text_size), prefs.getInt(C.CHAT_SIZE_MODIFIER, 100), 60..200, step = 10, valueText = { "$it %" }) {
            prefs.edit { putInt(C.CHAT_SIZE_MODIFIER, it) }
            chatFragment?.applySizeModifier()
            // the band under the stream is as high as its lines
            if (_binding != null) {
                applyTvChatLayout()
            }
        }
    }

    /** TV: write a chat message, with voice input and visual emote picker in the field (no system popup). */
    private fun showChatComposer() {
        val emoteGroupsState = androidx.compose.runtime.mutableStateOf(chatFragment?.getTvEmoteGroups().orEmpty())
        com.github.andreyasadchy.xtra.ui.tv.TvDialogs.show(requireContext()) { dismiss ->
            androidx.compose.runtime.LaunchedEffect(Unit) {
                emoteGroupsState.value = chatFragment?.getTvEmoteGroups().orEmpty()
                chatFragment?.observeEmoteUpdates()?.collect {
                    emoteGroupsState.value = chatFragment?.getTvEmoteGroups().orEmpty()
                }
            }
            com.github.andreyasadchy.xtra.ui.tv.ChatComposer(
                title = getString(R.string.tv_write_message),
                hint = getString(R.string.tv_message_hint),
                sendLabel = getString(R.string.tv_send),
                emoteGroups = emoteGroupsState.value,
            ) { text ->
                if (text.isNotBlank()) {
                    chatFragment?.sendExternalMessage(text)
                }
                dismiss()
            }
        }
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
        val pos = prefs.getInt(C.TV_CHAT_POSITION, 0)
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
        prefs.edit { putInt(C.TV_CHAT_POSITION, next) }
        if (_binding != null) {
            applyTvChatLayout()
        }
    }

    private fun togglePlayerMute() {
        val ctx = context ?: return
        val prefs = ctx.prefs()
        val current = ((getCurrentVolume() ?: 1f) * 100).toInt()
        if (current > 0) {
            unmutedVolume = current
            prefs.edit { putInt(C.PLAYER_VOLUME, 0) }
            changeVolume(0f)
            Toast.makeText(ctx, R.string.tv_muted, Toast.LENGTH_SHORT).show()
        } else {
            val restored = unmutedVolume.takeIf { it > 0 } ?: 100
            prefs.edit { putInt(C.PLAYER_VOLUME, restored) }
            changeVolume(restored / 100f)
            Toast.makeText(ctx, getString(R.string.tv_unmuted, restored), Toast.LENGTH_SHORT).show()
        }
    }

    fun executeTvRemoteAction(action: String): Boolean {
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        if (hudPeek && action != Keys.ACTION_SWITCH_PREV && action != Keys.ACTION_SWITCH_NEXT) {
            endHudPeek()
            hideController()
        }
        when (action) {
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
            Keys.ACTION_CYCLE_CHAT_NEXT -> cycleTvChatPosition(1)
            Keys.ACTION_CYCLE_CHAT_PREV -> cycleTvChatPosition(-1)
            Keys.ACTION_TOGGLE_CHAT -> {
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    if (isChatOpen) hideChat() else showChat()
                }
            }
            Keys.ACTION_CHAT_PANEL -> {
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && playbackService?.type != BasePlaybackService.OFFLINE_VIDEO) {
                    showChatLayoutPanel()
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
        com.github.andreyasadchy.xtra.ui.tv.TvRewardsPanel.show(
            context = ctx,
            channelId = playbackService?.channelId ?: dvrStream?.channelId ?: current?.channelId,
            channelLogin = playbackService?.channelLogin ?: dvrStream?.channelLogin ?: current?.channelLogin,
            gameId = playbackService?.gameId ?: dvrStream?.gameId ?: current?.gameId,
            gameName = playbackService?.gameName ?: dvrStream?.gameName ?: current?.gameName,
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
        when (playbackService?.type) {
            BasePlaybackService.STREAM -> {
                val exo = playbackService as? ExoPlayerService
                if (isExperimentalVodActive()) {
                    val edge = exo?.getExperimentalLiveEdgeMs() ?: 0L
                    val pos = getCurrentPosition() ?: edge
                    if (edge > 0L && edge - pos >= 12_000L && archiveVid != null) {
                        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                            context = ctx,
                            broadcasterId = broadcasterId,
                            channelLogin = channelLogin,
                            broadcastId = null,
                            videoId = archiveVid,
                            offsetSeconds = (pos / 1000.0).coerceAtLeast(1.0),
                        )
                    } else {
                        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                            context = ctx,
                            broadcasterId = broadcasterId,
                            channelLogin = channelLogin,
                            broadcastId = playbackService?.streamId,
                            videoId = null,
                            offsetSeconds = getStreamUptime()?.let { (it / 1000.0).coerceAtLeast(1.0) },
                        )
                    }
                } else {
                    val behind = exo?.getLiveTimeShiftBehindMs() ?: 0L
                    val uptime = getStreamUptime()
                    if (behind >= 12_000L && uptime != null && archiveVid != null) {
                        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                            context = ctx,
                            broadcasterId = broadcasterId,
                            channelLogin = channelLogin,
                            broadcastId = null,
                            videoId = archiveVid,
                            offsetSeconds = ((uptime - behind) / 1000.0).coerceAtLeast(1.0),
                        )
                    } else {
                        com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                            context = ctx,
                            broadcasterId = broadcasterId,
                            channelLogin = channelLogin,
                            broadcastId = playbackService?.streamId,
                            videoId = null,
                            offsetSeconds = uptime?.let { ((it - behind) / 1000.0).coerceAtLeast(1.0) },
                        )
                    }
                }
            }
            BasePlaybackService.VIDEO -> {
                val vid = playbackService?.videoId?.takeIf { it.isNotBlank() } ?: archiveVid
                val posSec = getCurrentPosition()?.let { (it / 1000.0).coerceAtLeast(1.0) }
                com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                    context = ctx,
                    broadcasterId = broadcasterId,
                    channelLogin = channelLogin,
                    broadcastId = if (vid == null) dvrStream?.id else null,
                    videoId = vid,
                    offsetSeconds = posSec,
                )
            }
            else -> {
                if (!channelLogin.isNullOrBlank()) {
                    com.github.andreyasadchy.xtra.ui.tv.TvQrShare.createAndShowClipQr(
                        context = ctx,
                        broadcasterId = broadcasterId,
                        channelLogin = channelLogin,
                        broadcastId = playbackService?.streamId ?: dvrStream?.id,
                        videoId = null,
                        offsetSeconds = null,
                    )
                }
            }
        }
    }

    private var customKeyDownSeen = 0

    fun dispatchCustomRemoteKey(event: KeyEvent): Boolean {
        if (!isTv || !isMaximized || _binding == null || binding.chatLayout.hasFocus()) {
            return false
        }
        val ctx = requireContext()
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        val pipOpen = (activity as? MainActivity)?.isTvPipOpen() == true
        val rawShort = Keys.customShortActionForKey(ctx, event.keyCode)
        val rawLong = Keys.customLongActionForKey(ctx, event.keyCode)
        if (rawShort == null && rawLong == null) {
            return false
        }
        val shortAction = rawShort?.takeUnless { !pipOpen && Keys.isPipOnlyAction(it) }
        val longAction = rawLong?.takeUnless { !pipOpen && Keys.isPipOnlyAction(it) }
        if (shortAction == null && longAction == null) {
            return false
        }
        if (longAction == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && shortAction != null) {
                executeTvRemoteAction(shortAction)
            }
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    customKeyDownSeen = event.keyCode
                } else if (customKeyDownSeen == event.keyCode && heldLongKeyCode != event.keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                    heldLongKeyCode = event.keyCode
                    customKeyDownSeen = 0
                    executeTvRemoteAction(longAction)
                }
            }
            KeyEvent.ACTION_UP -> {
                val wasDownSeen = customKeyDownSeen == event.keyCode
                customKeyDownSeen = 0
                if (wasDownSeen && heldLongKeyCode != event.keyCode && !event.isCanceled && shortAction != null) {
                    executeTvRemoteAction(shortAction)
                }
                if (heldLongKeyCode == event.keyCode) {
                    heldLongKeyCode = 0
                }
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
            if (visible) {
                syncPlayPauseButtonVisibility()
                // the button of the small window, only while it is open
                playerControls.tvPipSettings?.visibility = if ((activity as? MainActivity)?.isTvPipOpen() == true) View.VISIBLE else View.GONE
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
        if (isTv || requireContext().prefs().getBoolean(C.PLAYER_FRAME_RATE_MATCHING, false)) {
            binding.root.post(tvHudTicker)
        }
        if (!isTv) {
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
            } + listOfNotNull(tvGoLive, streamList, statsForNerds, chatMessage, multiStream, tvDim, tvProxy, tvPipSettings, chatLayoutSettings, tvPoints, tvClip)
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
            if (playbackService?.type == BasePlaybackService.STREAM && prefs.getBoolean(C.TV_BUTTON_MULTI_STREAM, true)) {
                multiStream?.visibility = View.VISIBLE
                multiStream?.setOnClickListener {
                    hideController()
                    (activity as? MainActivity)?.let { activity ->
                        TvMultiStreamPicker.show(activity, Stream(
                            id = playbackService?.streamId,
                            channelId = playbackService?.channelId,
                            channelLogin = playbackService?.channelLogin,
                            channelName = playbackService?.channelName,
                            gameName = playbackService?.gameName,
                            title = playbackService?.title,
                        ))
                    }
                }
            }
            // dimming of the stream: OK turns it on / off, OK held opens its level
            tvDim?.let { button ->
                button.visibility = if (prefs.getBoolean(C.TV_BUTTON_DIM, false)) View.VISIBLE else View.GONE
                button.setOnClickListener {
                    showController(force = true)
                    com.github.andreyasadchy.xtra.ui.tv.TvDim.setActive(!com.github.andreyasadchy.xtra.ui.tv.TvDim.active)
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
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            if (playbackService?.type == BasePlaybackService.STREAM && isLoggedIn && !prefs.getBoolean(C.CHAT_DISABLE, false) && prefs.getBoolean(C.TV_BUTTON_CHAT_MESSAGE, true)) {
                chatMessage?.visibility = View.VISIBLE
                chatMessage?.setOnClickListener {
                    showController(force = true)
                    val lock = computeTvChatLockState()
                    if (lock != null) {
                        Toast.makeText(requireContext(), lock.message, Toast.LENGTH_SHORT).show()
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
                    separator.visibility = if (position.isVisible && duration.isVisible) View.VISIBLE else View.GONE
                }
                updateSeparator()
                duration.viewTreeObserver.addOnGlobalLayoutListener { updateSeparator() }
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

    /** TV: picture of the channel next to its name, box art of the category next to it. */
    private fun updateTvAvatars() {
        if (!isTv || _binding == null) {
            return
        }
        with(binding.playerControls) {
            val context = requireContext()
            tvChannelAvatar?.let { avatar ->
                val image = playbackService?.channelImage
                if (!image.isNullOrBlank() && channel.isVisible) {
                    avatar.visibility = View.VISIBLE
                    context.imageLoader.enqueue(
                        coil3.request.ImageRequest.Builder(context).apply {
                            data(image)
                            transformations(coil3.transform.CircleCropTransformation())
                            target(avatar)
                        }.build()
                    )
                } else {
                    avatar.visibility = View.GONE
                }
            }
            val gameId = playbackService?.gameId
            tvCategoryAvatar?.let { avatar ->
                if (!gameId.isNullOrBlank() && category.isVisible) {
                    avatar.visibility = View.VISIBLE
                    context.imageLoader.enqueue(
                        coil3.request.ImageRequest.Builder(context).apply {
                            data("https://static-cdn.jtvnw.net/ttv-boxart/$gameId-52x72.jpg")
                            transformations(coil3.transform.RoundedCornersTransformation(6f))
                            target(avatar)
                        }.build()
                    )
                } else {
                    avatar.visibility = View.GONE
                }
            }
            tvCategorySeparator?.visibility = if (channel.isVisible && category.isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun showTooltipNow() {
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
                updateAdblockBadge()
                updateTvOledProtection()
                syncExperimentalChatState()
                syncStreamSpeedButtonVisibility()
                if (binding.playerControls.root.isVisible) {
                    binding.playerControls.tvQualityInfo?.let { info ->
                        val label = getCurrentQualityLabel()
                        if (info.text?.toString() != label) {
                            info.text = label
                        }
                        info.visibility = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                }
            }
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
            binding.playerLayout.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
                marginEnd = resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
            })
            tvTopRightStack = this
        }
    }

    fun onTvPredictionEvent(prediction: com.github.andreyasadchy.xtra.model.chat.Prediction) {
        val ctx = context ?: return
        if (!isTv || _binding == null || isChatOpen || !isMaximized || !ctx.prefs().getBoolean(C.TV_CHAT_PREDICTIONS_POPUP, false)) {
            hideTvPredictionPopup()
            return
        }
        val predId = prediction.id ?: prediction.title ?: return
        when (prediction.status) {
            "ACTIVE" -> {
                if (tvPredictionStartShownId != predId) {
                    tvPredictionStartShownId = predId
                    tvPredictionEndShownId = null
                    tvPredictionCurrent = prediction
                    showTvPredictionPopup(autoHideMs = 12_000L)
                } else if (tvPredictionView?.isVisible == true) {
                    tvPredictionCurrent = prediction
                    renderTvPredictionPopup()
                }
            }
            "RESOLVED", "RESOLVE_PENDING", "CANCELED", "CANCEL_PENDING" -> {
                if (tvPredictionEndShownId != predId) {
                    tvPredictionEndShownId = predId
                    tvPredictionCurrent = prediction
                    tvPredictionSeconds = null
                    showTvPredictionPopup(autoHideMs = 12_000L)
                } else if (tvPredictionView?.isVisible == true) {
                    tvPredictionCurrent = prediction
                    renderTvPredictionPopup()
                }
            }
            "LOCKED" -> {
                if (tvPredictionView?.isVisible == true) {
                    tvPredictionCurrent = prediction
                    tvPredictionSeconds = null
                    renderTvPredictionPopup()
                }
            }
            else -> hideTvPredictionPopup()
        }
    }

    fun onTvPredictionSecondsLeft(seconds: Int?) {
        tvPredictionSeconds = seconds
        if (tvPredictionView?.isVisible == true) {
            renderTvPredictionPopup()
        }
    }

    fun onTvPredictionHidden() {
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
        card.postDelayed(tvPredictionHideRunnable, autoHideMs)
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
        val info = getAdblockInfo()
        val badge = tvAdblockView ?: if (info == null) return else AdblockBadge(requireContext()).apply {
            setBackgroundResource(R.drawable.tv_chat_overlay_background)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            val density = resources.displayMetrics.density
            // room under the text for the bar
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            getTopRightStack().addView(this, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
            tvAdblockView = this
        }
        badge.removeCallbacks(adblockBadgeRefresh)
        if (info != null) {
            val density = resources.displayMetrics.density
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

    /** The first picture of the video is on screen: the picture of the stream fades away. */
    protected fun hideLoadingPicture() {
        switchFrameListener = null
        val image = loadingPicture ?: return
        loadingPicture = null
        image.animate().alpha(0f).setDuration(250L).withEndAction { (image.parent as? ViewGroup)?.removeView(image) }.start()
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
                        if (isLiveOrDvrScreen()) {
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
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isTv && hudHiddenOrPeek) {
                    val shortAction = effectiveDpadAction(Keys.upAction(requireContext()), pipOpen)
                    val longAction = effectiveDpadAction(Keys.upLongAction(requireContext()), pipOpen)
                    if (longAction != Keys.ACTION_DEFAULT && shortAction != longAction) {
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    dpadKeyDownSeen = keyCode
                                } else if (dpadKeyDownSeen == keyCode && heldLongKeyCode != keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                                    heldLongKeyCode = keyCode
                                    dpadKeyDownSeen = 0
                                    executeTvRemoteAction(longAction)
                                }
                            }
                            KeyEvent.ACTION_UP -> {
                                val wasDownSeen = dpadKeyDownSeen == keyCode
                                dpadKeyDownSeen = 0
                                if (wasDownSeen && heldLongKeyCode != keyCode && !event.isCanceled) {
                                    if (shortAction != Keys.ACTION_DEFAULT) {
                                        executeTvRemoteAction(shortAction)
                                    } else {
                                        openHudFromDpad()
                                    }
                                }
                                if (heldLongKeyCode == keyCode) {
                                    heldLongKeyCode = 0
                                }
                            }
                        }
                        return true
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
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    dpadKeyDownSeen = keyCode
                                } else if (dpadKeyDownSeen == keyCode && heldLongKeyCode != keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                                    heldLongKeyCode = keyCode
                                    dpadKeyDownSeen = 0
                                    if (longAction != Keys.ACTION_DEFAULT) {
                                        executeTvRemoteAction(longAction)
                                    } else {
                                        openHudFromDpad()
                                    }
                                }
                            }
                            KeyEvent.ACTION_UP -> {
                                val wasDownSeen = dpadKeyDownSeen == keyCode
                                dpadKeyDownSeen = 0
                                if (wasDownSeen && heldLongKeyCode != keyCode && !event.isCanceled) {
                                    if (shortAction != Keys.ACTION_DEFAULT) {
                                        executeTvRemoteAction(shortAction)
                                    } else {
                                        openHudFromDpad()
                                    }
                                }
                                if (heldLongKeyCode == keyCode) {
                                    heldLongKeyCode = 0
                                }
                            }
                        }
                        return true
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
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    dpadKeyDownSeen = keyCode
                                } else if (dpadKeyDownSeen == keyCode && heldLongKeyCode != keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                                    heldLongKeyCode = keyCode
                                    dpadKeyDownSeen = 0
                                    if (longAction != Keys.ACTION_DEFAULT) {
                                        executeTvRemoteAction(longAction)
                                    } else {
                                        openHudFromDpad()
                                    }
                                }
                            }
                            KeyEvent.ACTION_UP -> {
                                val wasDownSeen = dpadKeyDownSeen == keyCode
                                dpadKeyDownSeen = 0
                                if (wasDownSeen && heldLongKeyCode != keyCode && !event.isCanceled) {
                                    if (shortAction != Keys.ACTION_DEFAULT) {
                                        executeTvRemoteAction(shortAction)
                                    } else {
                                        openHudFromDpad()
                                    }
                                }
                                if (heldLongKeyCode == keyCode) {
                                    heldLongKeyCode = 0
                                }
                            }
                        }
                        return true
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
                                        binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
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
                    binding.playerControls.root.postDelayed(controllerHideAction, controllerHideDelay)
                }
            }
        }
    }

    private fun hideController(force: Boolean = false) {
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

    private fun applyTvMinimizedFrame(minimized: Boolean) {
        val b = _binding ?: return
        if (!isTv) return
        if (minimized) {
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
            val baseColor = if (com.github.andreyasadchy.xtra.ui.tv.TvAccent.current != null) {
                com.github.andreyasadchy.xtra.ui.tv.TvAccent.onDark
            } else {
                Color.WHITE
            }
            val borderColor = androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, 0xD9)
            b.dragView.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = cornerPx
                setStroke(strokePx, borderColor)
            }
        } else {
            b.slidingLayout.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            b.slidingLayout.elevation = 0f
            b.playerLayout.clipToOutline = false
            b.playerLayout.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            b.dragView.background = null
        }
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

    private fun hideTvStreamEndBanner() {
        tvStreamEndNextTarget = null
        tvStreamEndBannerView?.visibility = View.GONE
    }

    private fun cancelTvStreamEndCountdown(): Boolean {
        if (tvStreamEndNextTarget == null) return false
        streamEndJob?.cancel()
        streamEndJob = null
        hideTvStreamEndBanner()
        return true
    }

    private fun confirmTvStreamEndSwitchNow() {
        val target = tvStreamEndNextTarget ?: return
        streamEndJob?.cancel()
        streamEndJob = null
        hideTvStreamEndBanner()
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
     * TV: the stream stopped (Twitch ended it). After waiting for a potential raid, performs the configured
     * end-of-stream behavior ("home": close player, "next": 10s countdown to switch to another live followed channel
     * or same-category stream, "stay": remain on the player).
     */
    protected fun onTvStreamEnded() {
        if (view == null || !isAdded || !isTv || streamEndJob?.isActive == true) {
            return
        }
        val activity = activity as? MainActivity ?: return
        val login = activity.currentPlayingStream()?.channelLogin
        // just back from a multi-stream: the next stream of it instead of closing
        activity.takeMultiStreamFallback(login)?.let { next ->
            activity.startStream(next, peekHud = true)
            return
        }
        streamEndJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(6000L)
            // a raid announced in the chat: its stream comes, the player waits for it (30 s at most)
            var waited = 0
            while (chatFragment?.isRaidPending() == true && waited++ < 30) {
                kotlinx.coroutines.delay(1000L)
            }
            if (view == null || activity.currentPlayingStream()?.channelLogin != login || isPlayingNow()) {
                return@launch
            }
            restoreTvOledProtection()
            when (requireContext().prefs().getString(C.TV_STREAM_END_ACTION, "home") ?: "home") {
                "stay" -> return@launch
                "next" -> {
                    val next = pickNextLiveFollowedStream(activity, login)
                    if (next == null) {
                        close()
                        activity.closePlayer()
                        return@launch
                    }
                    tvStreamEndNextTarget = next
                    val nextName = next.channelName?.takeIf { it.isNotBlank() } ?: next.channelLogin ?: ""
                    for (sec in 10 downTo 1) {
                        if (view == null || activity.currentPlayingStream()?.channelLogin != login || isPlayingNow()) {
                            hideTvStreamEndBanner()
                            return@launch
                        }
                        showTvStreamEndBanner(nextName, sec)
                        kotlinx.coroutines.delay(1000L)
                    }
                    val target = tvStreamEndNextTarget
                    hideTvStreamEndBanner()
                    if (target != null && view != null && activity.currentPlayingStream()?.channelLogin == login && !isPlayingNow()) {
                        activity.startStream(target, peekHud = true)
                    }
                }
                else -> {
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
        return when (requireContext().prefs().getString(C.TV_KEEP_SOUND_ON_LEAVE, "always")) {
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
            if (isTv) {
                restoreTvOledProtection()
                cancelTvStreamEndCountdown()
                // the mini player is not reached with the D-pad, "Now playing" in the side menu brings it back
                dragView.isFocusable = false
                hideTvPredictionPopup()
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
                applyTvMinimizedFrame(true)
            } else {
                dragView.setBackgroundResource(R.drawable.player_minimized_focus)
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
                    val newX = slidingLayout.width - (slidingLayout.width * minimizedScaleX) - tvMargin
                    val newY = slidingLayout.height - (slidingLayout.height * minimizedScaleY) - tvMargin
                    targetTx = -scaledXDiff + newX
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
                            }
                        }
                    )
                    start()
                }
            }
            if (isPortrait) {
                chatLayout.visibility = View.GONE
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
            Toast.makeText(
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
            Toast.makeText(requireContext(), R.string.timer_canceled, Toast.LENGTH_LONG).show()
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

    override fun onDestroyView() {
        chatUnlockTimerJob?.cancel()
        chatUnlockTimerJob = null
        isTvChatLocked = null
        chatLockObservedFragment = null
        com.github.andreyasadchy.xtra.ui.tv.TvDim.removeListener(tvDimListener)
        _binding?.root?.removeCallbacks(tvHudTicker)
        _binding?.root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(hudFocusListener)
        if (isTv) {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        expArchiveLookupJob?.cancel()
        expArchiveLookupJob = null
        resetFrameRateMatching()
        seekPreview?.release()
        seekPreview = null
        tvPredictionView?.removeCallbacks(tvPredictionHideRunnable)
        tvPredictionView = null
        tvStreamEndNextTarget = null
        tvStreamEndBannerView = null
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

    fun getCoStreamers(): List<com.github.andreyasadchy.xtra.model.ui.Stream> =
        chatFragment?.getCoStreamers().orEmpty()

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
