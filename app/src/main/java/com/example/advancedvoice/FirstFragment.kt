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
import android.view.*
import android.widget.RadioButton
import android.widget.ScrollView
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
import kotlin.math.max
import kotlin.math.min

class FirstFragment : Fragment(), TextToSpeech.OnInitListener {

    private val TAG = "FirstFragment"

    data class ConversationEntry(
        val speaker: String,
        val sentences: MutableList<String>,
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

    private var renderPending = false
    private fun requestRender() {
        if (renderPending) return
        renderPending = true
        binding.conversationLog.postDelayed({
            renderPending = false
            renderConversation()
        }, 40)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textToSpeech = TextToSpeech(requireContext(), this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "[TTS] onStart speaking utteranceId: $utteranceId")
                currentUtteranceId = utteranceId
                isSpeaking = true
                requireActivity().runOnUiThread {
                    binding.newRecordingButton.visibility = View.VISIBLE
                    renderConversation()
                    updateButtonStates()
                }
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "[TTS] onDone speaking utteranceId: $utteranceId")
                requireActivity().runOnUiThread {
                    if (ttsQueue.isNotEmpty()) ttsQueue.removeFirst()
                    isSpeaking = false
                    currentSpeaking = null
                    renderConversation()  // *** Render when current sentence finishes ***
                    if (ttsQueue.isNotEmpty()) {
                        speakNext()
                    } else {
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
            onStreamDelta = { delta ->
                val idx = currentAssistantEntryIndex ?: addAssistantEntry(emptyList()).also { currentAssistantEntryIndex = it }
                conversation[idx].streamingText = (conversation[idx].streamingText ?: "") + delta
                requestRender()
            },
            onStreamSentence = { sentence ->
                Log.i(TAG, "[UI-CALLBACK] onStreamSentence received: '${sentence.take(50)}...'")
                val idx = currentAssistantEntryIndex ?: addAssistantEntry(emptyList()).also { currentAssistantEntryIndex = it }
                val normalized = sentence.trim()  // *** Normalize before comparing ***
                if (conversation[idx].sentences.none { it.trim() == normalized }) {
                    val pos = conversation[idx].sentences.size
                    conversation[idx].sentences.add(normalized)  // *** Store trimmed version ***
                    ttsQueue.addLast(SpokenSentence(normalized, idx, pos))
                    if (!isSpeaking) speakNext()
                    requestRender()
                }
            },
            onFirstSentence = { sentence ->
                Log.i(TAG, "[UI-CALLBACK] onFirstSentence received. Queueing for TTS: '${sentence.take(50)}...'")
                val idx = currentAssistantEntryIndex ?: addAssistantEntry(emptyList()).also {
                    currentAssistantEntryIndex = it
                    Log.d(TAG, "[UI-CALLBACK] New assistant entry created with index $it")
                }
                if (conversation[idx].sentences.isEmpty()) {
                    conversation[idx].sentences.add(sentence)
                    ttsQueue.addLast(SpokenSentence(sentence, idx, 0))
                    if (!isSpeaking) speakNext()
                    renderConversation()
                }
            },
            onRemainingSentences = { sentences ->
                if (sentences.isEmpty()) return@SentenceTurnEngine
                Log.i(TAG, "[UI-CALLBACK] onRemainingSentences received. Queueing ${sentences.size} sentences.")
                val idx = currentAssistantEntryIndex
                    ?: conversation.indexOfLast { it.isAssistant }.takeIf { it >= 0 }
                    ?: addAssistantEntry(emptyList())
                sentences.forEach { s ->
                    if (conversation[idx].sentences.none { it.trim() == s.trim() }) {
                        val pos = conversation[idx].sentences.size
                        conversation[idx].sentences.add(s)
                        ttsQueue.addLast(SpokenSentence(s, idx, pos))
                    }
                }
                conversation[idx].streamingText = null
                renderConversation()
                if (!isSpeaking && ttsQueue.isNotEmpty()) speakNext()
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

    private fun setupUI() {
        binding.settingsHeader.setOnClickListener {
            val visible = binding.settingsContainer.visibility == View.VISIBLE
            binding.settingsContainer.visibility = if (visible) View.GONE else View.VISIBLE
            binding.settingsHeader.text = getString(if (visible) R.string.settings_show else R.string.settings_hide)
            userToggledSettings = true
        }
        binding.clearButton.setOnClickListener {
            stopConversation()
            conversation.clear()
            renderConversation()
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
        // UPDATED: use latest aliases
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

        // NEW: preselect Pro by default (overrides XML default)
        selectRadio(binding.radioGeminiPro)
        currentModelName = "gemini-2.5-pro"
        addSystemEntry("Model switched to $currentModelName (default)")

        // Wire listeners after we set the default selection
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
            binding.settingsContainer.visibility = View.VISIBLE
            binding.settingsHeader.text = getString(R.string.settings_hide)
        }
        ttsQueue.clear()
        currentSpeaking = null
        // FIX: Reset the index on stop to prevent appending to old entries
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
        try { textToSpeech.stop() } catch (_: Exception) {}
        isSpeaking = false
        binding.newRecordingButton.visibility = View.GONE
        ttsQueue.clear()
        currentSpeaking = null
        renderConversation()
    }

    private fun callModelApi(userInput: String) {
        Log.i(TAG, "[TURN] Calling model API with user input: '${userInput.take(50)}...'")
        // FIX: Explicitly reset the index at the start of every new turn.
        currentAssistantEntryIndex = null
        Log.d(TAG, "[TURN] Reset currentAssistantEntryIndex to null.")
        setLoading(true)
        binding.speakButton.text = getString(R.string.status_thinking)
        if (!ensureKeyAvailableForModelOrShow(currentModelName)) {
            setLoading(false)
            binding.speakButton.text = getString(R.string.status_ready)
            return
        }
        engine.setMaxSentences(readMaxSentences())
        ttsQueue.clear()
        currentSpeaking = null
        pendingAutoListen = false
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

    private fun speakNext() {
        if (ttsQueue.isEmpty()) {
            isSpeaking = false
            currentSpeaking = null
            renderConversation()
            return
        }
        val next = ttsQueue.first()
        Log.i(TAG, "[TTS] Speaking now: '${next.text.take(50)}...'")
        currentSpeaking = next
        renderConversation()  // *** CRITICAL: Render immediately when currentSpeaking changes ***
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        textToSpeech.speak(next.text, TextToSpeech.QUEUE_ADD, null, id)
        isSpeaking = true
    }

    private fun addUserEntry(text: String) {
        conversation.add(ConversationEntry("You", mutableListOf(text), isAssistant = false))
        renderConversation()
    }

    private fun addSystemEntry(text: String) {
        conversation.add(ConversationEntry("System", mutableListOf(text), isAssistant = false))
        renderConversation()
    }

    private fun addErrorEntry(text: String) {
        conversation.add(ConversationEntry("Error", mutableListOf(text), isAssistant = false))
        renderConversation()
    }

    private fun addAssistantEntry(sentences: List<String>): Int {
        val entry = ConversationEntry("Assistant", sentences.toMutableList(), isAssistant = true, streamingText = null)
        conversation.add(entry)
        val idx = conversation.lastIndex
        renderConversation()
        return idx
    }

    private fun isAtBottom(scroll: ScrollView, thresholdPx: Int = 48): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        val diff = child.measuredHeight - (scroll.scrollY + scroll.height)
        return diff <= thresholdPx
    }

    private fun renderConversation() {
        val scroll = binding.contentScroll
        val wasAtBottom = isAtBottom(scroll)
        val prevY = scroll.scrollY
        val colorLabelYou = "#1E88E5"
        val colorLabelAssistant = "#43A047"
        val colorLabelSystem = "#8E24AA"
        val colorLabelError = "#E53935"
        val colorSpeaking = "#D32F2F"
        val colorContent = "#FFFFFF"
        fun escapeHtml(s: String): String = s.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")

        val sb = StringBuilder()
        conversation.forEachIndexed { entryIdx, entry ->
            val labelColor = when (entry.speaker.lowercase(Locale.US)) {
                "you" -> colorLabelYou
                "assistant" -> colorLabelAssistant
                "system" -> colorLabelSystem
                "error" -> colorLabelError
                else -> "#CCCCCC"
            }
            sb.append("<b><font color='$labelColor'>${escapeHtml(entry.speaker)}</font>:</b> ")

            if (entry.isAssistant) {
                val speaking = currentSpeaking?.takeIf { it.entryIndex == entryIdx }
                var lastSentenceEndIndexInStream = 0

                // 1. ALWAYS render the list of complete sentences using the robust index check.
                entry.sentences.forEachIndexed { sentenceIndex, sentence ->
                    val isSpeaking = (speaking?.sentenceIndex == sentenceIndex)
                    val color = if (isSpeaking) colorSpeaking else colorContent
                    sb.append("<font color='$color'>${escapeHtml(sentence)}</font>")
                    sb.append(" ") // Add space between sentences
                }

                // 2. If still streaming, find the text that came *after* the last complete sentence and append it.
                val inStream = entry.streamingText
                if (inStream != null) {
                    val completeText = entry.sentences.joinToString(" ")
                    // Find where the complete text ends in the stream buffer to avoid duplication.
                    // This is an approximation that works well for streaming additions.
                    lastSentenceEndIndexInStream = completeText.length

                    if (inStream.length > lastSentenceEndIndexInStream) {
                        val remainder = inStream.substring(lastSentenceEndIndexInStream)
                        sb.append("<font color='$colorContent'>${escapeHtml(remainder)}</font>")
                    }
                }
            } else {
                // User, System, or Error entry
                val text = entry.sentences.firstOrNull().orEmpty()
                sb.append("<font color='$colorContent'>${escapeHtml(text)}</font>")
            }
            sb.append("<br/><br/>")
        }

        val updated: Spanned = Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT)
        binding.conversationLog.text = updated
        binding.conversationLog.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    binding.conversationLog.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val child = scroll.getChildAt(0)
                    val maxY = max(0, (child?.measuredHeight ?: 0) - scroll.height)
                    if (wasAtBottom) {
                        scroll.scrollTo(0, maxY)
                    } else {
                        scroll.scrollTo(0, min(prevY, maxY))
                    }
                }
            }
        )
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