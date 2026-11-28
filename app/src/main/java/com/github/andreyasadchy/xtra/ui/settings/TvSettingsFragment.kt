package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Context.DEVICE_POLICY_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.DialogCustomProxyEditBinding
import com.github.andreyasadchy.xtra.databinding.DialogStreamProxyEditBinding
import com.github.andreyasadchy.xtra.databinding.DialogUpdateDownloadBinding
import com.github.andreyasadchy.xtra.databinding.DialogVideoSwapEditBinding
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.model.ui.StreamProxy
import com.github.andreyasadchy.xtra.model.ui.VideoSwap
import android.app.admin.DeviceAdminReceiver
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.settings.CustomProxySettingsViewModel.Companion.CustomProxySettingsViewModelFactory
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.ui.settings.StreamProxySettingsViewModel.Companion.StreamProxySettingsViewModelFactory
import com.github.andreyasadchy.xtra.ui.settings.VideoSwapSettingsViewModel.Companion.VideoSwapSettingsViewModelFactory
import com.github.andreyasadchy.xtra.ui.tv.ButtonRow
import com.github.andreyasadchy.xtra.ui.tv.FocusRow
import com.github.andreyasadchy.xtra.ui.tv.SidePanel
import com.github.andreyasadchy.xtra.ui.tv.TvAbout
import com.github.andreyasadchy.xtra.ui.tv.TvActionPanel
import com.github.andreyasadchy.xtra.ui.tv.TvAmbient
import com.github.andreyasadchy.xtra.ui.tv.TvBackup
import com.github.andreyasadchy.xtra.ui.tv.TvBlacklist
import com.github.andreyasadchy.xtra.ui.tv.TvClockSettings
import com.github.andreyasadchy.xtra.ui.tv.TvColors
import com.github.andreyasadchy.xtra.ui.tv.TvDialogs
import com.github.andreyasadchy.xtra.ui.tv.TvExperimentalAdblock
import com.github.andreyasadchy.xtra.ui.tv.TvFavorites
import com.github.andreyasadchy.xtra.ui.tv.TvListPanel
import com.github.andreyasadchy.xtra.ui.tv.TvMultiStreamQuality
import com.github.andreyasadchy.xtra.ui.tv.TvProxyPanel
import com.github.andreyasadchy.xtra.ui.tv.TvRemoteKeys
import com.github.andreyasadchy.xtra.ui.tv.TvSwitcherRows
import com.github.andreyasadchy.xtra.ui.tv.TvTheme
import com.github.andreyasadchy.xtra.ui.tv.TvUpdateDialog
import com.github.andreyasadchy.xtra.ui.tv.home.TvHomeRows
import com.github.andreyasadchy.xtra.ui.view.TvVoice
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.UpdateInstallHelper
import com.github.andreyasadchy.xtra.util.canInstallUnknownAppsCompat
import com.github.andreyasadchy.xtra.util.chat.ChatBlocklist
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.openUnknownAppSourcesSettings
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.chromium.net.CronetProvider
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified 2-column Google TV Compose Settings page integrated inside [MainActivity]'s NavHostFragment.
 * Supports hover-preview from the side rail without stealing focus, instant category hover switching,
 * search across all TV settings, sub-screens, and live updates to MainActivity.
 */
class TvSettingsFragment : Fragment() {

