package com.nboard.ime

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Offline correction-quality benchmark. Run with:
 *   NBOARD_SPELL_BENCHMARK=1 [NBOARD_TYPO_CORPUS=/path/spell-errors.txt] ./gradlew testDebugUnitTest --tests '*SpellCorrectionBenchmark*'
 * A: simulated touch typos on QWERTY (Gaussian touch noise plus slips),
 * B: human misspellings (Birkbeck corpus via norvig.com/ngrams/spell-errors.txt),
 * C: valid rare words outside the 50k dictionary that must be left alone.
 */
class SpellCorrectionBenchmark {
    private val assets = File("src/main/assets/dictionaries")
    private val frequencies: Map<String, Int> by lazy {
        val map = LinkedHashMap<String, Int>()
        File(assets, "english_50k.txt").forEachLine { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2) map[parts[0]] = parts[1].toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 1
        }
        map
    }
    private val bigrams: Map<String, Map<String, Long>> by lazy {
        val map = HashMap<String, HashMap<String, Long>>()
        File(assets, "english_bigrams.txt").forEachLine { line ->
            val p = line.trim().split(' ')
            if (p.size == 3) map.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toLongOrNull() ?: 1
        }
        map
    }

    /** Words the service already treats as valid (it never corrects them). */
    private val knownWords: Set<String> by lazy { File(assets, "en_words.txt").readLines().map { it.trim().lowercase() }.toSet() + frequencies.keys }

    data class Case(val typed: String, val expected: String, val previous: String?)

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val offsets = listOf(0.0, 0.5, 1.5)
    private val keyCenters: Map<Char, Pair<Double, Double>> = rows.flatMapIndexed { r, row ->
        row.mapIndexed { c, ch -> ch to (offsets[r] + c to r * 1.35) }
    }.toMap()

    private fun nearestKey(x: Double, y: Double): Char = keyCenters.minBy { (_, p) ->
        val dx = p.first - x; val dy = p.second - y; dx * dx + dy * dy
    }.key

    /** Each tap lands around the intended key; occasional transposition, omission or double tap. */
    private fun touchTypo(word: String, random: Random): String {
        val out = StringBuilder()
        for (ch in word) {
            val center = keyCenters[ch]
            if (center == null) { out.append(ch); continue }
            val x = center.first + random.nextGaussian() * 0.42
            val y = center.second + random.nextGaussian() * 0.38
            out.append(nearestKey(x, y))
        }
        var s = out.toString()
        if (s.length > 3 && random.nextDouble() < 0.06) {
            val i = random.nextInt(s.length - 1)
            s = s.substring(0, i) + s[i + 1] + s[i] + s.substring(i + 2)
        }
        if (s.length > 4 && random.nextDouble() < 0.06) {
            val i = random.nextInt(s.length); s = s.removeRange(i, i + 1)
        }
        return s
    }

    private fun previousWordFor(word: String, random: Random): String? {
        // Previous words that are known to precede the target; used for context-aware engines.
        val options = bigrams.entries.filter { (_, next) -> word in next }.map { it.key }
        return if (options.isEmpty()) null else options[random.nextInt(options.size)]
    }

    private fun touchCases(): List<Case> {
        val random = Random(42)
        val vocabulary = frequencies.keys.drop(30).take(12_000).filter { w -> w.length >= 3 && w.all { it in 'a'..'z' } }
        val cases = mutableListOf<Case>()
        while (cases.size < 1500) {
            val word = vocabulary[random.nextInt(vocabulary.size)]
            val typed = touchTypo(word, random)
            if (typed == word || typed in knownWords) continue // only non-word errors
            cases += Case(typed, word, previousWordFor(word, random))
        }
        return cases
    }

    private fun humanCases(): List<Case> {
        val path = System.getenv("NBOARD_TYPO_CORPUS") ?: return emptyList()
        val cases = mutableListOf<Case>()
        File(path).forEachLine { line ->
            val correct = line.substringBefore(':').trim().lowercase()
            if (correct !in frequencies) return@forEachLine
            line.substringAfter(':').split(',').map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } && it !in knownWords && it != correct }
                .forEach { cases += Case(it, correct, null) }
        }
        return cases.shuffled(Random(3)).take(System.getenv("NBOARD_HUMAN_LIMIT")?.toIntOrNull() ?: cases.size)
    }

    /** Names, brands and slang a person types on purpose; none are in the dictionaries. */
    private fun intendedUnknownWords(): List<String> = ("""
        jaxon brayden kaylee aaliyah jayden nevaeh khloe kinsley zayden greyson maddox paisley ryker
        tiktok snapchat insta whatsapp spotify netflix youtube venmo airbnb doordash zoom uber lyft
        lmao smh tbh imo idk ngl fr rn istg bruh sus yeet lowkey highkey finna bestie periodt slay
        kubernetes javascript typescript github postgres nginx figma notion chatgpt anthropic
        okayyy sooo yesss nooo hahaha omgg pls thx ty np
        siobhan saoirse niamh oisin padraig tadhg aoife ciara eoin ronan cormac
        """).trim().split(Regex("\\s+")).filter { it !in knownWords }

    fun report(name: String, correct: (typed: String, previous: String?) -> String?) {
        fun score(cases: List<Case>, label: String) {
            if (cases.isEmpty()) return
            var fixed = 0; var wrong = 0; var unchanged = 0
            val started = System.nanoTime()
            for (case in cases) {
                when (correct(case.typed, case.previous)) {
                    case.expected -> fixed++
                    null -> unchanged++
                    else -> wrong++
                }
            }
            val us = (System.nanoTime() - started) / 1000.0 / cases.size
            println("SPELL $name | $label n=${cases.size} fixed=${"%.1f".format(fixed * 100.0 / cases.size)}% " +
                "wrong=${"%.1f".format(wrong * 100.0 / cases.size)}% unchanged=${"%.1f".format(unchanged * 100.0 / cases.size)}% avg=${"%.0f".format(us)}us")
        }
        score(touchCases(), "A touch typos")
        score(humanCases(), "B human misspellings")
        val rare = intendedUnknownWords()
        val changed = rare.filter { correct(it, null) != null }
        println("SPELL $name | C names/slang n=${rare.size} overcorrected=${"%.1f".format(changed.size * 100.0 / rare.size)}% ${changed.take(12)}")
    }

    @Test fun benchmark() {
        assumeTrue(System.getenv("NBOARD_SPELL_BENCHMARK") != null)
        val current = AutoCorrect(emptyMap(), frequencies, AutoCorrect.AutoCorrectMode.ENGLISH_ONLY, cacheCapacity = 1)
        repeat(2) { current.correct("warmup", null) }
        report("current") { typed, previous -> current.correct(typed, previous) }

        val trie = DictionaryTrie().apply { frequencies.forEach { (w, f) -> insert(w, f) } }
        val totals = bigrams.mapValues { (_, next) -> next.values.sum().toDouble() }
        val context = { previous: String, word: String ->
            bigrams[previous]?.get(word)?.let { it / totals.getValue(previous) } ?: if (previous in bigrams) 0.0 else null
        }
        // Search once per typed word, then sweep scoring weights in memory.
        val searcher = NoisyChannelCorrector(trie)
        val cache = HashMap<String, List<NoisyChannelCorrector.Nearby>>()
        val searchStarted = System.nanoTime()
        val allTyped = (touchCases() + humanCases()).map { it.typed } + intendedUnknownWords()
        allTyped.forEach { typed -> cache.getOrPut(typed) { searcher.nearby(typed) } }
        println("SPELL search avg=${"%.0f".format((System.nanoTime() - searchStarted) / 1000.0 / cache.size)}us per word (n=${cache.size})")
        val grid = System.getenv("NBOARD_SPELL_GRID")?.split(';')?.map { spec ->
            val v = spec.split(',').map { it.toDouble() }
            NoisyChannelCorrector.Params(costWeight = v[0], contextWeight = v[1], keepTypedPrior = v[2],
                margin = v.getOrElse(3) { 0.0 }, budgetScale = v.getOrElse(4) { 1.0 })
        } ?: listOf(NoisyChannelCorrector.Params())
        for (params in grid) {
            val engine = NoisyChannelCorrector(trie, TypoModel(), params)
            val label = "noisy cost=${params.costWeight} ctx=${params.contextWeight} keep=${params.keepTypedPrior} margin=${params.margin} budget=${params.budgetScale}"
            report(label) { typed, previous ->
                engine.correct(typed, previous, context, engine.withinBudget(typed, cache.getValue(typed)))
            }
            for ((set, cases) in listOf("A" to touchCases(), "B" to humanCases())) {
                val top3 = cases.count { case ->
                    engine.rank(case.typed, case.previous, context, engine.withinBudget(case.typed, cache.getValue(case.typed)))
                        .take(3).any { it.first.word == case.expected }
                }
                println("SPELL $label | $set expected word in top-3 suggestions=${"%.1f".format(top3 * 100.0 / cases.size)}%")
            }
        }
    }
}
