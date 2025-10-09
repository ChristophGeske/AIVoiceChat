package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.*
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class FirstFragment : Fragment() {

    private val TAG = "FirstFragment"

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConversationViewModel by viewModels()

    private var autoContinue = true
    private var pendingStartAfterPermission = false

    private var currentModelName = "gemini-2.5-pro"
    private var userToggledSettings = false

    private val PREFS = "ai_prefs"
    private val PREF_SYSTEM_PROMPT_KEY = "system_prompt"
    private val PREF_MAX_SENTENCES_KEY = "max_sentences"
    private val PREF_FASTER_FIRST = "faster_first"
    private val PREF_LISTEN_SECONDS_KEY = "listen_seconds"
    private val PREF_SELECTED_MODEL = "selected_model"
    private val DEFAULT_SYSTEM_PROMPT = """
        You are a helpful assistant. Answer the user's request in clear, complete sentences.
        Avoid code blocks and JSON unless explicitly requested by the user.
    """.trimIndent()

    private val prefs by lazy { requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.i(TAG, "[PERM] RECORD_AUDIO granted=$isGranted pending=$pendingStartAfterPermission")
            if (isGranted && pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                handleStartListeningRequest()
            } else if (!isGranted) {
                Toast.makeText(context, getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    // A short grace period to prevent STT from starting while TTS audio might still be finalizing.
    private val STT_GRACE_MS_AFTER_TTS = 500L

    // Listen window tracking for post-TTS listening duration
    private var listenWindowDeadlineMs: Long = 0L
    private var listenRestartCount: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.i(TAG, "[LIFECYCLE] onViewCreated (savedInstanceState=${savedInstanceState != null})")

        setupRecyclerView()
        setupUI()
        setupPrefsBackedInputs()
        observeViewModel()

        var restoredModel = prefs.getString(PREF_SELECTED_MODEL, "gemini-2.5-pro") ?: "gemini-2.5-pro"
        // Migrate legacy OpenAI names to GPT‑5 (keep gpt‑5‑mini as-is)
        restoredModel = migrateLegacyOpenAiModelName(restoredModel)
        if (restoredModel != prefs.getString(PREF_SELECTED_MODEL, restoredModel)) {
            prefs.edit().putString(PREF_SELECTED_MODEL, restoredModel).apply()
        }

        currentModelName = restoredModel
        viewModel.setCurrentModel(restoredModel)

        // Initial radio selection (may be hidden later if key missing)
        when (restoredModel.lowercase()) {
            "gemini-2.5-flash" -> binding.radioGeminiFlash.isChecked = true
            "gemini-2.5-pro" -> binding.radioGeminiPro.isChecked = true
            "gpt-5" -> {
                val effort = prefs.getString("gpt5_effort", "low")?.lowercase() ?: "low"
                when (effort) {
                    "high" -> binding.radioGpt5High.isChecked = true
                    "medium" -> binding.radioGpt5Medium.isChecked = true
                    else -> binding.radioGpt5Low.isChecked = true
                }
            }
            "gpt-5-mini" -> {
                val effort = prefs.getString("gpt5_effort", "medium")?.lowercase() ?: "medium"
                when (effort) {
                    "high" -> binding.radioGpt5MiniHigh.isChecked = true
                    else -> binding.radioGpt5MiniMedium.isChecked = true
                }
            }
            else -> { /* we'll sync after visibility update */ }
        }

        // Ensure visibility matches available keys and selection remains valid
        updateModelOptionsVisibility()
        // Ensure radios reflect current model after visibility changes
        binding.root.post {
            syncRadioSelectionToCurrentModel()
            ensureRadioSelectedIfVisible()
        }

        Log.i(TAG, "[MODEL] Restored selection: $currentModelName")
        Log.i(TAG, "[STATE] Conversation entries: ${viewModel.conversation.value?.size ?: 0}")
        Log.i(TAG, "[STATE] Is speaking: ${viewModel.isSpeaking.value}")
        Log.i(TAG, "[STATE] Engine active: ${viewModel.isEngineActive()}")
    }

    private fun setupRecyclerView() {
        val adapter = ConversationAdapter { entryIndex ->
            viewModel.replayEntry(entryIndex)
        }
        binding.conversationRecyclerView.adapter = adapter

        viewModel.conversation.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list) {
                if (list.isNotEmpty()) {
                    binding.conversationRecyclerView.post {
                        binding.conversationRecyclerView.smoothScrollToPosition(list.size - 1)
                    }
                }
            }
        }
    }

    private fun setupPrefsBackedInputs() {
        // Use string resources for hints
        binding.geminiApiKeyInput.hint = getString(R.string.gemini_api_key_hint)
        binding.openaiApiKeyInput.hint = getString(R.string.openai_api_key_hint)

        binding.geminiApiKeyInput.setText(prefs.getString("gemini_key", "") ?: "")
        binding.openaiApiKeyInput.setText(prefs.getString("openai_key", "") ?: "")

        // Prepare Gemini help link (clickable HTML)
        setupGeminiKeyHelpLink()
        refreshGeminiKeyHelpLinkVisibility()

        binding.geminiApiKeyInput.doAfterTextChanged { editable ->
            prefs.edit().putString("gemini_key", editable?.toString()?.trim()).apply()
            updateModelOptionsVisibility()
            refreshGeminiKeyHelpLinkVisibility()
            // Post to ensure visibility/layout settled before checking
            binding.root.post {
                syncRadioSelectionToCurrentModel()
                ensureRadioSelectedIfVisible()
            }
        }
        binding.openaiApiKeyInput.doAfterTextChanged { editable ->
            prefs.edit().putString("openai_key", editable?.toString()?.trim()).apply()
            // If user just added OpenAI key and an old model string is stored, migrate it now
            val migrated = migrateLegacyOpenAiModelName(currentModelName)
            if (migrated != currentModelName) {
                currentModelName = migrated
                viewModel.setCurrentModel(migrated)
                prefs.edit().putString(PREF_SELECTED_MODEL, migrated).apply()
                Log.i(TAG, "[MODEL] Migrated selection to $migrated after OpenAI key entry")
            }
            updateModelOptionsVisibility()
            binding.root.post {
                syncRadioSelectionToCurrentModel()
                ensureRadioSelectedIfVisible()
            }
        }

        // Only one system prompt (extension removed). The effective prompt is dynamic based on settings.
        val savedPrompt = prefs.getString(PREF_SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT)
        binding.systemPromptInput.setText(savedPrompt)
        binding.systemPromptInput.doAfterTextChanged { editable ->
            prefs.edit().putString(PREF_SYSTEM_PROMPT_KEY, editable?.toString().orEmpty()).apply()
        }

        // Hide the legacy "system prompt extension" field completely
        binding.systemPromptExtensionInput.visibility = View.GONE

        val savedMax = prefs.getInt(PREF_MAX_SENTENCES_KEY, 4).coerceIn(1, 10)
        binding.maxSentencesInput.setText(savedMax.toString())
        binding.maxSentencesInput.doAfterTextChanged { editable ->
            val n = editable?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 10)
            if (n != null) {
                prefs.edit().putInt(PREF_MAX_SENTENCES_KEY, n).apply()
                viewModel.applyEngineConfigFromPrefs(prefs)
            }
        }

        val faster = prefs.getBoolean(PREF_FASTER_FIRST, true)
        binding.switchFasterFirst.isChecked = faster
        binding.switchFasterFirst.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_FASTER_FIRST, isChecked).apply()
            viewModel.applyEngineConfigFromPrefs(prefs)
            // Effective prompt is recomputed on turn via ViewModel provider.
        }

        val savedListen = prefs.getInt(PREF_LISTEN_SECONDS_KEY, 5).coerceIn(1, 120)
        binding.listenSecondsInput?.setText(savedListen.toString())
        binding.listenSecondsInput?.doAfterTextChanged { editable ->
            val sec = editable?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 120)
            if (sec != null) {
                prefs.edit().putInt(PREF_LISTEN_SECONDS_KEY, sec).apply()
            }
        }

        viewModel.applyEngineConfigFromPrefs(prefs)
    }

    private fun observeViewModel() {
        viewModel.currentSpeaking.observe(viewLifecycleOwner) { speaking ->
            val adapter = binding.conversationRecyclerView.adapter as ConversationAdapter
            val prev = adapter.currentSpeaking?.entryIndex
            adapter.currentSpeaking = speaking
            if (prev != null) adapter.notifyItemChanged(prev)
            val now = speaking?.entryIndex
            if (now != null) adapter.notifyItemChanged(now)

            binding.newRecordingButton.visibility = if (speaking != null) View.VISIBLE else View.GONE
            updateButtonStates()
        }

        viewModel.turnFinishedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "[LIFECYCLE] Turn finished event received. Unlocking UI.")
                setLoading(false)
            }
        }

        viewModel.ttsQueueFinishedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "[AutoContinue] TTS Queue finished event received. autoContinue=$autoContinue")
                if (autoContinue) {
                    startListenWindow("TTS")
                    handleStartListeningRequest(delayMs = STT_GRACE_MS_AFTER_TTS)
                }
            }
        }

        // Restart STT quickly if NO_MATCH occurs within the listen window
        viewModel.sttNoMatch.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                val remain = remainingListenWindowMs()
                if (autoContinue && isListenWindowActive()) {
                    listenRestartCount += 1
                    Log.w(TAG, "[ListenWindow] NO_MATCH within window. Restarting STT. restartCount=$listenRestartCount remaining=${remain}ms")
                    handleStartListeningRequest(delayMs = 50L)
                } else {
                    Log.i(TAG, "[ListenWindow] NO_MATCH but window expired or autoContinue=false. remaining=${remain}ms autoContinue=$autoContinue")
                }
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("LLM error")
                    .setMessage(msg.take(700))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        viewModel.sttIsListening.observe(viewLifecycleOwner) { isListening ->
            binding.speakButton.text = if (isListening) {
                getString(R.string.status_listening)
            } else {
                getString(R.string.status_ready)
            }
            if (isListening) {
                maybeAutoCollapseSettings()
            }
            setLoading(false)
            updateButtonStates()
        }
    }

    private fun setupUI() {
        binding.settingsHeader.setOnClickListener {
            val visible = binding.settingsContainerScrollView.visibility == View.VISIBLE
            binding.settingsContainerScrollView.visibility = if (visible) View.GONE else View.VISIBLE
            binding.settingsHeader.text = getString(if (visible) R.string.settings_show else R.string.settings_hide)
            userToggledSettings = true
        }
        binding.clearButton.setOnClickListener {
            viewModel.clearConversation()
        }
        binding.speakButton.setOnClickListener {
            autoContinue = true
            startListenWindow("Manual Speak")
            handleStartListeningRequest(delayMs = 0L)
        }
        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            startListenWindow("New Recording")
            viewModel.stopTts()
            view?.postDelayed({ handleStartListeningRequest(delayMs = 0L) }, 250)
        }
        binding.stopButton.setOnClickListener {
            autoContinue = false
            listenWindowDeadlineMs = 0L
            listenRestartCount = 0
            viewModel.stopAll()
            setLoading(false)
        }

        // Map radios to models/effort
        val radios = listOf(
            binding.radioGeminiFlash to ("gemini-2.5-flash" to null),
            binding.radioGeminiPro to ("gemini-2.5-pro" to null),
            binding.radioGpt5High to ("gpt-5" to "high"),
            binding.radioGpt5Medium to ("gpt-5" to "medium"),
            binding.radioGpt5Low to ("gpt-5" to "low"),
            binding.radioGpt5MiniHigh to ("gpt-5-mini" to "high"),
            binding.radioGpt5MiniMedium to ("gpt-5-mini" to "medium")
        )
        val selectRadio: (RadioButton) -> Unit = { selected ->
            val all = listOf(
                binding.radioGeminiFlash, binding.radioGeminiPro,
                binding.radioGpt5High, binding.radioGpt5Medium, binding.radioGpt5Low,
                binding.radioGpt5MiniHigh, binding.radioGpt5MiniMedium
            )
            all.forEach { rb -> rb.isChecked = (rb === selected) }
        }

        for ((rb, modelEffort) in radios) {
            rb.setOnClickListener {
                val (model, effort) = modelEffort
                val currentEffort = prefs.getString("gpt5_effort", "low")
                if (model == currentModelName && (effort == null || effort == currentEffort)) {
                    return@setOnClickListener
                }
                if (viewModel.isEngineActive()) viewModel.abortEngine()
                selectRadio(rb)
                currentModelName = model
                viewModel.setCurrentModel(model)
                prefs.edit().putString(PREF_SELECTED_MODEL, currentModelName).apply()
                effort?.let {
                    prefs.edit().putString("gpt5_effort", it).apply()
                }
                val userFacingModelName = rb.text.toString()
                Toast.makeText(context, "Model: $userFacingModelName", Toast.LENGTH_SHORT).show()
                viewModel.addSystemEntry("Model switched to $userFacingModelName")
            }
        }
    }

    private fun handleStartListeningRequest(delayMs: Long = STT_GRACE_MS_AFTER_TTS) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val remain = remainingListenWindowMs()
        Log.d(TAG, "[AutoContinue] Handling start listening request. Delay=${delayMs}ms, listenWindowRemaining=${remain}ms, autoContinue=$autoContinue")
        view?.postDelayed({ startListeningNow() }, delayMs)
    }

    private fun startListeningNow() {
        if (viewModel.sttIsListening.value == true || viewModel.isEngineActive() || viewModel.isSpeaking.value == true) {
            Log.w(TAG, "[AutoContinue] Skipping startListeningNow: isListening=${viewModel.sttIsListening.value}, isEngineActive=${viewModel.isEngineActive()}, isSpeaking=${viewModel.isSpeaking.value}")
            return
        }
        if (!ensureKeyAvailableForModelOrShow(currentModelName)) return

        val remain = remainingListenWindowMs()
        Log.i(TAG, "[AutoContinue] Starting listening. listenWindowRemaining=${remain}ms (deadline=$listenWindowDeadlineMs)")
        viewModel.startListening()
    }

    private fun ensureKeyAvailableForModelOrShow(modelName: String): Boolean {
        val isGpt = modelName.startsWith("gpt-", ignoreCase = true)
        val key = if (isGpt) prefs.getString("openai_key", "").orEmpty() else prefs.getString("gemini_key", "").orEmpty()
        if (key.isNotBlank()) return true
        val provider = if (isGpt) "OpenAI" else "Gemini"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("API key required")
            .setMessage("Please enter your $provider API key to use $modelName.")
            .setPositiveButton("OK", null)
            .show()
        return false
    }

    private fun maybeAutoCollapseSettings() {
        if (userToggledSettings) return
        if ((viewModel.conversation.value?.size ?: 0) > 2) {
            binding.settingsContainerScrollView.visibility = View.GONE
            binding.settingsHeader.text = getString(R.string.settings_show)
        }
    }

    private fun updateModelOptionsVisibility() {
        val geminiKey = prefs.getString("gemini_key", "").orEmpty().isNotBlank()
        val openaiKey = prefs.getString("openai_key", "").orEmpty().isNotBlank()

        // Show Gemini radios only if Gemini key present
        binding.radioGeminiFlash.visibility = if (geminiKey) View.VISIBLE else View.GONE
        binding.radioGeminiPro.visibility = if (geminiKey) View.VISIBLE else View.GONE

        // Show GPT‑5 radios only if OpenAI key present
        val gptVisibility = if (openaiKey) View.VISIBLE else View.GONE
        binding.radioGpt5High.visibility = gptVisibility
        binding.radioGpt5Medium.visibility = gptVisibility
        binding.radioGpt5Low.visibility = gptVisibility
        binding.radioGpt5MiniHigh.visibility = gptVisibility
        binding.radioGpt5MiniMedium.visibility = gptVisibility

        // Ensure current selection is valid given available providers
        val currentIsOpenAI = currentModelName.startsWith("gpt-", ignoreCase = true)
        val currentIsGemini = currentModelName.startsWith("gemini", ignoreCase = true)

        // Migrate legacy names if needed
        if (currentIsOpenAI) {
            val migrated = migrateLegacyOpenAiModelName(currentModelName)
            if (migrated != currentModelName) {
                Log.i(TAG, "[MODEL] Migrated selection to $migrated")
                currentModelName = migrated
                viewModel.setCurrentModel(migrated)
                prefs.edit().putString(PREF_SELECTED_MODEL, migrated).apply()
            }
        }

        val selectionInvalid = (currentIsOpenAI && !openaiKey) || (currentIsGemini && !geminiKey)

        if (selectionInvalid) {
            val newModel = when {
                geminiKey -> "gemini-2.5-pro"
                openaiKey -> "gpt-5"
                else -> null
            }
            if (newModel != null) {
                Log.i(TAG, "[MODEL] Adjusting selection to $newModel due to key availability")
                currentModelName = newModel
                viewModel.setCurrentModel(newModel)
                prefs.edit().putString(PREF_SELECTED_MODEL, currentModelName).apply()
            } else {
                Log.w(TAG, "[MODEL] No API keys available. Hiding all model options.")
                binding.radioGeminiFlash.isChecked = false
                binding.radioGeminiPro.isChecked = false
                binding.radioGpt5High.isChecked = false
                binding.radioGpt5Medium.isChecked = false
                binding.radioGpt5Low.isChecked = false
                binding.radioGpt5MiniHigh.isChecked = false
                binding.radioGpt5MiniMedium.isChecked = false
            }
        }

        // Always sync radio buttons with the currently active model
        syncRadioSelectionToCurrentModel()
        // If none is checked but radios are visible and the current model is available, ensure it's checked
        ensureRadioSelectedIfVisible()

        // Update the Gemini help link visibility when model options may change
        refreshGeminiKeyHelpLinkVisibility()

        updateButtonStates()
    }

    private fun syncRadioSelectionToCurrentModel() {
        val model = currentModelName.lowercase()
        val geminiKeyPresent = prefs.getString("gemini_key", "").orEmpty().isNotBlank()
        val openaiKeyPresent = prefs.getString("openai_key", "").orEmpty().isNotBlank()

        binding.radioGeminiFlash.isChecked = false
        binding.radioGeminiPro.isChecked = false
        binding.radioGpt5High.isChecked = false
        binding.radioGpt5Medium.isChecked = false
        binding.radioGpt5Low.isChecked = false
        binding.radioGpt5MiniHigh.isChecked = false
        binding.radioGpt5MiniMedium.isChecked = false

        when {
            model.startsWith("gemini") && geminiKeyPresent -> {
                if (model.startsWith("gemini-2.5-flash")) {
                    if (binding.radioGeminiFlash.visibility == View.VISIBLE) binding.radioGeminiFlash.isChecked = true
                } else {
                    if (binding.radioGeminiPro.visibility == View.VISIBLE) binding.radioGeminiPro.isChecked = true
                }
            }
            model == "gpt-5" && openaiKeyPresent -> {
                val effort = prefs.getString("gpt5_effort", "low")?.lowercase() ?: "low"
                when (effort) {
                    "high" -> if (binding.radioGpt5High.visibility == View.VISIBLE) binding.radioGpt5High.isChecked = true
                    "medium" -> if (binding.radioGpt5Medium.visibility == View.VISIBLE) binding.radioGpt5Medium.isChecked = true
                    else -> if (binding.radioGpt5Low.visibility == View.VISIBLE) binding.radioGpt5Low.isChecked = true
                }
            }
            model == "gpt-5-mini" && openaiKeyPresent -> {
                val effort = prefs.getString("gpt5_effort", "medium")?.lowercase() ?: "medium"
                when (effort) {
                    "high" -> if (binding.radioGpt5MiniHigh.visibility == View.VISIBLE) binding.radioGpt5MiniHigh.isChecked = true
                    else -> if (binding.radioGpt5MiniMedium.visibility == View.VISIBLE) binding.radioGpt5MiniMedium.isChecked = true
                }
            }
            else -> Log.d(TAG, "[MODEL] No matching radio to sync (model=$model)")
        }
    }

    private fun ensureRadioSelectedIfVisible() {
        val anyChecked = listOf(
            binding.radioGeminiFlash,
            binding.radioGeminiPro,
            binding.radioGpt5High,
            binding.radioGpt5Medium,
            binding.radioGpt5Low,
            binding.radioGpt5MiniHigh,
            binding.radioGpt5MiniMedium
        ).any { it.visibility == View.VISIBLE && it.isChecked }

        if (anyChecked) return

        when {
            currentModelName.equals("gemini-2.5-flash", true)
                    && binding.radioGeminiFlash.visibility == View.VISIBLE -> binding.radioGeminiFlash.isChecked = true
            currentModelName.startsWith("gemini", true)
                    && binding.radioGeminiPro.visibility == View.VISIBLE -> binding.radioGeminiPro.isChecked = true
            currentModelName.equals("gpt-5", true) -> {
                val effort = prefs.getString("gpt5_effort", "low")?.lowercase() ?: "low"
                when (effort) {
                    "high" -> if (binding.radioGpt5High.visibility == View.VISIBLE) binding.radioGpt5High.isChecked = true
                    "medium" -> if (binding.radioGpt5Medium.visibility == View.VISIBLE) binding.radioGpt5Medium.isChecked = true
                    else -> if (binding.radioGpt5Low.visibility == View.VISIBLE) binding.radioGpt5Low.isChecked = true
                }
            }
            currentModelName.equals("gpt-5-mini", true) -> {
                val effort = prefs.getString("gpt5_effort", "medium")?.lowercase() ?: "medium"
                when (effort) {
                    "high" -> if (binding.radioGpt5MiniHigh.visibility == View.VISIBLE) binding.radioGpt5MiniHigh.isChecked = true
                    else -> if (binding.radioGpt5MiniMedium.visibility == View.VISIBLE) binding.radioGpt5MiniMedium.isChecked = true
                }
            }
        }
    }

    private fun updateButtonStates() {
        val engineActive = viewModel.isEngineActive()
        val isSpeaking = viewModel.isSpeaking.value == true
        val isListening = viewModel.sttIsListening.value == true
        binding.stopButton.isEnabled = isSpeaking || isListening || engineActive
        binding.newRecordingButton.isEnabled = isSpeaking
        binding.speakButton.isEnabled = !isListening && !engineActive && !isSpeaking
        binding.clearButton.isEnabled = !isListening && !engineActive
    }

    private fun setLoading(isLoading: Boolean) {
        val engineActive = isLoading || viewModel.isEngineActive()
        val isListening = viewModel.sttIsListening.value == true
        binding.progressBar.visibility = if (engineActive && !isListening) View.VISIBLE else View.GONE

        binding.radioGeminiFlash.isEnabled = !engineActive
        binding.radioGeminiPro.isEnabled = !engineActive
        binding.radioGpt5High.isEnabled = !engineActive
        binding.radioGpt5Medium.isEnabled = !engineActive
        binding.radioGpt5Low.isEnabled = !engineActive
        binding.radioGpt5MiniHigh.isEnabled = !engineActive
        binding.radioGpt5MiniMedium.isEnabled = !engineActive
        binding.geminiApiKeyInput.isEnabled = !engineActive
        binding.openaiApiKeyInput.isEnabled = !engineActive
        updateButtonStates()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "[LIFECYCLE] onResume - checking TTS resume")
        view?.postDelayed({ viewModel.checkAndResumeTts() }, 300)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.i(TAG, "[LIFECYCLE] onDestroyView")
        _binding = null
    }

    // Listen window helpers
    private fun startListenWindow(reason: String) {
        val secs = prefs.getInt(PREF_LISTEN_SECONDS_KEY, 5).coerceIn(1, 120)
        val now = SystemClock.elapsedRealtime()
        listenWindowDeadlineMs = now + secs * 1000L
        listenRestartCount = 0
        Log.i(TAG, "[ListenWindow] Started ($reason): seconds=$secs, deadline=$listenWindowDeadlineMs now=$now")
    }
    private fun remainingListenWindowMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return (listenWindowDeadlineMs - now).coerceAtLeast(0L)
    }
    private fun isListenWindowActive(): Boolean = SystemClock.elapsedRealtime() < listenWindowDeadlineMs

    // Map legacy OpenAI names to GPT‑5 (keep gpt-5-mini)
    private fun migrateLegacyOpenAiModelName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower == "gpt-5-turbo" -> "gpt-5"
            lower.startsWith("gpt-4o") -> "gpt-5"
            else -> name
        }
    }

    // ---- Gemini API Key helper link ----

    private fun setupGeminiKeyHelpLink() {
        val html = "<a href=\"https://aistudio.google.com/api-keys\">Get free Gemini API Key here</a>"
        binding.geminiApiKeyHelpLink.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        binding.geminiApiKeyHelpLink.movementMethod = LinkMovementMethod.getInstance()
        binding.geminiApiKeyHelpLink.linksClickable = true
    }

    private fun refreshGeminiKeyHelpLinkVisibility() {
        val empty = binding.geminiApiKeyInput.text?.toString()?.trim().isNullOrEmpty()
        binding.geminiApiKeyHelpLink.visibility = if (empty) View.VISIBLE else View.GONE
    }
}