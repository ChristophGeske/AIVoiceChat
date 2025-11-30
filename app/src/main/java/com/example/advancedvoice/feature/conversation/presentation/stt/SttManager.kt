package com.example.advancedvoice.feature.conversation.presentation.stt

import android.app.Application
import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.prefs.Prefs
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiBatchSttController
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SttManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val tts: TtsController,
    private val onFinalTranscript: (String) -> Unit
) {
    private companion object { const val TAG = LoggerConfig.TAG_VM }

    private var stt: SttController? = null
    private var collectorJobs = mutableListOf<Job>()
    private var currentSttSystem: String? = null

    fun getStt(): SttController? = stt

    fun setupFromPreferences() {
        val newSttSystem = Prefs.getSttSystem(app)
        val geminiKey = Prefs.getGeminiKey(app)

        // If the system hasn't changed and an STT controller already exists, do nothing.
        if (newSttSystem == currentSttSystem && stt != null) {
            Log.d(TAG, "[STT Manager] STT system '$newSttSystem' already configured.")
            return
        }

        // Release the old controller if it exists
        stt?.release()
        stt = null
        currentSttSystem = newSttSystem

        if (geminiKey.isBlank() && (newSttSystem == Prefs.STT_SYSTEM_GEMINI_LIVE || newSttSystem == Prefs.STT_SYSTEM_GEMINI_BATCH)) {
            Log.w(TAG, "[STT Manager] No Gemini API key - STT will not be available")
            return
        }

        Log.i(TAG, "[STT Manager] Configuring STT system: $newSttSystem")

        stt = when (newSttSystem) {
            Prefs.STT_SYSTEM_GEMINI_LIVE -> createGeminiLiveStt(geminiKey)
            Prefs.STT_SYSTEM_GEMINI_BATCH -> createGeminiBatchStt(geminiKey)
            else -> {
                Log.w(TAG, "[STT Manager] 'Standard' STT not implemented yet.")
                null
            }
        }

        rewireCollectors()
    }

    private fun createGeminiLiveStt(geminiKey: String): GeminiLiveSttController {
        val liveModel = "models/gemini-2.5-flash-native-audio-preview-09-2025"
        val client = GeminiLiveClient(scope = scope)
        val transcriber = GeminiLiveTranscriber(scope = scope, client = client)
        return GeminiLiveSttController(
            scope = scope,
            app = app,
            client = client,
            transcriber = transcriber,
            apiKey = geminiKey,
            model = liveModel,
            tts = tts
        )
    }

    private fun createGeminiBatchStt(geminiKey: String): GeminiBatchSttController {
        return GeminiBatchSttController(
            scope = scope,
            app = app,
            apiKey = geminiKey
        )
    }

    private fun rewireCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
        val current = stt ?: return

        collectorJobs.add(scope.launch {
            current.transcripts.collectLatest { onFinalTranscript(it) }
        })

        if (current is GeminiLiveSttController) {
            collectorJobs.add(scope.launch {
                current.partialTranscripts.collectLatest { partialText ->
                    stateManager.updateLastUserStreamingText(partialText)
                }
            })
        } else {
            // For batch, clear streaming text when transcribing starts
            collectorJobs.add(scope.launch {
                current.isTranscribing.collectLatest { isTranscribing ->
                    if (isTranscribing) {
                        stateManager.updateLastUserStreamingText("Transcribing...")
                    }
                }
            })
        }

        collectorJobs.add(scope.launch { current.errors.collectLatest { stateManager.addError(it) } })
        collectorJobs.add(scope.launch { current.isListening.collectLatest { stateManager.setListening(it) } })
        collectorJobs.add(scope.launch { current.isHearingSpeech.collectLatest { stateManager.setHearingSpeech(it) } })
        collectorJobs.add(scope.launch { current.isTranscribing.collectLatest { stateManager.setTranscribing(it) } })
    }

    fun release() {
        stt?.release()
        stt = null
        currentSttSystem = null
    }
}