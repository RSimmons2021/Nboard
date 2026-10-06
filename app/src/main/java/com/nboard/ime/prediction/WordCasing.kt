package com.nboard.ime.prediction

import org.json.JSONArray
import java.util.Locale

/**
 * How the user writes capitals in a word ("jaxon" -> "Jaxon", "iphone" -> "iPhone", "nasa" -> "NASA").
 * Learned from words finished mid-sentence: a capital that starts a sentence says nothing about
 * the word. Writing the word in lowercase mid-sentence weakens the memory. Main-thread owned.
 */
internal class WordCasing(private val capacity: Int = 2600) {
    data class Entry(val surface: String, val evidence: Int)

    private val learned = LinkedHashMap<String, Entry>()

    private fun key(word: String) = word.lowercase(Locale.US).replace('’', '\'')

    /** Records a finished word. Returns true when the stored casing changed. */
    fun observe(surface: String, sentenceInitial: Boolean): Boolean {
        val word = surface.replace('’', '\'')
        val lower = key(word)
        if (lower.length < 2 || lower in ENGLISH_FIXED || !lower.any { it.isLetter() }) return false
        val hasCapitals = word != lower
        // "Hello" at a sentence start is ordinary capitalisation; "NASA" or "iPhone" still teach.
        if (sentenceInitial && word == lower.replaceFirstChar { it.uppercase(Locale.US) }) return false
        if (sentenceInitial && !hasCapitals) return false
        val current = learned[lower]
        when {
            hasCapitals && current?.surface == word -> learned[lower] = current.copy(evidence = (current.evidence + 1).coerceAtMost(MAX_EVIDENCE))
            hasCapitals && current != null && current.evidence > 1 -> learned[lower] = current.copy(evidence = current.evidence - 1)
            hasCapitals -> { learned.remove(lower); learned[lower] = Entry(word, 1) }
            current == null -> return false
            current.evidence <= 1 -> learned.remove(lower)
            else -> learned[lower] = current.copy(evidence = current.evidence - 1)
        }
        if (learned.size > capacity) {
            val weakest = learned.entries.minByOrNull { it.value.evidence }!!.key
            learned.remove(weakest)
        }
        return true
    }

    /** A rejected correction to this casing counts as a lowercase use. */
    fun weaken(word: String): Boolean = observe(key(word), sentenceInitial = false)

    /** The user's casing for [word], or null when they write it in lowercase. */
    fun preferred(word: String, english: Boolean, minimumEvidence: Int = 1): String? {
        val lower = key(word)
        if (english) ENGLISH_FIXED[lower]?.let { return it }
        return learned[lower]?.takeIf { it.evidence >= minimumEvidence }?.surface
    }

    /**
     * Case for a displayed suggestion. [fragment] is what is typed of the word so far;
     * [sentenceStart] asks for an initial capital. Typing in capitals keeps capitals.
     */
    fun display(word: String, fragment: String, sentenceStart: Boolean, english: Boolean): String {
        val typedCaps = fragment.length >= 2 && fragment.all { !it.isLetter() || it.isUpperCase() }
        val preferred = preferred(word, english)
        return when {
            typedCaps -> word.uppercase(Locale.US)
            preferred != null -> preferred // always has its capitals, also at a sentence start ("iPhone")
            fragment.isNotEmpty() && fragment.first().isUpperCase() -> word.replaceFirstChar { it.uppercase(Locale.US) }
            fragment.isEmpty() && sentenceStart -> word.replaceFirstChar { it.uppercase(Locale.US) }
            else -> word
        }
    }

    fun clear() = learned.clear()

    fun encode(): String = JSONArray().apply {
        learned.values.forEach { put(JSONArray(listOf(it.surface, it.evidence))) }
    }.toString()

    fun restore(raw: String?) {
        learned.clear()
        if (raw.isNullOrBlank()) return
        try {
            val json = JSONArray(raw)
            for (i in 0 until minOf(json.length(), capacity)) {
                val row = json.optJSONArray(i) ?: continue
                val surface = row.optString(0)
                val evidence = row.optInt(1)
                val lower = key(surface)
                if (lower.length in 2..48 && surface != lower && evidence > 0) learned[lower] = Entry(surface, evidence.coerceAtMost(MAX_EVIDENCE))
            }
        } catch (_: Exception) {
            learned.clear()
        }
    }

    companion object {
        private const val MAX_EVIDENCE = 20
        /** English words that are always capitalised. */
        val ENGLISH_FIXED = mapOf("i" to "I", "i'm" to "I'm", "i'll" to "I'll", "i've" to "I've", "i'd" to "I'd")
    }
}
