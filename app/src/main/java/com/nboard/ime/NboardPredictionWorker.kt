package com.nboard.ime

import android.util.Log
import androidx.core.view.isVisible
import com.nboard.ime.prediction.LocalPredictionEngine
import com.nboard.ime.prediction.PredictionRequest
import com.nboard.ime.prediction.PredictionRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

internal data class PredictionWork(val revision: Long, val key: String, val before: String,
                                   val request: PredictionRequest, val fragment: String, val uppercase: Boolean)

internal fun NboardImeService.startPredictionWorker() {
    serviceScope.launch(Dispatchers.Default) {
        val engine = LocalPredictionEngine(applicationContext)
        val neuralRequests = Channel<Pair<PredictionWork, List<com.nboard.ime.prediction.PredictionCandidate>>>(Channel.CONFLATED)
        try {
            kotlinx.coroutines.coroutineScope {
                launch {
                    for (work in predictionRequests) {
                        if (work.revision != predictionRevision) continue
                        try {
                            val started = System.nanoTime()
                            val local = engine.candidates(work.request)
                            withContext(Dispatchers.Main) { publishPredictions(work, local) }
                            neuralRequests.trySend(work to local)
                            if (BuildConfig.DEBUG) Log.d("NboardPrediction", "dictionary_ms=${(System.nanoTime()-started)/1_000_000}")
                        } catch (e: Exception) {
                            if (!isActive) throw e
                            Log.e("NboardPrediction", "Dictionary prediction failed", e)
                        }
                    }
                }
                // One neural worker, with a conflated mailbox. Dictionary feedback
                // stays independent of model startup and inference latency.
                launch {
                    for ((work, local) in neuralRequests) {
                        kotlinx.coroutines.delay(40)
                        if (work.revision != predictionRevision) continue
                        try {
                            val started = System.nanoTime()
                            val refined = engine.refine(work.request, local)
                            withContext(Dispatchers.Main) { publishPredictions(work, refined) }
                            if (BuildConfig.DEBUG) Log.d("NboardPrediction", "neural_ms=${(System.nanoTime()-started)/1_000_000}")
                        } catch (e: Exception) {
                            if (!isActive) throw e
                            Log.e("NboardPrediction", "Neural refinement failed; retaining dictionary suggestions", e)
                        }
                    }
                }
            }
        } finally { neuralRequests.close(); engine.close() }
    }
}

private fun NboardImeService.publishPredictions(work: PredictionWork, candidates: List<com.nboard.ime.prediction.PredictionCandidate>) {
    if (work.revision != predictionRevision || !shouldShowPredictionRow()) return
    if (currentInputConnection?.getTextBeforeCursor(PREDICTION_CONTEXT_WINDOW, 0)?.toString() != work.before) return
    val ranked = PredictionRanker.stableTop(candidates, lastRankedPredictions)
    lastRankedPredictions = ranked
    val words = ranked.map { word -> if (work.fragment.isNotBlank()) applyWordCase(word, work.fragment)
        else if (work.uppercase) word.replaceFirstChar { it.uppercase() } else word }
    setPredictionWords(words)
    predictionRenderCache = PredictionRenderCache(work.key, words)
    predictionRow.isVisible = words.isNotEmpty()
    toolbarActionsRow.isVisible = words.isEmpty()
}

internal fun NboardImeService.invalidatePredictions() {
    predictionRevision++
    requestedPredictionKey = null
    lastRankedPredictions = emptyList()
    predictionRenderCache = null
}

/** One editor read per UI refresh, shared by shift and prediction. Never retained across edits. */
internal fun NboardImeService.readKeyboardContext(): String {
    if (!shouldShowPredictionRow() &&
        !(autoCapitalizeAfterPunctuationEnabled && smartTypingBehavior.shouldAutoSpaceAndCapitalize())) return ""
    return currentInputConnection?.getTextBeforeCursor(PREDICTION_CONTEXT_WINDOW, 0)?.toString().orEmpty()
}

internal fun NboardImeService.requestPredictions(beforeCursor: String? = null) {
    if (!shouldShowPredictionRow()) {
        if (requestedPredictionKey != null) invalidatePredictions()
        setPredictionWords(emptyList())
        return
    }
    val before = beforeCursor ?: currentInputConnection?.getTextBeforeCursor(PREDICTION_CONTEXT_WINDOW, 0)?.toString() ?: return
    val key = predictionRenderContextKey(before)
    if (requestedPredictionKey == key) return
    requestedPredictionKey = key
    val fragment = extractCurrentWordFragment(before)
    val context = before.dropLast(fragment.length)
    // Continue showing only suggestions compatible with the new fragment until
    // the worker returns. Never leave a stale unrelated word tappable.
    setPredictionWords(predictionRow.words.filter { it.startsWith(fragment, true) && !it.equals(fragment, true) })
    val work = PredictionWork(++predictionRevision, key, before, PredictionRequest(
        context, fragment, keyboardLanguageMode.name, learnedWordFrequency.toMap(),
        learnedBigramFrequency.toMap(), learnedTrigramFrequency.toMap(), learnedWordLastUsed.toMap(), rejectedCorrections.toMap()
    ), fragment, isAutoShiftEnabled)
    predictionRequests.trySend(work)
}
