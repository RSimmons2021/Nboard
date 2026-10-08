package com.nboard.ime

import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Uses only synthetic, non-personalized editor content. */
class KeyboardPageAndCapitalizationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            find(root.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        error(message)
    }
    private fun tap(view: View) = main {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width / 2f, view.height / 2f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }
    private fun key(service: NboardImeService, label: String): View {
        var result: View? = null
        main { result = find(service.keyboardRoot) { it is TextView && it.isShown && it.text.toString() == label } }
        return requireNotNull(result) { "Key '$label' is missing" }
    }
    private fun withEditor(variation: Int, block: (NboardImeService, EditText) -> Unit) {
        ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
            var editor: EditText? = null
            val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or variation
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                editor = find(activity.window.decorView) { it is EditText } as EditText
                editor!!.inputType = type // No CAP_SENTENCES flag: test Nboard's own setting.
                editor!!.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await("Test editor did not connect") {
                val service = NboardImeService.debugInstance?.get()
                service?.isPredictionRowInitialized() == true && service.keyboardRoot.isShown &&
                    service.currentInputEditorInfo?.inputType?.and(InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION) ==
                        type.and(InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION) &&
                    !service.smartTypingBehavior.shouldPersonalize()
            }
            val service = NboardImeService.debugInstance!!.get()!!
            var autoCaps = false
            var returnToLetters = false
            main {
                autoCaps = service.autoCapitalizeAfterPunctuationEnabled
                returnToLetters = service.returnToLettersAfterNumberSpaceEnabled
                service.autoCapitalizeAfterPunctuationEnabled = true
                service.returnToLettersAfterNumberSpaceEnabled = true
                service.isNumbersMode = false
                service.isSymbolsSubmenuOpen = false
                service.isEmojiMode = false
                service.isClipboardOpen = false
                service.isAiMode = false
                service.manualShiftMode = ShiftMode.OFF
                service.latestClipboardDismissed = true
                service.refreshAutoShiftFromContextAndRerender(true)
            }
            try { block(service, editor!!) } finally {
                main {
                    service.autoCapitalizeAfterPunctuationEnabled = autoCaps
                    service.returnToLettersAfterNumberSpaceEnabled = returnToLetters
                    service.isNumbersMode = false
                    service.isSymbolsSubmenuOpen = false
                    service.refreshAutoShiftFromContextAndRerender(true)
                }
            }
        }
    }
    private fun context(service: NboardImeService, editor: EditText, text: String) {
        main { editor.setText(text); editor.setSelection(text.length) }
        await("Cursor did not update") { service.editorSelectionStart == text.length }
        main { service.manualShiftMode = ShiftMode.OFF; service.refreshAutoShiftFromContextAndRerender(true) }
    }

    @Test fun spaceReturnsBothSymbolPagesAfterPunctuationOrNumbers() = withEditor(0) { service, editor ->
        for (thirdPage in listOf(false, true)) for (text in listOf("hello!", "price €", "42", "")) {
            context(service, editor, text)
            main { service.isNumbersMode = true; service.isSymbolsSubmenuOpen = thirdPage; service.renderKeyRows(); service.refreshUi() }
            tap(service.spaceButton)
            await("Space did not return page ${if (thirdPage) 3 else 2} to QWERTY") {
                !service.isNumbersMode && !service.isSymbolsSubmenuOpen
            }
            main {
                assertEquals("$text ", editor.text.toString())
                assertEquals("Capitalization must be restored when returning to QWERTY", service.smartTypingBehavior.shouldAutoCapitalizeAtCursor("$text "), service.isAutoShiftEnabled)
            }
            key(service, if (service.isAutoShiftEnabled) "Q" else "q")
        }
    }

    @Test fun capitalizationWorksInRegularEditors() = checkCapitalization(0)
    @Test fun capitalizationWorksInWebFormEditors() = checkCapitalization(InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)
    private fun checkCapitalization(variation: Int) = withEditor(variation) { service, editor ->
        for (text in listOf("", "Hello. ", "Hello! ", "Hello? ", "ordinary words\n", "ordinary words\n  ", "Meet at 5. ")) {
            context(service, editor, text)
            main { assertTrue("No automatic capital after '$text'", service.isAutoShiftEnabled) }
            tap(key(service, "H"))
            await("Uppercase key did not insert uppercase text") { editor.text.toString() == text + "H" }
            await("Shift did not reset after a letter") { !service.isAutoShiftEnabled }
        }
        context(service, editor, "ordinary words")
        main { service.sendOrEnter() }
        await("Enter did not create a paragraph") { editor.text.toString() == "ordinary words\n" }
        await("Enter did not enable automatic capitalization") { service.isAutoShiftEnabled }
        tap(key(service, "H"))
        await("First letter after Enter was not capitalized") { editor.text.toString() == "ordinary words\nH" }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }

    @Test fun chromiumFormsCapitalizeWithoutEditorCapsFlags() {
        for (mode in listOf("textarea", "contenteditable", "input")) {
            shell("am start -W --activity-clear-task -n com.nboard.ime.test/com.nboard.ime.KeyboardBrowserTestActivity --es editorMode $mode")
            await("Chromium $mode did not connect") {
                val service = NboardImeService.debugInstance?.get()
                service?.isPredictionRowInitialized() == true && service.keyboardRoot.isShown &&
                    service.currentInputEditorInfo?.packageName == "com.nboard.ime.test" &&
                    service.currentInputEditorInfo?.inputType?.and(InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    !service.smartTypingBehavior.shouldPersonalize() &&
                    service.currentInputConnection?.getTextBeforeCursor(20, 0)?.isEmpty() == true
            }
            val service = NboardImeService.debugInstance!!.get()!!
            var savedAutoCaps = false
            main {
                savedAutoCaps = service.autoCapitalizeAfterPunctuationEnabled
                service.autoCapitalizeAfterPunctuationEnabled = true
                service.latestClipboardDismissed = true
                service.refreshAutoShiftFromContextAndRerender(true)
                assertTrue("Chromium $mode empty field did not capitalize", service.isAutoShiftEnabled)
            }
            try {
                tap(key(service, "H"))
                await("Chromium did not accept uppercase first letter") {
                    service.currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() == "H"
                }
                main { service.commitKeyText("ello. ") }
                await("Chromium sentence did not capitalize") { service.isAutoShiftEnabled }
                tap(key(service, "H"))
                await("Chromium sentence start was lowercase") {
                    service.currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() == "Hello. H"
                }
                if (mode != "input") {
                    main { service.sendOrEnter() }
                    await("Chromium Enter did not create a paragraph") {
                        service.currentInputConnection?.getTextBeforeCursor(30, 0)?.toString()?.endsWith("\n") == true
                    }
                    await("Chromium new paragraph did not capitalize") {
                        service.isAutoShiftEnabled && find(service.keyboardRoot) {
                            it is TextView && it.isShown && it.text.toString() == "H"
                        } != null
                    }
                    tap(key(service, "H"))
                    await("Chromium paragraph start was lowercase") {
                        service.currentInputConnection?.getTextBeforeCursor(30, 0)?.toString()?.endsWith("\nH") == true
                    }
                }
            } finally {
                main { service.autoCapitalizeAfterPunctuationEnabled = savedAutoCaps }
                shell("am start -W -n com.nboard.ime/.KeyboardTestActivity")
            }
        }
    }

    @Test fun autoCapsSettingStillDisablesCapitalization() = withEditor(0) { service, editor ->
        val saved = KeyboardModeSettings.loadAutoCapitalizeAfterPunctuationEnabled(service)
        KeyboardModeSettings.saveAutoCapitalizeAfterPunctuationEnabled(service, false)
        try {
            main { service.autoCapitalizeAfterPunctuationEnabled = false }
            context(service, editor, "Hello. ")
            main { assertFalse(service.isAutoShiftEnabled) }
            tap(key(service, "h"))
            await("Disabled capitalization still changed case") { editor.text.toString() == "Hello. h" }
        } finally {
            KeyboardModeSettings.saveAutoCapitalizeAfterPunctuationEnabled(service, saved)
            main { service.autoCapitalizeAfterPunctuationEnabled = saved; service.refreshAutoShiftFromContextAndRerender(true) }
        }
    }
}
