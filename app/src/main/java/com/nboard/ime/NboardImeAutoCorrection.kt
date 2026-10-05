package com.nboard.ime

import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

private val FRENCH_ELISION_PREFIXES = setOf('c', 'd', 'j', 'l', 'm', 'n', 's', 't')

internal fun NboardImeService.commitWordPrediction(predictedWord: String) {
    val word = predictedWord.trim()
    if (word.isBlank() || isAiMode || isClipboardOpen || isEmojiMode) {
        return
    }

    val inputConnection = currentInputConnection ?: return
    val beforeCursor = inputConnection
        .getTextBeforeCursor(PREDICTION_CONTEXT_WINDOW, 0)
        ?.toString()
        .orEmpty()
    val fragment = extractCurrentWordFragment(beforeCursor)
    val sentenceContext = extractPredictionSentenceContext(beforeCursor)
    val (previousWord2, previousWord1) = extractPreviousWordsForPrediction(sentenceContext, fragment)
    inputConnection.beginBatchEdit()
    try {
        if (fragment.isNotBlank()) inputConnection.deleteSurroundingText(fragment.length, 0)
        inputConnection.commitText(word, 1)
        inputConnection.commitText(" ", 1)
    } finally {
        inputConnection.endBatchEdit()
    }
    val normalizedWord = normalizeWord(word)
    recordLearnedTransition(previousWord1, normalizedWord, boost = 3)
    recordLearnedTrigram(previousWord2, previousWord1, normalizedWord, boost = 3)
    learnPredictionFromContext(inputConnection)
    pendingAutoCorrection = null
    pendingPredictionUndo = PredictionUndo(fragment, word, " ",
        (minOf(editorSelectionStart, editorSelectionEnd)-fragment.length).coerceAtLeast(0), aiFieldRevision)
    val consumedOneShot = consumeOneShotShiftIfNeeded(word)
    refreshAutoShiftFromContextAndRerender(consumedOneShot)
}

internal data class PredictionUndo(val fragment: String, val accepted: String, val suffix: String,
                                   val start: Int, val fieldRevision: Long)

internal fun NboardImeService.tryUndoPrediction(): Boolean {
    val undo = pendingPredictionUndo ?: return false
    pendingPredictionUndo = null
    val connection = currentInputConnection ?: return false
    val inserted = undo.accepted + undo.suffix
    if (aiFieldRevision != undo.fieldRevision || editorSelectionStart != undo.start+inserted.length ||
        editorSelectionEnd != editorSelectionStart) return false
    if (!connection.getSelectedText(0).isNullOrEmpty() || connection.getTextBeforeCursor(inserted.length, 0)?.toString() != inserted) return false
    connection.beginBatchEdit()
    try {
        connection.deleteSurroundingText(inserted.length, 0)
        connection.commitText(undo.fragment, 1)
    } finally { connection.endBatchEdit() }
    recordRejectedCorrection(undo.fragment, undo.accepted)
    return true
}

internal fun NboardImeService.tryRevertLastAutoCorrection(): Boolean {
    val correction = pendingAutoCorrection ?: return false
    val inputConnection = currentInputConnection ?: return false

    val probeSize = correction.correctedWord.length + correction.committedSuffix.length + 8
    val beforeCursor = inputConnection.getTextBeforeCursor(probeSize, 0)?.toString().orEmpty()
    if (!beforeCursor.endsWith(correction.correctedWord + correction.committedSuffix)) {
        pendingAutoCorrection = null
        return false
    }

    inputConnection.beginBatchEdit()
    try {
        inputConnection.deleteSurroundingText(
            correction.correctedWord.length + correction.committedSuffix.length,
            0
        )
        inputConnection.commitText(correction.originalWord + correction.committedSuffix, 1)
    } finally {
        inputConnection.endBatchEdit()
    }
    recordRejectedCorrection(correction.originalWord, correction.correctedWord)
    incrementLearnedWord(correction.originalWord, AUTOCORRECT_REVERT_LEARN_BOOST)
    persistPredictionLearningIfNeeded()
    pendingAutoCorrection = null
    return true
}

internal fun NboardImeService.resetLexicons() {
    englishLexicon = buildLexicon(ENGLISH_WORDS)
    frenchLexicon = buildLexicon(FRENCH_WORDS)
}

