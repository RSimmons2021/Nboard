package com.nboard.ime

import android.content.Context
import java.util.LinkedHashMap
import java.util.Locale

private enum class BilingualLanguageHint {
    FRENCH,
    ENGLISH,
    UNKNOWN
}

class AutoCorrect(
    private val context: Context?,
    private var mode: AutoCorrectMode = AutoCorrectMode.BILINGUAL,
    private val frenchAssetPath: String = "dictionaries/french_50k.txt",
    private val englishAssetPath: String = "dictionaries/english_50k.txt",
    private val cacheCapacity: Int = 1200
) {
    enum class AutoCorrectMode {
        FRENCH_ONLY,
        ENGLISH_ONLY,
        BILINGUAL
    }

    /** Frequencies live in the trie only; [size] is the word count. */
    private data class FrequencyDictionary(
        val size: Int,
        val trie: DictionaryTrie
    ) {
        fun frequency(word: String): Int = trie.frequency(word) ?: 0

        fun contains(word: String): Boolean = trie.contains(word)

        companion object {
            fun empty() = FrequencyDictionary(0, DictionaryTrie())
        }
    }

    private data class RankedCandidate(val word: String, val score: Double)

    private val lock = Any()

    @Volatile
    private var typoModel = TypoModel()
    private var keyboardRows: List<String> = TypoModel.QWERTY_ROWS

    /**
     * P(word | previous word) from the user's own history, or null when unknown.
     * Set by the keyboard service; read on the correcting thread.
     */
    @Volatile
    var contextProbability: ((previous: String, word: String) -> Double?)? = null

    /** Key geometry for slip costs; call when the letter layout changes (QWERTY, AZERTY, ...). */
    fun setKeyboardRows(rows: List<String>) {
        val letters = rows.map { row -> row.lowercase(Locale.ROOT).filter { it.isLetter() } }.filter { it.isNotEmpty() }
        if (letters.isEmpty() || letters == keyboardRows) return
        synchronized(lock) {
            keyboardRows = letters
            typoModel = TypoModel(letters)
            correctionCache.clear()
        }
    }

    @Volatile
    private var loaded = false

    private var frenchDictionary: FrequencyDictionary = FrequencyDictionary.empty()
    private var englishDictionary: FrequencyDictionary = FrequencyDictionary.empty()

    private val correctionCache = object : LinkedHashMap<String, String?>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > cacheCapacity
        }
    }

    internal constructor(
        frenchFrequencies: Map<String, Int>,
        englishFrequencies: Map<String, Int>,
        mode: AutoCorrectMode = AutoCorrectMode.BILINGUAL,
        cacheCapacity: Int = 1200
    ) : this(
        context = null,
        mode = mode,
        frenchAssetPath = "",
        englishAssetPath = "",
        cacheCapacity = cacheCapacity
    ) {
        synchronized(lock) {
            frenchDictionary = buildFrequencyDictionary(normalizeFrequencyMap(frenchFrequencies))
            englishDictionary = buildFrequencyDictionary(normalizeFrequencyMap(englishFrequencies))
            correctionCache.clear()
            loaded = true
        }
    }

    fun preload() {
        ensureLoaded()
    }

    /** Nonblocking recognition from the same dictionaries used for correction. */
    internal fun isLoadedWord(word: String, language: KeyboardLanguageMode): Boolean {
        if (!loaded || language == KeyboardLanguageMode.DISABLED) return false
        val normalized = normalizeWord(word)
        return (language != KeyboardLanguageMode.FRENCH && englishDictionary.contains(normalized)) ||
            (language != KeyboardLanguageMode.ENGLISH && frenchDictionary.contains(normalized))
    }

    fun setMode(newMode: AutoCorrectMode) {
        synchronized(lock) {
            if (mode == newMode) {
                return
            }
            mode = newMode
            correctionCache.clear()
            // Load (or release) dictionaries for the new language on the next correction.
            if (context != null) loaded = false
        }
    }

    fun setModeFromKeyboardMode(languageMode: KeyboardLanguageMode) {
        val resolved = when (languageMode) {
            KeyboardLanguageMode.FRENCH -> AutoCorrectMode.FRENCH_ONLY
            KeyboardLanguageMode.ENGLISH -> AutoCorrectMode.ENGLISH_ONLY
            KeyboardLanguageMode.BOTH -> AutoCorrectMode.BILINGUAL
            KeyboardLanguageMode.DISABLED -> AutoCorrectMode.BILINGUAL
        }
        setMode(resolved)
    }

    fun correct(word: String, previousWord: String?): String? {
        ensureLoaded()

        val normalizedWord = normalizeWord(word)
        if (normalizedWord.length < MIN_WORD_LENGTH || normalizedWord.length > MAX_WORD_LENGTH) {
            return null
        }

        val activeMode = synchronized(lock) { mode }
        val hint = languageHint(previousWord)
        val cacheKey = "$activeMode|$hint|${normalizeWord(previousWord.orEmpty())}|$normalizedWord"

        synchronized(lock) {
            if (correctionCache.containsKey(cacheKey)) {
                return correctionCache[cacheKey]
            }
        }

        val previous = previousWord?.let(::normalizeWord)?.takeIf { it.isNotBlank() }
        val suggestion = when (activeMode) {
            AutoCorrectMode.FRENCH_ONLY ->
                findBestSuggestion(normalizedWord, frenchDictionary, previous)

            AutoCorrectMode.ENGLISH_ONLY ->
                findBestSuggestion(normalizedWord, englishDictionary, previous)

            AutoCorrectMode.BILINGUAL ->
                correctBilingual(normalizedWord, hint, previous)
        }

        val result = suggestion?.takeIf { it != normalizedWord }
        synchronized(lock) {
            correctionCache[cacheKey] = result
        }
        return result
    }

    private fun correctBilingual(word: String, hint: BilingualLanguageHint, previous: String?): String? {
        if (frenchDictionary.contains(word) || englishDictionary.contains(word)) {
            return null
        }

        return when (hint) {
            BilingualLanguageHint.FRENCH ->
                findBestSuggestion(word, frenchDictionary, previous) ?: findBestSuggestion(word, englishDictionary, previous)

            BilingualLanguageHint.ENGLISH ->
                findBestSuggestion(word, englishDictionary, previous) ?: findBestSuggestion(word, frenchDictionary, previous)

            BilingualLanguageHint.UNKNOWN -> {
                val french = findBestCandidate(word, frenchDictionary, previous)
                val english = findBestCandidate(word, englishDictionary, previous)
                listOfNotNull(french, english).maxByOrNull { it.score }?.word
            }
        }?.takeIf { it != word }
    }

    private fun findBestSuggestion(word: String, dictionary: FrequencyDictionary, previous: String?): String? {
        if (dictionary.contains(word)) {
            return null
        }
        return findBestCandidate(word, dictionary, previous)?.word
    }

    /**
     * Keyboard-aware noisy-channel choice (see [NoisyChannelCorrector]): word frequency and
     * personal context against key-geometry slip costs, up to two slips. Null keeps the word.
     */
    private fun findBestCandidate(word: String, dictionary: FrequencyDictionary, previous: String?): RankedCandidate? {
        val corrector = NoisyChannelCorrector(dictionary.trie, typoModel, CORRECTION_PARAMS)
        val context = contextProbability
        val ranked = corrector.rank(word, previous, context)
        val chosen = corrector.correct(word, previous, context, ranked.map { it.first }) ?: return null
        return RankedCandidate(chosen, ranked.first { it.first.word == chosen }.second)
    }

    private fun languageHint(previousWord: String?): BilingualLanguageHint {
        val normalized = normalizeWord(previousWord.orEmpty())
        if (normalized.isBlank()) {
            return BilingualLanguageHint.UNKNOWN
        }
        if (FRENCH_INDICATORS.contains(normalized)) {
            return BilingualLanguageHint.FRENCH
        }
        if (ENGLISH_INDICATORS.contains(normalized)) {
            return BilingualLanguageHint.ENGLISH
        }
        return BilingualLanguageHint.UNKNOWN
    }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }

        synchronized(lock) {
            if (loaded) {
                return
            }

            // Only the active language(s) stay in memory: each dictionary is a 50k-word tree.
            val needEnglish = mode != AutoCorrectMode.FRENCH_ONLY
            val needFrench = mode != AutoCorrectMode.ENGLISH_ONLY
            englishDictionary = when {
                !needEnglish -> FrequencyDictionary.empty()
                englishDictionary.size == 0 -> loadFrequencyDictionary(englishAssetPath)
                else -> englishDictionary
            }
            frenchDictionary = when {
                !needFrench -> FrequencyDictionary.empty()
                frenchDictionary.size == 0 -> loadFrequencyDictionary(frenchAssetPath)
                else -> frenchDictionary
            }
            correctionCache.clear()
            loaded = true
        }
    }

    private fun loadFrequencyDictionary(path: String): FrequencyDictionary {
        val appContext = context ?: return FrequencyDictionary.empty()
        return try {
            val map = HashMap<String, Int>(60_000)
            appContext.assets.open(path).bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isBlank()) {
                        return@forEach
                    }

                    val parts = line.split(WHITESPACE_REGEX)
                    if (parts.isEmpty()) {
                        return@forEach
                    }

                    val (wordPart, frequencyPart) = when {
                        parts.size >= 3 && parts[0].all(Char::isDigit) -> parts[1] to parts[2]
                        parts.size >= 2 -> parts[0] to parts[1]
                        else -> parts[0] to "1"
                    }

                    val word = normalizeWord(wordPart)
                    if (word.length !in MIN_WORD_LENGTH..MAX_WORD_LENGTH || !WORD_PATTERN.matches(word)) {
                        return@forEach
                    }

                    val frequency = frequencyPart
                        .toLongOrNull()
                        ?.coerceAtLeast(1L)
                        ?.coerceAtMost(Int.MAX_VALUE.toLong())
                        ?.toInt()
                        ?: 1
                    val existing = map[word] ?: 0
                    map[word] = maxOf(existing, frequency)
                }
            }
            buildFrequencyDictionary(map)
        } catch (_: Exception) {
            FrequencyDictionary.empty()
        }
    }

    private fun buildFrequencyDictionary(frequencies: Map<String, Int>): FrequencyDictionary {
        if (frequencies.isEmpty()) {
            return FrequencyDictionary.empty()
        }

        val trie = DictionaryTrie()
        frequencies.forEach { (word, frequency) ->
            trie.insert(word, frequency)
        }
        trie.freeze()
        return FrequencyDictionary(
            size = frequencies.size,
            trie = trie
        )
    }

    private fun normalizeWord(value: String): String {
        return value
            .trim()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('ʼ', '\'')
            .replace('`', '\'')
            .replace('´', '\'')
            .replace('‛', '\'')
            .replace('＇', '\'')
            .lowercase(Locale.US)
    }

    companion object {
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_WORD_LENGTH = 24

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val WORD_PATTERN = Regex("[a-zàâäæçéèêëîïôöùûüÿœ]+(?:['-][a-zàâäæçéèêëîïôöùûüÿœ]+)*")

        /** Tuned with SpellCorrectionBenchmark: precision first; near misses stay as suggestions. */
        internal val CORRECTION_PARAMS = NoisyChannelCorrector.Params(
            costWeight = 2.0, contextWeight = 1.0, keepTypedPrior = 3.5, margin = 1.5
        )

        private val FRENCH_INDICATORS = setOf(
            "je", "tu", "il", "elle", "nous", "vous", "le", "la", "les", "un", "une", "des",
            "de", "du", "à", "au", "et", "est", "sont"
        )

        private val ENGLISH_INDICATORS = setOf(
            "i", "you", "he", "she", "we", "they", "the", "a", "an", "is", "are", "was", "were",
            "have", "has"
        )

        private fun normalizeFrequencyMap(raw: Map<String, Int>): Map<String, Int> {
            if (raw.isEmpty()) {
                return emptyMap()
            }

            val normalized = HashMap<String, Int>(raw.size)
            raw.forEach { (wordRaw, freqRaw) ->
                val word = wordRaw.trim()
                    .replace('’', '\'')
                    .replace('‘', '\'')
                    .replace('ʼ', '\'')
                    .replace('`', '\'')
                    .replace('´', '\'')
                    .replace('‛', '\'')
                    .replace('＇', '\'')
                    .lowercase(Locale.US)
                if (word.length !in MIN_WORD_LENGTH..MAX_WORD_LENGTH || !WORD_PATTERN.matches(word)) {
                    return@forEach
                }
                val frequency = freqRaw.coerceAtLeast(1)
                val existing = normalized[word] ?: 0
                normalized[word] = maxOf(existing, frequency)
            }
            return normalized
        }
    }
}
