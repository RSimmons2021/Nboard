package com.nboard.ime

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real two-thumb input injected at screen coordinates, through the IME window. */
class TypingRolloverDeviceTest {
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

    private fun keyCenter(service: NboardImeService, label: String): Pair<Float, Float> {
        var center = 0f to 0f
        instrumentation.runOnMainSync {
            val key = find(service.keyboardRoot) { it is TextView && it.isShown && it.text.toString() == label }!!
            val location = IntArray(2)
            key.getLocationOnScreen(location)
            center = location[0] + key.width / 2f to location[1] + key.height / 2f
        }
        return center
    }

    private fun inject(downAt: Long, action: Int, points: List<Pair<Float, Float>>, ids: List<Int>) {
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = points.map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, ids.size,
            properties.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    /** Press [first], press [second] while [first] is held, then release in [firstReleasedFirst] order. */
    private fun rollover(service: NboardImeService, first: String, second: String, firstReleasedFirst: Boolean) {
        val a = keyCenter(service, first)
        val b = keyCenter(service, second)
        val downAt = SystemClock.uptimeMillis()
        inject(downAt, MotionEvent.ACTION_DOWN, listOf(a), listOf(0))
        SystemClock.sleep(40)
        inject(downAt, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(a, b), listOf(0, 1))
        SystemClock.sleep(40)
        if (firstReleasedFirst) {
            inject(downAt, MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(a, b), listOf(0, 1))
            SystemClock.sleep(30)
            inject(downAt, MotionEvent.ACTION_UP, listOf(b), listOf(1))
        } else {
            inject(downAt, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(a, b), listOf(0, 1))
            SystemClock.sleep(30)
            inject(downAt, MotionEvent.ACTION_UP, listOf(a), listOf(0))
        }
        SystemClock.sleep(60)
    }

    private fun withKeyboard(block: (NboardImeService, EditText) -> Unit) {
        val previous = shell("settings get secure default_input_method")
        shell("ime enable com.nboard.ime/.NboardImeService")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown }
                var editor: EditText? = null
                scenario.onActivity { activity -> editor = find(activity.window.decorView) { it is EditText } as EditText }
                instrumentation.runOnMainSync {
                    service.latestClipboardDismissed = true
                    service.isAiMode = false; service.isNumbersMode = false
                    service.manualShiftMode = ShiftMode.OFF; service.isAutoShiftEnabled = false
                    service.renderKeyRows()
                }
                block(service, editor!!)
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }

    @Test fun overlappingTapsKeepPressOrder() = withKeyboard { service, editor ->
        val results = mutableListOf<String>()
        for (firstReleasedFirst in listOf(true, false)) {
            instrumentation.runOnMainSync { editor.setText("x "); editor.setSelection(2) }
            SystemClock.sleep(300)
            instrumentation.runOnMainSync {
                android.util.Log.i("RolloverTest", "before: text='${editor.text}' sel=${editor.selectionStart} ime=${service.editorSelectionStart}")
            }
            await { service.editorSelectionStart == 2 }
            rollover(service, "t", "h", firstReleasedFirst)
            await { true }
            var text = ""
            instrumentation.runOnMainSync { text = editor.text.toString() }
            results += "releaseFirstFirst=$firstReleasedFirst -> '${text}'"
        }
        android.util.Log.i("RolloverTest", results.joinToString(" | "))
        assertEquals(listOf("x th", "x th"), results.map { it.substringAfter("'").substringBeforeLast("'") })
    }

