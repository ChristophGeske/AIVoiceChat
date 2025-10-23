package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FirstFragment : Fragment() {

    private val TAG = "FirstFragment"
    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConversationViewModel by viewModels()

    private var autoContinue = true
    private var pendingStartAfterPermission = false
    private var currentModelName = "gemini-2.5-pro"
    private val STT_GRACE_MS_AFTER_TTS = 500L
    private var listenWindowDeadlineMs: Long = 0L
    private var listenRestartCount: Int = 0

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.i(TAG, "[${now()}][PERM] RECORD_AUDIO granted=$isGranted")
            if (isGranted && pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                handleStartListeningRequest()
            } else if (!isGranted) {
                Toast.makeText(context, getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        setupPrefsBackedInputs()
        observeViewModel()
        setupSttSystemSelection()
        setupTtsSettingsLink()

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        var restoredModel = prefs.getString("selected_model", "gemini-2.5-pro") ?: "gemini-2.5-pro"
        restoredModel = migrateLegacyOpenAiModelName(restoredModel)
        if (restoredModel != prefs.getString("selected_model", restoredModel)) {
            prefs.edit().putString("selected_model", restoredModel).apply()
        }

        currentModelName = restoredModel
        viewModel.setCurrentModel(restoredModel)

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
        }

        updateModelOptionsVisibility()
        binding.root.post {
            syncRadioSelectionToCurrentModel()
            ensureRadioSelectedIfVisible()
        }
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
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // API Keys
        binding.geminiApiKeyInput.hint = getString(R.string.gemini_api_key_hint)
        binding.openaiApiKeyInput.hint = getString(R.string.openai_api_key_hint)
        binding.geminiApiKeyInput.setText(prefs.getString("gemini_key", "") ?: "")
        binding.openaiApiKeyInput.setText(prefs.getString("openai_key", "") ?: "")
        setupGeminiKeyHelpLink()
        refreshGeminiKeyHelpLinkVisibility()

        binding.geminiApiKeyInput.doAfterTextChanged { editable ->
            prefs.edit().putString("gemini_key", editable?.toString()?.trim()).apply()
            updateModelOptionsVisibility()
            refreshGeminiKeyHelpLinkVisibility()
            binding.root.post {
                syncRadioSelectionToCurrentModel()
                ensureRadioSelectedIfVisible()
            }
        }
        binding.openaiApiKeyInput.doAfterTextChanged { editable ->
            prefs.edit().putString("openai_key", editable?.toString()?.trim()).apply()
            val migrated = migrateLegacyOpenAiModelName(currentModelName)
            if (migrated != currentModelName) {
                currentModelName = migrated
                viewModel.setCurrentModel(migrated)
                prefs.edit().putString("selected_model", migrated).apply()
                Log.i(TAG, "[MODEL] Migrated selection to $migrated")
            }
            updateModelOptionsVisibility()
            binding.root.post {
                syncRadioSelectionToCurrentModel()
                ensureRadioSelectedIfVisible()
            }
        }

        // Base System Prompt
        val savedPrompt = prefs.getString("system_prompt", ConversationViewModel.DEFAULT_SYSTEM_PROMPT)
        binding.systemPromptInput.setText(savedPrompt)
        binding.systemPromptInput.doAfterTextChanged { editable ->
            prefs.edit().putString("system_prompt", editable?.toString().orEmpty()).apply()
        }

        binding.resetSystemPromptButton.setOnClickListener {
            val defaultPrompt = ConversationViewModel.DEFAULT_SYSTEM_PROMPT
            binding.systemPromptInput.setText(defaultPrompt)
            prefs.edit().putString("system_prompt", defaultPrompt).apply()
            Toast.makeText(context, "System prompt reset to default", Toast.LENGTH_SHORT).show()
        }

        // System Prompt Extensions (editable, but with smart defaults)
        val defaultExtensions = buildDefaultPromptExtensions(prefs)
        val savedExtensions = prefs.getString("system_prompt_extensions", defaultExtensions)
        binding.promptExtensionsInput.setText(savedExtensions)

        binding.promptExtensionsInput.doAfterTextChanged { editable ->
            prefs.edit().putString("system_prompt_extensions", editable?.toString().orEmpty()).apply()
        }

        binding.resetPromptExtensionsButton.setOnClickListener {
            val defaults = buildDefaultPromptExtensions(prefs)
            binding.promptExtensionsInput.setText(defaults)
            prefs.edit().putString("system_prompt_extensions", defaults).apply()
            Toast.makeText(context, "Extensions reset to default", Toast.LENGTH_SHORT).show()
        }

        // Phase 1 Prompt (Faster First - First Sentence)
        val phase1Saved = prefs.getString("phase1_prompt", Prompts.getDefaultPhase1Prompt())
        binding.phase1PromptInput.setText(phase1Saved)
        binding.phase1PromptInput.doAfterTextChanged { editable ->
            prefs.edit().putString("phase1_prompt", editable?.toString().orEmpty()).apply()
        }

        binding.resetPhase1PromptButton.setOnClickListener {
            binding.phase1PromptInput.setText(Prompts.getDefaultPhase1Prompt())
            prefs.edit().putString("phase1_prompt", Prompts.getDefaultPhase1Prompt()).apply()
            Toast.makeText(context, "Phase 1 prompt reset to default", Toast.LENGTH_SHORT).show()
        }

        // Phase 2 Prompt (Faster First - Continuation)
        val phase2Saved = prefs.getString("phase2_prompt", Prompts.getDefaultPhase2Prompt())
        binding.phase2PromptInput.setText(phase2Saved)
        binding.phase2PromptInput.doAfterTextChanged { editable ->
            prefs.edit().putString("phase2_prompt", editable?.toString().orEmpty()).apply()
        }

        binding.resetPhase2PromptButton.setOnClickListener {
            binding.phase2PromptInput.setText(Prompts.getDefaultPhase2Prompt())
            prefs.edit().putString("phase2_prompt", Prompts.getDefaultPhase2Prompt()).apply()
            Toast.makeText(context, "Phase 2 prompt reset to default", Toast.LENGTH_SHORT).show()
        }

        // Function to show/hide phase prompts based on Faster First setting
        val updatePhasePromptsVisibility = { fasterFirstEnabled: Boolean ->
            val visibility = if (fasterFirstEnabled) View.VISIBLE else View.GONE
            binding.phase1PromptTitle.visibility = visibility
            binding.phase1PromptDesc.visibility = visibility
            binding.phase1PromptLayout.visibility = visibility
            binding.resetPhase1PromptButton.visibility = visibility
            binding.phase2PromptTitle.visibility = visibility
            binding.phase2PromptDesc.visibility = visibility
            binding.phase2PromptLayout.visibility = visibility
            binding.resetPhase2PromptButton.visibility = visibility
            binding.systemPromptWarning.visibility = if (fasterFirstEnabled) View.VISIBLE else View.GONE
        }

        // Max Sentences
        val savedMax = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        binding.maxSentencesInput.setText(savedMax.toString())
        binding.maxSentencesInput.doAfterTextChanged { editable ->
            val n = editable?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 10)
            if (n != null) {
                prefs.edit().putInt("max_sentences", n).apply()
                viewModel.applyEngineConfigFromPrefs(prefs)
            }
        }

        // Faster First Switch
        val faster = prefs.getBoolean("faster_first", true)
        binding.switchFasterFirst.isChecked = faster

        // Set initial visibility
        updatePhasePromptsVisibility(faster)

        // Update visibility when switch changes
        binding.switchFasterFirst.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("faster_first", isChecked).apply()
            viewModel.applyEngineConfigFromPrefs(prefs)
            updatePhasePromptsVisibility(isChecked)
        }

        // Listen Window Duration
        val savedListen = prefs.getInt("listen_seconds", 5).coerceIn(1, 120)
        binding.listenSecondsInput?.setText(savedListen.toString())
        binding.listenSecondsInput?.doAfterTextChanged { editable ->
            val sec = editable?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 120)
            if (sec != null) {
                prefs.edit().putInt("listen_seconds", sec).apply()
            }
        }

        viewModel.applyEngineConfigFromPrefs(prefs)

        // Force scroll when keyboard-heavy inputs get focus
        listOf(
            binding.promptExtensionsInput,
            binding.phase1PromptInput,
            binding.phase2PromptInput
        ).forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    binding.settingsContainerScrollView.postDelayed({
                        binding.settingsContainerScrollView.smoothScrollTo(0, view.bottom + 200)
                    }, 300)
                }
            }
        }
    }

    private fun buildDefaultPromptExtensions(prefs: android.content.SharedPreferences): String {
        val maxSent = prefs.getInt("max_sentences", 4).coerceIn(1, 10)
        val faster = prefs.getBoolean("faster_first", true)

        return buildList {
            add("Return plain text only.")
            add("IMPORTANT: Your response MUST be AT MOST $maxSent sentences long, unless the user explicitly asks for a longer/shorter response (e.g., 'in detail', 'explain thoroughly'). Prioritize the user's explicit length requests over this general rule.")
            if (faster) {
                add("Begin with a complete first sentence promptly, then continue.")
            }
        }.joinToString(" ")
    }

    private fun observeViewModel() {
        viewModel.uiControls.observe(viewLifecycleOwner) { state ->
            binding.speakButton.isEnabled = state.speakEnabled
            binding.stopButton.isEnabled = state.stopEnabled
            binding.newRecordingButton.visibility = if (state.newRecordingEnabled) View.VISIBLE else View.GONE
            binding.newRecordingButton.isEnabled = state.newRecordingEnabled
            binding.clearButton.isEnabled = state.clearEnabled

            binding.speakButton.text = state.speakButtonText

            val color = when (state.speakButtonText) {
                "Listening..." -> 0xFFF44336.toInt()
                "Processing & Listening..." -> 0xFFF44336.toInt() // Red for listening while processing
                "Processing..." -> 0xFF42A5F5.toInt()
                "Generating..." -> 0xFF4CAF50.toInt()
                "Assistant Speaking..." -> 0xFFF44336.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            binding.speakButton.setTextColor(color)
        }
    }

    private fun setupUI() {
        binding.clearButton.setOnClickListener {
            viewModel.clearConversation()
        }

        binding.speakButton.setOnClickListener {
            autoContinue = true
            if (!ensureKeyAvailableForModelOrShow(currentModelName)) return@setOnClickListener

            val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            val sttSystem = SttSystem.valueOf(prefs.getString("stt_system", SttSystem.STANDARD.name) ?: SttSystem.STANDARD.name)
            if (sttSystem == SttSystem.STANDARD) {
                startListenWindow("Manual Speak")
            }
            handleStartListeningRequest(delayMs = 0L)
        }

        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            val sttSystem = SttSystem.valueOf(prefs.getString("stt_system", SttSystem.STANDARD.name) ?: SttSystem.STANDARD.name)
            if (sttSystem == SttSystem.STANDARD) {
                startListenWindow("New Recording")
            }
            viewModel.stopTts()
            view?.postDelayed({ handleStartListeningRequest(delayMs = 0L) }, 250)
        }

        binding.stopButton.setOnClickListener {
            autoContinue = false
            listenWindowDeadlineMs = 0L
            listenRestartCount = 0
            viewModel.stopAll()
        }

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
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
                if (model == currentModelName && (effort == null || effort == currentEffort)) return@setOnClickListener
                if (viewModel.isEngineActive()) viewModel.abortEngine()
                selectRadio(rb)
                currentModelName = model
                viewModel.setCurrentModel(model)
                prefs.edit().putString("selected_model", currentModelName).apply()
                effort?.let { prefs.edit().putString("gpt5_effort", it).apply() }
                Toast.makeText(context, "Model: ${rb.text}", Toast.LENGTH_SHORT).show()
                viewModel.addSystemEntry("Model switched to ${rb.text}")
            }
        }
    }

    private fun handleStartListeningRequest(delayMs: Long = STT_GRACE_MS_AFTER_TTS) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        view?.postDelayed({ startListeningNow() }, delayMs)
    }

    private fun startListeningNow() {
        if (viewModel.isSpeaking.value == true) {
            Log.w(TAG, "[${now()}][STT] Blocked - assistant is speaking")
            return
        }
        if (!ensureKeyAvailableForModelOrShow(currentModelName)) return
        Log.i(TAG, "[${now()}][STT] Starting listening NOW")
        viewModel.startListening()
    }

    private fun ensureKeyAvailableForModelOrShow(modelName: String): Boolean {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val isGpt = modelName.startsWith("gpt-", ignoreCase = true)
        val key = if (isGpt) prefs.getString("openai_key", "").orEmpty() else prefs.getString("gemini_key", "").orEmpty()
        if (key.isNotBlank()) return true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("API key required")
            .setMessage("Please open Settings ⚙ and enter your ${if (isGpt) "OpenAI" else "Gemini"} API key to use $modelName.")
            .setPositiveButton("Open Settings") { _, _ -> toggleSettingsVisibility(forceShow = true) }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    private fun updateModelOptionsVisibility() {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val geminiKey = prefs.getString("gemini_key", "").orEmpty().isNotBlank()
        val openaiKey = prefs.getString("openai_key", "").orEmpty().isNotBlank()

        binding.radioGeminiFlash.visibility = if (geminiKey) View.VISIBLE else View.GONE
        binding.radioGeminiPro.visibility = if (geminiKey) View.VISIBLE else View.GONE

        val gptVisibility = if (openaiKey) View.VISIBLE else View.GONE
        binding.radioGpt5High.visibility = gptVisibility
        binding.radioGpt5Medium.visibility = gptVisibility
        binding.radioGpt5Low.visibility = gptVisibility
        binding.radioGpt5MiniHigh.visibility = gptVisibility
        binding.radioGpt5MiniMedium.visibility = gptVisibility
    }

    private fun syncRadioSelectionToCurrentModel() {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
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
        }
    }

    private fun ensureRadioSelectedIfVisible() {
        val anyChecked = listOf(
            binding.radioGeminiFlash, binding.radioGeminiPro,
            binding.radioGpt5High, binding.radioGpt5Medium, binding.radioGpt5Low,
            binding.radioGpt5MiniHigh, binding.radioGpt5MiniMedium
        ).any { it.visibility == View.VISIBLE && it.isChecked }
        if (anyChecked) return

        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        when {
            currentModelName.equals("gemini-2.5-flash", true) && binding.radioGeminiFlash.visibility == View.VISIBLE -> binding.radioGeminiFlash.isChecked = true
            currentModelName.startsWith("gemini", true) && binding.radioGeminiPro.visibility == View.VISIBLE -> binding.radioGeminiPro.isChecked = true
            currentModelName.equals("gpt-5", true) && binding.radioGpt5Low.visibility == View.VISIBLE -> {
                val effort = prefs.getString("gpt5_effort", "low")?.lowercase() ?: "low"
                when (effort) {
                    "high" -> binding.radioGpt5High.isChecked = true
                    "medium" -> binding.radioGpt5Medium.isChecked = true
                    else -> binding.radioGpt5Low.isChecked = true
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

    fun toggleSettingsVisibility(forceShow: Boolean = false) {
        val visible = binding.settingsContainerScrollView.visibility == View.VISIBLE
        val show = forceShow || !visible
        binding.settingsContainerScrollView.visibility = if (show) View.VISIBLE else View.GONE
        binding.conversationRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
        binding.bottomBar.visibility = if (show) View.GONE else View.VISIBLE
        viewModel.setSettingsVisible(show)
    }

    private fun startListenWindow(reason: String) {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val secs = prefs.getInt("listen_seconds", 5).coerceIn(1, 120)
        val now = SystemClock.elapsedRealtime()
        listenWindowDeadlineMs = now + secs * 1000L
        listenRestartCount = 0
        Log.d(TAG, "[${now()}][ListenWindow] Started: reason='$reason', duration=${secs}s")
    }

    private fun isSettingsVisible(): Boolean = binding.settingsContainerScrollView.visibility == View.VISIBLE

    private fun migrateLegacyOpenAiModelName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower == "gpt-5-turbo" -> "gpt-5"
            lower.startsWith("gpt-4o") -> "gpt-5"
            else -> name
        }
    }

    private fun setupGeminiKeyHelpLink() {
        val html = "<a href=\"https://aistudio.google.com/api-keys\">Get free Gemini API Key here</a>"
        binding.geminiApiKeyHelpLink.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        binding.geminiApiKeyHelpLink.movementMethod = LinkMovementMethod.getInstance()
        binding.geminiApiKeyHelpLink.linksClickable = true
    }

    private fun refreshGeminiKeyHelpLinkVisibility() {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val empty = prefs.getString("gemini_key", "").orEmpty().isBlank()
        binding.geminiApiKeyHelpLink.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun setupSttSystemSelection() {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val savedSystemName = prefs.getString("stt_system", SttSystem.STANDARD.name) ?: SttSystem.STANDARD.name
        val savedSystem = try { SttSystem.valueOf(savedSystemName) } catch (_: Exception) { SttSystem.STANDARD }


        when (savedSystem) {
            SttSystem.STANDARD -> binding.radioSttStandard.isChecked = true
            SttSystem.GEMINI_LIVE -> binding.radioSttGeminiLive.isChecked = true
        }

        viewModel.setSttSystem(savedSystem)

        binding.sttSystemRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedSystem = when (checkedId) {
                R.id.radioSttGeminiLive -> SttSystem.GEMINI_LIVE
                else -> SttSystem.STANDARD
            }

            prefs.edit().putString("stt_system", selectedSystem.name).apply()

            viewModel.setSttSystem(selectedSystem)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTtsSettingsLink() {
        binding.ttsSettingsLink.setOnClickListener {
            try {
                val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not open direct TTS settings, opening language settings instead.", e)
                Toast.makeText(context, "Could not open TTS settings directly.", Toast.LENGTH_SHORT).show()
                try {
                    val intent = android.content.Intent(Settings.ACTION_LOCALE_SETTINGS)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Could not open any language/TTS settings.", fallbackError)
                    Toast.makeText(context, "Could not open any system language settings.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}