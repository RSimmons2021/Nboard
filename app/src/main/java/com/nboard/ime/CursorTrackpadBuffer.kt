package com.nboard.ime

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/** A gesture-local editor snapshot. Offsets always land between complete graphemes. */
internal class CursorTrackpadBuffer(val text: String, val startOffset: Int, initialCursor: Int) {
    private val boundaries = buildList {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) { add(boundary); boundary = iterator.next() }
    }.toIntArray()
    var cursor = nearestBoundary(initialCursor)
        private set
    val absoluteCursor: Int get() = startOffset + cursor

    fun moveCharacters(steps: Int): Boolean {
        val index = boundaries.binarySearch(cursor).coerceAtLeast(0)
        return moveTo(boundaries[(index + steps).coerceIn(0, boundaries.lastIndex)])
    }

    fun moveTo(offset: Int): Boolean {
        val next = nearestBoundary(offset)
        if (next == cursor) return false
        cursor = next
        return true
    }

    private fun nearestBoundary(offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        val index = boundaries.binarySearch(bounded)
        if (index >= 0) return bounded
        val insertion = -index - 1
        val before = boundaries[(insertion - 1).coerceAtLeast(0)]
        val after = boundaries[insertion.coerceAtMost(boundaries.lastIndex)]
        return if (bounded - before <= after - bounded) before else after
    }
}

/** Keeps fractional movement; clears overscroll so reversing at an edge responds immediately. */
internal class CursorDragAxis(private val pixelsPerStep: Float) {
    private var remainder = 0f
    fun steps(delta: Float): Int {
        remainder += delta
        val count = (remainder / pixelsPerStep).toInt()
        remainder -= count * pixelsPerStep
        return count
    }
    fun reset() { remainder = 0f }
}

/** Closed-form, critically damped spring: retargeting preserves position and velocity. */
internal class CursorSpring(var position: Float = 0f) {
    var target = position
    private var velocity = 0f
    fun snap(value: Float) { position = value; target = value; velocity = 0f }
    fun advance(seconds: Float): Boolean {
        val dt = seconds.coerceIn(0f, .05f)
        val displacement = position - target
        val c = velocity + OMEGA * displacement
        val decay = exp(-OMEGA * dt)
        position = target + (displacement + c * dt) * decay
        velocity = (velocity - OMEGA * c * dt) * decay
        if (abs(position - target) < .1f && abs(velocity) < .5f) { snap(target); return false }
        return true
    }
    private companion object { const val OMEGA = 36f }
}
