package com.example.advancedvoice.feature.conversation.presentation.flow

import com.example.advancedvoice.core.audio.MicrophoneSession
import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
import com.example.advancedvoice.feature.conversation.presentation.interruption.InterruptionManager
import com.example.advancedvoice.feature.conversation.presentation.state.ConversationStateManager
import com.example.advancedvoice.feature.conversation.service.GeminiLiveSttController
import com.example.advancedvoice.feature.conversation.service.TtsController
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for VAD Noise Handling Behavior
 *
 * Expected Behavior:
 * - Empty transcripts (VAD false positives/noise) switch to MONITORING
 * - TIMEOUT signal switches to IDLE and stops session
 * - Real speech after noise is processed normally
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFlowNoiseTest {

    private lateinit var testScope: TestScope
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var interruption: InterruptionManager
    private lateinit var geminiStt: GeminiLiveSttController
    private lateinit var flowController: ConversationFlowController

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isListeningFlow = MutableStateFlow(false)

    @BeforeEach
    fun setup() {
        testScope = TestScope(StandardTestDispatcher())

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isHearingSpeech } returns MutableStateFlow(false)
            every { isSpeaking } returns MutableStateFlow(false)
            every { isTranscribing } returns MutableStateFlow(false)
            every { conversation } returns MutableStateFlow(emptyList())
        }

        engine = mockk(relaxed = true) {
            every { isActive() } returns false
        }

        tts = mockk(relaxed = true)
        interruption = mockk(relaxed = true)

        geminiStt = mockk(relaxed = true) {
            coEvery { start(any()) } just runs
            coEvery { stop(any()) } just runs
            every { switchMicMode(any()) } just runs
        }

        val prefsProvider = object : ConversationFlowController.PrefsProvider {
            override fun getFasterFirst() = false
            override fun getSelectedModel() = "gemini-2.0-flash-exp"
            override fun getAutoListen() = false
        }

        flowController = ConversationFlowController(
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
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `empty transcript should switch to MONITORING mode`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        verify(exactly = 1) { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }
    }

    @Test
    fun `empty transcript should remove placeholder`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        verify(exactly = 1) { stateManager.removeLastUserPlaceholderIfEmpty() }
    }

    @Test
    fun `empty transcript should clear listening flags`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        verify { stateManager.setListening(false) }
        verify { stateManager.setHearingSpeech(false) }
        verify { stateManager.setTranscribing(false) }
    }

    @Test
    fun `multiple empty transcripts each switch to MONITORING`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        repeat(3) {
            flowController.onFinalTranscript("")
            advanceUntilIdle()
        }

        verify(atLeast = 3) { geminiStt.switchMicMode(MicrophoneSession.Mode.MONITORING) }
        verify(exactly = 3) { stateManager.removeLastUserPlaceholderIfEmpty() }
    }

    @Test
    fun `real speech after noise is processed normally`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        flowController.onFinalTranscript("Hello world")
        advanceUntilIdle()

        verify { stateManager.replaceLastUser("Hello world") }
        verify { engine.startTurn("Hello world", any()) }
    }

    @Test
    fun `TIMEOUT signal stops session and switches to IDLE`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false
        every { interruption.isEvaluatingBargeIn } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("::TIMEOUT::")
        advanceUntilIdle()

        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        coVerify(exactly = 1) { geminiStt.stop(false) }
        verify(exactly = 1) { geminiStt.switchMicMode(MicrophoneSession.Mode.IDLE) }
    }

    @Test
    fun `noise during accumulation is handled by interruption manager`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns true

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        // Should not switch modes during accumulation
        verify(exactly = 0) { geminiStt.switchMicMode(any()) }
    }

    @Test
    fun `timeout during evaluation flushes buffered response`() = testScope.runTest {
        every { interruption.isEvaluatingBargeIn } returns true
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("::TIMEOUT::")
        advanceUntilIdle()

        verify(exactly = 1) { interruption.handleTimeoutDuringEvaluation() }
    }
}