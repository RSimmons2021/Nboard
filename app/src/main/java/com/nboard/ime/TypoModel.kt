package com.nboard.ime

import java.text.Normalizer
import kotlin.math.hypot

/**
 * Cost of each kind of typing slip, from key geometry. Costs are negative log-likelihoods
 * (smaller = more likely): pressing a neighbouring key is a common slip, a key across the
 * keyboard is not. Positions are in key widths; rows are staggered like a phone keyboard.
 * All pair costs are precomputed: the correction search evaluates them per trie cell.
 */
internal class TypoModel(rows: List<String> = QWERTY_ROWS) {
    private val index = IntArray(INDEX_RANGE) { -1 }
    private val substitutionCost: Array<DoubleArray>
    private val nearPairs: Array<BooleanArray>

    init {
        val widest = rows.maxOf { it.length }
        val positions = HashMap<Char, Pair<Double, Double>>()
        rows.forEachIndexed { r, row ->
            val offset = (widest - row.length) / 2.0
            row.forEachIndexed { c, ch -> positions[ch] = (offset + c) to r * ROW_PITCH }
        }
        val alphabet = (positions.keys + ACCENTED.toList() + APOSTROPHES).distinct()
        alphabet.forEachIndexed { i, ch -> if (ch.code < INDEX_RANGE) index[ch.code] = i }
        fun base(ch: Char) = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).first()
        fun distance(a: Char, b: Char): Double? {
            val p = positions[base(a)] ?: return null
            val q = positions[base(b)] ?: return null
            return hypot(p.first - q.first, p.second - q.second)
        }
        substitutionCost = Array(alphabet.size) { DoubleArray(alphabet.size) }
        nearPairs = Array(alphabet.size) { BooleanArray(alphabet.size) }
        for ((i, typed) in alphabet.withIndex()) for ((j, intended) in alphabet.withIndex()) {
            val d = distance(typed, intended)
            nearPairs[i][j] = d != null && d <= 1.15
            substitutionCost[i][j] = when {
                typed == intended -> 0.0
                typed in APOSTROPHES && intended in APOSTROPHES -> ACCENT
                base(typed) == base(intended) -> ACCENT
                d == null -> FAR
                d <= 1.15 -> NEIGHBOUR
                d <= 2.2 -> NEAR
                else -> FAR
            }
        }
    }

    private fun indexOf(ch: Char) = if (ch.code < INDEX_RANGE) index[ch.code] else -1

    /** [typed] was pressed where [intended] was meant. */
    fun substitution(typed: Char, intended: Char): Double {
        if (typed == intended) return 0.0
        val i = indexOf(typed); val j = indexOf(intended)
        return if (i < 0 || j < 0) FAR else substitutionCost[i][j]
    }

    /** Cost of typed[k] being an extra character (a double tap or a brush against a neighbour), for every k. */
    fun insertionCosts(typed: CharSequence): DoubleArray = DoubleArray(typed.length) { k ->
        val c = typed[k]
        val before = typed.getOrNull(k - 1)
        val after = typed.getOrNull(k + 1)
        when {
            c in APOSTROPHES -> APOSTROPHE
            c == before || c == after -> DOUBLE_TAP
            listOfNotNull(before, after).any { near(c, it) } -> BRUSH
            else -> EXTRA
        }
    }

    private fun near(a: Char, b: Char): Boolean {
        val i = indexOf(a); val j = indexOf(b)
        return i >= 0 && j >= 0 && nearPairs[i][j]
    }

    /** The intended character [missing] was not typed. */
    fun deletion(missing: Char, previousIntended: Char?): Double = when {
        missing == '\'' || missing == '’' -> APOSTROPHE
        missing == previousIntended -> DOUBLE_LETTER
        else -> OMISSION
    }

    companion object {
        val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private const val INDEX_RANGE = 0x2020
        private const val ACCENTED = "àâäæçéèêëîïôöùûüÿœ"
        private val APOSTROPHES = listOf('\'', '’')
        private const val ROW_PITCH = 1.35 // keys are taller than they are wide
        const val ACCENT = 0.25
        const val APOSTROPHE = 0.2
        const val NEIGHBOUR = 0.8
        const val NEAR = 1.4
        const val FAR = 2.0
        const val DOUBLE_TAP = 0.7
        const val BRUSH = 0.9
        const val EXTRA = 1.4
        const val DOUBLE_LETTER = 0.6
        const val OMISSION = 1.1
        const val TRANSPOSITION = 0.8
    }
}
