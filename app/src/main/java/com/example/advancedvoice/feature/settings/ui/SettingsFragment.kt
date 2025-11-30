package com.example.advancedvoice.feature.settings.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.example.advancedvoice.R
import com.example.advancedvoice.core.prefs.Prefs
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    // Views that need to sync bidirectionally
    private var maxSentencesInput: TextInputEditText? = null
    private var dynamicInstructionsInput: TextInputEditText? = null
    private var fullPromptPreviewText: TextView? = null
    private var fasterFirstSwitch: SwitchMaterial? = null

    // Flag to prevent infinite update loops
    private var isUpdatingFromCode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Cache the views that need bidirectional sync
        maxSentencesInput = view.findViewById(R.id.maxSentencesInput)
        dynamicInstructionsInput = view.findViewById(R.id.dynamicInstructionsInput)
        fullPromptPreviewText = view.findViewById(R.id.fullPromptPreview)
        fasterFirstSwitch = view.findViewById(R.id.switchFasterFirst)

        setupApiKeySection(view)
        setupModelRadios(view)
        setupMaxSentences(view)
        setupListenSeconds(view)
        setupAutoListen(view)
        setupFasterFirst(view)
        setupTtsSettingsLink(view)
        setupPromptSection(view)
        setupDynamicInstructions(view)

        // Initial population of all previews
        updateDynamicInstructionsFromSettings()
        updateFullPromptPreview()
    }

    // ════════════════════════════════════════════════════════════════════
    // BIDIRECTIONAL SYNC LOGIC
    // ════════════════════════════════════════════════════════════════════

    /**
     * Build the dynamic instructions text from current settings.
     */
    private fun buildDynamicInstructionsText(): String {
        val maxSentences = Prefs.getMaxSentences(requireContext())
        val fasterFirst = Prefs.getFasterFirst(requireContext())

        return buildString {
            append("RESPONSE REQUIREMENTS:\n")
            append("• Your response MUST be AT MOST $maxSentences sentences long.\n")
            append("• Return plain text only, no markdown or formatting.\n")
            if (fasterFirst) {
                append("• Start with a complete, informative first sentence immediately.\n")
            }
            append("• The user speaks via audio. Listen carefully and respond naturally.")
        }
    }

    /**
     * Update the dynamic instructions input field from settings (one-way: settings → text).
     */
    private fun updateDynamicInstructionsFromSettings() {
        if (isUpdatingFromCode) return
        isUpdatingFromCode = true

        val text = buildDynamicInstructionsText()
        dynamicInstructionsInput?.setText(text)

        // Also save to prefs so it persists
        Prefs.setDynamicInstructions(requireContext(), text)

        isUpdatingFromCode = false
        updateFullPromptPreview()
    }

    /**
     * Parse the dynamic instructions text and extract settings from it.
     * Returns the max sentences number if found, null otherwise.
     */
    private fun parseMaxSentencesFromText(text: String): Int? {
        // Look for patterns like "AT MOST 4 sentences" or "MOST 5 sentences"
        val patterns = listOf(
            Regex("""AT MOST (\d+) sentence""", RegexOption.IGNORE_CASE),
            Regex("""MOST (\d+) sentence""", RegexOption.IGNORE_CASE),
            Regex("""maximum (\d+) sentence""", RegexOption.IGNORE_CASE),
            Regex("""max (\d+) sentence""", RegexOption.IGNORE_CASE),
            Regex("""up to (\d+) sentence""", RegexOption.IGNORE_CASE),
            Regex("""(\d+) sentences? (?:long|max)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val number = match.groupValues[1].toIntOrNull()
                if (number != null && number in 1..10) {
                    return number
                }
            }
        }
        return null
    }

    /**
     * Check if "first sentence" instruction is present (for faster first toggle).
     */
    private fun parseFasterFirstFromText(text: String): Boolean {
        val patterns = listOf(
            "first sentence immediately",
            "start with a complete",
            "begin with a complete first",
            "first sentence promptly"
        )
        val lowerText = text.lowercase()
        return patterns.any { lowerText.contains(it) }
    }

    /**
     * When user edits the dynamic instructions text, extract and sync settings.
     */
    private fun onDynamicInstructionsChanged(newText: String) {
        if (isUpdatingFromCode) return

        // Save the custom text to prefs
        Prefs.setDynamicInstructions(requireContext(), newText)

        // Try to extract max sentences
        val extractedMax = parseMaxSentencesFromText(newText)
        if (extractedMax != null) {
            val currentMax = Prefs.getMaxSentences(requireContext())
            if (extractedMax != currentMax) {
                isUpdatingFromCode = true
                Prefs.setMaxSentences(requireContext(), extractedMax)
                maxSentencesInput?.setText(extractedMax.toString())
                isUpdatingFromCode = false
            }
        }

        // Try to extract faster first
        val extractedFasterFirst = parseFasterFirstFromText(newText)
        val currentFasterFirst = Prefs.getFasterFirst(requireContext())
        if (extractedFasterFirst != currentFasterFirst) {
            isUpdatingFromCode = true
            Prefs.setFasterFirst(requireContext(), extractedFasterFirst)
            fasterFirstSwitch?.isChecked = extractedFasterFirst

            // Update warning visibility
            view?.findViewById<TextView>(R.id.systemPromptWarning)?.visibility =
                if (extractedFasterFirst) View.VISIBLE else View.GONE

            isUpdatingFromCode = false
        }

        updateFullPromptPreview()
    }

    /**
     * Update the full prompt preview (read-only, shows combined result).
     */
    private fun updateFullPromptPreview() {
        val basePrompt = Prefs.getSystemPrompt(requireContext(), DEFAULT_SYSTEM_PROMPT.trimIndent())
        val extensions = Prefs.getSystemPromptExtensions(requireContext())
        val dynamicInstructions = Prefs.getDynamicInstructions(requireContext())
            .ifBlank { buildDynamicInstructionsText() }

        val fullPrompt = buildString {
            append("── BASE PROMPT ──\n")
            append(basePrompt)

            if (extensions.isNotBlank()) {
                append("\n\n── CUSTOM EXTENSIONS ──\n")
                append(extensions)
            }

            append("\n\n── RESPONSE INSTRUCTIONS ──\n")
            append(dynamicInstructions)
        }

        fullPromptPreviewText?.text = fullPrompt
    }

    // ════════════════════════════════════════════════════════════════════
    // SETUP METHODS
    // ════════════════════════════════════════════════════════════════════

    private fun setupDynamicInstructions(root: View) {
        val input = root.findViewById<TextInputEditText>(R.id.dynamicInstructionsInput)
        val resetBtn = root.findViewById<View>(R.id.resetDynamicInstructionsButton)

        // Load saved or generate default
        val saved = Prefs.getDynamicInstructions(requireContext())
        if (saved.isNotBlank()) {
            input?.setText(saved)
        } else {
            input?.setText(buildDynamicInstructionsText())
        }

        // Listen for changes
        input?.doAfterTextChanged { editable ->
            onDynamicInstructionsChanged(editable?.toString().orEmpty())
        }

        // Reset button
        resetBtn?.setOnClickListener {
            isUpdatingFromCode = true
            val defaultText = buildDynamicInstructionsText()
            input?.setText(defaultText)
            Prefs.setDynamicInstructions(requireContext(), defaultText)
            isUpdatingFromCode = false
            updateFullPromptPreview()
            Toast.makeText(requireContext(), "Instructions reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMaxSentences(root: View) {
        val input = root.findViewById<TextInputEditText>(R.id.maxSentencesInput)
        input.setText(Prefs.getMaxSentences(requireContext()).toString(), TextView.BufferType.EDITABLE)

        input.doAfterTextChanged {
            if (isUpdatingFromCode) return@doAfterTextChanged

            it?.toString()?.trim()?.toIntOrNull()?.let { n ->
                val clamped = n.coerceIn(1, 10)
                Prefs.setMaxSentences(requireContext(), clamped)
                updateDynamicInstructionsFromSettings()
            }
        }
    }

    private fun setupFasterFirst(root: View) {
        val sw = root.findViewById<SwitchMaterial>(R.id.switchFasterFirst)
        val warning = root.findViewById<TextView>(R.id.systemPromptWarning)

        val current = Prefs.getFasterFirst(requireContext())
        sw.isChecked = current
        warning.visibility = if (current) View.VISIBLE else View.GONE

        sw.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingFromCode) return@setOnCheckedChangeListener

            Prefs.setFasterFirst(requireContext(), isChecked)
            warning.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateDynamicInstructionsFromSettings()
        }
    }

    private fun setupApiKeySection(root: View) {
        val geminiInput = root.findViewById<TextInputEditText>(R.id.geminiApiKeyInput)
        val openaiInput = root.findViewById<TextInputEditText>(R.id.openaiApiKeyInput)
        val help = root.findViewById<TextView>(R.id.geminiApiKeyHelpLink)

        geminiInput.setText(Prefs.getGeminiKey(requireContext()), TextView.BufferType.EDITABLE)
        openaiInput.setText(Prefs.getOpenAiKey(requireContext()), TextView.BufferType.EDITABLE)

        help.text = Html.fromHtml(
            "<a href=\"https://aistudio.google.com/api-keys\">Get free Gemini API Key here</a>",
            Html.FROM_HTML_MODE_COMPACT
        )
        help.movementMethod = LinkMovementMethod.getInstance()
        help.visibility = if (Prefs.getGeminiKey(requireContext()).isBlank()) View.VISIBLE else View.GONE

        geminiInput.doAfterTextChanged {
            val v = it?.toString()?.trim().orEmpty()
            Prefs.setGeminiKey(requireContext(), v)
            help.visibility = if (v.isBlank()) View.VISIBLE else View.GONE
            updateModelOptionsVisibility(root)
        }

        openaiInput.doAfterTextChanged {
            val v = it?.toString()?.trim().orEmpty()
            Prefs.setOpenAiKey(requireContext(), v)
            updateModelOptionsVisibility(root)
        }

        updateModelOptionsVisibility(root)
    }

    private fun setupModelRadios(root: View) {
        val radioGeminiFlash = root.findViewById<RadioButton>(R.id.radioGeminiFlash)
        val radioGeminiPro = root.findViewById<RadioButton>(R.id.radioGeminiPro)
        val radioGpt5MiniHigh = root.findViewById<RadioButton>(R.id.radioGpt5MiniHigh)
        val radioGpt5MiniMedium = root.findViewById<RadioButton>(R.id.radioGpt5MiniMedium)
        val radioGpt51Low = root.findViewById<RadioButton>(R.id.radioGpt51Low)
        val radioGpt51Medium = root.findViewById<RadioButton>(R.id.radioGpt51Medium)
        val radioGpt51High = root.findViewById<RadioButton>(R.id.radioGpt51High)

        val allModelButtons = listOf(
            radioGeminiFlash, radioGeminiPro,
            radioGpt5MiniHigh, radioGpt5MiniMedium,
            radioGpt51Low, radioGpt51Medium, radioGpt51High
        )

        val currentModel = Prefs.getSelectedModel(requireContext()).lowercase()
        val currentEffort = Prefs.getGpt5Effort(requireContext()).lowercase()

        allModelButtons.forEach { it.isChecked = false }

        when {
            currentModel.startsWith("gemini-2.5-flash") || currentModel == "gemini-flash-latest" ->
                radioGeminiFlash.isChecked = true
            currentModel.startsWith("gemini") || currentModel == "gemini-pro-latest" ->
                radioGeminiPro.isChecked = true
            currentModel == "gpt-5-mini" && currentEffort == "high" ->
                radioGpt5MiniHigh.isChecked = true
            currentModel == "gpt-5-mini" ->
                radioGpt5MiniMedium.isChecked = true
            currentModel == "gpt-5.1" && currentEffort == "low" ->
                radioGpt51Low.isChecked = true
            currentModel == "gpt-5.1" && currentEffort == "medium" ->
                radioGpt51Medium.isChecked = true
            currentModel == "gpt-5.1" && currentEffort == "high" ->
                radioGpt51High.isChecked = true
        }

        val modelClickListener = View.OnClickListener { clickedView ->
            allModelButtons.forEach { button ->
                button.isChecked = (button.id == clickedView.id)
            }

            when (clickedView.id) {
                R.id.radioGeminiFlash -> onModelChosen("gemini-flash-latest", null)
                R.id.radioGeminiPro -> onModelChosen("gemini-pro-latest", null)
                R.id.radioGpt5MiniHigh -> onModelChosen("gpt-5-mini", "high")
                R.id.radioGpt5MiniMedium -> onModelChosen("gpt-5-mini", "medium")
                R.id.radioGpt51Low -> onModelChosen("gpt-5.1", "low")
                R.id.radioGpt51Medium -> onModelChosen("gpt-5.1", "medium")
                R.id.radioGpt51High -> onModelChosen("gpt-5.1", "high")
            }
        }

        allModelButtons.forEach { it.setOnClickListener(modelClickListener) }
    }

    private fun onModelChosen(model: String, effort: String?) {
        Prefs.setSelectedModel(requireContext(), model)
        if (effort != null) Prefs.setGpt5Effort(requireContext(), effort)
        Toast.makeText(
            requireContext(),
            "Model set to $model${effort?.let { " ($it)" } ?: ""}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateModelOptionsVisibility(root: View) {
        val hasGemini = Prefs.getGeminiKey(requireContext()).isNotBlank()
        val hasOpenAi = Prefs.getOpenAiKey(requireContext()).isNotBlank()

        root.findViewById<RadioButton>(R.id.radioGeminiFlash)?.visibility =
            if (hasGemini) View.VISIBLE else View.GONE
        root.findViewById<RadioButton>(R.id.radioGeminiPro)?.visibility =
            if (hasGemini) View.VISIBLE else View.GONE

        val gptVis = if (hasOpenAi) View.VISIBLE else View.GONE
        root.findViewById<RadioButton>(R.id.radioGpt5MiniHigh)?.visibility = gptVis
        root.findViewById<RadioButton>(R.id.radioGpt5MiniMedium)?.visibility = gptVis
        root.findViewById<RadioButton>(R.id.radioGpt51Low)?.visibility = gptVis
        root.findViewById<RadioButton>(R.id.radioGpt51Medium)?.visibility = gptVis
        root.findViewById<RadioButton>(R.id.radioGpt51High)?.visibility = gptVis
    }

    private fun setupListenSeconds(root: View) {
        val input = root.findViewById<TextInputEditText>(R.id.listenSecondsInput)
        input.setText(Prefs.getListenSeconds(requireContext()).toString(), TextView.BufferType.EDITABLE)
        input.doAfterTextChanged {
            it?.toString()?.trim()?.toIntOrNull()?.let { n ->
                Prefs.setListenSeconds(requireContext(), n)
            }
        }
    }

    private fun setupAutoListen(root: View) {
        val sw = root.findViewById<SwitchMaterial>(R.id.switchAutoListen)
        sw.isChecked = Prefs.getAutoListen(requireContext())
        sw.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoListen(requireContext(), isChecked)
            Toast.makeText(
                requireContext(),
                if (isChecked) "Auto-listen enabled" else "Auto-listen disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupTtsSettingsLink(root: View) {
        val link = root.findViewById<TextView>(R.id.ttsSettingsLink)
        link.setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun setupPromptSection(root: View) {
        val systemPromptInput = root.findViewById<TextInputEditText>(R.id.systemPromptInput)
        val resetSystemBtn = root.findViewById<View>(R.id.resetSystemPromptButton)
        val extensionsInput = root.findViewById<TextInputEditText>(R.id.promptExtensionsInput)
        val resetExtBtn = root.findViewById<View>(R.id.resetPromptExtensionsButton)

        val defaultSystem = DEFAULT_SYSTEM_PROMPT.trimIndent()

        // Setup base system prompt
        systemPromptInput.setText(
            Prefs.getSystemPrompt(requireContext(), defaultSystem),
            TextView.BufferType.EDITABLE
        )
        systemPromptInput.doAfterTextChanged {
            Prefs.setSystemPrompt(requireContext(), it?.toString().orEmpty())
            updateFullPromptPreview()
        }

        resetSystemBtn.setOnClickListener {
            systemPromptInput.setText(defaultSystem, TextView.BufferType.EDITABLE)
            Prefs.setSystemPrompt(requireContext(), defaultSystem)
            updateFullPromptPreview()
            Toast.makeText(requireContext(), "System prompt reset", Toast.LENGTH_SHORT).show()
        }

        // Setup custom extensions
        val currentExt = Prefs.getSystemPromptExtensions(requireContext())
        extensionsInput.setText(currentExt, TextView.BufferType.EDITABLE)
        extensionsInput.doAfterTextChanged {
            Prefs.setSystemPromptExtensions(requireContext(), it?.toString().orEmpty())
            updateFullPromptPreview()
        }

        resetExtBtn.setOnClickListener {
            extensionsInput.setText("", TextView.BufferType.EDITABLE)
            Prefs.setSystemPromptExtensions(requireContext(), "")
            updateFullPromptPreview()
            Toast.makeText(requireContext(), "Extensions cleared", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """
    }
}