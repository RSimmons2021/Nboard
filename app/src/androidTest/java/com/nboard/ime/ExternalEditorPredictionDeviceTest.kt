package com.nboard.ime

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Suggestion taps in another app's editor (run with that editor focused and nBoard showing,
 * e.g. a web page text area in Chrome). Logs each step under "ExternalEditor".
 */
class ExternalEditorPredictionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun log(message: String) = android.util.Log.i("ExternalEditor", message)

    private fun <T> main(block: () -> T): T { var result: T? = null; instrumentation.runOnMainSync { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition)) return true; SystemClock.sleep(25) }
        return false
    }

    private fun tapMiddleSuggestion(service: NboardImeService) {
        val (x, y) = main {
            val location = IntArray(2)
            service.predictionRow.getLocationOnScreen(location)
            location[0] + service.predictionRow.width / 2f to location[1] + service.predictionRow.height / 2f
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    private fun textBefore(service: NboardImeService) = main { service.currentInputConnection?.getTextBeforeCursor(100, 0)?.toString() }

    @Test fun tappedSuggestionsLeaveASpaceAndTheNextWordIsTappable() {
        // Starting the test restarts this process, and with it the keyboard: wait for it to show
        // again, tapping the focused page once to bring it back if needed.
        fun shown() = NboardImeService.debugInstance?.get()?.let { runCatching { it.isPredictionRowInitialized() && it.keyboardRoot.isShown }.getOrDefault(false) } == true
        if (!waitFor(4000) { shown() }) {
            instrumentation.uiAutomation.executeShellCommand("input tap 600 500").close()
            waitFor(6000) { shown() }
        }
        val service = NboardImeService.debugInstance?.get()
        assumeTrue("Focus another app's editor with nBoard showing first", service != null &&
            main { service.currentInputEditorInfo?.packageName != "com.nboard.ime" && shown() })
        service!!
        log("editor package=${main { service.currentInputEditorInfo?.packageName }} inputType=${main { service.currentInputEditorInfo?.inputType }}")
        main { service.latestClipboardDismissed = true; service.manualShiftMode = ShiftMode.OFF; service.isAutoShiftEnabled = false }
        main { "hel".forEach { service.commitKeyText(it.toString()) } }
        val started = SystemClock.uptimeMillis()
        val ready = waitFor(3000) { service.predictionRow.acceptSuggestions && service.predictionRow.words.firstOrNull()?.startsWith("hel", true) == true }
        log("after 'hel': ready=$ready in ${SystemClock.uptimeMillis() - started}ms row=${main { service.predictionRow.words }} text='${textBefore(service)}'")
        // Optional pause for a screenshot of the row (-e pauseMs 4000).
        InstrumentationRegistry.getArguments().getString("pauseMs")?.toLongOrNull()?.let { SystemClock.sleep(it) }
        tapMiddleSuggestion(service)
        SystemClock.sleep(400)
        log("after tapping middle: text='${textBefore(service)}' row=${main { service.predictionRow.words }} accepting=${main { service.predictionRow.acceptSuggestions }}")
        val next = SystemClock.uptimeMillis()
        val nextReady = waitFor(3000) { service.predictionRow.acceptSuggestions && service.predictionRow.words.isNotEmpty() }
        log("next-word row ready=$nextReady in ${SystemClock.uptimeMillis() - next}ms row=${main { service.predictionRow.words }}")
        tapMiddleSuggestion(service)
        SystemClock.sleep(400)
        log("after tapping next word: text='${textBefore(service)}'")
        main { service.currentInputConnection?.deleteSurroundingText(200, 200) } // leave the page empty
    }
}
