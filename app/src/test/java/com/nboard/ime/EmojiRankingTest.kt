package com.nboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiRankingTest {
    @Test fun frequentFavoritesComeBeforeOccasionalChoicesAndDefaults() {
        assertEquals(listOf("🦄", "🙂", "👍"), mostUsedEmojis(mapOf("🙂" to 1, "🦄" to 20), listOf("👍", "🙂")))
    }
    @Test fun emptyHistoryHasDefaultsAndStableTiesDoNotDependOnMapOrder() {
        assertEquals(listOf("👍", "🙂"), mostUsedEmojis(emptyMap(), listOf("👍", "👍", "🙂")))
        val first = mostUsedEmojis(linkedMapOf("🙂" to 3, "🦄" to 3), emptyList())
        assertEquals(first, mostUsedEmojis(linkedMapOf("🦄" to 3, "🙂" to 3), emptyList()))
        assertEquals(2, mostUsedEmojis(mapOf("🦄" to 10), listOf("👍", "🙂"), 2).size)
    }
}
