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

    companion object {
        private const val TAG = "TTS"  // ✅ Shorter for easier filtering
    }

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
            Log.i(TAG, "▶️ TTS started (id=${utteranceId?.take(8)})")
            _isSpeaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            Log.i(TAG, "⏹️ TTS finished (id=${utteranceId?.take(8)}, queue=${queue.size})")
            scope.launch {
                if (utteranceId != currentId) {
                    Log.w(TAG, "⚠️ Mismatched utterance ID, ignoring onDone")
                    return@launch
                }
                currentId = null
                if (queue.isNotEmpty()) {
                    Log.d(TAG, "▶️ Playing next from queue (${queue.size} remaining)")
                    speakNext()
                } else {
                    _isSpeaking.value = false
                    Log.i(TAG, "✅ Queue empty, emitting queueFinished")
                    _queueFinished.emit(Unit)
                }
            }
        }

        override fun onError(utteranceId: String?) {
            Log.e(TAG, "❌ TTS error (id=${utteranceId?.take(8)})")
            onDone(utteranceId)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "✅ TTS initialized successfully")
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(listener)
            _isReady.value = true
            if (queue.isNotEmpty() && currentId == null && !_isSpeaking.value) {
                Log.i(TAG, "▶️ Queue has ${queue.size} pending items, starting playback")
                speakNext()
            }
        } else {
            Log.e(TAG, "❌ TTS initialization FAILED (status=$status)")
            _isReady.value = false
        }
    }

    fun queue(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "⚠️ Ignoring blank text")
            return
        }
        queue.addLast(text)
        Log.i(TAG, "➕ Queued ${text.length} chars (queue=${queue.size}): '${text.take(60)}...'")
        if (_isReady.value && !_isSpeaking.value && currentId == null) {
            Log.i(TAG, "▶️ TTS idle, playing immediately")
            speakNext()
        }
    }

    private fun speakNext() {
        if (!_isReady.value || queue.isEmpty() || currentId != null) return
        val next = queue.removeFirst()
        val id = UUID.randomUUID().toString()
        currentId = id
        Log.i(TAG, "▶️ Speaking (id=${id.take(8)}, len=${next.length}): '${next.take(60)}...'")
        tts.speak(next, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stop() {
        Log.w(TAG, "🛑 STOP called (queue=${queue.size}, speaking=${_isSpeaking.value})")
        try {
            tts.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS: ${e.message}")
        }

        _isSpeaking.value = false
        currentId = null
        queue.clear()
    }

    fun clearQueue() {
        Log.i(TAG, "🗑️ Clearing queue (${queue.size} items)")
        queue.clear()
    }

    fun stopCurrent() {
        Log.i(TAG, "⏸️ Stopping current speech only")
        try {
            tts.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping current: ${e.message}")
        }

        _isSpeaking.value = false
        currentId = null
    }

    fun shutdown() {
        Log.w(TAG, "🔌 Shutting down TTS")
        stop()
        try {
            tts.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown: ${e.message}")
        }
    }
}