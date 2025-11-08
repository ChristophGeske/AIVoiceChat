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
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupApiKeySection(view)
        setupModelRadios(view)
        setupPromptSection(view)
        setupMaxSentences(view)
        setupListenSeconds(view)
        setupAutoListen(view)
        setupFasterFirst(view)
        setupTtsSettingsLink(view)
    }

    private fun setupApiKeySection(root: View) {
        val geminiInput = root.findViewById<TextInputEditText>(R.id.geminiApiKeyInput)
        val openaiInput = root.findViewById<TextInputEditText>(R.id.openaiApiKeyInput)
        val help = root.findViewById<TextView>(R.id.geminiApiKeyHelpLink)

        geminiInput.setText(Prefs.getGeminiKey(requireContext()), TextView.BufferType.EDITABLE)
        openaiInput.setText(Prefs.getOpenAiKey(requireContext()), TextView.BufferType.EDITABLE)

        help.text = Html.fromHtml("<a href=\"https://aistudio.google.com/api-keys\">Get free Gemini API Key here</a>", Html.FROM_HTML_MODE_COMPACT)
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
        val allModelButtons = listOf(
            root.findViewById<RadioButton>(R.id.radioGeminiFlash),
            root.findViewById<RadioButton>(R.id.radioGeminiPro),
            root.findViewById<RadioButton>(R.id.radioGpt5High),
            root.findViewById<RadioButton>(R.id.radioGpt5Medium),
            root.findViewById<RadioButton>(R.id.radioGpt5Low),
            root.findViewById<RadioButton>(R.id.radioGpt5MiniHigh),
            root.findViewById<RadioButton>(R.id.radioGpt5MiniMedium)
        )

        val currentModel = Prefs.getSelectedModel(requireContext()).lowercase()
        val currentEffort = Prefs.getGpt5Effort(requireContext()).lowercase()

        allModelButtons.forEach { it.isChecked = false }

        when {
            currentModel.startsWith("gemini-2.5-flash") -> root.findViewById<RadioButton>(R.id.radioGeminiFlash).isChecked = true
            currentModel.startsWith("gemini") -> root.findViewById<RadioButton>(R.id.radioGeminiPro).isChecked = true
            currentModel == "gpt-5" && currentEffort == "high" -> root.findViewById<RadioButton>(R.id.radioGpt5High).isChecked = true
            currentModel == "gpt-5" && currentEffort == "medium" -> root.findViewById<RadioButton>(R.id.radioGpt5Medium).isChecked = true
            currentModel == "gpt-5" -> root.findViewById<RadioButton>(R.id.radioGpt5Low).isChecked = true
            currentModel == "gpt-5-mini" && currentEffort == "high" -> root.findViewById<RadioButton>(R.id.radioGpt5MiniHigh).isChecked = true
            currentModel == "gpt-5-mini" -> root.findViewById<RadioButton>(R.id.radioGpt5MiniMedium).isChecked = true
        }

        val modelClickListener = View.OnClickListener { clickedView ->
            allModelButtons.forEach { button ->
                button.isChecked = (button.id == clickedView.id)
            }

            when (clickedView.id) {
                R.id.radioGeminiFlash -> onModelChosen("gemini-2.5-flash", null)
                R.id.radioGeminiPro -> onModelChosen("gemini-2.5-pro", null)
                R.id.radioGpt5High -> onModelChosen("gpt-5", "high")
                R.id.radioGpt5Medium -> onModelChosen("gpt-5", "medium")
                R.id.radioGpt5Low -> onModelChosen("gpt-5", "low")
                R.id.radioGpt5MiniHigh -> onModelChosen("gpt-5-mini", "high")
                R.id.radioGpt5MiniMedium -> onModelChosen("gpt-5-mini", "medium")
            }
        }

        allModelButtons.forEach { it.setOnClickListener(modelClickListener) }
    }

    private fun onModelChosen(model: String, effort: String?) {
        Prefs.setSelectedModel(requireContext(), model)
        if (effort != null) Prefs.setGpt5Effort(requireContext(), effort)
        Toast.makeText(requireContext(), "Model set to $model${effort?.let { " ($it)" } ?: ""}", Toast.LENGTH_SHORT).show()
    }

    private fun setupPromptSection(root: View) {
        val systemPrompt = root.findViewById<TextInputEditText>(R.id.systemPromptInput)
        val resetSystem = root.findViewById<View>(R.id.resetSystemPromptButton)
        val extInput = root.findViewById<TextInputEditText>(R.id.promptExtensionsInput)
        val resetExt = root.findViewById<View>(R.id.resetPromptExtensionsButton)
        val warning = root.findViewById<TextView>(R.id.systemPromptWarning)
        val fasterFirst = Prefs.getFasterFirst(requireContext())
        warning.visibility = if (fasterFirst) View.VISIBLE else View.GONE

        val defaultSystem = DEFAULT_SYSTEM_PROMPT.trimIndent()

        systemPrompt.setText(Prefs.getSystemPrompt(requireContext(), defaultSystem), TextView.BufferType.EDITABLE)
        systemPrompt.doAfterTextChanged {
            Prefs.setSystemPrompt(requireContext(), it?.toString().orEmpty())
        }
        resetSystem.setOnClickListener {
            systemPrompt.setText(defaultSystem, TextView.BufferType.EDITABLE)
            Prefs.setSystemPrompt(requireContext(), defaultSystem)
            Toast.makeText(requireContext(), "System prompt reset", Toast.LENGTH_SHORT).show()
        }

        val defaultExt = buildDefaultPromptExtensions()
        val currentExt = Prefs.getSystemPromptExtensions(requireContext())
        extInput.setText(currentExt, TextView.BufferType.EDITABLE)
        extInput.doAfterTextChanged {
            Prefs.setSystemPromptExtensions(requireContext(), it?.toString().orEmpty())
        }
        resetExt.setOnClickListener {
            extInput.setText(defaultExt, TextView.BufferType.EDITABLE)
            Prefs.setSystemPromptExtensions(requireContext(), defaultExt)
            Toast.makeText(requireContext(), "Extensions reset", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDefaultPromptExtensions(): String {
        val maxSent = Prefs.getMaxSentences(requireContext())
        val faster = Prefs.getFasterFirst(requireContext())
        return buildList {
            add("Return plain text only.")
            add("IMPORTANT: Your response MUST be AT MOST $maxSent sentences long, unless the user explicitly asks for a different length.")
            if (faster) add("Begin with a complete first sentence promptly, then continue.")
        }.joinToString(" ")
    }

    private fun setupMaxSentences(root: View) {
        val input = root.findViewById<TextInputEditText>(R.id.maxSentencesInput)
        input.setText(Prefs.getMaxSentences(requireContext()).toString(), TextView.BufferType.EDITABLE)
        input.doAfterTextChanged {
            it?.toString()?.trim()?.toIntOrNull()?.let { n ->
                Prefs.setMaxSentences(requireContext(), n)
            }
        }
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
        val sw = root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoListen)
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

    private fun setupFasterFirst(root: View) {
        val sw = root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchFasterFirst)
        val current = Prefs.getFasterFirst(requireContext())
        sw.isChecked = current
        val warning = root.findViewById<TextView>(R.id.systemPromptWarning)
        warning.visibility = if (current) View.VISIBLE else View.GONE
        sw.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setFasterFirst(requireContext(), isChecked)
            warning.visibility = if (isChecked) View.VISIBLE else View.GONE
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

    private fun updateModelOptionsVisibility(root: View) {
        val hasGemini = Prefs.getGeminiKey(requireContext()).isNotBlank()
        val hasOpenAi = Prefs.getOpenAiKey(requireContext()).isNotBlank()

        val rbGeminiFlash = root.findViewById<RadioButton>(R.id.radioGeminiFlash)
        val rbGeminiPro = root.findViewById<RadioButton>(R.id.radioGeminiPro)
        val rbGpt5High = root.findViewById<RadioButton>(R.id.radioGpt5High)
        val rbGpt5Medium = root.findViewById<RadioButton>(R.id.radioGpt5Medium)
        val rbGpt5Low = root.findViewById<RadioButton>(R.id.radioGpt5Low)
        val rbGpt5MiniHigh = root.findViewById<RadioButton>(R.id.radioGpt5MiniHigh)
        val rbGpt5MiniMedium = root.findViewById<RadioButton>(R.id.radioGpt5MiniMedium)

        rbGeminiFlash.visibility = if (hasGemini) View.VISIBLE else View.GONE
        rbGeminiPro.visibility = if (hasGemini) View.VISIBLE else View.GONE

        val gptVis = if (hasOpenAi) View.VISIBLE else View.GONE
        rbGpt5High.visibility = gptVis
        rbGpt5Medium.visibility = gptVis
        rbGpt5Low.visibility = gptVis
        rbGpt5MiniHigh.visibility = gptVis
        rbGpt5MiniMedium.visibility = gptVis
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's request in clear, complete sentences.
            Do not use JSON or code fences unless explicitly requested.
        """
    }
}