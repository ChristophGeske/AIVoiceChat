package com.example.advancedvoice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SentenceTurnEnginePipelineTest {

    private class FakeEngine(
        uiScope: CoroutineScope,
        private val queue: ArrayDeque<String?>,
        private val onFirst: (String) -> Unit,
        private val onRemaining: (List<String>) -> Unit,
        private val onFinish: () -> Unit,
        private val onSystemMsg: (String) -> Unit,
        private val onErrorMsg: (String) -> Unit
    ) : SentenceTurnEngine(
        uiScope = uiScope,
        http = OkHttpClient(),
        geminiKeyProvider = { "fake" },
        openAiKeyProvider = { "fake" },
        onStreamDelta = { /* Not used in this test */ },
        onStreamSentence = { /* Not used in this test */ },
        onFirstSentence = onFirst,
        onRemainingSentences = onRemaining,
        onTurnFinish = onFinish,
        onSystem = onSystemMsg,
        onError = onErrorMsg,
        ioDispatcher = Dispatchers.Default
    ) {
        var calls = 0
            private set

        // Override the actual network call method used by the two-phase logic
        override fun geminiGeneratePlain(modelName: String, prompt: String, maxTokens: Int, temperature: Double): String? {
            calls++
            return queue.pollFirst()
        }
    }

    @Test
    fun phase1_and_phase2_json_happy_path() {
        val firstOut = Collections.synchronizedList(mutableListOf<String>())
        val remainingOut = Collections.synchronizedList(mutableListOf<List<String>>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val finished = CountDownLatch(1)

        val q = ArrayDeque<String?>().apply {
            add("```json\n{\"first_sentence\":\"Obama was the 44th president of the United States.\"}\n```")
            add("{\"sentences\":[\"He served two terms.\",\"He was born in Hawaii.\",\"He won a Nobel Peace Prize.\",\"He enjoys basketball.\"]}")
        }

        val engine = FakeEngine(
            uiScope = CoroutineScope(Dispatchers.Default),
            queue = q,
            onFirst = { firstOut.add(it) },
            onRemaining = { remainingOut.add(it) },
            onFinish = { finished.countDown() },
            onSystemMsg = { /* ignore */ },
            onErrorMsg = { errors.add(it) }
        )

        engine.setMaxSentences(5) // 1 + 4
        engine.startTurn("tell me about Obama", "gemini-2.5-flash")

        assertTrue("Timed out waiting for finish", finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, firstOut.size)
        assertEquals("Obama was the 44th president of the United States.", firstOut[0])
        assertEquals(1, remainingOut.size)
        assertEquals(4, remainingOut[0].size)
        assertTrue("There should be no errors", errors.isEmpty())
        assertEquals(2, engine.calls) // phase 1 + phase 2
    }

    @Test
    fun phase2_blank_is_okay_no_error() {
        val firstOut = Collections.synchronizedList(mutableListOf<String>())
        val remainingOut = Collections.synchronizedList(mutableListOf<List<String>>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val finished = CountDownLatch(1)

        val q = ArrayDeque<String?>().apply {
            add("{\"first_sentence\":\"This is the very first sentence only.\"}")
            add("") // blank second phase allowed
        }

        val engine = FakeEngine(
            uiScope = CoroutineScope(Dispatchers.Default),
            queue = q,
            onFirst = { firstOut.add(it) },
            onRemaining = { remainingOut.add(it) },
            onFinish = { finished.countDown() },
            onSystemMsg = { },
            onErrorMsg = { errors.add(it) }
        )

        engine.setMaxSentences(3)
        engine.startTurn("topic", "gemini-2.5-flash")

        assertTrue("Timed out waiting for finish", finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, firstOut.size)
        assertTrue("Remaining sentences should be empty", remainingOut.isEmpty())
        assertTrue("There should be no errors", errors.isEmpty())
        assertEquals(2, engine.calls) // Phase 1 (succeeds) + Phase 2 (blank)
    }

    @Test
    fun phase1_blank_falls_back_and_completes_without_error() {
        val firstOut = Collections.synchronizedList(mutableListOf<String>())
        val remainingOut = Collections.synchronizedList(mutableListOf<List<String>>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val finished = CountDownLatch(1)

        // Multiple blanks to simulate retries failing for Phase 1
        val q = ArrayDeque<String?>().apply {
            add("") // Fails initial prompt
            add("") // Fails retry prompt
            add("{\"sentences\":[\"This is from phase two.\"]}") // Phase 2 succeeds
        }

        val engine = FakeEngine(
            uiScope = CoroutineScope(Dispatchers.Default),
            queue = q,
            onFirst = { firstOut.add(it) },
            onRemaining = { remainingOut.add(it) },
            onFinish = { finished.countDown() },
            onSystemMsg = { },
            onErrorMsg = { errors.add(it) }
        )

        engine.setMaxSentences(3)
        engine.startTurn("topic", "gemini-2.5-flash")

        assertTrue("Timed out waiting for finish", finished.await(5, TimeUnit.SECONDS))
        assertTrue("First sentence output should be empty", firstOut.isEmpty())
        assertEquals("Remaining sentences should have one entry", 1, remainingOut.size)
        assertEquals("Should get the sentence from phase 2", "This is from phase two.", remainingOut[0][0])
        assertTrue("No error should be reported for failed Phase 1", errors.isEmpty())
        assertEquals(3, engine.calls) // Phase 1 initial + Phase 1 retry + Phase 2
    }
}