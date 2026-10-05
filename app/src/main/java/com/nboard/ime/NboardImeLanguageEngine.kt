package com.nboard.ime

import android.content.Context
import android.view.inputmethod.InputConnection
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

internal fun NboardImeService.collapseRepeats(value: String, maxRepeat: Int): String {
    if (value.isBlank()) {
        return value
    }
    val builder = StringBuilder(value.length)
    var previous: Char? = null
    var count = 0
    value.forEach { char ->
        if (char == previous) {
            count++
        } else {
            previous = char
            count = 1
        }
        if (count <= maxRepeat) {
            builder.append(char)
        }
    }
    return builder.toString()
}

internal fun NboardImeService.hasRunAtLeast(value: String, size: Int): Boolean {
    if (value.isBlank()) {
        return false
    }
    var previous: Char? = null
    var count = 0
    value.forEach { char ->
        if (char == previous) {
            count++
        } else {
            previous = char
            count = 1
        }
        if (count >= size) {
            return true
        }
    }
    return false
}

internal fun NboardImeService.hasVowelRunAtLeast(value: String, size: Int): Boolean {
    if (value.isBlank()) {
        return false
    }
    var previous: Char? = null
    var count = 0
    value.forEach { char ->
        if (!isVowel(char)) {
            previous = null
            count = 0
            return@forEach
        }
        if (char == previous) {
            count++
        } else {
            previous = char
            count = 1
        }
        if (count >= size) {
            return true
        }
    }
    return false
}

internal fun NboardImeService.isVowel(char: Char): Boolean {
    val base = foldWord(char.toString())
    return base.firstOrNull() in VOWELS_FOR_REPEAT
}

internal fun NboardImeService.foldWord(word: String): String {
    val normalized = Normalizer.normalize(word, Normalizer.Form.NFD)
    return DIACRITIC_REGEX.replace(normalized, "")
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('ʼ', '\'')
        .replace('`', '\'')
        .replace('´', '\'')
        .replace('‛', '\'')
        .replace('＇', '\'')
        .lowercase(Locale.US)
}

internal fun NboardImeService.correctionPairKey(source: String, corrected: String): String {
    return "$source->$corrected"
}

internal fun NboardImeService.isCorrectionSuppressed(source: String, corrected: String): Boolean {
    val key = correctionPairKey(normalizeWord(source), normalizeWord(corrected))
    val count = rejectedCorrections[key] ?: 0
    return count >= AUTOCORRECT_REVERT_DISABLE_THRESHOLD
}

internal fun NboardImeService.recordRejectedCorrection(source: String, corrected: String) {
    val key = correctionPairKey(normalizeWord(source), normalizeWord(corrected))
    val next = (rejectedCorrections[key] ?: 0) + 1
    rejectedCorrections[key] = next
    val normalized = normalizeWord(corrected)
    learnedWordFrequency[normalized]?.let { learnedWordFrequency[normalized] = (it - 3).coerceAtLeast(0) }
    learningDirtyUpdates++
    saveRejectedCorrections()
}

internal fun NboardImeService.loadRejectedCorrections() {
    val prefs = getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_AUTOCORRECT_REJECTED_JSON, null) ?: return
    try {
        val json = JSONObject(raw)
        rejectedCorrections.clear()
        json.keys().forEach { key ->
            val value = json.optInt(key, 0)
            if (value > 0) {
                rejectedCorrections[key] = value
            }
        }
    } catch (_: Exception) {
        rejectedCorrections.clear()
    }
}

internal fun NboardImeService.saveRejectedCorrections() {
    val json = JSONObject().apply {
        rejectedCorrections.forEach { (key, value) ->
            if (value > 0) {
                put(key, value)
            }
        }
    }

    getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_AUTOCORRECT_REJECTED_JSON, json.toString())
        .apply()
}

