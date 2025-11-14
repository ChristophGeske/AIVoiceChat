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

/**
 * Basic ConversationFlowController Tests
 * - Starting/stopping listening
 * - Turn management
 * - Cleanup
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFlowBasicTest {

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
            every { isHearingSpeech } returns MutableStateFlow(false)
            every { isTranscribing } returns MutableStateFlow(false)
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
    fun `startListening when idle starts STT session`() = testScope.runTest {
        flowController.startListening()
        advanceUntilIdle()

        coVerify { sttController.start(isAutoListen = false) }
        verify { stateManager.addUserStreamingPlaceholder() }
        verify { interruption.reset() }
        assertEquals(1, tapToSpeakCalled)
    }

    @Test
    fun `startListening while generating interrupts and starts new session`() = testScope.runTest {
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
    fun `startListening while already listening ignores duplicate call`() = testScope.runTest {
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

    @Test
    fun `startListening while speaking interrupts playback`() = testScope.runTest {
        isSpeakingFlow.value = true

        flowController.startListening()
        advanceUntilIdle()

        verify { tts.stop() }
        coVerify { sttController.start(isAutoListen = false) }
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
}