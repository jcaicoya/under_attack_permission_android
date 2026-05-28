package com.cuarzopolar.permission.capture

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous speech recognition. Restarts automatically after each result or error.
 * Must be created and used on the main thread (SpeechRecognizer requirement).
 */
class SpeechManager(
    private val context: Context,
    private val onTranscript: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { start() }
            return
        }
        if (active) return
        active = true
        Log.d("SpeechManager", "Starting continuous recognition")
        listen()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop() }
            return
        }
        active = false
        recognizer?.destroy()
        recognizer = null
        Log.d("SpeechManager", "Stopped")
    }

    private fun listen() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { listen() }
            return
        }
        if (!active) return

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    Log.d("SpeechManager", "Result: $text")
                    onTranscript(text)
                }
                // Restart immediately
                handler.post { listen() }
            }

            override fun onError(error: Int) {
                val label = errorLabel(error)
                Log.d("SpeechManager", "Error: $label — restarting")
                // Short pause before restart to avoid tight loop on persistent errors
                handler.postDelayed({ listen() }, 800)
            }

            override fun onPartialResults(partial: Bundle) {
                val matches = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    Log.d("SpeechManager", "Partial: $text")
                    onTranscript("[$text]")
                }
            }

            // Unused callbacks
            override fun onReadyForSpeech(p: Bundle)  {
                // Unmute STREAM_MUSIC now that the beep has been suppressed
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            override fun onBeginningOfSpeech()         {}
            override fun onRmsChanged(v: Float)        {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech()               {}
            override fun onEvent(type: Int, b: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep listening longer before timing out
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        // Mute STREAM_MUSIC to suppress the SpeechRecognizer "ready" beep
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        recognizer?.startListening(intent)
    }

    private fun errorLabel(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO                -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT               -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
        SpeechRecognizer.ERROR_NETWORK              -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT      -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH             -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY      -> "BUSY"
        SpeechRecognizer.ERROR_SERVER               -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT       -> "SPEECH_TIMEOUT"
        else                                        -> "UNKNOWN($code)"
    }
}
