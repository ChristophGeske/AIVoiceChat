package com.example.advancedvoice.feature.conversation.presentation.state

import android.util.Log
import com.example.advancedvoice.domain.entities.ConversationEntry
import com.example.advancedvoice.feature.conversation.presentation.ControlsLogic
import com.example.advancedvoice.feature.conversation.presentation.ControlsState
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.service.ConversationStore
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ConversationStateManager(
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val tts: TtsController
) {
    private companion object { const val TAG = "StateManager" }

    @Volatile private var isHardStopped = false

    val conversation: StateFlow<List<ConversationEntry>> = store.state
    val isSpeaking: StateFlow<Boolean> = tts.isSpeaking

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing

    private val _phase = MutableStateFlow(GenerationPhase.IDLE)
    val phase: StateFlow<GenerationPhase> = _phase

    val controls: StateFlow<ControlsState> = combine(
        isSpeaking,
        isListening,
        isHearingSpeech,
        isTranscribing,
        phase
    ) { speaking, listening, hearing, transcribing, p ->
        ControlsLogic.derive(speaking, listening, hearing, transcribing, p)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        ControlsLogic.derive(false, false, false, false, GenerationPhase.IDLE)
    )

    init {
        // ✅ Enhanced debug logging
        scope.launch {
            combine(
                isListening,
                isHearingSpeech,
                isTranscribing,
                isSpeaking,
                phase
            ) { listening, hearing, transcribing, speaking, ph ->
                StateSnapshot(listening, hearing, transcribing, speaking, ph)
            }.collect { snapshot ->
                val btnText = controls.value.speakButtonText
                Log.d(TAG, "[State] L=${snapshot.listening} H=${snapshot.hearing} T=${snapshot.transcribing} S=${snapshot.speaking} P=${snapshot.phase} → \"$btnText\"")
            }
        }
    }

    fun setHardStop(stop: Boolean) {
        if (isHardStopped == stop) return
        Log.w(TAG, "[UI State] Hard Stop -> $stop")
        isHardStopped = stop
        if (stop) {
            tts.stop()
        }
    }

    fun setListening(value: Boolean) {
        if (_isListening.value != value) {
            Log.d(TAG, "[UI State] isListening -> $value")
            _isListening.value = value
        }
    }

    fun setHearingSpeech(value: Boolean) {
        if (_isHearingSpeech.value != value) {
            Log.d(TAG, "[UI State] isHearingSpeech -> $value")
            _isHearingSpeech.value = value
        }
    }

    fun setTranscribing(value: Boolean) {
        if (_isTranscribing.value != value) {
            Log.d(TAG, "[UI State] isTranscribing -> $value")
            _isTranscribing.value = value
        }
    }

    fun setPhase(value: GenerationPhase) {
        if (_phase.value != value) {
            Log.d(TAG, "[UI State] phase -> $value")
            _phase.value = value
        }
    }

    fun addUserStreamingPlaceholder() = store.addUserStreamingPlaceholder()
    fun updateLastUserStreamingText(text: String) = store.updateLastUserStreamingText(text)
    fun replaceLastUser(text: String) = store.replaceLastUser(text)

    fun addAssistant(sentences: List<String>) {
        if (isHardStopped) {
            Log.w(TAG, "🛑 Hard-stop is active, ignoring addAssistant call.")
            return
        }
        store.addAssistant(sentences)
    }

    fun appendAssistantSentences(index: Int, sentences: List<String>) {
        if (isHardStopped) {
            Log.w(TAG, "🛑 Hard-stop is active, ignoring appendAssistantSentences call.")
            return
        }
        store.appendAssistantSentences(index, sentences)
    }

    fun addSystem(text: String) = store.addSystem(text)
    fun addError(text: String) = store.addError(text)
    fun removeLastEntry() = store.removeLastEntry()
    fun clear() = store.clear()

    fun removeLastUserPlaceholderIfEmpty() {
        val lastEntry = conversation.value.lastOrNull()
        if (lastEntry != null &&
            lastEntry.speaker == "You" &&
            lastEntry.sentences.isEmpty() &&
            lastEntry.streamingText.isNullOrBlank()) {
            store.removeLastEntry()
        }
    }

    private data class StateSnapshot(
        val listening: Boolean,
        val hearing: Boolean,
        val transcribing: Boolean,
        val speaking: Boolean,
        val phase: GenerationPhase
    )
}