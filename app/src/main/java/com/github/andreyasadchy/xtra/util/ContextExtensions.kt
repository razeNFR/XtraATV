package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

fun Context.prefs(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

fun Context.tokenPrefs(): SharedPreferences = getSharedPreferences("prefs2", Context.MODE_PRIVATE)

fun Activity.applyTheme() {
    // On Android 15, wrong language is used when multiple languages are set in device settings
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val lang = AppCompatDelegate.getApplicationLocales()
        resources.configuration.setLocale(
            if (!lang.isEmpty) {
                Locale.forLanguageTag(lang.toLanguageTags())
            } else {
                Locale.getDefault()
            }
        )
    }
    val theme = if (prefs().getBoolean(C.UI_THEME_FOLLOW_SYSTEM, false)) {
        when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> prefs().getString(C.UI_THEME_DARK_ON, "0") ?: "0"
            else -> prefs().getString(C.UI_THEME_DARK_OFF, "2") ?: "2"
        }
    } else {
        prefs().getString(C.THEME, "0") ?: "0"
    }
    if (prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
        val reducedPadding = prefs().getBoolean(C.UI_THEME_REDUCED_PADDING, false)
        val compactText = prefs().getBoolean(C.UI_THEME_COMPACT_TEXT, false)
        when (prefs().getString(C.UI_THEME_ROUNDED_CORNERS, "0")) {
            "1" -> {
                when {
                    reducedPadding && compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCornersReducedPaddingCompactText
                                "6" -> R.style.AmoledThemeSmallCornersReducedPaddingCompactText
                                "5" -> R.style.LightThemeSmallCornersReducedPaddingCompactText
                                "1" -> R.style.AmoledThemeSmallCornersReducedPaddingCompactText
                                "2" -> R.style.LightThemeSmallCornersReducedPaddingCompactText
                                "3" -> R.style.BlueThemeSmallCornersReducedPaddingCompactText
                                else -> R.style.DarkThemeSmallCornersReducedPaddingCompactText
                            }
                        )
                    }
                    reducedPadding -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCornersReducedPadding
                                "6" -> R.style.AmoledThemeSmallCornersReducedPadding
                                "5" -> R.style.LightThemeSmallCornersReducedPadding
                                "1" -> R.style.AmoledThemeSmallCornersReducedPadding
                                "2" -> R.style.LightThemeSmallCornersReducedPadding
                                "3" -> R.style.BlueThemeSmallCornersReducedPadding
                                else -> R.style.DarkThemeSmallCornersReducedPadding
                            }
                        )
                    }
                    compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCornersCompactText
                                "6" -> R.style.AmoledThemeSmallCornersCompactText
                                "5" -> R.style.LightThemeSmallCornersCompactText
                                "1" -> R.style.AmoledThemeSmallCornersCompactText
                                "2" -> R.style.LightThemeSmallCornersCompactText
                                "3" -> R.style.BlueThemeSmallCornersCompactText
                                else -> R.style.DarkThemeSmallCornersCompactText
                            }
                        )
                    }
                    else -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCorners
                                "6" -> R.style.AmoledThemeSmallCorners
                                "5" -> R.style.LightThemeSmallCorners
                                "1" -> R.style.AmoledThemeSmallCorners
                                "2" -> R.style.LightThemeSmallCorners
                                "3" -> R.style.BlueThemeSmallCorners
                                else -> R.style.DarkThemeSmallCorners
                            }
                        )
                    }
                }
            }
            "2" -> {
                when {
                    reducedPadding && compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCornersReducedPaddingCompactText
                                "6" -> R.style.AmoledThemeNoCornersReducedPaddingCompactText
                                "5" -> R.style.LightThemeNoCornersReducedPaddingCompactText
                                "1" -> R.style.AmoledThemeNoCornersReducedPaddingCompactText
                                "2" -> R.style.LightThemeNoCornersReducedPaddingCompactText
                                "3" -> R.style.BlueThemeNoCornersReducedPaddingCompactText
                                else -> R.style.DarkThemeNoCornersReducedPaddingCompactText
                            }
                        )
                    }
                    reducedPadding -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCornersReducedPadding
                                "6" -> R.style.AmoledThemeNoCornersReducedPadding
                                "5" -> R.style.LightThemeNoCornersReducedPadding
                                "1" -> R.style.AmoledThemeNoCornersReducedPadding
                                "2" -> R.style.LightThemeNoCornersReducedPadding
                                "3" -> R.style.BlueThemeNoCornersReducedPadding
                                else -> R.style.DarkThemeNoCornersReducedPadding
                            }
                        )
                    }
                    compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCornersCompactText
                                "6" -> R.style.AmoledThemeNoCornersCompactText
                                "5" -> R.style.LightThemeNoCornersCompactText
                                "1" -> R.style.AmoledThemeNoCornersCompactText
                                "2" -> R.style.LightThemeNoCornersCompactText
                                "3" -> R.style.BlueThemeNoCornersCompactText
                                else -> R.style.DarkThemeNoCornersCompactText
                            }
                        )
                    }
                    else -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCorners
                                "6" -> R.style.AmoledThemeNoCorners
                                "5" -> R.style.LightThemeNoCorners
                                "1" -> R.style.AmoledThemeNoCorners
                                "2" -> R.style.LightThemeNoCorners
                                "3" -> R.style.BlueThemeNoCorners
                                else -> R.style.DarkThemeNoCorners
                            }
                        )
                    }
                }
            }
            else -> {
                when {
                    reducedPadding && compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeReducedPaddingCompactText
                                "6" -> R.style.AmoledThemeReducedPaddingCompactText
                                "5" -> R.style.LightThemeReducedPaddingCompactText
                                "1" -> R.style.AmoledThemeReducedPaddingCompactText
                                "2" -> R.style.LightThemeReducedPaddingCompactText
                                "3" -> R.style.BlueThemeReducedPaddingCompactText
                                else -> R.style.DarkThemeReducedPaddingCompactText
                            }
                        )
                    }
                    reducedPadding -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeReducedPadding
                                "6" -> R.style.AmoledThemeReducedPadding
                                "5" -> R.style.LightThemeReducedPadding
                                "1" -> R.style.AmoledThemeReducedPadding
                                "2" -> R.style.LightThemeReducedPadding
                                "3" -> R.style.BlueThemeReducedPadding
                                else -> R.style.DarkThemeReducedPadding
                            }
                        )
                    }
                    compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeCompactText
                                "6" -> R.style.AmoledThemeCompactText
                                "5" -> R.style.LightThemeCompactText
                                "1" -> R.style.AmoledThemeCompactText
                                "2" -> R.style.LightThemeCompactText
                                "3" -> R.style.BlueThemeCompactText
                                else -> R.style.DarkThemeCompactText
                            }
                        )
                    }
                    else -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkTheme
                                "6" -> R.style.AmoledTheme
                                "5" -> R.style.LightTheme
                                "1" -> R.style.AmoledTheme
                                "2" -> R.style.LightTheme
                                "3" -> R.style.BlueTheme
                                else -> R.style.DarkTheme
                            }
                        )
                    }
                }
            }
        }
        if (theme == "4" || theme == "6" || theme == "5") {
            DynamicColors.applyToActivityIfAvailable(
                this,
                DynamicColorsOptions.Builder().apply {
                    setThemeOverlay(
                        when (theme) {
                            "4" -> R.style.DarkDynamicOverlay
                            "6" -> R.style.AmoledDynamicOverlay
                            "5" -> R.style.LightDynamicOverlay
                            else -> R.style.DarkDynamicOverlay
                        }
                    )
                }.build()
            )
        }
    } else {
        setTheme(
            when (theme) {
                "4" -> R.style.AppCompatDarkTheme
                "6" -> R.style.AppCompatAmoledTheme
                "5" -> R.style.AppCompatLightTheme
                "1" -> R.style.AppCompatAmoledTheme
                "2" -> R.style.AppCompatLightTheme
                "3" -> R.style.AppCompatBlueTheme
                else -> R.style.AppCompatDarkTheme
            }
        )
    }
    val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
        it.getBoolean(0, false)
    }
    WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = isLightTheme
        isAppearanceLightNavigationBars = isLightTheme
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}

