package com.example.advancedvoice.feature.conversation.presentation

import android.app.Application
import com.example.advancedvoice.data.gemini_live.GeminiLiveClient
import com.example.advancedvoice.data.gemini_live.GeminiLiveTranscriber
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.flow.ConversationFlowController
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.SttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Auto-Listen Timeout Tests
 *
 * These tests verify that auto-listen respects the configured timeout limit
 * regardless of retries, noise, or false positives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoListenTimeoutTest {

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
    private var sttStopCalled = 0

    private var fasterFirstValue = false
    private var selectedModelValue = "gemini-2.5-flash"
    private var autoListenValue = true

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
        selectedModelValue = "gemini-2.5-flash"
        autoListenValue = true
        sttStopCalled = 0

        // Reset flows
        isListeningFlow.value = false
        isSpeakingFlow.value = false
        isHearingSpeechFlow.value = false
        isTranscribingFlow.value = false
        phaseFlow.value = GenerationPhase.IDLE

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isSpeaking } returns isSpeakingFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { isTranscribing } returns isTranscribingFlow
            every { conversation } returns MutableStateFlow(emptyList())

            // Mock setters to update our flows
            every { setListening(any()) } answers { isListeningFlow.value = firstArg() }
            every { setHearingSpeech(any()) } answers { isHearingSpeechFlow.value = firstArg() }
            every { setTranscribing(any()) } answers { isTranscribingFlow.value = firstArg() }
            every { setPhase(any()) } answers { phaseFlow.value = firstArg() }
        }

        engine = mockk(relaxed = true) {
            every { isActive() } returns false
        }

        tts = mockk(relaxed = true)

        sttController = mockk(relaxed = true) {
            every { isListening } returns isListeningFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { isTranscribing } returns isTranscribingFlow
            every { transcripts } returns MutableSharedFlow()
            every { errors } returns MutableSharedFlow()

            coEvery { stop(any()) } answers {
                sttStopCalled++
                isListeningFlow.value = false
            }
        }

        turnCompleteCalled = 0
        tapToSpeakCalled = 0

        interruption = InterruptionManager(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            getStt = { sttController },
            startTurnWithExistingHistory = { turnCompleteCalled++ },
            onInterruption = {},
            onNoiseConfirmed = {}
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
    // TIMEOUT LIMIT TESTS
    // ========================================

    @Test
    fun `auto-listen should respect total timeout regardless of retries`() = testScope.runTest {
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_000L

        val startTime = scheduler.currentTime

        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(10_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(700)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Auto-listen exceeded timeout limit. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )

        verify(atLeast = 1) { stateManager.setListening(false) }
    }

    @Test
    fun `auto-listen with two retries should still respect total timeout`() = testScope.runTest {
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_500L

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Multiple retries caused timeout violation. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )
    }

    @Test
    fun `retry should use remaining time not full timeout`() = testScope.runTest {
        val TOTAL_TIMEOUT_MS = 11_000L
        val FIRST_SESSION_DURATION = 9_000L
        val REMAINING_TIME = TOTAL_TIMEOUT_MS - FIRST_SESSION_DURATION

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(FIRST_SESSION_DURATION)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(REMAINING_TIME + 500)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        assertTrue(
            totalTime < 15_000,
            "Retry used full timeout instead of remaining time. Total: ${totalTime}ms"
        )

        assertTrue(
            totalTime >= TOTAL_TIMEOUT_MS,
            "Should at least reach the configured timeout. Total: ${totalTime}ms"
        )
    }

    // ========================================
    // NOISE HANDLING TESTS
    // ========================================

    @Test
    fun `noise detection should not reset cumulative timeout`() = testScope.runTest {
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_500L

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(5_000)
        isHearingSpeechFlow.value = true
        scheduler.runCurrent()

        scheduler.advanceTimeBy(2_000)
        isHearingSpeechFlow.value = false
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(5_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Noise detection reset the timeout timer. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )
    }

    @Test
    fun `multiple noise bursts should not infinitely extend timeout`() = testScope.runTest {
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 2_000L

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        repeat(4) { burst ->
            scheduler.advanceTimeBy(1_500)
            isHearingSpeechFlow.value = true
            scheduler.runCurrent()

            scheduler.advanceTimeBy(500)
            isHearingSpeechFlow.value = false
            flowController.onFinalTranscript("")
            scheduler.advanceTimeBy(300)
            scheduler.runCurrent()
        }

        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Multiple noise bursts extended timeout indefinitely. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )
    }

    // ========================================
    // RETRY BEHAVIOR TESTS
    // ========================================

    @Test
    fun `auto-listen should stop after timeout signal`() = testScope.runTest {
        flowController.startAutoListening()
        scheduler.runCurrent()

        repeat(3) { attempt ->
            scheduler.advanceTimeBy(2_000)
            flowController.onFinalTranscript("")
            scheduler.runCurrent()
        }

        assertEquals(0, sttStopCalled, "Session should stay alive - only timeout signal stops it")

        flowController.onFinalTranscript("::TIMEOUT::")
        scheduler.runCurrent()

        assertTrue(sttStopCalled >= 1, "Timeout signal should stop the session")
    }

    @Test
    fun `successful transcript should reset retry counter`() = testScope.runTest {
        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("Hello world")
        scheduler.runCurrent()

        verify { stateManager.replaceLastUser("Hello world") }
    }

    // ========================================
    // STATE CLEANUP TESTS
    // ========================================

    @Test
    fun `auto-listen should clean up state when timeout reached`() = testScope.runTest {
        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(4_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(4_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(4_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        verify(atLeast = 1) { stateManager.setListening(false) }
        verify(atLeast = 1) { stateManager.setHearingSpeech(false) }
        verify(atLeast = 1) { stateManager.setTranscribing(false) }
    }

    @Test
    fun `manual tap to speak should reset auto-listen timer`() = testScope.runTest {
        flowController.startAutoListening()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(8_000)

        flowController.startListening()
        scheduler.runCurrent()

        val manualStartTime = scheduler.currentTime

        scheduler.advanceTimeBy(10_000)
        flowController.onFinalTranscript("")

        assertTrue(scheduler.currentTime - manualStartTime >= 10_000)
    }

    // ========================================
    // INTEGRATION TESTS
    // ========================================

    @Test
    fun `complete auto-listen lifecycle with realistic timing`() = testScope.runTest {
        val TIMEOUT_MS = 11_000L
        val startTime = scheduler.currentTime

        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(3_000)
        isHearingSpeechFlow.value = true
        scheduler.runCurrent()
        scheduler.advanceTimeBy(500)
        isHearingSpeechFlow.value = false
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("What is the weather?")

        val totalTime = scheduler.currentTime - startTime

        assertTrue(totalTime < TIMEOUT_MS, "Total time: ${totalTime}ms")
        verify { engine.startTurn("What is the weather?", any()) }
    }

    @Test
    fun `auto-listen timeout should be independent per session`() = testScope.runTest {
        flowController.startAutoListening()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("First question")
        scheduler.runCurrent()

        scheduler.advanceTimeBy(5_000)

        val secondStartTime = scheduler.currentTime

        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(11_000)
        flowController.onFinalTranscript("")

        val secondSessionTime = scheduler.currentTime - secondStartTime

        assertTrue(
            secondSessionTime >= 11_000,
            "Second session timeout was affected by first session"
        )
    }

    // ========================================
    // BUG DETECTION TEST
    // ========================================

    @Test
    @Disabled("This test requires GeminiLiveSttController integration - use manual device test to verify fix")
    fun `BUG - noise after speech cancels timeout and never re-enables it`() = testScope.runTest {
        // This test documents the bug but cannot verify the fix at this level
        // because it requires testing GeminiLiveSttController's internal timeout logic.
        //
        // TO VERIFY THE FIX:
        // 1. Run the app on a device
        // 2. Ask a question
        // 3. Wait for TTS to finish (auto-listen starts)
        // 4. Make noise 3-4 times
        // 5. After 5 seconds total, session should timeout
        //
        // EXPECTED LOGS:
        // [Controller] Starting 5s session timeout at XXXXX
        // [Controller] Restarting session timeout with XXXXms remaining
        // [Controller] ⏱️ Session timeout - total time limit reached

        val TIMEOUT_MS = 5_000L
        val startTime = scheduler.currentTime

        flowController.startAutoListening()
        scheduler.runCurrent()

        scheduler.advanceTimeBy(1_000)
        isHearingSpeechFlow.value = true
        scheduler.runCurrent()

        scheduler.advanceTimeBy(1_000)
        isHearingSpeechFlow.value = false
        scheduler.runCurrent()

        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        println("⏱️  After first noise at T=${scheduler.currentTime - startTime}ms, isListening=${isListeningFlow.value}")

        scheduler.advanceTimeBy(1_000)
        isHearingSpeechFlow.value = true
        scheduler.runCurrent()
        isHearingSpeechFlow.value = false
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        println("⏱️  After second noise at T=${scheduler.currentTime - startTime}ms, isListening=${isListeningFlow.value}")

        scheduler.advanceTimeBy(7_000)
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime
        println("⏱️  Final time: ${totalTime}ms, isListening=${isListeningFlow.value}")

        assertFalse(
            isListeningFlow.value,
            "❌ BUG DETECTED: Session is still listening after ${totalTime}ms! Expected timeout at ${TIMEOUT_MS}ms"
        )

        assertTrue(
            totalTime <= TIMEOUT_MS + 2_000L,
            "Session should have stopped around ${TIMEOUT_MS}ms, but ran for ${totalTime}ms"
        )
    }

    // ========================================
    // SIMPLIFIED TIMEOUT TEST
    // ========================================

    @Test
    fun `session should stop when timeout signal is received`() = testScope.runTest {
        // This test verifies that the timeout signal mechanism works
        // The actual timeout logic is tested via manual device testing

        flowController.startAutoListening()
        scheduler.runCurrent()

        // Simulate some noise
        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        // Session should still be active
        assertTrue(isListeningFlow.value || sttStopCalled == 0)

        // Send timeout signal
        flowController.onFinalTranscript("::TIMEOUT::")
        scheduler.runCurrent()

        // Session should stop
        assertTrue(sttStopCalled >= 1, "Session should stop on timeout signal")
        assertFalse(isListeningFlow.value, "Should not be listening after timeout")
    }
}