package com.nboard.ime

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nboard.ime.prediction.LocalPredictionEngine
import com.nboard.ime.prediction.PredictionRanker
import com.nboard.ime.prediction.PredictionRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Full prediction pipeline (dictionary, model, ranking) on held-out conversational text.
 * Cases are not bundled; copy them into private storage first (built by tools/nextword-eval/make_cases.py):
 *   adb shell "run-as com.nboard.ime sh -c 'mkdir -p files/eval && cat > files/eval/cases.tsv'" < nextword_test.tsv
 * Debug builds use files/models/override.gguf when present (see LocalPredictionEngine).
 * With files/eval/history.txt (one message per line, none from the cases) the learned stores
 * are built from it, as a long-time user's would be (tools/nextword-eval/fit_personal.py).
 */
class ConversationalPredictionDeviceTest {
    private class History(val words: Map<String, Int>, val bigrams: Map<String, Int>,
                          val trigrams: Map<String, Int>, val lastUsed: Map<String, Long>,
                          val phrases: com.nboard.ime.prediction.PhraseMemory)

    private fun history(file: File): History? {
        if (!file.isFile) return null
        val word = Regex("[a-z]+(?:'[a-z]+)*")
        val words = HashMap<String, Int>(); val bigrams = HashMap<String, Int>(); val trigrams = HashMap<String, Int>()
        val capacity = InstrumentationRegistry.getArguments().getString("phraseCapacity")?.toIntOrNull() ?: 2048
        val phrases = com.nboard.ime.prediction.PhraseMemory(capacity)
        val start = System.currentTimeMillis() - 60L * 86_400_000
        file.readLines().forEachIndexed { index, message ->
            // As typing would: each finished word is recorded with the text before it.
            val time = start + index * (60L * 86_400_000 / 5000)
            Regex("[A-Za-z']+").findAll(message).forEach { match ->
                phrases.record(message.substring(0, match.range.first), match.value, "ENGLISH", "", time)
            }
        }
        for (message in file.readLines()) for (sentence in message.lowercase().split(Regex("[.!?\\n]"))) {
            val tokens = word.findAll(sentence).map { it.value }.toList()
            tokens.forEachIndexed { i, w ->
                if (w.length < 2) return@forEachIndexed
                words.merge(w, 1, Int::plus)
                if (i >= 1) bigrams.merge("${tokens[i - 1]}|$w", 1, Int::plus)
                if (i >= 2) trigrams.merge("${tokens[i - 2]}|${tokens[i - 1]}|$w", 1, Int::plus)
            }
        }
        fun <K> top(map: Map<K, Int>, n: Int) = map.entries.sortedByDescending { it.value }.take(n).associate { it.key to it.value }
        val random = java.util.Random(7)
        val now = System.currentTimeMillis()
        val kept = top(words, MAX_LEARNED_WORDS)
        return History(kept, top(bigrams, MAX_LEARNED_BIGRAMS), top(trigrams, MAX_LEARNED_TRIGRAMS),
            kept.keys.associateWith { now - (random.nextDouble() * 60 * 86_400_000).toLong() }, phrases)
    }

    private fun request(history: History?, context: String, prefix: String) = if (history == null) PredictionRequest(context, prefix, "ENGLISH")
        else PredictionRequest(context, prefix, "ENGLISH", history.words, history.bigrams, history.trigrams, history.lastUsed,
            phrases = history.phrases.suggestions(context, "ENGLISH", "", System.currentTimeMillis()))

    private fun percentile(values: List<Double>, p: Double) = values.sorted()[(values.size * p).toInt().coerceAtMost(values.size - 1)]

    @Test fun conversationalNextWordAccuracyAndLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "eval/cases.tsv")
        assumeTrue("Copy files/eval/cases.tsv to run", file.isFile)
        val limit = InstrumentationRegistry.getArguments().getString("limit")?.toIntOrNull() ?: Int.MAX_VALUE
        val cases = file.readLines().take(limit).map { it.split('\t') }
        val learned = history(File(context.filesDir, "eval/history.txt"))
        var top1 = 0; var top3 = 0; var letterTop3 = 0
        val firsts = mutableMapOf<String, Int>() // variety of the first next-word suggestion
        val bySource = mutableMapOf<String, IntArray>() // source -> cases, top-1, top-3
        var phraseOffered = 0; var phraseHasAnswer = 0
        val boundary = mutableListOf<Double>(); val keystroke = mutableListOf<Double>()
        LocalPredictionEngine(context).use { engine ->
            cases.forEachIndexed { index, (sentence, expected) ->
                // Warm the cache with the context one word earlier, as typing would have.
                val earlier = sentence.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
                if (earlier.isNotBlank()) request(learned, earlier, "").let { engine.refine(it, engine.candidates(it)) }
                val request = request(learned, sentence, "")
                if (request.phrases.isNotEmpty()) phraseOffered++
                if (expected in request.phrases) phraseHasAnswer++
                var started = System.nanoTime()
                val ranked = PredictionRanker.stableTop(engine.refine(request, engine.candidates(request)), emptyList())
                if (index > 0) boundary += (System.nanoTime() - started) / 1e6
                ranked.firstOrNull()?.let { firsts.merge(it, 1, Int::plus) }
                bySource.getOrPut(cases[index].getOrElse(2) { "all" }) { IntArray(3) }.let {
                    it[0]++; if (ranked.firstOrNull() == expected) it[1]++; if (expected in ranked) it[2]++
                }
                if (ranked.firstOrNull() == expected) top1++
                if (expected in ranked) top3++
                val typed = request(learned, sentence, expected.take(1))
                started = System.nanoTime()
                val completions = PredictionRanker.stableTop(engine.refine(typed, engine.candidates(typed)), emptyList())
                if (index > 0) keystroke += (System.nanoTime() - started) / 1e6
                if (expected in completions) letterTop3++
            }
        }
        val n = cases.size.toDouble()
        val summary = "phrases_offered=$phraseOffered phrases_with_answer=$phraseHasAnswer phrase_entries=${learned?.phrases?.snapshot()?.size} " +
            "history=${learned != null} conversational_cases=${cases.size} top1=${"%.1f".format(100 * top1 / n)}% top3=${"%.1f".format(100 * top3 / n)}% " +
            "after_one_letter_top3=${"%.1f".format(100 * letterTop3 / n)}% " +
            "boundary_p50_ms=${"%.0f".format(percentile(boundary, .5))} boundary_p95_ms=${"%.0f".format(percentile(boundary, .95))} " +
            "keystroke_p50_ms=${"%.0f".format(percentile(keystroke, .5))} keystroke_p95_ms=${"%.0f".format(percentile(keystroke, .95))} " +
            bySource.entries.joinToString(" ") { (source, v) -> "$source:top1=${"%.1f".format(100.0 * v[1] / v[0])}%,top3=${"%.1f".format(100.0 * v[2] / v[0])}%" } + " " +
            "distinct_first=${firsts.size} most_common_first=${firsts.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}:${it.value}" }}"
        Log.i("NboardBenchmark", summary)
    }
}
