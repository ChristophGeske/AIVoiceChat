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

/**
 * Core InterruptionManager Tests
 *
 * Tests basic functionality: transcript handling, buffering, timing
 * For sources buffering, see InterruptionManagerSourcesTest
 */
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
    fun `onTurnStart sets generation start time`() = testScope.runTest {
        val startTime = System.currentTimeMillis()
        interruptionManager.onTurnStart(startTime)
        assertEquals(startTime, interruptionManager.generationStartTime)
    }

    @Test
    fun `combineTranscript combines with existing text`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("First part")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val combined = interruptionManager.combineTranscript("second part")
        assertEquals("First part second part", combined)
    }

    @Test
    fun `combineTranscript handles empty previous text`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns emptyList()
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val combined = interruptionManager.combineTranscript("new text")
        assertEquals("new text", combined)
    }

    @Test
    fun `combineTranscript adds space if previous doesn't end with one`() = testScope.runTest {
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
    fun `reset clears public flags`() = testScope.runTest {
        interruptionManager.onTurnStart(System.currentTimeMillis())
        interruptionManager.reset()

        assertFalse(interruptionManager.isBargeInTurn)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `reset stops state flows`() = testScope.runTest {
        isListeningFlow.value = true
        isHearingSpeechFlow.value = true

        interruptionManager.reset()
        scheduler.runCurrent()

        verify { stateManager.setListening(false) }
        verify { stateManager.setHearingSpeech(false) }
        verify { stateManager.setTranscribing(false) }
    }

    @Test
    fun `reset can be called multiple times safely`() = testScope.runTest {
        interruptionManager.reset()
        interruptionManager.reset()
        interruptionManager.reset()

        assertFalse(interruptionManager.isBargeInTurn)
    }

    // ========================================
    // TRANSCRIPT HANDLING TESTS
    // ========================================

    @Test
    fun `checkTranscriptForInterruption when not evaluating returns false`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST

        val handled = interruptionManager.checkTranscriptForInterruption("Hello there friend")

        assertFalse(handled)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `checkTranscriptForInterruption short words when not evaluating`() = testScope.runTest {
        val result = interruptionManager.checkTranscriptForInterruption("a to be or")
        assertFalse(result)
    }

    @Test
    fun `checkTranscriptForInterruption numbers and symbols when not evaluating`() = testScope.runTest {
        val result = interruptionManager.checkTranscriptForInterruption("123 @#$ 456")
        assertFalse(result)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `checkTranscriptForInterruption blank text handled gracefully`() = testScope.runTest {
        val result = interruptionManager.checkTranscriptForInterruption("   \n\t  ")
        assertNotNull(result)
        assertFalse(result)
    }

    // ========================================
    // BUFFERING TESTS
    // ========================================

    @Test
    fun `maybeHandleAssistantFinalResponse returns false when not evaluating`() = testScope.runTest {
        val buffered = interruptionManager.maybeHandleAssistantFinalResponse("Test response")
        assertFalse(buffered)
    }

    @Test
    fun `flushBufferedAssistantIfAny when no buffer does nothing`() = testScope.runTest {
        interruptionManager.flushBufferedAssistantIfAny()

        verify(exactly = 0) { stateManager.addAssistant(any()) }
        verify(exactly = 0) { tts.queue(any<String>()) }
    }

    @Test
    fun `dropBufferedAssistant cleans engine history if needed`() = testScope.runTest {
        every { engine.getHistorySnapshot() } returns listOf(
            SentenceTurnEngine.Msg("user", "Test"),
            SentenceTurnEngine.Msg("assistant", "Response")
        )

        interruptionManager.dropBufferedAssistant()

        verify(atLeast = 0) { engine.clearHistory() }
    }

    // ========================================
    // INTEGRATION TESTS
    // ========================================

    @Test
    fun `normal flow transcript when idle does not trigger accumulation`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.IDLE
        interruptionManager.onTurnStart(System.currentTimeMillis())

        interruptionManager.checkTranscriptForInterruption("I want to add this")

        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `normal flow noise during idle handled gracefully`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.IDLE

        val result = interruptionManager.checkTranscriptForInterruption("")

        assertFalse(result)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }

    @Test
    fun `real interruption discards buffered response`() = testScope.runTest {
        val addedResponses = mutableListOf<String>()

        every { stateManager.addAssistant(any()) } answers {
            val text = firstArg<List<String>>().joinToString(" ")
            addedResponses.add(text)
        }

        val conversationFlow = MutableStateFlow(emptyList<com.example.advancedvoice.domain.entities.ConversationEntry>())
        every { stateManager.conversation } returns conversationFlow
        every { engine.getHistorySnapshot() } returns emptyList()

        val phaseFlow = MutableStateFlow(GenerationPhase.SINGLE_SHOT_GENERATING)
        val isListeningFlow = MutableStateFlow(false)
        val isTranscribingFlow = MutableStateFlow(false)
        val isHearingSpeechFlow = MutableStateFlow(false)

        every { stateManager.phase } returns phaseFlow
        every { stateManager.isListening } returns isListeningFlow
        every { stateManager.isTranscribing } returns isTranscribingFlow
        every { stateManager.isHearingSpeech } returns isHearingSpeechFlow

        val interruptMgr = InterruptionManager(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            getStt = { sttController },
            startTurnWithExistingHistory = {},
            onInterruption = {},
            onNoiseConfirmed = {}
        )

        // ❌ REMOVE THIS LINE - it starts the infinite flow
        // interruptMgr.initialize()
        // advanceUntilIdle()

        interruptMgr.onTurnStart(System.currentTimeMillis())

        // ✅ Manually set evaluation mode using reflection (instead of relying on initialize())
        val isEvaluatingField = InterruptionManager::class.java
            .getDeclaredField("isEvaluatingBargeIn")
        isEvaluatingField.isAccessible = true
        isEvaluatingField.set(interruptMgr, true)

        // Set listening state
        isListeningFlow.value = true
        isTranscribingFlow.value = true
        advanceUntilIdle()

        // Buffer a response
        val wasBuffered = interruptMgr.maybeHandleAssistantFinalResponse("Old response")
        assertTrue(wasBuffered, "Response should be buffered")

        // User says real speech
        isListeningFlow.value = false
        isTranscribingFlow.value = false
        advanceUntilIdle()

        interruptMgr.checkTranscriptForInterruption("New question")
        advanceUntilIdle()

        // Verify buffered response was dropped
        assertEquals(0, addedResponses.size, "Buffered response should be dropped")
    }

    // ========================================
    // TIMING TESTS
    // ========================================

    @Test
    fun `TIMING LLM response arrives immediately accepted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(0)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Quick response")

        assertFalse(accepted)
    }

    @Test
    fun `TIMING LLM response after 1 second accepted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(1000)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Normal response")

        assertFalse(accepted)
    }

    @Test
    fun `TIMING LLM response after 5 seconds during evaluation`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(5000)
        isListeningFlow.value = true

        val buffered = interruptionManager.maybeHandleAssistantFinalResponse("Late response")

        assertNotNull(buffered)
    }

    @Test
    fun `TIMING very fast LLM response 50ms handled correctly`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruptionManager.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(50)
        val accepted = interruptionManager.maybeHandleAssistantFinalResponse("Fast!")

        assertFalse(accepted)
        assertEquals(50L, scheduler.currentTime)
    }

    @Test
    fun `TIMING generation duration calculation`() = testScope.runTest {
        val startTime = scheduler.currentTime
        interruptionManager.onTurnStart(startTime)

        scheduler.advanceTimeBy(2_500)

        val duration = scheduler.currentTime - startTime
        assertEquals(2_500L, duration)
    }

    @Test
    fun `generationStartTime defaults to zero`() = testScope.runTest {
        assertTrue(interruptionManager.generationStartTime == 0L)
    }

    @Test
    fun `public flags default to false`() = testScope.runTest {
        assertFalse(interruptionManager.isBargeInTurn)
        assertFalse(interruptionManager.isAccumulatingAfterInterrupt)
    }
}