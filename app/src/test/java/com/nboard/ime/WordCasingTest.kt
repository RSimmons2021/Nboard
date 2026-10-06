package com.nboard.ime

import com.nboard.ime.prediction.WordCasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordCasingTest {
    @Test fun midSentenceCapitalsAreLearnedAndSentenceStartsAreNot() {
        val casing = WordCasing()
        casing.observe("Hello", sentenceInitial = true)
        assertNull(casing.preferred("hello", english = true))
        casing.observe("Jaxon", sentenceInitial = false)
        assertEquals("Jaxon", casing.preferred("jaxon", english = true))
        casing.observe("iPhone", sentenceInitial = true) // inner capital: informative even at a start
        assertEquals("iPhone", casing.preferred("iphone", english = true))
    }

    @Test fun lowercaseUseWeakensAndConfidenceNeedsRepetition() {
        val casing = WordCasing()
        casing.observe("Jaxon", false)
        assertNull(casing.preferred("jaxon", true, minimumEvidence = 2))
        casing.observe("Jaxon", false)
        assertEquals("Jaxon", casing.preferred("jaxon", true, minimumEvidence = 2))
        casing.observe("jaxon", false); casing.observe("jaxon", false)
        assertNull(casing.preferred("jaxon", true))
    }

    @Test fun displayFollowsLearnedCasingFixedEnglishAndTypedCaps() {
        val casing = WordCasing()
        casing.observe("NASA", false)
        assertEquals("NASA", casing.display("nasa", "na", sentenceStart = false, english = true))
        assertEquals("I'm", casing.display("i'm", "", sentenceStart = false, english = true))
        assertEquals("i'm", casing.display("i'm", "", sentenceStart = false, english = false))
        assertEquals("HELLO", casing.display("hello", "HE", sentenceStart = false, english = true))
        assertEquals("Hello", casing.display("hello", "", sentenceStart = true, english = true))
        assertEquals("hello", casing.display("hello", "he", sentenceStart = false, english = true))
    }

    @Test fun storageRoundTrips() {
        val casing = WordCasing()
        casing.observe("McDonald", false); casing.observe("McDonald", false)
        val restored = WordCasing().apply { restore(casing.encode()) }
        assertEquals("McDonald", restored.preferred("mcdonald", true, minimumEvidence = 2))
    }
}
