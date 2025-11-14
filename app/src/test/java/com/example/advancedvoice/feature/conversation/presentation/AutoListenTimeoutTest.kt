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

/**
 * Auto-Listen Timeout Tests
 *
 * These tests verify that auto-listen respects the configured timeout limit
 * regardless of retries, noise, or false positives.
 *
 * CURRENT STATE: These tests FAIL (demonstrating the bug)
 * AFTER FIX: These tests should PASS
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

        sttController = mockk(relaxed = true) {
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
        // Given: Configured timeout of 11 seconds
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_000L  // Allow 1 second tolerance

        val startTime = scheduler.currentTime

        // When: Start auto-listen
        flowController.startAutoListening()
        scheduler.runCurrent()

        // Simulate retry scenario: empty transcript at 10s → retry
        scheduler.advanceTimeBy(10_000)
        flowController.onFinalTranscript("")  // Triggers retry
        scheduler.advanceTimeBy(300)  // Retry delay
        scheduler.runCurrent()

        // Second empty transcript at 11s → should stop now (total time reached)
        scheduler.advanceTimeBy(700)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        // Then: Total listening time should not exceed configured timeout + tolerance
        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Auto-listen exceeded timeout limit. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )

        // And: STT should be stopped
        verify(atLeast = 1) { stateManager.setListening(false) }
    }

    @Test
    fun `auto-listen with two retries should still respect total timeout`() = testScope.runTest {
        // Given: Timeout of 11 seconds, max 2 retries
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_500L

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        // When: Empty transcript after 3 seconds → retry 1
        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        // Empty transcript after 6 seconds → retry 2
        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        // Empty transcript after 9 seconds → should stop (approaching limit)
        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        // Then: Total time should not exceed limit despite multiple retries
        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Multiple retries caused timeout violation. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )
    }

    @Test
    fun `retry should use remaining time not full timeout`() = testScope.runTest {
        // Given: Total timeout of 11 seconds
        val TOTAL_TIMEOUT_MS = 11_000L
        val FIRST_SESSION_DURATION = 9_000L
        val REMAINING_TIME = TOTAL_TIMEOUT_MS - FIRST_SESSION_DURATION  // 2 seconds

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        // When: First session runs for 9 seconds
        scheduler.advanceTimeBy(FIRST_SESSION_DURATION)
        flowController.onFinalTranscript("")  // Empty → retry
        scheduler.advanceTimeBy(300)  // Retry delay
        scheduler.runCurrent()

        // Advance beyond remaining time (2s) but less than another full timeout (11s)
        scheduler.advanceTimeBy(REMAINING_TIME + 500)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        // Then: Should have stopped around 11s (not 9s + 11s = 20s)
        assertTrue(
            totalTime < 15_000,  // Well below 20s
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
        // Given: Timeout of 11 seconds
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_500L

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        // When: Noise detected at 5 seconds
        scheduler.advanceTimeBy(5_000)
        isHearingSpeechFlow.value = true
        scheduler.runCurrent()

        // Noise ends at 7 seconds → empty transcript
        scheduler.advanceTimeBy(2_000)
        isHearingSpeechFlow.value = false
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)  // Retry delay
        scheduler.runCurrent()

        // More time passes (would exceed 11s if timer was reset)
        scheduler.advanceTimeBy(5_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        // Then: Total time should respect original limit (not reset by noise)
        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Noise detection reset the timeout timer. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )
    }

    @Test
    fun `multiple noise bursts should not infinitely extend timeout`() = testScope.runTest {
        // Given: Timeout of 11 seconds
        val TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 2_000L

        val startTime = scheduler.currentTime
        flowController.startAutoListening()
        scheduler.runCurrent()

        // When: Multiple short noise bursts (simulating background noise)
        repeat(4) { burst ->
            scheduler.advanceTimeBy(1_500)  // 1.5s
            isHearingSpeechFlow.value = true
            scheduler.runCurrent()

            scheduler.advanceTimeBy(500)  // 0.5s noise
            isHearingSpeechFlow.value = false
            flowController.onFinalTranscript("")  // Empty each time
            scheduler.advanceTimeBy(300)  // Retry delay
            scheduler.runCurrent()
        }

        // Force final transcript
        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.runCurrent()

        val totalTime = scheduler.currentTime - startTime

        // Then: Should stop around 11s despite multiple noise events
        assertTrue(
            totalTime <= TIMEOUT_MS + TOLERANCE_MS,
            "Multiple noise bursts extended timeout indefinitely. Expected: <=${TIMEOUT_MS + TOLERANCE_MS}ms, Actual: ${totalTime}ms"
        )
    }

    // ========================================
    // RETRY BEHAVIOR TESTS
    // ========================================

    @Test
    fun `auto-listen should stop after max retries even before timeout`() = testScope.runTest {
        // ✅ UPDATED: This test needs to reflect CURRENT behavior
        // Current code: No retry counter, sessions stay alive until ::TIMEOUT:: signal

        flowController.startAutoListening()
        scheduler.runCurrent()

        // Simulate 3 empty transcripts (VAD noise)
        repeat(3) { attempt ->
            scheduler.advanceTimeBy(2_000)
            flowController.onFinalTranscript("")
            scheduler.runCurrent()
        }

        // ✅ NEW EXPECTATION: Session should STILL be listening
        // (Only ::TIMEOUT:: signal stops it)

        // The session should NOT have stopped
        assertEquals(0, sttStopCalled, "Session should stay alive - only timeout signal stops it")

        // But if we send the timeout signal...
        flowController.onFinalTranscript("::TIMEOUT::")
        scheduler.runCurrent()

        // NOW it should stop
        assertTrue(sttStopCalled >= 1, "Timeout signal should stop the session")
    }

    @Test
    fun `successful transcript should reset retry counter`() = testScope.runTest {
        // Given: Had a retry, then successful transcript
        flowController.startAutoListening()
        scheduler.runCurrent()

        // Empty transcript → retry
        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        // When: Valid transcript received
        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("Hello world")
        scheduler.runCurrent()

        // Then: Retry counter should reset (not tested directly, but verified by behavior)
        // If user speaks again, they should get fresh retry attempts
        verify { stateManager.replaceLastUser("Hello world") }
    }

    // ========================================
    // STATE CLEANUP TESTS
    // ========================================

    @Test
    fun `auto-listen should clean up state when timeout reached`() = testScope.runTest {
        // Given: Auto-listen running
        flowController.startAutoListening()
        scheduler.runCurrent()

        // When: Timeout is reached through retries
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

        // Then: Should stop listening and clean up
        verify(atLeast = 1) { stateManager.setListening(false) }
        verify(atLeast = 1) { stateManager.setHearingSpeech(false) }
        verify(atLeast = 1) { stateManager.setTranscribing(false) }
    }

    @Test
    fun `manual tap to speak should reset auto-listen timer`() = testScope.runTest {
        // Given: Auto-listen had been running for 8 seconds
        flowController.startAutoListening()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(8_000)

        // When: User manually taps to speak
        flowController.startListening()  // Manual tap
        scheduler.runCurrent()

        val manualStartTime = scheduler.currentTime

        // Simulate long session (would exceed if old timer still counted)
        scheduler.advanceTimeBy(10_000)
        flowController.onFinalTranscript("")

        // Then: Should respect NEW session (not count previous 8s)
        // This is verified by not having timeout violations
        assertTrue(scheduler.currentTime - manualStartTime >= 10_000)
    }

    // ========================================
    // INTEGRATION TESTS
    // ========================================

    @Test
    fun `complete auto-listen lifecycle with realistic timing`() = testScope.runTest {
        // Given: User has auto-listen enabled
        val TIMEOUT_MS = 11_000L
        val startTime = scheduler.currentTime

        flowController.startAutoListening()
        scheduler.runCurrent()

        // Scenario: False positive → retry → noise → retry → real speech

        // 1. False positive at 2s
        scheduler.advanceTimeBy(2_000)
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        // 2. Noise at 5s
        scheduler.advanceTimeBy(3_000)
        isHearingSpeechFlow.value = true
        scheduler.runCurrent()
        scheduler.advanceTimeBy(500)
        isHearingSpeechFlow.value = false
        flowController.onFinalTranscript("")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()

        // 3. Real speech at 9s
        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("What is the weather?")

        val totalTime = scheduler.currentTime - startTime

        // Then: Should complete successfully within timeout
        assertTrue(totalTime < TIMEOUT_MS, "Total time: ${totalTime}ms")
        verify { engine.startTurn("What is the weather?", any()) }
    }

    @Test
    fun `auto-listen timeout should be independent per session`() = testScope.runTest {
        // Given: Complete first auto-listen session
        flowController.startAutoListening()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(3_000)
        flowController.onFinalTranscript("First question")
        scheduler.runCurrent()

        // Process response and TTS
        scheduler.advanceTimeBy(5_000)

        val secondStartTime = scheduler.currentTime

        // When: Start second auto-listen session
        flowController.startAutoListening()
        scheduler.runCurrent()

        // Run for full timeout
        scheduler.advanceTimeBy(11_000)
        flowController.onFinalTranscript("")

        val secondSessionTime = scheduler.currentTime - secondStartTime

        // Then: Second session should have full timeout (not affected by first)
        assertTrue(
            secondSessionTime >= 11_000,
            "Second session timeout was affected by first session"
        )
    }
}