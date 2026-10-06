package com.nboard.ime

import kotlin.math.ln

/**
 * Noisy-channel spelling correction: choose the word w maximising
 *   log P(w | previous word) - log P(typed | w)
 * where the typing cost comes from key geometry ([TypoModel]). Leaving the typed
 * word alone is itself a candidate with a fixed prior, so unknown names and slang
 * are only replaced when a dictionary word explains the keystrokes much better.
 */
internal class NoisyChannelCorrector(
    private val trie: DictionaryTrie,
    private val model: TypoModel = TypoModel(),
    private val params: Params = Params()
) {
    data class Params(
        /** Weight of the typing-slip cost against log word frequency. */
        val costWeight: Double = 2.4,
        /** Weight of the previous-word evidence. */
        val contextWeight: Double = 1.0,
        /** Log prior for "the typed word was intended" (an out-of-dictionary word). */
        val keepTypedPrior: Double = 7.5,
        /** Auto-replace only when the best word beats the runner-up by this much; otherwise only suggest. */
        val margin: Double = 0.0,
        /** Fraction of the per-length search budget used. */
        val budgetScale: Double = 1.0,
    )

    /** A dictionary word near the typed one, before scoring. */
    data class Nearby(val word: String, val frequency: Int, val cost: Double)

    /** Budget grows with length: short words have many neighbours, long ones absorb more slips. */
    private fun maxCost(length: Int): Double = params.budgetScale * when {
        length <= 2 -> 0.9
        length == 3 -> 1.3
        length == 4 -> 1.7
        length <= 6 -> 2.2
        else -> 2.6
    }

    /** Candidates found with a larger budget, restricted to this corrector's budget. */
    fun withinBudget(typed: String, candidates: List<Nearby>): List<Nearby> {
        val limit = maxCost(typed.length)
        return candidates.filter { it.cost <= limit }
    }

    fun nearby(typed: String): List<Nearby> {
        val found = ArrayList<Nearby>()
        trie.searchWithinCost(typed, maxCost(typed.length), model) { word, frequency, cost ->
            if (word != typed) found += Nearby(word, frequency, cost)
        }
        return found
    }

    /** [contextProbability] is P(word | previous word), or null when the pair is unknown. */
    fun score(candidate: Nearby, contextProbability: Double?): Double =
        ln(candidate.frequency.toDouble().coerceAtLeast(1.0)) - params.costWeight * candidate.cost +
            (contextProbability?.let { params.contextWeight * ln(1.0 + 200.0 * it) } ?: 0.0)

    /** Best first; [context] returns P(word | previous) or null. */
    fun rank(typed: String, previous: String?, context: ((String, String) -> Double?)?,
             candidates: List<Nearby> = nearby(typed)): List<Pair<Nearby, Double>> =
        candidates.map { it to score(it, if (previous != null && context != null) context(previous, it.word) else null) }
            .sortedByDescending { it.second }

    /** The replacement for [typed], or null to leave it as typed. */
    fun correct(typed: String, previous: String?, context: ((String, String) -> Double?)?,
                candidates: List<Nearby> = nearby(typed)): String? {
        val ranked = rank(typed, previous, context, candidates)
        val (best, score) = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.second ?: Double.NEGATIVE_INFINITY
        return best.word.takeIf { score > params.keepTypedPrior && score - runnerUp >= params.margin }
    }
}
