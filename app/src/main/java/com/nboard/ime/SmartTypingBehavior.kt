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
            isUsernameField() -> false
            else -> true
        }
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
        return shouldAutoSpaceAndCapitalize() && char in SENTENCE_ENDING_PUNCTUATION
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
        return variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
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

    private fun isUsernameField(): Boolean {
        return inputClass == InputType.TYPE_CLASS_TEXT &&
            variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
    }

    companion object {
        private val SENTENCE_ENDING_PUNCTUATION = setOf('.', '!', '?')
    }
}
