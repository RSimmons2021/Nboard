package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long clip offers an expand button that shows all of its text. Adds and removes two test clips. */
class ClipboardExpandDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
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
    private fun all(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else emptyList()

    @Test fun longClipExpandsToShowAllOfItsText() {
        val long = "Test clip: the meeting moved to Thursday at four, bring the signed forms, the blue folder, " +
            "your laptop charger and the printed agenda. Parking is behind the library, level two, row C. Call if late."
        val short = "Test clip: see you soon"
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use {
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown }
                instrumentation.runOnMainSync {
                    service.clipboardHistoryStore.addItem(short)
                    service.clipboardHistoryStore.addItem(long)
                    service.isClipboardOpen = true
                    service.refreshUi()
                    service.renderClipboardItems()
                }
                fun toggleFor(text: String): ImageButton? = all(service.clipboardItemsContainer)
                    .firstOrNull { it.tag == text }?.let { card -> all(card).filterIsInstance<ImageButton>().firstOrNull() }
                await { toggleFor(long)?.visibility == View.VISIBLE }
                SystemClock.sleep(300)
                shell("screencap -p /sdcard/Android/data/com.nboard.ime/files/clipboard-collapsed.png")
                instrumentation.runOnMainSync {
                    val card = all(service.clipboardItemsContainer).first { it.tag == short }
                    val button = all(card).filterIsInstance<Button>().first()
                    assertTrue("short clip: lines=${button.layout?.lineCount} width=${button.width} " +
                        "ellipsis=${button.layout?.let { it.getEllipsisCount(it.lineCount - 1) }} text=${button.text}",
                        toggleFor(short)?.visibility != View.VISIBLE)
                }
                instrumentation.runOnMainSync { toggleFor(long)!!.performClick() }
                SystemClock.sleep(600)
                instrumentation.runOnMainSync {
                    val button = all(service.clipboardItemsContainer).first { it.tag == long }
                        .let { card -> all(card).filterIsInstance<Button>().first() }
                    assertEquals(long, button.text.toString())
                    assertEquals(0, button.layout.getEllipsisCount(button.layout.lineCount - 1))
                }
                shell("screencap -p /sdcard/Android/data/com.nboard.ime/files/clipboard-expanded.png")
            }
        } finally {
            NboardImeService.debugInstance?.get()?.let { service ->
                instrumentation.runOnMainSync {
                    service.clipboardHistoryStore.getItems().filter { it.text.startsWith("Test clip:") }
                        .forEach { service.clipboardHistoryStore.removeItem(it.text) }
                    service.expandedClipText = null
                    service.isClipboardOpen = false
                    service.refreshUi()
                }
            }
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
