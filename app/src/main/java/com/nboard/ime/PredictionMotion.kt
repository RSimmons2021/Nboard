package com.nboard.ime

internal fun commonPredictionPrefix(first: String, second: String): String {
    var count = 0
    while (count < minOf(first.length, second.length) && first[count] == second[count]) count++
    // Never split a surrogate pair or keep an ellipsis as a shared letter.
    if (count > 0 && (first[count - 1].isHighSurrogate() || first[count - 1] == '…')) count--
    return first.substring(0, count)
}

internal data class PredictionGlyph(
    val id: Long,
    val text: String,
    val source: String,
    val start: Int,
    val end: Int,
    val exiting: Boolean = false,
    val entering: Boolean = false
)

/** Grapheme clusters keep accents, emoji modifiers, and joined emoji together. */
internal fun predictionGraphemes(word: String): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()
    var start = 0
    var offset = 0
    var joinNext = false
    var regionalCount = 0
    while (offset < word.length) {
        val cp = word.codePointAt(offset)
        val type = Character.getType(cp)
        val combining = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
        val modifier = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF
        val regional = cp in 0x1F1E6..0x1F1FF
        val attached = offset == start || combining || modifier || cp == 0x200D || joinNext || (regional && regionalCount % 2 == 1)
        if (!attached) { result.add(start to offset); start = offset }
        regionalCount = if (regional) regionalCount + 1 else 0
        joinNext = cp == 0x200D
        offset += Character.charCount(cp)
    }
    if (start < offset) result.add(start to offset)
    return result
}

/** LCS correspondence prevents repeated letters from crossing or being duplicated. */
internal fun reconcilePredictionGlyphs(previous: List<PredictionGlyph>, word: String): List<PredictionGlyph> {
    val spans = predictionGraphemes(word)
    val active = previous.filterNot { it.exiting }.sortedBy { it.start }
    val letters = spans.map { (start, end) -> word.substring(start, end) }
    val lengths = Array(active.size + 1) { IntArray(letters.size + 1) }
    for (i in active.indices.reversed()) for (j in letters.indices.reversed()) {
        lengths[i][j] = if (active[i].text == letters[j]) 1 + lengths[i + 1][j + 1] else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }
    val matches = mutableMapOf<Int, PredictionGlyph>()
    var i = 0
    var j = 0
    while (i < active.size && j < letters.size) {
        if (active[i].text == letters[j]) { matches[j] = active[i]; i++; j++ }
        else if (lengths[i + 1][j] >= lengths[i][j + 1]) i++ else j++
    }
    val used = matches.values.mapTo(mutableSetOf()) { it.id }
    var nextId = (previous.maxOfOrNull { it.id } ?: 0L) + 1
    val next = spans.mapIndexed { index, (start, end) ->
        val old = matches[index] ?: previous.firstOrNull { it.exiting && it.text == letters[index] && it.id !in used }
        if (old != null) {
            used.add(old.id)
            old.copy(source = word, start = start, end = end, exiting = false)
        } else PredictionGlyph(nextId++, letters[index], word, start, end, entering = true)
    }
    return next + previous.filterNot { it.id in used }.map { it.copy(exiting = true) }
}
