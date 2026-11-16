package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Process
import android.os.UserManager
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import java.io.File

/**
 * Installation of the updates downloaded in the app.
 *
 * On some Android TV / Google TV firmwares, Android keeps sending the user to "Install unknown apps" although it is
 * allowed for the app, and the update never installs (a loop). So:
 * - the settings "Install unknown apps" open at most once an hour, saved in the preferences (not in memory: the TV can
 *   close the app while its settings are open, and it starts again from nothing);
 * - the first try installs with a session of the installer of Android, the second one opens the APK with the installer
 *   of Android (the other way Android has to install an app), started for a result so Android knows the app asked;
 * - after that, a dialog: download in the browser, open the settings, or start again, with the details of what Android
 *   answered (a photo of it says why it failed).
 *
 * The tries count for the installed version only and for a day: a new version installed starts again from the first way.
 */
object UpdateInstallHelper {

    @Volatile var lastAttemptedUpdateUrl: String? = null

    /** The last answer of the installer of Android, shown in the details of the dialog when the installation is blocked. */
    @Volatile var lastInstallerStatus: String? = null

    /** Code of the installer of Android opened with the APK ([openApkWithInstaller]), its result says when it failed. */
    const val REQUEST_INSTALL_APK = 5401
    /** Code of the confirmation of the installer of Android for a session, started for a result so Android knows the app asked. */
    const val REQUEST_CONFIRM_INSTALL = 5402

    const val METHOD_SESSION = 0
    const val METHOD_INTENT = 1
    const val METHOD_BLOCKED = 2

    private const val PROMPTED_AT = "update_unknown_sources_prompted_at"
    private const val PENDING_URL = "update_pending_url"
    private const val PENDING_AT = "update_pending_at"
    private const val ATTEMPTS = "update_install_attempts"
    private const val ATTEMPTS_AT = "update_install_attempts_at"
    private const val ATTEMPTS_VERSION = "update_install_attempts_version"

    private const val PROMPT_WINDOW_MS = 60 * 60_000L
    private const val PENDING_WINDOW_MS = 10 * 60_000L
    private const val ATTEMPTS_WINDOW_MS = 24 * 60 * 60_000L

    private const val APK_DIR = "update"
    private const val APK_NAME = "XtraTV-update.apk"

    /**
     * "Update" pressed: the dialog when both ways already failed, else the settings "Install unknown apps" when Android
     * says the app may not install (once an hour, the download starts on return with [takePendingUpdate]), else [download].
     */
    fun startUpdate(activity: Activity, url: String, download: (String) -> Unit) {
        lastAttemptedUpdateUrl = url
        when {
            installMethod(activity) == METHOD_BLOCKED -> activity.showInstallBlockedDialog(url) {
                resetAttempts(activity)
                download(url)
            }
            promptUnknownSources(activity, url) -> Unit
            else -> download(url)
        }
    }

