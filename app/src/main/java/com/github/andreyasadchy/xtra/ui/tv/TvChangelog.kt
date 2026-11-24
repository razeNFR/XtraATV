package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.main.MainViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.util.Date
import kotlin.time.Instant

/**
 * TV "What's new / Changelog" side panel in Settings > About:
 * lists the installed version and previous GitHub releases (cached locally + bundled current release notes fallback),
 * and opens formatted, D-pad scrollable patch notes for any selected update.
 */
object TvChangelog {

    private const val PREF_CHANGELOG_CACHE = "tv_changelog_releases_cache"
    private const val DEFAULT_RELEASES_URL = "https://api.github.com/repos/razeNFR/XtraATV/releases?per_page=25"
    private val VERSION_REGEX = Regex("""\d+(?:\.\d+)+""")
    private val INSTALL_FOOTER_REGEX = Regex("""\n+#{1,4}\s*Install\b[\s\S]*$""", RegexOption.IGNORE_CASE)

    class Entry(
        val key: String,
        val title: String,
        val versionNumber: String?,
        val publishedAtMs: Long?,
        val notesMarkdown: String,
        val preview: String,
        val isInstalled: Boolean,
        val isNewer: Boolean,
    )

    @Volatile
    private var memoryCache: List<Entry>? = null

    fun show(context: Context) {
        TvDialogs.show(context) { dismiss ->
            ChangelogPanel(context = context, onDismiss = dismiss)
        }
    }

    private fun extractVersionNumber(text: String?): String? =
        text?.let { VERSION_REGEX.find(it)?.value }

    private fun cleanNotesBody(raw: String): String {
        val withoutInstall = raw.replace("\r", "").replace(INSTALL_FOOTER_REGEX, "").trim()
        val lines = withoutInstall.lines()
        val bodyLines = if (lines.firstOrNull()?.trim()?.startsWith("# ") == true) {
            lines.drop(1)
        } else {
            lines
        }
        return bodyLines.joinToString("\n").trim().ifEmpty { withoutInstall }
    }

