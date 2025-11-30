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
 * Service that refines streaming transcripts using Flash model.
 *
 * This runs in PARALLEL with response generation:
 * - PATH A (this service): Fast transcription with Flash → Updates UI with accurate text
 * - PATH B (generation): User's chosen model → Generates response
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
     * Uses Flash model for speed - runs in parallel with response generation.
     *
     * @param audioSegments The audio to transcribe
     * @param userEntryIndex The index of the user entry to update
     * @param streamingTranscript The current streaming transcript (for logging/comparison)
     */
    fun startRefinement(
        audioSegments: List<ByteArray>,
        userEntryIndex: Int,
        streamingTranscript: String
    ) {
        if (audioSegments.isEmpty()) {
            Log.w(TAG, "[PATH-A] ⚠️ No audio segments to refine")
            return
        }

        // Cancel any existing refinement
        currentRefinementJob?.cancel()

        val totalBytes = audioSegments.sumOf { it.size }
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "[PATH-A] 🚀 Starting PARALLEL transcript refinement")
        Log.i(TAG, "  Entry index: $userEntryIndex")
        Log.i(TAG, "  Audio segments: ${audioSegments.size}")
        Log.i(TAG, "  Total audio: ${totalBytes / 1024} KB")
        Log.i(TAG, "  Streaming preview: '${streamingTranscript.take(50)}...'")
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        currentRefinementJob = scope.launch {
            val startTime = System.currentTimeMillis()

            try {
                val refinedTranscript = withContext(Dispatchers.IO) {
                    // ✅ Always use Flash for speed
                    audioService.transcribeAudioFast(audioSegments)
                }

                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "[PATH-A] Refinement completed in ${elapsedMs}ms")
                Log.d(TAG, "[PATH-A] Result: '${refinedTranscript.take(80)}...'")

                if (refinedTranscript.isBlank()) {
                    Log.w(TAG, "[PATH-A] ⚠️ Refinement returned empty result - keeping streaming text")
                    return@launch
                }

                // Verify the entry is still valid
                val currentEntry = stateManager.conversation.value.getOrNull(userEntryIndex)
                if (currentEntry == null) {
                    Log.w(TAG, "[PATH-A] ⚠️ Entry $userEntryIndex no longer exists")
                    return@launch
                }

                if (currentEntry.speaker != "You") {
                    Log.w(TAG, "[PATH-A] ⚠️ Entry $userEntryIndex is not a user entry (speaker=${currentEntry.speaker})")
                    return@launch
                }

                // Check if entry was already refined (avoid double-update)
                if (currentEntry.isRefinedTranscript) {
                    Log.d(TAG, "[PATH-A] Entry $userEntryIndex already refined, skipping")
                    return@launch
                }

                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.i(TAG, "[PATH-A] ✅ APPLYING refined transcript")
                Log.i(TAG, "  Entry: $userEntryIndex")
                Log.i(TAG, "  Before: '${currentEntry.sentences.joinToString(" ").take(50)}...'")
                Log.i(TAG, "  After:  '${refinedTranscript.take(50)}...'")
                Log.i(TAG, "  Time: ${elapsedMs}ms")
                Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                stateManager.refineUserTranscript(userEntryIndex, refinedTranscript)

            } catch (e: Exception) {
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.e(TAG, "[PATH-A] ❌ Refinement failed after ${elapsedMs}ms: ${e.message}", e)
                // Don't update - keep the streaming transcript
            }
        }
    }

    /**
     * Cancel any ongoing refinement.
     */
    fun cancel() {
        currentRefinementJob?.cancel()
        currentRefinementJob = null
        Log.d(TAG, "[PATH-A] Refinement cancelled")
    }

    /**
     * Check if refinement is in progress.
     */
    fun isRefining(): Boolean = currentRefinementJob?.isActive == true
}