internal fun NboardImeService.loadPredictionLearning() {
    val prefs = getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE)
    learnedWordFrequency.clear()
    learnedBigramFrequency.clear()
    learnedTrigramFrequency.clear()

    val wordsRaw = prefs.getString(KEY_LEARNED_WORD_COUNTS_JSON, null)
    if (!wordsRaw.isNullOrBlank()) {
        try {
            val json = JSONObject(wordsRaw)
            json.keys().forEach { key ->
                val count = json.optInt(key, 0)
                val normalized = normalizeWord(key)
                if (count > 0 && normalized.length >= 2) {
                    learnedWordFrequency[normalized] = count
                }
            }
        } catch (_: Exception) {
            learnedWordFrequency.clear()
        }
    }

    val bigramsRaw = prefs.getString(KEY_LEARNED_BIGRAM_COUNTS_JSON, null)
    if (!bigramsRaw.isNullOrBlank()) {
        try {
            val json = JSONObject(bigramsRaw)
            json.keys().forEach { key ->
                val count = json.optInt(key, 0)
                if (count <= 0 || !key.contains('|')) {
                    return@forEach
                }
                val previous = key.substringBefore('|')
                val current = key.substringAfter('|')
                val normalizedPrevious = normalizeWord(previous)
                val normalizedCurrent = normalizeWord(current)
                if (normalizedPrevious.length >= 1 && normalizedCurrent.length >= 2) {
                    learnedBigramFrequency[predictionBigramKey(normalizedPrevious, normalizedCurrent)] = count
                }
            }
        } catch (_: Exception) {
            learnedBigramFrequency.clear()
        }
    }

    val trigramsRaw = prefs.getString(KEY_LEARNED_TRIGRAM_COUNTS_JSON, null)
    if (!trigramsRaw.isNullOrBlank()) {
        try {
            val json = JSONObject(trigramsRaw)
            json.keys().forEach { key ->
                val count = json.optInt(key, 0)
                if (count <= 0) {
                    return@forEach
                }
                val parts = key.split('|')
                if (parts.size != 3) {
                    return@forEach
                }
                val previous2 = normalizeWord(parts[0])
                val previous1 = normalizeWord(parts[1])
                val current = normalizeWord(parts[2])
                if (previous2.length >= 1 && previous1.length >= 1 && current.length >= 2) {
                    learnedTrigramFrequency[predictionTrigramKey(previous2, previous1, current)] = count
                }
            }
        } catch (_: Exception) {
            learnedTrigramFrequency.clear()
        }
    }
    learnedWordLastUsed.clear()
    runCatching {
        val times = JSONObject(prefs.getString("learned_word_last_used_json", "{}") ?: "{}")
        times.keys().forEach { word -> if (word in learnedWordFrequency) learnedWordLastUsed[word] = times.optLong(word) }
    }
    learningDirtyUpdates = 0
    trimLearnedPredictionsIfNeeded(force = true)
}

internal fun NboardImeService.savePredictionLearning(force: Boolean = false) {
    if (!force && learningDirtyUpdates <= 0) {
        return
    }
    trimLearnedPredictionsIfNeeded(force = true)
    val wordsJson = JSONObject().apply {
        learnedWordFrequency.forEach { (word, count) ->
            if (count > 0) {
                put(word, count)
            }
        }
    }
    val bigramsJson = JSONObject().apply {
        learnedBigramFrequency.forEach { (key, count) ->
            if (count > 0) {
                put(key, count)
            }
        }
    }
    val trigramsJson = JSONObject().apply {
        learnedTrigramFrequency.forEach { (key, count) ->
            if (count > 0) {
                put(key, count)
            }
        }
    }
    getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_LEARNED_WORD_COUNTS_JSON, wordsJson.toString())
        .putString("learned_word_last_used_json", JSONObject(learnedWordLastUsed.toMap()).toString())
        .putString(KEY_LEARNED_BIGRAM_COUNTS_JSON, bigramsJson.toString())
        .putString(KEY_LEARNED_TRIGRAM_COUNTS_JSON, trigramsJson.toString())
        .apply()
    learningDirtyUpdates = 0
}

internal fun NboardImeService.learnPredictionFromContext(inputConnection: InputConnection) {
    if (!smartTypingBehavior.shouldPersonalize()) return
    val beforeCursor = inputConnection
        .getTextBeforeCursor(LEARNING_CONTEXT_WINDOW, 0)
        ?.toString()
        .orEmpty()
    val tokens = extractNormalizedWordTokens(beforeCursor)
    if (tokens.isEmpty()) {
        return
    }
    val current = tokens.last()
    if (current.length < 2) {
        return
    }

    if (tokens.size < 2) incrementLearnedWord(current, 1)
    if (tokens.size >= 2) {
        val previous = tokens[tokens.lastIndex - 1]
        recordLearnedTransition(previous, current, boost = 1)
    }
    if (tokens.size >= 3) {
        val previous2 = tokens[tokens.lastIndex - 2]
        val previous1 = tokens[tokens.lastIndex - 1]
        recordLearnedTrigram(previous2, previous1, current, boost = 1)
    }
    persistPredictionLearningIfNeeded()
}

