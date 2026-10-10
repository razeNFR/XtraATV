package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech to text inside the app (no system popup): the words show up in the field while they are said,
 * like the voice search of the YouTube app.
 */
class TvVoiceInput(
    context: Context,
    private val onListening: (Boolean) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String?) -> Unit,
) {
    private val recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(context)) {
        try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            null
        }
    } else null

    val isAvailable: Boolean
        get() = recognizer != null

    var isListening = false
        private set

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // about -2 (silence) to 10 (loud)
                onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                finish(null)
            }

            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() })
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun finish(text: String?) {
        if (isListening) {
            isListening = false
            onListening(false)
            onLevel(0f)
            onResult(text)
        }
    }

    fun start() {
        val recognizer = recognizer ?: return
        if (isListening) {
            recognizer.stopListening()
            return
        }
        isListening = true
        onListening(true)
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun cancel() {
        if (isListening) {
            isListening = false
            recognizer?.cancel()
            onListening(false)
            onLevel(0f)
        }
    }

    fun destroy() {
        isListening = false
        recognizer?.destroy()
    }
}
