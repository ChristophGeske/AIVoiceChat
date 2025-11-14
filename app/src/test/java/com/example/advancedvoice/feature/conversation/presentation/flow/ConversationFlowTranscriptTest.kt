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
 * Transcript Handling Tests
 * - Valid text processing
 * - Accumulation during interruption
 * - Edge cases
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFlowTranscriptTest {

    private lateinit var testScope: TestScope
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var interruption: InterruptionManager
    private lateinit var sttController: SttController
    private lateinit var flowController: ConversationFlowController

    private var turnCompleteCalled = 0

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isListeningFlow = MutableStateFlow(false)

    private val prefsProvider = object : ConversationFlowController.PrefsProvider {
        override fun getFasterFirst() = false
        override fun getSelectedModel() = "gemini-2.0-flash-exp"
        override fun getAutoListen() = false
    }

    @BeforeEach
    fun setup() {
        testScope = TestScope(StandardTestDispatcher())

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isSpeaking } returns MutableStateFlow(false)
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

        flowController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
            getStt = { sttController },
            getPrefs = prefsProvider,
            onTurnComplete = { turnCompleteCalled++ },
            onTapToSpeak = {}
        )
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `onFinalTranscript with valid text starts turn`() = testScope.runTest {
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
    fun `onFinalTranscript during accumulation combines transcript`() = testScope.runTest {
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
    fun `onFinalTranscript handled by interruption returns early`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("noise") } returns true

        flowController.onFinalTranscript("noise")
        advanceUntilIdle()

        verify(exactly = 0) { engine.startTurn(any(), any()) }
        verify(exactly = 0) { stateManager.replaceLastUser(any()) }
    }

    @Test
    fun `onFinalTranscript with whitespace only treated as empty`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.onFinalTranscript("   \n  \t  ")
        advanceUntilIdle()

        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        verify(exactly = 0) { engine.startTurn(any(), any()) }
    }
}