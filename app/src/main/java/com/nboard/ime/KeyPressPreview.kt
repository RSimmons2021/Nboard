package com.nboard.ime

import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * A non-interactive letter preview above the pressed key. It never changes a key's hit area.
 * One transparent, non-touchable window covers the keyboard and the strip above it; the bubble
 * moves inside it. Adding a system window per keystroke cost ~11 ms of UI-thread time, and moving
 * a window between keys is not animatable. Between quick presses the bubble can glide from the
 * previous key (Settings > Preferences > Key pop-up slide and its intensity).
 */
internal class KeyPressPreview(private val service: NboardImeService) {
    private var window: PopupWindow? = null
    private var frame: FrameLayout? = null
    private var bubble: TextView? = null
    private var owner: View? = null
    private var hiddenAt = 0L
    private val windowGeometry = IntArray(4) // x, y, width, height in the keyboard window

    /** Glide between consecutive keys. */
    var slideEnabled = true
    /** 0..1: how long the glide takes (40 ms to 240 ms). */
    var slideIntensity = .5f

    fun show(key: View, label: String) {
        val wasShowing = bubble?.visibility == View.VISIBLE || SystemClock.uptimeMillis() - hiddenAt < QUICK_SUCCESSION_MS
        hide()
        if (label.length != 1 || !label[0].isLetter() || !key.isAttachedToWindow) return
        val width = maxOf(key.width + service.dp(8), service.dp(42))
        val height = service.dp(52)
        val keyboard = service.keyboardRoot
        val keyboardLocation = IntArray(2).also { keyboard.getLocationInWindow(it) }
        val geometry = intArrayOf(0, (keyboardLocation[1] - height).coerceAtLeast(0), key.rootView.width,
            keyboard.height + height)
        val preview = ensureWindow(key, geometry)
        preview.text = label
        preview.typeface = (key as? TextView)?.typeface
        if (preview.layoutParams.width != width) preview.layoutParams = FrameLayout.LayoutParams(width, height)
        preview.animate().cancel()
        preview.visibility = View.VISIBLE
        owner = key
        val container = frame!!
        // Measured on screen: a pop-up's requested and actual positions can differ by the
        // keyboard window's own offset. A new window is placed once its first layout is done.
        if (!container.isLaidOut || !container.isAttachedToWindow) {
            preview.visibility = View.INVISIBLE
            container.post { if (owner === key) { place(preview, container, key, width, height, glide = false); preview.visibility = View.VISIBLE } }
            return
        }
        place(preview, container, key, width, height, glide = slideEnabled && wasShowing && preview.translationX != 0f)
    }

    private fun place(preview: TextView, container: FrameLayout, key: View, width: Int, height: Int, glide: Boolean) {
        val keyOnScreen = IntArray(2).also { key.getLocationOnScreen(it) }
        val frameOnScreen = IntArray(2).also { container.getLocationOnScreen(it) }
        val x = (keyOnScreen[0] + (key.width - width) / 2 - frameOnScreen[0]).coerceIn(0, (container.width - width).coerceAtLeast(0)).toFloat()
        val y = (keyOnScreen[1] - height + service.dp(4) - frameOnScreen[1]).coerceAtLeast(0).toFloat()
        if (glide) {
            preview.animate().translationX(x).translationY(y)
                .setDuration((40 + slideIntensity * 200).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            preview.translationX = x
            preview.translationY = y
        }
    }

    private fun ensureWindow(key: View, geometry: IntArray): TextView {
        val preview = bubble ?: TextView(service).apply {
            textSize = 29f
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(service.dp(42), service.dp(52))
        }.also { bubble = it; restyle() }
        val existing = window
        if (existing != null && existing.isShowing) {
            if (!geometry.contentEquals(windowGeometry)) {
                existing.update(geometry[0], geometry[1], geometry[2], geometry[3])
                geometry.copyInto(windowGeometry)
            }
            return preview
        }
        val container = frame ?: FrameLayout(service).apply {
            clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(preview)
        }.also { frame = it }
        window = PopupWindow(container, geometry[2], geometry[3], false).apply {
            isTouchable = false
            isClippingEnabled = true
            animationStyle = 0
            showAtLocation(key, Gravity.TOP or Gravity.LEFT, geometry[0], geometry[1])
        }
        geometry.copyInto(windowGeometry)
        return preview
    }

    /** Hides the preview but keeps its window for the next keystroke. */
    fun hide(key: View? = null) {
        if (key != null && owner !== key) return
        bubble?.let { if (it.visibility == View.VISIBLE) hiddenAt = SystemClock.uptimeMillis(); it.visibility = View.INVISIBLE }
        owner = null
    }

    /** Re-reads theme colors, e.g. after a theme change. */
    fun restyle() {
        bubble?.apply {
            setTextColor(service.uiColor(R.color.key_text))
            elevation = service.dp(3).toFloat()
            background = GradientDrawable().apply {
                setColor(service.uiColor(R.color.key_bg))
                cornerRadius = service.dp(10).toFloat()
                setStroke(service.dp(1), service.uiColor(R.color.key_special_bg))
            }
        }
    }

    /** The letter bubble, for tests. */
    internal fun bubbleForTest(): View? = bubble

    /** Removes the window; call when the keyboard view hides or is replaced. */
    fun dismiss() {
        window?.dismiss(); window = null; frame = null; bubble = null; owner = null
    }

    private companion object {
        /** A press this soon after the last one continues the glide. */
        const val QUICK_SUCCESSION_MS = 250L
    }
}
