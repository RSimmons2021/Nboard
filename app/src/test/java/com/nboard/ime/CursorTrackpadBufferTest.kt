package com.nboard.ime

import org.junit.Assert.*
import org.junit.Test

class CursorTrackpadBufferTest {
    @Test fun reversesWithoutLosingCharactersAndClampsAtDocumentEdges() {
        val buffer = CursorTrackpadBuffer("hello world", 200, 5)
        assertTrue(buffer.moveCharacters(-3))
        assertEquals(202, buffer.absoluteCursor)
        assertTrue(buffer.moveCharacters(3))
        assertEquals(205, buffer.absoluteCursor)
        buffer.moveCharacters(-1000)
        assertEquals(200, buffer.absoluteCursor)
        assertFalse(buffer.moveCharacters(-1))
        assertTrue(buffer.moveCharacters(1))
        assertEquals(201, buffer.absoluteCursor)
        buffer.moveCharacters(1000)
        assertEquals(211, buffer.absoluteCursor)
    }
    @Test fun neverSplitsCombiningCharactersOrEmoji() {
        val clusters = listOf("a\u0301", "👍🏽", "👨‍👩‍👧‍👦", "🇺🇸", "☺️")
        for (cluster in clusters) {
            val text = "a${cluster}z"
            val buffer = CursorTrackpadBuffer(text, 0, text.length)
            buffer.moveCharacters(-1)
            assertEquals(text.length - 1, buffer.cursor)
            buffer.moveCharacters(-1)
            assertEquals("Must skip the whole $cluster", 1, buffer.cursor)
            buffer.moveCharacters(1)
            assertEquals(text.length - 1, buffer.cursor)
        }
    }
    @Test fun fractionalDragsAccumulateAndOverscrollCanReset() {
        val axis = CursorDragAxis(8f)
        assertEquals(0, axis.steps(-3f))
        assertEquals(-1, axis.steps(-5f))
        assertEquals(2, axis.steps(16f))
        assertEquals(0, axis.steps(7f))
        axis.reset()
        assertEquals(-1, axis.steps(-8f))
    }
    @Test fun springSettlesWithoutOvershootAndCanBeRetargeted() {
        val spring = CursorSpring(0f)
        spring.target = 100f
        repeat(8) { spring.advance(1f / 120); assertTrue(spring.position in 0f..100f) }
        val presentation = spring.position
        spring.target = 5f
        assertEquals(presentation, spring.position)
        repeat(240) { spring.advance(1f / 120) }
        assertEquals(5f, spring.position, .1f)
        spring.snap(20f)
        assertFalse(spring.advance(1f / 60))
        assertEquals(20f, spring.position)
    }
}
