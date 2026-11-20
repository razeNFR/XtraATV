package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Interactive TV side panel for live Twitch Polls & Channel Points Predictions.
 * Opened from the temporary player HUD button (`@+id/tvPollsPredictions`), which is visible
 * strictly while a Poll (`ACTIVE`) or Prediction (`ACTIVE` / `LOCKED`) is running.
 */
object TvPollsPredictionsPanel {

    private val BlueOutcomeColor = ComposeColor(0xFF387AFF)
    private val PinkOutcomeColor = ComposeColor(0xFFF5009B)
    private const val MAX_PREDICTION_BET = 250_000

    fun show(
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

        TvDialogs.show(context) { dismiss ->
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

            var selectedTab by remember {
                mutableIntStateOf(if (!hasPoll && hasPrediction) 1 else 0)
            }
            LaunchedEffect(hasPoll, hasPrediction) {
                if (selectedTab == 0 && !hasPoll && hasPrediction) selectedTab = 1
                else if (selectedTab == 1 && !hasPrediction && hasPoll) selectedTab = 0
            }

            var balance by remember { mutableStateOf<Int?>(null) }
            var pointsName by remember { mutableStateOf<String?>(null) }
            var selectedPresetIdx by remember { mutableIntStateOf(1) } // default 500
            var customBetActive by remember { mutableStateOf(false) }
            var customBetText by remember { mutableStateOf("1000") }
            var busy by remember { mutableStateOf(false) }

            val scrollState = rememberScrollState()
            val firstRequester = remember { FocusRequester() }
            val closeRequester = remember { FocusRequester() }
            var initialFocusDone by remember { mutableStateOf(false) }

            val userId = remember { context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() } }
            val isLoggedIn = remember {
                !userId.isNullOrBlank() && (
                    !TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                        !TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN].isNullOrBlank()
                    )
            }

            LaunchedEffect(channelLogin, hasPrediction) {
                if (isLoggedIn && !channelLogin.isNullOrBlank()) {
                    val headers = TwitchApiHelper.getGQLHeaders(context, true)
                    val store = repo.loadChannelPointsStore(networkLibrary, headers, channelLogin)
                    if (store != null) {
                        balance = store.balance
                        pointsName = store.pointsName
                    }
                }
            }

            fun computeBetAmount(): Int {
                val bal = balance ?: 0
                if (customBetActive) {
                    val parsed = customBetText.filter { it.isDigit() }.toIntOrNull() ?: 0
                    val capped = parsed.coerceAtMost(MAX_PREDICTION_BET)
                    return if (bal > 0) capped.coerceAtMost(bal) else capped
                }
                val raw = when (selectedPresetIdx) {
                    0 -> 100
                    1 -> 500
                    2 -> 1_000
                    3 -> 5_000
                    4 -> 10_000
                    5 -> if (bal > 0) max(10, (bal * 0.25).roundToInt()) else 1_000
                    6 -> if (bal > 0) bal.coerceAtMost(MAX_PREDICTION_BET) else MAX_PREDICTION_BET
                    else -> 500
                }
                val capped = raw.coerceAtMost(MAX_PREDICTION_BET)
                return if (bal > 0) capped.coerceAtMost(bal) else capped
            }

            val panelTitle = when {
                hasPoll && hasPrediction -> context.getString(R.string.tv_polls_predictions_title)
                hasPrediction -> context.getString(R.string.tv_prediction_panel_title)
                else -> context.getString(R.string.tv_poll_panel_title)
            }

