package com.github.andreyasadchy.xtra.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.tv.ButtonRow
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import androidx.compose.ui.graphics.Color as ComposeColor

/** What is saved for each way of logging in ([shown] changes each time the page comes back, to put the focus again). */
data class TvLoginStatus(val code: Boolean, val page: Boolean, val username: String?, val shown: Int = 0)

/**
 * TV: the page of the Twitch account, the two ways of logging in side by side with what each one gives and lacks,
 * whether it is done, and a button to do it (or do it again) without losing the other one.
 */
@Composable
fun TvLoginScreen(
    status: TvLoginStatus,
    onCode: () -> Unit,
    onPage: () -> Unit,
    onLogout: () -> Unit,
    onDone: () -> Unit,
) {
    val codeRequester = remember { FocusRequester() }
    val pageRequester = remember { FocusRequester() }
    val doneRequester = remember { FocusRequester() }
    LaunchedEffect(status) {
        runCatching {
            when {
                !status.code && !status.page -> codeRequester
                !status.page -> pageRequester
                !status.code -> codeRequester
                else -> doneRequester
            }.requestFocus()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF0E0D12))
            .padding(horizontal = 56.dp, vertical = 24.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tv_login_title), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (status.code || status.page) {
                            stringResource(R.string.tv_login_status_user, status.username ?: "?")
                        } else {
                            stringResource(R.string.tv_login_status_none)
                        },
                        fontSize = 16.sp,
                        color = ComposeColor.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tv_login_both_hint), fontSize = 14.sp, color = TvColors.secondaryText)
                }
                Spacer(Modifier.width(24.dp))
                if (status.code || status.page) {
                    ButtonRow(stringResource(R.string.log_out), Modifier.width(200.dp), onLogout)
                    Spacer(Modifier.width(12.dp))
                }
                ButtonRow(stringResource(R.string.tv_login_done_button), Modifier.width(200.dp).focusRequester(doneRequester), onDone)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MethodCard(
                    title = stringResource(R.string.tv_login_code_title),
                    gives = stringResource(R.string.tv_login_code_gives),
                    misses = stringResource(R.string.tv_login_code_misses),
                    connected = status.code,
                    requester = codeRequester,
                    onClick = onCode,
                    modifier = Modifier.weight(1f)
                )
                MethodCard(
                    title = stringResource(R.string.tv_login_page_title),
                    gives = stringResource(R.string.tv_login_page_gives),
                    misses = stringResource(R.string.tv_login_page_misses),
                    connected = status.page,
                    requester = pageRequester,
                    onClick = onPage,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MethodCard(
    title: String,
    gives: String,
    misses: String,
    connected: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TvColors.item)
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (connected) ComposeColor(0xFF1E7B3C) else ComposeColor(0x33FFFFFF))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                stringResource(if (connected) R.string.tv_login_connected else R.string.tv_login_not_connected),
                fontSize = 14.sp,
                color = ComposeColor.White
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.tv_login_gives), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor(0xFF7EE29A))
        gives.split('\n').filter { it.isNotBlank() }.forEach {
            Text("+  $it", fontSize = 15.sp, color = ComposeColor.White, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.tv_login_misses), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor(0xFFFF9A8A))
        misses.split('\n').filter { it.isNotBlank() }.forEach {
            Text("–  $it", fontSize = 15.sp, color = ComposeColor.White, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.weight(1f).height(8.dp))
        ButtonRow(
            stringResource(if (connected) R.string.tv_login_redo else R.string.tv_login_connect),
            Modifier.focusRequester(requester).align(Alignment.CenterHorizontally),
            onClick
        )
    }
}
