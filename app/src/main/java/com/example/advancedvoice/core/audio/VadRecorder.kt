package com.example.advancedvoice.core.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class VadRecorder(
    private val scope: CoroutineScope,
    private val sampleRate: Int = 16_000,
    private val silenceThresholdRms: Double = 250.0,
    // FIX: Two separate timeouts for better responsiveness
    private val endOfSpeechMs: Long = 1500L,   // Shorter timeout to end the turn quickly
    private val maxSilenceMs: Long = 5000L,     // Longer timeout for mid-speech pauses
    private val minSpeechDurationMs: Long = 400L,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val frameMillis: Int = 20
) {
    companion object {
        private const val TAG = "VadRecorder"
    }

    sealed class VadEvent {
        object SpeechStart : VadEvent()
        data class SpeechEnd(val durationMs: Long) : VadEvent()
        object SilenceTimeout : VadEvent()
        data class Error(val message: String) : VadEvent()
    }

    private val _events = MutableSharedFlow<VadEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<VadEvent> = _events

    private val _audio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audio: SharedFlow<ByteArray> = _audio

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var job: Job? = null

    @Volatile var isRunning: Boolean = false
        private set

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun start() {
        if (isRunning) {
            Log.w(TAG, "start() called while already running. Ignoring.")
            return
        }

        val minSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minSize == AudioRecord.ERROR || minSize == AudioRecord.ERROR_BAD_VALUE) {
            _events.tryEmit(VadEvent.Error("Unsupported audio config: minBufferSize=$minSize"))
            return
        }

        val bytesPerSample = 2 // For ENCODING_PCM_16BIT
        val channels = 1 // For CHANNEL_IN_MONO
        val frameSizeBytes = (sampleRate / 1000) * frameMillis * bytesPerSample * channels

        fun alignUp(value: Int, alignment: Int): Int {
            if (alignment <= 0) return value
            val rem = value % alignment
            return if (rem == 0) value else value + (alignment - rem)
        }

        var bufferBytes = max(minSize, frameSizeBytes * 4)
        bufferBytes = alignUp(bufferBytes, frameSizeBytes)

        val rec: AudioRecord = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                encoding,
                bufferBytes
            )
        } catch (se: SecurityException) {
            _events.tryEmit(VadEvent.Error("SecurityException: RECORD_AUDIO denied"))
            return
        } catch (t: Throwable) {
            _events.tryEmit(VadEvent.Error("AudioRecord build failed: ${t.message}"))
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            _events.tryEmit(VadEvent.Error("AudioRecord not initialized"))
            try { rec.release() } catch (_: Throwable) {}
            return
        }

        try {
            rec.startRecording()
        } catch (e: Exception) {
            _events.tryEmit(VadEvent.Error("startRecording failed: ${e.message}"))
            try { rec.release() } catch (_: Throwable) {}
            return
        }

        recorder = rec
        isRunning = true
        Log.i(TAG, "VAD recorder started successfully.")

        job = scope.launch(Dispatchers.IO) {
            val frameSamples = (sampleRate / 1000) * frameMillis
            val shortBuf = ShortArray(frameSamples)
            var hasSpeech = false
            var speechStartTs = 0L
            var silenceStartTs = 0L

            while (isActive && isRunning) {
                val read = rec.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) {
                    if (isActive) _events.tryEmit(VadEvent.Error("AudioRecord read error: $read"))
                    break
                }

                val rms = AudioRms.calculateRms(shortBuf, read)
                val isLoud = rms >= silenceThresholdRms
                val now = System.currentTimeMillis()

                if (isLoud) {
                    if (!hasSpeech) {
                        hasSpeech = true
                        speechStartTs = now
                        _events.tryEmit(VadEvent.SpeechStart)
                    }
                    silenceStartTs = 0L
                } else {
                    if (hasSpeech) {
                        if (silenceStartTs == 0L) {
                            silenceStartTs = now
                        }

                        // FIX: Use the shorter timeout to end the turn
                        val silenceDuration = now - silenceStartTs
                        if (silenceDuration > endOfSpeechMs) {
                            val duration = now - speechStartTs
                            if (duration >= minSpeechDurationMs) {
                                _events.tryEmit(VadEvent.SpeechEnd(durationMs = duration))
                            } else {
                                _events.tryEmit(VadEvent.SilenceTimeout)
                            }
                            break // End the loop
                        }
                    }
                }

                if (hasSpeech) {
                    _audio.tryEmit(shortBuf.toPcm16Le(read))
                }
            }
            Log.i(TAG, "VAD recording loop finished.")
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        Log.i(TAG, "Stopping VAD recorder...")
        isRunning = false

        job?.cancelAndJoin()
        job = null

        val rec = recorder
        recorder = null
        scope.launch(Dispatchers.IO) {
            try {
                if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec?.release()
                Log.i(TAG, "VAD recorder stopped and released successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping/releasing AudioRecord: ${e.message}")
            }
        }
    }
}