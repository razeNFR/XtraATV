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
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = prefs()
        migrateSettings()
        forceDisplaySettings()
        if (resources.getBoolean(R.bool.isTv)) {
            // favorite channels, read before the followed streams are loaded
            com.github.andreyasadchy.xtra.ui.tv.TvFavorites.load(this)
            prefs.edit {
                // on TV leaving the app stops the stream: no picture-in-picture, no background audio (the phone settings are hidden)
                putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                putBoolean(C.PLAYER_BACKGROUND_AUDIO, false)
                putBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, false)
                // back of the player: the old "back minimizes the player" switch becomes the mini player choice
                if (!prefs.contains(C.TV_BACK_ACTION)) {
                    putString(C.TV_BACK_ACTION, if (prefs.getBoolean(C.TV_BACK_MINIMIZES_PLAYER, false)) "mini" else "stop")
                }
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
        applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
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
                binding.root.background = com.github.andreyasadchy.xtra.ui.tv.TvAmbient.background(this, base)
            }
            supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
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
                        // a list of streams shown again after a while: the streamers that stopped go away
                        f.view?.let { refreshStaleStreamLists(it) }
                        if (tvInitialFocusDone && f.parentFragment is NavHostFragment && (playerFragment as? PlayerFragment)?.isMaximizedPlayer() != true) {
                            restoreTvScreenFocus()
                        }
                        if (!tvInitialFocusDone && f.view?.isShown == true && f.parentFragment is NavHostFragment) {
                            // browsing starts in the side menu
                            tvInitialFocusDone = true
                            showTvMenu(true)
                        } else {
                            ensureFocus()
                        }
                    }
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
            if (result.resultCode == RESULT_OK) {
                recreate()
            }
        }
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                restartActivity()
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
                Toast.makeText(this, R.string.no_connection, Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(this@MainActivity, if (isNetworkAvailable) R.string.connection_restored else R.string.no_connection, Toast.LENGTH_SHORT).show()
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
                                        prefs.getString(C.UPDATE_URL, null) ?: "https://api.github.com/repos/razeNFR/XtraATV/releases/latest",
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
                                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
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
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
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
                        getAlertDialogBuilder()
                            .setTitle(getString(R.string.update_available))
                            .setMessage(getString(R.string.update_message))
                            .setPositiveButton(getString(R.string.yes)) { _, _ ->
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
                                        Toast.makeText(this@MainActivity, R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                    }
                                } else {
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
                                    viewModel.downloadUpdate(prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP), it)
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
                            }
                            .setNegativeButton(getString(R.string.no)) { _, _ ->
                                tokenPrefs().edit {
                                    putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                }
                            }
                            .show()
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

    override fun onResume() {
        super.onResume()
        restorePlayerFragment()
    }

    override fun onDestroy() {
        networkCallback?.let {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        pipActionReceiver?.let { unregisterReceiver(it) }
        if (isFinishing) {
            (playerFragment as? PlayerFragment)?.close()
        }
        super.onDestroy()
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
                streams?.take(4)?.let { startMultiStream(it) }
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
    private var dispatchingPlayerKey = false

    fun isPlayerMaximized() = (playerFragment as? PlayerFragment)?.isMaximizedPlayer() == true

    fun hasPlayer() = playerFragment != null

    /** The full screen player has the focus and its controls are hidden: OK opens them. */
    fun isPlayerFocusedWithoutControls(): Boolean {
        val player = playerFragment as? PlayerFragment ?: return false
        val focus = currentFocus
        return player.isMaximizedPlayer() && !player.areControlsShown() && (focus == null || focus.isInside(binding.playerContainer))
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

    fun currentPlayingStream(): Stream? = (playerFragment as? PlayerFragment)?.getPlayingStream()

    /** OK held on a stream of the row of the other streams: it plays in a small window, without sound. */
    fun openTvPip(stream: Stream) {
        if (stream.channelLogin == currentStreamLogin) {
            return
        }
        val first = tvPip.stream == null
        tvPip.open(stream)
        if (first) {
            com.github.andreyasadchy.xtra.ui.player.TvPip.showHint(this)
        }
    }

    fun closeTvPip() {
        if (isTv) {
            tvPip.close()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && !dispatchingPlayerKey && tvPip.onKey(event)) {
            return true
        }
        if (BuildConfig.DEBUG && isTv) {
            android.util.Log.d("XtraTv", "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} focus=${currentFocus?.let { "${it.javaClass.simpleName}#${try { resources.getResourceEntryName(it.id) } catch (e: Exception) { it.id }}" }}")
        }
        (playerFragment as? PlayerFragment)?.onKeyEventDispatched(event)
        if (isTv && event.keyCode == KeyEvent.KEYCODE_BACK && !isTvMenuShown && isBrowsingRoot()) {
            // back on a main screen opens the menu, back again in the menu leaves the app
            if (event.action == KeyEvent.ACTION_UP) {
                showTvMenu(true)
            }
            return true
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN && isTvMenuShown &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
        ) {
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
                // from the menu, go back to the item last selected in the screen, else to its first item
                val last = lastContentFocus?.takeIf { it.isAttachedToWindow && it.isShown && it.isInside(binding.navHostFragment) }
                if (last != null && last.requestFocus()) {
                    return true
                }
                val grid = TvUi.findVisibleGrid(binding.navHostFragment)
                if (grid != null && TvUi.focusFirstItem(grid)) {
                    return true
                }
                if (binding.navHostFragment.requestFocus(View.FOCUS_RIGHT)) {
                    return true
                }
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
                    showTvMenu(true)
                    return true
                }
            }
        }
        if (isTv && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if ((playerFragment as? PlayerFragment)?.isPlayingStreamMaximized() == true && streamQueue.size > 1) {
                        if (event.repeatCount == 0) {
                            switchStream(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
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
    private var isTvMenuShown = true
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
            navController.currentBackStackEntry?.id?.let { tvFocusMemory[it] = position }
        }
    }

    /** Position of the focused card of each screen, to give it back when coming back to the screen. */
    private val tvFocusMemory = HashMap<String, Int>()

    /** Coming back to a screen: focus the card that had the focus there (the views were recreated). */
    private fun restoreTvScreenFocus(tries: Int = 0) {
        if (!::navController.isInitialized) {
            return
        }
        val position = navController.currentBackStackEntry?.id?.let { tvFocusMemory[it] } ?: return
        val focus = currentFocus
        if (focus != null && focus.isShown && focus.parent is RecyclerView) {
            return
        }
        val grid = TvUi.findVisibleGrid(binding.navHostFragment)
        val count = grid?.adapter?.itemCount ?: 0
        if (grid == null || count == 0) {
            if (tries < 15) {
                binding.root.postDelayed({ restoreTvScreenFocus(tries + 1) }, 150)
            }
            return
        }
        val target = position.coerceAtMost(count - 1)
        val holder = grid.findViewHolderForAdapterPosition(target)
        if (holder == null || !holder.itemView.requestFocus()) {
            grid.scrollToPosition(target)
            grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }

    private val tvMenuBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showTvMenu(true)
        }
    }

    private fun updateTvMenuBackCallback() {
        tvMenuBackCallback.isEnabled = isTv && !isTvMenuShown && ::navController.isInitialized && navController.previousBackStackEntry == null
    }

    private val tvMenuState = com.github.andreyasadchy.xtra.ui.tv.TvMenuState()

    /** The leanback menu, drawn with Compose from the entries of the navigation bar. */
    private fun setupTvSideMenu() {
        val composeView = binding.tvSideMenu ?: return
        val icons = mapOf(
            R.id.tvNavSearch to R.drawable.baseline_search_black_24,
            R.id.tvHomeFragment to R.drawable.baseline_home_black_24,
            R.id.rootGamesFragment to R.drawable.ic_games_black_24dp,
            R.id.rootTopFragment to R.drawable.ic_trending_up_black_24dp,
            R.id.followPagerFragment to R.drawable.ic_favorite_black_24dp,
            R.id.followMediaFragment to R.drawable.ic_favorite_black_24dp,
            R.id.savedPagerFragment to R.drawable.ic_file_download_black_24dp,
            R.id.savedMediaFragment to R.drawable.ic_file_download_black_24dp,
            R.id.tvNavSettings to R.drawable.baseline_settings_black_24,
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
                    R.id.tvNavSearch -> com.github.andreyasadchy.xtra.ui.tv.TvMenuState.GROUP_TOP
                    R.id.tvNavSettings, R.id.tvNavAccount -> com.github.andreyasadchy.xtra.ui.tv.TvMenuState.GROUP_BOTTOM
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
        com.github.andreyasadchy.xtra.ui.view.TvHeader.sectionTitle = {
            tvMenuState.items.find { it.id == tvMenuState.selected }?.title
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
                    binding.navBar.selectedItemId = id
                    tvMenuState.selected = tvMenuExtraSelected ?: binding.navBar.selectedItemId
                    if (id != R.id.tvNavSettings && id != R.id.tvNavAccount) {
                        // the chosen screen gets the focus, the menu folds away
                        focusContentAfterMenu(id == R.id.tvNavSearch)
                    }
                }
            }
        }
    }

    /** Entry of the side menu shown as chosen instead of its screen ("Followed videos" rather than "Following"). */
    private var tvMenuExtraSelected: Int? = null

    /** TV side menu: a tab of the followed channels (videos, channels, categories), its screen opened if needed. */
    private fun openFollowedTab(key: String, entry: Int) {
        val loggedInWithCode = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank()
        if (key == "2" && !loggedInWithCode) {
            // the videos of the followed channels need the login with the code (the token of the app)
            Toast.makeText(this, R.string.tv_followed_videos_need_code, Toast.LENGTH_LONG).show()
            return
        }
        tvMenuExtraSelected = entry
        tvMenuState.selected = entry
        val current = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
        if (current is com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment && current.showTab(key)) {
            return
        }
        com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment.requestedTab = key
        if (current is com.github.andreyasadchy.xtra.ui.following.FollowPagerFragment) {
            // the tab is hidden on the screen open: the screen again, with that tab
            navController.navigate(R.id.followPagerFragment)
        } else {
            binding.navBar.menu.findItem(R.id.followPagerFragment)?.let { NavigationUI.onNavDestinationSelected(it, navController) }
                ?: navController.navigate(R.id.followPagerFragment)
        }
    }

    /** Opens the side menu from a Compose screen (left on its first card). */
    fun openTvMenu() {
        showTvMenu(true)
    }

    private fun refreshStaleStreamLists(view: View) {
        when (view) {
            is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView -> view.refreshIfStale()
            is ViewGroup -> for (i in 0 until view.childCount) refreshStaleStreamLists(view.getChildAt(i))
        }
    }

    /** Goes to a section of the side menu ("see all" of the TV home). */
    fun openTvSection(id: Int) {
        binding.navBar.selectedItemId = id
        tvMenuState.selected = binding.navBar.selectedItemId
        focusContentAfterMenu(false)
    }

    private fun focusContentAfterMenu(search: Boolean, tries: Int = 0) {
        binding.root.postDelayed({
            val target = if (search) {
                binding.navHostFragment.findViewById<View>(R.id.tvSearchBar)
            } else {
                TvUi.findVisibleGrid(binding.navHostFragment)?.takeIf { (it.adapter?.itemCount ?: 0) > 0 && it.childCount > 0 }
                    ?: binding.navHostFragment.findViewById<View>(R.id.tvHome)
            }
            when {
                target is com.github.andreyasadchy.xtra.ui.view.GridRecyclerView -> TvUi.focusFirstItem(target)
                target != null && target.id == R.id.tvHome -> {
                    // the home: the first stream of the first row, never "refresh"
                    target.requestFocus()
                    currentTvHome()?.focusFirstCard()
                }
                target != null -> target.requestFocus()
                tries < 15 -> focusContentAfterMenu(search, tries + 1)
            }
        }, if (tries == 0) 250L else 150L)
    }

    private fun showTvMenu(focus: Boolean) {
        val menu = binding.navBarContainer
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
                if (!tvMenuState.focusSelected()) {
                    sideMenu.requestFocus()
                    sideMenu.post { tvMenuState.focusSelected() }
                }
            } else {
                val selected = binding.navBar.findViewById<View>(binding.navBar.selectedItemId)
                if (selected == null || !selected.requestFocus()) {
                    menu.requestFocus()
                }
            }
        }
    }

    private fun hideTvMenu() {
        val menu = binding.navBarContainer
        if (isTvMenuShown && menu.width > 0) {
            isTvMenuShown = false
            updateTvMenuBackCallback()
            menu.animate().cancel()
            menu.animate().translationX(-menu.width.toFloat()).alpha(0f).setDuration(180L).withEndAction {
                if (!isTvMenuShown) {
                    menu.visibility = View.INVISIBLE
                }
            }.start()
        }
    }

    private fun isBrowsingRoot(): Boolean {
        if ((playerFragment as? PlayerFragment)?.isMaximizedPlayer() == true) {
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

    fun openSettings() {
        settingsResultLauncher?.launch(Intent(this, SettingsActivity::class.java))
    }

    fun onAccountClicked() {
        if (resources.getBoolean(R.bool.isTv)) {
            // TV: the page of the account shows what is saved, with its log in and log out buttons
            loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
            return
        }
        val isLoggedIn = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()
        if (isLoggedIn) {
            getAlertDialogBuilder().apply {
                setTitle(getString(R.string.logout_title))
                tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                setNegativeButton(getString(R.string.no), null)
                setPositiveButton(getString(R.string.yes)) { _, _ -> logoutResultLauncher?.launch(Intent(this@MainActivity, LoginActivity::class.java)) }
            }.show()
        } else {
            loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
        }
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

    override fun onStart() {
        super.onStart()
        openingOwnScreen = false
        keepPlayerInBackground = false
    }

    /** TV: the app was left with the mini player or the sound only mode, the sound goes on behind. */
    private var keepPlayerInBackground = false
    val isKeepingPlayerInBackground get() = keepPlayerInBackground

    override fun onStop() {
        if (isTv && !openingOwnScreen && !isChangingConfigurations) {
            // leaving the app: the small window closes, only the main stream may keep its sound
            closeTvPip()
        }
        keepPlayerInBackground = false
        if (isTv && !openingOwnScreen && !isChangingConfigurations) {
            val player = playerFragment as? PlayerFragment
            if (player?.keepsPlayingWhenAppLeft() == true) {
                keepPlayerInBackground = true
            } else {
                // TV: leaving the app stops the streams, no sound left behind
                player?.let {
                    it.close()
                    closePlayer()
                }
            }
            closeMultiStream()
        }
        super.onStop()
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
                            val login = url.substringAfter("twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                            if (!login.isNullOrBlank()) {
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
            INTENT_INSTALL_UPDATE -> {
                val extras = intent.extras
                if (extras?.getInt(PackageInstaller.EXTRA_STATUS) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(Intent.EXTRA_INTENT)
                    }?.let {
                        tokenPrefs().edit {
                            putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                        }
                        startActivity(it)
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

    fun switchStream(offset: Int) {
        if (streamQueue.size < 2 || switchJob?.isActive == true) {
            return
        }
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
    fun startMultiStream(streams: List<Stream>) {
        closeTvPip()
        (playerFragment as? PlayerFragment)?.let {
            it.close()
            closePlayer(focusHome = false)
        }
        setMainContentFocusable(false)
        supportFragmentManager.beginTransaction()
            .replace(R.id.playerContainer, com.github.andreyasadchy.xtra.ui.player.MultiStreamFragment.newInstance(streams), MULTI_STREAM_TAG)
            .commit()
    }

    fun closeMultiStream() {
        supportFragmentManager.findFragmentByTag(MULTI_STREAM_TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        setMainContentFocusable(true)
    }

    fun showStreamSwitcher() {
        // shown at once with the list in memory, it fills in by itself if a newer list comes; tabs above it for the
        // other lists (followed, popular, languages, category), OK held opens a stream in a small window
        TvStreamSwitcher.show(
            this,
            streamQueue,
            currentStreamLogin,
            rows = com.github.andreyasadchy.xtra.ui.tv.TvStreamSources.rows(this, currentPlayingStream()),
            onPip = { openTvPip(it) },
            source = streamQueueSource,
        )
        refreshQueueIfOld()
    }

    fun startStream(stream: Stream, queue: List<Stream>? = null, queueSource: (() -> androidx.paging.PagingSource<Int, Stream>)? = null) {
        // the time of the start of the stream counts from here (debug builds), and its access of Twitch is asked
        // at once, while the player opens
        com.github.andreyasadchy.xtra.ui.player.TvLoadTimer.start()
        com.github.andreyasadchy.xtra.ui.player.TvStreamPrefetch.start(this, stream.channelLogin)
        com.github.andreyasadchy.xtra.ui.tv.TvStats.addStream(this)
        if (isTv && prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER) {
            // the picture of the stream instead of a black screen while it loads: the newest one there is
            val fresh = com.github.andreyasadchy.xtra.ui.tv.TvThumbnails.isFresh(stream)
            com.github.andreyasadchy.xtra.ui.player.PlayerFragment.pendingLoadingPicture = com.github.andreyasadchy.xtra.ui.player.PlayerFragment.Companion.LoadingPicture(
                stream.channelLogin,
                stream.thumbnail?.takeUnless { fresh || stream.thumbnailURL.isNullOrBlank() },
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
        currentStreamLogin = stream.channelLogin
        dvrStream = null
        dvrChatVideoId = null
        // TV, no player open: the playback service starts as soon as the stream is saved, while the screen of the
        // player is made (it binds to it once ready, the service starts only once)
        val startServiceEarly = isTv && playerFragment == null && prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER
        // TV, a stream already playing full screen: the next one plays in the same service, only the screen is made again
        val keptService = if (isTv) (playerFragment as? ExoPlayerFragment)?.handOverForStreamSwitch() else null
        if (keptService == null) {
            (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        }
        viewModel.savePlaybackState(PlaybackState(
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
        )) {
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
        startPlayer(fragment)
        prepareNeighborStreams()
    }

    private var neighborJob: kotlinx.coroutines.Job? = null

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
            kotlinx.coroutines.delay(4000L)
            while (currentStreamLogin == login && playerFragment != null) {
                val queue = streamQueue
                val index = queue.indexOfFirst { it.channelLogin == login }
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
        (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
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
        ))
        if (ignoreSavedPosition && prefs.getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            video.id?.toLongOrNull()?.let { id ->
                viewModel.saveVideoPosition(id, offset ?: 0)
            }
        }
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        startPlayer(fragment)
    }

    fun startClip(clip: Clip) {
        (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
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
        ))
        val fragment = when (prefs.getString(C.PLAYER, C.EXOPLAYER)) {
            C.MEDIA_PLAYER -> MediaPlayerFragment()
            else -> ExoPlayerFragment()
        }
        startPlayer(fragment)
    }

    fun startOfflineVideo(video: OfflineVideo, offset: Long? = null) {
        (playerFragment as? ExoPlayerFragment)?.close(deleteStates = false)
        viewModel.savePlaybackState(PlaybackState(
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
        ))
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
        com.github.andreyasadchy.xtra.ui.tv.TvPreviewSource.suspended = !focusable
        if (focusable) {
            if (binding.navHostFragment.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                return
            }
            binding.navHostFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            binding.navBarContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val lastFocus = lastContentFocus
            lastContentFocus = null
            val grid = lastFocusGrid
            val position = lastFocusPosition
            lastFocusGrid = null
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

    private fun stopTvPlayback() {
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

    /** The home, when it is the screen shown. */
    private fun currentTvHome(): com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment? {
        return supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments
            ?.firstOrNull { it.isVisible } as? com.github.andreyasadchy.xtra.ui.tv.home.TvHomeFragment
    }

    fun closePlayer(focusHome: Boolean = true) {
        neighborJob?.cancel()
        com.github.andreyasadchy.xtra.ui.player.TvStreamPrefetch.clear()
        closeTvPip()
        setMainContentFocusable(true)
        // back on the home: the card of the stream that was watched, else the first stream (not when a multi-stream
        // takes the place of the player: its streams keep the focus)
        if (focusHome) {
            currentTvHome()?.focusLastCard()
        }
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .remove(supportFragmentManager.findFragmentById(R.id.playerContainer)!!)
            .commit()
        playerFragment = null
        updateTvNowPlaying()
        viewModel.isPlayerOpened = false
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
                if (prefs.getString(C.PLAYER, C.EXOPLAYER) != C.MEDIA_PLAYER) {
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

    private fun initNavigation() {
        navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        if (isTv) {
            onBackPressedDispatcher.addCallback(this, tvMenuBackCallback)
            onBackPressedDispatcher.addCallback(this, tvGridBackCallback)
            navController.addOnDestinationChangedListener { _, destination, _ ->
                updateTvMenuBackCallback()
                if (destination.id != R.id.followPagerFragment) {
                    tvMenuExtraSelected = null
                }
                binding.root.post { tvMenuState.selected = tvMenuExtraSelected ?: binding.navBar.selectedItemId }
            }
        }
        val tabList = prefs.getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
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
        navController.setGraph(navController.navInflater.inflate(R.navigation.nav_graph).also {
            val startOnFollowed = prefs.getString(C.UI_START_ON_FOLLOWED, "1")?.toIntOrNull() ?: 1
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()
            val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
            when {
                isTv -> it.setStartDestination(R.id.tvHomeFragment)
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
            if (isTv) {
                menu.add(Menu.NONE, R.id.tvNavSearch, Menu.NONE, R.string.search).setIcon(R.drawable.baseline_search_black_24)
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
                // shortcuts to the tabs of the followed channels, chosen in the Xtra TV settings
                val extras = prefs.getStringSet(C.TV_MENU_EXTRAS, null).orEmpty()
                if ("videos" in extras) menu.add(Menu.NONE, R.id.tvNavFollowedVideos, Menu.NONE, R.string.tv_menu_followed_videos).setIcon(R.drawable.tv_ic_video_library)
                if ("channels" in extras) menu.add(Menu.NONE, R.id.tvNavFollowedChannels, Menu.NONE, R.string.tv_menu_followed_channels).setIcon(R.drawable.tv_ic_person)
                if ("games" in extras) menu.add(Menu.NONE, R.id.tvNavFollowedGames, Menu.NONE, R.string.tv_menu_followed_games).setIcon(R.drawable.tv_ic_gamepad)
            }
            if (isTv) {
                menu.add(Menu.NONE, R.id.tvNavSettings, Menu.NONE, R.string.settings).setIcon(R.drawable.baseline_settings_black_24)
                menu.add(Menu.NONE, R.id.tvNavAccount, Menu.NONE, R.string.tv_account).setIcon(R.drawable.baseline_person_black_24)
            }
            setupWithNavController(navController)
            if (isTv) {
                setupTvSideMenu()
            }
            setOnItemSelectedListener {
                when (it.itemId) {
                    R.id.tvNavSearch -> {
                        navController.navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        return@setOnItemSelectedListener false
                    }
                    R.id.tvNavSettings -> {
                        openSettings()
                        return@setOnItemSelectedListener false
                    }
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
                NavigationUI.onNavDestinationSelected(it, navController)
                return@setOnItemSelectedListener true
            }
            setOnItemReselectedListener {
                if (tvMenuExtraSelected != null && it.itemId == R.id.followPagerFragment) {
                    // back to "Following" from one of its shortcuts: the screen with all its tabs again
                    tvMenuExtraSelected = null
                    tvMenuState.selected = it.itemId
                    navController.navigate(R.id.followPagerFragment)
                    return@setOnItemReselectedListener
                }
                if (!navController.popBackStack(it.itemId, false)) {
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
                    if (currentFragment is Scrollable) {
                        currentFragment.scrollToTop()
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
    }
}