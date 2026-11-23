package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.NumberFormat
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * TV side panel for Channel Points custom rewards redemption, bonus chest claim,
 * and active Twitch Drops progress / instant claiming.
 */
object TvRewardsPanel {

    fun show(
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

        TvDialogs.show(context) { dismiss ->
            val scope = rememberCoroutineScope()
            var refreshTick by remember { mutableIntStateOf(0) }
            var loading by remember { mutableStateOf(true) }
            var store by remember { mutableStateOf<GraphQLRepository.ChannelPointsStoreData?>(null) }
            var drops by remember { mutableStateOf<List<GraphQLRepository.DropProgressItem>>(emptyList()) }
            var claimedKeys by remember { mutableStateOf(emptySet<String>()) }
            var busy by remember { mutableStateOf(false) }
            val firstRequester = remember { FocusRequester() }
            val lastRequester = remember { FocusRequester() }
            val closeRequester = remember { FocusRequester() }
            val scrollState = androidx.compose.foundation.rememberScrollState()
            var initialFocusDone by remember { mutableStateOf(false) }
            var manualTailScrollAnchor by remember { mutableIntStateOf(-1) }

            LaunchedEffect(refreshTick) {
                loading = true
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                val storeDeferred = async {
                    if (!channelLogin.isNullOrBlank()) {
                        repo.loadChannelPointsStore(networkLibrary, headers, channelLogin)
                    } else {
                        null
                    }
                }
                val dropsDeferred = async {
                    repo.loadDropsProgressFromInventory(networkLibrary, headers)
                }
                store = storeDeferred.await()
                drops = dropsDeferred.await()
                loading = false
            }

            fun isDropClaimable(drop: GraphQLRepository.DropProgressItem): Boolean {
                val reqMin = drop.requiredMinutes.coerceAtLeast(1)
                return !drop.isClaimed &&
                    drop.dropId !in claimedKeys &&
                    !drop.dropInstanceId.isNullOrBlank() &&
                    drop.dropInstanceId !in claimedKeys &&
                    drop.currentMinutes >= reqMin
            }

            val currentStore = store
            val visibleDrops = drops.filter { drop ->
                !drop.isClaimed &&
                    drop.dropId !in claimedKeys &&
                    (drop.dropInstanceId == null || drop.dropInstanceId !in claimedKeys)
            }
            val hasStreamCategory = !gameId.isNullOrBlank() || !gameName.isNullOrBlank()
            fun matchesStreamCategory(drop: GraphQLRepository.DropProgressItem): Boolean {
                if (!gameId.isNullOrBlank() && !drop.gameId.isNullOrBlank()) {
                    return drop.gameId == gameId
                }
                if (!gameName.isNullOrBlank() && !drop.gameName.isNullOrBlank()) {
                    return drop.gameName.equals(gameName, ignoreCase = true)
                }
                return false
            }
            val streamDrops = if (hasStreamCategory) visibleDrops.filter(::matchesStreamCategory) else visibleDrops
            val otherDrops = if (hasStreamCategory) visibleDrops.filterNot(::matchesStreamCategory) else emptyList()

            val claimId = currentStore?.availableClaimId
            val resolvedChannelId = currentStore?.channelId?.takeIf { it.isNotBlank() } ?: channelId
            val hasBonusButton = !channelLogin.isNullOrBlank() && !claimId.isNullOrBlank() && !resolvedChannelId.isNullOrBlank()
            val rewardsList = if (!channelLogin.isNullOrBlank()) currentStore?.rewards.orEmpty() else emptyList()
            val claimableDropsCount = streamDrops.count(::isDropClaimable) + otherDrops.count(::isDropClaimable)
            val totalInteractive = (if (hasBonusButton) 1 else 0) + rewardsList.size + claimableDropsCount

            SidePanel(
                title = context.getString(R.string.tv_points_and_drops),
                width = 480.dp,
                scrollable = true,
                scrollState = scrollState,
                footer = {
                    ButtonRow(
                        label = context.getString(R.string.close_player),
                        modifier = Modifier
                            .focusRequester(closeRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (ev.key) {
                                    Key.DirectionDown -> {
                                        if (scrollState.value < scrollState.maxValue - 4) {
                                            scope.launch {
                                                scrollState.animateScrollTo((scrollState.value + 220).coerceAtMost(scrollState.maxValue))
                                            }
                                            true
                                        } else {
                                            true
                                        }
                                    }
                                    Key.DirectionUp -> {
                                        if (totalInteractive == 0) {
                                            if (scrollState.value > 0) {
                                                scope.launch {
                                                    scrollState.animateScrollTo((scrollState.value - 220).coerceAtLeast(0))
                                                }
                                            }
                                            true
                                        } else if (manualTailScrollAnchor >= 0) {
                                            val targetAnchor = manualTailScrollAnchor
                                            val prev = (scrollState.value - 220).coerceAtLeast(targetAnchor)
                                            if (prev <= targetAnchor + 4) {
                                                manualTailScrollAnchor = -1
                                                runCatching { lastRequester.requestFocus() }
                                                scope.launch {
                                                    androidx.compose.runtime.withFrameNanos { }
                                                    scrollState.animateScrollTo(targetAnchor)
                                                }
                                            } else {
                                                scope.launch { scrollState.animateScrollTo(prev) }
                                            }
                                            true
                                        } else {
                                            runCatching { lastRequester.requestFocus() }
                                            true
                                        }
                                    }
                                    else -> false
                                }
                            },
                        onClick = dismiss,
                    )
                },
            ) {
                var interactiveCursor = 0
                fun nextInteractiveModifier(): Modifier {
                    val idx = interactiveCursor++
                    val isFirst = idx == 0
                    val isLast = idx == totalInteractive - 1
                    var m: Modifier = Modifier
                    if (isFirst) m = m.focusRequester(firstRequester)
                    if (isLast) m = m.focusRequester(lastRequester)
                    if (isFirst || isLast) {
                        m = m.onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionDown -> {
                                    if (isLast) {
                                        if (scrollState.value < scrollState.maxValue - 4) {
                                            manualTailScrollAnchor = scrollState.value
                                            val next = (scrollState.value + 220).coerceAtMost(scrollState.maxValue)
                                            runCatching { closeRequester.requestFocus() }
                                            scope.launch { scrollState.animateScrollTo(next) }
                                        } else {
                                            manualTailScrollAnchor = -1
                                            runCatching { closeRequester.requestFocus() }
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.DirectionUp -> {
                                    if (isFirst && scrollState.value > 0) {
                                        scope.launch {
                                            scrollState.animateScrollTo((scrollState.value - 220).coerceAtLeast(0))
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        }
                    }
                    return m
                }

                if (!channelLogin.isNullOrBlank()) {
                    val pointsTitle = currentStore?.pointsName?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.tv_points_default_name)
                    val balanceText = currentStore?.balance?.let { nf.format(it) } ?: "—"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_diamond),
                            contentDescription = null,
                            tint = TvColors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = context.getString(R.string.tv_points_balance, pointsTitle, balanceText),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = ComposeColor.White,
                        )
                    }

                    if (hasBonusButton && !claimId.isNullOrBlank() && !resolvedChannelId.isNullOrBlank()) {
                        ButtonRow(
                            label = "🎁 " + context.getString(R.string.tv_points_claim_bonus),
                            modifier = nextInteractiveModifier(),
                        ) {
                            if (busy) return@ButtonRow
                            busy = true
                            scope.launch {
                                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                                runCatching {
                                    repo.loadClaimPoints(networkLibrary, headers, resolvedChannelId, claimId)
                                }
                                TvToast.makeText(
                                    context,
                                    context.getString(R.string.tv_points_bonus_claimed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                busy = false
                                refreshTick++
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    if (!loading && rewardsList.isEmpty()) {
                        Text(
                            text = context.getString(R.string.tv_points_no_rewards),
                            fontSize = 14.sp,
                            color = TvColors.secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    } else {
                        val balance = currentStore?.balance ?: Int.MAX_VALUE
                        rewardsList.forEach { reward ->
                            val affordable = balance >= reward.cost
                            val nowMs = System.currentTimeMillis()
                            val onCooldown = reward.cooldownExpiresAtMs != null && reward.cooldownExpiresAtMs > nowMs
                            val canRedeem = !reward.isPaused && reward.isInStock && affordable && !onCooldown
                            val costFormatted = nf.format(reward.cost)
                            val statusNote = when {
                                reward.isPaused -> context.getString(R.string.tv_points_paused)
                                !reward.isInStock || onCooldown -> context.getString(R.string.tv_points_out_of_stock)
                                !affordable -> context.getString(R.string.tv_points_insufficient)
                                else -> null
                            }

                            FocusRow(
                                modifier = nextInteractiveModifier(),
                                onClick = {
                                    if (busy) return@FocusRow
                                    if (!canRedeem) {
                                        statusNote?.let {
                                            TvToast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                        }
                                        return@FocusRow
                                    }
                                    val targetChannelId = resolvedChannelId
                                    if (targetChannelId.isNullOrBlank()) return@FocusRow
                                    if (reward.isUserInputRequired) {
                                        TvDialogs.show(context) { dismissInput ->
                                            ChatComposer(
                                                title = reward.title,
                                                hint = reward.prompt?.takeIf { it.isNotBlank() }
                                                    ?: context.getString(R.string.tv_points_input_hint),
                                                sendLabel = context.getString(R.string.tv_points_redeem_action, costFormatted),
                                            ) { inputText ->
                                                val trimmed = inputText.trim()
                                                if (trimmed.isNotEmpty()) {
                                                    dismissInput()
                                                    busy = true
                                                    scope.launch {
                                                        val headers = TwitchApiHelper.getGQLHeaders(context, true)
                                                        val res = repo.redeemCustomReward(
                                                            networkLibrary = networkLibrary,
                                                            headers = headers,
                                                            channelId = targetChannelId,
                                                            rewardId = reward.id,
                                                            title = reward.title,
                                                            cost = reward.cost,
                                                            prompt = reward.prompt,
                                                            textInput = trimmed,
                                                        )
                                                        TvToast.makeText(
                                                            context,
                                                            if (res.success) {
                                                                context.getString(R.string.tv_points_redeemed_ok, reward.title)
                                                            } else {
                                                                context.getString(R.string.tv_points_redeem_failed, res.errorCode ?: "ERROR")
                                                            },
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                        busy = false
                                                        refreshTick++
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        TvDialogs.show(context) { dismissConfirm ->
                                            val confirmReq = remember { FocusRequester() }
                                            SidePanel(
                                                title = context.getString(R.string.tv_points_redeem_confirm, reward.title, costFormatted),
                                                subtitle = reward.prompt?.takeIf { it.isNotBlank() },
                                                scrollable = true,
                                            ) {
                                                ButtonRow(
                                                    label = context.getString(R.string.tv_points_redeem_action, costFormatted),
                                                    modifier = Modifier.focusRequester(confirmReq),
                                                ) {
                                                    dismissConfirm()
                                                    busy = true
                                                    scope.launch {
                                                        val headers = TwitchApiHelper.getGQLHeaders(context, true)
                                                        val res = repo.redeemCustomReward(
                                                            networkLibrary = networkLibrary,
                                                            headers = headers,
                                                            channelId = targetChannelId,
                                                            rewardId = reward.id,
                                                            title = reward.title,
                                                            cost = reward.cost,
                                                            prompt = reward.prompt,
                                                            textInput = null,
                                                        )
                                                        TvToast.makeText(
                                                            context,
                                                            if (res.success) {
                                                                context.getString(R.string.tv_points_redeemed_ok, reward.title)
                                                            } else {
                                                                context.getString(R.string.tv_points_redeem_failed, res.errorCode ?: "ERROR")
                                                            },
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                        busy = false
                                                        refreshTick++
                                                    }
                                                }
                                                ButtonRow(label = context.getString(android.R.string.cancel)) {
                                                    dismissConfirm()
                                                }
                                                LaunchedEffect(Unit) {
                                                    runCatching { confirmReq.requestFocus() }
                                                }
                                            }
                                        }
                                    }
                                },
                            ) { focused ->
                                val fg = if (focused) TvColors.onFocused else if (canRedeem) ComposeColor.White else TvColors.secondaryText
                                val sub = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = reward.title,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = fg,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "💎 $costFormatted",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (focused) TvColors.onFocused else if (affordable) TvColors.accent else TvColors.secondaryText,
                                        )
                                    }
                                    val secondLine = buildString {
                                        if (!reward.prompt.isNullOrBlank()) {
                                            append(reward.prompt)
                                        }
                                        if (!statusNote.isNullOrBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(statusNote)
                                        }
                                    }
                                    if (secondLine.isNotEmpty()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = secondLine,
                                            fontSize = 13.sp,
                                            color = sub,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }

                // Twitch Drops Section (current stream category)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.tv_ic_gift),
                        contentDescription = null,
                        tint = TvColors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = context.getString(R.string.tv_drops_section),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ComposeColor.White,
                    )
                }

                val claimDrop: (GraphQLRepository.DropProgressItem) -> Unit = { drop ->
                    val instanceId = drop.dropInstanceId
                    if (!busy && !instanceId.isNullOrBlank()) {
                        busy = true
                        scope.launch {
                            val headers = TwitchApiHelper.getGQLHeaders(context, true)
                            val res = repo.claimDropRewards(networkLibrary, headers, instanceId)
                            if (res == GraphQLRepository.DropClaimResult.CLAIMED ||
                                res == GraphQLRepository.DropClaimResult.ALREADY_CLAIMED
                            ) {
                                claimedKeys = claimedKeys + setOf(drop.dropId, instanceId)
                                TvToast.makeText(
                                    context,
                                    context.getString(R.string.tv_points_redeemed_ok, drop.dropName),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            busy = false
                            refreshTick++
                        }
                    }
                }

                if (!loading && streamDrops.isEmpty()) {
                    Text(
                        text = context.getString(
                            if (hasStreamCategory && otherDrops.isNotEmpty()) R.string.tv_drops_none_stream else R.string.tv_drops_none,
                        ),
                        fontSize = 14.sp,
                        color = TvColors.secondaryText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                } else {
                    streamDrops.forEach { drop ->
                        val claimable = isDropClaimable(drop)
                        DropItemRow(
                            context = context,
                            drop = drop,
                            modifier = if (claimable) nextInteractiveModifier() else Modifier,
                            onClaim = { claimDrop(drop) },
                        )
                    }
                }

                if (otherDrops.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_gift),
                            contentDescription = null,
                            tint = TvColors.secondaryText,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = context.getString(R.string.tv_drops_other_section),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ComposeColor.White,
                        )
                    }
                    otherDrops.forEach { drop ->
                        val claimable = isDropClaimable(drop)
                        DropItemRow(
                            context = context,
                            drop = drop,
                            modifier = if (claimable) nextInteractiveModifier() else Modifier,
                            onClaim = { claimDrop(drop) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                val hasTopFocusable = totalInteractive > 0 && (!hasBonusButton && rewardsList.isEmpty()).not()
                LaunchedEffect(loading, totalInteractive) {
                    if (loading && !initialFocusDone) {
                        runCatching { closeRequester.requestFocus() }
                    } else if (!loading && !initialFocusDone) {
                        initialFocusDone = true
                        manualTailScrollAnchor = -1
                        androidx.compose.runtime.withFrameNanos { }
                        scrollState.scrollTo(0)
                        if (hasTopFocusable) {
                            runCatching { firstRequester.requestFocus() }
                            scrollState.scrollTo(0)
                        } else if (totalInteractive > 0 && scrollState.maxValue == 0) {
                            runCatching { firstRequester.requestFocus() }
                        } else {
                            runCatching { closeRequester.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DropItemRow(
        context: Context,
        drop: GraphQLRepository.DropProgressItem,
        modifier: Modifier,
        onClaim: () -> Unit,
    ) {
        val reqMin = drop.requiredMinutes.coerceAtLeast(1)
        val curMin = drop.currentMinutes.coerceIn(0, reqMin)
        val progress = (curMin.toFloat() / reqMin.toFloat()).coerceIn(0f, 1f)
        val pct = (progress * 100f).toInt()
        val remaining = (reqMin - curMin).coerceAtLeast(0)
        val claimable = !drop.isClaimed && !drop.dropInstanceId.isNullOrBlank() && curMin >= reqMin
        val statusText = if (claimable) {
            context.getString(R.string.tv_drops_ready_claim)
        } else {
            context.getString(R.string.tv_drops_remaining, pct, remaining)
        }

        @Composable
        fun DropContent(focused: Boolean) {
            val fg = if (focused) TvColors.onFocused else ComposeColor.White
            val sub = if (focused) TvColors.onFocused.copy(alpha = 0.8f) else TvColors.secondaryText
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = buildString {
                        if (!drop.gameName.isNullOrBlank()) {
                            append(drop.gameName)
                            append(" · ")
                        }
                        append(drop.dropName)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (focused) TvColors.onFocused.copy(alpha = 0.22f) else ComposeColor(0x33FFFFFF)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (focused) TvColors.onFocused else TvColors.accent),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = if (claimable) FontWeight.Bold else FontWeight.Normal,
                    color = if (claimable && !focused) TvColors.accent else sub,
                )
            }
        }

        if (claimable) {
            FocusRow(
                modifier = modifier,
                onClick = onClaim,
            ) { focused ->
                DropContent(focused = focused)
            }
        } else {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(ComposeColor(0x14FFFFFF))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                DropContent(focused = false)
            }
        }
    }
}
