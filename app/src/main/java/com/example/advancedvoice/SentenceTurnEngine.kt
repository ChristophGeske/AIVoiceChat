// SentenceTurnEngine.kt
package com.example.advancedvoice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Orchestrates the conversation turn, deciding which generation strategy to use
 * based on user settings. It manages conversation history and state.
 */
class SentenceTurnEngine(
    private val uiScope: CoroutineScope,
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val systemPromptProvider: () -> String,
    val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "SentenceTurnEngine"
    }

    data class Callbacks(
        val onStreamDelta: (String) -> Unit,
        val onStreamSentence: (String) -> Unit,
        val onFirstSentence: (String) -> Unit,
        val onRemainingSentences: (List<String>) -> Unit,
        val onFinalResponse: (String) -> Unit,
        val onTurnFinish: () -> Unit,
        val onSystem: (String) -> Unit,
        val onError: (String) -> Unit
    )

    data class Msg(val role: String, val text: String)

    private val history = ArrayList<Msg>()
    private var maxSentencesPerTurn = 4
    private var fasterFirst: Boolean = false
    private var currentStrategy: IGenerationStrategy? = null
    @Volatile private var active = false

    fun setFasterFirst(enabled: Boolean) {
        fasterFirst = enabled
        Log.i(TAG, "Config: fasterFirst=$fasterFirst")
    }

    fun setMaxSentences(n: Int) {
        maxSentencesPerTurn = n.coerceIn(1, 10)
        Log.i(TAG, "Config: maxSentencesPerTurn=$maxSentencesPerTurn")
    }

    fun isActive(): Boolean = active

    fun abort() {
        if (!active) return
        Log.i(TAG, "Abort requested.")
        active = false
        currentStrategy?.abort()
        // Ensure UI is unlocked and messaged on the main thread
        uiScope.launch {
            callbacks.onSystem("Generation interrupted.")
            callbacks.onTurnFinish()
        }
    }

    /**
     * Inject a partial assistant draft into history (used on user barge-in).
     * If the last message is already from the assistant, it will be replaced with the draft.
     */
    fun injectAssistantDraft(text: String) {
        addAssistant(text)
        Log.i(TAG, "Injected assistant draft into history (len=${text.length})")
    }

    fun startTurn(userText: String, modelName: String) {
        if (userText.isBlank()) return
        if (active) abort()

        active = true
        addUser(userText)
        Log.i(TAG, "Start turn: model=$modelName fasterFirst=$fasterFirst historySize=${history.size} maxSentences=$maxSentencesPerTurn")

        currentStrategy = if (fasterFirst) {
            Log.i(TAG, "Strategy=FastFirstSentenceStrategy")
            FastFirstSentenceStrategy(http, geminiKeyProvider, openAiKeyProvider)
        } else {
            Log.i(TAG, "Strategy=RegularGenerationStrategy")
            RegularGenerationStrategy(http, geminiKeyProvider, openAiKeyProvider)
        }

        // Wrap ALL callbacks to guarantee main-thread UI access and add trace logs
        val strategyCallbacks = Callbacks(
            onStreamDelta = { delta ->
                uiScope.launch {
                    Log.d(TAG, "onStreamDelta(len=${delta.length})")
                    callbacks.onStreamDelta(delta)
                }
            },
            onStreamSentence = { sentence ->
                uiScope.launch {
                    Log.d(TAG, "onStreamSentence: '${sentence.take(60)}...'")
                    callbacks.onStreamSentence(sentence)
                }
            },
            onFirstSentence = { firstSentence ->
                uiScope.launch {
                    Log.d(TAG, "onFirstSentence: '${firstSentence.take(80)}...'")
                    addAssistant(firstSentence) // Add placeholder to history
                    callbacks.onFirstSentence(firstSentence)
                }
            },
            onRemainingSentences = { sentences ->
                uiScope.launch {
                    Log.d(TAG, "onRemainingSentences: count=${sentences.size}")
                    callbacks.onRemainingSentences(sentences)
                }
            },
            onFinalResponse = { fullText ->
                uiScope.launch {
                    Log.d(TAG, "onFinalResponse(len=${fullText.length})")
                    addAssistant(fullText) // Update history with final full text
                    callbacks.onFinalResponse(fullText)
                }
            },
            onTurnFinish = {
                uiScope.launch {
                    if (active) {
                        Log.d(TAG, "onTurnFinish (marking inactive)")
                        active = false
                        callbacks.onTurnFinish()
                    } else {
                        Log.d(TAG, "onTurnFinish skipped (already inactive)")
                    }
                }
            },
            onSystem = { msg ->
                uiScope.launch {
                    Log.d(TAG, "onSystem: ${msg.take(120)}")
                    callbacks.onSystem(msg)
                }
            },
            onError = { msg ->
                uiScope.launch {
                    Log.e(TAG, "onError: ${msg.take(200)}")
                    callbacks.onError(msg)
                }
            }
        )

        currentStrategy?.execute(
            scope = uiScope,
            history = ArrayList(history), // Pass a defensive copy
            modelName = modelName,
            systemPrompt = systemPromptProvider(),
            maxSentences = maxSentencesPerTurn,
            callbacks = strategyCallbacks
        )
    }

    fun clearHistory() {
        Log.i(TAG, "History cleared")
        history.clear()
    }

    private fun addUser(text: String) {
        history.add(Msg("user", text))
        Log.d(TAG, "History +user (len=${text.length}) total=${history.size}")
    }

    private fun addAssistant(text: String) {
        if (history.lastOrNull()?.role == "assistant") {
            history[history.lastIndex] = Msg("assistant", text)
            Log.d(TAG, "History updated last assistant (len=${text.length}) total=${history.size}")
        } else {
            history.add(Msg("assistant", text))
            Log.d(TAG, "History +assistant (len=${text.length}) total=${history.size}")
        }
    }
}