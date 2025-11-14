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
 * Tests for CORRECT VAD Noise Handling Behavior
 *
 * Expected Behavior:
 * - Empty transcripts (VAD false positives) should NOT stop the session
 * - Session continues until timeout or real speech
 * - resetTurnAfterNoise() called to restart turn
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
            every { resetTurnAfterNoise() } just runs
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
    fun `empty transcript should NOT stop STT session`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        coVerify(exactly = 0) { geminiStt.stop(any()) }
    }

    @Test
    fun `empty transcript should call resetTurnAfterNoise`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        verify(exactly = 1) { geminiStt.resetTurnAfterNoise() }
    }

    @Test
    fun `empty transcript should NOT switch to IDLE mode`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        coVerify(exactly = 0) { geminiStt.switchMicMode(MicrophoneSession.Mode.IDLE) }
    }

    @Test
    fun `multiple empty transcripts keep session active`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        repeat(3) {
            flowController.onFinalTranscript("")
            advanceUntilIdle()
        }

        verify(exactly = 3) { geminiStt.resetTurnAfterNoise() }
        coVerify(exactly = 0) { geminiStt.stop(any()) }
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
    fun `TIMEOUT signal stops session`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption(any()) } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("::TIMEOUT::")
        advanceUntilIdle()

        verify { stateManager.removeLastUserPlaceholderIfEmpty() }
        coVerify(exactly = 1) { geminiStt.stop(false) }
    }

    @Test
    fun `noise removes placeholder but continues session`() = testScope.runTest {
        every { interruption.checkTranscriptForInterruption("") } returns false
        every { interruption.isAccumulatingAfterInterrupt } returns false

        flowController.startListening()
        advanceUntilIdle()

        flowController.onFinalTranscript("")
        advanceUntilIdle()

        verify(exactly = 1) { stateManager.removeLastUserPlaceholderIfEmpty() }
        coVerify(exactly = 0) { geminiStt.stop(any()) }
    }
}