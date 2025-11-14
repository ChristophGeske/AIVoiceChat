package com.example.advancedvoice.feature.conversation.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Pure logic test for timeout vs. speech detection.
 *
 * This does NOT depend on Android Context, Prefs, or GeminiLiveSttController.
 * Instead it exercises equivalent logic in a small fake class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveSttTimeoutTest {

    private lateinit var testScope: TestScope

    @BeforeEach
    fun setup() {
        testScope = TestScope(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    /**
     * Minimal fake that mirrors the timeout + speech behavior
     * from GeminiLiveSttController.
     */
    private class FakeTimeoutController(
        private val scope: TestScope,
        private val timeoutMs: Long
    ) {
        var isListening = false
            private set

        var speechDetected = false
            private set

        var timeoutFired = false
            private set

        private var timeoutJob: Job? = null

        fun start() {
            isListening = true
            timeoutFired = false

            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(timeoutMs)
                if (isListening && !speechDetected) {
                    timeoutFired = true
                    isListening = false
                }
            }
        }

        fun onSpeechStart() {
            speechDetected = true
            // This is the critical line that was missing in real code:
            timeoutJob?.cancel()
        }

        fun onStop() {
            isListening = false
            timeoutJob?.cancel()
        }
    }

    @Test
    fun `timeout fires when no speech detected`() = testScope.runTest {
        val fake = FakeTimeoutController(this, timeoutMs = 5000)

        fake.start()
        advanceTimeBy(5000)
        advanceUntilIdle()

        assertTrue(fake.timeoutFired, "Timeout should fire when no speech occurs")
        assertFalse(fake.isListening, "Listening should be false after timeout")
    }

    @Test
    fun `timeout is cancelled when speech is detected before expiry`() = testScope.runTest {
        val fake = FakeTimeoutController(this, timeoutMs = 5000)

        fake.start()

        // 1s passes, then speech starts
        advanceTimeBy(1000)
        fake.onSpeechStart()
        advanceUntilIdle()

        // Advance beyond original timeout
        advanceTimeBy(5000)
        advanceUntilIdle()

        assertFalse(fake.timeoutFired, "Timeout should NOT fire after speech is detected")
        assertTrue(fake.isListening, "Should still be listening after speech")
    }
}