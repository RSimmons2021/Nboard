package com.nboard.ime.prediction

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

internal data class PredictionRequest(
    val context: String,
    val prefix: String,
    val language: String,
    val words: Map<String, Int> = emptyMap(),
    val bigrams: Map<String, Int> = emptyMap(),
    val trigrams: Map<String, Int> = emptyMap(),
    val lastUsed: Map<String, Long> = emptyMap(),
    val rejected: Map<String, Int> = emptyMap(),
    val now: Long = System.currentTimeMillis(),
    val phrases: Map<String, Double> = emptyMap()
)

internal data class PredictionCandidate(val word: String, val score: Double, val correction: Boolean = false,
                                       val dictionaryPrior: Double = 0.0)
internal data class PredictionSignals(val prefix: String, val previous: String, val previousTwo: String,
                                     val hasConfidentPrefix: Boolean = false)

/**
 * How learned history enters a score. Fitted with tools/nextword-eval/fit_personal.py:
 * a context-free bonus for often-typed words made next-word suggestions repeat the same
 * few words ("the", "you", "to") whatever the sentence, and cut top-1 accuracy by a third.
 */
internal data class PersonalWeights(
    /** Times typed, and how recently: only for the user's own vocabulary (rare in the dictionary). */
    val ownWord: Double, val recency: Double,
    /** Learned word pairs and triples: personal context. */
    val pair: Double, val triple: Double
)

internal object PredictionRanker {
    /** Next word: history counts only through context, as pairs, triples and phrases. */
    val NEXT_WORD_PERSONAL = PersonalWeights(ownWord = 0.0, recency = 0.0, pair = .7, triple = 1.8)
    /** Completing a typed word: the user's own words (names, slang) also count. */
    val COMPLETION_PERSONAL = PersonalWeights(ownWord = 1.65, recency = 1.8, pair = 2.5, triple = 3.0)
    /** Dictionary frequency below which a learned word counts as the user's own vocabulary. */
    const val OWN_VOCABULARY_MAX_FREQUENCY = 2000L

    private val diacritics = Regex("\\p{M}+")
    fun fold(word: String): String = Normalizer.normalize(word.lowercase(Locale.US), Normalizer.Form.NFD)
        .replace(diacritics, "").replace('’', '\'')

    /** Distance to a word's prefix, including a single adjacent transposition. */
    fun prefixDistance(typed: String, word: String): Int {
        if (word.startsWith(typed)) return 0
        if (typed.length < 3) return 2
        var best = 2
        for (size in (typed.length - 1)..minOf(word.length, typed.length + 1)) {
            if (size < 0) continue
            val target = word.take(size)
            if (typed == target) return 0
            if (oneEditApart(typed, target)) best = 1
        }
        return best
    }

    private fun oneEditApart(a: String, b: String): Boolean {
        var first = 0
        while (first < minOf(a.length, b.length) && a[first] == b[first]) first++
        if (a.length == b.length) {
            if (a.substring((first+1).coerceAtMost(a.length)) == b.substring((first+1).coerceAtMost(b.length))) return true
            return first+1 < a.length && a[first] == b[first+1] && a[first+1] == b[first] &&
                a.substring(first+2) == b.substring(first+2)
        }
        return if (a.length == b.length+1) a.substring((first+1).coerceAtMost(a.length)) == b.substring(first)
        else if (b.length == a.length+1) a.substring(first) == b.substring((first+1).coerceAtMost(b.length))
        else false
    }

    fun signals(request: PredictionRequest): PredictionSignals {
        val previous = PhraseMemory.sentenceTokens(request.context).takeLast(2)
        return PredictionSignals(fold(request.prefix), previous.lastOrNull().orEmpty(), previous.getOrNull(previous.lastIndex - 1).orEmpty())
    }

    fun score(word: String, frequency: Long, contextFrequency: Long, request: PredictionRequest,
              signals: PredictionSignals = signals(request), foldedWord: String = fold(word)): PredictionCandidate {
        val prefix = signals.prefix
        val distance = prefixDistance(prefix, foldedWord)
        val one = signals.previous
        val two = signals.previousTwo
        val personal = if (prefix.isEmpty()) NEXT_WORD_PERSONAL else COMPLETION_PERSONAL
        val ownWord = frequency < OWN_VOCABULARY_MAX_FREQUENCY
        val count = if (ownWord) request.words[word] ?: 0 else 0
        val ageDays = ((request.now - (request.lastUsed[word] ?: 0L)).coerceAtLeast(0L) / 86_400_000.0)
        val recency = if (ownWord && request.lastUsed.containsKey(word)) personal.recency / (1.0 + ageDays / 14.0) else 0.0
        val suppression = request.rejected["${request.prefix.lowercase(Locale.US)}->$word"] ?: 0
        val prior = ln(1.0 + frequency) * .32 + ln(1.0 + contextFrequency) * .48
        val value = prior +
            ln(1.0 + count) * personal.ownWord + recency + (request.phrases[word] ?: 0.0) +
            ln(1.0 + (request.bigrams["$one|$word"] ?: 0)) * personal.pair +
            ln(1.0 + (request.trigrams["$two|$one|$word"] ?: 0)) * personal.triple -
            distance * (if (signals.hasConfidentPrefix) 12.0 else 5.0) - suppression * 3.5 -
            (if (prefix.isEmpty()) 0.0 else (word.length - prefix.length).coerceAtLeast(0) * .025)
        return PredictionCandidate(word, value, distance > 0, prior)
    }

    /** Keep near-ties steady while allowing a meaningfully better candidate to win. */
    fun stableTop(candidates: List<PredictionCandidate>, previous: List<String>, limit: Int = 3): List<String> {
        val remaining = candidates.distinctBy { it.word }.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<String>()
        while (remaining.isNotEmpty() && result.size < limit) {
            val best = remaining.first()
            val old = previous.getOrNull(result.size)?.let { word -> remaining.find { it.word == word } }
            val chosen = old?.takeIf { best.score - it.score <= .22 } ?: best
            result += chosen.word
            remaining.remove(chosen)
        }
        return result
    }
}
