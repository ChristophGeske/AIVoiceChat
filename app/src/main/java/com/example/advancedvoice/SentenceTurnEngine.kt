package com.example.advancedvoice

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

open class SentenceTurnEngine(
    private val uiScope: CoroutineScope,
    private val http: OkHttpClient,
    private val geminiKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val systemPromptProvider: () -> String,
    private val onStreamDelta: (String) -> Unit,
    private val onStreamSentence: (String) -> Unit,
    private val onFirstSentence: (String) -> Unit,
    private val onRemainingSentences: (List<String>) -> Unit,
    private val onTurnFinish: () -> Unit,
    private val onSystem: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "SentenceTurnEngine"
        private const val MAX_RETRIES = 1
        private const val MIN_REQUEST_INTERVAL_MS = 1200L
        private const val MIN_SENTENCE_CHARS = 20
        private const val PROGRESS_TICK_MS = 250L
        private const val LOG_DELTA_SAMPLES = 8
    }

    protected data class Msg(val role: String, val text: String)

    private val history = ArrayList<Msg>()
    private var maxSentencesPerTurn = 4
    private var model = "gemini-pro-latest"
    private var active = false
    private var lastRequestTimeMs = 0L
    private val full = StringBuilder()
    private var firstEmitted = false
    private var firstSentence: String? = null
    private var receivedAny = false
    private var emitCursor = 0
    private var deliveredSentences = 0
    @Volatile private var stopStreamingEarly = false
    private var sentenceCapReached = false
    private var streamStartMs = 0L
    private var firstDeltaAtMs = -1L
    private var deltaCount = 0
    private var charCount = 0
    private var loggedDeltaSamples = 0
    private var progressJob: Job? = null
    private var fasterFirst: Boolean = false

    private var geminiNonStreamFallbackUsed = false
    private var geminiSingleChunkStreamDetected = false
    private val overrideCapOnGeminiFallback = true
    private var applyCapToPromptThisTurn = false

    fun setFasterFirst(enabled: Boolean) {
        fasterFirst = enabled
        Log.d(TAG, "FasterFirst set to: $fasterFirst")
    }

    fun setMaxSentences(n: Int) {
        maxSentencesPerTurn = n.coerceIn(1, 10)
        Log.d(TAG, "Max sentences set to: $maxSentencesPerTurn")
    }

    fun isActive(): Boolean = active

    fun abort() {
        active = false
        stopStreamingEarly = true
        cancelProgress()
        Log.d(TAG, "Generation aborted by user")
        onSystem("Generation interrupted.")
    }

    fun startTurn(userText: String, modelName: String) {
        if (userText.isBlank()) {
            Log.w(TAG, "startTurn called with blank text")
            return
        }
        model = modelName
        active = true
        full.clear()
        firstEmitted = false
        firstSentence = null
        receivedAny = false
        emitCursor = 0
        deliveredSentences = 0
        stopStreamingEarly = false
        sentenceCapReached = false
        streamStartMs = 0L
        firstDeltaAtMs = -1L
        deltaCount = 0
        charCount = 0
        loggedDeltaSamples = 0
        geminiNonStreamFallbackUsed = false
        geminiSingleChunkStreamDetected = false
        applyCapToPromptThisTurn = false
        cancelProgress()
        addUser(userText)
        Log.i(TAG, ">>>>> TURN START user='${userText.take(120)}...' model=$model fasterFirst=$fasterFirst")
        uiScope.launch(ioDispatcher) {
            throttleIfNeeded()
            callStreamOrTwoPhaseWithRetry()
        }
    }

    private suspend fun callStreamOrTwoPhaseWithRetry() {
        var attempt = 0
        var lastErr: String? = null
        val startClock = System.currentTimeMillis()
        val isGpt = model.startsWith("gpt-", ignoreCase = true)
        val isGemini = model.startsWith("gemini-", ignoreCase = true) || model.contains("gemini", ignoreCase = true)

        val useTwoPhase = fasterFirst && isGemini
        applyCapToPromptThisTurn = !useTwoPhase
        if (applyCapToPromptThisTurn) {
            Log.i(TAG, "[CAP-PROMPT] Non-fast mode: will append cap to last user message (max=$maxSentencesPerTurn).")
        }

        Log.d(TAG, "[STRATEGY-SELECT] model=$model isGpt=$isGpt isGemini=$isGemini fasterFirst=$fasterFirst → useTwoPhase=$useTwoPhase")

        val systemPrompt = systemPromptProvider()

        while (attempt <= MAX_RETRIES && active) {
            try {
                receivedAny = false
                streamStartMs = System.currentTimeMillis()
                Log.d(TAG, "STREAM OPEN: attempt ${attempt + 1}/${MAX_RETRIES + 1}, model=$model, fasterFirst=$fasterFirst")

                if (useTwoPhase) {
                    Log.i(TAG, "STRATEGY: Using Two-Phase for $model (Gemini + fasterFirst enabled)")
                    geminiTwoPhasePromptOnly()
                    return
                } else {
                    Log.i(TAG, "STRATEGY: Using Streaming for model $model")
                    startProgressTicker()
                    if (isGpt) {
                        streamOpenAi(systemPrompt)
                    } else if (isGemini) {
                        val ok = streamGemini(systemPrompt)
                        if (!ok) {
                            Log.w(TAG, "Gemini streaming not available; will fallback to non-stream")
                        }
                    } else {
                        throw RuntimeException("Unknown model type: $model")
                    }

                    if (!receivedAny) {
                        Log.w(TAG, "No tokens received from stream; falling back to non-stream")
                        val text = if (isGpt) {
                            callOpenAiNonStream(systemPrompt)
                        } else {
                            geminiNonStreamFallbackUsed = true
                            val t = callGeminiNonStream(systemPrompt)
                            Log.i(TAG, "[FALLBACK] Gemini non-stream used. length=${t.length}")
                            t
                        }
                        if (text.isNotBlank()) onChunk(text)
                    } else if (isGemini && deltaCount == 1) {
                        geminiSingleChunkStreamDetected = true
                        Log.i(TAG, "[DIAG] Gemini stream delivered a single large chunk. Treat as 'single-chunk stream'.")
                    }

                    cancelProgress()
                    onStreamEnd()
                    return
                }

            } catch (e: Exception) {
                lastErr = e.message ?: e.toString()
                Log.e(TAG, "Request failed: $lastErr", e)
                attempt++
                cancelProgress()
                if (attempt <= MAX_RETRIES && active) {
                    val wait = 500L
                    val elapsed = System.currentTimeMillis() - startClock
                    if (elapsed >= 2000L) {
                        uiScope.launch { onSystem("Retrying in ${wait / 1000}s... ($attempt/${MAX_RETRIES + 1})") }
                    }
                    delay(wait)
                } else {
                    break
                }
            }
        }
        if (!active) {
            Log.d(TAG, "Processing loop ended because 'active' is false.")
            return
        }
        active = false
        uiScope.launch {
            onError(lastErr ?: "Request failed")
            onTurnFinish()
        }
    }

    private fun lastUserMessage(): String = history.lastOrNull { it.role == "user" }?.text.orEmpty()

    private fun geminiTwoPhasePromptOnly() {
        val userMsg = lastUserMessage()
        val effModel = effectiveGeminiModel(model)
        val systemPrompt = systemPromptProvider() // FIXED: Get the system prompt
        Log.i(TAG, "[GEMINI-2PHASE] START (model=$model → $effModel) user='${userMsg.take(120)}...'")
        Log.i(TAG, "[GEMINI-2PHASE] ---> PHASE 1: Requesting first sentence...")

        val simpleInstr = """
        Answer the user's previous question with ONLY the first sentence of your response.
        The sentence must be complete (ends with ., !, or ?).
        Plain text only. No JSON. No extra text.
    """.trimIndent()

        // FIXED: Pass the system prompt to the API call
        val response1 = geminiGenerateWithHistory(effModel, systemPrompt, simpleInstr, temperature = 0.4)
        Log.d(TAG, "[GEMINI-2PHASE] Phase 1 raw response: '${response1?.first?.take(200)}'")

        var first: String? = null
        val finishReason = response1?.second
        if (finishReason == "MAX_TOKENS") {
            Log.e(TAG, "[GEMINI-2PHASE] Phase 1 hit MAX_TOKENS!")
        }
        val text1 = response1?.first
        if (!text1.isNullOrBlank()) {
            first = SentenceSplitter.extractFirstSentence(text1).first.takeIf { it.isNotBlank() }
        }

        if (first.isNullOrBlank()) {
            Log.w(TAG, "[GEMINI-2PHASE] Simple prompt failed, falling back to JSON prompt...")
            val jsonInstr = """
            Answer the user's previous question. Return ONLY a JSON object exactly like:
            {"first_sentence":"..."}
            Rules:
            - "first_sentence" must be exactly one complete sentence (ends with ., !, or ?)
            - Do not include any other text, keys, or explanation.
        """.trimIndent()
            // FIXED: Pass the system prompt to the API call
            val response2 = geminiGenerateWithHistory(effModel, systemPrompt, jsonInstr, temperature = 0.4)
            Log.d(TAG, "[GEMINI-2PHASE] JSON fallback raw response: '${response2?.first?.take(200)}'")

            if (response2?.second == "MAX_TOKENS") {
                Log.e(TAG, "[GEMINI-2PHASE] JSON fallback also hit MAX_TOKENS!")
            }
            val text2 = response2?.first
            if (!text2.isNullOrBlank()) {
                first = parseFirstSentenceFromJson(text2)
                if (first.isNullOrBlank()) {
                    first = SentenceSplitter.extractFirstSentence(text2).first.takeIf { it.isNotBlank() }
                }
            }
        }

        if (!active) {
            Log.w(TAG, "[GEMINI-2PHASE] Aborted during Phase 1")
            return
        }

        if (!first.isNullOrBlank()) {
            firstEmitted = true
            firstSentence = first
            deliveredSentences = 1
            addAssistant(first)
            Log.i(TAG, "[GEMINI-2PHASE] <--- PHASE 1 SUCCESS (len=${first.length}). Calling onFirstSentence().")
            uiScope.launch { onFirstSentence(first) }
        } else {
            Log.e(TAG, "[GEMINI-2PHASE] <--- PHASE 1 FAILED to get a sentence after all attempts.")
        }

        val remainingBudget = (maxSentencesPerTurn - deliveredSentences).coerceAtLeast(0)
        if (remainingBudget <= 0) {
            Log.i(TAG, "[GEMINI-2PHASE] No remaining sentence budget, finishing turn.")
            finishTurn()
            return
        }

        Log.i(TAG, "[GEMINI-2PHASE] ---> PHASE 2: Requesting $remainingBudget remaining sentences...")
        val contInstr = """
        Continue your answer with the next part.
        Return ONLY a JSON object exactly like:
        {"sentences":["...", "..."]}
        Rules:
        - Up to $remainingBudget continuation sentence${if (remainingBudget > 1) "s" else ""}
        - Do not repeat the first sentence.
        - Do not include any other text, keys, or explanation.
    """.trimIndent()

        // FIXED: Pass the system prompt to the API call
        val response3 = geminiGenerateWithHistory(effModel, systemPrompt, contInstr, temperature = 0.8)
        Log.d(TAG, "[GEMINI-2PHASE] Phase 2 raw response: '${response3?.first?.take(200)}'")

        if (response3?.second == "MAX_TOKENS") {
            Log.w(TAG, "[GEMINI-2PHASE] Phase 2 hit MAX_TOKENS (truncated response)")
        }

        val contOut = response3?.first
        var contList = parseSentencesArrayFromJson(contOut.orEmpty())
        if (contList.isEmpty() && !contOut.isNullOrBlank()) {
            Log.w(TAG, "[GEMINI-2PHASE] JSON parse failed, trying plain text fallback for Phase 2.")
            contList = SentenceSplitter.splitIntoSentences(contOut)
        }

        if (!active) {
            Log.w(TAG, "[GEMINI-2PHASE] Aborted during Phase 2")
            return
        }

        val allowed = contList.take(remainingBudget)
        if (allowed.isNotEmpty()) {
            deliveredSentences += allowed.size
            Log.i(TAG, "[GEMINI-2PHASE] <--- PHASE 2 SUCCESS. Calling onRemainingSentences() with ${allowed.size} sentences.")
            uiScope.launch { onRemainingSentences(allowed) }
        } else {
            Log.w(TAG, "[GEMINI-2PHASE] <--- PHASE 2 returned no new sentences.")
        }

        val fullResponse = listOfNotNull(first?.takeIf { it.isNotBlank() }).plus(allowed).joinToString(" ").trim()
        if (fullResponse.isNotBlank()) {
            updateLastAssistantMessage(fullResponse)
        }

        Log.i(TAG, "[GEMINI-2PHASE] All phases complete. Total sentences: $deliveredSentences")
        finishTurn()
    }

    // FIXED: The method now accepts the system prompt.
    protected open fun geminiGenerateWithHistory(
        modelName: String,
        systemPrompt: String,
        instruction: String,
        temperature: Double
    ): Pair<String, String?>? {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isBlank()) {
            Log.e(TAG, "[GEMINI-REQ] API key is blank.")
            return null
        }
        val url = "https://generativelanguage.googleapis.com/v1/models/$modelName:generateContent?key=$apiKey"

        val contents = JSONArray().apply {
            // FIXED: Inject the system prompt at the beginning of the conversation.
            // This is the standard way to provide system instructions to Gemini models.
            if (systemPrompt.isNotBlank()) {
                Log.d(TAG, "[GEMINI-REQ] Injecting system prompt.")
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
                })
                put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", "Understood. I will follow your instructions.") }) })
                })
            }

            for (m in history) {
                val role = if (m.role == "assistant") "model" else "user"
                put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", m.text) }) })
                })
            }
            // Add instruction as a new user message
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", instruction) }) })
            })
        }

        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
            })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        Log.d(TAG, "[GEMINI-REQ] model=$modelName instructionPreview='${instruction.replace("\n", " ").take(80)}...' temp=$temperature historySize=${history.size} (no token limit)")
        val req = Request.Builder().url(url).post(body).build()

        return try {
            http.newCall(req).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                Log.d(TAG, "[GEMINI-RESP] HTTP ${resp.code} length=${payload.length} preview='${payload.replace("\n", " ").take(120)}'")

                if (!resp.isSuccessful) {
                    val parsed = parseError(payload)
                    Log.w(TAG, "[GEMINI-RESP] ERROR ${resp.code}: ${parsed ?: payload.take(300)}")
                    return null
                }

                val root = JSONObject(payload)
                val finishReason = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optString("finishReason")

                Log.d(TAG, "[GEMINI-RESP] finishReason=$finishReason")

                val text = extractGeminiText(payload).trim()
                if (text.isEmpty()) null else (text to finishReason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[GEMINI-REQ] network error: ${e.message}", e)
            null
        }
    }

    private fun parseFirstSentenceFromJson(text: String): String? {
        if (text.isBlank()) return null
        val json = findFirstJsonObject(text) ?: return null
        val v = json.optString("first_sentence", "").trim()
        return v.takeIf { it.isNotEmpty() }
    }

    private fun parseSentencesArrayFromJson(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val json = findFirstJsonObject(text) ?: return emptyList()
        val arr = json.optJSONArray("sentences") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) list.add(s)
        }
        return list
    }

    private fun findFirstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var i = start
        var depth = 0
        var inStr = false
        var esc = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val cand = text.substring(start, i + 1)
                            return try { JSONObject(cand) } catch (_: Exception) { null }
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun snapshotHistoryForSend(): List<Msg> {
        if (!applyCapToPromptThisTurn) return ArrayList(history)
        val idx = history.indexOfLast { it.role == "user" }
        if (idx < 0) return ArrayList(history)
        val augmented = ArrayList(history)
        val cap = "\n\nPlease limit your answer to at most $maxSentencesPerTurn complete sentences."
        val last = augmented[idx]
        augmented[idx] = Msg(last.role, last.text + cap)
        Log.i(TAG, "[CAP-PROMPT] Appended sentence cap to last user message (max=$maxSentencesPerTurn).")
        return augmented
    }

    private fun findSentenceEndInOriginal(buffer: CharSequence, startIdx: Int): Int {
        var i = startIdx
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '.' || c == '!' || c == '?') {
                val next = buffer.getOrNull(i + 1)
                val boundary = next == null || next.isWhitespace()
                if (boundary && (i - startIdx + 1) >= MIN_SENTENCE_CHARS) {
                    var end = i + 1
                    while (end < buffer.length && buffer[end].isWhitespace()) end++
                    return end
                }
            }
            i++
        }
        return -1
    }

    private fun startProgressTicker() {
        cancelProgress()
        progressJob = uiScope.launch(ioDispatcher) {
            while (isActive()) {
                delay(PROGRESS_TICK_MS)
                val elapsed = System.currentTimeMillis() - streamStartMs
                Log.d(TAG, "PROGRESS model=$model deltas=$deltaCount chars=$charCount elapsed=${elapsed}ms firstDeltaAt=${firstDeltaAtMs}ms capReached=$sentenceCapReached")
            }
        }
    }

    private fun cancelProgress() {
        try { progressJob?.cancel() } catch (_: Exception) {}
        progressJob = null
    }

    private fun onChunk(delta: String) {
        if (!active || delta.isEmpty() || stopStreamingEarly) return
        full.append(delta)
        receivedAny = true
        deltaCount++
        charCount += delta.length
        if (firstDeltaAtMs < 0) {
            firstDeltaAtMs = System.currentTimeMillis() - streamStartMs
            Log.d(TAG, "FIRST DELTA at ${firstDeltaAtMs}ms, len=${delta.length}")
        }
        if (loggedDeltaSamples < LOG_DELTA_SAMPLES) {
            val elapsed = System.currentTimeMillis() - streamStartMs
            Log.d(TAG, "DELTA #$deltaCount len=${delta.length} elapsed=${elapsed}ms sample='${delta.replace("\n", " ").take(80)}...'")
            loggedDeltaSamples++
        }
        uiScope.launch { onStreamDelta(delta) }

        if (!firstEmitted) {
            val candidate = tryExtractFirst(full.toString())
            if (!candidate.isNullOrEmpty()) {
                firstEmitted = true
                firstSentence = candidate
                val end = findSentenceEndInOriginal(full, 0)
                emitCursor = if (end > 0) end else min(candidate.length, full.length)
                deliveredSentences = 1
                val at = System.currentTimeMillis() - streamStartMs
                Log.i(TAG, "FIRST SENTENCE detected (len=${candidate.length}) at ${at}ms")
                addAssistant(candidate)
                uiScope.launch { onFirstSentence(candidate) }
            }
        }

        if (!sentenceCapReached) {
            emitMoreCompletedSentences()
            if (deliveredSentences >= maxSentencesPerTurn) {
                sentenceCapReached = true
                Log.i(TAG, "Sentence cap reached ($deliveredSentences/$maxSentencesPerTurn). Continuing to read to capture full text, but will not emit more sentences.")
            }
        }
    }

    private fun emitMoreCompletedSentences() {
        if (sentenceCapReached) return
        while (!stopStreamingEarly && emitCursor < full.length && deliveredSentences < maxSentencesPerTurn) {
            while (emitCursor < full.length &&
                (full[emitCursor].isWhitespace() || full[emitCursor] == '.' || full[emitCursor] == '!' || full[emitCursor] == '?')
            ) emitCursor++

            if (emitCursor >= full.length) break
            val remaining = full.substring(emitCursor)
            if (remaining.isBlank()) break

            val (first, _) = SentenceSplitter.extractFirstSentence(remaining)
            val s = first.trim()
            val terminated = s.endsWith('.') || s.endsWith('!') || s.endsWith('?')

            if (s.length >= MIN_SENTENCE_CHARS && terminated) {
                val terminator = s.last()
                var endIdx = -1
                for (i in (MIN_SENTENCE_CHARS - 1).coerceAtMost(remaining.lastIndex)..remaining.lastIndex) {
                    if (remaining[i] != terminator) continue
                    val nextIdx = i + 1
                    if (nextIdx >= remaining.length) { endIdx = nextIdx; break }
                    val nextCh = remaining[nextIdx]
                    if (nextCh.isWhitespace()) {
                        val after = remaining.getOrNull(nextIdx + 1)
                        val boundary = after?.let { it.isUpperCase() || it.isDigit() || it.isWhitespace() } ?: true
                        if (boundary) { endIdx = nextIdx; break }
                    }
                }
                if (endIdx > 0) {
                    emitCursor += endIdx
                    while (emitCursor < full.length && full[emitCursor].isWhitespace()) emitCursor++
                    deliveredSentences++
                    Log.d(TAG, "EMIT SENTENCE mid-stream len=${s.length} delivered=$deliveredSentences/$maxSentencesPerTurn")
                    uiScope.launch { onStreamSentence(s) }
                } else break
            } else break
        }
    }

    private fun onStreamEnd() {
        if (!active) return
        val all = full.toString().trim()
        val elapsed = System.currentTimeMillis() - streamStartMs
        Log.i(TAG, "STREAM END: receivedAny=$receivedAny deltas=$deltaCount chars=$charCount totalChars=${all.length} elapsed=${elapsed}ms delivered=$deliveredSentences/${maxSentencesPerTurn} capReached=$sentenceCapReached fallbackNonStream=$geminiNonStreamFallbackUsed singleChunk=$geminiSingleChunkStreamDetected")

        if (!sentenceCapReached) emitMoreCompletedSentences()

        if (!firstEmitted && all.isNotEmpty() && deliveredSentences < maxSentencesPerTurn) {
            val first = SentenceSplitter.extractFirstSentence(all).first.ifBlank { all }
            firstSentence = first
            firstEmitted = true
            emitCursor = min(first.length, full.length)
            deliveredSentences++
            addAssistant(first)
            Log.d(TAG, "EMIT SENTENCE at end len=${first.length} delivered=$deliveredSentences/$maxSentencesPerTurn")
            uiScope.launch { onFirstSentence(first) }
        }

        if (all.isEmpty()) {
            Log.e(TAG, "No content produced by model")
            uiScope.launch {
                onError("Empty response from model. Please try again.")
                onTurnFinish()
            }
            active = false
            return
        }

        updateLastAssistantMessage(all)

        val allSentences = SentenceSplitter.splitIntoSentences(all)
        val overflowCount = (allSentences.size - deliveredSentences).coerceAtLeast(0)
        Log.i(TAG, "[DIAG] totalSentences=${allSentences.size} deliveredSentences=$deliveredSentences overflow=$overflowCount")

        val shouldOverrideCap = overrideCapOnGeminiFallback && (geminiNonStreamFallbackUsed || geminiSingleChunkStreamDetected)
        if (sentenceCapReached && overflowCount > 0 && shouldOverrideCap) {
            val overflow = allSentences.drop(deliveredSentences)
            Log.i(TAG, "[OVERRIDE] Delivering overflow beyond cap due to Gemini fallback/single-chunk. overflowCount=${overflow.size}")
            deliveredSentences += overflow.size
            uiScope.launch { onRemainingSentences(overflow) }
        } else if (!sentenceCapReached) {
            val remaining = if (firstSentence != null && allSentences.isNotEmpty() && allSentences[0].trim() == firstSentence!!.trim()) {
                allSentences.drop(1)
            } else {
                val cleaned = firstSentence?.let { all.removePrefix(it).trim() }.orEmpty()
                if (cleaned.isEmpty()) emptyList() else SentenceSplitter.splitIntoSentences(cleaned)
            }
            val allowedLeft = (maxSentencesPerTurn - deliveredSentences).coerceAtLeast(0)
            val toUse = remaining.take(allowedLeft)
            if (toUse.isNotEmpty()) {
                deliveredSentences += toUse.size
                Log.d(TAG, "FINAL BATCH sentences=${toUse.size} totalDelivered=$deliveredSentences/${maxSentencesPerTurn}")
                uiScope.launch { onRemainingSentences(toUse) }
            }
        } else {
            Log.i(TAG, "[INFO] Cap reached and no override applied. Remaining sentences stored in history only.")
        }

        finishTurn()
    }

    private fun tryExtractFirst(text: String): String? {
        val (first, _) = SentenceSplitter.extractFirstSentence(text)
        val terminated = first.endsWith('.') || first.endsWith('!') || first.endsWith('?')
        return if (first.length >= MIN_SENTENCE_CHARS && terminated) first else null
    }

    private fun finishTurn() {
        if (!active) return
        active = false
        cancelProgress()
        Log.i(TAG, "<<<<< TURN FINISH")
        uiScope.launch { onTurnFinish() }
    }

    private fun openAiAllowsTemperature(model: String): Boolean = !model.startsWith("gpt-5", ignoreCase = true)

    protected open fun streamOpenAi(systemPrompt: String) {
        val apiKey = openAiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("OpenAI API key missing")
        val url = "https://api.openai.com/v1/chat/completions"
        val msgs = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            for (m in snapshotHistoryForSend()) {
                put(JSONObject().apply {
                    put("role", if (m.role == "assistant") "assistant" else "user")
                    put("content", m.text)
                })
            }
        }
        val bodyObj = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("stream", true)
        }
        if (openAiAllowsTemperature(model)) {
            bodyObj.put("temperature", 0.7)
        } else {
            Log.d(TAG, "OpenAI: omitting temperature for model=$model (default=1)")
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(bodyObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val payload = resp.body?.string().orEmpty()
                val parsed = parseError(payload)
                throw RuntimeException("OpenAI ${resp.code}: ${parsed ?: payload.take(300)}")
            }
            val source = resp.body?.source() ?: return
            readOpenAiSse(source)
        }
    }

    protected open fun callOpenAiNonStream(systemPrompt: String): String {
        val apiKey = openAiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("OpenAI API key missing")
        val url = "https://api.openai.com/v1/chat/completions"
        val msgs = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            for (m in snapshotHistoryForSend()) {
                put(JSONObject().apply {
                    put("role", if (m.role == "assistant") "assistant" else "user")
                    put("content", m.text)
                })
            }
        }
        val bodyObj = JSONObject().apply {
            put("model", model)
            put("messages", msgs)
        }
        if (openAiAllowsTemperature(model)) {
            bodyObj.put("temperature", 0.7)
        } else {
            Log.d(TAG, "OpenAI (non-stream): omitting temperature for model=$model")
        }
        val req = Request.Builder()
            .url(url)
            .post(bodyObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val parsed = parseError(payload)
                throw RuntimeException("OpenAI ${resp.code}: ${parsed ?: payload.take(300)}")
            }
            return JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
        }
    }

    private fun readOpenAiSse(source: BufferedSource) {
        while (active && !stopStreamingEarly) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            try {
                val obj = JSONObject(data)
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                val token = delta?.optString("content", null)
                if (!token.isNullOrEmpty()) onChunk(token)
                if (stopStreamingEarly) break
            } catch (_: Exception) {}
        }
    }

    protected open fun streamGemini(systemPrompt: String): Boolean {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("Gemini API key missing")
        val effModel = effectiveGeminiModel(model)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effModel:streamGenerateContent?key=$apiKey"
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            for (m in snapshotHistoryForSend()) {
                val role = if (m.role == "assistant") "model" else "user"
                put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", m.text) }) })
                })
            }
        }
        Log.d(TAG, "[GEMINI-STREAM] model=$model → $effModel")
        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
            })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val payload = resp.body?.string().orEmpty()
                Log.w(TAG, "Gemini stream HTTP ${resp.code}: ${payload.take(200)}")
                return false
            }
            val source = resp.body?.source() ?: return false
            val acc = StringBuilder()
            var sawAny = false
            while (active && !stopStreamingEarly) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val candidate = acc.toString() + trimmed
                val obj = try { JSONObject(candidate) } catch (_: Exception) {
                    acc.append(trimmed); continue
                }
                acc.clear()
                val parts = obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text", null)
                        if (!t.isNullOrEmpty()) {
                            onChunk(t)
                            sawAny = true
                        }
                    }
                }
                if (stopStreamingEarly) break
            }
            return sawAny
        }
    }

    private fun callGeminiNonStream(systemPrompt: String): String {
        val apiKey = geminiKeyProvider().trim()
        if (apiKey.isBlank()) throw RuntimeException("Gemini API key missing")
        val effModel = effectiveGeminiModel(model)
        val url = "https://generativelanguage.googleapis.com/v1/models/$effModel:generateContent?key=$apiKey"
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            for (m in snapshotHistoryForSend()) {
                val role = if (m.role == "assistant") "model" else "user"
                put(JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", m.text) }) })
                })
            }
        }
        Log.d(TAG, "[GEMINI-NONSTREAM] model=$model → $effModel")
        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
            })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val parsed = parseError(payload)
                throw RuntimeException("Gemini ${resp.code}: ${parsed ?: payload.take(300)}")
            }
            return extractGeminiText(payload)
        }
    }

    private fun extractGeminiText(payload: String): String {
        return try {
            val root = JSONObject(payload)
            root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { br ->
                if (br.isNotBlank()) return "Blocked: $br"
            }
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text")
                if (!t.isNullOrEmpty()) sb.append(t)
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Gemini text", e)
            ""
        }
    }

    private fun effectiveGeminiModel(name: String): String {
        val n = name.lowercase()
        val mapped = when (n) {
            "gemini-pro-latest", "gemini-2.5-pro-latest" -> "gemini-2.5-pro"
            "gemini-flash-latest", "gemini-2.5-flash-latest" -> "gemini-2.5-flash"
            else -> name
        }
        if (mapped != name) Log.i(TAG, "[MODEL-MAP] '$name' → '$mapped' for Google API compatibility")
        return mapped
    }

    private fun parseError(body: String): String? {
        return try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
    }

    private fun addUser(text: String) { history.add(Msg("user", text)); trimHistory() }
    private fun addAssistant(text: String) { history.add(Msg("assistant", text)); trimHistory() }
    private fun updateLastAssistantMessage(fullText: String) {
        if (history.lastOrNull()?.role == "assistant") {
            history[history.lastIndex] = Msg("assistant", fullText)
        } else {
            history.add(Msg("assistant", fullText))
        }
        trimHistory()
    }
    private fun trimHistory() {
        var count = history.count { it.role == "user" || it.role == "assistant" }
        while (count > 44 && history.isNotEmpty()) {
            val removed = history.removeAt(0)
            if (removed.role == "user" || removed.role == "assistant") count--
        }
    }
    private suspend fun throttleIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestTimeMs
        if (elapsed < MIN_REQUEST_INTERVAL_MS) delay(MIN_REQUEST_INTERVAL_MS - elapsed)
        lastRequestTimeMs = System.currentTimeMillis()
    }
}