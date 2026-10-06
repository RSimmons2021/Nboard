package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/**
 * Main-thread cost of learned-history bookkeeping once the stores reach their caps.
 * Writes synthetic history into the keyboard's preferences, so it only runs on request:
 *   adb shell am instrument -w -e learningCost 1 -e class com.nboard.ime.LearningStoreCostDeviceTest ...
 * Back up shared_prefs first and restore them afterwards.
 */
class LearningStoreCostDeviceTest {
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
    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    /** Per-keystroke view work: key preview popup and full key-row rebuild (auto-shift changes). */
    @Test fun keystrokeViewCost() {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { _ ->
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown }
                SystemClock.sleep(500)
                val preview = mutableListOf<Double>()
                val rebuild = mutableListOf<Double>()
                repeat(30) { i ->
                    instrumentation.runOnMainSync {
                        val key = find(service.keyboardRoot) { it is android.widget.TextView && it.isShown &&
                            it.text.toString().equals(listOf("q", "w", "e", "r", "t")[i % 5], ignoreCase = true) } as android.widget.TextView
                        var t = System.nanoTime()
                        service.keyPressPreview.show(key, key.text.toString())
                        service.keyPressPreview.hide(key)
                        preview += (System.nanoTime() - t) / 1e6
                        t = System.nanoTime()
                        service.renderKeyRows()
                        rebuild += (System.nanoTime() - t) / 1e6
                    }
                    SystemClock.sleep(30)
                }
                val results = mapOf("previewShowHideMedianMs" to "%.2f".format(median(preview)),
                    "previewShowHideMaxMs" to "%.2f".format(preview.max()),
                    "renderKeyRowsMedianMs" to "%.2f".format(median(rebuild)),
                    "renderKeyRowsMaxMs" to "%.2f".format(rebuild.max()))
                android.util.Log.i("LearningCost", results.toString())
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }

    /** Recording a word into a full phrase memory, including the periodic trim (in memory only). */
    @Test fun phraseMemoryRecordCostAtCapacity() {
        val memory = com.nboard.ime.prediction.PhraseMemory(PHRASE_MEMORY_CAPACITY)
        val random = java.util.Random(3)
        fun word() = (1..(3 + random.nextInt(5))).map { 'a' + random.nextInt(26) }.joinToString("")
        val now = System.currentTimeMillis()
        val times = mutableListOf<Double>()
        repeat(40_000) { i ->
            val context = "${word()} ${word()} ${word()} ${word()} "
            val started = System.nanoTime()
            memory.record(context, word(), "ENGLISH", "app", now + i)
            times += (System.nanoTime() - started) / 1e6
        }
        val tail = times.takeLast(20_000).sorted()
        android.util.Log.i("LearningCost", "phrase entries=${memory.snapshot().size} record_p50_ms=${"%.3f".format(tail[tail.size / 2])} " +
            "record_max_ms=${"%.1f".format(tail.last())}")
    }

    @Test fun fullHistoryMainThreadCost() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("learningCost") != null)
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                await { NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() == true }
                val service = NboardImeService.debugInstance!!.get()!!
                await { service.keyboardRoot.isShown }
                var editor: EditText? = null
                scenario.onActivity { editor = find(it.window.decorView) { v -> v is EditText } as EditText }
                val results = linkedMapOf<String, String>()
                instrumentation.runOnMainSync {
                    val random = java.util.Random(7)
                    fun word() = (1..(3 + random.nextInt(6))).map { 'a' + random.nextInt(26) }.joinToString("")
                    while (service.learnedWordFrequency.size < MAX_LEARNED_WORDS) service.learnedWordFrequency[word()] = 1 + random.nextInt(40)
                    while (service.learnedBigramFrequency.size < MAX_LEARNED_BIGRAMS) service.learnedBigramFrequency["${word()}|${word()}"] = 1 + random.nextInt(40)
                    while (service.learnedTrigramFrequency.size < MAX_LEARNED_TRIGRAMS) service.learnedTrigramFrequency["${word()}|${word()}|${word()}"] = 1 + random.nextInt(40)
                    service.learnedWordFrequency.keys.forEach { service.learnedWordLastUsed[it] = System.currentTimeMillis() }
                    service.learningVersion++
                    editor!!.setText("I will see you "); editor!!.setSelection(15)
                }
                SystemClock.sleep(300)
                val save = mutableListOf<Double>()
                val request = mutableListOf<Double>()
                repeat(15) { i ->
                    instrumentation.runOnMainSync {
                        service.learningDirtyUpdates = LEARNING_SAVE_BATCH_SIZE
                        service.learningVersion++ // a save follows newly learned words
                        var t = System.nanoTime()
                        service.savePredictionLearning(force = false)
                        save += (System.nanoTime() - t) / 1e6
                        service.requestedPredictionKey = null
                        t = System.nanoTime()
                        service.requestPredictions("I will see you t" + "o".repeat(i % 3))
                        request += (System.nanoTime() - t) / 1e6
                    }
                    SystemClock.sleep(80)
                }
                results["periodicSaveMedianMs"] = "%.2f".format(median(save))
                results["periodicSaveMaxMs"] = "%.2f".format(save.max())
                results["perKeystrokeRequestMedianMs"] = "%.2f".format(median(request))
                results["perKeystrokeRequestMaxMs"] = "%.2f".format(request.max())
                android.util.Log.i("LearningCost", results.toString())
                println("LearningCost $results")
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }
}
