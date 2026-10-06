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
            val rankingSignals = signals.copy(hasConfidentPrefix = prefix.isNotBlank() && (source + request.words.keys + request.phrases.keys).any { word ->
                (dict.folded[word] ?: PredictionRanker.fold(word)).startsWith(prefix) &&
                    ((dict.frequencies[word] ?: 0) >= 1000 || (request.words[word] ?: 0) > 0)
            })
            for (word in (source + request.words.keys + request.phrases.keys).distinct()) {
                if (word == request.prefix.lowercase(Locale.US) || word.length > 24) continue
                if (language == "english" && word in DANISH_NOISE && word !in request.words) continue
                if (PredictionRanker.prefixDistance(prefix, dict.folded[word] ?: PredictionRanker.fold(word)) > 1) continue
                val c = PredictionRanker.score(word, dict.frequencies[word] ?: 1L, contextual[word] ?: 0L,
                    request, rankingSignals, dict.folded[word] ?: PredictionRanker.fold(word))
                if (c.score > (result[word]?.score ?: Double.NEGATIVE_INFINITY)) result[word] = c
            }
        }
        return result.values.sortedByDescending { it.score }.take(36)
    }

    fun refine(request: PredictionRequest, local: List<PredictionCandidate>, discoverWords: Boolean = true): List<PredictionCandidate> {
        // The downloaded model is English/Danish. French stays on the French dictionary.
        if (request.language != "ENGLISH" || request.context.isBlank()) return local
        if (!attemptedNeural) {
            attemptedNeural = true
            neural = try { NeuralPredictor(modelFile().absolutePath, dictionary("english").frequencies.keys.toList()) } catch (e: Exception) {
                Log.e("NboardPrediction", "Local model unavailable; dictionary remains active", e); null
            } catch (e: LinkageError) {
                Log.e("NboardPrediction", "Native runtime unavailable; dictionary remains active", e); null
            }
        }
        val scores = modelLikelihoods(request, local, discoverWords) ?: return local
        return combine(request, local, scores)
    }

    /** Model log-likelihood of each whole word after the context, or null without a model. */
    internal fun modelLikelihoods(request: PredictionRequest, local: List<PredictionCandidate>,
                                  discoverWords: Boolean = true): Map<String, Double>? {
        val model = neural ?: return null
        // Cased word-start models read capitals as signal; the original model is lowercase-only.
        val context = if (model.wordStart) request.context else request.context.lowercase(Locale.US)
        return model.score(context, request.prefix.lowercase(Locale.US), local.take(24).map { it.word }, discoverWords)
    }

    /**
     * How much of the dictionary frequency prior is replaced by the model, and the model's weight.
     * The model already conditions on the sentence, so a full unigram/bigram prior double-counts
     * frequency; next-word (empty prefix) and completion (typed prefix) are weighted separately.
     */
    internal data class BlendWeights(val priorDrop: Double, val likelihood: Double)

    private fun blendFor(request: PredictionRequest): BlendWeights {
        val general = neural?.wordStart == true
        return when {
            request.prefix.isBlank() -> if (general) GENERAL_MODEL_NEXT_WORD else KEYBOARD_MODEL_NEXT_WORD
            else -> if (general) GENERAL_MODEL_COMPLETION else KEYBOARD_MODEL_COMPLETION
        }
    }

    internal fun combine(request: PredictionRequest, local: List<PredictionCandidate>, scores: Map<String, Double>,
                         weights: BlendWeights = blendFor(request)): List<PredictionCandidate> {
        val dict = dictionary("english")
        val all = local.associateBy { it.word }.toMutableMap()
        val signals = PredictionRanker.signals(request)
        scores.forEach { (word, probability) ->
            if (word !in dict.frequencies && word !in request.words && word !in request.phrases) return@forEach
            if (word in DANISH_NOISE && word !in request.words) return@forEach
            if (word == request.prefix.lowercase(Locale.US)) return@forEach
            val candidate = all[word] ?: PredictionRanker.score(word, dict.frequencies[word] ?: 1,
                dict.bigrams[signals.previous]?.get(word) ?: 0, request, signals,
                dict.folded[word] ?: PredictionRanker.fold(word))
            // All candidates retain the same dictionary/personalization score; neural likelihood adds context.
            // A small model assigns very low likelihood to unfamiliar names.
            // Repeated user vocabulary supplies evidence the model never trained on.
            val likelihood = if ((request.words[word] ?: 0) >= 2) probability.coerceAtLeast(-8.0) else probability
            all[word] = candidate.copy(score = candidate.score - candidate.dictionaryPrior * weights.priorDrop + likelihood * weights.likelihood)
        }
        // Unscored tail candidates cannot outrank scored words merely by avoiding a negative log probability.
        val floor = (scores.values.minOrNull() ?: -16.0).coerceAtLeast(-25.0)
        return all.values.map {
            if (it.word in scores) it else it.copy(score = it.score - it.dictionaryPrior * weights.priorDrop + floor * weights.likelihood)
        }
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
        // Debug builds can evaluate another GGUF placed in private storage:
        //   adb shell "run-as com.nboard.ime sh -c 'mkdir -p files/models && cat > files/models/override.gguf'" < model.gguf
        if (com.nboard.ime.BuildConfig.DEBUG) {
            val override = File(context.filesDir, "models/override.gguf")
            if (override.isFile) { Log.i("NboardPrediction", "Using override model ${override.length()} bytes"); return override }
        }
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
    companion object {
        /**
         * Danish function words that the bilingual model predicts and the subtitle-derived
         * English list also contains ("og", "det"). Never English suggestions unless typed by the user.
         */
        private val DANISH_NOISE = setOf("og", "det", "er", "jeg", "ikke", "på", "har", "af", "som", "så",
            "hvad", "skal", "vil", "kan", "hun", "mig", "sig", "hende", "jer", "dem", "nu", "du", "ud", "fra",
            "hvor", "godt", "hej", "tak", "nej", "ja", "være", "blive", "også", "meget", "noget", "ingen")
        const val MODEL_SHA = "47b3a97a80ab96df2c7148b8bb155f471d0fe74bffed392c0c4ea220d36ea5f8"
        // Fitted per model on held-out conversational text (tools/nextword-eval/fit_blend.py, dev split).
        internal val KEYBOARD_MODEL_NEXT_WORD = BlendWeights(priorDrop = 1.0, likelihood = .5)
        internal val KEYBOARD_MODEL_COMPLETION = BlendWeights(priorDrop = .8, likelihood = 2.5)
        internal val GENERAL_MODEL_NEXT_WORD = BlendWeights(priorDrop = .9, likelihood = 1.0)
        internal val GENERAL_MODEL_COMPLETION = BlendWeights(priorDrop = .65, likelihood = 4.0)
    }
}

internal class NeuralPredictor(path: String, vocabulary: List<String> = emptyList()) : AutoCloseable {
    private var handle: Long = nativeOpen(path, vocabulary.toTypedArray())
    init { check(handle != 0L) { "Could not load local prediction model" } }
    /** Word-start tokenizer (" word"): cased general model. Otherwise the original lowercase keyboard model. */
    val wordStart: Boolean = nativeWordStart(handle)
    fun score(context: String, prefix: String, candidates: List<String>, discoverWords: Boolean = true): Map<String, Double> {
        val output = nativeScore(handle, context, prefix, candidates.toTypedArray(), discoverWords)
        return output.toList().chunked(2).associate { it[0] to it[1].toDouble() }
    }
    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0L } }
    private external fun nativeOpen(path: String, vocabulary: Array<String>): Long
    private external fun nativeScore(handle: Long, context: String, prefix: String, candidates: Array<String>, discoverWords: Boolean): Array<String>
    private external fun nativeClose(handle: Long)
    private external fun nativeWordStart(handle: Long): Boolean
    companion object { init { System.loadLibrary("nboard_prediction") } }
}