internal fun NboardImeService.preloadLexiconsFromAssets() {
    serviceScope.launch(Dispatchers.Default) {
        val englishExtra = loadWordsFromAsset("dictionaries/en_words.txt")
        if (englishExtra.isNotEmpty()) {
            englishLexicon = buildLexicon(englishLexicon.words + englishExtra)
        }

        val frenchExtra = loadWordsFromAsset("dictionaries/fr_words.txt")
        if (frenchExtra.isNotEmpty()) {
            frenchLexicon = buildLexicon(frenchLexicon.words + frenchExtra)
        }
    }
}

internal fun NboardImeService.loadWordsFromAsset(path: String): Set<String> {
    return try {
        assets.open(path).bufferedReader().useLines { sequence ->
            sequence
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.length in 2..24 }
                .filter { ASSET_WORD_REGEX.matches(it) }
                .toSet()
        }
    } catch (_: Exception) {
        emptySet()
    }
}

internal fun NboardImeService.buildLexicon(words: Collection<String>): Lexicon {
    if (words.isEmpty()) {
        return Lexicon.empty()
    }

    val normalizedWords = LinkedHashSet<String>(words.size)
    val foldedWords = HashSet<String>(words.size)
    val byFirst = HashMap<Char, MutableList<String>>()
    val byPrefix2 = HashMap<String, MutableList<String>>()
    val foldedToWord = HashMap<String, String>()

    words.forEach { raw ->
        val word = raw.trim().lowercase(Locale.US)
        if (word.length !in 2..24 || !ASSET_WORD_REGEX.matches(word)) {
            return@forEach
        }
        normalizedWords.add(word)

        val folded = foldWord(word)
        if (folded.isBlank()) {
            return@forEach
        }
        foldedWords.add(folded)
        byFirst.getOrPut(folded.first()) { mutableListOf() }.add(word)
        if (folded.length >= 2) {
            byPrefix2.getOrPut(folded.take(2)) { mutableListOf() }.add(word)
        }

        val existing = foldedToWord[folded]
        if (existing == null || word.length < existing.length) {
            foldedToWord[folded] = word
        }
    }

    val indexed = byFirst.mapValues { (_, list) ->
        list.distinct().sortedWith(compareBy<String> { it.length }.thenBy { it })
    }
    val indexedByPrefix2 = byPrefix2.mapValues { (_, list) ->
        list.distinct().sortedWith(compareBy<String> { it.length }.thenBy { it })
    }

    return Lexicon(
        words = normalizedWords,
        foldedWords = foldedWords,
        byFirst = indexed,
        byPrefix2 = indexedByPrefix2,
        foldedToWord = foldedToWord
    )
}

internal fun NboardImeService.applyAutoCorrectionBeforeDelimiter(inputConnection: InputConnection): AutoCorrectionResult? {
    if (keyboardLanguageMode == KeyboardLanguageMode.DISABLED) {
        return null
    }
    val beforeCursor = inputConnection.getTextBeforeCursor(AUTOCORRECT_CONTEXT_WINDOW, 0)?.toString().orEmpty()
    val sourceWord = extractTrailingWord(beforeCursor) ?: return null
    val normalizedSource = normalizeWord(sourceWord)
    if (normalizedSource.length < 2) {
        return null
    }
    if (!isKnownWord(normalizedSource)) {
        val learnedCount = learnedWordFrequency[normalizedSource] ?: 0
        if (learnedCount >= AUTOCORRECT_LEARNED_WORD_SKIP_THRESHOLD) {
            return null
        }
    }

    val previousWord = extractPreviousWordForAutoCorrection(beforeCursor, sourceWord)
    val contextLanguage = detectContextLanguage(beforeCursor)
    var suggestion = lookupTypoCorrection(normalizedSource, contextLanguage)

    if (suggestion == null) {
        autoCorrectEngine.setModeFromKeyboardMode(keyboardLanguageMode)
        val startNanos = SystemClock.elapsedRealtimeNanos()
        suggestion = autoCorrectEngine.correct(normalizedSource, previousWord)
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L
        if (elapsedMs > AUTOCORRECT_SLOW_LOG_THRESHOLD_MS) {
            Log.w(TAG, "Autocorrect took ${elapsedMs}ms")
        }
    }

    // Recognized words need no broad variant search. Explicit apostrophe typo
    // mappings above still apply (e.g. cant -> can't).
    if (suggestion == null && !isKnownWord(normalizedSource) && shouldRunDictionaryApostropheFallback(normalizedSource)) {
        suggestion = findBestDictionaryCorrection(normalizedSource, contextLanguage)
    }
    suggestion = suggestion?.let(::normalizeWord) ?: return null
    if (!isAutoCorrectionCandidateUsable(normalizedSource, suggestion)) {
        return null
    }
    if (isCorrectionSuppressed(normalizedSource, suggestion)) {
        return null
    }

    val correctedWord = applyWordCase(suggestion, sourceWord)
    if (correctedWord.equals(sourceWord, ignoreCase = true)) {
        return null
    }

    inputConnection.deleteSurroundingText(sourceWord.length, 0)
    inputConnection.commitText(correctedWord, 1)
    return AutoCorrectionResult(
        originalWord = sourceWord,
        correctedWord = correctedWord
    )
}

