package com.example.advancedvoice.feature.conversation.presentation

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.flow.ConversationFlowController
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
 * 🐛 BUG HUNTER: Multi-Turn Auto-Listen Tests
 *
 * These tests reveal the REAL bug: STT never stops after valid transcript
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoListenMultiTurnTest {

    private lateinit var testScope: TestScope
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var interruption: InterruptionManager
    private lateinit var sttController: GeminiLiveSttController
    private lateinit var flowController: ConversationFlowController

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isListeningFlow = MutableStateFlow(false)
    private val isSpeakingFlow = MutableStateFlow(false)
    private val isHearingSpeechFlow = MutableStateFlow(false)
    private val isTranscribingFlow = MutableStateFlow(false)

    private var autoListenValue = true
    private val sttStartCalls = mutableListOf<Boolean>()
    private val sttStopCalls = mutableListOf<Boolean>()

    private val prefsProvider = object : ConversationFlowController.PrefsProvider {
        override fun getFasterFirst() = false
        override fun getSelectedModel() = "gemini-2.0-flash-exp"
        override fun getAutoListen() = autoListenValue
    }

    @BeforeEach
    fun setup() {
        scheduler = TestCoroutineScheduler()
        testScope = TestScope(StandardTestDispatcher(scheduler))

        sttStartCalls.clear()
        sttStopCalls.clear()

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isListening } returns isListeningFlow
            every { isSpeaking } returns isSpeakingFlow
            every { isHearingSpeech } returns isHearingSpeechFlow
            every { isTranscribing } returns isTranscribingFlow
            every { conversation } returns MutableStateFlow(emptyList())
        }

        engine = mockk(relaxed = true) {
            every { isActive() } returns false
        }

        tts = mockk(relaxed = true) {
            every { isSpeaking } returns isSpeakingFlow
        }

        sttController = mockk<GeminiLiveSttController>(relaxed = true) {
            coEvery { start(any()) } answers {
                val isAutoListen = firstArg<Boolean>()
                sttStartCalls.add(isAutoListen)
                isListeningFlow.value = true
                println("📍 STT start(autoListen=$isAutoListen) - Call #${sttStartCalls.size}")
            }
            coEvery { stop(any()) } answers {
                val removeLastUser = firstArg<Boolean>()
                sttStopCalls.add(removeLastUser)
                isListeningFlow.value = false
                isHearingSpeechFlow.value = false
                isTranscribingFlow.value = false
                println("📍 STT stop(removeLastUser=$removeLastUser)")
            }
            every { resetTurnAfterNoise() } just runs
        }

        interruption = mockk<InterruptionManager>(relaxed = true) {
            every { checkTranscriptForInterruption(any()) } returns false
            every { isAccumulatingAfterInterrupt } returns false
            every { isBargeInTurn } returns false
            every { flushBufferedAssistantIfAny() } just runs
            every { reset() } just runs
            every { onTurnStart(any()) } just runs
            every { combineTranscript(any()) } answers { firstArg() }
        }

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

    @Test
    fun `🐛 BUG - Turn 2 auto-listen blocked because Turn 1 never stopped STT`() = testScope.runTest {
        println("\n" + "=".repeat(80))
        println("🐛 REAL BUG TEST: STT never stops after valid transcript")
        println("=".repeat(80))

        // ============================================================
        // TURN 1: Works fine
        // ============================================================
        println("\n📍 TURN 1: Starting...")
        flowController.startAutoListening()
        advanceUntilIdle()

        assertEquals(1, sttStartCalls.size, "Turn 1: STT should start")
        assertTrue(isListeningFlow.value, "Turn 1: Should be listening")

        println("📍 User speaks: 'What is the weather?'")
        flowController.onFinalTranscript("What is the weather?")
        advanceUntilIdle()

        // ✅ Check: Did STT stop after valid transcript?
        println("\n🔍 After Turn 1 transcript:")
        println("   STT stop() called: ${sttStopCalls.size} times")
        println("   isListening: ${isListeningFlow.value}")
        println("   isHearingSpeech: ${isHearingSpeechFlow.value}")

        // Simulate LLM response
        phaseFlow.value = GenerationPhase.SINGLE_SHOT_GENERATING
        advanceUntilIdle()
        phaseFlow.value = GenerationPhase.IDLE

        // Simulate TTS
        isSpeakingFlow.value = true
        advanceUntilIdle()
        isSpeakingFlow.value = false
        advanceUntilIdle()

        println("✅ Turn 1 complete")

        // ============================================================
        // 🔴 TURN 2: Should work, but will FAIL due to bug
        // ============================================================
        println("\n📍 TURN 2: Attempting to start auto-listen...")
        println("   Current state BEFORE Turn 2:")
        println("   - isListening: ${isListeningFlow.value}")
        println("   - isHearingSpeech: ${isHearingSpeechFlow.value}")
        println("   - isSpeaking: ${isSpeakingFlow.value}")

        val callsBefore = sttStartCalls.size

        flowController.startAutoListening()
        advanceUntilIdle()

        val callsAfter = sttStartCalls.size

        println("\n🔍 CRITICAL CHECK:")
        println("   STT calls before Turn 2: $callsBefore")
        println("   STT calls after Turn 2: $callsAfter")
        println("   isListening: ${isListeningFlow.value}")

        // 🔴 THIS WILL FAIL - Revealing the bug!
        assertEquals(
            2,
            callsAfter,
            """
            🐛 BUG DETECTED: Turn 2 auto-listen was BLOCKED!
            
            Root Cause: Turn 1 never called stop() on STT after valid transcript
            Expected: stop() should be called in handleNormalTranscript()
            Actual: isListening is still true, so Turn 2 is blocked by:
                    "Already listening, ignoring startListeningSession call"
            
            Fix needed in: ConversationFlowController.handleNormalTranscript()
            """.trimIndent()
        )

        println("✅ Turn 2 started successfully")
        println("=".repeat(80) + "\n")
    }

    @Test
    fun `🐛 BUG - 3rd iteration fails due to accumulated state`() = testScope.runTest {
        println("\n" + "=".repeat(80))
        println("🐛 REAL BUG TEST: 3 turns in a row")
        println("=".repeat(80))

        // Turn 1
        println("\n📍 TURN 1")
        flowController.startAutoListening()
        advanceUntilIdle()
        flowController.onFinalTranscript("Question 1")
        advanceUntilIdle()

        println("   STT stop calls: ${sttStopCalls.size}")
        println("   isListening: ${isListeningFlow.value}")

        // Try Turn 2
        println("\n📍 TURN 2")
        val turn2Before = sttStartCalls.size
        flowController.startAutoListening()
        advanceUntilIdle()
        val turn2After = sttStartCalls.size

        if (turn2After == turn2Before) {
            println("   ❌ Turn 2 BLOCKED (as expected due to bug)")
        } else {
            println("   ✅ Turn 2 worked (unexpected)")
        }

        // Try Turn 3
        println("\n📍 TURN 3")
        val turn3Before = sttStartCalls.size
        flowController.startAutoListening()
        advanceUntilIdle()
        val turn3After = sttStartCalls.size

        println("\n🔍 FINAL VERIFICATION:")
        println("   Total STT start calls: ${sttStartCalls.size}")
        println("   Expected: 3")
        println("   Actual: ${sttStartCalls.size}")

        assertEquals(
            3,
            sttStartCalls.size,
            "🐛 BUG: Only ${sttStartCalls.size}/3 turns worked. STT never stopped after valid transcripts."
        )
    }

    @Test
    fun `✅ CONTROL - Multiple manual tap-to-speak should work`() = testScope.runTest {
        println("\n" + "=".repeat(80))
        println("✅ CONTROL TEST: Manual tap should work (uses interruption flow)")
        println("=".repeat(80))

        // Manual taps should work because they interrupt
        println("\n📍 Manual Tap 1")
        flowController.startListening()
        advanceUntilIdle()
        assertEquals(1, sttStartCalls.size)

        println("\n📍 Manual Tap 2")
        flowController.startListening()
        advanceUntilIdle()

        println("\n📍 Manual Tap 3")
        flowController.startListening()
        advanceUntilIdle()

        println("\n🔍 Manual taps: ${sttStartCalls.size}")
        assertTrue(sttStartCalls.size >= 3, "Manual taps should work via interruption")

        println("=".repeat(80) + "\n")
    }

    @Test
    fun `🐛 BUG - STT stop should be called after valid transcript`() = testScope.runTest {
        println("\n" + "=".repeat(80))
        println("🐛 UNIT TEST: Verify stop() is called")
        println("=".repeat(80))

        flowController.startAutoListening()
        advanceUntilIdle()

        assertEquals(0, sttStopCalls.size, "Before transcript: no stop calls")

        println("\n📍 Processing valid transcript...")
        flowController.onFinalTranscript("Hello world")
        advanceUntilIdle()

        println("   STT stop calls: ${sttStopCalls.size}")

        assertEquals(
            1,
            sttStopCalls.size,
            """
            🐛 BUG: stop() was NOT called after valid transcript!
            
            Expected behavior:
              handleNormalTranscript("Hello world")
                → getStt()?.stop(false)
                → isListening = false
            
            Actual behavior:
              handleNormalTranscript("Hello world")
                → startTurn()
                → STT still running ❌
            """.trimIndent()
        )

        println("=".repeat(80) + "\n")
    }
}