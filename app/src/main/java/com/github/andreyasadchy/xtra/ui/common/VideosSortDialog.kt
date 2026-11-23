package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogVideosSortBinding
import com.github.andreyasadchy.xtra.ui.channel.clips.ChannelClipsFragment
import com.github.andreyasadchy.xtra.ui.channel.videos.ChannelVideosFragment
import com.github.andreyasadchy.xtra.ui.following.videos.FollowedVideosFragment
import com.github.andreyasadchy.xtra.ui.game.clips.GameClipsFragment
import com.github.andreyasadchy.xtra.ui.game.videos.GameVideosFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class VideosSortDialog : BottomSheetDialogFragment(), SelectLanguagesDialog.OnSelectedLanguagesChanged {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, period: String, periodText: CharSequence, type: String, typeText: CharSequence, languages: Array<String>, changed: Boolean, saveSort: Boolean, saveDefault: Boolean)
        fun deleteSavedSort()
    }

    companion object {
        const val PERIOD_DAY = "day"
        const val PERIOD_WEEK = "week"
        const val PERIOD_MONTH = "month"
        const val PERIOD_ALL = "all"
        const val SORT_TIME = "time"
        const val SORT_VIEWS = "views"
        const val VIDEO_TYPE_ALL = "all"
        const val VIDEO_TYPE_ARCHIVE = "archive"
        const val VIDEO_TYPE_HIGHLIGHT = "highlight"
        const val VIDEO_TYPE_UPLOAD = "upload"

        private const val SORT = "sort"
        private const val PERIOD = "period"
        private const val TYPE = "type"
        private const val LANGUAGES = "languages"
        private const val SAVED = "saved"

        fun newInstance(sort: String? = SORT_TIME, period: String? = PERIOD_WEEK, type: String? = VIDEO_TYPE_ALL, languages: Array<String>? = null, saved: Boolean = false): VideosSortDialog {
            return VideosSortDialog().apply {
                arguments = Bundle().apply {
                    putString(SORT, sort)
                    putString(PERIOD, period)
                    putString(TYPE, type)
                    putStringArray(LANGUAGES, languages)
                    putBoolean(SAVED, saved)
                }
            }
        }
    }

    private var _binding: DialogVideosSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    private var selectedLanguages: Array<String> = emptyArray()
    private var appliedPeriodId: Int = R.id.week
    private var appliedSortId: Int = R.id.time
    private var appliedTypeId: Int = R.id.typeAll
    private var appliedLanguages: Array<String> = emptyArray()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogVideosSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun updateLanguagesButtonLabel() {
        val b = _binding ?: return
        if (!resources.getBoolean(R.bool.isTv)) return
        b.selectLanguages.text = if (selectedLanguages.isNotEmpty()) {
            "${getString(R.string.languages)} · ${selectedLanguages.joinToString { it.uppercase() }}"
        } else {
            getString(R.string.languages)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val isTv = resources.getBoolean(R.bool.isTv)
        with(binding) {
            val args = requireArguments()
            when (parentFragment) {
                is ChannelClipsFragment -> {
                    sort.visibility = View.GONE
                    sortType.visibility = View.GONE
                    selectLanguages.visibility = View.GONE
                    saveSort.text = getString(R.string.save_sort_channel)
                    saveSortLayout.isVisible = !isTv && parentFragment?.arguments?.getString(C.CHANNEL_ID).isNullOrBlank() == false
                }
                is GameClipsFragment -> {
                    sort.visibility = View.GONE
                    sortType.visibility = View.GONE
                    saveSort.text = getString(R.string.save_sort_game)
                    saveSortLayout.isVisible = !isTv && parentFragment?.arguments?.getString(C.GAME_ID).isNullOrBlank() == false
                }
                is ChannelVideosFragment -> {
                    period.visibility = View.GONE
                    selectLanguages.visibility = View.GONE
                    saveSort.text = getString(R.string.save_sort_channel)
                    saveSortLayout.isVisible = !isTv && parentFragment?.arguments?.getString(C.CHANNEL_ID).isNullOrBlank() == false
                }
                is FollowedVideosFragment -> {
                    period.visibility = View.GONE
                    selectLanguages.visibility = View.GONE
                    saveSortLayout.visibility = View.GONE
                }
                is GameVideosFragment -> {
                    if (TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()) {
                        period.visibility = View.GONE
                    }
                    saveSort.text = getString(R.string.save_sort_game)
                    saveSortLayout.isVisible = !isTv && parentFragment?.arguments?.getString(C.GAME_ID).isNullOrBlank() == false
                }
            }
            val originalSortId = when (args.getString(SORT)) {
                SORT_TIME -> R.id.time
                SORT_VIEWS -> R.id.views
                else -> R.id.time
            }
            val originalPeriodId = when (args.getString(PERIOD)) {
                PERIOD_DAY -> R.id.today
                PERIOD_WEEK -> R.id.week
                PERIOD_MONTH -> R.id.month
                PERIOD_ALL -> R.id.all
                else -> R.id.week
            }
            val originalTypeId = when (args.getString(TYPE)) {
                VIDEO_TYPE_ALL -> R.id.typeAll
                VIDEO_TYPE_ARCHIVE -> R.id.typeArchive
                VIDEO_TYPE_HIGHLIGHT -> R.id.typeHighlight
                VIDEO_TYPE_UPLOAD -> R.id.typeUpload
                else -> R.id.typeAll
            }
            val originalLanguages = args.getStringArray(LANGUAGES) ?: emptyArray()
            appliedPeriodId = originalPeriodId
            appliedSortId = originalSortId
            appliedTypeId = originalTypeId
            appliedLanguages = originalLanguages.sortedArray()
            if (!args.getBoolean(SAVED) || isTv) {
                deleteSavedSort.visibility = View.GONE
            }
            sort.check(originalSortId)
            period.check(originalPeriodId)
            sortType.check(originalTypeId)
            selectedLanguages = originalLanguages
            if (isTv) {
                updateLanguagesButtonLabel()
                saveSortLayout.visibility = View.GONE
                saveDefault.visibility = View.GONE
                apply.visibility = View.GONE
                val singleSectionOnly = listOf(sort.isVisible, period.isVisible, sortType.isVisible, selectLanguages.isVisible).count { it } <= 1
                listOf(time, views).forEach { radio ->
                    radio.setOnClickListener {
                        sort.check(radio.id)
                        applyFilters(appliedPeriodId, appliedSortId, appliedTypeId, appliedLanguages, saveSort = false, saveDefault = true)
                        if (singleSectionOnly) dismiss()
                    }
                }
                listOf(today, week, month, all).forEach { radio ->
                    radio.setOnClickListener {
                        period.check(radio.id)
                        applyFilters(appliedPeriodId, appliedSortId, appliedTypeId, appliedLanguages, saveSort = false, saveDefault = true)
                        if (singleSectionOnly) dismiss()
                    }
                }
                listOf(typeAll, typeArchive, typeHighlight, typeUpload).forEach { radio ->
                    radio.setOnClickListener {
                        sortType.check(radio.id)
                        applyFilters(appliedPeriodId, appliedSortId, appliedTypeId, appliedLanguages, saveSort = false, saveDefault = true)
                        if (singleSectionOnly) dismiss()
                    }
                }
            }
            saveSort.setOnClickListener {
                applyFilters(originalPeriodId, originalSortId, originalTypeId, originalLanguages, saveSort = true, saveDefault = false)
                dismiss()
            }
            deleteSavedSort.setOnClickListener {
                listener.deleteSavedSort()
                deleteSavedSort.visibility = View.GONE
            }
            saveDefault.setOnClickListener {
                applyFilters(originalPeriodId, originalSortId, originalTypeId, originalLanguages, saveSort = false, saveDefault = true)
                dismiss()
            }
            apply.setOnClickListener {
                applyFilters(originalPeriodId, originalSortId, originalTypeId, originalLanguages, saveSort = false, saveDefault = false)
                dismiss()
            }
            selectLanguages.setOnClickListener {
                SelectLanguagesDialog.newInstance(selectedLanguages).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    private fun applyFilters(originalPeriodId: Int, originalSortId: Int, originalTypeId: Int, originalLanguages: Array<String>, saveSort: Boolean, saveDefault: Boolean) {
        with(binding) {
            val checkedPeriodId = period.checkedRadioButtonId
            val checkedSortId = sort.checkedRadioButtonId
            val checkedTypeId = sortType.checkedRadioButtonId
            val langs = selectedLanguages.sortedArray()
            val sortBtn = requireView().findViewById<RadioButton>(checkedSortId)
            val periodBtn = requireView().findViewById<RadioButton>(checkedPeriodId)
            val typeBtn = requireView().findViewById<RadioButton>(checkedTypeId)
            val hasChanged = checkedPeriodId != originalPeriodId ||
                checkedSortId != originalSortId ||
                checkedTypeId != originalTypeId ||
                !langs.contentEquals(originalLanguages.sortedArray())
            appliedPeriodId = checkedPeriodId
            appliedSortId = checkedSortId
            appliedTypeId = checkedTypeId
            appliedLanguages = langs
            listener.onChange(
                when (checkedSortId) {
                    R.id.time -> SORT_TIME
                    R.id.views -> SORT_VIEWS
                    else -> SORT_TIME
                },
                sortBtn.text,
                when (checkedPeriodId) {
                    R.id.today -> PERIOD_DAY
                    R.id.week -> PERIOD_WEEK
                    R.id.month -> PERIOD_MONTH
                    R.id.all -> PERIOD_ALL
                    else -> PERIOD_WEEK
                },
                periodBtn.text,
                when (checkedTypeId) {
                    R.id.typeAll -> VIDEO_TYPE_ALL
                    R.id.typeArchive -> VIDEO_TYPE_ARCHIVE
                    R.id.typeHighlight -> VIDEO_TYPE_HIGHLIGHT
                    R.id.typeUpload -> VIDEO_TYPE_UPLOAD
                    else -> VIDEO_TYPE_ALL
                },
                typeBtn.text,
                langs,
                hasChanged,
                saveSort,
                saveDefault
            )
        }
    }

    override fun onChange(languages: Array<String>) {
        selectedLanguages = languages
        if (resources.getBoolean(R.bool.isTv) && _binding != null) {
            updateLanguagesButtonLabel()
            applyFilters(appliedPeriodId, appliedSortId, appliedTypeId, appliedLanguages, saveSort = false, saveDefault = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
