package com.example.advancedvoice.core.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.advancedvoice.core.audio.toPcm16Le
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
    private val endOfSpeechMs: Long = 1500L,
    private val maxSilenceMs: Long? = null,
    private val minSpeechDurationMs: Long = 300L,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val frameMillis: Int = 20,
    private val startupGracePeriodMs: Long = 0L,
    val allowMultipleUtterances: Boolean = true
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

    // Expose current speech activity and allow external reset
    @Volatile var isSpeechActive: Boolean = false
        private set
    @Volatile private var resetRequested = false

    fun resetDetection() {
        // Will be applied in the IO loop atomically
        resetRequested = true
        Log.d(TAG, "[VAD] resetDetection() requested")
    }

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

        val bytesPerSample = 2
        val channels = 1
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
        isSpeechActive = false
        resetRequested = false

        val timeoutMsg = if (maxSilenceMs == null) "CONTINUOUS (no timeout)" else "${maxSilenceMs}ms"
        Log.i(TAG, "[VAD] Recorder started. Mode: $timeoutMsg, EndOfSpeech: ${endOfSpeechMs}ms, Grace: ${startupGracePeriodMs}ms, Multi-Utterance: $allowMultipleUtterances")

        job = scope.launch(Dispatchers.IO) {
            val frameSamples = (sampleRate / 1000) * frameMillis
            val shortBuf = ShortArray(frameSamples)
            var hasSpeech = false
            var speechStartTs = 0L
            var silenceStartTs = 0L
            val recordingStartTime = System.currentTimeMillis()

            while (isActive && isRunning) {
                val read = rec.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) {
                    if (isActive) _events.tryEmit(VadEvent.Error("AudioRecord read error: $read"))
                    break
                }

                // Apply external reset atomically
                if (resetRequested) {
                    hasSpeech = false
                    speechStartTs = 0L
                    silenceStartTs = 0L
                    isSpeechActive = false
                    resetRequested = false
                    // Do not emit events on reset; just clear internal state
                    Log.d(TAG, "[VAD] Detection state reset (hasSpeech=false)")
                }

                val rms = AudioRms.calculateRms(shortBuf, read)
                val isLoud = rms >= silenceThresholdRms
                val now = System.currentTimeMillis()
                val inGracePeriod = (now - recordingStartTime) < startupGracePeriodMs
                if (inGracePeriod && isLoud) {
                    Log.d(TAG, "[VAD] Ignoring loud audio during grace period (${now - recordingStartTime}ms, RMS: $rms)")
                    continue
                }

                if (isLoud) {
                    if (!hasSpeech) {
                        hasSpeech = true
                        isSpeechActive = true
                        speechStartTs = now
                        Log.i(TAG, "[VAD] Speech detected! RMS: $rms")
                        _events.tryEmit(VadEvent.SpeechStart)
                    }
                    silenceStartTs = 0L
                } else {
                    if (hasSpeech) {
                        if (silenceStartTs == 0L) {
                            silenceStartTs = now
                        }
                        val silenceDuration = now - silenceStartTs

                        if (silenceDuration > endOfSpeechMs) {
                            val duration = now - speechStartTs
                            if (duration >= minSpeechDurationMs) {
                                Log.i(TAG, "[VAD] Speech ended (duration=${duration}ms, silence=${silenceDuration}ms)")
                                _events.tryEmit(VadEvent.SpeechEnd(durationMs = duration))
                            } else {
                                Log.d(TAG, "[VAD] Speech too short (${duration}ms), treating as noise")
                            }

                            if (allowMultipleUtterances) {
                                Log.d(TAG, "[VAD] Resetting for next utterance.")
                                hasSpeech = false
                                isSpeechActive = false
                                speechStartTs = 0L
                                silenceStartTs = 0L
                            } else {
                                break
                            }
                        }
                    } else {
                        if (maxSilenceMs != null) {
                            if (silenceStartTs == 0L) {
                                silenceStartTs = now
                            }
                            val totalSilence = now - silenceStartTs
                            if (totalSilence > maxSilenceMs) {
                                Log.i(TAG, "[VAD] Max silence timeout (${totalSilence}ms) - no speech detected")
                                _events.tryEmit(VadEvent.SilenceTimeout)
                                break
                            }
                        }
                    }
                }

                if (hasSpeech) {
                    _audio.tryEmit(shortBuf.toPcm16Le(read))
                }
            }

            Log.i(TAG, "[VAD] Recording loop finished. hasSpeech=$hasSpeech")
        }
    }

    suspend fun stop() {
        if (!isRunning) return
        Log.i(TAG, "[VAD] Stopping recorder...")
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
                Log.i(TAG, "[VAD] Recorder stopped and released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping/releasing AudioRecord: ${e.message}")
            }
        }
    }
}