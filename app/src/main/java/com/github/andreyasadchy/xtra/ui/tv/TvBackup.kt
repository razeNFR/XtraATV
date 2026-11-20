package com.github.andreyasadchy.xtra.ui.tv

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Xml
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.ui.player.MediaPlayerService
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV & mobile helper: backup and restore of settings, TV favorites/stats, and Room database (never Twitch account tokens),
 * either to Download/ on the device (with cross-Android permission check & atomic in-place overwrite) or over Wi-Fi via QR code.
 */
object TvBackup {

    const val FILE_NAME = "XtraTV-backup.zip"
    const val REQ_STORAGE_PERMISSION = 4901
    private const val PREFS_ENTRY = "preferences.xml"
    private const val TV_FAVORITES_ENTRY = "tv_favorites.xml"
    private const val TV_STATS_ENTRY = "tv_stats.xml"
    private const val TV_BLACKLIST_ENTRY = "tv_blacklist.xml"
    private const val DB_ENTRY = "database"
    private const val PENDING_TIMEOUT_MS = 5 * 60_000L
    private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.US_ASCII)

    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationBusy = AtomicBoolean(false)

    private class PendingFileAction(
        val createdAtElapsed: Long,
        val onGranted: () -> Unit,
        val onFallback: () -> Unit,
    )

    private var pendingFileAction: PendingFileAction? = null

    private data class ParsedBackup(
        val mainPrefsXml: ByteArray?,
        val tvFavoritesXml: ByteArray?,
        val tvStatsXml: ByteArray?,
        val tvBlacklistXml: ByteArray?,
        val dbBytes: ByteArray?,
    )

    /** True when the app has full read/write/overwrite access to shared `Download/` across Android 7..14+. */
    fun hasFullFileAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Automatically resumes any pending backup/restore as soon as the user returns from Android Settings having granted access. */
    fun onActivityResumed(activity: Activity) {
        val pending = pendingFileAction ?: return
        if (SystemClock.elapsedRealtime() - pending.createdAtElapsed > PENDING_TIMEOUT_MS) {
            pendingFileAction = null
            return
        }
        if (hasFullFileAccess(activity)) {
            pendingFileAction = null
            mainHandler.postDelayed({ pending.onGranted() }, 140L)
        }
    }

    /** Handles Android 7..10 runtime storage permission dialog result. */
    fun onRequestPermissionsResult(activity: Activity, requestCode: Int) {
        if (requestCode != REQ_STORAGE_PERMISSION) return
        val pending = pendingFileAction ?: return
        if (hasFullFileAccess(activity)) {
            pendingFileAction = null
            pending.onGranted()
        } else if (!activity.isFinishing && !activity.isDestroyed) {
            showFilePermissionPanel(activity, pending.onGranted, pending.onFallback)
        }
    }

    private fun openAllFilesPermissionSettings(activity: Activity): Boolean {
        val pkgUri = Uri.fromParts("package", activity.packageName, null)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri))
                add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            val launched = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }

    private fun showFilePermissionPanel(
        activity: Activity,
        onGranted: () -> Unit,
        onFallback: () -> Unit,
    ) {
        TvDialogs.show(activity) { dismiss ->
            val primaryFocus = remember { FocusRequester() }
            SidePanel(
                title = activity.getString(R.string.tv_backup_perm_title),
                width = 480.dp,
            ) {
                Text(
                    activity.getString(R.string.tv_backup_perm_summary),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = TvColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(18.dp))
                ButtonRow(
                    label = activity.getString(R.string.tv_backup_perm_grant),
                    modifier = Modifier.focusRequester(primaryFocus),
                ) {
                    dismiss()
                    pendingFileAction = PendingFileAction(
                        createdAtElapsed = SystemClock.elapsedRealtime(),
                        onGranted = onGranted,
                        onFallback = onFallback,
                    )
                    if (!openAllFilesPermissionSettings(activity)) {
                        pendingFileAction = null
                        onFallback()
                    }
                }
                Spacer(Modifier.height(8.dp))
                ButtonRow(
                    label = activity.getString(R.string.tv_backup_perm_continue_anyway),
                ) {
                    dismiss()
                    pendingFileAction = null
                    onFallback()
                }
                LaunchedEffect(Unit) {
                    runCatching { primaryFocus.requestFocus() }
                }
            }
        }
    }

    private fun ensureFileAccessOrPrompt(
        context: Context,
        onGranted: () -> Unit,
        onFallback: () -> Unit,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { ensureFileAccessOrPrompt(context, onGranted, onFallback) }
            return
        }
        if (hasFullFileAccess(context)) {
            onGranted()
            return
        }
        val activity = TvToast.currentActivity(context)
        if (activity == null) {
            onFallback()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showFilePermissionPanel(activity, onGranted, onFallback)
        } else {
            pendingFileAction = PendingFileAction(
                createdAtElapsed = SystemClock.elapsedRealtime(),
                onGranted = onGranted,
                onFallback = onFallback,
            )
            val requested = runCatching {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQ_STORAGE_PERMISSION,
                )
                true
            }.getOrDefault(false)
            if (!requested) {
                showFilePermissionPanel(activity, onGranted, onFallback)
            }
        }
    }

    fun isValidSqliteBytes(bytes: ByteArray): Boolean =
        bytes.size >= 16 && SQLITE_MAGIC.indices.all { bytes[it] == SQLITE_MAGIC[it] }

    fun isValidSqliteFile(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        return runCatching {
            val header = ByteArray(16)
            val read = file.inputStream().use { it.read(header) }
            read >= 15 && SQLITE_MAGIC.indices.all { header[it] == SQLITE_MAGIC[it] }
        }.getOrDefault(false)
    }

    fun isSharedPrefsXmlBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val startOffset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0
        val headLen = minOf(256, bytes.size - startOffset)
        if (headLen <= 0) return false
        val head = String(bytes, startOffset, headLen, Charsets.UTF_8).trimStart()
        return head.startsWith("<?xml") || head.startsWith("<map") || head.contains("<map")
    }

    /** Serializes an Android [SharedPreferences] instance directly from memory into standard `<map>` XML bytes. */
    fun exportSharedPrefsXml(prefs: SharedPreferences): ByteArray {
        val snapshot = prefs.all
        val out = ByteArrayOutputStream()
        val serializer = Xml.newSerializer()
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "map")
        for ((key, value) in snapshot) {
            if (key == null || value == null) continue
            when (value) {
                is Boolean -> {
                    serializer.startTag(null, "boolean")
                    serializer.attribute(null, "name", key)
                    serializer.attribute(null, "value", value.toString())
                    serializer.endTag(null, "boolean")
                }
                is Int -> {
                    serializer.startTag(null, "int")
                    serializer.attribute(null, "name", key)
                    serializer.attribute(null, "value", value.toString())
                    serializer.endTag(null, "int")
                }
                is Long -> {
                    serializer.startTag(null, "long")
                    serializer.attribute(null, "name", key)
                    serializer.attribute(null, "value", value.toString())
                    serializer.endTag(null, "long")
                }
                is Float -> {
                    serializer.startTag(null, "float")
                    serializer.attribute(null, "name", key)
                    serializer.attribute(null, "value", value.toString())
                    serializer.endTag(null, "float")
                }
                is String -> {
                    serializer.startTag(null, "string")
                    serializer.attribute(null, "name", key)
                    serializer.text(value)
                    serializer.endTag(null, "string")
                }
                is Set<*> -> {
                    serializer.startTag(null, "set")
                    serializer.attribute(null, "name", key)
                    for (item in value) {
                        if (item is String) {
                            serializer.startTag(null, "string")
                            serializer.text(item)
                            serializer.endTag(null, "string")
                        }
                    }
                    serializer.endTag(null, "set")
                }
            }
        }
        serializer.endTag(null, "map")
        serializer.endDocument()
        serializer.flush()
        return out.toByteArray()
    }

    fun exportDefaultPrefsXml(context: Context): ByteArray = exportSharedPrefsXml(context.prefs())

    private fun parseSharedPrefsXml(xmlBytes: ByteArray): Map<String, Any>? {
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
            var sawMap = false
            val result = LinkedHashMap<String, Any>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "map" -> sawMap = true
                        "boolean" -> if (sawMap) {
                            val name = parser.getAttributeValue(null, "name")
                            val value = parser.getAttributeValue(null, "value")
                            if (name != null && value != null) result[name] = value.toBoolean()
                        }
                        "int" -> if (sawMap) {
                            val name = parser.getAttributeValue(null, "name")
                            val value = parser.getAttributeValue(null, "value")?.toIntOrNull()
                            if (name != null && value != null) result[name] = value
                        }
                        "long" -> if (sawMap) {
                            val name = parser.getAttributeValue(null, "name")
                            val value = parser.getAttributeValue(null, "value")?.toLongOrNull()
                            if (name != null && value != null) result[name] = value
                        }
                        "float" -> if (sawMap) {
                            val name = parser.getAttributeValue(null, "name")
                            val value = parser.getAttributeValue(null, "value")?.toFloatOrNull()
                            if (name != null && value != null) result[name] = value
                        }
                        "string" -> if (sawMap) {
                            val name = parser.getAttributeValue(null, "name")
                            val text = parser.nextText()
                            if (name != null) result[name] = text
                        }
                        "set" -> if (sawMap) {
                            val name = parser.getAttributeValue(null, "name")
                            val set = LinkedHashSet<String>()
                            var setDepth = 1
                            while (setDepth > 0) {
                                val inner = parser.next()
                                if (inner == XmlPullParser.END_DOCUMENT) break
                                if (inner == XmlPullParser.START_TAG) {
                                    if (parser.name == "string") {
                                        set.add(parser.nextText())
                                    } else {
                                        setDepth++
                                    }
                                } else if (inner == XmlPullParser.END_TAG) {
                                    setDepth--
                                }
                            }
                            if (name != null) result[name] = set
                        }
                    }
                }
                event = parser.next()
            }
            if (sawMap) result else null
        }.getOrNull()
    }

    /** Atomically replaces all keys in [prefs] in memory AND on disk via synchronous [SharedPreferences.Editor.commit]. */
    @Suppress("ApplySharedPref")
    private fun commitSharedPrefsXml(prefs: SharedPreferences, xmlBytes: ByteArray): Map<String, Any>? {
        val parsed = parseSharedPrefsXml(xmlBytes) ?: return null
        val editor = prefs.edit().clear()
        for ((key, value) in parsed) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.commit()
        return parsed
    }

    /** Repairs a corrupted SQLite database file if an earlier broken restore overwrote it with non-SQLite bytes. */
    fun ensureHealthyDatabaseFile(context: Context): File {
        val app = context.applicationContext as? XtraApp
        val dbFile = context.getDatabasePath(DB_ENTRY)
        if (dbFile.exists() && !isValidSqliteFile(dbFile)) {
            app?.xtraModule?.closeDatabaseIfOpen()
            runCatching { File(dbFile.parentFile, "database-shm").delete() }
            runCatching { File(dbFile.parentFile, "database-wal").delete() }
            runCatching { File(dbFile.parentFile, "database-journal").delete() }
            runCatching { dbFile.delete() }
        }
        return dbFile
    }

    /** Writes [bytes] to a sibling `.tmp` file with fsync, then atomically replaces [targetFile] in place. */
    private fun atomicOverwriteFile(targetFile: File, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val parent = targetFile.parentFile ?: return false
        runCatching { parent.mkdirs() }
        val tmpFile = File(parent, "${targetFile.name}.tmp")
        val wroteTmp = runCatching {
            FileOutputStream(tmpFile, false).use { out ->
                out.write(bytes)
                out.flush()
                runCatching { out.fd.sync() }
            }
            tmpFile.exists() && tmpFile.length() == bytes.size.toLong()
        }.getOrDefault(false)

        if (wroteTmp) {
            val renamedDirect = runCatching { tmpFile.renameTo(targetFile) }.getOrDefault(false)
            if (renamedDirect && targetFile.exists() && targetFile.length() == bytes.size.toLong()) {
                return true
            }
            val deletedAndRenamed = runCatching {
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)
            }.getOrDefault(false)
            if (deletedAndRenamed && targetFile.exists() && targetFile.length() == bytes.size.toLong()) {
                return true
            }
        }

        // Fallback on filesystems where rename over an existing file is unsupported: direct overwrite + fsync
        val directOk = runCatching {
            FileOutputStream(targetFile, false).use { out ->
                out.write(bytes)
                out.flush()
                runCatching { out.fd.sync() }
            }
            targetFile.exists() && targetFile.length() == bytes.size.toLong()
        }.getOrDefault(false)
        runCatching { if (tmpFile.exists()) tmpFile.delete() }
        return directOk
    }

    /** Builds the backup archive in memory (settings XML, TV favorites/stats XML, and Room database — never Twitch tokens). */
    fun buildBackupZip(context: Context): ByteArray {
        val appContext = context.applicationContext
        val app = appContext as? XtraApp
        val dbFile = ensureHealthyDatabaseFile(appContext)
        runCatching {
            app?.xtraModule?.database?.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))?.use {
                it.moveToPosition(-1)
            }
        }
        val mainPrefsBytes = exportDefaultPrefsXml(appContext)
        val suffix = runCatching { TvAccounts.storageSuffix(appContext) }.getOrDefault("")
        val tvFavPrefs = appContext.getSharedPreferences("tv_favorites$suffix", Context.MODE_PRIVATE)
            .takeIf { it.all.isNotEmpty() } ?: appContext.getSharedPreferences("tv_favorites", Context.MODE_PRIVATE)
        val tvStatsPrefs = appContext.getSharedPreferences("tv_stats$suffix", Context.MODE_PRIVATE)
            .takeIf { it.all.isNotEmpty() } ?: appContext.getSharedPreferences("tv_stats", Context.MODE_PRIVATE)
        val tvBlacklistPrefs = appContext.getSharedPreferences(TvBlacklist.FILE + suffix, Context.MODE_PRIVATE)
            .takeIf { it.all.isNotEmpty() } ?: appContext.getSharedPreferences(TvBlacklist.FILE, Context.MODE_PRIVATE)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PREFS_ENTRY))
            zip.write(mainPrefsBytes)
            zip.closeEntry()

            if (tvFavPrefs.all.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(TV_FAVORITES_ENTRY))
                zip.write(exportSharedPrefsXml(tvFavPrefs))
                zip.closeEntry()
            }
            if (tvStatsPrefs.all.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(TV_STATS_ENTRY))
                zip.write(exportSharedPrefsXml(tvStatsPrefs))
                zip.closeEntry()
            }
            if (tvBlacklistPrefs.all.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(TV_BLACKLIST_ENTRY))
                zip.write(exportSharedPrefsXml(tvBlacklistPrefs))
                zip.closeEntry()
            }
            if (isValidSqliteFile(dbFile)) {
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Strictly parses and validates a backup ZIP archive in memory before any state is modified (all-or-nothing). */
    private fun parseAndValidateZip(bytes: ByteArray): ParsedBackup? {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return null
        }
        return try {
            var mainPrefsXml: ByteArray? = null
            var tvFavoritesXml: ByteArray? = null
            var tvStatsXml: ByteArray? = null
            var tvBlacklistXml: ByteArray? = null
            var dbBytes: ByteArray? = null
            var sawAnyEntry = false
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    sawAnyEntry = true
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        val content = zip.readBytes()
                        when {
                            name == TV_FAVORITES_ENTRY -> tvFavoritesXml = content
                            name == TV_STATS_ENTRY -> tvStatsXml = content
                            name == TV_BLACKLIST_ENTRY -> tvBlacklistXml = content
                            name == "prefs2.xml" -> Unit
                            name == PREFS_ENTRY || name.endsWith("_preferences.xml") -> mainPrefsXml = content
                            name == DB_ENTRY -> dbBytes = content
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!sawAnyEntry) return null
            if (mainPrefsXml != null && parseSharedPrefsXml(mainPrefsXml) == null) return null
            if (tvFavoritesXml != null && parseSharedPrefsXml(tvFavoritesXml) == null) return null
            if (tvStatsXml != null && parseSharedPrefsXml(tvStatsXml) == null) return null
            if (tvBlacklistXml != null && parseSharedPrefsXml(tvBlacklistXml) == null) return null
            if (dbBytes != null && !isValidSqliteBytes(dbBytes)) return null
            if (mainPrefsXml == null && tvFavoritesXml == null && tvStatsXml == null && tvBlacklistXml == null && dbBytes == null) {
                return null
            }
            ParsedBackup(mainPrefsXml, tvFavoritesXml, tvStatsXml, tvBlacklistXml, dbBytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Applies parsed backup components synchronously to in-memory + disk SharedPreferences and Room database,
     * stops active playback services cleanly, shows OSD confirmation, updates app locale if needed, and performs a clean cold restart.
     */
    fun applyRestoredParts(
        context: Context,
        mainPrefsXml: ByteArray? = null,
        tvFavoritesXml: ByteArray? = null,
        tvStatsXml: ByteArray? = null,
        tvBlacklistXml: ByteArray? = null,
        dbBytes: ByteArray? = null,
        onPostPrefsCommit: ((Map<String, Any>) -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val validatedMain = mainPrefsXml?.takeIf { isSharedPrefsXmlBytes(it) && parseSharedPrefsXml(it) != null }
        val validatedFav = tvFavoritesXml?.takeIf { isSharedPrefsXmlBytes(it) && parseSharedPrefsXml(it) != null }
        val validatedStats = tvStatsXml?.takeIf { isSharedPrefsXmlBytes(it) && parseSharedPrefsXml(it) != null }
        val validatedBl = tvBlacklistXml?.takeIf { isSharedPrefsXmlBytes(it) && parseSharedPrefsXml(it) != null }
        val validatedDb = dbBytes?.takeIf { isValidSqliteBytes(it) }
        if (validatedMain == null && validatedFav == null && validatedStats == null && validatedBl == null && validatedDb == null) {
            return false
        }

        // Stop playback services before touching Room or restarting process so SQLite is never written concurrently
        runCatching { appContext.stopService(Intent(appContext, ExoPlayerService::class.java)) }
        runCatching { appContext.stopService(Intent(appContext, MediaPlayerService::class.java)) }

        var restoredAny = false
        var restoredLanguage: String? = null
        val suffix = runCatching { TvAccounts.storageSuffix(appContext) }.getOrDefault("")

        if (validatedMain != null) {
            val parsed = commitSharedPrefsXml(appContext.prefs(), validatedMain)
            if (parsed != null) {
                restoredAny = true
                restoredLanguage = parsed[C.UI_LANGUAGE] as? String
                runCatching { onPostPrefsCommit?.invoke(parsed) }
            }
        }
        if (validatedFav != null) {
            val favPrefs = appContext.getSharedPreferences("tv_favorites$suffix", Context.MODE_PRIVATE)
            if (commitSharedPrefsXml(favPrefs, validatedFav) != null) {
                if (suffix.isNotEmpty()) {
                    commitSharedPrefsXml(appContext.getSharedPreferences("tv_favorites", Context.MODE_PRIVATE), validatedFav)
                }
                runCatching { TvFavorites.reload(appContext) }
                restoredAny = true
            }
        }
        if (validatedStats != null) {
            val statsPrefs = appContext.getSharedPreferences("tv_stats$suffix", Context.MODE_PRIVATE)
            if (commitSharedPrefsXml(statsPrefs, validatedStats) != null) {
                if (suffix.isNotEmpty()) {
                    commitSharedPrefsXml(appContext.getSharedPreferences("tv_stats", Context.MODE_PRIVATE), validatedStats)
                }
                restoredAny = true
            }
        }
        if (validatedBl != null) {
            val blPrefs = appContext.getSharedPreferences(TvBlacklist.FILE + suffix, Context.MODE_PRIVATE)
            if (commitSharedPrefsXml(blPrefs, validatedBl) != null) {
                if (suffix.isNotEmpty()) {
                    commitSharedPrefsXml(appContext.getSharedPreferences(TvBlacklist.FILE, Context.MODE_PRIVATE), validatedBl)
                }
                runCatching { TvBlacklist.reload(appContext) }
                restoredAny = true
            }
        }
        if (validatedDb != null) {
            (appContext as? XtraApp)?.xtraModule?.closeDatabaseIfOpen()
            val dbTarget = appContext.getDatabasePath(DB_ENTRY)
            dbTarget.parentFile?.mkdirs()
            runCatching { File(dbTarget.parentFile, "database-shm").delete() }
            runCatching { File(dbTarget.parentFile, "database-wal").delete() }
            runCatching { File(dbTarget.parentFile, "database-journal").delete() }
            if (atomicOverwriteFile(dbTarget, validatedDb)) {
                restoredAny = true
            }
        }

        if (!restoredAny) return false

        backupScope.launch {
            TvToast.show(appContext, R.string.tv_backup_restored, Toast.LENGTH_SHORT, R.drawable.tv_ic_check)
            if (restoredLanguage != null) {
                val desired = LocaleListCompat.forLanguageTags(
                    restoredLanguage.takeIf { it.isNotBlank() && it != "auto" }
                )
                if (AppCompatDelegate.getApplicationLocales() != desired) {
                    runCatching { AppCompatDelegate.setApplicationLocales(desired) }
                }
            }
            delay(650)
            val restartIntent = runCatching {
                Intent.makeRestartActivityTask(ComponentName(appContext, MainActivity::class.java))
            }.getOrElse {
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            }
            appContext.startActivity(restartIntent)
            exitProcess(0)
        }
        return true
    }

    /** Restores settings XML and/or database from a backup zip (or raw XML / SQLite bytes) and cold-restarts the app. */
    fun applyBackupBytes(
        context: Context,
        bytes: ByteArray,
        onPostPrefsCommit: ((Map<String, Any>) -> Unit)? = null,
    ): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val parsed = parseAndValidateZip(bytes) ?: return false
            return applyRestoredParts(
                context = context,
                mainPrefsXml = parsed.mainPrefsXml,
                tvFavoritesXml = parsed.tvFavoritesXml,
                tvStatsXml = parsed.tvStatsXml,
                tvBlacklistXml = parsed.tvBlacklistXml,
                dbBytes = parsed.dbBytes,
                onPostPrefsCommit = onPostPrefsCommit,
            )
        }
        if (isValidSqliteBytes(bytes)) {
            return applyRestoredParts(context, dbBytes = bytes, onPostPrefsCommit = onPostPrefsCommit)
        }
        if (isSharedPrefsXmlBytes(bytes)) {
            return applyRestoredParts(context, mainPrefsXml = bytes, onPostPrefsCommit = onPostPrefsCommit)
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanupDuplicateMediaStoreRows(context: Context, keepExactFileName: Boolean) {
        runCatching {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("XtraTV-backup%"),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    if (keepExactFileName && name.equals(FILE_NAME, ignoreCase = true)) {
                        continue
                    }
                    val existingUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                    runCatching { resolver.delete(existingUri, null, null) }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStoreDownloads(context: Context, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        // First try overwriting an existing MediaStore row named XtraTV-backup.zip owned by this app
        val overwrittenExisting = runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME),
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val existingUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                    val wrote = runCatching {
                        resolver.openOutputStream(existingUri, "wt")?.use { out ->
                            out.write(bytes)
                            out.flush()
                        } != null
                    }.getOrDefault(false)
                    if (wrote) return@runCatching true
                }
                false
            } ?: false
        }.getOrDefault(false)

        if (overwrittenExisting) {
            cleanupDuplicateMediaStoreRows(context, keepExactFileName = true)
            return "${Environment.DIRECTORY_DOWNLOADS}/$FILE_NAME"
        }

        cleanupDuplicateMediaStoreRows(context, keepExactFileName = false)
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val wrote = runCatching {
                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } != null
            }.getOrDefault(false)
            if (!wrote) {
                runCatching { resolver.delete(itemUri, null, null) }
                return null
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            runCatching { resolver.update(itemUri, done, null, null) }
            val actualName = runCatching {
                resolver.query(
                    itemUri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: FILE_NAME
            if (!actualName.equals(FILE_NAME, ignoreCase = true)) {
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val directFile = File(pubDir, FILE_NAME)
                if (atomicOverwriteFile(directFile, bytes)) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    purgeSiblingBackups(pubDir, directFile)
                    runCatching {
                        MediaScannerConnection.scanFile(context, arrayOf(directFile.absolutePath), arrayOf("application/zip"), null)
                    }
                    return@runCatching "${Environment.DIRECTORY_DOWNLOADS}/$FILE_NAME"
                }
            }
            "${Environment.DIRECTORY_DOWNLOADS}/$actualName"
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun readAllFromMediaStoreDownloads(context: Context): List<Pair<Long, ByteArray>> {
        return runCatching {
            val resolver = context.contentResolver
            buildList {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("XtraTV-backup%"),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                        val modifiedMs = cursor.getLong(dateCol) * 1000L
                        val data = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                        if (data != null && parseAndValidateZip(data) != null) {
                            add(modifiedMs to data)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Candidate directories on the TV where backup files can be stored or discovered without a system file picker. */
    private fun candidateDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { dirs += it }
        }
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dirs += it }
        }
        dirs += context.filesDir
        return dirs.distinctBy { it.absolutePath }
    }

    private fun purgeSiblingBackups(dir: File, keepFile: File) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile &&
                    f.absolutePath != keepFile.absolutePath &&
                    f.name.startsWith("XtraTV-backup", ignoreCase = true) &&
                    (f.name.endsWith(".zip", ignoreCase = true) || f.name.endsWith(".tmp", ignoreCase = true))
                ) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    /** Saves the backup archive to Download/XtraTV-backup.zip (checking/requesting file access first so existing files are overwritten). */
    fun saveToTv(context: Context, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope, skipPermissionPrompt: Boolean = false) {
        val appContext = context.applicationContext
        if (!skipPermissionPrompt && !hasFullFileAccess(appContext)) {
            ensureFileAccessOrPrompt(
                context = context,
                onGranted = { performSaveToTv(appContext) },
                onFallback = { performSaveToTv(appContext) },
            )
            return
        }
        performSaveToTv(appContext)
    }

    private fun performSaveToTv(appContext: Context) {
        if (!operationBusy.compareAndSet(false, true)) return
        backupScope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val bytes = runCatching { buildBackupZip(appContext) }.getOrNull()
                        ?.takeIf { parseAndValidateZip(it) != null }
                        ?: return@withContext null

                    // Keep a fast local mirror inside app-specific external files as safety net
                    runCatching {
                        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { extDir ->
                            val extPrimary = File(extDir, FILE_NAME)
                            if (atomicOverwriteFile(extPrimary, bytes)) {
                                purgeSiblingBackups(extDir, extPrimary)
                            }
                        }
                    }

                    // 1. Direct atomic in-place overwrite of Download/XtraTV-backup.zip (works across Android 7..14+ when permission is granted)
                    val pubDownloadDir = runCatching {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    }.getOrNull()
                    if (pubDownloadDir != null) {
                        val directTarget = File(pubDownloadDir, FILE_NAME)
                        if (atomicOverwriteFile(directTarget, bytes)) {
                            purgeSiblingBackups(pubDownloadDir, directTarget)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                cleanupDuplicateMediaStoreRows(appContext, keepExactFileName = true)
                            }
                            runCatching {
                                MediaScannerConnection.scanFile(
                                    appContext,
                                    arrayOf(directTarget.absolutePath),
                                    arrayOf("application/zip"),
                                    null,
                                )
                            }
                            return@withContext "${Environment.DIRECTORY_DOWNLOADS}/$FILE_NAME"
                        }
                    }

                    // 2. Fallback when user chose "Continue without permission" on restricted firmware
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mediaStorePath = saveToMediaStoreDownloads(appContext, bytes)
                        if (mediaStorePath != null) return@withContext mediaStorePath
                    }
                    for (dir in candidateDirs(appContext)) {
                        val primary = File(dir, FILE_NAME)
                        if (atomicOverwriteFile(primary, bytes)) {
                            purgeSiblingBackups(dir, primary)
                            return@withContext primary.absolutePath
                        }
                    }
                    null
                }
                if (savedPath != null) {
                    TvToast.show(
                        appContext,
                        appContext.getString(R.string.tv_backup_saved, savedPath),
                        Toast.LENGTH_LONG,
                        R.drawable.tv_ic_check,
                    )
                } else {
                    TvToast.show(
                        appContext,
                        R.string.tv_backup_save_failed,
                        Toast.LENGTH_LONG,
                        R.drawable.tv_ic_info,
                    )
                }
            } finally {
                operationBusy.set(false)
            }
        }
    }

    /** Restores from the newest valid backup file found on the TV (requesting file access first if needed), or invokes [onNotFound]. */
    fun restoreFromTv(
        context: Context,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        skipPermissionPrompt: Boolean = false,
        onNotFound: () -> Unit,
    ) {
        val appContext = context.applicationContext
        if (!skipPermissionPrompt && !hasFullFileAccess(appContext)) {
            ensureFileAccessOrPrompt(
                context = context,
                onGranted = { performRestoreFromTv(appContext, onNotFound) },
                onFallback = { performRestoreFromTv(appContext, onNotFound) },
            )
            return
        }
        performRestoreFromTv(appContext, onNotFound)
    }

    private fun performRestoreFromTv(appContext: Context, onNotFound: () -> Unit) {
        if (!operationBusy.compareAndSet(false, true)) return
        backupScope.launch {
            var releasingLock = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    val candidates = mutableListOf<Pair<Long, ByteArray>>()
                    val pubDownloadDir = runCatching {
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    }.getOrNull()

                    // Prioritize public Download/XtraTV-backup.zip when accessible
                    if (pubDownloadDir != null) {
                        val exact = File(pubDownloadDir, FILE_NAME)
                        val exactBytes = runCatching {
                            if (exact.exists() && exact.length() > 4L) exact.readBytes() else null
                        }.getOrNull()
                        if (exactBytes != null && parseAndValidateZip(exactBytes) != null) {
                            candidates += exact.lastModified() to exactBytes
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        candidates += readAllFromMediaStoreDownloads(appContext)
                    }

                    for (dir in candidateDirs(appContext)) {
                        val zipFiles = buildList {
                            add(File(dir, FILE_NAME))
                            runCatching {
                                dir.listFiles()?.filter {
                                    it.isFile && it.name.startsWith("XtraTV-backup", ignoreCase = true) && it.name.endsWith(".zip", ignoreCase = true)
                                }?.let { addAll(it) }
                            }
                        }.distinctBy { it.absolutePath }
                        for (zf in zipFiles) {
                            val data = runCatching {
                                if (zf.exists() && zf.length() > 4L) zf.readBytes() else null
                            }.getOrNull()
                            if (data != null && parseAndValidateZip(data) != null) {
                                candidates += zf.lastModified() to data
                            }
                        }
                    }

                    for ((_, zipData) in candidates.sortedByDescending { it.first }) {
                        if (applyBackupBytes(appContext, zipData)) {
                            return@withContext true
                        }
                    }

                    // Fallback: raw preferences XML and/or SQLite database placed next to each other in a candidate folder
                    for (dir in candidateDirs(appContext)) {
                        val prefsCandidates = buildList {
                            add(File(dir, "${appContext.packageName}_preferences.xml"))
                            add(File(dir, PREFS_ENTRY))
                            runCatching {
                                dir.listFiles()?.filter { it.isFile && it.name.endsWith("_preferences.xml") && it.name != "prefs2.xml" }?.let { addAll(it) }
                            }
                        }.distinctBy { it.absolutePath }
                        val prefsBytes = prefsCandidates.firstNotNullOfOrNull { f ->
                            runCatching { if (f.exists() && f.length() > 0L) f.readBytes() else null }.getOrNull()
                                ?.takeIf { isSharedPrefsXmlBytes(it) && parseSharedPrefsXml(it) != null }
                        }
                        val dbFile = File(dir, DB_ENTRY)
                        val dbBytes = runCatching {
                            if (isValidSqliteFile(dbFile)) dbFile.readBytes() else null
                        }.getOrNull()
                        if (prefsBytes != null || dbBytes != null) {
                            if (applyRestoredParts(appContext, mainPrefsXml = prefsBytes, dbBytes = dbBytes)) {
                                return@withContext true
                            }
                        }
                    }
                    false
                }
                if (restored) {
                    // Keep lock held until cold restart exits the process
                    releasingLock = false
                } else {
                    onNotFound()
                }
            } finally {
                if (releasingLock) {
                    operationBusy.set(false)
                }
            }
        }
    }

    /** Shows a QR code that serves the backup file over local Wi-Fi to a phone. */
    fun showQrExport(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            val ip = withContext(Dispatchers.IO) { findLocalIpAddress() }
            if (ip == null) {
                TvToast.makeText(appContext, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                return@launch
            }
            val zipBytes = withContext(Dispatchers.IO) { runCatching { buildBackupZip(appContext) }.getOrNull() } ?: return@launch
            val server = withContext(Dispatchers.IO) { runCatching { ServerSocket(0) }.getOrNull() } ?: return@launch
            val url = "http://$ip:${server.localPort}/"
            val qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(url, 520) }
            val dialog = TvDialogs.show(context) { dismiss ->
                SidePanel(title = context.getString(R.string.tv_backup_qr_send)) {
                    val closeRequester = remember { FocusRequester() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            context.getString(R.string.tv_backup_qr_send_help),
                            fontSize = 15.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(url, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                        Spacer(Modifier.height(18.dp))
                        ButtonRow(
                            label = context.getString(R.string.close_player),
                            modifier = Modifier.focusRequester(closeRequester),
                            onClick = dismiss,
                        )
                    }
                    LaunchedEffect(Unit) {
                        runCatching { closeRequester.requestFocus() }
                    }
                }
            }
            val serverJob = scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handleExportClient(client, zipBytes, appContext) }
                }
            }
            dialog.setOnDismissListener {
                serverJob.cancel()
                runCatching { server.close() }
            }
        }
    }

    /** Shows a QR code that lets a phone upload a backup file over local Wi-Fi to restore on the TV. */
    fun showQrImport(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            val ip = withContext(Dispatchers.IO) { findLocalIpAddress() }
            if (ip == null) {
                TvToast.makeText(appContext, R.string.tv_backup_no_network, Toast.LENGTH_LONG).show()
                return@launch
            }
            val server = withContext(Dispatchers.IO) { runCatching { ServerSocket(0) }.getOrNull() } ?: return@launch
            val url = "http://$ip:${server.localPort}/"
            val qrBitmap = withContext(Dispatchers.Default) { QrCode.encode(url, 520) }
            var dialogRef: androidx.activity.ComponentDialog? = null
            val dialog = TvDialogs.show(context) { dismiss ->
                SidePanel(title = context.getString(R.string.tv_backup_qr_receive)) {
                    val closeRequester = remember { FocusRequester() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            context.getString(R.string.tv_backup_qr_receive_help),
                            fontSize = 15.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(url, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                        Spacer(Modifier.height(18.dp))
                        ButtonRow(
                            label = context.getString(R.string.close_player),
                            modifier = Modifier.focusRequester(closeRequester),
                            onClick = dismiss,
                        )
                    }
                    LaunchedEffect(Unit) {
                        runCatching { closeRequester.requestFocus() }
                    }
                }
            }
            dialogRef = dialog
            val serverJob = scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        val uploaded = handleImportClient(client, appContext)
                        if (uploaded != null) {
                            withContext(Dispatchers.Main) {
                                runCatching { dialogRef?.dismiss() }
                            }
                            if (!applyBackupBytes(appContext, uploaded)) {
                                withContext(Dispatchers.Main) {
                                    TvToast.makeText(appContext, R.string.tv_backup_not_found, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
            dialog.setOnDismissListener {
                serverJob.cancel()
                runCatching { server.close() }
            }
        }
    }

    private fun handleExportClient(socket: Socket, zipBytes: ByteArray, context: Context) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val out = s.getOutputStream()
            if (requestLine.startsWith("GET /backup")) {
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/zip\r\n" +
                        "Content-Disposition: attachment; filename=\"$FILE_NAME\"\r\n" +
                        "Content-Length: ${zipBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(zipBytes)
            } else {
                val title = context.getString(R.string.tv_backup_qr_send)
                val body = """
                    <!DOCTYPE html><html><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Xtra TV</title>
                    <style>body{background:#121216;color:#fff;font-family:sans-serif;text-align:center;padding:40px 20px}
                    a.btn{display:inline-block;margin-top:24px;padding:14px 28px;background:#9147ff;color:#fff;text-decoration:none;border-radius:10px;font-weight:bold;font-size:17px}</style>
                    </head><body><h2>Xtra TV</h2><p>$title</p>
                    <a class="btn" href="/backup">$FILE_NAME</a>
                    <script>setTimeout(function(){location.href='/backup'},300);</script>
                    </body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(body)
            }
            out.flush()
        }
    }

    private fun handleImportClient(socket: Socket, context: Context): ByteArray? {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return null
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val out = s.getOutputStream()
            if (requestLine.startsWith("POST /upload") && contentLength in 1..(20 * 1024 * 1024)) {
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                val doneText = context.getString(R.string.tv_backup_restored).toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${doneText.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(doneText)
                out.flush()
                return body.takeIf { read == contentLength }
            } else {
                val title = context.getString(R.string.tv_backup_qr_receive)
                val doneMsg = context.getString(R.string.tv_backup_restored)
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", " ")
                val html = """
                    <!DOCTYPE html><html><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Xtra TV</title>
                    <style>body{background:#121216;color:#fff;font-family:sans-serif;text-align:center;padding:40px 20px}
                    input[type=file]{display:block;margin:24px auto;color:#fff;font-size:16px}
                    button{padding:14px 28px;background:#9147ff;color:#fff;border:0;border-radius:10px;font-weight:bold;font-size:17px}</style>
                    </head><body><h2>Xtra TV</h2><p>$title</p>
                    <input type="file" id="f" accept=".zip,.xml,*/*">
                    <button onclick="send()">OK</button><p id="s"></p>
                    <script>
                    function send(){
                      var f=document.getElementById('f').files[0];if(!f)return;
                      fetch('/upload',{method:'POST',body:f}).then(function(){document.getElementById('s').innerText='$doneMsg';});
                    }
                    </script></body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(html)
                out.flush()
                return null
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun findLocalIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

/** Compact ISO/IEC 18004 QR code generator (Byte mode, Versions 3..6, ECC Level L — up to 134 bytes). */
object QrCode {

    fun encode(text: String, sizePx: Int): Bitmap {
        val raw = text.toByteArray(Charsets.ISO_8859_1)
        val trimmed = if (raw.size > 134 && text.contains('?')) {
            text.substringBefore('?').toByteArray(Charsets.ISO_8859_1)
        } else raw
        val data = if (trimmed.size > 134) trimmed.copyOf(134) else trimmed

        // Select smallest QR Version (3..6, ECC Level L) that fits [data] + 4-bit mode + 8-bit length
        val version: Int
        val numBlocks: Int
        val blockDataLen: Int
        val blockEcLen: Int
        when {
            data.size <= 53 -> { version = 3; numBlocks = 1; blockDataLen = 55; blockEcLen = 15 }
            data.size <= 78 -> { version = 4; numBlocks = 1; blockDataLen = 80; blockEcLen = 20 }
            data.size <= 106 -> { version = 5; numBlocks = 1; blockDataLen = 108; blockEcLen = 26 }
            else -> { version = 6; numBlocks = 2; blockDataLen = 68; blockEcLen = 18 }
        }
        val dim = 17 + 4 * version
        val totalDataCodewords = numBlocks * blockDataLen
        val totalEcCodewords = numBlocks * blockEcLen

        val bits = ArrayList<Int>(totalDataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        // Byte mode (0100) + 8-bit length (valid for versions 1..9)
        pushBits(0b0100, 4)
        pushBits(data.size, 8)
        for (b in data) pushBits(b.toInt() and 0xFF, 8)
        repeat(minOf(4, totalDataCodewords * 8 - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < totalDataCodewords * 8) {
            pushBits(padBytes[padIdx % 2], 8)
            padIdx++
        }

        val allData = IntArray(totalDataCodewords)
        for (i in 0 until totalDataCodewords) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[i * 8 + b]
            allData[i] = v
        }
        val codewords = IntArray(totalDataCodewords + totalEcCodewords)
        if (numBlocks == 1) {
            System.arraycopy(allData, 0, codewords, 0, totalDataCodewords)
            val ec = reedSolomon(allData, blockEcLen)
            System.arraycopy(ec, 0, codewords, totalDataCodewords, blockEcLen)
        } else {
            val dataBlocks = Array(numBlocks) { b ->
                allData.copyOfRange(b * blockDataLen, (b + 1) * blockDataLen)
            }
            val ecBlocks = Array(numBlocks) { b -> reedSolomon(dataBlocks[b], blockEcLen) }
            var idx = 0
            for (i in 0 until blockDataLen) {
                for (b in 0 until numBlocks) codewords[idx++] = dataBlocks[b][i]
            }
            for (i in 0 until blockEcLen) {
                for (b in 0 until numBlocks) codewords[idx++] = ecBlocks[b][i]
            }
        }

        val modules = Array(dim) { IntArray(dim) }
        val isFunction = Array(dim) { BooleanArray(dim) }

        fun setFunction(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until dim && c in 0 until dim) {
                modules[r][c] = if (dark) 1 else 0
                isFunction[r][c] = true
            }
        }

        fun drawFinder(row: Int, col: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = row + dr
                    val c = col + dc
                    val dark = (dr in 0..6 && (dc == 0 || dc == 6)) ||
                            (dc in 0..6 && (dr == 0 || dr == 6)) ||
                            (dr in 2..4 && dc in 2..4)
                    setFunction(r, c, dark)
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, dim - 7)
        drawFinder(dim - 7, 0)

        // Timing patterns
        for (i in 8 until dim - 8) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }

        // Alignment pattern for Versions 2..6 at (dim - 7, dim - 7)
        val alignCenter = dim - 7
        for (dr in -2..2) {
            for (dc in -2..2) {
                val dark = kotlin.math.abs(dr) == 2 || kotlin.math.abs(dc) == 2 || (dr == 0 && dc == 0)
                setFunction(alignCenter + dr, alignCenter + dc, dark)
            }
        }

        // Reserve format info areas & dark module
        for (i in 0..8) {
            if (!isFunction[8][i]) setFunction(8, i, false)
            if (!isFunction[i][8]) setFunction(i, 8, false)
        }
        for (i in 0..7) {
            if (!isFunction[8][dim - 1 - i]) setFunction(8, dim - 1 - i, false)
            if (!isFunction[dim - 1 - i][8]) setFunction(dim - 1 - i, 8, false)
        }
        setFunction(dim - 8, 8, true)

        // Place data bits in zigzag
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var col = dim - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until dim) {
                val row = if (upward) dim - 1 - step else step
                for (cOffset in 0..1) {
                    val c = col - cOffset
                    if (!isFunction[row][c]) {
                        val dark = if (bitIndex < totalBits) {
                            ((codewords[bitIndex / 8] ushr (7 - (bitIndex % 8))) and 1) == 1
                        } else false
                        // Mask 0: (row + c) % 2 == 0
                        val masked = if ((row + c) % 2 == 0) !dark else dark
                        modules[row][c] = if (masked) 1 else 0
                        bitIndex++
                    }
                }
            }
            upward = !upward
            col -= 2
        }

        // Format information for ECC Level L (01) and Mask 0 (000) -> 0x77C4
        val formatBits = 0x77C4
        for (i in 0..5) setFunction(8, i, ((formatBits ushr (14 - i)) and 1) == 1)
        setFunction(8, 7, ((formatBits ushr 8) and 1) == 1)
        setFunction(8, 8, ((formatBits ushr 7) and 1) == 1)
        setFunction(7, 8, ((formatBits ushr 6) and 1) == 1)
        for (i in 0..5) setFunction(5 - i, 8, ((formatBits ushr i) and 1) == 1)
        for (i in 0..7) setFunction(8, dim - 8 + i, ((formatBits ushr (7 - i)) and 1) == 1)
        for (i in 0..6) setFunction(dim - 1 - i, 8, ((formatBits ushr (14 - i)) and 1) == 1)

        val quiet = 2
        val totalModules = dim + quiet * 2
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            val mr = (y * totalModules) / sizePx - quiet
            for (x in 0 until sizePx) {
                val mc = (x * totalModules) / sizePx - quiet
                val dark = mr in 0 until dim && mc in 0 until dim && modules[mr][mc] == 1
                bmp.setPixel(x, y, if (dark) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun reedSolomon(data: IntArray, ecLen: Int): IntArray {
        val exp = IntArray(512)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

        var gen = intArrayOf(1)
        for (i in 0 until ecLen) {
            val next = IntArray(gen.size + 1)
            val root = exp[i]
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor mul(gen[j], root)
            }
            gen = next
        }
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) {
                rem[i] = rem[i] xor mul(gen[i + 1], factor)
            }
        }
        return rem
    }
}
