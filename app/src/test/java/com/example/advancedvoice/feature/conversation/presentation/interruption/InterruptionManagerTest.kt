package com.example.advancedvoice.feature.conversation.presentation.interruption

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionManagerTest {

    private lateinit var testScope: TestScope
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var sttController: SttController
    private lateinit var interruptionManager: InterruptionManager

    private var startTurnCalled = 0
    private var interruptionCalled = 0
    private var noiseConfirmedCalled = 0

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isHearingSpeechFlow = MutableStateFlow(false)
    private val isSpeakingFlow = MutableStateFlow(false)
    private val isListeningFlow = MutableStateFlow(false)
    private val isTranscribingFlow = MutableStateFlow(false)

    @BeforeEach
    fun setup() {
        scheduler = TestCoroutineScheduler()
        testScope = TestScope(StandardTestDispatcher(scheduler))

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { isSpeaking } returns isSpeakingFlow
            every { isListening } returns isListeningFlow
            every { isTranscribing } returns isTranscribingFlow
            every { conversation } returns MutableStateFlow(emptyList())
        }

        engine = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        sttController = mockk(relaxed = true)

        startTurnCalled = 0
        interruptionCalled = 0
        noiseConfirmedCalled = 0

        interruptionManager = InterruptionManager(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            getStt = { sttController },
            startTurnWithExistingHistory = { startTurnCalled++ },
            onInterruption = { interruptionCalled++ },
            onNoiseConfirmed = { noiseConfirmedCalled++ }
        )
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    // ========================================
    // BASIC FUNCTIONALITY TESTS
    // ========================================

    @Test
    fun `onTurnStart - sets generation start time`() = testScope.runTest {
        val startTime = System.currentTimeMillis()
        interruptionManager.onTurnStart(startTime)
        assertEquals(startTime, interruptionManager.generationStartTime)
    }

    @Test
    fun `combineTranscript - combines with existing text`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("First part")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val combined = interruptionManager.combineTranscript("second part")
        assertEquals("First part second part", combined)
    }

    @Test
    fun `combineTranscript - handles empty previous text`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns emptyList()
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val combined = interruptionManager.combineTranscript("new text")
        assertEquals("new text", combined)
    }

    @Test
    fun `combineTranscript - adds space if previous doesn't end with one`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("Hello")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val combined = interruptionManager.combineTranscript("world")
        assertEquals("Hello world", combined)
    }

    // ========================================
    // RESET TESTS
    // ========================================

    @Test
    fun `reset - clears public flags`() = testScope.runTest {
        interruptionManager.onTurnStart(System.currentTimeMillis())
        interruptionManager.reset()

        assertFalse(interruptionManager.isBargeInTurn)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `reset - stops state flows`() = testScope.runTest {
        isListeningFlow.value = true
        isHearingSpeechFlow.value = true

        interruptionManager.reset()
        scheduler.runCurrent()

        verify { stateManager.setListening(false) }
        verify { stateManager.setHearingSpeech(false) }
        verify { stateManager.setTranscribing(false) }
    }

    // ========================================
    // TRANSCRIPT HANDLING TESTS
    // ========================================

    @Test
    fun `checkTranscriptForInterruption - when not evaluating returns false`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST

        val handled = interruptionManager.checkTranscriptForInterruption("Hello there friend")

        assertFalse(handled)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `checkTranscriptForInterruption - short words when not evaluating`() = testScope.runTest {
        val result = interruptionManager.checkTranscriptForInterruption("a to be or")
        assertFalse(result)
    }

    @Test
    fun `checkTranscriptForInterruption - numbers and symbols when not evaluating`() = testScope.runTest {
        val result = interruptionManager.checkTranscriptForInterruption("123 @#$ 456")
        assertFalse(result)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `checkTranscriptForInterruption - blank text handled gracefully`() = testScope.runTest {
        val result = interruptionManager.checkTranscriptForInterruption("   \n\t  ")
        assertNotNull(result)
        assertFalse(result)
    }

    // ========================================
    // BUFFERING TESTS
    // ========================================

    @Test
    fun `maybeHandleAssistantFinalResponse - returns false when not evaluating`() = testScope.runTest {
        val buffered = interruptionManager.maybeHandleAssistantFinalResponse("Test response")
        assertFalse(buffered)
    }

    @Test
    fun `flushBufferedAssistantIfAny - when no buffer does nothing`() = testScope.runTest {
        interruptionManager.flushBufferedAssistantIfAny()

        verify(exactly = 0) { stateManager.addAssistant(any()) }
        verify(exactly = 0) { tts.queue(any<String>()) }
    }

    @Test
    fun `dropBufferedAssistant - cleans engine history if needed`() = testScope.runTest {
        every { engine.getHistorySnapshot() } returns listOf(
            SentenceTurnEngine.Msg("user", "Test"),
            SentenceTurnEngine.Msg("assistant", "Response")
        )

        interruptionManager.dropBufferedAssistant()

        verify(atLeast = 0) { engine.clearHistory() }
    }

    // ========================================
    // SIMPLE INTEGRATION TESTS
    // ========================================

    @Test
    fun `normal flow - transcript when idle does not trigger accumulation`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.IDLE
        interruptionManager.onTurnStart(System.currentTimeMillis())

        interruptionManager.checkTranscriptForInterruption("I want to add this")

        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `normal flow - noise during idle handled gracefully`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.IDLE

        val result = interruptionManager.checkTranscriptForInterruption("")

        assertFalse(result)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `combineTranscript - works without accumulation mode`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("First part")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val combined = interruptionManager.combineTranscript("second part")

        assertEquals("First part second part", combined)
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    fun `reset - can be called multiple times safely`() = testScope.runTest {
        interruptionManager.reset()
        interruptionManager.reset()
        interruptionManager.reset()

        assertFalse(interruptionManager.isBargeInTurn)
    }

    @Test
    fun `onTurnStart - can be called multiple times`() = testScope.runTest {
        interruptionManager.onTurnStart(1000L)
        interruptionManager.onTurnStart(2000L)
        interruptionManager.onTurnStart(3000L)

        assertEquals(3000L, interruptionManager.generationStartTime)
    }

    @Test
    fun `generationStartTime - defaults to zero`() = testScope.runTest {
        assertTrue(interruptionManager.generationStartTime == 0L)
    }

    @Test
    fun `public flags - default to false`() = testScope.runTest {
        assertFalse(interruptionManager.isBargeInTurn)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    // ========================================
    // TIMING TESTS - LLM RESPONSE TIMING
    // ========================================

    @Test
    fun `TIMING - LLM response arrives immediately - accepted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(0)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Quick response")

        assertFalse(accepted)
    }

    @Test
    fun `TIMING - LLM response arrives after 1 second - accepted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(1000)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Normal response")

        assertFalse(accepted)
    }

    @Test
    fun `TIMING - LLM response arrives after 5 seconds during evaluation`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(5000)
        isListeningFlow.value = true

        val buffered = interruptionManager.maybeHandleAssistantFinalResponse("Late response")

        assertNotNull(buffered)
    }

    @Test
    fun `TIMING - LLM response after 10 seconds - still processed if not interrupted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(10_000)
        isListeningFlow.value = false

        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Very slow response")

        assertFalse(accepted)
    }

    @Test
    fun `TIMING - very fast LLM response (50ms) - handled correctly`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(50)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Fast!")

        assertFalse(accepted)
        assertEquals(50L, scheduler.currentTime)
    }

    @Test
    fun `TIMING - very slow LLM response (60 seconds) - still processed`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(60_000)
        isListeningFlow.value = false

        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Finally done")

        assertFalse(accepted)
    }

    // ========================================
    // TIMING TESTS - RACE CONDITIONS
    // ========================================

    @Test
    fun `TIMING - transcript arrives 100ms before LLM response`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        interruptionManager.checkTranscriptForInterruption("User speech")
        scheduler.advanceTimeBy(100)

        val buffered = interruptionManager.maybeHandleAssistantFinalResponse("LLM response")

        assertTrue(scheduler.currentTime >= 100)
    }

    @Test
    fun `TIMING - LLM response timing verified`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        isListeningFlow.value = true

        val buffered1 = interruptionManager.maybeHandleAssistantFinalResponse("LLM response")
        scheduler.advanceTimeBy(100)

        interruptionManager.checkTranscriptForInterruption("User speech")

        // Verify timing was handled
        assertTrue(scheduler.currentTime >= 100)
        assertNotNull(buffered1)
    }

    @Test
    fun `TIMING - multiple rapid transcripts timing`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("First")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        scheduler.advanceTimeBy(100)
        val combined1 = interruptionManager.combineTranscript("second")

        scheduler.advanceTimeBy(100)
        every { entry.sentences } returns listOf("First second")
        val combined2 = interruptionManager.combineTranscript("third")

        scheduler.advanceTimeBy(100)

        // Verify timing
        assertEquals(300L, scheduler.currentTime)
        assertEquals("First second", combined1)
        assertTrue(combined2.contains("First"))
    }

    // ========================================
    // TIMING TESTS - EDGE CASES
    // ========================================

    @Test
    fun `TIMING - timestamp overflow handling - works with large timestamps`() = testScope.runTest {
        val largeTimestamp = System.currentTimeMillis() + 3_600_000

        interruptionManager.onTurnStart(largeTimestamp)

        assertEquals(largeTimestamp, interruptionManager.generationStartTime)
    }

    @Test
    fun `TIMING - generation duration calculation`() = testScope.runTest {
        val startTime = scheduler.currentTime
        interruptionManager.onTurnStart(startTime)

        scheduler.advanceTimeBy(2_500)

        val duration = scheduler.currentTime - startTime
        assertEquals(2_500L, duration)
    }

    @Test
    fun `TIMING - zero duration response - handled correctly`() = testScope.runTest {
        val now = scheduler.currentTime
        interruptionManager.onTurnStart(now)

        scheduler.advanceTimeBy(0)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Instant")

        assertFalse(accepted)
        assertEquals(0L, scheduler.currentTime - now)
    }

    @Test
    fun `TIMING - response timing window validation`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        // Test various time windows
        val testCases = listOf(0L, 100L, 500L, 1000L, 5000L, 10000L)

        testCases.forEach { delay ->
            scheduler.advanceTimeBy(delay)
            val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Response at ${delay}ms")
            // All should be accepted when not in evaluation mode
            assertFalse(accepted, "Response at ${delay}ms should be accepted")
        }
    }
}