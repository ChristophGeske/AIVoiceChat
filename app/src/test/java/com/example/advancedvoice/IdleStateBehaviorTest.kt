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

@OptIn(ExperimentalCoroutinesApi::class)
class IdleStateBehaviorTest {

    private lateinit var testScope: TestScope
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var stateManager: ConversationStateManager
    private lateinit var sttController: SttController
    private lateinit var flowController: ConversationFlowController

    private var isAutoListenEnabledInPrefs = false

    private val prefsProvider = object : ConversationFlowController.PrefsProvider {
        override fun getFasterFirst() = false
        override fun getSelectedModel() = "test-model"
        override fun getAutoListen() = isAutoListenEnabledInPrefs
    }

    @BeforeEach
    fun setup() {
        scheduler = TestCoroutineScheduler()
        testScope = TestScope(StandardTestDispatcher(scheduler))

        isAutoListenEnabledInPrefs = false

        stateManager = mockk()

        // Define behavior for functions that return values
        every { stateManager.isSpeaking } returns MutableStateFlow(false)
        every { stateManager.isListening } returns MutableStateFlow(false)
        every { stateManager.isHearingSpeech } returns MutableStateFlow(false)
        every { stateManager.phase } returns MutableStateFlow(GenerationPhase.IDLE)

        // ✅ THE FINAL FIX IS HERE: This function returns an Int.
        every { stateManager.addUserStreamingPlaceholder() } returns 0

        // Define behavior for functions that return Unit
        every { stateManager.removeLastUserPlaceholderIfEmpty() } returns Unit
        every { stateManager.replaceLastUser(any()) } returns Unit
        every { stateManager.setHardStop(any()) } returns Unit
        every { stateManager.setPhase(any()) } returns Unit
        every { stateManager.setListening(any()) } returns Unit
        every { stateManager.setHearingSpeech(any()) } returns Unit
        every { stateManager.setTranscribing(any()) } returns Unit

        sttController = mockk {
            coEvery { start(any()) } just runs
            coEvery { stop(any()) } just runs
        }

        flowController = ConversationFlowController(
            scope = testScope,
            stateManager = stateManager,
            engine = mockk(relaxed = true) {
                every { isActive() } returns false
            },
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
    fun `after a manual turn ends with stopAll, stt stop is called`() = testScope.runTest {
        isAutoListenEnabledInPrefs = false

        flowController.startListening()
        scheduler.runCurrent()

        flowController.onFinalTranscript("This is a manual turn.")
        scheduler.runCurrent()

        flowController.stopAll()
        scheduler.runCurrent()

        coVerify(atLeast = 1) { sttController.stop(false) }
    }

    @Test
    fun `after an auto-listen turn ends with stopAll, stt stop is called`() = testScope.runTest {
        isAutoListenEnabledInPrefs = true

        flowController.startAutoListening()
        scheduler.runCurrent()

        flowController.onFinalTranscript("This is an auto-listen turn.")
        scheduler.runCurrent()

        flowController.stopAll()
        scheduler.runCurrent()

        coVerify(atLeast = 1) { sttController.stop(false) }
    }
}