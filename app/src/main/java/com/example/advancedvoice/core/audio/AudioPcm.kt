package com.example.advancedvoice.core.audio

/**
 * Utilities for converting between PCM16 little-endian byte buffers and ShortArray samples.
 *
 * - "PCM16 LE" = signed 16-bit little-endian mono.
 * - Android's AudioRecord reads into ShortArray; network APIs often expect ByteArray.
 */
object AudioPcm {

    /**
     * Converts the first [readSize] samples from [input] into a PCM16 LE ByteArray.
     */
    @JvmStatic
    fun shortsToPcm16Le(input: ShortArray, readSize: Int = input.size): ByteArray {
        val size = readSize.coerceIn(0, input.size)
        val out = ByteArray(size * 2)
        var j = 0
        for (i in 0 until size) {
            val s = input[i].toInt()
            out[j++] = (s and 0xFF).toByte()        // low byte
            out[j++] = ((s shr 8) and 0xFF).toByte() // high byte
        }
        return out
    }

    /**
     * Converts a PCM16 LE ByteArray into a ShortArray.
     * The length of the resulting ShortArray is floor(bytes.size / 2).
     */
    @JvmStatic
    fun pcm16LeToShorts(bytes: ByteArray): ShortArray {
        val outLen = bytes.size / 2
        val out = ShortArray(outLen)
        var j = 0
        for (i in 0 until outLen) {
            val lo = (bytes[j++].toInt() and 0xFF)
            val hi = (bytes[j++].toInt() and 0xFF) shl 8
            out[i] = (hi or lo).toShort()
        }
        return out
    }
}

/**
 * Extension convenience: convert the first [readSize] samples to PCM16 LE.
 */
fun ShortArray.toPcm16Le(readSize: Int = this.size): ByteArray =
    AudioPcm.shortsToPcm16Le(this, readSize)

/**
 * Extension convenience: convert a full PCM16 LE byte buffer into ShortArray.
 */
fun ByteArray.toShortsPcm16Le(): ShortArray =
    AudioPcm.pcm16LeToShorts(this)

/* Backward-compatible aliases (safe to remove later) */
@Deprecated("Use toPcm16Le(readSize) from AudioPcm.kt", ReplaceWith("this.toPcm16Le(readSize)"))
fun ShortArray.to16BitPcm(readSize: Int): ByteArray = toPcm16Le(readSize)