internal fun NboardImeService.recordLearnedTransition(previous: String?, current: String, boost: Int) {
    if (!smartTypingBehavior.shouldPersonalize()) return
    val normalizedCurrent = normalizeWord(current)
    if (normalizedCurrent.length < 2) {
        return
    }
    incrementLearnedWord(normalizedCurrent, boost)
    val normalizedPrevious = previous?.let(::normalizeWord)
    if (!normalizedPrevious.isNullOrBlank() && normalizedPrevious.length >= 1) {
        val key = predictionBigramKey(normalizedPrevious, normalizedCurrent)
        val next = (learnedBigramFrequency[key] ?: 0) + boost
        learnedBigramFrequency[key] = next.coerceAtMost(MAX_LEARNING_COUNT)
        learningDirtyUpdates++
    }
    persistPredictionLearningIfNeeded()
}

internal fun NboardImeService.recordLearnedTrigram(previous2: String?, previous1: String?, current: String, boost: Int) {
    if (!smartTypingBehavior.shouldPersonalize()) return
    val normalizedCurrent = normalizeWord(current)
    if (normalizedCurrent.length < 2) {
        return
    }
    val normalizedPrevious2 = previous2?.let(::normalizeWord)
    val normalizedPrevious1 = previous1?.let(::normalizeWord)
    if (normalizedPrevious2.isNullOrBlank() || normalizedPrevious1.isNullOrBlank()) {
        return
    }
    if (normalizedPrevious2.length < 1 || normalizedPrevious1.length < 1) {
        return
    }

    val key = predictionTrigramKey(normalizedPrevious2, normalizedPrevious1, normalizedCurrent)
    val next = (learnedTrigramFrequency[key] ?: 0) + boost
    learnedTrigramFrequency[key] = next.coerceAtMost(MAX_LEARNING_COUNT)
    learningDirtyUpdates++
    persistPredictionLearningIfNeeded()
}

internal fun NboardImeService.incrementLearnedWord(word: String, delta: Int) {
    if (!smartTypingBehavior.shouldPersonalize()) return
    val normalized = normalizeWord(word)
    if (normalized.length < 2) {
        return
    }
    val next = (learnedWordFrequency[normalized] ?: 0) + delta
    learnedWordFrequency[normalized] = next.coerceIn(0, MAX_LEARNING_COUNT)
    learnedWordLastUsed[normalized] = System.currentTimeMillis()
    learningDirtyUpdates++
}

internal fun NboardImeService.persistPredictionLearningIfNeeded() {
    trimLearnedPredictionsIfNeeded(force = false)
    if (learningDirtyUpdates >= LEARNING_SAVE_BATCH_SIZE) {
        savePredictionLearning(force = false)
    }
}

internal fun NboardImeService.trimLearnedPredictionsIfNeeded(force: Boolean) {
    if (force || learnedWordFrequency.size > MAX_LEARNED_WORDS + LEARNED_TRIM_MARGIN) {
        val topWords = learnedWordFrequency.entries
            .sortedByDescending { it.value }
            .take(MAX_LEARNED_WORDS)
            .associate { it.key to it.value }
        learnedWordFrequency.clear()
        learnedWordFrequency.putAll(topWords)
        learnedWordLastUsed.keys.retainAll(topWords.keys)
    }
    if (force || learnedBigramFrequency.size > MAX_LEARNED_BIGRAMS + LEARNED_TRIM_MARGIN) {
        val topBigrams = learnedBigramFrequency.entries
            .sortedByDescending { it.value }
            .take(MAX_LEARNED_BIGRAMS)
            .associate { it.key to it.value }
        learnedBigramFrequency.clear()
        learnedBigramFrequency.putAll(topBigrams)
    }
    if (force || learnedTrigramFrequency.size > MAX_LEARNED_TRIGRAMS + LEARNED_TRIM_MARGIN) {
        val topTrigrams = learnedTrigramFrequency.entries
            .sortedByDescending { it.value }
            .take(MAX_LEARNED_TRIGRAMS)
            .associate { it.key to it.value }
        learnedTrigramFrequency.clear()
        learnedTrigramFrequency.putAll(topTrigrams)
    }
}

