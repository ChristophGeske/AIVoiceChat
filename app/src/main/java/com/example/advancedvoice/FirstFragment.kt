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
import android.view.*
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

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

    private lateinit var engine: SentenceTurnEngine

    private var currentModelName = "gemini-2.5-flash"
    private var autoContinue = true
    private var isListening = false
    private var isSpeaking = false
    private var currentUtteranceId: String? = null

    // TTS queue: speak one by one without interrupting previous sentence
    private val ttsQueue = ArrayDeque<String>()
    private val ttsBufferLimit = 4 // prefetch up to this many sentences

    // Settings collapse state
    private var userToggledSettings = false // if user toggles, disable auto-collapse

    // Prefs
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
                requireActivity().runOnUiThread {
                    if (ttsQueue.isNotEmpty()) ttsQueue.removeFirst()
                    isSpeaking = false
                    if (ttsQueue.isNotEmpty()) {
                        speakQueued(ttsQueue.first())
                    } else {
                        if (this@FirstFragment::engine.isInitialized && engine.hasMoreToSay()) {
                            engine.requestNext()
                        } else if (autoContinue) {
                            startListening()
                        }
                        binding.newRecordingButton.visibility = View.GONE
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                onDone(utteranceId)
            }
        })

        setupSpeechRecognizer()

        engine = SentenceTurnEngine(
            uiScope = viewLifecycleOwner.lifecycleScope,
            http = httpClient,
            geminiKeyProvider = { getGeminiApiKey() },
            openAiKeyProvider = { getOpenAiApiKey() },
            onSentence = { sentence ->
                // queue TTS to avoid interruption
                ttsQueue.addLast(sentence)
                if (!isSpeaking) {
                    speakQueued(ttsQueue.first())
                }
                // prefetch more while speaking up to buffer limit (or max sentences)
                val bufferLimit = min(readMaxSentences(), ttsBufferLimit)
                while (engine.hasMoreToSay() && ttsQueue.size < bufferLimit) {
                    engine.requestNext()
                }
                appendToConversation("Assistant", sentence)
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
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
            }
        )

        setupUI()
        setupMenu()
        loadSavedKeys()
        setupKeyPersistence()

        // default max sentences = 4
        binding.maxSentencesInput.setText("4")
        engine.setMaxSentences(readMaxSentences())
        binding.maxSentencesInput.addTextChangedListener {
            engine.setMaxSentences(readMaxSentences())
        }
    }

    private fun setupUI() {
        // Settings collapsible
        binding.settingsHeader.setOnClickListener {
            val visible = binding.settingsContainer.visibility == View.VISIBLE
            binding.settingsContainer.visibility = if (visible) View.GONE else View.VISIBLE
            binding.settingsHeader.text = getString(if (visible) R.string.settings_show else R.string.settings_hide)
            userToggledSettings = true
        }

        // Clear conversation button (bottom bar)
        binding.clearButton.setOnClickListener { clearConversationAndRevealSettings() }

        // Speak
        binding.speakButton.setOnClickListener {
            autoContinue = true
            startListening()
        }

        // New recording (preempt)
        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            stopTts()
            if (this::engine.isInitialized) engine.abort()
            startListening()
        }

        // Stop conversation
        binding.stopButton.setOnClickListener { stopConversation() }

        // Models (manual single-selection)
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

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
                val clear = menu.findItem(R.id.action_clear)
                clear?.isVisible = true
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_clear -> {
                        clearConversationAndRevealSettings()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun clearConversationAndRevealSettings() {
        binding.conversationLog.text = Html.fromHtml("", Html.FROM_HTML_MODE_COMPACT)
        ttsQueue.clear()
        if (!userToggledSettings) {
            binding.settingsContainer.visibility = View.VISIBLE
            binding.settingsHeader.text = getString(R.string.settings_hide)
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
                maybeAutoCollapseSettings()
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
                Toast.makeText(context, getString(R.string.error_stt), Toast.LENGTH_SHORT).show()
                binding.speakButton.text = getString(R.string.status_ready)
                setLoading(false)
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
        if (!userToggledSettings) {
            binding.settingsContainer.visibility = View.VISIBLE
            binding.settingsHeader.text = getString(R.string.settings_hide)
        }
        ttsQueue.clear()
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
        ttsQueue.clear()
    }

    private fun callModelApi(userInput: String) {
        setLoading(true)
        binding.speakButton.text = getString(R.string.status_thinking)

        if (!ensureKeyAvailableForModelOrShow(currentModelName)) {
            setLoading(false)
            binding.speakButton.text = getString(R.string.status_ready)
            return
        }

        engine.setMaxSentences(readMaxSentences())
        ttsQueue.clear()
        engine.startTurn(userInput, currentModelName)
    }

    private fun maybeAutoCollapseSettings() {
        if (userToggledSettings) return
        val longContent = (binding.conversationLog.text?.length ?: 0) > 200
        if (longContent) {
            binding.settingsContainer.visibility = View.GONE
            binding.settingsHeader.text = getString(R.string.settings_show)
        }
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

    private fun readMaxSentences(): Int {
        val n = binding.maxSentencesInput.text?.toString()?.trim()?.toIntOrNull()
        return (n ?: 4).coerceIn(1, 10)
    }

    private fun speakQueued(text: String) {
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        isSpeaking = true
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
        binding.contentScroll.post { binding.contentScroll.fullScroll(View.FOCUS_DOWN) }
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
        binding.maxSentencesInput.isEnabled = enableInputs
    }

    // Persist/load keys

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
                Toast.makeText(context, "TTS Language not supported.", Toast.LENGTH_SHORT).show()
            }
        } else {
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