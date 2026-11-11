package com.github.andreyasadchy.xtra.ui.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.view.TvUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android TV / Google TV Home Launcher integration:
 * 1. Preview Channel "Followed Live" showing currently live followed streamers.
 * 2. System "Watch Next" row synced with unfinished VODs from [TvResumeHistory].
 */
object TvLauncherChannels {

    private const val PREFS_FILE = "tv_launcher_channels"
    private const val KEY_FOLLOWED_CHANNEL_ID = "followed_channel_id"
    private const val KEY_BROWSABLE_REQUESTED = "followed_browsable_requested"
    private const val PROVIDER_FOLLOWED = "xtra_followed_live"
    private const val MAX_LIVE_PROGRAMS = 12
    private const val MAX_WATCH_NEXT = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var liveSyncJob: Job? = null
    private var watchNextJob: Job? = null

    fun syncFollowedLive(context: Context, streams: List<Stream>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !TvUi.isTv(context)) return
        val appContext = context.applicationContext
        val snapshot = streams.filter { !it.channelLogin.isNullOrBlank() }.take(MAX_LIVE_PROGRAMS)
        liveSyncJob?.cancel()
        liveSyncJob = scope.launch {
            runCatching { writeFollowedChannel(appContext, snapshot) }
        }
    }

    fun syncWatchNext(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !TvUi.isTv(context)) return
        val appContext = context.applicationContext
        watchNextJob?.cancel()
        watchNextJob = scope.launch {
            runCatching { writeWatchNext(appContext) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun writeFollowedChannel(context: Context, streams: List<Stream>) {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val channelId = getOrCreateFollowedChannel(context, prefs) ?: return

        // Replace existing preview programs in our channel with current live streams
        runCatching {
            resolver.delete(
                TvContract.buildPreviewProgramsUriForChannel(channelId),
                null,
                null,
            )
        }

        val total = streams.size
        streams.forEachIndexed { index, stream ->
            val login = stream.channelLogin ?: return@forEachIndexed
            val name = stream.channelName?.takeIf { it.isNotBlank() } ?: login
            val game = stream.gameName?.takeIf { it.isNotBlank() }
            val cardTitle = if (game != null) "$name · $game" else name
            val poster = stream.thumbnail?.takeUnless { stream.thumbnailURL.isNullOrBlank() }
                ?: stream.channelImage
            val deepLink = Uri.parse("https://www.twitch.tv/$login").buildUpon()
                .appendQueryParameter("xtratv_play", "1")
                .apply {
                    stream.id?.let { appendQueryParameter("id", it) }
                    stream.channelId?.let { appendQueryParameter("cid", it) }
                    appendQueryParameter("name", name)
                    stream.channelImageURL?.let { appendQueryParameter("img", it) }
                    stream.title?.let { appendQueryParameter("title", it) }
                    game?.let { appendQueryParameter("game", it) }
                }
                .build()
            val intentUri = Intent(Intent.ACTION_VIEW, deepLink)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .toUri(Intent.URI_INTENT_SCHEME)

            val values = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_EVENT)
                put(TvContract.PreviewPrograms.COLUMN_LIVE, 1)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, cardTitle)
                put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, stream.title.orEmpty())
                poster?.let { put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, it) }
                put(
                    TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.ASPECT_RATIO_16_9,
                )
                put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, intentUri)
                put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, "live_$login")
                put(TvContract.PreviewPrograms.COLUMN_WEIGHT, (total - index) * 10)
            }
            resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getOrCreateFollowedChannel(
        context: Context,
        prefs: android.content.SharedPreferences,
    ): Long? {
        val resolver = context.contentResolver
        val savedId = prefs.getLong(KEY_FOLLOWED_CHANNEL_ID, -1L)
        if (savedId > 0L) {
            val exists = resolver.query(
                TvContract.buildChannelUri(savedId),
                arrayOf(TvContract.Channels._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
            if (exists) return savedId
        }

        // Check if channel already exists by internal provider ID
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == PROVIDER_FOLLOWED) {
                    val foundId = cursor.getLong(0)
                    prefs.edit { putLong(KEY_FOLLOWED_CHANNEL_ID, foundId) }
                    return foundId
                }
            }
        }

        val appIntentUri = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .toUri(Intent.URI_INTENT_SCHEME)
        val channelValues = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, context.getString(R.string.tv_followed_live))
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, appIntentUri)
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, PROVIDER_FOLLOWED)
        }
        val uri = resolver.insert(TvContract.Channels.CONTENT_URI, channelValues) ?: return null
        val channelId = ContentUris.parseId(uri).takeIf { it > 0L } ?: return null
        prefs.edit { putLong(KEY_FOLLOWED_CHANNEL_ID, channelId) }

        writeChannelLogo(context, channelId)
        if (!prefs.getBoolean(KEY_BROWSABLE_REQUESTED, false)) {
            prefs.edit { putBoolean(KEY_BROWSABLE_REQUESTED, true) }
            runCatching { TvContract.requestChannelBrowsable(context, channelId) }
        }
        return channelId
    }

    private fun writeChannelLogo(context: Context, channelId: Long) {
        runCatching {
            val drawable = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher) ?: return
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun writeWatchNext(context: Context) {
        val resolver = context.contentResolver
        val items = TvResumeHistory.list(context).take(MAX_WATCH_NEXT)
        val wantedIds = items.map { "vod_${it.id}" }.toSet()

        // Query existing Xtra Watch Next entries so we update/remove cleanly without disturbing user dismissals
        val existing = mutableMapOf<String, Long>()
        resolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            arrayOf(
                TvContract.WatchNextPrograms._ID,
                TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val providerId = cursor.getString(1) ?: continue
                if (providerId.startsWith("vod_")) {
                    existing[providerId] = id
                }
            }
        }

        existing.forEach { (providerId, rowId) ->
            if (providerId !in wantedIds) {
                runCatching { resolver.delete(TvContract.buildWatchNextProgramUri(rowId), null, null) }
            }
        }

        val now = System.currentTimeMillis()
        items.forEachIndexed { index, item ->
            val providerId = "vod_${item.id}"
            val seconds = (item.positionMs / 1000L).coerceAtLeast(0L)
            val deepLink = Uri.parse("https://www.twitch.tv/videos/${item.id}?t=${seconds}s")
            val intentUri = Intent(Intent.ACTION_VIEW, deepLink)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .toUri(Intent.URI_INTENT_SCHEME)
            val name = item.channelName?.takeIf { it.isNotBlank() } ?: item.channelLogin.orEmpty()
            val title = item.title?.takeIf { it.isNotBlank() } ?: name

            val values = ContentValues().apply {
                put(
                    TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                    TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
                )
                put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_CLIP)
                put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, now - index * 1000L)
                put(TvContract.WatchNextPrograms.COLUMN_TITLE, title)
                put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, name)
                item.thumbnail?.takeIf { it.isNotBlank() }?.let {
                    put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it)
                }
                put(
                    TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.WatchNextPrograms.ASPECT_RATIO_16_9,
                )
                put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, item.positionMs.toInt())
                item.durationSeconds?.takeIf { it > 0 }?.let {
                    put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, it * 1000)
                }
                put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUri)
                put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, providerId)
            }

            val existingRow = existing[providerId]
            if (existingRow != null) {
                resolver.update(TvContract.buildWatchNextProgramUri(existingRow), values, null, null)
            } else {
                resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
            }
        }
    }
}
