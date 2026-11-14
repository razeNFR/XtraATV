package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.audiofx.DynamicsProcessing
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.StreamProxy
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.model.ui.VideoSwap
import com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock
import com.github.andreyasadchy.xtra.ui.tv.TvProxyStatus
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HlsPlaylistParser
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.MediaButtonReceiver
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(UnstableApi::class)
class ExoPlayerService : BasePlaybackService() {

    var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var artworkUri: String? = null
    private var cachedBitmap: Bitmap? = null
    private var bitmapLoadJob: Job? = null
    private var showStreamNotificationSeekbar = true

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var secondaryDynamicsProcessing: DynamicsProcessing? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null
    private var stopServiceTimer: Timer? = null

    private var customProxyList: List<CustomProxy>? = null
    private var streamProxyList: List<StreamProxy>? = null
    private var videoSwapList: List<VideoSwap>? = null
    var secondaryPlayer: ExoPlayer? = null
    private var secondaryPlayerTimerJob: Job? = null
    private var adDelayJob: Job? = null
    private var playingAds = false
    private var proxyMediaPlaylist = false
    /** Proxy of the stream being loaded, counted in the statistics of the home when the stream plays. */
    private var pendingProxyStat: String? = null
    private var stopProxy = false
    private var useVideoSwap = false
    private var currentVideoSwapItem = 0
    private var videoSwapActive = false
    private var stopVideoSwap = false
    private var hidden = false

    private var backupQualities: List<String>? = null
    private var updateQualities = false
    private var ignorePlaylistUpdate = false
    private var fallbackH264ForStream = false
    private var created = false
    private var intentionalStopOrPause = false
    private var streamEnded = false
    private var liveRecoveryJob: Job? = null
    private var liveRecoveryAttempt = 0

    val isIntentionallyPausedOrStopped: Boolean
        get() = intentionalStopOrPause || streamEnded

    interface Listener {
        fun started()
        fun loaded()
        fun changePlayerMode()
        fun toast(resId: Int, duration: Int)
        fun toast(text: CharSequence, duration: Int)
        fun updateVideoInfo()
        fun setPlayerSurface(secondaryPlayer: Boolean)
        /** TV: a picture of the stream itself (not of an ad) is on the screen, the loading picture can go. */
        fun streamPictureShown()
    }

