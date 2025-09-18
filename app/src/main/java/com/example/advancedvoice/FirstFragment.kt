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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class FirstFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    // Voice + network
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    // Conversation state and limits
    private data class Turn(val role: String, val text: String)
    private val history = mutableListOf<Turn>()
    private val MAX_CONTEXT_CHARS = 20000
    private val MAX_TURN_CHARS = 2000

    private var currentModelName = "gemini-2.5-flash"
    private var autoContinue = true
    private var isSpeaking = false
    private var isListening = false
    private var currentUtteranceId: String? = null
    private var currentCall: Call? = null

    // Prefs
    private val prefs by lazy { requireContext().getSharedPreferences("ai_prefs", Context.MODE_PRIVATE) }

    private class ApiHttpException(
        val provider: String,
        val url: String,
        val code: Int,
        val status: String?,
        val messageDetail: String?,
        val bodySnippet: String?,
        val requestId: String?,
        val retryAfterSeconds: Long?
    ) : RuntimeException()

    private class ApiNetworkException(
        val provider: String,
        val url: String,
        cause: Throwable
    ) : IOException(cause)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(context, getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // TTS
        textToSpeech = TextToSpeech(requireContext(), this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                currentUtteranceId = utteranceId
                isSpeaking = true
                requireActivity().runOnUiThread { binding.newRecordingButton.visibility = View.VISIBLE }
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                requireActivity().runOnUiThread {
                    binding.newRecordingButton.visibility = View.GONE
                    if (autoContinue) startListening()
                }
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                requireActivity().runOnUiThread {
                    binding.newRecordingButton.visibility = View.GONE
                    if (autoContinue) startListening()
                }
            }
        })

        // STT
        setupSpeechRecognizer()

        // UI
        setupUI()
        loadSavedKeys()
        setupKeyPersistence()
    }

    private fun setupUI() {
        binding.speakButton.setOnClickListener {
            autoContinue = true
            startListening()
        }

        binding.newRecordingButton.setOnClickListener {
            autoContinue = true
            stopTts()
            cancelOngoingCall()
            startListening()
        }

        binding.stopButton.setOnClickListener { stopConversation() }

        // Since we no longer use a single RadioGroup, enforce single-selection manually
        val radios = listOf(
            binding.radioGeminiFlash to "gemini-2.5-flash",
            binding.radioGeminiPro to "gemini-2.5-pro",
            binding.radioGpt5 to "gpt-5",
            binding.radioGpt5Mini to "gpt-5-mini"
        )

        val selectRadio: (RadioButton) -> Unit = { selected ->
            radios.forEach { (rb, _) -> if (rb !== selected) rb.isChecked = false }
            selected.isChecked = true
        }

        radios.forEach { (rb, model) ->
            rb.setOnClickListener {
                selectRadio(rb)
                currentModelName = model
                Toast.makeText(context, "Model: $currentModelName", Toast.LENGTH_SHORT).show()
                appendToConversation("System", "Model switched to $currentModelName")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(context, "Speech recognition is not available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                binding.speakButton.text = getString(R.string.status_listening)
                setLoading(false)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    appendToConversation("You", spokenText)
                    callModelApi(spokenText)
                }
                binding.speakButton.text = getString(R.string.status_ready)
            }

            override fun onError(error: Int) {
                isListening = false
                Log.e("STT_ERROR", "Error: $error")
                Toast.makeText(context, getString(R.string.error_stt), Toast.LENGTH_SHORT).show()
                binding.speakButton.text = getString(R.string.status_ready)
                setLoading(false)
                if (autoContinue) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(400)
                        startListening()
                    }
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (isListening) return
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        stopTts()
        cancelOngoingCall()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        speechRecognizer.startListening(intent)
    }

    private fun stopConversation() {
        autoContinue = false
        cancelOngoingCall()
        stopTts()
        stopListening()
        setLoading(false)
        binding.speakButton.text = getString(R.string.status_ready)
        binding.newRecordingButton.visibility = View.GONE
        appendToConversation("System", "Conversation stopped.")
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
    }

    private fun cancelOngoingCall() {
        try { currentCall?.cancel() } catch (_: Exception) {}
        currentCall = null
    }

    private fun callModelApi(userInputRaw: String) {
        setLoading(true)
        binding.speakButton.text = getString(R.string.status_thinking)

        if (!ensureKeyAvailableForModelOrShow(currentModelName)) {
            setLoading(false)
            binding.speakButton.text = getString(R.string.status_ready)
            return
        }

        maybeTruncateAndAddUserTurn(userInputRaw)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val aiResponse = withContext(Dispatchers.IO) { generateWithRetries(currentModelName) }
                appendToConversation("Assistant", aiResponse)
                addAssistantTurn(aiResponse)
                speak(aiResponse)
            } catch (e: Exception) {
                Log.e("LLM_API_ERROR", "API call failed", e)
                when (e) {
                    is ApiHttpException -> {
                        val proUnavailable = currentModelName.contains("gemini-2.5-pro", true) &&
                                (e.code == 401 || e.code == 403 || e.code == 404 ||
                                        (e.status?.contains("PERMISSION_DENIED", true) == true) ||
                                        (e.status?.contains("NOT_FOUND", true) == true))
                        if (proUnavailable) {
                            showModelUnavailableDialog("Gemini 2.5 Pro", "HTTP ${e.code}: ${e.messageDetail ?: e.status ?: "N/A"}")
                            appendToConversation("Error", "Gemini 2.5 Pro is not available for this API key or region.")
                        } else {
                            showApiErrorDialog(e)
                            appendToConversation(
                                "Error",
                                "HTTP ${e.code} ${e.status ?: ""}: ${(e.messageDetail ?: e.bodySnippet ?: "").take(300)}"
                            )
                        }
                    }
                    is ApiNetworkException -> {
                        showNetworkErrorDialog(e)
                        appendToConversation(
                            "Error",
                            "Network error: ${e.cause?.javaClass?.simpleName ?: ""} ${(e.cause?.message ?: "").take(200)}"
                        )
                    }
                    else -> {
                        val msg = e.message.orEmpty()
                        showGenericErrorDialog("Unexpected error", msg.take(600))
                        appendToConversation("Error", msg.ifBlank { getString(R.string.error_gemini) })
                    }
                }
            } finally {
                setLoading(false)
                binding.speakButton.text = getString(R.string.status_ready)
            }
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

    // ====== History helpers ======

    private fun maybeTruncateAndAddUserTurn(raw: String) {
        var text = raw.trim()
        if (text.length > MAX_TURN_CHARS) {
            appendToConversation("System", "Your input was long and was truncated to improve reliability.")
            text = text.take(MAX_TURN_CHARS)
        }
        history.add(Turn("user", text))
        pruneHistoryToBudget()
    }

    private fun addAssistantTurn(text: String) {
        history.add(Turn("assistant", text))
        pruneHistoryToBudget()
    }

    private fun pruneHistoryToBudget() {
        var total = history.sumOf { it.text.length }
        while (total > MAX_CONTEXT_CHARS && history.isNotEmpty()) {
            val removed = history.removeAt(0)
            total -= removed.text.length
        }
    }

    private fun buildGeminiContentsFromHistory(): JSONArray {
        val contents = JSONArray()
        for (t in history) {
            val role = if (t.role.equals("assistant", true)) "model" else "user"
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", t.text) })
                    })
                }
            )
        }
        return contents
    }

    private fun buildOpenAiMessagesFromHistory(): JSONArray {
        val messages = JSONArray()
        for (t in history) {
            val role = if (t.role.equals("assistant", true)) "assistant" else "user"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", t.text)
            })
        }
        return messages
    }

    // ====== Retry wrapper for transient errors ======

    private fun parseRetryAfterSeconds(retryAfterHeader: String?): Long? {
        if (retryAfterHeader.isNullOrBlank()) return null
        return retryAfterHeader.trim().toLongOrNull()
    }

    private suspend fun generateWithRetries(modelName: String): String {
        val maxRetries = 3
        var attempt = 0
        var backoffMs = 800L
        while (true) {
            try {
                return if (modelName.startsWith("gpt-", true)) {
                    generateWithOpenAI(modelName)
                } else {
                    generateWithGemini(modelName)
                }
            } catch (e: Exception) {
                val transient = when (e) {
                    is ApiHttpException -> e.code in listOf(408, 429, 500, 502, 503, 504)
                    is ApiNetworkException -> true
                    else -> false
                }
                if (!transient || attempt >= maxRetries) throw e

                val retryAfter = (e as? ApiHttpException)?.retryAfterSeconds?.let { it * 1000 }
                val jitter = Random.nextLong(0, 300)
                val delayMs = (retryAfter ?: backoffMs) + jitter
                Log.w("LLM_RETRY", "Transient error (${e.javaClass.simpleName}). Retrying in ${delayMs}ms (attempt ${attempt + 1}/$maxRetries)")
                delay(delayMs)
                attempt++
                backoffMs = min(backoffMs * 2, 8000)
            }
        }
    }

    // ====== Model calls with detailed error surfacing ======

    private fun generateWithGemini(modelName: String): String {
        val apiKey = getGeminiApiKey()
        if (apiKey.isBlank()) throw RuntimeException("Gemini API key is missing. Please enter your key.")

        val url = "https://generativelanguage.googleapis.com/v1/models/$modelName:generateContent?key=$apiKey"
        val bodyJson = JSONObject().apply {
            put("contents", buildGeminiContentsFromHistory())
            put("generationConfig", JSONObject().apply { put("temperature", 0.7) })
        }

        val requestBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()

        val call = httpClient.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = safeParseError(bodyStr)
                    val reqId = response.header("x-goog-request-id") ?: response.header("x-request-id")
                    val retryAfter = parseRetryAfterSeconds(response.header("Retry-After"))
                    throw ApiHttpException(
                        provider = "Gemini",
                        url = url,
                        code = response.code,
                        status = err.status,
                        messageDetail = err.message,
                        bodySnippet = bodyStr.take(800),
                        requestId = reqId,
                        retryAfterSeconds = retryAfter
                    )
                }
                val root = JSONObject(bodyStr)
                root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { br ->
                    if (br.isNotBlank()) return "The model blocked this request ($br). Try rephrasing."
                }
                val candidates = root.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text")
                        if (text.isNotBlank()) return text
                    }
                }
                return "No response from model."
            }
        } catch (io: IOException) {
            throw ApiNetworkException("Gemini", url, io)
        }
    }

    private fun supportsTemperatureParamForOpenAI(modelName: String): Boolean {
        // GPT-5 variants often require default temperature; omit to avoid 400
        return !(modelName.startsWith("gpt-5", true))
    }

    private fun generateWithOpenAI(modelName: String): String {
        val apiKey = getOpenAiApiKey()
        if (apiKey.isBlank()) throw RuntimeException("OpenAI API key is missing. Please enter your key.")

        val url = "https://api.openai.com/v1/chat/completions"
        val bodyJson = JSONObject().apply {
            put("model", modelName)
            put("messages", buildOpenAiMessagesFromHistory())
            if (supportsTemperatureParamForOpenAI(modelName)) {
                put("temperature", 0.7)
            }
        }

        val requestBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val call = httpClient.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = safeParseError(bodyStr)
                    val reqId = response.header("x-request-id")
                    val retryAfter = parseRetryAfterSeconds(response.header("Retry-After"))
                    throw ApiHttpException(
                        provider = "OpenAI",
                        url = url,
                        code = response.code,
                        status = err.status,
                        messageDetail = err.message,
                        bodySnippet = bodyStr.take(800),
                        requestId = reqId,
                        retryAfterSeconds = retryAfter
                    )
                }
                val root = JSONObject(bodyStr)
                val choices = root.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
                    if (content.isNotBlank()) return content
                }
                return "No response from model."
            }
        } catch (io: IOException) {
            throw ApiNetworkException("OpenAI", url, io)
        }
    }

    private data class ParsedError(val status: String?, val message: String?)
    private fun safeParseError(body: String): ParsedError {
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            if (err != null) {
                ParsedError(
                    status = err.optString("status", null) ?: err.optString("type", null),
                    message = err.optString("message", null)
                )
            } else {
                ParsedError(
                    status = null,
                    message = obj.optString("message", body.take(300))
                )
            }
        } catch (_: Exception) {
            ParsedError(status = null, message = body.take(300))
        }
    }

    // ====== Error dialogs ======

    private fun showApiErrorDialog(e: ApiHttpException) {
        val builder = StringBuilder()
        builder.append("Provider: ${e.provider}\n")
        builder.append("Model: $currentModelName\n")
        builder.append("HTTP: ${e.code}${if (!e.status.isNullOrBlank()) " (${e.status})" else ""}\n")
        if (!e.requestId.isNullOrBlank()) builder.append("Request-ID: ${e.requestId}\n")
        builder.append("Endpoint: ${e.url}\n")
        if (e.retryAfterSeconds != null) builder.append("Retry-After: ${e.retryAfterSeconds}s\n")
        builder.append("\n")
        val msg = e.messageDetail?.ifBlank { e.bodySnippet ?: "" } ?: (e.bodySnippet ?: "")
        if (msg.isNotBlank()) builder.append("Details:\n${msg.take(700)}")

        val hint = when (e.code) {
            400 -> "\n\nHint: Check parameters. For GPT‑5 models, omit 'temperature'."
            401 -> "\n\nHint: Invalid/disabled API key."
            403 -> "\n\nHint: The key is not allowed to access this model or region."
            404 -> "\n\nHint: Model or endpoint not found. Check model ID."
            408, 429 -> "\n\nHint: Rate limit/timeout. Try again or shorten input."
            413 -> "\n\nHint: Request too large. Your input/history may be too long."
            in 500..599 -> "\n\nHint: Server error/overload. Retry later."
            else -> ""
        }
        builder.append(hint)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${e.provider} API error")
            .setMessage(builder.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showNetworkErrorDialog(e: ApiNetworkException) {
        val cause = e.cause
        val causeName = cause?.javaClass?.simpleName ?: "IOException"
        val hint = when (cause) {
            is UnknownHostException -> "DNS/No internet. Check your connection or emulator proxy/VPN."
            is SocketTimeoutException -> "Network timeout. Try again or shorten input."
            else -> "Network failure."
        }
        val msg = buildString {
            append("Provider: ${e.provider}\n")
            append("Model: $currentModelName\n")
            append("Cause: $causeName\n")
            append("Endpoint: ${e.url}\n\n")
            append("Message: ${cause?.message ?: "No message"}\n\n")
            append("Hint: $hint")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${e.provider} network error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showModelUnavailableDialog(prettyName: String, details: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("$prettyName not available")
            .setMessage(
                "$prettyName is not available to your API key or region.\n\n" +
                        "Details: $details\n\n" +
                        "What you can do:\n" +
                        "• Verify model access in provider console\n" +
                        "• Ensure billing/quota is configured\n" +
                        "• Try a different account or model"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showGenericErrorDialog(title: String, details: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    // ====== UI helpers ======

    private fun speak(text: String) {
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        val params = Bundle()
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun appendToConversation(speaker: String, text: String) {
        val color = when (speaker.lowercase(Locale.US)) {
            "you" -> "#1E88E5"
            "assistant" -> "#43A047"
            "system" -> "#8E24AA"
            "error" -> "#E53935"
            else -> "#212121"
        }
        val safeText = text.replace("<", "&lt;").replace(">", "&gt;")
        // Add extra blank line between messages for better separation
        val entry = "<b><font color='$color'>$speaker</font>:</b> $safeText<br/><br/><br/>"
        val current: Spanned = binding.conversationLog.text as? Spanned
            ?: Html.fromHtml("", Html.FROM_HTML_MODE_COMPACT)
        val updated = Html.fromHtml(current.toString() + entry, Html.FROM_HTML_MODE_COMPACT)
        binding.conversationLog.text = updated
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setLoading(isLoading: Boolean) {
        val enableInputs = !isLoading && !isListening
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.speakButton.isEnabled = enableInputs

        // Enable/disable model radios
        binding.radioGeminiFlash.isEnabled = !isLoading
        binding.radioGeminiPro.isEnabled = !isLoading
        binding.radioGpt5.isEnabled = !isLoading
        binding.radioGpt5Mini.isEnabled = !isLoading

        binding.stopButton.isEnabled = !isLoading || isListening || isSpeaking
        binding.newRecordingButton.isEnabled = !isLoading && isSpeaking

        binding.geminiApiKeyInput.isEnabled = enableInputs
        binding.openaiApiKeyInput.isEnabled = enableInputs
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
                Log.e("TTS", "Language not supported")
                Toast.makeText(context, "TTS Language not supported.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("TTS", "Initialization failed")
            Toast.makeText(context, getString(R.string.error_tts_init), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { textToSpeech.stop(); textToSpeech.shutdown() } catch (_: Exception) {}
        try { speechRecognizer.destroy() } catch (_: Exception) {}
        cancelOngoingCall()
        _binding = null
    }
}