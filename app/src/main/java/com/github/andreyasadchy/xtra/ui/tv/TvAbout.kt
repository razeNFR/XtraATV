package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R

/**
 * TV "About" side panel showing app version, fork credits, device info, and a QR code to the GitHub repository.
 */
object TvAbout {

    const val CREDIT_TEXT = "XtraTV forked from https://github.com/crackededed/Xtra by razeN with AI"
    const val GITHUB_URL = "https://github.com/razeNFR/XtraATV"

    fun deviceSummary(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return "$manufacturer ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            if (abi.isNotEmpty()) " · $abi" else ""
    }

    fun show(context: Context) {
        val qrBitmap = QrCode.encode(GITHUB_URL, 520)
        val versionTitle = "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val deviceInfo = deviceSummary()
        TvDialogs.show(context) { dismiss ->
            SidePanel(title = context.getString(R.string.tv_settings_section_about)) {
                val closeRequester = remember { FocusRequester() }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        versionTitle,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ComposeColor.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        CREDIT_TEXT,
                        fontSize = 14.sp,
                        color = TvColors.secondaryText,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        deviceInfo,
                        fontSize = 13.sp,
                        color = TvColors.secondaryText,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ComposeColor.White)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = GITHUB_URL,
                            modifier = Modifier.size(190.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(GITHUB_URL, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                    Spacer(Modifier.height(16.dp))
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
    }
}
