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

/**
 * Simple voice activity recording loop with RMS-based VAD:
 * - Emits audio frames (PCM16 LE) only after speech is detected.
 * - Emits events for SpeechStart, SilenceTimeout, SpeechEnd, and Errors.
 *
 * NOTE: Caller must hold RECORD_AUDIO permission before calling start().
 */
class VadRecorder(
    private val scope: CoroutineScope,
    private val sampleRate: Int = 16_000,
    private val silenceThresholdRms: Double = 250.0,
    private val silenceTimeoutMs: Long = 3_000L,
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

        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        val frameSizeBytes = (sampleRate / 1000) * frameMillis * bytesPerSample * channels

        fun alignUp(value: Int, alignment: Int): Int {
            if (alignment <= 0) return value
            val rem = value % alignment
            return if (rem == 0) value else value + (alignment - rem)
        }

        var bufferBytes = max(minSize, frameSizeBytes * 4)
        bufferBytes = alignUp(bufferBytes, frameSizeBytes)

        val rec: AudioRecord = try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    encoding,
                    bufferBytes
                )
            }
        } catch (se: SecurityException) {
            _events.tryEmit(VadEvent.Error("SecurityException: RECORD_AUDIO denied"))
            return
        } catch (t: Throwable) {
            _events.tryEmit(VadEvent.Error("AudioRecord build failed: ${t.message}"))
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (min=$minSize, chosen=$bufferBytes, frame=$frameSizeBytes, ch=$channels, bps=$bytesPerSample)")
            _events.tryEmit(VadEvent.Error("AudioRecord not initialized"))
            try { rec.release() } catch (_: Throwable) {}
            return
        }

        try {
            rec.startRecording()
        } catch (se: SecurityException) {
            _events.tryEmit(VadEvent.Error("SecurityException on startRecording"))
            try { rec.release() } catch (_: Throwable) {}
            return
        } catch (e: IllegalStateException) {
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
                    if (isActive) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        _events.tryEmit(VadEvent.Error("AudioRecord read error: $read"))
                    }
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
                    silenceStartTs = 0L // Reset silence counter
                } else {
                    if (hasSpeech) {
                        if (silenceStartTs == 0L) {
                            silenceStartTs = now
                        } else if (now - silenceStartTs > silenceTimeoutMs) {
                            val duration = now - speechStartTs
                            if (duration >= minSpeechDurationMs) {
                                _events.tryEmit(VadEvent.SpeechEnd(durationMs = duration))
                            } else {
                                // Speech was too short, treat as noise/silence
                                _events.tryEmit(VadEvent.SilenceTimeout)
                            }
                            break // End the loop on speech end/timeout
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

        val j = job
        job = null
        try {
            j?.cancelAndJoin()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling VAD job: ${e.message}")
        }

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