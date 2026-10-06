package com.nboard.ime

import org.junit.Assert.*
import org.junit.Test

class PredictionMotionTest {
    @Test fun commonPrefixesDoNotSplitEmojiSurrogates() {
        assertEquals("hel", commonPredictionPrefix("hello", "help"))
        assertEquals("", commonPredictionPrefix("😀", "😁"))
        assertEquals("ét", commonPredictionPrefix("étude", "été"))
    }
    private fun glyphs(word: String) = predictionGraphemes(word).mapIndexed { index, (start, end) ->
        PredictionGlyph(index.toLong() + 1, word.substring(start, end), word, start, end)
    }
    @Test fun matchingLettersThroughoutTheWordKeepTheirIdentity() {
        val previous = glyphs("hello")
        val result = reconcilePredictionGlyphs(previous, "hallo")
        assertEquals(listOf(1L, 3L, 4L, 5L), result.filterNot { it.exiting }.filter { it.text != "a" }.map { it.id })
        assertTrue(result.single { it.text == "e" }.exiting)
    }
    @Test fun repeatedLettersRemainDistinctAndOrdered() {
        val previous = glyphs("bookkeeper")
        val result = reconcilePredictionGlyphs(previous, "books")
        assertEquals(listOf("b", "o", "o", "k"), result.filterNot { it.exiting }.take(4).map { it.text })
        val retained = result.filterNot { it.exiting }.mapNotNull { glyph -> previous.firstOrNull { it.id == glyph.id } }
        assertEquals(retained.map { it.start }.sorted(), retained.map { it.start })
        assertEquals(result.size, result.map { it.id }.distinct().size)
    }
    @Test fun deletedLettersReturnWithoutResettingIdentityDuringRapidBackspace() {
        val previous = glyphs("tests")
        val shorter = reconcilePredictionGlyphs(previous, "test")
        val restored = reconcilePredictionGlyphs(shorter, "tests")
        assertEquals(previous.map { it.id }, restored.filterNot { it.exiting }.map { it.id })
    }
    @Test fun accentsAndJoinedEmojiAnimateAsWholeGraphemes() {
        val word = "a\u0301👨‍👩‍👧‍👦🇺🇸👍🏽"
        assertEquals(listOf("a\u0301", "👨‍👩‍👧‍👦", "🇺🇸", "👍🏽"), predictionGraphemes(word).map { (start, end) -> word.substring(start, end) })
    }
    @Test fun unexpectedShortWordDoesNotDragLettersAcrossTheSlot() {
        val previous = glyphs("international")
        val result = reconcilePredictionGlyphs(previous, "in")
        for (glyph in result.filterNot { it.exiting }) {
            val old = previous.firstOrNull { it.id == glyph.id } ?: continue
            val oldPosition = old.start - old.source.length / 2f
            val newPosition = glyph.start - glyph.source.length / 2f
            assertTrue("A retained letter travelled ${kotlin.math.abs(newPosition - oldPosition)} character widths",
                kotlin.math.abs(newPosition - oldPosition) <= 2f)
        }
    }
    @Test fun onlyRelatedWordsMorphLetterByLetter() {
        assertTrue(predictionWordsRelated("hel", "hello"))
        assertTrue(predictionWordsRelated("hello", "help"))
        assertTrue(predictionWordsRelated("I", "It"))
        assertFalse(predictionWordsRelated("hello", "thanks"))
        assertFalse(predictionWordsRelated("internet", "izq"))
        assertFalse(predictionWordsRelated("", "hello"))
    }
    @Test fun typedLettersAreConfirmedInSuggestions() {
        assertEquals(3, typedPrefixLength("hello", "Hel"))
        assertEquals(1, typedPrefixLength("the", "teh"))     // correction: only the matching start
        assertEquals(2, typedPrefixLength("café", "ca"))
        assertEquals(0, typedPrefixLength("hello", ""))
    }
}
