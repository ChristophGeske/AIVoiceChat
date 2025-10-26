package com.example.advancedvoice.domain.engine

import android.util.Log
import com.example.advancedvoice.domain.strategy.FastFirstSentenceStrategy
import com.example.advancedvoice.domain.strategy.IGenerationStrategy
import com.example.advancedvoice.domain.strategy.RegularGenerationStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Orchestrates a conversation turn. Picks a generation strategy based on settings,
 * forwards callbacks, and maintains the conversation history to supply to models.
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

    data class Msg(val role: String, val text: String)

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

    /**
     * Abort the current turn. If silent=true, do not post a "Generation interrupted." system message.
     */
    fun abort(silent: Boolean = false) {
        if (!active) return
        Log.i(TAG, "Abort requested. silent=$silent")
        active = false
        currentStrategy?.abort()
        uiScope.launch {
            if (!silent) callbacks.onSystem("Generation interrupted.")
            callbacks.onTurnFinish()
        }
    }

    fun seedHistory(seed: List<Msg>) {
        history.clear()
        history.addAll(seed)
        Log.i(TAG, "Seeded history size=${history.size}")
    }

    fun getHistorySnapshot(): List<Msg> = ArrayList(history)

    /**
     * Inject partial assistant text into history (for barge-in/draft preservation).
     */
    fun injectAssistantDraft(text: String) {
        addAssistant(text)
        Log.i(TAG, "Injected assistant draft into history (len=${text.length})")
    }

    /**
     * Replace the last user message in history (for interruption combining).
     * If the last message is not user, add a user message instead.
     */
    fun replaceLastUserMessage(newText: String) {
        if (history.isNotEmpty() && history.last().role == "user") {
            history[history.lastIndex] = Msg("user", newText)
            Log.d(TAG, "History replaced last user message (len=${newText.length}) total=${history.size}")
        } else {
            history.add(Msg("user", newText))
            Log.d(TAG, "History +user (no previous to replace) (len=${newText.length}) total=${history.size}")
        }
    }

    fun startTurn(userText: String, modelName: String) {
        if (userText.isBlank()) return
        if (active) {
            Log.w(TAG, "startTurn called while already active - aborting previous turn")
            abort(silent = true)
        }

        active = true
        addUser(userText)

        Log.i(TAG, "=== TURN START === model=$modelName fasterFirst=$fasterFirst historySize=${history.size} maxSentences=$maxSentencesPerTurn")

        currentStrategy = createStrategy()
        currentStrategy?.execute(
            scope = uiScope,
            history = ArrayList(history), // defensive copy
            modelName = modelName,
            systemPrompt = systemPromptProvider(),
            maxSentences = maxSentencesPerTurn,
            callbacks = wrapCallbacks()
        )
    }

    /**
     * Start a turn using the existing history (used after replacing last user message).
     */
    fun startTurnWithCurrentHistory(modelName: String) {
        if (active) {
            Log.w(TAG, "startTurnWithCurrentHistory called while already active - aborting previous turn")
            abort(silent = true)
        }

        active = true
        Log.i(TAG, "=== TURN START (with existing history) === model=$modelName fasterFirst=$fasterFirst historySize=${history.size} maxSentences=$maxSentencesPerTurn")

        currentStrategy = createStrategy()
        currentStrategy?.execute(
            scope = uiScope,
            history = ArrayList(history),
            modelName = modelName,
            systemPrompt = systemPromptProvider(),
            maxSentences = maxSentencesPerTurn,
            callbacks = wrapCallbacks()
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

    private fun createStrategy(): IGenerationStrategy {
        return if (fasterFirst) {
            Log.i(TAG, "Strategy=FastFirstSentenceStrategy")
            FastFirstSentenceStrategy(
                http = http,
                geminiKeyProvider = geminiKeyProvider,
                openAiKeyProvider = openAiKeyProvider
            )
        } else {
            Log.i(TAG, "Strategy=RegularGenerationStrategy")
            RegularGenerationStrategy(
                http = http,
                geminiKeyProvider = geminiKeyProvider,
                openAiKeyProvider = openAiKeyProvider
            )
        }
    }

    /**
     * Wrap callbacks so all UI updates happen on uiScope and only while active.
     */
    private fun wrapCallbacks(): Callbacks {
        return Callbacks(
            onStreamDelta = { delta ->
                if (active) {
                    uiScope.launch {
                        Log.d(TAG, "→ onStreamDelta(len=${delta.length})")
                        callbacks.onStreamDelta(delta)
                    }
                }
            },
            onStreamSentence = { sentence ->
                if (active) {
                    uiScope.launch {
                        Log.d(TAG, "→ onStreamSentence: '${sentence.take(60)}...'")
                        callbacks.onStreamSentence(sentence)
                    }
                }
            },
            onFirstSentence = { firstSentence ->
                if (active) {
                    uiScope.launch {
                        Log.i(TAG, "→ onFirstSentence: '${firstSentence.take(80)}...'")
                        addAssistant(firstSentence)
                        callbacks.onFirstSentence(firstSentence)
                    }
                }
            },
            onRemainingSentences = { sentences ->
                if (active) {
                    uiScope.launch {
                        Log.i(TAG, "→ onRemainingSentences: count=${sentences.size}")
                        callbacks.onRemainingSentences(sentences)
                    }
                }
            },
            onFinalResponse = { fullText ->
                if (active) {
                    uiScope.launch {
                        Log.i(TAG, "→ onFinalResponse(len=${fullText.length})")
                        addAssistant(fullText)
                        callbacks.onFinalResponse(fullText)
                    }
                }
            },
            onTurnFinish = {
                uiScope.launch {
                    if (active) {
                        Log.i(TAG, "=== TURN FINISH === (marking inactive)")
                        active = false
                        callbacks.onTurnFinish()
                    } else {
                        Log.d(TAG, "onTurnFinish skipped (already inactive)")
                    }
                }
            },
            onSystem = { msg ->
                uiScope.launch {
                    Log.i(TAG, "→ onSystem: ${msg.take(120)}")
                    callbacks.onSystem(msg)
                }
            },
            onError = { msg ->
                uiScope.launch {
                    Log.e(TAG, "→ onError: ${msg.take(200)}")
                    callbacks.onError(msg)
                }
            }
        )
    }
}
