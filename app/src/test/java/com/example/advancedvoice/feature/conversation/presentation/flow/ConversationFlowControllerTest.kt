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
    // TRANSCRIPT HANDLING TESTS (✅ UPDATED)
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
    fun `onFinalTranscript with empty text - switches to MONITORING and clears states`() = testScope.runTest {
        val geminiStt = mockk<GeminiLiveSttController>(relaxed = true) {
            coEvery { start(any()) } just runs
            coEvery { switchMicMode(any()) } just runs
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

        testController.onFinalTranscript("")
        advanceUntilIdle()

        // ✅ UPDATED: Should remove placeholder
        verify { stateManager.removeLastUserPlaceholderIfEmpty() }

        // ✅ UPDATED: Should switch to MONITORING
        coVerify { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }

        // ✅ UPDATED: Should clear listening states
        verify { stateManager.setListening(false) }
        verify { stateManager.setHearingSpeech(false) }
        verify { stateManager.setTranscribing(false) }
    }

    @Test
    fun `BUGFIX - empty transcript should switch to MONITORING and stop listening`() = testScope.runTest {
        println("\n========================================")
        println("✅ TESTING: Empty transcript → MONITORING mode")
        println("========================================")

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            coEvery { switchMicMode(any()) } just runs
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

        println("📍 Starting listening session...")
        testController.startListening()
        advanceUntilIdle()

        println("✅ Session started")
        coVerify(exactly = 1) { geminiStt.start(isAutoListen = false) }

        println("📍 Processing empty transcript (VAD false positive)...")
        testController.onFinalTranscript("")
        advanceUntilIdle()

        println("✅ Empty transcript processed")

        // ✅ NEW EXPECTATIONS: Should switch to MONITORING
        println("\n🔍 VERIFICATION: Checking switchMicMode(MONITORING)...")
        coVerify(exactly = 1) { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }
        println("✅ Switched to MONITORING")

        println("\n🔍 VERIFICATION: Checking listening states cleared...")
        verify(exactly = 1) { stateManager.setListening(false) }
        verify(exactly = 1) { stateManager.setHearingSpeech(false) }
        verify(exactly = 1) { stateManager.setTranscribing(false) }
        println("✅ States cleared")

        println("\n========================================")
        println("✅ TEST PASSED - New behavior working!")
        println("========================================\n")
    }

    @Test
    fun `timeout signal should stop STT and switch to IDLE`() = testScope.runTest {
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
        every { interruption.isEvaluatingBargeIn } returns false

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

    @Test
    fun `VAD noise switches to MONITORING - ready for real interruption`() = testScope.runTest {
        println("\n========================================")
        println("✅ TESTING: VAD noise → MONITORING (ready for interruption)")
        println("========================================")

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            coEvery { switchMicMode(any()) } just runs
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

        println("📍 Step 1: User taps to speak")
        testController.startListening()
        advanceUntilIdle()

        coVerify(exactly = 1) { geminiStt.start(false) }

        println("📍 Step 2: VAD detects noise → empty transcript received")
        testController.onFinalTranscript("")
        advanceUntilIdle()

        println("🔍 VERIFICATION: Should switch to MONITORING")
        coVerify(exactly = 1) { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }

        println("🔍 VERIFICATION: Should clear listening states")
        verify { stateManager.setListening(false) }
        verify { stateManager.setHearingSpeech(false) }
        verify { stateManager.setTranscribing(false) }

        println("\n========================================")
        println("✅ VAD NOISE HANDLING TEST COMPLETE!")
        println("========================================\n")
    }

    // ========================================
    // REMAINING TESTS (✅ UPDATED)
    // ========================================

    @Test
    fun `onFinalTranscript empty during auto-listen - switches to MONITORING`() = testScope.runTest {
        val geminiStt = mockk<GeminiLiveSttController>(relaxed = true)

        val testController = ConversationFlowController(
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

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        testController.startAutoListening()
        advanceUntilIdle()

        testController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        // ✅ UPDATED: Should switch to MONITORING
        coVerify(exactly = 1) { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }
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
    // EDGE CASES (✅ UPDATED)
    // ========================================

    @Test
    fun `multiple empty transcripts switch to MONITORING each time`() = testScope.runTest {
        val geminiStt = mockk<GeminiLiveSttController>(relaxed = true)

        val testController = ConversationFlowController(
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

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        testController.startAutoListening()
        advanceUntilIdle()

        // Send 5 empty transcripts
        repeat(5) {
            testController.onFinalTranscript("")
            advanceTimeBy(500)
            advanceUntilIdle()
        }

        // ✅ UPDATED: Should have switched to MONITORING 5 times
        coVerify(exactly = 5) { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }
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
    fun `empty transcript should switch to MONITORING mode`() = testScope.runTest {
        val modeSwitches = mutableListOf<MicrophoneSession.Mode>()

        val geminiStt = mockk<GeminiLiveSttController>(relaxed = false) {
            coEvery { start(any()) } just runs
            coEvery { switchMicMode(any()) } answers {
                modeSwitches.add(firstArg())
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

        assertTrue(
            modeSwitches.contains(MicrophoneSession.Mode.MONITORING),
            "Should switch to MONITORING after noise"
        )
    }
}