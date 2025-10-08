package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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

class FirstFragment : Fragment() {

    private val TAG = "FirstFragment"

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConversationViewModel by viewModels()

    private var autoContinue = true
    private var pendingAutoListen = false
    private var pendingStartAfterPermission = false

    private var currentModelName = "gemini-2.5-pro"
    private var userToggledSettings = false

    private val PREFS = "ai_prefs"
    private val PREF_SYSTEM_PROMPT_KEY = "system_prompt"
    private val PREF_SYSTEM_PROMPT_EXT_KEY = "system_prompt_extension"
    private val PREF_MAX_SENTENCES_KEY = "max_sentences"
    private val PREF_FASTER_FIRST = "faster_first"
    private val PREF_LISTEN_SECONDS_KEY = "listen_seconds"
    private val PREF_SELECTED_MODEL = "selected_model"
    private val DEFAULT_SYSTEM_PROMPT = """
        You are a helpful assistant. Answer the user's request in clear, complete sentences.
        Return plain text only. Do not use JSON or code fences unless explicitly requested.
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

    private val STT_GRACE_MS_AFTER_TTS = 500L

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

        val restoredModel = prefs.getString(PREF_SELECTED_MODEL, "gemini-2.5-pro") ?: "gemini-2.5-pro"
        currentModelName = restoredModel
        viewModel.setCurrentModel(restoredModel)
        when (restoredModel) {
            "gemini-2.5-flash" -> binding.radioGeminiFlash.isChecked = true
            "gemini-2.5-pro" -> binding.radioGeminiPro.isChecked = true
            "gpt-5-turbo" -> binding.radioGpt5.isChecked = true
            "gpt-5-mini" -> binding.radioGpt5Mini.isChecked = true
            else -> binding.radioGeminiPro.isChecked = true
        }
        Log.i(TAG, "[MODEL] Restored selection $currentModelName")
        Log.i(TAG, "[STATE] Restored conversation size=${viewModel.conversation.value?.size ?: 0}")

        // [FIX] After rotation, check if TTS was interrupted and needs to be resumed.
        viewModel.checkAndResumeTts()
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
        binding.geminiApiKeyInput.setText(prefs.getString("gemini_key", "") ?: "")
        binding.openaiApiKeyInput.setText(prefs.getString("openai_key", "") ?: "")
        binding.geminiApiKeyInput.doAfterTextChanged { editable ->
            prefs.edit().putString("gemini_key", editable?.toString()?.trim()).apply()
        }
        binding.openaiApiKeyInput.doAfterTextChanged { editable ->
            prefs.edit().putString("openai_key", editable?.toString()?.trim()).apply()
        }

        val savedPrompt = prefs.getString(PREF_SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT)
        binding.systemPromptInput.setText(savedPrompt)
        val savedExt = prefs.getString(PREF_SYSTEM_PROMPT_EXT_KEY, "") ?: ""
        binding.systemPromptExtensionInput.setText(savedExt)
        binding.systemPromptInput.doAfterTextChanged { editable ->
            prefs.edit().putString(PREF_SYSTEM_PROMPT_KEY, editable?.toString().orEmpty()).apply()
        }
        binding.systemPromptExtensionInput.doAfterTextChanged { editable ->
            prefs.edit().putString(PREF_SYSTEM_PROMPT_EXT_KEY, editable?.toString().orEmpty()).apply()
        }
        binding.resetSystemPromptButton.setOnClickListener {
            binding.systemPromptInput.setText(DEFAULT_SYSTEM_PROMPT)
            Toast.makeText(context, "System prompt reset to default", Toast.LENGTH_SHORT).show()
        }

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
        }

        val savedListen = prefs.getInt(PREF_LISTEN_SECONDS_KEY, 3).coerceIn(1, 120)
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

            if (pendingAutoListen && speaking == null) {
                pendingAutoListen = false
                binding.conversationRecyclerView.postDelayed({ handleStartListeningRequest() }, STT_GRACE_MS_AFTER_TTS)
            }
        }

        viewModel.turnFinishedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                if (autoContinue) {
                    if (viewModel.isSpeaking.value == true) {
                        pendingAutoListen = true
                    } else {
                        handleStartListeningRequest()
                    }
                }
                setLoading(false)
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
            if(isListening) {
                maybeAutoCollapseSettings()
            }
            setLoading(false)
        }

        viewModel.sttError.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { errorMessage ->
                Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            }
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
            handleStartListeningRequest()
        }
        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            viewModel.stopTts()
            view?.postDelayed({ handleStartListeningRequest() }, 250)
        }
        binding.stopButton.setOnClickListener {
            autoContinue = false
            pendingAutoListen = false
            viewModel.stopAll()
            setLoading(false)
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

        for ((rb, model) in radios) {
            rb.setOnClickListener {
                if (model == currentModelName) return@setOnClickListener
                if (viewModel.isEngineActive()) viewModel.abortEngine()
                selectRadio(rb)
                currentModelName = model
                viewModel.setCurrentModel(model)
                prefs.edit().putString(PREF_SELECTED_MODEL, currentModelName).apply()
                val userFacingModelName = rb.text.toString()
                Toast.makeText(context, "Model: $userFacingModelName", Toast.LENGTH_SHORT).show()
                viewModel.addSystemEntry("Model switched to $userFacingModelName")
            }
        }
    }

    private fun handleStartListeningRequest() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (viewModel.isSpeaking.value == true) {
            pendingAutoListen = true
            return
        }
        val elapsed = viewModel.elapsedSinceTtsDone()
        if (elapsed < STT_GRACE_MS_AFTER_TTS) {
            pendingAutoListen = true
            view?.postDelayed({ handleStartListeningRequest() }, STT_GRACE_MS_AFTER_TTS - elapsed)
            return
        }

        pendingAutoListen = false
        if (viewModel.sttIsListening.value == false) {
            if (!ensureKeyAvailableForModelOrShow(currentModelName)) return
            viewModel.startListening()
        }
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

    private fun updateButtonStates() {
        val engineActive = viewModel.isEngineActive()
        val isSpeaking = viewModel.isSpeaking.value == true
        val isListening = viewModel.sttIsListening.value ?: false
        binding.stopButton.isEnabled = isSpeaking || isListening || engineActive
        binding.newRecordingButton.isEnabled = isSpeaking
        binding.speakButton.isEnabled = !isListening && !engineActive
    }

    private fun setLoading(isLoading: Boolean) {
        val isListening = viewModel.sttIsListening.value ?: false
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.speakButton.isEnabled = !isLoading && !isListening
        binding.radioGeminiFlash.isEnabled = !isLoading
        binding.radioGeminiPro.isEnabled = !isLoading
        binding.radioGpt5.isEnabled = !isLoading
        binding.radioGpt5Mini.isEnabled = !isLoading
        binding.geminiApiKeyInput.isEnabled = !isLoading && !isListening
        binding.openaiApiKeyInput.isEnabled = !isLoading && !isListening
        // Keep settings enabled
        binding.maxSentencesInput.isEnabled = true
        binding.systemPromptInput.isEnabled = true
        binding.systemPromptExtensionInput.isEnabled = true
        binding.listenSecondsInput?.isEnabled = true
        updateButtonStates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}