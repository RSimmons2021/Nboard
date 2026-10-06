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

/** Match nearby letters only; unrelated suggestions must not drag shared letters across a slot. */
internal fun reconcilePredictionGlyphs(
    previous: List<PredictionGlyph>, word: String,
    positionOf: (String, Int) -> Float = { source, start -> start - source.length / 2f },
    maxTravel: Float = 2f
): List<PredictionGlyph> {
    val spans = predictionGraphemes(word)
    val active = previous.filterNot { it.exiting }.sortedBy { it.start }
    val letters = spans.map { (start, end) -> word.substring(start, end) }
    // Text shaping is more expensive than matching; measure each glyph once.
    val previousPositions = previous.associate { it.id to positionOf(it.source, it.start) }
    val targetPositions = spans.map { (start, _) -> positionOf(word, start) }
    fun canMatch(glyph: PredictionGlyph, index: Int): Boolean = glyph.text == letters[index] &&
        kotlin.math.abs(previousPositions.getValue(glyph.id) - targetPositions[index]) <= maxTravel
    val compatible = Array(active.size) { i -> BooleanArray(letters.size) { j -> canMatch(active[i], j) } }
    val lengths = Array(active.size + 1) { IntArray(letters.size + 1) }
    for (i in active.indices.reversed()) for (j in letters.indices.reversed()) {
        lengths[i][j] = if (compatible[i][j]) 1 + lengths[i + 1][j + 1] else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }
    val matches = mutableMapOf<Int, PredictionGlyph>()
    var i = 0
    var j = 0
    while (i < active.size && j < letters.size) {
        if (compatible[i][j]) { matches[j] = active[i]; i++; j++ }
        else if (lengths[i + 1][j] >= lengths[i][j + 1]) i++ else j++
    }
    val used = matches.values.mapTo(mutableSetOf()) { it.id }
    var nextId = (previous.maxOfOrNull { it.id } ?: 0L) + 1
    val next = spans.mapIndexed { index, (start, end) ->
        val old = matches[index] ?: previous.firstOrNull { it.exiting && canMatch(it, index) && it.id !in used }
        if (old != null) {
            used.add(old.id)
            old.copy(source = word, start = start, end = end, exiting = false)
        } else PredictionGlyph(nextId++, letters[index], word, start, end, entering = true)
    }
    return next + previous.filterNot { it.id in used }.map { it.copy(exiting = true) }
}

/** Letter morphing suits edits of one word ("hel" -> "hello"), not replacements ("hello" -> "thanks"). */
internal fun predictionWordsRelated(previous: String, next: String): Boolean {
    if (previous.isEmpty() || next.isEmpty()) return previous.isEmpty() && next.isEmpty()
    val shared = commonPredictionPrefix(previous.lowercase(), next.lowercase()).length
    return shared >= minOf(2, previous.length, next.length)
}

/** How strongly suggestion letters move when a suggestion changes (Settings > Suggestion animation). */
enum class PredictionMotionLevel(val label: String, internal val stiffness: Float, internal val entryDp: Float,
                                 internal val fadeInMs: Int, internal val fadeOutMs: Int) {
    OFF("Off", 0f, 0f, 0, 0),
    SUBTLE("Subtle", 3200f, 0f, 80, 50),
    STANDARD("Standard", 1600f, 0f, 110, 65),
    LIVELY("Lively", 700f, 2f, 140, 90);

    val animates: Boolean get() = this != OFF
}
