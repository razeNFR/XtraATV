package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResult

/** Speech to text through the system recognizer, used for voice search and chat messages on TV. */
object TvVoice {

    fun createIntent(prompt: String?): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            prompt?.let { putExtra(RecognizerIntent.EXTRA_PROMPT, it) }
        }
    }

    fun isAvailable(context: Context): Boolean {
        return createIntent(null).resolveActivity(context.packageManager) != null
    }

    fun getText(result: ActivityResult): String? {
        return result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