fun Context.getAlertDialogBuilder(): AlertDialog.Builder {
    if (resources.getBoolean(R.bool.isTv)) {
        // TV: the focus is on the main button ("Yes", "OK"...) as soon as the dialog opens, no key to press first
        return if (prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) TvMaterialDialogBuilder(this) else TvDialogBuilder(this)
    }
    return if (prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
        MaterialAlertDialogBuilder(this)
    } else {
        AlertDialog.Builder(this)
    }
}

/** The focus on the positive button once shown (not for a dialog with a list: its checked entry keeps the focus). */
private fun AlertDialog.focusPositiveButtonOnShow() {
    setOnShowListener {
        if (listView == null) {
            getButton(AlertDialog.BUTTON_POSITIVE)?.takeIf { it.visibility == android.view.View.VISIBLE }?.let { button ->
                button.post {
                    // a text to type (url of a proxy...): it keeps the focus
                    if (currentFocus !is android.widget.EditText) {
                        button.requestFocus()
                    }
                }
            }
        }
    }
}

private class TvMaterialDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun create(): AlertDialog = super.create().also { it.focusPositiveButtonOnShow() }
}

private class TvDialogBuilder(context: Context) : AlertDialog.Builder(context) {
    override fun create(): AlertDialog = super.create().also { it.focusPositiveButtonOnShow() }
}

