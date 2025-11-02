package com.example.advancedvoice.feature.conversation.presentation.state

import com.example.advancedvoice.domain.entities.ConversationEntry
import com.example.advancedvoice.feature.conversation.presentation.ControlsLogic
import com.example.advancedvoice.feature.conversation.presentation.ControlsState
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.service.ConversationStore
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/**
 * Manages all UI state for the conversation feature.
 */
class ConversationStateManager(
    private val scope: CoroutineScope,  // ✅ ADDED
    private val store: ConversationStore,
    private val tts: TtsController
) {
    // User conversation state
    val conversation: StateFlow<List<ConversationEntry>> = store.state

    // TTS state
    val isSpeaking: StateFlow<Boolean> = tts.isSpeaking

    // STT states
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing

    // Generation phase
    private val _phase = MutableStateFlow(GenerationPhase.IDLE)
    val phase: StateFlow<GenerationPhase> = _phase

    // Derived controls state
    val controls: StateFlow<ControlsState> = combine(
        isSpeaking,
        isListening,
        isHearingSpeech,
        isTranscribing,
        phase
    ) { speaking, listening, hearing, transcribing, p ->
        ControlsLogic.derive(speaking, listening, hearing, transcribing, p)
    }.stateIn(
        scope,  // ✅ FIXED: Use passed scope instead of tts.scope
        SharingStarted.Eagerly,
        ControlsLogic.derive(false, false, false, false, GenerationPhase.IDLE)
    )

    // Setters for internal use
    fun setListening(value: Boolean) {
        _isListening.value = value
    }

    fun setHearingSpeech(value: Boolean) {
        _isHearingSpeech.value = value
    }

    fun setTranscribing(value: Boolean) {
        _isTranscribing.value = value
    }

    fun setPhase(value: GenerationPhase) {
        _phase.value = value
    }

    // Store delegates
    fun addUserStreamingPlaceholder() = store.addUserStreamingPlaceholder()
    fun updateLastUserStreamingText(text: String) = store.updateLastUserStreamingText(text)
    fun replaceLastUser(text: String) = store.replaceLastUser(text)
    fun addAssistant(sentences: List<String>) = store.addAssistant(sentences)
    fun appendAssistantSentences(index: Int, sentences: List<String>) =
        store.appendAssistantSentences(index, sentences)
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
}