    /** True when the settings "Install unknown apps" were opened, false when they are not needed or were already opened in the last hour. */
    private fun promptUnknownSources(activity: Activity, url: String): Boolean {
        if (activity.canInstallUnknownAppsCompat()) return false
        val prefs = activity.tokenPrefs()
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PROMPTED_AT, 0L) in 0..PROMPT_WINDOW_MS) return false
        prefs.edit {
            putLong(PROMPTED_AT, now)
            putString(PENDING_URL, url)
            putLong(PENDING_AT, now)
        }
        if (!activity.openUnknownAppSourcesSettings()) {
            prefs.edit {
                remove(PENDING_URL)
                remove(PENDING_AT)
            }
            return false
        }
        return true
    }

    /**
     * Back from the settings "Install unknown apps" (also when the app was closed meanwhile): the update to download now,
     * once. It downloads even when Android still says no: some firmwares answer no although it is allowed, and the
     * settings will not open again for an hour.
     */
    fun takePendingUpdate(context: Context): String? {
        val prefs = context.tokenPrefs()
        val url = prefs.getString(PENDING_URL, null) ?: return null
        val at = prefs.getLong(PENDING_AT, 0L)
        prefs.edit {
            remove(PENDING_URL)
            remove(PENDING_AT)
        }
        return url.takeIf { System.currentTimeMillis() - at in 0..PENDING_WINDOW_MS }
    }

    private fun attempts(context: Context): Int {
        val prefs = context.tokenPrefs()
        val sameVersion = prefs.getInt(ATTEMPTS_VERSION, -1) == BuildConfig.VERSION_CODE
        val recent = System.currentTimeMillis() - prefs.getLong(ATTEMPTS_AT, 0L) in 0..ATTEMPTS_WINDOW_MS
        return if (sameVersion && recent) prefs.getInt(ATTEMPTS, 0) else 0
    }

    /** The way of the next installation: [METHOD_SESSION] first, [METHOD_INTENT] after one try, [METHOD_BLOCKED] after both. */
    fun installMethod(context: Context): Int {
        return when (attempts(context)) {
            0 -> METHOD_SESSION
            1 -> METHOD_INTENT
            else -> METHOD_BLOCKED
        }
    }

    private fun recordAttempt(context: Context) {
        val count = attempts(context) + 1
        context.tokenPrefs().edit {
            putInt(ATTEMPTS, count)
            putLong(ATTEMPTS_AT, System.currentTimeMillis())
            putInt(ATTEMPTS_VERSION, BuildConfig.VERSION_CODE)
        }
    }

    fun resetAttempts(context: Context) {
        context.tokenPrefs().edit {
            remove(ATTEMPTS)
            remove(ATTEMPTS_AT)
            remove(ATTEMPTS_VERSION)
        }
    }

    /**
     * Installs the downloaded [apk] the way of [installMethod]: returns the file to open with the installer of Android
     * ([openApkWithInstaller]), or null when it went to a session (its answer comes to [MainActivity]).
     * A [SecurityException] means Android blocked the installation.
     */
    fun install(context: Context, apk: ByteArray, url: String): File? {
        val method = installMethod(context)
        recordAttempt(context)
        if (method == METHOD_INTENT) {
            val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            return File(dir, APK_NAME).apply { writeBytes(apk) }
        }
        val packageInstaller = context.packageManager.packageInstaller
        val sessionId = packageInstaller.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("package", 0, apk.size.toLong()).use {
                it.write(apk)
            }
            session.commit(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply {
                        setAction(MainActivity.INTENT_INSTALL_UPDATE)
                        putExtra("update_url", url)
                    },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ).intentSender
            )
        }
        return null
    }

    /** The result of the installer of Android: the dialog when it says the installation failed. True when the result was an installation one. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        return when (requestCode) {
            REQUEST_INSTALL_APK -> {
                lastInstallerStatus = "apk result=$resultCode"
                if (resultCode == Activity.RESULT_FIRST_USER) {
                    activity.showInstallBlockedDialog(lastAttemptedUpdateUrl)
                }
                true
            }
            REQUEST_CONFIRM_INSTALL -> true
            else -> false
        }
    }

    /** Opens the [apk] with the installer of Android (the second way), started for a result so Android knows the app asked. */
    fun openApkWithInstaller(activity: Activity, apk: File) {
        val uri = try {
            FileProvider.getUriForFile(activity, activity.packageName + ".updates", apk)
        } catch (e: Exception) {
            activity.showInstallBlockedDialog(lastAttemptedUpdateUrl)
            return
        }
        val mimeType = "application/vnd.android.package-archive"
        val intents = listOf(
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, mimeType)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                @Suppress("DEPRECATION")
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
            },
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_INSTALL_APK)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        activity.showInstallBlockedDialog(lastAttemptedUpdateUrl)
    }

    /** What Android answers about the installation, shown in the dialog when it is blocked (a photo of it says why). */
    fun diagnostics(context: Context): String {
        val canRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrNull()?.toString() ?: "?"
        } else "n/a"
        val appOp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val op = AppOpsManager.permissionToOp(android.Manifest.permission.REQUEST_INSTALL_PACKAGES)
                    ?: "android:request_install_packages"
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
                }
                when (mode) {
                    AppOpsManager.MODE_ALLOWED -> "allowed"
                    AppOpsManager.MODE_IGNORED -> "ignored"
                    AppOpsManager.MODE_ERRORED -> "errored"
                    AppOpsManager.MODE_DEFAULT -> "default"
                    else -> mode.toString()
                }
            }.getOrNull() ?: "?"
        } else "n/a"
        val restricted = runCatching {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
        }.getOrNull()?.toString() ?: "?"
        return buildString {
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("canRequestPackageInstalls=$canRequest · appOp=$appOp · restricted=$restricted\n")
            append("tries=${attempts(context)}")
            lastInstallerStatus?.let { append(" · $it") }
        }
    }
}
