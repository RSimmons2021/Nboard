package com.nboard.ime

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView

/** A non-interactive preview above the finger. It never changes a key's hit area. */
internal class KeyPressPreview(private val service: NboardImeService) {
    private var window: PopupWindow? = null
    private var owner: View? = null
    fun show(key: View, label: String) {
        hide()
        if (label.length != 1 || !label[0].isLetter() || !key.isAttachedToWindow) return
        val width = maxOf(key.width + service.dp(8), service.dp(42))
        val height = service.dp(52)
        val text = TextView(service).apply {
            this.text = label
            textSize = 29f
            typeface = (key as? TextView)?.typeface
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(service.uiColor(R.color.key_text))
            background = GradientDrawable().apply {
                setColor(service.uiColor(R.color.key_bg))
                cornerRadius = service.dp(10).toFloat()
                setStroke(service.dp(1), service.uiColor(R.color.key_special_bg))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        owner = key
        window = PopupWindow(text, width, height, false).apply {
            isTouchable = false
            isClippingEnabled = true
            elevation = service.dp(3).toFloat()
            animationStyle = 0
            val position = IntArray(2)
            key.getLocationInWindow(position)
            val x = (position[0] + (key.width-width)/2).coerceIn(0, (key.rootView.width-width).coerceAtLeast(0))
            showAtLocation(key, Gravity.TOP or Gravity.LEFT, x, (position[1]-height+service.dp(4)).coerceAtLeast(0))
        }
    }
    fun hide(key: View? = null) {
        if (key != null && owner !== key) return
        window?.dismiss(); window = null; owner = null
    }
}
