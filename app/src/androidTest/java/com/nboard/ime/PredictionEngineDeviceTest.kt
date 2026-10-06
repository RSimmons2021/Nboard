package com.nboard.ime

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nboard.ime.prediction.LocalPredictionEngine
import com.nboard.ime.prediction.NeuralPredictor
import com.nboard.ime.prediction.PredictionRanker
import com.nboard.ime.prediction.PredictionRequest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic public fixtures only: no field contents or personal dictionaries. */
class PredictionEngineDeviceTest {
    @Test fun typoCompletionsAndRepeatedPersonalNamesRemainUseful() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        LocalPredictionEngine(context).use { engine ->
            for ((sentence,prefix,expected) in listOf(Triple("please say ","helo","hello"),
                Triple("where is my ","phne","phone"),Triple("please open ","nbo","nboard"))) {
                val request=PredictionRequest(sentence,prefix,"ENGLISH",words=mapOf("nboard" to 12),lastUsed=mapOf("nboard" to System.currentTimeMillis()))
                val result=PredictionRanker.stableTop(engine.refine(request,engine.candidates(request)),emptyList())
                assertTrue("$prefix should suggest $expected; got $result",expected in result)
            }
        }
    }
    @Test fun benchmarkCompleteWordSuggestionsAgainstExistingPredictor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val old = BigramPredictor(context, BigramPredictor.PredictionMode.ENGLISH_ONLY)
        old.preload()
        val cases = instrumentation.context.assets.open("prediction-cases.tsv").bufferedReader().readLines()
        val timings = mutableListOf<Double>()
        var oldHits = 0
        var localHits = 0
        var hybridHits = 0
        val report = StringBuilder("context\tprefix\texpected\told\tlocal\thybrid\tms\n")
        LocalPredictionEngine(context).use { engine ->
            cases.forEachIndexed { index, line ->
                val parts = line.split('\t')
                val request = PredictionRequest(parts[0], parts[1], "ENGLISH")
                val previous = Regex("[\\p{L}']+").findAll(request.context).lastOrNull()?.value
                val baseline = old.predictWords(request.prefix, previous)
                val started = System.nanoTime()
                val candidates = engine.candidates(request)
                val local = PredictionRanker.stableTop(candidates, emptyList())
                val refined = engine.refine(request, candidates)
                val hybrid = PredictionRanker.stableTop(refined, emptyList())
                val elapsed = (System.nanoTime()-started)/1_000_000.0
                if (index > 0) timings += elapsed
                if (parts[2] in baseline) oldHits++
                if (parts[2] in local) localHits++
                if (parts[2] in hybrid) hybridHits++
                report.append(listOf(parts[0],parts[1],parts[2],baseline.joinToString(),local.joinToString(),hybrid.joinToString(),elapsed.toString()).joinToString("\t")).append('\n')
                assertTrue(hybrid.all { it.matches(Regex("[\\p{L}'-]+")) })
                assertTrue(hybrid.size <= 3)
                assertTrue("Neural refinement must supply context scores", refined != candidates)
            }
        }
        timings.sort()
        val summary = "synthetic_cases=${cases.size} old_top3=$oldHits local_top3=$localHits hybrid_top3=$hybridHits warm_p50_ms=${timings[timings.size/2]} warm_p95_ms=${timings[(timings.size*.95).toInt().coerceAtMost(timings.lastIndex)]}"
        Log.i("NboardBenchmark", summary)
        java.io.File(context.filesDir,"prediction-benchmark.tsv").writeText("$summary\n$report")
        assertTrue(hybridHits > 0)
    }
    @Test fun nextWordDiscoveryBenchmarkUsesOnlyEmptyPrefixes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cases = instrumentation.context.assets.open("next-word-cases.tsv").bufferedReader().readLines()
        val timings = mutableListOf<Double>()
        var baselineHits = 0; var upgradedHits = 0; var baselineCoverage = 0; var upgradedCoverage = 0
        val report = StringBuilder("context\texpected\tbaseline\tupgraded\tms\n")
        // Separate model instances give both searches the same context-cache conditions.
        val baselines = LocalPredictionEngine(context).use { engine -> cases.map { line ->
            val sentence = line.substringBefore('\t')
            val request = PredictionRequest(sentence, "", "ENGLISH")
            engine.refine(request, engine.candidates(request), discoverWords = false)
        } }
        LocalPredictionEngine(context).use { engine ->
            cases.forEachIndexed { index, line ->
                val (sentence, expected) = line.split('\t')
                val request = PredictionRequest(sentence, "", "ENGLISH")
                val baseline = baselines[index]
                val started = System.nanoTime()
                val local = engine.candidates(request)
                val upgraded = engine.refine(request, local)
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                if (index > 0) timings += elapsed
                val oldTop = PredictionRanker.stableTop(baseline, emptyList())
                val newTop = PredictionRanker.stableTop(upgraded, emptyList())
                if (expected in oldTop) baselineHits++
                if (expected in newTop) upgradedHits++
                if (baseline.any { it.word == expected }) baselineCoverage++
                if (upgraded.any { it.word == expected }) upgradedCoverage++
                assertTrue(upgraded.all { it.score.isFinite() && it.word.matches(Regex("[\\p{L}']+")) })
                report.append(listOf(sentence, expected, oldTop.joinToString(), newTop.joinToString(), elapsed).joinToString("\t")).append('\n')
            }
        }
        timings.sort()
        val summary = "next_word_cases=${cases.size} baseline_top3=$baselineHits upgraded_top3=$upgradedHits baseline_coverage=$baselineCoverage upgraded_coverage=$upgradedCoverage warm_p50_ms=${timings[timings.size/2]} warm_p95_ms=${timings[(timings.size*.95).toInt().coerceAtMost(timings.lastIndex)]}"
        java.io.File(context.filesDir, "next-word-benchmark.tsv").writeText("$summary\n$report")
        Log.i("NboardBenchmark", summary)
        assertTrue("Discovery must improve candidate coverage", upgradedCoverage > baselineCoverage)
        assertTrue("Next-word accuracy regressed: $summary", upgradedHits >= baselineHits)
    }
    @Test fun cachedSentenceScoresMatchFreshScoresAfterContextChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalPredictionEngine(context).use { engine ->
            val request = PredictionRequest("i need to charge my ", "pho", "ENGLISH")
            val local = engine.candidates(request)
            val fresh = engine.refine(request, local).associate { it.word to it.score }
            val cached = engine.refine(request, local).associate { it.word to it.score }
            assertEquals(fresh.keys, cached.keys)
            fresh.forEach { (word, score) -> assertEquals(score, cached.getValue(word), .0001) }
            val other = PredictionRequest("thank you very ", "", "ENGLISH")
            engine.refine(other, engine.candidates(other))
            val revisited = engine.refine(request, local).associate { it.word to it.score }
            assertEquals(fresh.keys, revisited.keys)
            fresh.forEach { (word, score) -> assertEquals(score, revisited.getValue(word), .001) }
        }
    }
    @Test fun repeatedLongPhraseContinuationsSurviveNeuralRanking() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memory = com.nboard.ime.prediction.PhraseMemory()
        repeat(6) {
            memory.record("please collect it at the ", "station", "ENGLISH", "synthetic.app", System.currentTimeMillis())
            memory.record("please leave it at the ", "office", "ENGLISH", "synthetic.app", System.currentTimeMillis())
        }
        LocalPredictionEngine(context).use { engine ->
            val request = PredictionRequest("please collect it at the ", "", "ENGLISH", words = mapOf("station" to 6, "office" to 6),
                phrases = memory.suggestions("please collect it at the ", "ENGLISH", "synthetic.app", System.currentTimeMillis()))
            val local = engine.candidates(request)
            assertTrue(local.any { it.word == "station" })
            assertEquals("station", PredictionRanker.stableTop(engine.refine(request, local), emptyList()).first())
        }
    }
}
