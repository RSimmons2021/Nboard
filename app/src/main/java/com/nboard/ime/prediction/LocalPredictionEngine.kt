package com.nboard.ime.prediction

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Dictionary worker initializes immutable dictionaries; one neural worker owns the model. No editor/view access. */
internal class LocalPredictionEngine(private val context: Context) : AutoCloseable {
    private data class Dictionary(val frequencies: Map<String, Long>, val folded: Map<String, String>, val byFirst: Map<Char, List<String>>,
                                  val sortedWords: List<String>,
                                  val bigrams: Map<String, Map<String, Long>>)
    private var english: Dictionary? = null
    private var french: Dictionary? = null
    private var neural: NeuralPredictor? = null
    private var attemptedNeural = false

    private fun dictionary(language: String): Dictionary {
        val existing = if (language == "english") english else french
        if (existing != null) return existing
        val words = linkedMapOf<String, Long>()
        context.assets.open("dictionaries/${language}_50k.txt").bufferedReader().useLines { lines ->
            lines.forEach { line -> val p = line.trim().split(' '); if (p.size == 2) words[p[0]] = p[1].toLongOrNull() ?: 1 }
        }
        val bigrams = mutableMapOf<String, MutableMap<String, Long>>()
        context.assets.open("dictionaries/${language}_bigrams.txt").bufferedReader().useLines { lines ->
            lines.forEach { line -> val p = line.trim().split(' '); if (p.size == 3)
                bigrams.getOrPut(p[0]) { mutableMapOf() }[p[1]] = p[2].toLongOrNull() ?: 1 }
        }
        val folded = words.keys.associateWith { PredictionRanker.fold(it) }
        return Dictionary(words, folded, words.keys.groupBy { folded[it]?.firstOrNull() ?: ' ' },
            words.keys.sortedBy { folded[it] }, bigrams).also {
            if (language == "english") english = it else french = it
        }
    }

    fun candidates(request: PredictionRequest): List<PredictionCandidate> {
        val languages = when (request.language) { "FRENCH" -> listOf("french"); "ENGLISH" -> listOf("english"); else -> listOf("english", "french") }
        val prefix = PredictionRanker.fold(request.prefix)
        if (prefix.length > 24) return emptyList()
        val signals = PredictionRanker.signals(request)
        val previous = signals.previous
        val result = mutableMapOf<String, PredictionCandidate>()
        for (language in languages) {
            val dict = dictionary(language)
            val contextual = dict.bigrams[previous].orEmpty()
            val source = when {
                prefix.isBlank() -> dict.frequencies.keys.take(150) + contextual.keys
                prefix.length < 3 -> dict.byFirst[prefix.first()].orEmpty().filter { dict.folded[it]!!.startsWith(prefix) }.take(256)
                else -> prefixCandidates(dict, prefix)
            }
            val rankingSignals = signals.copy(hasConfidentPrefix = prefix.isNotBlank() && (source + request.words.keys).any { word ->
                (dict.folded[word] ?: PredictionRanker.fold(word)).startsWith(prefix) &&
                    ((dict.frequencies[word] ?: 0) >= 1000 || (request.words[word] ?: 0) > 0)
            })
            for (word in (source + request.words.keys).distinct()) {
                if (word == request.prefix.lowercase(Locale.US) || word.length > 24) continue
                if (PredictionRanker.prefixDistance(prefix, dict.folded[word] ?: PredictionRanker.fold(word)) > 1) continue
                val c = PredictionRanker.score(word, dict.frequencies[word] ?: 1L, contextual[word] ?: 0L,
                    request, rankingSignals, dict.folded[word] ?: PredictionRanker.fold(word))
                if (c.score > (result[word]?.score ?: Double.NEGATIVE_INFINITY)) result[word] = c
            }
        }
        return result.values.sortedByDescending { it.score }.take(36)
    }

