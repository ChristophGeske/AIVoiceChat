package com.example.advancedvoice.feature.conversation.presentation.flow

import com.example.advancedvoice.core.audio.MicrophoneSession
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
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
class ConversationFlowControllerTest {

    private lateinit var testScope: TestScope
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var interruption: InterruptionManager
    private lateinit var sttController: SttController
    private lateinit var flowController: ConversationFlowController

    private var turnCompleteCalled = 0
    private var tapToSpeakCalled = 0

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isListeningFlow = MutableStateFlow(false)
    private val isSpeakingFlow = MutableStateFlow(false)
    private val isHearingSpeechFlow = MutableStateFlow(false)

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
        testScope = TestScope(StandardTestDispatcher())

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isSpeaking } returns isSpeakingFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { conversation } returns MutableStateFlow(emptyList())
        }

        engine = mockk(relaxed = true) {
            every { isActive() } returns false
        }

        tts = mockk(relaxed = true)
        interruption = mockk(relaxed = true)
        sttController = mockk(relaxed = true)

        turnCompleteCalled = 0
        tapToSpeakCalled = 0

        fasterFirstValue = false
        selectedModelValue = "gemini-2.0-flash-exp"
        autoListenValue = false

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
    // START LISTENING TESTS
    // ========================================

    @Test
    fun `startListening when idle - starts STT session`() = testScope.runTest {
        flowController.startListening()
        advanceUntilIdle()

        coVerify { sttController.start(isAutoListen = false) }
        verify { stateManager.addUserStreamingPlaceholder() }
        verify { interruption.reset() }
        assertEquals(1, tapToSpeakCalled)
    }

    @Test
    fun `startListening while generating - interrupts and starts new session`() = testScope.runTest {
        every { engine.isActive() } returns true

        flowController.startListening()
        advanceUntilIdle()

        verify { interruption.reset() }
        verify { engine.abort(true) }
        verify { tts.stop() }
        coVerify { sttController.stop(false) }
        coVerify { sttController.start(isAutoListen = false) }
    }

    @Test
    fun `startListening while already listening - ignores duplicate call`() = testScope.runTest {
        isListeningFlow.value = true

        flowController.startListening()
        advanceUntilIdle()

        verify(exactly = 0) { stateManager.addUserStreamingPlaceholder() }
        coVerify(exactly = 0) { sttController.start(any()) }

        assertEquals(1, tapToSpeakCalled)
    }

    @Test
    fun `startAutoListening starts with auto-listen flag`() = testScope.runTest {
        flowController.startAutoListening()
        advanceUntilIdle()

        coVerify { sttController.start(isAutoListen = true) }
        verify { interruption.reset() }
    }

    // ========================================
    // TRANSCRIPT HANDLING TESTS
    // ========================================

    @Test
    fun `onFinalTranscript with valid text - starts turn`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("Hello world")
        advanceUntilIdle()

        verify { interruption.flushBufferedAssistantIfAny() }
        verify { stateManager.replaceLastUser("Hello world") }
        verify { engine.startTurn("Hello world", any()) }
        assertEquals(1, turnCompleteCalled)
    }

    @Test
    fun `onFinalTranscript with empty text - removes placeholder and stops STT`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        coVerify { sttController.stop(false) }
        verify { stateManager.setListening(false) }
    }

    // ========================================
    // 🔴 NEW FAILING TESTS TO REPRODUCE BUG
    // ========================================

    @Test
    fun `BUGFIX - empty transcript should stop STT and prevent continuous monitoring`() = testScope.runTest {
        println("\n========================================")
        println("🔴 RUNNING BUGFIX TEST")
        println("========================================")

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            coEvery { stop(any()) } just runs
            coEvery { switchMicMode(any()) } just runs
        }

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        val bugTestController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = { turnCompleteCalled++ },
            onTapToSpeak = { tapToSpeakCalled++ }
        )

        println("📍 Starting listening session...")
        bugTestController.startListening()
        advanceUntilIdle()

        println("✅ Session started - verifying initial start() call")
        coVerify(exactly = 1) { geminiStt.start(isAutoListen = false) }

        println("📍 Processing empty transcript (VAD false positive)...")
        bugTestController.onFinalTranscript("")
        advanceUntilIdle()

        println("✅ Empty transcript processed")

        println("\n🔍 VERIFICATION 1: Checking if stop() was called...")
        coVerify(exactly = 1) {
            geminiStt.stop(false)
        }
        println("✅ stop(false) was called")

        println("\n🔍 VERIFICATION 2: Checking if switchMicMode(IDLE) was called...")
        coVerify(exactly = 1) {
            geminiStt.switchMicMode(MicrophoneSession.Mode.IDLE)
        }
        println("✅ switchMicMode(IDLE) was called")

        println("\n🔍 VERIFICATION 3: Checking if listening state was cleared...")
        verify(exactly = 1) {
            stateManager.setListening(false)
        }
        println("✅ setListening(false) was called")

        println("\n🔍 VERIFICATION 4: Checking if hearing speech state was cleared...")
        verify(exactly = 1) {
            stateManager.setHearingSpeech(false)
        }
        println("✅ setHearingSpeech(false) was called")

        println("\n🔍 VERIFICATION 5: Checking if transcribing state was cleared...")
        verify(exactly = 1) {
            stateManager.setTranscribing(false)
        }
        println("✅ setTranscribing(false) was called")

        println("\n========================================")
        println("✅ ALL VERIFICATIONS PASSED!")
        println("========================================\n")
    }

    @Test
    fun `timeout signal should stop STT and clear states`() = testScope.runTest {
        println("\n========================================")
        println("🔵 RUNNING TIMEOUT TEST")
        println("========================================")

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            coEvery { stop(any()) } just runs
            coEvery { switchMicMode(any()) } just runs
        }

        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        val timeoutTestController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = { turnCompleteCalled++ },
            onTapToSpeak = { tapToSpeakCalled++ }
        )

        println("📍 Starting listening session...")
        timeoutTestController.startListening()
        advanceUntilIdle()

        println("📍 Simulating session timeout (::TIMEOUT:: signal)...")
        timeoutTestController.onFinalTranscript("::TIMEOUT::")
        advanceUntilIdle()

        println("\n🔍 Verifying timeout handling...")
        coVerify(exactly = 1) { geminiStt.stop(false) }
        println("✅ stop(false) was called")

        coVerify(exactly = 1) { geminiStt.switchMicMode(MicrophoneSession.Mode.IDLE) }
        println("✅ switchMicMode(IDLE) was called")

        verify { stateManager.setListening(false) }
        println("✅ setListening(false) was called")

        println("\n========================================")
        println("✅ TIMEOUT HANDLING VERIFIED!")
        println("========================================\n")
    }

    // ========================================
    // 🆕 TEST FOR REAL BUG: VAD noise handling
    // ========================================

    @Test
    fun `VAD noise should not end session - should keep listening until timeout or real words`() = testScope.runTest {
        println("\n========================================")
        println("🔴 TESTING: VAD noise should NOT stop session")
        println("========================================")

        var sessionActive = true

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } answers {
                sessionActive = true
            }
            coEvery { stop(any()) } answers {
                sessionActive = false
            }
            every { switchMicMode(any()) } just runs
            every { resetTurnAfterNoise() } just runs  // ✅ ADD THIS LINE
        }

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        val testController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = { turnCompleteCalled++ },
            onTapToSpeak = { tapToSpeakCalled++ }
        )

        println("📍 Step 1: User taps to speak (11s timeout starts)")
        testController.startListening()
        advanceUntilIdle()

        assertTrue(sessionActive, "Session should be active after start")
        coVerify(exactly = 1) { geminiStt.start(false) }

        println("📍 Step 2: VAD detects noise → empty transcript received")
        testController.onFinalTranscript("")
        advanceUntilIdle()

        println("🔍 CRITICAL VERIFICATION: Session should STILL be active")
        assertTrue(
            sessionActive,
            "Session should keep listening after VAD noise"
        )

        // Should NOT call stop after noise
        println("🔍 Verifying stop() was NOT called...")
        coVerify(exactly = 0) { geminiStt.stop(any()) }

        // Should NOT switch to IDLE after noise
        println("🔍 Verifying switchMicMode(IDLE) was NOT called...")
        coVerify(exactly = 0) { geminiStt.switchMicMode(MicrophoneSession.Mode.IDLE) }

        // SHOULD call resetTurnAfterNoise
        println("🔍 Verifying resetTurnAfterNoise() WAS called...")
        verify(exactly = 1) { geminiStt.resetTurnAfterNoise() }

        println("📍 Step 3: User says real words")
        every { interruption.checkTranscriptForInterruption("Hello world") } returns false
        testController.onFinalTranscript("Hello world")
        advanceUntilIdle()

        println("🔍 VERIFICATION: Real words should start turn")
        verify { engine.startTurn("Hello world", any()) }

        println("\n========================================")
        println("✅ VAD NOISE HANDLING TEST COMPLETE!")
        println("========================================\n")
    }

    // ========================================
    // EXISTING TESTS CONTINUE...
    // ========================================

    @Test
    fun `onFinalTranscript empty during auto-listen - retries`() = testScope.runTest {
        flowController.startAutoListening()
        advanceUntilIdle()

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        coVerify(atLeast = 2) { sttController.start(isAutoListen = true) }
    }

    @Test
    fun `onFinalTranscript with valid text after retries - resets retry counter`() = testScope.runTest {
        flowController.startAutoListening()
        advanceUntilIdle()

        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        flowController.onFinalTranscript("Hello")
        advanceUntilIdle()

        verify { engine.startTurn("Hello", any()) }
    }

    @Test
    fun `onFinalTranscript during accumulation - combines transcript`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns true
        every { interruption.combineTranscript(any()) } returns "Combined text"

        flowController.onFinalTranscript("new part")
        advanceUntilIdle()

        verify { interruption.combineTranscript("new part") }
        verify { stateManager.replaceLastUser("Combined text") }
        verify(exactly = 0) { engine.startTurn(any(), any()) }
    }

    @Test
    fun `onFinalTranscript handled by interruption - returns early`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("noise") } returns true

        flowController.onFinalTranscript("noise")
        advanceUntilIdle()

        verify(exactly = 0) { engine.startTurn(any(), any()) }
        verify(exactly = 0) { stateManager.replaceLastUser(any()) }
    }

    // ========================================
    // TURN MANAGEMENT TESTS
    // ========================================

    @Test
    fun `startTurn sets correct phase for fasterFirst`() = testScope.runTest {
        fasterFirstValue = true
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("Test")
        advanceUntilIdle()

        verify { stateManager.setPhase(GenerationPhase.GENERATING_FIRST) }
    }

    @Test
    fun `startTurn sets correct phase for regular generation`() = testScope.runTest {
        fasterFirstValue = false
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("Test")
        advanceUntilIdle()

        verify { stateManager.setPhase(GenerationPhase.SINGLE_SHOT_GENERATING) }
    }

    @Test
    fun `startTurnWithExistingHistory uses current history`() = testScope.runTest {
        flowController.startTurnWithExistingHistory()
        advanceUntilIdle()

        verify { engine.startTurnWithCurrentHistory(any()) }
        verify { interruption.onTurnStart(any()) }
        assertEquals(1, turnCompleteCalled)
    }

    @Test
    fun `startTurn uses selected model from prefs`() = testScope.runTest {
        selectedModelValue = "gemini-pro"
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("Test")
        advanceUntilIdle()

        verify { engine.startTurn("Test", "gemini-pro") }
    }

    // ========================================
    // STOP AND CLEANUP TESTS
    // ========================================

    @Test
    fun `stopAll aborts everything and sets hard stop`() = testScope.runTest {
        flowController.stopAll()
        advanceUntilIdle()

        verify { stateManager.setHardStop(true) }
        verify { engine.abort(true) }
        verify { tts.stop() }
        coVerify { sttController.stop(false) }
        verify { stateManager.setPhase(GenerationPhase.IDLE) }
        verify { interruption.reset() }
    }

    @Test
    fun `clearConversation resets all state`() = testScope.runTest {
        flowController.clearConversation()
        advanceUntilIdle()

        verify { interruption.reset() }
        verify { engine.abort(true) }
        verify { tts.stop() }
        verify { stateManager.clear() }
        verify { stateManager.setPhase(GenerationPhase.IDLE) }
        coVerify { sttController.stop(false) }
    }

    @Test
    fun `cleanup releases resources`() = testScope.runTest {
        flowController.cleanup()

        verify { interruption.reset() }
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    fun `multiple empty transcripts eventually stop retrying`() = testScope.runTest {
        flowController.startAutoListening()
        advanceUntilIdle()

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        coVerify(atMost = 4) { sttController.start(isAutoListen = true) }
        verify(atLeast = 1) { stateManager.setListening(false) }
    }

    @Test
    fun `startListening while speaking - interrupts playback`() = testScope.runTest {
        isSpeakingFlow.value = true

        flowController.startListening()
        advanceUntilIdle()

        verify { tts.stop() }
        coVerify { sttController.start(isAutoListen = false) }
    }

    @Test
    fun `onFinalTranscript with whitespace only - treated as empty`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("   \n  \t  ")
        advanceUntilIdle()

        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        verify(exactly = 0) { engine.startTurn(any(), any()) }
    }

    @Test
    fun `VAD noise should keep session active - isListening should remain true`() = testScope.runTest {
        println("\n========================================")
        println("🔴 TESTING: isListening state after VAD noise")
        println("========================================")

        // Track actual state changes
        val listeningStates = mutableListOf<Boolean>()

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } answers {
                // ✅ FIX: Simulate what real STT does
                stateManager.setListening(true)
            }
            coEvery { stop(any()) } just runs
            every { switchMicMode(any()) } just runs
            every { resetTurnAfterNoise() } answers {
                // ✅ FIX: Simulate what resetTurnAfterNoise does
                stateManager.setListening(true)
            }
        }

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // Capture all setListening calls
        every { stateManager.setListening(any()) } answers {
            val value = firstArg<Boolean>()
            listeningStates.add(value)
            println("📍 setListening($value) called")
        }

        val testController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = { turnCompleteCalled++ },
            onTapToSpeak = { tapToSpeakCalled++ }
        )

        println("📍 Step 1: Start listening")
        testController.startListening()
        advanceUntilIdle()

        println("📍 Listening states so far: $listeningStates")
        assertTrue(listeningStates.contains(true), "Should have set listening=true")
        listeningStates.clear()

        println("\n📍 Step 2: Receive empty transcript (VAD noise)")
        testController.onFinalTranscript("")
        advanceUntilIdle()

        println("📍 Listening state changes after noise: $listeningStates")

        // The key check: after noise, listening should be restored to true
        println("\n🔍 CRITICAL CHECK: Final listening state")
        val finalState = listeningStates.lastOrNull()
        println("   Final state: $finalState")
        println("   All states: $listeningStates")

        assertEquals(
            true,
            finalState,
            "❌ BUG: After VAD noise, isListening should be restored to true! States: $listeningStates"
        )

        println("\n📍 Step 3: Try to send real words")
        every { interruption.checkTranscriptForInterruption("Hello") } returns false
        testController.onFinalTranscript("Hello")
        advanceUntilIdle()

        println("🔍 Verifying real words were processed...")
        verify { engine.startTurn("Hello", any()) }

        println("\n========================================")
        println("✅ TEST COMPLETE")
        println("========================================\n")
    }

    @Test
    fun `empty transcript should restore isListening to true`() = testScope.runTest {
        val listeningCalls = mutableListOf<Boolean>()

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            every { resetTurnAfterNoise() } answers { stateManager.setListening(true) }
        }

        every { stateManager.setListening(any()) } answers {
            listeningCalls.add(firstArg())
        }
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        val controller = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = {},
            onTapToSpeak = {}
        )

        controller.startListening()
        advanceUntilIdle()
        listeningCalls.clear()

        controller.onFinalTranscript("")
        advanceUntilIdle()

        assertEquals(true, listeningCalls.lastOrNull(), "isListening should be restored to true")
    }

    @Test
    fun `empty transcript should restart session timeout`() = testScope.runTest {
        var timeoutRestarted = false

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            every { resetTurnAfterNoise() } answers {
                timeoutRestarted = true
            }
        }

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        val controller = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = {},
            onTapToSpeak = {}
        )

        controller.startListening()
        advanceUntilIdle()

        controller.onFinalTranscript("")
        advanceUntilIdle()

        assertTrue(timeoutRestarted, "resetTurnAfterNoise should be called")
    }

    @Test
    fun `empty transcript should not switch to MONITORING mode`() = testScope.runTest {
        val modeSwitches = mutableListOf<String>()

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            every { switchMicMode(any()) } answers {
                modeSwitches.add("switchMicMode(${firstArg<MicrophoneSession.Mode>()})")
            }
            every { resetTurnAfterNoise() } just runs
        }

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        val controller = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { geminiStt },
            getPrefs = prefsProvider,
            onTurnComplete = {},
            onTapToSpeak = {}
        )

        controller.startListening()
        advanceUntilIdle()

        controller.onFinalTranscript("")
        advanceUntilIdle()

        assertFalse(
            modeSwitches.contains("switchMicMode(MONITORING)"),
            "Should NOT switch to MONITORING after noise"
        )
    }


}