internal fun NboardImeService.lookupTypoCorrection(
    source: String,
    contextLanguage: KeyboardLanguageMode?
): String? {
    val normalized = normalizeWord(source)
    if (normalized.length < 2) {
        return null
    }

    val orderedMaps = when (keyboardLanguageMode) {
        KeyboardLanguageMode.FRENCH -> listOf(FRENCH_TYPOS)
        KeyboardLanguageMode.ENGLISH -> listOf(ENGLISH_TYPOS)
        KeyboardLanguageMode.BOTH -> when (contextLanguage) {
            KeyboardLanguageMode.FRENCH -> listOf(FRENCH_TYPOS, ENGLISH_TYPOS)
            KeyboardLanguageMode.ENGLISH -> listOf(ENGLISH_TYPOS, FRENCH_TYPOS)
            else -> listOf(ENGLISH_TYPOS, FRENCH_TYPOS)
        }
        KeyboardLanguageMode.DISABLED -> emptyList()
    }

    orderedMaps.forEach { map ->
        val candidate = map[normalized]?.let(::normalizeWord) ?: return@forEach
        if (candidate.contains(' ') || candidate.length < 2) {
            return@forEach
        }
        if (isKnownWord(normalized) && !shouldPreferApostropheTypoCandidate(normalized, candidate)) {
            return@forEach
        }
        if (!isAutoCorrectionCandidateUsable(normalized, candidate)) {
            return@forEach
        }
        if (!isCorrectionSuppressed(normalized, candidate)) {
            return candidate
        }
    }
    return null
}

internal fun NboardImeService.shouldPreferApostropheTypoCandidate(source: String, candidate: String): Boolean {
    val normalizedSource = normalizeWord(source)
    val normalizedCandidate = normalizeWord(candidate)
    if ('\'' in normalizedSource || '\'' !in normalizedCandidate) {
        return false
    }
    return normalizedCandidate.replace("'", "") == normalizedSource
}

internal fun NboardImeService.shouldRunDictionaryApostropheFallback(source: String): Boolean {
    val normalized = normalizeWord(source)
    if (normalized.length < 3) {
        return false
    }
    if (normalized.contains('\'')) {
        return true
    }
    if (ENGLISH_TYPOS.containsKey(normalized) || FRENCH_TYPOS.containsKey(normalized)) {
        return true
    }
    if (normalized.startsWith("qu") || normalized.startsWith("jusqu")) {
        return true
    }
    return normalized.firstOrNull() in FRENCH_ELISION_PREFIXES
}

internal fun NboardImeService.isAutoCorrectionCandidateUsable(source: String, candidate: String): Boolean {
    val normalizedSource = normalizeWord(source)
    val normalizedCandidate = normalizeWord(candidate)
    if (normalizedCandidate.length !in 2..24) {
        return false
    }
    if (!normalizedCandidate.any(Char::isLetter)) {
        return false
    }
    if (!normalizedCandidate.first().isLetter() || !normalizedCandidate.last().isLetter()) {
        return false
    }
    if (normalizedSource.length >= 4) {
        val prefix = commonPrefixLength(foldWord(normalizedSource), foldWord(normalizedCandidate))
        if (prefix == 0) {
            return false
        }
    }
    return true
}

