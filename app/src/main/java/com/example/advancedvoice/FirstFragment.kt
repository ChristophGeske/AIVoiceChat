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
import android.util.Log
import android.view.*
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class FirstFragment : Fragment(), TextToSpeech.OnInitListener {

    private val TAG = "FirstFragment"

    data class ConversationEntry(
        val speaker: String,
        val sentences: List<String>,
        val isAssistant: Boolean,
        var streamingText: String? = null
    )
    data class SpokenSentence(
        val text: String,
        val entryIndex: Int,
        val sentenceIndex: Int
    )

    private val conversation = mutableListOf<ConversationEntry>()
    private val ttsQueue = ArrayDeque<SpokenSentence>()
    private var currentSpeaking: SpokenSentence? = null
    private var currentAssistantEntryIndex: Int? = null

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var engine: SentenceTurnEngine
    private lateinit var conversationAdapter: ConversationAdapter

    private val DEFAULT_SYSTEM_PROMPT = """
        You are a helpful assistant. Answer the user's request in clear, complete sentences.
        Return plain text only. Do not use JSON or code fences unless explicitly requested.
    """.trimIndent()
    private val PREF_SYSTEM_PROMPT_KEY = "system_prompt"


    private var currentModelName = "gemini-2.5-pro"
    private var autoContinue = true
    private var isListening = false
    private var isSpeaking = false
    private var currentUtteranceId: String? = null
    private var pendingAutoListen = false

    private var userToggledSettings = false
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
        setupRecyclerView()

        textToSpeech = TextToSpeech(requireContext(), this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                requireActivity().runOnUiThread {
                    binding.newRecordingButton.visibility = View.VISIBLE
                    updateButtonStates()
                }
            }

            override fun onDone(utteranceId: String?) {
                requireActivity().runOnUiThread {
                    if (ttsQueue.isNotEmpty()) ttsQueue.removeFirst()

                    val previouslySpeaking = currentSpeaking
                    currentSpeaking = null

                    // FIXED: Explicitly notify that the old row needs its highlight removed.
                    if (previouslySpeaking != null) {
                        Log.d(TAG, "[HIGHLIGHT] Finished entry ${previouslySpeaking.entryIndex}, sentence ${previouslySpeaking.sentenceIndex}. Clearing highlight.")
                        conversationAdapter.currentSpeaking = null
                        conversationAdapter.notifyItemChanged(previouslySpeaking.entryIndex)
                    }

                    if (ttsQueue.isNotEmpty()) {
                        speakNext()
                    } else {
                        isSpeaking = false // Set only when queue is truly empty
                        Log.d(TAG, "[TTS] Queue finished.")
                        binding.newRecordingButton.visibility = View.GONE
                        updateButtonStates()
                        if (autoContinue && pendingAutoListen) {
                            Log.d(TAG, "TTS finished, triggering pending auto-listen.")
                            pendingAutoListen = false
                            startListeningInternal()
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "[TTS] onError speaking utteranceId: $utteranceId")
                onDone(utteranceId)
            }
        })

        setupSpeechRecognizer()

        engine = SentenceTurnEngine(
            uiScope = viewLifecycleOwner.lifecycleScope,
            http = httpClient,
            geminiKeyProvider = { getGeminiApiKey() },
            openAiKeyProvider = { getOpenAiApiKey() },
            systemPromptProvider = { getSystemPrompt() },
            onStreamDelta = { delta ->
                val idx = currentAssistantEntryIndex ?: addAssistantEntry(emptyList()).also { currentAssistantEntryIndex = it }
                val currentEntry = conversation[idx]
                val updatedStreamingText = (currentEntry.streamingText ?: "") + delta
                conversation[idx] = currentEntry.copy(streamingText = updatedStreamingText)
                updateConversationView()
            },
            onStreamSentence = { sentence ->
                val idx = currentAssistantEntryIndex ?: addAssistantEntry(emptyList()).also { currentAssistantEntryIndex = it }
                val normalized = sentence.trim()
                if (conversation[idx].sentences.none { it.trim() == normalized }) {
                    val pos = conversation[idx].sentences.size
                    val newSentences = conversation[idx].sentences.toMutableList().apply { add(normalized) }
                    conversation[idx] = conversation[idx].copy(sentences = newSentences)

                    ttsQueue.addLast(SpokenSentence(normalized, idx, pos))
                    if (!isSpeaking) speakNext()
                    updateConversationView()
                }
            },
            onFirstSentence = { sentence ->
                val idx = currentAssistantEntryIndex ?: addAssistantEntry(emptyList()).also { currentAssistantEntryIndex = it }
                if (conversation[idx].sentences.isEmpty()) {
                    conversation[idx] = conversation[idx].copy(sentences = listOf(sentence))
                    ttsQueue.addLast(SpokenSentence(sentence, idx, 0))
                    if (!isSpeaking) speakNext()
                    updateConversationView()
                }
            },
            onRemainingSentences = { sentences ->
                if (sentences.isEmpty()) return@SentenceTurnEngine
                val idx = currentAssistantEntryIndex ?: conversation.indexOfLast { it.isAssistant }.takeIf { it >= 0 } ?: addAssistantEntry(emptyList())

                val currentEntry = conversation[idx]
                val newSentences = currentEntry.sentences.toMutableList()
                var sentencesAdded = false
                sentences.forEach { s ->
                    if (newSentences.none { it.trim() == s.trim() }) {
                        val pos = newSentences.size
                        newSentences.add(s)
                        ttsQueue.addLast(SpokenSentence(s, idx, pos))
                        sentencesAdded = true
                    }
                }
                conversation[idx] = if (sentencesAdded) {
                    currentEntry.copy(sentences = newSentences, streamingText = null)
                } else {
                    currentEntry.copy(streamingText = null)
                }

                if (!isSpeaking && ttsQueue.isNotEmpty()) speakNext()
                updateConversationView()
            },
            onTurnFinish = {
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
                updateButtonStates()
                if (autoContinue) {
                    pendingAutoListen = true
                    if (!isSpeaking && ttsQueue.isEmpty()) {
                        Log.d(TAG, "Turn finished and TTS idle, triggering auto-listen immediately.")
                        pendingAutoListen = false
                        startListeningInternal()
                    } else {
                        Log.d(TAG, "Turn finished, but TTS is active. Auto-listen is pending.")
                    }
                }
            },
            onSystem = { msg -> addSystemEntry(msg) },
            onError = { msg ->
                addErrorEntry(msg.take(700))
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("LLM error").setMessage(msg.take(700))
                    .setPositiveButton("OK", null).show()
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
                updateButtonStates()
            }
        )
        setupUI()
        loadSavedKeys()
        setupKeyPersistence()
        setupSystemPrompt()

        binding.maxSentencesInput.setText("4")
        engine.setMaxSentences(readMaxSentences())
        binding.maxSentencesInput.addTextChangedListener { engine.setMaxSentences(readMaxSentences()) }
        val faster = prefs.getBoolean("faster_first", true)
        engine.setFasterFirst(faster)
        binding.switchFasterFirst.isChecked = faster
        binding.switchFasterFirst.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("faster_first", isChecked).apply()
            engine.setFasterFirst(isChecked)
        }
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationAdapter { entryIndex ->
            replayMessage(entryIndex)
        }
        binding.conversationRecyclerView.adapter = conversationAdapter
    }

    private fun replayMessage(entryIndex: Int) {
        Log.d(TAG, "Replay requested for entry index: $entryIndex")
        stopTts()

        val entryToReplay = conversation.getOrNull(entryIndex)
        if (entryToReplay == null || !entryToReplay.isAssistant) {
            Log.e(TAG, "Replay failed: Could not find a valid assistant entry at index $entryIndex")
            return
        }

        entryToReplay.sentences.forEachIndexed { sentenceIndex, sentence ->
            val spokenSentence = SpokenSentence(sentence, entryIndex, sentenceIndex)
            ttsQueue.addLast(spokenSentence)
        }

        if (ttsQueue.isNotEmpty()) {
            speakNext()
        }
    }

    private fun setupSystemPrompt() {
        val savedPrompt = prefs.getString(PREF_SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT)
        binding.systemPromptInput.setText(savedPrompt)
        binding.systemPromptInput.addTextChangedListener {
            prefs.edit().putString(PREF_SYSTEM_PROMPT_KEY, it.toString()).apply()
        }
        binding.resetSystemPromptButton.setOnClickListener {
            binding.systemPromptInput.setText(DEFAULT_SYSTEM_PROMPT)
            Toast.makeText(context, "System prompt reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getSystemPrompt(): String = binding.systemPromptInput.text.toString().trim()

    private fun setupUI() {
        binding.settingsHeader.setOnClickListener {
            val visible = binding.settingsContainerScrollView.visibility == View.VISIBLE
            binding.settingsContainerScrollView.visibility = if (visible) View.GONE else View.VISIBLE
            binding.settingsHeader.text = getString(if (visible) R.string.settings_show else R.string.settings_hide)
            userToggledSettings = true
        }
        binding.clearButton.setOnClickListener {
            stopConversation()
            conversation.clear()
            updateConversationView()
        }
        binding.speakButton.setOnClickListener {
            autoContinue = true
            startListeningInternal()
        }
        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            stopTts()
            if (this::engine.isInitialized) engine.abort()
            startListeningInternal()
        }
        binding.stopButton.setOnClickListener { stopConversation() }

        val radios = listOf(
            binding.radioGeminiFlash to "gemini-2.5-flash",
            binding.radioGeminiPro to "gemini-2.5-pro",
            binding.radioGpt5 to "gpt-5",
            binding.radioGpt5Mini to "gpt-5-mini"
        )
        val selectRadio: (RadioButton) -> Unit = { selected ->
            for ((rb, _) in radios) if (rb !== selected) rb.isChecked = false
            selected.isChecked = true
        }
        selectRadio(binding.radioGeminiPro)
        currentModelName = "gemini-2.5-pro"
        addSystemEntry("Model switched to $currentModelName (default)")
        for ((rb, model) in radios) {
            rb.setOnClickListener {
                if (this::engine.isInitialized) engine.abort()
                selectRadio(rb)
                currentModelName = model
                Toast.makeText(context, "Model: $currentModelName", Toast.LENGTH_SHORT).show()
                addSystemEntry("Model switched to $currentModelName")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_LONG).show()
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
                    addUserEntry(spokenText)
                    callModelApi(spokenText)
                }
                binding.speakButton.text = getString(R.string.status_ready)
            }
            override fun onError(error: Int) {
                isListening = false
                Toast.makeText(context, getString(R.string.error_stt) + " (code:$error)", Toast.LENGTH_SHORT).show()
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

    private fun startListeningInternal() {
        if (engine.isActive()) engine.abort()
        if (isSpeaking) stopTts()
        if (isListening) return
        pendingAutoListen = false
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
        pendingAutoListen = false
        if (this::engine.isInitialized) engine.abort()
        stopTts()
        stopListening()
        setLoading(false)
        binding.speakButton.text = getString(R.string.status_ready)
        binding.newRecordingButton.visibility = View.GONE
        if (!userToggledSettings) {
            binding.settingsContainerScrollView.visibility = View.VISIBLE
            binding.settingsHeader.text = getString(R.string.settings_hide)
        }
        currentAssistantEntryIndex = null
        Log.i(TAG, "[CONTROL] Conversation stopped. Resetting state.")
        updateButtonStates()
    }

    private fun stopListening() {
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        try { speechRecognizer.cancel() } catch (_: Exception) {}
        isListening = false
    }

    private fun stopTts() {
        val previouslySpeaking = currentSpeaking
        try { textToSpeech.stop() } catch (_: Exception) {}
        isSpeaking = false
        binding.newRecordingButton.visibility = View.GONE
        ttsQueue.clear()
        currentSpeaking = null
        if (previouslySpeaking != null) {
            Log.d(TAG, "[HIGHLIGHT] Manually stopping TTS. Clearing highlight for entry ${previouslySpeaking.entryIndex}.")
            conversationAdapter.currentSpeaking = null
            conversationAdapter.notifyItemChanged(previouslySpeaking.entryIndex)
        }
    }

    private fun callModelApi(userInput: String) {
        currentAssistantEntryIndex = null
        setLoading(true)
        binding.speakButton.text = getString(R.string.status_thinking)
        if (!ensureKeyAvailableForModelOrShow(currentModelName)) {
            setLoading(false)
            binding.speakButton.text = getString(R.string.status_ready)
            return
        }
        engine.setMaxSentences(readMaxSentences())
        stopTts()
        engine.startTurn(userInput, currentModelName)
    }

    private fun maybeAutoCollapseSettings() {
        if (userToggledSettings) return
        if (conversation.size > 2) {
            binding.settingsContainerScrollView.visibility = View.GONE
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

    private fun speakNext() {
        if (ttsQueue.isEmpty()) {
            isSpeaking = false
            return
        }
        val next = ttsQueue.first()
        currentSpeaking = next

        Log.d(TAG, "[HIGHLIGHT] Setting highlight for entry ${next.entryIndex}, sentence ${next.sentenceIndex}")
        conversationAdapter.currentSpeaking = next
        conversationAdapter.notifyItemChanged(next.entryIndex)

        val id = UUID.randomUUID().toString()
        textToSpeech.speak(next.text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun addUserEntry(text: String) {
        conversation.add(ConversationEntry("You", listOf(text), isAssistant = false))
        updateConversationView()
    }

    private fun addSystemEntry(text: String) {
        conversation.add(ConversationEntry("System", listOf(text), isAssistant = false))
        updateConversationView()
    }

    private fun addErrorEntry(text: String) {
        conversation.add(ConversationEntry("Error", listOf(text), isAssistant = false))
        updateConversationView()
    }

    private fun addAssistantEntry(sentences: List<String>): Int {
        val entry = ConversationEntry("Assistant", sentences, isAssistant = true, streamingText = null)
        conversation.add(entry)
        val idx = conversation.lastIndex
        updateConversationView()
        return idx
    }

    // FIXED: This is the new, robust update function.
    private fun updateConversationView() {
        Log.d(TAG, "[UI] Submitting new list to adapter. Size: ${conversation.size}")
        // Submit a fresh copy of the list. DiffUtil will handle the animations efficiently.
        conversationAdapter.submitList(conversation.toList()) {
            // This block runs after the diff is calculated and updates are dispatched.
            // Scrolling here ensures we scroll after the new item is actually in the list.
            if (conversation.isNotEmpty()) {
                binding.conversationRecyclerView.post {
                    binding.conversationRecyclerView.smoothScrollToPosition(conversation.size - 1)
                }
            }
        }
    }

    private fun updateButtonStates() {
        binding.stopButton.isEnabled = isSpeaking || isListening || (this::engine.isInitialized && engine.isActive())
        binding.newRecordingButton.isEnabled = isSpeaking
    }

    private fun setLoading(isLoading: Boolean) {
        val enableInputs = !isLoading && !isListening
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.speakButton.isEnabled = enableInputs
        binding.radioGeminiFlash.isEnabled = !isLoading
        binding.radioGeminiPro.isEnabled = !isLoading
        binding.radioGpt5.isEnabled = !isLoading
        binding.radioGpt5Mini.isEnabled = !isLoading
        binding.geminiApiKeyInput.isEnabled = enableInputs
        binding.openaiApiKeyInput.isEnabled = enableInputs
        binding.maxSentencesInput.isEnabled = enableInputs
        binding.systemPromptInput.isEnabled = enableInputs
        updateButtonStates()
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

    // FIXED: Use the device's default language.
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale.getDefault()
            val result = textToSpeech.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported for locale: $locale. Falling back to US English.")
                // Fallback to US English if the default is not supported
                val fallbackResult = textToSpeech.setLanguage(Locale.US)
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(context, "TTS Language not supported.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.i(TAG, "TTS initialized with default locale: $locale")
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