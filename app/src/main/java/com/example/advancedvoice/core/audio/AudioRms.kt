package com.example.advancedvoice.core.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Signal-level helpers for PCM16 audio.
 */
object AudioRms {

    /**
     * Computes RMS (root mean square) of the first [length] samples of [samples].
     * RMS is proportional to perceived loudness for short frames.
     */
    @JvmStatic
    fun calculateRms(samples: ShortArray, length: Int = samples.size): Double {
        val n = length.coerceIn(0, samples.size)
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n)
    }

    /**
     * Converts RMS to dBFS where 0 dBFS is full-scale (|32768|) and silence -> -∞.
     * We clamp at a very low floor to avoid -Infinity for 0 input.
     */
    @JvmStatic
    fun rmsToDbfs(rms: Double): Double {
        if (rms <= 0.0000001) return Double.NEGATIVE_INFINITY
        val fullScale = 32768.0
        return 20.0 * log10(rms / fullScale)
    }
}

/* Backward-compatible helper matching old signature */
@Deprecated("Use AudioRms.calculateRms(samples, length)", ReplaceWith("AudioRms.calculateRms(data, readSize)"))
fun calculateRms(data: ShortArray, readSize: Int): Double =
    AudioRms.calculateRms(data, readSize)