internal fun NboardImeService.findBestDictionaryCorrection(
    source: String,
    contextLanguage: KeyboardLanguageMode?
): String? {
    val normalizedSource = normalizeWord(source)
    val foldedSource = foldWord(normalizedSource)
    if (foldedSource.length < 2) {
        return null
    }

    val variants = buildAutoCorrectionVariants(normalizedSource)
    if (variants.isEmpty()) {
        return null
    }

    var best: DictionaryCorrectionCandidate? = null
    var secondBest: DictionaryCorrectionCandidate? = null

    fun considerCandidate(candidate: DictionaryCorrectionCandidate) {
        if (isBetterDictionaryCandidate(candidate, best)) {
            if (best?.word != candidate.word) {
                secondBest = best
            }
            best = candidate
            return
        }
        if (best?.word == candidate.word) {
            return
        }
        if (isBetterDictionaryCandidate(candidate, secondBest)) {
            secondBest = candidate
        }
    }

    // Fast-path for repeated letter noise.
    findRepeatedLetterCorrection(normalizedSource, contextLanguage)?.let { quick ->
        if (!isCorrectionSuppressed(normalizedSource, quick)) {
            val foldedQuick = foldWord(quick)
            val rawDistance = levenshteinDistanceBounded(
                foldedSource,
                foldedQuick,
                computeRawDistanceLimit(normalizedSource.length, 2)
            )
            val normalizedRaw = if (rawDistance == Int.MAX_VALUE) 6 else rawDistance
            val candidate = DictionaryCorrectionCandidate(
                word = quick,
                score = 130 + normalizedRaw * 8 + quick.length,
                language = null,
                variantPenalty = 2,
                editDistance = normalizedRaw,
                prefixLength = commonPrefixLength(foldedSource, foldedQuick)
            )
            considerCandidate(candidate)
        }
    }

    listOf(
        KeyboardLanguageMode.FRENCH to frenchLexicon,
        KeyboardLanguageMode.ENGLISH to englishLexicon
    ).forEach { (language, lexicon) ->
        if (!isLanguageEnabled(language)) {
            return@forEach
        }
        val languagePenalty = languageBiasPenalty(language, contextLanguage)

        variants.forEach { variant ->
            val foldedVariant = foldWord(variant.word)
            if (foldedVariant.length < 2) {
                return@forEach
            }

            val exactWord = lexicon.foldedToWord[foldedVariant]
            if (!exactWord.isNullOrBlank() && !isCorrectionSuppressed(normalizedSource, exactWord)) {
                val rawDistance = levenshteinDistanceBounded(
                    foldedSource,
                    foldWord(exactWord),
                    computeRawDistanceLimit(normalizedSource.length, variant.penalty)
                )
                val normalizedRaw = if (rawDistance == Int.MAX_VALUE) 8 else rawDistance
                val score = languagePenalty * 80 +
                    variant.penalty * 20 +
                    normalizedRaw * 9 +
                    coreLexiconPenalty(language, exactWord)
                val candidate = DictionaryCorrectionCandidate(
                    word = exactWord,
                    score = score,
                    language = language,
                    variantPenalty = variant.penalty,
                    editDistance = normalizedRaw,
                    prefixLength = commonPrefixLength(foldedSource, foldWord(exactWord))
                )
                considerCandidate(candidate)
            }

            val bucket = lexicon.byFirst[foldedVariant.first()] ?: return@forEach
            val editLimit = computeVariantDistanceLimit(variant.word.length, variant.penalty)
            bucket.forEach { candidateWord ->
                if (isCorrectionSuppressed(normalizedSource, candidateWord)) {
                    return@forEach
                }

                val lengthGap = kotlin.math.abs(candidateWord.length - variant.word.length)
                if (lengthGap > editLimit + 1) {
                    return@forEach
                }

                val foldedCandidate = foldWord(candidateWord)
                val prefixLength = commonPrefixLength(foldedVariant, foldedCandidate)
                if (foldedVariant.length >= 6 && prefixLength < 1) {
                    return@forEach
                }
                if (foldedVariant.length >= 8 && prefixLength < 2) {
                    return@forEach
                }

                val variantDistance = levenshteinDistanceBounded(
                    foldedVariant,
                    foldedCandidate,
                    editLimit
                )
                if (variantDistance == Int.MAX_VALUE) {
                    return@forEach
                }

                val rawDistance = levenshteinDistanceBounded(
                    foldedSource,
                    foldedCandidate,
                    computeRawDistanceLimit(normalizedSource.length, variant.penalty)
                )
                val normalizedRawDistance = if (rawDistance == Int.MAX_VALUE) {
                    computeRawDistanceLimit(normalizedSource.length, variant.penalty) + 2
                } else {
                    rawDistance
                }

                val score = languagePenalty * 90 +
                    variant.penalty * 18 +
                    variantDistance * 17 +
                    normalizedRawDistance * 7 +
                    lengthGap * 4 -
                    prefixLength * 6 +
                    coreLexiconPenalty(language, candidateWord)

                val candidate = DictionaryCorrectionCandidate(
                    word = candidateWord,
                    score = score,
                    language = language,
                    variantPenalty = variant.penalty,
                    editDistance = variantDistance,
                    prefixLength = prefixLength
                )
                considerCandidate(candidate)
            }
        }
    }

    val topCandidate = best ?: return null
    if (!isDictionaryCandidateConfident(normalizedSource, topCandidate, secondBest)) {
        return null
    }

    val selected = topCandidate.word
    return if (selected.equals(normalizedSource, ignoreCase = true)) {
        null
    } else {
        selected
    }
}

