package com.nboard.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nboard.ime.prediction.LocalPredictionEngine
import com.nboard.ime.prediction.PREDICTION_PAUSE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PausePredictionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(10)
        }
        error("Prediction editor did not reach the expected state")
    }

    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun pausedRowPreservesTheFirstTwoSuggestions() {
        assumeTrue(File(instrumentation.targetContext.filesDir, LocalPredictionEngine.PAUSE_MODEL_PATH).isFile)
        ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
            await { NboardImeService.debugInstance?.get()?.let { it.isPredictionRowInitialized() && it.isInputViewShown } == true }
            val service = NboardImeService.debugInstance!!.get()!!
            val language = service.keyboardLanguageMode
            val enabled = service.wordPredictionEnabled
            try {
                instrumentation.runOnMainSync {
                    service.keyboardLanguageMode = KeyboardLanguageMode.ENGLISH
                    service.wordPredictionEnabled = true
                    service.latestClipboardDismissed = true
                    service.isToolbarOpen = false
                }
                var changed = false
                for (context in listOf("I think we need ", "Can you give me ", "I can't wait to ",
                    "We should grab a ", "I'll send you the ", "I'm running a little ", "I left my keys in ")) {
                    scenario.onActivity {
                        val edit = editor(it.window.decorView)!!
                        edit.setText(context)
                        edit.setSelection(context.length)
                        service.invalidatePredictions()
                        service.requestPredictions(context)
                    }
                    await { service.predictionRow.acceptSuggestions && service.lastRankedPredictions.size == 3 }
                    // Allow the initial neural worker to settle, still inside the idle delay.
                    SystemClock.sleep(115)
                    var first = emptyList<String>()
                    instrumentation.runOnMainSync { first = service.lastRankedPredictions }
                    SystemClock.sleep(PREDICTION_PAUSE_MS + 500)
                    instrumentation.runOnMainSync {
                        val final = service.lastRankedPredictions
                        assertEquals(first.take(2), final.take(2))
                        changed = changed || final != first
                    }
                }
                assertTrue("The pause model should replace at least one last-slot suggestion", changed)
            } finally {
                instrumentation.runOnMainSync {
                    service.keyboardLanguageMode = language
                    service.wordPredictionEnabled = enabled
                }
            }
        }
    }

    @Test fun compareVisibleNextWords() {
        val file = File(instrumentation.targetContext.filesDir, "eval/keyboard-comparison.tsv")
        assumeTrue(file.isFile)
        val cases = file.readLines().drop(1).map { it.split('\t') }
        ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
            await { NboardImeService.debugInstance?.get()?.let { it.isPredictionRowInitialized() && it.isInputViewShown } == true }
            val service = NboardImeService.debugInstance!!.get()!!
            val savedPhrases = service.phraseMemory
            val savedLanguage = service.keyboardLanguageMode
            val savedEnabled = service.wordPredictionEnabled
            val temporaryMemory = com.nboard.ime.prediction.PhraseMemory()
            fun input(text: String) {
                scenario.onActivity {
                    val edit = editor(it.window.decorView)!!
                    edit.text.replace(0, edit.text.length, text)
                    edit.setSelection(text.length)
                    service.keyboardLanguageMode = KeyboardLanguageMode.ENGLISH
                    service.wordPredictionEnabled = true
                    service.isAiMode = false
                    service.isEmojiMode = false
                    service.isClipboardOpen = false
                    service.isNumbersMode = false
                    service.isToolbarOpen = false
                    service.latestClipboardDismissed = true
                }
                await { service.currentInputConnection?.getTextBeforeCursor(120, 0)?.toString() == text }
            }
            fun readSuggestions(index: Int, phase: String) {
                val (_, context, expected) = cases[index]
                input("$context ")
                instrumentation.runOnMainSync {
                    service.invalidatePredictions()
                    service.requestPredictions("$context ")
                }
                SystemClock.sleep(500)
                await { service.predictionRow.acceptSuggestions && service.predictionRow.isShown }
                instrumentation.runOnMainSync {
                    check(service.predictionRow.acceptSuggestions) { "Suggestion request did not finish for case $index" }
                    val words = service.lastRankedPredictions
                    android.util.Log.i("NboardCompare", "phase=$phase index=$index expected=$expected suggestions=${words.joinToString("|")}")
                }
            }
            try {
                // Train the temporary phrase table only. The editor and active IME use
                // normal prediction requests, with no synthetic writes to saved history.
                instrumentation.runOnMainSync { service.phraseMemory = temporaryMemory }
                cases.indices.forEach { readSuggestions(it, "before") }
                val habits = cases.indices.filter { cases[it][0] == "habit" }
                repeat(3) { round ->
                    for (i in habits.indices) {
                        val (_, context, expected) = cases[habits[(i + round * 3) % habits.size]]
                        temporaryMemory.record("$context ", expected, "ENGLISH", "com.nboard.ime", System.currentTimeMillis())
                    }
                }
                input("")
                habits.forEach { readSuggestions(it, "after") }
            } finally {
                instrumentation.runOnMainSync {
                    service.phraseMemory = savedPhrases
                    service.keyboardLanguageMode = savedLanguage
                    service.wordPredictionEnabled = savedEnabled
                    service.invalidatePredictions()
                }
            }
        }
    }

    @Test fun systemSpellCheckerReturnsSuggestionsWhileNboardIsInstalled() {
        val manager = instrumentation.targetContext.getSystemService(TextServicesManager::class.java)
        assumeTrue(manager.isSpellCheckerEnabled)
        val results = LinkedBlockingQueue<List<String>>()
        lateinit var session: SpellCheckerSession
        instrumentation.runOnMainSync {
            val params = SpellCheckerSession.SpellCheckerSessionParams.Builder().setLocale(Locale.US).build()
            session = requireNotNull(manager.newSpellCheckerSession(params, instrumentation.targetContext.mainExecutor,
                object : SpellCheckerSession.SpellCheckerSessionListener {
                    override fun onGetSuggestions(suggestions: Array<out SuggestionsInfo>) = Unit
                    override fun onGetSentenceSuggestions(suggestions: Array<out SentenceSuggestionsInfo>) {
                        results.offer(suggestions.flatMap { sentence ->
                            (0 until sentence.suggestionsCount).flatMap { index ->
                                val words = sentence.getSuggestionsInfoAt(index)
                                (0 until words.suggestionsCount).map { words.getSuggestionAt(it) }
                            }
                        })
                    }
                }))
            session.getSentenceSuggestions(arrayOf(TextInfo("I did not recieve the letter")), 5)
        }
        try {
            val suggestions = results.poll(8, TimeUnit.SECONDS).orEmpty()
            assertTrue("The current spell checker did not offer receive", "receive" in suggestions)
        } finally {
            instrumentation.runOnMainSync { session.close() }
        }
    }
}
