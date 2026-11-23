package com.nboard.ime

import android.content.Context
import java.util.Locale

/**
 * English word -> emoji, best first, from `assets/dictionaries/emoji_keywords_en.tsv`
 * (Unicode CLDR annotations, built by tools/emoji-keywords/build_emoji_keywords.py).
 *
 * Like Gboard, the strip offers emoji for the word being typed ("pizza" -> 🍕) and, after a
 * space, for the word just finished ("happy birthday " -> 🎂 🥳).
 */
internal class EmojiKeywords(private val index: Map<String, List<String>>) {

    /**
     * Up to [limit] emoji for the word at the end of [beforeCursor]: the word being typed, or
     * the previous word when exactly one space follows it. Emoji the user picks often
     * ([usage], counted per skin-tone base) move ahead; the table order breaks ties.
     */
    fun suggest(beforeCursor: String, usage: Map<String, Int> = emptyMap(), limit: Int = 2): List<String> {
        val word = targetWord(beforeCursor) ?: return emptyList()
        val found = index[word] ?: word.takeIf { it.length > 3 && it.endsWith('s') }?.let { index[it.dropLast(1)] }
            ?: return emptyList()
        return found.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { usage[EmojiTones.base(it.value)] ?: 0 }.thenBy { it.index })
            .take(limit).map { it.value }
    }

    private fun targetWord(beforeCursor: String): String? {
        val text = when {
            beforeCursor.lastOrNull()?.isLetter() == true -> beforeCursor
            // One space after a word; a second space or punctuation ends the moment.
            beforeCursor.endsWith(' ') && beforeCursor.getOrNull(beforeCursor.length - 2)?.isLetter() == true -> beforeCursor.dropLast(1)
            else -> return null
        }
        val word = text.takeLastWhile { it.isLetter() }
        // Part of a longer token ("www.pizza", "@dog", "don't") is not a word to illustrate.
        val before = text.getOrNull(text.length - word.length - 1)
        if (before != null && !before.isWhitespace() && before !in "\"“(") return null
        return word.lowercase(Locale.US).takeIf { it.length >= 2 }
    }

    companion object {
        const val ASSET_PATH = "dictionaries/emoji_keywords_en.tsv"
        val EMPTY = EmojiKeywords(emptyMap())

        fun parse(lines: Sequence<String>): EmojiKeywords = EmojiKeywords(lines
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            }
            .toMap())

        fun load(context: Context): EmojiKeywords = try {
            context.assets.open(ASSET_PATH).bufferedReader().useLines { parse(it) }
        } catch (_: Exception) {
            EMPTY
        }
    }
}
