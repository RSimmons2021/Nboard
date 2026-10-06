package com.nboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiTonesTest {
    private val medium = EmojiTones.MODIFIERS[2]

    @Test fun toneFollowsTheFirstCodePoint() {
        assertEquals("👍$medium", EmojiTones.withTone("👍", medium))
        assertEquals("✌$medium", EmojiTones.withTone("✌️", medium)) // variation selector replaced
        assertEquals("👩$medium‍💻", EmojiTones.withTone("👩‍💻", medium))
    }

    @Test fun baseRemovesAnyToneAndDefaultRestoresYellow() {
        assertEquals("👍", EmojiTones.base("👍${EmojiTones.MODIFIERS[4]}"))
        assertEquals("👍", EmojiTones.withTone("👍${EmojiTones.MODIFIERS[0]}", null))
        assertEquals("👍${EmojiTones.MODIFIERS[0]}", EmojiTones.withTone("👍$medium", EmojiTones.MODIFIERS[0]))
    }
}
