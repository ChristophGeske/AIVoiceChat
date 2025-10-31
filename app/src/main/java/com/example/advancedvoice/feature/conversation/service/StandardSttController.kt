package com.example.advancedvoice.feature.conversation.service

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Hardened Android STT:
 * - Main-thread-only SpeechRecognizer operations.
 * - Friendly error messages (including vendor-specific code 11).
 * - Recreate recognizer on ERROR_CLIENT / ERROR_RECOGNIZER_BUSY / vendor codes.
 * - Backoff window after throttling errors to avoid immediate re-failure.
 * - Correctly handles user-initiated stops to prevent false "no-match" errors.
 */
class StandardSttController(
    private val app: Application,
    private val scope: CoroutineScope
) : SttController {

    companion object { private const val TAG = "StandardSTT" }

    private val main = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    // NEW: Implement the isTranscribing state from the interface. It's always false for this controller.
    private val _isTranscribing = MutableStateFlow(false)
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 2)
    override val transcripts: SharedFlow<String> = _transcripts

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    override val errors: SharedFlow<String> = _errors

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var starting = false
    @Volatile private var destroyed = false
    @Volatile private var userStopped = false

    @Volatile private var nextStartAllowedAt: Long = 0L
    @Volatile private var throttleStreak: Int = 0
    @Volatile private var forceLangTag: String? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "[STT_CB] onReadyForSpeech")
            _isListening.value = true
            _isHearingSpeech.value = false
            throttleStreak = 0
        }
        override fun onBeginningOfSpeech() {
            Log.i(TAG, "[STT_CB] onBeginningOfSpeech")
            _isHearingSpeech.value = true
        }
        override fun onEndOfSpeech() {
            Log.i(TAG, "[STT_CB] onEndOfSpeech")
            _isListening.value = false
            _isHearingSpeech.value = false
        }
        override fun onError(error: Int) {
            _isListening.value = false
            _isHearingSpeech.value = false

            if (userStopped) {
                Log.w(TAG, "[STT_CB] onError (code=$error) ignored because user initiated stop.")
                userStopped = false
                return
            }

            val friendly = mapErrorFriendly(error)
            Log.w(TAG, "[STT_CB] onError: $friendly (code=$error)")
            _errors.tryEmit(friendly)

            if (isVendorOrThrottle(error)) {
                throttleStreak = (throttleStreak + 1).coerceAtMost(5)
                val backoff = computeBackoffMs(throttleStreak)
                nextStartAllowedAt = System.currentTimeMillis() + backoff
                if (forceLangTag == null) forceLangTag = "en-US"
                Log.i(TAG, "Throttled. Backoff ${backoff}ms; fallbackLang=$forceLangTag")
            }
            if (shouldRecreate(error)) {
                main.post { recreateRecognizer() }
            }
        }
        override fun onResults(results: Bundle?) {
            _isListening.value = false
            _isHearingSpeech.value = false
            throttleStreak = 0
            forceLangTag = null
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            Log.i(TAG, "[STT_CB] onResults: '${text.take(120)}'")
            if (text.isNotBlank()) _transcripts.tryEmit(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
    }

    init {
        main.post { createRecognizer() }
    }

    private fun createRecognizer() {
        if (destroyed || recognizer != null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { createRecognizer() }
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer?.setRecognitionListener(listener)
        Log.i(TAG, "Recognizer created")
    }

    private fun recreateRecognizer() {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { recreateRecognizer() }
            return
        }
        recognizer?.destroy()
        recognizer = null
        createRecognizer()
    }

    private fun sttIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        val lang = forceLangTag ?: Locale.getDefault().toLanguageTag()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    override suspend fun start() {
        Log.i(TAG, "start() called.")
        userStopped = false

        val now = System.currentTimeMillis()
        if (now < nextStartAllowedAt) {
            val wait = nextStartAllowedAt - now
            Log.w(TAG, "start(): backoff active, wait ${wait}ms")
            _errors.tryEmit("Speech service busy. Please wait a moment.")
            return
        }

        if (starting || _isListening.value) {
            Log.d(TAG, "start(): ignored (already active)")
            return
        }
        starting = true
        main.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                    _errors.tryEmit("Speech recognition not available on this device.")
                    return@post
                }
                if (recognizer == null) {
                    createRecognizer()
                }
                recognizer?.startListening(sttIntent())
                Log.i(TAG, "startListening() posted")
            } catch (t: Throwable) {
                Log.e(TAG, "startListening failed: ${t.message}")
                _errors.tryEmit("Could not start speech recognition.")
                recreateRecognizer()
            } finally {
                starting = false
            }
        }
    }

    override suspend fun stop(transcribe: Boolean) {
        if (!_isListening.value && !_isHearingSpeech.value) return
        Log.w(TAG, "stop(transcribe=$transcribe) called.")
        userStopped = true
        main.post {
            recognizer?.stopListening()
            _isListening.value = false
            _isHearingSpeech.value = false
        }
    }

    override fun release() {
        Log.w(TAG, "release() called.")
        destroyed = true
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun mapErrorFriendly(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio input problem."
        SpeechRecognizer.ERROR_CLIENT -> "Speech service issue. Please tap again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK -> "Network error. Check your connection."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn’t catch that. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Retrying…"
        SpeechRecognizer.ERROR_SERVER -> "Speech service temporarily unavailable."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        else -> "Speech service busy or unsupported (code=$code)."
    }

    private fun isVendorOrThrottle(code: Int): Boolean = code !in 1..9

    private fun shouldRecreate(code: Int): Boolean = when (code) {
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
        else -> isVendorOrThrottle(code)
    }

    private fun computeBackoffMs(streak: Int): Long = (streak * 1000L).coerceAtMost(5000L)
}