package com.example.advancedvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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

    // A short grace period to prevent STT from starting while TTS audio might still be finalizing.
    private val STT_GRACE_MS_AFTER_TTS = 500L

    // NEW: listen window tracking for post-TTS listening duration
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

        // CHANGED default to 5s to match ViewModel and behavior
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
            // Notify both the previously highlighted and newly highlighted items to update
            if (prev != null) adapter.notifyItemChanged(prev)
            val now = speaking?.entryIndex
            if (now != null) adapter.notifyItemChanged(now)

            binding.newRecordingButton.visibility = if (speaking != null) View.VISIBLE else View.GONE
            updateButtonStates()
        }

        // [FIX] This observer now ONLY handles the loading state. Auto-listening is moved.
        viewModel.turnFinishedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "[LIFECYCLE] Turn finished event received. Unlocking UI.")
                setLoading(false)
            }
        }

        // NEW: correctly trigger auto-listening and start listen window.
        viewModel.ttsQueueFinishedEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Log.d(TAG, "[AutoContinue] TTS Queue finished event received. autoContinue=$autoContinue")
                if (autoContinue) {
                    startListenWindow("TTS")
                    handleStartListeningRequest(delayMs = STT_GRACE_MS_AFTER_TTS)
                }
            }
        }

        // NEW: Restart STT quickly when NO_MATCH occurs within the listen window
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
            if(isListening) {
                maybeAutoCollapseSettings()
            }
            setLoading(false)
            updateButtonStates()
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
            startListenWindow("Manual Speak")
            handleStartListeningRequest(delayMs = 0L)
        }
        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            startListenWindow("New Recording")
            viewModel.stopTts()
            // Post-delay to allow TTS to fully stop before listening starts
            view?.postDelayed({ handleStartListeningRequest(delayMs = 0L) }, 250)
        }
        binding.stopButton.setOnClickListener {
            autoContinue = false
            listenWindowDeadlineMs = 0L
            listenRestartCount = 0
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

    /**
     * Contains the actual logic to start listening, called after any necessary permissions or delays.
     */
    private fun startListeningNow() {
        // This check is still important to prevent race conditions or starting while another process is active.
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

        // Disable model/key inputs when the engine is running
        binding.radioGeminiFlash.isEnabled = !engineActive
        binding.radioGeminiPro.isEnabled = !engineActive
        binding.radioGpt5.isEnabled = !engineActive
        binding.radioGpt5Mini.isEnabled = !engineActive
        binding.geminiApiKeyInput.isEnabled = !engineActive
        binding.openaiApiKeyInput.isEnabled = !engineActive
        updateButtonStates()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "[LIFECYCLE] onResume - checking TTS resume")
        // Check TTS resume after a slight delay to ensure TTS is fully ready
        view?.postDelayed({
            viewModel.checkAndResumeTts()
        }, 300)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.i(TAG, "[LIFECYCLE] onDestroyView")
        _binding = null
    }

    // NEW: Listen window helpers
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
}