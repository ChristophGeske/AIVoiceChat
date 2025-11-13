package com.example.advancedvoice.domain.engine

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class SentenceTurnEngineTest {

    private lateinit var testScope: TestScope
    private lateinit var http: OkHttpClient
    private lateinit var callbacks: SentenceTurnEngine.Callbacks
    private lateinit var engine: SentenceTurnEngine

    private val callbackLogs = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        testScope = TestScope(StandardTestDispatcher())
        http = mockk(relaxed = true)

        callbackLogs.clear()

        callbacks = SentenceTurnEngine.Callbacks(
            onStreamDelta = { callbackLogs.add("delta:$it") },
            onStreamSentence = { callbackLogs.add("sentence:$it") },
            onFirstSentence = { text, sources -> callbackLogs.add("first:$text (sources=${sources.size})") },
            onRemainingSentences = { sentences, sources -> callbackLogs.add("remaining:${sentences.size} (sources=${sources.size})") },
            onFinalResponse = { text, sources -> callbackLogs.add("final:${text.length} (sources=${sources.size})") },
            onTurnFinish = { callbackLogs.add("finish") },
            onSystem = { callbackLogs.add("system:$it") },
            onError = { callbackLogs.add("error:$it") }
        )

        engine = SentenceTurnEngine(
            uiScope = testScope,
            http = http,
            geminiKeyProvider = { "test-gemini-key" },
            openAiKeyProvider = { "test-openai-key" },
            systemPromptProvider = { "You are a test assistant" },
            callbacks = callbacks
        )
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    // ========================================
    // HISTORY MANAGEMENT TESTS
    // ========================================

    @Test
    fun `startTurn adds user message to history`() = testScope.runTest {
        // When: Start turn
        engine.startTurn("Hello", "gemini-2.0-flash-exp")
        advanceUntilIdle()

        // Then: History should contain user message
        val history = engine.getHistorySnapshot()
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hello", history[0].text)
    }

    @Test
    fun `replaceLastUserMessage replaces existing user message`() = testScope.runTest {
        // Given: History with user message
        engine.startTurn("First message", "gemini-2.0-flash-exp")
        advanceUntilIdle()

        // When: Replace last user message
        engine.replaceLastUserMessage("Second message")

        // Then: Should replace
        val history = engine.getHistorySnapshot()
        assertEquals(1, history.size)
        assertEquals("Second message", history[0].text)
    }

    @Test
    fun `replaceLastUserMessage handles assistant response in history`() = testScope.runTest {
        // Given: History with user -> assistant
        engine.seedHistory(listOf(
            SentenceTurnEngine.Msg("user", "Hello"),
            SentenceTurnEngine.Msg("assistant", "Hi there")
        ))

        // When: Replace last user message
        engine.replaceLastUserMessage("Updated hello")

        // Then: Should replace the user message at correct position
        val history = engine.getHistorySnapshot()
        assertEquals(2, history.size)
        assertEquals("Updated hello", history[0].text)
        assertEquals("Hi there", history[1].text)
    }

    @Test
    fun `replaceLastUserMessage adds message if no user exists`() = testScope.runTest {
        // Given: Empty history
        engine.clearHistory()

        // When: Replace (no user exists)
        engine.replaceLastUserMessage("New message")

        // Then: Should add new message
        val history = engine.getHistorySnapshot()
        assertEquals(1, history.size)
        assertEquals("New message", history[0].text)
    }

    @Test
    fun `injectAssistantDraft adds to history`() = testScope.runTest {
        // Given: User message exists
        engine.startTurn("Hello", "gemini-2.0-flash-exp")
        advanceUntilIdle()

        // When: Inject draft
        engine.injectAssistantDraft("Partial response")

        // Then: Should add assistant message
        val history = engine.getHistorySnapshot()
        assertEquals(2, history.size)
        assertEquals("assistant", history[1].role)
        assertEquals("Partial response", history[1].text)
    }

    @Test
    fun `injectAssistantDraft updates existing assistant message`() = testScope.runTest {
        // Given: Existing assistant draft
        engine.startTurn("Hello", "gemini-2.0-flash-exp")
        engine.injectAssistantDraft("First draft")

        // When: Inject another draft
        engine.injectAssistantDraft("Updated draft")

        // Then: Should update, not add new
        val history = engine.getHistorySnapshot()
        assertEquals(2, history.size)
        assertEquals("Updated draft", history[1].text)
    }

    // ========================================
    // LIFECYCLE TESTS
    // ========================================

    @Test
    fun `engine becomes active when turn starts`() = testScope.runTest {
        // Given: Idle
        assertFalse(engine.isActive())

        // When: Start turn
        engine.startTurn("Test", "gemini-2.0-flash-exp")

        // Then: Should be active immediately
        assertTrue(engine.isActive())
    }

    @Test
    fun `abort stops engine and marks inactive`() = testScope.runTest {
        // Given: Active turn
        engine.startTurn("Test", "gemini-2.0-flash-exp")
        assertTrue(engine.isActive())

        // When: Abort
        engine.abort(silent = false)
        advanceUntilIdle()

        // Then: Should be inactive
        assertFalse(engine.isActive())
        assertTrue(callbackLogs.any { it.startsWith("system:") })
        assertTrue(callbackLogs.contains("finish"))
    }

    @Test
    fun `abort with silent=true skips system message`() = testScope.runTest {
        // Given: Active turn
        engine.startTurn("Test", "gemini-2.0-flash-exp")

        // When: Silent abort
        engine.abort(silent = true)
        advanceUntilIdle()

        // Then: No system message
        assertFalse(callbackLogs.any { it.startsWith("system:") })
        assertTrue(callbackLogs.contains("finish"))
        assertFalse(engine.isActive())
    }

    @Test
    fun `startTurn while active aborts previous turn`() = testScope.runTest {
        // Given: Active turn
        engine.startTurn("First", "gemini-2.0-flash-exp")
        assertTrue(engine.isActive())
        val firstHistorySize = engine.getHistorySnapshot().size

        // When: Start second turn
        callbackLogs.clear()
        engine.startTurn("Second", "gemini-2.0-flash-exp")
        advanceUntilIdle()

        // Then: Should abort first and start second
        assertTrue(engine.isActive())
        assertEquals(firstHistorySize + 1, engine.getHistorySnapshot().size)
    }

    // ========================================
    // CONFIGURATION TESTS
    // ========================================

    @Test
    fun `setFasterFirst changes configuration`() = testScope.runTest {
        // When: Enable faster first
        engine.setFasterFirst(true)

        // Then: Should not crash (internal state changed)
        engine.startTurn("Test", "gemini-2.0-flash-exp")
        assertTrue(engine.isActive())
    }

    @Test
    fun `setMaxSentences accepts valid range`() = testScope.runTest {
        // When: Set valid values
        engine.setMaxSentences(1)
        engine.setMaxSentences(5)
        engine.setMaxSentences(10)

        // Then: Should not crash
        engine.startTurn("Test", "gemini-2.0-flash-exp")
        assertTrue(engine.isActive())
    }

    @Test
    fun `setMaxSentences clamps to valid range`() = testScope.runTest {
        // When: Set invalid values (should be clamped to 1-10)
        engine.setMaxSentences(-5)
        engine.setMaxSentences(100)

        // Then: Should not crash (clamped internally)
        engine.startTurn("Test", "gemini-2.0-flash-exp")
        assertTrue(engine.isActive())
    }

    @Test
    fun `seedHistory replaces current history`() = testScope.runTest {
        // Given: Existing history
        engine.startTurn("Old", "gemini-2.0-flash-exp")
        advanceUntilIdle()

        // When: Seed new history
        val newHistory = listOf(
            SentenceTurnEngine.Msg("user", "New 1"),
            SentenceTurnEngine.Msg("assistant", "New 2")
        )
        engine.seedHistory(newHistory)

        // Then: Should replace completely
        val history = engine.getHistorySnapshot()
        assertEquals(2, history.size)
        assertEquals("New 1", history[0].text)
        assertEquals("New 2", history[1].text)
    }

    @Test
    fun `clearHistory removes all messages`() = testScope.runTest {
        // Given: History with messages
        engine.startTurn("Test", "gemini-2.0-flash-exp")
        advanceUntilIdle()
        assertTrue(engine.getHistorySnapshot().isNotEmpty())

        // When: Clear
        engine.clearHistory()

        // Then: Should be empty
        assertTrue(engine.getHistorySnapshot().isEmpty())
    }

    @Test
    fun `getHistorySnapshot returns defensive copy`() = testScope.runTest {
        // Given: History
        engine.startTurn("Test", "gemini-2.0-flash-exp")
        val snapshot1 = engine.getHistorySnapshot()

        // When: Modify snapshot
        engine.startTurn("Another", "gemini-2.0-flash-exp")
        val snapshot2 = engine.getHistorySnapshot()

        // Then: Snapshots should be independent
        assertEquals(1, snapshot1.size)
        assertEquals(2, snapshot2.size)
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    fun `startTurn with blank text does nothing`() = testScope.runTest {
        // When: Start with blank text
        engine.startTurn("", "gemini-2.0-flash-exp")
        engine.startTurn("   ", "gemini-2.0-flash-exp")

        // Then: Should not add to history or become active
        assertTrue(engine.getHistorySnapshot().isEmpty())
        assertFalse(engine.isActive())
    }

    @Test
    fun `abort while idle is safe`() = testScope.runTest {
        // When: Abort while not active
        engine.abort(silent = false)
        advanceUntilIdle()

        // Then: Should not crash
        assertFalse(engine.isActive())
    }

    @Test
    fun `startTurnWithCurrentHistory without history handles gracefully`() = testScope.runTest {
        // Given: Empty history
        engine.clearHistory()

        // When: Start with current history
        engine.startTurnWithCurrentHistory("gemini-2.0-flash-exp")

        // Then: Should still become active
        assertTrue(engine.isActive())
    }
}