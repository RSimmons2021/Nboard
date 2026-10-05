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
}
