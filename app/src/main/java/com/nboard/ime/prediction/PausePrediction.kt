package com.nboard.ime.prediction

internal const val PREDICTION_PAUSE_MS = 220L

internal fun isPauseRefinementEligible(request: PredictionRequest): Boolean =
    request.language == "ENGLISH" && request.prefix.isEmpty() &&
        PhraseMemory.sentenceTokens(request.context).size >= 2

/**
 * Keep the positions the user just saw. An idle update can offer one new option in
 * the last slot, provided the second model strongly favors it over that slot's word.
 */
internal fun refineVisiblePredictions(
    visible: List<String>,
    refined: List<PredictionCandidate>,
    phrases: Map<String, Double>,
    margin: Double = 0.6
): List<String> {
    if (visible.size != 3 || visible.last() in phrases) return visible
    val lastScore = refined.firstOrNull { it.word == visible.last() }?.score ?: return visible
    val challenger = refined.asSequence().filter { it.word !in visible }.maxByOrNull { it.score }
        ?: return visible
    return if (challenger.score > lastScore + margin) visible.dropLast(1) + challenger.word else visible
}
