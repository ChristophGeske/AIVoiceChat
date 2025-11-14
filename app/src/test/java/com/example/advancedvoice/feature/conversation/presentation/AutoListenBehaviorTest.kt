package com.example.advancedvoice.feature.conversation.presentation

import com.example.advancedvoice.feature.conversation.presentation.flow.ConversationFlowController
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.SttController
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoListenBehaviorTest {

    private lateinit var testScope: TestScope
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var stateManager: ConversationStateManager
    private lateinit var sttController: SttController
    private lateinit var flowController: ConversationFlowController

    // ✅ FIX: Use real MutableStateFlows to accurately track state changes
    private val isListeningFlow = MutableStateFlow(false)
    private val isHearingSpeechFlow = MutableStateFlow(false)
    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)

    private val prefsProvider = object : ConversationFlowController.PrefsProvider {
        override fun getFasterFirst() = false
        override fun getSelectedModel() = "test-model"
        override fun getAutoListen() = true
    }

    @BeforeEach
    fun setup() {
        scheduler = TestCoroutineScheduler()
        testScope = TestScope(StandardTestDispatcher(scheduler))

        // Reset flows before each test
        isListeningFlow.value = false
        isHearingSpeechFlow.value = false
        phaseFlow.value = GenerationPhase.IDLE

        stateManager = mockk(relaxed = true) {
            // Point the mock to our real StateFlows
            every { isListening } returns isListeningFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { phase } returns phaseFlow

            // Mock the setter methods to update our real flows
            every { setListening(any()) } answers { isListeningFlow.value = firstArg() }
            every { setHearingSpeech(any()) } answers { isHearingSpeechFlow.value = firstArg() }
            every { setPhase(any()) } answers { phaseFlow.value = firstArg() }
        }

        sttController = mockk {
            coEvery { start(any()) } just runs
            coEvery { stop(any()) } just runs
        }

        flowController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = mockk(relaxed = true),
            tts = mockk(relaxed = true),
            interruption = mockk(relaxed = true),
            getStt = { sttController },
            getPrefs = prefsProvider,
            onTurnComplete = {},
            onTapToSpeak = {}
        )
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `auto-listen session should stop at timeout and not restart`() = testScope.runTest {
        // --- ARRANGE ---
        val CONFIGURED_TIMEOUT_MS = 11_000L
        val TOLERANCE_MS = 1_000L
        val MAX_EXPECTED_TIME = CONFIGURED_TIMEOUT_MS + TOLERANCE_MS

        val startTime = scheduler.currentTime
        println("[TEST] Starting auto-listen. Expected to stop by T=${MAX_EXPECTED_TIME}ms.")

        // --- ACT ---
        // 1. Start the auto-listen session
        flowController.startAutoListening()
        scheduler.runCurrent()
        println("[TEST] T=${scheduler.currentTime}ms. Session started. isListening=${isListeningFlow.value}")

        // 2. Simulate a VAD false positive (noise) at 5 seconds.
        scheduler.advanceTimeBy(5_000)
        println("[TEST] T=${scheduler.currentTime}ms. Simulating VAD noise (sends empty string).")
        flowController.onFinalTranscript("")
        scheduler.runCurrent()
        println("[TEST] T=${scheduler.currentTime}ms. VAD noise was processed. isListening=${isListeningFlow.value}")

        // 3. Simulate the session hitting its timeout at 11 seconds.
        scheduler.advanceTimeBy(CONFIGURED_TIMEOUT_MS - 5000) // Advance to the 11s mark
        println("[TEST] T=${scheduler.currentTime}ms. Simulating session timeout (sends '::TIMEOUT::').")
        flowController.onFinalTranscript("::TIMEOUT::")
        scheduler.runCurrent() // Allow the stop logic to execute

        val totalTime = scheduler.currentTime - startTime
        println("[TEST] T=${totalTime}ms. Test finished. isListening=${isListeningFlow.value}")

        // --- ASSERT ---
        // With the code fix, this assertion should now PASS.
        assertTrue(
            totalTime <= MAX_EXPECTED_TIME,
            "Session did not stop at timeout! Expected <=$MAX_EXPECTED_TIME ms, but took $totalTime ms."
        )

        // Verify that STT was not started again after the VAD noise.
        coVerify(exactly = 1) { sttController.start(any()) }

        // Verify that STT was stopped after the timeout signal.
        coVerify(exactly = 1) { sttController.stop(false) }

        // ✅ THE KEY VERIFICATION: Check the final state of our real StateFlow
        assertFalse(isListeningFlow.value, "isListening should be false after timeout.")
    }
}