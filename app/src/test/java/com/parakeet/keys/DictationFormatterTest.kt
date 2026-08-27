package com.parakeet.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationFormatterTest {
    @Test
    fun preservesCapitalizationAtStartOfSentence() {
        assertEquals(" Hello there.", DictationFormatter.format("Hello there.", "Done."))
        assertEquals("Hello there.", DictationFormatter.format("Hello there.", "Done. "))
    }

    @Test
    fun lowercasesContinuationAfterTrailingSpace() {
        assertEquals("this continues.", DictationFormatter.format("This continues.", "Earlier words "))
    }

    @Test
    fun insertsPeriodAfterUnpunctuatedText() {
        assertEquals(". Next thought.", DictationFormatter.format("Next thought.", "Earlier words"))
    }

    @Test
    fun insertsSpaceAfterContinuationPunctuation() {
        assertEquals(" and more.", DictationFormatter.format("And more.", "First,"))
    }

    @Test
    fun preservesPronounAndAcronymsInContinuation() {
        assertEquals("I'm ready.", DictationFormatter.format("I'm ready.", "Now "))
        assertEquals("NVIDIA works.", DictationFormatter.format("NVIDIA works.", "With "))
    }
}