internal fun NboardImeService.extractNormalizedWordTokens(value: String): List<String> {
    if (value.isBlank()) {
        return emptyList()
    }
    val tokens = WORD_TOKEN_REGEX
        .findAll(value)
        .map { normalizeWord(it.value).trim('\'') }
        .filter { it.length in 1..24 }
        .toList()
    return if (tokens.size <= LEARNING_TOKEN_WINDOW) {
        tokens
    } else {
        tokens.takeLast(LEARNING_TOKEN_WINDOW)
    }
}

internal fun NboardImeService.extractPredictionTokens(value: String): List<String> {
    if (value.isBlank()) {
        return emptyList()
    }
    val tokens = WORD_TOKEN_REGEX
        .findAll(value)
        .map { normalizeWord(it.value).trim('\'') }
        .filter { it.length in 1..24 }
        .toList()
    return if (tokens.size <= PREDICTION_TOKEN_WINDOW) {
        tokens
    } else {
        tokens.takeLast(PREDICTION_TOKEN_WINDOW)
    }
}

internal fun NboardImeService.predictionBigramKey(previous: String, current: String): String {
    return "${normalizeWord(previous)}|${normalizeWord(current)}"
}

internal fun NboardImeService.predictionTrigramKey(previous2: String, previous1: String, current: String): String {
    return "${normalizeWord(previous2)}|${normalizeWord(previous1)}|${normalizeWord(current)}"
}

internal fun NboardImeService.commonPrefixLength(source: String, target: String): Int {
    val max = minOf(source.length, target.length)
    var index = 0
    while (index < max && source[index] == target[index]) {
        index++
    }
    return index
}

internal fun NboardImeService.extractTrailingWord(value: String): String? {
    if (value.isBlank()) {
        return null
    }
    var end = value.length - 1
    while (end >= 0 && !isWordChar(value[end])) {
        end--
    }
    if (end < 0) {
        return null
    }

    var start = end
    while (start >= 0 && isWordChar(value[start])) {
        start--
    }
    return value.substring(start + 1, end + 1)
}

internal fun NboardImeService.extractPreviousWordForAutoCorrection(beforeCursor: String, sourceWord: String): String? {
    if (beforeCursor.isBlank() || sourceWord.isBlank()) {
        return null
    }
    if (beforeCursor.length < sourceWord.length) {
        return null
    }
    val withoutCurrent = beforeCursor.dropLast(sourceWord.length)
    return extractTrailingWord(withoutCurrent)
}

internal fun NboardImeService.extractCurrentWordFragment(value: String): String {
    if (value.isBlank()) {
        return ""
    }
    val end = value.length - 1
    if (!isWordChar(value[end])) {
        return ""
    }
    var start = end
    while (start >= 0 && isWordChar(value[start])) {
        start--
    }
    return value.substring(start + 1, end + 1)
}

internal fun NboardImeService.isWordChar(char: Char): Boolean {
    return char.isLetter() || char in APOSTROPHE_CHARS
}

internal fun NboardImeService.normalizeWord(word: String): String {
    return word.lowercase(Locale.US)
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('ʼ', '\'')
        .replace('`', '\'')
        .replace('´', '\'')
        .replace('‛', '\'')
        .replace('＇', '\'')
}

internal fun NboardImeService.applyWordCase(base: String, source: String): String {
    if (source.isEmpty()) {
        return base
    }
    return when {
        source.all { !it.isLetter() || it.isUpperCase() } -> base.uppercase(Locale.US)
        source.first().isUpperCase() -> base.replaceFirstChar { it.uppercase(Locale.US) }
        else -> base
    }
}

internal fun NboardImeService.levenshteinDistanceBounded(source: String, target: String, limit: Int): Int {
    if (kotlin.math.abs(source.length - target.length) > limit) {
        return Int.MAX_VALUE
    }

    val prev = IntArray(target.length + 1) { it }
    val curr = IntArray(target.length + 1)

    for (i in 1..source.length) {
        curr[0] = i
        var rowBest = curr[0]
        for (j in 1..target.length) {
            val cost = if (source[i - 1] == target[j - 1]) 0 else 1
            curr[j] = minOf(
                prev[j] + 1,
                curr[j - 1] + 1,
                prev[j - 1] + cost
            )
            if (curr[j] < rowBest) {
                rowBest = curr[j]
            }
        }
        if (rowBest > limit) {
            return Int.MAX_VALUE
        }
        for (j in prev.indices) {
            prev[j] = curr[j]
        }
    }

    return prev[target.length]
}
