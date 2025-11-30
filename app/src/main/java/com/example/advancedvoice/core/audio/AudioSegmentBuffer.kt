package com.example.advancedvoice.core.audio

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Buffers audio frames and can combine multiple segments into WAV format.
 * Used for collecting audio to send directly to LLM.
 */
class AudioSegmentBuffer(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    companion object {
        private const val TAG = "AudioSegmentBuffer"
    }

    private val currentSegmentFrames = mutableListOf<ByteArray>()
    private val completedSegments = mutableListOf<ByteArray>() // Each is WAV bytes

    /**
     * Add a PCM audio frame to the current segment.
     */
    fun addFrame(frame: ByteArray) {
        currentSegmentFrames.add(frame.copyOf())
    }

    /**
     * Get the number of frames in the current segment.
     */
    fun getCurrentFrameCount(): Int = currentSegmentFrames.size

    /**
     * Complete the current segment and convert to WAV.
     * Returns the WAV bytes for this segment.
     */
    fun completeCurrentSegment(): ByteArray? {
        if (currentSegmentFrames.isEmpty()) {
            Log.w(TAG, "No frames in current segment")
            return null
        }

        val wavBytes = framesToWav(currentSegmentFrames)
        completedSegments.add(wavBytes)
        currentSegmentFrames.clear()

        Log.d(TAG, "Completed segment: ${wavBytes.size} bytes, total segments: ${completedSegments.size}")
        return wavBytes
    }

    /**
     * Get all completed segments as WAV byte arrays.
     */
    fun getCompletedSegments(): List<ByteArray> = completedSegments.toList()

    /**
     * Get current segment as WAV bytes without completing it.
     */
    fun getCurrentSegmentAsWav(): ByteArray? {
        if (currentSegmentFrames.isEmpty()) return null
        return framesToWav(currentSegmentFrames)
    }

    /**
     * Get all audio (completed + current) as a single WAV file.
     */
    fun getAllAudioAsWav(): ByteArray? {
        val allFrames = mutableListOf<ByteArray>()

        // Add frames from completed segments (need to extract PCM from WAV)
        completedSegments.forEach { wav ->
            val pcm = extractPcmFromWav(wav)
            if (pcm != null) {
                allFrames.add(pcm)
            }
        }

        // Add current segment frames
        allFrames.addAll(currentSegmentFrames)

        if (allFrames.isEmpty()) return null
        return framesToWav(allFrames)
    }

    /**
     * Clear all segments and frames.
     */
    fun clear() {
        currentSegmentFrames.clear()
        completedSegments.clear()
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Clear only the current segment (keep completed segments).
     */
    fun clearCurrentSegment() {
        currentSegmentFrames.clear()
    }

    /**
     * Check if there's any audio buffered.
     */
    fun hasAudio(): Boolean = currentSegmentFrames.isNotEmpty() || completedSegments.isNotEmpty()

    /**
     * Get total audio duration in milliseconds.
     */
    fun getTotalDurationMs(): Long {
        val bytesPerFrame = currentSegmentFrames.firstOrNull()?.size ?: return 0L
        val totalFrames = currentSegmentFrames.size +
                completedSegments.sumOf { extractPcmFromWav(it)?.size?.div(bytesPerFrame) ?: 0 }
        val samplesPerFrame = bytesPerFrame / (bitsPerSample / 8)
        val totalSamples = totalFrames * samplesPerFrame
        return (totalSamples * 1000L) / sampleRate
    }

    private fun framesToWav(frames: List<ByteArray>): ByteArray {
        val totalPcmSize = frames.sumOf { it.size }
        val pcmData = ByteArray(totalPcmSize)
        var offset = 0
        frames.forEach { frame ->
            System.arraycopy(frame, 0, pcmData, offset, frame.size)
            offset += frame.size
        }

        return createWavHeader(pcmData.size) + pcmData
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())

        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat (PCM = 1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        return buffer.array()
    }

    private fun extractPcmFromWav(wavBytes: ByteArray): ByteArray? {
        if (wavBytes.size < 44) return null
        // WAV header is 44 bytes, PCM data follows
        return wavBytes.copyOfRange(44, wavBytes.size)
    }
}