    @Test fun secondFingerInRowGapStillTypes() = withKeyboard { service, editor ->
        instrumentation.runOnMainSync { editor.setText("x "); editor.setSelection(2) }
        await { service.editorSelectionStart == 2 }
        SystemClock.sleep(500) // let the suggestion row settle so key positions are final
        val a = keyCenter(service, "t")
        var gap = 0f to 0f
        instrumentation.runOnMainSync {
            val key = find(service.keyboardRoot) { it is TextView && it.isShown && it.text.toString() == "h" }!!
            val location = IntArray(2)
            key.getLocationOnScreen(location)
            // 3 dp below the key: inside the 8 dp gap between rows, nearest to "h".
            gap = location[0] + key.width / 2f to location[1] + key.height + 3 * key.resources.displayMetrics.density
        }
        val downAt = SystemClock.uptimeMillis()
        inject(downAt, MotionEvent.ACTION_DOWN, listOf(a), listOf(0))
        SystemClock.sleep(40)
        inject(downAt, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(a, gap), listOf(0, 1))
        SystemClock.sleep(40)
        inject(downAt, MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(a, gap), listOf(0, 1))
        SystemClock.sleep(30)
        inject(downAt, MotionEvent.ACTION_UP, listOf(gap), listOf(1))
        SystemClock.sleep(100)
        var text = ""
        instrumentation.runOnMainSync { text = editor.text.toString() }
        android.util.Log.i("RolloverTest", "gap -> '$text'")
        assertEquals("x th", text)
    }

    private fun editAndRead(service: NboardImeService, editor: EditText, text: String, cursor: Int, action: () -> Unit): String {
        instrumentation.runOnMainSync { editor.setText(text); editor.setSelection(cursor) }
        await { service.editorSelectionStart == cursor }
        SystemClock.sleep(200) // the editor connection must reflect the new text before typing
        instrumentation.runOnMainSync(action)
        SystemClock.sleep(100)
        var result = ""
        instrumentation.runOnMainSync { result = editor.text.toString() }
        return result
    }

    @Test fun editingNearPunctuationAndInsideWords() = withKeyboard { service, editor ->
        await { service.autoCorrectEngine.isLoadedWord("tomorrow", KeyboardLanguageMode.ENGLISH) }
        val results = linkedMapOf<String, String>()
        fun typeChars(chars: String) = chars.forEach { service.commitKeyText(it.toString()) }
        results["didn't + space"] = editAndRead(service, editor, "", 0) { typeChars("didn't ") }
        results["didn’t + space"] = editAndRead(service, editor, "", 0) { typeChars("didn’t ") }
        results["typing ok google.com"] = editAndRead(service, editor, "", 0) { typeChars("ok google.com ") }
        results["space after (teh)"] = editAndRead(service, editor, "(teh)", 5) { service.commitKeyText(" ") }
        results["space after teh\""] = editAndRead(service, editor, "\"teh\"", 5) { service.commitKeyText(" ") }
        results["accept hello inside hel|lo"] = editAndRead(service, editor, "hello world", 3) { service.commitWordPrediction("hello") }
        results["accept world before space"] = editAndRead(service, editor, "hello wor there", 9) { service.commitWordPrediction("world") }
        // While the row refreshes, a completion of the typed fragment is accepted...
        results["refreshing: tap hello after hel"] = editAndRead(service, editor, "hel", 3) {
            service.predictionRow.acceptSuggestions = false; service.predictionRow.onAccept("hello")
        }
        // ...but a stale completion after the word has ended is not.
        results["refreshing: tap hello after hel+space"] = editAndRead(service, editor, "hel ", 4) {
            service.predictionRow.acceptSuggestions = false; service.predictionRow.onAccept("hello")
        }
        android.util.Log.i("RolloverTest", results.toString())
        // Correcting or leaving the word are both acceptable; damaging neighbouring text is not.
        val acceptable = mapOf(
            "didn't + space" to setOf("Didn't ", "didn't "),
            "didn’t + space" to setOf("Didn’t ", "didn’t ", "Didn't ", "didn't "),
            "typing ok google.com" to setOf("Ok google.com ", "ok google.com ", "OK google.com "),
            "space after (teh)" to setOf("(the) ", "(teh) "),
            "space after teh\"" to setOf("\"the\" ", "\"teh\" "),
            "accept hello inside hel|lo" to setOf("hello world"),
            "accept world before space" to setOf("hello world there"),
            "refreshing: tap hello after hel" to setOf("hello "),
            "refreshing: tap hello after hel+space" to setOf("hel "),
        )
        val failures = results.filter { (case, text) -> text !in acceptable.getValue(case) }
        assertEquals("Unexpected edits: $failures", emptyMap<String, String>(), failures)
    }
}
