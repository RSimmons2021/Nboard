package com.nboard.ime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SystemSpellCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun checker(): SystemSpellCheck {
        val manager = context.getSystemService(android.view.textservice.TextServicesManager::class.java)
        assumeTrue(manager.isSpellCheckerEnabled)
        return SystemSpellCheck(context)
    }

    private fun SystemSpellCheck.ask(word: String): SpellVerdict? {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { prefetch(word, Locale.US) }
        return verdict(word, Locale.US, waitMs = 3_000)
    }

    @Test fun recommendsTheIntendedWordForAClearTypo() {
        val checker = checker()
        try {
            val verdict = checker.ask("recieve")
            assertTrue("No answer from the system spell checker", verdict != null)
            assertTrue("recieve was not flagged: $verdict", verdict!!.looksLikeTypo)
            assertTrue("receive was not offered: $verdict", "receive" in verdict.suggestions)
            val known = checker.ask("receive")
            assertTrue("receive was flagged as a typo: $known", known != null && !known.looksLikeTypo)
        } finally {
            checker.close()
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }
    private fun find(root: android.view.View, predicate: (android.view.View) -> Boolean): android.view.View? {
        if (predicate(root)) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            android.os.SystemClock.sleep(50)
        }
        error("Keyboard did not reach the expected state")
    }

    /** Typed key by key at a brisk pace, through the real editor connection. Writes learned data. */
    @Test fun spaceUsesTheCheckersSecondOpinion() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            androidx.test.core.app.ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown && service.autoCorrectEngine.isCommonEnglishWord("the") }
                var editor: android.widget.EditText? = null
                scenario.onActivity { editor = find(it.window.decorView) { v -> v is android.widget.EditText } as android.widget.EditText }
                var savedLanguage = KeyboardLanguageMode.ENGLISH
                instrumentation.runOnMainSync {
                    savedLanguage = service.keyboardLanguageMode
                    service.keyboardLanguageMode = KeyboardLanguageMode.ENGLISH
                    service.autoCorrectEngine.setModeFromKeyboardMode(KeyboardLanguageMode.ENGLISH)
                    service.latestClipboardDismissed = true
                }
                val spaceMs = mutableListOf<Double>()
                fun type(word: String, keyGapMs: Long = 90): String {
                    instrumentation.runOnMainSync { editor!!.setText("we said "); editor!!.setSelection(8) }
                    await { service.editorSelectionStart == 8 }
                    android.os.SystemClock.sleep(200)
                    "$word ".forEach { char ->
                        instrumentation.runOnMainSync {
                            service.manualShiftMode = ShiftMode.OFF; service.isAutoShiftEnabled = false
                            val started = System.nanoTime()
                            service.commitKeyText(char.toString())
                            if (char == ' ') spaceMs += (System.nanoTime() - started) / 1e6
                        }
                        android.os.SystemClock.sleep(keyGapMs)
                    }
                    android.os.SystemClock.sleep(150)
                    var text = ""
                    instrumentation.runOnMainSync { text = editor!!.text.toString() }
                    return text.removePrefix("we said ").trimEnd()
                }
                try {
                    val results = listOf("yeet", "lyft", "postgres", "pozt", "paude", "recieve").associateWith { word ->
                        val alone = service.autoCorrectEngine.correct(word, "said")
                        type(word).also { Log.i("NboardSpellCheck", "$word: nboard alone=$alone, typed=$it") }
                    }
                    // Words the checker knows stay as typed; one-edit typos it is sure of are fixed.
                    assertTrue(results.toString(), results["yeet"] == "yeet" && results["lyft"] == "lyft" && results["postgres"] == "postgres")
                    assertTrue(results.toString(), results["pozt"] == "post" && results["paude"] == "pause" && results["recieve"] == "receive")
                    // Fast typing reaches Space before the answer; the wait for it stays bounded.
                    spaceMs.clear()
                    repeat(3) { listOf("pozt", "paude", "lyft", "tossd").forEach { type(it, keyGapMs = 15) } }
                    Log.i("NboardSpellCheck", "fast space ms=${spaceMs.map { "%.1f".format(it) }}")
                    assertTrue("Space took ${spaceMs.max()} ms", spaceMs.max() < SYSTEM_SPELL_CHECK_WAIT_MS + 25)
                } finally {
                    instrumentation.runOnMainSync {
                        service.keyboardLanguageMode = savedLanguage
                        service.autoCorrectEngine.setModeFromKeyboardMode(savedLanguage)
                    }
                }
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }

    /**
     * Replays SpellCorrectionBenchmark cases through the system checker. Push the dump first:
     *   adb push cases.tsv /sdcard/Android/data/com.nboard.ime/files/spell-cases.tsv
     * and pull spell-verdicts.tsv from the same folder afterwards.
     */
    @Test fun replayBenchmarkCases() {
        val folder = requireNotNull(context.getExternalFilesDir(null))
        val cases = File(folder, "spell-cases.tsv")
        assumeTrue(cases.isFile)
        val checker = checker()
        try {
            File(folder, "spell-verdicts.tsv").printWriter().use { out ->
                cases.readLines().map { it.split('\t')[1] }.distinct().forEach { typed ->
                    val verdict = checker.ask(typed)
                    out.println(listOf(typed, verdict?.looksLikeTypo ?: "", verdict?.recommended ?: "",
                        verdict?.suggestions?.joinToString("|").orEmpty(), checker.lastLatencyMs).joinToString("\t"))
                }
            }
            Log.i("NboardSpellCheck", "Replayed ${cases.readLines().size} cases")
        } finally {
            checker.close()
        }
    }
}
