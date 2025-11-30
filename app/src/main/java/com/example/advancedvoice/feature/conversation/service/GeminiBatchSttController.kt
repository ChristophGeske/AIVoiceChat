package com.example.advancedvoice.feature.conversation.service

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.advancedvoice.core.audio.VadRecorder
import com.example.advancedvoice.core.prefs.Prefs
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class GeminiBatchSttController(
    private val scope: CoroutineScope,
    private val app: Application,
    private val apiKey: String
) : SttController {

    companion object {
        private const val TAG = "GeminiBatchSTT"
    }

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _isHearingSpeech = MutableStateFlow(false)
    override val isHearingSpeech: StateFlow<Boolean> = _isHearingSpeech

    private val _isTranscribing = MutableStateFlow(false)
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val transcripts = _transcripts

    override val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var vad: VadRecorder? = null
    private var eventJob: Job? = null
    private var sessionTimeoutJob: Job? = null

    @Volatile private var audioFrames = mutableListOf<ByteArray>()
    @Volatile private var turnEnded = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(isAutoListen: Boolean) {
        Log.i(TAG, "start(autoListen=$isAutoListen)")

        if (vad != null) {
            Log.w(TAG, "Already recording")
            return
        }

        _isListening.value = true
        _isHearingSpeech.value = false
        _isTranscribing.value = false
        turnEnded = false
        audioFrames.clear()

        vad = VadRecorder(
            scope = scope,
            sampleRate = 16_000,
            silenceThresholdRms = 350.0,
            maxSilenceMs = null,
            endOfSpeechMs = 800L,
            allowMultipleUtterances = false,
            startupGracePeriodMs = 100L, // Small grace period
            minSpeechDurationMs = 150L
        )

        scope.launch {
            vad?.audio?.collect { frame ->
                audioFrames.add(frame.copyOf())
            }
        }

        eventJob?.cancel()
        eventJob = scope.launch {
            vad?.events?.collect { event ->
                when (event) {
                    is VadRecorder.VadEvent.SpeechStart -> {
                        Log.i(TAG, "[VAD] Speech started")
                        _isHearingSpeech.value = true
                        sessionTimeoutJob?.cancel()
                    }
                    is VadRecorder.VadEvent.SpeechEnd -> {
                        Log.i(TAG, "[VAD] Speech ended (${event.durationMs}ms)")
                        _isHearingSpeech.value = false
                        endTurn()
                    }
                    is VadRecorder.VadEvent.SilenceTimeout -> {
                        Log.i(TAG, "[VAD] Silence timeout")
                        _transcripts.emit("")
                        stop(false)
                    }
                    is VadRecorder.VadEvent.Error -> {
                        errors.emit(event.message)
                    }
                }
            }
        }

        try {
            withContext(Dispatchers.IO) { vad?.start() }
            Log.i(TAG, "Recording started")
        } catch (se: SecurityException) {
            Log.e(TAG, "Permission denied", se)
            errors.tryEmit("Microphone permission denied")
            cleanup()
            throw se
        }

        sessionTimeoutJob?.cancel()
        val listenSeconds = Prefs.getListenSeconds(app)
        sessionTimeoutJob = scope.launch {
            delay(listenSeconds * 1000L)
            if (_isListening.value && !turnEnded) {
                Log.w(TAG, "Session timeout after ${listenSeconds}s")
                _transcripts.emit("::TIMEOUT::")
                stop(false)
            }
        }
    }

    private fun endTurn() {
        if (turnEnded) return
        turnEnded = true

        Log.i(TAG, "[TURN] Ending turn, collected ${audioFrames.size} audio frames")

        _isListening.value = false
        _isHearingSpeech.value = false
        _isTranscribing.value = true

        scope.launch { vad?.stop() }
        vad = null

        scope.launch(Dispatchers.IO) {
            try {
                val transcript = transcribeAudio()
                Log.i(TAG, "[TRANSCRIPT] Received: '$transcript'")
                withContext(Dispatchers.Main) { _transcripts.emit(transcript) }
            } catch (e: Exception) {
                Log.e(TAG, "[TRANSCRIPT] Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    errors.emit("Transcription failed: ${e.message}")
                    _transcripts.emit("")
                }
            } finally {
                withContext(Dispatchers.Main) { _isTranscribing.value = false }
                audioFrames.clear()
            }
        }
    }

    private suspend fun transcribeAudio(): String {
        if (audioFrames.isEmpty()) {
            Log.w(TAG, "No audio frames collected")
            return ""
        }

        Log.i(TAG, "[TRANSCRIBE] Preparing audio file (${audioFrames.size} frames)...")

        val audioFile = File(app.cacheDir, "temp_recording_${System.currentTimeMillis()}.wav")
        try {
            saveAsWav(audioFile, audioFrames)
            Log.i(TAG, "[TRANSCRIBE] Saved WAV: ${audioFile.length()} bytes")

            val generativeModel = GenerativeModel(
                modelName = "gemini-flash-latest",
                apiKey = apiKey
            )

            // ✅ FIX: Build a single 'Content' object with both text and audio parts
            val response = generativeModel.generateContent(
                content {
                    text("Transcribe this audio. Only return the exact words spoken, nothing else.")
                    blob(
                        mimeType = "audio/wav",
                        blob = audioFile.readBytes()
                    )
                }
            )

            val transcript = response.text?.trim() ?: ""
            Log.i(TAG, "[TRANSCRIBE] Success: ${transcript.length} chars")
            return transcript

        } finally {
            // Ensure temp file is always deleted
            if (audioFile.exists()) {
                audioFile.delete()
            }
        }
    }

    private fun saveAsWav(file: File, frames: List<ByteArray>) {
        val audioData = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        FileOutputStream(file).use { fos ->
            val sampleRate = 16000
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = audioData.size
            val fileSize = 36 + dataSize

            fos.write("RIFF".toByteArray())
            fos.write(intToBytes(fileSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToBytes(16))
            fos.write(shortToBytes(1))
            fos.write(shortToBytes(channels))
            fos.write(intToBytes(sampleRate))
            fos.write(intToBytes(byteRate))
            fos.write(shortToBytes(blockAlign))
            fos.write(shortToBytes(bitsPerSample))
            fos.write("data".toByteArray())
            fos.write(intToBytes(dataSize))
            fos.write(audioData)
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    override suspend fun stop(transcribe: Boolean) {
        Log.i(TAG, "stop(transcribe=$transcribe)")
        sessionTimeoutJob?.cancel()
        eventJob?.cancel()

        if (transcribe && !turnEnded) {
            endTurn()
        } else {
            cleanup()
        }
    }

    private fun cleanup() {
        scope.launch { vad?.stop() }
        vad = null
        eventJob?.cancel()
        sessionTimeoutJob?.cancel()
        audioFrames.clear()
        _isListening.value = false
        _isHearingSpeech.value = false
        _isTranscribing.value = false
    }

    override fun release() {
        Log.i(TAG, "release()")
        cleanup()
    }
}