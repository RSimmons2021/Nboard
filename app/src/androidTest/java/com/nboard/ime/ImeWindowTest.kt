package com.nboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.nboard.ime.clipboard.ClipboardHistoryStore
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inspects the actual IME decor; accessibility snapshots can omit IME windows. */
@SdkSuppress(minSdkVersion = 29)
class ImeWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .let { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() } }

    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) find(root.getChildAt(index), predicate)?.let { return it }
        return null
    }

    private fun waitForPrediction(): PredictionStripView {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var result: PredictionStripView? = null
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().forEach { decor ->
                    find(decor) { it.contentDescription == "Dismiss recent clipboard" && it.isShown }
                        ?.let { dismiss ->
                            val now = SystemClock.uptimeMillis()
                            dismiss.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
                            dismiss.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, 1f, 1f, 0))
                        }
                    (find(decor) { it is PredictionStripView && it.isShown } as? PredictionStripView)
                        ?.takeIf { it.words.isNotEmpty() && it.isAttachedToWindow }?.let { result = it }
                }
            }
            result?.let { return it }
            SystemClock.sleep(100)
        }
        error("Compose predictions did not appear in the real keyboard window")
    }

    @Test fun composePredictionsAttachToImeWindowAndSurviveReopening() {
        val previous = shell("settings get secure default_input_method")
        shell("ime enable com.nboard.ime/.NboardImeService")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                waitForPrediction()
                scenario.onActivity { activity ->
                    val input = find(activity.window.decorView) { it is EditText } as EditText
                    input.setText("hel")
                    input.setSelection(input.text.length)
                }
                val strip = waitForPrediction()
                val location = IntArray(2)
                instrumentation.runOnMainSync { strip.getLocationOnScreen(location) }
                val x = location[0] + strip.width / 2f
                val y = location[1] + strip.height / 2f
                val now = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
                scenario.onActivity { activity ->
                    val input = find(activity.window.decorView) { it is EditText } as EditText
                    assertTrue(input.text.toString() != "hel")
                    activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                }
                SystemClock.sleep(400)
                scenario.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.currentFocus!!, InputMethodManager.SHOW_IMPLICIT)
                }
                waitForPrediction()
                // Verify OS clipboard capture, the hold-to-pin gesture, and disk-backed reload.
                val context = instrumentation.targetContext
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                var originalClip: ClipData? = null
                val synthetic = "Nboard persistent test clip"
                ClipboardHistoryStore(context).setPinned(synthetic, false)
                scenario.onActivity {
                    originalClip = clipboard.primaryClip
                    clipboard.setPrimaryClip(ClipData.newPlainText("Keyboard test", synthetic))
                }
                try {
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    while (ClipboardHistoryStore(context).getItems().none { it.text == synthetic } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                    assertTrue(ClipboardHistoryStore(context).getItems().any { it.text == synthetic })
                    fun windowView(predicate: (View) -> Boolean): View {
                        var result: View? = null
                        instrumentation.runOnMainSync {
                            result = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it, predicate) }
                        }
                        return checkNotNull(result)
                    }
                    fun tap(view: View) {
                        instrumentation.runOnMainSync {
                            val time = SystemClock.uptimeMillis()
                            view.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, 0))
                            view.dispatchTouchEvent(MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, view.width / 2f, view.height / 2f, 0))
                        }
                        SystemClock.sleep(350)
                    }
                    tap(windowView { it.id == R.id.aiModeButton })
                    assertTrue(windowView { it.id == R.id.emojiPanel }.isShown)
                    tap(windowView { it.id == R.id.modeSwitchButton })
                    tap(windowView { it.id == R.id.modeSwitchButton })
                    val symbols = windowView { it.id == R.id.row2 } as ViewGroup
                    instrumentation.runOnMainSync {
                        val labels = (0 until symbols.childCount).mapNotNull { (symbols.getChildAt(it) as? TextView)?.text?.toString() }
                        org.junit.Assert.assertEquals(listOf("!", "@", "#", "$"), labels.take(4))
                    }
                    tap(windowView { it.id == R.id.modeSwitchButton })
                    tap(windowView { it.id == R.id.toolbarClipboardButton })
                    SystemClock.sleep(400)
                    val card = windowView { it is TextView && it.text.toString() == synthetic && it.isShown }
                    val cardLocation = IntArray(2)
                    instrumentation.runOnMainSync { card.getLocationOnScreen(cardLocation) }
                    val down = SystemClock.uptimeMillis()
                    fun inject(action: Int, x: Float, y: Float) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                        event.recycle()
                    }
                    inject(MotionEvent.ACTION_DOWN, cardLocation[0] + card.width / 2f, cardLocation[1] + card.height / 2f)
                    SystemClock.sleep(650)
                    val pin = windowView { it.contentDescription == "Pin clip" && it.isShown }
                    val pinLocation = IntArray(2)
                    instrumentation.runOnMainSync { pin.getLocationOnScreen(pinLocation) }
                    val pinX = pinLocation[0] + pin.width / 2f
                    val pinY = pinLocation[1] + pin.height / 2f
                    inject(MotionEvent.ACTION_MOVE, pinX, pinY)
                    inject(MotionEvent.ACTION_UP, pinX, pinY)
                    SystemClock.sleep(200)
                    assertTrue(ClipboardHistoryStore(context).getItems().single { it.text == synthetic }.pinned)
                } finally {
                    scenario.onActivity { if (originalClip != null) clipboard.setPrimaryClip(originalClip!!) else clipboard.clearPrimaryClip() }
                }
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
