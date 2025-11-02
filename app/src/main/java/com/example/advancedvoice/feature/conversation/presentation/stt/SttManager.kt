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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages STT (Speech-to-Text) setup and transcript handling.
 */
class SttManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val stateManager: ConversationStateManager,
    private val onFinalTranscript: (String) -> Unit
) {
    private companion object {
        const val TAG = LoggerConfig.TAG_VM
    }

    private var stt: SttController? = null

    fun getStt(): SttController? = stt

    /**
     * Setup STT system from preferences.
     */
    fun setupFromPreferences() {
        val sttPref = Prefs.getSttSystem(app)
        val geminiKey = Prefs.getGeminiKey(app)
        Log.i(TAG, "[ViewModel] setupSttSystemFromPreferences: stt=$sttPref, hasGeminiKey=${geminiKey.isNotBlank()}")

        val shouldUseGeminiLive = sttPref == SttSystem.GEMINI_LIVE && geminiKey.isNotBlank()
        val currentlyUsingGeminiLive = stt is GeminiLiveSttController

        if (stt != null && currentlyUsingGeminiLive == shouldUseGeminiLive) {
            Log.d(TAG, "[ViewModel] STT system is already correctly configured.")
            return
        }

        Log.i(TAG, "[ViewModel] Need to reconfigure STT. Current: ${stt?.javaClass?.simpleName ?: "null"}, want GeminiLive=$shouldUseGeminiLive")

        stt?.release()

        stt = if (shouldUseGeminiLive) {
            createGeminiLiveStt(geminiKey)
        } else {
            Log.i(TAG, "[ViewModel] Creating and wiring StandardSttController.")
            StandardSttController(app, scope)
        }

        Log.i(TAG, "[ViewModel] STT configured: ${stt?.javaClass?.simpleName}")

        rewireCollectors()
    }

    /**
     * Create Gemini Live STT controller.
     */
    private fun createGeminiLiveStt(geminiKey: String): GeminiLiveSttController {
        Log.i(TAG, "[ViewModel] Creating and wiring GeminiLiveSttController.")

        val liveModel = "models/gemini-2.5-flash-native-audio-preview-09-2025"
        val listenSeconds = Prefs.getListenSeconds(app)
        val maxSilenceMs = (listenSeconds * 1000L).coerceIn(3000L, 30000L)

        val vad = VadRecorder(
            scope = scope,
            endOfSpeechMs = 1000L,
            maxSilenceMs = maxSilenceMs
        )

        val client = GeminiLiveClient(scope = scope)
        val transcriber = GeminiLiveTranscriber(scope = scope, client = client)

        return GeminiLiveSttController(scope, vad, client, transcriber, geminiKey, liveModel)
    }

    /**
     * Rewire STT collectors to ViewModel.
     */
    private fun rewireCollectors() {
        val current = stt ?: return
        Log.d(TAG, "[ViewModel] Rewiring STT collectors for ${current.javaClass.simpleName}")

        scope.launch {
            current.transcripts.collectLatest { onFinalTranscript(it) }
        }

        if (current is GeminiLiveSttController) {
            scope.launch {
                current.partialTranscripts.collectLatest { partialText ->
                    stateManager.updateLastUserStreamingText(partialText)
                }
            }
        }

        scope.launch {
            current.errors.collectLatest { stateManager.addError(it) }
        }

        scope.launch {
            current.isListening.collectLatest { stateManager.setListening(it) }
        }

        scope.launch {
            current.isHearingSpeech.collectLatest { stateManager.setHearingSpeech(it) }
        }

        scope.launch {
            current.isTranscribing.collectLatest { stateManager.setTranscribing(it) }
        }
    }

    /**
     * Release STT resources.
     */
    fun release() {
        stt?.release()
    }
}