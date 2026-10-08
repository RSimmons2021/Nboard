package com.nboard.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTypingBehaviorTest {
    @Test
    fun sentenceCapitalizationRecognizesParagraphsAndPunctuationAcrossEditors() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            val behavior = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or variation)
            for (text in listOf("", "   ", "Hello. ", "Hello!", "Hello? ", "hello\n", "hello\n  ",
                "hello\r\n", "hello\r", "Meet at 5. ", "Wait... ", "\"Hello.\" ")) {
                assertTrue("Must capitalize after '$text' in variation $variation", behavior.shouldAutoCapitalizeAtCursor(text))
            }
            for (text in listOf("hello", "hello ", "hello, ", "3.", "3.14", "hello\nworld ")) {
                assertFalse("Must stay lowercase after '$text' in variation $variation", behavior.shouldAutoCapitalizeAtCursor(text))
            }
        }
    }

    @Test
    fun structuredFieldsKeepTheirCasingAndNamesHonorEditorFlags() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val behavior = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            assertFalse(behavior.shouldAutoCapitalizeAtCursor(""))
            assertFalse(behavior.shouldAutoCapitalizeAtCursor("Hello. "))
        }
        val name = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        assertTrue(name.shouldAutoCapitalizeAtCursor(""))
        assertTrue(name.shouldAutoCapitalizeAtCursor("Jane "))
        assertFalse(name.shouldAutoCapitalizeAtCursor("Ja"))
        assertFalse(name.shouldAutoSpaceAndCapitalize())
        val capitals = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        assertTrue(capitals.shouldAutoCapitalizeAtCursor("abc"))
        assertFalse(SmartTypingBehavior(InputType.TYPE_CLASS_NUMBER).shouldAutoCapitalizeAtCursor(""))
    }

    @Test
    fun webFormTextSupportsSentenceCapitalization() {
        val web = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
        assertTrue(web.shouldAutoSpaceAndCapitalize())
        assertTrue(web.shouldAutoCapitalizeAfterChar('.'))
    }

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
