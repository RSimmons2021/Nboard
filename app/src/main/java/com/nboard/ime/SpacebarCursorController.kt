package com.nboard.ime

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

/** Owns one Space gesture, a bounded editor snapshot, and its transient visual feedback. */
internal class SpacebarCursorController(private val service: NboardImeService) {
    var isActive = false
        private set
    internal val lens = CursorLens(service)
    private var owner: View? = null
    private var connection: InputConnection? = null
    private var buffer: CursorTrackpadBuffer? = null
    private var layout: StaticLayout? = null
    private var anchor: CursorAnchorInfo? = null
    private var downX = 0f
    private var downY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var processedX = 0f
    private var processedY = 0f
    private var committedEarly = false
    private var cursorAllowed = false
    private var baseAlpha = 1f
    private var preferredColumn: Float? = null
    private val horizontal = CursorDragAxis(service.dp(8).toFloat())
    private val vertical = CursorDragAxis(service.dp(32).toFloat())
    private var framePending = false
    private var glyphAnimation: ValueAnimator? = null
    private val textColors = mutableListOf<Pair<TextView, ColorStateList>>()
    private val imageAlphas = mutableListOf<Pair<ImageView, Int>>()
    private val knownSelections = ArrayDeque<Int>()
    private val caretPosition = FloatArray(2)
    private val rootLocation = IntArray(2)
    private var editorWidth = 0
    private var showTextLens = false
    private var mayHaveMoreAfter = false
    private var glyphFraction = 1f

    private val activateAfterHold = Runnable { if (owner != null && cursorAllowed && !committedEarly) activate() }
    private val processMovement = Runnable {
        framePending = false
        if (isActive) moveToPointer()
    }

