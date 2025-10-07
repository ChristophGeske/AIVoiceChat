// FirstFragment.kt
package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
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
    private var isTtsInitialized = false
    private var ttsEnginePackageName: String? = null // Stored engine name
    private lateinit var speechRecognizer: SpeechRecognizer
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var conversationAdapter: ConversationAdapter

    private val DEFAULT_SYSTEM_PROMPT = """
        You are a helpful assistant. Answer the user's request in clear, complete sentences.
        Return plain text only. Do not use JSON or code fences unless explicitly requested.
    """.trimIndent()
    private val PREF_SYSTEM_PROMPT_KEY = "system_prompt"
    private val PREF_SYSTEM_PROMPT_EXT_KEY = "system_prompt_extension"
    private val PREF_MAX_SENTENCES_KEY = "max_sentences"

    private var currentModelName = "gemini-2.5-pro"
    private var autoContinue = true
    private var isListening = false
    private var isSpeaking = false
    private var pendingAutoListen = false
    private var pendingStartAfterPermission = false

    private var userToggledSettings = false
    private val prefs by lazy { requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE) }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Log.i(TAG, "[PERM] RECORD_AUDIO granted=$isGranted pending=$pendingStartAfterPermission")
            if (isGranted && pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                startListeningInternal()
            } else if (!isGranted) {
                Toast.makeText(context, getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    private val engine: SentenceTurnEngine by lazy {
        val engineCallbacks = SentenceTurnEngine.Callbacks(
            onStreamDelta = { delta ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val currentEntry = conversation.getOrNull(idx) ?: return@Callbacks
                val updatedStreamingText = (currentEntry.streamingText ?: "") + delta
                conversation[idx] = currentEntry.copy(streamingText = updatedStreamingText)
                Log.d(TAG, "[UI] Stream delta len=${delta.length}, entry=$idx streamLen=${updatedStreamingText.length}")
                updateConversationView()
            },
            onStreamSentence = { sentence ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val currentEntry = conversation.getOrNull(idx) ?: return@Callbacks
                val trimmedSentence = sentence.trim()
                if (currentEntry.sentences.none { it.trim() == trimmedSentence }) {
                    val newSentences = currentEntry.sentences.toMutableList().apply { add(trimmedSentence) }
                    conversation[idx] = currentEntry.copy(sentences = newSentences)
                    val pos = newSentences.size - 1
                    Log.d(TAG, "[UI] Stream sentence added entry=$idx pos=$pos len=${trimmedSentence.length}")
                    ttsQueue.addLast(SpokenSentence(trimmedSentence, idx, pos))
                    if (!isSpeaking) speakNext()
                    updateConversationView()
                }
            },
            onFirstSentence = { sentence ->
                val newEntry = ConversationEntry("Assistant", listOf(sentence), isAssistant = true)
                conversation.add(newEntry)
                currentAssistantEntryIndex = conversation.lastIndex
                Log.d(TAG, "[UI] First sentence added entryIndex=$currentAssistantEntryIndex len=${sentence.length}")

                ttsQueue.addLast(SpokenSentence(sentence, currentAssistantEntryIndex!!, 0))
                if (!isSpeaking) speakNext()
                updateConversationView()
            },
            onRemainingSentences = { sentences ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val currentEntry = conversation.getOrNull(idx) ?: return@Callbacks
                val newSentences = currentEntry.sentences.toMutableList()
                var sentencesAdded = false
                sentences.forEach { s ->
                    val trimmedSentence = s.trim()
                    if (newSentences.none { it.trim() == trimmedSentence }) {
                        val pos = newSentences.size
                        newSentences.add(trimmedSentence)
                        ttsQueue.addLast(SpokenSentence(trimmedSentence, idx, pos))
                        sentencesAdded = true
                        Log.d(TAG, "[UI] Remainder sentence added entry=$idx pos=$pos len=${trimmedSentence.length}")
                    }
                }
                if (sentencesAdded) {
                    conversation[idx] = currentEntry.copy(sentences = newSentences)
                    if (!isSpeaking) speakNext()
                    updateConversationView()
                }
            },
            onFinalResponse = { fullText ->
                val idx = currentAssistantEntryIndex ?: return@Callbacks
                val currentEntry = conversation.getOrNull(idx) ?: return@Callbacks
                val finalSentences = SentenceSplitter.splitIntoSentences(fullText)
                conversation[idx] = currentEntry.copy(sentences = finalSentences, streamingText = null)
                Log.d(TAG, "[UI] Final response applied entry=$idx sentences=${finalSentences.size}")
                updateConversationView()
            },
            onTurnFinish = {
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
                updateButtonStates()
                if (autoContinue) {
                    pendingAutoListen = true
                    if (!isSpeaking && ttsQueue.isEmpty()) {
                        Log.d(TAG, "Turn finished, TTS idle, auto-listening immediately.")
                        pendingAutoListen = false
                        startListeningInternal()
                    } else {
                        Log.d(TAG, "Turn finished, TTS active, auto-listen is pending.")
                    }
                }
            },
            onSystem = { msg ->
                addSystemEntry(msg)
            },
            onError = { msg ->
                addErrorEntry(msg.take(700))
                Log.e(TAG, "[UI] LLM error: ${msg.take(300)}")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("LLM error")
                    .setMessage(msg.take(700))
                    .setPositiveButton("OK", null)
                    .show()
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
                updateButtonStates()
            }
        )
        SentenceTurnEngine(
            uiScope = viewLifecycleOwner.lifecycleScope,
            http = httpClient,
            geminiKeyProvider = { getGeminiApiKey() },
            openAiKeyProvider = { getOpenAiApiKey() },
            systemPromptProvider = { getCombinedSystemPrompt() }, // Use combined prompt
            callbacks = engineCallbacks
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupTextToSpeech()
        setupSpeechRecognizer()
        setupUI()
        loadSavedKeys()
        setupKeyPersistence()
        setupSystemPrompt()
        setupMaxSentences() // load + live-apply max sentences

        engine.setMaxSentences(readMaxSentences())
        val faster = prefs.getBoolean("faster_first", true)
        engine.setFasterFirst(faster)
        binding.switchFasterFirst.isChecked = faster
        binding.switchFasterFirst.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("faster_first", isChecked).apply()
            engine.setFasterFirst(isChecked)
        }
    }

    private fun setupTextToSpeech() {
        val tempTts = TextToSpeech(context, null)
        ttsEnginePackageName = tempTts.defaultEngine // Store the package name
        tempTts.shutdown()
        Log.i(TAG, "[TTS] System's preferred engine is: $ttsEnginePackageName")

        textToSpeech = TextToSpeech(requireContext(), this, ttsEnginePackageName)

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

                    if (previouslySpeaking != null) {
                        Log.d(TAG, "[HIGHLIGHT] Finished entry ${previouslySpeaking.entryIndex}, sentence ${previouslySpeaking.sentenceIndex}. Clearing highlight.")
                        conversationAdapter.currentSpeaking = null
                        conversationAdapter.notifyItemChanged(previouslySpeaking.entryIndex)
                    }

                    if (ttsQueue.isNotEmpty()) {
                        speakNext()
                    } else {
                        isSpeaking = false
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
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            val locale = Locale.getDefault()
            val result = textToSpeech.setLanguage(locale)

            Log.i(TAG, "[TTS] TTS Engine Initialized successfully. Engine in use: $ttsEnginePackageName")

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "[TTS] Language ($locale) not supported by this engine! Falling back to US English.")
                val fallbackResult = textToSpeech.setLanguage(Locale.US)
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "[TTS] Fallback to US English also failed!")
                    Toast.makeText(context, "TTS Language not supported.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.i(TAG, "[TTS] Set language to default locale: $locale")
            }
        } else {
            isTtsInitialized = false
            Log.e(TAG, "[TTS] TTS Initialization failed with status code: $status")
            Toast.makeText(context, getString(R.string.error_tts_init), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationAdapter { entryIndex -> replayMessage(entryIndex) }
        binding.conversationRecyclerView.adapter = conversationAdapter
    }

    private fun replayMessage(entryIndex: Int) {
        if (!isTtsInitialized) {
            Toast.makeText(context, "Text-to-Speech is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
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

        val savedExt = prefs.getString(PREF_SYSTEM_PROMPT_EXT_KEY, "") ?: ""
        binding.systemPromptExtensionInput.setText(savedExt)

        binding.systemPromptInput.doAfterTextChanged { editable ->
            val v = editable?.toString().orEmpty()
            prefs.edit().putString(PREF_SYSTEM_PROMPT_KEY, v).apply()
            Log.d(TAG, "[CONFIG] systemPrompt updated len=${v.length}")
            // Applied on next turn via provider
        }
        binding.systemPromptExtensionInput.doAfterTextChanged { editable ->
            val v = editable?.toString().orEmpty()
            prefs.edit().putString(PREF_SYSTEM_PROMPT_EXT_KEY, v).apply()
            Log.d(TAG, "[CONFIG] systemPromptExtension updated len=${v.length}")
        }

        binding.resetSystemPromptButton.setOnClickListener {
            binding.systemPromptInput.setText(DEFAULT_SYSTEM_PROMPT)
            Toast.makeText(context, "System prompt reset to default", Toast.LENGTH_SHORT).show()
            // No reset for extension per requirements
        }
    }

    private fun setupMaxSentences() {
        val saved = prefs.getInt(PREF_MAX_SENTENCES_KEY, 4).coerceIn(1, 10)
        binding.maxSentencesInput.setText(saved.toString())
        binding.maxSentencesInput.doAfterTextChanged { editable ->
            val n = editable?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 10)
            if (n != null) {
                prefs.edit().putInt(PREF_MAX_SENTENCES_KEY, n).apply()
                engine.setMaxSentences(n)
                Log.i(TAG, "[CONFIG] maxSentences updated live to $n")
            }
        }
    }

    private fun getCombinedSystemPrompt(): String {
        val base = binding.systemPromptInput.text?.toString()?.trim().orEmpty()
        val ext = binding.systemPromptExtensionInput.text?.toString()?.trim().orEmpty()
        return if (ext.isBlank()) base else "$base\n\n$ext"
    }

    private fun getSystemPrompt(): String = binding.systemPromptInput.text.toString().trim()

    private fun setupUI() {
        binding.settingsHeader.setOnClickListener {
            val visible = binding.settingsContainerScrollView.visibility == View.VISIBLE
            binding.settingsContainerScrollView.visibility = if (visible) View.GONE else View.VISIBLE
            binding.settingsHeader.text = getString(if (visible) R.string.settings_show else R.string.settings_hide)
            userToggledSettings = true
            Log.d(TAG, "[UI] Settings panel toggled -> visible=${!visible}")
        }
        binding.clearButton.setOnClickListener {
            Log.i(TAG, "[CONTROL] Clear conversation tapped")
            stopConversation()
            conversation.clear()
            engine.clearHistory()
            updateConversationView()
        }
        binding.speakButton.setOnClickListener {
            Log.i(TAG, "[CONTROL] Speak tapped -> start listening (autoContinue=true)")
            autoContinue = true
            startListeningInternal()
        }
        binding.newRecordingButton.setOnClickListener {
            Log.i(TAG, "[CONTROL] New recording tapped -> stop TTS and restart listening")
            autoContinue = true
            stopTts()
            startListeningInternal()
        }
        binding.stopButton.setOnClickListener {
            Log.i(TAG, "[CONTROL] Stop tapped")
            stopConversation()
        }

        val radios = listOf(
            binding.radioGeminiFlash to "gemini-2.5-flash",
            binding.radioGeminiPro to "gemini-2.5-pro",
            binding.radioGpt5 to "gpt-5-turbo",
            binding.radioGpt5Mini to "gpt-5-mini"
        )
        val selectRadio: (RadioButton) -> Unit = { selected ->
            radios.forEach { (rb, _) -> rb.isChecked = (rb === selected) }
        }

        selectRadio(binding.radioGeminiPro)
        currentModelName = "gemini-2.5-pro"
        addSystemEntry("Model switched to Gemini 2.5 Pro (default)")

        for ((rb, model) in radios) {
            rb.setOnClickListener {
                if (engine.isActive()) engine.abort()
                selectRadio(rb)
                currentModelName = model
                val userFacingModelName = rb.text.toString()
                Toast.makeText(context, "Model: $userFacingModelName", Toast.LENGTH_SHORT).show()
                addSystemEntry("Model switched to $userFacingModelName")
                Log.i(TAG, "[MODEL] Selected $model")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_LONG).show()
            Log.e(TAG, "[STT] Recognition not available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                binding.speakButton.text = getString(R.string.status_listening)
                setLoading(false)
                Log.d(TAG, "[STT] Ready for speech")
                maybeAutoCollapseSettings()
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "[STT] Results: ${matches?.firstOrNull()?.take(100)}")
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    addUserEntry(spokenText)
                    callModelApi(spokenText)
                }
                binding.speakButton.text = getString(R.string.status_ready)
            }
            override fun onError(error: Int) {
                isListening = false
                Log.e(TAG, "[STT] Error code=$error")
                Toast.makeText(context, getString(R.string.error_stt) + " (code:$error)", Toast.LENGTH_SHORT).show()
                binding.speakButton.text = getString(R.string.status_ready)
                setLoading(false)
            }
            override fun onBeginningOfSpeech() { Log.d(TAG, "[STT] Beginning of speech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d(TAG, "[STT] End of speech") }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) Log.d(TAG, "[STT] Partial: ${partial.take(80)}")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListeningInternal() {
        // Capture draft BEFORE aborting, so we can preserve interrupted content
        val draft = buildAssistantDraft()
        if (engine.isActive() && draft != null) {
            Log.i(TAG, "[INTERRUPT] Injecting assistant draft len=${draft.length} before abort")
            engine.injectAssistantDraft(draft)
        }

        if (engine.isActive()) {
            Log.i(TAG, "[CONTROL] Start listening requested -> aborting active engine")
            engine.abort()
        }
        if (isSpeaking) {
            Log.i(TAG, "[CONTROL] Start listening requested -> stopping TTS")
            stopTts()
        }
        if (isListening) {
            Log.d(TAG, "[STT] Already listening; ignoring start request")
            return
        }
        pendingAutoListen = false

        val permission = Manifest.permission.RECORD_AUDIO
        val granted = ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "[PERM] RECORD_AUDIO missing -> requesting")
            pendingStartAfterPermission = true
            requestPermissionLauncher.launch(permission)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        Log.i(TAG, "[STT] startListening()")
        speechRecognizer.startListening(intent)
    }

    private fun stopConversation() {
        autoContinue = false
        pendingAutoListen = false
        if (engine.isActive()) engine.abort()
        stopTts()
        stopListening()
        setLoading(false)
        binding.speakButton.text = getString(R.string.status_ready)
        binding.newRecordingButton.visibility = View.GONE
        currentAssistantEntryIndex = null
        Log.i(TAG, "[CONTROL] Conversation stopped. Resetting state.")
        updateButtonStates()
    }

    private fun stopListening() {
        try {
            if (this::speechRecognizer.isInitialized) {
                Log.i(TAG, "[STT] stopListening() + cancel()")
                speechRecognizer.stopListening()
                speechRecognizer.cancel()
            }
        } catch (_: Exception) {}
        isListening = false
    }

    private fun stopTts() {
        if (!this::textToSpeech.isInitialized) return
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
        Log.i(TAG, "[LLM] Calling model: $currentModelName")
        if (!ensureKeyAvailableForModelOrShow(currentModelName)) {
            setLoading(false)
            binding.speakButton.text = getString(R.string.status_ready)
            return
        }
        engine.setMaxSentences(readMaxSentences()) // ensure latest value at call time
        stopTts()
        engine.startTurn(userInput, currentModelName)
    }

    private fun maybeAutoCollapseSettings() {
        if (userToggledSettings) return
        if (conversation.size > 2) {
            binding.settingsContainerScrollView.visibility = View.GONE
            binding.settingsHeader.text = getString(R.string.settings_show)
            Log.d(TAG, "[UI] Auto-collapsed settings")
        }
    }

    private fun ensureKeyAvailableForModelOrShow(modelName: String): Boolean {
        val isGpt = modelName.startsWith("gpt-", ignoreCase = true)
        val key = if (isGpt) getOpenAiApiKey() else getGeminiApiKey()
        if (key.isNotBlank()) return true
        val provider = if (isGpt) "OpenAI" else "Gemini"
        Log.w(TAG, "[KEY] Missing API key for $provider")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("API key required")
            .setMessage("Please enter your $provider API key to use $modelName.")
            .setPositiveButton("OK", null)
            .show()
        return false
    }

    private fun readMaxSentences(): Int {
        val n = binding.maxSentencesInput.text?.toString()?.trim()?.toIntOrNull()
        val value = (n ?: prefs.getInt(PREF_MAX_SENTENCES_KEY, 4)).coerceIn(1, 10)
        Log.i(TAG, "[CONFIG] maxSentences=$value")
        return value
    }

    private fun speakNext() {
        if (!isTtsInitialized) {
            Log.w(TAG, "[TTS] Speak call ignored: TTS not yet initialized.")
            return
        }
        if (ttsQueue.isEmpty()) {
            isSpeaking = false
            return
        }
        val next = ttsQueue.first()
        currentSpeaking = next

        Log.d(TAG, "[HIGHLIGHT] Setting highlight for entry ${next.entryIndex}, sentence ${next.sentenceIndex}")
        conversationAdapter.currentSpeaking = next
        binding.conversationRecyclerView.post {
            conversationAdapter.notifyItemChanged(next.entryIndex)
        }

        val id = UUID.randomUUID().toString()
        textToSpeech.speak(next.text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun addUserEntry(text: String) {
        conversation.add(ConversationEntry("You", listOf(text), isAssistant = false))
        Log.d(TAG, "[UI] +User entry (len=${text.length})")
        updateConversationView()
    }

    private fun addSystemEntry(text: String) {
        conversation.add(ConversationEntry("System", listOf(text), isAssistant = false))
        Log.d(TAG, "[UI] +System entry: ${text.take(100)}")
        updateConversationView()
    }

    private fun addErrorEntry(text: String) {
        conversation.add(ConversationEntry("Error", listOf(text), isAssistant = false))
        Log.d(TAG, "[UI] +Error entry: ${text.take(100)}")
        updateConversationView()
    }

    private fun updateConversationView() {
        Log.d(TAG, "[UI] Submitting new list to adapter. Size: ${conversation.size}")
        conversationAdapter.submitList(conversation.toList()) {
            if (conversation.isNotEmpty()) {
                binding.conversationRecyclerView.post {
                    binding.conversationRecyclerView.smoothScrollToPosition(conversation.size - 1)
                }
            }
        }
    }

    private fun updateButtonStates() {
        binding.stopButton.isEnabled = isSpeaking || isListening || engine.isActive()
        binding.newRecordingButton.isEnabled = isSpeaking
        Log.d(TAG, "[UI] Buttons updated: stop=${binding.stopButton.isEnabled} newRec=${binding.newRecordingButton.isEnabled}")
    }

    private fun setLoading(isLoading: Boolean) {
        val enableInputs = !isLoading && !isListening
        Log.d(TAG, "[UI] setLoading=$isLoading enableInputs=$enableInputs")
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.speakButton.isEnabled = enableInputs
        binding.radioGeminiFlash.isEnabled = !isLoading
        binding.radioGeminiPro.isEnabled = !isLoading
        binding.radioGpt5.isEnabled = !isLoading
        binding.radioGpt5Mini.isEnabled = !isLoading
        binding.geminiApiKeyInput.isEnabled = enableInputs
        binding.openaiApiKeyInput.isEnabled = enableInputs
        // Keep these ALWAYS enabled so user changes apply live
        binding.maxSentencesInput.isEnabled = true
        binding.systemPromptInput.isEnabled = true
        binding.systemPromptExtensionInput.isEnabled = true
        updateButtonStates()
    }

    private fun loadSavedKeys() {
        val g = prefs.getString("gemini_key", "") ?: ""
        val o = prefs.getString("openai_key", "") ?: ""
        binding.geminiApiKeyInput.setText(g)
        binding.openaiApiKeyInput.setText(o)
        Log.d(TAG, "[KEY] Loaded saved keys: gemini=${g.isNotEmpty()} openai=${o.isNotEmpty()}")
    }

    private fun setupKeyPersistence() {
        binding.geminiApiKeyInput.doAfterTextChanged { editable ->
            val v = editable?.toString()?.trim()
            prefs.edit().putString("gemini_key", v).apply()
            Log.d(TAG, "[KEY] Gemini key updated len=${v?.length ?: 0}")
        }
        binding.openaiApiKeyInput.doAfterTextChanged { editable ->
            val v = editable?.toString()?.trim()
            prefs.edit().putString("openai_key", v).apply()
            Log.d(TAG, "[KEY] OpenAI key updated len=${v?.length ?: 0}")
        }
    }

    private fun getGeminiApiKey(): String = binding.geminiApiKeyInput.text?.toString()?.trim().orEmpty()
    private fun getOpenAiApiKey(): String = binding.openaiApiKeyInput.text?.toString()?.trim().orEmpty()

    // Build the assistant draft from the current entry: sentences + any streaming remainder
    private fun buildAssistantDraft(): String? {
        val idx = currentAssistantEntryIndex ?: return null
        val entry = conversation.getOrNull(idx) ?: return null
        val base = entry.sentences.joinToString(" ").trim()
        val inStream = entry.streamingText
        val draft = if (!inStream.isNullOrBlank() && inStream.length > base.length) {
            inStream // streamingText already includes base + remainder
        } else {
            base
        }
        return draft.ifBlank { null }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            if (this::textToSpeech.isInitialized) {
                Log.i(TAG, "[LIFECYCLE] Shutting down TTS")
                textToSpeech.stop()
                textToSpeech.shutdown()
            }
            if (this::speechRecognizer.isInitialized) {
                Log.i(TAG, "[LIFECYCLE] Destroying SpeechRecognizer")
                speechRecognizer.destroy()
            }
        } catch (_: Exception) {}
        _binding = null
    }
}