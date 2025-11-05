package com.example.advancedvoice.feature.conversation.presentation.stt

import android.app.Application
import android.util.Log
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.prefs.Prefs
import com.example.advancedvoice.core.prefs.SttSystem
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.StandardSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SttManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val onFinalTranscript: (String) -> Unit

) {
    private companion object { const val TAG = LoggerConfig.TAG_VM }

    private var stt: SttController? = null
    private var collectorJobs = mutableListOf<Job>()

    fun getStt(): SttController? = stt

    fun setupFromPreferences() {
        val sttPref = Prefs.getSttSystem(app)
        val geminiKey = Prefs.getGeminiKey(app)
        val shouldUseGeminiLive = sttPref == SttSystem.GEMINI_LIVE && geminiKey.isNotBlank()
        val currentlyUsingGeminiLive = stt is GeminiLiveSttController

        if (stt != null && currentlyUsingGeminiLive == shouldUseGeminiLive) {
            return
        }
        Log.i(TAG, "[STT Manager] Reconfiguring STT. Want GeminiLive=$shouldUseGeminiLive")

        stt?.release()
        stt = if (shouldUseGeminiLive) {
            createGeminiLiveStt(geminiKey)
        } else {
            StandardSttController(app, scope)
        }
        Log.i(TAG, "[STT Manager] Configured with ${stt?.javaClass?.simpleName}")
        rewireCollectors()
    }

    private fun createGeminiLiveStt(geminiKey: String): GeminiLiveSttController {
        val liveModel = "models/gemini-2.5-flash-native-audio-preview-09-2025"

        // ✅ REMOVED: VAD creation (MicrophoneSession creates it internally)
        val client = GeminiLiveClient(scope = scope)
        val transcriber = GeminiLiveTranscriber(scope = scope, client = client)

        return GeminiLiveSttController(
            scope = scope,
            app = app,
            client = client,
            transcriber = transcriber,
            apiKey = geminiKey,
            model = liveModel
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
        }

        collectorJobs.add(scope.launch { current.errors.collectLatest { stateManager.addError(it) } })
        collectorJobs.add(scope.launch { current.isListening.collectLatest { stateManager.setListening(it) } })
        collectorJobs.add(scope.launch { current.isHearingSpeech.collectLatest { stateManager.setHearingSpeech(it) } })
        collectorJobs.add(scope.launch { current.isTranscribing.collectLatest { stateManager.setTranscribing(it) } })
    }

    fun release() {
        stt?.release()
    }
}