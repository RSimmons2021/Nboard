package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold an emoji, choose a skin tone, and the choice sticks. Writes the tone preference, then clears it. */
class EmojiToneDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun <T> main(block: () -> T): T { var r: T? = null; instrumentation.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    @Test fun holdToChooseAToneThatIsRemembered() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        val thumbs = "👍"
        val medium = EmojiTones.withTone(thumbs, EmojiTones.MODIFIERS[2])
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline && !main { NboardImeService.debugInstance?.get()?.let { s -> runCatching { s.keyboardRoot.isShown }.getOrDefault(false) } == true }) SystemClock.sleep(50)
                val service = NboardImeService.debugInstance!!.get()!!
                var editor: EditText? = null
                scenario.onActivity { editor = find(it.window.decorView) { v -> v is EditText } as EditText }
                main { editor!!.setText(""); service.latestClipboardDismissed = true; service.performBottomModeTap(BottomKeyMode.EMOJI) }
                SystemClock.sleep(600)
                assertTrue("this phone's emoji font has skin tones", main { EmojiTones.supportsTones(thumbs) })
                val key = main { find(service.keyboardRoot) { it is TextView && it.isShown && it.text.toString() == thumbs }!! }
                val (x, y) = main { IntArray(2).also { key.getLocationOnScreen(it) }.let { it[0] + key.width / 2f to it[1] + key.height / 2f } }
                main { service.showEmojiTonePopup(key, thumbs, x, y) }
                SystemClock.sleep(400)
                shell("screencap -p /sdcard/Download/nb_tones.png")
                main { service.activeSwipePopupSession!!.selectedIndex = 3; service.highlightSwipePopupSelection(3); service.commitSelectedSwipePopup() }
                SystemClock.sleep(300)
                assertEquals(medium, main { editor!!.text.toString() })
                // The grid key now shows and inserts the chosen tone.
                val toned = main { find(service.keyboardRoot) { it is TextView && it.isShown && it.text.toString() == medium } }
                assertTrue("key shows $medium", toned != null)
                main {
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(now, now + 20, action, toned!!.width / 2f, toned.height / 2f, 0)
                        toned.dispatchTouchEvent(event); event.recycle()
                    }
                }
                SystemClock.sleep(200)
                assertEquals(medium + medium, main { editor!!.text.toString() })
                main { service.emojiTonePreferences.remove(thumbs); service.saveEmojiTonePreferences(); service.performBottomModeTap(BottomKeyMode.EMOJI) }
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
