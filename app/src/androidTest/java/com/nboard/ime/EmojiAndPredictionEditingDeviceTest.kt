package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class EmojiAndPredictionEditingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun findEditor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
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
        error("Test keyboard did not become ready")
    }
    private fun tap(view: View) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
            view.dispatchTouchEvent(event); event.recycle()
        }
    }

    @Test fun acceptedWordsDeleteOneCharacterAndFavoritesUseTwoFrequencyRows() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.autoCorrectEngine.isLoadedWord("tomorrow", KeyboardLanguageMode.ENGLISH) }
                instrumentation.runOnMainSync { assertTrue(service.isKnownWord("tomorrow")) }
                var editor: EditText? = null
                scenario.onActivity {
                    editor = findEditor(it.window.decorView)!!
                    editor!!.setText("hel"); editor!!.setSelection(3)
                }
                await { service.editorSelectionStart == 3 && service.keyboardRoot.isShown }
                instrumentation.runOnMainSync {
                    service.latestClipboardDismissed = true
                    service.isAiMode = false; service.isNumbersMode = false
                    service.commitWordPrediction("hello")
                }
                scenario.onActivity { assertEquals("hello ", editor!!.text.toString()) }
                await { service.editorSelectionStart == 6 }
                instrumentation.runOnMainSync { service.deleteOneCharacter() }
                scenario.onActivity { assertEquals("hello", editor!!.text.toString()) }
                await { service.editorSelectionStart == 5 }
                instrumentation.runOnMainSync { service.deleteOneCharacter() }
                scenario.onActivity { assertEquals("hell", editor!!.text.toString()) }

                var shift: View? = null
                instrumentation.runOnMainSync {
                    service.manualShiftMode = ShiftMode.OFF; service.isAutoShiftEnabled = false; service.shiftTapArmed = false
                    service.renderKeyRows()
                    val row = service.keyboardRoot.findViewById<LinearLayout>(R.id.row3)
                    shift = (0 until row.childCount).map { row.getChildAt(it) }.first { it is ImageButton }
                    tap(shift!!)
                }
                // A slow second tap still locks caps; this is deliberately not a timed double tap.
                SystemClock.sleep(1200)
                instrumentation.runOnMainSync {
                    tap(shift!!)
                    assertEquals(ShiftMode.CAPS_LOCK, service.manualShiftMode)
                    assertEquals("Caps lock on. Tap to turn off", shift!!.contentDescription.toString())
                    val row = service.keyboardRoot.findViewById<LinearLayout>(R.id.row1)
                    val e = (0 until row.childCount).map { row.getChildAt(it) }.first { it is TextView && it.text.toString() == "E" }
                    tap(e); tap(e)
                    assertEquals(ShiftMode.CAPS_LOCK, service.manualShiftMode)
                    tap(shift!!)
                    assertEquals(ShiftMode.OFF, service.manualShiftMode)
                }
                scenario.onActivity { assertEquals("hellEE", editor!!.text.toString()) }

                val counts = service.emojiUsageCounts.toMap()
                val recents = service.emojiRecents.toList()
                try {
                    instrumentation.runOnMainSync {
                        service.emojiUsageCounts.clear()
                        service.emojiUsageCounts.putAll(mapOf("🦄" to 20, "🙂" to 1))
                        service.emojiRecents.clear(); service.emojiRecents.add("🙂")
                        service.performBottomModeTap(BottomKeyMode.EMOJI)
                        val section = service.emojiMostUsedRow
                        assertEquals(2, section.childCount)
                        for (i in 0..1) assertEquals(8, (section.getChildAt(i) as LinearLayout).childCount)
                        assertEquals("🦄", ((section.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text.toString())
                        assertTrue(service.emojiSuggestionsScroll.isShown)
                        service.onEmojiChosen("🙂")
                        assertEquals("🦄", ((section.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text.toString())
                        assertEquals(2, service.emojiUsageCounts["🙂"])
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        service.emojiUsageCounts.clear(); service.emojiUsageCounts.putAll(counts)
                        service.emojiRecents.clear(); service.emojiRecents.addAll(recents)
                        service.saveEmojiUsage()
                        if (service.isEmojiMode) service.performBottomModeTap(BottomKeyMode.EMOJI)
                    }
                }
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
