package com.nboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpacebarCursorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(30)
        }
        var state = ""
        main {
            val service = NboardImeService.debugInstance?.get()
            state = "active=${service?.spacebarCursor?.isActive}, selection=${service?.editorSelectionStart}, lens=${service?.spacebarCursor?.lens?.lens?.isShown}, pressed=${service?.spaceButton?.isPressed}"
        }
        error("$message ($state)")
    }
    private fun findEditor(root: View): EditText? {
        if (root is EditText) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findEditor(root.getChildAt(i))?.let { return it }
        return null
    }
    private fun withEditor(text: String, cursor: Int = text.length, block: (NboardImeService, EditText) -> Unit) {
        ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
            var editor: EditText? = null
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                editor = findEditor(activity.window.decorView)!!
                editor!!.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editor!!.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                editor!!.setText(text); editor!!.setSelection(cursor)
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await("Private test editor did not connect") {
                val service = NboardImeService.debugInstance?.get()
                service?.isPredictionRowInitialized() == true && service.isInputViewShown && service.spaceButton.isShown && service.spaceButton.height > 0 &&
                    service.currentInputEditorInfo?.packageName == "com.nboard.ime" &&
                    service.currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 &&
                    service.editorSelectionStart == cursor
            }
            val service = NboardImeService.debugInstance!!.get()!!
            main {
                service.latestClipboardDismissed = true
                service.isAiMode = false; service.isClipboardOpen = false; service.isEmojiMode = false
                service.isNumbersMode = false; service.isSymbolsSubmenuOpen = false
                service.refreshAutoShiftFromContextAndRerender(true)
            }
            SystemClock.sleep(500) // Initial editor and keyboard layout have now settled.
            try { block(service, editor!!) } finally {
                cancelTouchStream()
                main { service.spacebarCursor.cancel() }
            }
        }
    }
    private fun cancelTouchStream() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
    }
    private inner class Gesture(private val service: NboardImeService) {
        private val downAt = SystemClock.uptimeMillis()
        private var x = 0f
        private var y = 0f
        init {
            main {
                val location = IntArray(2)
                service.spaceButton.getLocationOnScreen(location)
                x = location[0] + service.spaceButton.width / 2f
                y = location[1] + service.spaceButton.height / 2f
            }
            cancelTouchStream()
            event(MotionEvent.ACTION_DOWN)
            await("Space did not receive the press at ($x, $y)") { service.spaceButton.isPressed }
        }
        private fun event(action: Int) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { this.x = this@Gesture.x; this.y = this@Gesture.y; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("Touch $action at ($x, $y) was rejected", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        fun hold() { await("Stationary Space hold did not enter cursor mode") { service.spacebarCursor.isActive }; SystemClock.sleep(170) }
        fun move(dx: Int, dy: Int = 0) { x += service.dp(kotlin.math.abs(dx)) * kotlin.math.sign(dx.toFloat()); y += service.dp(kotlin.math.abs(dy)) * kotlin.math.sign(dy.toFloat()); event(MotionEvent.ACTION_MOVE) }
        fun up() { event(MotionEvent.ACTION_UP) }
        fun cancel() { event(MotionEvent.ACTION_CANCEL) }
    }

    @Test fun stationaryHoldShowsLensAndReleaseNeverInsertsSpace() = withEditor("Move this cursor smoothly") { service, editor ->
        val before = editor.text.toString()
        val gesture = Gesture(service)
        gesture.hold()
        main { assertEquals("Space label must fade out in trackpad mode", 0, android.graphics.Color.alpha(service.spaceButton.currentTextColor)) }
        await("Text lens was not shown") { service.spacebarCursor.lens.lens?.isShown == true }
        await("Magnifier did not float above the editor's caret") {
            val bubble = service.spacebarCursor.lens.lens ?: return@await false
            val location = IntArray(2); bubble.getLocationOnScreen(location)
            val editorLocation = IntArray(2); editor.getLocationOnScreen(editorLocation)
            val caretTop = editorLocation[1] + editor.totalPaddingTop +
                editor.layout.getLineTop(editor.layout.getLineForOffset(editor.selectionStart)) - editor.scrollY
            location[1] + bubble.height <= caretTop
        }
        // Render only our lens view, never a screen capture containing another app or PiP.
        main {
            val lens = service.spacebarCursor.lens.lens!!
            assertTrue(lens.width > 0 && lens.height > 0)
            val bitmap = Bitmap.createBitmap(lens.width, lens.height, Bitmap.Config.ARGB_8888)
            lens.draw(Canvas(bitmap))
            File(instrumentation.targetContext.cacheDir, "cursor-lens-test.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        gesture.up()
        await("Cursor mode did not exit") { !service.spacebarCursor.isActive }
        await("Lens did not dismiss") { service.spacebarCursor.lens.lens == null }
        await("Keyboard label did not return") { android.graphics.Color.alpha(service.spaceButton.currentTextColor) == 255 }
        main { assertEquals(before, editor.text.toString()) }
    }

    @Test fun preciseMovementReversesAndKeepsEmojiIntact() = withEditor("a👨‍👩‍👧‍👦👍🏽z") { service, editor ->
        val text = editor.text.toString()
        val gesture = Gesture(service)
        gesture.hold()
        gesture.move(-8)
        await("First precision step did not move one character") { editor.selectionStart == text.length - 1 }
        gesture.move(-8)
        await("Skin-tone emoji was split") { editor.selectionStart == text.indexOf("👍") }
        gesture.move(-8)
        await("Family emoji was split") { editor.selectionStart == 1 }
        gesture.move(8)
        await("Reversal was not precise") { editor.selectionStart == text.indexOf("👍") }
        gesture.up()
        main { assertEquals(text, editor.text.toString()) }
    }

    @Test fun verticalMotionKeepsTheColumnAndCancelRestoresKeyboard() {
        val line = "abcdefghij"
        val text = "$line\n$line\n$line"
        withEditor(text, 25) { service, editor ->
            val gesture = Gesture(service)
            gesture.hold()
            gesture.move(0, -32)
            await("Vertical gesture did not move to the previous line") { editor.selectionStart == 14 }
            gesture.move(0, 32)
            await("Vertical reversal lost the column") { editor.selectionStart == 25 }
            gesture.cancel()
            await("Cancelled gesture left the trackpad active") { !service.spacebarCursor.isActive && service.spacebarCursor.lens.lens == null }
            main { assertEquals(text, editor.text.toString()); assertEquals(1f, service.spaceButton.scaleX, .05f) }
        }
    }

    @Test fun documentEdgeReversesImmediatelyAndShortTapStillTypesSpace() = withEditor("abcdefghij", 0) { service, editor ->
        val gesture = Gesture(service)
        gesture.hold()
        gesture.move(-80)
        SystemClock.sleep(80)
        main { assertEquals(0, editor.selectionStart) }
        gesture.move(8)
        await("Overscroll made reversal unresponsive") { editor.selectionStart == 1 }
        gesture.up()
        SystemClock.sleep(170)
        val tap = Gesture(service)
        tap.up()
        await("A normal space tap no longer types one space") { editor.text.toString() == "a bcdefghij" }
    }
}
