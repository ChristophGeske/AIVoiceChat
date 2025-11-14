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

@OptIn(ExperimentalCoroutinesApi::class)
class AutoListenMultiTurnTest {

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
    private val isHearingSpeechFlow = MutableStateFlow(false)  // ✅ ADD THIS!

    private val sttStartCalls = mutableListOf<Boolean>()

    private val prefsProvider = object : ConversationFlowController.PrefsProvider {
        override fun getFasterFirst() = false
        override fun getSelectedModel() = "gemini-2.0-flash-exp"
        override fun getAutoListen() = true
    }

    @BeforeEach
    fun setup() {
        scheduler = TestCoroutineScheduler()
        testScope = TestScope(StandardTestDispatcher(scheduler))
        sttStartCalls.clear()

        // ✅ Reset all flows
        isListeningFlow.value = false
        isSpeakingFlow.value = false
        isHearingSpeechFlow.value = false
        phaseFlow.value = GenerationPhase.IDLE

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isSpeaking } returns isSpeakingFlow
            every { isHearingSpeech } returns isHearingSpeechFlow  // ✅ ADD THIS!
        }

        engine = mockk(relaxed = true)

        tts = mockk(relaxed = true) {
            every { isSpeaking } returns isSpeakingFlow
        }

        sttController = mockk(relaxed = true) {
            coEvery { start(any()) } answers {
                sttStartCalls.add(firstArg())
                isListeningFlow.value = true
            }
            coEvery { stop(any()) } just Runs
        }

        interruption = mockk(relaxed = true)

        flowController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            interruption = interruption,
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

    private fun TestScope.completeSuccessfulTurn(transcript: String) {
        flowController.startAutoListening()
        advanceUntilIdle()

        flowController.onFinalTranscript(transcript)
        advanceUntilIdle()

        isListeningFlow.value = false
        advanceUntilIdle()

        phaseFlow.value = GenerationPhase.SINGLE_SHOT_GENERATING
        advanceUntilIdle()

        isSpeakingFlow.value = true
        advanceUntilIdle()

        isSpeakingFlow.value = false
        phaseFlow.value = GenerationPhase.IDLE
        advanceUntilIdle()
    }

    @Test
    fun `✅ With Correct Simulation - 3 full conversation turns should work`() = testScope.runTest {
        println("Simulating 3 full conversation turns...")

        // Turn 1
        println("--- Turn 1 ---")
        completeSuccessfulTurn("Question 1")
        assertEquals(1, sttStartCalls.size, "STT should have started for Turn 1")

        // Turn 2
        println("--- Turn 2 ---")
        completeSuccessfulTurn("Question 2")
        assertEquals(2, sttStartCalls.size, "STT should have started for Turn 2")

        // Turn 3
        println("--- Turn 3 ---")
        completeSuccessfulTurn("Question 3")
        assertEquals(3, sttStartCalls.size, "STT should have started for Turn 3")

        println("✅ All 3 turns successfully started listening.")
    }

    @Test
    fun `✅ Manual tap during TTS should interrupt and restart`() = testScope.runTest {
        println("Simulating manual tap during TTS...")

        completeSuccessfulTurn("Question to trigger TTS")

        isSpeakingFlow.value = true
        advanceUntilIdle()

        val callsBeforeInterrupt = sttStartCalls.size
        assertEquals(1, callsBeforeInterrupt)
        assertTrue(isSpeakingFlow.value, "Precondition: TTS should be speaking")

        println("--- User taps to interrupt ---")
        flowController.startListening()
        advanceUntilIdle()

        verify { tts.stop() }

        val callsAfterInterrupt = sttStartCalls.size
        assertEquals(
            callsBeforeInterrupt + 1,
            callsAfterInterrupt,
            "STT should be RESTARTED after a manual interruption"
        )

        println("✅ Interruption worked as expected.")
    }
}