internal fun NboardImeService.buildAutoCorrectionVariants(source: String): List<AutoCorrectionVariant> {
    val variants = linkedMapOf<String, Int>()

    fun addVariant(word: String, penalty: Int) {
        val normalized = normalizeWord(word)
        if (normalized.length < 2) {
            return
        }
        val existing = variants[normalized]
        if (existing == null || penalty < existing) {
            variants[normalized] = penalty
        }
    }

    addVariant(source, 0)

    val collapsedTwo = collapseRepeats(source, 2)
    val collapsedOne = collapseRepeats(source, 1)
    if (collapsedTwo != source) {
        addVariant(collapsedTwo, 1)
    }
    if (collapsedOne != source) {
        addVariant(collapsedOne, 2)
    }

    val firstPass = variants.entries.map { AutoCorrectionVariant(it.key, it.value) }
    firstPass.forEach { seed ->
        expandSuffixRepairVariants(seed.word, seed.penalty, ::addVariant)
        expandApostropheVariants(seed.word, seed.penalty, ::addVariant)
    }

    val secondPass = variants.entries.map { AutoCorrectionVariant(it.key, it.value) }
    secondPass.forEach { seed ->
        if (seed.penalty <= 3) {
            expandSuffixRepairVariants(seed.word, seed.penalty + 1, ::addVariant)
            expandApostropheVariants(seed.word, seed.penalty + 1, ::addVariant)
        }
    }

    return variants.entries
        .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
        .take(MAX_AUTOCORRECT_VARIANTS)
        .map { AutoCorrectionVariant(it.key, it.value) }
}

internal fun NboardImeService.expandApostropheVariants(
    source: String,
    penalty: Int,
    addVariant: (String, Int) -> Unit
) {
    if (source.length < 3) {
        return
    }
    if (source.contains('\'')) {
        val collapsedApostrophes = source.replace("''", "'")
        if (collapsedApostrophes != source) {
            addVariant(collapsedApostrophes, penalty)
        }
        val stripped = source.trim('\'')
        if (stripped.length >= 2 && stripped != source) {
            addVariant(stripped, penalty + 1)
        }
        val noApostrophe = source.replace("'", "")
        if (noApostrophe.length >= 2 && noApostrophe != source) {
            addVariant(noApostrophe, penalty + 1)
        }
        return
    }

    if (source.startsWith("qu") && source.length > 3) {
        addVariant("qu'${source.drop(2)}", penalty + 1)
    }
    if (source.startsWith("jusqu") && source.length > 6) {
        addVariant("jusqu'${source.drop(5)}", penalty + 1)
    }
    if (source.firstOrNull() in FRENCH_ELISION_PREFIXES && source.length > 2) {
        addVariant("${source.first()}'${source.drop(1)}", penalty + 1)
    }
}

