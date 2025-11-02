package com.example.advancedvoice.feature.conversation.service

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class TtsController(
    app: Application,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener {

    companion object { private const val TAG = "TtsController" }

    private val tts: TextToSpeech = TextToSpeech(app, this)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _queueFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueFinished: SharedFlow<Unit> = _queueFinished

    private val queue = ArrayDeque<String>()
    private var currentId: String? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.i(TAG, "[TTS] onStart: utteranceId=$utteranceId")
            _isSpeaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            Log.i(TAG, "[TTS] onDone: utteranceId=$utteranceId")
            scope.launch {
                if (utteranceId != currentId) {
                    Log.w(TAG, "[TTS] Ignoring onDone for mismatched ID.")
                    return@launch
                }
                currentId = null
                if (queue.isNotEmpty()) {
                    speakNext()
                } else {
                    _isSpeaking.value = false
                    Log.i(TAG, "[TTS] Queue is empty. Emitting queueFinished event.")
                    _queueFinished.emit(Unit)
                }
            }
        }

        override fun onError(utteranceId: String?) {
            Log.e(TAG, "[TTS] onError: utteranceId=$utteranceId")
            onDone(utteranceId)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "[TTS] Initialization SUCCESSFUL.")
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(listener)
            _isReady.value = true
            if (queue.isNotEmpty() && currentId == null && !_isSpeaking.value) {
                Log.i(TAG, "[TTS] onInit: Queue has pending items, starting playback now.")
                speakNext()
            }
        } else {
            Log.e(TAG, "[TTS] Initialization FAILED with status: $status")
            _isReady.value = false
        }
    }

    fun queue(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "[TTS] queue() called with blank text. Ignoring.")
            return
        }
        queue.addLast(text)
        Log.i(TAG, "[TTS] Queued ${text.length} chars. New queue size: ${queue.size}. Text: '${text.take(80)}...'")
        if (_isReady.value && !_isSpeaking.value && currentId == null) {
            Log.i(TAG, "[TTS] TTS is idle, speaking immediately.")
            speakNext()
        }
    }

    private fun speakNext() {
        if (!_isReady.value || queue.isEmpty() || currentId != null) return
        val next = queue.removeFirst()
        val id = UUID.randomUUID().toString()
        currentId = id
        Log.i(TAG, "[TTS] speakNext() called. UtteranceId=$id, Text: '${next.take(80)}...'")
        tts.speak(next, TextToSpeech.QUEUE_ADD, null, id)
    }

    /**
     * Stop current speech and clear queue.
     * ✅ For immediate interruption (user interrupts assistant).
     */
    fun stop() {
        Log.w(TAG, "[TTS] stop() called. Halting playback and clearing queue.")
        try {
            tts.stop()
        } catch (_: Exception) {}

        _isSpeaking.value = false
        currentId = null
        queue.clear()
    }

    /**
     * Clear only the queue but let current speech finish.
     * ✅ For low-latency: allows current sentence to finish naturally.
     */
    fun clearQueue() {
        Log.i(TAG, "[TTS] clearQueue() called. Clearing ${queue.size} pending items.")
        queue.clear()
        // currentId remains - current speech will finish
    }

    /**
     * Stop current speech but keep queue.
     * ✅ For pause functionality (if needed later).
     */
    fun stopCurrent() {
        Log.i(TAG, "[TTS] stopCurrent() called. Stopping current speech only.")
        try {
            tts.stop()
        } catch (_: Exception) {}

        _isSpeaking.value = false
        currentId = null
        // queue remains - can resume with speakNext()
    }

    fun shutdown() {
        Log.w(TAG, "[TTS] shutdown() called.")
        stop()
        try {
            tts.shutdown()
        } catch (_: Exception) {}
    }
}