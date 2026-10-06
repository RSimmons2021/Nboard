package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPreviewDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun <T> main(block: () -> T): T { var r: T? = null; instrumentation.runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun screen(view: View) = IntArray(2).also { view.getLocationOnScreen(it) }

    @Test fun bubbleSitsAboveTheKeyAndGlidesOnlyWhenEnabled() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline && !main { NboardImeService.debugInstance?.get()?.let { s -> runCatching { s.keyboardRoot.isShown }.getOrDefault(false) } == true }) SystemClock.sleep(50)
                val service = NboardImeService.debugInstance!!.get()!!
                SystemClock.sleep(400)
                fun key(label: String) = main { find(service.keyboardRoot) { it is TextView && it.isShown && it.text.toString().equals(label, true) } as TextView }
                val q = key("q"); val p = key("p")
                // The first press creates the pop-up window, as the first keystroke of a session does.
                main { service.keyPressPreview.show(q, "q"); service.keyPressPreview.hide(q) }
                SystemClock.sleep(400)
                // Sliding on: the bubble starts at q and travels to p.
                main { service.keyPressPreview.slideEnabled = true; service.keyPressPreview.slideIntensity = 1f
                    service.keyPressPreview.show(q, "q"); service.keyPressPreview.hide(q); service.keyPressPreview.show(p, "p") }
                SystemClock.sleep(50)
                val bubble = main { service.keyPressPreview.bubbleForTest()!! }
                val midway = main { screen(bubble)[0] }
                SystemClock.sleep(400)
                val (endX, endY) = main { screen(bubble).let { it[0] to it[1] } }
                val (pX, pY) = main { screen(p).let { it[0] to it[1] } }
                android.util.Log.i("KeyPreviewTest", "midway=$midway end=$endX,$endY keyP=$pX,$pY bubble=${bubble.width}x${bubble.height} visible=${bubble.isShown}")
                assertTrue(main { bubble.isShown })
                assertTrue("bubble centred over p", kotlin.math.abs(endX + bubble.width / 2 - (pX + p.width / 2)) <= 4)
                // Just above the key: its bottom overlaps the key's top edge by the 4 dp design overlap.
                val overlap = endY + bubble.height - pY
                assertTrue("bubble bottom ${endY + bubble.height} vs key top $pY", overlap in 0..(8 * p.resources.displayMetrics.density).toInt())
                assertTrue("glides from q toward p", midway < endX - 20)
                // Sliding off: it jumps straight to the new key.
                main { service.keyPressPreview.slideEnabled = false
                    service.keyPressPreview.show(q, "q"); service.keyPressPreview.hide(q); service.keyPressPreview.show(p, "p") }
                SystemClock.sleep(30)
                assertEquals(endX, main { screen(bubble)[0] })
                main { service.keyPressPreview.hide() }
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
