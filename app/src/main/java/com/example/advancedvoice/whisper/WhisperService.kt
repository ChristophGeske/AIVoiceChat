// In: app/src/main/java/com/example/advancedvoice/whisper/WhisperService.kt
package com.example.advancedvoice.whisper

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.advancedvoice.Event // <-- ADD THIS IMPORT
import com.whispertflite.asr.Recorder
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
    private val TAG = "WhisperService"

    // LiveData for external observers (ViewModel)
    private val _transcriptionResult = MutableLiveData<Event<String>>()
    val transcriptionResult: LiveData<Event<String>> = _transcriptionResult

    private val _downloadProgress = MutableLiveData(0)
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _isModelReady = MutableLiveData(false)
    val isModelReady: LiveData<Boolean> = _isModelReady

    private var whisper: Whisper? = null
    private var recorder: Recorder? = null
    var activeModel: Model? = null
        private set

    // Define the models available
    val multilingualModel = availableModels.find { it.fileName == "whisper-small.tflite" }!!
    val englishModel = availableModels.find { it.fileName == "whisper-tiny.en.tflite" }!!

    fun initialize(model: Model) {
        if (activeModel?.fileName == model.fileName && _isModelReady.value == true) {
            Log.d(TAG, "Model ${model.name} is already initialized.")
            return
        }
        _isModelReady.value = false
        activeModel = model
        scope.launch(Dispatchers.IO) {
            try {
                // Ensure vocab files are copied from assets
                copyAllVocabAssets(application)

                if (checkModel(application, model)) {
                    initializeWhisper(model)
                } else {
                    Log.w(TAG, "Model ${model.name} not downloaded. Please download it first.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization", e)
            }
        }
    }

    private suspend fun initializeWhisper(model: Model) {
        withContext(Dispatchers.IO) {
            whisper?.unloadModel()
            try {
                val dataDir = application.getExternalFilesDir(null)!!
                val modelFile = File(dataDir, model.fileName)
                val vocabFileName = if (model.isMultilingual) "filters_vocab_multilingual.bin" else "filters_vocab_en.bin"
                val vocabFile = File(dataDir, vocabFileName)

                whisper = Whisper(application).apply {
                    loadModel(modelFile, vocabFile, model.isMultilingual)
                    setLanguage(-1) // Auto-detect language
                    setListener(createWhisperListener())
                }
                recorder = Recorder(application).apply { setListener(createRecorderListener()) }
                Log.d(TAG, "Whisper engine initialized successfully with model: ${model.name}")
                withContext(Dispatchers.Main) { _isModelReady.postValue(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Whisper", e)
                withContext(Dispatchers.Main) { _isModelReady.postValue(false) }
            }
        }
    }

    fun startRecording() {
        if (_isModelReady.value != true) {
            Log.e(TAG, "Cannot start recording, model not ready.")
            return
        }
        if (recorder?.isInProgress == false) {
            whisper?.setAction(Whisper.Action.TRANSCRIBE)
            _isRecording.postValue(true)
            recorder?.start()
        }
    }

    fun stopRecording() {
        if (recorder?.isInProgress == true) {
            recorder?.stop()
            // isRecording will be set to false in the recorder listener
        }
    }

    fun downloadModel(model: Model, onFinished: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val listener = object : DownloadListener {
                override fun onProgress(progress: Int, downloadedMb: Float, totalMb: Float) {
                    _downloadProgress.postValue(progress)
                }
                override fun onComplete(completedModel: Model) {
                    _downloadProgress.postValue(100) // Mark as complete
                    initialize(completedModel)
                    scope.launch { onFinished(true) }
                }
                override fun onError(message: String) {
                    Log.e(TAG, "Download error: $message")
                    _downloadProgress.postValue(-1) // Indicate error
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
        whisper?.unloadModel()
        recorder?.stop()
    }

    private fun createWhisperListener() = object : Whisper.WhisperListener {
        override fun onUpdateReceived(message: String) {
            Log.d(TAG, "Whisper Update: $message")
        }
        override fun onResultReceived(result: WhisperResult) {
            Log.i(TAG, "Whisper Result: ${result.result}")
            _transcriptionResult.postValue(Event(result.result))
        }
    }

    private fun createRecorderListener() = object : Recorder.RecorderListener {
        override fun onUpdateReceived(message: String) {
            if (message == Recorder.MSG_RECORDING_DONE) {
                _isRecording.postValue(false)
                whisper?.start()
            }
        }
    }
}