package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV QR code sharing ("Open on phone" at exact VOD/DVR timestamp) and instant 30s Twitch Clip creation + QR modal.
 */
object TvQrShare {

    fun formatVodTimestamp(posMs: Long): String? {
        if (posMs < 5_000L) return null
        val totalSeconds = posMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.US, "%02dh%02dm%02ds", hours, minutes, seconds)
    }

    fun buildVideoUrl(videoId: String, posMs: Long?): String {
        val ts = posMs?.let { formatVodTimestamp(it) }
        return if (ts != null) {
            "https://www.twitch.tv/videos/$videoId?t=$ts"
        } else {
            "https://www.twitch.tv/videos/$videoId"
        }
    }

    fun showQrModal(
        context: Context,
        title: String,
        subtitle: String?,
        url: String,
    ) {
        val qrBitmap = rememberBitmapSafe(url)
        TvDialogs.show(context) { dismiss ->
            SidePanel(title = title, subtitle = subtitle, scrollable = true) {
                val closeRequester = remember { FocusRequester() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .size(184.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ComposeColor.White)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = url,
                            modifier = Modifier.size(164.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = url,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ComposeColor.White,
                        textAlign = TextAlign.Center,
                    )
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

    fun createAndShowClipQr(
        context: Context,
        broadcasterId: String?,
        channelLogin: String?,
        broadcastId: String?,
        videoId: String?,
        offsetSeconds: Double?,
    ) {
        val gqlToken = TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN]
        val helixToken = TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN]
        if (gqlToken.isNullOrBlank() && helixToken.isNullOrBlank()) {
            TvToast.makeText(context, R.string.tv_clip_login_required, Toast.LENGTH_SHORT).show()
            return
        }

        val app = context.applicationContext as XtraApp
        val repo = app.xtraModule.graphQLRepository
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

        TvDialogs.show(context) { dismiss ->
            var clipUrl by remember { mutableStateOf<String?>(null) }
            val closeRequester = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                var resolvedBroadcasterId = broadcasterId?.takeIf { it.isNotBlank() }
                if (resolvedBroadcasterId == null && !channelLogin.isNullOrBlank()) {
                    resolvedBroadcasterId = runCatching {
                        repo.loadQueryUser(networkLibrary, headers, null, channelLogin).data?.user?.id
                    }.getOrNull()
                }
                val bid = resolvedBroadcasterId?.takeIf { it.isNotBlank() }
                if (bid == null) {
                    TvToast.makeText(
                        context,
                        context.getString(R.string.tv_clip_failed, "BROADCASTER_NOT_FOUND"),
                        Toast.LENGTH_LONG,
                    ).show()
                    dismiss()
                    return@LaunchedEffect
                }
                val result = repo.createClip(
                    networkLibrary = networkLibrary,
                    headers = headers,
                    broadcasterId = bid,
                    broadcastId = broadcastId,
                    videoId = videoId,
                    offsetSeconds = offsetSeconds,
                )
                val finalUrl = result.url?.takeIf { it.isNotBlank() }
                    ?: result.slug?.takeIf { it.isNotBlank() }?.let { "https://clips.twitch.tv/$it" }
                if (finalUrl != null) {
                    clipUrl = finalUrl
                } else {
                    TvToast.makeText(
                        context,
                        context.getString(R.string.tv_clip_failed, result.error ?: "ERROR"),
                        Toast.LENGTH_LONG,
                    ).show()
                    dismiss()
                }
            }

            val readyUrl = clipUrl
            SidePanel(
                title = context.getString(if (readyUrl != null) R.string.tv_clip_ready_title else R.string.tv_create_clip),
                subtitle = if (readyUrl != null) context.getString(R.string.tv_clip_ready_subtitle) else context.getString(R.string.tv_clip_creating),
                scrollable = true,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (readyUrl != null) {
                        val qrBitmap = remember(readyUrl) { rememberBitmapSafe(readyUrl) }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .size(184.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ComposeColor.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = readyUrl,
                                modifier = Modifier.size(164.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = readyUrl,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ComposeColor.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                    } else {
                        Spacer(Modifier.height(24.dp))
                    }
                    ButtonRow(
                        label = context.getString(R.string.close_player),
                        modifier = Modifier.focusRequester(closeRequester),
                        onClick = dismiss,
                    )
                }
                LaunchedEffect(readyUrl) {
                    runCatching { closeRequester.requestFocus() }
                }
            }
        }
    }

    private fun rememberBitmapSafe(url: String): android.graphics.Bitmap {
        return QrCode.encode(url, 520)
    }
}
