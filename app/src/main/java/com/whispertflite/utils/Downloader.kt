// In: app/src/main/java/com/whispertflite/utils/Downloader.kt
package com.whispertflite.utils

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.URL
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

private const val TAG = "Downloader"

data class Model(
    val name: String,
    val fileName: String,
    val url: String,
    val md5: String,
    val sizeInBytes: Long,
    val isMultilingual: Boolean,
    val languageTokens: Map<String, Int>? = null
)

val availableModels = listOf(
    Model(
        name = "English - Tiny (Fast)",
        fileName = "whisper-tiny.en.tflite",
        url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-tiny.en.tflite",
        md5 = "2e745cdd5dfe2f868f47caa7a199f91a",
        sizeInBytes = 41486616L,
        isMultilingual = false
    ),
    Model(
        name = "Multilingual - Small (Accurate)",
        fileName = "whisper-small.tflite",
        url = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-small.tflite",
        md5 = "7b10527f410230cf09b553da0213bb6c",
        sizeInBytes = 485821128L,
        isMultilingual = true
    )
)

interface DownloadListener {
    fun onProgress(progress: Int, downloadedMb: Float, totalMb: Float)
    fun onComplete(model: Model)
    fun onError(message: String)
}

suspend fun checkModel(context: Context, model: Model): Boolean = withContext(Dispatchers.IO) {
    copyAllVocabAssets(context)
    val modelFile = File(context.getExternalFilesDir(null), model.fileName)
    if (!modelFile.exists()) return@withContext false

    try {
        val calculatedMD5 = calculateMD5(modelFile)
        val isValid = calculatedMD5.equals(model.md5, ignoreCase = true)
        if (!isValid) {
            Log.w(TAG, "MD5 mismatch for ${model.fileName}: expected ${model.md5}, got $calculatedMD5")
            modelFile.delete()
        }
        return@withContext isValid
    } catch (e: Exception) {
        Log.e(TAG, "Error calculating MD5 for ${model.fileName}", e)
        modelFile.delete()
        return@withContext false
    }
}

suspend fun downloadModel(context: Context, model: Model, listener: DownloadListener) = withContext(Dispatchers.IO) {
    val modelFile = File(context.getExternalFilesDir(null), model.fileName)
    val totalSizeMb = model.sizeInBytes / 1024f / 1024f

    try {
        Log.d(TAG, "Starting download for ${model.fileName} from ${model.url}")

        val url = URL(model.url)
        val connection = url.openConnection().apply {
            readTimeout = 5000
            connectTimeout = 10000
        }

        BufferedInputStream(connection.getInputStream(), 5 * 1024).use { inputStream ->
            if (modelFile.exists()) {
                modelFile.delete()
            }
            modelFile.createNewFile()

            FileOutputStream(modelFile).use { outputStream ->
                val buffer = ByteArray(5 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val progress = ((downloadedBytes.toDouble() / model.sizeInBytes) * 100).toInt()
                    val downloadedMb = downloadedBytes / 1024f / 1024f

                    withContext(Dispatchers.Main) {
                        listener.onProgress(progress, downloadedMb, totalSizeMb)
                    }
                }

                outputStream.flush()
            }
        }

        Log.d(TAG, "Download completed for ${model.fileName}, verifying MD5...")

        val calculatedMD5 = calculateMD5(modelFile)
        if (!calculatedMD5.equals(model.md5, ignoreCase = true)) {
            modelFile.delete()
            throw IOException("MD5 checksum mismatch for ${model.fileName}. Expected ${model.md5} but got $calculatedMD5")
        }

        Log.d(TAG, "MD5 verification successful for ${model.fileName}")
        withContext(Dispatchers.Main) { listener.onComplete(model) }

    } catch (e: Exception) {
        Log.e(TAG, "Download failed for ${model.fileName}", e)
        if (modelFile.exists()) {
            modelFile.delete()
        }
        withContext(Dispatchers.Main) {
            listener.onError("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

@Throws(IOException::class, NoSuchAlgorithmException::class)
private fun calculateMD5(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    BufferedInputStream(FileInputStream(file), 8192).use { inputStream ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            md.update(buffer, 0, bytesRead)
        }
    }
    val hashBytes = md.digest()
    return BigInteger(1, hashBytes).toString(16).padStart(32, '0')
}

// *** THIS FUNCTION IS NOW INTERNAL INSTEAD OF PRIVATE ***
internal fun copyAllVocabAssets(context: Context) {
    val assetManager: AssetManager = context.assets
    val vocabFiles = listOf("filters_vocab_en.bin", "filters_vocab_multilingual.bin")

    vocabFiles.forEach { vocabFileName ->
        val outFile = File(context.getExternalFilesDir(null), vocabFileName)
        if (outFile.exists()) return@forEach

        try {
            assetManager.open(vocabFileName).use { inputStream ->
                FileOutputStream(outFile).use { outputStream ->
                    val buffer = ByteArray(1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            Log.d(TAG, "Copied vocab file: $vocabFileName")
        } catch (e: IOException) {
            Log.w(TAG, "Could not copy vocab file $vocabFileName (may not exist in assets)")
        }
    }
}