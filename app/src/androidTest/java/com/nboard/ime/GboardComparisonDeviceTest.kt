package com.nboard.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Drives the installed Gboard with real key taps, and Nboard through its key handler, with one
 * protocol each for phrase learning and next-word prediction, so Nboard can be tuned against
 * Gboard's observed behaviour. Run explicitly: the Gboard runs switch the active keyboard to
 * Gboard for their duration and add what they type to Gboard's own history.
 *
 *   adb shell ime enable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
 *   adb shell am instrument -w -e gboard 1 -e class com.nboard.ime.GboardComparisonDeviceTest ...
 */
class GboardComparisonDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val automation by lazy {
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
    }
    private fun shell(command: String) = automation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText().trim() } }

    private fun imeRoot(): AccessibilityNodeInfo? =
        automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            out += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return out
    }

    private fun label(node: AccessibilityNodeInfo) = (node.text ?: node.contentDescription)?.toString().orEmpty()
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    @Test fun dumpKeyboardTree() {
        assumeTrue(arguments.getString("gboard") != null && arguments.getString("dump") != null)
        withGboard { _ ->
            nodes(imeRoot()).forEach { node ->
                Log.i("GboardProbe", "${node.className} id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} ${bounds(node).toShortString()}")
            }
        }
    }

    private fun withGboard(block: (EditText) -> Unit) {
        val previous = shell("settings get secure default_input_method")
        shell("ime set $GBOARD")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                var editor: EditText? = null
                scenario.onActivity { activity -> editor = findEditor(activity.window.decorView) }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (keyCenters().size < 26 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
                check(keyCenters().size >= 26) { "Gboard keys not found" }
                block(editor!!)
            }
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }

    /** Letter and space keys by label, from Gboard's accessibility tree. */
    private fun keyCenters(): Map<Char, Pair<Float, Float>> {
        val keys = HashMap<Char, Pair<Float, Float>>()
        nodes(imeRoot()).forEach { node ->
            val name = label(node).lowercase()
            val char = when {
                name.length == 1 && name[0] in 'a'..'z' -> name[0]
                name == "space" -> ' '
                else -> null
            } ?: return@forEach
            val rect = bounds(node)
            if (rect.width() > 0 && char !in keys) keys[char] = rect.exactCenterX() to rect.exactCenterY()
        }
        return keys
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long) = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        automation.injectInputEvent(event(MotionEvent.ACTION_DOWN, down), true)
        automation.injectInputEvent(event(MotionEvent.ACTION_UP, down + 40), true)
    }

    private fun typeText(text: String, keys: Map<Char, Pair<Float, Float>>) {
        text.lowercase().forEach { char ->
            val (x, y) = keys[char] ?: error("No key for '$char'")
            tap(x, y)
            SystemClock.sleep(KEY_GAP_MS)
        }
    }

    /** The visible suggestion strip: labels in the header above the letter rows, minus its tool buttons. */
    private fun suggestions(): List<String> {
        val all = nodes(imeRoot())
        val keyTop = all.filter { label(it).length == 1 && label(it)[0].isLetter() }.minOf { bounds(it).top }
        return all.filter { node ->
            val text = label(node)
            val rect = bounds(node)
            text.isNotBlank() && rect.width() > 0 && rect.bottom <= keyTop && node.isVisibleToUser &&
                text !in HEADER_TOOLS && !text.startsWith("Voice typing") && text.length <= 30
        }.sortedBy { bounds(it).left }.map { label(it).trim('"', '“', '”').lowercase() }.distinct()
    }

    private fun setText(editor: EditText, text: String) {
        instrumentation.runOnMainSync { editor.setText(text); editor.setSelection(text.length) }
        SystemClock.sleep(400)
    }

    /**
     * For each fresh phrase: probe the prompt, type the whole phrase once, repeat. Records after how
     * many exposures the expected word reaches the strip, and whether a shorter or different lead-in
     * recalls it (how much context Gboard keys learned phrases on).
     */
    @Test fun phraseLearningCurve() {
        assumeTrue(arguments.getString("gboard") != null)
        val rounds = arguments.getString("rounds")?.toIntOrNull() ?: 4
        val out = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "gboard-phrase-curve.tsv")
        withGboard { editor ->
            val keys = keyCenters()
            runCurve(out, rounds,
                probe = { prompt -> setText(editor, ""); typeText(prompt, keys); SystemClock.sleep(SETTLE_MS); suggestions() },
                expose = { text -> setText(editor, ""); typeText(text, keys); SystemClock.sleep(300) })
            setText(editor, "")
        }
    }

    /**
     * The same protocol through Nboard's own key handling. Existing learned data stays in use, as
     * Gboard's does, and is restored afterwards so the practice phrases leave no trace.
     */
    @Test fun nboardPhraseLearningCurve() {
        assumeTrue(arguments.getString("nboard") != null)
        val rounds = arguments.getString("rounds")?.toIntOrNull() ?: 4
        val out = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "nboard-phrase-curve.tsv")
        withNboard { service, editor, typeKeys ->
            fun clearField() {
                setText(editor, "")
                val until = SystemClock.uptimeMillis() + 5_000
                while (service.editorSelectionStart != 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
            }
            runCurve(out, rounds,
                probe = { prompt -> clearField(); typeKeys(prompt); SystemClock.sleep(SETTLE_MS); visibleRow(service) },
                expose = { text -> clearField(); typeKeys(text); SystemClock.sleep(300) })
        }
    }

    /**
     * Held-out next-word cases through either keyboard, one protocol: the context up to its last word
     * is placed in the field, then the last word and Space are typed with keys. Cases whose last word
     * is not plain letters are skipped. Push `nextword-cases.tsv` (context, expected) to the app's
     * external files folder; `-e keyboard gboard|nboard -e limit N`.
     */
    @Test fun nextWordComparison() {
        val keyboard = arguments.getString("keyboard") ?: return assumeTrue(false)
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val limit = arguments.getString("limit")?.toIntOrNull() ?: 150
        val cases = File(folder, "nextword-cases.tsv").readLines().map { it.split('\t') }
            .map { listOf(it[0].trimEnd()) + it.drop(1) }
            .filter { it.size >= 2 && it[0].substringAfterLast(' ').let { w -> w.isNotEmpty() && w.all { c -> c in 'a'..'z' || c in 'A'..'Z' } } }
            .take(limit)
        val out = File(folder, "nextword-$keyboard.tsv")
        fun run(place: (String) -> Unit, typeKeys: (String) -> Unit, row: () -> List<String>) = out.printWriter().use { writer ->
            for ((context, expected) in cases) {
                val lead = context.substringBeforeLast(' ', "").let { if (it.isEmpty()) it else "$it " }
                place(lead)
                typeKeys(context.substringAfterLast(' ') + " ")
                SystemClock.sleep(SETTLE_MS)
                val shown = row()
                writer.println(listOf(context, expected, shown.joinToString("|"), expected.lowercase() in shown).joinToString("\t"))
                writer.flush()
            }
        }
        when (keyboard) {
            "gboard" -> withGboard { editor ->
                val keys = keyCenters()
                run({ setText(editor, it) }, { typeText(it, keys) }, ::suggestions)
            }
            "nboard" -> withNboard { service, editor, typeKeys ->
                run({ lead ->
                    setText(editor, lead)
                    val until = SystemClock.uptimeMillis() + 5_000
                    while (service.editorSelectionStart != lead.length && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
                }, typeKeys) { visibleRow(service) }
            }
        }
    }

    private fun visibleRow(service: NboardImeService): List<String> {
        var shown = emptyList<String>()
        instrumentation.runOnMainSync { shown = service.predictionRow.words.map { it.lowercase() } }
        return shown
    }

    /** Nboard as the active keyboard, typing through its key handler; learned data is restored after. */
    private fun withNboard(block: (NboardImeService, EditText, (String) -> Unit) -> Unit) {
        val previous = shell("settings get secure default_input_method")
        shell("ime set com.nboard.ime/.NboardImeService")
        try {
            ActivityScenario.launch(KeyboardTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (NboardImeService.debugInstance?.get()?.isPredictionRowInitialized() != true && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                val service = NboardImeService.debugInstance!!.get()!!
                lateinit var editor: EditText
                scenario.onActivity { activity -> editor = findEditor(activity.window.decorView)!! }
                val phrases = service.phraseMemory.snapshot()
                val words = service.learnedWordFrequency.toMap()
                val bigrams = service.learnedBigramFrequency.toMap()
                val trigrams = service.learnedTrigramFrequency.toMap()
                val recency = service.learnedWordLastUsed.toMap()
                val typeKeys: (String) -> Unit = { text ->
                    text.forEach { char ->
                        instrumentation.runOnMainSync { service.commitKeyText(char.toString()) }
                        SystemClock.sleep(KEY_GAP_MS)
                    }
                }
                try {
                    instrumentation.runOnMainSync { service.latestClipboardDismissed = true }
                    block(service, editor, typeKeys)
                } finally {
                    instrumentation.runOnMainSync {
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
        } finally {
            if (previous.isNotBlank() && previous != "null") shell("ime set $previous")
        }
    }

    private fun findEditor(view: android.view.View): EditText? = when (view) {
        is EditText -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditor(view.getChildAt(it)) }
        else -> null
    }

    /** Probe every phrase, add one exposure of each (rotated order), repeat for [rounds]. */
    private fun runCurve(out: File, rounds: Int, probe: (String) -> List<String>, expose: (String) -> Unit) {
        // A keyboard that has seen a set before cannot measure first exposures again; "-e set b" picks fresh ones.
        val phrases = if (arguments.getString("set") == "b") PHRASES_B else PHRASES
        out.printWriter().use { writer ->
            writer.println("phrase\texposures\tprobe\tsuggestions\thit")
            for (exposure in 0..rounds) {
                for ((context, expected, variants) in phrases) {
                    val probes = listOf("full" to "$context ") + if (exposure == 0) emptyList() else variants
                    for ((kind, prompt) in probes) {
                        val shown = probe(prompt)
                        writer.println(listOf(expected, exposure, kind, shown.joinToString("|"), expected in shown).joinToString("\t"))
                        writer.flush()
                        Log.i("PhraseCurve", "exposures=$exposure $kind '$prompt' -> $shown")
                    }
                }
                if (exposure == rounds) break
                val order = phrases.indices.map { (it + exposure) % phrases.size }
                for (index in order) {
                    val (context, expected) = phrases[index]
                    expose("$context $expected ")
                }
            }
        }
    }

    private data class Phrase(val context: String, val expected: String, val variants: List<Pair<String, String>>)

    private companion object {
        const val GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        const val KEY_GAP_MS = 70L
        val HEADER_TOOLS = setOf("Open features menu", "Settings", "Translate", "Clipboard", "Emoji", "Stickers", "GIF", "More options")
        const val SETTLE_MS = 700L
        /** Invented, so neither keyboard knows them beforehand. Variants test shorter and changed lead-ins. */
        val PHRASES = listOf(
            Phrase("the walnut ledger stays under the", "turnstile", listOf(
                "short2" to "under the ", "short3" to "stays under the ", "other" to "my notebook stays under the ")),
            Phrase("we always feed the parrot near the", "greenhouse", listOf(
                "short2" to "near the ", "short3" to "parrot near the ", "other" to "they feed the parrot near the ")),
            Phrase("send the copper invoice to the", "harbormaster", listOf(
                "short2" to "to the ", "short3" to "invoice to the ", "other" to "please send the copper invoice to the ")),
            Phrase("my cousin parks the van beside the", "sawmill", listOf(
                "short2" to "beside the ", "short3" to "van beside the ", "other" to "her cousin parks the van beside the ")),
            Phrase("leave the folded maps inside the", "lighthouse", listOf(
                "short2" to "inside the ", "short3" to "maps inside the ", "other" to "keep the folded maps inside the ")),
            Phrase("the night shift starts after the", "foghorn", listOf(
                "short2" to "after the ", "short3" to "starts after the ", "other" to "our night shift starts after the "))
        )
        val PHRASES_B = listOf(
            Phrase("grandpa keeps the brass compass behind the", "woodstove", listOf(
                "short2" to "behind the ", "short3" to "compass behind the ", "other" to "she keeps the brass compass behind the ")),
            Phrase("we tie the canoe below the", "boathouse", listOf(
                "short2" to "below the ", "short3" to "canoe below the ", "other" to "they tie the canoe below the ")),
            Phrase("the choir rehearses inside the", "chapel", listOf(
                "short2" to "inside the ", "short3" to "rehearses inside the ", "other" to "our choir rehearses inside the ")),
            Phrase("put the spare battery under the", "doormat", listOf(
                "short2" to "under the ", "short3" to "battery under the ", "other" to "hide the spare battery under the ")),
            Phrase("my aunt waters the ferns beside the", "aquarium", listOf(
                "short2" to "beside the ", "short3" to "ferns beside the ", "other" to "his aunt waters the ferns beside the ")),
            Phrase("the courier waits outside the", "bakery", listOf(
                "short2" to "outside the ", "short3" to "waits outside the ", "other" to "a courier waits outside the "))
        )
    }
}