    fun bind(space: View) {
        cancel()
        space.setOnTouchListener { touched, event ->
            if (!touched.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    service.commitHeldKeys(except = touched)
                    owner = touched
                    committedEarly = false
                    cursorAllowed = !service.isAiPromptInputActive() && !service.isEmojiSearchInputActive() &&
                        !service.isClipboardOpen && service.currentInputConnection != null
                    downX = event.rawX; downY = event.rawY
                    pointerX = downX; pointerY = downY
                    baseAlpha = touched.alpha
                    touched.isPressed = true
                    touched.alpha = (baseAlpha * KEY_PRESSED_ALPHA).coerceAtLeast(MIN_PRESSED_ALPHA)
                    touched.animate().cancel()
                    touched.animate().scaleX(KEY_PRESS_SCALE).scaleY(KEY_PRESS_SCALE).setDuration(KEY_PRESS_ANIM_MS).start()
                    service.performKeyHaptic(touched)
                    service.heldKeyCommits[touched] = {
                        touched.removeCallbacks(activateAfterHold)
                        committedEarly = true
                        if (!isActive) service.handleSpaceTap()
                    }
                    if (cursorAllowed) touched.postDelayed(activateAfterHold, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    pointerX = event.rawX; pointerY = event.rawY
                    if (cursorAllowed && !committedEarly) {
                        if (!isActive && abs(pointerX - downX) >= service.dp(16) &&
                            abs(pointerX - downX) > abs(pointerY - downY) * 1.3f) activate()
                        if (isActive && !framePending) {
                            framePending = true
                            touched.postOnAnimation(processMovement)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pointerX = event.rawX; pointerY = event.rawY
                    if (isActive) moveToPointer() // Commit the final frame before dismissing.
                    val wasCursor = isActive
                    finish(touched, immediate = false)
                    if (!wasCursor && !committedEarly) service.handleSpaceTap()
                    committedEarly = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> { finish(touched, immediate = true); committedEarly = false; true }
                else -> true
            }
        }
    }

    private fun activate() {
        if (isActive || committedEarly || !cursorAllowed) return
        val touched = owner ?: return
        touched.removeCallbacks(activateAfterHold)
        connection = service.currentInputConnection ?: return
        buffer = readBuffer()
        horizontal.reset(); vertical.reset(); preferredColumn = null
        processedX = pointerX; processedY = pointerY
        isActive = true
        showTextLens = !service.isPasswordInputType(service.currentInputEditorInfo?.inputType ?: 0)
        service.keyPressPreview.hide()
        touched.animate().cancel()
        touched.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        service.performKeyHaptic(touched)
        knownSelections.clear()
        buffer?.let { knownSelections.add(it.absoluteCursor); rebuildLayout(it) }
        captureGlyphs(service.keyRowsContainer)
        // Bottom-row controls share the same trackpad state as the letter keys.
        (service.spaceButton.parent as? ViewGroup)?.let { captureGlyphs(it) }
        animateGlyphs(hidden = true)
        val mode = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
        val filters = if (Build.VERSION.SDK_INT >= 33) InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or
            InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS else 0
        if (connection?.requestCursorUpdates(mode or filters) != true && filters != 0) connection?.requestCursorUpdates(mode)
        buffer?.let { if (showTextLens) { caretLocation(); lens.show(it, caretPosition[0], caretPosition[1]) } }
    }

    private fun readBuffer(): CursorTrackpadBuffer? {
        val input = connection ?: return null
        if (Build.VERSION.SDK_INT >= 31) {
            val surrounding = input.getSurroundingText(CONTEXT_RADIUS, CONTEXT_RADIUS, 0)
            if (surrounding != null) {
                val offset = if (surrounding.offset >= 0) surrounding.offset else
                    service.editorSelectionStart - surrounding.selectionStart
                if (offset >= 0) return boundedBuffer(surrounding.text.toString(), offset, surrounding.selectionEnd)
            }
        }
        val extracted = input.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = CONTEXT_RADIUS * 2; hintMaxLines = 64
        }, 0)
        if (extracted?.text != null && extracted.startOffset >= 0 && extracted.selectionEnd >= 0 &&
            extracted.partialStartOffset < 0) return boundedBuffer(extracted.text.toString(), extracted.startOffset, extracted.selectionEnd)
        val before = input.getTextBeforeCursor(CONTEXT_RADIUS, 0)?.toString() ?: return null
        val after = input.getTextAfterCursor(CONTEXT_RADIUS, 0)?.toString().orEmpty()
        val offset = service.editorSelectionEnd - before.length
        mayHaveMoreAfter = after.length >= CONTEXT_RADIUS
        return if (offset >= 0) CursorTrackpadBuffer(before + after, offset, before.length) else null
    }

    private fun boundedBuffer(text: String, offset: Int, cursor: Int): CursorTrackpadBuffer {
        val selection = cursor.coerceIn(0, text.length)
        mayHaveMoreAfter = text.length - selection >= CONTEXT_RADIUS
        var start = (selection - CONTEXT_RADIUS).coerceAtLeast(0)
        var end = (selection + CONTEXT_RADIUS).coerceAtMost(text.length)
        if (start > 0 && text[start].isLowSurrogate()) start--
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end++
        return CursorTrackpadBuffer(text.substring(start, end), offset + start, selection - start)
    }

    private fun rebuildLayout(snapshot: CursorTrackpadBuffer) {
        val info = anchor
        val width = if (Build.VERSION.SDK_INT >= 33) info?.editorBoundsInfo?.editorBounds?.width()?.toInt() else null
        editorWidth = width?.takeIf { it > service.dp(80) } ?: (service.resources.displayMetrics.widthPixels - service.dp(48))
        val fontHeight = info?.let { it.insertionMarkerBottom - it.insertionMarkerTop }?.takeIf { it.isFinite() && it > 0 }
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (fontHeight?.times(.8f) ?: service.dp(18).toFloat())
            if (Build.VERSION.SDK_INT >= 34) info?.textAppearanceInfo?.let { textSize = it.textSize }
        }
        layout = StaticLayout.Builder.obtain(snapshot.text, 0, snapshot.text.length, paint, editorWidth)
            .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    private fun moveToPointer() {
        val dx = pointerX - processedX; val dy = pointerY - processedY
        processedX = pointerX; processedY = pointerY
        val columns = horizontal.steps(dx)
        val rows = vertical.steps(dy)
        if (columns == 0 && rows == 0) return
        val snapshot = buffer
        if (snapshot == null) {
            repeat(abs(columns).coerceAtMost(32)) { if (columns < 0) service.moveCursorLeft() else service.moveCursorRight() }
            repeat(abs(rows).coerceAtMost(8)) { sendDirection(if (rows < 0) android.view.KeyEvent.KEYCODE_DPAD_UP else android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
            return
        }
        val old = snapshot.cursor
        val content = layout
        if (columns != 0) {
            val rtl = content?.getParagraphDirection(content.getLineForOffset(snapshot.cursor)) == -1
            snapshot.moveCharacters(if (rtl) -columns else columns)
            preferredColumn = null
        }
        if (rows != 0 && content != null) {
            val line = content.getLineForOffset(snapshot.cursor)
            val column = preferredColumn ?: content.getPrimaryHorizontal(snapshot.cursor).also { preferredColumn = it }
            val targetLine = (line + rows).coerceIn(0, content.lineCount - 1)
            snapshot.moveTo(content.getOffsetForHorizontal(targetLine, column))
        }
        if (snapshot.cursor == old) {
            if (columns != 0) horizontal.reset()
            if (rows != 0) vertical.reset()
            return
        }
        val accepted = connection?.setSelection(snapshot.absoluteCursor, snapshot.absoluteCursor)
        if (accepted != true) {
            snapshot.moveTo(old)
            buffer = null; layout = null
            lens.hide()
            repeat(abs(columns).coerceAtMost(32)) { if (columns < 0) service.moveCursorLeft() else service.moveCursorRight() }
            return
        }
        knownSelections.add(snapshot.absoluteCursor)
        if (knownSelections.size > 128) knownSelections.removeFirst()
        service.pendingAutoCorrection = null
        if (showTextLens) { caretLocation(); lens.update(snapshot, caretPosition[0], caretPosition[1]) }
        if (snapshot.cursor < 64 && snapshot.startOffset > 0 || snapshot.text.length - snapshot.cursor < 64 && mayHaveMoreAfter) {
            // Refresh only near a snapshot edge, never once per moved character.
            readBuffer()?.let { buffer = it; rebuildLayout(it) }
        }
    }

    private fun sendDirection(code: Int) {
        connection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        connection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    fun onAnchorInfo(info: CursorAnchorInfo?) {
        if (!isActive || info == null) return
        anchor = info
        val snapshot = buffer ?: return
        if (info.selectionStart != snapshot.absoluteCursor || info.selectionEnd != snapshot.absoluteCursor) return
        if (Build.VERSION.SDK_INT >= 33 && info.editorBoundsInfo?.editorBounds?.width()?.toInt()?.let { it != editorWidth } == true) rebuildLayout(snapshot)
        if (showTextLens) { caretLocation(); lens.update(snapshot, caretPosition[0], caretPosition[1]) }
    }

    fun onSelectionChanged(start: Int, end: Int) {
        if (!isActive || start in knownSelections && end == start) return
        // An editor changed the selection itself; its state takes precedence over our snapshot.
        buffer = readBuffer()
        buffer?.let { rebuildLayout(it); knownSelections.add(it.absoluteCursor) }
    }

    private fun caretLocation() {
        val snapshot = buffer
        val info = anchor
        if (info != null && snapshot != null && info.selectionStart - snapshot.startOffset in 0..snapshot.text.length &&
            info.insertionMarkerHorizontal.isFinite() && info.insertionMarkerTop.isFinite() &&
            info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION != 0) {
            caretPosition[0] = info.insertionMarkerHorizontal; caretPosition[1] = info.insertionMarkerTop
            // Bridge the editor's asynchronous acknowledgement without dropping the lens
            // back to the keyboard between characters.
            layout?.let { content ->
                val acknowledged = info.selectionStart - snapshot.startOffset
                val oldLine = content.getLineForOffset(acknowledged)
                val newLine = content.getLineForOffset(snapshot.cursor)
                caretPosition[0] += content.getPrimaryHorizontal(snapshot.cursor) - content.getPrimaryHorizontal(acknowledged)
                caretPosition[1] += content.getLineTop(newLine) - content.getLineTop(oldLine)
            }
            info.matrix.mapPoints(caretPosition)
            return
        }
        service.keyboardRoot.getLocationOnScreen(rootLocation)
        caretPosition[0] = (downX + (pointerX - downX) * .45f).coerceIn(service.dp(100).toFloat(),
            (service.resources.displayMetrics.widthPixels - service.dp(100)).toFloat())
        caretPosition[1] = rootLocation[1].toFloat()
    }

    private fun captureGlyphs(root: View) {
        when (root) {
            is TextView -> if (textColors.none { it.first === root }) textColors.add(root to root.textColors)
            is ImageView -> if (imageAlphas.none { it.first === root }) imageAlphas.add(root to root.imageAlpha)
            is ViewGroup -> for (i in 0 until root.childCount) captureGlyphs(root.getChildAt(i))
        }
    }
    private fun animateGlyphs(hidden: Boolean) {
        glyphAnimation?.cancel()
        val from = glyphFraction
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setGlyphVisibility(if (hidden) 0f else 1f)
            if (!hidden) clearGlyphs()
            return
        }
        glyphAnimation = ValueAnimator.ofFloat(from, if (hidden) 0f else 1f).apply {
            duration = if (hidden) 120 else 140
            addUpdateListener { setGlyphVisibility(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && !hidden && !isActive && glyphFraction == 1f) {
                        clearGlyphs()
                    }
                }
            })
            start()
        }
    }
    private fun setGlyphVisibility(fraction: Float) {
        glyphFraction = fraction
        textColors.forEach { (view, colors) ->
            if (fraction >= 1f) view.setTextColor(colors) else {
                val color = colors.getColorForState(view.drawableState, colors.defaultColor)
                view.setTextColor((color and 0x00ffffff) or ((Color.alpha(color) * fraction).toInt() shl 24))
            }
        }
        imageAlphas.forEach { (view, alpha) -> view.imageAlpha = (alpha * fraction).toInt() }
    }
    private fun clearGlyphs() { textColors.clear(); imageAlphas.clear() }

    private fun finish(touched: View, immediate: Boolean) {
        touched.removeCallbacks(activateAfterHold)
        touched.removeCallbacks(processMovement)
        service.heldKeyCommits.remove(touched)
        touched.isPressed = false; touched.alpha = baseAlpha
        touched.animate().cancel()
        touched.animate().scaleX(1f).scaleY(1f).setDuration(KEY_RELEASE_ANIM_MS).start()
        val active = isActive
        isActive = false; owner = null; framePending = false
        connection?.requestCursorUpdates(0)
        connection = null; buffer = null; layout = null; anchor = null; knownSelections.clear()
        if (immediate) { lens.dismiss(); glyphAnimation?.cancel(); setGlyphVisibility(1f); clearGlyphs() }
        else if (active) {
            lens.hide()
            val returningFrom = glyphFraction
            glyphAnimation?.cancel()
            setGlyphVisibility(1f); clearGlyphs()
            // Update capitalization immediately, then transfer the fade to the new keys
            // in this same frame so the row never flashes fully opaque on release.
            service.refreshAutoShiftFromContextAndRerender(forceRerender = true)
            captureGlyphs(service.keyRowsContainer)
            (service.spaceButton.parent as? ViewGroup)?.let { captureGlyphs(it) }
            setGlyphVisibility(returningFrom)
            animateGlyphs(hidden = false)
        }
    }

    fun cancel() {
        owner?.let { finish(it, immediate = true) }
        glyphAnimation?.cancel(); glyphAnimation = null
        setGlyphVisibility(1f); clearGlyphs()
        lens.dismiss()
        isActive = false
    }

    private companion object {
        const val HOLD_MS = 320L
        const val CONTEXT_RADIUS = 2048
    }
}
