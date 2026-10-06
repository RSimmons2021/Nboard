package com.nboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AutoCorrectTest {
    @Test fun recognitionUsesCorrectionVocabularyAndRespectsActiveLanguage() {
        val engine = AutoCorrect(frenchFrequencies = mapOf("bonjour" to 10), englishFrequencies = mapOf("tomorrow" to 20))
        assertTrue(engine.isLoadedWord("Tomorrow", KeyboardLanguageMode.ENGLISH))
        assertFalse(engine.isLoadedWord("tomorow", KeyboardLanguageMode.ENGLISH))
        assertFalse(engine.isLoadedWord("tomorrow", KeyboardLanguageMode.FRENCH))
        assertTrue(engine.isLoadedWord("bonjour", KeyboardLanguageMode.BOTH))
        assertFalse(engine.isLoadedWord("tomorrow", KeyboardLanguageMode.DISABLED))
    }
    private val englishFrequencies = mapOf(
        "the" to 10_000,
        "you" to 8_000,
        "i" to 7_500,
        "hello" to 6_000,
        "receive" to 5_000,
        "weird" to 4_000,
        "occurred" to 4_500,
        "is" to 9_000,
        "are" to 8_500,
        "have" to 8_200,
        "has" to 7_200
    )

    private val frenchFrequencies = mapOf(
        "je" to 9_000,
        "de" to 9_500,
        "bonjour" to 6_500,
        "merci" to 6_200,
        "toujours" to 4_800,
        "aujourd'hui" to 4_600,
        "est" to 7_000,
        "sont" to 5_200,
        "le" to 8_200,
        "la" to 8_000
    )

    private fun createEngine(mode: AutoCorrect.AutoCorrectMode): AutoCorrect {
        return AutoCorrect(
            frenchFrequencies = frenchFrequencies,
            englishFrequencies = englishFrequencies,
            mode = mode
        )
    }

    @Test
    fun englishCorrections_work() {
        val engine = createEngine(AutoCorrect.AutoCorrectMode.ENGLISH_ONLY)
        assertEquals("hello", engine.correct("helo", null))
        assertEquals("receive", engine.correct("recieve", null))
        assertEquals("the", engine.correct("teh", null))
        assertEquals("weird", engine.correct("wierd", null))
        assertEquals("occurred", engine.correct("occured", null))
    }

    @Test
    fun frenchCorrections_work() {
        val engine = createEngine(AutoCorrect.AutoCorrectMode.FRENCH_ONLY)
        assertEquals("bonjour", engine.correct("bonjur", null))
        assertEquals("merci", engine.correct("mersi", null))
        assertEquals("toujours", engine.correct("toujour", null))
        assertEquals("aujourd'hui", engine.correct("aujourdhui", null))
    }

    @Test
    fun bilingualContext_prefersLanguageIndicators() {
        val engine = createEngine(AutoCorrect.AutoCorrectMode.BILINGUAL)
        assertEquals("hello", engine.correct("helo", "I"))
        assertEquals("merci", engine.correct("mersi", "Je"))
    }

    @Test
    fun doesNotOverCorrectKnownWords_orInvalidInput() {
        val engine = createEngine(AutoCorrect.AutoCorrectMode.BILINGUAL)
        assertNull(engine.correct("hello", "i"))
        assertNull(engine.correct("bonjour", "je"))
        assertNull(engine.correct("", "je"))
        assertNull(engine.correct("a", "je"))
    }

    @Test
    fun setModeFromKeyboardMode_updatesCorrectionLanguage() {
        val engine = createEngine(AutoCorrect.AutoCorrectMode.BILINGUAL)

        engine.setModeFromKeyboardMode(KeyboardLanguageMode.FRENCH)
        assertNull(engine.correct("helo", null))
        assertEquals("merci", engine.correct("mersi", null))

        engine.setModeFromKeyboardMode(KeyboardLanguageMode.ENGLISH)
        assertEquals("hello", engine.correct("helo", null))
        assertNull(engine.correct("mersi", null))

        engine.setModeFromKeyboardMode(KeyboardLanguageMode.DISABLED)
        assertEquals("merci", engine.correct("mersi", "je"))
    }

    @Test
    fun typographicApostrophe_isNormalized() {
        val engine = createEngine(AutoCorrect.AutoCorrectMode.BILINGUAL)
        assertNull(engine.correct("aujourd’hui", "je"))
    }

    @Test
    fun bilingualWithoutLanguageHint_prefersTheLikelierSlipOverTheMoreFrequentWord() {
        val engine = AutoCorrect(
            frenchFrequencies = mapOf("able" to 10_000),
            englishFrequencies = mapOf("apple" to 5_000),
            mode = AutoCorrect.AutoCorrectMode.BILINGUAL
        )

        // A missed double letter is far likelier than pressing p for b across the keyboard.
        assertEquals("apple", engine.correct("aple", null))
    }

    @Test
    fun equallyLikelyCandidates_leaveTheWordForTheSuggestionStrip() {
        val engine = AutoCorrect(
            frenchFrequencies = emptyMap(),
            englishFrequencies = mapOf(
                "heap" to 100,
                "heat" to 100
            ),
            mode = AutoCorrect.AutoCorrectMode.ENGLISH_ONLY
        )

        assertNull(engine.correct("hea", null))
    }

    @Test
    fun neighbouringKeySlipsBeatMoreFrequentDistantWords() {
        val engine = AutoCorrect(
            frenchFrequencies = emptyMap(),
            englishFrequencies = mapOf("to" to 50_000, "of" to 60_000, "it" to 40_000, "work" to 9_000, "pork" to 9_500),
            mode = AutoCorrect.AutoCorrectMode.ENGLISH_ONLY
        )
        // o and p are neighbours; w and p are not.
        assertEquals("work", engine.correct("wprk", null))
    }

    @Test
    fun personalContextTipsACloseCall() {
        val engine = AutoCorrect(
            frenchFrequencies = emptyMap(),
            englishFrequencies = mapOf("heap" to 100, "heat" to 100),
            mode = AutoCorrect.AutoCorrectMode.ENGLISH_ONLY
        )
        engine.contextProbability = { previous, word -> if (previous == "the" && word == "heat") 0.05 else null }
        assertEquals("heat", engine.correct("hea", "the"))
    }
}