internal fun NboardImeService.expandSuffixRepairVariants(
    source: String,
    penalty: Int,
    addVariant: (String, Int) -> Unit
) {
    if (source.length < 4) {
        return
    }
    if (source.endsWith("eauxe")) {
        addVariant(source.dropLast(5) + "eau", penalty)
    }
    if (source.endsWith("auxe")) {
        addVariant(source.dropLast(4) + "au", penalty)
    }
    if (source.endsWith("eaux")) {
        addVariant(source.dropLast(4) + "eau", penalty + 1)
    }
    if (source.endsWith("aux")) {
        addVariant(source.dropLast(3) + "au", penalty + 1)
    }
    if (source.endsWith("xe")) {
        addVariant(source.dropLast(2), penalty + 1)
    }
    if (source.endsWith("es") && source.length > 4) {
        addVariant(source.dropLast(2), penalty + 2)
    }
    if (source.endsWith("s") && source.length > 4) {
        addVariant(source.dropLast(1), penalty + 2)
    }
    if (source.endsWith("e") && source.length > 4) {
        addVariant(source.dropLast(1), penalty + 2)
    }
}

internal fun NboardImeService.computeVariantDistanceLimit(wordLength: Int, variantPenalty: Int): Int {
    val base = when {
        wordLength <= 4 -> 1
        wordLength <= 7 -> 2
        wordLength <= 10 -> 3
        else -> 4
    }
    val penaltyBonus = if (variantPenalty >= 2) 1 else 0
    return (base + penaltyBonus).coerceAtMost(5)
}

internal fun NboardImeService.computeRawDistanceLimit(wordLength: Int, variantPenalty: Int): Int {
    return (computeVariantDistanceLimit(wordLength, variantPenalty) + 2).coerceAtMost(8)
}

internal fun NboardImeService.coreLexiconPenalty(language: KeyboardLanguageMode, candidateWord: String): Int {
    return when (language) {
        KeyboardLanguageMode.FRENCH -> if (FRENCH_WORDS.contains(candidateWord)) -16 else 0
        KeyboardLanguageMode.ENGLISH -> if (ENGLISH_WORDS.contains(candidateWord)) -16 else 0
        else -> 0
    }
}

internal fun NboardImeService.isDictionaryCandidateConfident(
    source: String,
    best: DictionaryCorrectionCandidate,
    secondBest: DictionaryCorrectionCandidate?
): Boolean {
    val foldedSource = foldWord(source)
    val foldedBest = foldWord(best.word)

    val absoluteDistanceLimit = when {
        source.length <= 4 -> 1
        source.length <= 7 -> 2
        source.length <= 10 -> 3
        else -> 4
    } + if (best.variantPenalty >= 2) 1 else 0

    val absoluteDistance = levenshteinDistanceBounded(
        foldedSource,
        foldedBest,
        absoluteDistanceLimit.coerceAtMost(6)
    )
    if (absoluteDistance == Int.MAX_VALUE) {
        return false
    }

    val minPrefix = when {
        source.length <= 4 -> 1
        source.length <= 7 -> 2
        else -> 2
    }
    if (best.prefixLength < minPrefix && best.variantPenalty <= 1) {
        return false
    }

    val margin = if (secondBest == null) Int.MAX_VALUE else secondBest.score - best.score
    if (source.length <= 5 && margin < 8) {
        return false
    }
    if (source.length <= 7 && best.editDistance >= 2 && margin < 6) {
        return false
    }

    return true
}

internal fun NboardImeService.isBetterDictionaryCandidate(
    incoming: DictionaryCorrectionCandidate,
    current: DictionaryCorrectionCandidate?
): Boolean {
    if (current == null) {
        return true
    }
    if (incoming.score != current.score) {
        return incoming.score < current.score
    }
    if (incoming.variantPenalty != current.variantPenalty) {
        return incoming.variantPenalty < current.variantPenalty
    }
    if (incoming.editDistance != current.editDistance) {
        return incoming.editDistance < current.editDistance
    }
    if (incoming.prefixLength != current.prefixLength) {
        return incoming.prefixLength > current.prefixLength
    }
    if (incoming.word.length != current.word.length) {
        return incoming.word.length < current.word.length
    }
    return incoming.word < current.word
}