            val subtitleText = buildString {
                if (!channelLogin.isNullOrBlank()) {
                    append(channelLogin)
                }
                val bal = balance
                if (bal != null && (hasPrediction || (poll?.pointsVotingEnabled == true))) {
                    if (isNotEmpty()) append("  ·  ")
                    append(nf.format(bal))
                    append(" ")
                    append(pointsName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tv_points_default_name))
                }
            }.takeIf { it.isNotBlank() }

            SidePanel(
                title = panelTitle,
                subtitle = subtitleText,
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
                fun firstFocusMod(): Modifier {
                    return if (!assignedFirstFocus) {
                        assignedFirstFocus = true
                        Modifier.focusRequester(firstRequester)
                    } else {
                        Modifier
                    }
                }

                if (!hasPoll && !hasPrediction) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = context.getString(R.string.tv_polls_predictions_none),
                        fontSize = 15.sp,
                        color = TvColors.secondaryText,
                    )
                } else {
                    if (hasPoll && hasPrediction) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TabPill(
                                label = context.getString(R.string.tv_poll_tab),
                                selected = selectedTab == 0,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedTab = 0 },
                            )
                            TabPill(
                                label = context.getString(R.string.tv_prediction_tab),
                                selected = selectedTab == 1,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedTab = 1 },
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    val activeTab = if (hasPoll && hasPrediction) selectedTab else if (hasPrediction) 1 else 0
                    if (activeTab == 0 && poll != null) {
                        val currentPoll = poll!!
                        val pollId = currentPoll.id.orEmpty()
                        val myChoiceId = votedPollMap[pollId]
                        val sec = pollSec ?: currentPoll.remainingMilliseconds?.let { (it / 1000).coerceAtLeast(0) }
                        val totalVotes = currentPoll.totalVotes ?: currentPoll.choices?.sumOf { it.totalVotes ?: 0 } ?: 0

                        EventHeaderCard(
                            title = currentPoll.title.orEmpty(),
                            badgeText = if (sec != null && sec > 0) {
                                DateUtils.formatElapsedTime(sec.toLong())
                            } else {
                                context.getString(R.string.tv_live)
                            },
                            metaText = context.getString(R.string.tv_poll_total_votes, nf.format(totalVotes)),
                        )
                        Spacer(Modifier.height(12.dp))

                        if (!isLoggedIn) {
                            Text(
                                text = context.getString(R.string.tv_polls_login_hint),
                                fontSize = 13.sp,
                                color = TvColors.secondaryText,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        } else if (myChoiceId != null && currentPoll.pointsVotingEnabled && currentPoll.pointsPerVote > 0) {
                            Text(
                                text = context.getString(
                                    R.string.tv_poll_extra_vote_hint,
                                    nf.format(currentPoll.pointsPerVote),
                                ),
                                fontSize = 13.sp,
                                color = TvColors.accent,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        currentPoll.choices.orEmpty().forEach { choice ->
                            val votes = choice.totalVotes ?: 0
                            val fraction = if (totalVotes > 0) (votes.toFloat() / totalVotes.toFloat()).coerceIn(0f, 1f) else 0f
                            val pct = (fraction * 100f).roundToInt()
                            val isMyVote = !myChoiceId.isNullOrBlank() && myChoiceId == choice.id
                            val canExtraVote = myChoiceId != null && currentPoll.pointsVotingEnabled && currentPoll.pointsPerVote > 0
                            val clickable = currentPoll.status == "ACTIVE" && !choice.id.isNullOrBlank() && (myChoiceId == null || canExtraVote)

                            PollChoiceRow(
                                title = choice.title.orEmpty(),
                                votesText = nf.format(votes),
                                pct = pct,
                                fraction = fraction,
                                isSelected = isMyVote,
                                modifier = firstFocusMod(),
                                onClick = {
                                    if (busy) return@PollChoiceRow
                                    if (!isLoggedIn || userId == null) {
                                        TvToast.makeText(context, R.string.tv_polls_login_hint, Toast.LENGTH_SHORT).show()
                                        return@PollChoiceRow
                                    }
                                    if (!clickable) {
                                        TvToast.makeText(context, R.string.tv_poll_already_voted, Toast.LENGTH_SHORT).show()
                                        return@PollChoiceRow
                                    }
                                    val extraCost = if (myChoiceId != null && canExtraVote) currentPoll.pointsPerVote else null
                                    if (extraCost != null && (balance ?: 0) < extraCost) {
                                        TvToast.makeText(context, R.string.tv_prediction_not_enough_points, Toast.LENGTH_SHORT).show()
                                        return@PollChoiceRow
                                    }
                                    val cId = choice.id ?: return@PollChoiceRow
                                    busy = true
                                    scope.launch {
                                        val headers = TwitchApiHelper.getGQLHeaders(context, true)
                                        val res = repo.voteInPoll(
                                            networkLibrary = networkLibrary,
                                            headers = headers,
                                            pollId = pollId,
                                            choiceId = cId,
                                            userId = userId,
                                            channelPointsCost = extraCost,
                                        )
                                        busy = false
                                        if (res.success) {
                                            selfVotedPollChoicesFlow.value = selfVotedPollChoicesFlow.value + (pollId to cId)
                                            if (extraCost != null && balance != null) {
                                                balance = (balance!! - extraCost).coerceAtLeast(0)
                                            }
                                            val updatedChoices = currentPoll.choices.orEmpty().map { c ->
                                                if (c.id == cId) {
                                                    Poll.PollChoice(
                                                        id = c.id,
                                                        title = c.title,
                                                        totalVotes = (c.totalVotes ?: 0) + 1,
                                                    )
                                                } else c
                                            }
                                            onOptimisticPollUpdate(
                                                Poll(
                                                    id = currentPoll.id,
                                                    title = currentPoll.title,
                                                    status = currentPoll.status,
                                                    choices = updatedChoices,
                                                    totalVotes = totalVotes + 1,
                                                    remainingMilliseconds = currentPoll.remainingMilliseconds,
                                                    pointsVotingEnabled = currentPoll.pointsVotingEnabled,
                                                    pointsPerVote = currentPoll.pointsPerVote,
                                                )
                                            )
                                            TvToast.makeText(context, R.string.tv_poll_vote_sent, Toast.LENGTH_SHORT).show()
                                        } else {
                                            TvToast.makeText(
                                                context,
                                                context.getString(R.string.tv_poll_vote_failed, res.errorCode ?: "ERROR"),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                            )
                        }
                    } else if (prediction != null) {
                        val currentPred = prediction!!
                        val predId = currentPred.id.orEmpty()
                        val isLocked = currentPred.status == "LOCKED"
                        val sec = predSec ?: run {
                            val created = currentPred.createdAt
                            val win = currentPred.predictionWindowSeconds
                            if (!isLocked && created != null && win != null && win > 0) {
                                (((created + win * 1000L) - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                            } else null
                        }
                        val totalPool = currentPred.outcomes?.sumOf { (it.totalPoints ?: 0).toLong() } ?: 0L

                        EventHeaderCard(
                            title = currentPred.title.orEmpty(),
                            badgeText = when {
                                isLocked -> context.getString(R.string.prediction_locked)
                                sec != null && sec > 0 -> DateUtils.formatElapsedTime(sec.toLong())
                                else -> context.getString(R.string.tv_live)
                            },
                            metaText = context.getString(R.string.tv_prediction_pool, nf.format(totalPool)),
                        )
                        Spacer(Modifier.height(12.dp))

                        if (selfBetOutcomeId != null && selfBetPoints > 0) {
                            val myOutcomeTitle = currentPred.outcomes?.firstOrNull { it.id == selfBetOutcomeId }?.title.orEmpty()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TvColors.accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = context.getString(
                                        R.string.tv_prediction_your_bet,
                                        nf.format(selfBetPoints),
                                        myOutcomeTitle,
                                    ),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ComposeColor.White,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        if (!isLoggedIn) {
                            Text(
                                text = context.getString(R.string.tv_polls_login_hint),
                                fontSize = 13.sp,
                                color = TvColors.secondaryText,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        } else if (!isLocked) {
                            BetAmountSelector(
                                context = context,
                                nf = nf,
                                balance = balance,
                                selectedPresetIdx = selectedPresetIdx,
                                customActive = customBetActive,
                                customText = customBetText,
                                effectiveAmount = computeBetAmount(),
                                onSelectPreset = { idx ->
                                    selectedPresetIdx = idx
                                    customBetActive = false
                                },
                                onActivateCustom = { customBetActive = true },
                                onCustomTextChange = { text ->
                                    customBetText = text.filter { it.isDigit() }.take(6)
                                    customBetActive = true
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        val outcomes = currentPred.outcomes.orEmpty()
                        outcomes.forEachIndexed { idx, outcome ->
                            val pts = (outcome.totalPoints ?: 0).toLong()
                            val users = outcome.totalUsers ?: 0
                            val fraction = if (totalPool > 0L) (pts.toFloat() / totalPool.toFloat()).coerceIn(0f, 1f) else 0f
                            val pct = (fraction * 100f).roundToInt()
                            val ratioText = if (pts > 0L && totalPool > 0L) {
                                String.format(Locale.US, "1:%.2f", totalPool.toDouble() / pts.toDouble())
                            } else {
                                "—"
                            }
                            val outcomeColor = when (outcome.color?.uppercase(Locale.ROOT)) {
                                "PINK" -> PinkOutcomeColor
                                "BLUE" -> BlueOutcomeColor
                                else -> if (idx % 2 == 1 && outcomes.size == 2) PinkOutcomeColor else BlueOutcomeColor
                            }
                            val isMyOutcome = selfBetOutcomeId != null && selfBetOutcomeId == outcome.id
                            val lockedToOtherOutcome = selfBetOutcomeId != null && selfBetOutcomeId != outcome.id

                            PredictionOutcomeRow(
                                title = outcome.title.orEmpty(),
                                pointsText = nf.format(pts),
                                usersText = nf.format(users),
                                ratioText = ratioText,
                                pct = pct,
                                fraction = fraction,
                                accentColor = outcomeColor,
                                isMyOutcome = isMyOutcome,
                                isLockedOrBlocked = isLocked || lockedToOtherOutcome,
                                modifier = firstFocusMod(),
                                onClick = {
                                    if (busy) return@PredictionOutcomeRow
                                    if (!isLoggedIn) {
                                        TvToast.makeText(context, R.string.tv_polls_login_hint, Toast.LENGTH_SHORT).show()
                                        return@PredictionOutcomeRow
                                    }
                                    if (isLocked) {
                                        TvToast.makeText(context, R.string.prediction_locked, Toast.LENGTH_SHORT).show()
                                        return@PredictionOutcomeRow
                                    }
                                    if (lockedToOtherOutcome) {
                                        TvToast.makeText(context, R.string.tv_prediction_other_outcome_locked, Toast.LENGTH_SHORT).show()
                                        return@PredictionOutcomeRow
                                    }
                                    val betAmount = computeBetAmount()
                                    if (betAmount < 10) {
                                        TvToast.makeText(context, R.string.tv_prediction_min_points, Toast.LENGTH_SHORT).show()
                                        return@PredictionOutcomeRow
                                    }
                                    if (balance != null && betAmount > balance!!) {
                                        TvToast.makeText(context, R.string.tv_prediction_not_enough_points, Toast.LENGTH_SHORT).show()
                                        return@PredictionOutcomeRow
                                    }
                                    val oId = outcome.id ?: return@PredictionOutcomeRow
                                    busy = true
                                    scope.launch {
                                        val headers = TwitchApiHelper.getGQLHeaders(context, true)
                                        val res = repo.makePrediction(
                                            networkLibrary = networkLibrary,
                                            headers = headers,
                                            eventId = predId,
                                            outcomeId = oId,
                                            points = betAmount,
                                        )
                                        busy = false
                                        if (res.success) {
                                            val newMyPoints = (if (selfBetOutcomeId == oId) selfBetPoints else 0) + betAmount
                                            selfBetOutcomeIdFlow.value = oId
                                            selfBetPointsFlow.value = newMyPoints
                                            if (balance != null) {
                                                balance = (balance!! - betAmount).coerceAtLeast(0)
                                            }
                                            val updatedOutcomes = outcomes.map { o ->
                                                if (o.id == oId) {
                                                    Prediction.PredictionOutcome(
                                                        id = o.id,
                                                        title = o.title,
                                                        totalPoints = (o.totalPoints ?: 0) + betAmount,
                                                        totalUsers = (o.totalUsers ?: 0) + if (isMyOutcome) 0 else 1,
                                                        color = o.color,
                                                    )
                                                } else o
                                            }
                                            onOptimisticPredictionUpdate(
                                                Prediction(
                                                    id = currentPred.id,
                                                    createdAt = currentPred.createdAt,
                                                    outcomes = updatedOutcomes,
                                                    predictionWindowSeconds = currentPred.predictionWindowSeconds,
                                                    status = currentPred.status,
                                                    title = currentPred.title,
                                                    winningOutcomeId = currentPred.winningOutcomeId,
                                                )
                                            )
                                            TvToast.makeText(
                                                context,
                                                context.getString(R.string.tv_prediction_bet_sent, nf.format(betAmount), outcome.title.orEmpty()),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            TvToast.makeText(
                                                context,
                                                context.getString(R.string.tv_prediction_bet_failed, res.errorCode ?: "ERROR"),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                val hasTopFocus = assignedFirstFocus
                LaunchedEffect(hasTopFocus) {
                    if (!initialFocusDone) {
                        initialFocusDone = true
                        withFrameNanos { }
                        if (hasTopFocus) {
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
    private fun TabPill(
        label: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val bg = when {
            focused -> TvColors.focused
            selected -> TvColors.accent.copy(alpha = 0.25f)
            else -> TvColors.item
        }
        val fg = when {
            focused -> TvColors.onFocused
            selected -> ComposeColor.White
            else -> TvColors.secondaryText
        }
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .then(
                    if (selected && !focused) Modifier.border(1.dp, TvColors.accent, RoundedCornerShape(10.dp))
                    else Modifier
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
        }
    }

    @Composable
    private fun EventHeaderCard(
        title: String,
        badgeText: String,
        metaText: String,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ComposeColor(0x18FFFFFF))
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TvColors.accent.copy(alpha = 0.24f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvColors.accent,
                    )
                }
                Text(
                    text = metaText,
                    fontSize = 12.sp,
                    color = TvColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (title.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ComposeColor.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun PollChoiceRow(
        title: String,
        votesText: String,
        pct: Int,
        fraction: Float,
        isSelected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FocusRow(modifier = modifier, onClick = onClick) { focused ->
            val fg = if (focused) TvColors.onFocused else ComposeColor.White
            val sub = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_check),
                            contentDescription = null,
                            tint = if (focused) TvColors.onFocused else TvColors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$pct%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (focused) ComposeColor(0x28000000) else ComposeColor(0x28FFFFFF)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(if (focused) TvColors.onFocused else TvColors.accent),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = votesText,
                    fontSize = 12.sp,
                    color = sub,
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun BetAmountSelector(
        context: Context,
        nf: NumberFormat,
        balance: Int?,
        selectedPresetIdx: Int,
        customActive: Boolean,
        customText: String,
        effectiveAmount: Int,
        onSelectPreset: (Int) -> Unit,
        onActivateCustom: () -> Unit,
        onCustomTextChange: (String) -> Unit,
    ) {
        val presets = listOf("100", "500", "1K", "5K", "10K", "25%", "Max")
        val keyboard = LocalSoftwareKeyboardController.current
        val disableSoftKeyboard = remember { context.prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false) }
        val customFieldRequester = remember { FocusRequester() }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ComposeColor(0x12FFFFFF))
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.tv_prediction_bet_amount_label),
                    fontSize = 13.sp,
                    color = TvColors.secondaryText,
                )
                Text(
                    text = nf.format(effectiveAmount),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvColors.accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEachIndexed { idx, label ->
                    val chosen = !customActive && selectedPresetIdx == idx
                    PresetChip(
                        label = label,
                        selected = chosen,
                        onClick = { onSelectPreset(idx) },
                    )
                }
                PresetChip(
                    label = context.getString(R.string.tv_prediction_custom_chip),
                    selected = customActive,
                    onClick = {
                        onActivateCustom()
                        runCatching { customFieldRequester.requestFocus() }
                        if (!disableSoftKeyboard) {
                            keyboard?.show()
                        }
                    },
                )
            }
            if (customActive) {
                Spacer(Modifier.height(8.dp))
                val fieldInteraction = remember { MutableInteractionSource() }
                val fieldFocused by fieldInteraction.collectIsFocusedAsState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ComposeColor(0x22FFFFFF))
                        .border(
                            width = if (fieldFocused) 2.dp else 1.dp,
                            color = if (fieldFocused) TvColors.focused else ComposeColor(0x33FFFFFF),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = customText,
                        onValueChange = onCustomTextChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = ComposeColor.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        cursorBrush = SolidColor(TvColors.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { keyboard?.hide() },
                        ),
                        interactionSource = fieldInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(customFieldRequester),
                    )
                }
            }
            if (balance != null && balance < 10) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = context.getString(R.string.tv_prediction_not_enough_points),
                    fontSize = 12.sp,
                    color = ComposeColor(0xFFFF8A80),
                )
            }
        }
    }

    @Composable
    private fun PresetChip(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "chipScale")
        val bg = when {
            focused -> TvColors.focused
            selected -> TvColors.accent
            else -> ComposeColor(0x22FFFFFF)
        }
        val fg = when {
            focused -> TvColors.onFocused
            selected -> ComposeColor(TvAccent.onAccent)
            else -> ComposeColor.White
        }
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }

    @Composable
    private fun PredictionOutcomeRow(
        title: String,
        pointsText: String,
        usersText: String,
        ratioText: String,
        pct: Int,
        fraction: Float,
        accentColor: ComposeColor,
        isMyOutcome: Boolean,
        isLockedOrBlocked: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FocusRow(
            modifier = modifier.graphicsLayer { alpha = if (isLockedOrBlocked && !isMyOutcome) 0.65f else 1f },
            onClick = onClick,
        ) { focused ->
            val fg = if (focused) TvColors.onFocused else ComposeColor.White
            val sub = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else TvColors.secondaryText
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accentColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isMyOutcome) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(R.drawable.tv_ic_check),
                            contentDescription = null,
                            tint = if (focused) TvColors.onFocused else accentColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$pct%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = fg,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (focused) ComposeColor(0x28000000) else ComposeColor(0x28FFFFFF)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(accentColor),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$pointsText · $usersText",
                        fontSize = 12.sp,
                        color = sub,
                    )
                    Text(
                        text = ratioText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) TvColors.onFocused else accentColor,
                    )
                }
            }
        }
    }
}
