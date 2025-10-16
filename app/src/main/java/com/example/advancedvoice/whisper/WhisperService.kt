// In: app/src/main/java/com/example/advancedvoice/whisper/WhisperService.kt
package com.example.advancedvoice.whisper

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.advancedvoice.Event
import com.whispertflite.asr.RecordBuffer
import com.whispertflite.asr.Whisper
import com.whispertflite.asr.WhisperResult
import com.whispertflite.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WhisperService(
    private val application: Application,
    private val scope: CoroutineScope
) {
    private val TAG = "WhisperService" // Log tag

    private val _transcriptionResult = MutableLiveData<Event<String>>()
    val transcriptionResult: LiveData<Event<String>> = _transcriptionResult

    private val _downloadProgress = MutableLiveData(0)
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _isModelReady = MutableLiveData(false)
    val isModelReady: LiveData<Boolean> = _isModelReady

    private var whisper: Whisper? = null
    var activeModel: Model? = null
        private set

    val multilingualModel = availableModels.find { it.fileName == "whisper-small.tflite" }!!
    val englishModel = availableModels.find { it.fileName == "whisper-tiny.en.tflite" }!!

    fun initialize(model: Model) {
        if (activeModel?.fileName == model.fileName && _isModelReady.value == true) {
            Log.d(TAG, "Model ${model.name} is already initialized and ready.")
            return
        }
        Log.i(TAG, "Initializing model: ${model.name}...")
        _isModelReady.value = false
        activeModel = model
        scope.launch(Dispatchers.IO) {
            try {
                copyAllVocabAssets(application)
                if (checkModel(application, model)) {
                    Log.d(TAG, "Model file found. Initializing Whisper engine.")
                    initializeWhisperEngine(model)
                } else {
                    Log.w(TAG, "Model ${model.name} not downloaded. Please download it first.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization", e)
            }
        }
    }

    private suspend fun initializeWhisperEngine(model: Model) {
        withContext(Dispatchers.IO) {
            whisper?.unloadModel()
            try {
                val dataDir = application.getExternalFilesDir(null)!!
                val modelFile = File(dataDir, model.fileName)
                val vocabFileName = if (model.isMultilingual) "filters_vocab_multilingual.bin" else "filters_vocab_en.bin"
                val vocabFile = File(dataDir, vocabFileName)

                whisper = Whisper(application).apply {
                    loadModel(modelFile, vocabFile, model.isMultilingual)
                    setLanguage(-1)
                    setListener(createWhisperListener())
                }
                Log.i(TAG, "Whisper engine initialized successfully with model: ${model.name}")
                withContext(Dispatchers.Main) { _isModelReady.postValue(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Whisper engine", e)
                withContext(Dispatchers.Main) { _isModelReady.postValue(false) }
            }
        }
    }

    fun transcribeAudio(audioData: ByteArray) {
        Log.d(TAG, "transcribeAudio called. Model ready: ${_isModelReady.value}, Audio size: ${audioData.size}")
        if (_isModelReady.value != true) {
            Log.e(TAG, "Cannot transcribe, model not ready.")
            _transcriptionResult.postValue(Event(""))
            return
        }
        if (audioData.isEmpty()) {
            Log.e(TAG, "Cannot transcribe, audio data is empty.")
            _transcriptionResult.postValue(Event(""))
            return
        }
        RecordBuffer.setOutputBuffer(audioData)
        whisper?.setAction(Whisper.Action.TRANSCRIBE)
        whisper?.start()
    }

    fun downloadModel(model: Model, onFinished: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val listener = object : DownloadListener {
                override fun onProgress(progress: Int, downloadedMb: Float, totalMb: Float) {
                    _downloadProgress.postValue(progress)
                }
                override fun onComplete(completedModel: Model) {
                    _downloadProgress.postValue(100)
                    initialize(completedModel)
                    scope.launch { onFinished(true) }
                }
                override fun onError(message: String) {
                    Log.e(TAG, "Download error: $message")
                    _downloadProgress.postValue(-1)
                    scope.launch { onFinished(false) }
                }
            }
            com.whispertflite.utils.downloadModel(application, model, listener)
        }
    }

    suspend fun isModelDownloaded(model: Model): Boolean {
        return withContext(Dispatchers.IO) {
            checkModel(application, model)
        }
    }

    fun release() {
        Log.i(TAG, "Releasing Whisper service resources.")
        whisper?.unloadModel()
        _isModelReady.postValue(false)
        activeModel = null
    }

    private fun createWhisperListener() = object : Whisper.WhisperListener {
        override fun onUpdateReceived(message: String) {
            Log.d(TAG, "Whisper Engine Update: $message")
        }
        override fun onResultReceived(result: WhisperResult) {
            Log.i(TAG, "Whisper Engine Result: ${result.result}")
            _transcriptionResult.postValue(Event(result.result))
        }
    }
}