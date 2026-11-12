package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Customizable remote shortcuts (#59):
 * - Per-direction D-pad actions (Left, Right, Up, Down, Down long-press) while player controls are hidden.
 * - Arbitrary custom remote key bindings (color buttons, Info, Guide, Channels, digits, gamepad, etc.).
 */
object TvRemoteKeys {

    const val ACTION_DEFAULT = "default"
    const val ACTION_SWITCH_PREV = "switch_prev"
    const val ACTION_SWITCH_NEXT = "switch_next"
    const val ACTION_CYCLE_CHAT_NEXT = "cycle_chat_next"
    const val ACTION_CYCLE_CHAT_PREV = "cycle_chat_prev"
    const val ACTION_TOGGLE_CHAT = "toggle_chat"
    const val ACTION_CHAT_PANEL = "chat_panel"
    const val ACTION_STREAM_SWITCHER = "stream_switcher"
    const val ACTION_PLAY_PAUSE = "play_pause"
    const val ACTION_MUTE = "mute"
    const val ACTION_QUALITY = "quality"
    const val ACTION_STATS = "stats"

    val ALL_ACTIONS = listOf(
        ACTION_DEFAULT,
        ACTION_SWITCH_PREV,
        ACTION_SWITCH_NEXT,
        ACTION_CYCLE_CHAT_NEXT,
        ACTION_CYCLE_CHAT_PREV,
        ACTION_TOGGLE_CHAT,
        ACTION_CHAT_PANEL,
        ACTION_STREAM_SWITCHER,
        ACTION_PLAY_PAUSE,
        ACTION_MUTE,
        ACTION_QUALITY,
        ACTION_STATS,
    )

    val CUSTOM_KEY_ACTIONS = ALL_ACTIONS.filter { it != ACTION_DEFAULT }

    data class CustomKeyBinding(val keyCode: Int, val action: String)

    fun ensureMigrated(context: Context) {
        val prefs = context.prefs()
        if (prefs.getBoolean(C.TV_DEFAULTS_REMOTE_MIGRATED, false)) {
            return
        }
        val legacySwitch = prefs.getBoolean(C.TV_DPAD_SWITCH_STREAM, false)
        val legacyUpDown = prefs.getBoolean(C.TV_DPAD_UP_DOWN, false)
        prefs.edit {
            if (!prefs.contains(C.TV_DPAD_LEFT_ACTION)) {
                putString(C.TV_DPAD_LEFT_ACTION, if (legacySwitch) ACTION_SWITCH_PREV else ACTION_DEFAULT)
            }
            if (!prefs.contains(C.TV_DPAD_RIGHT_ACTION)) {
                putString(C.TV_DPAD_RIGHT_ACTION, if (legacySwitch) ACTION_SWITCH_NEXT else ACTION_DEFAULT)
            }
            if (!prefs.contains(C.TV_DPAD_UP_ACTION)) {
                putString(C.TV_DPAD_UP_ACTION, if (legacyUpDown) ACTION_STREAM_SWITCHER else ACTION_DEFAULT)
            }
            if (!prefs.contains(C.TV_DPAD_DOWN_ACTION)) {
                putString(C.TV_DPAD_DOWN_ACTION, if (legacyUpDown) ACTION_TOGGLE_CHAT else ACTION_DEFAULT)
            }
            if (!prefs.contains(C.TV_DPAD_DOWN_LONG_ACTION)) {
                putString(C.TV_DPAD_DOWN_LONG_ACTION, ACTION_DEFAULT)
            }
            putBoolean(C.TV_DEFAULTS_REMOTE_MIGRATED, true)
        }
    }

    fun dpadAction(context: Context, keyPreference: String): String {
        ensureMigrated(context)
        val raw = context.prefs().getString(keyPreference, ACTION_DEFAULT) ?: ACTION_DEFAULT
        return if (raw in ALL_ACTIONS) raw else ACTION_DEFAULT
    }

    fun leftAction(context: Context): String = dpadAction(context, C.TV_DPAD_LEFT_ACTION)
    fun rightAction(context: Context): String = dpadAction(context, C.TV_DPAD_RIGHT_ACTION)
    fun upAction(context: Context): String = dpadAction(context, C.TV_DPAD_UP_ACTION)
    fun downAction(context: Context): String = dpadAction(context, C.TV_DPAD_DOWN_ACTION)
    fun downLongAction(context: Context): String = dpadAction(context, C.TV_DPAD_DOWN_LONG_ACTION)

    fun dpadForKeyCode(context: Context, keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> leftAction(context)
        KeyEvent.KEYCODE_DPAD_RIGHT -> rightAction(context)
        KeyEvent.KEYCODE_DPAD_UP -> upAction(context)
        KeyEvent.KEYCODE_DPAD_DOWN -> downAction(context)
        else -> ACTION_DEFAULT
    }

    fun actionLabel(context: Context, action: String): String = when (action) {
        ACTION_SWITCH_PREV -> context.getString(R.string.tv_remote_action_switch_prev)
        ACTION_SWITCH_NEXT -> context.getString(R.string.tv_remote_action_switch_next)
        ACTION_CYCLE_CHAT_NEXT -> context.getString(R.string.tv_remote_action_cycle_chat_next)
        ACTION_CYCLE_CHAT_PREV -> context.getString(R.string.tv_remote_action_cycle_chat_prev)
        ACTION_TOGGLE_CHAT -> context.getString(R.string.tv_remote_action_toggle_chat)
        ACTION_CHAT_PANEL -> context.getString(R.string.tv_remote_action_chat_panel)
        ACTION_STREAM_SWITCHER -> context.getString(R.string.tv_remote_action_stream_switcher)
        ACTION_PLAY_PAUSE -> context.getString(R.string.tv_remote_action_play_pause)
        ACTION_MUTE -> context.getString(R.string.tv_remote_action_mute)
        ACTION_QUALITY -> context.getString(R.string.tv_remote_action_quality)
        ACTION_STATS -> context.getString(R.string.tv_remote_action_stats)
        else -> context.getString(R.string.tv_remote_action_default)
    }

    fun keyLabel(context: Context, keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_PROG_RED -> context.getString(R.string.tv_key_red)
        KeyEvent.KEYCODE_PROG_GREEN -> context.getString(R.string.tv_key_green)
        KeyEvent.KEYCODE_PROG_YELLOW -> context.getString(R.string.tv_key_yellow)
        KeyEvent.KEYCODE_PROG_BLUE -> context.getString(R.string.tv_key_blue)
        KeyEvent.KEYCODE_INFO -> "Info"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_CAPTIONS -> context.getString(R.string.subtitles)
        KeyEvent.KEYCODE_CHANNEL_UP -> "CH +"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "CH −"
        KeyEvent.KEYCODE_PAGE_UP -> "Page +"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page −"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play / Pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "Next ⏭"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Prev ⏮"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "FF ⏩"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "RW ⏪"
        KeyEvent.KEYCODE_MENU -> "Menu"
        KeyEvent.KEYCODE_BOOKMARK -> "Bookmark"
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        else -> KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    fun loadCustomKeys(context: Context): List<CustomKeyBinding> {
        val raw = context.prefs().getString(C.TV_CUSTOM_KEYS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val code = obj.optInt("keyCode", 0)
                    val act = obj.optString("action", "")
                    if (code > 0 && act in CUSTOM_KEY_ACTIONS) {
                        add(CustomKeyBinding(code, act))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomKeys(context: Context, bindings: List<CustomKeyBinding>) {
        val arr = JSONArray()
        bindings.forEach { b ->
            arr.put(JSONObject().put("keyCode", b.keyCode).put("action", b.action))
        }
        context.prefs().edit {
            putString(C.TV_CUSTOM_KEYS, arr.toString())
        }
    }

    fun customActionForKey(context: Context, keyCode: Int): String? {
        if (keyCode <= 0) return null
        return loadCustomKeys(context).firstOrNull { it.keyCode == keyCode }?.action
    }

    fun dpadSummary(context: Context): String {
        ensureMigrated(context)
        val active = buildList {
            if (leftAction(context) != ACTION_DEFAULT) add("← ${actionLabel(context, leftAction(context))}")
            if (rightAction(context) != ACTION_DEFAULT) add("→ ${actionLabel(context, rightAction(context))}")
            if (upAction(context) != ACTION_DEFAULT) add("↑ ${actionLabel(context, upAction(context))}")
            if (downAction(context) != ACTION_DEFAULT) add("↓ ${actionLabel(context, downAction(context))}")
            if (downLongAction(context) != ACTION_DEFAULT) add("↓⏳ ${actionLabel(context, downLongAction(context))}")
        }
        return if (active.isEmpty()) {
            context.getString(R.string.tv_dpad_shortcuts_summary)
        } else {
            active.joinToString(" · ")
        }
    }

    fun customKeysSummary(context: Context): String {
        val list = loadCustomKeys(context)
        if (list.isEmpty()) {
            return context.getString(R.string.tv_custom_keys_summary)
        }
        return list.joinToString(" · ") { "${keyLabel(context, it.keyCode)} → ${actionLabel(context, it.action)}" }
    }

    fun showDpadPanel(context: Context, onChanged: () -> Unit = {}) {
        ensureMigrated(context)
        val prefs = context.prefs()
        val labels = ALL_ACTIONS.map { actionLabel(context, it) }
        fun idxOf(action: String) = ALL_ACTIONS.indexOf(action).coerceAtLeast(0)
        TvDialogs.show(context) {
            val firstFocus = remember { FocusRequester() }
            var resetTick by remember { mutableIntStateOf(0) }
            SidePanel(
                title = context.getString(R.string.tv_dpad_shortcuts),
                subtitle = context.getString(R.string.tv_dpad_shortcuts_summary),
                width = 520.dp,
                scrollable = true,
            ) {
                key(resetTick) {
                    SelectorRow(
                        label = context.getString(R.string.tv_dpad_left),
                        options = labels,
                        selected = idxOf(leftAction(context)),
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { i ->
                        prefs.edit { putString(C.TV_DPAD_LEFT_ACTION, ALL_ACTIONS[i]) }
                        onChanged()
                    }
                    SelectorRow(
                        label = context.getString(R.string.tv_dpad_right),
                        options = labels,
                        selected = idxOf(rightAction(context)),
                    ) { i ->
                        prefs.edit { putString(C.TV_DPAD_RIGHT_ACTION, ALL_ACTIONS[i]) }
                        onChanged()
                    }
                    SelectorRow(
                        label = context.getString(R.string.tv_dpad_up),
                        options = labels,
                        selected = idxOf(upAction(context)),
                    ) { i ->
                        prefs.edit { putString(C.TV_DPAD_UP_ACTION, ALL_ACTIONS[i]) }
                        onChanged()
                    }
                    SelectorRow(
                        label = context.getString(R.string.tv_dpad_down),
                        options = labels,
                        selected = idxOf(downAction(context)),
                    ) { i ->
                        prefs.edit { putString(C.TV_DPAD_DOWN_ACTION, ALL_ACTIONS[i]) }
                        onChanged()
                    }
                    SelectorRow(
                        label = context.getString(R.string.tv_dpad_down_long),
                        options = labels,
                        selected = idxOf(downLongAction(context)),
                    ) { i ->
                        prefs.edit { putString(C.TV_DPAD_DOWN_LONG_ACTION, ALL_ACTIONS[i]) }
                        onChanged()
                    }
                    Spacer(Modifier.height(12.dp))
                    ButtonRow(label = context.getString(R.string.tv_reset)) {
                        prefs.edit {
                            putString(C.TV_DPAD_LEFT_ACTION, ACTION_DEFAULT)
                            putString(C.TV_DPAD_RIGHT_ACTION, ACTION_DEFAULT)
                            putString(C.TV_DPAD_UP_ACTION, ACTION_DEFAULT)
                            putString(C.TV_DPAD_DOWN_ACTION, ACTION_DEFAULT)
                            putString(C.TV_DPAD_DOWN_LONG_ACTION, ACTION_DEFAULT)
                        }
                        resetTick++
                        onChanged()
                    }
                }
            }
            LaunchedEffect(resetTick) {
                firstFocus.requestFocus()
            }
        }
    }

    fun showCustomKeysPanel(context: Context, onChanged: () -> Unit = {}) {
        TvDialogs.show(context) {
            val addFocus = remember { FocusRequester() }
            var bindings by remember { mutableStateOf(loadCustomKeys(context)) }
            fun updateBindings(next: List<CustomKeyBinding>) {
                bindings = next
                saveCustomKeys(context, next)
                onChanged()
            }
            SidePanel(
                title = context.getString(R.string.tv_custom_keys),
                subtitle = context.getString(R.string.tv_custom_keys_summary),
                width = 500.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ButtonRow(
                        label = context.getString(R.string.tv_custom_keys_add),
                        modifier = Modifier.focusRequester(addFocus),
                    ) {
                        showKeyCaptureDialog(context) { capturedKeyCode ->
                            val existingIdx = bindings.indexOfFirst { it.keyCode == capturedKeyCode }
                            val currentActionIdx = if (existingIdx >= 0) {
                                CUSTOM_KEY_ACTIONS.indexOf(bindings[existingIdx].action).coerceAtLeast(0)
                            } else 0
                            val options = CUSTOM_KEY_ACTIONS.map { actionLabel(context, it) }
                            TvDialogs.showChoices(
                                context = context,
                                title = keyLabel(context, capturedKeyCode),
                                options = options,
                                selected = currentActionIdx,
                            ) { selectedIdx ->
                                val chosen = CUSTOM_KEY_ACTIONS[selectedIdx]
                                val next = bindings.toMutableList()
                                if (existingIdx >= 0) {
                                    next[existingIdx] = CustomKeyBinding(capturedKeyCode, chosen)
                                } else {
                                    next.add(CustomKeyBinding(capturedKeyCode, chosen))
                                }
                                updateBindings(next)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(bindings, key = { _, item -> item.keyCode }) { index, item ->
                            FocusRow(
                                onClick = {
                                    val options = CUSTOM_KEY_ACTIONS.map { actionLabel(context, it) } +
                                        context.getString(R.string.delete)
                                    val currentIdx = CUSTOM_KEY_ACTIONS.indexOf(item.action).coerceAtLeast(0)
                                    TvDialogs.showChoices(
                                        context = context,
                                        title = keyLabel(context, item.keyCode),
                                        options = options,
                                        selected = currentIdx,
                                    ) { chosenIdx ->
                                        val next = bindings.toMutableList()
                                        if (chosenIdx == options.lastIndex) {
                                            next.removeAt(index)
                                        } else {
                                            next[index] = CustomKeyBinding(item.keyCode, CUSTOM_KEY_ACTIONS[chosenIdx])
                                        }
                                        updateBindings(next)
                                    }
                                }
                            ) { focused ->
                                val textColor = if (focused) TvColors.onFocused else ComposeColor.White
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (focused) ComposeColor(0x22000000) else ComposeColor(0x26FFFFFF))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            keyLabel(context, item.keyCode),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textColor,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        actionLabel(context, item.action),
                                        fontSize = 16.sp,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (bindings.isNotEmpty()) {
                            item(key = "reset_all") {
                                Spacer(Modifier.height(12.dp))
                                ButtonRow(label = context.getString(R.string.tv_reset)) {
                                    updateBindings(emptyList())
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                addFocus.requestFocus()
            }
        }
    }

    private fun isReservedNavigationKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> false
    }

    private fun isSystemIgnoredKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_UNKNOWN,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_TV_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP -> true
        else -> false
    }

    private fun showKeyCaptureDialog(context: Context, onKeyCaptured: (Int) -> Unit) {
        val hintState = mutableStateOf(context.getString(R.string.tv_custom_keys_press_hint))
        val dialog = object : ComponentDialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
            private var dropOpeningSelect = true

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val code = event.keyCode
                val isSelect = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (dropOpeningSelect && isSelect) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        dropOpeningSelect = false
                    }
                    return true
                }
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        dismiss()
                    }
                    return true
                }
                if (isSystemIgnoredKey(code)) {
                    return super.dispatchKeyEvent(event)
                }
                if (isReservedNavigationKey(code)) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        hintState.value = context.getString(R.string.tv_custom_keys_reserved_hint)
                    }
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dismiss()
                    onKeyCaptured(code)
                }
                return true
            }
        }
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.rgb(145, 71, 255))
        dialog.setContentView(ComposeView(context).apply {
            setContent {
                TvTheme(accent) {
                    val hint by hintState
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier
                                .width(460.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(TvColors.panel)
                                .padding(horizontal = 32.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                context.getString(R.string.tv_custom_keys_press_title),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ComposeColor.White,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                hint,
                                fontSize = 15.sp,
                                color = TvColors.secondaryText,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.show()
    }
}
