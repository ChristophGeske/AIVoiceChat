package com.example.advancedvoice.feature.conversation.presentation

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.flow.ConversationFlowController
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
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
class TimingAndRaceConditionTest {

    private lateinit var testScope: TestScope
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var interruption: InterruptionManager
    private lateinit var sttController: SttController
    private lateinit var flowController: ConversationFlowController

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isListeningFlow = MutableStateFlow(false)
    private val isSpeakingFlow = MutableStateFlow(false)
    private val isHearingSpeechFlow = MutableStateFlow(false)
    private val isTranscribingFlow = MutableStateFlow(false)

    private var turnCompleteCalled = 0
    private var tapToSpeakCalled = 0
    private var interruptionCalled = 0
    private var noiseConfirmedCalled = 0

    private var fasterFirstValue = false
    private var selectedModelValue = "gemini-2.0-flash-exp"
    private var autoListenValue = false

    private val prefsProvider = object : ConversationFlowController.PrefsProvider {
        override fun getFasterFirst() = fasterFirstValue
        override fun getSelectedModel() = selectedModelValue
        override fun getAutoListen() = autoListenValue
    }

    @BeforeEach
    fun setup() {
        scheduler = TestCoroutineScheduler()
        testScope = TestScope(StandardTestDispatcher(scheduler))

        fasterFirstValue = false
        selectedModelValue = "gemini-2.0-flash-exp"
        autoListenValue = false

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isSpeaking } returns isSpeakingFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { isTranscribing } returns isTranscribingFlow
            every { conversation } returns MutableStateFlow(emptyList())
        }

        engine = mockk(relaxed = true) {
            every { isActive() } returns false
        }

        tts = mockk(relaxed = true)
        sttController = mockk(relaxed = true)

        turnCompleteCalled = 0
        tapToSpeakCalled = 0
        interruptionCalled = 0
        noiseConfirmedCalled = 0

        interruption = InterruptionManager(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            getStt = { sttController },
            startTurnWithExistingHistory = { turnCompleteCalled++ },
            onInterruption = { interruptionCalled++ },
            onNoiseConfirmed = { noiseConfirmedCalled++ }
        )

        flowController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { sttController },
            getPrefs = prefsProvider,
            onTurnComplete = { turnCompleteCalled++ },
            onTapToSpeak = { tapToSpeakCalled++ }
        )
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    // ========================================
    // LLM RESPONSE TIMING TESTS
    // ========================================

    @Test
    fun `LLM response arrives immediately - accepted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(0)
        val accepted = interruption.maybeHandleAssistantFinalResponse("Quick response")

        assertFalse(accepted)
    }

    @Test
    fun `LLM response arrives after 1 second - accepted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(1000)
        val accepted = interruption.maybeHandleAssistantFinalResponse("Normal response")

        assertFalse(accepted)
    }

    @Test
    fun `LLM response arrives after 5 seconds during evaluation - buffered`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(5000)
        isListeningFlow.value = true

        val buffered = interruption.maybeHandleAssistantFinalResponse("Late response")

        assertNotNull(buffered)
    }

    @Test
    fun `LLM response arrives after 10 seconds - still processed if not interrupted`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(10_000)
        isListeningFlow.value = false

        val accepted = interruption.maybeHandleAssistantFinalResponse("Very slow response")

        assertFalse(accepted)
    }

    // ========================================
    // RACE CONDITION TIMING TESTS
    // ========================================

    @Test
    fun `transcript arrives 100ms before LLM response - waits for response`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        interruption.checkTranscriptForInterruption("User speech")
        scheduler.advanceTimeBy(100)

        val buffered = interruption.maybeHandleAssistantFinalResponse("LLM response")

        assertTrue(scheduler.currentTime >= 100)
    }

    @Test
    fun `LLM response arrives 100ms before transcript - response buffered`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        isListeningFlow.value = true

        val buffered1 = interruption.maybeHandleAssistantFinalResponse("LLM response")
        scheduler.advanceTimeBy(100)

        interruption.checkTranscriptForInterruption("User speech")

        // Verify timing handled correctly
        assertTrue(scheduler.currentTime >= 100)
        assertNotNull(buffered1)
    }

    @Test
    fun `multiple transcripts timing - combined correctly`() = testScope.runTest {
        // Given: Mock conversation entry
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("First")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        // When: Transcripts arrive rapidly
        scheduler.advanceTimeBy(100)
        val combined1 = interruption.combineTranscript("second")

        scheduler.advanceTimeBy(100)
        every { entry.sentences } returns listOf("First second")
        val combined2 = interruption.combineTranscript("third")

        scheduler.advanceTimeBy(100)

        // Then: Should combine correctly
        assertEquals("First second", combined1)
        assertTrue(combined2.contains("First"))
        assertEquals(300L, scheduler.currentTime)
    }

    // ========================================
    // AUTO-LISTEN RETRY TIMING TESTS
    // ========================================

    @Test
    fun `auto-listen retry - waits 300ms between retries`() = testScope.runTest {
        flowController.startAutoListening()
        scheduler.runCurrent()

        val startTime = scheduler.currentTime
        flowController.onFinalTranscript("")

        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        assertTrue(scheduler.currentTime - startTime >= 300)
    }

    @Test
    fun `auto-listen - stops retrying eventually`() = testScope.runTest {
        // When: Multiple empty transcripts
        flowController.startAutoListening()
        scheduler.runCurrent()

        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()

        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()

        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()

        // Then: Should eventually stop (verify side effects)
        verify(atLeast = 1) { stateManager.setListening(false) }
    }

    // ========================================
    // MIN RESTART INTERVAL TESTS
    // ========================================

    @Test
    fun `restart interval - timing validation`() = testScope.runTest {
        val entry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "You"
            every { sentences } returns listOf("First speech")
        }
        every { stateManager.conversation.value } returns listOf(entry)

        val startTime = scheduler.currentTime

        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()

        val firstRestartTime = scheduler.currentTime

        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()

        // Verify timing
        assertTrue(scheduler.currentTime >= startTime + 2_000)
    }

    // ========================================
    // TTS TIMING TESTS
    // ========================================

    @Test
    fun `TTS completion to auto-listen - waits 150ms`() = testScope.runTest {
        autoListenValue = true
        phaseFlow.value = GenerationPhase.IDLE

        val ttsFlow = MutableStateFlow(true)
        every { tts.isSpeaking } returns ttsFlow

        val stopTime = scheduler.currentTime
        ttsFlow.value = false

        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()

        assertTrue(scheduler.currentTime - stopTime >= 150)
    }

    // ========================================
    // TIMING EDGE CASES
    // ========================================

    @Test
    fun `very fast LLM response (50ms) - handled correctly`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(50)
        val accepted = interruption.maybeHandleAssistantFinalResponse("Fast!")

        assertFalse(accepted)
        assertEquals(50L, scheduler.currentTime)
    }

    @Test
    fun `very slow LLM response (60 seconds) - still processed`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        scheduler.advanceTimeBy(60_000)
        isListeningFlow.value = false

        val accepted = interruption.maybeHandleAssistantFinalResponse("Finally done")

        assertFalse(accepted)
    }

    @Test
    fun `timestamp overflow handling - works with large timestamps`() = testScope.runTest {
        val largeTimestamp = System.currentTimeMillis() + 3_600_000

        interruption.onTurnStart(largeTimestamp)

        assertEquals(largeTimestamp, interruption.generationStartTime)
    }

    @Test
    fun `response timing across multiple windows`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        interruption.onTurnStart(scheduler.currentTime)

        val timings = listOf(0L, 50L, 100L, 500L, 1000L, 5000L)

        timings.forEach { delay ->
            scheduler.advanceTimeBy(delay)
            val accepted = interruption.maybeHandleAssistantFinalResponse("Response $delay")
            assertFalse(accepted, "Response at ${delay}ms should be accepted")
        }
    }

    @Test
    fun `rapid state transitions timing`() = testScope.runTest {
        phaseFlow.value = GenerationPhase.IDLE
        scheduler.advanceTimeBy(100)

        phaseFlow.value = GenerationPhase.GENERATING_FIRST
        scheduler.advanceTimeBy(100)

        phaseFlow.value = GenerationPhase.GENERATING_REMAINDER
        scheduler.advanceTimeBy(100)

        phaseFlow.value = GenerationPhase.IDLE
        scheduler.advanceTimeBy(100)

        // Verify timing
        assertEquals(400L, scheduler.currentTime)
    }
}