    private fun extractPreview(cleanedBody: String): String {
        val bullets = cleanedBody.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { line ->
                line.drop(2)
                    .replace("**", "")
                    .replace("`", "")
                    .trim()
                    .removeSuffix(":")
            }
            .filter { it.isNotBlank() }
            .take(3)
            .toList()
        if (bullets.isNotEmpty()) {
            return bullets.joinToString(" · ")
        }
        return cleanedBody.lineSequence()
            .map { it.trim().trimStart('#').trim().replace("**", "").replace("`", "") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun bundledCurrentEntry(installedVersion: String): Entry? {
        val raw = BuildConfig.RELEASE_NOTES.trim()
        if (raw.isEmpty()) return null
        val firstHeading = raw.lineSequence()
            .firstOrNull { it.trim().startsWith("# ") }
            ?.trim()
            ?.removePrefix("# ")
            ?.trim()
        val installedNum = extractVersionNumber(installedVersion)
        val notesNum = extractVersionNumber(firstHeading) ?: installedNum
        val title = firstHeading?.takeIf { it.isNotBlank() } ?: "Xtra TV ${installedNum ?: installedVersion}"
        val cleaned = cleanNotesBody(raw)
        if (cleaned.isBlank()) return null
        return Entry(
            key = notesNum ?: installedVersion,
            title = title,
            versionNumber = notesNum,
            publishedAtMs = null,
            notesMarkdown = cleaned,
            preview = extractPreview(cleaned),
            isInstalled = true,
            isNewer = false,
        )
    }

    private fun parseGithubReleases(json: Json, rawJson: String, installedVersion: String): List<Entry> {
        if (rawJson.isBlank()) return emptyList()
        val installedNum = extractVersionNumber(installedVersion)
        val element = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()
        val objects: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }.filter {
                it["draft"]?.jsonPrimitive?.booleanOrNull != true &&
                    it["prerelease"]?.jsonPrimitive?.booleanOrNull != true
            }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return objects.mapNotNull { rel ->
            val rawBody = rel["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val name = rel["name"]?.jsonPrimitive?.contentOrNull?.trim()
            val tag = rel["tag_name"]?.jsonPrimitive?.contentOrNull?.trim()
            val displayTitle = name?.takeIf { it.isNotBlank() }
                ?: tag?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val cleaned = cleanNotesBody(rawBody)
            if (cleaned.isBlank()) return@mapNotNull null
            val verNum = extractVersionNumber(tag) ?: extractVersionNumber(name)
            val publishedMs = rel["published_at"]?.jsonPrimitive?.contentOrNull?.let {
                Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0L }
            }
            val isInstalled = if (verNum != null && installedNum != null) {
                verNum == installedNum
            } else {
                tag != null && installedVersion.startsWith(tag.removePrefix("v"))
            }
            val isNewer = !isInstalled && MainViewModel.isNewerVersion(tag ?: name, installedVersion)
            Entry(
                key = tag ?: verNum ?: displayTitle,
                title = displayTitle,
                versionNumber = verNum,
                publishedAtMs = publishedMs,
                notesMarkdown = cleaned,
                preview = extractPreview(cleaned),
                isInstalled = isInstalled,
                isNewer = isNewer,
            )
        }
    }

    private fun mergeEntries(remoteEntries: List<Entry>, installedVersion: String): List<Entry> {
        val bundled = bundledCurrentEntry(installedVersion) ?: return remoteEntries
        val hasInstalledInRemote = remoteEntries.any {
            it.isInstalled || (bundled.versionNumber != null && it.versionNumber == bundled.versionNumber)
        }
        if (hasInstalledInRemote) return remoteEntries
        val newerCount = remoteEntries.indexOfFirst { !it.isNewer }.let { if (it < 0) remoteEntries.size else it }
        return buildList {
            addAll(remoteEntries.take(newerCount))
            add(bundled)
            addAll(remoteEntries.drop(newerCount))
        }
    }

    private fun initialEntries(context: Context): List<Entry> {
        memoryCache?.takeIf { it.isNotEmpty() }?.let { return it }
        val app = context.applicationContext as XtraApp
        val installed = BuildConfig.VERSION_NAME
        val cachedRaw = context.prefs().getString(PREF_CHANGELOG_CACHE, null).orEmpty()
        val parsed = parseGithubReleases(app.xtraModule.json, cachedRaw, installed)
        return mergeEntries(parsed, installed).also {
            if (it.isNotEmpty()) memoryCache = it
        }
    }

    private fun resolveReleasesListUrl(context: Context): String {
        val configured = context.prefs().getString(C.UPDATE_URL, null)?.trim()
        if (configured.isNullOrBlank()) return DEFAULT_RELEASES_URL
        return configured.replace(Regex("""/releases(?:/latest)?(?:\?.*)?$"""), "/releases?per_page=25")
    }

    private suspend fun fetchFreshEntries(context: Context): List<Entry>? = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext as XtraApp
            val url = resolveReleasesListUrl(context)
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
            val raw = app.xtraModule.okHttpClient.value.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body.string()
            } ?: return@runCatching null
            val installed = BuildConfig.VERSION_NAME
            val parsed = parseGithubReleases(app.xtraModule.json, raw, installed)
            if (parsed.isNotEmpty()) {
                context.prefs().edit { putString(PREF_CHANGELOG_CACHE, raw) }
            }
            mergeEntries(parsed, installed).also {
                if (it.isNotEmpty()) memoryCache = it
            }
        }.getOrNull()
    }

    private fun formatDate(context: Context, epochMs: Long?): String? {
        if (epochMs == null || epochMs <= 0L) return null
        return runCatching {
            DateFormat.getMediumDateFormat(context).format(Date(epochMs))
        }.getOrNull()
    }

    private fun formatMarkdown(markdown: String, accentColor: Color): AnnotatedString = buildAnnotatedString {
        val lines = markdown.replace("\r", "").lines()
        lines.forEachIndexed { index, raw ->
            val trimmedEnd = raw.trimEnd()
            val leadingSpaces = trimmedEnd.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            val stripped = trimmedEnd.trimStart()
            when {
                stripped.startsWith("#") -> {
                    val level = stripped.takeWhile { it == '#' }.length
                    val heading = stripped.drop(level).trim()
                    if (index > 0) append("\n")
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (level <= 2) 17.sp else 15.5.sp,
                            color = if (level >= 3) accentColor else Color.White,
                        )
                    ) {
                        append(heading)
                    }
                }
                stripped.startsWith("- ") || stripped.startsWith("* ") -> {
                    val nested = leadingSpaces >= 2
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = if (nested) Color(0xB3FFFFFF) else accentColor,
                        )
                    ) {
                        append(if (nested) "      ◦  " else "  •  ")
                    }
                    appendInlineMarkdown(stripped.drop(2), accentColor)
                }
                else -> appendInlineMarkdown(stripped, accentColor)
            }
            if (index < lines.lastIndex) append("\n")
        }
    }

    private fun AnnotatedString.Builder.appendInlineMarkdown(text: String, accentColor: Color) {
        val boldParts = text.split("**")
        boldParts.forEachIndexed { bIdx, segment ->
            if (bIdx % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                    append(segment.replace("`", ""))
                }
            } else {
                val codeParts = segment.split("`")
                codeParts.forEachIndexed { cIdx, codeSeg ->
                    if (cIdx % 2 == 1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accentColor)) {
                            append(codeSeg)
                        }
                    } else {
                        append(codeSeg)
                    }
                }
            }
        }
    }

    @Composable
    private fun ChangelogPanel(context: Context, onDismiss: () -> Unit) {
        var entries by remember { mutableStateOf(initialEntries(context)) }
        var loading by remember { mutableStateOf(true) }
        var selectedIndex by remember { mutableIntStateOf(-1) }
        var returnToIndex by remember { mutableIntStateOf(-1) }
        val listState = rememberLazyListState()
        val itemRequesters = remember { mutableMapOf<Int, FocusRequester>() }
        val closeRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            val fresh = fetchFreshEntries(context)
            if (!fresh.isNullOrEmpty()) {
                entries = fresh
            }
            loading = false
        }

        BackHandler(enabled = selectedIndex >= 0) {
            returnToIndex = selectedIndex
            selectedIndex = -1
        }

        val activeEntry = entries.getOrNull(selectedIndex)
        val panelTitle = activeEntry?.title ?: context.getString(R.string.tv_about_changelog)
        val panelSubtitle = if (activeEntry != null) {
            val dateText = formatDate(context, activeEntry.publishedAtMs)
            val statusText = when {
                activeEntry.isInstalled -> context.getString(R.string.tv_changelog_installed)
                activeEntry.isNewer -> context.getString(R.string.tv_changelog_new)
                else -> null
            }
            listOfNotNull(statusText, dateText).joinToString(" · ").ifEmpty { null }
        } else {
            val base = "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
            if (loading && entries.size <= 1) {
                "$base · ${context.getString(R.string.tv_changelog_loading)}"
            } else {
                base
            }
        }

        SidePanel(
            title = panelTitle,
            subtitle = panelSubtitle,
            width = 620.dp,
            scrollable = false,
            compactHeader = true,
            footer = {
                ButtonRow(
                    label = context.getString(if (activeEntry != null) R.string.tv_changelog_back else R.string.tv_close_playback),
                    modifier = Modifier.focusRequester(closeRequester),
                    compact = true,
                    onClick = {
                        if (activeEntry != null) {
                            returnToIndex = selectedIndex
                            selectedIndex = -1
                        } else {
                            onDismiss()
                        }
                    },
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (activeEntry != null) {
                    ReleaseDetailView(
                        entry = activeEntry,
                        downTarget = closeRequester,
                    )
                } else if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(if (loading) R.string.tv_changelog_loading else R.string.tv_changelog_empty),
                            fontSize = 15.sp,
                            color = TvColors.secondaryText,
                        )
                    }
                    LaunchedEffect(entries.isEmpty()) {
                        runCatching { closeRequester.requestFocus() }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .tvTopFade(height = 24.dp) { listState.canScrollBackward },
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(entries, key = { idx, item -> "${item.key}_$idx" }) { idx, item ->
                            val requester = itemRequesters.getOrPut(idx) { FocusRequester() }
                            ReleaseCardRow(
                                context = context,
                                entry = item,
                                modifier = Modifier.focusRequester(requester),
                                onClick = { selectedIndex = idx },
                            )
                        }
                    }
                    LaunchedEffect(entries.size, selectedIndex) {
                        if (selectedIndex >= 0 || entries.isEmpty()) return@LaunchedEffect
                        val targetIdx = if (returnToIndex in entries.indices) {
                            returnToIndex
                        } else {
                            entries.indexOfFirst { it.isInstalled }.takeIf { it >= 0 } ?: 0
                        }
                        returnToIndex = -1
                        listState.scrollToItem((targetIdx - 1).coerceAtLeast(0))
                        repeat(6) {
                            androidx.compose.runtime.withFrameNanos { }
                            if (runCatching { itemRequesters[targetIdx]?.requestFocus() }.getOrNull() == true) {
                                return@LaunchedEffect
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReleaseCardRow(
        context: Context,
        entry: Entry,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val accent = MaterialTheme.colorScheme.primary
        val shape = RoundedCornerShape(14.dp)
        val bg = when {
            focused -> TvColors.focused
            entry.isInstalled -> Color(0x28FFFFFF)
            else -> Color(0x16FFFFFF)
        }
        val borderColor = when {
            focused -> Color.Transparent
            entry.isInstalled -> accent.copy(alpha = 0.72f)
            else -> Color(0x22FFFFFF)
        }
        val titleColor = if (focused) TvColors.onFocused else Color.White
        val subColor = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText
        val dateText = remember(entry.publishedAtMs) { formatDate(context, entry.publishedAtMs) }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .border(1.dp, borderColor, shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isInstalled || entry.isNewer) {
                        val badgeBg = when {
                            focused -> TvColors.onFocused.copy(alpha = 0.14f)
                            entry.isNewer -> Color(0xFF22C55E).copy(alpha = 0.25f)
                            else -> accent.copy(alpha = 0.28f)
                        }
                        val badgeFg = when {
                            focused -> TvColors.onFocused
                            entry.isNewer -> Color(0xFF86EFAC)
                            else -> Color.White
                        }
                        Text(
                            text = context.getString(
                                if (entry.isInstalled) R.string.tv_changelog_installed else R.string.tv_changelog_new
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(badgeBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (!dateText.isNullOrBlank()) {
                        Text(
                            text = dateText,
                            fontSize = 12.sp,
                            color = subColor,
                            maxLines = 1,
                        )
                    }
                }
                if (entry.preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.preview,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = subColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                painter = painterResource(R.drawable.tv_ic_open_full),
                contentDescription = null,
                tint = if (focused) TvColors.onFocused else Color(0xB3FFFFFF),
                modifier = Modifier.size(16.dp),
            )
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.ReleaseDetailView(
        entry: Entry,
        downTarget: FocusRequester,
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val formatted = remember(entry.notesMarkdown, accent) {
            formatMarkdown(entry.notesMarkdown, accent)
        }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val notesRequester = remember { FocusRequester() }
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val shape = RoundedCornerShape(14.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(Color(0x16FFFFFF))
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) accent else Color(0x24FFFFFF),
                    shape = shape,
                )
                .focusRequester(notesRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (scroll.value > 0) {
                                scope.launch { scroll.animateScrollBy(-240f) }
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (scroll.value < scroll.maxValue) {
                                scope.launch { scroll.animateScrollBy(240f) }
                                true
                            } else {
                                runCatching { downTarget.requestFocus() }.getOrNull() == true
                            }
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction)
                .tvTopFade(height = 22.dp) { scroll.value > 0 }
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(
                text = formatted,
                fontSize = 14.5.sp,
                lineHeight = 22.sp,
                color = Color(0xEBFFFFFF),
            )
        }

        LaunchedEffect(entry.key) {
            scroll.scrollTo(0)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { notesRequester.requestFocus() }
        }
    }
}
