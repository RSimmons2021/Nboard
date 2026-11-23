package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emoji in the strip's right slot, and what tapping one inserts. Writes learned data and emoji usage. */
class EmojiPredictionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun find(view: View): EditText? = when (view) {
        is EditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Keyboard did not reach the expected state")
    }

    @Test fun stripOffersEmojiAndTapInsertsIt() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown }
                await { service.emojiKeywords.suggest("pizza").isNotEmpty() }
                lateinit var editor: EditText
                scenario.onActivity { editor = find(it.window.decorView)!! }
                instrumentation.runOnMainSync { service.latestClipboardDismissed = true }
                fun type(text: String) {
                    instrumentation.runOnMainSync { editor.setText(""); editor.setSelection(0) }
                    await { service.editorSelectionStart == 0 }
                    text.forEach { char -> instrumentation.runOnMainSync { service.commitKeyText(char.toString()) }; SystemClock.sleep(60) }
                    SystemClock.sleep(500)
                }
                type("pizza")
                await { "🍕" in service.predictionRow.emojis }
                type("happy birthday ")
                await { "🎂" in service.predictionRow.emojis }
                type("the ")
                instrumentation.runOnMainSync { assertTrue(service.predictionRow.emojis.toString(), service.predictionRow.emojis.isEmpty()) }
                type("i want pizza")
                await { "🍕" in service.predictionRow.emojis }
                shell("screencap -p /sdcard/Android/data/com.nboard.ime/files/emoji-strip.png")
                instrumentation.runOnMainSync { service.predictionRow.onEmoji("🍕") }
                SystemClock.sleep(300)
                var text = ""
                instrumentation.runOnMainSync { text = editor.text.toString() }
                assertEquals("I want pizza 🍕 ", text)
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