    fun refine(request: PredictionRequest, local: List<PredictionCandidate>): List<PredictionCandidate> {
        // The downloaded model is English/Danish. French stays on the French dictionary.
        if (request.language != "ENGLISH" || request.context.isBlank()) return local
        if (!attemptedNeural) {
            attemptedNeural = true
            neural = try { NeuralPredictor(modelFile().absolutePath) } catch (e: Exception) {
                Log.e("NboardPrediction", "Local model unavailable; dictionary remains active", e); null
            } catch (e: LinkageError) {
                Log.e("NboardPrediction", "Native runtime unavailable; dictionary remains active", e); null
            }
        }
        val model = neural ?: return local
        val scores = model.score(request.context.lowercase(Locale.US), request.prefix.lowercase(Locale.US), local.take(24).map { it.word })
        val dict = dictionary("english")
        val all = local.associateBy { it.word }.toMutableMap()
        val signals = PredictionRanker.signals(request)
        scores.forEach { (word, probability) ->
            if (word !in dict.frequencies && word !in request.words) return@forEach
            if (word == request.prefix.lowercase(Locale.US)) return@forEach
            val candidate = all[word] ?: PredictionRanker.score(word, dict.frequencies[word] ?: 1,
                dict.bigrams[signals.previous]?.get(word) ?: 0, request, signals,
                dict.folded[word] ?: PredictionRanker.fold(word))
            // All candidates retain the same dictionary/personalization score; neural likelihood adds context.
            // A small model assigns very low likelihood to unfamiliar names.
            // Repeated user vocabulary supplies evidence the model never trained on.
            val likelihood = if ((request.words[word] ?: 0) >= 2) probability.coerceAtLeast(-8.0) else probability
            all[word] = candidate.copy(score = candidate.score - candidate.dictionaryPrior * .65 + likelihood * 1.25)
        }
        // Unscored tail candidates cannot outrank scored words merely by avoiding a negative log probability.
        val floor = (scores.values.minOrNull() ?: -16.0).coerceAtLeast(-25.0)
        return all.values.map { if (it.word in scores) it else it.copy(score = it.score - it.dictionaryPrior * .65 + floor * 1.25) }
    }

    /** Binary prefix lookup avoids scanning thousands of words for every typo. */
    private fun prefixCandidates(dict: Dictionary, prefix: String): List<String> {
        val result = linkedSetOf<String>()
        fun collect(value: String, limit: Int) {
            var low = 0
            var high = dict.sortedWords.size
            while (low < high) {
                val middle = (low+high) ushr 1
                if (dict.folded[dict.sortedWords[middle]]!! < value) low = middle+1 else high = middle
            }
            var count = 0
            while (low < dict.sortedWords.size && count < limit) {
                val word = dict.sortedWords[low++]
                if (!dict.folded[word]!!.startsWith(value)) break
                result += word; count++
            }
        }
        collect(prefix, 256)
        val variants = linkedSetOf<String>()
        for (i in prefix.indices) {
            variants += prefix.removeRange(i, i+1)
            if (i+1 < prefix.length) variants += prefix.substring(0,i)+prefix[i+1]+prefix[i]+prefix.substring(i+2)
            for (c in 'a'..'z') if (c != prefix[i]) variants += prefix.substring(0,i)+c+prefix.substring(i+1)
        }
        for (i in 0..prefix.length) for (c in 'a'..'z') variants += prefix.substring(0,i)+c+prefix.substring(i)
        variants.forEach { collect(it, 12) }
        return result.toList()
    }

    private fun modelFile(): File {
        val target = File(context.noBackupFilesDir, "models/daen-xbu-q6_k.gguf")
        target.parentFile!!.mkdirs()
        if (target.isFile && target.length() == 54_763_904L) return target
        val temporary = File(target.parentFile, "model.download")
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("models/daen-xbu-q6_k.gguf").use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size); output.write(buffer, 0, size) }
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA) { "Prediction model checksum mismatch" }
        check(temporary.renameTo(target)) { "Could not install prediction model" }
        return target
    }

    override fun close() { neural?.close(); neural = null }
    companion object { const val MODEL_SHA = "47b3a97a80ab96df2c7148b8bb155f471d0fe74bffed392c0c4ea220d36ea5f8" }
}

internal class NeuralPredictor(path: String) : AutoCloseable {
    private var handle: Long = nativeOpen(path)
    init { check(handle != 0L) { "Could not load local prediction model" } }
    fun score(context: String, prefix: String, candidates: List<String>): Map<String, Double> {
        val output = nativeScore(handle, context, prefix, candidates.toTypedArray())
        return output.toList().chunked(2).associate { it[0] to it[1].toDouble() }
    }
    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0L } }
    private external fun nativeOpen(path: String): Long
    private external fun nativeScore(handle: Long, context: String, prefix: String, candidates: Array<String>): Array<String>
    private external fun nativeClose(handle: Long)
    companion object { init { System.loadLibrary("nboard_prediction") } }
}
