package com.github.andreyasadchy.xtra.ui.channel.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentAboutBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.channel.about.ChannelAboutViewModel.Companion.ChannelAboutViewModelFactory
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.team.TeamFragmentDirections
import com.github.andreyasadchy.xtra.ui.tv.ButtonRow
import com.github.andreyasadchy.xtra.ui.tv.QrCode
import com.github.andreyasadchy.xtra.ui.tv.SidePanel
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.ui.view.TvHeader
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChannelAboutFragment : BaseNetworkFragment(), IntegrityDialog.Listener {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelAboutViewModel by viewModels { ChannelAboutViewModelFactory }
    private var panelAdapter: ChannelPanelAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (TvUi.isTv(requireContext())) {
            val firstRequester = FocusRequester()
            lateinit var composeView: ComposeView
            composeView = TvDialogs.composeView(requireContext()) {
                TvAboutContent(
                    viewModel = viewModel,
                    firstRequester = firstRequester,
                    onFocusHeader = { TvHeader.focusHeaderOf(composeView) },
                    onOpenMenu = { (activity as? MainActivity)?.openTvMenu() },
                    onOpenTeam = { teamName ->
                        findNavController().navigate(TeamFragmentDirections.actionGlobalTeamFragment(teamName = teamName))
                    },
                    onShowQr = { title, url, description -> showTvPanelDetail(title, url, description) },
                )
            }
            TvHeader.registerTabContentFocus(composeView) {
                runCatching { firstRequester.requestFocus() }.isSuccess
            }
            return composeView
        }
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collect {
                    (requireActivity() as? MainActivity)?.getNewIntegrityToken(it, childFragmentManager)
                }
            }
        }
        val b = _binding ?: return
        panelAdapter = ChannelPanelAdapter(this@ChannelAboutFragment)
        b.recyclerView.adapter = panelAdapter
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                b.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        _binding?.apply {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.description.collectLatest {
                        if (!it.isNullOrBlank()) {
                            description.visibility = View.VISIBLE
                            description.text = it
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.socialMedias.collectLatest { result ->
                        if (result != null) {
                            socialMediaList.visibility = View.VISIBLE
                            socialMediaList.removeAllViews()
                            result.forEach {
                                val title = it.first
                                val url = it.second
                                if (!title.isNullOrBlank()) {
                                    socialMediaList.addView(
                                        TextView(requireContext()).apply {
                                            val host = url?.toUri()?.host?.removePrefix("www.")
                                            val string = if (host != null) {
                                                "$title ($host)"
                                            } else {
                                                title
                                            }
                                            val spannableString = SpannableString(string)
                                            spannableString.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                            if (url != null) {
                                                spannableString.setSpan(object : ClickableSpan() {
                                                    override fun onClick(widget: View) {
                                                        try {
                                                            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                                                addCategory(Intent.CATEGORY_BROWSABLE)
                                                            }
                                                            startActivity(intent)
                                                        } catch (e: ActivityNotFoundException) {
                                                            Toast.makeText(requireContext(), R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }, 0, string.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                                movementMethod = LinkMovementMethod.getInstance()
                                            }
                                            text = spannableString
                                            layoutParams = LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT
                                            )
                                            context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                                                TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                                            }
                                            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, resources.displayMetrics).toInt()
                                            setPadding(0, 0, 0, padding)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.team.collectLatest { result ->
                        if (result != null) {
                            val name = result.first
                            val displayName = result.second
                            if (!displayName.isNullOrBlank()) {
                                team.visibility = View.VISIBLE
                                val string = getString(R.string.team, displayName)
                                val index = string.indexOf(displayName)
                                val spannableString = SpannableString(string)
                                spannableString.setSpan(StyleSpan(Typeface.BOLD), index, index + displayName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                if (name != null) {
                                    spannableString.setSpan(object : ClickableSpan() {
                                        override fun onClick(widget: View) {
                                            findNavController().navigate(
                                                TeamFragmentDirections.actionGlobalTeamFragment(
                                                    teamName = name,
                                                )
                                            )
                                        }
                                    }, index, index + displayName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    team.movementMethod = LinkMovementMethod.getInstance()
                                }
                                team.text = spannableString
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.originalName.collectLatest {
                        if (!it.isNullOrBlank()) {
                            originalName.visibility = View.VISIBLE
                            originalName.text = getString(R.string.old_username, it)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.panels.collectLatest { result ->
                        panelAdapter?.submitList(result)
                    }
                }
            }
        }
        viewModel.loadAbout(
            channelId = args.channelId,
            channelLogin = args.channelLogin,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    private fun showTvPanelDetail(title: String, url: String?, description: String?) {
        val ctx = context ?: return
        val qrBitmap = url?.takeIf { it.isNotBlank() }?.let { runCatching { QrCode.encode(it, 520) }.getOrNull() }
        TvDialogs.show(ctx) { dismiss ->
            SidePanel(title = title.ifBlank { args.channelName ?: args.channelLogin.orEmpty() }, width = 460.dp, scrollable = true) {
                val closeRequester = remember { FocusRequester() }
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = Color(0xEBFFFFFF),
                    )
                    Spacer(Modifier.height(18.dp))
                }
                if (qrBitmap != null && !url.isNullOrBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = ctx.getString(R.string.tv_scan_qr_link),
                            fontSize = 14.sp,
                            color = TvColors.secondaryText,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = url,
                                modifier = Modifier.size(190.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = url,
                            fontSize = 13.sp,
                            color = TvColors.secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                }
                ButtonRow(
                    label = ctx.getString(R.string.close_player),
                    modifier = Modifier.focusRequester(closeRequester),
                    onClick = dismiss,
                )
                LaunchedEffect(Unit) {
                    runCatching { closeRequester.requestFocus() }
                }
            }
        }
    }

    override fun onNetworkRestored() {
        viewModel.loadAbout(
            channelId = args.channelId,
            channelLogin = args.channelLogin,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    override fun onIntegrityTokenLoaded(callback: String?) {
        (parentFragment as? IntegrityDialog.Listener)?.onIntegrityTokenLoaded("refresh")
        when (callback) {
            "refresh" -> {
                viewModel.loadAbout(
                    channelId = args.channelId,
                    channelLogin = args.channelLogin,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun cleanPanelMarkdown(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace("__", "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

@Composable
private fun TvAboutContent(
    viewModel: ChannelAboutViewModel,
    firstRequester: FocusRequester,
    onFocusHeader: () -> Boolean,
    onOpenMenu: () -> Unit,
    onOpenTeam: (String) -> Unit,
    onShowQr: (title: String, url: String?, description: String?) -> Unit,
) {
    val description by viewModel.description.collectAsState()
    val socialMedias by viewModel.socialMedias.collectAsState()
    val team by viewModel.team.collectAsState()
    val originalName by viewModel.originalName.collectAsState()
    val panels by viewModel.panels.collectAsState()

    val validSocials = remember(socialMedias) {
        socialMedias.orEmpty().mapNotNull { (title, url) ->
            if (!title.isNullOrBlank() && !url.isNullOrBlank()) title to url else null
        }
    }
    val validPanels = remember(panels) {
        panels.orEmpty().filter { !it.title.isNullOrBlank() || !it.imageUrl.isNullOrBlank() || !it.description.isNullOrBlank() }
    }
    val hasBioRow = !description.isNullOrBlank() || !originalName.isNullOrBlank() || !team?.second.isNullOrBlank()
    val socialRows = remember(validSocials) { validSocials.chunked(4) }
    val panelRows = remember(validPanels) { validPanels.chunked(3) }

    val firstSection = when {
        hasBioRow -> 0
        socialRows.isNotEmpty() -> 1
        panelRows.isNotEmpty() -> 2
        else -> 3
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 14.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (hasBioRow) {
            item(key = "bio_row") {
                val hasBioCard = !description.isNullOrBlank() || !originalName.isNullOrBlank()
                val teamSlug = team?.first
                val teamDisplay = team?.second
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (hasBioCard) {
                        val bioText = description.orEmpty()
                        val oldUser = originalName?.let { stringResource(R.string.old_username, it) }
                        TvAboutSurfaceCard(
                            modifier = Modifier
                                .weight(1f)
                                .then(if (firstSection == 0) Modifier.focusRequester(firstRequester) else Modifier),
                            isTopRow = firstSection == 0,
                            isLeftEdge = true,
                            onFocusHeader = onFocusHeader,
                            onOpenMenu = onOpenMenu,
                            onClick = {
                                if (bioText.isNotBlank()) {
                                    onShowQr(oldUser ?: "", null, bioText)
                                }
                            },
                        ) { focused ->
                            Column {
                                if (bioText.isNotBlank()) {
                                    Text(
                                        text = bioText,
                                        fontSize = 16.sp,
                                        lineHeight = 23.sp,
                                        color = if (focused) TvColors.onFocused else Color(0xF0FFFFFF),
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!oldUser.isNullOrBlank()) {
                                    if (bioText.isNotBlank()) Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = oldUser,
                                        fontSize = 13.sp,
                                        color = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                    if (!teamDisplay.isNullOrBlank()) {
                        TvAboutSurfaceCard(
                            modifier = Modifier
                                .then(if (!hasBioCard && firstSection == 0) Modifier.focusRequester(firstRequester) else Modifier),
                            isTopRow = firstSection == 0,
                            isLeftEdge = !hasBioCard,
                            onFocusHeader = onFocusHeader,
                            onOpenMenu = onOpenMenu,
                            onClick = {
                                if (!teamSlug.isNullOrBlank()) onOpenTeam(teamSlug)
                            },
                        ) { focused ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.tv_ic_streamers),
                                    contentDescription = null,
                                    tint = if (focused) TvColors.onFocused else TvColors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.team, teamDisplay),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (focused) TvColors.onFocused else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }

        itemsIndexed(socialRows, key = { idx, _ -> "social_row_$idx" }) { rowIdx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEachIndexed { colIdx, (title, url) ->
                    val host = remember(url) { url.toUri().host?.removePrefix("www.") }
                    val isFirst = firstSection == 1 && rowIdx == 0 && colIdx == 0
                    TvAboutSurfaceCard(
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isFirst) Modifier.focusRequester(firstRequester) else Modifier),
                        isTopRow = firstSection == 1 && rowIdx == 0,
                        isLeftEdge = colIdx == 0,
                        onFocusHeader = onFocusHeader,
                        onOpenMenu = onOpenMenu,
                        onClick = { onShowQr(title, url, null) },
                    ) { focused ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.tv_ic_globe),
                                contentDescription = null,
                                tint = if (focused) TvColors.onFocused else TvColors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (focused) TvColors.onFocused else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!host.isNullOrBlank()) {
                                    Text(
                                        text = host,
                                        fontSize = 12.sp,
                                        color = if (focused) TvColors.onFocused.copy(alpha = 0.72f) else TvColors.secondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        itemsIndexed(panelRows, key = { idx, _ -> "panel_row_$idx" }) { rowIdx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                row.forEachIndexed { colIdx, panel ->
                    val isFirst = firstSection == 2 && rowIdx == 0 && colIdx == 0
                    TvChannelPanelCard(
                        panel = panel,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isFirst) Modifier.focusRequester(firstRequester) else Modifier),
                        isTopRow = firstSection == 2 && rowIdx == 0,
                        isLeftEdge = colIdx == 0,
                        onFocusHeader = onFocusHeader,
                        onOpenMenu = onOpenMenu,
                        onClick = {
                            val cleanDesc = cleanPanelMarkdown(panel.description)
                            if (!panel.linkUrl.isNullOrBlank() || cleanDesc.isNotBlank()) {
                                onShowQr(panel.title.orEmpty(), panel.linkUrl, cleanDesc.takeIf { it.isNotBlank() })
                            }
                        },
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        if (firstSection == 3) {
            item(key = "about_empty") {
                TvAboutSurfaceCard(
                    modifier = Modifier.focusRequester(firstRequester),
                    isTopRow = true,
                    isLeftEdge = true,
                    onFocusHeader = onFocusHeader,
                    onOpenMenu = onOpenMenu,
                    onClick = {},
                ) { focused ->
                    Text(
                        text = stringResource(R.string.about),
                        fontSize = 15.sp,
                        color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvChannelPanelCard(
    panel: ChannelPanel,
    modifier: Modifier = Modifier,
    isTopRow: Boolean,
    isLeftEdge: Boolean,
    onFocusHeader: () -> Boolean,
    onOpenMenu: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val cleanDesc = remember(panel.description) { cleanPanelMarkdown(panel.description) }
    val host = remember(panel.linkUrl) { panel.linkUrl?.toUri()?.host?.removePrefix("www.") }
    TvAboutSurfaceCard(
        modifier = modifier,
        isTopRow = isTopRow,
        isLeftEdge = isLeftEdge,
        onFocusHeader = onFocusHeader,
        onOpenMenu = onOpenMenu,
        onClick = onClick,
    ) { focused ->
        Column {
            if (!panel.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = remember(panel.imageUrl) {
                        ImageRequest.Builder(context)
                            .data(panel.imageUrl)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = panel.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                if (!panel.title.isNullOrBlank() || cleanDesc.isNotBlank() || !host.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (!panel.title.isNullOrBlank()) {
                Text(
                    text = panel.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (focused) TvColors.onFocused else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (cleanDesc.isNotBlank()) {
                if (!panel.title.isNullOrBlank()) Spacer(Modifier.height(6.dp))
                Text(
                    text = cleanDesc,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = if (focused) TvColors.onFocused.copy(alpha = 0.85f) else TvColors.secondaryText,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!host.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.tv_ic_globe),
                        contentDescription = null,
                        tint = if (focused) TvColors.onFocused else TvColors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = host,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) TvColors.onFocused else TvColors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvAboutSurfaceCard(
    modifier: Modifier = Modifier,
    isTopRow: Boolean,
    isLeftEdge: Boolean,
    onFocusHeader: () -> Boolean,
    onOpenMenu: () -> Unit,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "aboutScale")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.accent else Color(0x1FFFFFFF),
                shape = shape,
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    if (isTopRow && event.key == Key.DirectionUp) {
                        return@onPreviewKeyEvent onFocusHeader()
                    }
                    if (isLeftEdge && event.key == Key.DirectionLeft) {
                        onOpenMenu()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        content(focused)
    }
}