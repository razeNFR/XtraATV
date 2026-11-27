package com.github.andreyasadchy.xtra.ui.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.Outline
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.StreamProxy
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.ui.tv.TvAbout
import com.github.andreyasadchy.xtra.ui.tv.TvBlacklist
import com.github.andreyasadchy.xtra.ui.tv.TvChangelog
import com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock
import com.github.andreyasadchy.xtra.ui.tv.TvFavorites
import com.github.andreyasadchy.xtra.ui.tv.TvStats
import com.github.andreyasadchy.xtra.ui.tv.TvToast
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Native Material 3 Compose Bottom Sheets for mobile phones & tablets.
 * Strictly mobile-only: never modifies or replaces Android TV side panels.
 */
object MobileBottomSheets {

    data class SheetAction(
        val label: String,
        val iconRes: Int,
        val subtitle: String? = null,
        val highlighted: Boolean = false,
        val destructive: Boolean = false,
        val onClick: () -> Unit,
    )

    private fun resolveColorScheme(context: Context): androidx.compose.material3.ColorScheme {
        val surfaceInt = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0xFF14131B.toInt())
        val surfaceContainerInt = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainer, surfaceInt)
        val surfaceContainerHighInt = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, surfaceContainerInt)
        val primaryInt = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF9147FF.toInt())
        val onSurfaceInt = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, AndroidColor.WHITE)
        val onSurfaceVariantInt = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFB5B3C2.toInt())
        val outlineInt = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0x33FFFFFF)

        val surfaceColor = Color(surfaceInt)
        val isDark = surfaceColor.luminance() < 0.45f ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val customAccent = MobileAccent.color(context)
        val primary = when {
            customAccent != null -> Color(customAccent)
            isDark && Color(primaryInt).luminance() > 0.85f -> Color(0xFFA970FF)
            else -> Color(primaryInt)
        }
        val onPrimary = if (primary.luminance() > 0.5f) Color(0xFF12101A) else Color.White

        return if (isDark) {
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                surface = surfaceColor,
                onSurface = Color(onSurfaceInt),
                onSurfaceVariant = Color(onSurfaceVariantInt),
                surfaceVariant = Color(surfaceContainerHighInt),
                surfaceContainer = Color(surfaceContainerInt),
                surfaceContainerHigh = Color(surfaceContainerHighInt),
                outline = Color(outlineInt),
                outlineVariant = Color(outlineInt),
                error = Color(0xFFFF6B6B),
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                surface = surfaceColor,
                onSurface = Color(onSurfaceInt),
                onSurfaceVariant = Color(onSurfaceVariantInt),
                surfaceVariant = Color(surfaceContainerHighInt),
                surfaceContainer = Color(surfaceContainerInt),
                surfaceContainerHigh = Color(surfaceContainerHighInt),
                outline = Color(outlineInt),
                outlineVariant = Color(outlineInt),
                error = Color(0xFFD93025),
            )
        }
    }

    private fun showSheet(
        context: Context,
        onDismiss: (() -> Unit)? = null,
        content: @Composable (dismiss: () -> Unit) -> Unit,
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val scheme = resolveColorScheme(context)
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(dialog)
            setViewTreeSavedStateRegistryOwner(dialog)
            setContent {
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                        ) {
                            // Drag handle
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                                )
                            }
                            content { dialog.dismiss() }
                        }
                    }
                }
            }
        }
        dialog.setContentView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(AndroidColor.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        if (onDismiss != null) {
            dialog.setOnDismissListener { onDismiss() }
        }
        dialog.show()
        return dialog
    }

    @Composable
    private fun AppLogoIcon(sizeDp: Int = 44) {
        AndroidView(
            factory = { ctx ->
                val density = ctx.resources.displayMetrics.density
                val radius = (sizeDp * 0.24f) * density
                ImageView(ctx).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }
                    clipToOutline = true
                }
            },
            modifier = Modifier.size(sizeDp.dp),
        )
    }

    @Composable
    private fun FallbackPill(text: String) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }

    // -------------------------------------------------------------------------
    // 1. Generic Long-Press Action Sheet
    // -------------------------------------------------------------------------

    fun showActionSheet(
        context: Context,
        title: String,
        subtitle: String?,
        actions: List<SheetAction>,
    ) {
        showSheet(context) { dismiss ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    actions.forEach { action ->
                        val tint = when {
                            action.destructive -> MaterialTheme.colorScheme.error
                            action.highlighted -> Color(0xFFFACC15)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        val labelColor = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dismiss()
                                    action.onClick()
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Icon(
                                painter = painterResource(action.iconRes),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = action.label,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = labelColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!action.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = action.subtitle,
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. About Sheet (official logo, direct links, copyable device info)
    // -------------------------------------------------------------------------

    fun showAbout(context: Context) {
        val versionTitle = "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val deviceInfo = TvAbout.deviceSummary()
        showSheet(context) { dismiss ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppLogoIcon(sizeDp = 64)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = versionTitle,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = TvAbout.CREDIT_TEXT,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { MobileUi.openUrl(context, TvAbout.GITHUB_URL) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_globe),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("GitHub", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { MobileUi.openUrl(context, TvAbout.DISCORD_URL) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_discord),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Discord", maxLines = 1)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            dismiss()
                            showChangelog(context)
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_list),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = context.getString(R.string.tv_about_changelog),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = context.getString(R.string.tv_about_changelog_summary),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            MobileUi.copyToClipboard(context, "XtraPlus", "$versionTitle\n$deviceInfo")
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = context.getString(R.string.tv_about_device),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = deviceInfo,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. Changelog Sheet (reusing TvChangelog cache & GitHub releases)
    // -------------------------------------------------------------------------

    fun showChangelog(context: Context) {
        showSheet(context) { dismiss ->
            var entries by remember { mutableStateOf(TvChangelog.initialEntries(context)) }
            var loading by remember { mutableStateOf(true) }
            var selectedIdx by remember { mutableIntStateOf(-1) }

            LaunchedEffect(Unit) {
                val fresh = TvChangelog.fetchFreshEntries(context)
                if (!fresh.isNullOrEmpty()) {
                    entries = fresh
                }
                loading = false
            }

            val activeEntry = entries.getOrNull(selectedIdx)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (activeEntry != null) {
                        TextButton(
                            onClick = { selectedIdx = -1 },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("← ${context.getString(R.string.tv_changelog_back)}")
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeEntry?.title ?: context.getString(R.string.tv_about_changelog),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = if (activeEntry != null) {
                            val date = TvChangelog.formatDate(context, activeEntry.publishedAtMs)
                            val badge = when {
                                activeEntry.isInstalled -> context.getString(R.string.tv_changelog_installed)
                                activeEntry.isNewer -> context.getString(R.string.tv_changelog_new)
                                else -> null
                            }
                            listOfNotNull(badge, date).joinToString(" · ")
                        } else {
                            "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
                        }
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (loading && activeEntry == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (activeEntry != null) {
                    val accent = MaterialTheme.colorScheme.primary
                    val textColor = MaterialTheme.colorScheme.onSurface
                    val formatted = remember(activeEntry.notesMarkdown, accent, textColor) {
                        TvChangelog.formatMarkdown(activeEntry.notesMarkdown, accent, textColor)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(
                            text = formatted,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = textColor,
                        )
                    }
                } else if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(if (loading) R.string.tv_changelog_loading else R.string.tv_changelog_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(entries, key = { idx, item -> "${item.key}_$idx" }) { idx, entry ->
                            val date = remember(entry.publishedAtMs) { TvChangelog.formatDate(context, entry.publishedAtMs) }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .then(
                                        if (entry.isInstalled) {
                                            Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                                RoundedCornerShape(14.dp),
                                            )
                                        } else Modifier
                                    )
                                    .clickable { selectedIdx = idx },
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = entry.title,
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (entry.isInstalled || entry.isNewer) {
                                            FallbackPill(
                                                context.getString(
                                                    if (entry.isInstalled) R.string.tv_changelog_installed else R.string.tv_changelog_new,
                                                )
                                            )
                                        }
                                        if (!date.isNullOrBlank()) {
                                            Text(
                                                text = date,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (entry.preview.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = entry.preview,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(context.getString(R.string.close_player))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. Update Available Sheet
    // -------------------------------------------------------------------------

    fun showUpdate(
        context: Context,
        version: String?,
        notes: String?,
        onUpdate: () -> Unit,
        onIgnore: () -> Unit,
    ) {
        showSheet(context) { dismiss ->
            val accent = MaterialTheme.colorScheme.primary
            val textColor = MaterialTheme.colorScheme.onSurface
            val title = if (!version.isNullOrBlank()) {
                context.getString(R.string.tv_update_title_version, version)
            } else {
                context.getString(R.string.update_available)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLogoIcon(sizeDp = 42)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )
                        Text(
                            text = context.getString(R.string.update_message),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val cleanNotes = notes?.trim().orEmpty()
                if (cleanNotes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    val formatted = remember(cleanNotes, accent, textColor) {
                        TvChangelog.formatMarkdown(cleanNotes, accent, textColor)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                    ) {
                        Text(
                            text = formatted,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = textColor,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            dismiss()
                            onIgnore()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(context.getString(R.string.tv_update_ignore))
                    }
                    Button(
                        onClick = {
                            dismiss()
                            onUpdate()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(context.getString(R.string.tv_update_now))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. Adblock & Proxies Quick Sheet (for Mobile Player)
    // -------------------------------------------------------------------------

    fun showAdblockAndProxies(
        context: Context,
        customProxies: List<CustomProxy>,
        streamProxies: List<StreamProxy>,
        openCustomSettings: () -> Unit,
        openStreamSettings: () -> Unit,
        onClosed: (changed: Boolean) -> Unit,
    ) {
        TvExperimentalAdblock.normalizePrefs(context)
        val swapBefore = TvExperimentalAdblock.isSwapAdblockEnabled(context)
        val swapPlusBefore = TvExperimentalAdblock.isSwapPlusEnabled(context)
        val expBefore = TvExperimentalAdblock.isExperimentalEnabled(context)
        val useCustomBefore = context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
        val useStreamBefore = context.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)

        showSheet(
            context = context,
            onDismiss = {
                TvExperimentalAdblock.normalizePrefs(context)
                val changed = TvExperimentalAdblock.isSwapAdblockEnabled(context) != swapBefore ||
                    TvExperimentalAdblock.isSwapPlusEnabled(context) != swapPlusBefore ||
                    TvExperimentalAdblock.isExperimentalEnabled(context) != expBefore ||
                    context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false) != useCustomBefore ||
                    context.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false) != useStreamBefore
                onClosed(changed)
            },
        ) { dismiss ->
            var expAdblock by remember { mutableStateOf(TvExperimentalAdblock.isExperimentalEnabled(context)) }
            var swapPlus by remember { mutableStateOf(TvExperimentalAdblock.isSwapPlusEnabled(context)) }
            var useCustom by remember { mutableStateOf(!expAdblock && !swapPlus && context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)) }
            var useStream by remember { mutableStateOf(!expAdblock && !swapPlus && context.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)) }
            var swapAdblock by remember { mutableStateOf(TvExperimentalAdblock.isSwapAdblockEnabled(context)) }

            val anyProxyOn = !expAdblock && !swapPlus && (useCustom || useStream)
            val swapIsFallback = !anyProxyOn && (swapPlus || expAdblock)
            val swapPlusIsFallback = !anyProxyOn && expAdblock && swapPlus

            fun syncStates() {
                TvExperimentalAdblock.normalizePrefs(context)
                expAdblock = TvExperimentalAdblock.isExperimentalEnabled(context)
                swapPlus = TvExperimentalAdblock.isSwapPlusEnabled(context)
                useCustom = context.prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                useStream = context.prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                swapAdblock = TvExperimentalAdblock.isSwapAdblockEnabled(context)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = context.getString(R.string.tv_adblock_and_proxies_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = context.getString(R.string.tv_adblock_and_proxies_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                // Adblock (Swap)
                ToggleCardRow(
                    title = context.getString(R.string.tv_adblock_swap_title),
                    subtitle = context.getString(R.string.tv_adblock_swap_desc),
                    checked = swapAdblock,
                    badge = if (swapIsFallback) context.getString(R.string.tv_adblock_fallback_badge) else null,
                    showSwitch = !swapIsFallback,
                    onToggle = { next ->
                        if (swapIsFallback) {
                            TvToast.makeText(context, R.string.tv_adblock_swap_locked_exp_hint, Toast.LENGTH_SHORT).show()
                        } else {
                            context.prefs().edit { putBoolean(C.PLAYER_ADBLOCK, next) }
                            syncStates()
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))

                // Adblock (Swap+)
                ToggleCardRow(
                    title = context.getString(R.string.tv_adblock_swap_plus_title),
                    subtitle = context.getString(R.string.mobile_adblock_swap_plus_desc),
                    checked = swapPlus,
                    badge = if (swapPlusIsFallback) context.getString(R.string.tv_adblock_fallback_badge) else null,
                    showSwitch = true,
                    onToggle = { next ->
                        context.prefs().edit {
                            putBoolean(C.TV_ADBLOCK_SWAP_PLUS, next)
                            if (next) {
                                putBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                                putBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                                putBoolean(C.PLAYER_ADBLOCK, true)
                            }
                        }
                        syncStates()
                    },
                )
                Spacer(Modifier.height(8.dp))

                // Adblock (VOD)
                ToggleCardRow(
                    title = context.getString(R.string.tv_adblock_exp_title),
                    subtitle = context.getString(R.string.mobile_adblock_vod_desc),
                    checked = expAdblock,
                    badge = null,
                    showSwitch = true,
                    onToggle = { next ->
                        context.prefs().edit {
                            putBoolean(C.TV_EXPERIMENTAL_ADBLOCK, next)
                            if (next) {
                                putBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                                putBoolean(C.PLAYER_USE_STREAM_PROXY, false)
                                putBoolean(C.PLAYER_ADBLOCK, true)
                            }
                        }
                        syncStates()
                    },
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Spacer(Modifier.height(12.dp))

                // Custom Proxies
                val customCount = customProxies.count { it.enabled }
                ToggleCardRow(
                    title = context.getString(R.string.use_custom_proxies),
                    subtitle = if (customProxies.isEmpty()) {
                        context.getString(R.string.custom_proxy_settings)
                    } else {
                        "$customCount / ${customProxies.size}"
                    },
                    checked = useCustom,
                    badge = null,
                    showSwitch = true,
                    onToggle = { next ->
                        context.prefs().edit {
                            putBoolean(C.PLAYER_USE_CUSTOM_PROXY, next)
                            if (next) {
                                putBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false)
                                putBoolean(C.TV_ADBLOCK_SWAP_PLUS, false)
                            }
                        }
                        syncStates()
                    },
                )
                Spacer(Modifier.height(8.dp))

                // HTTP Stream Proxies
                val streamCount = streamProxies.count { it.enabled }
                ToggleCardRow(
                    title = context.getString(R.string.use_stream_proxies),
                    subtitle = if (streamProxies.isEmpty()) {
                        context.getString(R.string.proxy_settings)
                    } else {
                        "$streamCount / ${streamProxies.size}"
                    },
                    checked = useStream,
                    badge = null,
                    showSwitch = true,
                    onToggle = { next ->
                        context.prefs().edit {
                            putBoolean(C.PLAYER_USE_STREAM_PROXY, next)
                            if (next) {
                                putBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false)
                                putBoolean(C.TV_ADBLOCK_SWAP_PLUS, false)
                            }
                        }
                        syncStates()
                    },
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            dismiss()
                            openCustomSettings()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = context.getString(R.string.custom_proxy_settings),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            dismiss()
                            openStreamSettings()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = context.getString(R.string.proxy_settings),
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(context.getString(android.R.string.ok))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun ToggleCardRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        badge: String?,
        showSwitch: Boolean,
        onToggle: (Boolean) -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onToggle(!checked) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (badge != null && showSwitch) {
                            FallbackPill(badge)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badge != null && !showSwitch) {
                    FallbackPill(badge)
                } else {
                    Switch(
                        checked = checked,
                        onCheckedChange = onToggle,
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. My Statistics Sheet (reusing TvStats)
    // -------------------------------------------------------------------------

    private fun formatDuration(context: Context, ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return if (hours > 0L) {
            context.getString(R.string.tv_stats_hours, hours.toInt(), mins.toInt())
        } else {
            "$mins min"
        }
    }

    fun showStats(context: Context) {
        showSheet(context) { dismiss ->
            var periodDays by remember { mutableStateOf<Int?>(30) }
            var tick by remember { mutableIntStateOf(0) }
            var confirmReset by remember { mutableStateOf(false) }
            val detail = remember(periodDays, tick) { TvStats.detail(context, periodDays) }
            val nf = remember { NumberFormat.getInstance() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.tv_ic_bar_chart),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = context.getString(R.string.mobile_stats_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = periodDays == 7,
                        onClick = { periodDays = 7 },
                        label = { Text(context.getString(R.string.tv_stats_period_7)) },
                    )
                    FilterChip(
                        selected = periodDays == 30,
                        onClick = { periodDays = 30 },
                        label = { Text(context.getString(R.string.tv_stats_period_30)) },
                    )
                    FilterChip(
                        selected = periodDays == null,
                        onClick = { periodDays = null },
                        label = { Text(context.getString(R.string.tv_stats_period_all)) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 2x2 KPI Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatKpiCard(
                        label = context.getString(R.string.tv_stats_watched),
                        value = formatDuration(context, detail.watchMs),
                        modifier = Modifier.weight(1f),
                    )
                    StatKpiCard(
                        label = context.getString(R.string.tv_stats_ads),
                        value = nf.format(detail.ads),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatKpiCard(
                        label = context.getString(R.string.tv_stats_streams),
                        value = nf.format(detail.streams),
                        modifier = Modifier.weight(1f),
                    )
                    StatKpiCard(
                        label = context.getString(R.string.tv_stats_streak),
                        value = context.getString(R.string.tv_stats_days, max(detail.currentStreak, detail.bestStreak)),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (detail.streamers.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = context.getString(R.string.tv_stats_top_streamers),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    detail.streamers.take(5).forEachIndexed { idx, s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                text = s.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatDuration(context, s.ms),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (detail.games.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = context.getString(R.string.tv_stats_top_games),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    detail.games.take(5).forEachIndexed { idx, g ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                text = g.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatDuration(context, g.ms),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (confirmReset) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = context.getString(R.string.tv_stats_reset_message),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { confirmReset = false },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(context.getString(R.string.no))
                                }
                                Button(
                                    onClick = {
                                        TvStats.reset(context)
                                        confirmReset = false
                                        tick++
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(context.getString(R.string.yes), color = Color.White)
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(context.getString(R.string.tv_stats_reset))
                        }
                        Button(
                            onClick = dismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(context.getString(R.string.close_player))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun StatKpiCard(label: String, value: String, modifier: Modifier = Modifier) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = value,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // 7. Favorites Manager Sheet (reusing TvFavorites)
    // -------------------------------------------------------------------------

    fun showFavoritesManager(context: Context, onChanged: (() -> Unit)? = null) {
        TvFavorites.load(context)
        showSheet(context, onDismiss = onChanged) { dismiss ->
            val current by TvFavorites.items.collectAsState()
            val working = remember(current) { mutableStateListOf<TvFavorites.Favorite>().apply { addAll(current) } }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = context.getString(R.string.tv_favorites),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = context.getString(R.string.mobile_favorites_how),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (working.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(R.string.mobile_favorites_empty),
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(working, key = { _, fav -> fav.login }) { index, fav ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.tv_ic_star),
                                        contentDescription = null,
                                        tint = Color(0xFFFACC15),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = fav.name?.takeIf { it.isNotBlank() } ?: fav.login,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val moved = working.removeAt(index)
                                                working.add(index - 1, moved)
                                                TvFavorites.reorder(context, working.toList())
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Text("▲", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < working.lastIndex) {
                                                val moved = working.removeAt(index)
                                                working.add(index + 1, moved)
                                                TvFavorites.reorder(context, working.toList())
                                            }
                                        },
                                        enabled = index < working.lastIndex,
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Text("▼", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            TvFavorites.remove(context, fav.login)
                                        },
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.tv_ic_delete),
                                            contentDescription = context.getString(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(context.getString(R.string.close_player))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // -------------------------------------------------------------------------
    // 8. Hidden Content / Blacklist Manager Sheet (reusing TvBlacklist)
    // -------------------------------------------------------------------------

    fun showBlacklistManager(
        context: Context,
        initialTab: Int = 0,
        onChanged: (() -> Unit)? = null,
    ) {
        TvBlacklist.load(context)
        showSheet(context, onDismiss = onChanged) { dismiss ->
            val channelList by TvBlacklist.channels.collectAsState()
            val categoryList by TvBlacklist.categories.collectAsState()
            val keywordList by TvBlacklist.keywords.collectAsState()
            var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
            var keywordDraft by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = context.getString(R.string.tv_blacklist_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = context.getString(R.string.tv_blacklist_subtitle),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("${context.getString(R.string.channels)} (${channelList.size})", maxLines = 1) },
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("${context.getString(R.string.games)} (${categoryList.size})", maxLines = 1) },
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        label = { Text("${context.getString(R.string.tv_blacklist_tab_keywords)} (${keywordList.size})", maxLines = 1) },
                    )
                }

                Spacer(Modifier.height(10.dp))

                when (selectedTab) {
                    0 -> {
                        if (channelList.isEmpty()) {
                            BlacklistEmptyMessage(
                                context.getString(R.string.tv_blacklist_empty_channels),
                                context.getString(R.string.mobile_blacklist_hint_cards),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 340.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(channelList, key = { _, ch -> ch.id ?: ch.login ?: ch.displayLabel }) { _, ch ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        ) {
                                            val avatar = ch.avatarUrl?.let { TwitchApiHelper.getProfileImage(it) ?: it }
                                            if (!avatar.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = avatar,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape),
                                                )
                                                Spacer(Modifier.width(12.dp))
                                            }
                                            Text(
                                                text = ch.displayLabel,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = {
                                                    TvBlacklist.unblockChannel(context, ch.id, ch.login)
                                                    TvToast.makeText(
                                                        context,
                                                        context.getString(R.string.tv_blacklist_channel_unhidden, ch.displayLabel),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.tv_ic_delete),
                                                    contentDescription = context.getString(R.string.delete),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        if (categoryList.isEmpty()) {
                            BlacklistEmptyMessage(
                                context.getString(R.string.tv_blacklist_empty_categories),
                                context.getString(R.string.mobile_blacklist_hint_cards),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 340.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(categoryList, key = { _, cat -> cat.id ?: cat.slug ?: cat.displayLabel }) { _, cat ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        ) {
                                            val art = cat.boxArtUrl?.let { TwitchApiHelper.getGameBoxArt(it) ?: it }
                                            if (!art.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = art,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .width(28.dp)
                                                        .height(38.dp)
                                                        .clip(RoundedCornerShape(6.dp)),
                                                )
                                                Spacer(Modifier.width(12.dp))
                                            }
                                            Text(
                                                text = cat.displayLabel,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = {
                                                    TvBlacklist.unblockCategory(context, cat.id, cat.slug, cat.name)
                                                    TvToast.makeText(
                                                        context,
                                                        context.getString(R.string.tv_blacklist_category_unhidden, cat.displayLabel),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.tv_ic_delete),
                                                    contentDescription = context.getString(R.string.delete),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        val submitKeyword = {
                            val trimmed = keywordDraft.trim()
                            if (trimmed.isNotEmpty()) {
                                val added = TvBlacklist.addKeyword(context, trimmed)
                                keywordDraft = ""
                                if (added > 0) {
                                    TvToast.makeText(
                                        context,
                                        context.getString(R.string.tv_blacklist_keyword_added, trimmed.removePrefix("#").trim()),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = keywordDraft,
                                onValueChange = { keywordDraft = it },
                                placeholder = { Text(context.getString(R.string.tv_blacklist_keyword_hint), maxLines = 1) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submitKeyword() }),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = submitKeyword,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.tv_ic_add),
                                    contentDescription = context.getString(R.string.tv_blacklist_keyword_add),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (keywordList.isEmpty()) {
                            BlacklistEmptyMessage(
                                context.getString(R.string.tv_blacklist_empty_keywords),
                                context.getString(R.string.tv_blacklist_hint_keywords),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(keywordList, key = { _, kw -> kw.lowercase(Locale.ROOT) }) { _, kw ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        ) {
                                            Text(
                                                text = kw,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = {
                                                    TvBlacklist.removeKeyword(context, kw)
                                                    TvToast.makeText(
                                                        context,
                                                        context.getString(R.string.tv_blacklist_keyword_removed, kw),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.tv_ic_delete),
                                                    contentDescription = context.getString(R.string.delete),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                val activeCount = when (selectedTab) {
                    0 -> channelList.size
                    1 -> categoryList.size
                    else -> keywordList.size
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (activeCount >= 2) {
                        OutlinedButton(
                            onClick = { TvBlacklist.clearTab(context, selectedTab) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = context.getString(R.string.tv_blacklist_clear_tab),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(context.getString(R.string.close_player))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun BlacklistEmptyMessage(title: String, hint: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // -------------------------------------------------------------------------
    // 9. Create 30s Clip Sheet (Native Android Share + Copy + Open)
    // -------------------------------------------------------------------------

    fun showCreateClip(
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

        showSheet(context) { dismiss ->
            var clipUrl by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                var resolvedBid = broadcasterId?.takeIf { it.isNotBlank() }
                if (resolvedBid == null && !channelLogin.isNullOrBlank()) {
                    resolvedBid = runCatching {
                        repo.loadQueryUser(networkLibrary, headers, null, channelLogin).data?.user?.id
                    }.getOrNull()
                }
                val bid = resolvedBid?.takeIf { it.isNotBlank() }
                if (bid == null) {
                    TvToast.makeText(context, context.getString(R.string.tv_clip_failed, "BROADCASTER_NOT_FOUND"), Toast.LENGTH_LONG).show()
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
                    TvToast.makeText(context, context.getString(R.string.tv_clip_failed, result.error ?: "ERROR"), Toast.LENGTH_LONG).show()
                    dismiss()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ready = clipUrl
                Text(
                    text = context.getString(if (ready != null) R.string.tv_clip_ready_title else R.string.tv_create_clip),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                if (ready == null) {
                    Text(
                        text = context.getString(R.string.tv_clip_creating),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = ready,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            MobileUi.shareText(context, ready, context.getString(R.string.tv_clip_ready_title))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painter = painterResource(R.drawable.tv_ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(R.string.share))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { MobileUi.copyToClipboard(context, "Clip", ready) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(context.getString(R.string.mobile_copy_link), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { MobileUi.openUrl(context, ready) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(context.getString(R.string.mobile_open_browser), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = dismiss) {
                    Text(context.getString(R.string.close_player))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 10. Channel Points & Twitch Drops Sheet
    // -------------------------------------------------------------------------

    fun showRewards(
        context: Context,
        channelId: String?,
        channelLogin: String?,
        gameId: String? = null,
        gameName: String? = null,
    ) {
        val app = context.applicationContext as XtraApp
        val repo = app.xtraModule.graphQLRepository
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val nf = NumberFormat.getInstance()

        showSheet(context) { dismiss ->
            val scope = rememberCoroutineScope()
            var refreshTick by remember { mutableIntStateOf(0) }
            var loading by remember { mutableStateOf(true) }
            var store by remember { mutableStateOf<GraphQLRepository.ChannelPointsStoreData?>(null) }
            var drops by remember { mutableStateOf<List<GraphQLRepository.DropProgressItem>>(emptyList()) }
            var claimedKeys by remember { mutableStateOf(emptySet<String>()) }
            var busy by remember { mutableStateOf(false) }
            var promptReward by remember { mutableStateOf<GraphQLRepository.CustomRewardItem?>(null) }
            var promptInput by remember { mutableStateOf("") }

            LaunchedEffect(refreshTick) {
                loading = true
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                val storeDeferred = async {
                    if (!channelLogin.isNullOrBlank()) repo.loadChannelPointsStore(networkLibrary, headers, channelLogin) else null
                }
                val dropsDeferred = async {
                    repo.loadDropsProgressFromInventory(networkLibrary, headers)
                }
                store = storeDeferred.await()
                drops = dropsDeferred.await()
                loading = false
            }

            val currentStore = store
            val resolvedChannelId = currentStore?.channelId?.takeIf { it.isNotBlank() } ?: channelId
            val claimId = currentStore?.availableClaimId
            val visibleDrops = drops.filter { !it.isClaimed && it.dropId !in claimedKeys && (it.dropInstanceId == null || it.dropInstanceId !in claimedKeys) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.tv_ic_diamond),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = context.getString(R.string.tv_points_and_drops),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                if (!channelLogin.isNullOrBlank()) {
                    val pointsTitle = currentStore?.pointsName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tv_points_default_name)
                    val balanceText = currentStore?.balance?.let { nf.format(it) } ?: "—"
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = context.getString(R.string.tv_points_balance, pointsTitle, balanceText),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Inline text input when redeeming a reward requiring user message
                val activePrompt = promptReward
                if (activePrompt != null && !resolvedChannelId.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = activePrompt.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!activePrompt.prompt.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = activePrompt.prompt,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                placeholder = { Text(context.getString(R.string.tv_points_input_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        promptReward = null
                                        promptInput = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(context.getString(android.R.string.cancel))
                                }
                                Button(
                                    enabled = !busy && promptInput.isNotBlank(),
                                    onClick = {
                                        val msg = promptInput.trim()
                                        promptReward = null
                                        promptInput = ""
                                        busy = true
                                        scope.launch {
                                            val res = repo.redeemCustomReward(
                                                networkLibrary = networkLibrary,
                                                headers = TwitchApiHelper.getGQLHeaders(context, true),
                                                channelId = resolvedChannelId,
                                                rewardId = activePrompt.id,
                                                title = activePrompt.title,
                                                cost = activePrompt.cost,
                                                prompt = activePrompt.prompt,
                                                textInput = msg,
                                            )
                                            TvToast.makeText(
                                                context,
                                                if (res.success) context.getString(R.string.tv_points_redeemed_ok, activePrompt.title)
                                                else context.getString(R.string.tv_points_redeem_failed, res.errorCode ?: "ERROR"),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            busy = false
                                            refreshTick++
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(context.getString(R.string.tv_points_redeem_action, nf.format(activePrompt.cost)))
                                }
                            }
                        }
                    }
                }

                // Claim bonus chest
                if (!claimId.isNullOrBlank() && !resolvedChannelId.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                repo.loadClaimPoints(networkLibrary, TwitchApiHelper.getGQLHeaders(context, true), resolvedChannelId, claimId)
                                TvToast.makeText(context, R.string.tv_points_bonus_claimed, Toast.LENGTH_SHORT).show()
                                busy = false
                                refreshTick++
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painter = painterResource(R.drawable.tv_ic_gift), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(R.string.tv_points_claim_bonus))
                    }
                }

                // Custom rewards
                val rewards = currentStore?.rewards.orEmpty()
                if (!channelLogin.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    if (!loading && rewards.isEmpty()) {
                        Text(
                            text = context.getString(R.string.tv_points_no_rewards),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val bal = currentStore?.balance ?: 0
                        rewards.forEach { reward ->
                            val canRedeem = reward.isEnabled && !reward.isPaused && reward.isInStock && bal >= reward.cost && !resolvedChannelId.isNullOrBlank()
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = canRedeem && !busy) {
                                        if (reward.isUserInputRequired) {
                                            promptReward = reward
                                            promptInput = ""
                                        } else if (!resolvedChannelId.isNullOrBlank()) {
                                            busy = true
                                            scope.launch {
                                                val res = repo.redeemCustomReward(
                                                    networkLibrary = networkLibrary,
                                                    headers = TwitchApiHelper.getGQLHeaders(context, true),
                                                    channelId = resolvedChannelId,
                                                    rewardId = reward.id,
                                                    title = reward.title,
                                                    cost = reward.cost,
                                                    prompt = reward.prompt,
                                                    textInput = null,
                                                )
                                                TvToast.makeText(
                                                    context,
                                                    if (res.success) context.getString(R.string.tv_points_redeemed_ok, reward.title)
                                                    else context.getString(R.string.tv_points_redeem_failed, res.errorCode ?: "ERROR"),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                busy = false
                                                refreshTick++
                                            }
                                        }
                                    },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Text(
                                            text = reward.title,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (canRedeem) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val statusLabel = when {
                                            reward.isPaused -> context.getString(R.string.tv_points_paused)
                                            !reward.isInStock -> context.getString(R.string.tv_points_out_of_stock)
                                            bal < reward.cost -> context.getString(R.string.tv_points_insufficient)
                                            !reward.prompt.isNullOrBlank() -> reward.prompt
                                            else -> null
                                        }
                                        if (!statusLabel.isNullOrBlank()) {
                                            Text(
                                                text = statusLabel,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    FallbackPill(nf.format(reward.cost))
                                }
                            }
                        }
                    }
                }

                // Twitch Drops
                Spacer(Modifier.height(16.dp))
                Text(
                    text = context.getString(R.string.tv_drops_section),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                if (!loading && visibleDrops.isEmpty()) {
                    Text(
                        text = context.getString(R.string.tv_drops_none),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    visibleDrops.forEach { drop ->
                        val reqMin = drop.requiredMinutes.coerceAtLeast(1)
                        val progress = (drop.currentMinutes.toFloat() / reqMin.toFloat()).coerceIn(0f, 1f)
                        val claimable = !drop.dropInstanceId.isNullOrBlank() && drop.currentMinutes >= reqMin
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = drop.dropName,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${drop.gameName ?: drop.campaignName} · ${drop.currentMinutes}/$reqMin min",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (claimable) {
                                        Button(
                                            enabled = !busy,
                                            onClick = {
                                                val instanceId = drop.dropInstanceId ?: return@Button
                                                busy = true
                                                scope.launch {
                                                    val res = repo.claimDropRewards(networkLibrary, TwitchApiHelper.getGQLHeaders(context, true), instanceId)
                                                    if (res == GraphQLRepository.DropClaimResult.CLAIMED || res == GraphQLRepository.DropClaimResult.ALREADY_CLAIMED) {
                                                        claimedKeys = claimedKeys + setOf(drop.dropId, instanceId)
                                                        TvToast.makeText(context, context.getString(R.string.tv_points_redeemed_ok, drop.dropName), Toast.LENGTH_SHORT).show()
                                                    }
                                                    busy = false
                                                    refreshTick++
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text(context.getString(R.string.mobile_claim_drop), fontSize = 12.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(CircleShape),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(context.getString(R.string.close_player))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // -------------------------------------------------------------------------
    // 11. Polls & Predictions Sheet
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalLayoutApi::class)
    fun showPollsAndPredictions(
        context: Context,
        channelLogin: String?,
        pollFlow: StateFlow<Poll?>,
        pollSecondsFlow: StateFlow<Int?>,
        predictionFlow: StateFlow<Prediction?>,
        predictionSecondsFlow: StateFlow<Int?>,
        selfBetOutcomeIdFlow: MutableStateFlow<String?>,
        selfBetPointsFlow: MutableStateFlow<Int>,
        selfVotedPollChoicesFlow: MutableStateFlow<Map<String, String>>,
        onOptimisticPollUpdate: (Poll) -> Unit,
        onOptimisticPredictionUpdate: (Prediction) -> Unit,
    ) {
        val app = context.applicationContext as XtraApp
        val repo = app.xtraModule.graphQLRepository
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val nf = NumberFormat.getInstance()

        showSheet(context) { dismiss ->
            val scope = rememberCoroutineScope()
            val poll by pollFlow.collectAsState()
            val pollSec by pollSecondsFlow.collectAsState()
            val prediction by predictionFlow.collectAsState()
            val predSec by predictionSecondsFlow.collectAsState()
            val selfBetOutcomeId by selfBetOutcomeIdFlow.collectAsState()
            val selfBetPoints by selfBetPointsFlow.collectAsState()
            val votedPollMap by selfVotedPollChoicesFlow.collectAsState()

            val hasPoll = poll != null && poll?.status == "ACTIVE"
            val hasPrediction = prediction != null && (prediction?.status == "ACTIVE" || prediction?.status == "LOCKED")
            var selectedTab by remember { mutableIntStateOf(if (!hasPoll && hasPrediction) 1 else 0) }
            var balance by remember { mutableStateOf<Int?>(null) }
            var selectedPreset by remember { mutableIntStateOf(500) }
            var busy by remember { mutableStateOf(false) }

            val userId = remember { context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() } }

            LaunchedEffect(channelLogin) {
                if (!channelLogin.isNullOrBlank()) {
                    val store = repo.loadChannelPointsStore(networkLibrary, TwitchApiHelper.getGQLHeaders(context, true), channelLogin)
                    balance = store?.balance
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = context.getString(
                        when {
                            hasPoll && hasPrediction -> R.string.tv_polls_predictions_title
                            hasPrediction -> R.string.tv_prediction_panel_title
                            else -> R.string.tv_poll_panel_title
                        }
                    ),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                balance?.let { bal ->
                    Text(
                        text = "${nf.format(bal)} ${context.getString(R.string.tv_points_default_name)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (hasPoll && hasPrediction) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            label = { Text(context.getString(R.string.tv_poll_tab)) },
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            label = { Text(context.getString(R.string.tv_prediction_tab)) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                val activeTab = if (hasPoll && hasPrediction) selectedTab else if (hasPrediction) 1 else 0
                if (!hasPoll && !hasPrediction) {
                    Text(
                        text = context.getString(R.string.tv_polls_predictions_none),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else if (activeTab == 0 && poll != null) {
                    val p = poll!!
                    val pollId = p.id.orEmpty()
                    val myChoice = votedPollMap[pollId]
                    val totalVotes = p.totalVotes ?: p.choices?.sumOf { it.totalVotes ?: 0 } ?: 0
                    val sec = pollSec ?: p.remainingMilliseconds?.let { (it / 1000).coerceAtLeast(0) }
                    Text(
                        text = p.title.orEmpty(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (sec != null && sec > 0) {
                        Text(
                            text = DateUtils.formatElapsedTime(sec.toLong()),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    p.choices.orEmpty().forEach { choice ->
                        val votes = choice.totalVotes ?: 0
                        val pct = if (totalVotes > 0) ((votes * 100.0) / totalVotes).roundToInt() else 0
                        val isMine = myChoice != null && myChoice == choice.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isMine) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy && myChoice == null && userId != null && !choice.id.isNullOrBlank()) {
                                    val cId = choice.id ?: return@clickable
                                    busy = true
                                    scope.launch {
                                        val res = repo.voteInPoll(
                                            networkLibrary = networkLibrary,
                                            headers = TwitchApiHelper.getGQLHeaders(context, true),
                                            pollId = pollId,
                                            choiceId = cId,
                                            userId = userId!!,
                                            channelPointsCost = null,
                                        )
                                        busy = false
                                        if (res.success) {
                                            selfVotedPollChoicesFlow.value = selfVotedPollChoicesFlow.value + (pollId to cId)
                                            val updated = p.choices.orEmpty().map { c ->
                                                if (c.id == cId) Poll.PollChoice(c.id, c.title, (c.totalVotes ?: 0) + 1) else c
                                            }
                                            onOptimisticPollUpdate(
                                                Poll(p.id, p.title, p.status, updated, totalVotes + 1, p.remainingMilliseconds, p.pointsVotingEnabled, p.pointsPerVote)
                                            )
                                        }
                                    }
                                },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = choice.title.orEmpty(),
                                        fontSize = 14.5.sp,
                                        fontWeight = if (isMine) FontWeight.Bold else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "$pct% (${nf.format(votes)})",
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                )
                            }
                        }
                    }
                } else if (prediction != null) {
                    val pred = prediction!!
                    val predId = pred.id.orEmpty()
                    val isLocked = pred.status == "LOCKED"
                    val totalPts = pred.outcomes.orEmpty().sumOf { it.totalPoints?.toLong() ?: 0L }
                    Text(
                        text = pred.title.orEmpty(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val sec = predSec
                    if (!isLocked && sec != null && sec > 0) {
                        Text(
                            text = DateUtils.formatElapsedTime(sec.toLong()),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (isLocked) {
                        Text(
                            text = context.getString(R.string.prediction_locked),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!isLocked) {
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(100, 500, 1_000, 5_000, 10_000).forEach { amount ->
                                FilterChip(
                                    selected = selectedPreset == amount,
                                    onClick = { selectedPreset = amount },
                                    label = { Text(nf.format(amount), fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    pred.outcomes.orEmpty().forEachIndexed { idx, outcome ->
                        val pts = outcome.totalPoints ?: 0
                        val pct = if (totalPts > 0L) ((pts.toLong() * 100.0) / totalPts).roundToInt() else 0
                        val isMine = selfBetOutcomeId != null && selfBetOutcomeId == outcome.id
                        val accentColor = if (idx % 2 == 0) Color(0xFF387AFF) else Color(0xFFF5009B)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isMine) accentColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    enabled = !busy && !isLocked && !outcome.id.isNullOrBlank() &&
                                        (selfBetOutcomeId == null || selfBetOutcomeId == outcome.id)
                                ) {
                                    val oId = outcome.id ?: return@clickable
                                    val betAmount = balance?.let { selectedPreset.coerceAtMost(it) } ?: selectedPreset
                                    if (betAmount <= 0) return@clickable
                                    busy = true
                                    scope.launch {
                                        val res = repo.makePrediction(
                                            networkLibrary = networkLibrary,
                                            headers = TwitchApiHelper.getGQLHeaders(context, true),
                                            eventId = predId,
                                            outcomeId = oId,
                                            points = betAmount,
                                        )
                                        busy = false
                                        if (res.success) {
                                            val newMine = (if (selfBetOutcomeId == oId) selfBetPoints else 0) + betAmount
                                            selfBetOutcomeIdFlow.value = oId
                                            selfBetPointsFlow.value = newMine
                                            balance = balance?.let { (it - betAmount).coerceAtLeast(0) }
                                            val updatedOutcomes = pred.outcomes.orEmpty().map { o ->
                                                if (o.id == oId) {
                                                    Prediction.PredictionOutcome(
                                                        o.id,
                                                        o.title,
                                                        (o.totalPoints ?: 0) + betAmount,
                                                        (o.totalUsers ?: 0) + if (isMine) 0 else 1,
                                                        o.color,
                                                    )
                                                } else o
                                            }
                                            onOptimisticPredictionUpdate(
                                                Prediction(pred.id, pred.createdAt, updatedOutcomes, pred.predictionWindowSeconds, pred.status, pred.title, pred.winningOutcomeId)
                                            )
                                        }
                                    }
                                },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = outcome.title.orEmpty(),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "$pct% · ${nf.format(pts)}",
                                        fontSize = 12.5.sp,
                                        color = accentColor,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                                    color = accentColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(context.getString(R.string.close_player))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // -------------------------------------------------------------------------
    // 11. Multi-Account Switcher Sheet (reusing TvAccounts vault)
    // -------------------------------------------------------------------------

    fun showAccountsSheet(
        activity: MainActivity,
        onOpenCurrentLoginMethods: () -> Unit,
        onAddAnotherAccount: () -> Unit,
    ) {
        com.github.andreyasadchy.xtra.ui.tv.TvAccounts.syncCurrentFromPrefs(activity)
        MobileUi.ensureAccountAvatarLoaded(activity)
        showSheet(activity) { dismiss ->
            var accounts by remember { mutableStateOf(com.github.andreyasadchy.xtra.ui.tv.TvAccounts.listAccounts(activity)) }
            val activeKey = remember(accounts) { com.github.andreyasadchy.xtra.ui.tv.TvAccounts.activeAccountKey(activity) }
            val guestActive = remember(accounts) { com.github.andreyasadchy.xtra.ui.tv.TvAccounts.isGuestMode(activity) }
            val liveAvatarUrl by MobileUi.accountAvatarUrl.collectAsState()
            var confirmRemove by remember { mutableStateOf<com.github.andreyasadchy.xtra.ui.tv.TvAccounts.SavedAccount?>(null) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_person_black_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = activity.getString(R.string.tv_accounts_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (accounts.isEmpty() && !guestActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = activity.getString(R.string.tv_accounts_guest),
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    dismiss()
                                    onOpenCurrentLoginMethods()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(activity.getString(R.string.log_in))
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            val isActive = acc.key == activeKey
                            val avatarToShow = acc.avatarUrl ?: if (isActive) liveAvatarUrl else null
                            Surface(
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isActive) {
                                            Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                                RoundedCornerShape(16.dp),
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        if (isActive) {
                                            dismiss()
                                        } else {
                                            val switched = com.github.andreyasadchy.xtra.ui.tv.TvAccounts.switchToAccount(activity, acc.key)
                                            dismiss()
                                            if (switched != null) {
                                                TvToast.makeText(
                                                    activity,
                                                    activity.getString(R.string.tv_accounts_switched_to, switched.username),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                activity.onTvAccountSwitched()
                                            }
                                        }
                                    },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    if (!avatarToShow.isNullOrBlank()) {
                                        AsyncImage(
                                            model = avatarToShow,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape),
                                        )
                                    } else {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.baseline_person_black_24),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = acc.username,
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isActive) {
                                            Text(
                                                text = activity.getString(R.string.tv_accounts_active),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { confirmRemove = acc },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.tv_ic_delete),
                                            contentDescription = activity.getString(R.string.mobile_accounts_remove, acc.username),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Guest / Anonymous mode row
                        Surface(
                            color = if (guestActive) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (guestActive) {
                                        Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                            RoundedCornerShape(16.dp),
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    if (!guestActive) {
                                        com.github.andreyasadchy.xtra.ui.tv.TvAccounts.switchToGuest(activity)
                                        dismiss()
                                        TvToast.makeText(
                                            activity,
                                            activity.getString(R.string.tv_accounts_switched_guest),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        activity.onTvAccountSwitched()
                                    } else {
                                        dismiss()
                                    }
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.baseline_person_black_24),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activity.getString(R.string.tv_accounts_guest),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (guestActive) {
                                        Text(
                                            text = activity.getString(R.string.tv_accounts_active),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inline confirmation banner when removing an account
                    val pending = confirmRemove
                    if (pending != null) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = activity.getString(R.string.mobile_accounts_remove, pending.username),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    OutlinedButton(
                                        onClick = { confirmRemove = null },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(activity.getString(R.string.no))
                                    }
                                    Button(
                                        onClick = {
                                            val wasActive = pending.key == activeKey
                                            com.github.andreyasadchy.xtra.ui.tv.TvAccounts.removeAccount(activity, pending.key)
                                            TvToast.makeText(
                                                activity,
                                                activity.getString(R.string.tv_accounts_removed, pending.username),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            confirmRemove = null
                                            if (wasActive) {
                                                dismiss()
                                                activity.onTvAccountSwitched()
                                            } else {
                                                accounts = com.github.andreyasadchy.xtra.ui.tv.TvAccounts.listAccounts(activity)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(activity.getString(R.string.yes), color = MaterialTheme.colorScheme.onError)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            dismiss()
                            onAddAnotherAccount()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(activity.getString(R.string.tv_accounts_add))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

