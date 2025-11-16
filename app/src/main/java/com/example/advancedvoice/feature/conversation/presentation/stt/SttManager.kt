package com.example.advancedvoice.feature.conversation.presentation.stt

import android.app.Application
import android.util.Log
import com.example.advancedvoice.core.logging.LoggerConfig
import com.example.advancedvoice.core.prefs.Prefs
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController  // ✅ ADD THIS IMPORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SttManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val tts: TtsController,  // ✅ ADD THIS PARAMETER
    private val onFinalTranscript: (String) -> Unit
) {
    private companion object { const val TAG = LoggerConfig.TAG_VM }

    private var stt: SttController? = null
    private var collectorJobs = mutableListOf<Job>()

    fun getStt(): SttController? = stt

    fun setupFromPreferences() {
        val geminiKey = Prefs.getGeminiKey(app)

        if (geminiKey.isBlank()) {
            Log.w(TAG, "[STT Manager] No Gemini API key - STT will not be available")
            stt?.release()
            stt = null
            return
        }

        if (stt != null) {
            Log.d(TAG, "[STT Manager] GeminiLive STT already configured")
            return
        }

        Log.i(TAG, "[STT Manager] Configuring GeminiLive STT")
        stt = createGeminiLiveStt(geminiKey)
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
            tts = tts  // ✅ ADD THIS PARAMETER
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
        val stt = getStt()
        if (stt is GeminiLiveSttController) {
            scope.launch {
                stt.release()  // ✅ This disconnects WebSocket
            }
        }
    }
}