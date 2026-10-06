package com.nboard.ime

import android.graphics.Paint

/** Emoji skin tones (Fitzpatrick modifiers U+1F3FB..U+1F3FF). */
internal object EmojiTones {
    /** Light, medium-light, medium, medium-dark, dark. */
    val MODIFIERS = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    private const val VARIATION_SELECTOR = '️'
    private val paint = Paint()
    private val supportCache = HashMap<String, Boolean>()

    private fun isModifier(codePoint: Int) = codePoint in 0x1F3FB..0x1F3FF

    /** [emoji] without any skin tone ("👍🏽" -> "👍"). */
    fun base(emoji: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (!isModifier(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /**
     * [emoji] in the tone [modifier] (null: default yellow). The tone follows the first code
     * point, replacing a variation selector there ("✌️" -> "✌🏽", "👩‍💻" -> "👩🏽‍💻").
     */
    fun withTone(emoji: String, modifier: String?): String {
        val plain = base(emoji)
        if (modifier == null || plain.isEmpty()) return plain
        val first = Character.charCount(plain.codePointAt(0))
        var rest = plain.substring(first)
        if (rest.firstOrNull() == VARIATION_SELECTOR) rest = rest.substring(1)
        return plain.substring(0, first) + modifier + rest
    }

    /** True when this phone's emoji font draws [emoji] in skin tones (one glyph, not two). */
    fun supportsTones(emoji: String): Boolean = supportCache.getOrPut(base(emoji)) {
        val plain = base(emoji)
        plain.isNotEmpty() && paint.hasGlyph(withTone(plain, MODIFIERS[2]))
    }
}
