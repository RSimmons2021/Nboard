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

/** Capitals the keyboard learns or knows, through the real editor connection. Writes learned data. */
class WordCasingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        return null
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

    @Test fun learnedAndFixedCapitals() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown && service.autoCorrectEngine.isLoadedWord("tomorrow", KeyboardLanguageMode.ENGLISH) }
                var editor: EditText? = null
                scenario.onActivity { editor = find(it.window.decorView) { v -> v is EditText } as EditText }
                fun type(start: String, chars: String): String {
                    instrumentation.runOnMainSync { editor!!.setText(start); editor!!.setSelection(start.length) }
                    await { service.editorSelectionStart == start.length }
                    SystemClock.sleep(200)
                    instrumentation.runOnMainSync {
                        service.manualShiftMode = ShiftMode.OFF; service.isAutoShiftEnabled = false
                        chars.forEach { service.commitKeyText(it.toString()) }
                    }
                    SystemClock.sleep(150)
                    var text = ""
                    instrumentation.runOnMainSync { text = editor!!.text.toString() }
                    return text
                }
                // Independent of the phone's own history ("im" may be the owner's habit, or a rejected fix).
                instrumentation.runOnMainSync {
                    service.wordCasing.clear()
                    service.latestClipboardDismissed = true // a recent clip replaces the suggestion row
                    listOf("im", "jaxon").forEach { service.learnedWordFrequency.remove(it) }
                    service.rejectedCorrections.keys.removeAll { it.startsWith("im->") || it.startsWith("jaxon->") || it.startsWith("i->") }
                }
                assertEquals("so I ", type("so ", "i "))
                assertEquals("so I'm ", type("so ", "im "))
                // Written capitalised once: suggested that way, not yet autocorrected.
                type("I saw ", "Jaxon ")
                assertEquals("met jaxon ", type("met ", "jaxon "))
                // Twice: lowercase typing is corrected, and backspace undoes it.
                type("I saw ", "Jaxon ")
                type("I saw ", "Jaxon ")
                assertEquals("met Jaxon ", type("met ", "jaxon "))
                instrumentation.runOnMainSync { service.deleteOneCharacter() }
                SystemClock.sleep(150)
                var reverted = ""
                instrumentation.runOnMainSync { reverted = editor!!.text.toString() }
                assertEquals("met jaxon ", reverted)
                // Suggestions use the learned capitals.
                type("we met ", "jax")
                await { service.predictionRow.words.any { it == "Jaxon" } }
                instrumentation.runOnMainSync { assertTrue(service.predictionRow.words.toString(), "Jaxon" in service.predictionRow.words) }
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
