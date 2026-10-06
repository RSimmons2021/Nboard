package com.nboard.ime.prediction

import org.json.JSONArray
import java.util.Locale
import kotlin.math.ln

/** Bounded suffix index. Stores word continuations, never entire messages. Main-thread owned. */
internal class PhraseMemory(private val capacity: Int = 2048) {
    data class Entry(val language: String, val app: String, val context: String, val word: String,
                     val count: Int, val lastUsed: Long)
    data class Receipt(val entries: List<Entry>, val sentence: List<String>)
    private data class Key(val language: String, val app: String, val context: String)
    private val index = linkedMapOf<Key, MutableMap<String, Entry>>()
    private var size = 0

    fun record(context: String, word: String, language: String, app: String, now: Long): Receipt? {
        if (language !in setOf("ENGLISH", "FRENCH", "BOTH")) return null
        val previous = sentenceTokens(context).takeLast(5)
        val normalized = normalize(word)
        if (previous.size < 2 || !validWord(normalized)) return null
        val changed = mutableListOf<Entry>()
        for (length in 2..previous.size) {
            val suffix = previous.takeLast(length).joinToString("|")
            // Global evidence supplies a fallback in new apps. App evidence breaks local ties.
            for (scope in listOf("", app).distinct()) {
                val key = Key(language, scope, suffix)
                val words = index.getOrPut(key) { linkedMapOf() }
                val old = words[normalized]
                val entry = Entry(language, scope, suffix, normalized, ((old?.count ?: 0) + 1).coerceAtMost(1000), now)
                if (old == null) size++
                words[normalized] = entry
                if (old?.count != entry.count) changed += entry
            }
        }
        trim(now)
        return Receipt(changed, previous + normalized)
    }

    fun retract(receipt: Receipt) {
        for (entry in receipt.entries) {
            val key = Key(entry.language, entry.app, entry.context)
            val words = index[key] ?: continue
            val current = words[entry.word] ?: continue
            if (current.count <= 1) { words.remove(entry.word); size-- }
            else words[entry.word] = current.copy(count = current.count - 1)
            if (words.isEmpty()) index.remove(key)
        }
    }

    /** A single strongest match wins; overlapping suffixes do not multiply the evidence. */
    fun suggestions(context: String, language: String, app: String, now: Long): Map<String, Double> {
        val tokens = sentenceTokens(context).takeLast(5)
        val result = mutableMapOf<String, Double>()
        // Established long-context habits outrank unrelated shorter suffixes.
        // Back off only when no longer context has repeated supporting evidence.
        val reliableLength = (4..tokens.size).filter { length ->
            val suffix = tokens.takeLast(length).joinToString("|")
            listOf("", app).distinct().any { scope ->
                index[Key(language, scope, suffix)].orEmpty().values.any { it.count >= 3 }
            }
        }.maxOrNull() ?: 2
        for (length in reliableLength..tokens.size) {
            val suffix = tokens.takeLast(length).joinToString("|")
            for (scope in listOf("", app).distinct()) {
                index[Key(language, scope, suffix)].orEmpty().values.forEach { entry ->
                    if (entry.count < 2) return@forEach // One accidental acceptance is not a recurring phrase.
                    val age = (now - entry.lastUsed).coerceAtLeast(0) / 86_400_000.0
                    val score = ln(1.0 + entry.count) * 2.0 + (length - 1) * .8 +
                        1.2 / (1 + age / 30) + if (scope.isNotEmpty()) .7 else 0.0
                    result[entry.word] = maxOf(result[entry.word] ?: 0.0, score)
                }
            }
        }
        return result
    }

    fun snapshot(): List<Entry> = index.values.flatMap { it.values }
    fun restore(entries: List<Entry>, now: Long = System.currentTimeMillis()) {
        clear()
        entries.take(capacity * 2).forEach { entry ->
            val parts = entry.context.split('|')
            if (entry.language !in setOf("ENGLISH", "FRENCH", "BOTH") || entry.app.length > 200 ||
                parts.size !in 2..5 || parts.any { !validWord(it) } || !validWord(entry.word) || entry.count <= 0) return@forEach
            val words = index.getOrPut(Key(entry.language, entry.app, entry.context)) { linkedMapOf() }
            if (entry.word !in words) size++
            words[entry.word] = entry.copy(count = entry.count.coerceAtMost(1000), lastUsed = entry.lastUsed.coerceIn(0, now))
        }
        trim(now)
    }
    fun clear() { index.clear(); size = 0 }
    /**
     * Trims to 90% of capacity once full, so this runs every ~capacity/10 entries, not every word.
     * Scores are computed once, the cut-off comes from a primitive sort, and the weakest entries
     * are removed in place (rebuilding 60k kept entries is what made a trim slow).
     */
    private fun trim(now: Long) {
        if (size <= capacity) return
        fun score(e: Entry) = ln(1.0 + e.count) / (1.0 + (now - e.lastUsed).coerceAtLeast(0) / (90 * 86_400_000.0))
        val scores = DoubleArray(size)
        var i = 0
        for (words in index.values) for (entry in words.values) scores[i++] = score(entry)
        val removeCount = size - capacity * 9 / 10
        val threshold = scores.copyOf(i).also { it.sort() }[removeCount - 1]
        var removed = 0
        val keys = index.entries.iterator()
        while (keys.hasNext() && removed < removeCount) {
            val words = keys.next().value
            val entries = words.values.iterator()
            while (entries.hasNext() && removed < removeCount) {
                if (score(entries.next()) <= threshold) { entries.remove(); removed++ }
            }
            if (words.isEmpty()) keys.remove()
        }
        size -= removed
    }

    companion object {
        /** Upper bound when reading a saved table (twice the keyboard's capacity). */
        private const val MAX_DECODED_ENTRIES = 131_072
        private val wordPattern = Regex("[\\p{L}]+(?:['’][\\p{L}]+)*")
        private val boundaryPattern = Regex("[.!?\\n\\r]")
        fun normalize(word: String) = word.lowercase(Locale.US).replace('’', '\'')
        fun sentenceTokens(text: String): List<String> {
            val boundary = boundaryPattern.findAll(text).lastOrNull()?.range?.last ?: -1
            return wordPattern.findAll(text.substring(boundary + 1)).map { normalize(it.value) }
                .filter { validWord(it) }.toList()
        }
        private fun validWord(word: String) = word.length in 1..24 && wordPattern.matches(word)
        fun encode(entries: List<Entry>): String = JSONArray().apply {
            entries.forEach { e -> put(JSONArray(listOf(e.language, e.app, e.context, e.word, e.count, e.lastUsed))) }
        }.toString()
        fun decode(raw: String): List<Entry> = try {
            val json = JSONArray(raw)
            (0 until minOf(json.length(), MAX_DECODED_ENTRIES)).mapNotNull { i ->
                val row = json.optJSONArray(i) ?: return@mapNotNull null
                if (row.length() != 6) return@mapNotNull null
                Entry(row.optString(0), row.optString(1), row.optString(2), row.optString(3), row.optInt(4), row.optLong(5))
            }
        } catch (_: Exception) { emptyList() }
    }
}
