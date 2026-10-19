package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.SettingsNavGraphDirections
import com.github.andreyasadchy.xtra.databinding.ActivitySettingsBinding
import com.github.andreyasadchy.xtra.databinding.DialogUpdateDownloadBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.model.ui.SettingsSearchItem
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.ui.view.TvReorder
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.appbar.AppBarLayout
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.chromium.net.CronetProvider
import java.util.Collections
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var changed = false
    var searchItem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean(KEY_CHANGED) == true) {
            setResult()
        }
        applyTheme()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (TvUi.isTv(this)) {
            (binding.root.background as? android.graphics.drawable.ColorDrawable)?.color?.let { base ->
                binding.root.background = com.github.andreyasadchy.xtra.ui.tv.TvAmbient.background(this, base)
            }
            TvUi.adapt(binding.root, hideToolbarActions = false)
            supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    TvUi.adapt(v, hideToolbarActions = false)
                    if (f is androidx.fragment.app.DialogFragment) {
                        v.post { TvUi.adaptDialog(v) }
                    }
                }

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    if (f is androidx.fragment.app.DialogFragment) {
                        TvUi.adaptSheet(f)
                    }
                }
            }, true)
        }
        val ignoreCutouts = prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            val cutoutInsets = if (ignoreCutouts) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            } else {
                insets
            }
            binding.appBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            windowInsets
        }
        val navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        val appBarConfiguration = AppBarConfiguration(setOf(), fallbackOnNavigateUpListener = {
            onBackPressedDispatcher.onBackPressed()
            true
        })
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        if (TvUi.isTv(this)) {
            // under the name of the screen: the app and its version ("Xtra TV 1.2.0", "(debug)" on the test builds)
            val version = BuildConfig.VERSION_NAME.removeSuffix("-DEBUG").removeSuffix("-tv")
            binding.tvSettingsSubtitle?.text = getString(R.string.app_name) + " " + version + if (BuildConfig.DEBUG) " (debug)" else ""
            // Google TV style: the name of the screen in big on the left, the toolbar keeps the back and search buttons
            navController.addOnDestinationChangedListener { _, _, _ ->
                binding.toolbar.post {
                    binding.tvSettingsTitle?.text = binding.toolbar.title
                    binding.toolbar.title = null
                }
            }
        }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> {
                    navController.navigate(SettingsNavGraphDirections.actionGlobalSettingsSearchFragment())
                    true
                }
                else -> false
            }
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var job: Job? = null

            override fun onQueryTextSubmit(query: String): Boolean {
                (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                job?.cancel()
                if (newText.isNotEmpty()) {
                    job = lifecycleScope.launch {
                        delay(750.milliseconds)
                        withResumed {
                            (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                        }
                    }
                } else {
                    (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                }
                return false
            }
        })
    }

    /**
     * Reorder dialog. [onSave] replaces the saving in [prefKey] (the favorites of the TV), without [showDefault]
     * the items have no "default" button.
     */
    fun showDragListDialog(
        list: List<SettingsDragListItem>,
        prefKey: String,
        title: CharSequence?,
        showDefault: Boolean = true,
        onSave: ((List<SettingsDragListItem>) -> Unit)? = null,
    ) {
        val listAdapter = SettingsDragListAdapter()
        val dragCallback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                // the rows under another one follow it, they are not moved by themselves
                val item = list.getOrNull(viewHolder.bindingAdapterPosition)
                return if (item?.parent != null || item?.locked == true) 0 else super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                var toPosition = target.bindingAdapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION || list.getOrNull(fromPosition)?.parent != null) {
                    return false
                }
                // a locked row (the home of the TV) stays where it is, nothing goes past it
                if (list.getOrNull(fromPosition)?.locked == true || list.getOrNull(toPosition)?.locked == true) {
                    return false
                }
                // over the rows under another one: the move goes past that whole group
                list.getOrNull(toPosition)?.parent?.let { parentKey ->
                    toPosition = if (toPosition < fromPosition) {
                        list.indexOfFirst { it.key == parentKey }
                    } else {
                        (list.indexOfLast { it.parent == parentKey } + 1).takeIf { it < list.size } ?: return false
                    }
                    if (toPosition < 0 || toPosition == fromPosition) {
                        return false
                    }
                }
                if (fromPosition < toPosition) {
                    for (from in fromPosition until toPosition) {
                        val to = from + 1
                        Collections.swap(list, from, to)
                        listAdapter.notifyItemMoved(from, to)
                    }
                } else {
                    for (to in (toPosition until fromPosition).reversed()) {
                        val from = to + 1
                        Collections.swap(list, from, to)
                        listAdapter.notifyItemMoved(from, to)
                    }
                }
                // the rows under another one go back right under it
                val rows = list as? MutableList<SettingsDragListItem>
                if (rows != null) {
                    rows.filter { it.parent != null }.groupBy { it.parent }.forEach { (parentKey, children) ->
                        children.forEachIndexed { index, child ->
                            val at = rows.indexOf(child)
                            rows.removeAt(at)
                            val parentIndex = rows.indexOfFirst { it.key == parentKey }
                            val wanted = if (parentIndex >= 0) (parentIndex + 1 + index).coerceAtMost(rows.size) else at
                            rows.add(wanted, child)
                            if (at != wanted) {
                                listAdapter.notifyItemMoved(at, wanted)
                            }
                        }
                    }
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }
        }
        val itemTouchHelper = ItemTouchHelper(dragCallback)
        listAdapter.itemTouchHelper = itemTouchHelper
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = listAdapter
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10F, resources.displayMetrics).toInt()
            setPadding(0, padding, 0, 0)
        }
        if (showDefault && list.none { it.default && it.enabled && it.parent == null }) {
            // the house was on an unchecked tab (or nowhere): it goes to the first checked one
            list.forEach { it.default = false }
            list.firstOrNull { it.enabled && it.parent == null }?.default = true
        }
        listAdapter.setDefault = if (!showDefault) null else { item ->
            list.find { it.default }?.let { previous ->
                previous.default = false
                recyclerView.findViewHolderForAdapterPosition(
                    list.indexOf(previous)
                )?.itemView?.findViewById<ImageButton>(R.id.setAsDefault)?.let {
                    it.setImageResource(R.drawable.outline_home_black_24)
                    it.isClickable = true
                }
            }
            item.default = true
        }
        itemTouchHelper.attachToRecyclerView(recyclerView)
        TvReorder.install(recyclerView, dragCallback)
        if (TvUi.isTv(this)) {
            TvUi.highlightRows(recyclerView)
        }
        listAdapter.submitList(list)
        getAlertDialogBuilder()
            .setTitle(title)
            .setView(recyclerView)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                if (onSave != null) {
                    onSave(listAdapter.currentList)
                    return@setPositiveButton
                }
                prefs().edit {
                    putString(prefKey, listAdapter.currentList.joinToString(",") {
                        "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                    })
                    setResult()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showSearchView(showSearch: Boolean) {
        with(binding) {
            val isTv = resources.getBoolean(R.bool.isTv)
            if (showSearch) {
                toolbar.menu.findItem(R.id.search).isVisible = false
                if (isTv) {
                    // TV: search bar with the mic in it
                    toolbar.findViewById<View>(R.id.tvSearchBar)?.let { toolbar.removeView(it) }
                    com.github.andreyasadchy.xtra.ui.tv.TvDialogs.replaceSearchView(searchView, getString(R.string.search))?.let { bar ->
                        bar.post { bar.requestFocus() }
                    }
                } else {
                    searchView.visibility = View.VISIBLE
                }
            } else {
                toolbar.menu.findItem(R.id.search).isVisible = true
                searchView.setQuery(null, false)
                searchView.visibility = View.GONE
                toolbar.findViewById<View>(R.id.tvSearchBar)?.let { toolbar.removeView(it) }
            }
        }
    }

    private fun getSelectedSearchItem(): String? {
        return searchItem?.also {
            searchItem = null
        }
    }

    private fun setResult() {
        if (!changed) {
            changed = true
            setResult(RESULT_OK)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CHANGED, changed)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val KEY_CHANGED = "changed"
    }

    class SettingsFragment : MaterialPreferenceFragment() {

        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }
        private var backupResultLauncher: ActivityResultLauncher<Intent>? = null
        private var restoreResultLauncher: ActivityResultLauncher<Intent>? = null
        private var updateDownloadDialogBinding: DialogUpdateDownloadBinding? = null
        private var updateDownloadDialog: AlertDialog? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            backupResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let {
                        viewModel.backupSettings(it.toString())
                    }
                }
            }
            restoreResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val list = mutableListOf<String>()
                    result.data?.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            item.uri?.let {
                                list.add(it.toString())
                            }
                        }
                    } ?: result.data?.data?.let {
                        list.add(it.toString())
                    }
                    viewModel.restoreSettings(
                        list = list,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                        helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
                    )
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            findPreference<ListPreference>(C.UI_LANGUAGE)?.apply {
                val lang = AppCompatDelegate.getApplicationLocales()
                if (lang.isEmpty) {
                    setValueIndex(findIndexOfValue("auto"))
                } else {
                    try {
                        setValueIndex(findIndexOfValue(lang.toLanguageTags()))
                    } catch (e: Exception) {
                        try {
                            setValueIndex(findIndexOfValue(
                                lang.toLanguageTags().substringBefore("-").let {
                                    when (it) {
                                        "id" -> "in"
                                        "pt" -> "pt-BR"
                                        "zh" -> "zh-TW"
                                        else -> it
                                    }
                                }
                            ))
                        } catch (e: Exception) {
                            setValueIndex(findIndexOfValue("en"))
                        }
                    }
                }
                setOnPreferenceChangeListener { _, value ->
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            if (value.toString() == "auto") {
                                null
                            } else {
                                value.toString()
                            }
                        )
                    )
                    true
                }
            }
            findPreference<SwitchPreferenceCompat>(C.UI_DRAW_BEHIND_CUTOUTS)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setOnPreferenceChangeListener { _, _ ->
                        (requireActivity() as? SettingsActivity)?.changed = true
                        requireActivity().recreate()
                        true
                    }
                } else {
                    isVisible = false
                }
            }
            findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                viewModel.toggleNotifications(
                    enabled = newValue as Boolean,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
                )
                true
            }
            findPreference<Preference>("theme_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalThemeSettingsFragment())
                true
            }
            findPreference<Preference>("xtratv_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalXtraTvSettingsFragment())
                true
            }
            findPreference<Preference>("ui_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalUiSettingsFragment())
                true
            }
            findPreference<Preference>("chat_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalChatSettingsFragment())
                true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_PICTURE_IN_PICTURE)?.isVisible = false
            }
            findPreference<Preference>("player_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment())
                true
            }
            findPreference<Preference>("player_button_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment())
                true
            }
            findPreference<Preference>("buffer_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalBufferSettingsFragment())
                true
            }
            findPreference<Preference>("custom_proxy_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalCustomProxySettingsFragment())
                true
            }
            findPreference<Preference>("stream_proxy_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalStreamProxySettingsFragment())
                true
            }
            findPreference<Preference>("playback_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlaybackSettingsFragment())
                true
            }
            findPreference<Preference>("api_token_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalApiTokenSettingsFragment())
                true
            }
            val httpEngine = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
            val cronet = CronetProvider.getAllProviders(requireContext()).any { it.isEnabled }
            if (!httpEngine || !cronet) {
                findPreference<ListPreference>(C.NETWORK_LIBRARY)?.apply {
                    when {
                        !httpEngine && !cronet -> {
                            isVisible = false
                        }
                        !cronet -> {
                            setEntries(R.array.networkLibraryEntriesNoCronet)
                            setEntryValues(R.array.networkLibraryEntriesNoCronet)
                        }
                        else -> {
                            setEntries(R.array.networkLibraryEntriesNoHttpEngine)
                            setEntryValues(R.array.networkLibraryEntriesNoHttpEngine)
                        }
                    }
                }
            }
            findPreference<Preference>("download_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment())
                true
            }
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
                viewModel.checkUpdates(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    requireContext().prefs().getString(C.UPDATE_URL, null) ?: "https://api.github.com/repos/razeNFR/XtraATV/releases/latest",
                    requireContext().tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0)
                )
                true
            }
            findPreference<Preference>("update_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment())
                true
            }
            findPreference<Preference>("backup_settings")?.setOnPreferenceClickListener {
                backupResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                true
            }
            findPreference<Preference>("restore_settings")?.setOnPreferenceClickListener {
                restoreResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                })
                true
            }
            findPreference<Preference>("debug_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalDebugSettingsFragment())
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateUrl.collectLatest {
                        if (it != null) {
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                                !requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false) &&
                                !requireContext().packageManager.canRequestPackageInstalls()
                            ) {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        "package:${requireContext().packageName}".toUri()
                                    )
                                    startActivity(intent)
                                } catch (e: ActivityNotFoundException) {

                                }
                            }
                            requireActivity().getAlertDialogBuilder()
                                .setTitle(getString(R.string.update_available))
                                .setMessage(getString(R.string.update_message))
                                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                    if (requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false)) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, it.toUri()).apply {
                                                addCategory(Intent.CATEGORY_BROWSABLE)
                                            }
                                            startActivity(intent)
                                            requireContext().tokenPrefs().edit {
                                                putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                            }
                                        } catch (e: ActivityNotFoundException) {
                                            Toast.makeText(requireContext(), R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        val binding = DialogUpdateDownloadBinding.inflate(layoutInflater)
                                        updateDownloadDialogBinding = binding
                                        val size = viewModel.updateSize
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
                                        viewModel.downloadUpdate(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), it)
                                        val dialog = requireActivity().getAlertDialogBuilder()
                                            .setView(binding.root)
                                            .setNegativeButton(getString(android.R.string.cancel), null)
                                            .setOnDismissListener {
                                                viewModel.updateJob?.cancel()
                                                updateDownloadDialogBinding = null
                                                updateDownloadDialog = null
                                            }
                                            .show()
                                        updateDownloadDialog = dialog
                                    }
                                }
                                .setNegativeButton(getString(R.string.no), null)
                                .show()
                        } else {
                            Toast.makeText(requireContext(), R.string.no_updates_found, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateProgress.collectLatest {
                        updateDownloadDialogBinding?.let { binding ->
                            val size = viewModel.updateSize
                            if (size != null) {
                                binding.textView.text = getString(
                                    R.string.downloading_update_progress,
                                    Formatter.formatFileSize(requireContext(), it.toLong()),
                                    Formatter.formatFileSize(requireContext(), size),
                                )
                                binding.progressBar.progress = (((it.toFloat() / size) * 100)).toInt()
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.closeUpdateDialog.collectLatest {
                        updateDownloadDialog?.dismiss()
                    }
                }
            }
        }
    }

    class ThemeSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.theme_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                findPreference<ListPreference>(C.THEME)?.apply {
                    setEntries(R.array.themeNoDynamicEntries)
                    setEntryValues(R.array.themeNoDynamicValues)
                }
                findPreference<ListPreference>(C.UI_THEME_DARK_ON)?.apply {
                    setEntries(R.array.themeNoDynamicEntries)
                    setEntryValues(R.array.themeNoDynamicValues)
                }
                findPreference<ListPreference>(C.UI_THEME_DARK_OFF)?.apply {
                    setEntries(R.array.themeNoDynamicEntries)
                    setEntryValues(R.array.themeNoDynamicValues)
                }
            }
            val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
                (requireActivity() as? SettingsActivity)?.changed = true
                requireActivity().recreate()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_ROUND_USER_IMAGE)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.THEME)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_FOLLOW_SYSTEM)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.UI_THEME_DARK_ON)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.UI_THEME_DARK_OFF)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.UI_THEME_ROUNDED_CORNERS)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_REDUCED_PADDING)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_COMPACT_TEXT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_APPBAR_LIFT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_BOTTOM_NAV_COLOR)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_MATERIAL3)?.onPreferenceChangeListener = changeListener
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class UiSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.ui_preferences, rootKey)
            val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
                (requireActivity() as? SettingsActivity)?.setResult()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_ROUND_USER_IMAGE)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_TRUNCATE_VIEW_COUNT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_UPTIME)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_TAGS)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_BROADCASTERS_COUNT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_BOOKMARK_TIME_LEFT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_SCROLL_TOP)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.PORTRAIT_COLUMN_COUNT)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.LANDSCAPE_COLUMN_COUNT)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.COMPACT_STREAMS)?.onPreferenceChangeListener = changeListener
            findPreference<Preference>("ui_navigation_tab_list_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_NAVIGATION_TAB_LIST.split(',')
                    if (tabPref != null) {
                        val list = tabPref.split(',').filter { item ->
                            defaultTabs.find { it.first() == item.first() } != null
                        }.toMutableList()
                        defaultTabs.forEachIndexed { index, item ->
                            if (list.find { it.first() == item.first() } == null) {
                                list.add(index, item)
                            }
                        }
                        list
                    } else defaultTabs
                }
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
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                // TV: the shortcuts of the side menu to the tabs of the followed channels, checked under "Following"
                val isTv = requireContext().resources.getBoolean(R.bool.isTv)
                val extras = requireContext().prefs().getStringSet(C.TV_MENU_EXTRAS, null).orEmpty()
                // TV: the home on top (it can't be moved nor hidden), the house says the page the app opens on
                val startPage = requireContext().prefs().getString(C.TV_START_PAGE, "home")
                if (isTv) {
                    tabs.forEach { it.default = it.key == startPage }
                }
                val home = SettingsDragListItem(key = "home", text = getString(R.string.tv_home), default = startPage == "home", enabled = true, locked = true)
                val rows = if (isTv) {
                    (listOf(home) + tabs.flatMap { tab ->
                        if (tab.key == "2") {
                            listOf(tab) + listOf(
                                "videos" to R.string.tv_menu_followed_videos,
                                "channels" to R.string.tv_menu_followed_channels,
                                "games" to R.string.tv_menu_followed_games,
                            ).map { (key, text) -> SettingsDragListItem(key = key, text = getString(text), default = false, enabled = key in extras, parent = "2") }
                        } else listOf(tab)
                    }).toMutableList()
                } else tabs
                (requireActivity() as? SettingsActivity)?.showDragListDialog(rows, C.UI_NAVIGATION_TAB_LIST, preference.title) { saved ->
                    requireContext().prefs().edit {
                        putString(C.UI_NAVIGATION_TAB_LIST, saved.filter { it.parent == null && !it.locked }.joinToString(",") {
                            "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                        })
                        if (isTv) {
                            // the page the app opens on: the row with the house (a checked one), else the home
                            putString(C.TV_START_PAGE, saved.firstOrNull { it.default && it.enabled && it.parent == null }?.key ?: "home")
                            putStringSet(C.TV_MENU_EXTRAS, saved.filter { it.parent != null && it.enabled }.map { it.key }.toSet())
                        }
                    }
                    (requireActivity() as? SettingsActivity)?.setResult()
                }
                true
            }
            findPreference<Preference>("ui_following_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_FOLLOWING_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_FOLLOWING_TABS.split(',')
                    if (tabPref != null) {
                        val list = tabPref.split(',').filter { item ->
                            defaultTabs.find { it.first() == item.first() } != null
                        }.toMutableList()
                        defaultTabs.forEachIndexed { index, item ->
                            if (list.find { it.first() == item.first() } == null) {
                                list.add(index, item)
                            }
                        }
                        list
                    } else defaultTabs
                }
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.games)
                            "1" -> getString(R.string.live)
                            "2" -> getString(R.string.videos)
                            "3" -> getString(R.string.channels)
                            else -> getString(R.string.live)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_FOLLOWING_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_saved_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_SAVED_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_SAVED_TABS.split(',')
                    if (tabPref != null) {
                        val list = tabPref.split(',').filter { item ->
                            defaultTabs.find { it.first() == item.first() } != null
                        }.toMutableList()
                        defaultTabs.forEachIndexed { index, item ->
                            if (list.find { it.first() == item.first() } == null) {
                                list.add(index, item)
                            }
                        }
                        list
                    } else defaultTabs
                }
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.bookmarks)
                            "1" -> getString(R.string.downloads)
                            "2" -> getString(R.string.filters)
                            else -> getString(R.string.downloads)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_SAVED_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_channel_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_CHANNEL_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_CHANNEL_TABS.split(',')
                    if (tabPref != null) {
                        val list = tabPref.split(',').filter { item ->
                            defaultTabs.find { it.first() == item.first() } != null
                        }.toMutableList()
                        defaultTabs.forEachIndexed { index, item ->
                            if (list.find { it.first() == item.first() } == null) {
                                list.add(index, item)
                            }
                        }
                        list
                    } else defaultTabs
                }
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
                            else -> getString(R.string.videos)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                // the buttons of the channel page, each one can be hidden, after the tabs
                val hidden = requireContext().prefs().getStringSet(C.UI_CHANNEL_HIDDEN_BUTTONS, null).orEmpty()
                val rows = (tabs + listOf(
                    "download" to R.string.channel_button_download,
                    "share" to R.string.channel_button_share,
                    "notifications" to R.string.channel_button_notifications,
                ).map { (key, text) ->
                    SettingsDragListItem(key = key, text = getString(text), default = false, enabled = key !in hidden, parent = "buttons")
                }).toMutableList()
                (requireActivity() as? SettingsActivity)?.showDragListDialog(rows, C.UI_CHANNEL_TABS, preference.title) { saved ->
                    requireContext().prefs().edit {
                        putString(C.UI_CHANNEL_TABS, saved.filter { it.parent == null }.joinToString(",") {
                            "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                        })
                        putStringSet(C.UI_CHANNEL_HIDDEN_BUTTONS, saved.filter { it.parent != null && !it.enabled }.map { it.key }.toSet())
                    }
                    (requireActivity() as? SettingsActivity)?.setResult()
                }
                true
            }
            findPreference<Preference>("ui_game_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_GAME_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_GAME_TABS.split(',')
                    if (tabPref != null) {
                        val list = tabPref.split(',').filter { item ->
                            defaultTabs.find { it.first() == item.first() } != null
                        }.toMutableList()
                        defaultTabs.forEachIndexed { index, item ->
                            if (list.find { it.first() == item.first() } == null) {
                                list.add(index, item)
                            }
                        }
                        list
                    } else defaultTabs
                }
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.videos)
                            "1" -> getString(R.string.live)
                            "2" -> getString(R.string.clips)
                            else -> getString(R.string.live)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_GAME_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_search_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_SEARCH_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_SEARCH_TABS.split(',')
                    if (tabPref != null) {
                        val list = tabPref.split(',').filter { item ->
                            defaultTabs.find { it.first() == item.first() } != null
                        }.toMutableList()
                        defaultTabs.forEachIndexed { index, item ->
                            if (list.find { it.first() == item.first() } == null) {
                                list.add(index, item)
                            }
                        }
                        list
                    } else defaultTabs
                }
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.videos)
                            "1" -> getString(R.string.streams)
                            "2" -> getString(R.string.channels)
                            "3" -> getString(R.string.games)
                            else -> getString(R.string.channels)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_SEARCH_TABS, preference.title)
                true
            }
            findPreference<Preference>("delete_recent_searches")?.setOnPreferenceClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_recent_searches_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        viewModel.deleteRecentSearches()
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class ChatSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.chat_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                findPreference<ListPreference>(C.CHAT_IMAGE_LIBRARY)?.apply {
                    setEntries(R.array.imageLibraryEntriesNoWebp)
                    setEntryValues(R.array.imageLibraryValuesNoWebp)
                }
            }
            findPreference<SeekBarPreference>("chatWidth")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    (requireActivity() as? SettingsActivity)?.setResult()
                    val width = resources.displayMetrics.widthPixels
                    val height = resources.displayMetrics.heightPixels
                    val chatWidth = ((if (height > width) height else width) * (newValue as Int / 100f)).toInt()
                    requireContext().prefs().edit { putInt(C.LANDSCAPE_CHAT_WIDTH, chatWidth) }
                    true
                }
            }
            if (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                val languages = TranslateLanguage.getAllLanguages()
                val names = languages.map { Locale.forLanguageTag(it).displayLanguage }.toTypedArray()
                findPreference<Preference>("downloaded_languages")?.setOnPreferenceClickListener {
                    val modelManager = RemoteModelManager.getInstance()
                    modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                        .addOnSuccessListener { models ->
                            val downloaded = models.map { it.language }
                            val checked = languages.map { downloaded.contains(it) }.toBooleanArray()
                            val selectedItems = downloaded.toMutableList()
                            requireActivity().getAlertDialogBuilder()
                                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                                    languages.getOrNull(which)?.let { language ->
                                        if (isChecked) {
                                            if (!selectedItems.contains(language)) {
                                                selectedItems.add(language)
                                            }
                                        } else {
                                            selectedItems.remove(language)
                                        }
                                    }
                                }
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    downloaded.filter { !selectedItems.contains(it) }.forEach {
                                        modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build())
                                    }
                                    selectedItems.filter { !downloaded.contains(it) }.forEach {
                                        modelManager.download(
                                            TranslateRemoteModel.Builder(it).build(),
                                            DownloadConditions.Builder().build()
                                        )
                                    }
                                }
                                .setNegativeButton(getString(android.R.string.cancel), null)
                                .show()
                        }
                    true
                }
                findPreference<ListPreference>("chat_translate_target")?.apply {
                    entries = names
                    entryValues = languages.toTypedArray()
                }
            } else {
                findPreference<SwitchPreferenceCompat>("chat_translate")?.isVisible = false
                findPreference<Preference>("downloaded_languages")?.isVisible = false
                findPreference<ListPreference>("chat_translate_target")?.isVisible = false
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlayerSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED)?.isVisible = false
                findPreference<SwitchPreferenceCompat>(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED)?.isVisible = false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_ROUNDED_CORNER_PADDING)?.isVisible = false
            }
            findPreference<Preference>("delete_video_positions")?.setOnPreferenceClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_video_positions_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        viewModel.deletePositions()
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlayerButtonSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_button_preferences, rootKey)
            if (resources.getBoolean(R.bool.isTv)) {
                // TV: each setting shows the icon of its button in the player
                val icons = mapOf(
                    C.TV_BUTTON_STREAM_LIST to R.drawable.tv_ic_streamers,
                    C.TV_BUTTON_MULTI_STREAM to R.drawable.tv_ic_multistream,
                    C.TV_BUTTON_CHAT_MESSAGE to R.drawable.tv_ic_chat_send,
                    C.TV_BUTTON_CHAT_LAYOUT to R.drawable.tv_ic_chat_layout,
                    C.TV_BUTTON_STATS to R.drawable.tv_ic_stats,
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
                val size = (24 * resources.displayMetrics.density).toInt()
                val tint = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE)
                icons.forEach { (key, icon) ->
                    val preference = findPreference<Preference>(key) ?: return@forEach
                    val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), icon)?.mutate() ?: return@forEach
                    drawable.setTint(tint)
                    // every icon at the same size, whatever the size of its picture
                    preference.icon = android.graphics.drawable.LayerDrawable(arrayOf(drawable)).apply { setLayerSize(0, size, size) }
                }
            }
            findPreference<SwitchPreferenceCompat>("sleep_timer_lock")?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) {
                    val devicePolicyManager = requireContext().getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(requireContext(), DeviceAdminReceiver::class.java)
                    if (!devicePolicyManager.isAdminActive(admin)) {
                        startActivity(
                            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            }
                        )
                    }
                }
                true
            }
            findPreference<Preference>("admin_settings")?.setOnPreferenceClickListener {
                startActivity(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")))
                true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_AUDIO_COMPRESSOR_BUTTON)?.isVisible = false
            }
            findPreference<Preference>("player_menu_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlayerMenuSettingsFragment())
                true
            }
            findPreference<EditTextPreference>(C.PLAYER_REWIND)?.apply {
                summary = getString(R.string.seconds_full, requireContext().prefs().getString(C.PLAYER_REWIND, "10"))
                setOnPreferenceChangeListener { _, newValue ->
                    summary = getString(R.string.seconds_full, newValue.toString())
                    true
                }
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER
                    it.setSelection(it.text.length)
                }
            }
            findPreference<EditTextPreference>(C.PLAYER_FORWARD)?.apply {
                summary = getString(R.string.seconds_full, requireContext().prefs().getString(C.PLAYER_FORWARD, "10"))
                setOnPreferenceChangeListener { _, newValue ->
                    summary = getString(R.string.seconds_full, newValue.toString())
                    true
                }
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER
                    it.setSelection(it.text.length)
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlayerMenuSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_menu_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class BufferSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.buffer_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlaybackSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.playback_preferences, rootKey)
            findPreference<Preference>("video_swap_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalVideoSwapSettingsFragment())
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    /** Settings added by Xtra TV, all in one screen. */
    class XtraTvSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.xtratv_preferences, rootKey)
            // what leaving the app keeps playing: the summary says it in full for the choice made
            findPreference<ListPreference>(C.TV_KEEP_SOUND_ON_LEAVE)?.let { preference ->
                val updateSummary = { value: String? ->
                    preference.summary = getString(
                        when (value) {
                            "never" -> R.string.tv_keep_sound_never_summary
                            "minimized" -> R.string.tv_keep_sound_minimized_summary
                            else -> R.string.tv_keep_sound_always_summary
                        }
                    )
                }
                updateSummary(preference.value)
                preference.setOnPreferenceChangeListener { _, newValue ->
                    updateSummary(newValue as? String)
                    true
                }
            }
            // favorite channels: their order (first in the followed streams), unchecked ones leave the favorites
            findPreference<Preference>("tv_favorites")?.let { preference ->
                val context = requireContext()
                com.github.andreyasadchy.xtra.ui.tv.TvFavorites.load(context)
                val updateSummary = {
                    val count = com.github.andreyasadchy.xtra.ui.tv.TvFavorites.items.value.size
                    preference.summary = if (count == 0) getString(R.string.tv_favorites_empty) else getString(R.string.tv_favorites_count, count)
                }
                updateSummary()
                preference.setOnPreferenceClickListener {
                    val favorites = com.github.andreyasadchy.xtra.ui.tv.TvFavorites.items.value
                    if (favorites.isEmpty()) {
                        android.widget.Toast.makeText(context, R.string.tv_favorites_how, android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        val items = favorites.map { SettingsDragListItem(key = it.login, text = it.name ?: it.login, default = false, enabled = true) }.toMutableList()
                        (requireActivity() as? SettingsActivity)?.showDragListDialog(items, "", preference.title, showDefault = false) { list ->
                            val names = favorites.associate { it.login to it.name }
                            com.github.andreyasadchy.xtra.ui.tv.TvFavorites.reorder(
                                context,
                                list.filter { it.enabled }.map { com.github.andreyasadchy.xtra.ui.tv.TvFavorites.Favorite(it.key, names[it.key]) }
                            )
                            updateSummary()
                        }
                    }
                    true
                }
            }
            // the ambiance color is drawn when the app starts: it offers to restart it
            findPreference<ListPreference>(C.TV_AMBIENT_COLOR)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue != requireContext().prefs().getString(C.TV_AMBIENT_COLOR, "violet")) {
                    (requireActivity() as? SettingsActivity)?.setResult()
                    requireContext().getAlertDialogBuilder()
                        .setMessage(R.string.tv_ambient_restart)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            val activity = requireActivity()
                            activity.startActivity(
                                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            )
                            activity.finish()
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
                true
            }
            findPreference<Preference>("adblock_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalAdblockSettingsFragment())
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    /** Ad blocker of Xtra TV: on / off, backup player types (video swap), hiding during ads. */
    class AdblockSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.adblock_preferences, rootKey)
            // the ad blocker always uses video swap: its switch only counts when the ad blocker is off
            val useVideoSwap = findPreference<SwitchPreferenceCompat>(C.PLAYER_USE_VIDEO_SWAP)
            val updateUseVideoSwap = { adblock: Boolean ->
                useVideoSwap?.isEnabled = !adblock
                useVideoSwap?.summary = getString(if (adblock) R.string.use_video_swap_adblock_on else R.string.use_video_swap_desc)
            }
            findPreference<SwitchPreferenceCompat>(C.PLAYER_ADBLOCK)?.let { adblock ->
                updateUseVideoSwap(adblock.isChecked)
                adblock.setOnPreferenceChangeListener { _, newValue ->
                    updateUseVideoSwap(newValue as Boolean)
                    true
                }
            }
            findPreference<Preference>("video_swap_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalVideoSwapSettingsFragment())
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class ApiTokenSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.api_token_preferences, rootKey)
            findPreference<EditTextPreference>("user_id")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.USER_ID, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.USER_ID, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("username")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.USERNAME, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.USERNAME, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("token")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.TOKEN, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.TOKEN, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("gql_token2")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_TOKEN2, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.GQL_TOKEN2, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("gql_token_web")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.GQL_TOKEN_WEB, newValue.toString())
                    }
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class DownloadSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.download_preferences, rootKey)
            findPreference<Preference>("import_app_downloads")?.setOnPreferenceClickListener {
                viewModel.importDownloads()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class UpdateSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.update_preferences, rootKey)
            findPreference<SwitchPreferenceCompat>("update_check_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    newValue == true &&
                    !requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false) &&
                    !requireContext().packageManager.canRequestPackageInstalls()
                ) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${requireContext().packageName}".toUri()
                        )
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {

                    }
                }
                true
            }
            findPreference<EditTextPreference>("update_check_frequency")?.apply {
                summary = getString(R.string.update_check_frequency_summary, text)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = getString(R.string.update_check_frequency_summary, newValue)
                    true
                }
            }
            findPreference<SwitchPreferenceCompat>("update_use_browser")?.setOnPreferenceChangeListener { _, newValue ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    newValue == false &&
                    requireContext().prefs().getBoolean(C.UPDATE_CHECK_ENABLED, true) &&
                    !requireContext().packageManager.canRequestPackageInstalls()
                ) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:${requireContext().packageName}".toUri()
                        )
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {

                    }
                }
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class DebugSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.debug_preferences, rootKey)
            findPreference<EditTextPreference>("gql_headers")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_HEADERS, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.GQL_HEADERS, newValue.toString())
                    }
                    true
                }
            }
            findPreference<Preference>("get_integrity_token")?.setOnPreferenceClickListener {
                IntegrityDialog.newInstance(null).show(childFragmentManager, null)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    listView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class SettingsSearchFragment : Fragment() {
        private var preferences: List<SettingsSearchItem>? = null
        private var adapter: SettingsSearchAdapter? = null
        private var savedQuery: String? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            return RecyclerView(requireContext()).apply {
                clipToPadding = false
                layoutManager = LinearLayoutManager(requireContext())
            }
        }

        @SuppressLint("RestrictedApi")
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true) && !TvUi.isTv(requireContext())) {
                    (view as RecyclerView).let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            (requireActivity() as? SettingsActivity)?.showSearchView(true)
            adapter = SettingsSearchAdapter(this).also {
                it.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        it.unregisterAdapterDataObserver(this)
                        it.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                                try {
                                    if (positionStart == 0) {
                                        (view as RecyclerView).scrollToPosition(0)
                                    }
                                } catch (e: Exception) {

                                }
                            }
                        })
                    }
                })
            }
            (view as RecyclerView).adapter = adapter
            if (preferences == null) {
                val list = mutableListOf<SettingsSearchItem>()
                val preferenceManager = PreferenceManager(requireContext())
                listOf(
                    Triple(R.xml.api_token_preferences, SettingsNavGraphDirections.actionGlobalApiTokenSettingsFragment(), getString(R.string.api_token_settings)),
                    Triple(R.xml.buffer_preferences, SettingsNavGraphDirections.actionGlobalBufferSettingsFragment(), getString(R.string.buffer_settings)),
                    Triple(R.xml.chat_preferences, SettingsNavGraphDirections.actionGlobalChatSettingsFragment(), getString(R.string.chat_settings)),
                    Triple(R.xml.debug_preferences, SettingsNavGraphDirections.actionGlobalDebugSettingsFragment(), getString(R.string.debug_settings)),
                    Triple(R.xml.download_preferences, SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment(), getString(R.string.download_settings)),
                    Triple(R.xml.playback_preferences, SettingsNavGraphDirections.actionGlobalPlaybackSettingsFragment(), getString(R.string.playback_settings)),
                    Triple(R.xml.player_button_preferences, SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment(), getString(R.string.player_buttons)),
                    Triple(R.xml.player_menu_preferences, SettingsNavGraphDirections.actionGlobalPlayerMenuSettingsFragment(), getString(R.string.player_menu_settings)),
                    Triple(R.xml.player_preferences, SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment(), getString(R.string.player_settings)),
                    Triple(R.xml.root_preferences, SettingsNavGraphDirections.actionGlobalSettingsFragment(), null),
                    Triple(R.xml.theme_preferences, SettingsNavGraphDirections.actionGlobalThemeSettingsFragment(), getString(R.string.theme)),
                    Triple(R.xml.ui_preferences, SettingsNavGraphDirections.actionGlobalUiSettingsFragment(), getString(R.string.ui_settings)),
                    Triple(R.xml.update_preferences, SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment(), getString(R.string.update_settings)),
                    Triple(R.xml.xtratv_preferences, SettingsNavGraphDirections.actionGlobalXtraTvSettingsFragment(), getString(R.string.xtratv_settings)),
                    Triple(R.xml.adblock_preferences, SettingsNavGraphDirections.actionGlobalAdblockSettingsFragment(), getString(R.string.adblock)),
                ).filter {
                    // no theme settings on TV
                    !(resources.getBoolean(R.bool.isTv) && it.first == R.xml.theme_preferences)
                }.forEach { item ->
                    // the settings inside the categories are found too
                    fun androidx.preference.PreferenceGroup.flatten(): List<Preference> = (0 until preferenceCount).flatMap { index ->
                        getPreference(index).let { if (it is PreferenceCategory) it.flatten() else listOf(it) }
                    }
                    preferenceManager.inflateFromResource(requireContext(), item.first, null).flatten().forEach {
                        // the settings hidden on this device are not found either
                        if (!it.isVisible || (resources.getBoolean(R.bool.isTv) && it.key in MaterialPreferenceFragment.TV_HIDDEN_KEYS)) {
                            return@forEach
                        }
                        when (it) {
                            is SwitchPreferenceCompat -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = if (it.isChecked) {
                                        getString(R.string.enabled_setting)
                                    } else {
                                        getString(R.string.disabled_setting)
                                    }
                                ))
                            }
                            is SeekBarPreference -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = it.value.toString()
                                ))
                            }
                            is PreferenceCategory -> {}
                            else -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                ))
                            }
                        }
                    }
                }
                preferences = list
            }
            requireActivity().findViewById<SearchView>(R.id.searchView)?.let {
                savedQuery?.let { query -> it.setQuery(query, true) }
                if (!resources.getBoolean(R.bool.isTv)) {
                    it.requestFocus()
                    WindowCompat.getInsetsController(requireActivity().window, it).show(WindowInsetsCompat.Type.ime())
                }
            }
        }

        fun search(query: String) {
            savedQuery = query
            if (query.isNotBlank()) {
                preferences?.filter { it.title?.contains(query, true) == true || it.summary?.contains(query, true) == true }?.let { list ->
                    adapter?.submitList(list)
                }
            } else {
                adapter?.submitList(emptyList())
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            (requireActivity() as? SettingsActivity)?.showSearchView(false)
        }
    }
}