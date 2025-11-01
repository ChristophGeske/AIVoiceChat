package com.example.advancedvoice.feature.conversation.service

import com.example.advancedvoice.domain.entities.ConversationEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConversationStore {

    private val list = mutableListOf<ConversationEntry>()
    private val _state = MutableStateFlow<List<ConversationEntry>>(emptyList())
    val state: StateFlow<List<ConversationEntry>> = _state

    fun addUser(text: String): Int {
        val e = ConversationEntry("You", listOf(text), isAssistant = false, streamingText = null)
        list.add(e); publish()
        return list.lastIndex
    }

    fun addUserStreamingPlaceholder(): Int {
        val e = ConversationEntry("You", emptyList(), isAssistant = false, streamingText = "")
        list.add(e); publish()
        return list.lastIndex
    }

    fun updateLastUserStreamingText(streamingText: String) {
        val idx = list.indexOfLast { it.speaker == "You" }
        if (idx >= 0) {
            val current = list[idx]
            list[idx] = current.copy(streamingText = streamingText)
            publish()
        }
    }

    fun replaceLastUser(text: String) {
        val idx = list.indexOfLast { it.speaker == "You" }
        if (idx >= 0) {
            list[idx] = ConversationEntry("You", listOf(text), isAssistant = false, streamingText = null)
            publish()
        } else {
            addUser(text)
        }
    }

    fun addAssistant(sentences: List<String>): Int {
        val e = ConversationEntry("Assistant", sentences, isAssistant = true)
        list.add(e); publish()
        return list.lastIndex
    }

    fun appendAssistantSentences(index: Int, sentences: List<String>) {
        val cur = list.getOrNull(index) ?: return
        if (!cur.isAssistant) return
        list[index] = cur.copy(sentences = cur.sentences + sentences, streamingText = null)
        publish()
    }

    fun addSystem(text: String) {
        list.add(ConversationEntry("System", listOf(text), isAssistant = false)); publish()
    }

    fun addError(text: String) {
        list.add(ConversationEntry("Error", listOf(text), isAssistant = false)); publish()
    }

    fun clear() {
        list.clear(); publish()
    }

    private fun publish() {
        _state.value = list.toList()
    }

    fun replaceLastAssistant(sentences: List<String>) {
        val idx = list.indexOfLast { it.isAssistant }
        if (idx >= 0) {
            val current = list[idx]
            list[idx] = current.copy(sentences = sentences, streamingText = null)
            publish()
        } else {
            addAssistant(sentences)
        }
    }
}