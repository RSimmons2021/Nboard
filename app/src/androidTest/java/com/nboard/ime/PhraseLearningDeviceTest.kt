package com.nboard.ime

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nboard.ime.prediction.PhraseMemory
import com.nboard.ime.prediction.PhraseMemoryStorage
import org.junit.Assert.*
import org.junit.Test

class PhraseLearningDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
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
        error("Synthetic editor did not become ready")
    }
    @Test fun learningRequiresCommittedWordsHonorsPrivacyAndRetractsCorrections() {
        ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            await { NboardImeService.debugInstance?.get()?.currentInputEditorInfo?.packageName == "com.nboard.ime" }
            val service = NboardImeService.debugInstance!!.get()!!
            lateinit var edit: EditText
            scenario.onActivity { edit = editor(it.window.decorView)!! }
            val phrases = service.phraseMemory.snapshot()
            val words = service.learnedWordFrequency.toMap()
            val bigrams = service.learnedBigramFrequency.toMap()
            val trigrams = service.learnedTrigramFrequency.toMap()
            val recency = service.learnedWordLastUsed.toMap()
            val rejected = service.rejectedCorrections.toMap()
            val behavior = service.smartTypingBehavior
            fun setText(text: String) {
                scenario.onActivity { edit.setText(text); edit.setSelection(text.length) }
                await { service.editorSelectionStart == text.length }
            }
            try {
                instrumentation.runOnMainSync {
                    service.phraseMemory.clear(); service.phraseWordTouched = false
                    service.smartTypingBehavior = SmartTypingBehavior(InputType.TYPE_CLASS_TEXT)
                    service.isAiMode = false; service.isEmojiMode = false; service.isClipboardOpen = false
                }
                setText("meet me at lumen")
                instrumentation.runOnMainSync { service.commitKeyText(" "); assertTrue(service.phraseMemory.snapshot().isEmpty()) }
                repeat(2) {
                    setText("meet me at ")
                    instrumentation.runOnMainSync { service.commitWordPrediction("lumen") }
                }
                instrumentation.runOnMainSync {
                    assertTrue("lumen" in service.phraseMemory.suggestions("meet me at ", service.keyboardLanguageMode.name,
                        service.currentInputEditorInfo?.packageName.orEmpty(), System.currentTimeMillis()))
                    service.deleteOneCharacter() // Space; word is still confirmed.
                    service.deleteOneCharacter() // Editing word retracts its phrase observation.
                    assertTrue(service.phraseMemory.suggestions("meet me at ", service.keyboardLanguageMode.name,
                        service.currentInputEditorInfo?.packageName.orEmpty(), System.currentTimeMillis()).isEmpty())
                }
                repeat(2) {
                    setText("i would like you to please meet me at ")
                    instrumentation.runOnMainSync { service.commitWordPrediction("lumen") }
                }
                instrumentation.runOnMainSync {
                    assertNotNull(service.lastPhraseReceipt)
                    // Keep the editor buffer: EditText.setText restarts the IME and
                    // deliberately invalidates pending learning/undo state.
                    val connection = service.currentInputConnection!!
                    connection.beginBatchEdit()
                    try { connection.deleteSurroundingText(1, 0); connection.commitText(". ", 1) }
                    finally { connection.endBatchEdit() }
                    assertNotNull(service.lastPhraseReceipt)
                    service.pendingAutoCorrection = AutoCorrectionUndo("lumenn", "lumen", ". ")
                    assertTrue(service.tryRevertLastAutoCorrection())
                    assertTrue(service.phraseMemory.snapshot().filter { it.context == "to|please|meet|me|at" && it.word == "lumen" }.all { it.count == 1 })
                }
                for (privateBehavior in listOf(
                    SmartTypingBehavior(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                    SmartTypingBehavior(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))) {
                    setText("meet me at ")
                    instrumentation.runOnMainSync {
                        val size = service.phraseMemory.snapshot().size
                        service.smartTypingBehavior = privateBehavior
                        service.commitWordPrediction("privateword")
                        assertEquals(size, service.phraseMemory.snapshot().size)
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    service.rejectedCorrections.clear(); service.rejectedCorrections.putAll(rejected)
                    service.saveRejectedCorrections()
                    service.smartTypingBehavior = behavior
                    service.phraseMemory.restore(phrases); service.phraseMemoryDirty = true
                    service.lastPhraseReceipt = null; service.phraseWordTouched = false
                    service.learnedWordFrequency.clear(); service.learnedWordFrequency.putAll(words)
                    service.learnedBigramFrequency.clear(); service.learnedBigramFrequency.putAll(bigrams)
                    service.learnedTrigramFrequency.clear(); service.learnedTrigramFrequency.putAll(trigrams)
                    service.learnedWordLastUsed.clear(); service.learnedWordLastUsed.putAll(recency)
                    service.savePredictionLearning(force = true)
                }
            }
        }
    }
    @Test fun backgroundPersistenceSurvivesReloadAndClearOrdersAfterPendingWrites() {
        val context = instrumentation.targetContext
        val original = PhraseMemoryStorage.load(context)
        try {
            val memory = PhraseMemory()
            repeat(2) { memory.record("meet me at ", "lumen", "ENGLISH", "synthetic.app", System.currentTimeMillis()) }
            PhraseMemoryStorage.save(context, memory.snapshot())
            val reloaded = PhraseMemory()
            reloaded.restore(PhraseMemoryStorage.load(context))
            assertTrue("lumen" in reloaded.suggestions("meet me at ", "ENGLISH", "synthetic.app", System.currentTimeMillis()))
            repeat(3) { PhraseMemoryStorage.save(context, memory.snapshot()) }
            PhraseMemoryStorage.clear(context)
            assertTrue(PhraseMemoryStorage.load(context).isEmpty())
        } finally { PhraseMemoryStorage.save(context, original); PhraseMemoryStorage.load(context) }
    }
}
