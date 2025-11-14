package com.example.advancedvoice.feature.conversation.presentation.interruption

import com.example.advancedvoice.domain.engine.SentenceTurnEngine
import com.example.advancedvoice.feature.conversation.presentation.GenerationPhase
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
 * Tests for Sources Buffering Feature
 *
 * ✅ These tests verify that grounding sources are properly:
 * - Buffered along with response text during interruption evaluation
 * - Flushed when noise is confirmed
 * - Dropped when real speech interruption is confirmed
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptionManagerSourcesTest {

    private lateinit var testScope: TestScope
    private lateinit var stateManager: ConversationStateManager
    private lateinit var engine: SentenceTurnEngine
    private lateinit var tts: TtsController
    private lateinit var sttController: SttController
    private lateinit var interruptionManager: InterruptionManager

    private val phaseFlow = MutableStateFlow(GenerationPhase.IDLE)
    private val isListeningFlow = MutableStateFlow(false)
    private val isTranscribingFlow = MutableStateFlow(false)

    private val systemMessages = mutableListOf<String>()
    private val assistantMessages = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        testScope = TestScope(StandardTestDispatcher())

        systemMessages.clear()
        assistantMessages.clear()

        stateManager = mockk(relaxed = true) {
            every { phase } returns phaseFlow
            every { isHearingSpeech } returns MutableStateFlow(false)
            every { isSpeaking } returns MutableStateFlow(false)
            every { isListening } returns isListeningFlow
            every { isTranscribing } returns isTranscribingFlow
            every { conversation } returns MutableStateFlow(emptyList())
            every { addSystem(any()) } answers {
                systemMessages.add(firstArg())
            }
            every { addAssistant(any()) } answers {
                assistantMessages.add(firstArg<List<String>>().joinToString(" "))
            }
        }

        engine = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        sttController = mockk(relaxed = true)

        interruptionManager = InterruptionManager(
            scope = testScope,
            stateManager = stateManager,
            engine = engine,
            tts = tts,
            getStt = { sttController },
            startTurnWithExistingHistory = {},
            onInterruption = {},
            onNoiseConfirmed = {}
        )
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `assistantSourcesBuffer property exists`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("✅ TEST: assistantSourcesBuffer property exists")
        println("=".repeat(60))

        val hasField = try {
            val field = InterruptionManager::class.java
                .getDeclaredField("assistantSourcesBuffer")
            field.isAccessible = true
            println("✅ Field exists: ${field.name}")
            println("   Type: ${field.type}")
            true
        } catch (e: NoSuchFieldException) {
            println("❌ Field does NOT exist: assistantSourcesBuffer")
            false
        }

        assertTrue(hasField, "assistantSourcesBuffer property should exist")
        println("✅ PASS: Property exists")
        println("=".repeat(60) + "\n")
    }

    @Test
    fun `flushBufferedAssistantIfAny flushes sources correctly`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("✅ TEST: Sources should be flushed")
        println("=".repeat(60))

        // ✅ Use reflection to set BOTH buffers
        val assistantHoldBufferField = InterruptionManager::class.java
            .getDeclaredField("assistantHoldBuffer")
        assistantHoldBufferField.isAccessible = true
        assistantHoldBufferField.set(interruptionManager, "Buffered response")

        val assistantSourcesBufferField = InterruptionManager::class.java
            .getDeclaredField("assistantSourcesBuffer")
        assistantSourcesBufferField.isAccessible = true
        assistantSourcesBufferField.set(
            interruptionManager,
            listOf("https://example.com" to "Test Source")
        )

        println("📍 Buffers set: text='Buffered response', sources=1")

        // Call flush
        interruptionManager.flushBufferedAssistantIfAny()

        println("📍 After flush:")
        println("   Assistant messages: ${assistantMessages.size}")
        println("   System messages: ${systemMessages.size}")

        // Text should be flushed
        assertEquals(1, assistantMessages.size, "Text should be flushed")
        assertEquals("Buffered response", assistantMessages[0])

        // ✅ Sources should be flushed
        assertEquals(
            1,
            systemMessages.size,
            "✅ Sources should be flushed! Got: ${systemMessages.size}"
        )

        println("✅ PASS: Sources flushed correctly")
        println("=".repeat(60) + "\n")
    }

    @Test
    fun `dropBufferedAssistant drops sources from conversation`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("✅ TEST: Sources should be dropped from conversation")
        println("=".repeat(60))

        // ✅ First, set a buffered response so the drop logic actually runs
        val assistantHoldBufferField = InterruptionManager::class.java
            .getDeclaredField("assistantHoldBuffer")
        assistantHoldBufferField.isAccessible = true
        assistantHoldBufferField.set(interruptionManager, "Buffered text")

        val assistantSourcesBufferField = InterruptionManager::class.java
            .getDeclaredField("assistantSourcesBuffer")
        assistantSourcesBufferField.isAccessible = true
        assistantSourcesBufferField.set(
            interruptionManager,
            listOf("https://example.com" to "Source")
        )

        // ✅ Mock conversation with System entry
        val systemEntry = mockk<com.example.advancedvoice.domain.entities.ConversationEntry> {
            every { speaker } returns "System"
        }
        val conversationList = mutableListOf(systemEntry)
        val conversationFlow = MutableStateFlow(conversationList.toList())

        every { stateManager.conversation } returns conversationFlow
        every { stateManager.removeLastEntry() } answers {
            if (conversationList.isNotEmpty()) {
                conversationList.removeLast()
                conversationFlow.value = conversationList.toList()
            }
        }

        println("📍 Before drop: ${conversationList.size} conversation entries")
        println("📍 Buffers set: text + sources")

        // Drop buffered response
        interruptionManager.dropBufferedAssistant()

        println("📍 After drop: ${conversationList.size} conversation entries")

        // ✅ System entry should be removed
        assertEquals(
            0,
            conversationList.size,
            "✅ System entry should be dropped! Got: ${conversationList.size}"
        )

        println("✅ PASS: Sources dropped correctly")
        println("=".repeat(60) + "\n")
    }

    @Test
    fun `sources parameter accepted in API`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("✅ TEST: Sources parameter accepted")
        println("=".repeat(60))

        val testSources = listOf(
            "https://example.com/1" to "Source 1",
            "https://example.com/2" to "Source 2"
        )

        // Verify the API accepts sources without crashing
        val result = interruptionManager.maybeHandleAssistantFinalResponse(
            text = "Response text",
            sources = testSources
        )

        println("📍 Function completed successfully")
        println("📍 Sources accepted: ${testSources.size}")
        assertNotNull(result)

        println("✅ PASS: Sources parameter accepted")
        println("=".repeat(60) + "\n")
    }

    @Test
    fun `empty sources list handled correctly`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("✅ TEST: Empty sources list")
        println("=".repeat(60))

        val result = interruptionManager.maybeHandleAssistantFinalResponse(
            text = "Test",
            sources = emptyList()
        )

        assertNotNull(result)
        println("✅ PASS: Empty sources handled")
        println("=".repeat(60) + "\n")
    }

    @Test
    fun `sources buffer cleared on reset`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("✅ TEST: Sources buffer cleared on reset")
        println("=".repeat(60))

        // Set buffers using reflection
        val assistantSourcesBufferField = InterruptionManager::class.java
            .getDeclaredField("assistantSourcesBuffer")
        assistantSourcesBufferField.isAccessible = true
        assistantSourcesBufferField.set(
            interruptionManager,
            listOf("https://example.com" to "Source")
        )

        println("📍 Buffer set with 1 source")

        // Call reset
        interruptionManager.reset()

        // Check buffer is cleared
        val bufferValue = assistantSourcesBufferField.get(interruptionManager)
        assertNull(bufferValue, "Sources buffer should be cleared on reset")

        println("✅ PASS: Buffer cleared on reset")
        println("=".repeat(60) + "\n")
    }

    @Test
    fun `🔴 BUG - EngineCallbacksFactory adds sources BEFORE buffering check`() = testScope.runTest {
        println("\n" + "=".repeat(60))
        println("🔴 BUG TEST: Sources added before buffering (SHOULD FAIL)")
        println("=".repeat(60))

        // This simulates what EngineCallbacksFactory.onFinalResponse does (the bug!)

        val testSources = listOf(
            "https://example.com/1" to "Source 1",
            "https://example.com/2" to "Source 2"
        )

        // ❌ BUG: Callback adds sources to UI FIRST
        println("📍 Step 1: EngineCallbacksFactory processes sources (BUG)")
        com.example.advancedvoice.domain.util.GroundingUtils.processAndDisplaySources(testSources) { html ->
            stateManager.addSystem(html)
        }

        println("📍 Sources in UI: ${systemMessages.size}")

        // THEN checks if should buffer
        println("📍 Step 2: Then checks buffering")

        // Set evaluation mode
        val assistantHoldBufferField = InterruptionManager::class.java
            .getDeclaredField("assistantHoldBuffer")
        assistantHoldBufferField.isAccessible = true

        val isEvaluatingField = InterruptionManager::class.java
            .getDeclaredField("isEvaluatingBargeIn")
        isEvaluatingField.isAccessible = true
        isEvaluatingField.set(interruptionManager, true)

        isListeningFlow.value = true

        val buffered = interruptionManager.maybeHandleAssistantFinalResponse(
            text = "Response",
            sources = testSources
        )

        println("📍 Response buffered: $buffered")
        println("📍 Sources still in UI: ${systemMessages.size}")

        // User interrupts
        println("📍 Step 3: User interrupts")
        interruptionManager.dropBufferedAssistant()

        println("📍 Sources after drop: ${systemMessages.size}")

        // ❌ THIS SHOULD FAIL - Sources are still visible!
        assertEquals(
            0,
            systemMessages.size,
            "🐛 BUG: Sources were added BEFORE buffering check, so they're still visible! Got: ${systemMessages.size}"
        )

        println("=".repeat(60) + "\n")
    }
}