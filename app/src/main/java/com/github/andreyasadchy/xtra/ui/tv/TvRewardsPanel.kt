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
            val closeRequester = remember { FocusRequester() }
            val scrollState = androidx.compose.foundation.rememberScrollState()
            var initialFocusDone by remember { mutableStateOf(false) }

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

            SidePanel(
                title = context.getString(R.string.tv_points_and_drops),
                width = 480.dp,
                scrollable = true,
                scrollState = scrollState,
                footer = {
                    ButtonRow(
                        label = context.getString(R.string.close_player),
                        modifier = Modifier.focusRequester(closeRequester),
                        onClick = dismiss,
                    )
                },
            ) {
                var assignedFirstFocus = false
                fun firstFocusModifier(): Modifier {
                    return if (!assignedFirstFocus) {
                        assignedFirstFocus = true
                        Modifier.focusRequester(firstRequester)
                    } else {
                        Modifier
                    }
                }

                val currentStore = store
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

                    val claimId = currentStore?.availableClaimId
                    val resolvedChannelId = currentStore?.channelId?.takeIf { it.isNotBlank() } ?: channelId
                    if (!claimId.isNullOrBlank() && !resolvedChannelId.isNullOrBlank()) {
                        ButtonRow(
                            label = "🎁 " + context.getString(R.string.tv_points_claim_bonus),
                            modifier = firstFocusModifier(),
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
                    val rewards = currentStore?.rewards.orEmpty()
                    if (!loading && rewards.isEmpty()) {
                        Text(
                            text = context.getString(R.string.tv_points_no_rewards),
                            fontSize = 14.sp,
                            color = TvColors.secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    } else {
                        val balance = currentStore?.balance ?: Int.MAX_VALUE
                        rewards.forEach { reward ->
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
                                modifier = firstFocusModifier(),
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
                        DropItemRow(
                            context = context,
                            drop = drop,
                            modifier = firstFocusModifier(),
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
                        DropItemRow(
                            context = context,
                            drop = drop,
                            modifier = firstFocusModifier(),
                            onClaim = { claimDrop(drop) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                val hasTopFocusable = assignedFirstFocus
                LaunchedEffect(loading, hasTopFocusable) {
                    if (loading && !initialFocusDone) {
                        runCatching { closeRequester.requestFocus() }
                    } else if (!loading && !initialFocusDone) {
                        initialFocusDone = true
                        androidx.compose.runtime.withFrameNanos { }
                        if (hasTopFocusable) {
                            runCatching { firstRequester.requestFocus() }
                        } else {
                            runCatching { closeRequester.requestFocus() }
                        }
                        scrollState.scrollTo(0)
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

        FocusRow(
            modifier = modifier,
            onClick = {
                if (claimable) onClaim()
            },
        ) { focused ->
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
    }
}