    private val settingsViewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }
    private val customProxyViewModel: CustomProxySettingsViewModel by viewModels { CustomProxySettingsViewModelFactory }
    private val streamProxyViewModel: StreamProxySettingsViewModel by viewModels { StreamProxySettingsViewModelFactory }
    private val videoSwapViewModel: VideoSwapSettingsViewModel by viewModels { VideoSwapSettingsViewModelFactory }

    private val focusLeftTrigger = mutableIntStateOf(0)
    private val focusRightTrigger = mutableIntStateOf(0)
    private var handledFocusRightTrigger = 0
    private val refreshStamp = mutableIntStateOf(0)
    private val customProxyCountState = mutableIntStateOf(0)

    private val selectedCategoryIndex = mutableIntStateOf(0)
    private val subScreenStack = mutableStateListOf<SubScreen>()
    private val searchQueryState = mutableStateOf("")
    private val returnFocusKeyState = mutableStateOf<String?>(null)
    private val rightColumnFocusedState = mutableStateOf(false)
    private val editingSliderKeyState = mutableStateOf<String?>(null)

    private var restoreResultLauncher: ActivityResultLauncher<Intent>? = null
    private var voiceSearchLauncher: ActivityResultLauncher<Intent>? = null
    private var updateDownloadDialogBinding: DialogUpdateDownloadBinding? = null
    private var updateDownloadDialog: AlertDialog? = null

    fun canHandleTvBackPress(): Boolean {
        return isAdded && !isHidden && (
            editingSliderKeyState.value != null ||
                subScreenStack.isNotEmpty() ||
                searchQueryState.value.isNotBlank() ||
                rightColumnFocusedState.value
            )
    }

    fun handleTvBackPress(): Boolean {
        if (editingSliderKeyState.value != null) {
            editingSliderKeyState.value = null
            updateBackCallback()
            return true
        }
        if (subScreenStack.isNotEmpty()) {
            val popped = subScreenStack.removeAt(subScreenStack.lastIndex)
            returnFocusKeyState.value = popped.parentItemKey
            refreshStamp.intValue++
            updateBackCallback()
            focusRightTrigger.intValue++
            return true
        }
        if (searchQueryState.value.isNotBlank()) {
            searchQueryState.value = ""
            returnFocusKeyState.value = null
            rightColumnFocusedState.value = false
            updateBackCallback()
            focusLeftTrigger.intValue++
            return true
        }
        if (rightColumnFocusedState.value) {
            returnFocusKeyState.value = null
            rightColumnFocusedState.value = false
            updateBackCallback()
            focusLeftTrigger.intValue++
            return true
        }
        return false
    }

    private val subScreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val act = activity as? MainActivity
            if (act?.isTvMenuOpen() == true) {
                isEnabled = false
                act.onBackPressedDispatcher.onBackPressed()
                return
            }
            if (!handleTvBackPress()) {
                isEnabled = false
                act?.onBackPressedDispatcher?.onBackPressed()
            }
        }
    }

    sealed interface SubScreen {
        val title: String
        val parentItemKey: String?

        data class Xml(
            val xmlRes: Int,
            override val title: String,
            val highlightKey: String? = null,
            override val parentItemKey: String? = null,
        ) : SubScreen
        data class CustomProxies(override val title: String, override val parentItemKey: String? = null) : SubScreen
        data class StreamProxies(override val title: String, override val parentItemKey: String? = null) : SubScreen
        data class VideoSwaps(override val title: String, override val parentItemKey: String? = null) : SubScreen
    }

    private data class CategorySpec(
        val xmlRes: Int,
        val titleRes: Int,
        val iconRes: Int,
    )

    private sealed interface PrefNode {
        data class Header(val title: String) : PrefNode
        data class Item(
            val key: String,
            val title: String,
            val summary: String?,
            val valueBadge: String? = null,
            val iconRes: Int,
            val enabled: Boolean,
            val disabledHint: (() -> String)?,
            val kind: Kind,
            val locationBreadcrumb: String? = null,
            val sourceSubScreen: SubScreen? = null,
        ) : PrefNode
    }

    private sealed interface Kind {
        data class Toggle(val checked: Boolean) : Kind
        data class Choice(
            val value: String?,
            val entries: List<String>,
            val entryValues: List<String>,
            val colors: List<Int?>? = null,
            val subtitle: String? = null,
        ) : Kind
        data class MultiChoice(
            val values: Set<String>,
            val entries: List<String>,
            val entryValues: List<String>,
        ) : Kind
        data class Slider(val value: Int, val min: Int, val max: Int, val step: Int) : Kind
        data class TextInput(
            val currentValue: String,
            val isNumeric: Boolean = false,
            val useTokenPrefs: Boolean = false,
            val tokenKey: String? = null,
        ) : Kind
        data class Action(val hasChevron: Boolean = false) : Kind
    }

    fun requestContentFocus() {
        if (subScreenStack.isNotEmpty() || rightColumnFocusedState.value) {
            focusRightTrigger.intValue++
        } else {
            focusLeftTrigger.intValue++
        }
    }

    fun isRightColumnFocused(): Boolean = rightColumnFocusedState.value

    private fun updateBackCallback() {
        subScreenBackCallback.isEnabled = isResumed && !isHidden &&
            (editingSliderKeyState.value != null || subScreenStack.isNotEmpty() || searchQueryState.value.isNotBlank() || rightColumnFocusedState.value)
        (activity as? MainActivity)?.refreshTvMiniPlayerPosition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val list = mutableListOf<String>()
                result.data?.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i)?.uri?.let { list.add(it.toString()) }
                    }
                } ?: result.data?.data?.let { list.add(it.toString()) }
                settingsViewModel.restoreSettings(
                    list = list,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                )
            }
        }
        voiceSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            TvVoice.getText(result)?.takeIf { it.isNotBlank() }?.let { spoken ->
                subScreenStack.clear()
                searchQueryState.value = spoken.trim()
                updateBackCallback()
                focusRightTrigger.intValue++
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        TvExperimentalAdblock.normalizePrefs(context)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, subScreenBackCallback)
        consumePendingSubScreen(context)
        val act = activity as? MainActivity
        if (!isHidden && act?.isTvMenuOpen() != true && act?.isTvPlayerOverContent() != true) {
            requestContentFocus()
        }
        val accent = MaterialColors.getColor(
            context,
            androidx.appcompat.R.attr.colorPrimary,
            android.graphics.Color.rgb(145, 71, 255),
        )
        return ComposeView(context).apply {
            id = R.id.tvSettingsRoot
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TvTheme(accent) {
                    SettingsScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reloadProxyCount()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.updateUrl.collectLatest { url ->
                    if (url != null) {
                        UpdateInstallHelper.lastAttemptedUpdateUrl = url
                        val startUpdate: () -> Unit = {
                            UpdateInstallHelper.lastAttemptedUpdateUrl = url
                            if (requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false)) {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                        addCategory(Intent.CATEGORY_BROWSABLE)
                                    })
                                    requireContext().tokenPrefs().edit {
                                        putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                    }
                                } catch (_: ActivityNotFoundException) {
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.no_updates_found, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                UpdateInstallHelper.startUpdate(requireActivity(), url) { u -> startUpdateDownload(u) }
                            }
                        }
                        TvUpdateDialog.show(requireActivity(), settingsViewModel.updateVersion, settingsViewModel.updateNotes, startUpdate) {}
                    } else {
                        com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(requireContext(), R.string.no_updates_found, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.updateProgress.collectLatest { bytes ->
                    updateDownloadDialogBinding?.let { binding ->
                        val size = settingsViewModel.updateSize
                        if (size != null && size > 0L) {
                            binding.textView.text = getString(
                                R.string.downloading_update_progress,
                                Formatter.formatFileSize(requireContext(), bytes.toLong()),
                                Formatter.formatFileSize(requireContext(), size),
                            )
                            binding.progressBar.progress = ((bytes.toFloat() / size) * 100).toInt()
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.closeUpdateDialog.collectLatest {
                    updateDownloadDialog?.dismiss()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.updateInstallApk.collectLatest { apk ->
                    UpdateInstallHelper.openApkWithInstaller(requireActivity(), apk)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        context?.let { ctx ->
            TvExperimentalAdblock.normalizePrefs(ctx)
            consumePendingSubScreen(ctx)
            UpdateInstallHelper.takePendingUpdate(ctx)?.let { startUpdateDownload(it) }
        }
        reloadProxyCount()
        refreshStamp.intValue++
        updateBackCallback()
    }

    override fun onPause() {
        super.onPause()
        updateBackCallback()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateBackCallback()
        if (hidden) {
            (activity as? MainActivity)?.consumePendingTvRecreate()
        }
    }

    fun consumePendingSubScreen(context: Context) {
        val pending = pendingOpenSubScreen ?: return
        pendingOpenSubScreen = null
        searchQueryState.value = ""
        subScreenStack.clear()
        when (pending) {
            SettingsActivity.OPEN_CUSTOM_PROXIES -> {
                selectedCategoryIndex.intValue = CATEGORIES.indexOfFirst { it.xmlRes == R.xml.tv_settings_ads }.coerceAtLeast(0)
                customProxyViewModel.getProxies(context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP))
                subScreenStack.add(SubScreen.CustomProxies(context.getString(R.string.custom_proxy_settings)))
            }
            SettingsActivity.OPEN_STREAM_PROXIES -> {
                selectedCategoryIndex.intValue = CATEGORIES.indexOfFirst { it.xmlRes == R.xml.tv_settings_ads }.coerceAtLeast(0)
                streamProxyViewModel.getProxies()
                subScreenStack.add(SubScreen.StreamProxies(context.getString(R.string.proxy_settings)))
            }
        }
        updateBackCallback()
    }

    private fun reloadProxyCount() {
        val act = activity ?: return
        val repo = (act.application as XtraApp).xtraModule.playerRepository
        viewLifecycleOwner.lifecycleScope.launch {
            customProxyCountState.intValue = runCatching { repo.getCustomProxies().size }.getOrDefault(0)
        }
    }

    private fun startUpdateDownload(url: String) {
        val binding = DialogUpdateDownloadBinding.inflate(layoutInflater)
        updateDownloadDialogBinding = binding
        val size = settingsViewModel.updateSize
        if (size != null) {
            binding.textView.text = getString(
                R.string.downloading_update_progress,
                Formatter.formatFileSize(requireContext(), 0),
                Formatter.formatFileSize(requireContext(), size),
            )
        } else {
            binding.textView.text = getString(R.string.downloading_update)
            binding.progressBar.visibility = View.GONE
        }
        settingsViewModel.downloadUpdate(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), url)
        updateDownloadDialog = requireActivity().getAlertDialogBuilder()
            .setView(binding.root)
            .setNegativeButton(getString(android.R.string.cancel), null)
            .setOnDismissListener {
                settingsViewModel.updateJob?.cancel()
                updateDownloadDialogBinding = null
                updateDownloadDialog = null
            }
            .show()
    }

    @Composable
    private fun SettingsScreen() {
        val context = LocalContext.current
        val stamp = refreshStamp.intValue
        val proxyCount = customProxyCountState.intValue
        val catIdx = selectedCategoryIndex.intValue
        val activeSub = subScreenStack.lastOrNull()
        val query = searchQueryState.value
        val returnKey = returnFocusKeyState.value

        val leftCategoryRequesters = remember { List(CATEGORIES.size) { FocusRequester() } }
        val searchRequester = remember { FocusRequester() }
        val micRequester = remember { FocusRequester() }
        val rightFirstRequester = remember { FocusRequester() }
        var focusedLeftSlot by remember { mutableIntStateOf(catIdx) }
        var focusedOnMic by remember { mutableStateOf(false) }

        LaunchedEffect(focusLeftTrigger.intValue) {
            if (focusLeftTrigger.intValue > 0) {
                runCatching { leftCategoryRequesters.getOrNull(catIdx)?.requestFocus() }
            }
        }

        val returnToLeftColumn: () -> Unit = {
            rightColumnFocusedState.value = false
            updateBackCallback()
            runCatching { leftCategoryRequesters.getOrNull(catIdx)?.requestFocus() }
        }

        val hasWallpaper = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.isActiveReactive(context)
        val settingsPanelBg = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.settingsPanelColor(context, Color(0xD913121C))

        Row(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { ev ->
                    ev.type == KeyEventType.KeyDown && (
                        ev.key == Key.DirectionUp ||
                            ev.key == Key.DirectionDown ||
                            ev.key == Key.DirectionLeft ||
                            ev.key == Key.DirectionRight
                        )
                }
                .padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Left Column: Title, Search, 9 Categories
            Column(
                modifier = Modifier
                    .width(if (hasWallpaper) 280.dp else 264.dp)
                    .fillMaxHeight()
                    .then(
                        if (hasWallpaper) {
                            val leftShape = RoundedCornerShape(24.dp)
                            Modifier
                                .clip(leftShape)
                                .background(settingsPanelBg)
                                .background(com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.cardGlassSheenBrush)
                                .border(1.dp, com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.cardGlassRimBrush, leftShape)
                                .padding(horizontal = 12.dp, vertical = 14.dp)
                        } else {
                            Modifier
                        }
                    )
                    .onFocusChanged {
                        if (it.hasFocus) {
                            rightColumnFocusedState.value = false
                            updateBackCallback()
                        }
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionLeft -> {
                                if (focusedOnMic) {
                                    runCatching { searchRequester.requestFocus() }
                                } else {
                                    (activity as? MainActivity)?.openTvMenu()
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (focusedLeftSlot == -2 && !focusedOnMic) {
                                    runCatching { micRequester.requestFocus() }
                                } else {
                                    focusRightTrigger.intValue++
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                when {
                                    focusedLeftSlot > 0 -> runCatching { leftCategoryRequesters[focusedLeftSlot - 1].requestFocus() }
                                    focusedLeftSlot == 0 -> runCatching { searchRequester.requestFocus() }
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                when {
                                    focusedLeftSlot == -2 -> runCatching { leftCategoryRequesters.firstOrNull()?.requestFocus() }
                                    focusedLeftSlot in 0 until CATEGORIES.lastIndex -> runCatching {
                                        leftCategoryRequesters[focusedLeftSlot + 1].requestFocus()
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_settings_black_24),
                        contentDescription = null,
                        tint = TvColors.accent,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = context.getString(R.string.settings),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                            fontSize = 11.sp,
                            color = TvColors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Search pill + Voice button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchPill(
                        query = query,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchRequester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusedLeftSlot = -2
                                    focusedOnMic = false
                                }
                            },
                        onClick = { showSearchPrompt(context) },
                    )
                    val micMod = Modifier
                        .focusRequester(micRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusedLeftSlot = -2
                                focusedOnMic = true
                            }
                        }
                    if (query.isNotBlank()) {
                        SmallIconPill(
                            iconRes = R.drawable.tv_ic_close,
                            modifier = micMod,
                            onClick = {
                                searchQueryState.value = ""
                                returnFocusKeyState.value = null
                                updateBackCallback()
                            },
                        )
                    } else {
                        SmallIconPill(
                            iconRes = R.drawable.tv_ic_mic,
                            modifier = micMod,
                            onClick = {
                                runCatching {
                                    voiceSearchLauncher?.launch(TvVoice.createIntent(context.getString(R.string.search)))
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    CATEGORIES.forEachIndexed { index, cat ->
                        val isSelected = query.isBlank() && index == catIdx
                        CategoryRow(
                            title = context.getString(cat.titleRes),
                            iconRes = cat.iconRes,
                            selected = isSelected,
                            requester = leftCategoryRequesters[index],
                            onFocused = {
                                focusedLeftSlot = index
                                focusedOnMic = false
                            },
                            onHoverSelect = {
                                if (selectedCategoryIndex.intValue != index || searchQueryState.value.isNotBlank()) {
                                    selectedCategoryIndex.intValue = index
                                    subScreenStack.clear()
                                    returnFocusKeyState.value = null
                                    searchQueryState.value = ""
                                    updateBackCallback()
                                }
                            },
                            onClick = {
                                selectedCategoryIndex.intValue = index
                                subScreenStack.clear()
                                returnFocusKeyState.value = null
                                searchQueryState.value = ""
                                updateBackCallback()
                                focusRightTrigger.intValue++
                            },
                        )
                    }
                }
            }

            // Right Column: Active SubScreen, Search Results, or Category Preferences
            val subBreadcrumb = if (activeSub != null) {
                buildList {
                    add(context.getString(CATEGORIES[catIdx].titleRes))
                    subScreenStack.dropLast(1).forEach { add(it.title) }
                }.joinToString("  ›  ")
            } else null

            val rightShape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged {
                        if (it.hasFocus) {
                            rightColumnFocusedState.value = true
                            updateBackCallback()
                        }
                    }
                    .clip(rightShape)
                    .background(if (hasWallpaper) settingsPanelBg else Color(0x12FFFFFF))
                    .then(
                        if (hasWallpaper) {
                            Modifier
                                .background(com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.cardGlassSheenBrush)
                                .border(1.dp, com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.cardGlassRimBrush, rightShape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp),
            ) {
                when (activeSub) {
                    is SubScreen.CustomProxies -> CustomProxiesSubScreen(
                        title = activeSub.title,
                        parentBreadcrumb = subBreadcrumb,
                        firstRequester = rightFirstRequester,
                        onNavigateLeft = returnToLeftColumn,
                    )
                    is SubScreen.StreamProxies -> StreamProxiesSubScreen(
                        title = activeSub.title,
                        parentBreadcrumb = subBreadcrumb,
                        firstRequester = rightFirstRequester,
                        onNavigateLeft = returnToLeftColumn,
                    )
                    is SubScreen.VideoSwaps -> VideoSwapsSubScreen(
                        title = activeSub.title,
                        parentBreadcrumb = subBreadcrumb,
                        firstRequester = rightFirstRequester,
                        onNavigateLeft = returnToLeftColumn,
                    )
                    is SubScreen.Xml -> {
                        val nodes = remember(activeSub.xmlRes, stamp, proxyCount) {
                            buildNodesForXml(context, activeSub.xmlRes, proxyCount)
                        }
                        PreferenceNodesList(
                            headerTitle = activeSub.title,
                            parentBreadcrumb = subBreadcrumb,
                            nodes = nodes,
                            listKey = "sub_${activeSub.xmlRes}",
                            highlightKey = returnKey ?: activeSub.highlightKey,
                            firstRequester = rightFirstRequester,
                            onNavigateLeft = returnToLeftColumn,
                        )
                    }
                    null -> {
                        if (query.isNotBlank()) {
                            val results = remember(query, stamp, proxyCount) {
                                buildSearchResults(context, query, proxyCount)
                            }
                            PreferenceNodesList(
                                headerTitle = "${context.getString(R.string.search)} · \"$query\"",
                                parentBreadcrumb = null,
                                nodes = results,
                                listKey = "search_$query",
                                highlightKey = null,
                                firstRequester = rightFirstRequester,
                                onNavigateLeft = returnToLeftColumn,
                            )
                        } else {
                            val cat = CATEGORIES[catIdx]
                            val nodes = remember(cat.xmlRes, stamp, proxyCount) {
                                buildNodesForXml(context, cat.xmlRes, proxyCount)
                            }
                            PreferenceNodesList(
                                headerTitle = context.getString(cat.titleRes),
                                parentBreadcrumb = null,
                                nodes = nodes,
                                listKey = "cat_${cat.xmlRes}",
                                highlightKey = returnKey,
                                firstRequester = rightFirstRequester,
                                onNavigateLeft = returnToLeftColumn,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchPill(query: String, modifier: Modifier, onClick: () -> Unit) {
        val context = LocalContext.current
        val hasWallpaper = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.isActive(context)
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        focused -> TvColors.focused
                        hasWallpaper -> Color(0xCC1E1D2B)
                        else -> Color(0x1AFFFFFF)
                    }
                )
                .then(
                    if (hasWallpaper && !focused) Modifier.border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp)) else Modifier
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.baseline_search_black_24),
                contentDescription = null,
                tint = if (focused) TvColors.onFocused else TvColors.secondaryText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = query.ifBlank { context.getString(R.string.search) },
                fontSize = 13.sp,
                fontWeight = if (query.isNotBlank() || focused) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    focused -> TvColors.onFocused
                    query.isNotBlank() -> Color.White
                    else -> TvColors.secondaryText
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun SmallIconPill(iconRes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val context = LocalContext.current
        val hasWallpaper = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.isActive(context)
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier = modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    when {
                        focused -> TvColors.focused
                        hasWallpaper -> Color(0xCC1E1D2B)
                        else -> Color(0x1AFFFFFF)
                    }
                )
                .then(
                    if (hasWallpaper && !focused) Modifier.border(1.dp, Color(0x22FFFFFF), CircleShape) else Modifier
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (focused) TvColors.onFocused else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    @Composable
    private fun CategoryRow(
        title: String,
        iconRes: Int,
        selected: Boolean,
        requester: FocusRequester,
        onFocused: () -> Unit,
        onHoverSelect: () -> Unit,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        LaunchedEffect(focused) {
            if (focused) {
                delay(130)
                onHoverSelect()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .clip(RoundedCornerShape(11.dp))
                .background(
                    when {
                        focused -> TvColors.focused
                        selected -> Color(0x24FFFFFF)
                        else -> Color.Transparent
                    }
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = when {
                    focused -> TvColors.onFocused
                    selected -> TvColors.accent
                    else -> Color(0xCCFFFFFF)
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    focused -> TvColors.onFocused
                    selected -> Color.White
                    else -> Color(0xCCFFFFFF)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected && !focused) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TvColors.accent)
                )
            }
        }
    }

    @Composable
    private fun SubScreenHeaderTitle(headerTitle: String, parentBreadcrumb: String?, modifier: Modifier = Modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            if (!parentBreadcrumb.isNullOrBlank()) {
                Text(
                    text = "$parentBreadcrumb  ›  ",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TvColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = headerTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun PreferenceNodesList(
        headerTitle: String,
        parentBreadcrumb: String?,
        nodes: List<PrefNode>,
        listKey: String,
        highlightKey: String?,
        firstRequester: FocusRequester,
        onNavigateLeft: () -> Unit,
    ) {
        androidx.compose.runtime.key(listKey) {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val itemRequesters = remember { HashMap<String, FocusRequester>() }
            val emptyBoxRequester = remember { firstRequester }
            var lastFocusedId by remember { mutableStateOf<String?>(null) }

            val nodeIds = remember(nodes) {
                val counts = HashMap<String, Int>()
                nodes.map { node ->
                    when (node) {
                        is PrefNode.Header -> {
                            val base = "hdr_${node.title}"
                            val n = (counts[base] ?: 0) + 1
                            counts[base] = n
                            if (n == 1) base else "${base}_$n"
                        }
                        is PrefNode.Item -> {
                            val base = "itm_${node.key.ifBlank { node.title }}_${node.locationBreadcrumb.orEmpty()}"
                            val n = (counts[base] ?: 0) + 1
                            counts[base] = n
                            if (n == 1) base else "${base}_$n"
                        }
                    }
                }
            }

            fun requesterFor(id: String): FocusRequester = itemRequesters.getOrPut(id) { FocusRequester() }

            val firstInteractiveIndex = remember(nodes, highlightKey) {
                val highlighted = highlightKey?.let { hk ->
                    nodes.indexOfFirst { it is PrefNode.Item && it.key == hk }.takeIf { it >= 0 }
                }
                highlighted ?: nodes.indexOfFirst { it is PrefNode.Item }
            }

            suspend fun focusNodeAtIndex(targetIdx: Int) {
                if (targetIdx !in nodes.indices || nodes[targetIdx] !is PrefNode.Item) return
                val targetId = nodeIds[targetIdx]
                val req = requesterFor(targetId)
                if (listState.layoutInfo.visibleItemsInfo.any { it.index == targetIdx }) {
                    if (runCatching { req.requestFocus() }.getOrDefault(false)) return
                }
                runCatching { listState.scrollToItem((targetIdx - 1).coerceAtLeast(0)) }
                withFrameNanos { }
                if (!runCatching { req.requestFocus() }.getOrDefault(false)) {
                    delay(35)
                    runCatching { req.requestFocus() }
                }
            }

            LaunchedEffect(highlightKey) {
                if (highlightKey != null && firstInteractiveIndex > 0) {
                    runCatching { listState.scrollToItem((firstInteractiveIndex - 1).coerceAtLeast(0)) }
                }
            }

            LaunchedEffect(focusRightTrigger.intValue, nodes.size) {
                val trigger = focusRightTrigger.intValue
                if (trigger > handledFocusRightTrigger) {
                    if (nodes.none { it is PrefNode.Item }) {
                        handledFocusRightTrigger = trigger
                        delay(20)
                        runCatching { emptyBoxRequester.requestFocus() }
                    } else {
                        handledFocusRightTrigger = trigger
                        val targetIdx = when {
                            highlightKey != null -> nodes.indexOfFirst { it is PrefNode.Item && it.key == highlightKey }
                                .takeIf { it >= 0 }
                            lastFocusedId != null -> nodeIds.indexOf(lastFocusedId).takeIf { it >= 0 && nodes[it] is PrefNode.Item }
                            else -> null
                        } ?: listState.layoutInfo.visibleItemsInfo
                            .map { it.index }
                            .firstOrNull { it in nodes.indices && nodes[it] is PrefNode.Item }
                            ?: firstInteractiveIndex
                        if (returnFocusKeyState.value != null && highlightKey == returnFocusKeyState.value) {
                            returnFocusKeyState.value = null
                        }
                        focusNodeAtIndex(targetIdx)
                    }
                }
            }

            Column(Modifier.fillMaxSize()) {
                SubScreenHeaderTitle(headerTitle = headerTitle, parentBreadcrumb = parentBreadcrumb)
                Spacer(Modifier.height(10.dp))
                if (nodes.none { it is PrefNode.Item }) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(emptyBoxRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                    onNavigateLeft()
                                    true
                                } else false
                            }
                            .focusable(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("—", fontSize = 15.sp, color = TvColors.secondaryText)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        itemsIndexed(
                            items = nodes,
                            key = { idx, _ -> nodeIds[idx] },
                        ) { index, node ->
                            when (node) {
                                is PrefNode.Header -> {
                                    Text(
                                        text = node.title.uppercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp,
                                        color = TvColors.accent,
                                        modifier = Modifier.padding(
                                            start = 6.dp,
                                            top = if (index == 0) 2.dp else 12.dp,
                                            bottom = 2.dp,
                                        ),
                                    )
                                }
                                is PrefNode.Item -> {
                                    val itemId = nodeIds[index]
                                    val req = requesterFor(itemId)
                                    PreferenceItemCard(
                                        item = node,
                                        modifier = Modifier
                                            .focusRequester(req)
                                            .onFocusChanged { fs ->
                                                if (fs.isFocused) {
                                                    lastFocusedId = itemId
                                                }
                                            },
                                        onNavigateLeft = onNavigateLeft,
                                        onNavigateUp = {
                                            val prevIdx = (index - 1 downTo 0).firstOrNull { nodes[it] is PrefNode.Item }
                                            if (prevIdx == null) {
                                                if (index > 0) {
                                                    scope.launch { runCatching { listState.animateScrollToItem(0) } }
                                                }
                                            } else {
                                                val prevReq = requesterFor(nodeIds[prevIdx])
                                                val focusedImmediate = listState.layoutInfo.visibleItemsInfo.any { it.index == prevIdx } &&
                                                    runCatching { prevReq.requestFocus() }.getOrDefault(false)
                                                if (focusedImmediate) {
                                                    if (prevIdx <= 1 && prevIdx > 0) {
                                                        scope.launch { runCatching { listState.animateScrollToItem(0) } }
                                                    }
                                                } else {
                                                    scope.launch { focusNodeAtIndex(prevIdx) }
                                                }
                                            }
                                        },
                                        onNavigateDown = {
                                            val nextIdx = (index + 1..nodes.lastIndex).firstOrNull { nodes[it] is PrefNode.Item }
                                            if (nextIdx != null) {
                                                val nextReq = requesterFor(nodeIds[nextIdx])
                                                val focusedImmediate = listState.layoutInfo.visibleItemsInfo.any { it.index == nextIdx } &&
                                                    runCatching { nextReq.requestFocus() }.getOrDefault(false)
                                                if (!focusedImmediate) {
                                                    scope.launch {
                                                        runCatching {
                                                            listState.scrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(nextIdx))
                                                        }
                                                        withFrameNanos { }
                                                        if (!runCatching { nextReq.requestFocus() }.getOrDefault(false)) {
                                                            focusNodeAtIndex(nextIdx)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PreferenceItemCard(
        item: PrefNode.Item,
        modifier: Modifier,
        onNavigateLeft: () -> Unit,
        onNavigateUp: () -> Unit,
        onNavigateDown: () -> Unit,
    ) {
        val context = LocalContext.current
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val isEditingSlider = item.kind is Kind.Slider && editingSliderKeyState.value == item.key

        LaunchedEffect(focused) {
            if (!focused && editingSliderKeyState.value == item.key) {
                editingSliderKeyState.value = null
                updateBackCallback()
            }
        }

        val hasWallpaper = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.isActive(context)

        Row(
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (item.enabled) 1f else 0.48f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        focused -> TvColors.focused
                        hasWallpaper -> Color(0xCC1E1D2B)
                        else -> Color(0x14FFFFFF)
                    }
                )
                .then(
                    when {
                        isEditingSlider -> Modifier.border(2.dp, TvColors.accent, RoundedCornerShape(12.dp))
                        hasWallpaper && !focused -> Modifier.border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
                        else -> Modifier
                    }
                )
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            val slider = item.kind as? Kind.Slider
                            if (item.enabled && slider != null && isEditingSlider) {
                                if (slider.value > slider.min) {
                                    val next = (slider.value - slider.step).coerceAtLeast(slider.min)
                                    applySliderValue(context, item.key, next)
                                }
                            } else {
                                onNavigateLeft()
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            val slider = item.kind as? Kind.Slider
                            if (item.enabled && slider != null && isEditingSlider && slider.value < slider.max) {
                                val next = (slider.value + slider.step).coerceAtMost(slider.max)
                                applySliderValue(context, item.key, next)
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (isEditingSlider) {
                                editingSliderKeyState.value = null
                                updateBackCallback()
                            }
                            onNavigateUp()
                            true
                        }
                        Key.DirectionDown -> {
                            if (isEditingSlider) {
                                editingSliderKeyState.value = null
                                updateBackCallback()
                            }
                            onNavigateDown()
                            true
                        }
                        else -> false
                    }
                }
                .clickable(interactionSource = interaction, indication = null) {
                    if (!item.enabled) {
                        item.disabledHint?.invoke()?.let { msg ->
                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                        return@clickable
                    }
                    if (item.sourceSubScreen != null && subScreenStack.isEmpty() && searchQueryState.value.isNotBlank()) {
                        searchQueryState.value = ""
                        subScreenStack.add(item.sourceSubScreen)
                        updateBackCallback()
                        focusRightTrigger.intValue++
                        return@clickable
                    }
                    onPreferenceClicked(context, item)
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                tint = if (focused) TvColors.onFocused else TvColors.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            val inlineRightBadge = item.valueBadge?.takeIf { item.summary.isNullOrBlank() && it.length <= 26 && '\n' !in it }
            val subTitleBadge = item.valueBadge?.takeIf { inlineRightBadge == null }
            Column(Modifier.weight(1f)) {
                if (!item.locationBreadcrumb.isNullOrBlank()) {
                    Text(
                        text = item.locationBreadcrumb,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) TvColors.onFocused.copy(alpha = 0.7f) else TvColors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    lineHeight = 18.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) TvColors.onFocused else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subTitleBadge.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (focused) TvColors.onFocused.copy(alpha = 0.12f)
                                else TvColors.accent.copy(alpha = 0.18f)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = subTitleBadge,
                            fontSize = 11.sp,
                            lineHeight = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (focused) TvColors.onFocused else TvColors.accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!item.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (focused) TvColors.onFocused.copy(alpha = 0.30f)
                                    else TvColors.accent.copy(alpha = 0.48f)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.summary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = if (focused) TvColors.onFocused.copy(alpha = 0.78f) else Color(0xBDFFFFFF),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (item.kind is Kind.Slider) {
                    Spacer(Modifier.height(6.dp))
                    val frac = if (item.kind.max > item.kind.min) {
                        ((item.kind.value - item.kind.min).toFloat() / (item.kind.max - item.kind.min).toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        Modifier
                            .fillMaxWidth(0.75f)
                            .height(if (isEditingSlider) 7.dp else 5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (focused) TvColors.onFocused.copy(alpha = 0.22f) else Color(0x26FFFFFF))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .fillMaxHeight()
                                .background(if (isEditingSlider) TvColors.accent else if (focused) TvColors.onFocused else TvColors.accent)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (!inlineRightBadge.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 195.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (focused) TvColors.onFocused.copy(alpha = 0.12f)
                            else Color(0x1EFFFFFF)
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.key == C.TV_AMBIENT_COLOR && item.kind is Kind.Choice) {
                        val colorInt = TvAmbient.colorOf(item.kind.value ?: "violet")
                        if (colorInt != null) {
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorInt))
                                    .border(1.dp, if (focused) TvColors.onFocused else Color(0x66FFFFFF), CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text(
                        text = inlineRightBadge,
                        fontSize = 11.5.sp,
                        lineHeight = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) TvColors.onFocused else Color(0xEBFFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            when (val k = item.kind) {
                is Kind.Toggle -> TvListPanel.Switch(k.checked, focused)
                is Kind.Slider -> {
                    if (isEditingSlider) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(TvColors.accent)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "◀ ${k.value} ▶",
                                fontSize = 12.5.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    } else {
                        Text(
                            text = k.value.toString(),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (focused) TvColors.onFocused else TvColors.accent,
                        )
                    }
                }
                is Kind.Choice -> {
                    if (inlineRightBadge == null && item.key == C.TV_AMBIENT_COLOR) {
                        val colorInt = TvAmbient.colorOf(k.value ?: "violet")
                        if (colorInt != null) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorInt))
                                    .border(1.dp, if (focused) TvColors.onFocused else Color(0x66FFFFFF), CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text(
                        text = "›",
                        fontSize = 18.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                    )
                }
                is Kind.MultiChoice -> {
                    Text(
                        text = "›",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                    )
                }
                is Kind.TextInput -> {
                    Text(
                        text = "✎",
                        fontSize = 14.sp,
                        color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                    )
                }
                is Kind.Action -> {
                    if (k.hasChevron) {
                        Text(
                            text = "›",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (focused) TvColors.onFocused else TvColors.secondaryText,
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // Custom Proxies / Stream Proxies / Video Swaps Compose Sub-Screens
    // =========================================================================

    @Composable
    private fun CustomProxiesSubScreen(
        title: String,
        parentBreadcrumb: String?,
        firstRequester: FocusRequester,
        onNavigateLeft: () -> Unit,
    ) {
        val context = LocalContext.current
        val list by customProxyViewModel.list.collectAsState()
        val stamp = refreshStamp.intValue

        LaunchedEffect(focusRightTrigger.intValue) {
            val trigger = focusRightTrigger.intValue
            if (trigger > handledFocusRightTrigger) {
                handledFocusRightTrigger = trigger
                delay(25)
                if (!runCatching { firstRequester.requestFocus() }.getOrDefault(false)) {
                    delay(50)
                    runCatching { firstRequester.requestFocus() }
                }
            }
        }

        LaunchedEffect(Unit) {
            customProxyViewModel.statusChanged.collect {
                refreshStamp.intValue++
            }
        }

        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val rowRequesters = remember { HashMap<Int, FocusRequester>() }
        fun rowReq(idx: Int): FocusRequester = rowRequesters.getOrPut(idx) { FocusRequester() }
        fun focusRow(targetIdx: Int) {
            if (targetIdx !in list.indices) return
            scope.launch {
                val req = rowReq(targetIdx)
                if (!runCatching { req.requestFocus() }.getOrDefault(false)) {
                    runCatching { listState.scrollToItem(targetIdx) }
                    withFrameNanos { }
                    runCatching { req.requestFocus() }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SubScreenHeaderTitle(headerTitle = title, parentBreadcrumb = parentBreadcrumb, modifier = Modifier.weight(1f))
                ActionPillButton(
                    label = "+ ${context.getString(R.string.add_item)}",
                    modifier = Modifier
                        .focusRequester(firstRequester)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.DirectionLeft -> {
                                        onNavigateLeft(); true
                                    }
                                    Key.DirectionDown -> {
                                        if (list.isNotEmpty()) focusRow(0)
                                        true
                                    }
                                    Key.DirectionUp, Key.DirectionRight -> true
                                    else -> false
                                }
                            } else false
                        },
                ) {
                    val item = CustomProxy(position = list.size)
                    showCustomProxyEditDialog(item) { url, addQueryParams ->
                        if (!url.isNullOrBlank()) {
                            item.url = url.trim()
                            item.addQueryParams = addQueryParams
                            list.add(item)
                            customProxyViewModel.saveProxy(item)
                            customProxyViewModel.updateProxyStatus(context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), item.url)
                            reloadProxyCount()
                            refreshStamp.intValue++
                            (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(list.toList(), key = { idx, p -> if (p.id != 0) "cp_${p.id}" else "cp_new_$idx" }) { index, proxy ->
                    @Suppress("UNUSED_EXPRESSION")
                    stamp
                    val online = proxy.url?.let { customProxyViewModel.statusMap[it] }
                    val statusText = when (online) {
                        true -> "Online"
                        false -> "Offline"
                        null -> "…"
                    }
                    ManagedEntityRow(
                        title = proxy.url.orEmpty().ifBlank { "—" },
                        subtitle = statusText,
                        statusColor = when (online) {
                            true -> Color(0xFF4CAF50)
                            false -> Color(0xFFEF5350)
                            null -> TvColors.secondaryText
                        },
                        enabled = proxy.enabled,
                        canMoveUp = index > 0,
                        canMoveDown = index < list.lastIndex,
                        canDelete = true,
                        mainRowRequester = rowReq(index),
                        onNavigateUp = {
                            if (index == 0) runCatching { firstRequester.requestFocus() } else focusRow(index - 1)
                        },
                        onNavigateDown = {
                            if (index < list.lastIndex) focusRow(index + 1)
                        },
                        onNavigateLeft = onNavigateLeft,
                        onToggle = {
                            proxy.enabled = !proxy.enabled
                            customProxyViewModel.updateProxy(proxy)
                            refreshStamp.intValue++
                            (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                        },
                        onMoveUp = {
                            if (index > 0) {
                                Collections.swap(list, index, index - 1)
                                customProxyViewModel.updateProxies()
                                refreshStamp.intValue++
                            }
                        },
                        onMoveDown = {
                            if (index < list.lastIndex) {
                                Collections.swap(list, index, index + 1)
                                customProxyViewModel.updateProxies()
                                refreshStamp.intValue++
                            }
                        },
                        onEdit = {
                            showCustomProxyEditDialog(proxy) { url, addQueryParams ->
                                proxy.url = url?.trim()
                                proxy.addQueryParams = addQueryParams
                                customProxyViewModel.updateProxy(proxy)
                                customProxyViewModel.updateProxyStatus(context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), proxy.url)
                                refreshStamp.intValue++
                                (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                            }
                        },
                        onDelete = {
                            val del = context.getString(R.string.delete)
                            context.getAlertDialogBuilder()
                                .setTitle(del)
                                .setMessage(context.getString(R.string.delete_proxy_message))
                                .setPositiveButton(del) { _, _ ->
                                    list.remove(proxy)
                                    customProxyViewModel.deleteProxy(proxy)
                                    reloadProxyCount()
                                    refreshStamp.intValue++
                                    (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                                    if (list.isEmpty()) {
                                        runCatching { firstRequester.requestFocus() }
                                    } else {
                                        focusRow(index.coerceAtMost(list.lastIndex))
                                    }
                                }
                                .setNegativeButton(context.getString(android.R.string.cancel), null)
                                .show()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun StreamProxiesSubScreen(
        title: String,
        parentBreadcrumb: String?,
        firstRequester: FocusRequester,
        onNavigateLeft: () -> Unit,
    ) {
        val context = LocalContext.current
        val list by streamProxyViewModel.list.collectAsState()
        val stamp = refreshStamp.intValue
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val rowRequesters = remember { HashMap<Int, FocusRequester>() }
        fun rowReq(idx: Int): FocusRequester = rowRequesters.getOrPut(idx) { FocusRequester() }
        fun focusRow(targetIdx: Int) {
            if (targetIdx !in list.indices) return
            scope.launch {
                val req = rowReq(targetIdx)
                if (!runCatching { req.requestFocus() }.getOrDefault(false)) {
                    runCatching { listState.scrollToItem(targetIdx) }
                    withFrameNanos { }
                    runCatching { req.requestFocus() }
                }
            }
        }

        LaunchedEffect(focusRightTrigger.intValue) {
            val trigger = focusRightTrigger.intValue
            if (trigger > handledFocusRightTrigger) {
                handledFocusRightTrigger = trigger
                delay(25)
                if (!runCatching { firstRequester.requestFocus() }.getOrDefault(false)) {
                    delay(50)
                    runCatching { firstRequester.requestFocus() }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SubScreenHeaderTitle(headerTitle = title, parentBreadcrumb = parentBreadcrumb, modifier = Modifier.weight(1f))
                ActionPillButton(
                    label = "+ ${context.getString(R.string.add_item)}",
                    modifier = Modifier
                        .focusRequester(firstRequester)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.DirectionLeft -> {
                                        onNavigateLeft(); true
                                    }
                                    Key.DirectionDown -> {
                                        if (list.isNotEmpty()) focusRow(0)
                                        true
                                    }
                                    Key.DirectionUp, Key.DirectionRight -> true
                                    else -> false
                                }
                            } else false
                        },
                ) {
                    val item = StreamProxy(position = list.size)
                    showStreamProxyEditDialog(item) { host, port, user, pass, tok, multi, media ->
                        if (!host.isNullOrBlank()) {
                            item.host = host.trim()
                            item.port = port?.toIntOrNull()
                            item.username = user
                            item.password = pass
                            item.proxyPlaybackAccessToken = tok
                            item.proxyMultivariantPlaylist = multi
                            item.proxyMediaPlaylist = media
                            list.add(item)
                            streamProxyViewModel.saveProxy(item)
                            refreshStamp.intValue++
                            (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(list.toList(), key = { idx, p -> if (p.id != 0) "sp_${p.id}" else "sp_new_$idx" }) { index, proxy ->
                    @Suppress("UNUSED_EXPRESSION")
                    stamp
                    val label = com.github.andreyasadchy.xtra.util.StreamProxyHelper.formatDisplayLabel(proxy.host, proxy.port)
                    ManagedEntityRow(
                        title = label,
                        subtitle = proxy.username?.takeIf { it.isNotBlank() },
                        statusColor = null,
                        enabled = proxy.enabled,
                        canMoveUp = index > 0,
                        canMoveDown = index < list.lastIndex,
                        canDelete = true,
                        mainRowRequester = rowReq(index),
                        onNavigateUp = {
                            if (index == 0) runCatching { firstRequester.requestFocus() } else focusRow(index - 1)
                        },
                        onNavigateDown = {
                            if (index < list.lastIndex) focusRow(index + 1)
                        },
                        onNavigateLeft = onNavigateLeft,
                        onToggle = {
                            proxy.enabled = !proxy.enabled
                            streamProxyViewModel.updateProxy(proxy)
                            refreshStamp.intValue++
                            (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                        },
                        onMoveUp = {
                            if (index > 0) {
                                Collections.swap(list, index, index - 1)
                                streamProxyViewModel.updateProxies()
                                refreshStamp.intValue++
                            }
                        },
                        onMoveDown = {
                            if (index < list.lastIndex) {
                                Collections.swap(list, index, index + 1)
                                streamProxyViewModel.updateProxies()
                                refreshStamp.intValue++
                            }
                        },
                        onEdit = {
                            showStreamProxyEditDialog(proxy) { host, port, user, pass, tok, multi, media ->
                                proxy.host = host?.trim()
                                proxy.port = port?.toIntOrNull()
                                proxy.username = user
                                proxy.password = pass
                                proxy.proxyPlaybackAccessToken = tok
                                proxy.proxyMultivariantPlaylist = multi
                                proxy.proxyMediaPlaylist = media
                                streamProxyViewModel.updateProxy(proxy)
                                refreshStamp.intValue++
                                (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                            }
                        },
                        onDelete = {
                            val del = context.getString(R.string.delete)
                            context.getAlertDialogBuilder()
                                .setTitle(del)
                                .setMessage(context.getString(R.string.delete_proxy_message))
                                .setPositiveButton(del) { _, _ ->
                                    list.remove(proxy)
                                    streamProxyViewModel.deleteProxy(proxy)
                                    refreshStamp.intValue++
                                    (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                                    if (list.isEmpty()) {
                                        runCatching { firstRequester.requestFocus() }
                                    } else {
                                        focusRow(index.coerceAtMost(list.lastIndex))
                                    }
                                }
                                .setNegativeButton(context.getString(android.R.string.cancel), null)
                                .show()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun VideoSwapsSubScreen(
        title: String,
        parentBreadcrumb: String?,
        firstRequester: FocusRequester,
        onNavigateLeft: () -> Unit,
    ) {
        val context = LocalContext.current
        val list by videoSwapViewModel.list.collectAsState()
        val stamp = refreshStamp.intValue
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val rowRequesters = remember { HashMap<Int, FocusRequester>() }
        fun rowReq(idx: Int): FocusRequester = rowRequesters.getOrPut(idx) { FocusRequester() }
        fun focusRow(targetIdx: Int) {
            if (targetIdx !in list.indices) return
            scope.launch {
                val req = rowReq(targetIdx)
                if (!runCatching { req.requestFocus() }.getOrDefault(false)) {
                    runCatching { listState.scrollToItem(targetIdx) }
                    withFrameNanos { }
                    runCatching { req.requestFocus() }
                }
            }
        }

        LaunchedEffect(focusRightTrigger.intValue) {
            val trigger = focusRightTrigger.intValue
            if (trigger > handledFocusRightTrigger) {
                handledFocusRightTrigger = trigger
                delay(25)
                if (!runCatching { firstRequester.requestFocus() }.getOrDefault(false)) {
                    delay(50)
                    runCatching { firstRequester.requestFocus() }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SubScreenHeaderTitle(headerTitle = title, parentBreadcrumb = parentBreadcrumb, modifier = Modifier.weight(1f))
                ActionPillButton(
                    label = "+ ${context.getString(R.string.add_item)}",
                    modifier = Modifier
                        .focusRequester(firstRequester)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.DirectionLeft -> {
                                        onNavigateLeft(); true
                                    }
                                    Key.DirectionDown -> {
                                        if (list.isNotEmpty()) focusRow(0)
                                        true
                                    }
                                    Key.DirectionUp, Key.DirectionRight -> true
                                    else -> false
                                }
                            } else false
                        },
                ) {
                    val item = VideoSwap(position = list.size)
                    showVideoSwapEditDialog(item) { platform, playerType ->
                        item.platform = platform?.trim()
                        item.playerType = playerType?.trim()
                        list.add(item)
                        videoSwapViewModel.saveVideoSwap(item)
                        refreshStamp.intValue++
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(
                    list.toList(),
                    key = { idx, v ->
                        when {
                            v.position == -1 -> "vs_primary"
                            v.id != 0 -> "vs_${v.id}"
                            else -> "vs_new_$idx"
                        }
                    },
                ) { index, swap ->
                    @Suppress("UNUSED_EXPRESSION")
                    stamp
                    val isPrimary = swap.position == -1 || index == 0
                    ManagedEntityRow(
                        title = "${swap.platform.orEmpty()} / ${swap.playerType.orEmpty()}",
                        subtitle = null,
                        statusColor = null,
                        enabled = if (isPrimary) true else swap.enabled,
                        showToggle = !isPrimary,
                        canMoveUp = index > 1,
                        canMoveDown = !isPrimary && index < list.lastIndex,
                        canDelete = !isPrimary,
                        mainRowRequester = rowReq(index),
                        onNavigateUp = {
                            if (index == 0) runCatching { firstRequester.requestFocus() } else focusRow(index - 1)
                        },
                        onNavigateDown = {
                            if (index < list.lastIndex) focusRow(index + 1)
                        },
                        onNavigateLeft = onNavigateLeft,
                        onToggle = {
                            if (!isPrimary) {
                                swap.enabled = !swap.enabled
                                videoSwapViewModel.updateVideoSwap(swap)
                                refreshStamp.intValue++
                            }
                        },
                        onMoveUp = {
                            if (index > 1) {
                                Collections.swap(list, index, index - 1)
                                videoSwapViewModel.updateVideoSwapItems()
                                refreshStamp.intValue++
                            }
                        },
                        onMoveDown = {
                            if (!isPrimary && index < list.lastIndex) {
                                Collections.swap(list, index, index + 1)
                                videoSwapViewModel.updateVideoSwapItems()
                                refreshStamp.intValue++
                            }
                        },
                        onEdit = {
                            showVideoSwapEditDialog(swap) { platform, playerType ->
                                swap.platform = platform?.trim()
                                swap.playerType = playerType?.trim()
                                if (swap.position == -1) {
                                    context.prefs().edit {
                                        putString(C.TOKEN_PLATFORM, swap.platform)
                                        putString(C.TOKEN_PLAYER_TYPE, swap.playerType)
                                    }
                                } else {
                                    videoSwapViewModel.updateVideoSwap(swap)
                                }
                                refreshStamp.intValue++
                            }
                        },
                        onDelete = {
                            val del = context.getString(R.string.delete)
                            context.getAlertDialogBuilder()
                                .setTitle(del)
                                .setMessage(context.getString(R.string.delete_item_message))
                                .setPositiveButton(del) { _, _ ->
                                    list.remove(swap)
                                    videoSwapViewModel.deleteVideoSwap(swap)
                                    refreshStamp.intValue++
                                    if (list.isEmpty()) {
                                        runCatching { firstRequester.requestFocus() }
                                    } else {
                                        focusRow(index.coerceAtMost(list.lastIndex))
                                    }
                                }
                                .setNegativeButton(context.getString(android.R.string.cancel), null)
                                .show()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun ActionPillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) TvColors.focused else TvColors.accent.copy(alpha = 0.25f))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (focused) TvColors.onFocused else Color.White,
            )
        }
    }

    @Composable
    private fun ManagedEntityRow(
        title: String,
        subtitle: String?,
        statusColor: Color?,
        enabled: Boolean,
        showToggle: Boolean = true,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        canDelete: Boolean,
        mainRowRequester: FocusRequester,
        onNavigateUp: () -> Unit,
        onNavigateDown: () -> Unit,
        onNavigateLeft: () -> Unit,
        onToggle: () -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val moveUpReq = remember { FocusRequester() }
        val moveDownReq = remember { FocusRequester() }
        val editReq = remember { FocusRequester() }
        val deleteReq = remember { FocusRequester() }

        val slots = buildList {
            add(mainRowRequester)
            if (canMoveUp) add(moveUpReq)
            if (canMoveDown) add(moveDownReq)
            add(editReq)
            if (canDelete) add(deleteReq)
        }

        fun slotModifier(req: FocusRequester): Modifier = Modifier
            .focusRequester(req)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val pos = slots.indexOf(req)
                when (ev.key) {
                    Key.DirectionLeft -> {
                        if (pos <= 0) {
                            onNavigateLeft()
                        } else {
                            runCatching { slots[pos - 1].requestFocus() }
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (pos in 0 until slots.lastIndex) {
                            runCatching { slots[pos + 1].requestFocus() }
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        onNavigateUp()
                        true
                    }
                    Key.DirectionDown -> {
                        onNavigateDown()
                        true
                    }
                    else -> false
                }
            }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xB81E1D2B))
                .border(1.dp, Color(0x1CFFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(slotModifier(mainRowRequester))
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) TvColors.focused else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = null, onClick = if (showToggle) onToggle else onEdit)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (statusColor != null) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        lineHeight = 18.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (focused) TvColors.onFocused else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = if (focused) TvColors.onFocused.copy(alpha = 0.72f) else TvColors.secondaryText,
                        )
                    }
                }
                if (showToggle) {
                    Spacer(Modifier.width(8.dp))
                    TvListPanel.Switch(enabled, focused)
                }
            }
            if (canMoveUp) SmallTextChip("↑", slotModifier(moveUpReq), onMoveUp)
            if (canMoveDown) SmallTextChip("↓", slotModifier(moveDownReq), onMoveDown)
            SmallTextChip("✎", slotModifier(editReq), onEdit)
            if (canDelete) SmallIconPill(iconRes = R.drawable.tv_ic_close, modifier = slotModifier(deleteReq), onClick = onDelete)
        }
    }

    @Composable
    private fun SmallTextChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier = modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (focused) TvColors.focused else Color(0x1AFFFFFF))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (focused) TvColors.onFocused else Color.White)
        }
    }

    // =========================================================================
    // XML Preference Tree Builder & Action Handlers
    // =========================================================================

    private fun buildNodesForXml(context: Context, xmlRes: Int, proxyCount: Int): List<PrefNode> {
        TvExperimentalAdblock.normalizePrefs(context)
        val rawEntries = parseXmlEntries(context, xmlRes)
        val caps = deviceCaps(context)
        val result = ArrayList<PrefNode>(rawEntries.size * 4)

        for (entry in rawEntries) {
            when (entry) {
                is RawXmlEntry.HeaderGroup -> {
                    val children = ArrayList<PrefNode>(entry.children.size)
                    for (child in entry.children) {
                        mapRawPreference(context, child, xmlRes, proxyCount, caps)?.let { children.add(it) }
                    }
                    if (children.isNotEmpty()) {
                        if (entry.title != null) result.add(PrefNode.Header(entry.title))
                        result.addAll(children)
                    }
                }
                is RawXmlEntry.Single -> {
                    mapRawPreference(context, entry.item, xmlRes, proxyCount, caps)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    @SuppressLint("RestrictedApi")
    private fun buildSearchResults(context: Context, rawQuery: String, proxyCount: Int): List<PrefNode> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val seenKeys = HashSet<String>()
        val results = mutableListOf<PrefNode>()

        val xtraTitle = context.getString(R.string.xtratv_settings)
        val sources: List<Triple<Int, String, SubScreen?>> = listOf(
            Triple(R.xml.tv_settings_xtratv, xtraTitle, null),
            Triple(R.xml.tv_xtratv_customization, "$xtraTitle · ${context.getString(R.string.tv_xtratv_sub_customization_title)}", SubScreen.Xml(R.xml.tv_xtratv_customization, context.getString(R.string.tv_xtratv_sub_customization_title))),
            Triple(R.xml.tv_xtratv_home, "$xtraTitle · ${context.getString(R.string.tv_xtratv_sub_home_title)}", SubScreen.Xml(R.xml.tv_xtratv_home, context.getString(R.string.tv_xtratv_sub_home_title))),
            Triple(R.xml.tv_xtratv_remote, "$xtraTitle · ${context.getString(R.string.tv_xtratv_sub_remote_title)}", SubScreen.Xml(R.xml.tv_xtratv_remote, context.getString(R.string.tv_xtratv_sub_remote_title))),
            Triple(R.xml.tv_xtratv_player, "$xtraTitle · ${context.getString(R.string.tv_xtratv_sub_player_title)}", SubScreen.Xml(R.xml.tv_xtratv_player, context.getString(R.string.tv_xtratv_sub_player_title))),
            Triple(R.xml.tv_xtratv_previews, "$xtraTitle · ${context.getString(R.string.tv_xtratv_sub_previews_title)}", SubScreen.Xml(R.xml.tv_xtratv_previews, context.getString(R.string.tv_xtratv_sub_previews_title))),
            Triple(R.xml.tv_xtratv_languages, "$xtraTitle · ${context.getString(R.string.tv_xtratv_sub_languages_title)}", SubScreen.Xml(R.xml.tv_xtratv_languages, context.getString(R.string.tv_xtratv_sub_languages_title))),
            Triple(R.xml.tv_settings_player, context.getString(R.string.player_settings), null),
            Triple(R.xml.player_preferences, context.getString(R.string.player_settings), SubScreen.Xml(R.xml.player_preferences, context.getString(R.string.player_settings))),
            Triple(R.xml.playback_preferences, context.getString(R.string.playback_settings), SubScreen.Xml(R.xml.playback_preferences, context.getString(R.string.playback_settings))),
            Triple(R.xml.buffer_preferences, context.getString(R.string.buffer_settings), SubScreen.Xml(R.xml.buffer_preferences, context.getString(R.string.buffer_settings))),
            Triple(R.xml.player_button_preferences, context.getString(R.string.player_buttons), SubScreen.Xml(R.xml.player_button_preferences, context.getString(R.string.player_buttons))),
            Triple(R.xml.player_menu_preferences, context.getString(R.string.player_menu_settings), SubScreen.Xml(R.xml.player_menu_preferences, context.getString(R.string.player_menu_settings))),
            Triple(R.xml.chat_preferences, context.getString(R.string.chat_settings), null),
            Triple(R.xml.tv_settings_interface, context.getString(R.string.ui_settings), null),
            Triple(R.xml.ui_preferences, context.getString(R.string.ui_settings), SubScreen.Xml(R.xml.ui_preferences, context.getString(R.string.ui_settings))),
            Triple(R.xml.tv_settings_ads, context.getString(R.string.tv_settings_section_ads), null),
            Triple(R.xml.download_preferences, context.getString(R.string.download_settings), null),
            Triple(R.xml.tv_settings_updates, context.getString(R.string.update_settings), null),
            Triple(R.xml.update_preferences, context.getString(R.string.update_settings), SubScreen.Xml(R.xml.update_preferences, context.getString(R.string.update_settings))),
            Triple(R.xml.tv_settings_advanced, context.getString(R.string.tv_settings_section_advanced), null),
            Triple(R.xml.api_token_preferences, context.getString(R.string.api_token_settings), SubScreen.Xml(R.xml.api_token_preferences, context.getString(R.string.api_token_settings))),
            Triple(R.xml.debug_preferences, context.getString(R.string.debug_settings), SubScreen.Xml(R.xml.debug_preferences, context.getString(R.string.debug_settings))),
            Triple(R.xml.tv_settings_about, context.getString(R.string.tv_settings_section_about), null),
        )

        sources.forEach { (xmlRes, breadcrumb, sub) ->
            buildNodesForXml(context, xmlRes, proxyCount).filterIsInstance<PrefNode.Item>().forEach { item ->
                val dedupKey = "${item.key}:${sub?.title.orEmpty()}"
                if (item.key.isNotBlank() && seenKeys.contains(item.key)) return@forEach
                val matches = item.title.lowercase().contains(q) ||
                    item.summary.orEmpty().lowercase().contains(q) ||
                    item.valueBadge.orEmpty().lowercase().contains(q) ||
                    breadcrumb.lowercase().contains(q)
                if (matches && seenKeys.add(item.key.ifBlank { dedupKey })) {
                    val targetSub = (sub as? SubScreen.Xml)?.copy(highlightKey = item.key) ?: sub
                    results.add(item.copy(locationBreadcrumb = breadcrumb, sourceSubScreen = targetSub))
                }
            }
        }
        return results
    }

    private fun mapRawPreference(
        context: Context,
        raw: RawPrefItem,
        xmlRes: Int,
        proxyCount: Int,
        caps: DeviceCaps,
    ): PrefNode.Item? {
        val key = raw.key
        if (key in MaterialPreferenceFragment.TV_HIDDEN_KEYS) return null
        if (xmlRes == R.xml.adblock_preferences && key == "video_swap_settings") return null
        if (xmlRes == R.xml.debug_preferences && (key == "video_swap_settings" || key == C.PLAYER_USE_VIDEO_SWAP || key == C.PLAYER_HIDE_ADS)) return null
        if (!caps.hasPip) {
            if (key == C.PLAYER_PICTURE_IN_PICTURE || key == C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED || key == C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED) {
                return null
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && key == C.PLAYER_ROUNDED_CORNER_PADDING) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && (key == C.UI_DRAW_BEHIND_CUTOUTS || key == C.PLAYER_AUDIO_COMPRESSOR_BUTTON)) return null

        if (!caps.arm64 && (key == "chat_translate" || key == "downloaded_languages" || key == "chat_translate_target")) {
            return null
        }

        if (key == C.NETWORK_LIBRARY && !caps.httpEngine && !caps.cronet) return null

        val prefs = context.prefs()
        val expOn = prefs.getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false)
        val swapPlusOn = prefs.getBoolean(C.TV_ADBLOCK_SWAP_PLUS, false)
        val customProxyOn = prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
        val streamProxyOn = prefs.getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
        val anyProxyOn = !expOn && !swapPlusOn && (customProxyOn || streamProxyOn)

        var enabled = raw.isEnabled
        var disabledHint: (() -> String)? = null
        val depKey = raw.dependency
        if (!depKey.isNullOrBlank()) {
            enabled = enabled && prefs.getBoolean(depKey, false)
        }

        when (key) {
            C.PLAYER_ADBLOCK -> {
                enabled = anyProxyOn
                disabledHint = {
                    context.getString(if (expOn) R.string.tv_adblock_swap_locked_exp_hint else R.string.tv_adblock_swap_locked_hint)
                }
            }
            C.TV_ADBLOCK_SWAP_PLUS -> {
                enabled = !anyProxyOn
                disabledHint = { context.getString(R.string.tv_adblock_exp_disabled_by_proxy) }
            }
            C.TV_EXPERIMENTAL_ADBLOCK -> {
                enabled = !anyProxyOn
                disabledHint = { context.getString(R.string.tv_adblock_exp_disabled_by_proxy) }
            }
            "custom_proxy_settings", C.PLAYER_USE_STREAM_PROXY, "stream_proxy_settings" -> {
                enabled = !expOn && !swapPlusOn
                disabledHint = { context.getString(R.string.tv_adblock_exp_proxies_disabled) }
            }
        }

        val title = when (key) {
            C.PLAYER_ADBLOCK -> context.getString(R.string.tv_adblock_swap_title)
            "about_app" -> "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            else -> raw.title
        }
        if (title.isBlank()) return null

        var summary: String? = raw.summary?.takeIf { it.isNotBlank() && it != "%s" }
        var valueBadge: String? = null
        val kind: Kind = when (val rk = raw.rawKind) {
            is RawKind.Toggle -> {
                val checked = when (key) {
                    C.PLAYER_ADBLOCK -> if (!anyProxyOn) true else prefs.getBoolean(C.PLAYER_ADBLOCK, true)
                    C.TV_ADBLOCK_SWAP_PLUS -> swapPlusOn
                    C.TV_EXPERIMENTAL_ADBLOCK -> expOn
                    C.PLAYER_USE_STREAM_PROXY -> !expOn && !swapPlusOn && streamProxyOn
                    else -> if (key.isNotBlank() && prefs.contains(key)) prefs.getBoolean(key, rk.defaultChecked) else rk.defaultChecked
                }
                if (key == C.PLAYER_ADBLOCK) {
                    summary = context.getString(R.string.tv_adblock_summary_1440p)
                    if (!anyProxyOn && (swapPlusOn || expOn)) {
                        valueBadge = context.getString(R.string.tv_adblock_fallback_badge)
                    }
                } else if (key == C.TV_ADBLOCK_SWAP_PLUS && !anyProxyOn && expOn && swapPlusOn) {
                    valueBadge = context.getString(R.string.tv_adblock_fallback_badge)
                }
                Kind.Toggle(checked)
            }
            is RawKind.Choice -> {
                var entries = rk.entries
                var values = rk.entryValues
                var currentValue = if (key.isNotBlank() && prefs.contains(key)) prefs.getString(key, rk.defaultValue) else rk.defaultValue

                when (key) {
                    C.UI_LANGUAGE -> {
                        val lang = AppCompatDelegate.getApplicationLocales()
                        currentValue = if (lang.isEmpty) {
                            "auto"
                        } else {
                            val tag = lang.toLanguageTags()
                            if (tag in values) tag
                            else {
                                val short = when (tag.substringBefore("-")) {
                                    "id" -> "in"
                                    "pt" -> "pt-BR"
                                    "zh" -> "zh-TW"
                                    else -> tag.substringBefore("-")
                                }
                                if (short in values) short else "en"
                            }
                        }
                    }
                    C.TV_KEEP_SOUND_ON_LEAVE -> {
                        val back = prefs.getString(C.TV_BACK_ACTION, "mini")
                        when (back) {
                            "mini" -> {
                                entries = listOf(context.getString(R.string.tv_keep_sound_never), context.getString(R.string.tv_keep_sound_mini_only), context.getString(R.string.tv_keep_sound_always))
                                values = listOf("never", "minimized", "always")
                            }
                            "audio" -> {
                                entries = listOf(context.getString(R.string.tv_keep_sound_never), context.getString(R.string.tv_keep_sound_audio_only), context.getString(R.string.tv_keep_sound_always))
                                values = listOf("never", "minimized", "always")
                            }
                            else -> {
                                entries = listOf(context.getString(R.string.tv_keep_sound_never), context.getString(R.string.tv_keep_sound_fullscreen))
                                values = listOf("never", "always")
                            }
                        }
                        summary = context.getString(
                            when {
                                currentValue == "always" && back != "mini" && back != "audio" -> R.string.tv_keep_sound_fullscreen_summary
                                currentValue == "always" -> R.string.tv_keep_sound_always_summary
                                currentValue == "minimized" && back == "mini" -> R.string.tv_keep_sound_mini_summary
                                currentValue == "minimized" && back == "audio" -> R.string.tv_keep_sound_audio_summary
                                else -> R.string.tv_keep_sound_never_summary
                            }
                        )
                    }
                    C.CHAT_IMAGE_LIBRARY -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            entries = context.resources.getStringArray(R.array.imageLibraryEntriesNoWebp).toList()
                            values = context.resources.getStringArray(R.array.imageLibraryValuesNoWebp).toList()
                        }
                    }
                    "chat_translate_target" -> {
                        entries = caps.translateLanguages.map { it.second }
                        values = caps.translateLanguages.map { it.first }
                    }
                    C.NETWORK_LIBRARY -> {
                        if (!caps.cronet) {
                            entries = context.resources.getStringArray(R.array.networkLibraryEntriesNoCronet).toList()
                            values = entries
                        } else if (!caps.httpEngine) {
                            entries = context.resources.getStringArray(R.array.networkLibraryEntriesNoHttpEngine).toList()
                            values = entries
                        }
                    }
                }
                val selectedLabel = values.indexOf(currentValue).takeIf { it >= 0 }?.let { entries.getOrNull(it) }
                valueBadge = selectedLabel
                if (summary == selectedLabel) {
                    summary = null
                }
                val colors = if (key == C.TV_AMBIENT_COLOR) values.map { TvAmbient.colorOf(it) } else null
                val subtitle = if (key == C.TV_STREAM_END_ACTION) context.getString(R.string.tv_stream_end_raid_note) else null
                Kind.Choice(currentValue, entries, values, colors, subtitle)
            }
            is RawKind.MultiChoice -> {
                val entries = rk.entries
                val values = rk.entryValues
                val selected = if (key.isNotBlank() && prefs.contains(key)) {
                    prefs.getStringSet(key, rk.defaultValues).orEmpty()
                } else rk.defaultValues
                val labels = values.mapIndexedNotNull { idx, v -> if (v in selected) entries.getOrNull(idx) else null }
                valueBadge = if (labels.isEmpty()) context.getString(R.string.disabled_setting) else labels.joinToString(", ")
                Kind.MultiChoice(selected, entries, values)
            }
            is RawKind.Slider -> {
                val cur = if (key.isNotBlank() && prefs.contains(key)) prefs.getInt(key, rk.defaultValue) else rk.defaultValue
                Kind.Slider(cur, rk.min, rk.max, rk.step)
            }
            is RawKind.TextInput -> {
                val tokenMapping = TOKEN_PREF_KEYS[key]
                val cur = if (tokenMapping != null) {
                    context.tokenPrefs().getString(tokenMapping, "").orEmpty()
                } else {
                    prefs.getString(key, rk.defaultText).orEmpty()
                }
                when (key) {
                    C.PLAYER_REWIND -> valueBadge = context.getString(R.string.seconds_full, cur.ifBlank { "10" })
                    C.PLAYER_FORWARD -> valueBadge = context.getString(R.string.seconds_full, cur.ifBlank { "10" })
                    "update_check_frequency" -> summary = context.getString(R.string.update_check_frequency_summary, cur.ifBlank { "24" })
                    else -> if (cur.isNotBlank() && tokenMapping == null) valueBadge = cur
                }
                val numeric = key == C.PLAYER_REWIND || key == C.PLAYER_FORWARD || key == "update_check_frequency"
                Kind.TextInput(cur, isNumeric = numeric, useTokenPrefs = tokenMapping != null, tokenKey = tokenMapping)
            }
            RawKind.Action -> {
                when (key) {
                    "tv_favorites" -> {
                        val count = TvFavorites.items.value.size
                        valueBadge = if (count == 0) context.getString(R.string.tv_favorites_empty) else context.getString(R.string.tv_favorites_count, count)
                    }
                    "tv_blacklist" -> {
                        val status = TvBlacklist.summary(context)
                        if (status == context.getString(R.string.tv_blacklist_summary_empty)) {
                            summary = status
                        } else {
                            valueBadge = status
                        }
                    }
                    C.TV_PLAYER_CLOCK -> valueBadge = TvClockSettings.summary(context)
                    C.TV_WALLPAPER -> valueBadge = com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.summary(context)
                    C.TV_MULTISTREAM_QUALITY -> valueBadge = TvMultiStreamQuality.summary(context)
                    C.TV_SWITCHER_ROWS -> valueBadge = TvSwitcherRows.summary(context)
                    "tv_dpad_shortcuts" -> valueBadge = TvRemoteKeys.dpadSummary(context)
                    C.TV_CUSTOM_KEYS -> valueBadge = TvRemoteKeys.customKeysSummary(context)
                    C.TV_HOME_ROWS -> valueBadge = TvHomeRows.summary(context)
                    C.TV_CLEAR_RECENT_CHANNELS -> {
                        val count = com.github.andreyasadchy.xtra.ui.tv.TvRecentChannels.count(context)
                        if (count > 0) valueBadge = count.toString()
                    }
                    C.CHAT_HIGHLIGHTED_USERS -> valueBadge = ChatBlocklist.highlightedSummary(context)
                    C.CHAT_BLOCKED_USERS -> valueBadge = ChatBlocklist.summary(context)
                    "tv_chat_polls_popup" -> {
                        val labels = listOf(
                            context.getString(R.string.tv_chat_delay_off),
                            context.getString(R.string.tv_chat_predictions_30s),
                            context.getString(R.string.tv_chat_predictions_full),
                        )
                        valueBadge = labels.getOrElse(C.getTvPollsPopupMode(prefs)) { labels[0] }
                    }
                    "tv_chat_predictions_popup" -> {
                        val labels = listOf(
                            context.getString(R.string.tv_chat_delay_off),
                            context.getString(R.string.tv_chat_predictions_30s),
                            context.getString(R.string.tv_chat_predictions_full),
                        )
                        valueBadge = labels.getOrElse(C.getTvPredictionsPopupMode(prefs)) { labels[0] }
                    }
                    "custom_proxy_settings" -> {
                        val on = prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, false)
                        val adsOnly = prefs.getString(C.PLAYER_CUSTOM_PROXY_MODE, "0") == "1"
                        valueBadge = listOf(
                            context.getString(if (on) R.string.tv_proxies_on else R.string.tv_proxies_off),
                            context.getString(if (adsOnly) R.string.tv_proxy_mode_ads else R.string.tv_proxy_mode_always).lowercase(),
                            if (proxyCount == 1) context.getString(R.string.tv_proxies_count_one) else context.getString(R.string.tv_proxies_count, proxyCount),
                        ).joinToString(" · ")
                        summary = context.getString(R.string.tv_proxies_1440p)
                    }
                    "about_app" -> summary = TvAbout.CREDIT_TEXT
                    "about_changelog" -> valueBadge = BuildConfig.VERSION_NAME
                    "about_device" -> summary = caps.deviceSummary
                }
                Kind.Action(hasChevron = key in CHEVRON_KEYS)
            }
        }

        return PrefNode.Item(
            key = key,
            title = title,
            summary = summary,
            valueBadge = valueBadge,
            iconRes = raw.iconRes,
            enabled = enabled,
            disabledHint = disabledHint,
            kind = kind,
        )
    }

    private fun applySliderValue(context: Context, key: String, value: Int) {
        context.prefs().edit {
            putInt(key, value)
            if (key == "chatWidth") {
                val dm = context.resources.displayMetrics
                val maxSide = maxOf(dm.widthPixels, dm.heightPixels)
                putInt(C.LANDSCAPE_CHAT_WIDTH, (maxSide * (value / 100f)).toInt())
            }
        }
        refreshStamp.intValue++
    }

    private fun onPreferenceClicked(context: Context, item: PrefNode.Item) {
        val key = item.key
        when (val kind = item.kind) {
            is Kind.Toggle -> {
                val next = !kind.checked
                when (key) {
                    C.TV_ADBLOCK_SWAP_PLUS -> {
                        if (next && TvExperimentalAdblock.isAnyProxyEnabled(context)) {
                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_adblock_exp_disabled_by_proxy, Toast.LENGTH_LONG).show()
                            return
                        }
                        context.prefs().edit { putBoolean(C.TV_ADBLOCK_SWAP_PLUS, next) }
                        TvExperimentalAdblock.normalizePrefs(context)
                        (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                    }
                    C.TV_EXPERIMENTAL_ADBLOCK -> {
                        if (next && TvExperimentalAdblock.isAnyProxyEnabled(context)) {
                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_adblock_exp_disabled_by_proxy, Toast.LENGTH_LONG).show()
                            return
                        }
                        context.prefs().edit { putBoolean(C.TV_EXPERIMENTAL_ADBLOCK, next) }
                        TvExperimentalAdblock.normalizePrefs(context)
                        (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                    }
                    C.PLAYER_USE_STREAM_PROXY -> {
                        if (next && (context.prefs().getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false) ||
                                context.prefs().getBoolean(C.TV_ADBLOCK_SWAP_PLUS, false))
                        ) {
                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_adblock_exp_proxies_disabled, Toast.LENGTH_LONG).show()
                            return
                        }
                        context.prefs().edit { putBoolean(C.PLAYER_USE_STREAM_PROXY, next) }
                        (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                    }
                    C.PLAYER_ADBLOCK -> {
                        if (!next && !TvExperimentalAdblock.isAnyProxyEnabled(context)) {
                            val hintRes = if (context.prefs().getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false)) {
                                R.string.tv_adblock_swap_locked_exp_hint
                            } else {
                                R.string.tv_adblock_swap_locked_hint
                            }
                            com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, hintRes, Toast.LENGTH_LONG).show()
                            return
                        }
                        context.prefs().edit { putBoolean(C.PLAYER_ADBLOCK, next) }
                        (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                    }
                    C.TV_VOD_AUDIO_RECOVERY -> {
                        context.prefs().edit { putBoolean(C.TV_VOD_AUDIO_RECOVERY, next) }
                        (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                    }
                    "live_notifications_enabled" -> {
                        context.prefs().edit { putBoolean(key, next) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                        }
                        settingsViewModel.toggleNotifications(
                            enabled = next,
                            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true),
                            helixHeaders = TwitchApiHelper.getHelixHeaders(context),
                        )
                    }
                    "sleep_timer_lock" -> {
                        context.prefs().edit { putBoolean(key, next) }
                        if (next) {
                            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val admin = ComponentName(context, DeviceAdminReceiver::class.java)
                            if (!dpm.isAdminActive(admin)) {
                                runCatching {
                                    startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                                    })
                                }
                            }
                        }
                    }
                    "update_check_enabled" -> {
                        context.prefs().edit { putBoolean(key, next) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            next &&
                            !context.prefs().getBoolean(C.UPDATE_USE_BROWSER, false) &&
                            !context.canInstallUnknownAppsCompat()
                        ) {
                            requireActivity().openUnknownAppSourcesSettings()
                        }
                    }
                    "update_use_browser" -> {
                        context.prefs().edit { putBoolean(key, next) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            !next &&
                            context.prefs().getBoolean(C.UPDATE_CHECK_ENABLED, true) &&
                            !context.canInstallUnknownAppsCompat()
                        ) {
                            requireActivity().openUnknownAppSourcesSettings()
                        }
                    }
                    C.CHAT_HIDE_BOTS -> {
                        context.prefs().edit { putBoolean(key, next) }
                        ChatBlocklist.notifyPrefsChanged()
                    }
                    C.UI_DRAW_BEHIND_CUTOUTS -> {
                        context.prefs().edit { putBoolean(key, next) }
                        requireActivity().recreate()
                    }
                    else -> {
                        context.prefs().edit { putBoolean(key, next) }
                        if (key == C.TV_COMPACT_RAIL || key == C.TV_HIDE_MENU_STATS) {
                            (activity as? MainActivity)?.refreshTvNavigationUi()
                        }
                        if (key == C.TV_MINI_PLAYER_AUTO_SLIDE || key == C.TV_COMPACT_RAIL) {
                            (activity as? MainActivity)?.refreshTvMiniPlayerPosition(immediate = true)
                        }
                        if (key == C.TV_CARD_ZOOM || key == C.TV_COMPACT_RAIL) {
                            (activity as? MainActivity)?.refreshTvLayoutSettings(rebindCards = false)
                        } else if (key == C.UI_TRUNCATE_VIEW_COUNT || key == C.UI_ROUND_USER_IMAGE ||
                            key == C.UI_TAGS || key == C.UI_UPTIME
                        ) {
                            (activity as? MainActivity)?.refreshTvLayoutSettings(rebindCards = true)
                        } else if (key.startsWith("ui_theme_") || key == C.UI_FOLLOW_PAGER || key == C.UI_GAME_PAGER) {
                            (activity as? MainActivity)?.markTvRecreateOnLeaveSettings()
                        }
                        if (key == C.TV_SYNC_VOD_HISTORY && next) {
                            val app = context.applicationContext as? XtraApp
                            if (app != null) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    com.github.andreyasadchy.xtra.ui.tv.TvVodHistorySync.pullFromTwitch(
                                        context = app,
                                        xtraModule = app.xtraModule,
                                        force = true,
                                    )
                                }
                            }
                        }
                    }
                }
                refreshStamp.intValue++
            }
            is Kind.Choice -> {
                if (kind.entries.isEmpty() || kind.entryValues.isEmpty()) return
                TvDialogs.showChoices(
                    context = context,
                    title = item.title,
                    options = kind.entries,
                    selected = kind.entryValues.indexOf(kind.value).coerceAtLeast(0),
                    colors = kind.colors,
                    subtitle = kind.subtitle,
                ) { idx ->
                    val chosen = kind.entryValues.getOrNull(idx) ?: return@showChoices
                    if (key == C.UI_LANGUAGE) {
                        context.prefs().edit { putString(key, chosen) }
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(if (chosen == "auto") null else chosen)
                        )
                    } else if (key == C.TV_RECENT_CHANNELS_FILTER) {
                        com.github.andreyasadchy.xtra.ui.tv.TvRecentChannels.setFilterMode(context, chosen)
                    } else {
                        val previous = context.prefs().getString(key, null)
                        context.prefs().edit { putString(key, chosen) }
                        if (chosen != previous) {
                            when (key) {
                                C.TV_AMBIENT_COLOR -> (activity as? MainActivity)?.refreshTvAmbientColor()
                                C.TV_OLED_SCREENSAVER_TIMEOUT, C.TV_OLED_SCREENSAVER_DIM ->
                                    (activity as? MainActivity)?.refreshTvOledScreensaver()
                                C.TV_HOME_CARDS, C.TV_STREAM_COLUMNS ->
                                    (activity as? MainActivity)?.refreshTvLayoutSettings(rebindCards = false)
                                C.UI_NAME_DISPLAY ->
                                    (activity as? MainActivity)?.refreshTvLayoutSettings(rebindCards = true)
                                C.UI_INTERFACE_MODE -> {
                                    context.prefs().edit(commit = true) { putString(key, chosen) }
                                    com.github.andreyasadchy.xtra.ui.view.TvUi.restartAppForInterfaceMode(context)
                                    return@showChoices
                                }
                                else -> if (key.startsWith("ui_theme_")) {
                                    (activity as? MainActivity)?.markTvRecreateOnLeaveSettings()
                                }
                            }
                        }
                    }
                    refreshStamp.intValue++
                }
            }
            is Kind.MultiChoice -> {
                showMultiSelectPanel(context, item.title, kind) { selected ->
                    context.prefs().edit { putStringSet(key, selected) }
                    if (key == C.TV_HOME_LANGUAGE_ROWS) {
                        TvHomeRows.syncFromLanguageRowsPref(context, selected)
                    }
                    refreshStamp.intValue++
                }
            }
            is Kind.Slider -> {
                editingSliderKeyState.value = if (editingSliderKeyState.value == key) null else key
                updateBackCallback()
            }
            is Kind.TextInput -> {
                showTextInputDialog(context, item.title, kind) { newText ->
                    if (kind.useTokenPrefs && kind.tokenKey != null) {
                        context.tokenPrefs().edit { putString(kind.tokenKey, newText) }
                    } else {
                        context.prefs().edit { putString(key, newText) }
                    }
                    refreshStamp.intValue++
                }
            }
            is Kind.Action -> handleActionClick(context, item)
        }
    }

    private fun handleActionClick(context: Context, item: PrefNode.Item) {
        when (item.key) {
            C.TV_CLEAR_RECENT_CHANNELS -> {
                com.github.andreyasadchy.xtra.ui.tv.TvRecentChannels.clear(context)
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(
                    context,
                    R.string.tv_recent_channels_cleared,
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStamp.intValue++
            }
            "tv_chat_polls_popup" -> {
                val options = listOf(
                    context.getString(R.string.tv_chat_delay_off),
                    context.getString(R.string.tv_chat_predictions_30s),
                    context.getString(R.string.tv_chat_predictions_full),
                )
                TvDialogs.showChoices(
                    context = context,
                    title = item.title,
                    options = options,
                    selected = C.getTvPollsPopupMode(context.prefs()),
                ) { idx ->
                    context.prefs().edit { putInt(C.TV_CHAT_POLLS_MODE, idx.coerceIn(0, 2)) }
                    refreshStamp.intValue++
                }
            }
            "tv_chat_predictions_popup" -> {
                val options = listOf(
                    context.getString(R.string.tv_chat_delay_off),
                    context.getString(R.string.tv_chat_predictions_30s),
                    context.getString(R.string.tv_chat_predictions_full),
                )
                TvDialogs.showChoices(
                    context = context,
                    title = item.title,
                    options = options,
                    selected = C.getTvPredictionsPopupMode(context.prefs()),
                ) { idx ->
                    val mode = idx.coerceIn(0, 2)
                    context.prefs().edit {
                        putInt(C.TV_CHAT_PREDICTIONS_MODE, mode)
                        putBoolean(C.TV_CHAT_PREDICTIONS_POPUP, mode != 0)
                    }
                    refreshStamp.intValue++
                }
            }
            // Sub-screens
            "tv_xtratv_sub_customization" -> pushXmlSubScreen(R.xml.tv_xtratv_customization, context.getString(R.string.tv_xtratv_sub_customization_title), item.key)
            "tv_xtratv_sub_home" -> pushXmlSubScreen(R.xml.tv_xtratv_home, context.getString(R.string.tv_xtratv_sub_home_title), item.key)
            "tv_xtratv_sub_remote" -> pushXmlSubScreen(R.xml.tv_xtratv_remote, context.getString(R.string.tv_xtratv_sub_remote_title), item.key)
            "tv_xtratv_sub_player" -> pushXmlSubScreen(R.xml.tv_xtratv_player, context.getString(R.string.tv_xtratv_sub_player_title), item.key)
            "tv_xtratv_sub_previews" -> pushXmlSubScreen(R.xml.tv_xtratv_previews, context.getString(R.string.tv_xtratv_sub_previews_title), item.key)
            "tv_xtratv_sub_languages" -> pushXmlSubScreen(R.xml.tv_xtratv_languages, context.getString(R.string.tv_xtratv_sub_languages_title), item.key)
            "player_settings" -> pushXmlSubScreen(R.xml.player_preferences, context.getString(R.string.player_settings), item.key)
            "playback_settings" -> pushXmlSubScreen(R.xml.playback_preferences, context.getString(R.string.playback_settings), item.key)
            "buffer_settings" -> pushXmlSubScreen(R.xml.buffer_preferences, context.getString(R.string.buffer_settings), item.key)
            "player_button_settings" -> pushXmlSubScreen(R.xml.player_button_preferences, context.getString(R.string.player_buttons), item.key)
            "player_menu_settings" -> pushXmlSubScreen(R.xml.player_menu_preferences, context.getString(R.string.player_menu_settings), item.key)
            "ui_settings" -> pushXmlSubScreen(R.xml.ui_preferences, context.getString(R.string.ui_settings), item.key)
            "adblock_settings" -> pushXmlSubScreen(R.xml.adblock_preferences, context.getString(R.string.adblock), item.key)
            "update_settings" -> pushXmlSubScreen(R.xml.update_preferences, context.getString(R.string.update_settings), item.key)
            "api_token_settings" -> pushXmlSubScreen(R.xml.api_token_preferences, context.getString(R.string.api_token_settings), item.key)
            "debug_settings" -> pushXmlSubScreen(R.xml.debug_preferences, context.getString(R.string.debug_settings), item.key)
            "video_swap_settings" -> {
                videoSwapViewModel.getVideoSwapItems(
                    context.applicationContext,
                    VideoSwap(
                        platform = context.prefs().getString(C.TOKEN_PLATFORM, "web"),
                        playerType = context.prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                        position = -1,
                    )
                )
                returnFocusKeyState.value = null
                subScreenStack.add(SubScreen.VideoSwaps(item.title, item.key))
                updateBackCallback()
                focusRightTrigger.intValue++
            }
            "stream_proxy_settings" -> {
                if (context.prefs().getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false) ||
                    context.prefs().getBoolean(C.TV_ADBLOCK_SWAP_PLUS, false)
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_adblock_exp_proxies_disabled, Toast.LENGTH_LONG).show()
                    return
                }
                streamProxyViewModel.getProxies()
                returnFocusKeyState.value = null
                subScreenStack.add(SubScreen.StreamProxies(item.title, item.key))
                updateBackCallback()
                focusRightTrigger.intValue++
            }
            "custom_proxy_settings" -> {
                if (context.prefs().getBoolean(C.TV_EXPERIMENTAL_ADBLOCK, false) ||
                    context.prefs().getBoolean(C.TV_ADBLOCK_SWAP_PLUS, false)
                ) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_adblock_exp_proxies_disabled, Toast.LENGTH_LONG).show()
                    return
                }
                val activity = requireActivity()
                val repository = (activity.application as XtraApp).xtraModule.playerRepository
                viewLifecycleOwner.lifecycleScope.launch {
                    val proxies = repository.getCustomProxies()
                    TvProxyPanel.show(
                        activity,
                        proxies,
                        openSettings = {
                            if (isAdded) {
                                customProxyViewModel.getProxies(context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP))
                                returnFocusKeyState.value = null
                                subScreenStack.add(SubScreen.CustomProxies(item.title, item.key))
                                updateBackCallback()
                                focusRightTrigger.intValue++
                            }
                        },
                        manage = TvProxyPanel.Manage(activity.lifecycleScope, repository),
                    ) { changed ->
                        activity.lifecycleScope.launch {
                            if (changed != null) {
                                repository.updateCustomProxies(changed)
                            }
                            if (isAdded) {
                                reloadProxyCount()
                                refreshStamp.intValue++
                                (activity as? MainActivity)?.onTvAdblockOrProxyChanged()
                            }
                        }
                    }
                }
            }

            // Xtra TV Panels
            "tv_favorites" -> {
                TvFavorites.load(context)
                val favorites = TvFavorites.items.value
                if (favorites.isEmpty()) {
                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_favorites_how, Toast.LENGTH_LONG).show()
                } else {
                    val items = favorites.map {
                        SettingsDragListItem(key = it.login, text = it.name ?: it.login, default = false, enabled = true)
                    }
                    TvListPanel.show(context, item.title, items, showDefault = false) { saved ->
                        val names = favorites.associate { it.login to it.name }
                        TvFavorites.reorder(
                            context,
                            saved.filter { it.enabled }.map { TvFavorites.Favorite(it.key, names[it.key]) }
                        )
                        refreshStamp.intValue++
                    }
                }
            }
            "tv_blacklist" -> {
                TvBlacklist.showManagerPanel(context) {
                    refreshStamp.intValue++
                }
            }
            C.TV_HOME_ROWS -> {
                TvHomeRows.showManagerPanel(context) {
                    refreshStamp.intValue++
                }
            }
            C.TV_PLAYER_CLOCK -> {
                TvClockSettings.showPanel(context) {
                    refreshStamp.intValue++
                }
            }
            C.TV_WALLPAPER -> {
                val act = activity as? MainActivity
                val settingsRoot = view
                com.github.andreyasadchy.xtra.ui.tv.TvWallpaper.showPanel(
                    context = context,
                    onLiveRefresh = { act?.refreshTvAmbientColor() },
                    onPreviewOpen = {
                        settingsRoot?.animate()?.alpha(0f)?.setDuration(160L)?.start()
                    },
                    onPreviewClose = {
                        settingsRoot?.animate()?.alpha(1f)?.setDuration(150L)?.start()
                        refreshStamp.intValue++
                    },
                ) {
                    refreshStamp.intValue++
                }
            }
            C.TV_MULTISTREAM_QUALITY -> {
                TvMultiStreamQuality.showPanel(context = context) {
                    refreshStamp.intValue++
                }
            }
            C.TV_SWITCHER_ROWS -> {
                TvSwitcherRows.showManagerPanel(context) {
                    refreshStamp.intValue++
                }
            }
            "tv_dpad_shortcuts" -> {
                TvRemoteKeys.showDpadPanel(context) {
                    refreshStamp.intValue++
                }
            }
            C.TV_CUSTOM_KEYS -> {
                TvRemoteKeys.showCustomKeysPanel(context) {
                    refreshStamp.intValue++
                }
            }

            // Tab List Reorder Panels
            "ui_navigation_tab_list_dialog" -> showNavigationTabsPanel(context, item.title)
            "ui_following_tabs_dialog" -> showSimpleTabsPanel(
                context, item.title, C.UI_FOLLOWING_TABS, C.DEFAULT_FOLLOWING_TABS
            ) { k ->
                when (k) {
                    "0" -> getString(R.string.games)
                    "1" -> getString(R.string.live)
                    "2" -> getString(R.string.videos)
                    "3" -> getString(R.string.channels)
                    else -> getString(R.string.live)
                }
            }
            "ui_saved_tabs_dialog" -> showSimpleTabsPanel(
                context, item.title, C.UI_SAVED_TABS, C.DEFAULT_SAVED_TABS
            ) { k ->
                when (k) {
                    "0" -> getString(R.string.bookmarks)
                    "1" -> getString(R.string.downloads)
                    "2" -> getString(R.string.filters)
                    else -> getString(R.string.downloads)
                }
            }
            "ui_game_tabs_dialog" -> showSimpleTabsPanel(
                context, item.title, C.UI_GAME_TABS, C.DEFAULT_GAME_TABS
            ) { k ->
                when (k) {
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.live)
                    "2" -> getString(R.string.clips)
                    else -> getString(R.string.live)
                }
            }
            "ui_search_tabs_dialog" -> showSimpleTabsPanel(
                context, item.title, C.UI_SEARCH_TABS, C.DEFAULT_SEARCH_TABS
            ) { k ->
                when (k) {
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.streams)
                    "2" -> getString(R.string.channels)
                    "3" -> getString(R.string.games)
                    else -> getString(R.string.channels)
                }
            }
            "ui_channel_tabs_dialog" -> showChannelTabsPanel(context, item.title)

            // Chat & MLKit
            C.CHAT_HIGHLIGHTED_USERS -> {
                ChatBlocklist.showHighlightedSettingsDialog(requireActivity()) {
                    refreshStamp.intValue++
                }
            }
            C.CHAT_BLOCKED_USERS -> {
                ChatBlocklist.showSettingsDialog(requireActivity()) {
                    refreshStamp.intValue++
                }
            }
            "downloaded_languages" -> {
                val languages = TranslateLanguage.getAllLanguages()
                val names = languages.map { Locale.forLanguageTag(it).displayLanguage }
                val modelManager = RemoteModelManager.getInstance()
                modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                    .addOnSuccessListener { models ->
                        if (!isAdded) return@addOnSuccessListener
                        val downloaded = models.map { it.language }.toSet()
                        showMultiSelectPanel(
                            context,
                            item.title,
                            Kind.MultiChoice(downloaded, names, languages),
                        ) { selected ->
                            downloaded.filter { it !in selected }.forEach {
                                modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build())
                            }
                            selected.filter { it !in downloaded }.forEach {
                                modelManager.download(
                                    TranslateRemoteModel.Builder(it).build(),
                                    DownloadConditions.Builder().build()
                                )
                            }
                        }
                    }
            }

            // Maintenance & Updates & Backup & About
            "delete_video_positions" -> {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_video_positions_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ -> settingsViewModel.deletePositions() }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
            }
            "delete_recent_searches" -> {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_recent_searches_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ -> settingsViewModel.deleteRecentSearches() }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
            }
            "import_app_downloads" -> {
                settingsViewModel.importDownloads()
                com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, android.R.string.ok, Toast.LENGTH_SHORT).show()
            }
            "admin_settings" -> {
                runCatching {
                    startActivity(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")))
                }
            }
            "check_updates" -> {
                settingsViewModel.checkUpdates(
                    context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    context.prefs().getString(C.UPDATE_URL, null) ?: "https://api.github.com/repos/razeNFR/XtraPlus/releases/latest",
                    context.tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0),
                )
            }
            "backup_settings" -> {
                TvActionPanel.show(
                    context,
                    getString(R.string.backup_settings),
                    listOf(
                        TvActionPanel.Action(getString(R.string.tv_backup_save_tv), R.drawable.tv_ic_download) {
                            TvBackup.saveToTv(context, lifecycleScope)
                        },
                        TvActionPanel.Action(getString(R.string.tv_backup_qr_send), R.drawable.tv_ic_share) {
                            TvBackup.showQrExport(context, lifecycleScope)
                        },
                    )
                )
            }
            "restore_settings" -> {
                TvActionPanel.show(
                    context,
                    getString(R.string.restore_settings),
                    listOf(
                        TvActionPanel.Action(getString(R.string.tv_backup_restore_tv), R.drawable.tv_ic_history) {
                            TvBackup.restoreFromTv(context, lifecycleScope) {
                                try {
                                    restoreResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    })
                                } catch (_: ActivityNotFoundException) {
                                    com.github.andreyasadchy.xtra.ui.tv.TvToast.makeText(context, R.string.tv_backup_not_found, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        TvActionPanel.Action(getString(R.string.tv_backup_qr_receive), R.drawable.tv_ic_wifi) {
                            TvBackup.showQrImport(context, lifecycleScope)
                        },
                    )
                )
            }
            "get_integrity_token" -> {
                IntegrityDialog.newInstance(null).show(childFragmentManager, null)
            }
            "about_app", "about_github", "about_device" -> TvAbout.show(context)
            "about_changelog" -> com.github.andreyasadchy.xtra.ui.tv.TvChangelog.show(context)
            "about_discord" -> TvAbout.show(context, discord = true)
        }
    }

    private fun pushXmlSubScreen(xmlRes: Int, title: String, parentItemKey: String? = null) {
        returnFocusKeyState.value = null
        subScreenStack.add(SubScreen.Xml(xmlRes, title, parentItemKey = parentItemKey))
        updateBackCallback()
        focusRightTrigger.intValue++
    }

    // =========================================================================
    // Helper Dialogs & Side Panels
    // =========================================================================

    private fun parseTabSpecList(rawPref: String?, defaultCsv: String): List<String> {
        val defaultTabs = defaultCsv.split(',')
        if (rawPref == null) return defaultTabs
        val list = rawPref.split(',').filter { item ->
            defaultTabs.any { it.first() == item.first() }
        }.toMutableList()
        defaultTabs.forEachIndexed { index, item ->
            if (list.none { it.first() == item.first() }) {
                list.add(index.coerceAtMost(list.size), item)
            }
        }
        return list
    }

    private fun showNavigationTabsPanel(context: Context, title: String) {
        val tabList = parseTabSpecList(context.prefs().getString(C.UI_NAVIGATION_TAB_LIST, null), C.DEFAULT_NAVIGATION_TAB_LIST)
        val startPage = context.prefs().getString(C.TV_START_PAGE, "home")
        val extras = context.prefs().getStringSet(C.TV_MENU_EXTRAS, null).orEmpty()
        val extrasOrder = C.parseTvMenuExtrasOrder(context.prefs())
        val extraLabels = mapOf(
            "videos" to R.string.tv_menu_followed_videos,
            "channels" to R.string.tv_menu_followed_channels,
            "games" to R.string.tv_menu_followed_games,
        )
        val tabs = tabList.map {
            val split = it.split(':')
            SettingsDragListItem(
                key = split[0],
                text = when (split[0]) {
                    "0" -> getString(R.string.games)
                    "1" -> getString(R.string.popular)
                    "2" -> getString(R.string.following)
                    "3" -> getString(R.string.saved)
                    else -> getString(R.string.popular)
                },
                default = split[0] == startPage,
                enabled = split[2] != "0",
            )
        }
        val home = SettingsDragListItem(
            key = "home",
            text = getString(R.string.tv_home),
            default = startPage == "home",
            enabled = true,
            locked = true,
        )
        val rows = listOf(home) + tabs.flatMap { tab ->
            if (tab.key == "2") {
                listOf(tab) + extrasOrder.mapNotNull { k ->
                    extraLabels[k]?.let { labelRes ->
                        SettingsDragListItem(key = k, text = getString(labelRes), default = false, enabled = k in extras, parent = "2")
                    }
                }
            } else listOf(tab)
        }
        TvListPanel.show(context, title, rows, showDefault = true) { saved ->
            context.prefs().edit {
                putString(C.UI_NAVIGATION_TAB_LIST, saved.filter { it.parent == null && !it.locked }.joinToString(",") {
                    "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                })
                putString(C.TV_START_PAGE, saved.firstOrNull { it.default && it.enabled && it.parent == null }?.key ?: "home")
                putString(C.TV_MENU_EXTRAS_ORDER, saved.filter { it.parent != null }.joinToString(",") { it.key })
                putStringSet(C.TV_MENU_EXTRAS, saved.filter { it.parent != null && it.enabled }.map { it.key }.toSet())
            }
            (activity as? MainActivity)?.refreshTvNavigationUi()
            refreshStamp.intValue++
        }
    }

    private fun showSimpleTabsPanel(
        context: Context,
        title: String,
        prefKey: String,
        defaultCsv: String,
        labelOf: (String) -> String,
    ) {
        val tabList = parseTabSpecList(context.prefs().getString(prefKey, null), defaultCsv)
        val tabs = tabList.map {
            val split = it.split(':')
            SettingsDragListItem(
                key = split[0],
                text = labelOf(split[0]),
                default = split[1] != "0",
                enabled = split[2] != "0",
            )
        }
        TvListPanel.show(context, title, tabs, showDefault = true) { saved ->
            context.prefs().edit {
                putString(prefKey, saved.joinToString(",") {
                    "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                })
            }
            refreshStamp.intValue++
        }
    }

    private fun showChannelTabsPanel(context: Context, title: String) {
        val tabList = parseTabSpecList(context.prefs().getString(C.UI_CHANNEL_TABS, null), C.DEFAULT_CHANNEL_TABS)
        val tabs = tabList.map {
            val split = it.split(':')
            SettingsDragListItem(
                key = split[0],
                text = when (split[0]) {
                    "0" -> getString(R.string.suggestions)
                    "1" -> getString(R.string.videos)
                    "2" -> getString(R.string.clips)
                    "3" -> getString(R.string.chat)
                    "4" -> getString(R.string.about)
                    "5" -> getString(R.string.tv_schedule)
                    else -> getString(R.string.videos)
                },
                default = split[1] != "0",
                enabled = split[2] != "0",
            )
        }
        val hidden = context.prefs().getStringSet(C.UI_CHANNEL_HIDDEN_BUTTONS, C.DEFAULT_CHANNEL_HIDDEN_BUTTONS).orEmpty()
        val rows = tabs + listOf(
            "download" to R.string.channel_button_download,
            "share" to R.string.channel_button_share,
            "notifications" to R.string.channel_button_notifications,
        ).map { (k, labelRes) ->
            SettingsDragListItem(key = k, text = getString(labelRes), default = false, enabled = k !in hidden, parent = "buttons")
        }
        TvListPanel.show(context, title, rows, showDefault = true) { saved ->
            context.prefs().edit {
                putString(C.UI_CHANNEL_TABS, saved.filter { it.parent == null }.joinToString(",") {
                    "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                })
                putStringSet(C.UI_CHANNEL_HIDDEN_BUTTONS, saved.filter { it.parent != null && !it.enabled }.map { it.key }.toSet())
            }
            refreshStamp.intValue++
        }
    }

    private fun showMultiSelectPanel(
        context: Context,
        title: String,
        kind: Kind.MultiChoice,
        onSave: (Set<String>) -> Unit,
    ) {
        var current by mutableStateOf(kind.values)
        val dialog = TvDialogs.show(context) { dismiss ->
            val firstReq = remember { FocusRequester() }
            SidePanel(
                title = title,
                footer = {
                    ButtonRow(
                        label = context.getString(R.string.close_player),
                        onClick = dismiss,
                    )
                },
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(kind.entries) { idx, label ->
                        val v = kind.entryValues.getOrNull(idx) ?: return@itemsIndexed
                        val checked = v in current
                        FocusRow(
                            onClick = {
                                current = if (checked) current - v else current + v
                            },
                            modifier = if (idx == 0) Modifier.focusRequester(firstReq) else Modifier,
                        ) { focused ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    lineHeight = 18.5.sp,
                                    fontWeight = if (checked || focused) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (focused) TvColors.onFocused else Color.White,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(10.dp))
                                TvListPanel.Switch(checked, focused)
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { firstReq.requestFocus() } }
        }
        dialog.setOnDismissListener { onSave(current) }
    }

    private fun showSearchPrompt(context: Context) {
        val density = context.resources.displayMetrics.density
        val disableSoftKeyboard = context.prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)
        val edit = EditText(context).apply {
            hint = context.getString(R.string.search)
            setText(searchQueryState.value)
            setSelection(text.length)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            if (disableSoftKeyboard) showSoftInputOnFocus = false
        }
        val container = FrameLayout(context).apply {
            val h = (22 * density).toInt()
            val v = (8 * density).toInt()
            setPadding(h, v, h, 0)
            addView(edit)
        }
        val dialog = context.getAlertDialogBuilder()
            .setTitle(R.string.search)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val q = edit.text?.toString()?.trim().orEmpty()
                subScreenStack.clear()
                searchQueryState.value = q
                updateBackCallback()
                if (q.isNotEmpty()) focusRightTrigger.intValue++
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = edit.text?.toString()?.trim().orEmpty()
                subScreenStack.clear()
                searchQueryState.value = q
                updateBackCallback()
                dialog.dismiss()
                if (q.isNotEmpty()) focusRightTrigger.intValue++
                true
            } else false
        }
        dialog.show()
        if (edit.requestFocus() && !disableSoftKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.window?.decorView?.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    private fun showTextInputDialog(
        context: Context,
        title: String,
        kind: Kind.TextInput,
        onSave: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val disableSoftKeyboard = context.prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)
        val edit = EditText(context).apply {
            setText(kind.currentValue)
            setSelection(text.length)
            inputType = if (kind.isNumeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            if (disableSoftKeyboard) showSoftInputOnFocus = false
        }
        val container = FrameLayout(context).apply {
            val h = (22 * density).toInt()
            val v = (8 * density).toInt()
            setPadding(h, v, h, 0)
            addView(edit)
        }
        val dialog = context.getAlertDialogBuilder()
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(edit.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSave(edit.text?.toString().orEmpty())
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
        if (edit.requestFocus() && !disableSoftKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.window?.decorView?.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    private fun showCustomProxyEditDialog(item: CustomProxy, onSave: (String?, Boolean) -> Unit) {
        val binding = DialogCustomProxyEditBinding.inflate(layoutInflater)
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(binding.editText.editText?.text?.toString(), binding.checkBox.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val disableSoftKeyboard = requireContext().prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)
        binding.editText.editText?.apply {
            if (disableSoftKeyboard) showSoftInputOnFocus = false
            val url = item.url.orEmpty()
            text.replace(0, length(), url, 0, url.length)
            inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            maxLines = 3
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onSave(binding.editText.editText?.text?.toString(), binding.checkBox.isChecked)
                    dialog.dismiss()
                    true
                } else false
            }
        }
        binding.checkBox.isChecked = item.addQueryParams
        dialog.show()
        if (binding.editText.editText?.requestFocus() == true && !disableSoftKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.window?.decorView?.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    private fun showStreamProxyEditDialog(
        item: StreamProxy,
        onSave: (String?, String?, String?, String?, Boolean, Boolean, Boolean) -> Unit,
    ) {
        val binding = DialogStreamProxyEditBinding.inflate(layoutInflater)
        val protocols = com.github.andreyasadchy.xtra.util.StreamProxyHelper.ProxyProtocol.entries
        binding.protocolSpinner.adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            protocols.map { it.displayName },
        )
        val parsedInitial = com.github.andreyasadchy.xtra.util.StreamProxyHelper.parse(item.host, item.port)
        binding.protocolSpinner.setSelection(
            protocols.indexOf(parsedInitial?.protocol ?: com.github.andreyasadchy.xtra.util.StreamProxyHelper.ProxyProtocol.HTTP).coerceAtLeast(0)
        )
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedProtocol = protocols.getOrElse(binding.protocolSpinner.selectedItemPosition) {
                    com.github.andreyasadchy.xtra.util.StreamProxyHelper.ProxyProtocol.HTTP
                }
                val (storedHost, resolvedPort) = com.github.andreyasadchy.xtra.util.StreamProxyHelper.formatStoredHost(
                    selectedProtocol = selectedProtocol,
                    rawHost = binding.hostInput.editText?.text?.toString(),
                    rawPort = binding.portInput.editText?.text?.toString(),
                )
                onSave(
                    storedHost,
                    resolvedPort?.toString(),
                    binding.userInput.editText?.text?.toString(),
                    binding.passwordInput.editText?.text?.toString(),
                    binding.proxyPlaybackAccessTokenCheckBox.isChecked,
                    binding.proxyMultivariantPlaylistCheckBox.isChecked,
                    binding.proxyMediaPlaylistCheckBox.isChecked,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val disableSoftKeyboard = requireContext().prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)
        listOf(
            binding.hostInput.editText to (parsedInitial?.host ?: item.host.orEmpty()),
            binding.portInput.editText to ((parsedInitial?.port ?: item.port)?.toString().orEmpty()),
            binding.userInput.editText to item.username.orEmpty(),
            binding.passwordInput.editText to item.password.orEmpty(),
        ).forEach { (et, initial) ->
            et?.apply {
                if (disableSoftKeyboard) showSoftInputOnFocus = false
                text.replace(0, length(), initial, 0, initial.length)
            }
        }
        binding.proxyPlaybackAccessTokenCheckBox.isChecked = item.proxyPlaybackAccessToken
        binding.proxyMultivariantPlaylistCheckBox.isChecked = item.proxyMultivariantPlaylist
        binding.proxyMediaPlaylistCheckBox.isChecked = item.proxyMediaPlaylist
        dialog.show()
        if (binding.hostInput.editText?.requestFocus() == true && !disableSoftKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.window?.decorView?.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    private fun showVideoSwapEditDialog(item: VideoSwap, onSave: (String?, String?) -> Unit) {
        val binding = DialogVideoSwapEditBinding.inflate(layoutInflater)
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(
                    binding.platformInput.editText?.text?.toString(),
                    binding.playerTypeInput.editText?.text?.toString(),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val disableSoftKeyboard = requireContext().prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)
        binding.platformInput.editText?.apply {
            if (disableSoftKeyboard) showSoftInputOnFocus = false
            val s = item.platform.orEmpty()
            text.replace(0, length(), s, 0, s.length)
        }
        binding.playerTypeInput.editText?.apply {
            if (disableSoftKeyboard) showSoftInputOnFocus = false
            val s = item.playerType.orEmpty()
            text.replace(0, length(), s, 0, s.length)
        }
        dialog.show()
        if (binding.platformInput.editText?.requestFocus() == true && !disableSoftKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dialog.window?.decorView?.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    companion object {
        @Volatile
        var pendingOpenSubScreen: String? = null

        private val CATEGORIES = listOf(
            CategorySpec(R.xml.tv_settings_xtratv, R.string.app_name, R.drawable.tv_ic_tv_small),
            CategorySpec(R.xml.tv_settings_player, R.string.player_settings, R.drawable.tv_ic_play),
            CategorySpec(R.xml.chat_preferences, R.string.chat_settings, R.drawable.tv_ic_chat),
            CategorySpec(R.xml.tv_settings_interface, R.string.ui_settings, R.drawable.tv_ic_view_quilt),
            CategorySpec(R.xml.tv_settings_ads, R.string.tv_settings_section_ads, R.drawable.tv_ic_shield),
            CategorySpec(R.xml.download_preferences, R.string.download_settings, R.drawable.tv_ic_download),
            CategorySpec(R.xml.tv_settings_updates, R.string.tv_settings_section_updates, R.drawable.tv_ic_cloud),
            CategorySpec(R.xml.tv_settings_advanced, R.string.tv_settings_section_advanced, R.drawable.tv_ic_tune),
            CategorySpec(R.xml.tv_settings_about, R.string.tv_settings_section_about, R.drawable.tv_ic_info),
        )

        private val TOKEN_PREF_KEYS = mapOf(
            "user_id" to C.USER_ID,
            "username" to C.USERNAME,
            "token" to C.TOKEN,
            "gql_token2" to C.GQL_TOKEN2,
            "gql_token_web" to C.GQL_TOKEN_WEB,
            "gql_headers" to C.GQL_HEADERS,
        )

        private val CHEVRON_KEYS = setOf(
            "tv_xtratv_sub_customization", "tv_xtratv_sub_home", "tv_xtratv_sub_remote", "tv_xtratv_sub_player", "tv_xtratv_sub_previews",
            "tv_xtratv_sub_languages", "player_settings", "playback_settings", "buffer_settings",
            "player_button_settings", "player_menu_settings", "ui_settings", "adblock_settings", "update_settings",
            "api_token_settings", "debug_settings", "video_swap_settings", "stream_proxy_settings",
            "custom_proxy_settings", "tv_favorites", "tv_blacklist", C.TV_HOME_ROWS, C.TV_PLAYER_CLOCK, C.TV_WALLPAPER,
            C.TV_MULTISTREAM_QUALITY, C.TV_SWITCHER_ROWS, "tv_dpad_shortcuts", C.TV_CUSTOM_KEYS,
            "ui_navigation_tab_list_dialog", "ui_following_tabs_dialog", "ui_saved_tabs_dialog",
            "ui_game_tabs_dialog", "ui_search_tabs_dialog", "ui_channel_tabs_dialog",
            C.CHAT_HIGHLIGHTED_USERS, C.CHAT_BLOCKED_USERS, "downloaded_languages",
            "backup_settings", "restore_settings", "about_changelog",
        )

        private val PLAYER_BUTTON_ICONS = mapOf(
            C.TV_BUTTON_STREAM_LIST to R.drawable.tv_ic_streamers,
            C.TV_BUTTON_MULTI_STREAM to R.drawable.tv_ic_multistream,
            C.TV_BUTTON_CHAT_MESSAGE to R.drawable.tv_ic_chat_send,
            C.TV_BUTTON_CHAT_LAYOUT to R.drawable.tv_ic_chat_layout,
            C.TV_BUTTON_POINTS to R.drawable.tv_ic_diamond,
            C.TV_BUTTON_CLIP to R.drawable.tv_ic_clip,
            C.TV_BUTTON_STATS to R.drawable.tv_ic_stats,
            C.TV_BUTTON_DIM to R.drawable.tv_ic_dim,
            C.TV_BUTTON_PROXY to R.drawable.tv_ic_proxy,
            C.TV_PLAYER_FAVORITE_STAR to R.drawable.tv_ic_star,
            "player_download" to R.drawable.ic_file_download_black_24dp,
            "player_follow" to R.drawable.baseline_favorite_border_black_24,
            "player_sleep" to R.drawable.baseline_alarm_black_24,
            "player_speedbutton" to androidx.media3.ui.R.drawable.exo_ic_speed,
            "player_settings" to R.drawable.tv_ic_hd,
            "player_menu" to R.drawable.baseline_settings_black_24,
            "player_gamesbutton" to R.drawable.baseline_format_list_bulleted_black_24,
            "player_restart" to R.drawable.baseline_replay_black_24,
            "player_seeklive" to androidx.media3.ui.R.drawable.exo_icon_fastforward,
            "player_volumebutton" to R.drawable.baseline_volume_up_black_24,
            "player_audio_compressor_button" to R.drawable.baseline_audio_compressor_off_24dp,
            "player_mode" to R.drawable.baseline_audiotrack_black_24,
            "player_subtitles" to androidx.media3.ui.R.drawable.exo_ic_subtitle_off,
            "player_chattoggle" to R.drawable.baseline_speaker_notes_black_24,
            "player_viewericon" to R.drawable.baseline_person_black_24,
            "player_pause" to R.drawable.baseline_play_arrow_black_48,
        )

        private val ALL_PREWARM_XMLS = listOf(
            R.xml.tv_settings_xtratv,
            R.xml.tv_xtratv_customization,
            R.xml.tv_xtratv_home,
            R.xml.tv_xtratv_remote,
            R.xml.tv_xtratv_player,
            R.xml.tv_xtratv_previews,
            R.xml.tv_xtratv_languages,
            R.xml.tv_settings_player,
            R.xml.player_preferences,
            R.xml.playback_preferences,
            R.xml.buffer_preferences,
            R.xml.player_button_preferences,
            R.xml.player_menu_preferences,
            R.xml.chat_preferences,
            R.xml.tv_settings_interface,
            R.xml.ui_preferences,
            R.xml.tv_settings_ads,
            R.xml.adblock_preferences,
            R.xml.download_preferences,
            R.xml.tv_settings_updates,
            R.xml.update_preferences,
            R.xml.tv_settings_advanced,
            R.xml.api_token_preferences,
            R.xml.debug_preferences,
            R.xml.tv_settings_about,
        )

        private class DeviceCaps(appCtx: Context) {
            val hasPip: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            val arm64: Boolean = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"
            val httpEngine: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
            val cronet: Boolean = runCatching {
                CronetProvider.getAllProviders(appCtx).any { it.isEnabled }
            }.getOrDefault(false)
            val deviceSummary: String = TvAbout.deviceSummary()
            val translateLanguages: List<Pair<String, String>> by lazy {
                TranslateLanguage.getAllLanguages().map { it to Locale.forLanguageTag(it).displayLanguage }
            }
        }

        private sealed interface RawKind {
            data class Toggle(val defaultChecked: Boolean) : RawKind
            data class Choice(
                val defaultValue: String?,
                val entries: List<String>,
                val entryValues: List<String>,
            ) : RawKind
            data class MultiChoice(
                val defaultValues: Set<String>,
                val entries: List<String>,
                val entryValues: List<String>,
            ) : RawKind
            data class Slider(val defaultValue: Int, val min: Int, val max: Int, val step: Int) : RawKind
            data class TextInput(val defaultText: String?) : RawKind
            data object Action : RawKind
        }

        private data class RawPrefItem(
            val key: String,
            val title: String,
            val summary: String?,
            val isEnabled: Boolean,
            val dependency: String?,
            val iconRes: Int,
            val rawKind: RawKind,
        )

        private sealed interface RawXmlEntry {
            data class HeaderGroup(val title: String?, val children: List<RawPrefItem>) : RawXmlEntry
            data class Single(val item: RawPrefItem) : RawXmlEntry
        }

        @Volatile
        private var cachedDeviceCaps: DeviceCaps? = null
        private val parsedXmlCache = ConcurrentHashMap<String, List<RawXmlEntry>>()
        private val xmlInflateLock = Any()
        private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun deviceCaps(context: Context): DeviceCaps {
            cachedDeviceCaps?.let { return it }
            return synchronized(xmlInflateLock) {
                cachedDeviceCaps ?: DeviceCaps(context.applicationContext).also { cachedDeviceCaps = it }
            }
        }

        private fun parseRawPrefItem(pref: Preference): RawPrefItem {
            val key = pref.key.orEmpty()
            val iconRes = PLAYER_BUTTON_ICONS[key] ?: TvSettingIcons.iconOf(pref)
            val title = pref.title?.toString().orEmpty()
            val summary = if (pref is EditTextPreference && pref.summaryProvider != null) {
                null
            } else {
                pref.summary?.toString()
            }
            val rawKind: RawKind = when (pref) {
                is TwoStatePreference -> RawKind.Toggle(defaultChecked = pref.isChecked)
                is ListPreference -> RawKind.Choice(
                    defaultValue = pref.value,
                    entries = pref.entries?.map { it.toString() }.orEmpty(),
                    entryValues = pref.entryValues?.map { it.toString() }.orEmpty(),
                )
                is MultiSelectListPreference -> RawKind.MultiChoice(
                    defaultValues = pref.values?.toSet().orEmpty(),
                    entries = pref.entries?.map { it.toString() }.orEmpty(),
                    entryValues = pref.entryValues?.map { it.toString() }.orEmpty(),
                )
                is SeekBarPreference -> RawKind.Slider(
                    defaultValue = pref.value,
                    min = pref.min,
                    max = pref.max,
                    step = pref.seekBarIncrement.takeIf { it > 0 } ?: 1,
                )
                is EditTextPreference -> RawKind.TextInput(defaultText = pref.text)
                else -> RawKind.Action
            }
            return RawPrefItem(
                key = key,
                title = title,
                summary = summary,
                isEnabled = pref.isEnabled,
                dependency = pref.dependency,
                iconRes = iconRes,
                rawKind = rawKind,
            )
        }

        @SuppressLint("RestrictedApi")
        private fun parseXmlEntries(context: Context, xmlRes: Int): List<RawXmlEntry> {
            val cacheKey = "${context.resources.configuration.locales.toLanguageTags()}:$xmlRes"
            parsedXmlCache[cacheKey]?.let { return it }
            return synchronized(xmlInflateLock) {
                parsedXmlCache[cacheKey]?.let { return@synchronized it }
                val themedCtx = androidx.appcompat.view.ContextThemeWrapper(context, R.style.DarkTheme)
                val pm = PreferenceManager(themedCtx).apply {
                    sharedPreferencesName = "tv_xml_defaults_only"
                }
                val screen = pm.inflateFromResource(themedCtx, xmlRes, null)
                val entries = ArrayList<RawXmlEntry>(screen.preferenceCount)
                for (i in 0 until screen.preferenceCount) {
                    val pref = screen.getPreference(i)
                    if (!pref.isVisible) continue
                    if (pref is PreferenceCategory) {
                        val catHeader = pref.title?.toString()?.takeIf { it.isNotBlank() }
                        val children = ArrayList<RawPrefItem>(pref.preferenceCount)
                        for (j in 0 until pref.preferenceCount) {
                            val child = pref.getPreference(j)
                            if (!child.isVisible) continue
                            children.add(parseRawPrefItem(child))
                        }
                        if (children.isNotEmpty()) {
                            entries.add(RawXmlEntry.HeaderGroup(catHeader, children))
                        }
                    } else {
                        entries.add(RawXmlEntry.Single(parseRawPrefItem(pref)))
                    }
                }
                parsedXmlCache[cacheKey] = entries
                entries
            }
        }

        fun prewarmAsync(context: Context) {
            val config = Configuration(context.resources.configuration)
            val appCtx = context.applicationContext
            prewarmScope.launch {
                runCatching {
                    val localizedCtx = appCtx.createConfigurationContext(config)
                    deviceCaps(localizedCtx)
                    ALL_PREWARM_XMLS.forEach { xmlRes ->
                        runCatching { parseXmlEntries(localizedCtx, xmlRes) }
                    }
                }
            }
        }
    }
}
