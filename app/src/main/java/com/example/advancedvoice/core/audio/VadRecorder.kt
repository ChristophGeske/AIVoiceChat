package com.example.advancedvoice.core.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val frameMillis: Int = 20 // 20 ms per read -> 320 samples at 16 kHz mono 16-bit
) {

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
        if (isRunning) return

        val minSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minSize == AudioRecord.ERROR || minSize == AudioRecord.ERROR_BAD_VALUE) {
            _events.tryEmit(VadEvent.Error("Unsupported audio config: minBufferSize=$minSize"))
            return
        }

        // Compute frame + buffer sizes and align buffer
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val channels = when (channelConfig) {
            AudioFormat.CHANNEL_IN_STEREO -> 2
            else -> 1
        }
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
            _events.tryEmit(
                VadEvent.Error(
                    "AudioRecord not initialized (min=$minSize, chosen=$bufferBytes, frame=$frameSizeBytes, ch=$channels, bps=$bytesPerSample)"
                )
            )
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

        job = scope.launch(Dispatchers.IO) {
            val frameSamples = (sampleRate / 1000) * frameMillis
            val shortBuf = ShortArray(frameSamples)
            var hasSpeech = false
            var speechStartTs = 0L
            var silenceStartTs = 0L

            while (isActive && isRunning) {
                val read = rec.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) {
                    _events.tryEmit(VadEvent.Error("AudioRecord read error: $read"))
                    break
                }

                val rms = AudioRms.calculateRms(shortBuf, read)
                val silent = rms < silenceThresholdRms
                val now = System.currentTimeMillis()

                if (!silent) {
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
                        } else if (now - silenceStartTs > silenceTimeoutMs) {
                            val duration = now - speechStartTs
                            if (duration >= minSpeechDurationMs) {
                                _events.tryEmit(VadEvent.SpeechEnd(durationMs = duration))
                            } else {
                                _events.tryEmit(VadEvent.SilenceTimeout)
                            }
                            break
                        }
                    }
                }

                if (hasSpeech) {
                    _audio.tryEmit(shortBuf.toPcm16Le(read))
                }
            }
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        isRunning = false

        val j = job
        job = null
        try {
            j?.cancelAndJoin()
        } catch (_: Throwable) {}

        val rec = recorder
        recorder = null
        try {
            rec?.stop()
            rec?.release()
        } catch (_: Throwable) {}
    }
}
