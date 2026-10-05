package com.nboard.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTypingBehaviorTest {
    @org.junit.Test
    fun `personalization is disabled for passwords and private editors`() {
        org.junit.Assert.assertFalse(SmartTypingBehavior(android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD).shouldPersonalize())
        org.junit.Assert.assertFalse(SmartTypingBehavior(android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD).shouldPersonalize())
        org.junit.Assert.assertFalse(SmartTypingBehavior(android.text.InputType.TYPE_CLASS_TEXT,
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).shouldPersonalize())
        org.junit.Assert.assertTrue(SmartTypingBehavior(android.text.InputType.TYPE_CLASS_TEXT).shouldPersonalize())
    }
    @Test
    fun autoSpaceAndCapitalize_enabledForNormalText() {
        val behavior = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        assertTrue(behavior.shouldAutoSpaceAndCapitalize())
        assertTrue(behavior.shouldAutoCapitalizeAfterChar('.'))
    }

    @Test
    fun autoSpaceAndCapitalize_disabledForNonTextInputClasses() {
        val number = SmartTypingBehavior(InputType.TYPE_CLASS_NUMBER)
        val phone = SmartTypingBehavior(InputType.TYPE_CLASS_PHONE)

        assertFalse(number.shouldAutoSpaceAndCapitalize())
        assertFalse(phone.shouldAutoSpaceAndCapitalize())
    }

    @Test
    fun autoSpaceAndCapitalize_disabledForSpecialFields() {
        val email = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val url = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val webEdit = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        val webEmail =
            SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val password = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val visiblePassword =
            SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val numberPassword =
            SmartTypingBehavior(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val username = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)

        assertFalse(email.shouldAutoSpaceAndCapitalize())
        assertFalse(url.shouldAutoSpaceAndCapitalize())
        assertFalse(webEdit.shouldAutoSpaceAndCapitalize())
        assertFalse(webEmail.shouldAutoSpaceAndCapitalize())
        assertFalse(password.shouldAutoSpaceAndCapitalize())
        assertFalse(visiblePassword.shouldAutoSpaceAndCapitalize())
        assertFalse(numberPassword.shouldAutoSpaceAndCapitalize())
        assertFalse(username.shouldAutoSpaceAndCapitalize())
    }

    @Test
    fun autoSpaceAfterPunctuation_handlesEdgeCases() {
        val behavior = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)

        assertTrue(
            behavior.shouldAutoSpaceAfterChar(
                char = '.',
                previousChar = 'o',
                last2Chars = "lo",
                nextChar = null
            )
        )
        assertFalse(
            behavior.shouldAutoSpaceAfterChar(
                char = '.',
                previousChar = '3',
                last2Chars = "e3",
                nextChar = null
            )
        )
        assertFalse(
            behavior.shouldAutoSpaceAfterChar(
                char = '.',
                previousChar = '.',
                last2Chars = "..",
                nextChar = null
            )
        )
        assertFalse(
            behavior.shouldAutoSpaceAfterChar(
                char = '!',
                previousChar = 't',
                last2Chars = "at",
                nextChar = '?'
            )
        )
        assertFalse(
            behavior.shouldAutoSpaceAfterChar(
                char = '?',
                previousChar = '!',
                last2Chars = "t!",
                nextChar = null
            )
        )
        assertFalse(
            behavior.shouldAutoSpaceAfterChar(
                char = '?',
                previousChar = 't',
                last2Chars = "at",
                nextChar = ' '
            )
        )
    }

    @Test
    fun returnToLettersAfterNumberSpace_isContextAware() {
        val textBehavior = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        val numberBehavior = SmartTypingBehavior(InputType.TYPE_CLASS_NUMBER)
        val phoneBehavior = SmartTypingBehavior(InputType.TYPE_CLASS_PHONE)
        val datetimeBehavior = SmartTypingBehavior(InputType.TYPE_CLASS_DATETIME)

        assertTrue(textBehavior.shouldReturnToLettersAfterNumberSpace())
        assertFalse(numberBehavior.shouldReturnToLettersAfterNumberSpace())
        assertFalse(phoneBehavior.shouldReturnToLettersAfterNumberSpace())
        assertFalse(datetimeBehavior.shouldReturnToLettersAfterNumberSpace())
    }

    @Test
    fun autoCapitalizeAfterChar_respectsFieldTypeAndPunctuation() {
        val normal = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        val email = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

        assertTrue(normal.shouldAutoCapitalizeAfterChar('!'))
        assertFalse(normal.shouldAutoCapitalizeAfterChar(','))
        assertFalse(email.shouldAutoCapitalizeAfterChar('!'))
    }
}