/**
 * Returns true if this app is allowed to request package installs.
 * Some Android TV / Fire OS / OEM firmwares keep returning false from [android.content.pm.PackageManager.canRequestPackageInstalls]
 * even when "Install unknown apps" is enabled in system settings (`AppOpsManager.MODE_ALLOWED`).
 */
fun Context.canInstallUnknownAppsCompat(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    if (try { packageManager.canRequestPackageInstalls() } catch (_: Throwable) { false }) {
        return true
    }
    return try {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val op = AppOpsManager.permissionToOp(android.Manifest.permission.REQUEST_INSTALL_PACKAGES)
            ?: "android:request_install_packages"
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }
}

/**
 * Android blocked the installation of the update: download it in the browser, open the settings, or [onRetry] (else
 * cancel), with the details of what Android answered (a photo of them says why it failed).
 */
fun Activity.showInstallBlockedDialog(downloadUrl: String?, onRetry: (() -> Unit)? = null) {
    if (isFinishing || isDestroyed) return
    val openBrowser: (String) -> Unit = { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
            tokenPrefs().edit().putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis()).apply()
        } catch (_: ActivityNotFoundException) {
            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(this, R.string.no_browser_found, Toast.LENGTH_LONG).show()
        }
    }
    val builder = getAlertDialogBuilder()
        .setTitle(R.string.update_available)
        .setMessage(getString(R.string.tv_update_install_blocked) + "\n\n" + UpdateInstallHelper.diagnostics(this))
    if (onRetry != null) {
        builder.setNegativeButton(R.string.tv_update_try_again) { _, _ -> onRetry() }
    } else {
        builder.setNegativeButton(android.R.string.cancel, null)
    }
    if (!downloadUrl.isNullOrBlank()) {
        builder.setPositiveButton(R.string.update_using_browser) { _, _ -> openBrowser(downloadUrl) }
        builder.setNeutralButton(R.string.settings) { _, _ -> openUnknownAppSourcesSettings() }
    } else {
        builder.setPositiveButton(R.string.settings) { _, _ -> openUnknownAppSourcesSettings() }
    }
    builder.show()
}

/**
 * Opens "Install unknown apps" settings. On Android TV 14+, TvSettings dropped the `package:` URI filter on
 * [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES], so we fall back to the action without URI, then Security / Apps / main Settings.
 */
fun Context.openUnknownAppSourcesSettings(showHintToast: Boolean = true): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    try {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        return true
    } catch (_: ActivityNotFoundException) {
    }
    val fallbacks = listOf(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Settings.ACTION_SECURITY_SETTINGS,
        Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in fallbacks) {
        try {
            startActivity(Intent(action))
            if (showHintToast) {
                Toast.makeText(this, R.string.tv_enable_unknown_sources, Toast.LENGTH_LONG).show()
            }
            return true
        } catch (_: ActivityNotFoundException) {
        }
    }
    return false
}