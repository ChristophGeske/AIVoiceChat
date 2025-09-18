package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.text.Spanned
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class FirstFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private var currentModelName = "gemini-2.5-flash"
    private var autoContinue = true
    private var isSpeaking = false
    private var isListening = false
    private var currentUtteranceId: String? = null

    private lateinit var engine: SentenceTurnEngine

    private val prefs by lazy { requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE) }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) Toast.makeText(context, getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TTS
        textToSpeech = TextToSpeech(requireContext(), this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                currentUtteranceId = utteranceId
                isSpeaking = true
                requireActivity().runOnUiThread { binding.newRecordingButton.visibility = View.VISIBLE }
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                requireActivity().runOnUiThread {
                    binding.newRecordingButton.visibility = View.GONE
                    // If engine is still active and has more, ask next sentence; else resume listening
                    if (this@FirstFragment::engine.isInitialized && engine.isActive() && engine.hasMoreToSay()) {
                        engine.requestNext()
                    } else if (autoContinue) {
                        startListening()
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                requireActivity().runOnUiThread {
                    binding.newRecordingButton.visibility = View.GONE
                    if (autoContinue) startListening()
                }
            }
        })

        setupSpeechRecognizer()

        engine = SentenceTurnEngine(
            uiScope = viewLifecycleOwner.lifecycleScope,
            http = httpClient,
            geminiKeyProvider = { getGeminiApiKey() },
            openAiKeyProvider = { getOpenAiApiKey() },
            onSentence = { sentence ->
                appendToConversation("Assistant", sentence)
                speak(sentence)
            },
            onTurnFinish = {
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
            },
            onSystem = { msg -> appendToConversation("System", msg) },
            onError = { msg ->
                appendToConversation("Error", msg)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("LLM error")
                    .setMessage(msg.take(700))
                    .setPositiveButton("OK", null)
                    .show()
            }
        )

        setupUI()
        loadSavedKeys()
        setupKeyPersistence()
    }

    private fun setupUI() {
        binding.speakButton.setOnClickListener {
            autoContinue = true
            startListening()
        }

        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            stopTts()
            if (this::engine.isInitialized) engine.abort()
            startListening()
        }

        binding.stopButton.setOnClickListener { stopConversation() }

        val radios = listOf(
            binding.radioGeminiFlash to "gemini-2.5-flash",
            binding.radioGeminiPro to "gemini-2.5-pro",
            binding.radioGpt5 to "gpt-5",
            binding.radioGpt5Mini to "gpt-5-mini"
        )

        val selectRadio: (RadioButton) -> Unit = { selected ->
            radios.forEach { (rb, _) -> if (rb !== selected) rb.isChecked = false }
            selected.isChecked = true
        }

        radios.forEach { (rb, model) ->
            rb.setOnClickListener {
                if (this::engine.isInitialized) engine.abort()
                selectRadio(rb)
                currentModelName = model
                Toast.makeText(context, "Model: $currentModelName", Toast.LENGTH_SHORT).show()
                appendToConversation("System", "Model switched to $currentModelName")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(context, "Speech recognition is not available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                binding.speakButton.text = getString(R.string.status_listening)
                setLoading(false)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    appendToConversation("You", spokenText)
                    callModelApi(spokenText)
                }
                binding.speakButton.text = getString(R.string.status_ready)
            }

            override fun onError(error: Int) {
                isListening = false
                Log.e("STT_ERROR", "Error: $error")
                Toast.makeText(context, getString(R.string.error_stt), Toast.LENGTH_SHORT).show()
                binding.speakButton.text = getString(R.string.status_ready)
                setLoading(false)
                if (autoContinue) startListening()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening) return
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        stopTts()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        speechRecognizer.startListening(intent)
    }

    private fun stopConversation() {
        autoContinue = false
        if (this::engine.isInitialized) engine.abort()
        stopTts()
        stopListening()
        setLoading(false)
        binding.speakButton.text = getString(R.string.status_ready)
        binding.newRecordingButton.visibility = View.GONE
        appendToConversation("System", "Conversation stopped.")
    }

    private fun stopListening() {
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        try { speechRecognizer.cancel() } catch (_: Exception) {}
        isListening = false
    }

    private fun stopTts() {
        try { textToSpeech.stop() } catch (_: Exception) {}
        isSpeaking = false
        binding.newRecordingButton.visibility = View.GONE
    }

    private fun callModelApi(userInput: String) {
        setLoading(true)
        binding.speakButton.text = getString(R.string.status_thinking)

        if (!ensureKeyAvailableForModelOrShow(currentModelName)) {
            setLoading(false)
            binding.speakButton.text = getString(R.string.status_ready)
            return
        }

        // Start sentence-by-sentence turn (one sentence per request)
        engine.startTurn(userInput, currentModelName)
    }

    private fun ensureKeyAvailableForModelOrShow(modelName: String): Boolean {
        val isGpt = modelName.startsWith("gpt-", ignoreCase = true)
        val key = if (isGpt) getOpenAiApiKey() else getGeminiApiKey()
        if (key.isNotBlank()) return true

        val provider = if (isGpt) "OpenAI" else "Gemini"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("API key required")
            .setMessage("Please enter your $provider API key to use $modelName.")
            .setPositiveButton("OK", null)
            .show()
        return false
    }

    // ---- UI helpers ----

    private fun speak(text: String) {
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        val params = Bundle()
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun appendToConversation(speaker: String, text: String) {
        val color = when (speaker.lowercase(Locale.US)) {
            "you" -> "#1E88E5"
            "assistant" -> "#43A047"
            "system" -> "#8E24AA"
            "error" -> "#E53935"
            else -> "#212121"
        }
        val safeText = text.replace("<", "&lt;").replace(">", "&gt;")
        val entry = "<b><font color='$color'>$speaker</font>:</b> $safeText<br/><br/><br/>"
        val current: Spanned = binding.conversationLog.text as? Spanned
            ?: Html.fromHtml("", Html.FROM_HTML_MODE_COMPACT)
        val updated = Html.fromHtml(current.toString() + entry, Html.FROM_HTML_MODE_COMPACT)
        binding.conversationLog.text = updated
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setLoading(isLoading: Boolean) {
        val enableInputs = !isLoading && !isListening
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.speakButton.isEnabled = enableInputs

        binding.radioGeminiFlash.isEnabled = !isLoading
        binding.radioGeminiPro.isEnabled = !isLoading
        binding.radioGpt5.isEnabled = !isLoading
        binding.radioGpt5Mini.isEnabled = !isLoading

        binding.stopButton.isEnabled = !isLoading || isListening || isSpeaking
        binding.newRecordingButton.isEnabled = !isLoading && isSpeaking

        binding.geminiApiKeyInput.isEnabled = enableInputs
        binding.openaiApiKeyInput.isEnabled = enableInputs
    }

    private fun loadSavedKeys() {
        binding.geminiApiKeyInput.setText(prefs.getString("gemini_key", "") ?: "")
        binding.openaiApiKeyInput.setText(prefs.getString("openai_key", "") ?: "")
    }

    private fun setupKeyPersistence() {
        binding.geminiApiKeyInput.addTextChangedListener {
            prefs.edit().putString("gemini_key", it?.toString()?.trim()).apply()
        }
        binding.openaiApiKeyInput.addTextChangedListener {
            prefs.edit().putString("openai_key", it?.toString()?.trim()).apply()
        }
    }

    private fun getGeminiApiKey(): String = binding.geminiApiKeyInput.text?.toString()?.trim().orEmpty()
    private fun getOpenAiApiKey(): String = binding.openaiApiKeyInput.text?.toString()?.trim().orEmpty()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
                Toast.makeText(context, "TTS Language not supported.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("TTS", "Initialization failed")
            Toast.makeText(context, getString(R.string.error_tts_init), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { textToSpeech.stop(); textToSpeech.shutdown() } catch (_: Exception) {}
        try { speechRecognizer.destroy() } catch (_: Exception) {}
        _binding = null
    }
}