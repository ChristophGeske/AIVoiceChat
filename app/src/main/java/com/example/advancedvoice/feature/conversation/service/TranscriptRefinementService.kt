package com.example.advancedvoice.feature.conversation.service

import android.util.Log
import com.example.advancedvoice.data.gemini.GeminiAudioService
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service that refines streaming transcripts in the background during TTS playback.
 * Uses LLM to get high-quality transcription of the audio.
 */
class TranscriptRefinementService(
    private val scope: CoroutineScope,
    private val geminiKeyProvider: () -> String,
    private val stateManager: ConversationStateManager
) {
    companion object {
        private const val TAG = "TranscriptRefinement"
    }

    private var currentRefinementJob: Job? = null
    private val audioService by lazy { GeminiAudioService(geminiKeyProvider) }

    /**
     * Start refining the transcript for a specific user entry.
     * Should be called when TTS starts speaking (background task during "dead time").
     *
     * @param audioSegments The audio to transcribe
     * @param userEntryIndex The index of the user entry in conversation to update
     * @param streamingTranscript The current streaming transcript (for comparison)
     */
    fun startRefinement(
        audioSegments: List<ByteArray>,
        userEntryIndex: Int,
        streamingTranscript: String
    ) {
        if (audioSegments.isEmpty()) {
            Log.w(TAG, "No audio to refine")
            return
        }

        // Cancel any existing refinement
        currentRefinementJob?.cancel()

        Log.i(TAG, "Starting transcript refinement for entry $userEntryIndex (${audioSegments.size} segments, streaming: '${streamingTranscript.take(50)}...')")

        currentRefinementJob = scope.launch {
            try {
                val refinedTranscript = withContext(Dispatchers.IO) {
                    audioService.transcribeAudio(audioSegments)
                }

                if (refinedTranscript.isNotBlank()) {
                    Log.i(TAG, "Refinement complete: '${refinedTranscript.take(100)}...'")

                    // Update the conversation entry with refined transcript
                    stateManager.refineUserTranscript(userEntryIndex, refinedTranscript)

                    Log.d(TAG, "Entry $userEntryIndex updated with refined transcript")
                } else {
                    Log.w(TAG, "Refinement returned empty result, keeping streaming transcript")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Refinement failed: ${e.message}", e)
                // Keep the streaming transcript on failure
            }
        }
    }

    /**
     * Cancel any ongoing refinement.
     */
    fun cancel() {
        currentRefinementJob?.cancel()
        currentRefinementJob = null
        Log.d(TAG, "Refinement cancelled")
    }

    /**
     * Check if refinement is in progress.
     */
    fun isRefining(): Boolean = currentRefinementJob?.isActive == true
}