    var serviceListener: Listener? = null

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        // statistics of the home: time watched, by streamer
        lifecycleScope.launch {
            while (true) {
                delay(30_000L)
                if (visiblePlayer?.isPlaying == true && (type == STREAM || type == VIDEO)) {
                    com.github.andreyasadchy.xtra.ui.tv.TvStats.addWatchTime(this@ExoPlayerService, channelLogin, channelName, 30_000L, gameId, gameName, channelImage)
                }
                // the system (and the screensavers of the launchers that look at it) sees a playback going on: its state
                // is given again now and then, whichever player plays (the backup player of the ad blocker too)
                updatePlaybackState()
            }
        }
        startLiveWatchdog()
    }

    private enum class ExperimentalFallback { NONE, NEW_STREAM, UNSUPPORTED }

    /** True while a live broadcast (`type == STREAM`) plays via Twitch's ongoing CDN VOD (`index-dvr.m3u8`) at `-32s`. */
    var experimentalVodActive = false
        private set
    /** True when the viewer explicitly rewound or seeked back behind the ad-free live edge. */
    var experimentalVodUserRewound = false
        private set
    private var experimentalVodCreatedAtMs: Long? = null
    private var experimentalVodUpgradeJob: Job? = null
    private var experimentalFallback = ExperimentalFallback.NONE
    private var experimentalNoticeTitleRes = 0
    private var experimentalNoticeSubtitleRes = 0
    private var experimentalNoticeStartedAtMs = 0L

    private fun showExperimentalBadgeNotice(titleRes: Int, subtitleRes: Int = 0) {
        experimentalNoticeTitleRes = titleRes
        experimentalNoticeSubtitleRes = subtitleRes
        experimentalNoticeStartedAtMs = 0L
    }

    private fun clearExperimentalBadgeNotice() {
        experimentalNoticeTitleRes = 0
        experimentalNoticeSubtitleRes = 0
        experimentalNoticeStartedAtMs = 0L
    }

    /** Ad-free live edge inside the ongoing VOD (`max(0, vodDuration - 32s)`). */
    fun getExperimentalLiveEdgeMs(): Long {
        val p = player ?: return 0L
        val dur = p.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0L } ?: return 0L
        return (dur - TvExperimentalAdblock.TARGET_OFFSET_MS).coerceAtLeast(0L)
    }

    /** Epoch timestamp (ms) of the video frame currently rendered on screen, for both standard live and Experimental VOD. */
    fun getStreamPictureEpochMs(): Long? {
        val active = if (videoSwapActive || proxyMediaPlaylist) secondaryPlayer else player
        active ?: return null
        val timeline = active.currentTimeline
        if (timeline.isEmpty || active.currentMediaItemIndex !in 0 until timeline.windowCount) return null
        val window = Timeline.Window()
        timeline.getWindow(active.currentMediaItemIndex, window)
        val pos = active.currentPosition.coerceAtLeast(0L)
        if (window.windowStartTimeMs != androidx.media3.common.C.TIME_UNSET) {
            return window.windowStartTimeMs + pos
        }
        if (experimentalVodActive && active === player) {
            val baseMs = experimentalVodCreatedAtMs
                ?: TvExperimentalAdblock.parseEpochMs(createdAt)?.also { experimentalVodCreatedAtMs = it }
            if (baseMs != null) {
                return baseMs + pos
            }
        }
        return null
    }

    private fun create(restorePauseState: Boolean) {
        if (!created) {
            created = true
            showStreamNotificationSeekbar = prefs().getBoolean(C.PLAYER_SHOW_STREAM_NOTIFICATION_SEEKBAR, true)
            val playerListener = object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    dynamicsProcessing?.let {
                        runCatching { it.release() }
                        dynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        reinitializeDynamicsProcessing(audioSessionId)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updatePlaybackState()
                    updateMetadata()
                }

                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    updateMetadata()
                    updateNotification()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty) {
                        if (!loaded) {
                            loaded = true
                            serviceListener?.loaded()
                            toggleSubtitles(prefs().getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                        }
                        if (qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null &&
                            quality?.name != VideoQuality.AUDIO_ONLY_QUALITY &&
                            !playingAds && !isSwapping && !hidden && adDelayJob?.isActive != true
                        ) {
                            changeQuality(quality)
                        }
                    }
                }

                override fun onRenderedFirstFrame() {
                    TvLoadTimer.finish(isSwapping)?.let { serviceListener?.toast(it, Toast.LENGTH_LONG) }
                    mainFrameRendered = true
                    checkStreamPictureShown()
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    updatePlaybackState()
                    updateMetadata()
                    updateNotification()
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null) {
                        if (ignorePlaylistUpdate) {
                            ignorePlaylistUpdate = false
                        } else {
                            if (type == STREAM) {
                                updateQualities = quality?.name != VideoQuality.AUDIO_ONLY_QUALITY
                            }
                        }
                    }
                    if (qualities.isNullOrEmpty() || updateQualities) {
                        val playlist = (player?.currentManifest as? HlsManifest)?.multivariantPlaylist
                        val list = playlist?.variants?.mapNotNull { variant ->
                            val name = variant.stableVariantId?.takeIf { it.isNotBlank() }
                                ?: playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.takeIf { it.isNotBlank() }
                            if (name != null) {
                                VideoQuality(name, variant.format.height, variant.format.frameRate, variant.format.bitrate, variant.format.codecs, variant.url.toString())
                            } else null
                        }
                        if (!list.isNullOrEmpty()) {
                            qualities = list
                                .sortedByDescending { it.bitrate }
                                .sortedByDescending { it.frameRate }
                                .sortedByDescending { it.resolution }
                                .toMutableList().apply {
                                    add(0, VideoQuality(VideoQuality.AUTO_QUALITY))
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(1, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                    }
                                    val audio = find { it.name?.startsWith("audio", true) == true }
                                    audio?.let { remove(it) }
                                    add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio?.resolution, audio?.frameRate, audio?.bitrate, audio?.codecs, audio?.url))
                                    if (type == STREAM) {
                                        add(VideoQuality(VideoQuality.CHAT_ONLY_QUALITY))
                                    }
                                }
                            setDefaultQuality()
                            serviceListener?.changePlayerMode()
                            if (quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                                changeQuality(quality)
                            }
                        }
                        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                            updateQualities = false
                        }
                    }
                    if (type == STREAM && experimentalVodActive) {
                        if ((player?.currentManifest as? HlsManifest)?.mediaPlaylist != null) {
                            checkFirstPlaylist = false
                            checkStreamPictureShown()
                        }
                    } else if (type == STREAM) {
                        val useProxy = useStreamProxy && !stopProxy && streamProxyList?.getOrNull(currentStreamProxy)?.proxyMediaPlaylist == true
                        val useVideoSwap = useVideoSwap && !stopVideoSwap
                        val hideAds = prefs().getBoolean(C.PLAYER_HIDE_ADS, false) || TvExperimentalAdblock.isSwapAdblockEnabled(this@ExoPlayerService)
                        if (!(useProxy || useVideoSwap || hideAds) && (player?.currentManifest as? HlsManifest)?.mediaPlaylist != null) {
                            // nothing looks for ads: the first playlist has nothing to wait for
                            checkFirstPlaylist = false
                            checkStreamPictureShown()
                        }
                        if (useProxy || useVideoSwap || hideAds) {
                            val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist
                            val ads = checkPlaylist(playlist, mainPlayer = true)
                            val oldValue = playingAds
                            playingAds = ads
                            // the ad being blocked from the marks of Twitch ("2/3", its end), for the badge
                            if (ads) {
                                adDelayJob?.cancel()
                                adDelayJob = null
                                backLiveFrom = 0L
                                backLiveProgress = 0f
                                player?.setPlaybackSpeed(1f)
                                if (!oldValue) {
                                    resetAdPod()
                                }
                                updateAdPod(playlist)
                            }
                            checkStreamPictureShown()
                            if (ads) {
                                if (!oldValue) {
                                    // pre-roll (start of the stream) or mid-roll, shown in the badge of the ad blocker
                                    adRollType = rollTypeOf(playlist) ?: if (firstPlaylistAd) "preroll" else "midroll"
                                    firstPlaylistAd = false
                                    // statistics of the home: an ad break handled by the ad blocker (or a proxy)
                                    com.github.andreyasadchy.xtra.ui.tv.TvStats.addAdBlocked(this@ExoPlayerService)
                                    when {
                                        useProxy -> {
                                            if (!proxyMediaPlaylist) {
                                                proxyMediaPlaylist = true
                                                serviceListener?.toast(R.string.starting_proxy, Toast.LENGTH_SHORT)
                                                player?.let { player ->
                                                    if (quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                        }.build()
                                                    }
                                                    player.volume = 0f
                                                }
                                                secondaryPlayerTimerJob?.cancel()
                                                secondaryPlayerTimerJob = lifecycleScope.launch {
                                                    delay(3.minutes)
                                                    proxyMediaPlaylist = false
                                                    serviceListener?.toast(R.string.stopping_proxy, Toast.LENGTH_SHORT)
                                                    stopSecondaryPlayer2()
                                                    secondaryPlayerTimerJob = null
                                                }
                                                startSecondaryPlayer(true)
                                            }
                                        }
                                        useVideoSwap -> {
                                            if (!videoSwapActive) {
                                                startVideoSwap()
                                            }
                                        }
                                        else -> {
                                            if (!hidden) {
                                                hidden = true
                                                serviceListener?.toast(R.string.waiting_ads, Toast.LENGTH_LONG)
                                                player?.let { player ->
                                                    if (quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                        }.build()
                                                    }
                                                    player.volume = 0f
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (proxyMediaPlaylist || videoSwapActive || hidden) {
                                startReturnToLiveIfNeeded()
                            }
                        }
                    }
                }

                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    updatePlaybackState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    updatePlaybackState()
                    when (type) {
                        STREAM -> {
                            val generation = streamGeneration
                            val login = channelLogin
                            val responseCode = (player?.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                            val timeout = (player?.playerError?.cause as? DataSourceException)?.reason?.let {
                                it == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                                        || it == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            } == true
                            val isNetworkAvailable = isNetworkValidated()
                            if (isNetworkAvailable) {
                                when {
                                    responseCode == 404 -> {
                                        streamEnded = true
                                        liveRecoveryJob?.cancel()
                                        liveRecoveryJob = null
                                        serviceListener?.toast(R.string.stream_ended, Toast.LENGTH_LONG)
                                    }
                                    useCustomProxy && (responseCode >= 400 || timeout) -> {
                                        val host = customProxyList?.getOrNull(currentCustomProxy)?.url?.let {
                                            it.toUri().host ?: "https://$it".toUri().host
                                        }
                                        val proxyHost = customProxyList?.getOrNull(currentCustomProxy)?.let { customProxyHost(it) }
                                        currentCustomProxy += 1
                                        if (responseCode == 403) {
                                            // 403 = geo-blocked / forbidden on this channel: skip for this channel only and retry immediately
                                            proxyHost?.let { geoBlockedProxiesForChannel.add(it) }
                                            liveRecoveryJob?.cancel()
                                            liveRecoveryJob = lifecycleScope.launch {
                                                if (generation == streamGeneration && !intentionalStopOrPause && !streamEnded) {
                                                    liveRecoveryJob = null
                                                    restartPlayer(fromAutoRecovery = true)
                                                }
                                            }
                                        } else {
                                            // not tried again for a while, on this stream or the next ones
                                            proxyHost?.let { TvProxyStatus.markFailed(it) }
                                            if (host != null) {
                                                serviceListener?.toast(getString(R.string.proxy_error, host), Toast.LENGTH_LONG)
                                            }
                                            scheduleLiveRecovery(generation, login, initialDelayMs = 1500L)
                                        }
                                    }
                                    useStreamProxy && (responseCode >= 400 || timeout) -> {
                                        val host = streamProxyList?.getOrNull(currentStreamProxy)?.host
                                        currentStreamProxy += 1
                                        if (responseCode == 403) {
                                            liveRecoveryJob?.cancel()
                                            liveRecoveryJob = lifecycleScope.launch {
                                                if (generation == streamGeneration && !intentionalStopOrPause && !streamEnded) {
                                                    liveRecoveryJob = null
                                                    restartPlayer(fromAutoRecovery = true)
                                                }
                                            }
                                        } else {
                                            if (host != null) {
                                                serviceListener?.toast(getString(R.string.proxy_error, host), Toast.LENGTH_LONG)
                                            }
                                            scheduleLiveRecovery(generation, login, initialDelayMs = 1500L)
                                        }
                                    }
                                    isSwapping || hidden -> {
                                        // Background main player errored while ads are blocked: reconnect it silently at live edge
                                        lifecycleScope.launch {
                                            delay(1000.milliseconds)
                                            if ((isSwapping || hidden) && generation == streamGeneration) {
                                                player?.seekToDefaultPosition()
                                                player?.prepare()
                                                player?.play()
                                            }
                                        }
                                    }
                                    else -> {
                                        val isCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
                                        if (!fallbackH264ForStream && !useCustomProxy) {
                                            fallbackH264ForStream = true
                                            if (isCodecError) {
                                                // Hardware AV1/HEVC/1440p decoder failed: switch immediately to H.264 1080p without error toast
                                                liveRecoveryJob?.cancel()
                                                liveRecoveryJob = lifecycleScope.launch {
                                                    if (generation == streamGeneration && !intentionalStopOrPause && !streamEnded) {
                                                        liveRecoveryJob = null
                                                        restartPlayer(fromAutoRecovery = true)
                                                    }
                                                }
                                            } else {
                                                serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                                scheduleLiveRecovery(generation, login, initialDelayMs = 1500L)
                                            }
                                        } else {
                                            serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                            scheduleLiveRecovery(generation, login, initialDelayMs = 1500L)
                                        }
                                    }
                                }
                            } else if (!(isSwapping || hidden) && !intentionalStopOrPause && !streamEnded) {
                                // Brief Wi-Fi band-steer / network drop: wait quietly for connectivity and auto-recover
                                scheduleLiveRecovery(generation, login, initialDelayMs = 2000L)
                            }
                        }
                        VIDEO -> {
                            val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                            val isNetworkAvailable = networkCapabilities != null
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            if (isNetworkAvailable) {
                                when {
                                    !skipAccessToken && responseCode != 0 && videoAnimatedPreviewURL != null -> {
                                        skipAccessToken = true
                                        videoAnimatedPreviewURL?.let { preview ->
                                            val backupQualities = backupQualities
                                            val list = (backupQualities ?: TwitchApiHelper.defaultQualityList).map { quality ->
                                                val split = quality.split("p")
                                                val resolution = split.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                                val frameRate = split.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                                val url = preview
                                                    .replace("storyboards", quality)
                                                    .replaceAfterLast(
                                                        "/",
                                                        if (videoType?.lowercase() == "highlight") {
                                                            "highlight-${preview.substringAfterLast("/").substringBefore("-")}.m3u8"
                                                        } else {
                                                            "index-dvr.m3u8"
                                                        }
                                                    )
                                                val name = if (quality == "chunked") {
                                                    "source"
                                                } else {
                                                    quality
                                                }
                                                VideoQuality(name, resolution, frameRate.toFloat(), url = url)
                                            }
                                            qualities = list
                                                .sortedByDescending { it.bitrate }
                                                .sortedByDescending { it.frameRate }
                                                .sortedByDescending { it.resolution }
                                                .toMutableList().apply {
                                                    find { it.name.equals("source", true) }?.let { source ->
                                                        remove(source)
                                                        add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                                    }
                                                    val audio = find { it.name?.startsWith("audio", true) == true }
                                                    audio?.let { remove(it) }
                                                    add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio?.resolution, audio?.frameRate, audio?.bitrate, audio?.codecs, audio?.url))
                                                }
                                            if (backupQualities != null) {
                                                setDefaultQuality()
                                            } else {
                                                quality = qualities?.firstOrNull()
                                            }
                                            serviceListener?.changePlayerMode()
                                            val url = quality?.url
                                            if (url != null) {
                                                player?.let { player ->
                                                    val playbackPosition = player.currentPosition
                                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                    }.build()
                                                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                                    player.setMediaSource(
                                                        HlsMediaSource.Factory(
                                                            DefaultDataSource.Factory(
                                                                this@ExoPlayerService,
                                                                when {
                                                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                                                    }
                                                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                                                        CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                                                    }
                                                                    else -> {
                                                                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null, null, null, null) { false }
                                                                    }
                                                                }
                                                            )
                                                        ).apply {
                                                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                                        }.createMediaSource(
                                                            MediaItem.fromUri(url)
                                                        )
                                                    )
                                                    player.volume = userVolume()
                                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                                    player.prepare()
                                                    player.playWhenReady = true
                                                    player.seekTo(playbackPosition)
                                                }
                                            }
                                        }
                                    }
                                    !skipAccessToken && responseCode != 0 && videoAnimatedPreviewURL == null && !videoId.isNullOrBlank() && !videoPreviewLookupDone -> {
                                        // sub-only VODs play from the CDN through the preview URL: fetch it when the video was opened without it
                                        videoPreviewLookupDone = true
                                        val listener = this
                                        lifecycleScope.launch {
                                            val preview = try {
                                                xtraModule.graphQLRepository.loadQueryVideo(
                                                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                                                    headers = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService),
                                                    id = videoId
                                                ).data?.video?.animatedPreviewURL
                                            } catch (e: Exception) {
                                                null
                                            }
                                            if (preview != null) {
                                                videoAnimatedPreviewURL = preview
                                                listener.onPlayerError(error)
                                            } else {
                                                serviceListener?.toast(R.string.video_subscribers_only, Toast.LENGTH_LONG)
                                            }
                                        }
                                    }
                                    responseCode == 403 -> {
                                        serviceListener?.toast(R.string.video_subscribers_only, Toast.LENGTH_LONG)
                                    }
                                    else -> {
                                        serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                        lifecycleScope.launch {
                                            delay(1500.milliseconds)
                                            player?.prepare()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    updatePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        videoStartPosition = null
                        streamEnded = false
                    } else if (playbackState == Player.STATE_ENDED && type == STREAM) {
                        streamEnded = true
                        liveRecoveryJob?.cancel()
                        liveRecoveryJob = null
                    }
                    updatePlaybackState()
                    updateNotification()
                    onBufferingForAuto(playbackState)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlaybackState()
                    if (isPlaying) {
                        pendingProxyStat?.let {
                            pendingProxyStat = null
                            com.github.andreyasadchy.xtra.ui.tv.TvStats.addProxy(this@ExoPlayerService, it)
                        }
                        if (savePositionTimer == null && type != STREAM) {
                            savePositionTimer = Timer().apply {
                                scheduleAtFixedRate(30000, 30000) {
                                    Handler(Looper.getMainLooper()).post {
                                        updateSavedPosition()
                                    }
                                }
                            }
                        }
                        stopServiceTimer?.cancel()
                        stopServiceTimer = null
                    } else {
                        savePositionTimer?.cancel()
                        savePositionTimer = null
                        updateSavedPosition()
                        if (stopServiceTimer == null && serviceListener == null) {
                            stopServiceTimer = Timer().apply {
                                schedule(600000) {
                                    Handler(Looper.getMainLooper()).post {
                                        stopSelf()
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    updatePlaybackState()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    updatePlaybackState()
                }
            }
            val sessionCallback = object : MediaSession.Callback() {
                override fun onPrepare() {
                    player?.prepare()
                }

                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    seekForward()
                }

                override fun onSkipToPrevious() {
                    seekBack()
                }

                override fun onFastForward() {
                    seekForward()
                }

                override fun onRewind() {
                    seekBack()
                }

                override fun onStop() {
                    stop()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos)
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    player?.setPlaybackSpeed(speed)
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        INTENT_REWIND -> seekBack()
                        INTENT_FAST_FORWARD -> seekForward()
                    }
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val eventHandled = super.onMediaButtonEvent(mediaButtonIntent)
                    return if (eventHandled) {
                        true
                    } else {
                        if (mediaButtonIntent.action == Intent.ACTION_MEDIA_BUTTON) {
                            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                            }
                            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                                when (keyEvent.keyCode) {
                                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                        seekBack()
                                        true
                                    }
                                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                        seekForward()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        } else false
                    }
                }
            }
            val player = ExoPlayer.Builder(this).apply {
                setLoadControl(
                    DefaultLoadControl.Builder().apply {
                        setBufferDurationsMs(
                            prefs().getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                            prefs().getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                            // TV: 1 s of video to start instead of 2 when the setting was not changed (after a stall it
                            // still waits for its own setting, 2 s)
                            prefs().getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull()
                                ?.let { if (it == 2000 && resources.getBoolean(R.bool.isTv)) 1000 else it } ?: 2000,
                            prefs().getString(C.PLAYER_BUFFER_REBUFFER, "2000")?.toIntOrNull() ?: 2000
                        )
                    }.build()
                )
                if (resources.getBoolean(R.bool.isTv)) {
                    // TV: the sound of a film, the audio focus is held by the app for every player (TvPlaybackSignals),
                    // and Bluetooth keyboards / remotes / HDMI audio sinks going idle must never pause the stream
                    setAudioAttributes(com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.playerAttributes, false)
                    setHandleAudioBecomingNoisy(false)
                } else {
                    setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false))
                    setHandleAudioBecomingNoisy(prefs().getBoolean(C.PLAYER_HANDLE_AUDIO_BECOMING_NOISY, true))
                }
                setSeekBackIncrementMs((prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10) * 1000)
                setSeekForwardIncrementMs((prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10) * 1000)
                setBandwidthMeter(getBandwidthMeter(this@ExoPlayerService))
                setTrackSelector(createTrackSelector())
            }.build()
            this.player = player
            player.addListener(playerListener)
            if (BuildConfig.DEBUG) {
                // debug: time of the playlists and of the first piece of video of a stream that starts
                player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onLoadCompleted(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
                    ) {
                        when (mediaLoadData.dataType) {
                            androidx.media3.common.C.DATA_TYPE_MANIFEST -> TvLoadTimer.markPlaylist()
                            androidx.media3.common.C.DATA_TYPE_MEDIA -> TvLoadTimer.mark("Premier morceau de vidéo")
                        }
                    }
                })
            }
            val session = MediaSession(this, "ExoPlayerService")
            this.session = session
            session.setCallback(sessionCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    session.setMediaButtonBroadcastReceiver(ComponentName(this, MediaButtonReceiver::class.java))
                } catch (e: IllegalArgumentException) {
                    // https://github.com/androidx/media/issues/1730
                }
            } else {
                @Suppress("DEPRECATION")
                session.setMediaButtonReceiver(
                    PendingIntent.getBroadcast(this, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver::class.java), PendingIntent.FLAG_MUTABLE)
                )
            }
            if (resources.getBoolean(R.bool.isTv)) {
                // TV: shown to the system as the sessions of the usual players (media buttons, transport controls, a queue
                // of one item), with the sound of a film: the launchers looking at it see a playback going on
                @Suppress("DEPRECATION")
                session.setFlags(7)
                session.setPlaybackToLocal(com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.systemAttributes)
            }
            session.isActive = true
            notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = getString(R.string.notification_playback_channel_id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                notificationManager?.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        ContextCompat.getString(this, R.string.notification_playback_channel_title),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            setShowBadge(false)
                        }
                    }
                )
            }
            start(restorePauseState)
        }
    }

    private fun start(restorePauseState: Boolean) {
        lifecycleScope.launch {
            restorePlaybackState()
            experimentalFallback = ExperimentalFallback.NONE
            clearExperimentalBadgeNotice()
            when (type) {
                STREAM -> {
                    TvExperimentalAdblock.normalizePrefs(this@ExoPlayerService)
                    val expEnabled = TvExperimentalAdblock.isExperimentalEnabled(this@ExoPlayerService)
                    val wasExpUrl = playlistUrl?.contains("/tv_exp_vod/") == true
                    if (expEnabled || wasExpUrl) {
                        qualities = null
                        playlistUrl = null
                        restorePlaylist = false
                    }
                    experimentalVodActive = false
                    experimentalVodUserRewound = false
                    experimentalVodCreatedAtMs = null
                    experimentalVodUpgradeJob?.cancel()
                    experimentalVodUpgradeJob = null
                    intentionalStopOrPause = (restorePauseState && paused) || quality?.name == VideoQuality.CHAT_ONLY_QUALITY
                    streamEnded = false
                    liveRecoveryAttempt = 0
                    liveRecoveryJob?.cancel()
                    liveRecoveryJob = null
                    started = true
                    serviceListener?.started()
                    if (!qualities.isNullOrEmpty()) {
                        if (quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                            serviceListener?.changePlayerMode()
                            player?.let { player ->
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                }.build()
                            }
                        }
                    } else if (expEnabled) {
                        useCustomProxy = false
                        useStreamProxy = false
                    } else {
                        useCustomProxy = prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                        if (!useCustomProxy) {
                            useStreamProxy = prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                        }
                    }
                    if (useCustomProxy) {
                        customProxyList = xtraModule.playerRepository.getCustomProxies().filter {
                            it.enabled && !it.url.isNullOrBlank()
                        }.sortedBy { it.position }
                    }
                    // the custom proxies are the first backups of the ad blocker while an ad plays (in the mode "always", the
                    // other proxies than the one of the stream when an ad comes through it), back to the stream once it is over
                    val adsOnlyProxies = if (useCustomProxy) {
                        channelLogin?.let { login ->
                            customProxyList.orEmpty().mapIndexedNotNull { index, proxy ->
                                customProxyUrl(proxy, login)?.let { url -> VideoSwap(AD_PROXY_PLATFORM, url, index) }
                            }
                        }.orEmpty()
                    } else emptyList()
                    // option: the custom proxies only while an ad plays, the stream itself straight from Twitch (low latency, 1440p)
                    if (useCustomProxy && prefs().getString(C.PLAYER_CUSTOM_PROXY_MODE, "0") == "1") {
                        useCustomProxy = false
                    }
                    if (useStreamProxy) {
                        streamProxyList = xtraModule.playerRepository.getStreamProxies().filter {
                            it.enabled && !it.host.isNullOrBlank() && it.port != null
                                    && (it.proxyPlaybackAccessToken || it.proxyMultivariantPlaylist || it.proxyMediaPlaylist)
                        }.sortedBy { it.position }
                    }
                    val adblock = TvExperimentalAdblock.isSwapAdblockEnabled(this@ExoPlayerService)
                    // the ad blocker also backs up the proxies: if an ad still comes through them, the playlist of another player type is used
                    useVideoSwap = (!useCustomProxy && !useStreamProxy && prefs().getBoolean(C.PLAYER_USE_VIDEO_SWAP, true)) || adblock || adsOnlyProxies.isNotEmpty()
                    if (useVideoSwap) {
                        videoSwapList = adsOnlyProxies + xtraModule.playerRepository.getVideoSwapItems(this@ExoPlayerService).filter {
                            it.enabled && !it.platform.isNullOrBlank() && !it.playerType.isNullOrBlank()
                        }.sortedBy { it.position }.ifEmpty {
                            // ad blocker: like the VAFT userscript, switch to the playlist of another player type while ads play
                            if (adblock) ADBLOCK_PLAYER_TYPES.mapIndexed { index, (platform, playerType) -> VideoSwap(platform, playerType, index) } else emptyList()
                        }
                    }
                    // the custom proxies are checked meanwhile: the offline ones are not tried (from the next stream for the one
                    // of the stream in the mode "always", the stream does not wait for it)
                    adsOnlyProxies.mapNotNull { swap -> swap.playerType?.let { url -> adProxyHost(swap)?.let { it to url } } }.forEach { (host, url) ->
                        lifecycleScope.launch {
                            if (!TvProxyStatus.reachable(this@ExoPlayerService, url)) {
                                TvProxyStatus.markFailed(host)
                            }
                        }
                    }
                    loadStream(restorePauseState)
                }
                VIDEO -> {
                    started = true
                    serviceListener?.started()
                    if (videoId != null) {
                        if (!qualities.isNullOrEmpty() && quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                            serviceListener?.changePlayerMode()
                            player?.let { player ->
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                }.build()
                            }
                        }
                        loadVideo(restorePauseState)
                        if (title == null) {
                            updateVideoInfo()
                        }
                    } else {
                        videoStartPosition = savedPosition ?: 0
                        qualities?.let { list ->
                            qualities = list
                                .sortedByDescending { it.bitrate }
                                .sortedByDescending { it.frameRate }
                                .sortedByDescending { it.resolution }
                                .toMutableList().apply {
                                    find { it.name.equals("source", true) }?.let { source ->
                                        remove(source)
                                        add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                    }
                                    val audio = find { it.name?.startsWith("audio", true) == true }
                                    audio?.let { remove(it) }
                                    add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio?.resolution, audio?.frameRate, audio?.bitrate, audio?.codecs, audio?.url))
                                }
                            setDefaultQuality()
                            serviceListener?.changePlayerMode()
                            val url = quality?.url
                            if (url != null) {
                                player?.let { player ->
                                    if (quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                        }.build()
                                    }
                                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                                    player.setMediaSource(
                                        HlsMediaSource.Factory(
                                            DefaultDataSource.Factory(
                                                this@ExoPlayerService,
                                                when {
                                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                                    }
                                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                                        CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                                    }
                                                    else -> {
                                                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null, null, null, null) { false }
                                                    }
                                                }
                                            )
                                        ).apply {
                                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                        }.createMediaSource(
                                            MediaItem.fromUri(url)
                                        )
                                    )
                                    player.volume = userVolume()
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = !restorePauseState || !paused
                                    player.seekTo(savedPosition ?: 0)
                                }
                            } else {
                                videoStartPosition = null
                            }
                        } ?: run {
                            videoStartPosition = null
                        }
                    }
                }
                CLIP -> {
                    started = true
                    serviceListener?.started()
                    loadClip(restorePauseState)
                }
                OFFLINE_VIDEO -> {
                    offlineVideoId?.let { id ->
                        val video = xtraModule.offlineVideosRepository.getById(id)
                        if (video != null) {
                            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                                video.lastWatchPosition
                            } else {
                                null
                            } ?: savedPosition ?: 0
                            chatUrl = video.chatUrl
                            started = true
                            serviceListener?.started()
                            if (qualities.isNullOrEmpty()) {
                                qualities = listOf(
                                    VideoQuality(VideoQuality.SOURCE_QUALITY, url = video.url),
                                    VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY),
                                )
                                setDefaultQuality()
                            }
                            serviceListener?.changePlayerMode()
                            val url = quality?.url ?: qualities?.firstOrNull()?.url
                            if (url != null) {
                                player?.let { player ->
                                    if (quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                        }.build()
                                    }
                                    player.setMediaItem(MediaItem.fromUri(url))
                                    player.volume = userVolume()
                                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                                    player.prepare()
                                    player.playWhenReady = true
                                    player.seekTo(playbackPosition)
                                }
                            }
                        }
                    }
                }
                else -> stopSelf()
            }
        }
    }

    private var loadingStreamCount = 0

    private fun scheduleExperimentalVodUpgrade(generation: Int, login: String) {
        experimentalVodUpgradeJob?.cancel()
        experimentalVodUpgradeJob = lifecycleScope.launch {
            repeat(TvExperimentalAdblock.UPGRADE_MAX_ATTEMPTS) {
                delay(TvExperimentalAdblock.UPGRADE_POLL_INTERVAL_MS)
                if (generation != streamGeneration || !created || !started || type != STREAM ||
                    intentionalStopOrPause || streamEnded || experimentalVodActive ||
                    !TvExperimentalAdblock.isExperimentalEnabled(this@ExoPlayerService) ||
                    channelLogin != login || quality?.name == VideoQuality.CHAT_ONLY_QUALITY
                ) {
                    return@launch
                }
                val outcome = TvExperimentalAdblock.resolveLiveVod(
                    context = this@ExoPlayerService,
                    xtraModule = xtraModule,
                    channelLogin = login,
                    streamIdHint = streamId,
                    createdAtHint = createdAt,
                    fallbackH264 = fallbackH264ForStream,
                )
                if (!outcome.channelIsLive) {
                    return@launch
                }
                val vod = outcome.vod
                if (vod != null && vod.vodDurationMs >= TvExperimentalAdblock.UPGRADE_MIN_VOD_DURATION_MS) {
                    if (generation != streamGeneration || !created || !started || type != STREAM ||
                        intentionalStopOrPause || streamEnded || experimentalVodActive ||
                        !TvExperimentalAdblock.isExperimentalEnabled(this@ExoPlayerService)
                    ) {
                        return@launch
                    }
                    // Completely tear down any live VAFT secondary player and ad state before switching onto VOD
                    freezeWatchJob?.cancel()
                    freezeWatchJob = null
                    adMainWatchJob?.cancel()
                    adMainWatchJob = null
                    secondaryLoadJob?.cancel()
                    secondaryLoadJob = null
                    adDelayJob?.cancel()
                    adDelayJob = null
                    if (secondaryPlayer != null || videoSwapActive || proxyMediaPlaylist) {
                        stopSecondaryPlayer()
                    }
                    playingAds = false
                    backLiveFrom = 0L
                    backLiveProgress = 0f
                    resetAdPod()
                    if (hidden) {
                        hidden = false
                    }
                    if (streamId.isNullOrBlank()) streamId = outcome.streamId
                    if (createdAt.isNullOrBlank()) createdAt = outcome.createdAtIso
                    experimentalVodCreatedAtMs = outcome.createdAtMs
                    experimentalVodActive = true
                    experimentalVodUserRewound = false
                    experimentalFallback = ExperimentalFallback.NONE
                    qualities = null
                    restorePlaylist = false
                    playlistUrl = vod.masterUri.toString()
                    checkFirstPlaylist = false
                    player?.let { p ->
                        val isAudioOnly = quality?.name == VideoQuality.AUDIO_ONLY_QUALITY || videoHidden
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, isAudioOnly)
                        }.build()
                        p.setMediaSource(TvExperimentalAdblock.buildMediaSource(this@ExoPlayerService, xtraModule, vod.masterUri))
                        p.volume = userVolume()
                        p.setPlaybackSpeed(1f)
                        p.prepare()
                        p.playWhenReady = !paused
                    }
                    showExperimentalBadgeNotice(R.string.tv_adblock_exp_switched)
                    return@launch
                } else if (vod == null && outcome.streamUptimeMs != null && outcome.streamUptimeMs >= TvExperimentalAdblock.NEW_STREAM_MAX_UPTIME_MS) {
                    // Streamer does not support ongoing recording: keep Swap Adblock active without further polling
                    if (experimentalFallback != ExperimentalFallback.UNSUPPORTED) {
                        experimentalFallback = ExperimentalFallback.UNSUPPORTED
                        showExperimentalBadgeNotice(R.string.tv_adblock_exp_unsupported_title, R.string.tv_adblock_exp_fallback_swap)
                    }
                    return@launch
                }
            }
            if (generation == streamGeneration && created && started && type == STREAM &&
                !experimentalVodActive && experimentalFallback == ExperimentalFallback.NEW_STREAM &&
                TvExperimentalAdblock.isExperimentalEnabled(this@ExoPlayerService)
            ) {
                experimentalFallback = ExperimentalFallback.UNSUPPORTED
                showExperimentalBadgeNotice(R.string.tv_adblock_exp_unsupported_title, R.string.tv_adblock_exp_fallback_swap)
            }
        }
    }

    private suspend fun loadStream(restorePauseState: Boolean = false, restart: Boolean = false) {
        loadingStreamCount++
        try {
        experimentalVodUpgradeJob?.cancel()
        experimentalVodUpgradeJob = null
        // the stream loads (again): its first seconds are not counted as stops for the quality of Auto
        playingSince = 0L
        if (!restart) {
            experimentalVodUserRewound = false
            experimentalFallback = ExperimentalFallback.NONE
            clearExperimentalBadgeNotice()
            // an ad at the start of the stream is looked for in its whole first playlist
            checkFirstPlaylist = true
            firstPlaylistAd = false
            adRollType = null
            // the loading picture stays until a picture of the stream itself is shown
            mainFrameRendered = false
            streamPictureShown = false
            TvLoadTimer.mark("Ouverture du lecteur")
        } else {
            // a restart (ad blocker, error): the player asks the access itself
            channelLogin?.let { TvStreamPrefetch.forget(it) }
        }
        channelLogin?.let { channelLogin ->
            if (TvExperimentalAdblock.isExperimentalEnabled(this@ExoPlayerService) && quality?.name != VideoQuality.CHAT_ONLY_QUALITY) {
                val outcome = TvExperimentalAdblock.resolveLiveVod(
                    context = this@ExoPlayerService,
                    xtraModule = xtraModule,
                    channelLogin = channelLogin,
                    streamIdHint = streamId,
                    createdAtHint = createdAt,
                    fallbackH264 = fallbackH264ForStream,
                )
                if (streamId.isNullOrBlank() && !outcome.streamId.isNullOrBlank()) {
                    streamId = outcome.streamId
                }
                if (createdAt.isNullOrBlank() && !outcome.createdAtIso.isNullOrBlank()) {
                    createdAt = outcome.createdAtIso
                }
                experimentalVodCreatedAtMs = outcome.createdAtMs
                val vod = outcome.vod
                if (vod != null && vod.vodDurationMs >= TvExperimentalAdblock.MIN_INITIAL_VOD_DURATION_MS) {
                    experimentalVodActive = true
                    experimentalVodUserRewound = false
                    experimentalFallback = ExperimentalFallback.NONE
                    clearExperimentalBadgeNotice()
                    qualities = null
                    restorePlaylist = false
                    playlistUrl = vod.masterUri.toString()
                    pendingProxyStat = null
                    checkFirstPlaylist = false
                    val isTvDevice = resources.getBoolean(R.bool.isTv)
                    if (isTvDevice && !videoHidden && quality?.name != VideoQuality.AUDIO_ONLY_QUALITY && serviceListener == null) {
                        kotlinx.coroutines.withTimeoutOrNull(400L) {
                            while (serviceListener == null) delay(10L)
                        }
                    }
                    player?.let { player ->
                        player.setMediaSource(TvExperimentalAdblock.buildMediaSource(this@ExoPlayerService, xtraModule, vod.masterUri))
                        if (fallbackH264ForStream) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setMaxVideoSize(1920, 1080)
                                .setForceHighestSupportedBitrate(false)
                                .build()
                        }
                        player.volume = userVolume()
                        player.setPlaybackSpeed(1f)
                        player.prepare()
                        player.playWhenReady = !restorePauseState || !paused
                    }
                    return
                } else {
                    experimentalVodActive = false
                    experimentalVodUserRewound = false
                    if (playlistUrl?.contains("/tv_exp_vod/") == true) {
                        playlistUrl = null
                        qualities = null
                    }
                    val isKnownUnsupported = outcome.channelIsLive &&
                        outcome.streamUptimeMs != null &&
                        outcome.streamUptimeMs >= TvExperimentalAdblock.NEW_STREAM_MAX_UPTIME_MS &&
                        vod == null
                    if (isKnownUnsupported) {
                        experimentalFallback = ExperimentalFallback.UNSUPPORTED
                        if (!restart) {
                            showExperimentalBadgeNotice(R.string.tv_adblock_exp_unsupported_title, R.string.tv_adblock_exp_fallback_swap)
                        }
                    } else {
                        experimentalFallback = ExperimentalFallback.NEW_STREAM
                        if (!restart) {
                            showExperimentalBadgeNotice(R.string.tv_adblock_exp_new_stream_title, R.string.tv_adblock_exp_temp_swap)
                        }
                        scheduleExperimentalVodUpgrade(streamGeneration, channelLogin)
                    }
                }
            } else {
                experimentalVodActive = false
                experimentalVodUserRewound = false
                experimentalFallback = ExperimentalFallback.NONE
                clearExperimentalBadgeNotice()
                experimentalVodCreatedAtMs = null
                if (playlistUrl?.contains("/tv_exp_vod/") == true) {
                    playlistUrl = null
                    qualities = null
                }
            }
            var streamProxy = if (useStreamProxy) {
                streamProxyList?.getOrNull(currentStreamProxy).also {
                    if (it == null) {
                        useStreamProxy = false
                    }
                }
            } else null
            val needsFreshUrl = restart || qualities.isNullOrEmpty()
            var fetchedUrl: String? = null
            waitingIntegrity = false
            if (needsFreshUrl) {
                val proxyUrl = if (useCustomProxy) {
                    // the custom proxies known offline or geo-blocked (403) on this channel are not tried
                    while (customProxyList?.getOrNull(currentCustomProxy)?.let { customProxyHost(it) }?.let {
                        TvProxyStatus.isFailed(it) || geoBlockedProxiesForChannel.contains(it)
                    } == true) {
                        currentCustomProxy += 1
                    }
                    customProxyList?.getOrNull(currentCustomProxy)?.let { proxy -> customProxyUrl(proxy, channelLogin) }
                } else null
                if (proxyUrl != null) {
                    fetchedUrl = proxyUrl
                    playlistUrl = proxyUrl
                } else {
                    useCustomProxy = false
                    val codecOverride = if (fallbackH264ForStream) "h264" else null
                    val url = if (streamProxy?.proxyPlaybackAccessToken == true) {
                        var url: String?
                        while (true) {
                            val result = getStreamPlaylistUrl(channelLogin, streamProxy, codecOverride)
                            if (result != null) {
                                url = result
                                break
                            } else {
                                val host = streamProxy?.host
                                currentStreamProxy += 1
                                if (host != null) {
                                    serviceListener?.toast(getString(R.string.proxy_error, host), Toast.LENGTH_LONG)
                                }
                                streamProxy = streamProxyList?.getOrNull(currentStreamProxy)
                                if (streamProxy == null) {
                                    useStreamProxy = false
                                    url = getStreamPlaylistUrl(channelLogin, supportedCodecsOverride = codecOverride)
                                    break
                                } else {
                                    if (!streamProxy.proxyPlaybackAccessToken) {
                                        url = getStreamPlaylistUrl(channelLogin, supportedCodecsOverride = codecOverride)
                                        break
                                    }
                                }
                            }
                        }
                        url
                    } else {
                        // asked as soon as OK was pressed (same request, no proxy): not asked a second time unless falling back to H.264
                        (if (!restart && !fallbackH264ForStream) TvStreamPrefetch.take(channelLogin) else null)
                            ?: getStreamPlaylistUrl(channelLogin, supportedCodecsOverride = codecOverride)
                    }
                    fetchedUrl = url
                    if (url != null || !restart) {
                        playlistUrl = url
                    }
                }
            }
            val url = if (needsFreshUrl) fetchedUrl else playlistUrl
            TvLoadTimer.mark("Accès Twitch (jeton)")
            if (url != null) {
                // statistics of the home: the proxy the stream goes through (the list of the app or added ones),
                // counted once the stream plays so a proxy that fails is not counted
                pendingProxyStat = when {
                    useCustomProxy -> url.toUri().host?.removePrefix("www.")
                    else -> streamProxy?.takeIf { it.proxyPlaybackAccessToken || it.proxyMultivariantPlaylist }?.host
                }
                if (isTvDevice && !videoHidden && quality?.name != VideoQuality.AUDIO_ONLY_QUALITY && serviceListener == null) {
                    // Wait for the new player fragment to bind its SurfaceView so MediaCodec configures directly on the real surface
                    kotlinx.coroutines.withTimeoutOrNull(400L) {
                        while (serviceListener == null) delay(10L)
                    }
                }
                player?.let { player ->
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                    val customProxyUrl = if (useCustomProxy) {
                        url
                    } else null
                    val proxyTimeout = prefs().getString(C.PROXY_TIMEOUT, "3000")?.toIntOrNull() ?: 3000
                    val proxyHost = streamProxy?.host
                    val proxyPort = streamProxy?.port
                    val proxyUser = streamProxy?.username
                    val proxyPassword = streamProxy?.password
                    val proxyMultivariantPlaylist = streamProxy?.proxyMultivariantPlaylist == true && !proxyHost.isNullOrBlank() && proxyPort != null
                    player.setMediaSource(
                        HlsMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val proxyClient = if (proxyMultivariantPlaylist) {
                                            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                listOf(android.util.Pair("Proxy-Authorization", Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)))
                                            } else emptyList()
                                            val builder = HttpEngine.Builder(application)
                                            try {
                                                builder.setProxyOptions(ProxyOptions.fromProxyList(
                                                    listOf(
                                                        android.net.http.Proxy.createHttpProxy(
                                                            android.net.http.Proxy.SCHEME_HTTP,
                                                            proxyHost,
                                                            proxyPort,
                                                            xtraModule.cronetExecutor.value,
                                                            object : android.net.http.Proxy.HttpConnectCallback {
                                                                override fun onBeforeRequest(request: android.net.http.Proxy.HttpConnectCallback.Request) {
                                                                    request.proceed(proxyHeaders)
                                                                }

                                                                override fun onResponseReceived(responseHeaders: List<android.util.Pair<String?, String?>?>, statusCode: Int): Int {
                                                                    return android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED
                                                                }
                                                            }
                                                        )
                                                    ),
                                                    ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
                                                ))
                                            } catch (e: NoClassDefFoundError) {
                                                null
                                            }?.build()
                                        } else null
                                        val multivariantPlaylistProxyOkHttpClient = if (proxyMultivariantPlaylist && proxyClient == null) {
                                            xtraModule.okHttpClient.value.newBuilder().apply {
                                                val proxyTimeout = proxyTimeout.toLong()
                                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                proxySelector(
                                                    object : ProxySelector() {
                                                        override fun select(u: URI): List<Proxy> {
                                                            return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                            } else {
                                                                listOf(Proxy.NO_PROXY)
                                                            }
                                                        }

                                                        override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                    }
                                                )
                                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                    proxyAuthenticator { _, response ->
                                                        response.request.newBuilder().header(
                                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                        ).build()
                                                    }
                                                }
                                            }.build()
                                        } else null
                                        HttpEngineDataSource.Factory(
                                            xtraModule.httpEngine.value,
                                            xtraModule.cronetExecutor.value,
                                            customProxyUrl,
                                            proxyTimeout,
                                            proxyMultivariantPlaylist,
                                            false,
                                            proxyClient,
                                            multivariantPlaylistProxyOkHttpClient,
                                            null
                                        ) { false }
                                    }
                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                        val proxyClient = if (proxyMultivariantPlaylist && CronetProvider.getAllProviders(application).any { it.isEnabled }) {
                                            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                mapOf("Proxy-Authorization" to Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)).entries.toList()
                                            } else emptyList()
                                            val builder = CronetEngine.Builder(application).apply {
                                                val userAgent = "Cronet/" + defaultUserAgent.substringAfter("Cronet/", "").substringBefore(')')
                                                setUserAgent(userAgent)
                                                @QuicOptions.Experimental
                                                setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
                                            }
                                            try {
                                                @org.chromium.net.ProxyOptions.Experimental
                                                builder.setProxyOptions(org.chromium.net.ProxyOptions(
                                                    listOf(
                                                        org.chromium.net.Proxy(
                                                            org.chromium.net.Proxy.HTTP,
                                                            proxyHost,
                                                            proxyPort,
                                                            xtraModule.cronetExecutor.value,
                                                            object : org.chromium.net.Proxy.Callback() {
                                                                override fun onBeforeTunnelRequest(request: org.chromium.net.Proxy.Callback.Request) {
                                                                    request.proceed(proxyHeaders)
                                                                }

                                                                override fun onTunnelHeadersReceived(responseHeaders: List<Map.Entry<String?, String?>?>, statusCode: Int): Boolean {
                                                                    return true
                                                                }
                                                            }
                                                        )
                                                    )
                                                ))
                                            } catch (e: UnsupportedOperationException) {
                                                null
                                            }?.build()
                                        } else null
                                        val multivariantPlaylistProxyOkHttpClient = if (proxyMultivariantPlaylist && proxyClient == null) {
                                            xtraModule.okHttpClient.value.newBuilder().apply {
                                                val proxyTimeout = proxyTimeout.toLong()
                                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                proxySelector(
                                                    object : ProxySelector() {
                                                        override fun select(u: URI): List<Proxy> {
                                                            return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                            } else {
                                                                listOf(Proxy.NO_PROXY)
                                                            }
                                                        }

                                                        override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                    }
                                                )
                                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                    proxyAuthenticator { _, response ->
                                                        response.request.newBuilder().header(
                                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                        ).build()
                                                    }
                                                }
                                            }.build()
                                        } else null
                                        CronetDataSource.Factory(
                                            xtraModule.cronetEngine.value,
                                            xtraModule.cronetExecutor.value,
                                            customProxyUrl,
                                            proxyTimeout,
                                            proxyMultivariantPlaylist,
                                            false,
                                            proxyClient,
                                            multivariantPlaylistProxyOkHttpClient,
                                            null
                                        ) { false }
                                    }
                                    else -> {
                                        val customProxyClient = if (customProxyUrl != null) {
                                            xtraModule.okHttpClient.value.newBuilder().apply {
                                                val proxyTimeout = proxyTimeout.toLong()
                                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            }.build()
                                        } else null
                                        val multivariantPlaylistProxyClient = if (proxyMultivariantPlaylist) {
                                            xtraModule.okHttpClient.value.newBuilder().apply {
                                                val proxyTimeout = proxyTimeout.toLong()
                                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                                proxySelector(
                                                    object : ProxySelector() {
                                                        override fun select(u: URI): List<Proxy> {
                                                            return if (Regex(MULTIVARIANT_PLAYLIST_REGEX).matches(u.host)) {
                                                                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                            } else {
                                                                listOf(Proxy.NO_PROXY)
                                                            }
                                                        }

                                                        override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                    }
                                                )
                                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                    proxyAuthenticator { _, response ->
                                                        response.request.newBuilder().header(
                                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                        ).build()
                                                    }
                                                }
                                            }.build()
                                        } else null
                                        OkHttpDataSource.Factory(
                                            xtraModule.okHttpClient.value,
                                            customProxyClient,
                                            customProxyUrl,
                                            multivariantPlaylistProxyClient,
                                            null
                                        ) { false }
                                    }
                                }
                            )
                        ).apply {
                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                            setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(
                                if (useCustomProxy || proxyMultivariantPlaylist) {
                                    1
                                } else {
                                    6
                                }
                            ))
                        }.createMediaSource(
                            MediaItem.Builder().apply {
                                setUri(url.toUri())
                                setMimeType(MimeTypes.APPLICATION_M3U8)
                                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                    prefs().getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                    prefs().getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                    prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                }.build())
                            }.build()
                        )
                    )
                    if (fallbackH264ForStream) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setMaxVideoSize(1920, 1080)
                            .setForceHighestSupportedBitrate(false)
                            .build()
                    }
                    player.volume = userVolume()
                    player.setPlaybackSpeed(1f)
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                }
            } else if (type == STREAM && !waitingIntegrity && !intentionalStopOrPause && !streamEnded && quality?.name != VideoQuality.CHAT_ONLY_QUALITY) {
                scheduleLiveRecovery(streamGeneration, channelLogin)
            }
        }
        } finally {
            loadingStreamCount = (loadingStreamCount - 1).coerceAtLeast(0)
        }
    }

    private var waitingIntegrity = false

    private suspend fun getStreamPlaylistUrl(
        channelLogin: String,
        streamProxy: StreamProxy? = null,
        supportedCodecsOverride: String? = null,
    ): String? {
        return try {
            loadStreamPlaylistUrl(this, xtraModule, channelLogin, streamProxy, supportedCodecsOverride)
        } catch (e: Exception) {
            if (e.message == C.FAILED_INTEGRITY_CHECK) {
                waitingIntegrity = true
                integrity.emit("refreshStream")
            }
            null
        }
    }

    private var videoPreviewLookupDone = false

    /** Position the video starts at while its playlist is still loaded: the timeline shows it instead of 00:00. */
    var videoStartPosition: Long? = null
        private set

    private suspend fun loadVideo(restorePauseState: Boolean = false) {
        videoId?.let { videoId ->
            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                videoId.toLongOrNull()?.let { xtraModule.playerRepository.getVideoPosition(it)?.position }
            } else {
                null
            } ?: savedPosition ?: 0
            videoStartPosition = playbackPosition
            if (qualities.isNullOrEmpty()) {
                val result = try {
                    xtraModule.playerRepository.loadVideoPlaylistUrl(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                        videoId = videoId,
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshVideo")
                    }
                    null
                }
                if (result != null) {
                    playlistUrl = result.first
                    backupQualities = result.second
                }
            }
            val url = if (skipAccessToken) {
                quality?.url
            } else {
                playlistUrl
            }
            if (url != null) {
                player?.let { player ->
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                    player.setMediaSource(
                        HlsMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                    }
                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                        CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                    }
                                    else -> {
                                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null, null, null, null) { false }
                                    }
                                }
                            )
                        ).apply {
                            setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                        }.createMediaSource(
                            MediaItem.fromUri(url)
                        )
                    )
                    player.volume = userVolume()
                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                    player.seekTo(playbackPosition)
                }
            } else {
                videoStartPosition = null
            }
        }
    }

    private suspend fun updateVideoInfo() {
        val video = try {
            val response = xtraModule.graphQLRepository.loadQueryVideo(
                networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                headers = TwitchApiHelper.getGQLHeaders(this),
                id = videoId
            )
            if (prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                    integrity.emit("refresh")
                    return
                }
            }
            response.data!!.let { item ->
                item.video?.let {
                    Video(
                        id = videoId,
                        channelId = it.owner?.id,
                        channelLogin = it.owner?.login,
                        channelName = it.owner?.displayName,
                        channelImageURL = it.owner?.profileImageURL,
                        gameId = it.game?.id,
                        gameSlug = it.game?.slug,
                        gameName = it.game?.displayName,
                        title = it.title,
                        thumbnailURL = it.previewThumbnailURL,
                        createdAt = it.createdAt?.toString(),
                        durationSeconds = it.lengthSeconds,
                        type = it.broadcastType?.toString(),
                        animatedPreviewURL = it.animatedPreviewURL,
                    )
                }
            }
        } catch (e: Exception) {
            val helixHeaders = TwitchApiHelper.getHelixHeaders(this)
            if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    xtraModule.helixRepository.getVideos(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        headers = helixHeaders,
                        ids = videoId?.let { listOf(it) }
                    ).data.firstOrNull()?.let {
                        Video(
                            id = it.id,
                            channelId = it.channelId,
                            channelLogin = it.channelLogin,
                            channelName = it.channelName,
                            title = it.title,
                            thumbnailURL = it.thumbnailURL,
                            createdAt = it.createdAt,
                            viewCount = it.viewCount,
                            durationSeconds = it.duration?.let { duration -> TwitchApiHelper.getDuration(duration) },
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        if (video != null) {
            channelId = video.channelId
            channelLogin = video.channelLogin
            channelName = video.channelName
            channelImage = video.channelImage
            gameId = video.gameId
            gameSlug = video.gameSlug
            gameName = video.gameName
            title = video.title
            thumbnail = video.thumbnail
            createdAt = video.createdAt
            durationSeconds = video.durationSeconds
            videoType = video.type
            videoAnimatedPreviewURL = video.animatedPreviewURL
            updateMetadata()
            updateNotification()
            serviceListener?.updateVideoInfo()
        }
    }

    private suspend fun loadClip(restorePauseState: Boolean = false) {
        clipId?.let { clipId ->
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            if (qualities.isNullOrEmpty()) {
                val list = try {
                    xtraModule.playerRepository.loadClipQualities(
                        networkLibrary = networkLibrary,
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService),
                        clipId = clipId,
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false)
                    )
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshClip")
                    }
                    null
                }
                if (list != null) {
                    val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")?.split(',') ?: emptyList()
                    val filtered = list.filterNot {
                        it.codecs?.substringBefore('.').let { codec ->
                            (codec == "av01" && !supportedCodecs.contains("av1")) || ((codec == "hev1" || codec == "hvc1") && !supportedCodecs.contains("h265"))
                        }
                    }
                    qualities = filtered
                        .sortedByDescending { it.bitrate }
                        .sortedByDescending { it.frameRate }
                        .sortedByDescending { it.resolution }
                        .toMutableList().apply {
                            add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY))
                        }
                    setDefaultQuality()
                }
            }
            serviceListener?.changePlayerMode()
            val url = quality?.url ?: qualities?.firstOrNull()?.url
            if (url != null) {
                player?.let { player ->
                    if (quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        }.build()
                    }
                    player.setMediaSource(
                        ProgressiveMediaSource.Factory(
                            DefaultDataSource.Factory(
                                this@ExoPlayerService,
                                when {
                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                        HttpEngineDataSource.Factory(xtraModule.httpEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                    }
                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                        CronetDataSource.Factory(xtraModule.cronetEngine.value, xtraModule.cronetExecutor.value, null, 0, false, false, null, null, null) { false }
                                    }
                                    else -> {
                                        OkHttpDataSource.Factory(xtraModule.okHttpClient.value, null, null, null, null) { false }
                                    }
                                }
                            )
                        ).createMediaSource(
                            MediaItem.fromUri(url)
                        )
                    )
                    player.volume = userVolume()
                    player.setPlaybackSpeed(prefs().getFloat(C.PLAYER_SPEED, 1f))
                    player.prepare()
                    player.playWhenReady = !restorePauseState || !paused
                    player.seekTo(savedPosition ?: 0)
                }
            }
        }
    }

    fun retry(item: String) {
        when (item) {
            "refreshStream" -> {
                streamEnded = false
                liveRecoveryJob?.cancel()
                liveRecoveryJob = null
                lifecycleScope.launch {
                    loadStream()
                }
            }
            "refreshVideo" -> {
                lifecycleScope.launch {
                    loadVideo()
                }
            }
            "refreshClip" -> {
                lifecycleScope.launch {
                    loadClip()
                }
            }
        }
    }

    private fun setStreamMediaUri(player: ExoPlayer, mediaItem: MediaItem, uriString: String, seekPositionMs: Long) {
        if (experimentalVodActive) {
            player.setMediaSource(TvExperimentalAdblock.buildMediaSource(this, xtraModule, uriString.toUri()), seekPositionMs)
            player.prepare()
        } else {
            player.setMediaItem(mediaItem.buildUpon().setUri(uriString).build())
            player.prepare()
            player.seekTo(seekPositionMs)
        }
    }

    fun changeQuality(selectedQuality: VideoQuality?) {
        val wasChatOnly = quality?.name == VideoQuality.CHAT_ONLY_QUALITY
        previousQuality = quality
        quality = selectedQuality
        quality?.let { quality ->
            if (wasChatOnly && quality.name != VideoQuality.CHAT_ONLY_QUALITY) {
                intentionalStopOrPause = false
                if (player?.currentMediaItem == null && type == STREAM) {
                    restartPlayer()
                    return
                }
            }
            player?.let { player ->
                player.currentMediaItem?.let { mediaItem ->
                    when (quality.name) {
                        VideoQuality.AUTO_QUALITY -> {
                            if (restorePlaylist) {
                                restorePlaylist = false
                                playlistUrl?.let { uri ->
                                    if (mediaItem.localConfiguration?.uri != uri.toUri()) {
                                        ignorePlaylistUpdate = true
                                        val position = player.currentPosition
                                        setStreamMediaUri(player, mediaItem, uri, position)
                                    }
                                }
                            } else {
                                player.prepare()
                            }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                        }
                        VideoQuality.AUDIO_ONLY_QUALITY -> {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            quality.url?.let {
                                val position = player.currentPosition
                                if (qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null) {
                                    restorePlaylist = true
                                    ignorePlaylistUpdate = true
                                }
                                setStreamMediaUri(player, mediaItem, it, position)
                            }
                        }
                        VideoQuality.CHAT_ONLY_QUALITY -> {
                            intentionalStopOrPause = true
                            liveRecoveryJob?.cancel()
                            liveRecoveryJob = null
                            experimentalVodUpgradeJob?.cancel()
                            experimentalVodUpgradeJob = null
                            player.stop()
                        }
                        else -> {
                            if (qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null) {
                                if (restorePlaylist) {
                                    restorePlaylist = false
                                    playlistUrl?.let { uri ->
                                        ignorePlaylistUpdate = true
                                        val position = player.currentPosition
                                        setStreamMediaUri(player, mediaItem, uri, position)
                                    }
                                } else {
                                    player.prepare()
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    if (!player.currentTracks.isEmpty) {
                                        player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let { trackGroup ->
                                            if (trackGroup.mediaTrackGroup.length > 0) {
                                                val formats = mutableListOf<Pair<Int, Format>>()
                                                for (i in 0 until trackGroup.mediaTrackGroup.length) {
                                                    formats.add(i to trackGroup.mediaTrackGroup.getFormat(i))
                                                }
                                                val list = formats
                                                    .sortedByDescending { it.second.bitrate }
                                                    .sortedByDescending { it.second.frameRate }
                                                    .sortedByDescending { it.second.height }
                                                val chosen = if (quality.name == VideoQuality.SOURCE_QUALITY) {
                                                    list.firstOrNull()
                                                } else if (quality.resolution != null) {
                                                    list.find {
                                                        (quality.resolution == it.second.height
                                                                && (quality.frameRate?.let { fps -> floor(fps) } ?: 30f) >= floor(it.second.frameRate)
                                                                && (quality.bitrate == null || quality.bitrate >= it.second.bitrate))
                                                                || quality.resolution > it.second.height
                                                                || it == list.last()
                                                    }
                                                } else null
                                                if (chosen != null) {
                                                    setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, chosen.first))
                                                } else {
                                                    setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0))
                                                }
                                            }
                                        }
                                    }
                                }.build()
                            } else {
                                player.currentMediaItem?.let {
                                    val targetUrl = quality.url
                                    if (targetUrl != null && it.localConfiguration?.uri?.toString() != targetUrl) {
                                        val position = player.currentPosition
                                        setStreamMediaUri(player, it, targetUrl, position)
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                            }
                        }
                    }
                    val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                    if ((!cellular && prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved") == "saved") || (cellular && prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                        prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
                    }
                }
            }
        }
    }

    fun toggleSubtitles(enabled: Boolean) {
        player?.let { player ->
            if (enabled) {
                player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }?.let {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                        .build()
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
    }

    /**
     * Ad in the playlist: its last segment, or any of its segments for the first playlist of a stream (an ad at the
     * start, "pre-roll", is then caught before its end).
     */
    private fun checkPlaylist(playlist: HlsMediaPlaylist?, mainPlayer: Boolean = false): Boolean {
        if (playlist != null && mainPlayer && checkFirstPlaylist) {
            checkFirstPlaylist = false
            if (playlist.segments.any { isAdSegment(playlist, it) }) {
                firstPlaylistAd = true
                return true
            }
        }
        return playlist?.segments?.lastOrNull()?.let { isAdSegment(playlist, it) } == true
    }

    /** True for the first media playlist after a stream started to load. */
    private var checkFirstPlaylist = false

    /** The first playlist of the stream had an ad: a pre-roll. */
    private var firstPlaylistAd = false

    /** Start and end of one ad, in the time of the device. */
    private class AdTimes(val start: Long, val end: Long)

    /** Times of the ads of the break, by their id: each one is computed once, when its mark first shows. */
    private val adTimes = HashMap<String, AdTimes>()
    /** The ad playing now: its place in the break (1 based, 0: not known), the number of ads (0: not known) and its times. */
    private var adPodPosition = 0
    private var adPodLength = 0
    private var adPodTimes: AdTimes? = null
    /** End of the last ad once the badge shows "Back to live" (0: not shown), it stays until the badge goes away. */
    private var backLiveFrom = 0L
    private var backLiveProgress = 0f

    private fun resetAdPod() {
        adTimes.clear()
        adPodPosition = 0
        adPodLength = 0
        adPodTimes = null
    }

    /**
     * The ad playing now, like the proxyivshook userscript: each ad of Twitch has its mark (EXT-X-DATERANGE of a stitched
     * ad: id, start, duration, place and number of ads) at its own start, so the newest one is the ad playing. Its end is
     * set once, from the live edge of the playlist (the date of its last segment), so the clock of the device does not matter.
     */
    private fun updateAdPod(playlist: HlsMediaPlaylist?) {
        playlist ?: return
        val last = playlist.segments.lastOrNull()
        val liveEdge = if (playlist.hasProgramDateTime && last != null) (playlist.startTimeUs + last.relativeStartTimeUs + last.durationUs) / 1000 else null
        var currentId: String? = null
        var currentStart: Long? = null
        var currentDuration: Double? = null
        var currentPosition: Int? = null
        var currentLength: Int? = null
        playlist.tags.forEach { tag ->
            if (tag.startsWith("#EXT-X-DATERANGE") && tag.contains("stitched", true)) {
                val start = Regex("START-DATE=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)?.let { kotlin.time.Instant.parseOrNull(it)?.toEpochMilliseconds() }
                if (currentId == null || (start != null && (currentStart == null || start > currentStart!!))) {
                    currentId = Regex("(?<![A-Z-])ID=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: tag
                    currentStart = start
                    currentDuration = Regex("(?<![A-Z-])DURATION=([\\d.]+)").find(tag)?.groupValues?.get(1)?.toDoubleOrNull()
                    currentPosition = Regex("X-TV-TWITCH-AD-POD-POSITION=\"?(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    currentLength = Regex("X-TV-TWITCH-AD-POD-LENGTH=\"?(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        }
        val id = currentId ?: return
        adPodPosition = currentPosition?.let { it + 1 } ?: 0
        adPodLength = currentLength?.takeIf { it > 0 } ?: 0
        val start = currentStart
        val duration = currentDuration
        adPodTimes = if (start != null && duration != null && duration > 0) {
            adTimes.getOrPut(id) {
                val elapsed = liveEdge?.let { ((it - start) / 1000.0).coerceIn(0.0, duration) } ?: 0.0
                val end = System.currentTimeMillis() + ((duration - elapsed) * 1000).toLong()
                AdTimes(end - (duration * 1000).toLong(), end)
            }
        } else null
    }

    /**
     * The break is over (the stream is back): how long it took after the end of its last ad is kept, the bar of
     * "Back to live" fills in that time the next time.
     */
    private fun adBreakOver() {
        val start = backLiveFrom.takeIf { it > 0L } ?: adPodTimes?.end
        if (start != null) {
            val measured = System.currentTimeMillis() - start
            if (measured in 400..20_000) {
                val previous = prefs().getLong(AD_RETURN_DELAY, DEFAULT_AD_RETURN_DELAY).coerceIn(1000L, 10_000L)
                prefs().edit().putLong(AD_RETURN_DELAY, (previous + measured) / 2).apply()
            }
        }
        backLiveFrom = 0L
        backLiveProgress = 0f
        stopVideoSwap = false
        currentVideoSwapItem = 0
        resetAdPod()
    }

    /** What the badge of the ad blocker shows: two lines and the bar (0 to 1, null: no bar). */
    class AdblockInfo(val title: String, val source: String?, val progress: Float?)

    /** Kind of the ad being blocked, "preroll" or "midroll", for the badge of the ad blocker. */
    private var adRollType: String? = null

    /** The kind of ad Twitch writes in its marks (X-TV-TWITCH-AD-ROLL-TYPE), null when it is not there. */
    private fun rollTypeOf(playlist: HlsMediaPlaylist?): String? {
        playlist ?: return null
        val value = playlist.interstitials.firstNotNullOfOrNull { dateRange ->
            dateRange.clientDefinedAttributes.find { it.name == "X-TV-TWITCH-AD-ROLL-TYPE" }?.textValue
        } ?: playlist.tags.firstNotNullOfOrNull { tag ->
            Regex("X-TV-TWITCH-AD-ROLL-TYPE=\"?([A-Za-z]+)").find(tag)?.groupValues?.get(1)
        }
        return when {
            value == null -> null
            value.contains("pre", true) -> "preroll"
            value.contains("mid", true) -> "midroll"
            else -> null
        }
    }

    /** The main player showed a picture since the stream started to load. */
    private var mainFrameRendered = false

    /**
     * TV: a picture of the stream itself is on the screen since it started to load: of the main player once its first
     * playlist was checked without an ad, else of the backup player of the ad blocker. Until then the player screen keeps
     * the loading picture over the video, an ad at the start (also after a switch of stream) is never seen.
     */
    var streamPictureShown = false
        private set

    private fun checkStreamPictureShown() {
        if (!streamPictureShown && type == STREAM && mainFrameRendered && !checkFirstPlaylist && !playingAds && !isSwapping) {
            streamPictureShown = true
            serviceListener?.streamPictureShown()
        }
    }

    private fun isAdSegment(playlist: HlsMediaPlaylist, segment: HlsMediaPlaylist.Segment): Boolean {
        return segment.let { segment ->
            val title = segment.title.substringBefore('|')
            if (title == "Amazon" || title == "Adform" || title == "DCM") {
                true
            } else {
                val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
                playlist.interstitials.find { dateRange ->
                    if (dateRange.id.startsWith("stitched-ad-")
                        || dateRange.clientDefinedAttributes.find { it.name == "CLASS" }?.textValue == "twitch-stitched-ad"
                        || dateRange.clientDefinedAttributes.find { it.name.startsWith("X-TV-TWITCH-AD-") } != null
                    ) {
                        val startTime = dateRange.startDateUnixUs
                        val endTime = dateRange.endDateUnixUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                            ?: dateRange.durationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                            ?: dateRange.plannedDurationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                        if (endTime != null) {
                            segmentStartTime in startTime..<endTime
                        } else false
                    } else false
                } != null
            }
        } == true
    }

    /** Changes with each stream played in this service: what the ad blocker started for a previous stream stops. */
    private var streamGeneration = 0
    private var secondaryLoadJob: Job? = null
    /** Proxies that returned HTTP 403 (geo-blocked / forbidden) on the current channel, skipped for this channel only. */
    private val geoBlockedProxiesForChannel = HashSet<String>()

    private fun startSecondaryPlayer(proxy: Boolean) {
        val generation = streamGeneration
        val login = channelLogin
        secondaryLoadJob?.cancel()
        secondaryLoadJob = lifecycleScope.launch {
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val streamProxy = if (proxy) {
                streamProxyList?.getOrNull(currentStreamProxy)
            } else null
            val videoSwap = if (!proxy) {
                videoSwapList?.getOrNull(currentVideoSwapItem)
            } else null
            // statistics of the home: proxy or backup player type used once it actually plays without ads
            var pendingSecondaryProxyStat: String? = streamProxy?.host ?: videoSwap?.let {
                if (it.platform == AD_PROXY_PLATFORM) it.playerType?.toUri()?.host?.removePrefix("www.") else it.playerType
            }
            var checkFirstSecondaryPlaylist = true
            val playerListener = object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (generation != streamGeneration || channelLogin != login) {
                        return
                    }
                    secondaryDynamicsProcessing?.let {
                        runCatching { it.release() }
                        secondaryDynamicsProcessing = null
                    }
                    if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        secondaryDynamicsProcessing = createDynamicsProcessing(audioSessionId)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        pendingSecondaryProxyStat?.let {
                            pendingSecondaryProxyStat = null
                            com.github.andreyasadchy.xtra.ui.tv.TvStats.addProxy(this@ExoPlayerService, it)
                        }
                    }
                    // the backup player plays: the system sees the playback going on
                    updatePlaybackState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlaybackState()
                }

                override fun onRenderedFirstFrame() {
                    // the stream without the ad is on the screen: the loading picture can go
                    if (generation == streamGeneration && !streamPictureShown) {
                        streamPictureShown = true
                        serviceListener?.streamPictureShown()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // the backup (a custom proxy that stopped answering in the middle of the ad...) fails: the next one
                    if (videoSwap != null && videoSwapActive && generation == streamGeneration && channelLogin == login &&
                        videoSwapList?.getOrNull(currentVideoSwapItem) === videoSwap) {
                        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                        if (code == 403) {
                            adProxyHost(videoSwap)?.let { geoBlockedProxiesForChannel.add(it) }
                        } else {
                            adProxyHost(videoSwap)?.let { TvProxyStatus.markFailed(it) }
                        }
                        nextVideoSwap()
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    val playlist = (secondaryPlayer?.currentManifest as? HlsManifest)?.mediaPlaylist ?: return
                    val ads = if (checkFirstSecondaryPlaylist) {
                        checkFirstSecondaryPlaylist = false
                        playlist.segments.any { isAdSegment(playlist, it) }
                    } else {
                        checkPlaylist(playlist)
                    }
                    if (ads) {
                        when {
                            proxyMediaPlaylist -> {
                                currentStreamProxy += 1
                                if (streamProxyList?.getOrNull(currentStreamProxy) != null) {
                                    secondaryPlayerTimerJob?.cancel()
                                    secondaryPlayerTimerJob = lifecycleScope.launch {
                                        delay(3.minutes)
                                        proxyMediaPlaylist = false
                                        serviceListener?.toast(R.string.stopping_proxy, Toast.LENGTH_SHORT)
                                        stopSecondaryPlayer2()
                                        secondaryPlayerTimerJob = null
                                    }
                                    startSecondaryPlayer(true)
                                } else {
                                    stopProxy = true
                                    serviceListener?.toast(R.string.stopping_proxy, Toast.LENGTH_SHORT)
                                    stopSecondaryPlayer()
                                    if (useVideoSwap && !stopVideoSwap) {
                                        startVideoSwap()
                                    } else {
                                        hideAdFallback()
                                    }
                                }
                            }
                            videoSwapActive -> nextVideoSwap()
                        }
                    }
                }
            }
            val player = ExoPlayer.Builder(this@ExoPlayerService).apply {
                setLoadControl(
                    DefaultLoadControl.Builder().apply {
                        setBufferDurationsMs(
                            prefs().getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                            prefs().getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                            prefs().getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull()
                                ?.let { if (it == 2000 && resources.getBoolean(R.bool.isTv)) 1000 else it } ?: 2000,
                            prefs().getString(C.PLAYER_BUFFER_REBUFFER, "2000")?.toIntOrNull() ?: 2000
                        )
                    }.build()
                )
                if (resources.getBoolean(R.bool.isTv)) {
                    // TV: the sound of a film, the audio focus is held by the app for every player (TvPlaybackSignals)
                    setAudioAttributes(com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.playerAttributes, false)
                    setHandleAudioBecomingNoisy(false)
                } else {
                    setAudioAttributes(AudioAttributes.DEFAULT, prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false))
                    setHandleAudioBecomingNoisy(prefs().getBoolean(C.PLAYER_HANDLE_AUDIO_BECOMING_NOISY, true))
                }
                setSeekBackIncrementMs((prefs().getString(C.PLAYER_REWIND, "10")?.toLongOrNull() ?: 10) * 1000)
                setSeekForwardIncrementMs((prefs().getString(C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10) * 1000)
                setBandwidthMeter(createSecondaryBandwidthMeter(this@ExoPlayerService))
                setTrackSelector(createTrackSelector())
            }.build()
            player.addListener(playerListener)
            matchSecondaryQuality(player)
            startAdMainWatch()
            var playlistStatusCode = 0
            val url = if (streamProxy != null) {
                quality?.url
            } else {
                if (videoSwap != null) {
                    // a custom proxy while the ad plays: its playlist, else the playlist of another player type
                    val playlistUrl = if (videoSwap.platform == AD_PROXY_PLATFORM) videoSwap.playerType else channelLogin?.let { channelLogin ->
                        try {
                            xtraModule.playerRepository.loadStreamPlaylistUrl(
                                context = this@ExoPlayerService,
                                networkLibrary = networkLibrary,
                                gqlHeaders = getStreamTokenHeaders(this@ExoPlayerService, videoSwap.platform),
                                channelLogin = channelLogin,
                                platform = videoSwap.platform,
                                playerType = videoSwap.playerType,
                                supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                                proxyPlaybackAccessToken = false,
                                proxyHost = null,
                                proxyPort = null,
                                proxyUser = null,
                                proxyPassword = null,
                                proxyTimeout = null,
                                enableIntegrity = false
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val playlist = if (playlistUrl != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout()
                                            val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                                playlistUrl,
                                                xtraModule.cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        playlistStatusCode = response.info.httpStatusCode
                                        if (playlistStatusCode in 200..299) {
                                            response.body.decodeToString()
                                        } else null
                                    }
                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout()
                                            val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                                playlistUrl,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                xtraModule.cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        playlistStatusCode = response.info.httpStatusCode
                                        if (playlistStatusCode in 200..299) {
                                            response.body.decodeToString()
                                        } else null
                                    }
                                    else -> {
                                        xtraModule.okHttpClient.value.newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                                            playlistStatusCode = response.code
                                            if (response.isSuccessful) {
                                                response.body.string()
                                            } else null
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } else null
                    if (!playlist.isNullOrBlank()) {
                        val stableVariantIds = Regex("STABLE-VARIANT-ID=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toList()
                        val resolutions = Regex("RESOLUTION=(\\d+x\\d+)").findAll(playlist).mapNotNull { it.groups[1]?.value }.toList()
                        val frameRates = Regex("FRAME-RATE=([\\d.]+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toFloatOrNull() }.toList()
                        val bitrates = Regex("BANDWIDTH=(\\d+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toIntOrNull() }.toList()
                        val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toList()
                        val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toList()
                        val list = stableVariantIds.mapIndexedNotNull { index, variantId ->
                            urls.getOrNull(index)?.let { url ->
                                if (!variantId.endsWith("-portrait")) {
                                    VideoQuality(variantId, resolutions.getOrNull(index)?.substringAfter('x')?.toIntOrNull(), frameRates.getOrNull(index), bitrates.getOrNull(index), codecs.getOrNull(index), url)
                                } else null
                            }
                        }
                        val qualities = list
                            .sortedByDescending { it.bitrate }
                            .sortedByDescending { it.frameRate }
                            .sortedByDescending { it.resolution }
                            .toMutableList().apply {
                                find { it.name.equals("source", true) }?.let { source ->
                                    remove(source)
                                    add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                                }
                                find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                                    remove(audio)
                                    add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio.resolution, audio.frameRate, audio.bitrate, audio.codecs, audio.url))
                                }
                            }
                        qualities.firstOrNull()?.url
                    } else null
                } else null
            }
            if (url != null) {
                val proxyTimeout = prefs().getString(C.PROXY_TIMEOUT, "3000")?.toIntOrNull() ?: 3000
                val proxyHost = streamProxy?.host
                val proxyPort = streamProxy?.port
                val proxyUser = streamProxy?.username
                val proxyPassword = streamProxy?.password
                val proxyMediaPlaylist = streamProxy?.proxyMediaPlaylist == true && !proxyHost.isNullOrBlank() && proxyPort != null
                player.setMediaSource(
                    HlsMediaSource.Factory(
                        DefaultDataSource.Factory(
                            this@ExoPlayerService,
                            when {
                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val proxyClient = if (proxyMediaPlaylist) {
                                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                            listOf(android.util.Pair("Proxy-Authorization", Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)))
                                        } else emptyList()
                                        val builder = HttpEngine.Builder(application)
                                        try {
                                            builder.setProxyOptions(ProxyOptions.fromProxyList(
                                                listOf(
                                                    android.net.http.Proxy.createHttpProxy(
                                                        android.net.http.Proxy.SCHEME_HTTP,
                                                        proxyHost,
                                                        proxyPort,
                                                        xtraModule.cronetExecutor.value,
                                                        object : android.net.http.Proxy.HttpConnectCallback {
                                                            override fun onBeforeRequest(request: android.net.http.Proxy.HttpConnectCallback.Request) {
                                                                request.proceed(proxyHeaders)
                                                            }

                                                            override fun onResponseReceived(responseHeaders: List<android.util.Pair<String?, String?>?>, statusCode: Int): Int {
                                                                return android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED
                                                            }
                                                        }
                                                    )
                                                ),
                                                ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
                                            ))
                                        } catch (e: NoClassDefFoundError) {
                                            null
                                        }?.build()
                                    } else null
                                    val mediaPlaylistProxyOkHttpClient = if (proxyMediaPlaylist && proxyClient == null) {
                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                            val proxyTimeout = proxyTimeout.toLong()
                                            connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            proxySelector(
                                                object : ProxySelector() {
                                                    override fun select(u: URI): List<Proxy> {
                                                        return if (Regex(MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                        } else {
                                                            listOf(Proxy.NO_PROXY)
                                                        }
                                                    }

                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                }
                                            )
                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                proxyAuthenticator { _, response ->
                                                    response.request.newBuilder().header(
                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                    ).build()
                                                }
                                            }
                                        }.build()
                                    } else null
                                    HttpEngineDataSource.Factory(
                                        xtraModule.httpEngine.value,
                                        xtraModule.cronetExecutor.value,
                                        null,
                                        proxyTimeout,
                                        false,
                                        proxyMediaPlaylist,
                                        proxyClient,
                                        null,
                                        mediaPlaylistProxyOkHttpClient
                                    ) { this@ExoPlayerService.proxyMediaPlaylist }
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val proxyClient = if (proxyMediaPlaylist && CronetProvider.getAllProviders(application).any { it.isEnabled }) {
                                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                            mapOf("Proxy-Authorization" to Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)).entries.toList()
                                        } else emptyList()
                                        val builder = CronetEngine.Builder(application).apply {
                                            val userAgent = "Cronet/" + defaultUserAgent.substringAfter("Cronet/", "").substringBefore(')')
                                            setUserAgent(userAgent)
                                            @QuicOptions.Experimental
                                            setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
                                        }
                                        try {
                                            @org.chromium.net.ProxyOptions.Experimental
                                            builder.setProxyOptions(org.chromium.net.ProxyOptions(
                                                listOf(
                                                    org.chromium.net.Proxy(
                                                        org.chromium.net.Proxy.HTTP,
                                                        proxyHost,
                                                        proxyPort,
                                                        xtraModule.cronetExecutor.value,
                                                        object : org.chromium.net.Proxy.Callback() {
                                                            override fun onBeforeTunnelRequest(request: org.chromium.net.Proxy.Callback.Request) {
                                                                request.proceed(proxyHeaders)
                                                            }

                                                            override fun onTunnelHeadersReceived(responseHeaders: List<Map.Entry<String?, String?>?>, statusCode: Int): Boolean {
                                                                return true
                                                            }
                                                        }
                                                    )
                                                )
                                            ))
                                        } catch (e: UnsupportedOperationException) {
                                            null
                                        }?.build()
                                    } else null
                                    val mediaPlaylistProxyOkHttpClient = if (proxyMediaPlaylist && proxyClient == null) {
                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                            val proxyTimeout = proxyTimeout.toLong()
                                            connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            proxySelector(
                                                object : ProxySelector() {
                                                    override fun select(u: URI): List<Proxy> {
                                                        return if (Regex(MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                        } else {
                                                            listOf(Proxy.NO_PROXY)
                                                        }
                                                    }

                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                }
                                            )
                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                proxyAuthenticator { _, response ->
                                                    response.request.newBuilder().header(
                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                    ).build()
                                                }
                                            }
                                        }.build()
                                    } else null
                                    CronetDataSource.Factory(
                                        xtraModule.cronetEngine.value,
                                        xtraModule.cronetExecutor.value,
                                        null,
                                        proxyTimeout,
                                        false,
                                        proxyMediaPlaylist,
                                        proxyClient,
                                        null,
                                        mediaPlaylistProxyOkHttpClient
                                    ) { this@ExoPlayerService.proxyMediaPlaylist }
                                }
                                else -> {
                                    val mediaPlaylistProxyClient = if (proxyMediaPlaylist) {
                                        xtraModule.okHttpClient.value.newBuilder().apply {
                                            val proxyTimeout = proxyTimeout.toLong()
                                            connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                            proxySelector(
                                                object : ProxySelector() {
                                                    override fun select(u: URI): List<Proxy> {
                                                        return if (Regex(MEDIA_PLAYLIST_REGEX).matches(u.host)) {
                                                            listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)), Proxy.NO_PROXY)
                                                        } else {
                                                            listOf(Proxy.NO_PROXY)
                                                        }
                                                    }

                                                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                                                }
                                            )
                                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                                proxyAuthenticator { _, response ->
                                                    response.request.newBuilder().header(
                                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                                    ).build()
                                                }
                                            }
                                        }.build()
                                    } else null
                                    OkHttpDataSource.Factory(
                                        xtraModule.okHttpClient.value,
                                        null,
                                        null,
                                        null,
                                        mediaPlaylistProxyClient
                                    ) { this@ExoPlayerService.proxyMediaPlaylist }
                                }
                            }
                        )
                    ).apply {
                        setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                        setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(
                            if (proxy) {
                                1
                            } else {
                                6
                            }
                        ))
                    }.createMediaSource(
                        MediaItem.Builder().apply {
                            setUri(url.toUri())
                            setMimeType(MimeTypes.APPLICATION_M3U8)
                            setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                prefs().getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                prefs().getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                            }.build())
                        }.build()
                    )
                )
                // another stream meanwhile (left / right): this backup player was for the previous one
                if (generation != streamGeneration || channelLogin != login || !(videoSwapActive || this@ExoPlayerService.proxyMediaPlaylist)) {
                    player.release()
                    return@launch
                }
                val previous = secondaryPlayer
                if (previous != null && previous !== player) {
                    previous.setVideoSurfaceView(null)
                    secondaryDynamicsProcessing?.let {
                        runCatching { it.release() }
                        secondaryDynamicsProcessing = null
                    }
                }
                player.volume = userVolume()
                player.setPlaybackSpeed(1f)
                player.prepare()
                player.playWhenReady = true
                secondaryPlayer = player
                if (prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false) && secondaryDynamicsProcessing == null) {
                    secondaryDynamicsProcessing = createDynamicsProcessing(player.audioSessionId)
                }
                serviceListener?.setPlayerSurface(true)
                // the backup player it replaces (next player type) no longer plays behind it
                if (previous != null && previous !== player) {
                    previous.release()
                }
            } else if (generation == streamGeneration && videoSwap != null && videoSwapActive && channelLogin == login) {
                // its playlist did not load (a custom proxy offline or 403 geo-blocked...): the next backup, like when the ad comes through
                player.release()
                if (playlistStatusCode == 403) {
                    adProxyHost(videoSwap)?.let { geoBlockedProxiesForChannel.add(it) }
                } else {
                    adProxyHost(videoSwap)?.let { TvProxyStatus.markFailed(it) }
                }
                nextVideoSwap()
            } else if (generation == streamGeneration) {
                player.release()
                stopSecondaryPlayer()
            } else {
                player.release()
            }
        }
    }

    /** The playlist address of a custom proxy for [channelLogin], with the parameters of Twitch added when asked. */
    private fun customProxyUrl(proxy: CustomProxy, channelLogin: String): String? {
        return proxy.url?.let { proxyUrl ->
                    (proxyUrl.toUri().takeIf { it.host != null } ?: "https://$proxyUrl".toUri()).let { uri ->
                        if (proxy.addQueryParams) {
                            val source = uri.getQueryParameter("allow_source") == null
                            val audio = uri.getQueryParameter("allow_audio_only") == null
                            val lowLatency = uri.getQueryParameter("fast_bread") == null
                            val unavailable = uri.getQueryParameter("include_unavailable") == null
                            val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")
                            val codecs = !supportedCodecs.isNullOrBlank() && uri.getQueryParameter("supported_codecs") == null
                            if (source || audio || lowLatency || unavailable || codecs) {
                                uri.buildUpon().apply {
                                    if (source) {
                                        appendQueryParameter("allow_source", "true")
                                    }
                                    if (audio) {
                                        appendQueryParameter("allow_audio_only", "true")
                                    }
                                    if (lowLatency) {
                                        appendQueryParameter("fast_bread", "true")
                                    }
                                    if (unavailable) {
                                        appendQueryParameter("include_unavailable", "true")
                                    }
                                    if (codecs) {
                                        appendQueryParameter("supported_codecs", supportedCodecs)
                                    }
                                }.build()
                            } else uri
                        } else uri
                    }.toString().replace("\$channel", channelLogin)
        }
    }

    private fun startVideoSwap() {
        skipFailedAdProxies()
        if (videoSwapList?.getOrNull(currentVideoSwapItem) == null) {
            // every backup tried (custom proxies offline...): hide and mute the ad
            stopVideoSwap = true
            hideAdFallback()
            return
        }
        videoSwapActive = true
        val adProxy = videoSwapList?.getOrNull(currentVideoSwapItem)?.platform == AD_PROXY_PLATFORM
        serviceListener?.toast(if (adProxy) R.string.starting_proxy else R.string.starting_video_swap, Toast.LENGTH_SHORT)
        player?.let { player ->
            if (quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                }.build()
            }
            player.volume = 0f
        }
        secondaryPlayerTimerJob?.cancel()
        secondaryPlayerTimerJob = lifecycleScope.launch {
            delay(3.minutes)
            videoSwapActive = false
            serviceListener?.toast(R.string.stopping_video_swap, Toast.LENGTH_SHORT)
            stopSecondaryPlayer2()
            secondaryPlayerTimerJob = null
        }
        startSecondaryPlayer(false)
    }

    /** The backup of the ad blocker failed or let the ad through: the next one (custom proxies known offline skipped), else none. */
    private fun nextVideoSwap() {
        currentVideoSwapItem += 1
        skipFailedAdProxies()
        if (videoSwapList?.getOrNull(currentVideoSwapItem) != null) {
            secondaryPlayerTimerJob?.cancel()
            secondaryPlayerTimerJob = lifecycleScope.launch {
                delay(3.minutes)
                videoSwapActive = false
                serviceListener?.toast(R.string.stopping_video_swap, Toast.LENGTH_SHORT)
                stopSecondaryPlayer2()
                secondaryPlayerTimerJob = null
            }
            startSecondaryPlayer(false)
        } else {
            stopVideoSwap = true
            serviceListener?.toast(R.string.stopping_video_swap, Toast.LENGTH_SHORT)
            stopSecondaryPlayer()
            hideAdFallback()
        }
    }

    /** Hides and silences the main player when no ad-free backup stream is available while an ad break plays. */
    private fun hideAdFallback() {
        val hideAds = prefs().getBoolean(C.PLAYER_HIDE_ADS, false) || TvExperimentalAdblock.isSwapAdblockEnabled(this)
        if (playingAds && !hidden && hideAds) {
            hidden = true
            serviceListener?.toast(R.string.waiting_ads, Toast.LENGTH_LONG)
            player?.let { player ->
                if (quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                    }.build()
                }
                player.volume = 0f
            }
            serviceListener?.setPlayerSurface(false)
            startAdMainWatch()
        }
    }

    /** Host of a custom proxy used while the ad plays, null for a player type of Twitch. */
    private fun adProxyHost(videoSwap: VideoSwap): String? {
        return if (videoSwap.platform == AD_PROXY_PLATFORM) videoSwap.playerType?.toUri()?.host?.removePrefix("www.") else null
    }

    /** The custom proxies known offline or geo-blocked (403) on this channel are not tried. */
    private fun skipFailedAdProxies() {
        while (videoSwapList?.getOrNull(currentVideoSwapItem)?.let { swap ->
                adProxyHost(swap)?.let { host ->
                    // mode "always": the proxy of the stream is the one the ad came through
                    TvProxyStatus.isFailed(host) || geoBlockedProxiesForChannel.contains(host) || (useCustomProxy && swap.position == currentCustomProxy)
                }
            } == true) {
            currentVideoSwapItem += 1
        }
    }

    /** Host of a custom proxy, as [adProxyHost] gives it. */
    private fun customProxyHost(proxy: CustomProxy): String? {
        val url = proxy.url ?: return null
        return (url.toUri().takeIf { it.host != null } ?: "https://$url".toUri()).host?.removePrefix("www.")
    }

    /** True while the backup player of the ad blocker shows the picture. */
    val isSwapping: Boolean
        get() = secondaryPlayer != null && (videoSwapActive || proxyMediaPlaylist)

    /** True while ads are hidden with a black veil without any backup player. */
    val isHidingAds: Boolean
        get() = hidden && !isSwapping

    /** True only when the main video surface itself should be torn down (audio-only / chat-only / background video hidden). */
    fun shouldHidePlayerSurface(): Boolean {
        return videoHidden ||
            quality?.name == VideoQuality.AUDIO_ONLY_QUALITY ||
            quality?.name == VideoQuality.CHAT_ONLY_QUALITY
    }

    /**
     * After the display switched its refresh rate: the HDMI resync paused the players, both have to play again
     * and the picture has to go back on the surface of the player that shows it.
     */
    fun resumeAfterDisplayChange() {
        if (intentionalStopOrPause || streamEnded) {
            return
        }
        player?.let { if (!it.playWhenReady) it.play() }
        secondaryPlayer?.let { if (!it.playWhenReady) it.play() }
        serviceListener?.setPlayerSurface(isSwapping)
    }

    private var videoHidden = false

    /** TV audio only: the picture of the stream is not decoded while the player is hidden. */
    fun setVideoHidden(hidden: Boolean) {
        videoHidden = hidden
        if (quality?.name == VideoQuality.AUDIO_ONLY_QUALITY) {
            return
        }
        // the main player keeps its picture off while the backup player of the ad blocker shows it
        val target = if (isSwapping) secondaryPlayer else player
        target?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, hidden).build()
        }
        serviceListener?.setPlayerSurface(isSwapping)
    }

    private var duckedVolume: Float? = null

    /** Silences the stream while a preview with sound plays over it (stream switcher of the player). */
    fun setDucked(ducked: Boolean) {
        val target = visiblePlayer ?: return
        if (ducked) {
            if (duckedVolume == null) {
                duckedVolume = target.volume
                target.volume = 0f
            }
        } else {
            duckedVolume?.let { target.volume = it }
            duckedVolume = null
        }
    }

    /** Player whose picture is on screen: the backup player while ads are blocked, else the main one. */
    val visiblePlayer: ExoPlayer?
        get() = secondaryPlayer?.takeIf { videoSwapActive || proxyMediaPlaylist } ?: player

    /**
     * Badge of the ad blocker, like the proxyivshook userscript: "Mid-roll ad 2/3 · 12 s" over the source of the stream
     * without the ad, a bar for the ad playing that starts again with the next one, then "Back to live…" until the stream
     * is back. Without the marks of Twitch: the kind of ad and the source only.
     */
    fun getAdblockInfo(): AdblockInfo? {
        if (type != STREAM) return null
        val source = when {
            videoSwapActive -> videoSwapList?.getOrNull(currentVideoSwapItem)?.let { swap ->
                val host = adProxyHost(swap)?.let { TvProxyStatus.displayName(it) }
                if (host != null) {
                    host
                } else {
                    val base = swap.playerType ?: "backup"
                    val height = secondaryPlayer?.videoFormat?.height?.takeIf { it > 0 }
                        ?: if (swap.playerType == "autoplay" && quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) 360 else null
                    if (height != null) "$base (${height}p)" else base
                }
            } ?: "backup"
            proxyMediaPlaylist -> "proxy"
            else -> null
        }
        val now = System.currentTimeMillis()
        if (source == null && !hidden) {
            backLiveFrom = 0L
            backLiveProgress = 0f
            if (experimentalNoticeTitleRes != 0) {
                if (!streamPictureShown && player?.playbackState != Player.STATE_READY) {
                    return null
                }
                if (experimentalNoticeStartedAtMs == 0L) {
                    experimentalNoticeStartedAtMs = now
                }
                if (now - experimentalNoticeStartedAtMs < 4_500L) {
                    val title = getString(experimentalNoticeTitleRes)
                    val subtitle = experimentalNoticeSubtitleRes.takeIf { it != 0 }?.let { getString(it) }
                    return AdblockInfo(title, subtitle, null)
                }
                clearExperimentalBadgeNotice()
            }
            return null
        }
        // A pre-roll / Swap badge is already explaining the fallback state; don't queue a duplicate notice after it
        if (experimentalNoticeStartedAtMs == 0L && experimentalFallback != ExperimentalFallback.NONE) {
            clearExperimentalBadgeNotice()
        }
        val fallbackLabel = when (experimentalFallback) {
            ExperimentalFallback.UNSUPPORTED -> getString(R.string.tv_adblock_exp_unsupported_title)
            ExperimentalFallback.NEW_STREAM -> getString(R.string.tv_adblock_exp_new_stream_title)
            ExperimentalFallback.NONE -> null
        }
        val displaySource = when {
            source != null && fallbackLabel != null -> "$fallbackLabel · $source"
            source != null -> source
            else -> fallbackLabel
        }
        val times = adPodTimes
        val position = adPodPosition
        val length = adPodLength
        val ended = times != null && now >= times.end
        if (!playingAds) {
            if (backLiveFrom == 0L) {
                backLiveFrom = now
            }
            if (adDelayJob?.isActive != true) {
                startReturnToLiveIfNeeded()
            }
            val delay = prefs().getLong(AD_RETURN_DELAY, DEFAULT_AD_RETURN_DELAY).coerceIn(1500L, 6000L)
            val timeProgress = ((now - backLiveFrom).toFloat() / delay).coerceIn(0f, 0.92f)
            val progress = maxOf(timeProgress, backLiveProgress).coerceIn(0.05f, 0.95f)
            backLiveProgress = progress
            return AdblockInfo(getString(R.string.tv_adblock_back_live), displaySource, progress)
        }
        val ad = getString(
            when (adRollType) {
                "preroll" -> R.string.tv_adblock_ad_preroll
                "midroll" -> R.string.tv_adblock_ad_midroll
                else -> R.string.tv_adblock_ad
            }
        )
        if (source == null) {
            // no stream without the ad: the picture waits for the end of the ad
            return AdblockInfo(ad + " · " + getString(R.string.tv_adblock_on_hold), fallbackLabel, null)
        }
        val title = StringBuilder(ad)
        if (position > 0 && length > 0) {
            // between two ads the next one is not marked yet: its number already
            title.append(' ').append(if (ended) minOf(position + 1, length) else position).append('/').append(length)
        }
        val progress = when {
            times != null && !ended -> {
                title.append(" · ").append(maxOf(1L, (times.end - now + 999) / 1000)).append(" s")
                ((now - times.start).toFloat() / (times.end - times.start)).coerceIn(0f, 1f)
            }
            ended && position > 0 && length > 0 -> if (position >= length) 1f else 0f
            else -> null
        }
        return AdblockInfo(title.toString(), displaySource, progress)
    }

    /** Keeps the quality of the backup player in line with the quality chosen for the main one. */
    private fun matchSecondaryQuality(secondaryPlayer: ExoPlayer) {
        val height = quality?.takeIf { it.name != VideoQuality.AUTO_QUALITY }?.resolution
            ?: player?.videoFormat?.height?.takeIf { it > 0 && quality?.name != VideoQuality.AUTO_QUALITY }
        if (height != null) {
            secondaryPlayer.trackSelectionParameters = secondaryPlayer.trackSelectionParameters.buildUpon().apply {
                setMaxVideoSize(Int.MAX_VALUE, height)
                setForceHighestSupportedBitrate(true)
            }.build()
        }
    }

    private var autoHighest = false
    private val rebufferTimes = ArrayDeque<Long>()
    /** Time the stream started to play (0: not yet): the stops of its first seconds (start, switch) are not counted. */
    private var playingSince = 0L
    /** Auto on Wi-Fi / Ethernet after real stops: the highest quality allowed, one step under the one that stopped. */
    private var autoCapHeight: Int? = null
    private var autoRaiseJob: Job? = null

    /**
     * Auto on Wi-Fi / Ethernet: the highest quality the device decodes. Twitch low latency sends the segments while they
     * are made, so the measured bandwidth is the bitrate of the quality being played: the adaptive choice of the player
     * only goes down and never comes back up, it is not used. When the stream stops to buffer twice in a minute (after
     * playing for 10 s, a start or a switch of stream does not count), the quality goes down one step, and back up one
     * step after 2 min 30 without a stop, up to the highest again.
     */
    private fun onBufferingForAuto(playbackState: Int) {
        val player = player ?: return
        if (playingAds || isSwapping || hidden || adDelayJob?.isActive == true) {
            playingSince = 0L
            rebufferTimes.clear()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (playbackState == Player.STATE_READY) {
            if (playingSince == 0L) {
                playingSince = now
            }
        } else if (playbackState == Player.STATE_BUFFERING && autoHighest && player.playWhenReady && playingSince != 0L &&
            now - playingSince >= 10_000L && (quality == null || quality?.name == VideoQuality.AUTO_QUALITY)) {
            rebufferTimes.addLast(now)
            while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > 60_000L) {
                rebufferTimes.removeFirst()
            }
            if (rebufferTimes.size >= 2) {
                rebufferTimes.clear()
                stepAutoQuality(down = true)
            }
        }
    }

    /** Heights of the qualities of the stream, highest first. */
    private fun autoHeights(player: ExoPlayer): List<Int> {
        val group = player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } ?: return emptyList()
        return (0 until group.mediaTrackGroup.length).map { group.mediaTrackGroup.getFormat(it).height }.filter { it > 0 }.distinct().sortedDescending()
    }

    private fun stepAutoQuality(down: Boolean) {
        val player = player ?: return
        val heights = autoHeights(player)
        if (heights.isEmpty()) {
            return
        }
        val current = autoCapHeight ?: player.videoFormat?.height?.takeIf { it > 0 } ?: heights.first()
        val next = if (down) {
            heights.firstOrNull { it < current } ?: return
        } else {
            heights.lastOrNull { it > current }
        }
        // back to the highest: no limit
        autoCapHeight = next?.takeIf { it != heights.first() }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, autoCapHeight ?: Int.MAX_VALUE)
            .setForceHighestSupportedBitrate(true)
            .build()
        autoRaiseJob?.cancel()
        if (autoCapHeight != null) {
            autoRaiseJob = lifecycleScope.launch {
                delay(150_000L)
                // no step down since (a new one restarts this wait): one step up
                rebufferTimes.clear()
                stepAutoQuality(down = false)
            }
        }
    }

    /** A new stream in the same player: the quality of Auto starts again at the highest, nothing of the last one counts. */
    private fun resetAutoQuality() {
        autoRaiseJob?.cancel()
        autoRaiseJob = null
        autoCapHeight = null
        rebufferTimes.clear()
        playingSince = 0L
        autoHighest = isUnmeteredNetwork()
        player?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(autoHighest)
                .build()
        }
    }

    private var adMainWatchJob: Job? = null

    /**
     * While an ad break is covered by a backup player or hidden, keeps the muted background main player alive if it
     * stalls on an ad discontinuity so it stays near live and restores clean quality immediately after the break.
     */
    private fun startAdMainWatch() {
        if (type != STREAM) {
            return
        }
        val generation = streamGeneration
        val login = channelLogin
        adMainWatchJob?.cancel()
        adMainWatchJob = lifecycleScope.launch {
            var lastPos = -1L
            var stuckSteps = 0
            while (playingAds && (videoSwapActive || proxyMediaPlaylist || hidden) && generation == streamGeneration && channelLogin == login) {
                delay(2000L)
                if (!playingAds || !(videoSwapActive || proxyMediaPlaylist || hidden) || generation != streamGeneration || channelLogin != login) {
                    break
                }
                val p = player ?: break
                val pos = p.currentPosition
                val stalled = when (p.playbackState) {
                    Player.STATE_IDLE, Player.STATE_ENDED -> true
                    Player.STATE_BUFFERING -> {
                        stuckSteps = if (pos == lastPos) stuckSteps + 1 else 1
                        stuckSteps >= 4
                    }
                    else -> {
                        stuckSteps = 0
                        false
                    }
                }
                lastPos = pos
                if (stalled) {
                    stuckSteps = 0
                    p.volume = 0f
                    if (quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            .build()
                    }
                    p.seekToDefaultPosition()
                    p.prepare()
                    p.play()
                }
            }
        }
    }

    /**
     * Restores the user's chosen quality (or full-bitrate Auto) on the main player when returning from an ad break,
     * clearing any bandwidth/cap degradation accumulated while muted behind an ad without re-preparing the stream.
     */
    private fun restoreMainPlayerQualityAfterAd(player: ExoPlayer) {
        autoRaiseJob?.cancel()
        autoRaiseJob = null
        autoCapHeight = null
        rebufferTimes.clear()
        playingSince = 0L
        autoHighest = isUnmeteredNetwork()
        val q = quality
        if (q?.name == VideoQuality.AUDIO_ONLY_QUALITY || q?.name == VideoQuality.CHAT_ONLY_QUALITY) {
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, videoHidden)
            setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            if (q == null || q.name == VideoQuality.AUTO_QUALITY) {
                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                setForceHighestSupportedBitrate(autoHighest)
            } else if (qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null && !player.currentTracks.isEmpty) {
                player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let { trackGroup ->
                    if (trackGroup.mediaTrackGroup.length > 0) {
                        if (q.resolution != null) {
                            val formats = mutableListOf<Pair<Int, Format>>()
                            for (i in 0 until trackGroup.mediaTrackGroup.length) {
                                formats.add(i to trackGroup.mediaTrackGroup.getFormat(i))
                            }
                            val list = formats
                                .sortedByDescending { it.second.bitrate }
                                .sortedByDescending { it.second.frameRate }
                                .sortedByDescending { it.second.height }
                            list.find {
                                (q.resolution == it.second.height
                                        && (q.frameRate?.let { fps -> floor(fps) } ?: 30f) >= floor(it.second.frameRate)
                                        && (q.bitrate == null || q.bitrate >= it.second.bitrate))
                                        || q.resolution > it.second.height
                                        || it == list.last()
                            }?.first?.let { index ->
                                setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, index))
                            }
                        } else {
                            setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0))
                        }
                    }
                }
            }
        }.build()
    }

    /** The website client for the web platform (1440p), the client of the app for the others. */

    private fun isUnmeteredNetwork(): Boolean = isUnmetered(this)

    private fun createTrackSelector(): DefaultTrackSelector {
        // Twitch segments are short: with the default settings the estimate stays low and Auto keeps a lower quality than the connection allows
        return DefaultTrackSelector(this, AdaptiveTrackSelection.Factory(
            4000,
            AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
            AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
            0.85f
        )).apply {
            // no limit to the size of the screen: a 1440p stream (enhanced broadcasting) also looks better than 1080p on a 1080p TV
            autoHighest = isUnmeteredNetwork()
            rebufferTimes.clear()
            playingSince = 0L
            parameters = buildUponParameters().clearViewportSizeConstraints().setForceHighestSupportedBitrate(autoHighest).build()
        }
    }

    /** TV: the next stream can play in this service (not with a proxy or the sound only mode). */
    fun canKeepForStreamSwitch(): Boolean {
        return created && type == STREAM && !useStreamProxy && customProxyList.isNullOrEmpty() && quality?.name != VideoQuality.AUDIO_ONLY_QUALITY
    }

    /**
     * TV, left / right or another stream from the player: the service and its player are kept for the next stream
     * (no new player to make). Called before the next stream is saved: what belongs to this stream stops here.
     */
    fun prepareStreamSwitch(): Boolean {
        if (!canKeepForStreamSwitch()) {
            return false
        }
        started = false
        loaded = false
        intentionalStopOrPause = true
        streamEnded = false
        liveRecoveryAttempt = 0
        waitingIntegrity = false
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        experimentalVodUpgradeJob?.cancel()
        experimentalVodUpgradeJob = null
        experimentalVodActive = false
        experimentalVodCreatedAtMs = null
        // the main player stops first: while the ad blocker replaced an ad, it still played the ad behind (no picture,
        // no sound), it must not come back on the screen for a moment when the backup player goes
        player?.setPlaybackSpeed(1f)
        player?.volume = 0f
        player?.stop()
        // everything the ad blocker started for this stream stops, nothing of it may come over the next one
        streamGeneration++
        freezeWatchJob?.cancel()
        freezeWatchJob = null
        adMainWatchJob?.cancel()
        adMainWatchJob = null
        secondaryLoadJob?.cancel()
        secondaryLoadJob = null
        adDelayJob?.cancel()
        adDelayJob = null
        // nothing of the stream that ends counts for the next one: no picture of it shown yet, its first playlist not
        // checked yet (else the events of the player being emptied would take the loading picture away at once)
        streamPictureShown = false
        mainFrameRendered = false
        checkFirstPlaylist = true
        firstPlaylistAd = false
        adRollType = null
        playingAds = false
        backLiveFrom = 0L
        backLiveProgress = 0f
        geoBlockedProxiesForChannel.clear()
        resetAdPod()
        if (secondaryPlayer != null) {
            stopSecondaryPlayer()
        }
        secondaryDynamicsProcessing?.let {
            runCatching { it.release() }
            secondaryDynamicsProcessing = null
        }
        secondaryPlayerTimerJob?.cancel()
        secondaryPlayerTimerJob = null
        videoSwapActive = false
        this.proxyMediaPlaylist = false
        if (hidden) {
            hidden = false
            player?.volume = userVolume()
        }
        player?.stop()
        player?.clearMediaItems()
        // stopped: the sound of the next stream comes back
        player?.volume = userVolume()
        resetAutoQuality()
        return true
    }

    /** The next stream is saved: everything of the previous one is reset, as in a new service, and the stream loads. */
    fun startSwitchedStream() {
        if (!created) {
            return
        }
        intentionalStopOrPause = false
        streamEnded = false
        liveRecoveryAttempt = 0
        waitingIntegrity = false
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        experimentalVodUpgradeJob?.cancel()
        experimentalVodUpgradeJob = null
        experimentalVodActive = false
        experimentalVodCreatedAtMs = null
        playingAds = false
        backLiveFrom = 0L
        backLiveProgress = 0f
        geoBlockedProxiesForChannel.clear()
        resetAdPod()
        stopProxy = false
        stopVideoSwap = false
        videoSwapActive = false
        currentVideoSwapItem = 0
        pendingProxyStat = null
        backupQualities = null
        updateQualities = false
        ignorePlaylistUpdate = false
        fallbackH264ForStream = false
        qualities = null
        quality = null
        previousQuality = null
        restoreQuality = false
        playlistUrl = null
        restorePlaylist = false
        currentCustomProxy = 0
        currentStreamProxy = 0
        skipAccessToken = false
        chatUrl = null
        savedPosition = null
        videoStartPosition = null
        videoPreviewLookupDone = false
        paused = false
        player?.let { player ->
            autoHighest = isUnmeteredNetwork()
            player.setPlaybackSpeed(1f)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                clearOverrides()
                clearVideoSizeConstraints()
                clearViewportSizeConstraints()
                setForceHighestSupportedBitrate(autoHighest)
                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, videoHidden)
            }.build()
        }
        start(restorePauseState = false)
    }

    private fun stopSecondaryPlayer() {
        proxyMediaPlaylist = false
        videoSwapActive = false
        secondaryLoadJob?.cancel()
        secondaryLoadJob = null
        secondaryPlayerTimerJob?.cancel()
        secondaryPlayerTimerJob = null
        stopSecondaryPlayer2()
    }

    private fun stopSecondaryPlayer2() {
        adMainWatchJob?.cancel()
        adMainWatchJob = null
        secondaryDynamicsProcessing?.let {
            runCatching { it.release() }
            secondaryDynamicsProcessing = null
        }
        secondaryPlayer?.playWhenReady = false
        serviceListener?.setPlayerSurface(false)
        secondaryPlayer?.release()
        secondaryPlayer = null
        player?.let { player ->
            player.setPlaybackSpeed(1f)
            restoreMainPlayerQualityAfterAd(player)
            player.volume = userVolume()
        }
        // the main player plays again: the state the system sees is its own
        updatePlaybackState()
        startFreezeWatch()
    }

    private fun startReturnToLiveIfNeeded() {
        if (playingAds || !(proxyMediaPlaylist || videoSwapActive || hidden)) {
            return
        }
        adMainWatchJob?.cancel()
        adMainWatchJob = null
        if (backLiveFrom == 0L) {
            backLiveFrom = System.currentTimeMillis()
        }
        if (adDelayJob?.isActive == true) {
            return
        }
        val generation = streamGeneration
        val login = channelLogin
        adDelayJob = lifecycleScope.launch {
            try {
                val ready = returnToLiveAfterAd(generation, login)
                if (ready && !playingAds && generation == streamGeneration && channelLogin == login) {
                    adBreakOver()
                    when {
                        proxyMediaPlaylist -> {
                            serviceListener?.toast(R.string.stopping_proxy, Toast.LENGTH_SHORT)
                            stopSecondaryPlayer()
                        }
                        videoSwapActive -> {
                            serviceListener?.toast(R.string.stopping_video_swap, Toast.LENGTH_SHORT)
                            stopSecondaryPlayer()
                        }
                        hidden -> {
                            hidden = false
                            serviceListener?.setPlayerSurface(false)
                            player?.let { player ->
                                player.setPlaybackSpeed(1f)
                                restoreMainPlayerQualityAfterAd(player)
                                player.volume = userVolume()
                            }
                            startFreezeWatch()
                        }
                    }
                }
            } finally {
                player?.setPlaybackSpeed(1f)
                adDelayJob = null
            }
        }
    }

    /**
     * Drains the remaining buffered ad segments on the muted main player at 2x speed without calling seekTo(),
     * waiting until mainPlayer.currentPosition is strictly inside a "live" segment past all ad segments before
     * restoring 1x speed and switching back from the backup player (which releases its decoder first so the main
     * hardware MediaCodec configures directly on the real SurfaceView with its native color space).
     */
    private suspend fun returnToLiveAfterAd(generation: Int, login: String?): Boolean {
        if (backLiveFrom == 0L) {
            backLiveFrom = System.currentTimeMillis()
        }
        val initialPlayer = player ?: return false
        initialPlayer.volume = 0f
        if (initialPlayer.playbackState == Player.STATE_IDLE || initialPlayer.playbackState == Player.STATE_ENDED) {
            initialPlayer.prepare()
        }
        initialPlayer.play()
        var initialGapMs = 0L
        var liveReadySteps = 0
        try {
            initialPlayer.setPlaybackSpeed(2f)
            for (step in 0 until 150) {
                if (playingAds || generation != streamGeneration || channelLogin != login) {
                    return false
                }
                val p = player ?: return false
                val playlist = (p.currentManifest as? HlsManifest)?.mediaPlaylist ?: return true
                val segments = playlist.segments
                if (segments.isEmpty()) {
                    return true
                }
                val lastAdIdx = segments.indexOfLast { isAdSegment(playlist, it) }
                val posMs = p.currentPosition
                val posUs = posMs * 1000
                val currentIdx = segments.indexOfLast { it.relativeStartTimeUs <= posUs }
                val onLiveSegment = currentIdx > lastAdIdx &&
                    currentIdx >= 0 &&
                    !isAdSegment(playlist, segments[currentIdx]) &&
                    (segments.none { it.title.isNotEmpty() } || segments[currentIdx].title.contains("live", true))
                if (onLiveSegment) {
                    p.setPlaybackSpeed(1f)
                    liveReadySteps++
                    backLiveProgress = maxOf(backLiveProgress, (0.80f + liveReadySteps * 0.05f).coerceAtMost(0.95f))
                    if (hidden || (p.playbackState == Player.STATE_READY && liveReadySteps >= 3) || liveReadySteps >= 8) {
                        return true
                    }
                } else {
                    if (lastAdIdx + 1 < segments.size) {
                        val firstLiveStartMs = segments[lastAdIdx + 1].relativeStartTimeUs / 1000
                        val remainingMs = (firstLiveStartMs - posMs).coerceAtLeast(0L)
                        if (initialGapMs <= 0L && remainingMs > 0L) {
                            initialGapMs = remainingMs
                        }
                        if (initialGapMs > 0L) {
                            val drainRatio = (1f - remainingMs.toFloat() / initialGapMs.toFloat()).coerceIn(0f, 1f)
                            backLiveProgress = maxOf(backLiveProgress, 0.08f + drainRatio * 0.70f)
                        }
                    }
                }
                delay(100)
            }
            return !playingAds && generation == streamGeneration && channelLogin == login
        } finally {
            player?.setPlaybackSpeed(1f)
        }
    }

    private var freezeWatchJob: Job? = null

    /**
     * Back to the stream after an ad: if its picture does not move (stuck on the last picture of the ad) or it keeps
     * loading, the stream starts again at the live edge instead of staying stuck.
     */
    private fun startFreezeWatch() {
        if (type != STREAM) {
            return
        }
        freezeWatchJob?.cancel()
        freezeWatchJob = lifecycleScope.launch {
            var lastPosition = -1L
            var frozen = 0
            var loading = 0
            repeat(15) {
                delay(1000)
                val player = player ?: return@launch
                if (secondaryPlayer != null || hidden || !player.playWhenReady || player.playerError != null) {
                    return@launch
                }
                val position = player.currentPosition
                when (player.playbackState) {
                    Player.STATE_READY -> {
                        loading = 0
                        frozen = if (position == lastPosition) frozen + 1 else 0
                    }
                    Player.STATE_BUFFERING -> {
                        frozen = 0
                        loading++
                    }
                    else -> return@launch
                }
                lastPosition = position
                if (frozen >= 3 || loading >= 8) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                    return@launch
                }
            }
        }
    }

    /** Volume of the stream, without the share taken by the small window of the TV (the volume control shows this one). */
    fun getCurrentVolume(): Float? {
        val mix = TvPip.mainMix
        if (mix <= 0f) {
            return prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
        }
        return (secondaryPlayer?.volume ?: player?.volume)?.let { (it / mix).coerceAtMost(1f) }
    }

    fun play() {
        intentionalStopOrPause = false
        streamEnded = false
        liveRecoveryAttempt = 0
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        secondaryPlayer?.let { sp ->
            if (sp.playbackState == Player.STATE_IDLE) {
                sp.prepare()
            } else if (sp.playbackState == Player.STATE_ENDED) {
                sp.seekToDefaultPosition()
            }
            sp.play()
        }
        val p = player ?: return
        if (type == STREAM && quality?.name != VideoQuality.CHAT_ONLY_QUALITY) {
            if (p.currentMediaItem == null) {
                restartPlayer()
                return
            }
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                p.seekToDefaultPosition()
                p.prepare()
                p.play()
                return
            }
            if (!p.playWhenReady && isTvDevice && !prefs().getBoolean(C.PLAYER_PAUSE, false)) {
                p.seekToDefaultPosition()
            }
            p.play()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            } else if (p.playbackState == Player.STATE_ENDED) {
                p.seekToDefaultPosition()
            }
            p.play()
        }
    }

    fun togglePause() {
        val active = visiblePlayer ?: return
        if (Util.shouldShowPlayButton(active)) {
            play()
        } else {
            pause()
        }
    }

    fun pause() {
        intentionalStopOrPause = true
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        if (secondaryPlayer != null) {
            secondaryPlayer?.pause()
        }
        player?.pause()
    }

    fun stop() {
        intentionalStopOrPause = true
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        if (type == VIDEO && prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            player?.takeIf { !it.currentTracks.isEmpty }?.let { player ->
                videoId?.toLongOrNull()?.let { saveTvResume(it, player.currentPosition) }
            }
        }
        if (secondaryPlayer != null) {
            secondaryPlayer?.stop()
        }
        player?.stop()
    }

    fun seekBack() {
        if (type == STREAM && experimentalVodActive) {
            experimentalVodUserRewound = true
            player?.seekBack()
            return
        }
        if (secondaryPlayer != null) {
            secondaryPlayer?.seekBack()
        }
        player?.seekBack()
    }

    fun seekForward() {
        if (type == STREAM && experimentalVodActive) {
            val p = player ?: return
            val edge = getExperimentalLiveEdgeMs()
            val target = (p.currentPosition + p.seekForwardIncrement).coerceAtMost(edge)
            experimentalVodUserRewound = (edge - target) > 8_000L
            p.seekTo(target)
            return
        }
        if (secondaryPlayer != null) {
            secondaryPlayer?.seekForward()
        }
        player?.seekForward()
    }

    fun seekTo(position: Long) {
        if (type == STREAM && experimentalVodActive) {
            val edge = getExperimentalLiveEdgeMs()
            val clamped = position.coerceIn(0L, edge)
            experimentalVodUserRewound = (edge - clamped) > 8_000L
            player?.seekTo(clamped)
            return
        }
        if (type == VIDEO && player?.playbackState != Player.STATE_READY) {
            videoStartPosition = position
        }
        if (secondaryPlayer != null) {
            secondaryPlayer?.seekTo(position)
        }
        player?.seekTo(position)
    }

    fun seekToDefaultPosition() {
        if (type == STREAM && experimentalVodActive) {
            experimentalVodUserRewound = false
            player?.seekTo(getExperimentalLiveEdgeMs())
            return
        }
        if (secondaryPlayer != null) {
            secondaryPlayer?.seekToDefaultPosition()
        }
        player?.seekToDefaultPosition()
    }

    fun changeVolume(volume: Float) {
        val secondaryPlayer = secondaryPlayer
        if (secondaryPlayer != null) {
            secondaryPlayer.volume = volume * TvPip.mainMix
        } else {
            player?.volume = volume * TvPip.mainMix
        }
    }

    /** The volume of the settings, with the share the small window of the TV leaves to this stream. */
    private fun userVolume() = if (com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.leaveMuted) 0f else prefs().getInt(C.PLAYER_VOLUME, 100) / 100f * TvPip.mainMix

    /** TV: the sound of the small window changed (its panel, opened or closed): the stream that is heard follows. */
    fun refreshVolume() {
        if (hidden || duckedVolume != null) {
            return
        }
        if (isSwapping) {
            secondaryPlayer?.volume = userVolume()
        } else if (!playingAds) {
            // during an ad the main player stays silent, the ad blocker gives its sound back after it
            player?.volume = userVolume()
        }
    }

    fun getPlaylistTags(mediaPlaylist: Boolean): String? {
        return if (mediaPlaylist) {
            ((secondaryPlayer ?: player)?.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray()
        } else {
            val player = if (videoSwapActive && secondaryPlayer != null) {
                secondaryPlayer
            } else {
                player
            }
            (player?.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray()
        }?.joinToString("\n")
    }

    fun restartPlayer(fromAutoRecovery: Boolean = false) {
        if (quality?.name != VideoQuality.CHAT_ONLY_QUALITY) {
            intentionalStopOrPause = false
            streamEnded = false
            if (!fromAutoRecovery) {
                liveRecoveryAttempt = 0
                liveRecoveryJob?.cancel()
                liveRecoveryJob = null
            }
            streamGeneration++
            experimentalVodUpgradeJob?.cancel()
            experimentalVodUpgradeJob = null
            adMainWatchJob?.cancel()
            adMainWatchJob = null
            secondaryLoadJob?.cancel()
            secondaryLoadJob = null
            adDelayJob?.cancel()
            adDelayJob = null
            playingAds = false
            backLiveFrom = 0L
            backLiveProgress = 0f
            resetAdPod()
            if (hidden) {
                hidden = false
                player?.volume = userVolume()
            }
            lifecycleScope.launch {
                if (secondaryPlayer != null) {
                    stopSecondaryPlayer()
                }
                loadStream(restart = true)
            }
        }
    }

    private fun isNetworkValidated(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Resilient background live-stream recovery: retries fetching a fresh Twitch access token with exponential backoff
     * when a brief Wi-Fi/CDN blip interrupts playback, stopping cleanly if Twitch confirms the streamer went offline.
     */
    private fun scheduleLiveRecovery(generation: Int, login: String?, initialDelayMs: Long? = null) {
        if (login.isNullOrBlank() || type != STREAM || intentionalStopOrPause || streamEnded ||
            waitingIntegrity || quality?.name == VideoQuality.CHAT_ONLY_QUALITY
        ) {
            return
        }
        liveRecoveryJob?.cancel()
        liveRecoveryJob = lifecycleScope.launch {
            val attempt = liveRecoveryAttempt++
            val backoffMs = initialDelayMs ?: when (attempt) {
                0 -> 2000L
                1 -> 4000L
                2 -> 8000L
                3 -> 15_000L
                else -> 25_000L
            }
            delay(backoffMs)
            var waitedOffline = 0
            while (!isNetworkValidated() && waitedOffline < 20) {
                if (!created || !started || generation != streamGeneration || channelLogin != login ||
                    type != STREAM || intentionalStopOrPause || streamEnded || quality?.name == VideoQuality.CHAT_ONLY_QUALITY
                ) {
                    return@launch
                }
                delay(1000L)
                waitedOffline++
            }
            if (!created || !started || generation != streamGeneration || channelLogin != login ||
                type != STREAM || intentionalStopOrPause || streamEnded || quality?.name == VideoQuality.CHAT_ONLY_QUALITY
            ) {
                return@launch
            }
            if (visiblePlayer?.isPlaying == true) {
                return@launch
            }
            if (attempt >= 1 && isNetworkValidated()) {
                val stillLive = runCatching {
                    val user = xtraModule.graphQLRepository.loadQueryUsersStream(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        headers = TwitchApiHelper.getGQLHeaders(this@ExoPlayerService),
                        ids = null,
                        logins = listOf(login),
                    ).data?.users?.firstOrNull()
                    if (user != null) user.stream != null else true
                }.getOrDefault(true)
                if (!stillLive) {
                    if (generation == streamGeneration && channelLogin == login && !streamEnded) {
                        streamEnded = true
                        serviceListener?.toast(R.string.stream_ended, Toast.LENGTH_LONG)
                    }
                    return@launch
                }
            }
            if (!created || !started || generation != streamGeneration || channelLogin != login ||
                type != STREAM || intentionalStopOrPause || streamEnded || quality?.name == VideoQuality.CHAT_ONLY_QUALITY
            ) {
                return@launch
            }
            liveRecoveryJob = null
            restartPlayer(fromAutoRecovery = true)
        }
    }

    /**
     * Lightweight defensive watchdog for long-running live streams: completely inert while playback advances normally,
     * during intentional pause/stop, ad blocking, or genuine stream end; after ~5s of silent STATE_IDLE / unintended
     * pause / frozen position (or 15s of zero-progress buffering), performs a soft kick to the live edge first (just
     * like pressing Play on the remote) and escalates to a full token reload only if needed.
     */
    private fun startLiveWatchdog() {
        lifecycleScope.launch {
            var watchedGeneration = -1
            var idleOrPausedSec = 0
            var frozenReadySec = 0
            var stuckBufferingSec = 0
            var healthyPlaySec = 0
            var lastPos = androidx.media3.common.C.TIME_UNSET
            var lastBuffered = androidx.media3.common.C.TIME_UNSET
            var softKickUsed = false

            fun resetCounters() {
                idleOrPausedSec = 0
                frozenReadySec = 0
                stuckBufferingSec = 0
                healthyPlaySec = 0
                lastPos = androidx.media3.common.C.TIME_UNSET
                lastBuffered = androidx.media3.common.C.TIME_UNSET
            }

            while (true) {
                if (!created || !started || type != STREAM || intentionalStopOrPause || streamEnded ||
                    quality?.name == VideoQuality.CHAT_ONLY_QUALITY ||
                    com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.leaveMuted
                ) {
                    resetCounters()
                    softKickUsed = false
                    delay(3000L)
                    continue
                }
                delay(1000L)
                if (!created || !started || type != STREAM || intentionalStopOrPause || streamEnded ||
                    waitingIntegrity || loadingStreamCount > 0 ||
                    quality?.name == VideoQuality.CHAT_ONLY_QUALITY ||
                    com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.leaveMuted ||
                    playingAds || videoSwapActive || proxyMediaPlaylist || hidden ||
                    adDelayJob?.isActive == true || secondaryLoadJob?.isActive == true ||
                    freezeWatchJob?.isActive == true || liveRecoveryJob?.isActive == true
                ) {
                    resetCounters()
                    continue
                }
                if (watchedGeneration != streamGeneration) {
                    watchedGeneration = streamGeneration
                    resetCounters()
                    softKickUsed = false
                }
                val p = player ?: run {
                    resetCounters()
                    continue
                }
                if (p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                    resetCounters()
                    continue
                }
                if (!p.playWhenReady) {
                    frozenReadySec = 0
                    stuckBufferingSec = 0
                    healthyPlaySec = 0
                    idleOrPausedSec++
                    if (idleOrPausedSec >= 5) {
                        idleOrPausedSec = 0
                        if (p.currentMediaItem == null) {
                            scheduleLiveRecovery(streamGeneration, channelLogin, initialDelayMs = 500L)
                        } else {
                            val preserveRewind = experimentalVodActive &&
                                (experimentalVodUserRewound || (getExperimentalLiveEdgeMs() - p.currentPosition) > 45_000L)
                            if (!preserveRewind) {
                                p.seekToDefaultPosition()
                            }
                            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                                p.prepare()
                            }
                            p.play()
                        }
                    }
                    continue
                }
                fun softKick(p: ExoPlayer) {
                    val preserveRewind = experimentalVodActive &&
                        (experimentalVodUserRewound || (getExperimentalLiveEdgeMs() - p.currentPosition) > 45_000L)
                    if (!preserveRewind) {
                        p.seekToDefaultPosition()
                    }
                    p.prepare()
                    p.play()
                }
                when (p.playbackState) {
                    Player.STATE_IDLE -> {
                        frozenReadySec = 0
                        stuckBufferingSec = 0
                        healthyPlaySec = 0
                        idleOrPausedSec++
                        if (idleOrPausedSec >= 5) {
                            idleOrPausedSec = 0
                            if (!softKickUsed && p.currentMediaItem != null) {
                                softKickUsed = true
                                softKick(p)
                            } else {
                                softKickUsed = false
                                scheduleLiveRecovery(streamGeneration, channelLogin, initialDelayMs = 500L)
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        idleOrPausedSec = 0
                        stuckBufferingSec = 0
                        val pos = p.currentPosition
                        if (p.isPlaying && pos != lastPos) {
                            frozenReadySec = 0
                            healthyPlaySec++
                            if (healthyPlaySec >= 8) {
                                softKickUsed = false
                                liveRecoveryAttempt = 0
                            }
                        } else {
                            healthyPlaySec = 0
                            frozenReadySec++
                            if (frozenReadySec >= 6) {
                                frozenReadySec = 0
                                if (!softKickUsed) {
                                    softKickUsed = true
                                    softKick(p)
                                } else {
                                    softKickUsed = false
                                    scheduleLiveRecovery(streamGeneration, channelLogin, initialDelayMs = 500L)
                                }
                            }
                        }
                        lastPos = pos
                        lastBuffered = p.bufferedPosition
                    }
                    Player.STATE_BUFFERING -> {
                        idleOrPausedSec = 0
                        frozenReadySec = 0
                        healthyPlaySec = 0
                        val pos = p.currentPosition
                        val buf = p.bufferedPosition
                        if (pos == lastPos && buf == lastBuffered) {
                            stuckBufferingSec++
                            if (stuckBufferingSec >= 15) {
                                stuckBufferingSec = 0
                                if (!softKickUsed) {
                                    softKickUsed = true
                                    softKick(p)
                                } else {
                                    softKickUsed = false
                                    scheduleLiveRecovery(streamGeneration, channelLogin, initialDelayMs = 500L)
                                }
                            }
                        } else {
                            stuckBufferingSec = 0
                        }
                        lastPos = pos
                        lastBuffered = buf
                    }
                    else -> resetCounters()
                }
            }
        }
    }

    fun startAudioOnly() {
        player?.let { player ->
            if (quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                restoreQuality = true
                previousQuality = quality
                quality = qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
                quality?.let { quality ->
                    player.currentMediaItem?.let { mediaItem ->
                        if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                        }
                        if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                            quality.url?.let { url ->
                                val position = player.currentPosition
                                if (qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null) {
                                    restorePlaylist = true
                                    ignorePlaylistUpdate = true
                                }
                                setStreamMediaUri(player, mediaItem, url, position)
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop(isInPIPMode: Boolean) {
        player?.let { player ->
            val isInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if ((!isInPIPMode && isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO, true))
                || (!isInPIPMode && !isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, true))
                || (isInPIPMode && isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED, false))
                || (isInPIPMode && !isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED, true))) {
                if (player.playWhenReady && quality?.name != VideoQuality.AUDIO_ONLY_QUALITY) {
                    restoreQuality = true
                    previousQuality = quality
                    quality = qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
                    quality?.let { quality ->
                        player.currentMediaItem?.let { mediaItem ->
                            if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                }.build()
                            }
                            if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                quality.url?.let { url ->
                                    val position = player.currentPosition
                                    if (qualities?.find { it.name == VideoQuality.AUTO_QUALITY } != null) {
                                        restorePlaylist = true
                                        ignorePlaylistUpdate = true
                                    }
                                    setStreamMediaUri(player, mediaItem, url, position)
                                }
                            }
                        }
                    }
                }
            } else {
                pause()
            }
        }
    }

    private fun updatePlaybackState() {
        (secondaryPlayer ?: player)?.let { player ->
            val showSeekbar = showStreamNotificationSeekbar || !player.isCurrentMediaItemLive
            session?.setPlaybackState(
                PlaybackState.Builder().apply {
                    setState(
                        when (player.playbackState) {
                            Player.STATE_IDLE -> PlaybackState.STATE_NONE
                            Player.STATE_BUFFERING -> {
                                if (Util.shouldShowPlayButton(player)) {
                                    PlaybackState.STATE_PAUSED
                                } else {
                                    PlaybackState.STATE_BUFFERING
                                }
                            }
                            Player.STATE_READY -> {
                                if (Util.shouldShowPlayButton(player)) {
                                    PlaybackState.STATE_PAUSED
                                } else {
                                    PlaybackState.STATE_PLAYING
                                }
                            }
                            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
                            else -> PlaybackState.STATE_NONE
                        },
                        if (showSeekbar) {
                            player.currentPosition
                        } else {
                            -1
                        },
                        if (player.isPlaying && showSeekbar) {
                            player.playbackParameters.speed
                        } else {
                            0f
                        }
                    )
                    setBufferedPosition(
                        if (showSeekbar) {
                            player.bufferedPosition
                        } else {
                            -1
                        }
                    )
                    setActions(
                        (PlaybackState.ACTION_STOP
                                or PlaybackState.ACTION_PAUSE
                                or PlaybackState.ACTION_PLAY
                                or PlaybackState.ACTION_REWIND
                                or PlaybackState.ACTION_FAST_FORWARD
                                or PlaybackState.ACTION_SET_RATING
                                or PlaybackState.ACTION_PLAY_PAUSE
                                or PlaybackState.ACTION_PREPARE).let {
                            if (showSeekbar) {
                                it or PlaybackState.ACTION_SEEK_TO
                            } else {
                                it
                            }.let {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    it or PlaybackState.ACTION_SET_PLAYBACK_SPEED
                                } else {
                                    it
                                }
                            }
                        }
                    )
                    addCustomAction(INTENT_REWIND, ContextCompat.getString(this@ExoPlayerService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                    addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@ExoPlayerService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
                    if (isTvDevice) {
                        setActiveQueueItemId(0L)
                    }
                }.build()
            )
        }
        updateAudioFocus()
    }

    private val isTvDevice by lazy { resources.getBoolean(R.bool.isTv) }

    /**
     * TV: the app holds the audio focus while the stream or the video plays (or loads to play), whichever player shows it,
     * and gives it back once paused or stopped.
     */
    private fun updateAudioFocus() {
        if (!isTvDevice) {
            return
        }
        val playing = player?.let { it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED } == true ||
                secondaryPlayer?.let { it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED } == true
        if (playing) {
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.hold(this, this) { pause() }
        } else {
            com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.release(this, this)
        }
    }

    private fun updateMetadata() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            xtraModule.cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            xtraModule.cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                else -> {
                                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.bytes()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    setMetadata(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
        setMetadata(bitmap)
    }

    private fun setMetadata(bitmap: Bitmap?) {
        (secondaryPlayer ?: player)?.let { player ->
            val metadata = MediaMetadata.Builder().apply {
                    putText(MediaMetadata.METADATA_KEY_TITLE, title)
                    putText(MediaMetadata.METADATA_KEY_ARTIST, channelName)
                    if (bitmap != null) {
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                    }
                    putLong(
                        MediaMetadata.METADATA_KEY_DURATION,
                        if (showStreamNotificationSeekbar || !player.isCurrentMediaItemLive) {
                            player.duration
                        } else {
                            -1
                        }
                    )
                }.build()
            session?.setMetadata(metadata)
            if (isTvDevice) {
                // the item playing, as a queue of one item (as the usual players show it)
                session?.setQueue(listOf(MediaSession.QueueItem(metadata.description, 0L)))
            }
        }
    }

    private fun updateNotification() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.HttpEngineTimeout()
                                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            xtraModule.cronetExecutor.value,
                                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        val timeout = NetworkUtils.CronetTimeout()
                                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                            url,
                                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                            xtraModule.cronetExecutor.value
                                        ).build()
                                        timeout.start(request, continuation)
                                        request.start()
                                        continuation.invokeOnCancellation {
                                            request.cancel()
                                            timeout.stop()
                                        }
                                    }
                                    if (response.info.httpStatusCode in 200..299) {
                                        response.body
                                    } else null
                                }
                                else -> {
                                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.bytes()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    sendNotification(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
        sendNotification(bitmap)
    }

    /** What the notification sent last showed: it is only sent again when something of it changed (not at every playlist). */
    private var lastNotificationKey: String? = null

    private fun sendNotification(bitmap: Bitmap?) {
        (secondaryPlayer ?: player)?.let { player ->
            val playingAtSpeed = player.isPlaying && player.playbackParameters.speed == 1f
            val key = listOf(
                title, channelName, bitmap?.let { System.identityHashCode(it) }, Util.shouldShowPlayButton(player), playingAtSpeed,
                // start of the time shown, to 5 s (a seek or a stall moves it)
                if (playingAtSpeed) (System.currentTimeMillis() - player.currentPosition) / 5000 else null
            ).joinToString("|")
            if (key == lastNotificationKey) {
                return
            }
            lastNotificationKey = key
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, getString(R.string.notification_playback_channel_id))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle(title)
                setContentText(channelName)
                setSmallIcon(R.drawable.notification_icon)
                if (bitmap != null) {
                    setLargeIcon(bitmap)
                }
                setGroup(GROUP_KEY)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setOngoing(false)
                setOnlyAlertOnce(true)
                if (player.isPlaying && player.playbackParameters.speed == 1f) {
                    setWhen(System.currentTimeMillis() - player.currentPosition)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                setContentIntent(
                    PendingIntent.getActivity(
                        this@ExoPlayerService,
                        REQUEST_CODE_RESUME,
                        Intent(this@ExoPlayerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_rewind),
                        ContextCompat.getString(this@ExoPlayerService, R.string.rewind),
                        PendingIntent.getService(
                            this@ExoPlayerService,
                            REQUEST_CODE_REWIND,
                            Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (Util.shouldShowPlayButton(player)) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_play),
                            ContextCompat.getString(this@ExoPlayerService, R.string.resume),
                            PendingIntent.getService(
                                this@ExoPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_pause),
                            ContextCompat.getString(this@ExoPlayerService, R.string.pause),
                            PendingIntent.getService(
                                this@ExoPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@ExoPlayerService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                        ContextCompat.getString(this@ExoPlayerService, R.string.forward),
                        PendingIntent.getService(
                            this@ExoPlayerService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@ExoPlayerService, ExoPlayerService::class.java).apply {
                                action = INTENT_FAST_FORWARD
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
            }.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    fun setSleepTimer(duration: Long): Long {
        val endTime = sleepTimerEndTime
        sleepTimer?.cancel()
        sleepTimerEndTime = 0L
        if (duration > 0L) {
            sleepTimer = Timer().apply {
                schedule(duration) {
                    Handler(Looper.getMainLooper()).post {
                        intentionalStopOrPause = true
                        liveRecoveryJob?.cancel()
                        liveRecoveryJob = null
                        savePosition()
                        player?.clearMediaItems()
                        player?.playWhenReady = false
                        stopSelf()
                    }
                }
            }
            sleepTimerEndTime = System.currentTimeMillis() + duration
        }
        return endTime
    }

    fun setStopServiceTimer(start: Boolean) {
        if (start) {
            if (stopServiceTimer == null && player?.isPlaying == false) {
                stopServiceTimer = Timer().apply {
                    schedule(600000) {
                        Handler(Looper.getMainLooper()).post {
                            stopSelf()
                        }
                    }
                }
            }
        } else {
            stopServiceTimer?.cancel()
            stopServiceTimer = null
        }
    }

    fun toggleDynamicsProcessing(): Boolean {
        val enabled = !(dynamicsProcessing?.enabled == true)
        prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled) }
        if (enabled) {
            if (dynamicsProcessing == null) {
                player?.audioSessionId?.takeIf { it != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET }?.let {
                    reinitializeDynamicsProcessing(it)
                }
            } else {
                dynamicsProcessing?.enabled = true
            }
            if (secondaryDynamicsProcessing == null) {
                secondaryPlayer?.audioSessionId?.takeIf { it != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET }?.let {
                    secondaryDynamicsProcessing = createDynamicsProcessing(it)
                }
            } else {
                secondaryDynamicsProcessing?.enabled = true
            }
        } else {
            dynamicsProcessing?.enabled = false
            secondaryDynamicsProcessing?.enabled = false
        }
        return enabled
    }

    private fun reinitializeDynamicsProcessing(audioSessionId: Int) {
        dynamicsProcessing?.release()
        dynamicsProcessing = createDynamicsProcessing(audioSessionId)
    }

    private fun createDynamicsProcessing(audioSessionId: Int): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
            return try {
                DynamicsProcessing(0, audioSessionId, null).apply {
                    for (channelIdx in 0 until channelCount) {
                        for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                            setMbcBandByChannelIndex(
                                channelIdx,
                                bandIdx,
                                getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                    attackTime = 0f
                                    releaseTime = 0.25f
                                    ratio = 1.6f
                                    threshold = -50f
                                    kneeWidth = 40f
                                    preGain = 0f
                                    postGain = 10f
                                }
                            )
                        }
                    }
                    enabled = true
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun savePosition() {
        player?.let { player ->
            if (!player.currentTracks.isEmpty) {
                if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                    when (type) {
                        VIDEO -> {
                            videoId?.toLongOrNull()?.let {
                                runBlocking {
                                    xtraModule.playerRepository.saveVideoPosition(VideoPosition(it, player.currentPosition))
                                }
                                saveTvResume(it, player.currentPosition)
                            }
                        }
                        OFFLINE_VIDEO -> {
                            offlineVideoId?.let {
                                runBlocking {
                                    xtraModule.offlineVideosRepository.updatePosition(it, player.currentPosition)
                                }
                            }
                        }
                    }
                }
                runBlocking {
                    xtraModule.playerRepository.deletePlaybackStates()
                }
            }
        }
    }

    /** TV: the "Continue watching" row of the home, kept up to date while the video plays and when it stops. */
    private fun saveTvResume(id: Long, position: Long) {
        com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.save(
            this,
            com.github.andreyasadchy.xtra.ui.tv.TvResumeHistory.Item(
                id = id.toString(),
                title = title,
                channelId = channelId,
                channelLogin = channelLogin,
                channelName = channelName,
                channelImage = channelImage,
                gameId = gameId,
                gameName = gameName,
                thumbnail = thumbnail,
                createdAt = createdAt,
                durationSeconds = durationSeconds ?: player?.duration?.takeIf { duration -> duration > 0 }?.div(1000)?.toInt(),
                positionMs = position,
                type = videoType,
            )
        )
    }

    private fun updateSavedPosition() {
        player?.let { player ->
            if (!player.currentTracks.isEmpty) {
                val currentPosition = player.currentPosition
                val savedPosition = lastSavedPosition
                if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                    lastSavedPosition = currentPosition
                    if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                        when (type) {
                            VIDEO -> {
                                videoId?.toLongOrNull()?.let {
                                    runBlocking {
                                        xtraModule.playerRepository.saveVideoPosition(VideoPosition(it, currentPosition))
                                    }
                                    saveTvResume(it, currentPosition)
                                }
                            }
                            OFFLINE_VIDEO -> {
                                offlineVideoId?.let {
                                    runBlocking {
                                        xtraModule.offlineVideosRepository.updatePosition(it, currentPosition)
                                    }
                                }
                            }
                        }
                    }
                    runBlocking {
                        savePlaybackState(currentPosition, !player.playWhenReady)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            INTENT_REWIND -> seekBack()
            INTENT_PLAY_PAUSE -> togglePause()
            INTENT_FAST_FORWARD -> seekForward()
            INTENT_START -> create(restorePauseState = true)
            Intent.ACTION_MEDIA_BUTTON -> create(restorePauseState = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@ExoPlayerService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        intentionalStopOrPause = true
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        experimentalVodUpgradeJob?.cancel()
        experimentalVodUpgradeJob = null
        savePosition()
        player?.playWhenReady = false
        secondaryPlayer?.playWhenReady = false
        stopSelf()
    }

    /** Synchronously releases all hardware decoders and resets service state when closing before a new playback session. */
    fun releaseNow() {
        streamGeneration++
        intentionalStopOrPause = true
        streamEnded = false
        liveRecoveryAttempt = 0
        waitingIntegrity = false
        liveRecoveryJob?.cancel()
        liveRecoveryJob = null
        experimentalVodUpgradeJob?.cancel()
        experimentalVodUpgradeJob = null
        experimentalVodActive = false
        experimentalVodUserRewound = false
        experimentalFallback = ExperimentalFallback.NONE
        clearExperimentalBadgeNotice()
        experimentalVodCreatedAtMs = null
        freezeWatchJob?.cancel()
        freezeWatchJob = null
        adMainWatchJob?.cancel()
        adMainWatchJob = null
        secondaryLoadJob?.cancel()
        secondaryLoadJob = null
        adDelayJob?.cancel()
        adDelayJob = null
        secondaryPlayerTimerJob?.cancel()
        secondaryPlayerTimerJob = null
        bitmapLoadJob?.cancel()
        bitmapLoadJob = null
        savePositionTimer?.cancel()
        savePositionTimer = null
        stopServiceTimer?.cancel()
        stopServiceTimer = null
        com.github.andreyasadchy.xtra.ui.tv.TvPlaybackSignals.release(this, this)
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        runCatching { secondaryDynamicsProcessing?.release() }
        secondaryDynamicsProcessing = null
        player?.release()
        player = null
        secondaryPlayer?.release()
        secondaryPlayer = null
        session?.release()
        session = null
        notificationManager?.cancel(NOTIFICATION_ID)
        created = false
        started = false
        loaded = false
        playingAds = false
        videoSwapActive = false
        proxyMediaPlaylist = false
        hidden = false
        fallbackH264ForStream = false
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseNow()
    }

    class CustomHlsPlaylistParserFactory: HlsPlaylistParserFactory {
        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser()
        }

        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> {
            return HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        }
    }

    companion object {
        private fun getStreamTokenHeaders(context: android.content.Context, platform: String?): Map<String, String> {
            val includeToken = context.prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)
            return if (platform.isNullOrBlank() || platform == "web") {
                TwitchApiHelper.getGQLWebHeaders(context, includeToken)
            } else {
                TwitchApiHelper.getGQLHeaders(context, includeToken)
            }
        }

        /** Address of the playlist of a stream (access of Twitch included), with the settings of the player. Throws on failure. */
        suspend fun loadStreamPlaylistUrl(
            context: android.content.Context,
            xtraModule: com.github.andreyasadchy.xtra.XtraModule,
            channelLogin: String,
            streamProxy: StreamProxy? = null,
            supportedCodecsOverride: String? = null,
        ): String {
            val prefs = context.prefs()
            return xtraModule.playerRepository.loadStreamPlaylistUrl(
                context = context,
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = getStreamTokenHeaders(context, prefs.getString(C.TOKEN_PLATFORM, "web")),
                channelLogin = channelLogin,
                platform = prefs.getString(C.TOKEN_PLATFORM, "web"),
                playerType = prefs.getString(C.TOKEN_PLAYER_TYPE, "site").let {
                    // like VAFT, the "popout" player type gets fewer ads than the default "site" one
                    if (TvExperimentalAdblock.isSwapAdblockEnabled(context) && (it.isNullOrBlank() || it == "site")) "popout" else it
                },
                supportedCodecs = supportedCodecsOverride ?: prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                proxyPlaybackAccessToken = streamProxy != null,
                proxyHost = streamProxy?.host,
                proxyPort = streamProxy?.port,
                proxyUser = streamProxy?.username,
                proxyPassword = streamProxy?.password,
                proxyTimeout = prefs.getString(C.PROXY_TIMEOUT, "3000")?.toIntOrNull() ?: 3000,
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false) && streamProxy == null
            )
        }

        /** Player types whose playlists usually come without ads (same idea as the VAFT userscript). */
        private var bandwidthMeter: DefaultBandwidthMeter? = null

        private fun isUnmetered(context: android.content.Context): Boolean {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        /** Shared by main players across stream switches. */
        fun getBandwidthMeter(context: android.content.Context): DefaultBandwidthMeter {
            return bandwidthMeter ?: DefaultBandwidthMeter.Builder(context.applicationContext).apply {
                if (isUnmetered(context)) {
                    setInitialBitrateEstimate(20_000_000L)
                }
            }.build().also { bandwidthMeter = it }
        }

        /**
         * Isolated bandwidth meter for the ad-blocker backup player so slow or throttled proxy segments never pollute
         * the main player's bandwidth estimate when returning to live after an ad break.
         */
        fun createSecondaryBandwidthMeter(context: android.content.Context): DefaultBandwidthMeter {
            return DefaultBandwidthMeter.Builder(context.applicationContext).apply {
                if (isUnmetered(context)) {
                    setInitialBitrateEstimate(20_000_000L)
                } else {
                    bandwidthMeter?.bitrateEstimate?.takeIf { it > 0L }?.let { setInitialBitrateEstimate(it) }
                }
            }.build()
        }

        // autoplay first, like the default video swap list
        /** A backup of the ad blocker that is a custom proxy (its playlist address in the player type). */
        private const val AD_PROXY_PLATFORM = "xtra_custom_proxy"
        /** Time between the end of the last ad and the stream back, measured on the previous breaks (ms). */
        private const val AD_RETURN_DELAY = "tv_ad_return_delay"
        private const val DEFAULT_AD_RETURN_DELAY = 2500L

        private val ADBLOCK_PLAYER_TYPES = listOf(
            "android" to "autoplay",
            "web" to "embed",
            "web" to "popout",
        )
        const val MULTIVARIANT_PLAYLIST_REGEX = "^usher\\.ttvnw\\.net$"
        const val MEDIA_PLAYLIST_REGEX = "^(?:[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net)$"

        private const val NOTIFICATION_ID = 1001
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.PLAYBACK_NOTIFICATIONS"

        private const val REQUEST_CODE_RESUME = 0
        private const val REQUEST_CODE_REWIND = 1
        private const val REQUEST_CODE_PLAY_PAUSE = 2
        private const val REQUEST_CODE_FAST_FORWARD = 3

        private const val INTENT_REWIND = "com.github.andreyasadchy.xtra.REWIND"
        private const val INTENT_PLAY_PAUSE = "com.github.andreyasadchy.xtra.PLAY_PAUSE"
        private const val INTENT_FAST_FORWARD = "com.github.andreyasadchy.xtra.FAST_FORWARD"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_PLAYBACK_SERVICE"
    }
}