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

/** Wait this long for neural ranking before showing dictionary suggestions for a typed prefix. */
private const val PREFIX_PUBLISH_DEADLINE_MS = 55L

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
                            withContext(Dispatchers.Main) {
                                if (work.revision != predictionRevision) return@withContext
                                // Merge the fast dictionary and neural updates into one row
                                // change per keystroke; two changes read as flicker. A bounded
                                // deadline keeps the row responsive when the model is slow.
                                pendingBoundaryPrediction?.cancel()
                                pendingBoundaryPrediction = serviceScope.launch {
                                    kotlinx.coroutines.delay(if (work.request.prefix.isBlank()) 80 else PREFIX_PUBLISH_DEADLINE_MS)
                                    publishPredictions(work, local)
                                }
                            }
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
                        // No debounce: the conflated mailbox already skips stale keystrokes,
                        // and the result must arrive before the publish deadline.
                        if (work.revision != predictionRevision) continue
                        try {
                            val started = System.nanoTime()
                            val refined = engine.refine(work.request, local)
                            withContext(Dispatchers.Main) {
                                if (work.revision != predictionRevision) return@withContext
                                pendingBoundaryPrediction?.cancel()
                                pendingBoundaryPrediction = null
                                publishPredictions(work, refined)
                            }
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
    // The user's own capitals ("Jaxon", "iPhone") and fixed English ones ("I'm") come first.
    val english = usesEnglishCasing()
    val words = ranked.map { word -> wordCasing.display(word, work.fragment, work.uppercase && work.fragment.isBlank(), english) }
        .ifEmpty { if (work.fragment.isNotBlank()) listOf(work.fragment) else emptyList() }
    setPredictionWords(words)
    predictionRow.typed = work.fragment
    predictionRow.acceptSuggestions = true
    predictionRenderCache = PredictionRenderCache(work.key, words)
    predictionRow.isVisible = words.isNotEmpty()
    toolbarActionsRow.isVisible = words.isEmpty()
}

internal fun NboardImeService.invalidatePredictions() {
    pendingBoundaryPrediction?.cancel()
    pendingBoundaryPrediction = null
    predictionRevision++
    requestedPredictionKey = null
    lastRankedPredictions = emptyList()
    predictionRenderCache = null
}

/** One editor read per UI refresh, shared by shift and prediction. Never retained across edits. */
internal fun NboardImeService.readKeyboardContext(): String {
    if (!shouldShowPredictionRow() &&
        !(autoCapitalizeAfterPunctuationEnabled && smartTypingBehavior.shouldAutoCapitalize())) return ""
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
    pendingBoundaryPrediction?.cancel()
    pendingBoundaryPrediction = null
    val fragment = extractCurrentWordFragment(before)
    val context = before.dropLast(fragment.length)
    // Keep the current row drawn until the next one is ready (one visual change per
    // keystroke). Its words cannot be inserted while they are out of date.
    predictionRow.acceptSuggestions = false
    val learned = learningSnapshot()
    val work = PredictionWork(++predictionRevision, key, before, PredictionRequest(
        context, fragment, keyboardLanguageMode.name, learned.words,
        learned.bigrams, learned.trigrams, learned.lastUsed, learned.rejected,
        phrases = if (smartTypingBehavior.shouldPersonalize()) phraseMemory.suggestions(context, keyboardLanguageMode.name,
            currentInputEditorInfo?.packageName.orEmpty(), System.currentTimeMillis()) else emptyMap()
    ), fragment, isAutoShiftEnabled)
    predictionRequests.trySend(work)
}
