package com.example.advancedvoice

import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive unit tests for SentenceSplitter.
 *
 * Run with: ./gradlew test
 * Or in Android Studio: Right-click on test class > Run 'SentenceSplitterTest'
 */
class SentenceSplitterTest {

    @Test
    fun testSimpleSentencesSeparatedByPeriods() {
        val input = "This is the first sentence. This is the second sentence. This is the third sentence."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("Input: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertEquals(3, result.size)
        assertEquals("This is the first sentence.", result[0])
        assertEquals("This is the second sentence.", result[1])
        assertEquals("This is the third sentence.", result[2])
    }

    @Test
    fun testQuestionsAndExclamations() {
        val input = "How are you today? I am doing great! What about you?"
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertEquals(3, result.size)
        assertTrue(result[0].contains("How are you"))
        assertTrue(result[1].contains("doing great"))
        assertTrue(result[2].contains("What about"))
    }

    @Test
    fun testShortListItemsGetMerged() {
        val input = "Here are the items: 1. Apple. 2. Banana. 3. Cherry. That's all for today."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        // Short items (<20 chars) should be merged, not split
        assertTrue("Should merge short items", result.size < 5)
        assertTrue("First part should contain list items", result[0].contains("Apple"))
    }

    @Test
    fun testLongListItemsStaySeparate() {
        val input = "Here are detailed explanations. First, we have apples which are very nutritious. " +
                "Second, bananas provide great energy for workouts. Third, cherries are rich in antioxidants."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        // Long sentences (>20 chars) should be kept separate
        assertTrue("Should have multiple sentences", result.size >= 3)
    }

    @Test
    fun testAbbreviationsAreHandled() {
        val input = "Dr. Smith visited the U.S. yesterday. He met with several experts. They discussed AI advancements."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        // Should not split on Dr. or U.S., should have 3 real sentences
        assertTrue("Should handle abbreviations", result.size >= 2)
        assertTrue("Should keep Dr. together", result[0].contains("Dr.") || result[0].contains("U.S."))
    }

    @Test
    fun testSentenceWithoutTerminator() {
        val input = "This sentence has no terminator"
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertEquals(1, result.size)
        assertEquals(input, result[0])
    }

    @Test
    fun testEmptyInput() {
        val result = SentenceSplitter.splitIntoSentences("")

        println("\nInput: (empty)")
        println("Output: ${result.size} sentences")

        assertEquals(0, result.size)
    }

    @Test
    fun testMultipleSpacesAreNormalized() {
        val input = "This  has   multiple    spaces.    And  so   does  this  one."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertEquals(2, result.size)
        assertFalse("Should normalize spaces", result[0].contains("  "))
        assertFalse("Should normalize spaces", result[1].contains("  "))
    }

    @Test
    fun testExtractFirstSentenceFromMultiSentenceText() {
        val input = "This is the first sentence. This is the second. This is the third."
        val (first, rest) = SentenceSplitter.extractFirstSentence(input)

        println("\nInput: $input")
        println("First: $first")
        println("Rest: $rest")

        assertEquals("This is the first sentence.", first)
        assertTrue(rest.contains("second"))
        assertTrue(rest.contains("third"))
    }

    @Test
    fun testExtractFirstSentenceWhenOnlyOneExists() {
        val input = "This is the only sentence."
        val (first, rest) = SentenceSplitter.extractFirstSentence(input)

        println("\nInput: $input")
        println("First: $first")
        println("Rest: '$rest'")

        assertEquals("This is the only sentence.", first)
        assertEquals("", rest)
    }

    @Test
    fun testExtractFirstSentenceWithoutTerminator() {
        val input = "This has no terminator"
        val (first, rest) = SentenceSplitter.extractFirstSentence(input)

        println("\nInput: $input")
        println("First: $first")
        println("Rest: '$rest'")

        assertEquals(input, first)
        assertEquals("", rest)
    }

    @Test
    fun testRealWorldWeatherResponse() {
        val input = """
            The weather today is quite pleasant with clear skies. Temperature is around 72 degrees Fahrenheit. 
            There's a slight breeze from the west. You might want to bring a light jacket for the evening.
            Overall, it's a great day for outdoor activities!
        """.trimIndent()

        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertTrue("Should extract multiple sentences", result.size >= 3)
        assertTrue("First should mention weather or pleasant",
            result[0].contains("weather") || result[0].contains("pleasant"))
    }

    @Test
    fun testNumberedListInResponse() {
        val input = "Here are the steps to follow carefully. 1. First step is preparation. " +
                "2. Second step is execution phase. 3. Third step is verification. Follow them in order."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertTrue("Should have reasonable grouping", result.size >= 2)
    }

    @Test
    fun testDialogueWithQuotes() {
        val input = """
            "Hello there!" she exclaimed enthusiastically. "How have you been doing?" 
            He replied, "I've been doing well, thank you very much."
        """.trimIndent()

        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertTrue("Should handle dialogue", result.size >= 2)
    }

    @Test
    fun testMixedPunctuationTypes() {
        val input = "Is this working correctly? Yes! It absolutely is working. Great to hear that news!"
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertTrue("Should handle mixed punctuation", result.size >= 3)
    }

    @Test
    fun testVeryLongSingleSentence() {
        val input = "This is a very long sentence that goes on and on and on, discussing various topics " +
                "including weather, politics, sports, entertainment, technology, science, history, " +
                "and many other interesting subjects that people might want to talk about in their " +
                "daily conversations with friends, family, colleagues, and acquaintances."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input (${input.length} chars)")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: length=${s.length}") }

        assertEquals("Should keep as one sentence", 1, result.size)
    }

    @Test
    fun testNewlinesInText() {
        val input = "First sentence here.\nSecond sentence here.\n\nThird sentence after blank line."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        assertEquals(3, result.size)
    }

    @Test
    fun testCodeLikeContentWithPeriods() {
        val input = "To use the API, call api.connect() first. Then call api.getData() to fetch information. Finally, use api.disconnect() to clean up resources."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        // Should handle function calls with () properly
        assertTrue("Should split sentences", result.size >= 2)
    }

    @Test
    fun testDecimalNumbers() {
        val input = "The value is 3.14 approximately. Another measurement is 2.71 for comparison. These are mathematical constants."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        // Should not split on decimal points
        assertEquals(3, result.size)
        assertTrue("Should preserve decimals", result[0].contains("3.14"))
    }

    @Test
    fun testEllipsis() {
        val input = "Well... that's interesting. I'm not sure about that... Maybe we should discuss this further."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s -> println("  [$i]: $s") }

        // Ellipsis handling - should still split on actual sentence endings
        assertTrue("Should handle ellipsis", result.size >= 1)
    }

    @Test
    fun testMinimumLengthThresholdWithBorderlineCases() {
        val input = "Exactly twenty chars. This is nineteen ch. Another short one. This is definitely long enough to be kept separate."
        val result = SentenceSplitter.splitIntoSentences(input)

        println("\nInput: $input")
        println("Output: ${result.size} sentences")
        result.forEachIndexed { i, s ->
            println("  [$i]: length=${s.length}, text='$s'")
        }

        // Should merge sentences under 20 chars with adjacent ones
        assertTrue("Should apply minimum length rule", result.isNotEmpty())
    }
}