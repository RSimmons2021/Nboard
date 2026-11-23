package com.nboard.ime

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow

/** Enlarged editor text, not a screenshot: works across app surfaces without capture permissions. */
internal class CursorLens(private val service: NboardImeService) {
    private var window: PopupWindow? = null
    private var frame: FrameLayout? = null
    internal var lens: LensView? = null
        private set
    private val screenLocation = IntArray(2)
    private val x = CursorSpring()
    private val y = CursorSpring()
    private var frameTime = 0L
    private var following = false
    private var latestCaretX = 0f
    private var latestCaretTop = 0f
    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (frameTime == 0L) 1f / 60f else (now - frameTime) / 1000f
            frameTime = now
            val movingX = x.advance(dt)
            val movingY = y.advance(dt)
            lens?.translationX = x.position
            lens?.translationY = y.position
            if (following && (movingX || movingY)) frame?.postOnAnimation(this) else frameTime = 0L
        }
    }

    fun show(buffer: CursorTrackpadBuffer, caretX: Float, caretTop: Float) {
        val root = service.keyboardRoot
        if (!root.isAttachedToWindow) return
        if (window?.isShowing != true) {
            val density = root.resources.displayMetrics.density
            val width = root.resources.displayMetrics.widthPixels
            val height = root.resources.displayMetrics.heightPixels
            val content = FrameLayout(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                clipChildren = false
            }
            val bubble = LensView(service).apply {
                layoutParams = FrameLayout.LayoutParams((184 * density).toInt(), (54 * density).toInt())
                elevation = 8 * density
                background = GradientDrawable().apply {
                    setColor(service.uiColor(R.color.key_bg))
                    cornerRadius = 27 * density
                    setStroke(maxOf(1, density.toInt()), service.uiColor(R.color.key_special_bg))
                }
                outlineProvider = ViewOutlineProviderForLens
                clipToOutline = true
            }
            content.addView(bubble)
            lens = bubble; frame = content
            window = PopupWindow(content, width, height, false).apply {
                isTouchable = false
                isFocusable = false
                isClippingEnabled = false
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                animationStyle = 0
                if (Build.VERSION.SDK_INT >= 29) {
                    setIsLaidOutInScreen(true)
                    setIsClippedToScreen(true)
                }
                showAtLocation(root, Gravity.TOP or Gravity.LEFT, 0, 0)
            }
            content.post { if (following) position(latestCaretX, latestCaretTop, snap = true) }
        }
        following = true
        val bubble = lens ?: return
        bubble.animate().cancel()
        bubble.setBuffer(buffer, snap = true)
        position(caretX, caretTop, snap = true)
        bubble.alpha = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
        bubble.scaleX = .9f; bubble.scaleY = .9f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun update(buffer: CursorTrackpadBuffer, caretX: Float, caretTop: Float) {
        lens?.setBuffer(buffer, snap = false)
        position(caretX, caretTop, snap = !ValueAnimator.areAnimatorsEnabled())
    }

    private fun position(caretX: Float, caretTop: Float, snap: Boolean) {
        latestCaretX = caretX; latestCaretTop = caretTop
        val container = frame ?: return
        val bubble = lens ?: return
        container.getLocationOnScreen(screenLocation)
        val width = bubble.layoutParams.width
        val height = bubble.layoutParams.height
        val margin = service.dp(8).toFloat()
        val nextX = (caretX - screenLocation[0] - width / 2f)
            .coerceIn(margin, (container.resources.displayMetrics.widthPixels - width - margin).coerceAtLeast(margin))
        val nextY = (caretTop - screenLocation[1] - height - service.dp(12)).coerceAtLeast(service.dp(28).toFloat())
        if (snap) { x.snap(nextX); y.snap(nextY) } else { x.target = nextX; y.target = nextY }
        container.removeCallbacks(tick)
        container.postOnAnimation(tick)
    }

    fun hide() {
        following = false
        frame?.removeCallbacks(tick)
        frameTime = 0L
        lens?.animate()?.cancel()
        lens?.animate()?.alpha(0f)?.scaleX(.94f)?.scaleY(.94f)?.setDuration(110)?.withEndAction {
            if (!following) dismiss()
        }?.start()
    }

    fun dismiss() {
        following = false
        frame?.removeCallbacks(tick)
        lens?.animate()?.cancel()
        lens?.clear()
        window?.dismiss()
        window = null; lens = null; frame = null; frameTime = 0L
    }

    internal class LensView(private val service: NboardImeService) : View(service) {
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = service.uiColor(R.color.key_text)
            textSize = 25f * resources.displayMetrics.scaledDensity
        }
        private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = service.uiColor(R.color.key_text) }
        private val scroll = CursorSpring()
        private var line = ""
        private var layout: StaticLayout? = null
        private var caretOffset = 0
        private var lastFrame = 0L
        private val clip = Path()
        private val bounds = RectF()

        fun setBuffer(buffer: CursorTrackpadBuffer, snap: Boolean) {
            val start = buffer.text.lastIndexOf('\n', (buffer.cursor - 1).coerceAtLeast(-1)) + 1
            val end = buffer.text.indexOf('\n', buffer.cursor).let { if (it < 0) buffer.text.length else it }
            val next = buffer.text.substring(start, end)
            val changedLine = next != line
            if (changedLine || layout == null) {
                line = next
                layout = StaticLayout.Builder.obtain(line, 0, line.length, textPaint,
                    maxOf(1, textPaint.measureText(line).toInt() + 64))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            }
            caretOffset = (buffer.cursor - start).coerceIn(0, line.length)
            val target = layout?.getPrimaryHorizontal(caretOffset) ?: 0f
            if (snap || changedLine || !ValueAnimator.areAnimatorsEnabled()) scroll.snap(target) else scroll.target = target
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = android.os.SystemClock.uptimeMillis()
            val moving = scroll.advance(if (lastFrame == 0L) 1f / 60 else (now - lastFrame) / 1000f)
            lastFrame = if (moving) now else 0L
            val content = layout ?: return
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            clip.reset(); clip.addRoundRect(bounds, height / 2f, height / 2f, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            canvas.translate(width / 2f - scroll.position, (height - content.height) / 2f)
            content.draw(canvas)
            canvas.restore()
            val inset = 12f * resources.displayMetrics.density
            val caretWidth = 2f * resources.displayMetrics.density
            canvas.drawRoundRect(width / 2f - caretWidth / 2, inset, width / 2f + caretWidth / 2,
                height - inset, caretWidth, caretWidth, caretPaint)
            if (moving) postInvalidateOnAnimation()
        }
        fun clear() { line = ""; layout = null; lastFrame = 0L; invalidate() }
    }

    private companion object {
        val ViewOutlineProviderForLens = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
    }
}
