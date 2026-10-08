package com.nboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

class SmartTypingBehavior(private val inputType: Int, private val imeOptions: Int = 0) {
    constructor(editorInfo: EditorInfo?) : this(editorInfo?.inputType ?: 0, editorInfo?.imeOptions ?: 0)

    private val inputClass: Int = inputType and InputType.TYPE_MASK_CLASS
    private val variation: Int = inputType and InputType.TYPE_MASK_VARIATION

    fun shouldPersonalize(): Boolean = inputClass == InputType.TYPE_CLASS_TEXT &&
        !isPasswordField() && imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0

    fun shouldAutoSpaceAndCapitalize(): Boolean {
        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        return when {
            isEmailAddressField() -> false
            isUrlField() -> false
            isPasswordField() -> false
            isPersonNameField() -> false
            else -> true
        }
    }

    fun shouldAutoCapitalize(): Boolean = inputClass == InputType.TYPE_CLASS_TEXT &&
        !isEmailAddressField() && !isUrlField() && !isPasswordField()

    fun shouldAutoCapitalizeAtCursor(beforeCursor: String): Boolean {
        if (!shouldAutoCapitalize()) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 &&
            (beforeCursor.isEmpty() || beforeCursor.last().isWhitespace())) return true

        // Keep paragraph breaks: trimEnd() without a predicate erases them.
        val context = beforeCursor.trimEnd { it.isWhitespace() && it != '\n' && it != '\r' }
        if (context.isEmpty() || context.last() == '\n' || context.last() == '\r') return true
        val sentence = context.trimEnd { it in SENTENCE_CLOSERS }
        if (sentence.lastOrNull() !in SENTENCE_ENDING_PUNCTUATION) return false
        // An unfinished decimal isn't a sentence; whitespace after it is a boundary.
        if (sentence.last() == '.' && sentence.getOrNull(sentence.lastIndex - 1)?.isDigit() == true &&
            context.length == beforeCursor.length) return false
        return true
    }

    fun shouldAutoSpaceAfterChar(
        char: Char,
        previousChar: Char?,
        last2Chars: String?,
        nextChar: Char? = null
    ): Boolean {
        if (!shouldAutoSpaceAndCapitalize()) return false
        if (char !in SENTENCE_ENDING_PUNCTUATION) return false

        if (previousChar in SENTENCE_ENDING_PUNCTUATION) return false
        if (char == '.' && previousChar?.isDigit() == true) return false
        if (char == '.' && (previousChar == '.' || last2Chars == "..")) return false
        if (nextChar?.isWhitespace() == true) return false
        if (nextChar in SENTENCE_ENDING_PUNCTUATION) return false

        return true
    }

    fun shouldAutoCapitalizeAfterChar(char: Char): Boolean {
        return shouldAutoCapitalize() && char in SENTENCE_ENDING_PUNCTUATION
    }

    fun shouldReturnToLettersAfterNumberSpace(): Boolean {
        return when (inputClass) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> false
            InputType.TYPE_CLASS_TEXT -> true
            else -> false
        }
    }

    private fun isEmailAddressField(): Boolean {
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private fun isUrlField(): Boolean {
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        // WEB_EDIT_TEXT describes ordinary web forms, not browser address bars.
        return variation == InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun isPasswordField(): Boolean {
        val textPassword = inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val numberPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword
    }

    private fun isPersonNameField(): Boolean {
        return inputClass == InputType.TYPE_CLASS_TEXT &&
            variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
    }

    companion object {
        private const val SENTENCE_CLOSERS = "\"'’”)]}"
        private val SENTENCE_ENDING_PUNCTUATION = setOf('.', '!', '?')
    }
}
