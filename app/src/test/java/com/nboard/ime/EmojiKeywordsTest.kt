package com.nboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EmojiKeywordsTest {
    private val keywords = EmojiKeywords.parse(sequenceOf(
        "# header", "pizza\t🍕", "birthday\t🎂 🥳 🎉", "dog\t🐶 🐕", "love\t❤️ 😍 🥰"))

    @Test fun wordBeingTypedAndWordJustFinished() {
        assertEquals(listOf("🍕"), keywords.suggest("I want pizza"))
        assertEquals(listOf("🎂", "🥳"), keywords.suggest("happy birthday "))
        assertEquals(listOf("🐶", "🐕"), keywords.suggest("two dogs"))
    }

    @Test fun nothingOnceTheMomentHasPassedOrInsideATokenOrForPartialWords() {
        assertEquals(emptyList<String>(), keywords.suggest("happy birthday  "))
        assertEquals(emptyList<String>(), keywords.suggest("happy birthday. "))
        assertEquals(emptyList<String>(), keywords.suggest("www.pizza"))
        assertEquals(emptyList<String>(), keywords.suggest("piz"))
    }

    @Test fun theUsersFavouriteComesFirst() {
        assertEquals(listOf("🥰", "❤️"), keywords.suggest("love", usage = mapOf("🥰" to 5)))
    }

    @Test fun bundledTableCoversCommonWords() {
        val table = EmojiKeywords.parse(File("src/main/assets/${EmojiKeywords.ASSET_PATH}").readLines().asSequence())
        assertEquals("🍕", table.suggest("pizza").first())
        assertEquals("😂", table.suggest("lol").first())
        assertEquals("🔥", table.suggest("fire").first())
        assertEquals(emptyList<String>(), table.suggest("the "))
    }
}
