package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.HttpEngine
import android.provider.DocumentsContract
import android.util.JsonReader
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationWorker
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.UpdateInstallHelper
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.system.exitProcess
import kotlin.time.Instant

class SettingsViewModel(
    private val applicationContext: Context,
    private val playerRepository: PlayerRepository,
    private val offlineVideosRepository: OfflineVideosRepository,
    private val recentSearchesRepository: RecentSearchesRepository,
    private val notificationsRepository: NotificationsRepository,
    private val appDatabase: AppDatabase,
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) : ViewModel() {

    val updateUrl = MutableSharedFlow<String?>()
    var updateSize: Long? = null
    var updateVersion: String? = null
    var updateNotes: String? = null
    var updateJob: Job? = null
    val updateProgress = MutableSharedFlow<Int>()
    val closeUpdateDialog = MutableSharedFlow<Boolean>()
    val updateInstallBlocked = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The downloaded update to open with the installer of Android (the second way of installing). */
    val updateInstallApk = MutableSharedFlow<File>(extraBufferCapacity = 1)

    fun deletePositions() {
        viewModelScope.launch {
            playerRepository.deleteVideoPositions()
            offlineVideosRepository.deletePositions()
        }
    }

    fun deleteRecentSearches() {
        viewModelScope.launch {
            recentSearchesRepository.deleteAll()
        }
    }

    fun importDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val chatFiles = mutableMapOf<String, String>()
            applicationContext.getExternalFilesDirs(".downloads").forEach { storage ->
                storage?.absolutePath?.let { directory ->
                    File(directory).listFiles()?.let { files ->
                        files.filter { it.name.endsWith(".json") }.forEach { chatFile ->
                            chatFiles[chatFile.name.removeSuffix(".json").removeSuffix("_chat")] = chatFile.path
                        }
                        files.filter { !it.name.endsWith(".json") }.forEach { file ->
                            if (file.isDirectory) {
                                file.listFiles()?.filter { it.name.endsWith(".m3u8") }?.forEach { playlistFile ->
                                        val existingVideo = offlineVideosRepository.getByUrl(playlistFile.path)
                                        if (existingVideo == null) {
                                            val playlist = FileInputStream(playlistFile).use {
                                                PlaylistUtils.parseMediaPlaylist(it)
                                            }
                                            var totalDuration = 0L
                                            val segments = ArrayList<Segment>()
                                            playlist.segments.forEach { segment ->
                                                totalDuration += (segment.duration * 1000f).toLong()
                                                segments.add(segment.copy(uri = segment.uri.substringAfterLast("%2F").substringAfterLast("/")))
                                            }
                                            FileOutputStream(playlistFile).use {
                                                PlaylistUtils.writeMediaPlaylist(playlist.copy(
                                                    initSegmentUri = playlist.initSegmentUri?.substringAfterLast("%2F")?.substringAfterLast("/"),
                                                    segments = segments
                                                ), it)
                                            }
                                            val chatFile = chatFiles[file.name + playlistFile.name.removeSuffix(".m3u8")]
                                            var id: String? = null
                                            var title: String? = null
                                            var uploadDate: Long? = null
                                            var channelId: String? = null
                                            var channelLogin: String? = null
                                            var channelName: String? = null
                                            var gameId: String? = null
                                            var gameSlug: String? = null
                                            var gameName: String? = null
                                            chatFile?.let { uri ->
                                                try {
                                                    FileInputStream(File(uri)).bufferedReader().use { fileReader ->
                                                        JsonReader(fileReader).use { reader ->
                                                            reader.beginObject()
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName()) {
                                                                    "video" -> {
                                                                        reader.beginObject()
                                                                        while (reader.hasNext()) {
                                                                            when (reader.nextName()) {
                                                                                "id" -> id = reader.nextString()
                                                                                "title" -> title = reader.nextString()
                                                                                "uploadDate" -> uploadDate = reader.nextLong()
                                                                                "channelId" -> channelId = reader.nextString()
                                                                                "channelLogin" -> channelLogin = reader.nextString()
                                                                                "channelName" -> channelName = reader.nextString()
                                                                                "gameId" -> gameId = reader.nextString()
                                                                                "gameSlug" -> gameSlug = reader.nextString()
                                                                                "gameName" -> gameName = reader.nextString()
                                                                                else -> reader.skipValue()
                                                                            }
                                                                        }
                                                                        reader.endObject()
                                                                    }
                                                                    else -> reader.skipValue()
                                                                }
                                                            }
                                                            reader.endObject()
                                                        }
                                                    }
                                                } catch (e: Exception) {

                                                }
                                            }
                                            offlineVideosRepository.save(
                                                OfflineVideo(
                                                    url = playlistFile.path,
                                                    name = if (!title.isNullOrBlank()) title else Uri.decode(file.name),
                                                    channelId = if (!channelId.isNullOrBlank()) channelId else null,
                                                    channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                                                    channelName = if (!channelName.isNullOrBlank()) channelName else null,
                                                    thumbnail = file.path + File.separator + segments.getOrNull(max(0, (segments.size / 2) - 1))?.uri,
                                                    gameId = if (!gameId.isNullOrBlank()) gameId else null,
                                                    gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                                                    gameName = if (!gameName.isNullOrBlank()) gameName else null,
                                                    duration = totalDuration,
                                                    uploadDate = uploadDate,
                                                    progress = 100,
                                                    maxProgress = 100,
                                                    status = OfflineVideo.STATUS_DOWNLOADED,
                                                    videoId = if (!id.isNullOrBlank()) id else null,
                                                    chatUrl = chatFile
                                                )
                                            )
                                        }
                                    }
                            } else if (file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))) {
                                val existingVideo = offlineVideosRepository.getByUrl(file.path)
                                if (existingVideo == null) {
                                    val fileName = file.name.removeSuffix(".mp4").removeSuffix(".ts")
                                    val chatFile = chatFiles[fileName]
                                    var id: String? = null
                                    var title: String? = null
                                    var uploadDate: Long? = null
                                    var channelId: String? = null
                                    var channelLogin: String? = null
                                    var channelName: String? = null
                                    var gameId: String? = null
                                    var gameSlug: String? = null
                                    var gameName: String? = null
                                    chatFile?.let { uri ->
                                        try {
                                            FileInputStream(File(uri)).bufferedReader().use { fileReader ->
                                                JsonReader(fileReader).use { reader ->
                                                    reader.beginObject()
                                                    while (reader.hasNext()) {
                                                        when (reader.nextName()) {
                                                            "video" -> {
                                                                reader.beginObject()
                                                                while (reader.hasNext()) {
                                                                    when (reader.nextName()) {
                                                                        "id" -> id = reader.nextString()
                                                                        "title" -> title = reader.nextString()
                                                                        "uploadDate" -> uploadDate = reader.nextLong()
                                                                        "channelId" -> channelId = reader.nextString()
                                                                        "channelLogin" -> channelLogin = reader.nextString()
                                                                        "channelName" -> channelName = reader.nextString()
                                                                        "gameId" -> gameId = reader.nextString()
                                                                        "gameSlug" -> gameSlug = reader.nextString()
                                                                        "gameName" -> gameName = reader.nextString()
                                                                        else -> reader.skipValue()
                                                                    }
                                                                }
                                                                reader.endObject()
                                                            }
                                                            else -> reader.skipValue()
                                                        }
                                                    }
                                                    reader.endObject()
                                                }
                                            }
                                        } catch (e: Exception) {

                                        }
                                    }
                                    offlineVideosRepository.save(
                                        OfflineVideo(
                                            url = file.path,
                                            name = if (!title.isNullOrBlank()) title else Uri.decode(fileName),
                                            channelId = if (!channelId.isNullOrBlank()) channelId else null,
                                            channelLogin = if (!channelLogin.isNullOrBlank()) channelLogin else null,
                                            channelName = if (!channelName.isNullOrBlank()) channelName else null,
                                            thumbnail = file.path,
                                            gameId = if (!gameId.isNullOrBlank()) gameId else null,
                                            gameSlug = if (!gameSlug.isNullOrBlank()) gameSlug else null,
                                            gameName = if (!gameName.isNullOrBlank()) gameName else null,
                                            uploadDate = uploadDate,
                                            progress = 100,
                                            maxProgress = 100,
                                            status = OfflineVideo.STATUS_DOWNLOADED,
                                            videoId = if (!id.isNullOrBlank()) id else null,
                                            chatUrl = chatFile
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun checkUpdates(networkLibrary: String?, url: String, lastChecked: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            updateUrl.emit(
                try {
                    val requestUrl = com.github.andreyasadchy.xtra.ui.main.MainViewModel.resolveUpdateRequestUrl(url)
                    val rawBody = when {
                        networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.HttpEngineTimeout()
                                val request = httpEngine.value!!.newUrlRequestBuilder(
                                    requestUrl,
                                    cronetExecutor.value,
                                    NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                ).build()
                                timeout.start(request, continuation)
                                request.start()
                                continuation.invokeOnCancellation {
                                    request.cancel()
                                    timeout.stop()
                                }
                            }
                            response.body.decodeToString()
                        }
                        networkLibrary == C.CRONET && cronetEngine.value != null -> {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.CronetTimeout()
                                val request = cronetEngine.value!!.newUrlRequestBuilder(
                                    requestUrl,
                                    NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                    cronetExecutor.value
                                ).build()
                                timeout.start(request, continuation)
                                request.start()
                                continuation.invokeOnCancellation {
                                    request.cancel()
                                    timeout.stop()
                                }
                            }
                            response.body.decodeToString()
                        }
                        else -> {
                            okHttpClient.value.newCall(Request.Builder().url(requestUrl).build()).executeAsync().use { response ->
                                response.body.string()
                            }
                        }
                    }
                    com.github.andreyasadchy.xtra.ui.main.MainViewModel.parseUpdatePayload(json, rawBody, lastChecked)?.let { parsed ->
                        updateSize = parsed.size
                        updateVersion = parsed.version
                        updateNotes = parsed.notes
                        parsed.downloadUrl
                    }
                } catch (e: Exception) {
                    null
                }
            )
        }
    }

    fun downloadUpdate(networkLibrary: String?, url: String) {
        UpdateInstallHelper.lastAttemptedUpdateUrl = url
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val progressListener = NetworkUtils.ProgressListener { bytesRead ->
                    runBlocking {
                        updateProgress.emit(bytesRead)
                    }
                }
                val response = when {
                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.HttpEngineTimeout()
                            val request = httpEngine.value!!.newUrlRequestBuilder(
                                url,
                                cronetExecutor.value,
                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout, progressListener)
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
                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.CronetTimeout()
                            val request = cronetEngine.value!!.newUrlRequestBuilder(
                                url,
                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout, progressListener),
                                cronetExecutor.value
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
                        okHttpClient.value.newBuilder().apply {
                            addNetworkInterceptor(NetworkUtils.ProgressInterceptor(progressListener))
                        }.build().newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            if (response.isSuccessful) {
                                response.body.bytes()
                            } else null
                        }
                    }
                }
                if (response != null && response.isNotEmpty()) {
                    // a session first, then the APK opened with the installer of Android (see UpdateInstallHelper)
                    UpdateInstallHelper.install(applicationContext, response, url)?.let {
                        updateInstallApk.emit(it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                updateInstallBlocked.emit(url)
            } catch (e: Exception) {

            }
            closeUpdateDialog.emit(true)
        }
    }

    private fun writeTreeDocument(
        treeUri: Uri,
        directoryUri: Uri,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String? {
        val resolver = applicationContext.contentResolver
        val base = if ('.' in fileName) fileName.substringBeforeLast('.') else fileName
        val ext = if ('.' in fileName) ".${fileName.substringAfterLast('.')}" else ""
        val duplicatePattern = Regex("^${Regex.escape(base)}\\s*\\(\\d+\\)${Regex.escape(ext)}$", RegexOption.IGNORE_CASE)
        var overwrittenExistingName: String? = null

        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val duplicateUris = mutableListOf<Uri>()
                var primaryEntry: Pair<Uri, String>? = null
                while (cursor.moveToNext()) {
                    val existingName = cursor.getString(nameCol) ?: continue
                    val existingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    if (existingName.equals(fileName, ignoreCase = true) && primaryEntry == null) {
                        primaryEntry = existingUri to existingName
                    } else if (duplicatePattern.matches(existingName)) {
                        duplicateUris += existingUri
                    }
                }
                duplicateUris.forEach { dupUri ->
                    runCatching { DocumentsContract.deleteDocument(resolver, dupUri) }
                }
                primaryEntry?.let { (existingUri, existingName) ->
                    val overwritten = runCatching {
                        resolver.openFileDescriptor(existingUri, "rwt")?.use { pfd ->
                            java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                                out.channel.truncate(0L)
                                out.write(bytes)
                                out.flush()
                            }
                            true
                        } ?: false
                    }.getOrElse {
                        runCatching {
                            resolver.openFileDescriptor(existingUri, "w")?.use { pfd ->
                                java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                                    out.channel.truncate(0L)
                                    out.write(bytes)
                                    out.flush()
                                }
                                true
                            } ?: false
                        }.getOrDefault(false)
                    }
                    if (overwritten) {
                        overwrittenExistingName = existingName
                    } else {
                        runCatching { DocumentsContract.deleteDocument(resolver, existingUri) }
                    }
                }
            }
        }
        overwrittenExistingName?.let { return it }
        val createdUri = runCatching {
            DocumentsContract.createDocument(resolver, directoryUri, mimeType, fileName)
        }.getOrNull() ?: return null
        val wrote = runCatching {
            resolver.openOutputStream(createdUri)?.use { out ->
                out.write(bytes)
                out.flush()
            } != null
        }.getOrDefault(false)
        if (!wrote) {
            runCatching { DocumentsContract.deleteDocument(resolver, createdUri) }
            return null
        }
        return runCatching {
            resolver.query(
                createdUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: fileName
    }

    fun backupSettings(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val zipBytes = runCatching {
                com.github.andreyasadchy.xtra.ui.tv.TvBackup.buildBackupZip(applicationContext)
            }.getOrNull() ?: return@launch

            val savedName = runCatching {
                val treeUri = url.toUri()
                val documentId = DocumentsContract.getTreeDocumentId(treeUri)
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

                val zipSaved = writeTreeDocument(
                    treeUri = treeUri,
                    directoryUri = directoryUri,
                    fileName = com.github.andreyasadchy.xtra.ui.tv.TvBackup.FILE_NAME,
                    mimeType = "application/zip",
                    bytes = zipBytes
                )
                val prefsName = "${applicationContext.packageName}_preferences.xml"
                val prefsSaved = writeTreeDocument(
                    treeUri = treeUri,
                    directoryUri = directoryUri,
                    fileName = prefsName,
                    mimeType = "text/xml",
                    bytes = com.github.andreyasadchy.xtra.ui.tv.TvBackup.exportDefaultPrefsXml(applicationContext)
                )
                val dbFile = applicationContext.getDatabasePath("database")
                if (com.github.andreyasadchy.xtra.ui.tv.TvBackup.isValidSqliteFile(dbFile)) {
                    runCatching {
                        writeTreeDocument(
                            treeUri = treeUri,
                            directoryUri = directoryUri,
                            fileName = dbFile.name,
                            mimeType = "application/octet-stream",
                            bytes = dbFile.readBytes()
                        )
                    }
                }
                zipSaved ?: prefsSaved
            }.getOrNull()

            if (savedName != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        applicationContext,
                        applicationContext.getString(com.github.andreyasadchy.xtra.R.string.tv_backup_saved, savedName),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                com.github.andreyasadchy.xtra.ui.tv.TvBackup.saveToTv(applicationContext, viewModelScope)
            }
        }
    }

    fun restoreSettings(list: List<String>, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        if (list.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = applicationContext.contentResolver
            val postPrefsCallback: (Map<String, Any>) -> Unit = { parsed ->
                if (!applicationContext.resources.getBoolean(com.github.andreyasadchy.xtra.R.bool.isTv)) {
                    toggleNotifications(
                        parsed[C.LIVE_NOTIFICATIONS_ENABLED] == true,
                        networkLibrary,
                        gqlHeaders,
                        helixHeaders
                    )
                }
            }

            var mainPrefsXml: ByteArray? = null
            var tvFavoritesXml: ByteArray? = null
            var tvStatsXml: ByteArray? = null
            var tvBlacklistXml: ByteArray? = null
            var dbBytes: ByteArray? = null

            for (rawUri in list) {
                val uri = runCatching { rawUri.toUri() }.getOrNull() ?: continue
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue

                // ZIP archive (magic bytes PK) — contains all backup components together
                if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    if (com.github.andreyasadchy.xtra.ui.tv.TvBackup.applyBackupBytes(applicationContext, bytes, postPrefsCallback)) {
                        return@launch
                    }
                    continue
                }

                if (com.github.andreyasadchy.xtra.ui.tv.TvBackup.isValidSqliteBytes(bytes)) {
                    dbBytes = bytes
                    continue
                }

                if (com.github.andreyasadchy.xtra.ui.tv.TvBackup.isSharedPrefsXmlBytes(bytes)) {
                    val displayName = runCatching {
                        resolver.query(
                            uri,
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull().orEmpty()
                    when {
                        displayName.equals("tv_favorites.xml", ignoreCase = true) -> tvFavoritesXml = bytes
                        displayName.equals("tv_stats.xml", ignoreCase = true) -> tvStatsXml = bytes
                        displayName.equals("tv_blacklist.xml", ignoreCase = true) -> tvBlacklistXml = bytes
                        displayName.equals("prefs2.xml", ignoreCase = true) -> Unit
                        else -> mainPrefsXml = bytes
                    }
                }
            }

            val restored = com.github.andreyasadchy.xtra.ui.tv.TvBackup.applyRestoredParts(
                context = applicationContext,
                mainPrefsXml = mainPrefsXml,
                tvFavoritesXml = tvFavoritesXml,
                tvStatsXml = tvStatsXml,
                tvBlacklistXml = tvBlacklistXml,
                dbBytes = dbBytes,
                onPostPrefsCommit = postPrefsCallback
            )
            if (!restored) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                        applicationContext,
                        com.github.andreyasadchy.xtra.R.string.tv_backup_not_found,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun toggleNotifications(enabled: Boolean, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                notificationsRepository.getNewStreams(networkLibrary, gqlHeaders, helixHeaders)
                WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                    "live_notifications",
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                        .setInitialDelay(1, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            } else {
                WorkManager.getInstance(applicationContext).cancelUniqueWork("live_notifications")
            }
        }
    }

    companion object {
        val SettingsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SettingsViewModel(application.applicationContext, xtraModule.playerRepository, xtraModule.offlineVideosRepository, xtraModule.recentSearchesRepository, xtraModule.notificationsRepository, xtraModule.database, xtraModule.httpEngine, xtraModule.cronetEngine, xtraModule.cronetExecutor, xtraModule.okHttpClient, xtraModule.json)
            }
        }
    }
}