internal fun NboardImeService.detectContextLanguage(beforeCursor: String): KeyboardLanguageMode? {
    if (beforeCursor.isBlank()) {
        return null
    }

    val words = Regex("[\\p{L}’']+")
        .findAll(beforeCursor)
        .map { normalizeWord(it.value) }
        .toList()
        .takeLast(CONTEXT_LANGUAGE_WORD_WINDOW)

    if (words.isEmpty()) {
        return null
    }

    var frenchScore = 0
    var englishScore = 0
    words.forEach { word ->
        if (frenchLexicon.words.contains(word) || frenchLexicon.foldedWords.contains(foldWord(word)) || FRENCH_TYPOS.containsKey(word)) {
            frenchScore++
        }
        if (englishLexicon.words.contains(word) || englishLexicon.foldedWords.contains(foldWord(word)) || ENGLISH_TYPOS.containsKey(word)) {
            englishScore++
        }
    }

    return when {
        frenchScore >= englishScore + 1 -> KeyboardLanguageMode.FRENCH
        englishScore >= frenchScore + 1 -> KeyboardLanguageMode.ENGLISH
        keyboardLanguageMode == KeyboardLanguageMode.BOTH ||
            keyboardLanguageMode == KeyboardLanguageMode.DISABLED -> null
        else -> keyboardLanguageMode
    }
}

internal fun NboardImeService.isLanguageEnabled(language: KeyboardLanguageMode): Boolean {
    if (keyboardLanguageMode == KeyboardLanguageMode.DISABLED) {
        return false
    }
    return keyboardLanguageMode == KeyboardLanguageMode.BOTH || keyboardLanguageMode == language
}

internal fun NboardImeService.languageBiasPenalty(
    candidateLanguage: KeyboardLanguageMode,
    contextLanguage: KeyboardLanguageMode?
): Int {
    return when {
        keyboardLanguageMode == KeyboardLanguageMode.DISABLED -> 0
        keyboardLanguageMode == KeyboardLanguageMode.BOTH && contextLanguage == null -> 0
        contextLanguage != null && candidateLanguage == contextLanguage -> 0
        candidateLanguage == keyboardLanguageMode -> 1
        else -> 2
    }
}

internal fun NboardImeService.isKnownWord(word: String): Boolean {
    val folded = foldWord(word)
    if (keyboardLanguageMode != KeyboardLanguageMode.ENGLISH) {
        if (frenchLexicon.words.contains(word) || frenchLexicon.foldedWords.contains(folded)) {
            return true
        }
    }
    if (keyboardLanguageMode != KeyboardLanguageMode.FRENCH) {
        if (englishLexicon.words.contains(word) || englishLexicon.foldedWords.contains(folded)) {
            return true
        }
    }
    return false
}

internal fun NboardImeService.findRepeatedLetterCorrection(
    source: String,
    contextLanguage: KeyboardLanguageMode?
): String? {
    if (!hasRunAtLeast(source, 3)) {
        return null
    }

    val collapsedOne = collapseRepeats(source, 1)
    val collapsedTwo = collapseRepeats(source, 2)
    val orderedCandidates = linkedSetOf<String>()
    if (hasVowelRunAtLeast(source, 3)) {
        orderedCandidates.add(collapsedOne)
        orderedCandidates.add(collapsedTwo)
    } else {
        orderedCandidates.add(collapsedTwo)
        orderedCandidates.add(collapsedOne)
    }

    var bestWord: String? = null
    var bestScore = Int.MAX_VALUE
    listOf(
        KeyboardLanguageMode.FRENCH to frenchLexicon,
        KeyboardLanguageMode.ENGLISH to englishLexicon
    ).forEach { (language, lexicon) ->
        if (!isLanguageEnabled(language)) {
            return@forEach
        }
        orderedCandidates.forEach { candidate ->
            val foldedCandidate = foldWord(candidate)
            val word = lexicon.foldedToWord[foldedCandidate] ?: return@forEach
            if (isCorrectionSuppressed(source, word)) {
                return@forEach
            }
            val score = languageBiasPenalty(language, contextLanguage) * 10 + word.length
            if (score < bestScore) {
                bestScore = score
                bestWord = word
            }
        }
    }
    if (!bestWord.isNullOrBlank()) {
        return bestWord
    }

    // Conservative fallback when dictionaries do not contain the word yet.
    val fallback = if (hasVowelRunAtLeast(source, 3)) collapsedOne else collapsedTwo
    return if (
        fallback.length >= 3 &&
        fallback != source &&
        !isCorrectionSuppressed(source, fallback)
    ) {
        fallback
    } else {
        null
    }
}
