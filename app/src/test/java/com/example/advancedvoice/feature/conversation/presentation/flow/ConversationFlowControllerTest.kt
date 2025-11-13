package com.example.advancedvoice.feature.conversation.presentation.flow

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
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

    // ✅ FIXED: Use simple variables instead of properties to avoid clash
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

        // Reset prefs values
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
        // When: Start listening
        flowController.startListening()
        advanceUntilIdle()

        // Then: Should start STT and add placeholder
        coVerify { sttController.start(isAutoListen = false) }
        verify { stateManager.addUserStreamingPlaceholder() }
        verify { interruption.reset() }
        assertEquals(1, tapToSpeakCalled)
    }

    @Test
    fun `startListening while generating - interrupts and starts new session`() = testScope.runTest {
        // Given: Engine is active
        every { engine.isActive() } returns true

        // When: Start listening
        flowController.startListening()
        advanceUntilIdle()

        // Then: Should stop all and start new session
        verify { interruption.reset() }
        verify { engine.abort(true) }
        verify { tts.stop() }
        coVerify { sttController.stop(false) }
        coVerify { sttController.start(isAutoListen = false) }
    }

    @Test
    fun `startListening while already listening - ignores duplicate call`() = testScope.runTest {
        // Given: Already listening
        isListeningFlow.value = true

        // When: Start listening again
        flowController.startListening()
        advanceUntilIdle()

        // Then: Should NOT add placeholder when already listening (actual behavior)
        verify(exactly = 0) { stateManager.addUserStreamingPlaceholder() }
        coVerify(exactly = 0) { sttController.start(any()) }

        // But should still call tapToSpeak callback
        assertEquals(1, tapToSpeakCalled)
    }

    @Test
    fun `startAutoListening starts with auto-listen flag`() = testScope.runTest {
        // When: Start auto-listen
        flowController.startAutoListening()
        advanceUntilIdle()

        // Then: Should start with auto-listen flag (verified by parameter)
        coVerify { sttController.start(isAutoListen = true) }
        verify { interruption.reset() }
    }

    // ========================================
    // TRANSCRIPT HANDLING TESTS
    // ========================================

    @Test
    fun `onFinalTranscript with valid text - starts turn`() = testScope.runTest {
        // Given: Not interrupted
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Transcript arrives
        flowController.onFinalTranscript("Hello world")
        advanceUntilIdle()

        // Then: Should flush buffer, replace user, start turn
        verify { interruption.flushBufferedAssistantIfAny() }
        verify { stateManager.replaceLastUser("Hello world") }
        verify { engine.startTurn("Hello world", any()) }
        assertEquals(1, turnCompleteCalled)
    }

    @Test
    fun `onFinalTranscript with empty text - removes placeholder and stops STT`() = testScope.runTest {
        // Given: Normal (non-auto-listen) mode
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Empty transcript
        flowController.onFinalTranscript("")
        advanceUntilIdle()

        // Then: Should remove placeholder and stop STT
        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        coVerify { sttController.stop(false) }
        verify { stateManager.setListening(false) }
    }

    @Test
    fun `onFinalTranscript empty during auto-listen - retries`() = testScope.runTest {
        // Given: Auto-listen mode (triggered by startAutoListening)
        flowController.startAutoListening()
        advanceUntilIdle()

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: First empty transcript
        flowController.onFinalTranscript("")
        advanceTimeBy(500) // Wait for retry delay
        advanceUntilIdle()

        // Then: Should retry (start called at least twice: initial + retry)
        coVerify(atLeast = 2) { sttController.start(isAutoListen = true) }
    }

    @Test
    fun `onFinalTranscript with valid text after retries - resets retry counter`() = testScope.runTest {
        // Given: Auto-listen with previous empty transcripts
        flowController.startAutoListening()
        advanceUntilIdle()

        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // Empty transcript (triggers retry)
        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        // When: Valid transcript arrives
        flowController.onFinalTranscript("Hello")
        advanceUntilIdle()

        // Then: Should process normally
        verify { engine.startTurn("Hello", any()) }
    }

    @Test
    fun `onFinalTranscript during accumulation - combines transcript`() = testScope.runTest {
        // Given: Accumulating
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns true
        every { interruption.combineTranscript(any()) } returns "Combined text"

        // When: Transcript arrives
        flowController.onFinalTranscript("new part")
        advanceUntilIdle()

        // Then: Should combine and update, NOT start turn
        verify { interruption.combineTranscript("new part") }
        verify { stateManager.replaceLastUser("Combined text") }
        verify(exactly = 0) { engine.startTurn(any(), any()) }
    }

    @Test
    fun `onFinalTranscript handled by interruption - returns early`() = testScope.runTest {
        // Given: Interruption handles it
        every { interruption.checkTranscriptForInterruption("noise") } returns true

        // When: Transcript arrives
        flowController.onFinalTranscript("noise")
        advanceUntilIdle()

        // Then: Should not process further
        verify(exactly = 0) { engine.startTurn(any(), any()) }
        verify(exactly = 0) { stateManager.replaceLastUser(any()) }
    }

    // ========================================
    // TURN MANAGEMENT TESTS
    // ========================================

    @Test
    fun `startTurn sets correct phase for fasterFirst`() = testScope.runTest {
        // Given: Faster first enabled
        fasterFirstValue = true  // ✅ FIXED: Set value directly
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Start turn
        flowController.onFinalTranscript("Test")
        advanceUntilIdle()

        // Then: Should set GENERATING_FIRST phase
        verify { stateManager.setPhase(GenerationPhase.GENERATING_FIRST) }
    }

    @Test
    fun `startTurn sets correct phase for regular generation`() = testScope.runTest {
        // Given: Faster first disabled
        fasterFirstValue = false  // ✅ FIXED: Set value directly
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Start turn
        flowController.onFinalTranscript("Test")
        advanceUntilIdle()

        // Then: Should set SINGLE_SHOT_GENERATING phase
        verify { stateManager.setPhase(GenerationPhase.SINGLE_SHOT_GENERATING) }
    }

    @Test
    fun `startTurnWithExistingHistory uses current history`() = testScope.runTest {
        // When: Start with existing history
        flowController.startTurnWithExistingHistory()
        advanceUntilIdle()

        // Then: Should use engine method
        verify { engine.startTurnWithCurrentHistory(any()) }
        verify { interruption.onTurnStart(any()) }
        assertEquals(1, turnCompleteCalled)
    }

    @Test
    fun `startTurn uses selected model from prefs`() = testScope.runTest {
        // Given: Model in prefs
        selectedModelValue = "gemini-pro"  // ✅ FIXED: Set value directly
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Start turn
        flowController.onFinalTranscript("Test")
        advanceUntilIdle()

        // Then: Should pass model name to engine
        verify { engine.startTurn("Test", "gemini-pro") }
    }

    // ========================================
    // STOP AND CLEANUP TESTS
    // ========================================

    @Test
    fun `stopAll aborts everything and sets hard stop`() = testScope.runTest {
        // When: Stop all
        flowController.stopAll()
        advanceUntilIdle()

        // Then: Should stop everything
        verify { stateManager.setHardStop(true) }
        verify { engine.abort(true) }
        verify { tts.stop() }
        coVerify { sttController.stop(false) }
        verify { stateManager.setPhase(GenerationPhase.IDLE) }
        verify { interruption.reset() }
    }

    @Test
    fun `clearConversation resets all state`() = testScope.runTest {
        // When: Clear
        flowController.clearConversation()
        advanceUntilIdle()

        // Then: Should reset everything
        verify { interruption.reset() }
        verify { engine.abort(true) }
        verify { tts.stop() }
        verify { stateManager.clear() }
        verify { stateManager.setPhase(GenerationPhase.IDLE) }
        coVerify { sttController.stop(false) }
    }

    @Test
    fun `cleanup releases resources`() = testScope.runTest {
        // When: Cleanup
        flowController.cleanup()

        // Then: Should reset interruption
        verify { interruption.reset() }
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    fun `multiple empty transcripts eventually stop retrying`() = testScope.runTest {
        // Given: Auto-listen mode
        flowController.startAutoListening()
        advanceUntilIdle()

        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Multiple empty transcripts (more than MAX_RETRIES = 2)
        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceTimeBy(500)
        advanceUntilIdle()

        // Then: Should eventually stop trying
        coVerify(atMost = 4) { sttController.start(isAutoListen = true) }
        verify(atLeast = 1) { stateManager.setListening(false) }
    }

    @Test
    fun `startListening while speaking - interrupts playback`() = testScope.runTest {
        // Given: TTS is speaking
        isSpeakingFlow.value = true

        // When: Start listening
        flowController.startListening()
        advanceUntilIdle()

        // Then: Should interrupt and start new session
        verify { tts.stop() }
        coVerify { sttController.start(isAutoListen = false) }
    }

    @Test
    fun `onFinalTranscript with whitespace only - treated as empty`() = testScope.runTest {
        // Given: Not accumulating
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        // When: Whitespace-only transcript
        flowController.onFinalTranscript("   \n  \t  ")
        advanceUntilIdle()

        // Then: Should treat as empty
        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        verify(exactly = 0) { engine.startTurn(any(), any()) }
    }
}