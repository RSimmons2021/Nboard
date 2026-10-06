package com.nboard.ime

import androidx.test.platform.app.InstrumentationRegistry
import com.nboard.ime.prediction.LocalPredictionEngine
import com.nboard.ime.prediction.PredictionRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the score components the prediction blend combines, for offline weight fitting
 * (tools/nextword-eval/fit_blend.py). Reads files/eval/dev.tsv; writes files/eval/blend-dump.tsv:
 *   expected <TAB> prefix <TAB> word:baseScore:prior:likelihood(or "-") ...
 */
class PredictionBlendDumpDeviceTest {
    @Test fun dumpBlendComponents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "eval/dev.tsv")
        assumeTrue("Copy files/eval/dev.tsv to run", input.isFile)
        val output = StringBuilder()
        LocalPredictionEngine(context).use { engine ->
            engine.refine(PredictionRequest("warm up ", "", "ENGLISH"), emptyList()) // loads the model
            for (line in input.readLines()) {
                val (sentence, expected) = line.split('\t')
                for (prefix in listOf("", expected.take(1))) {
                    val request = PredictionRequest(sentence, prefix, "ENGLISH")
                    val local = engine.candidates(request)
                    val likelihoods = engine.modelLikelihoods(request, local) ?: emptyMap()
                    val base = engine.combine(request, local, likelihoods, LocalPredictionEngine.BlendWeights(0.0, 0.0))
                    output.append(expected).append('\t').append(prefix)
                    for (candidate in base) {
                        output.append('\t').append(candidate.word).append(':').append(candidate.score).append(':')
                            .append(candidate.dictionaryPrior).append(':').append(likelihoods[candidate.word]?.toString() ?: "-")
                    }
                    output.append('\n')
                }
            }
        }
        File(context.filesDir, "eval/blend-dump.tsv").writeText(output.toString())
    }
}
