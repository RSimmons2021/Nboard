package com.nboard.ime

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout

data class KeyHitRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal fun nearestKeyRectIndex(
    x: Float,
    y: Float,
    rects: List<KeyHitRect>,
    maxDistance: Float
): Int? {
    var bestIndex: Int? = null
    var bestDistanceSquared = maxDistance * maxDistance
    rects.forEachIndexed { index, rect ->
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared <= bestDistanceSquared) {
            bestDistanceSquared = distanceSquared
            bestIndex = index
        }
    }
    return bestIndex
}

class NearestKeyLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /**
     * Fingers that landed in a gap between keys, by pointer id. Each one gets its own
     * single-pointer stream to the nearest key; the rest of the gesture is dispatched
     * normally without it. Android would otherwise hand a gap finger to the key under an
     * earlier finger, where its movement reads as a swipe from that key.
     */
    private val gapTargets = LinkedHashMap<Int, View>()
    private val screenLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    private val hitRect = Rect()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) gapTargets.clear()
        getLocationOnScreen(screenLocation)
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val index = event.actionIndex
            val x = event.getX(index)
            val y = event.getY(index)
            val target = if (isOverKey(x, y)) null else findNearestKey(x, y)
            if (target == null && action == MotionEvent.ACTION_DOWN && !isOverKey(x, y)) return false
            if (target != null) {
                gapTargets[event.getPointerId(index)] = target
                dispatchSingle(target, event, index, MotionEvent.ACTION_DOWN)
                return true
            }
        }
        if (gapTargets.isEmpty()) return super.dispatchTouchEvent(event)

        val actionId = event.getPointerId(event.actionIndex)
        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            val target = gapTargets[id] ?: continue
            when {
                action == MotionEvent.ACTION_CANCEL -> dispatchSingle(target, event, index, MotionEvent.ACTION_CANCEL)
                action == MotionEvent.ACTION_MOVE -> dispatchSingle(target, event, index, MotionEvent.ACTION_MOVE)
                id == actionId && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) -> {
                    dispatchSingle(target, event, index, MotionEvent.ACTION_UP)
                    gapTargets.remove(id)
                }
            }
        }
        val rest = withoutGapPointers(event)
        val handled = rest?.let { try { super.dispatchTouchEvent(it) } finally { it.recycle() } } ?: true
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gapTargets.clear()
        return handled
    }

    private fun isOverKey(x: Float, y: Float): Boolean {
        val keys = mutableListOf<View>()
        collectKeyLeaves(this, keys)
        return keys.any { key ->
            hitRect.set(0, 0, key.width, key.height)
            offsetDescendantRectToMyCoords(key, hitRect)
            hitRect.contains(x.toInt(), y.toInt())
        }
    }

    private fun findNearestKey(x: Float, y: Float): View? {
        val candidates = mutableListOf<View>()
        collectKeyLeaves(this, candidates)
        if (candidates.isEmpty()) return null

        val rects = candidates.map { candidate ->
            val rect = Rect(0, 0, candidate.width, candidate.height)
            offsetDescendantRectToMyCoords(candidate, rect)
            KeyHitRect(rect.left, rect.top, rect.right, rect.bottom)
        }
        val maxDistance = 24f * resources.displayMetrics.density
        val index = nearestKeyRectIndex(x, y, rects, maxDistance) ?: return null
        return candidates[index]
    }

    private fun collectKeyLeaves(parent: ViewGroup, output: MutableList<View>) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (!child.isShown || !child.isEnabled || child.width <= 0 || child.height <= 0) {
                continue
            }
            when (child) {
                is ViewGroup -> collectKeyLeaves(child, output)
                is Button,
                is ImageButton -> output.add(child)
            }
        }
    }

    private fun coords(event: MotionEvent, index: Int) = MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) }
    private fun properties(event: MotionEvent, index: Int) = MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }

    /** Builds events in screen coordinates, then offsets them: key listeners read rawX/rawY. */
    private fun obtain(source: MotionEvent, action: Int, indices: List<Int>, downTime: Long): MotionEvent {
        val pointerCoords = indices.map { index -> coords(source, index).apply {
            x += screenLocation[0]; y += screenLocation[1]
        } }.toTypedArray()
        val pointerProperties = indices.map { properties(source, it) }.toTypedArray()
        return MotionEvent.obtain(downTime, source.eventTime, action, indices.size, pointerProperties, pointerCoords,
            source.metaState, source.buttonState, source.xPrecision, source.yPrecision, source.deviceId,
            source.edgeFlags, source.source, source.flags)
    }

    private fun dispatchSingle(target: View, source: MotionEvent, index: Int, action: Int) {
        val downTime = if (action == MotionEvent.ACTION_DOWN) source.eventTime else source.downTime
        val event = obtain(source, action, listOf(index), downTime)
        target.getLocationOnScreen(targetLocation)
        event.offsetLocation(-targetLocation[0].toFloat(), -targetLocation[1].toFloat())
        try {
            target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /** The same gesture minus gap fingers, with its action re-indexed; null when nothing remains. */
    private fun withoutGapPointers(source: MotionEvent): MotionEvent? {
        val kept = (0 until source.pointerCount).filter { source.getPointerId(it) !in gapTargets }
        val actionIndex = source.actionIndex
        val actionId = source.getPointerId(actionIndex)
        if (kept.isEmpty()) return null
        val action = when (source.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val position = kept.indexOf(actionIndex)
                val isDown = source.actionMasked == MotionEvent.ACTION_DOWN || source.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                when {
                    position < 0 || actionId in gapTargets -> MotionEvent.ACTION_MOVE
                    kept.size == 1 -> if (isDown) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                    else -> (if (isDown) MotionEvent.ACTION_POINTER_DOWN else MotionEvent.ACTION_POINTER_UP) or
                        (position shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }
            else -> source.actionMasked
        }
        val downTime = if (action == MotionEvent.ACTION_DOWN) source.eventTime else source.downTime
        return obtain(source, action, kept, downTime).also {
            it.offsetLocation(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
        }
    }
}
