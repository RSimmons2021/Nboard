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
        assertEquals(listOf(1L, 2L, 3L, 4L), result.filterNot { it.exiting }.take(4).map { it.id })
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
}
