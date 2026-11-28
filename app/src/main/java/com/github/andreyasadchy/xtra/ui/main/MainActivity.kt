package com.github.andreyasadchy.xtra.ui.main

import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.text.format.Formatter
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.ui.setupWithNavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityMainBinding
import com.github.andreyasadchy.xtra.databinding.DialogUpdateDownloadBinding
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.download.StreamDownloadService
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadService
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentDirections
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainViewModel.Companion.MainViewModelFactory
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.MediaPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.player.TvStreamSwitcher
import com.github.andreyasadchy.xtra.ui.saved.SavedMediaFragment
import com.github.andreyasadchy.xtra.ui.saved.SavedPagerFragment
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.team.TeamFragmentDirections
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.UpdateInstallHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.showInstallBlockedDialog
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.chromium.net.CronetProvider
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {

    companion object {
        /** TV: time a screen may stay over the app before it counts as left (the streams stop). */
        private const val LEAVE_DELAY_MS = 3000L
        const val KEY_VIDEO = "video"

        const val INTENT_INSTALL_UPDATE = "com.github.andreyasadchy.xtra.INSTALL_UPDATE"
        const val INTENT_LIVE_NOTIFICATION = "com.github.andreyasadchy.xtra.LIVE_NOTIFICATION"
        const val INTENT_OPEN_DOWNLOADS_TAB = "com.github.andreyasadchy.xtra.OPEN_DOWNLOADS_TAB"
        const val INTENT_OPEN_DOWNLOADED_VIDEO = "com.github.andreyasadchy.xtra.OPEN_DOWNLOADED_VIDEO"
        const val INTENT_OPEN_PLAYER = "com.github.andreyasadchy.xtra.OPEN_PLAYER"
        const val INTENT_START_AUDIO_ONLY = "com.github.andreyasadchy.xtra.START_AUDIO_ONLY"
        const val INTENT_PLAY_PAUSE_PLAYER = "com.github.andreyasadchy.xtra.PLAY_PAUSE_PLAYER"
        const val INTENT_CLOSE_PLAYER = "com.github.andreyasadchy.xtra.CLOSE_PLAYER"
        private const val MULTI_STREAM_TAG = "multiStream"
        /** The list of the other streams is loaded again every minute while a stream plays. */
        private const val QUEUE_REFRESH_MS = 60_000L
        /** Opening the other streams or switching loads it again when it is older than this. */
        private const val QUEUE_FRESH_MS = 30_000L
        /** Settle delay before auto-switching sections when stepping through the TV side menu without OK. */
        private const val TV_MENU_HOVER_SETTLE_MS = 130L
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory }
    private lateinit var navController: NavController
    var playerFragment: Fragment? = null
        private set
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pipActionReceiver: BroadcastReceiver? = null
    private lateinit var prefs: SharedPreferences
    var settingsResultLauncher: ActivityResultLauncher<Intent>? = null
    var loginResultLauncher: ActivityResultLauncher<Intent>? = null
    var logoutResultLauncher: ActivityResultLauncher<Intent>? = null
    private var updateDownloadDialogBinding: DialogUpdateDownloadBinding? = null
    private var updateDownloadDialog: AlertDialog? = null

    //Lifecycle methods

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.github.andreyasadchy.xtra.ui.view.TvUi.wrapTvContext(newBase))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        com.github.andreyasadchy.xtra.ui.view.TvUi.enforceTvUiMode(overrideConfiguration)
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = prefs()
        showLastCrash()
        migrateSettings()
        if (com.github.andreyasadchy.xtra.ui.view.TvUi.isTv(this)) {
            forceDisplaySettings()
        }
        // favorite channels, read before the followed streams are loaded (TV and mobile)
        com.github.andreyasadchy.xtra.ui.tv.TvFavorites.load(this)
        if (resources.getBoolean(R.bool.isTv)) {
            if (savedInstanceState == null) {
                com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.advanceRotationIfDue(this, onLaunch = true)
            }
            // the color of accent, read again (the app restarts this screen when the ambiance color changes)
            com.github.andreyasadchy.xtra.ui.tv.TvAccent.init(this)
            // pre-warm the TV settings XML cache in the background so opening Settings is instant
            com.github.andreyasadchy.xtra.ui.settings.TvSettingsFragment.prewarmAsync(this)
            prefs.edit {
                // on TV leaving the app stops the stream: no picture-in-picture, no background audio (the phone settings are hidden)
                putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                putBoolean(C.PLAYER_BACKGROUND_AUDIO, false)
                putBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, false)
                // back of the player: the old "back minimizes the player" switch becomes the mini player choice
                if (!prefs.contains(C.TV_BACK_ACTION)) {
                    putString(C.TV_BACK_ACTION, if (prefs.getBoolean(C.TV_BACK_MINIMIZES_PLAYER, false)) "mini" else "stop")
                }
                // sound when the app is left: defaults to "never" unless legacy switches were explicitly configured
                if (!prefs.contains(C.TV_KEEP_SOUND_ON_LEAVE)) {
                    val hasLegacy = prefs.contains(C.TV_MINI_PLAYER_KEEP_ON_HOME) ||
                        prefs.contains(C.TV_AUDIO_ONLY_KEEP_ON_HOME) ||
                        prefs.contains(C.TV_FULLSCREEN_KEEP_ON_HOME)
                    if (hasLegacy) {
                        val mini = prefs.getBoolean(C.TV_MINI_PLAYER_KEEP_ON_HOME, false)
                        val audio = prefs.getBoolean(C.TV_AUDIO_ONLY_KEEP_ON_HOME, false)
                        val fullscreen = prefs.getBoolean(C.TV_FULLSCREEN_KEEP_ON_HOME, false)
                        putString(C.TV_KEEP_SOUND_ON_LEAVE, when {
                            !mini && !audio && !fullscreen -> "never"
                            fullscreen -> "always"
                            else -> "minimized"
                        })
                    } else {
                        putString(C.TV_KEEP_SOUND_ON_LEAVE, "never")
                    }
                }
                // no clock face for the sleep timer on TV (made for a touch screen)
                putBoolean(C.SLEEP_TIMER_USE_TIME_PICKER, false)
                // the live notifications of Android (checked in the background) are replaced by the live banner: turned off,
                // their background check is cancelled with them
                if (prefs.getBoolean("live_notifications_enabled", false)) {
                    putBoolean("live_notifications_enabled", false)
                    androidx.work.WorkManager.getInstance(this@MainActivity).cancelUniqueWork("live_notifications")
                }
                // no theme setting on TV: a theme chosen before (light, rounded corners...) goes back to the one of Xtra TV
                listOf(
                    C.THEME, C.UI_THEME_FOLLOW_SYSTEM, C.UI_THEME_DARK_ON, C.UI_THEME_DARK_OFF, C.UI_THEME_ROUNDED_CORNERS,
                    C.UI_THEME_REDUCED_PADDING, C.UI_THEME_COMPACT_TEXT, C.UI_THEME_MATERIAL3,
                ).forEach { if (prefs.contains(it)) remove(it) }
            }
        }
        if (resources.getBoolean(R.bool.isTv) && !prefs.getBoolean(C.TV_DEFAULTS_QUALITY, false)) {
            // on TV the streams start in Auto (the saved quality of the app is 720p60 at first)
            prefs.edit {
                putBoolean(C.TV_DEFAULTS_QUALITY, true)
                if (!prefs.contains(C.PLAYER_QUALITY)) {
                    putString(C.PLAYER_QUALITY, "auto")
                }
            }
        }
        if (resources.getBoolean(R.bool.isTv) && !prefs.getBoolean(C.TV_DEFAULTS_PROXIES_AND_BUTTONS, false)) {
            // custom proxies off at first (VAFT handles ads out of the box), proxy button shown and dimming button hidden
            prefs.edit {
                putBoolean(C.TV_DEFAULTS_PROXIES_AND_BUTTONS, true)
                putBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                putBoolean(C.TV_BUTTON_PROXY, true)
                putBoolean(C.TV_BUTTON_DIM, false)
            }
        }
        if (resources.getBoolean(R.bool.isTv) && !prefs.getBoolean(C.TV_DEFAULTS_COMPACT_RAIL, false)) {
            // compact side rail enabled once by default (can still be turned off afterwards in Xtra TV settings)
            prefs.edit {
                putBoolean(C.TV_DEFAULTS_COMPACT_RAIL, true)
                putBoolean(C.TV_COMPACT_RAIL, true)
            }
        }
        if (resources.getBoolean(R.bool.isTv) && !prefs.getBoolean(C.TV_DEFAULTS_LIVE_PAUSE, false)) {
            // Pause button enabled once by default on TV live streams for time-shift pause/resume (can still be turned off in Player buttons)
            prefs.edit {
                putBoolean(C.TV_DEFAULTS_LIVE_PAUSE, true)
                putBoolean(C.PLAYER_PAUSE, true)
            }
        }
        if (resources.getBoolean(R.bool.isTv) && !prefs.getBoolean(C.TV_DEFAULTS_SOUND_LEAVE_NEVER, false)) {
            // Background audio when leaving the app is turned off once on update to 1.4.6.5 (can be re-enabled anytime in Settings)
            prefs.edit {
                putBoolean(C.TV_DEFAULTS_SOUND_LEAVE_NEVER, true)
                putString(C.TV_KEEP_SOUND_ON_LEAVE, "never")
            }
        }
        if (tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0) <= 0L) {
            tokenPrefs().edit {
                putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collect {
                    if (prefs.getBoolean(C.USE_WEBVIEW_INTEGRITY, true)) {
                        getNewIntegrityToken(null, supportFragmentManager)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    kotlinx.coroutines.delay(30_000L)
                    if (isTv && !isTvPlayerOverContent() && hasWindowFocus()) {
                        if (com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.advanceRotationIfDue(this@MainActivity, onLaunch = false)) {
                            tvBaseBackgroundColor?.let { base ->
                                com.github.andreyasadchy.xtra.ui.tv.TvAmbient.crossfadeToCurrentBackground(binding.root, base)
                            }
                        }
                    }
                    if (!isTvPlayerOverContent() && hasWindowFocus() &&
                        android.os.SystemClock.elapsedRealtime() - lastUserInteractionAt >= 2 * 60_000L
                    ) {
                        refreshVisibleScreensIfStale(3 * 60_000L)
                    }
                }
            }
        }
        applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (isTv) {
            binding.playerContainer.isFocusable = false
            binding.playerContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener listener@{ oldFocus, newFocus ->
            // the page shown from the side menu without OK takes the focus by itself (its list loaded): the focus stays in
            // the menu, only right (or OK) goes into the page
            if (isTv && oldFocus != null && newFocus != null && oldFocus.isInside(binding.navBarContainer) && newFocus.isInside(binding.navHostFragment) &&
                android.os.SystemClock.elapsedRealtime() - tvMenuAutoNavAt < 2000L &&
                !(lastTvKeyCode in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && android.os.SystemClock.elapsedRealtime() - lastTvKeyAt < 500L)
            ) {
                binding.root.post { tvMenuState.focusCurrentOrSelected() }
                return@listener
            }
            if (isTv && tvMultiStreamShown && newFocus != null && (newFocus.isInside(binding.navHostFragment) || newFocus.isInside(binding.navBarContainer))) {
                // something behind the multi-stream took the focus (a list that loaded, the home): back on the streams
                binding.root.post { keepFocusInMultiStream() }
                return@listener
            }
            if (isTv && !tvMultiStreamShown && !restoringPlayerFocus && hasWindowFocus() && !hasOpenDialogFragment() &&
                binding.navHostFragment.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS &&
                newFocus != null && (playerFragment as? PlayerFragment)?.let {
                    it.isMaximizedPlayer() && !it.isRemoving && !it.isDetached && (
                        newFocus === binding.root || newFocus === binding.playerContainer ||
                            newFocus.isInside(binding.navHostFragment) || newFocus.isInside(binding.navBarContainer) ||
                            !it.hasValidTvFocus()
                        )
                } == true
            ) {
                // something behind or outside the maximized player took focus: bring it back to the player
                binding.root.removeCallbacks(keepFocusInPlayerAction)
                binding.root.post(keepFocusInPlayerAction)
                return@listener
            }
            if (newFocus != null && (newFocus.isInside(binding.navHostFragment) || newFocus.isInside(binding.navBarContainer))) {
                lastContentFocus = newFocus
            }
            if (isTv && newFocus != null && newFocus.isInside(binding.navHostFragment)) {
                onTvContentFocus(newFocus)
            }
            if (isTv && newFocus != null) {
                // leanback menu: open while it has the focus, folded away as soon as the content gets it
                if (newFocus.isInside(binding.navBarContainer)) {
                    showTvMenu(false)
                } else if (newFocus.isInside(binding.navHostFragment)) {
                    hideTvMenu()
                }
            }
        }
        if (isTv) {
            // soft glow of the ambiance color behind the screens (Xtra TV settings)
            (binding.root.background as? android.graphics.drawable.ColorDrawable)?.color?.let { base ->
                tvBaseBackgroundColor = base
                binding.root.background = com.github.andreyasadchy.xtra.ui.tv.TvAmbient.background(this, base)
            }
            com.github.andreyasadchy.xtra.ui.tv.TvAmbient.setDynamicListener(tvAmbientListener)
            applyTvNavHostPadding()
            supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    if (f.parentFragment is NavHostFragment && f !is androidx.fragment.app.DialogFragment) {
                        applyTvFragmentPadding(f, v)
                        if (f.isHidden) {
                            (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        }
                    }
                    TvUi.adapt(v, hideToolbarActions = true)
                    if (f is androidx.fragment.app.DialogFragment) {
                        v.post { TvUi.adaptDialog(v) }
                    }
                }

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    if (f is androidx.fragment.app.DialogFragment) {
                        // sheets become side panels with the focus inside
                        TvUi.adaptSheet(f)
                    }
                }

                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    if (f is androidx.fragment.app.DialogFragment) {
                        return
                    }
                    binding.root.post {
                        if (f.parentFragment is NavHostFragment) {
                            f.view?.let { v ->
                                com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(v)
                                tintTvCards(v, onlyVisible = true)
                            }
                        }
                        // a list of streams shown again after a while: the streamers that stopped go away (deferred past fade & active menu input)
                        f.view?.let { v ->
                            val refreshAction = object : Runnable {
                                override fun run() {
                                    if (!f.isResumed || !v.isShown) return
                                    if (isTvMenuOpen() && idleDurationMs() < 380L) {
                                        v.postDelayed(this, 320L)
                                        return
                                    }
                                    refreshStaleStreamLists(v, 20_000L)
                                }
                            }
                            v.postDelayed(refreshAction, 160L)
                        }
                        if (tvInitialFocusDone && !isTvMenuShown && f.parentFragment is NavHostFragment && !isTvPlayerOverContent()) {
                            when (f) {
                                is com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment -> {
                                    f.focusLastCard()
                                    f.view?.requestFocus()
                                }
                                is com.github.andreyasadchy.xtra.ui.tv.TvStatsFragment -> {
                                    f.requestContentFocus()
                                    f.view?.requestFocus()
                                }
                                is com.github.andreyasadchy.xtra.ui.settings.TvSettingsFragment -> {
                                    f.requestContentFocus()
                                    f.view?.requestFocus()
                                }
                                else -> restoreTvScreenFocus()
                            }
                        }
                        if (!tvInitialFocusDone && f.view?.isShown == true && f.parentFragment is NavHostFragment && !isTvPlayerOverContent()) {
                            tvInitialFocusDone = true
                            if (!prefs.getBoolean(C.TV_HIDE_MENU_ON_LAUNCH, false)) {
                                // browsing starts in the side menu
                                showTvMenu(true)
                            } else {
                                // the side menu stays closed: the focus on the start page (its list may still be loading)
                                focusContentAfterMenu(false, maxTries = 60)
                            }
                            scheduleTvRootPrewarm()
                        } else {
                            ensureFocus()
                        }
                    }
                }
            }, true)
        } else {
            com.github.andreyasadchy.xtra.ui.mobile.MobileAccent.applyScreenAmbient(binding.root)
            (binding.navBar as? com.google.android.material.navigation.NavigationBarView)?.let {
                com.github.andreyasadchy.xtra.ui.mobile.MobileAccent.applyBottomNavTint(it)
            }
            supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    fun applyToTree(node: View) {
                        when (node) {
                            is com.google.android.material.tabs.TabLayout ->
                                com.github.andreyasadchy.xtra.ui.mobile.MobileAccent.applyTabLayoutTint(node)
                            is androidx.swiperefreshlayout.widget.SwipeRefreshLayout -> {
                                com.github.andreyasadchy.xtra.ui.mobile.MobileAccent.color(node.context)?.let { c ->
                                    node.setColorSchemeColors(c)
                                }
                            }
                            is ViewGroup -> {
                                for (i in 0 until node.childCount) applyToTree(node.getChildAt(i))
                            }
                        }
                    }
                    applyToTree(v)
                }
            }, true)
        }
        setNavBarColor(resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        val ignoreCutouts = prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = if (ignoreCutouts) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            } else {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            }
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            binding.navBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            windowInsets
        }
        settingsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val prevAdblock = adblockPrefsBeforeSettings
            adblockPrefsBeforeSettings = null
            if (result.resultCode == RESULT_OK) {
                recreatingActivity = true
                recreate()
            } else if (isTv && prevAdblock != null && prevAdblock != snapshotAdblockPrefs() && currentPlayingStream() != null) {
                val wasMaximized = isPlayerMaximized()
                restartCurrentStream()
                if (!wasMaximized) {
                    (playerFragment as? PlayerFragment)?.minimize()
                }
            }
        }
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val prevKey = pendingAddAccountPreviousKey
            pendingAddAccountPreviousKey = null
            com.github.andreyasadchy.xtra.ui.tv.TvAccounts.restoreAfterCanceledAdd(this, prevKey)
            com.github.andreyasadchy.xtra.ui.tv.TvAccounts.syncCurrentFromPrefs(this)
            if (result.resultCode == RESULT_OK) {
                restartActivity()
            } else if (isTv) {
                updateTvMenuAccount()
            } else {
                com.github.andreyasadchy.xtra.ui.mobile.MobileUi.ensureAccountAvatarLoaded(this)
            }
        }
        logoutResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            restartActivity()
        }

        var initialized = savedInstanceState != null
        initNavigation()
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!initialized) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val isNetworkAvailable = networkCapabilities != null
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!isNetworkAvailable) {
                initialized = true
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, R.string.no_connection, Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.checkNetworkStatus.collectLatest {
                    if (it) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val isNetworkAvailable = networkCapabilities != null
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (viewModel.isNetworkAvailable.value != isNetworkAvailable) {
                            viewModel.isNetworkAvailable.value = isNetworkAvailable
                            if (initialized) {
                                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this@MainActivity, if (isNetworkAvailable) R.string.connection_restored else R.string.no_connection, Toast.LENGTH_SHORT).show()
                            } else {
                                initialized = true
                            }
                            if (isNetworkAvailable) {
                                if (!TwitchApiHelper.checkedValidation && prefs.getBoolean(C.VALIDATE_TOKENS, true)) {
                                    viewModel.validate(
                                        prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        TwitchApiHelper.getGQLHeaders(this@MainActivity, true),
                                        prefs.getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko"),
                                        tokenPrefs().getString(C.GQL_TOKEN_WEB, null)?.takeIf { it.isNotBlank() }?.let { TwitchApiHelper.addTokenPrefixGQL(it) },
                                        TwitchApiHelper.getHelixHeaders(this@MainActivity),
                                        this@MainActivity.tokenPrefs().getString(C.USER_ID, null),
                                        this@MainActivity.tokenPrefs().getString(C.USERNAME, null),
                                        this@MainActivity
                                    )
                                }
                                if (!TwitchApiHelper.checkedUpdates &&
                                    prefs.getBoolean(C.UPDATE_CHECK_ENABLED, true) &&
                                    (prefs.getString(C.UPDATE_CHECK_FREQUENCY, "1")?.toIntOrNull() ?: 1) * 86400000 + tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0) < System.currentTimeMillis()
                                ) {
                                    viewModel.checkUpdates(
                                        prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        prefs.getString(C.UPDATE_URL, null) ?: "https://api.github.com/repos/razeNFR/XtraPlus/releases/latest",
                                        tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0)
                                    )
                                }
                            }
                        }
                        viewModel.checkNetworkStatus.value = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.checkCellularStatus.collectLatest {
                    if (it) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val cellular = networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        if (!cellular) {
                            if (prefs.getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                val downloads = viewModel.getWaitingDownloads()
                                if (downloads.isNotEmpty()) {
                                    downloads.forEach {
                                        val intent = if (it.live) {
                                            Intent(this@MainActivity, StreamDownloadService::class.java).apply {
                                                action = StreamDownloadService.INTENT_START
                                                putExtra(StreamDownloadService.KEY_VIDEO_ID, it.id)
                                            }
                                        } else {
                                            Intent(this@MainActivity, VideoDownloadService::class.java).apply {
                                                action = VideoDownloadService.INTENT_START
                                                putExtra(VideoDownloadService.KEY_VIDEO_ID, it.id)
                                            }
                                        }
                                        startService(intent)
                                    }
                                    val currentFragment = currentVisibleNavFragment()
                                    if (currentFragment is SavedPagerFragment || currentFragment is SavedMediaFragment) {
                                        val fragment = currentFragment.childFragmentManager.fragments.find { it is DownloadsFragment }
                                        if (downloads.any { it.live }) {
                                            (fragment as? DownloadsFragment)?.bindStreamDownloadService(true)
                                        }
                                        if (downloads.any { !it.live }) {
                                            (fragment as? DownloadsFragment)?.bindVideoDownloadService(true)
                                        }
                                    }
                                }
                            }
                        }
                        viewModel.checkCellularStatus.value = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.startDownloadService.collect {
                    val videoId = it.first
                    val live = it.second
                    if (live) {
                        val intent = Intent(this@MainActivity, StreamDownloadService::class.java).apply {
                            action = StreamDownloadService.INTENT_START
                            putExtra(StreamDownloadService.KEY_VIDEO_ID, videoId)
                        }
                        startService(intent)
                    } else {
                        val intent = Intent(this@MainActivity, VideoDownloadService::class.java).apply {
                            action = VideoDownloadService.INTENT_START
                            putExtra(VideoDownloadService.KEY_VIDEO_ID, videoId)
                        }
                        startService(intent)
                    }
                    val currentFragment = currentVisibleNavFragment()
                    if (currentFragment is SavedPagerFragment || currentFragment is SavedMediaFragment) {
                        val fragment = currentFragment.childFragmentManager.fragments.find { it is DownloadsFragment }
                        if (live) {
                            (fragment as? DownloadsFragment)?.bindStreamDownloadService(true)
                        } else {
                            (fragment as? DownloadsFragment)?.bindVideoDownloadService(true)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateUrl.collectLatest {
                    if (it != null) {
                        UpdateInstallHelper.lastAttemptedUpdateUrl = it
                        val startUpdate: () -> Unit = {
                            UpdateInstallHelper.lastAttemptedUpdateUrl = it
                            if (prefs.getBoolean(C.UPDATE_USE_BROWSER, false)) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, it.toUri()).apply {
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                    }
                                    startActivity(intent)
                                    tokenPrefs().edit {
                                        putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                    }
                                } catch (e: ActivityNotFoundException) {
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this@MainActivity, R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                // the settings "Install unknown apps" at most once an hour, then the ways of installing (never a loop)
                                UpdateInstallHelper.startUpdate(this@MainActivity, it) { url -> startUpdateDownload(url) }
                            }
                        }
                        val ignoreUpdate: () -> Unit = {
                            tokenPrefs().edit {
                                putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                            }
                        }
                        if (isTv) {
                            // TV: the version and its changelog, the focus on "Update"
                            com.github.andreyasadchy.xtra.ui.tv.TvUpdateDialog.show(this@MainActivity, viewModel.updateVersion, viewModel.updateNotes, startUpdate, ignoreUpdate)
                        } else {
                            com.github.andreyasadchy.xtra.ui.mobile.MobileBottomSheets.showUpdate(
                                this@MainActivity,
                                viewModel.updateVersion,
                                viewModel.updateNotes,
                                startUpdate,
                                ignoreUpdate,
                            )
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateProgress.collectLatest {
                    updateDownloadDialogBinding?.let { binding ->
                        val size = viewModel.updateSize
                        if (size != null) {
                            binding.textView.text = getString(
                                R.string.downloading_update_progress,
                                Formatter.formatFileSize(this@MainActivity, it.toLong()),
                                Formatter.formatFileSize(this@MainActivity, size),
                            )
                            binding.progressBar.progress = (((it.toFloat() / size) * 100)).toInt()
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.closeUpdateDialog.collectLatest {
                    updateDownloadDialog?.dismiss()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateInstallBlocked.collectLatest { blockedUrl ->
                    showInstallBlockedDialog(blockedUrl)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateInstallApk.collectLatest { apk ->
                    UpdateInstallHelper.openApkWithInstaller(this@MainActivity, apk)
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lifecycleScope.launch {
                    viewModel.checkNetworkStatus.value = true
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                lifecycleScope.launch {
                    viewModel.checkCellularStatus.value = true
                }
            }

            override fun onLost(network: Network) {
                lifecycleScope.launch {
                    viewModel.checkNetworkStatus.value = true
                }
            }
        }
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }.build(),
            callback
        )
        networkCallback = callback
        val pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    INTENT_START_AUDIO_ONLY -> {
                        (playerFragment as? PlayerFragment)?.startAudioOnly()
                        moveTaskToBack(false)
                    }
                    INTENT_PLAY_PAUSE_PLAYER -> {
                        (playerFragment as? PlayerFragment)?.playPause()
                    }
                    INTENT_CLOSE_PLAYER -> {
                        (playerFragment as? PlayerFragment)?.let {
                            it.close()
                            closePlayer()
                        }
                        moveTaskToBack(false)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            IntentFilter().apply {
                addAction(INTENT_START_AUDIO_ONLY)
                addAction(INTENT_PLAY_PAUSE_PLAYER)
                addAction(INTENT_CLOSE_PLAYER)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pipActionReceiver = pipReceiver
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playbackStates.collectLatest { states ->
                    val savedState = states.firstOrNull()
                    if (savedState != null) {
                        (playerFragment as? PlayerFragment)?.close()
                        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
                            C.MEDIA_PLAYER -> MediaPlayerFragment()
                            else -> ExoPlayerFragment()
                        }.apply {
                            if (savedState.type == BasePlaybackService.OFFLINE_VIDEO) {
                                arguments = Bundle().apply {
                                    putBoolean(PlayerFragment.KEY_OFFLINE, true)
                                }
                            }
                        }
                        startPlayer(fragment)
                    }
                }
            }
        }
        restorePlayerFragment()
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.video.collectLatest { pair ->
                    val video = pair?.first
                    val offset = pair?.second
                    if (video != null) {
                        if (!video.id.isNullOrBlank()) {
                            (playerFragment as? PlayerFragment)?.also {
                                it.minimize()
                                it.close()
                                closePlayer()
                            }
                            startVideo(video, offset, offset != null)
                        }
                        viewModel.video.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.clip.collectLatest { clip ->
                    if (clip != null) {
                        if (!clip.id.isNullOrBlank()) {
                            startClip(clip)
                        }
                        viewModel.clip.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.user.collectLatest { user ->
                    if (user != null) {
                        if (!user.id.isNullOrBlank() || !user.login.isNullOrBlank()) {
                            leavePlayerForBrowsing()
                            navController.navigate(
                                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                    channelId = user.id,
                                    channelLogin = user.login,
                                    channelName = user.name,
                                    channelImage = user.profileImage,
                                )
                            )
                        }
                        viewModel.user.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.game.collectLatest { pair ->
                    if (pair != null) {
                        val game = pair.first
                        val tag = pair.second
                        if (game != null) {
                            leavePlayerForBrowsing()
                            navController.navigate(
                                if (prefs.getBoolean(C.UI_GAME_PAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameId = game.id,
                                        gameSlug = game.slug,
                                        gameName = game.name,
                                        boxArt = game.boxArt,
                                        tags = tag?.let { arrayOf(it) },
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameId = game.id,
                                        gameSlug = game.slug,
                                        gameName = game.name,
                                        boxArt = game.boxArt,
                                        tags = tag?.let { arrayOf(it) },
                                    )
                                }
                            )
                        }
                        viewModel.game.value = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tag.collectLatest { tag ->
                    if (tag != null) {
                        leavePlayerForBrowsing()
                        navController.navigate(
                            GamesFragmentDirections.actionGlobalGamesFragment(
                                tags = arrayOf(tag)
                            )
                        )
                        viewModel.tag.value = null
                    }
                }
            }
        }
        if (prefs.getBoolean(C.ENABLE_INTEGRITY, false) && TwitchApiHelper.isIntegrityTokenExpired(this)) {
            getNewIntegrityToken(null, supportFragmentManager)
        }
        if (prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)) {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "live_notifications",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    private fun setNavBarColor(isPortrait: Boolean) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                window.isNavigationBarContrastEnforced = !isPortrait || !binding.navBarContainer.isVisible
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                @Suppress("DEPRECATION")
                window.navigationBarColor = if (isPortrait && binding.navBarContainer.isVisible) {
                    Color.TRANSPARENT
                } else {
                    val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                        it.getBoolean(0, false)
                    }
                    ContextCompat.getColor(this, if (!isLightTheme) R.color.darkScrim else R.color.lightScrim)
                }
            }
            else -> {
                val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                    it.getBoolean(0, false)
                }
                @Suppress("DEPRECATION")
                if (!isLightTheme) {
                    window.navigationBarColor = if (isPortrait && binding.navBarContainer.isVisible) {
                        Color.TRANSPARENT
                    } else {
                        ContextCompat.getColor(this, R.color.darkScrim)
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setNavBarColor(newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    private fun startUpdateDownload(url: String) {
        val binding = DialogUpdateDownloadBinding.inflate(layoutInflater)
        updateDownloadDialogBinding = binding
        val size = viewModel.updateSize
        if (size != null) {
            binding.textView.text = getString(
                R.string.downloading_update_progress,
                Formatter.formatFileSize(this@MainActivity, 0),
                Formatter.formatFileSize(this@MainActivity, size),
            )
        } else {
            binding.textView.text = getString(R.string.downloading_update)
            binding.progressBar.visibility = View.GONE
        }
        viewModel.downloadUpdate(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), url)
        val dialog = getAlertDialogBuilder()
            .setView(binding.root)
            .setNegativeButton(getString(android.R.string.cancel), null)
            .setOnDismissListener {
                viewModel.updateJob?.cancel()
                updateDownloadDialogBinding = null
                updateDownloadDialog = null
            }
            .show()
        updateDownloadDialog = dialog
    }

    override fun onResume() {
        super.onResume()
        lastUserInteractionAt = android.os.SystemClock.elapsedRealtime()
        restorePlayerFragment()
        if (isTv) {
            updateTvMenuAccount()
        }
        if (!isTvPlayerOverContent()) {
            refreshVisibleScreensIfStale(60_000L)
        }
        // back from the settings "Install unknown apps" (also when the TV closed the app meanwhile): the update downloads
        UpdateInstallHelper.takePendingUpdate(this)?.let { startUpdateDownload(it) }
    }

    override fun onDestroy() {
        leaveHandler.removeCallbacks(leaveApp)
        if (isTv) {
            com.github.andreyasadchy.xtra.ui.tv.TvAmbient.removeDynamicListener(tvAmbientListener)
        }
        networkCallback?.let {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        pipActionReceiver?.let { unregisterReceiver(it) }
        if (isFinishing && !keepPlayerInBackground) {
            (playerFragment as? PlayerFragment)?.close()
            if (isTv) {
                com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.update(null, emptyList())
                viewModel.deletePlaybackStates()
            }
        }
        super.onDestroy()
    }

    @Deprecated("The installer of Android is started for a result so it knows the app asked for the installation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        UpdateInstallHelper.onActivityResult(this, requestCode, resultCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        com.github.andreyasadchy.xtra.ui.tv.TvBackup.onRequestPermissionsResult(this, requestCode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.hasExtra("tv_test")) {
            runTvTest(intent.getStringExtra("tv_test"))
            return
        }
        handleIntent(intent)
    }

    /** Debug builds only: opens screens directly for the screenshots of the emulator test. */
    private fun runTvTest(action: String?) {
        when (action) {
            "multistream" -> lifecycleScope.launch {
                val streams = try {
                    (com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource(
                        gqlQueryLanguages = null,
                        gqlQuerySort = com.github.andreyasadchy.xtra.graphql.type.StreamSort.VIEWER_COUNT,
                        gqlLanguages = null,
                        gqlSort = "VIEWER_COUNT",
                        tags = null,
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@MainActivity),
                        graphQLRepository = (application as XtraApp).xtraModule.graphQLRepository,
                        helixHeaders = TwitchApiHelper.getHelixHeaders(this@MainActivity),
                        helixRepository = (application as XtraApp).xtraModule.helixRepository,
                        enableIntegrity = false,
                        networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    ).load(androidx.paging.PagingSource.LoadParams.Refresh(null, 10, false)) as? androidx.paging.PagingSource.LoadResult.Page)?.data
                } catch (e: Exception) {
                    null
                }
                streams?.take(6)?.let { startMultiStream(it) }
            }
            else -> (playerFragment as? PlayerFragment)?.runTvTest(action)
        }
    }

    private fun restartActivity() {
        finish()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        )
    }

    /** TV: second stream in a small window over the full screen player. */
    private val tvPip by lazy {
        // a layer over the player, full screen, that only holds the small window and never takes the focus
        val layer = android.widget.FrameLayout(this).apply {
            elevation = 24 * resources.displayMetrics.density
            isFocusable = false
            isClickable = false
        }
        (binding.root as ViewGroup).addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        com.github.andreyasadchy.xtra.ui.player.TvPip(this, layer)
    }
    /** TV: banner when a followed streamer goes live, while the app is open. */
    private val tvLiveAlerts by lazy {
        // a layer over everything (player, small window), that never takes the focus
        val layer = android.widget.FrameLayout(this).apply {
            elevation = 32 * resources.displayMetrics.density
            isFocusable = false
            isClickable = false
        }
        (binding.root as ViewGroup).addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        com.github.andreyasadchy.xtra.ui.tv.TvLiveAlerts(this, layer)
    }
    private var dispatchingPlayerKey = false

    fun isPlayerMaximized() = (playerFragment as? PlayerFragment)?.isMaximizedPlayer() == true

    fun isPlayerMaximizedScreen() = (playerFragment as? PlayerFragment)?.isMaximizedScreen() == true

    fun hasPlayer() = playerFragment != null

    /** The full screen player has the focus and its controls are hidden: OK opens them. */
    fun isPlayerFocusedWithoutControls(): Boolean {
        val player = playerFragment as? PlayerFragment ?: return false
        val focus = currentFocus
        return player.isMaximizedPlayer() && !player.areControlsShown() && !player.isChatFocused() &&
                (focus == null || focus == binding.root || focus.isInside(binding.playerContainer))
    }

    /** A key given to the player as if the small window was not there. */
    fun dispatchPlayerKey(event: KeyEvent) {
        dispatchingPlayerKey = true
        try {
            dispatchKeyEvent(event)
        } finally {
            dispatchingPlayerKey = false
        }
    }

    private var currentStream: Stream? = null

    fun currentPlayingStream(): Stream? {
        val live = (playerFragment as? PlayerFragment)?.getPlayingStream()
        val cached = currentStream?.takeIf { live == null || it.channelLogin == live.channelLogin }
        return if (live != null && cached != null) {
            Stream(
                id = live.id ?: cached.id,
                channelId = live.channelId ?: cached.channelId,
                channelLogin = live.channelLogin ?: cached.channelLogin,
                channelName = live.channelName ?: cached.channelName,
                channelImageURL = cached.channelImageURL,
                gameId = live.gameId ?: cached.gameId,
                gameSlug = live.gameSlug ?: cached.gameSlug,
                gameName = live.gameName ?: cached.gameName,
                title = live.title ?: cached.title,
                thumbnailURL = cached.thumbnailURL,
                createdAt = live.createdAt ?: cached.createdAt,
                viewerCount = live.viewerCount ?: cached.viewerCount,
                tags = cached.tags,
            )
        } else {
            live ?: cached
        }
    }

    /** TV: small window(s) open over the full screen player. */
    fun isTvPipOpen() = isTv && tvPip.activeCount > 0

    fun tvPipActiveSlotIndices(): List<Int> = if (isTv) tvPip.activeSlotIndices() else emptyList()

    fun tvPipStreamAt(slotIndex: Int): Stream? = if (isTv) tvPip.streamAt(slotIndex) else null

    fun tvPipActiveStreams(): List<Stream> = if (isTv) tvPip.activeStreams() else emptyList()

    fun isStreamInTvPip(login: String?): Boolean = isTv && tvPip.containsStream(login)

    /** TV: corner, size, edge margin, label mode and sound of the small window(s) changed in its panel. */
    fun applyTvPipSettings() {
        if (tvPip.activeCount > 0) {
            tvPip.applyLayout()
            tvPip.applySound()
            tvPip.wakeChrome()
        }
    }

    fun isTvLiveAlertShowing(): Boolean = isTv && tvLiveAlerts.isBannerShowing

    fun openTvLiveAlert(): Boolean = if (isTv) tvLiveAlerts.openCurrentOrRecent() else false

    /** The controls of the full screen player are shown or hidden: the small window(s) make room for them. */
    fun onTvControlsVisibility(visible: Boolean) {
        if (isTv && tvPip.hasAnySlot) {
            tvPip.setControlsShown(visible)
        }
    }

    /** All small windows closed: the player takes its button away (and the focus off it). */
    fun onTvPipClosed() {
        (playerFragment as? PlayerFragment)?.onTvPipClosed()
    }

    fun swapTvPip(slotIndex: Int? = null) {
        tvPip.swapStreams(slotIndex)
    }

    fun startTvPipFreeMove(slotIndex: Int) {
        if (isTv) {
            tvPip.startFreeMove(slotIndex)
        }
    }

    fun isTvSideMenuOpen(): Boolean = isTv && isTvMenuShown

    fun isMinimizedGhostScreen(): Boolean {
        if (!isTv || !::navController.isInitialized) return false
        val destId = navController.currentDestination?.id
        return destId == R.id.tvSettingsFragment || destId == R.id.tvStatsFragment
    }

    fun isTvSettingsScreen(): Boolean {
        if (!isTv || !::navController.isInitialized) return false
        return navController.currentDestination?.id == R.id.tvSettingsFragment
    }

    fun isTvSettingsRightColumnFocused(): Boolean = currentTvSettings()?.isRightColumnFocused() == true

    fun refreshTvMiniPlayerPosition(immediate: Boolean = false) {
        (playerFragment as? PlayerFragment)?.scheduleTvMinimizedGhostCheck(immediate)
    }

    /** The volume of the full screen stream follows the sound chosen for the small window(s). */
    fun refreshPlayerVolume() {
        (playerFragment as? PlayerFragment)?.refreshVolume()
    }

    /** OK held on a stream of the row of the other streams: it plays in a small window (up to 2). */
    fun openTvPip(stream: Stream, targetSlot: Int? = null) {
        if (stream.channelLogin.equals(currentStreamLogin, ignoreCase = true) || tvPip.containsStream(stream.channelLogin)) {
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, R.string.tv_pip_already_shown, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val first = tvPip.activeCount == 0
        tvPip.open(stream, targetSlot)
        tvPip.setControlsShown((playerFragment as? PlayerFragment)?.areControlsShown() == true)
        (playerFragment as? PlayerFragment)?.onTvPipOpened()
        if (first) {
            com.github.andreyasadchy.xtra.ui.player.TvPip.showHint(this)
        }
    }

    fun closeTvPipSlot(slotIndex: Int) {
        if (isTv) {
            tvPip.closeSlot(slotIndex)
        }
    }

    fun dropTvPipForEndedStream(login: String?) {
        if (isTv) {
            tvPip.dropForEndedStream(login)
        }
    }

    fun closeLatestTvPip() {
        if (isTv) {
            tvPip.closeLatest()
        }
    }

    fun closeTvPip() {
        if (isTv) {
            tvPip.close()
        }
    }

    private var lastUserInteractionAt = android.os.SystemClock.elapsedRealtime()
    private var oledScreensaverView: View? = null
    private var oledScreensaverActive = false
    private var oledWakeKeyCode = 0
    private val oledScreensaverHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val oledScreensaverTick: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val timeoutSec = prefs().getString(C.TV_OLED_SCREENSAVER_TIMEOUT, "0")?.toLongOrNull() ?: 0L
            if (timeoutSec <= 0L) {
                dismissTvOledScreensaver()
                return
            }
            val playingActiveVideo = tvMultiStreamShown ||
                (playerFragment as? PlayerFragment)?.isActivelyPlayingVideo() == true
            if (playingActiveVideo) {
                if (oledScreensaverActive) dismissTvOledScreensaver()
            } else if (idleDurationMs() >= timeoutSec * 1_000L) {
                showTvOledScreensaver()
            }
            oledScreensaverHandler.postDelayed(this, 5_000L)
        }
    }

    fun refreshTvOledScreensaver() {
        oledScreensaverHandler.removeCallbacks(oledScreensaverTick)
        if (!isTv) return
        val timeoutSec = prefs().getString(C.TV_OLED_SCREENSAVER_TIMEOUT, "0")?.toLongOrNull() ?: 0L
        if (timeoutSec <= 0L) {
            dismissTvOledScreensaver()
        } else {
            oledScreensaverHandler.postDelayed(oledScreensaverTick, 5_000L)
        }
    }

    private fun showTvOledScreensaver() {
        val targetAlpha = (prefs().getString(C.TV_OLED_SCREENSAVER_DIM, "100")?.toIntOrNull() ?: 100)
            .coerceIn(50, 100) / 100f
        if (oledScreensaverActive) {
            oledScreensaverView?.let { v ->
                if (v.alpha != targetAlpha) v.alpha = targetAlpha
            }
            return
        }
        oledScreensaverActive = true
        com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.releaseAll()
        val overlay = oledScreensaverView ?: View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            elevation = 10_000f
            alpha = 0f
            visibility = View.GONE
            this@MainActivity.findViewById<ViewGroup>(android.R.id.content)?.addView(
                this,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            oledScreensaverView = this
        }
        overlay.animate().cancel()
        overlay.bringToFront()
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(targetAlpha).setDuration(600L).start()
    }

    private fun dismissTvOledScreensaver() {
        if (!oledScreensaverActive && oledScreensaverView?.visibility != View.VISIBLE) return
        oledScreensaverActive = false
        oledScreensaverView?.let { v ->
            v.animate().cancel()
            v.alpha = 0f
            v.visibility = View.GONE
        }
    }

    fun idleDurationMs(): Long =
        (android.os.SystemClock.elapsedRealtime() - lastUserInteractionAt).coerceAtLeast(0L)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastUserInteractionAt = android.os.SystemClock.elapsedRealtime()
            if (oledScreensaverActive) {
                dismissTvOledScreensaver()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private var tvBackLongPending = false
    private var tvBackLongPendingAction: String? = null
    private var noPlayerCustomDownKey = 0
    private var noPlayerCustomLongAction: String? = null
    private val tvBackLongHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val tvBackLongTimeoutRunnable = Runnable {
        if (!tvBackLongPending || isFinishing || isDestroyed) return@Runnable
        val act = tvBackLongPendingAction ?: resolveApplicableTvBackLongAction()
        tvBackLongPending = false
        tvBackLongPendingAction = null
        if (act != null) {
            PlayerFragment.markHeldRemoteKey(KeyEvent.KEYCODE_BACK)
            triggerTvBackLongAction(act)
        }
    }

    private val noPlayerCustomLongRunnable = Runnable {
        val key = noPlayerCustomDownKey
        val act = noPlayerCustomLongAction
        if (key == 0 || act == null || isFinishing || isDestroyed) return@Runnable
        noPlayerCustomDownKey = 0
        noPlayerCustomLongAction = null
        PlayerFragment.markHeldRemoteKey(key)
        when (act) {
            com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.ACTION_LAST_STREAM -> switchToLastWatchedStream()
            com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys.ACTION_LIVE_ALERT -> openTvLiveAlert()
        }
    }

    private fun resolveApplicableTvBackLongAction(): String? {
        if (!isTv || tvMultiStreamShown || hasOpenDialogFragment()) return null
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        val raw = Keys.backLongAction(this)
        if (raw == Keys.ACTION_DEFAULT) return null
        val player = (playerFragment as? PlayerFragment)?.takeIf { !it.isRemoving && !it.isDetached }
        if (player == null) {
            return raw.takeIf {
                (it == Keys.ACTION_LAST_STREAM && hasLastWatchedStream()) ||
                    (it == Keys.ACTION_LIVE_ALERT && isTvLiveAlertShowing())
            }
        }
        return if (player.isMaximizedPlayer()) {
            if (!player.canRunHiddenHudRemoteShortcuts()) return null
            val effective = player.effectiveRemoteAction(raw)
            effective.takeIf { it != Keys.ACTION_DEFAULT && it != Keys.ACTION_OPEN_MINI_PLAYER }
        } else {
            raw.takeIf {
                (it == Keys.ACTION_LIVE_ALERT && isTvLiveAlertShowing()) ||
                    (it != Keys.ACTION_LIVE_ALERT && player.getTvNowPlaying() != null && Keys.isBackgroundCapableAction(it)) ||
                    (it == Keys.ACTION_LAST_STREAM && hasLastWatchedStream())
            }
        }
    }

    private fun triggerTvBackLongAction(action: String) {
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        if (action == Keys.ACTION_LAST_STREAM) {
            switchToLastWatchedStream()
            return
        }
        if (action == Keys.ACTION_LIVE_ALERT) {
            openTvLiveAlert()
            return
        }
        val player = playerFragment as? PlayerFragment ?: return
        if (player.isMaximizedPlayer()) {
            player.executeTvRemoteAction(action)
        } else {
            player.executeTvBackgroundRemoteAction(action)
        }
    }

    private fun performShortTvBack() {
        val player = playerFragment as? PlayerFragment
        if (isTvPipOpen() && isPlayerFocusedWithoutControls()) {
            closeLatestTvPip()
            return
        }
        if (isTvMenuShown && !tvMenuOpenedByBack &&
            prefs.getBoolean(C.TV_BACK_CLOSES_MENU, true) && !tvMultiStreamShown &&
            player?.isMaximizedPlayer() != true
        ) {
            closeTvMenuAndFocusContent()
            return
        }
        if (!isTvMenuShown && isBrowsingRoot() && !tvGridBackCallback.isEnabled) {
            val settingsFrag = if (::navController.isInitialized && navController.currentDestination?.id == R.id.tvSettingsFragment) {
                currentTvSettings()
            } else null
            if (settingsFrag != null && settingsFrag.canHandleTvBackPress()) {
                settingsFrag.handleTvBackPress()
                return
            }
            val onHome = ::navController.isInitialized && navController.currentDestination?.id == R.id.tvHomeFragment
            if (onHome && prefs.getBoolean(C.TV_CONFIRM_EXIT, true)) {
                confirmOrExitTvApp()
            } else {
                tvMenuOpenedByBack = true
                showTvMenu(true)
            }
            return
        }
        onBackPressedDispatcher.onBackPressed()
    }

    private fun dispatchTvBackLongKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) {
            return false
        }
        val action = tvBackLongPendingAction ?: resolveApplicableTvBackLongAction()
        if (action == null) {
            tvBackLongHandler.removeCallbacks(tvBackLongTimeoutRunnable)
            tvBackLongPending = false
            tvBackLongPendingAction = null
            return false
        }
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 550L)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    tvBackLongPending = true
                    tvBackLongPendingAction = action
                    tvBackLongHandler.removeCallbacks(tvBackLongTimeoutRunnable)
                    tvBackLongHandler.postDelayed(tvBackLongTimeoutRunnable, longPressMs)
                } else if (tvBackLongPending && (event.repeatCount >= 1 || event.isLongPress)) {
                    tvBackLongHandler.removeCallbacks(tvBackLongTimeoutRunnable)
                    tvBackLongPending = false
                    tvBackLongPendingAction = null
                    PlayerFragment.markHeldRemoteKey(KeyEvent.KEYCODE_BACK)
                    triggerTvBackLongAction(action)
                }
            }
            KeyEvent.ACTION_UP -> {
                tvBackLongHandler.removeCallbacks(tvBackLongTimeoutRunnable)
                val wasPending = tvBackLongPending
                tvBackLongPending = false
                tvBackLongPendingAction = null
                if (wasPending && !event.isCanceled) {
                    val heldMs = if (event.downTime > 0L) event.eventTime - event.downTime else 0L
                    if (heldMs >= longPressMs) {
                        triggerTvBackLongAction(action)
                    } else {
                        performShortTvBack()
                    }
                }
            }
            else -> {
                tvBackLongHandler.removeCallbacks(tvBackLongTimeoutRunnable)
                tvBackLongPending = false
                tvBackLongPendingAction = null
            }
        }
        return true
    }

    private fun dispatchNoPlayerCustomRemoteKey(event: KeyEvent): Boolean {
        if (!isTv || tvMultiStreamShown || playerFragment != null || hasOpenDialogFragment()) return false
        val Keys = com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
        fun isNoPlayerAction(act: String): Boolean =
            act == Keys.ACTION_LAST_STREAM || act == Keys.ACTION_LIVE_ALERT
        fun runNoPlayerAction(act: String) {
            when (act) {
                Keys.ACTION_LAST_STREAM -> switchToLastWatchedStream()
                Keys.ACTION_LIVE_ALERT -> openTvLiveAlert()
            }
        }
        val shortAction = Keys.customShortActionForKey(this, event.keyCode)?.takeIf(::isNoPlayerAction)
        val longAction = Keys.customLongActionForKey(this, event.keyCode)?.takeIf(::isNoPlayerAction)
        if (shortAction == null && longAction == null) return false
        if (longAction == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && shortAction != null) {
                runNoPlayerAction(shortAction)
            }
            return true
        }
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350L, 550L)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    noPlayerCustomDownKey = event.keyCode
                    noPlayerCustomLongAction = longAction
                    tvBackLongHandler.removeCallbacks(noPlayerCustomLongRunnable)
                    tvBackLongHandler.postDelayed(noPlayerCustomLongRunnable, longPressMs)
                } else if (noPlayerCustomDownKey == event.keyCode && (event.repeatCount >= 1 || event.isLongPress)) {
                    tvBackLongHandler.removeCallbacks(noPlayerCustomLongRunnable)
                    noPlayerCustomDownKey = 0
                    noPlayerCustomLongAction = null
                    PlayerFragment.markHeldRemoteKey(event.keyCode)
                    runNoPlayerAction(longAction)
                }
            }
            KeyEvent.ACTION_UP -> {
                tvBackLongHandler.removeCallbacks(noPlayerCustomLongRunnable)
                val wasDown = noPlayerCustomDownKey == event.keyCode
                noPlayerCustomDownKey = 0
                noPlayerCustomLongAction = null
                if (wasDown && !event.isCanceled) {
                    val heldMs = if (event.downTime > 0L) event.eventTime - event.downTime else 0L
                    if (heldMs >= longPressMs) {
                        runNoPlayerAction(longAction)
                    } else if (shortAction != null) {
                        runNoPlayerAction(shortAction)
                    }
                }
            }
            else -> {
                tvBackLongHandler.removeCallbacks(noPlayerCustomLongRunnable)
                noPlayerCustomDownKey = 0
                noPlayerCustomLongAction = null
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (oledScreensaverActive) {
            lastUserInteractionAt = android.os.SystemClock.elapsedRealtime()
            if (event.action == KeyEvent.ACTION_DOWN) {
                oledWakeKeyCode = event.keyCode
                dismissTvOledScreensaver()
            }
            return true
        }
        if (oledWakeKeyCode != 0 && event.keyCode == oledWakeKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) {
                oledWakeKeyCode = 0
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastTvKeyCode = event.keyCode
            lastTvKeyAt = android.os.SystemClock.elapsedRealtime()
            lastUserInteractionAt = lastTvKeyAt
            if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                lastExitBackAt = 0L
            }
        }
        if (isTv && PlayerFragment.consumeHeldRemoteKeyTail(event)) {
            return true
        }
        if (isTv && tvMultiStreamShown && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK &&
            keepFocusInMultiStream() && currentFocus?.isInside(binding.playerContainer) == true
        ) {
            // the focus was not on a stream: this key put it back there
            return true
        }
        if (isTv && !dispatchingPlayerKey && tvLiveAlerts.onKey(event)) {
            return true
        }
        if (isTv && !dispatchingPlayerKey && (tvPip.isSelectorOpen() || tvPip.isFreeMoving()) && tvPip.onKey(event)) {
            return true
        }
        if (isTv && !dispatchingPlayerKey && (playerFragment as? PlayerFragment)?.onTvChatFreeMoveKey(event) == true) {
            return true
        }
        if (isTv && !dispatchingPlayerKey && (playerFragment as? PlayerFragment)?.onTvChatScrollKey(event) == true) {
            return true
        }
        if (isTv && !dispatchingPlayerKey && (playerFragment as? PlayerFragment)?.onTvVolumePillKey(event) == true) {
            return true
        }
        if (isTv && !dispatchingPlayerKey && dispatchTvBackLongKey(event)) {
            return true
        }
        if (isTv && !dispatchingPlayerKey && tvPip.onKey(event)) {
            return true
        }
        if (isTv && !tvMultiStreamShown && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            val player = playerFragment as? PlayerFragment
            val controlsWereShown = player?.let { it.isMaximizedPlayer() && it.areControlsShown() } == true
            if (keepFocusInPlayer() && controlsWereShown) {
                // HUD was already shown without focus: land the focus disc on its primary control first
                return true
            }
        }
        if (BuildConfig.DEBUG && isTv) {
            android.util.Log.d("XtraTv", "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} focus=${currentFocus?.let { "${it.javaClass.simpleName}#${try { resources.getResourceEntryName(it.id) } catch (e: Exception) { it.id }}" }}")
        }
        (playerFragment as? PlayerFragment)?.onKeyEventDispatched(event)
        if (isTv && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            val player = playerFragment as? PlayerFragment
            val switchDelta = player?.handlesDpadSwitchDirection(event.keyCode)
            if (player != null && switchDelta != null) {
                // configured D-pad direction changes the streamer whatever has the focus: keys pressed while a switch makes
                // the new player screen never reach the HUD (seek) nor the screens behind the player (focus lost)
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && streamQueue.size > 1) {
                    switchStream(switchDelta)
                }
                val focus = currentFocus
                if (focus == null || !focus.isShown || !focus.isInside(binding.playerContainer)) {
                    player.requestPlayerFocus()
                }
                return true
            }
        }
        if (isTv && event.keyCode == KeyEvent.KEYCODE_BACK && isTvMenuShown && !tvMenuOpenedByBack &&
            prefs.getBoolean(C.TV_BACK_CLOSES_MENU, true) && !tvMultiStreamShown &&
            (playerFragment as? PlayerFragment)?.isMaximizedPlayer() != true
        ) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                closeTvMenuAndFocusContent()
            }
            return true
        }
        if (isTv && event.keyCode == KeyEvent.KEYCODE_BACK && !isTvMenuShown && isBrowsingRoot() && !tvGridBackCallback.isEnabled) {
            val settingsFrag = if (::navController.isInitialized && navController.currentDestination?.id == R.id.tvSettingsFragment) {
                currentTvSettings()
            } else null
            if (settingsFrag != null && settingsFrag.canHandleTvBackPress()) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    settingsFrag.handleTvBackPress()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val onHome = ::navController.isInitialized && navController.currentDestination?.id == R.id.tvHomeFragment
                if (onHome && prefs.getBoolean(C.TV_CONFIRM_EXIT, true)) {
                    confirmOrExitTvApp()
                } else {
                    tvMenuOpenedByBack = true
                    showTvMenu(true)
                }
            }
            return true
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN && isTvMenuShown &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
        ) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                tvMenuOpenedByBack = false
            }
            // the menu keeps the focus at its ends, only right leaves it
            val focus = currentFocus
            // the Compose menu handles its own ends
            if (focus != null && focus.isInside(binding.navBarContainer) && binding.tvSideMenu?.let { focus.isInside(it) } != true) {
                val direction = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    else -> View.FOCUS_LEFT
                }
                val next = FocusFinder.getInstance().findNextFocus(binding.root, focus, direction)
                if (next == null || !next.isInside(binding.navBarContainer)) {
                    return true
                }
            }
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val focus = currentFocus
            if (focus != null && focus.isInside(binding.navBarContainer)) {
                if (event.repeatCount == 0 && isTvMenuShown && tvMenuState.isNowPlayingOpenFocused && tvMenuState.focusNowPlayingClose()) {
                    return true
                }
                if (event.repeatCount == 0) {
                    closeTvMenuAndFocusContent()
                }
                return true
            }
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val focus = currentFocus
            if (focus != null && focus.parent?.parent is com.google.android.material.tabs.TabLayout && focus.isInside(binding.navHostFragment)) {
                // from the section tabs, go to the list of the selected section
                val grid = TvUi.findVisibleGrid(binding.navHostFragment)
                if (grid != null && TvUi.focusFirstItem(grid)) {
                    return true
                }
            }
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !isTvMenuShown) {
            val focus = currentFocus
            if (focus != null && focus.isInside(binding.navHostFragment) && (playerFragment as? PlayerFragment)?.isMaximizedPlayer() != true &&
                focus.parent !is androidx.compose.ui.platform.ComposeView
            ) {
                val next = FocusFinder.getInstance().findNextFocus(binding.root, focus, View.FOCUS_LEFT)
                if (next == null || !next.isInside(binding.navHostFragment)) {
                    tvMenuOpenedByBack = false
                    showTvMenu(true)
                    return true
                }
            }
        }
        if (isTv && !tvMultiStreamShown && (playerFragment as? PlayerFragment)?.dispatchCustomRemoteKey(event) == true) {
            return true
        }
        if (isTv && !tvMultiStreamShown && dispatchNoPlayerCustomRemoteKey(event)) {
            return true
        }
        if (isTv && !tvMultiStreamShown &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) &&
            (playerFragment as? PlayerFragment)?.let { it.isMaximizedPlayer() && it.isLiveStreamScreen() } == true
        ) {
            return true
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if ((playerFragment as? PlayerFragment)?.let { it.isMaximizedPlayer() && it.isLiveOrDvrScreen() } == true && streamQueue.size > 1) {
                        if (event.repeatCount == 0) {
                            switchStream(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                        }
                        return true
                    } else if ((playerFragment as? PlayerFragment)?.isMaximizedPlayer() == true && currentClipId != null && clipQueue.size > 1) {
                        if (event.repeatCount == 0) {
                            playNextClip(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1, wrap = true)
                        }
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val focus = currentFocus
                    if (focus == null || !focus.isShown) {
                        // the focused view went away (list refresh, navigation...): bring the focus back instead of losing the key
                        if (ensureFocus(force = true)) {
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private val isTv by lazy { resources.getBoolean(R.bool.isTv) }
    private var tvBaseBackgroundColor: Int? = null

    /** The dynamic ambiance changed color: the glow of the background, and the texts of the cards in the color of accent. */
    private val tvAmbientListener: (Int) -> Unit = { color ->
        if (!isDestroyed) {
            tvBaseBackgroundColor?.let { base ->
                com.github.andreyasadchy.xtra.ui.tv.TvAmbient.applyDynamicToView(binding.root, base, color)
            }
            tintTvCards(binding.navHostFragment, onlyVisible = true)
            (playerFragment as? PlayerFragment)?.refreshTvMinimizedBorderColor()
        }
    }

    private fun tintTvCards(view: View, onlyVisible: Boolean = false) {
        if (onlyVisible && !view.isShown) return
        when (view) {
            is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView -> view.tintCards()
            is ViewGroup -> for (i in 0 until view.childCount) tintTvCards(view.getChildAt(i), onlyVisible)
        }
    }
    private var isTvMenuShown = true
    private var tvMenuOpenedByBack = false
    fun isTvMenuOpen() = isTv && isTvMenuShown
    private fun isCompactRailEnabled() = isTv && prefs.getBoolean(C.TV_COMPACT_RAIL, true)

    private fun applyTvFragmentPadding(f: Fragment, v: View) {
        if (!isTv) return
        val isHome = f is com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment
        val density = resources.displayMetrics.density
        val overscan = resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val start = when {
            isHome && isCompactRailEnabled() -> (68 * density).toInt()
            isHome -> 0
            isCompactRailEnabled() -> (84 * density).toInt()
            else -> overscan
        }
        val end = if (isHome) 0 else overscan
        (v as? ViewGroup)?.apply {
            clipToPadding = false
            clipChildren = false
        }
        if (v.paddingStart != start || v.paddingEnd != end) {
            v.setPaddingRelative(start, v.paddingTop, end, v.paddingBottom)
        }
    }

    private fun applyTvNavHostPadding() {
        if (!isTv) return
        binding.navHostFragment.clipToPadding = false
        binding.navHostFragment.clipChildren = false
        if (binding.navHostFragment.paddingStart != 0 || binding.navHostFragment.paddingEnd != 0) {
            binding.navHostFragment.setPaddingRelative(
                0,
                binding.navHostFragment.paddingTop,
                0,
                binding.navHostFragment.paddingBottom,
            )
        }
        supportFragmentManager.findFragmentById(R.id.navHostFragment)
            ?.childFragmentManager?.fragments
            ?.forEach { f ->
                if (f !is androidx.fragment.app.DialogFragment) {
                    f.view?.let { v -> applyTvFragmentPadding(f, v) }
                }
            }
    }
    private var tvInitialFocusDone = false

    /** Back on a main screen opens the side menu, back again in the menu leaves the app. */
    /** Back far down a list goes up to its top first, like YouTube. */
    private val tvGridBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val grid = tvFocusedGrid ?: return
            isEnabled = false
            grid.scrollToPosition(0)
            grid.post {
                if (grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() != true) {
                    TvUi.focusFirstItem(grid)
                }
            }
        }
    }
    private var tvFocusedGrid: com.github.andreyasadchy.xtra.ui.view.GridRecyclerView? = null

    /** Called when the focus moves: remembers the card of the list and enables back to the top. */
    private fun onTvContentFocus(focus: View) {
        var view: View? = focus
        var child: View? = null
        while (view != null && view !is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView) {
            child = view
            view = view.parent as? View
        }
        val grid = view as? com.github.andreyasadchy.xtra.ui.view.GridRecyclerView
        val position = if (grid != null && child != null) grid.getChildAdapterPosition(child) else RecyclerView.NO_POSITION
        tvFocusedGrid = grid
        val span = grid?.columnCount ?: 1
        tvGridBackCallback.isEnabled = grid != null && position >= span * 2
        if (grid != null && position != RecyclerView.NO_POSITION && ::navController.isInitialized) {
            tvFocusKey()?.let { tvFocusMemory[it] = position }
        }
        if (child != null && grid != null && com.github.andreyasadchy.xtra.ui.tv.TvAmbient.isDynamic(this) &&
            !(::navController.isInitialized && navController.currentDestination?.id == R.id.channelPagerFragment)
        ) {
            // dynamic ambiance: the pictures of the item of the card (thumbnail, then avatar), the same way on every
            // list; the picture drawn in the card only for a list whose items are not known
            val item = try {
                when (val adapter = grid.adapter) {
                    is androidx.paging.PagingDataAdapter<*, *> -> position.takeIf { it in 0 until adapter.itemCount }?.let { adapter.peek(it) }
                    is androidx.recyclerview.widget.ListAdapter<*, *> -> adapter.currentList.getOrNull(position)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            if (!com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromItem(this, item, lifecycleScope)) {
                extractCardAmbient(child)
            }
        }
    }

    /** Dynamic ambiance, a list whose items are not known: the color of the picture drawn in the focused card. */
    private fun extractCardAmbient(card: View) {
        var best: android.widget.ImageView? = null
        var bestArea = 0
        fun scan(v: View) {
            if (v is android.widget.ImageView && v.isShown && v.drawable != null) {
                val area = v.width * v.height
                if (area > bestArea) {
                    bestArea = area
                    best = v
                }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
        }
        scan(card)
        val drawable = best?.drawable ?: return
        val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        com.github.andreyasadchy.xtra.ui.tv.TvAmbient.updateFromBitmap(this, null, bmp, lifecycleScope)
    }

    /** Position of the focused card of each screen, to give it back when coming back to the screen. */
    private val tvFocusMemory = HashMap<String, Int>()

    /**
     * Key of the screen shown in [tvFocusMemory]: its section for a screen of the side menu (opened again from its root
     * each time, the card is still remembered), else its place in the back stack.
     */
    private fun tvFocusKey(): String? {
        if (!::navController.isInitialized) {
            return null
        }
        val entry = navController.currentBackStackEntry ?: return null
        return if (binding.navBar.menu.findItem(entry.destination.id) != null) "section:${entry.destination.id}" else entry.id
    }

    /** From the side menu into a list: the card that had the focus there, else its first card. */
    private fun focusRememberedItem(grid: com.github.andreyasadchy.xtra.ui.view.GridRecyclerView): Boolean {
        val count = grid.adapter?.itemCount ?: 0
        val position = tvFocusKey()?.let { tvFocusMemory[it] }?.takeIf { it in 1 until count } ?: return TvUi.focusFirstItem(grid)
        val holder = grid.findViewHolderForAdapterPosition(position)
        if (holder != null && holder.itemView.requestFocus()) {
            return true
        }
        grid.scrollToPosition(position)
        grid.post {
            if (grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() != true) {
                TvUi.focusFirstItem(grid)
            }
        }
        return true
    }

    /** Coming back to a screen: focus the card that had the focus there (the views were recreated or unhidden). */
    private fun restoreTvScreenFocus(tries: Int = 0) {
        if (!::navController.isInitialized || isTvMenuShown) {
            return
        }
        val activeRoot = currentVisibleNavFragment()?.view ?: binding.navHostFragment
        val focus = currentFocus
        if (focus != null && focus.isShown && focus.isInside(activeRoot) && focus.parent is RecyclerView) {
            return
        }
        val grid = TvUi.findVisibleGrid(activeRoot)
        val count = grid?.adapter?.itemCount ?: 0
        if (grid == null || count == 0) {
            if (tries < 15) {
                binding.root.postDelayed({ restoreTvScreenFocus(tries + 1) }, 150)
            }
            return
        }
        val position = tvFocusKey()?.let { tvFocusMemory[it] } ?: 0
        val target = position.coerceIn(0, count - 1)
        val holder = grid.findViewHolderForAdapterPosition(target)
        if (holder == null || !holder.itemView.requestFocus()) {
            grid.scrollToPosition(target)
            grid.post {
                if (grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() != true) {
                    TvUi.focusFirstItem(grid)
                }
            }
        }
    }

    /**
     * Back in the side menu of the first screen leaves the app: like Home, the stream of the mini player or of the sound
     * only mode keeps playing behind when the Xtra TV settings keep the sound (else the app closes as before).
     */
    private var lastExitBackAt = 0L

    private fun exitTvApp() {
        lastExitBackAt = 0L
        val player = playerFragment as? PlayerFragment
        if (player?.keepsPlayingWhenAppLeft() == true) {
            moveTaskToBack(true)
        } else {
            leaveHandler.removeCallbacks(leaveApp)
            keepPlayerInBackground = false
            setLeaveMuted(false, main = true)
            closeTvPip()
            player?.let {
                it.close()
                closePlayer(focusHome = false)
            }
            closeMultiStream()
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.update(null, emptyList())
            viewModel.deletePlaybackStates()
            finish()
        }
    }

    private fun confirmOrExitTvApp() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExitBackAt <= 2000L) {
            exitTvApp()
        } else {
            lastExitBackAt = now
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, R.string.tv_press_back_again_to_exit, Toast.LENGTH_SHORT).show()
        }
    }

    private val tvLeaveBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val player = playerFragment as? PlayerFragment
            val leavesApp = isTvMenuShown && !tvMultiStreamShown && ::navController.isInitialized &&
                    navController.previousBackStackEntry == null && player?.isMaximizedPlayer() != true
            if (leavesApp) {
                if (prefs.getBoolean(C.TV_CONFIRM_EXIT, true)) {
                    confirmOrExitTvApp()
                } else {
                    exitTvApp()
                }
            } else {
                // anything else: the back of before
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                updateTvMenuBackCallback()
            }
        }
    }

    private val tvMenuBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val onHome = ::navController.isInitialized && navController.currentDestination?.id == R.id.tvHomeFragment
            if (onHome && prefs.getBoolean(C.TV_CONFIRM_EXIT, true)) {
                confirmOrExitTvApp()
            } else {
                tvMenuOpenedByBack = true
                showTvMenu(true)
            }
        }
    }

    private fun updateTvMenuBackCallback() {
        val atRoot = isTv && ::navController.isInitialized && navController.previousBackStackEntry == null
        tvLeaveBackCallback.isEnabled = atRoot && isTvMenuShown
        tvMenuBackCallback.isEnabled = atRoot && !isTvMenuShown
    }

    private val tvMenuState = com.github.andreyasadchy.xtra.ui.tv.TvMenuState()

    /** The leanback menu, drawn with Compose from the entries of the navigation bar. */
    private fun setupTvSideMenu() {
        val composeView = binding.tvSideMenu ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            composeView.defaultFocusHighlightEnabled = false
            binding.navBarContainer.defaultFocusHighlightEnabled = false
        }
        val icons = mapOf(
            R.id.searchPagerFragment to R.drawable.baseline_search_black_24,
            R.id.tvHomeFragment to R.drawable.baseline_home_black_24,
            R.id.rootGamesFragment to R.drawable.ic_games_black_24dp,
            R.id.rootTopFragment to R.drawable.ic_trending_up_black_24dp,
            R.id.followPagerFragment to R.drawable.ic_favorite_black_24dp,
            R.id.followMediaFragment to R.drawable.ic_favorite_black_24dp,
            R.id.savedPagerFragment to R.drawable.ic_file_download_black_24dp,
            R.id.savedMediaFragment to R.drawable.ic_file_download_black_24dp,
            R.id.tvStatsFragment to R.drawable.tv_ic_bar_chart,
            R.id.tvSettingsFragment to R.drawable.baseline_settings_black_24,
            R.id.tvNavAccount to R.drawable.baseline_person_black_24,
            R.id.tvNavFollowedVideos to R.drawable.tv_ic_video_library,
            R.id.tvNavFollowedChannels to R.drawable.tv_ic_person,
            R.id.tvNavFollowedGames to R.drawable.tv_ic_gamepad,
        )
        val menu = binding.navBar.menu
        val followedTabs = listOf(R.id.tvNavFollowedVideos, R.id.tvNavFollowedChannels, R.id.tvNavFollowedGames)
        val hasFollowing = menu.findItem(R.id.followPagerFragment) != null
        val entries = (0 until menu.size()).map { index ->
            val item = menu.getItem(index)
            val sub = hasFollowing && item.itemId in followedTabs
            com.github.andreyasadchy.xtra.ui.tv.TvMenuItem(
                id = item.itemId,
                title = item.title.toString(),
                icon = icons[item.itemId],
                group = when (item.itemId) {
                    R.id.searchPagerFragment -> com.github.andreyasadchy.xtra.ui.tv.TvMenuState.GROUP_TOP
                    R.id.tvStatsFragment, R.id.tvSettingsFragment, R.id.tvNavAccount -> com.github.andreyasadchy.xtra.ui.tv.TvMenuState.GROUP_BOTTOM
                    else -> com.github.andreyasadchy.xtra.ui.tv.TvMenuState.GROUP_MAIN
                },
                sub = sub,
                // under "Following" the short name is enough
                label = if (sub) {
                    getString(
                        when (item.itemId) {
                            R.id.tvNavFollowedVideos -> R.string.videos
                            R.id.tvNavFollowedChannels -> R.string.channels
                            else -> R.string.games
                        }
                    )
                } else item.title.toString()
            )
        }
        // the shortcuts to the tabs of the followed channels go right under "Following"
        tvMenuState.items = if (hasFollowing) {
            val subs = entries.filter { it.sub }
            entries.filter { !it.sub }.flatMap { if (it.id == R.id.followPagerFragment) listOf(it) + subs else listOf(it) }
        } else entries
        tvMenuState.selected = binding.navBar.selectedItemId
        tvMenuState.accountId = R.id.tvNavAccount
        updateTvMenuAccount()
        // option: the page of the focused entry is shown without OK (all integrated pages except Account switcher dialog)
        tvMenuState.onFocusItem = { id ->
            binding.root.removeCallbacks(tvMenuAutoNav)
            tvMenuPendingId = 0
            val onSubScreen = ::navController.isInitialized && navController.previousBackStackEntry != null
            if (prefs.getBoolean(C.TV_FOCUS_SWITCH, false) && id != R.id.tvNavAccount && (id != tvMenuState.selected || onSubScreen)) {
                tvMenuPendingId = id
                binding.root.postDelayed(tvMenuAutoNav, TV_MENU_HOVER_SETTLE_MS)
            }
        }
        com.github.andreyasadchy.xtra.ui.view.TvHeader.sectionTitle = {
            tvMenuState.items.find { it.id == tvMenuState.selected }?.title
        }
        com.github.andreyasadchy.xtra.ui.view.TvHeader.sectionIcon = {
            // only on the screen of the section itself, not on a category or another screen opened from it
            if (::navController.isInitialized && navController.currentBackStackEntry?.destination?.id?.let { binding.navBar.menu.findItem(it) } != null) {
                tvMenuState.items.find { it.id == tvMenuState.selected }?.icon
            } else null
        }
        // "Now playing": the stream kept in the mini player or as audio only
        tvMenuState.onNowPlayingClick = {
            hideTvMenu()
            (playerFragment as? PlayerFragment)?.maximize()
        }
        tvMenuState.onNowPlayingClose = {
            stopTvPlayback()
            tvMenuState.focusSelected()
        }
        // a preview with sound plays over the stream kept in the background: only the preview is heard
        com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.onPreviewSound = { playing ->
            (playerFragment as? PlayerFragment)?.takeIf { !it.isMaximizedPlayer() }?.setDucked(playing)
        }
        val accent = MaterialColors.getColor(composeView, androidx.appcompat.R.attr.colorPrimary)
        composeView.setContent {
            com.github.andreyasadchy.xtra.ui.tv.TvTheme(accent) {
                com.github.andreyasadchy.xtra.ui.tv.TvSideMenu(tvMenuState) { id ->
                    // OK on an entry: its page gets the focus (even right after it was shown without OK)
                    binding.root.removeCallbacks(tvMenuAutoNav)
                    tvMenuPendingId = 0
                    tvMenuAutoNavAt = 0L
                    tvMenuAutoHovering = false
                    binding.navBar.selectedItemId = id
                    tvMenuState.selected = tvMenuExtraSelected ?: binding.navBar.selectedItemId
                    if (id != R.id.tvNavAccount) {
                        // the chosen screen gets the focus, the menu folds away
                        hideTvMenu()
                        focusContentAfterMenu(id == R.id.searchPagerFragment)
                    }
                }
            }
        }
    }

    private val tvRootDestinations = com.github.andreyasadchy.xtra.ui.tv.TvNavHostFragment.TV_ROOT_DESTINATIONS

    /** Last key pressed (and when): a move of the focus asked by the user, not by a page that loaded. */
    private var lastTvKeyCode = 0
    private var lastTvKeyAt = 0L
    /** When a page was shown from the side menu without OK. */
    private var tvMenuAutoNavAt = 0L
    private var tvMenuPendingId = 0
    private var tvMenuAutoHovering = false
    /** Option of the Xtra TV settings: the entry focused in the side menu shows its page after settling on it. */
    private val tvMenuAutoNav = Runnable {
        val id = tvMenuPendingId
        tvMenuPendingId = 0
        val onSubScreen = ::navController.isInitialized && navController.previousBackStackEntry != null
        val wrongRoot = ::navController.isInitialized && id in tvRootDestinations && navController.currentDestination?.id != id
        if (id == 0 || !isTvMenuShown || (id == binding.navBar.selectedItemId && tvMenuExtraSelected == null && !onSubScreen && !wrongRoot)) {
            return@Runnable
        }
        tvMenuAutoNavAt = android.os.SystemClock.elapsedRealtime()
        lastContentFocus = null
        tvMenuAutoHovering = true
        try {
            if (id == binding.navBar.selectedItemId && id in tvRootDestinations && (tvMenuExtraSelected != null || onSubScreen || wrongRoot)) {
                tvMenuExtraSelected = null
                openTvSectionRoot(id)
            } else {
                binding.navBar.selectedItemId = id
            }
        } finally {
            tvMenuAutoHovering = false
        }
        tvMenuState.selected = tvMenuExtraSelected ?: binding.navBar.selectedItemId
    }

    /** Entry of the side menu shown as chosen instead of its screen ("Followed videos" rather than "Following"). */
    private var tvMenuExtraSelected: Int? = null

    private fun followMenuEntryForKey(key: String?): Int? = when (key) {
        "2" -> R.id.tvNavFollowedVideos
        "3" -> R.id.tvNavFollowedChannels
        "0" -> R.id.tvNavFollowedGames
        else -> null
    }?.takeIf { id -> tvMenuState.items.any { it.id == id } }

    private fun resolveFollowMenuEntryId(): Int {
        tvMenuExtraSelected?.takeIf { id -> tvMenuState.items.any { it.id == id } }?.let { return it }
        val followFrag = currentVisibleNavFragment() as? com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment
        val activeEntry = followMenuEntryForKey(followFrag?.standaloneTabKey() ?: followFrag?.activeTabKey())
        if (activeEntry != null) {
            tvMenuExtraSelected = activeEntry
            return activeEntry
        }
        return R.id.followPagerFragment
    }

    fun onFollowTabChanged(key: String?) {
        if (!isTv || isTvMenuShown) return
        if (!::navController.isInitialized || navController.currentDestination?.id != R.id.followPagerFragment) return
        val extra = followMenuEntryForKey(key)
        tvMenuExtraSelected = extra
        tvMenuState.selected = extra ?: R.id.followPagerFragment
    }

    /** TV side menu: a tab of the followed channels (videos, channels, categories), switched in place when already open. */
    private fun openFollowedTab(key: String, entry: Int, fromAutoHover: Boolean = tvMenuAutoHovering) {
        val loggedInWithCode = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank()
        if (key == "2" && !loggedInWithCode) {
            if (!fromAutoHover) {
                // the videos of the followed channels need the login with the code (the token of the app)
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, R.string.tv_followed_videos_need_code, Toast.LENGTH_LONG).show()
            }
            return
        }
        tvMenuExtraSelected = entry
        tvMenuState.selected = entry
        val current = currentVisibleNavFragment()
        if (current is com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment && current.selectMenuTab(key)) {
            return
        }
        com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.clearStandalone = false
        com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.requestedTab = key
        openTvSectionRoot(R.id.followPagerFragment)
    }

    /**
     * TV: switches to a side-menu root section with zero view re-inflation via [com.github.andreyasadchy.xtra.ui.tv.TvKeepStateFragmentNavigator],
     * popping any transient sub-screens (category, channel) and keeping the back stack flat (`previousBackStackEntry == null`)
     * so Back on any root section cleanly opens the side menu.
     */
    private fun openTvSectionRoot(id: Int): Boolean {
        return try {
            if (id == R.id.followPagerFragment && tvMenuExtraSelected == null) {
                com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.clearStandalone = true
                com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.requestedTab = null
                val current = currentVisibleNavFragment()
                if (current is com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment &&
                    navController.previousBackStackEntry == null &&
                    current.selectMenuTab(null)
                ) {
                    com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.clearStandalone = false
                    return true
                }
            }
            if (navController.currentDestination?.id == id && navController.previousBackStackEntry == null) {
                if (!tvMenuAutoHovering) {
                    binding.root.postDelayed({ refreshVisibleScreensIfStale(20_000L) }, 160L)
                }
                return true
            }
            val hoverSwitch = tvMenuAutoHovering
            navController.navigate(id, null, androidx.navigation.navOptions {
                launchSingleTop = true
                popUpTo(navController.graph.id) { inclusive = false }
                if (!hoverSwitch) {
                    anim {
                        enter = R.anim.tv_section_fade_in
                        exit = R.anim.tv_section_fade_out
                        popEnter = R.anim.tv_section_fade_in
                        popExit = R.anim.tv_section_fade_out
                    }
                }
            })
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** The account at the top of the side menu: its name, read again when the app comes back (logged in or out since). */
    private fun updateTvMenuAccount() {
        com.github.andreyasadchy.xtra.ui.tv.TvAccounts.syncCurrentFromPrefs(this)
        val rawName = tokenPrefs().getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }
        val name = rawName ?: if (com.github.andreyasadchy.xtra.ui.tv.TvAccounts.isGuestMode(this)) {
            getString(R.string.tv_accounts_guest_short)
        } else {
            null
        }
        if (name != tvMenuState.accountName) {
            tvMenuState.accountName = name
            if (rawName == null) {
                tvMenuState.accountImage = null
            }
        }
    }

    /** The picture of the account, loaded by the home. */
    fun setTvAccountImage(image: String?) {
        tvMenuState.accountImage = image
        if (!image.isNullOrBlank()) {
            com.github.andreyasadchy.xtra.ui.tv.TvAccounts.syncCurrentFromPrefs(this, avatarUrl = image)
        }
    }

    /** Opens the side menu from a Compose screen (left on its first card / category). */
    fun openTvMenu() {
        binding.root.post {
            tvMenuOpenedByBack = false
            showTvMenu(true)
        }
    }

    private fun closeTvMenuAndFocusContent() {
        binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (tvMenuPendingId != 0) {
            val pendingId = tvMenuPendingId
            tvMenuPendingId = 0
            binding.root.removeCallbacks(tvMenuAutoNav)
            tvMenuAutoNavAt = 0L
            tvMenuAutoHovering = false
            lastContentFocus = null
            binding.navBar.selectedItemId = pendingId
            tvMenuState.selected = tvMenuExtraSelected ?: binding.navBar.selectedItemId
            hideTvMenu()
            focusContentAfterMenu(pendingId == R.id.searchPagerFragment)
            return
        }
        val activeRoot = currentVisibleNavFragment()?.view ?: binding.navHostFragment
        // the home first: giving the focus back to its view would put it on its banner. The card that had the
        // focus is asked before the view takes the focus (the banner focused meanwhile is not remembered)
        val home = activeRoot.findViewById<View>(R.id.tvHome)?.takeIf { it.isShown }
        if (home != null) {
            hideTvMenu()
            currentTvHome()?.focusLastCard()
            home.requestFocus()
            return
        }
        val statsView = activeRoot.findViewById<View>(R.id.tvStatsRoot)?.takeIf { it.isShown }
        if (statsView != null) {
            hideTvMenu()
            currentTvStats()?.requestContentFocus()
            statsView.requestFocus()
            return
        }
        val settingsView = activeRoot.findViewById<View>(R.id.tvSettingsRoot)?.takeIf { it.isShown }
        if (settingsView != null) {
            hideTvMenu()
            currentTvSettings()?.requestContentFocus()
            settingsView.requestFocus()
            return
        }
        // from the menu, go back to the item last selected in the screen, else to its first item
        val last = lastContentFocus?.takeIf { it.isAttachedToWindow && it.isShown && it.isInside(activeRoot) }
        if (last != null && last.requestFocus()) {
            hideTvMenu()
            return
        }
        val grid = TvUi.findVisibleGrid(activeRoot)
        if (grid != null && focusRememberedItem(grid)) {
            hideTvMenu()
            return
        }
        if (activeRoot.requestFocus(View.FOCUS_RIGHT)) {
            hideTvMenu()
            return
        }
        hideTvMenu()
        focusContentAfterMenu(false)
    }

    private fun refreshStaleStreamLists(view: View, maxAgeMs: Long = 20_000L) {
        if (!view.isShown) return
        when (view) {
            is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView -> view.refreshIfStale(maxAgeMs)
            is ViewGroup -> for (i in 0 until view.childCount) refreshStaleStreamLists(view.getChildAt(i), maxAgeMs)
        }
    }

    /** Goes to a section of the side menu ("see all" of the TV home). */
    fun openTvSection(id: Int) {
        if (binding.navBar.menu.findItem(id) == null) {
            val sectionName = when (id) {
                R.id.rootTopFragment -> getString(R.string.popular)
                R.id.rootGamesFragment -> getString(R.string.games)
                R.id.followPagerFragment, R.id.followMediaFragment -> getString(R.string.following)
                R.id.savedPagerFragment, R.id.savedMediaFragment -> getString(R.string.saved)
                R.id.tvStatsFragment -> getString(R.string.tv_nav_stats)
                R.id.searchPagerFragment -> getString(R.string.search)
                else -> getString(R.string.popular)
            }
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                this,
                getString(R.string.tv_section_hidden_toast, sectionName),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        hideTvMenu()
        binding.navBar.selectedItemId = id
        tvMenuState.selected = binding.navBar.selectedItemId
        focusContentAfterMenu(id == R.id.searchPagerFragment)
    }

    private fun focusContentAfterMenu(search: Boolean, tries: Int = 0, maxTries: Int = 15) {
        binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.root.postDelayed({
            binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val activeRoot = currentVisibleNavFragment()?.view ?: binding.navHostFragment
            val target = if (search) {
                activeRoot.findViewById<View>(R.id.tvSearchBar)?.takeIf { it.isShown }
                    ?: activeRoot.findViewById<View>(R.id.tvHeaderCompose)?.takeIf { it.isShown }
            } else {
                TvUi.findVisibleGrid(activeRoot)?.takeIf { (it.adapter?.itemCount ?: 0) > 0 && it.childCount > 0 }
                    ?: activeRoot.findViewById<View>(R.id.tvHome)?.takeIf { it.isShown }
                    ?: activeRoot.findViewById<View>(R.id.tvStatsRoot)?.takeIf { it.isShown }
                    ?: activeRoot.findViewById<View>(R.id.tvSettingsRoot)?.takeIf { it.isShown }
            }
            when {
                target is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView -> focusRememberedItem(target)
                target != null && target.id == R.id.tvHome -> {
                    // the home: the card that had the focus (else the first stream of the first row), never "refresh"
                    currentTvHome()?.focusLastCard()
                    target.requestFocus()
                }
                target != null && target.id == R.id.tvStatsRoot -> {
                    currentTvStats()?.requestContentFocus()
                    target.requestFocus()
                }
                target != null && target.id == R.id.tvSettingsRoot -> {
                    currentTvSettings()?.requestContentFocus()
                    target.requestFocus()
                }
                target != null -> target.requestFocus()
                tries < maxTries -> focusContentAfterMenu(search, tries + 1, maxTries)
            }
        }, if (tries == 0) 250L else 150L)
    }

    private fun showTvMenu(focus: Boolean) {
        if (tvMultiStreamShown) {
            // never over a multi-stream
            keepFocusInMultiStream()
            return
        }
        val menu = binding.navBarContainer
        binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        menu.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (::navController.isInitialized) {
            navController.currentDestination?.id?.let { destId ->
                if (destId == R.id.followPagerFragment) {
                    tvMenuState.selected = resolveFollowMenuEntryId()
                } else if (tvMenuExtraSelected == null && tvMenuState.items.any { it.id == destId }) {
                    tvMenuState.selected = destId
                }
            }
        }
        val wasCollapsed = tvMenuState.collapsed
        tvMenuState.collapsed = false
        if (!isTvMenuShown) {
            isTvMenuShown = true
            updateTvMenuBackCallback()
            menu.animate().cancel()
            menu.visibility = View.VISIBLE
            menu.animate().translationX(0f).alpha(1f).setDuration(180L).start()
        }
        if (focus) {
            val sideMenu = binding.tvSideMenu
            if (sideMenu != null) {
                val alreadyOpenWithFocus = !wasCollapsed && sideMenu.hasFocus()
                tvMenuState.requestFocusSelected()
                if (!sideMenu.hasFocus()) {
                    sideMenu.requestFocus()
                }
                if (alreadyOpenWithFocus && tvMenuState.focusSelected() && tvMenuState.focusedId == tvMenuState.selected) {
                    tvMenuState.pendingFocus = false
                    tvMenuState.suppressFocusCallback = false
                }
            } else {
                val selected = binding.navBar.findViewById<View>(binding.navBar.selectedItemId)
                if (selected == null || !selected.requestFocus()) {
                    menu.requestFocus()
                }
            }
        }
        (playerFragment as? PlayerFragment)?.scheduleTvMinimizedGhostCheck()
    }

    private fun hideTvMenu() {
        if (!isTv) return
        tvMenuOpenedByBack = false
        tvMenuState.pendingFocus = false
        // a player or a multi-stream is over the screens: they stay out of reach of the focus
        if (!isTvPlayerOverContent()) {
            binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val menu = binding.navBarContainer
        if (isCompactRailEnabled()) {
            if (isTvMenuShown) {
                isTvMenuShown = false
                updateTvMenuBackCallback()
            }
            tvMenuState.collapsed = true
            menu.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            menu.animate().cancel()
            menu.translationX = 0f
            menu.alpha = 1f
            menu.visibility = View.VISIBLE
        } else if (isTvMenuShown) {
            isTvMenuShown = false
            updateTvMenuBackCallback()
            menu.animate().cancel()
            if (menu.width > 0) {
                menu.animate().translationX(-menu.width.toFloat()).alpha(0f).setDuration(180L).withEndAction {
                    if (!isTvMenuShown) {
                        menu.visibility = View.INVISIBLE
                    }
                }.start()
            } else {
                menu.alpha = 0f
                menu.visibility = View.INVISIBLE
                menu.doOnLayout { if (!isTvMenuShown) it.translationX = -it.width.toFloat() }
            }
        }
        (playerFragment as? PlayerFragment)?.scheduleTvMinimizedGhostCheck()
    }

    private fun isBrowsingRoot(): Boolean {
        if (tvMultiStreamShown || (playerFragment as? PlayerFragment)?.isMaximizedPlayer() == true) {
            return false
        }
        val focus = currentFocus
        if (focus != null && !focus.isInside(binding.navHostFragment)) {
            return false
        }
        return ::navController.isInitialized && navController.previousBackStackEntry == null
    }

    /**
     * Gives the focus back to the screen when nothing visible has it.
     * Returns true if the focus was moved.
     */
    private fun ensureFocus(force: Boolean = false): Boolean {
        val focus = currentFocus
        if (!force && focus != null && focus.isShown) {
            return false
        }
        val player = playerFragment as? PlayerFragment
        if (player != null && player.requestPlayerFocus()) {
            return true
        }
        if (isTv && player != null && player.isMaximizedScreen()) {
            // the player screen is being made (a switch): the focus waits for it, it never goes behind the player
            return true
        }
        if (isTv && tvMultiStreamShown) {
            // a multi-stream: the focus on one of its streams, never behind them
            keepFocusInMultiStream()
            return true
        }
        if (isTv && isTvMenuShown && (focus == null || !focus.isShown)) {
            // the leanback menu is where browsing starts
            showTvMenu(true)
            if (currentFocus != null) {
                return true
            }
        }
        lastContentFocus?.let {
            if (it.isAttachedToWindow && it.isShown && it.requestFocus()) {
                return true
            }
        }
        return binding.navHostFragment.requestFocus() || binding.navBarContainer.requestFocus()
    }

    private var adblockPrefsBeforeSettings: List<Boolean>? = null

    private fun snapshotAdblockPrefs(): List<Boolean> = listOf(
        com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isExperimentalEnabled(this),
        com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock.isSwapAdblockEnabled(this),
        prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false),
        prefs.getBoolean(C.PLAYER_USE_STREAM_PROXY, false),
    )

    private fun openIntegratedTvSettings(subScreen: String?) {
        com.github.andreyasadchy.xtra.ui.settings.TvSettingsFragment.pendingOpenSubScreen = subScreen
        if (isPlayerMaximized()) {
            (playerFragment as? PlayerFragment)?.let { pf ->
                if (prefs.getString(C.TV_BACK_ACTION, "mini") == "audio") pf.leaveForBrowsing() else pf.minimize()
            }
        }
        openTvSection(R.id.tvSettingsFragment)
        currentTvSettings()?.consumePendingSubScreen(this)
    }

    fun openSettings() {
        if (isTv) {
            openIntegratedTvSettings(null)
            return
        }
        settingsResultLauncher?.launch(Intent(this, SettingsActivity::class.java))
    }

    /** TV: the settings of the custom proxies (from the proxy panel of the player). */
    fun openCustomProxySettings() {
        if (isTv) {
            openIntegratedTvSettings(SettingsActivity.OPEN_CUSTOM_PROXIES)
            return
        }
        settingsResultLauncher?.launch(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, SettingsActivity.OPEN_CUSTOM_PROXIES))
    }

    /** TV: the settings of the HTTP stream proxies (from the proxy panel of the player). */
    fun openStreamProxySettings() {
        if (isTv) {
            openIntegratedTvSettings(SettingsActivity.OPEN_STREAM_PROXIES)
            return
        }
        settingsResultLauncher?.launch(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, SettingsActivity.OPEN_STREAM_PROXIES))
    }

    fun refreshTvAmbientColor() {
        if (!isTv) return
        com.github.andreyasadchy.xtra.ui.tv.TvAccent.init(this)
        tvBaseBackgroundColor?.let { base ->
            binding.root.background = com.github.andreyasadchy.xtra.ui.tv.TvAmbient.background(this, base)
        }
        tintTvCards(binding.navHostFragment)
        (playerFragment as? PlayerFragment)?.refreshTvMinimizedBorderColor()
        setupTvSideMenu()
    }

    fun refreshTvNavigationUi() {
        if (!isTv) return
        val prevSelected = binding.navBar.selectedItemId
        populateNavBarMenu(parseNavigationTabList())
        if (binding.navBar.menu.findItem(prevSelected) != null) {
            binding.navBar.selectedItemId = prevSelected
        }
        setupTvSideMenu()
        applyTvNavHostPadding()
        if (!isTvMenuShown) {
            hideTvMenu()
        }
    }

    private var pendingTvRecreate = false

    fun markTvRecreateOnLeaveSettings() {
        if (isTv) {
            pendingTvRecreate = true
        }
    }

    fun consumePendingTvRecreate(): Boolean {
        if (!pendingTvRecreate) return false
        pendingTvRecreate = false
        recreatingActivity = true
        recreate()
        return true
    }

    /** Applies updated TV card sizes, card zoom, rail inset, and card display options live across all kept-alive screens. */
    fun refreshTvLayoutSettings(rebindCards: Boolean = false) {
        if (!isTv) return
        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment)
        navHost?.childFragmentManager?.fragments?.forEach { frag ->
            if (frag is com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment) {
                frag.syncPreferencesNow()
            }
            frag.view?.let { refreshGridRecyclerViews(it, rebindCards) }
        }
        refreshGridRecyclerViews(binding.navHostFragment, rebindCards)
    }

    private fun refreshGridRecyclerViews(root: View, rebindCards: Boolean) {
        if (root is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView) {
            root.refreshTvLayoutSettings(rebindCards)
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                refreshGridRecyclerViews(root.getChildAt(i), rebindCards)
            }
        }
    }

    fun onTvAdblockOrProxyChanged() {
        if (!isTv || currentPlayingStream() == null) return
        val wasMaximized = isPlayerMaximized()
        restartCurrentStream()
        if (!wasMaximized) {
            (playerFragment as? PlayerFragment)?.let { pf ->
                if (prefs.getString(C.TV_BACK_ACTION, "mini") == "audio") pf.leaveForBrowsing() else pf.minimize()
            }
        }
    }

    /** TV: the stream watched starts again (proxy turned on or off): its last picture stays until it plays again. */
    fun restartCurrentStream() {
        val stream = currentPlayingStream() ?: return
        startStream(stream)
    }

    private var pendingAddAccountPreviousKey: String? = null

    fun onTvAccountSwitched() {
        restartActivity()
    }

    fun onAccountClicked() {
        if (resources.getBoolean(R.bool.isTv)) {
            com.github.andreyasadchy.xtra.ui.tv.TvAccounts.syncCurrentFromPrefs(this, avatarUrl = tvMenuState.accountImage)
            val savedAccounts = com.github.andreyasadchy.xtra.ui.tv.TvAccounts.listAccounts(this)
            if (savedAccounts.isNotEmpty() || com.github.andreyasadchy.xtra.ui.tv.TvAccounts.isGuestMode(this)) {
                com.github.andreyasadchy.xtra.ui.tv.TvAccounts.showSwitcherPanel(
                    activity = this,
                    onOpenCurrentLoginMethods = {
                        pendingAddAccountPreviousKey = null
                        loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
                    },
                    onAddAnotherAccount = {
                        pendingAddAccountPreviousKey = com.github.andreyasadchy.xtra.ui.tv.TvAccounts.prepareAddAccount(this)
                        loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
                    },
                )
            } else {
                pendingAddAccountPreviousKey = null
                loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
            }
            return
        }
        com.github.andreyasadchy.xtra.ui.tv.TvAccounts.syncCurrentFromPrefs(this)
        com.github.andreyasadchy.xtra.ui.mobile.MobileBottomSheets.showAccountsSheet(
            activity = this,
            onOpenCurrentLoginMethods = {
                pendingAddAccountPreviousKey = null
                loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
            },
            onAddAnotherAccount = {
                pendingAddAccountPreviousKey = com.github.andreyasadchy.xtra.ui.tv.TvAccounts.prepareAddAccount(this)
                loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
            },
        )
    }

    /** True while one of the screens of the app opens over this one (settings, login): the streams go on. */
    private var openingOwnScreen = false
    val isOpeningOwnScreen get() = openingOwnScreen

    override fun startActivity(intent: Intent?, options: Bundle?) {
        openingOwnScreen = intent?.component?.packageName == packageName
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        openingOwnScreen = intent.component?.packageName == packageName
        super.startActivityForResult(intent, requestCode, options)
    }

    private var recreatingActivity = false

    override fun onStart() {
        super.onStart()
        leaveHandler.removeCallbacks(leaveApp)
        if (isTv) {
            setLeaveMuted(false, main = true)
        }
        openingOwnScreen = false
        recreatingActivity = false
        keepPlayerInBackground = false
        if (isTv && ::binding.isInitialized) {
            tvLiveAlerts.start()
            refreshTvOledScreensaver()
        }
    }

    /** TV: the app was left with the mini player or the sound only mode, the sound goes on behind. */
    private var keepPlayerInBackground = false
    val isKeepingPlayerInBackground get() = keepPlayerInBackground

    override fun onStop() {
        oledScreensaverHandler.removeCallbacks(oledScreensaverTick)
        dismissTvOledScreensaver()
        if (isTv && ::binding.isInitialized) {
            tvLiveAlerts.stop()
            com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.releaseAll()
        }
        leaveHandler.removeCallbacks(leaveApp)
        keepPlayerInBackground = false
        if (isTv && !openingOwnScreen && !isChangingConfigurations && !recreatingActivity) {
            val player = playerFragment as? PlayerFragment
            if (player?.keepsPlayingWhenAppLeft() == true) {
                keepPlayerInBackground = true
                closeTvPip()
                closeMultiStream()
                setLeaveMuted(false, main = true)
            } else {
                // Close playback immediately when leaving the app without background sound so Android TV process freeze
                // or task removal never leaves a paused stream, expired playlist spinner, or stale "Playing" badge behind.
                keepPlayerInBackground = false
                setLeaveMuted(false, main = true)
                closeTvPip()
                player?.let {
                    it.close()
                    closePlayer(focusHome = false)
                }
                closeMultiStream()
                com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.update(null, emptyList())
                viewModel.deletePlaybackStates()
            }
        }
        super.onStop()
    }

    /** TV: the sound while the app is left a few seconds: [main] the stream of the player too, else only the others. */
    private fun setLeaveMuted(muted: Boolean, main: Boolean) {
        val mainMuted = muted && main
        if (com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.leaveMuted != mainMuted) {
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.leaveMuted = mainMuted
            refreshPlayerVolume()
        }
        if (com.github.andreyasadchy.xtra.ui.player.TvPip.leaveMuted != muted) {
            com.github.andreyasadchy.xtra.ui.player.TvPip.leaveMuted = muted
            if (isTvPipOpen()) {
                tvPip.applySound()
            }
        }
        (supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG) as? com.github.andreyasadchy.xtra.ui.player.MultiStreamFragment)?.setLeaveMuted(muted)
    }

    private val leaveHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** TV: fallback cleanup runnable if scheduled while app is stopped. */
    private val leaveApp = Runnable {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            return@Runnable
        }
        closeTvPip()
        val player = playerFragment as? PlayerFragment
        if (player?.keepsPlayingWhenAppLeft() == true) {
            keepPlayerInBackground = true
        } else {
            keepPlayerInBackground = false
            player?.let {
                it.close()
                if (!isDestroyed) {
                    closePlayer(focusHome = false)
                }
            }
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.update(null, emptyList())
            viewModel.deletePlaybackStates()
        }
        if (!isDestroyed) {
            closeMultiStream()
        }
        setLeaveMuted(false, main = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // TV: a panel opened from the HUD of the player closed, the HUD comes back
        (playerFragment as? PlayerFragment)?.onTvWindowFocusChanged(hasFocus)
        if (hasFocus && isTv && !tvMultiStreamShown) {
            binding.root.removeCallbacks(keepFocusInPlayerAction)
            binding.root.post(keepFocusInPlayerAction)
            binding.root.postDelayed(keepFocusInPlayerAction, 80L)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true) &&
            (playerFragment as? PlayerFragment)?.canEnterPictureInPicture() == true
        ) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: IllegalStateException) {
                //device doesn't support PIP
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.data?.toString()
                if (url != null) {
                    when {
                        url.contains("twitch.tv/videos/") -> {
                            val id = url.substringAfter("twitch.tv/videos/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            val offset = url.substringAfter("?t=", "").takeIf { it.isNotBlank() }?.let { TwitchApiHelper.getDuration(it).toLong() * 1000 }
                            if (!id.isNullOrBlank()) {
                                viewModel.loadVideo(
                                    id,
                                    offset,
                                    prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getGQLHeaders(this),
                                    TwitchApiHelper.getHelixHeaders(this),
                                    prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        url.contains("/clip/") -> {
                            val id = url.substringAfter("/clip/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!id.isNullOrBlank()) {
                                viewModel.loadClip(
                                    id,
                                    prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getGQLHeaders(this),
                                    TwitchApiHelper.getHelixHeaders(this),
                                    prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        url.contains("clips.twitch.tv/") -> {
                            val id = url.substringAfter("clips.twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!id.isNullOrBlank()) {
                                viewModel.loadClip(
                                    id,
                                    prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getHelixHeaders(this),
                                    TwitchApiHelper.getGQLHeaders(this),
                                    prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        url.contains("twitch.tv/directory/category/") -> {
                            val slug = url.substringAfter("twitch.tv/directory/category/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            val tag = url.substringAfter("?tl=", "").takeIf { it.isNotBlank() }?.substringBefore("&")
                            if (!slug.isNullOrBlank()) {
                                viewModel.loadGame(
                                    gameSlug = slug,
                                    tag = tag?.let { Uri.decode(it) },
                                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this),
                                    helixHeaders = TwitchApiHelper.getHelixHeaders(this),
                                    enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        url.contains("twitch.tv/directory/game/") -> {
                            val name = url.substringAfter("twitch.tv/directory/game/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            val tag = url.substringAfter("?tl=", "").takeIf { it.isNotBlank() }?.substringBefore("&")
                            if (!name.isNullOrBlank()) {
                                viewModel.loadGame(
                                    gameName = Uri.decode(name),
                                    tag = tag?.let { Uri.decode(it) },
                                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this),
                                    helixHeaders = TwitchApiHelper.getHelixHeaders(this),
                                    enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        url.contains("twitch.tv/directory/all/tags/") -> {
                            val tag = url.substringAfter("twitch.tv/directory/all/tags/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!tag.isNullOrBlank()) {
                                leavePlayerForBrowsing()
                                navController.navigate(
                                    TopStreamsFragmentDirections.actionGlobalTopFragment(
                                        tags = arrayOf(Uri.decode(tag))
                                    )
                                )
                            }
                        }
                        url.contains("twitch.tv/directory/all") -> {
                            leavePlayerForBrowsing()
                            navController.navigate(
                                TopStreamsFragmentDirections.actionGlobalTopFragment()
                            )
                        }
                        url.contains("twitch.tv/directory/tags/") -> {
                            val tagId = url.substringAfter("twitch.tv/directory/tags/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!tagId.isNullOrBlank()) {
                                viewModel.loadTag(
                                    tagId,
                                    prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                    TwitchApiHelper.getGQLHeaders(this),
                                    prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        url.contains("twitch.tv/directory") -> {
                            leavePlayerForBrowsing()
                            navController.navigate(
                                GamesFragmentDirections.actionGlobalGamesFragment()
                            )
                        }
                        url.contains("twitch.tv/team/") -> {
                            val teamName = url.substringAfter("twitch.tv/team/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!teamName.isNullOrBlank()) {
                                leavePlayerForBrowsing()
                                navController.navigate(
                                    TeamFragmentDirections.actionGlobalTeamFragment(
                                        teamName = Uri.decode(teamName)
                                    )
                                )
                            }
                        }
                        else -> {
                            val uri = intent.data
                            val login = url.substringAfter("twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!login.isNullOrBlank()) {
                                if (uri?.getQueryParameter("xtratv_play") == "1") {
                                    startStream(
                                        Stream(
                                            id = uri.getQueryParameter("id"),
                                            channelId = uri.getQueryParameter("cid"),
                                            channelLogin = login,
                                            channelName = uri.getQueryParameter("name") ?: login,
                                            channelImageURL = uri.getQueryParameter("img"),
                                            title = uri.getQueryParameter("title"),
                                            gameName = uri.getQueryParameter("game"),
                                        )
                                    )
                                } else {
                                    viewModel.loadUser(
                                        login,
                                        prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                        TwitchApiHelper.getGQLHeaders(this),
                                        TwitchApiHelper.getHelixHeaders(this),
                                        prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            INTENT_INSTALL_UPDATE -> {
                val extras = intent.extras
                val status = extras?.getInt(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    ?: PackageInstaller.STATUS_FAILURE
                val fallbackUrl = extras?.getString("update_url") ?: UpdateInstallHelper.lastAttemptedUpdateUrl
                val msg = extras?.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateInstallHelper.lastInstallerStatus = "session status=$status" + (msg?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                // "Try again" in the dialog: the other way of installing, while one is left
                val retry = fallbackUrl?.takeIf { UpdateInstallHelper.installMethod(this) == UpdateInstallHelper.METHOD_INTENT }?.let { url ->
                    { startUpdateDownload(url) }
                }
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            extras?.getParcelable(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            extras?.getParcelable(Intent.EXTRA_INTENT)
                        }
                        if (confirmIntent != null) {
                            try {
                                // started for a result from this screen: Android knows the app asked for the installation
                                @Suppress("DEPRECATION")
                                startActivityForResult(confirmIntent, UpdateInstallHelper.REQUEST_CONFIRM_INSTALL)
                            } catch (_: Exception) {
                                try {
                                    startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: Exception) {
                                    showInstallBlockedDialog(fallbackUrl, retry)
                                }
                            }
                        } else {
                            showInstallBlockedDialog(fallbackUrl, retry)
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS,
                    PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                        showInstallBlockedDialog(fallbackUrl, retry)
                    }
                    else -> {
                        if (!msg.isNullOrBlank()) {
                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                        showInstallBlockedDialog(fallbackUrl, retry)
                    }
                }
            }
            INTENT_LIVE_NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(KEY_VIDEO, Stream::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(KEY_VIDEO)
                }?.let {
                    startStream(it)
                }
            }
            INTENT_OPEN_DOWNLOADS_TAB -> {
                binding.navBar.selectedItemId = if (prefs.getBoolean(C.UI_SAVED_PAGER, true)) {
                    R.id.savedPagerFragment
                } else {
                    R.id.savedMediaFragment
                }
            }
            INTENT_OPEN_DOWNLOADED_VIDEO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(KEY_VIDEO, OfflineVideo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(KEY_VIDEO)
                }?.let {
                    startOfflineVideo(it)
                }
            }
            INTENT_OPEN_PLAYER -> {
                if (playerFragment != null) {
                    (playerFragment as? PlayerFragment)?.maximize()
                } else {
                    if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER) {
                        viewModel.getPlaybackStates()
                    }
                }
            }
        }
    }

    fun getNewIntegrityToken(callback: String?, fragmentManager: FragmentManager) {
        if (!viewModel.loadingIntegrityToken) {
            if (prefs.getBoolean(C.USE_WEBVIEW_INTEGRITY, true)) {
                viewModel.loadingIntegrityToken = true
                IntegrityDialog.newInstance(callback).show(fragmentManager, null)
            }
        }
    }

    fun integrityTokenLoaded() {
        viewModel.loadingIntegrityToken = false
    }

//Navigation listeners

    /** Streams of the list the current stream was opened from, to switch between them from the player. */
    var streamQueue: List<Stream> = emptyList()
        private set

    /** Live stream whose in-progress VOD is being watched, to go back to the live from the VOD. */
    var dvrStream: Stream? = null

    /** Id of the VOD being recorded of [dvrStream], for the chat of the broadcast. */
    var dvrChatVideoId: String? = null
    private var currentStreamLogin: String? = null

    private var switchJob: kotlinx.coroutines.Job? = null
    private var queueCheckJob: kotlinx.coroutines.Job? = null

    /** Loads the list the current stream was opened from again (followed, a category, popular...), null when it can't be. */
    private var streamQueueSource: (() -> androidx.paging.PagingSource<Int, Stream>)? = null
    private var queueRefreshedAt = 0L
    private var queueRefreshJob: kotlinx.coroutines.Job? = null

    /** A left / right pressed while a switch was still being made: done right after it, not lost. */
    private var pendingSwitch = 0

    fun switchStream(offset: Int) {
        if (streamQueue.size < 2) {
            return
        }
        if (switchJob?.isActive == true) {
            pendingSwitch = offset
            return
        }
        pendingSwitch = 0
        switchJob = lifecycleScope.launch {
            var tries = 0
            while (tries++ < 5) {
                val queue = streamQueue
                if (queue.size < 2) {
                    return@launch
                }
                val index = queue.indexOfFirst { it.channelLogin != null && it.channelLogin == currentStreamLogin }
                val nextIndex = Math.floorMod(index + offset, queue.size)
                val next = queue.getOrNull(nextIndex) ?: return@launch
                // a streamer of the list that stopped since: out of the list, the next one in the same direction
                if (isOffline(next)) {
                    streamQueue = queue.filter { it !== next }
                    if (index < 0) {
                        return@launch
                    }
                    continue
                }
                // the HUD of the stream reached is shown for a moment, left / right keep switching
                com.github.andreyasadchy.xtra.ui.player.PlayerFragment.peekHudOnStart = true
                startStream(next)
                // the list is loaded again in the background for the next switch, the switch itself does not wait for it
                refreshQueueIfOld()
                // near the end of the list: its next page comes after it
                val at = streamQueue.indexOf(next)
                if (at >= 0 && (if (offset > 0) at >= streamQueue.size - 3 else at <= 2)) {
                    extendQueue()
                }
                val updated = streamQueue
                val newIndex = updated.indexOf(next)
                // the streamers that left and right go to now
                com.github.andreyasadchy.xtra.ui.player.TvSwitchOverlay.show(
                    this@MainActivity,
                    updated.getOrNull(Math.floorMod(newIndex - 1, updated.size)),
                    updated.getOrNull(Math.floorMod(newIndex + 1, updated.size)),
                )
                return@launch
            }
        }
        // a left / right pressed meanwhile: the next switch once this one is made
        switchJob?.invokeOnCompletion {
            binding.root.post {
                val next = pendingSwitch
                pendingSwitch = 0
                if (next != 0 && !isFinishing) {
                    switchStream(next)
                }
            }
        }
    }

    /** Live streams of these channels, null when the request fails. */
    private suspend fun loadLiveStreams(ids: List<String>): Map<String, com.github.andreyasadchy.xtra.graphql.UsersStreamQuery.Stream>? {
        return try {
            val result = mutableMapOf<String, com.github.andreyasadchy.xtra.graphql.UsersStreamQuery.Stream>()
            ids.chunked(100).forEach { chunk ->
                (application as XtraApp).xtraModule.graphQLRepository.loadQueryUsersStream(
                    prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    TwitchApiHelper.getGQLHeaders(this),
                    ids = chunk
                ).data!!.users?.forEach { user ->
                    val id = user?.id
                    val stream = user?.stream
                    if (id != null && stream != null) {
                        result[id] = stream
                    }
                }
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun isOffline(stream: Stream): Boolean {
        val id = stream.channelId ?: return false
        return loadLiveStreams(listOf(id))?.let { !it.containsKey(id) } ?: false
    }

    /**
     * The list used to switch streamer is kept up to date while a stream plays: loaded again every minute from where it
     * came from (new streamers, order of Twitch, the ones that stopped go away), else only checked for the ones that stopped.
     */
    private fun startQueueCheck() {
        queueCheckJob?.cancel()
        queueCheckJob = lifecycleScope.launch {
            while (streamQueueSource != null || streamQueue.size > 1) {
                kotlinx.coroutines.delay(QUEUE_REFRESH_MS)
                // nothing to load while the app is in the background or no stream plays
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) || playerFragment == null) {
                    continue
                }
                if (streamQueueSource != null) {
                    if (refreshQueue()) {
                        TvStreamSwitcher.update(streamQueue)
                    }
                } else {
                    removeOfflineFromQueue()
                }
            }
        }
    }

    /** Loads the list again in the background when the last load is older than 30 seconds: it is not waited for. */
    private fun refreshQueueIfOld() {
        if (System.currentTimeMillis() - queueRefreshedAt < QUEUE_FRESH_MS || queueRefreshJob?.isActive == true) {
            return
        }
        queueRefreshJob = lifecycleScope.launch {
            if (streamQueueSource != null) {
                if (refreshQueue()) {
                    TvStreamSwitcher.update(streamQueue)
                }
            } else if (streamQueue.size > 1) {
                // a list without a source: the streamers that stopped leave it
                val before = streamQueue
                removeOfflineFromQueue()
                queueRefreshedAt = System.currentTimeMillis()
                if (streamQueue !== before) {
                    TvStreamSwitcher.update(streamQueue)
                }
            }
        }
    }

    /** Only the streams of [list] that are still live (the ones whose channel is unknown are kept). */
    suspend fun liveStreamsOnly(list: List<Stream>): List<Stream> {
        val ids = list.mapNotNull { it.channelId }
        if (ids.isEmpty()) {
            return list
        }
        val live = loadLiveStreams(ids) ?: return list
        return list.filter { stream -> stream.channelId == null || live.containsKey(stream.channelId) }
    }

    private var queueExtendJob: kotlinx.coroutines.Job? = null

    /** The next page of the list of left / right (30 more streams), added after it. */
    private fun extendQueue() {
        val factory = streamQueueSource ?: return
        if (queueExtendJob?.isActive == true || queueRefreshJob?.isActive == true) {
            return
        }
        val before = streamQueue
        queueExtendJob = lifecycleScope.launch {
            val loaded = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.loadPages(factory, before.size + 30)
            if (loaded.isNullOrEmpty() || streamQueueSource !== factory || streamQueue !== before) {
                return@launch
            }
            val known = before.mapNotNull { it.channelLogin }.toSet()
            val added = loaded.filter { it.channelLogin !in known }
            if (added.isNotEmpty()) {
                streamQueue = before + added
                TvStreamSwitcher.update(streamQueue)
            }
        }
    }

    /** Source of the list of left / right, null when it has none (for the row of the other streams to load more of it). */
    val queueSource get() = streamQueueSource

    /** The row of the other streams loaded more of the list of left / right: left / right go through it too. */
    fun onQueueExtended(list: List<Stream>, source: (() -> androidx.paging.PagingSource<Int, Stream>)?) {
        if (source != null && source === streamQueueSource && list.size > streamQueue.size) {
            streamQueue = list
        }
    }

    /** Loads the list again from its source, in the order of Twitch. True when the list was replaced. */
    private suspend fun refreshQueue(): Boolean {
        val factory = streamQueueSource ?: return false
        val before = streamQueue
        // as many streams as the list had (the pages scrolled to stay), 30 by request
        val loaded = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.loadPages(factory, before.size.coerceIn(30, 150))
        // a failed request, or the list changed meanwhile (other list opened, streamer gone): kept as it is
        if (loaded.isNullOrEmpty() || streamQueueSource !== factory || streamQueue !== before) {
            return false
        }
        queueRefreshedAt = System.currentTimeMillis()
        val fresh = loaded.filter { !it.channelLogin.isNullOrBlank() }.distinctBy { it.channelLogin }
        val current = before.find { it.channelLogin != null && it.channelLogin == currentStreamLogin }
        streamQueue = if (current != null && fresh.none { it.channelLogin == currentStreamLogin }) {
            // the stream being watched stays at its place, left / right start from it
            fresh.toMutableList().apply { add(before.indexOf(current).coerceAtMost(size), current) }
        } else fresh
        return true
    }

    /** List without a source: only the streamers that stopped go away, the others get their new title and viewers. */
    private suspend fun removeOfflineFromQueue() {
        val queue = streamQueue
        val ids = queue.mapNotNull { it.channelId }
        if (ids.isEmpty()) {
            return
        }
        val live = loadLiveStreams(ids) ?: return
        if (streamQueue !== queue) {
            return
        }
        streamQueue = queue.mapNotNull { stream ->
            val update = stream.channelId?.let { live[it] }
            when {
                // the stream being watched stays, the switch starts from it
                stream.channelLogin == currentStreamLogin && update == null -> stream
                update == null -> null
                else -> stream.apply {
                    viewerCount = update.viewersCount ?: viewerCount
                    title = update.broadcaster?.broadcastSettings?.title ?: title
                    gameName = update.game?.displayName ?: gameName
                }
            }
        }
    }

    /** TV multi-stream: replaces the player with up to 4 streams shown together. */
    fun startMultiStream(streams: List<Stream>, origin: Stream? = null) {
        // from here the focus belongs to the streams: nothing behind them (home, lists, side menu) may take it
        tvMultiStreamShown = true
        hideTvMenu()
        closeTvPip()
        (playerFragment as? PlayerFragment)?.let {
            it.close()
            closePlayer(focusHome = false)
        }
        setMainContentFocusable(false)
        binding.navBarContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        supportFragmentManager.beginTransaction()
            .replace(R.id.playerContainer, com.github.andreyasadchy.xtra.ui.player.MultiStreamFragment.newInstance(streams, origin), MULTI_STREAM_TAG)
            .commit()
    }

    /** A multi-stream is shown (set before its screen is made, so that the focus waits for it). */
    private var tvMultiStreamShown = false
    fun isTvMultiStreamOpen() = tvMultiStreamShown

    /** The screen of a multi-stream went away, however it was closed (a stream opened over it, back...). */
    fun onMultiStreamDestroyed() {
        binding.root.post {
            if (tvMultiStreamShown && supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG) == null) {
                tvMultiStreamShown = false
                if (playerFragment == null) {
                    setMainContentFocusable(true)
                    refreshVisibleScreensIfStale(60_000L)
                }
            }
        }
    }

    /** A full screen player or a multi-stream covers the screens. */
    fun isTvPlayerOverContent() = tvMultiStreamShown || (playerFragment as? PlayerFragment)?.isMaximizedScreen() == true

    /** Called when the player transitions from full screen to mini-player over the browsing screens. */
    fun onPlayerMinimized() {
        if (!tvMultiStreamShown) {
            refreshVisibleScreensIfStale(60_000L)
        }
    }

    /**
     * Silently refreshes the currently visible browsing screens (TV Home and paged lists) if their data is older than [maxAgeMs].
     */
    fun refreshVisibleScreensIfStale(maxAgeMs: Long = 20_000L) {
        if (isFinishing || isDestroyed || !::binding.isInitialized) return
        fun visit(fragments: List<androidx.fragment.app.Fragment>) {
            for (fragment in fragments) {
                if (!fragment.isAdded || !fragment.isVisible) continue
                when (fragment) {
                    is com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment -> fragment.refreshIfStale(maxAgeMs)
                    is com.github.andreyasadchy.xtra.ui.common.PagedListFragment -> fragment.refreshIfStale(maxAgeMs)
                }
                visit(fragment.childFragmentManager.fragments)
            }
        }
        supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.let { visit(it) }
        if (isTv && maxAgeMs <= 60_000L) {
            refreshStaleStreamLists(binding.navHostFragment, maxAgeMs)
        }
    }

    /** The focus on a stream of the multi-stream when it is anywhere else in this window. True when it was moved or waits for the streams. */
    private fun keepFocusInMultiStream(): Boolean {
        if (!tvMultiStreamShown) {
            return false
        }
        val focus = currentFocus
        val behind = focus == null || !focus.isShown || focus === binding.root || focus === binding.playerContainer ||
                focus.isInside(binding.navHostFragment) || focus.isInside(binding.navBarContainer)
        if (!behind) {
            return false
        }
        (supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG) as? com.github.andreyasadchy.xtra.ui.player.MultiStreamFragment)?.requestTileFocus()
        return true
    }

    private var restoringPlayerFocus = false
    private val keepFocusInPlayerAction = Runnable {
        if (!isFinishing && !isDestroyed) {
            keepFocusInPlayer()
        }
    }

    private fun hasOpenDialogFragment(): Boolean {
        fun anyDialog(fm: FragmentManager): Boolean {
            return fm.fragments.any { f ->
                f.isAdded && ((f is androidx.fragment.app.DialogFragment && f.showsDialog && f.dialog?.isShowing == true) || anyDialog(f.childFragmentManager))
            }
        }
        return anyDialog(supportFragmentManager)
    }

    /** Keeps focus inside the maximized TV player when focus drifted behind onto browsing screens or containers. */
    private fun keepFocusInPlayer(): Boolean {
        if (!isTv || tvMultiStreamShown || restoringPlayerFocus || !hasWindowFocus() || hasOpenDialogFragment()) {
            return false
        }
        if (binding.navHostFragment.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            return false
        }
        val player = playerFragment as? PlayerFragment ?: return false
        if (!player.isMaximizedPlayer() || player.isRemoving || player.isDetached) {
            return false
        }
        val focus = currentFocus
        val lostOrBehind = focus == null || !focus.isShown ||
            focus === binding.root || focus === binding.playerContainer ||
            focus.isInside(binding.navHostFragment) || focus.isInside(binding.navBarContainer) ||
            !focus.isInside(binding.playerContainer)
        if (!lostOrBehind && player.hasValidTvFocus()) {
            return false
        }
        restoringPlayerFocus = true
        return try {
            player.restoreTvFocusIfLost()
        } finally {
            restoringPlayerFocus = false
        }
    }

    /** Back from a multi-stream: the next streams to start when the one started ends at once (20 s). */
    private var multiStreamFallback: List<Stream> = emptyList()
    private var multiStreamFallbackLogin: String? = null
    private var multiStreamFallbackAt = 0L

    fun startStreamFromMultiStream(stream: Stream, fallback: List<Stream>) {
        multiStreamFallback = fallback
        multiStreamFallbackLogin = stream.channelLogin
        multiStreamFallbackAt = System.currentTimeMillis()
        startStream(stream, peekHud = true)
    }

    /** The stream [login] ended just after a back from a multi-stream: the next choice, null when there is none. */
    fun takeMultiStreamFallback(login: String?): Stream? {
        if (login == null || login != multiStreamFallbackLogin || System.currentTimeMillis() - multiStreamFallbackAt > 20_000L) {
            return null
        }
        val next = multiStreamFallback.firstOrNull() ?: return null
        multiStreamFallback = multiStreamFallback.drop(1)
        multiStreamFallbackLogin = next.channelLogin
        multiStreamFallbackAt = System.currentTimeMillis()
        return next
    }

    /** Whether [stream] is live, asked to Twitch (null: no answer). */
    suspend fun isStreamLive(stream: Stream): Boolean? {
        val id = stream.channelId ?: return null
        return loadLiveStreams(listOf(id))?.containsKey(id)
    }

    fun isMultiStreamOpen() = supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG) != null

    fun addStreamToMultiStream(stream: Stream): Boolean {
        val multi = supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG) as? com.github.andreyasadchy.xtra.ui.player.MultiStreamFragment ?: return false
        return multi.addStreamFromAlert(stream)
    }

    fun closeMultiStream() {
        supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        tvMultiStreamShown = false
        setMainContentFocusable(true)
        if (playerFragment == null) {
            refreshVisibleScreensIfStale(60_000L)
        }
    }

    fun showStreamSwitcher() {
        // shown at once with the list in memory, it fills in by itself if a newer list comes; tabs above it for the
        // other lists (followed, popular, languages, category), OK held opens a stream in a small window
        lifecycleScope.launch {
            val rows = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.rows(this@MainActivity, currentPlayingStream())
            TvStreamSwitcher.show(
                this@MainActivity,
                streamQueue,
                currentStreamLogin,
                rows = rows,
                onPip = { openTvPip(it) },
                source = streamQueueSource,
            )
            refreshQueueIfOld()
        }
    }

    private var previousWatchedStream: Stream? = null

    fun hasLastWatchedStream(): Boolean {
        val prev = previousWatchedStream ?: return false
        val prevLogin = prev.channelLogin?.takeIf { it.isNotBlank() } ?: return false
        val curLogin = currentStreamLogin?.takeIf { playerFragment != null }
        return !prevLogin.equals(curLogin, ignoreCase = true)
    }

    fun switchToLastWatchedStream(): Boolean {
        val target = previousWatchedStream
        val targetLogin = target?.channelLogin?.takeIf { it.isNotBlank() }
        val curLogin = currentStreamLogin?.takeIf { playerFragment != null }
        if (target == null || targetLogin == null || targetLogin.equals(curLogin, ignoreCase = true)) {
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, R.string.tv_no_last_stream, android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        val label = target.channelName?.takeIf { it.isNotBlank() } ?: targetLogin
        startStream(target, peekHud = true)
        com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, label, android.widget.Toast.LENGTH_SHORT, R.drawable.tv_ic_swap).show()
        return true
    }

    private fun openSearchTabWithQuery(query: String?) {
        if (tvMultiStreamShown) {
            closeMultiStream()
        }
        if ((playerFragment as? PlayerFragment)?.isMaximizedPlayer() == true) {
            leavePlayerForBrowsing()
        }
        if (!query.isNullOrBlank()) {
            com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment.pendingExternalQuery = query
        }
        if (isTv) {
            openTvSection(R.id.searchPagerFragment)
            binding.root.post {
                (currentVisibleNavFragment() as? com.github.andreyasadchy.xtra.ui.search.SearchPagerFragment)?.consumePendingExternalQuery()
            }
        } else {
            runCatching { navController.navigate(R.id.searchPagerFragment) }
        }
    }

    private fun openExternalSearch(query: String?, autoPlayLiveMatch: Boolean) {
        if (autoPlayLiveMatch && !query.isNullOrBlank()) {
            val clean = query.trim()
            val norm = clean.lowercase().replace(" ", "")
            lifecycleScope.launch {
                val matched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val app = application as XtraApp
                        val ds = com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource(
                            query = clean,
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(this@MainActivity),
                            graphQLRepository = app.xtraModule.graphQLRepository,
                            helixHeaders = TwitchApiHelper.getHelixHeaders(this@MainActivity),
                            helixRepository = app.xtraModule.helixRepository,
                            enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        )
                        val res = ds.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 8, false))
                        val list = (res as? androidx.paging.PagingSource.LoadResult.Page)?.data.orEmpty()
                        list.firstOrNull { s ->
                            s.channelLogin?.lowercase() == norm ||
                                s.channelName?.lowercase()?.replace(" ", "") == norm
                        } ?: list.firstOrNull { s ->
                            s.channelLogin?.lowercase()?.startsWith(norm) == true ||
                                s.channelName?.lowercase()?.replace(" ", "")?.startsWith(norm) == true
                        }
                    }.getOrNull()
                }
                if (matched != null && !matched.channelLogin.isNullOrBlank()) {
                    if (tvMultiStreamShown) {
                        closeMultiStream()
                    }
                    startStream(matched, peekHud = true)
                } else {
                    openSearchTabWithQuery(clean)
                }
            }
            return
        }
        openSearchTabWithQuery(query)
    }

    fun startStream(stream: Stream, queue: List<Stream>? = null, queueSource: (() -> androidx.paging.PagingSource<Int, Stream>)? = null, peekHud: Boolean = false) {
        currentClipId = null
        // TV: the info of the stream for a moment each time a stream starts (from the home, a list, a switch...)
        com.github.andreyasadchy.xtra.ui.player.PlayerFragment.peekHudImmediateSwitch =
            isTv && (playerFragment as? PlayerFragment)?.isMaximizedScreen() == true
        com.github.andreyasadchy.xtra.ui.player.PlayerFragment.peekHudOnStart = isTv
        // the time of the start of the stream counts from here (debug builds), and its access of Twitch is asked
        // at once, while the player opens
        com.github.andreyasadchy.xtra.ui.player.TvLoadTimer.start()
        com.github.andreyasadchy.xtra.ui.player.TvStreamPrefetch.start(this, stream.channelLogin)
        if (playerFragment == null || currentStreamLogin != stream.channelLogin) {
            com.github.andreyasadchy.xtra.ui.tv.TvStats.addStream(this)
        }
        if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER) {
            // the picture of the stream instead of a black screen while it loads: the newest one there is
            // a thumbnail Twitch has not made yet is replaced by the picture of the channel (TvThumbnails)
            com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.remember(stream)
            com.github.andreyasadchy.xtra.ui.player.PlayerFragment.pendingLoadingPicture = com.github.andreyasadchy.xtra.ui.player.PlayerFragment.Companion.LoadingPicture(
                stream.channelLogin,
                stream.thumbnail?.takeUnless { stream.thumbnailURL.isNullOrBlank() },
                stream.channelImage,
            )
        }
        if (queue != null) {
            streamQueue = queue
            streamQueueSource = queueSource
            queueRefreshedAt = System.currentTimeMillis()
            queueRefreshJob?.cancel()
            startQueueCheck()
        }
        if (isTv) {
            tvPip.syncWithMainStream(stream.channelLogin)
        }
        val outgoing = currentPlayingStream() ?: currentStream
        if (outgoing != null &&
            !outgoing.channelLogin.isNullOrBlank() &&
            !outgoing.channelLogin.equals(stream.channelLogin, ignoreCase = true)
        ) {
            previousWatchedStream = outgoing
        }
        currentStream = stream
        currentStreamLogin = stream.channelLogin
        if (isTv) {
            com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.recordLiveStreamSession(
                context = this,
                channelId = stream.channelId,
                channelLogin = stream.channelLogin,
                streamId = stream.id,
                streamCreatedAt = stream.createdAt,
            )
            syncTvPlaybackState()
        }
        dvrStream = null
        dvrChatVideoId = null
        // TV, no player open: the playback service starts as soon as the stream is saved, while the screen of the
        // player is made (it binds to it once ready, the service starts only once)
        val startServiceEarly = isTv && playerFragment == null && prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER
        // TV, a stream already playing full screen: its last picture stays on the screen until the picture of the next one
        // comes (never a black screen), the next one plays in the same service, only the screen is made again
        if (isTv) {
            (playerFragment as? PlayerFragment)?.captureSwitchFrame()
        }
        val keptService = if (isTv) (playerFragment as? ExoPlayerFragment)?.handOverForStreamSwitch() else null
        if (keptService == null) {
            (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        }
        com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.pendingDvrResume = null
        val state = PlaybackState(
            type = BasePlaybackService.STREAM,
            streamId = stream.id,
            channelId = stream.channelId,
            channelLogin = stream.channelLogin,
            channelName = stream.channelName,
            channelImage = stream.channelImage,
            gameId = stream.gameId,
            gameSlug = stream.gameSlug,
            gameName = stream.gameName,
            title = stream.title,
            thumbnail = stream.thumbnail,
            createdAt = stream.createdAt,
            viewerCount = stream.viewerCount,
        )
        BasePlaybackService.pendingImmediatePlaybackState = state
        viewModel.savePlaybackState(state) {
            if (keptService != null) {
                keptService.startSwitchedStream()
            } else if (startServiceEarly && playerFragment != null && currentStreamLogin == stream.channelLogin) {
                startService(Intent(this, com.github.andreyasadchy.xtra.ui.player.ExoPlayerService::class.java).apply {
                    action = com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.INTENT_START
                })
            }
        }
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        // the player screen knows at once that it plays a live stream (left / right switch streams), before its service is there
        fragment.arguments = (fragment.arguments ?: Bundle()).apply {
            putBoolean(com.github.andreyasadchy.xtra.ui.player.PlayerFragment.KEY_STREAM, true)
        }
        startPlayer(fragment)
        prepareNeighborStreams()
    }

    private var neighborJob: kotlinx.coroutines.Job? = null

    /**
     * TV: loads the pictures shown while a stream loads (its thumbnail of this minute, else the picture of its channel) of
     * the 2 streams on each side of the one watched, into the cache the loading picture reads first.
     */
    private fun preloadNeighborPictures(login: String) {
        val queue = streamQueue
        val index = queue.indexOfFirst { it.channelLogin == login }
        if (index < 0 || queue.size < 2) {
            return
        }
        val neighbors = listOf(-1, 1, -2, 2).map { queue[Math.floorMod(index + it, queue.size)] }
            .filter { it.channelLogin != login }
            .distinctBy { it.channelLogin }
        val loader = coil3.SingletonImageLoader.get(this)
        neighbors.forEach { stream ->
            // a thumbnail Twitch has not made yet is replaced by the picture of the channel (TvThumbnails)
            com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.remember(stream)
            val thumbnail = stream.thumbnail?.takeUnless { stream.thumbnailURL.isNullOrBlank() }
            listOfNotNull(
                com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.withStamp(thumbnail, com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.minute()),
                stream.channelImage,
            ).forEach { url ->
                loader.enqueue(coil3.request.ImageRequest.Builder(this).data(url).build())
            }
        }
    }

    /**
     * TV: while a stream plays, the access of Twitch to the streams of left / right is asked in the background (a few
     * seconds after the start, again every 90 s), switching to them does not wait for it.
     */
    private fun prepareNeighborStreams() {
        neighborJob?.cancel()
        if (!isTv) {
            return
        }
        val login = currentStreamLogin ?: return
        neighborJob = lifecycleScope.launch {
            // the pictures of the 2 streams on each side first (small, quick): a switch to them shows their picture at
            // once, like a stream already seen
            kotlinx.coroutines.delay(1500L)
            preloadNeighborPictures(login)
            kotlinx.coroutines.delay(2500L)
            var first = true
            while (currentStreamLogin == login && playerFragment != null) {
                val queue = streamQueue
                val index = queue.indexOfFirst { it.channelLogin == login }
                // the thumbnails change each minute: loaded again with the access of the streams
                if (!first) {
                    preloadNeighborPictures(login)
                }
                first = false
                if (index >= 0 && queue.size > 1) {
                    com.github.andreyasadchy.xtra.ui.player.TvStreamPrefetch.prepareNeighbors(
                        this@MainActivity,
                        listOf(
                            queue[Math.floorMod(index - 1, queue.size)].channelLogin,
                            queue[Math.floorMod(index + 1, queue.size)].channelLogin,
                        ).filter { it != login }
                    )
                }
                kotlinx.coroutines.delay(90_000L)
            }
        }
    }

    fun startVideo(video: Video, offset: Long?, ignoreSavedPosition: Boolean = false, qualities: String? = null) {
        currentClipId = null
        if (video.id != null) {
            if (isTv) {
                if (video.subOnly || com.github.andreyasadchy.xtra.ui.tv.TvSubOnlyVodCache.isSubOnly(video.id)) {
                    com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.markSubOnly(video.id)
                }
                com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.onExplicitVodOpened(this, video.id)
            }
            com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.pendingDvrResume = null
            currentStream = null
            dvrStream = null
            dvrChatVideoId = null
            streamQueue = emptyList()
            streamQueueSource = null
            currentStreamLogin = null
            queueCheckJob?.cancel()
            queueRefreshJob?.cancel()
            queueExtendJob?.cancel()
            neighborJob?.cancel()
            if (isTv) {
                tvPip.syncWithMainStream(null)
                syncTvPlaybackState()
            }
        }
        (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        val state = PlaybackState(
            type = BasePlaybackService.VIDEO,
            videoId = video.id,
            channelId = video.channelId,
            channelLogin = video.channelLogin,
            channelName = video.channelName,
            channelImage = video.channelImage,
            gameId = video.gameId,
            gameSlug = video.gameSlug,
            gameName = video.gameName,
            title = video.title,
            thumbnail = video.thumbnail,
            createdAt = video.createdAt,
            durationSeconds = video.durationSeconds,
            videoType = video.type,
            videoAnimatedPreviewURL = video.animatedPreviewURL,
            position = offset,
            qualities = qualities,
        )
        BasePlaybackService.pendingImmediatePlaybackState = state
        viewModel.savePlaybackState(state)
        if (ignoreSavedPosition && prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            video.id?.toLongOrNull()?.let { id ->
                viewModel.saveVideoPosition(id, offset ?: 0)
            }
            if (isTv && (offset == null || offset == 0L) && !video.id.isNullOrBlank()) {
                com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.pushPositionAsync(
                    context = this,
                    xtraModule = (application as com.github.andreyasadchy.xtra.XtraApp).xtraModule,
                    videoId = video.id,
                    positionMs = 0L,
                    force = true,
                )
            }
        }
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        fragment.arguments = (fragment.arguments ?: Bundle()).apply {
            putBoolean(com.github.andreyasadchy.xtra.ui.player.PlayerFragment.KEY_VIDEO, true)
            if (video.id == null && dvrStream != null) {
                putBoolean(com.github.andreyasadchy.xtra.ui.player.PlayerFragment.KEY_DVR, true)
            }
        }
        startPlayer(fragment)
    }

    /** Queue of clips from the grid the current clip was opened from, for TV autoplay and channel up/down. */
    var clipQueue: List<Clip> = emptyList()
        private set
    private var currentClipId: String? = null

    fun playNextClip(offset: Int = 1, wrap: Boolean = false): Boolean {
        val queue = clipQueue
        val id = currentClipId ?: return false
        if (queue.size < 2) return false
        val index = queue.indexOfFirst { it.id == id }
        if (index < 0) return false
        val nextIndex = if (wrap) Math.floorMod(index + offset, queue.size) else index + offset
        val next = queue.getOrNull(nextIndex) ?: return false
        startClip(next)
        return true
    }

    fun startClip(clip: Clip, queue: List<Clip>? = null) {
        if (queue != null) {
            clipQueue = queue
        }
        com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.pendingDvrResume = null
        currentClipId = clip.id
        currentStream = null
        currentStreamLogin = null
        if (isTv) {
            com.github.andreyasadchy.xtra.ui.player.PlayerFragment.peekHudOnStart = true
            tvPip.syncWithMainStream(null)
            syncTvPlaybackState()
        }
        dvrStream = null
        dvrChatVideoId = null
        (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        val state = PlaybackState(
            type = BasePlaybackService.CLIP,
            videoId = clip.videoId,
            clipId = clip.id,
            channelId = clip.channelId,
            channelLogin = clip.channelLogin,
            channelName = clip.channelName,
            channelImage = clip.channelImage,
            gameId = clip.gameId,
            gameSlug = clip.gameSlug,
            gameName = clip.gameName,
            title = clip.title,
            thumbnail = clip.thumbnail,
            createdAt = clip.createdAt,
            durationSeconds = clip.durationSeconds,
            videoOffsetSeconds = clip.videoOffsetSeconds,
            videoCreatedAt = clip.videoCreatedAt,
            videoAnimatedPreviewURL = clip.videoAnimatedPreviewURL,
        )
        BasePlaybackService.pendingImmediatePlaybackState = state
        viewModel.savePlaybackState(state)
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        startPlayer(fragment)
    }

    fun startOfflineVideo(video: OfflineVideo, offset: Long? = null) {
        com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.pendingDvrResume = null
        currentClipId = null
        currentStream = null
        currentStreamLogin = null
        if (isTv) {
            tvPip.syncWithMainStream(null)
            syncTvPlaybackState()
        }
        dvrStream = null
        dvrChatVideoId = null
        (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        val state = PlaybackState(
            type = BasePlaybackService.OFFLINE_VIDEO,
            offlineVideoId = video.id,
            channelId = video.channelId,
            channelLogin = video.channelLogin,
            channelName = video.channelName,
            channelImage = video.channelLogo,
            gameId = video.gameId,
            gameSlug = video.gameSlug,
            gameName = video.gameName,
            title = video.name,
            createdAt = video.uploadDate?.toString(),
            videoCreatedAt = video.videoCreatedAt,
        )
        BasePlaybackService.pendingImmediatePlaybackState = state
        viewModel.savePlaybackState(state)
        if (offset != null && prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            viewModel.saveOfflineVideoPosition(video.id, offset)
        }
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }.apply {
            arguments = Bundle().apply {
                putBoolean(PlayerFragment.KEY_OFFLINE, true)
            }
        }
        startPlayer(fragment)
    }

//Player methods

    private var lastContentFocus: View? = null
    private var lastFocusGrid: RecyclerView? = null
    private var lastFocusPosition = RecyclerView.NO_POSITION

    /**
     * Blocks D-pad focus from reaching the browsing UI hidden behind the maximized player,
     * and restores the previously focused item when the player is minimized or closed.
     */
    fun setMainContentFocusable(focusable: Boolean) {
        // the previews of the lists behind a player stop, with their sound
        com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.suspended = !focusable || tvMultiStreamShown
        if (focusable && tvMultiStreamShown) {
            // the player closed for a multi-stream: the screens behind stay out of reach, the streams keep the focus
            return
        }
        if (focusable) {
            if (isTv) {
                binding.playerContainer.isFocusable = false
                binding.playerContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            if (binding.navHostFragment.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                return
            }
            binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            binding.navBarContainer.descendantFocusability = if (isTvMenuShown || !isCompactRailEnabled()) {
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            } else {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            val lastFocus = lastContentFocus
            lastContentFocus = null
            val grid = lastFocusGrid
            val position = lastFocusPosition
            lastFocusGrid = null
            currentTvHome()?.let { home ->
                home.focusLastCard()
                return
            }
            currentTvSettings()?.let { settings ->
                settings.requestContentFocus()
                settings.view?.requestFocus()
                return
            }
            if (lastFocus != null && lastFocus.isAttachedToWindow && lastFocus.isShown && lastFocus.requestFocus()) {
                return
            }
            // the card was recycled or the list was refreshed while playing: focus the card at the same position
            if (grid != null && grid.isAttachedToWindow && position != RecyclerView.NO_POSITION) {
                val count = grid.adapter?.itemCount ?: 0
                if (count > 0) {
                    val target = position.coerceAtMost(count - 1)
                    val holder = grid.findViewHolderForAdapterPosition(target)
                    if (holder != null && holder.itemView.requestFocus()) {
                        return
                    }
                    grid.scrollToPosition(target)
                    grid.post {
                        if (grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() != true) {
                            ensureFocus(force = true)
                        }
                    }
                    return
                }
            }
            if (!binding.navHostFragment.requestFocus()) {
                ensureFocus(force = true)
            }
        } else {
            if (isTv) {
                binding.playerContainer.isFocusable = false
                binding.playerContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            if (binding.navHostFragment.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                return
            }
            // remember which card was focused, by its position, to come back to it when the player closes
            lastFocusGrid = null
            lastFocusPosition = RecyclerView.NO_POSITION
            var view: View? = lastContentFocus
            var child: View? = null
            while (view != null && view !== binding.navHostFragment) {
                val parent = view.parent as? View
                if (parent is RecyclerView) {
                    lastFocusGrid = parent
                    lastFocusPosition = parent.getChildAdapterPosition(view)
                    break
                }
                child = view
                view = parent
            }
            binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            binding.navBarContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun View.isInside(parent: View): Boolean {
        var view: View? = this
        while (view != null) {
            if (view == parent) {
                return true
            }
            view = view.parent as? View
        }
        return false
    }

    private fun startPlayer(fragment: Fragment) {
        playerFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.playerContainer, fragment).commit()
        viewModel.isPlayerOpened = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
        }
    }

    /** Leaves the player for another screen: on TV as the back setting says, else the player is minimized. */
    private fun leavePlayerForBrowsing() {
        (playerFragment as? PlayerFragment)?.let {
            if (isTv) it.leaveForBrowsing() else it.minimize()
        }
    }

    fun updateTvNowPlaying() {
        if (isTv) {
            tvMenuState.nowPlaying = (playerFragment as? PlayerFragment)?.takeIf { !it.isMaximizedPlayer() }?.getTvNowPlaying()
        }
    }

    fun reopenTvMiniPlayer() {
        val player = (playerFragment as? PlayerFragment)?.takeIf { !it.isRemoving && !it.isDetached } ?: return
        if (player.isMaximizedPlayer()) return
        hideTvMenu()
        player.maximize()
    }

    fun stopTvPlayback() {
        (playerFragment as? PlayerFragment)?.let {
            it.close()
            closePlayer()
        }
    }

    /** The streamer page, on a given tab ("1": the videos). */
    fun openTvChannel(stream: Stream, tab: String?) {
        com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragment.pendingTab = tab
        hideTvMenu()
        navController.navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = stream.channelId,
                channelLogin = stream.channelLogin,
                channelName = stream.channelName,
                channelImage = stream.channelImage,
                streamId = stream.id,
            )
        )
    }

    fun openTvGame(gameId: String?, gameSlug: String?, gameName: String?) {
        hideTvMenu()
        navController.navigate(
            if (prefs.getBoolean(C.UI_GAME_PAGER, true)) {
                GamePagerFragmentDirections.actionGlobalGamePagerFragment(gameId = gameId, gameSlug = gameSlug, gameName = gameName)
            } else {
                GameMediaFragmentDirections.actionGlobalGameMediaFragment(gameId = gameId, gameSlug = gameSlug, gameName = gameName)
            }
        )
    }

    fun showTvStreamActions(stream: Stream, last: List<com.github.andreyasadchy.xtra.ui.tv.TvStreamActions.Extra> = emptyList()) {
        com.github.andreyasadchy.xtra.ui.tv.TvStreamActions.show(this, stream, last = last)
    }

    /** Debug builds: the error of the last crash, once, so that it can be photographed and sent. */
    private fun showLastCrash() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val file = java.io.File(filesDir, com.github.andreyasadchy.xtra.XtraApp.CRASH_FILE)
        val text = try {
            file.takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        } ?: return
        file.delete()
        getAlertDialogBuilder()
            .setTitle("Crash")
            .setMessage(text.take(4000))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun currentVisibleNavFragment(): Fragment? {
        val host = supportFragmentManager.findFragmentById(R.id.navHostFragment) ?: return null
        host.childFragmentManager.primaryNavigationFragment
            ?.takeIf { it !is androidx.fragment.app.DialogFragment && it.isVisible && !it.isRemoving }
            ?.let { return it }
        return host.childFragmentManager.fragments
            .lastOrNull { it !is androidx.fragment.app.DialogFragment && it.isVisible && !it.isRemoving }
    }

    /** The home, when it is the screen shown. */
    private fun currentTvHome(): com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment? {
        return currentVisibleNavFragment() as? com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment
    }

    private fun currentTvStats(): com.github.andreyasadchy.xtra.ui.tv.TvStatsFragment? {
        return currentVisibleNavFragment() as? com.github.andreyasadchy.xtra.ui.tv.TvStatsFragment
    }

    private fun currentTvSettings(): com.github.andreyasadchy.xtra.ui.settings.TvSettingsFragment? {
        return currentVisibleNavFragment() as? com.github.andreyasadchy.xtra.ui.settings.TvSettingsFragment
    }

    private var tvPrewarmScheduled = false

    /**
     * Pre-warms unvisited TV side-rail root sections one by one during main-thread idle windows
     * so even the first hover/visit to any category is instant (`0 ms` layout inflation).
     */
    private fun scheduleTvRootPrewarm() {
        if (!isTv || tvPrewarmScheduled || !::navController.isInitialized) return
        val tvNavHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as? com.github.andreyasadchy.xtra.ui.tv.TvNavHostFragment ?: return
        val navigator = tvNavHost.keepStateNavigator ?: return
        tvPrewarmScheduled = true
        val candidates = listOf(
            R.id.tvHomeFragment,
            R.id.rootGamesFragment,
            R.id.rootTopFragment,
            R.id.followPagerFragment,
            R.id.followMediaFragment,
            R.id.tvStatsFragment,
            R.id.tvSettingsFragment,
            R.id.savedPagerFragment,
            R.id.savedMediaFragment,
            R.id.searchPagerFragment,
        ).filter { id -> binding.navBar.menu.findItem(id) != null }
        val queue = ArrayDeque(candidates)
        fun pump(delayMs: Long) {
            if (queue.isEmpty() || isFinishing || isDestroyed) return
            binding.root.postDelayed({
                if (queue.isEmpty() || isFinishing || isDestroyed) return@postDelayed
                android.os.Looper.myQueue().addIdleHandler {
                    if (queue.isEmpty() || isFinishing || isDestroyed) return@addIdleHandler false
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                        isTvPlayerOverContent() ||
                        tvMenuPendingId != 0 ||
                        idleDurationMs() < 700L
                    ) {
                        pump(900L)
                        return@addIdleHandler false
                    }
                    var warmed = false
                    while (queue.isNotEmpty() && !warmed) {
                        val nextId = queue.removeFirst()
                        if (navController.currentDestination?.id != nextId) {
                            warmed = navigator.prewarmRoot(nextId, navController.graph)
                        }
                    }
                    if (queue.isNotEmpty()) {
                        pump(650L)
                    }
                    false
                }
            }, delayMs)
        }
        pump(1400L)
    }

    fun syncTvPlaybackState() {
        if (!isTv) return
        val mainLogin = if (playerFragment != null || currentStream != null) currentStreamLogin else null
        val pipLogins = tvPipActiveStreams().map { it.channelLogin }
        com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.update(mainLogin, pipLogins)
    }

    fun closePlayer(focusHome: Boolean = true) {
        binding.root.removeCallbacks(keepFocusInPlayerAction)
        currentClipId = null
        val closingStream = currentPlayingStream() ?: currentStream
        if (closingStream != null && !closingStream.channelLogin.isNullOrBlank()) {
            previousWatchedStream = closingStream
        }
        currentStream = null
        currentStreamLogin = null
        neighborJob?.cancel()
        com.github.andreyasadchy.xtra.ui.player.TvStreamPrefetch.clear()
        closeTvPip()
        val fragmentToRemove = supportFragmentManager.findFragmentById(R.id.playerContainer)
        playerFragment = null
        syncTvPlaybackState()
        setMainContentFocusable(true)
        // back on the home: the card of the stream that was watched, else the first stream (not when a multi-stream
        // takes the place of the player: its streams keep the focus)
        if (focusHome) {
            currentTvHome()?.focusLastCard()
        }
        if (fragmentToRemove != null) {
            val tx = supportFragmentManager.beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .remove(fragmentToRemove)
            if (supportFragmentManager.isStateSaved) {
                tx.commitAllowingStateLoss()
            } else {
                tx.commit()
            }
        }
        updateTvNowPlaying()
        viewModel.isPlayerOpened = false
        if (!tvMultiStreamShown) {
            refreshVisibleScreensIfStale(60_000L)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
        viewModel.sleepTimer?.cancel()
        viewModel.sleepTimerEndTime = 0L
    }

    private fun restorePlayerFragment() {
        if (playerFragment == null) {
            playerFragment = supportFragmentManager.findFragmentById(R.id.playerContainer) as? PlayerFragment
            if (playerFragment == null) {
                if (isTv) {
                    if (intent?.action == INTENT_OPEN_PLAYER && prefs.getString(C.TV_KEEP_SOUND_ON_LEAVE, "never") != "never") {
                        if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER) {
                            viewModel.getPlaybackStates()
                        }
                    } else {
                        com.github.andreyasadchy.xtra.ui.tv.TvPlaybackState.update(null, emptyList())
                        viewModel.deletePlaybackStates()
                    }
                } else if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER) {
                    viewModel.getPlaybackStates()
                }
            }
        } else {
            if (viewModel.isPlayerOpened && (playerFragment as? PlayerFragment)?.secondViewIsHidden() == true && prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)) {
                (playerFragment as? PlayerFragment)?.maximize()
            }
        }
    }

    fun setSleepTimer(duration: Long) {
        viewModel.sleepTimer?.cancel()
        viewModel.sleepTimerEndTime = 0L
        if (duration > 0L) {
            viewModel.sleepTimer = Timer().apply {
                schedule(duration) {
                    lifecycleScope.launch {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            (playerFragment as? PlayerFragment)?.also {
                                it.minimize()
                                it.close()
                                closePlayer()
                            }
                            if (prefs.getBoolean(C.SLEEP_TIMER_LOCK, false)) {
                                if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
                                    try {
                                        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                                    } catch (e: SecurityException) {

                                    }
                                }
                            }
                        } else {
                            withStarted {
                                (playerFragment as? PlayerFragment)?.also {
                                    it.minimize()
                                    it.close()
                                    closePlayer()
                                }
                            }
                        }
                    }
                }
            }
            viewModel.sleepTimerEndTime = System.currentTimeMillis() + duration
        }
    }

    fun getSleepTimerTimeLeft(): Long {
        return viewModel.sleepTimerEndTime - System.currentTimeMillis()
    }

    fun downloadStream(filesDir: String, id: String?, title: String?, createdAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModel.downloadStream(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), filesDir, id, title, createdAt, channelId, channelLogin, channelName, channelImage, thumbnail, gameId, gameSlug, gameName, downloadPath, quality, downloadChat, downloadChatEmotes, wifiOnly)
    }

    fun downloadVideo(filesDir: String, id: String?, title: String?, createdAt: String?, type: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, from: Long, to: Long, downloadChat: Boolean, downloadChatEmotes: Boolean, playlistToFile: Boolean, wifiOnly: Boolean) {
        viewModel.downloadVideo(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), filesDir, id, title, createdAt, type, channelId, channelLogin, channelName, channelImage, thumbnail, gameId, gameSlug, gameName, url, downloadPath, quality, from, to, downloadChat, downloadChatEmotes, playlistToFile, wifiOnly)
    }

    fun downloadClip(filesDir: String, clipId: String?, title: String?, createdAt: String?, durationSeconds: Int?, videoId: String?, videoOffsetSeconds: Int?, videoCreatedAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelImage: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModel.downloadClip(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), filesDir, clipId, title, createdAt, durationSeconds, videoId, videoOffsetSeconds, videoCreatedAt, channelId, channelLogin, channelName, channelImage, thumbnail, gameId, gameSlug, gameName, url, downloadPath, quality, downloadChat, downloadChatEmotes, wifiOnly)
    }

    fun popFragment() {
        navController.navigateUp()
    }

    private fun parseNavigationTabList(): List<String> {
        return prefs.getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
            val defaultTabs = C.DEFAULT_NAVIGATION_TAB_LIST.split(',')
            if (tabPref != null) {
                val list = tabPref.split(',').filter { item ->
                    defaultTabs.find { it.first() == item.first() } != null
                }.toMutableList()
                defaultTabs.forEachIndexed { index, item ->
                    if (list.find { it.first() == item.first() } == null) {
                        list.add(index, item)
                    }
                }
                list
            } else defaultTabs
        }
    }

    private fun populateNavBarMenu(tabList: List<String>) {
        binding.navBar.menu.clear()
        with(binding.navBar) {
            if (isTv) {
                menu.add(Menu.NONE, R.id.searchPagerFragment, Menu.NONE, R.string.search).setIcon(R.drawable.baseline_search_black_24)
                menu.add(Menu.NONE, R.id.tvHomeFragment, Menu.NONE, R.string.tv_home).setIcon(R.drawable.baseline_home_black_24)
            }
            if (isTv || tabList.any { it.split(':')[2] != "0" }) {
                tabList.forEach {
                    val split = it.split(':')
                    val key = split[0]
                    val enabled = split[2] != "0"
                    if (enabled) {
                        when (key) {
                            "0" -> menu.add(Menu.NONE, R.id.rootGamesFragment, Menu.NONE, R.string.games).setIcon(R.drawable.ic_games_black_24dp)
                            "1" -> menu.add(Menu.NONE, R.id.rootTopFragment, Menu.NONE, R.string.popular).setIcon(R.drawable.ic_trending_up_black_24dp)
                            "2" -> {
                                if (prefs.getBoolean(C.UI_FOLLOW_PAGER, true)) {
                                    menu.add(Menu.NONE, R.id.followPagerFragment, Menu.NONE, R.string.following).setIcon(R.drawable.ic_favorite_black_24dp)
                                } else {
                                    menu.add(Menu.NONE, R.id.followMediaFragment, Menu.NONE, R.string.following).setIcon(R.drawable.ic_favorite_black_24dp)
                                }
                            }
                            "3" -> {
                                if (prefs.getBoolean(C.UI_SAVED_PAGER, true)) {
                                    menu.add(Menu.NONE, R.id.savedPagerFragment, Menu.NONE, R.string.saved).setIcon(R.drawable.ic_file_download_black_24dp)
                                } else {
                                    menu.add(Menu.NONE, R.id.savedMediaFragment, Menu.NONE, R.string.saved).setIcon(R.drawable.ic_file_download_black_24dp)
                                }
                            }
                        }
                    }
                }
            } else {
                binding.navBarContainer.visibility = View.GONE
            }
            if (isTv && prefs.getBoolean(C.UI_FOLLOW_PAGER, true)) {
                val extras = prefs.getStringSet(C.TV_MENU_EXTRAS, null).orEmpty()
                for (extraKey in C.parseTvMenuExtrasOrder(prefs)) {
                    if (extraKey !in extras) continue
                    when (extraKey) {
                        "videos" -> menu.add(Menu.NONE, R.id.tvNavFollowedVideos, Menu.NONE, R.string.tv_menu_followed_videos).setIcon(R.drawable.tv_ic_video_library)
                        "channels" -> menu.add(Menu.NONE, R.id.tvNavFollowedChannels, Menu.NONE, R.string.tv_menu_followed_channels).setIcon(R.drawable.tv_ic_person)
                        "games" -> menu.add(Menu.NONE, R.id.tvNavFollowedGames, Menu.NONE, R.string.tv_menu_followed_games).setIcon(R.drawable.tv_ic_gamepad)
                    }
                }
            }
            if (isTv) {
                if (!prefs.getBoolean(C.TV_HIDE_MENU_STATS, false)) {
                    menu.add(Menu.NONE, R.id.tvStatsFragment, Menu.NONE, R.string.tv_nav_stats).setIcon(R.drawable.tv_ic_bar_chart)
                }
                menu.add(Menu.NONE, R.id.tvSettingsFragment, Menu.NONE, R.string.settings).setIcon(R.drawable.baseline_settings_black_24)
                menu.add(Menu.NONE, R.id.tvNavAccount, Menu.NONE, R.string.tv_account).setIcon(R.drawable.baseline_person_black_24)
            }
        }
    }

    private fun initNavigation() {
        navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        if (isTv) {
            onBackPressedDispatcher.addCallback(this, tvLeaveBackCallback)
            onBackPressedDispatcher.addCallback(this, tvMenuBackCallback)
            onBackPressedDispatcher.addCallback(this, tvGridBackCallback)
            navController.addOnDestinationChangedListener { _, destination, _ ->
                if (destination.id != R.id.channelPagerFragment &&
                    com.github.andreyasadchy.xtra.ui.tv.TvAmbient.clearChannelOverride(this@MainActivity)
                ) {
                    tvBaseBackgroundColor?.let { base ->
                        val restored = com.github.andreyasadchy.xtra.ui.tv.TvAmbient.color(this@MainActivity)
                        if (restored != null) {
                            com.github.andreyasadchy.xtra.ui.tv.TvAmbient.applyDynamicToView(binding.root, base, restored)
                        } else {
                            binding.root.background = com.github.andreyasadchy.xtra.ui.tv.TvAmbient.background(this@MainActivity, base)
                        }
                    }
                    tintTvCards(binding.navHostFragment, onlyVisible = true)
                    (playerFragment as? PlayerFragment)?.refreshTvMinimizedBorderColor()
                }
                updateTvMenuBackCallback()
                (playerFragment as? PlayerFragment)?.scheduleTvMinimizedGhostCheck()
                if (destination.id in tvRootDestinations && destination.id != R.id.followPagerFragment) {
                    tvMenuExtraSelected = null
                }
                val destId = destination.id
                binding.root.post {
                    tvMenuState.selected = if (destId == R.id.followPagerFragment) {
                        resolveFollowMenuEntryId()
                    } else {
                        tvMenuExtraSelected
                            ?: destId.takeIf { id -> tvMenuState.items.any { it.id == id } }
                            ?: binding.navBar.selectedItemId
                    }
                    currentVisibleNavFragment()?.view?.let { com.github.andreyasadchy.xtra.ui.view.TvHeader.refresh(it) }
                    (playerFragment as? PlayerFragment)?.scheduleTvMinimizedGhostCheck()
                }
            }
        }
        val tabList = parseNavigationTabList()
        navController.setGraph(navController.navInflater.inflate(R.navigation.nav_graph).also {
            val startOnFollowed = prefs.getString(C.UI_START_ON_FOLLOWED, "1")?.toIntOrNull() ?: 1
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()
            val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
            when {
                isTv -> {
                    val startPage = prefs.getString(C.TV_START_PAGE, "home")
                    val shown = tabList.any { tab -> tab.split(':').let { it[0] == startPage && it[2] != "0" } }
                    it.setStartDestination(
                        when {
                            !shown -> R.id.tvHomeFragment
                            startPage == "0" -> R.id.rootGamesFragment
                            startPage == "1" -> R.id.rootTopFragment
                            startPage == "2" -> R.id.followPagerFragment
                            startPage == "3" -> R.id.savedPagerFragment
                            else -> R.id.tvHomeFragment
                        }
                    )
                }
                (isLoggedIn && startOnFollowed < 2) || (!isLoggedIn && startOnFollowed == 0) || defaultItem == "2" -> {
                    if (prefs.getBoolean(C.UI_FOLLOW_PAGER, true)) {
                        it.setStartDestination(R.id.followPagerFragment)
                    } else {
                        it.setStartDestination(R.id.followMediaFragment)
                    }
                }
                defaultItem == "0" -> it.setStartDestination(R.id.rootGamesFragment)
                defaultItem == "3" -> {
                    if (prefs.getBoolean(C.UI_SAVED_PAGER, true)) {
                        it.setStartDestination(R.id.savedPagerFragment)
                    } else {
                        it.setStartDestination(R.id.savedMediaFragment)
                    }
                }
            }
        }, null)
        binding.navBar.apply {
            if (!prefs.getBoolean(C.UI_THEME_BOTTOM_NAV_COLOR, true) && prefs.getBoolean(C.UI_THEME_MATERIAL3, true)) {
                setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            }
            populateNavBarMenu(tabList)
            setupWithNavController(navController)
            if (isTv) {
                setupTvSideMenu()
                if (prefs.getBoolean(C.TV_HIDE_MENU_ON_LAUNCH, false)) {
                    val sideMenu = binding.navBarContainer
                    isTvMenuShown = false
                    updateTvMenuBackCallback()
                    if (isCompactRailEnabled()) {
                        tvMenuState.collapsed = true
                        sideMenu.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        sideMenu.alpha = 1f
                        sideMenu.visibility = View.VISIBLE
                    } else {
                        sideMenu.alpha = 0f
                        sideMenu.visibility = View.INVISIBLE
                        sideMenu.doOnLayout { if (!isTvMenuShown) it.translationX = -it.width.toFloat() }
                    }
                }
            }
            setOnItemSelectedListener {
                when (it.itemId) {
                    R.id.tvNavAccount -> {
                        onAccountClicked()
                        return@setOnItemSelectedListener false
                    }
                    R.id.tvNavFollowedVideos, R.id.tvNavFollowedChannels, R.id.tvNavFollowedGames -> {
                        openFollowedTab(
                            when (it.itemId) {
                                R.id.tvNavFollowedVideos -> "2"
                                R.id.tvNavFollowedChannels -> "3"
                                else -> "0"
                            },
                            it.itemId
                        )
                        return@setOnItemSelectedListener false
                    }
                }
                tvMenuExtraSelected = null
                if (isTv) {
                    return@setOnItemSelectedListener openTvSectionRoot(it.itemId)
                }
                NavigationUI.onNavDestinationSelected(it, navController)
                return@setOnItemSelectedListener true
            }
            setOnItemReselectedListener {
                if (tvMenuExtraSelected != null && it.itemId == R.id.followPagerFragment) {
                    // back to "Following" from one of its shortcuts: restore its full tab set in place
                    tvMenuExtraSelected = null
                    tvMenuState.selected = it.itemId
                    com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.clearStandalone = true
                    com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.requestedTab = null
                    val current = currentVisibleNavFragment()
                    if (current is com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment && current.selectMenuTab(null)) {
                        com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.clearStandalone = false
                    } else {
                        openTvSectionRoot(R.id.followPagerFragment)
                    }
                    return@setOnItemReselectedListener
                }
                if (isTv && it.itemId in tvRootDestinations &&
                    (tvMenuExtraSelected != null || navController.currentDestination?.id != it.itemId || navController.previousBackStackEntry != null)
                ) {
                    tvMenuExtraSelected = null
                    openTvSectionRoot(it.itemId)
                    return@setOnItemReselectedListener
                }
                if (!navController.popBackStack(it.itemId, false)) {
                    val currentFragment = currentVisibleNavFragment()
                    if (currentFragment is Scrollable) {
                        currentFragment.scrollToTop()
                    }
                    if (isTv) {
                        refreshVisibleScreensIfStale(5_000L)
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    /**
     * Display settings removed from the UI settings: the same for everyone (previews of the streams shown, round
     * pictures, compact numbers, uptime, tags, numbers of streams, tabs pages, scroll to top, time left, recent searches).
     */
    private fun forceDisplaySettings() {
        val on = listOf(
            C.UI_ROUND_USER_IMAGE, C.UI_TRUNCATE_VIEW_COUNT, C.UI_UPTIME, C.UI_TAGS, C.UI_BROADCASTERS_COUNT,
            C.UI_FOLLOW_PAGER, C.UI_SAVED_PAGER, C.UI_GAME_PAGER, C.UI_SCROLL_TOP, C.UI_BOOKMARK_TIME_LEFT, C.UI_STORE_RECENT_SEARCHES,
        )
        if (on.any { !prefs.getBoolean(it, true) } || prefs.getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
            prefs.edit {
                on.forEach { putBoolean(it, true) }
                putString(C.COMPACT_STREAMS, "disabled")
            }
        }
    }

    private fun migrateSettings() {
        val version = prefs.getInt(C.SETTINGS_VERSION, 0).let {
            if (it == 0 && !prefs.getBoolean(C.FIRST_LAUNCH2, true)) {
                when {
                    !prefs.getBoolean(C.FIRST_LAUNCH9, true) -> 8
                    !prefs.getBoolean(C.FIRST_LAUNCH8, true) -> 7
                    !prefs.getBoolean(C.FIRST_LAUNCH7, true) -> 6
                    !prefs.getBoolean(C.FIRST_LAUNCH6, true) -> 5
                    !prefs.getBoolean(C.FIRST_LAUNCH5, true) -> 4
                    !prefs.getBoolean(C.FIRST_LAUNCH3, true) -> 3
                    !prefs.getBoolean(C.FIRST_LAUNCH1, true) -> 2
                    else -> 1
                }
            } else {
                it
            }
        }
        if (version < 1) {
            prefs.edit {
                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels
                val chatWidth = ((if (height > width) height else width) * (30 / 100f)).toInt()
                putInt(C.LANDSCAPE_CHAT_WIDTH, chatWidth)
                if (resources.getBoolean(R.bool.isTablet)) {
                    putString(C.PORTRAIT_COLUMN_COUNT, "2")
                    putString(C.LANDSCAPE_COLUMN_COUNT, "3")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    putString(C.THEME, "4")
                }
            }
        }
        if (version < 3) {
            val langPref = prefs.getString(C.UI_LANGUAGE, "")
            if (!langPref.isNullOrBlank() && langPref != "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langPref))
            }
        }
        if (version < 4) {
            prefs.edit {
                if (prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp") == "kd1unb4b3q4t58fwlpcbzcbnm76a8fp" && prefs().getString(C.GQL_TOKEN2, null).isNullOrBlank()) {
                    putString(C.GQL_CLIENT_ID2, "ue6666qo983tsx6so1t0vnawi233wa")
                    putString(C.GQL_REDIRECT2, "https://www.twitch.tv/settings/connections")
                }
                putInt(C.SETTINGS_VERSION, 4)
            }
        }
        if (version < 5) {
            prefs.edit {
                if (prefs.getString(C.PLAYER_PROXY, "1")?.toIntOrNull() == 0) {
                    putBoolean(C.PLAYER_STREAM_PROXY, true)
                }
                putInt(C.SETTINGS_VERSION, 5)
            }
        }
        if (version < 6) {
            prefs.edit {
                when {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).none { it.hardwareAccelerated } -> {
                        putString(C.TOKEN_SUPPORTED_CODECS, "h264")
                    }
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_AV1, false, false).none { it.hardwareAccelerated } -> {
                        putString(C.TOKEN_SUPPORTED_CODECS, "h265,h264")
                    }
                }
                putInt(C.SETTINGS_VERSION, 6)
            }
        }
        if (version < 7) {
            prefs.edit {
                if (prefs.getString(C.UI_CUTOUT_MODE, "0") == "1") {
                    putBoolean(C.UI_DRAW_BEHIND_CUTOUTS, true)
                }
                putInt(C.SETTINGS_VERSION, 7)
            }
        }
        if (version < 8) {
            tokenPrefs().edit {
                putString(C.USER_ID, prefs.getString(C.USER_ID, null))
                putString(C.USERNAME, prefs.getString(C.USERNAME, null))
                putString(C.TOKEN, prefs.getString(C.TOKEN, null))
                putString(C.GQL_TOKEN2, prefs.getString(C.GQL_TOKEN2, null))
                putString(C.GQL_HEADERS, prefs.getString(C.GQL_HEADERS, null))
                putLong(C.INTEGRITY_EXPIRATION, prefs.getLong(C.INTEGRITY_EXPIRATION, 0))
            }
            prefs.edit {
                remove(C.USER_ID)
                remove(C.USERNAME)
                remove(C.TOKEN)
                remove(C.GQL_TOKEN)
                remove(C.GQL_TOKEN2)
                remove(C.GQL_HEADERS)
                remove(C.INTEGRITY_EXPIRATION)
                putInt(C.SETTINGS_VERSION, 8)
            }
        }
        if (version < 9) {
            prefs.edit {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    putBoolean(C.CHAT_USE_WEBP, false)
                    putString(C.CHAT_IMAGE_LIBRARY, "1")
                }
                putInt(C.SETTINGS_VERSION, 9)
            }
        }
        if (version < 10) {
            viewModel.deleteOldImages()
            prefs.edit {
                prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0")?.let {
                    if (it == "1") {
                        putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                    } else if (it == "2") {
                        putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                        putBoolean(C.PLAYER_BACKGROUND_AUDIO, false)
                    }
                }
                putInt(C.SETTINGS_VERSION, 10)
            }
        }
        if (version < 11) {
            prefs.edit {
                val tabs = prefs.getStringSet(C.UI_NAVIGATION_TABS, null)?.toSortedSet()
                val defaultPage = prefs.getString(C.UI_DEFAULT_PAGE, null)
                if (tabs != null || defaultPage != null) {
                    val set = tabs ?: setOf("0", "1", "2", "3")
                    val default = defaultPage ?: "1"
                    val list = "0:${if (default == "0") "1" else "0"}:${if (set.contains("0")) "1" else "0"}," +
                            "1:${if (default == "1") "1" else "0"}:${if (set.contains("1")) "1" else "0"}," +
                            "2:${if (default == "2") "1" else "0"}:${if (set.contains("2")) "1" else "0"}," +
                            "3:${if (default == "3") "1" else "0"}:${if (set.contains("3")) "1" else "0"}"
                    putString(C.UI_NAVIGATION_TAB_LIST, list)
                }
                val defaultFollowPage = prefs.getString(C.UI_FOLLOW_DEFAULT_PAGE, null)
                if (defaultFollowPage != null) {
                    val list = "0:${if (defaultFollowPage == "3") "1" else "0"}:1," +
                            "1:${if (defaultFollowPage == "0") "1" else "0"}:1," +
                            "2:${if (defaultFollowPage == "1") "1" else "0"}:1," +
                            "3:${if (defaultFollowPage == "2") "1" else "0"}:1"
                    putString(C.UI_FOLLOWING_TABS, list)
                }
                val defaultSavedPage = prefs.getString(C.UI_SAVED_DEFAULT_PAGE, null)
                if (defaultSavedPage != null) {
                    val list = "0:${if (defaultSavedPage == "0") "1" else "0"}:1," +
                            "1:${if (defaultSavedPage == "1") "1" else "0"}:1"
                    putString(C.UI_SAVED_TABS, list)
                }
                putInt(C.SETTINGS_VERSION, 11)
            }
        }
        if (version < 12) {
            prefs.edit {
                if (!prefs.getBoolean("ui_theme_rounded_corners", true)) {
                    putString(C.UI_THEME_ROUNDED_CORNERS, "2")
                }
                putInt(C.SETTINGS_VERSION, 12)
            }
        }
        if (version < 13) {
            prefs.edit {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
                    putString(C.NETWORK_LIBRARY, C.HTTP_ENGINE)
                } else {
                    if (CronetProvider.getAllProviders(this@MainActivity).any { it.isEnabled }) {
                        putString(C.NETWORK_LIBRARY, C.CRONET)
                    }
                }
                prefs.getString("playerRewind", null)?.toLongOrNull()?.let {
                    putString(C.PLAYER_REWIND, (it / 1000).toString())
                }
                prefs.getString("playerForward", null)?.toLongOrNull()?.let {
                    putString(C.PLAYER_FORWARD, (it / 1000).toString())
                }
                putInt(C.SETTINGS_VERSION, 13)
            }
        }
        if (version < 14) {
            viewModel.updateProxies(prefs.getString(C.PLAYER_PROXY_URL, null))
            prefs.edit {
                putInt(C.SETTINGS_VERSION, 14)
            }
        }
        if (version < 15) {
            val oldProxy = prefs.getString(C.PLAYER_PROXY_URL, null)
            if (!oldProxy.isNullOrBlank() && oldProxy.toUri().host == "api.ttv.lol") {
                viewModel.deleteOldProxy()
            }
            prefs.edit {
                putInt(C.SETTINGS_VERSION, 15)
            }
        }
        if (version < 16) {
            viewModel.updateStreamProxies(
                prefs.getString(C.PROXY_HOST, null),
                prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                prefs.getString(C.PROXY_USER, null),
                prefs.getString(C.PROXY_PASSWORD, null),
                prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true),
            )
            prefs.edit {
                putInt(C.SETTINGS_VERSION, 16)
            }
        }
        if (version < 17) {
            viewModel.setDefaultVideoSwapItems()
            prefs.edit {
                putInt(C.SETTINGS_VERSION, 17)
            }
        }
        if (version < 18) {
            // the lists made before 1.2.9 get the proxy of nadeko (a new list already has it)
            if (version >= 14) {
                viewModel.addDefaultProxy(MainViewModel.NADEKO_PROXY)
            }
            prefs.edit {
                putInt(C.SETTINGS_VERSION, 18)
            }
        }
    }
}