package com.github.andreyasadchy.xtra.ui.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import com.github.andreyasadchy.xtra.ui.view.TvUi
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MaterialEditTextPreference : EditTextPreferenceDialogFragmentCompat() {

    private var mEditText: EditText? = null
    private val mShowSoftInputRunnable = Runnable { scheduleShowSoftInputInner() }

    private fun isSoftKeyboardDisabled(context: Context): Boolean =
        TvUi.isTv(context) && context.prefs().getBoolean(C.TV_DISABLE_SOFT_KEYBOARD, false)

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        mEditText = view.findViewById(android.R.id.edit)
        if (isSoftKeyboardDisabled(view.context)) {
            mEditText?.showSoftInputOnFocus = false
        }
    }

    private fun scheduleShowSoftInputInner() {
        mEditText?.let { mEditText ->
            if (isSoftKeyboardDisabled(mEditText.context)) return
            if (mEditText.isFocused) {
                val imm = mEditText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (!imm.showSoftInput(mEditText, 0)) {
                    mEditText.removeCallbacks(mShowSoftInputRunnable)
                    mEditText.postDelayed(mShowSoftInputRunnable, 50)
                }
            }
        }
    }

    private var mWhichButtonClicked = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        mWhichButtonClicked = DialogInterface.BUTTON_NEGATIVE
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.dialogTitle)
            .setIcon(preference.dialogIcon)
            .setPositiveButton(preference.positiveButtonText, this)
            .setNegativeButton(preference.negativeButtonText, this)
        val contentView = onCreateDialogView(requireContext())
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        } else {
            builder.setMessage(preference.dialogMessage)
        }
        onPrepareDialogBuilder(builder)

        // Create the dialog
        val dialog: Dialog = builder.create()
        if (!isSoftKeyboardDisabled(requireContext())) {
            requestInputMethod(dialog)
        }
        return dialog
    }

    private fun requestInputMethod(dialog: Dialog) {
        if (isSoftKeyboardDisabled(requireContext())) return
        val window = dialog.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window!!.decorView.windowInsetsController!!.show(WindowInsets.Type.ime())
        } else {
            scheduleShowSoftInputInner()
        }
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        mWhichButtonClicked = which
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogClosed(mWhichButtonClicked == DialogInterface.BUTTON_POSITIVE)
    }
}