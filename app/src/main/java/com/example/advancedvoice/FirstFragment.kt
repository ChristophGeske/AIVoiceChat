package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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
import androidx.lifecycle.lifecycleScope
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings

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
    private val AUTO_STT_DELAY_MS = 800L

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
        setupWhisperSettings()
        observeWhisperStatus()
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
    }

    private fun observeViewModel() {
        viewModel.uiControls.observe(viewLifecycleOwner) { state ->
            binding.speakButton.isEnabled = state.speakEnabled
            binding.stopButton.isEnabled = state.stopEnabled
            binding.newRecordingButton.visibility = if (state.newRecordingEnabled) View.VISIBLE else View.GONE
            binding.newRecordingButton.isEnabled = state.newRecordingEnabled
            binding.clearButton.isEnabled = state.clearEnabled
        }

        val updateSpeakVisuals = {
            val speaking = viewModel.isSpeaking.value == true
            val listening = viewModel.sttIsListening.value == true
            val transcribing = viewModel.isTranscribing.value == true
            val phase = viewModel.generationPhase.value ?: GenerationPhase.IDLE
            val generating = phase != GenerationPhase.IDLE

            binding.progressBar.visibility = View.GONE

            when {
                listening && generating -> {
                    binding.speakButton.text = "Recording (LLM generating)"
                    binding.speakButton.setTextColor(0xFFF44336.toInt())
                }
                listening && transcribing -> {
                    binding.speakButton.text = "Recording (processing…)"
                    binding.speakButton.setTextColor(0xFFF44336.toInt())
                }
                listening -> {
                    binding.speakButton.text = "Recording - Agent Listening"
                    binding.speakButton.setTextColor(0xFFF44336.toInt())
                }
                transcribing -> {
                    binding.speakButton.text = "Processing recording…"
                    binding.speakButton.setTextColor(0xFFFFFFFF.toInt())
                }
                speaking && generating -> {
                    binding.speakButton.text = "Agent speaking + Generating next response"
                    binding.speakButton.setTextColor(0xFF42A5F5.toInt())
                }
                speaking -> {
                    binding.speakButton.text = "Agent speaking"
                    binding.speakButton.setTextColor(0xFFFFFFFF.toInt())
                }
                generating -> {
                    binding.speakButton.text = "Response gets Generated"
                    binding.speakButton.setTextColor(0xFF4CAF50.toInt())
                }
                else -> {
                    binding.speakButton.text = getString(R.string.start_conversation)
                    binding.speakButton.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }

        viewModel.generationPhase.observe(viewLifecycleOwner) { updateSpeakVisuals() }
        viewModel.isSpeaking.observe(viewLifecycleOwner) { updateSpeakVisuals() }
        viewModel.sttIsListening.observe(viewLifecycleOwner) { updateSpeakVisuals() }
        viewModel.isTranscribing.observe(viewLifecycleOwner) { updateSpeakVisuals() }

        viewModel.generationPhase.observe(viewLifecycleOwner) { phase ->
            val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            val useWhisper = prefs.getBoolean("use_whisper", false)
            val generating = phase != GenerationPhase.IDLE
            val alreadyListening = viewModel.sttIsListening.value == true
            val delivered = viewModel.llmDelivered.value == true

            if (generating && !alreadyListening && !delivered && !isSettingsVisible()) {
                Log.i(TAG, "[${now()}][BargeIn] Generation started - scheduling barge-in detection in 800ms")
                view?.postDelayed({
                    val stillGenerating = viewModel.generationPhase.value != GenerationPhase.IDLE
                    val stillNotDelivered = viewModel.llmDelivered.value != true
                    val notListening = viewModel.sttIsListening.value != true
                    val notSpeaking = viewModel.isSpeaking.value != true

                    if (stillGenerating && stillNotDelivered && notListening && notSpeaking) {
                        Log.i(TAG, "[${now()}][BargeIn] Starting barge-in detection (Whisper=$useWhisper)")
                        if (useWhisper) {
                            viewModel.startListeningForBargeIn()
                        } else {
                            handleStartListeningRequest(delayMs = 0L)
                        }
                    }
                }, AUTO_STT_DELAY_MS)
            }
        }

        viewModel.isSpeaking.observe(viewLifecycleOwner) { isSpeaking ->
            if (isSpeaking && viewModel.sttIsListening.value == true) {
                Log.i(TAG, "[${now()}][AutoStopSTT] TTS started. Stopping listening only (and not transcribing).")
                viewModel.stopListeningOnly(transcribe = false)
            }
        }

        viewModel.ttsQueueFinishedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                val stillGenerating = viewModel.generationPhase.value != GenerationPhase.IDLE
                if (autoContinue && !isSettingsVisible() && !stillGenerating) {
                    val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("use_whisper", false)) {
                        Log.i(TAG, "[${now()}][AutoContinue] All TTS finished & generation done - starting Whisper")
                    } else {
                        Log.i(TAG, "[${now()}][AutoContinue] All TTS finished & generation done - starting STT")
                        startListenWindow("TTS")
                    }
                    handleStartListeningRequest(delayMs = STT_GRACE_MS_AFTER_TTS)
                } else if (stillGenerating) {
                    Log.d(TAG, "[${now()}][AutoContinue] Skipped - generation still active")
                }
            }
        }

        viewModel.sttNoMatch.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("use_whisper", false)) {
                    return@let
                }
                val remain = remainingListenWindowMs()
                if (autoContinue && isListenWindowActive() && !isSettingsVisible()) {
                    listenRestartCount += 1
                    Log.w(TAG, "[${now()}][ListenWindow] NO_MATCH within window. Restarting STT (count=$listenRestartCount, remaining=${remain}ms)")
                    handleStartListeningRequest(delayMs = 50L)
                } else {
                    Log.i(TAG, "[${now()}][ListenWindow] NO_MATCH but window expired (remaining=${remain}ms, autoContinue=$autoContinue)")
                }
            }
        }
    }

    private fun setupUI() {
        binding.clearButton.setOnClickListener {
            Log.i(TAG, "[${now()}][UI] Clear button pressed")
            viewModel.clearConversation()
        }

        binding.speakButton.setOnClickListener {
            Log.i(TAG, "[${now()}][UI] Speak button pressed")
            autoContinue = true
            if (!ensureKeyAvailableForModelOrShow(currentModelName)) return@setOnClickListener
            val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("use_whisper", false)) {
                startListenWindow("Manual Speak")
            }
            handleStartListeningRequest(delayMs = 0L)
        }

        binding.newRecordingButton.setOnClickListener {
            Log.i(TAG, "[${now()}][UI] New Recording button pressed")
            autoContinue = true
            val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("use_whisper", false)) {
                startListenWindow("New Recording")
            }
            viewModel.stopTts()
            view?.postDelayed({ handleStartListeningRequest(delayMs = 0L) }, 250)
        }

        binding.stopButton.setOnClickListener {
            Log.i(TAG, "[${now()}][UI] Stop button pressed")
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
        val useWhisper = prefs.getBoolean("use_whisper", false)
        if (useWhisper) return true

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

    private fun remainingListenWindowMs(): Long =
        (listenWindowDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun isListenWindowActive(): Boolean = SystemClock.elapsedRealtime() < listenWindowDeadlineMs

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

    private fun setupWhisperSettings() {
        val prefs = requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val useWhisper = prefs.getBoolean("use_whisper", false)
        binding.switchUseWhisper.isChecked = useWhisper
        if (useWhisper) {
            viewModel.setUseWhisper(true)
            checkAndDownloadModel()
        }
        binding.switchUseWhisper.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_whisper", isChecked).apply()
            viewModel.setUseWhisper(isChecked)
            if (isChecked) {
                checkAndDownloadModel()
            }
        }
    }

    private fun checkAndDownloadModel() {
        lifecycleScope.launch {
            val whisperService = viewModel.whisperService
            val modelToUse = whisperService.multilingualModel
            if (!whisperService.isModelDownloaded(modelToUse)) {
                binding.whisperDownloadStatus.text = "Downloading ${modelToUse.name}..."
                binding.whisperDownloadStatus.visibility = View.VISIBLE
                binding.whisperDownloadProgress.visibility = View.VISIBLE
                whisperService.downloadModel(modelToUse) { success ->
                    if (success) {
                        Toast.makeText(context, "${modelToUse.name} downloaded.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Log.d(TAG, "Model ${modelToUse.name} is already downloaded.")
                if(viewModel.whisperService.isModelReady.value != true) {
                    whisperService.initialize(modelToUse)
                }
            }
        }
    }

    private fun observeWhisperStatus() {
        val whisperService = viewModel.whisperService
        whisperService.downloadProgress.observe(viewLifecycleOwner) { progress ->
            if (progress in 1..99) {
                binding.whisperDownloadStatus.visibility = View.VISIBLE
                binding.whisperDownloadProgress.visibility = View.VISIBLE
                binding.whisperDownloadProgress.progress = progress
            } else {
                binding.whisperDownloadProgress.visibility = View.GONE
                binding.whisperDownloadStatus.visibility = View.GONE
            }
        }
        whisperService.isModelReady.observe(viewLifecycleOwner) { isReady ->
            if (isReady) {
                Log.d(TAG, "Whisper model is ready.")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTtsSettingsLink() {
        binding.ttsSettingsLink.setOnClickListener {
            try {
                // This Intent action directly opens the system's TTS settings screen.
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                // As a fallback, open the general language settings if the specific TTS screen is not available.
                Log.w(TAG, "Could not open direct TTS settings, opening language settings instead.", e)
                Toast.makeText(context, "Could not open TTS settings directly.", Toast.LENGTH_SHORT).show()
                try {
                    val intent = Intent(Settings.ACTION_LOCALE_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Could not open any language/TTS settings.", fallbackError)
                    Toast.makeText(context, "Could not open any system language settings.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}