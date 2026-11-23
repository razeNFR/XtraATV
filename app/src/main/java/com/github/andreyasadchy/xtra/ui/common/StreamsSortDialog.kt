package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogStreamsSortBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.game.streams.GameStreamsFragment
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

class StreamsSortDialog : BottomSheetDialogFragment(), SearchTagsDialog.OnTagSelectedListener, SelectLanguagesDialog.OnSelectedLanguagesChanged {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, tags: Array<String>, languages: Array<String>, changed: Boolean, saveFilters: Boolean, saveSort: Boolean, saveDefault: Boolean)
        fun deleteSavedSort()
    }

    companion object {
        const val SORT_VIEWERS = "VIEWER_COUNT"
        const val SORT_VIEWERS_ASC = "VIEWER_COUNT_ASC"
        const val RECENT = "RECENT"
        /** "Recommended for you": the order Twitch chooses for the logged in user (only offered when logged in). */
        const val SORT_RELEVANCE = "RELEVANCE"

        private const val SORT = "sort"
        private const val TAGS = "tags"
        private const val LANGUAGES = "languages"
        private const val SAVED = "saved"

        fun newInstance(sort: String?, tags: Array<String>?, languages: Array<String>?, saved: Boolean = false): StreamsSortDialog {
            return StreamsSortDialog().apply {
                arguments = Bundle().apply {
                    putString(SORT, sort)
                    putStringArray(TAGS, tags)
                    putStringArray(LANGUAGES, languages)
                    putBoolean(SAVED, saved)
                }
            }
        }
    }

    private var _binding: DialogStreamsSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    private var selectedTags = mutableListOf<String>()
    private var selectedLanguages: Array<String> = emptyArray()
    private var appliedSortId: Int = R.id.viewers_high
    private var appliedTags: Array<String> = emptyArray()
    private var appliedLanguages: Array<String> = emptyArray()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogStreamsSortBinding.inflate(inflater, container, false)
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

    private fun addTagChip(name: String, isTv: Boolean) {
        val b = _binding ?: return
        b.tagGroup.addView(
            Chip(requireContext()).apply {
                text = name
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    selectedTags.remove(name)
                    b.tagGroup.removeView(this)
                    if (isTv) {
                        applyTvNow(dismissAfter = false)
                    }
                }
            }
        )
    }

    private fun applyTvNow(dismissAfter: Boolean) {
        if (_binding == null) return
        applyFilters(appliedSortId, appliedTags, appliedLanguages, saveFilters = false, saveSort = false, saveDefault = true)
        if (dismissAfter) {
            dismiss()
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
                is GameStreamsFragment -> {
                    saveSortLayout.isVisible = !isTv && parentFragment?.arguments?.getString(C.GAME_ID).isNullOrBlank() == false
                }
                is TopStreamsFragment -> {
                    saveSortLayout.visibility = View.GONE
                }
            }
            val loggedIn = TwitchApiHelper.isLoggedIn(requireContext())
            recommended.isVisible = loggedIn
            val originalSortId = when (args.getString(SORT)) {
                SORT_RELEVANCE -> if (loggedIn) R.id.recommended else R.id.viewers_high
                SORT_VIEWERS -> R.id.viewers_high
                SORT_VIEWERS_ASC -> R.id.viewers_low
                RECENT -> R.id.recent
                else -> R.id.viewers_high
            }
            val originalTags = args.getStringArray(TAGS) ?: emptyArray()
            val originalLanguages = args.getStringArray(LANGUAGES) ?: emptyArray()
            appliedSortId = originalSortId
            appliedTags = originalTags.sortedArray()
            appliedLanguages = originalLanguages.sortedArray()
            if (!args.getBoolean(SAVED) || isTv) {
                deleteSavedSort.visibility = View.GONE
            }
            sort.check(originalSortId)
            selectedTags = originalTags.toMutableList()
            selectedLanguages = originalLanguages
            originalTags.forEach { name ->
                addTagChip(name, isTv)
            }
            if (isTv) {
                selectTags.text = getString(R.string.tags_filter)
                updateLanguagesButtonLabel()
                saveFilters.visibility = View.GONE
                saveSortLayout.visibility = View.GONE
                saveDefault.visibility = View.GONE
                apply.visibility = View.GONE
                listOf(recommended, viewersHigh, viewersLow, recent).forEach { radio ->
                    radio.setOnClickListener {
                        sort.check(radio.id)
                        applyTvNow(dismissAfter = true)
                    }
                }
            }
            selectTags.setOnClickListener {
                SearchTagsDialog.newInstance(false).show(childFragmentManager, null)
            }
            selectLanguages.setOnClickListener {
                SelectLanguagesDialog.newInstance(selectedLanguages).show(childFragmentManager, "closeOnPip")
            }
            saveFilters.setOnClickListener {
                applyFilters(originalSortId, originalTags, originalLanguages, saveFilters = true, saveSort = false, saveDefault = false)
                dismiss()
            }
            saveSort.setOnClickListener {
                applyFilters(originalSortId, originalTags, originalLanguages, saveFilters = false, saveSort = true, saveDefault = false)
                dismiss()
            }
            deleteSavedSort.setOnClickListener {
                listener.deleteSavedSort()
                deleteSavedSort.visibility = View.GONE
            }
            saveDefault.setOnClickListener {
                applyFilters(originalSortId, originalTags, originalLanguages, saveFilters = false, saveSort = false, saveDefault = true)
                dismiss()
            }
            apply.setOnClickListener {
                applyFilters(originalSortId, originalTags, originalLanguages, saveFilters = false, saveSort = false, saveDefault = false)
                dismiss()
            }
        }
    }

    private fun applyFilters(originalSortId: Int, originalTags: Array<String>, originalLanguages: Array<String>, saveFilters: Boolean, saveSort: Boolean, saveDefault: Boolean) {
        with(binding) {
            val checkedSortId = sort.checkedRadioButtonId
            val tags = selectedTags.toTypedArray().sortedArray()
            val langs = selectedLanguages.sortedArray()
            val sortBtn = requireView().findViewById<RadioButton>(checkedSortId)
            val hasChanged = checkedSortId != originalSortId || !tags.contentEquals(originalTags.sortedArray()) || !langs.contentEquals(originalLanguages.sortedArray())
            appliedSortId = checkedSortId
            appliedTags = tags
            appliedLanguages = langs
            listener.onChange(
                when (checkedSortId) {
                    R.id.recommended -> SORT_RELEVANCE
                    R.id.viewers_high -> SORT_VIEWERS
                    R.id.viewers_low -> SORT_VIEWERS_ASC
                    R.id.recent -> RECENT
                    else -> SORT_VIEWERS
                },
                sortBtn.text,
                tags,
                langs,
                hasChanged,
                saveFilters,
                saveSort,
                saveDefault
            )
        }
    }

    override fun onTagSelected(tag: Tag) {
        val isTv = resources.getBoolean(R.bool.isTv)
        tag.name?.let { name ->
            if (!selectedTags.contains(name)) {
                selectedTags.add(name)
                addTagChip(name, isTv)
                if (isTv) {
                    applyTvNow(dismissAfter = false)
                }
            }
        }
    }

    override fun onChange(languages: Array<String>) {
        selectedLanguages = languages
        if (resources.getBoolean(R.bool.isTv)) {
            updateLanguagesButtonLabel()
            applyTvNow(dismissAfter = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}