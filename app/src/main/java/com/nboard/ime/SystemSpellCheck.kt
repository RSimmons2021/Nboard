package com.nboard.ime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone's system spell checker (Gboard's on most Android phones) judging one typed word.
 * [recommended] is the checker's own "confident enough to replace" signal.
 */
internal data class SpellVerdict(val looksLikeTypo: Boolean, val recommended: Boolean, val suggestions: List<String>)

/**
 * Nboard's own correction for [typed], checked against the system spell checker's [verdict].
 * Replaying SpellCorrectionBenchmark through Gboard's checker on a Nothing A024 showed its top
 * suggestion alone is wrong ~30% of the time, but two narrow uses beat Nboard alone everywhere:
 *  - a word the checker accepts (a name, slang, a rarer real word) is left alone;
 *  - when Nboard has nothing, the checker's top suggestion is taken only if it is one edit
 *    away and a common word ([isCommonWord]).
 */
internal fun combineWithSystemSpellCheck(
    typed: String,
    ownSuggestion: String?,
    verdict: SpellVerdict,
    isCommonWord: (String) -> Boolean
): String? {
    if (!verdict.looksLikeTypo) return null
    if (ownSuggestion != null) return ownSuggestion
    val top = verdict.suggestions.firstOrNull() ?: return null
    // A capitalised suggestion is a name or brand; a lower-case typed word rarely means one.
    if (top != top.lowercase() || top.any { !it.isLetter() && it != '\'' }) return null
    return top.takeIf { editDistanceWithSwaps(typed, it) <= 1 && isCommonWord(it) }
}

/** Edit distance counting an adjacent swap ("teh" -> "the") as one edit. */
internal fun editDistanceWithSwaps(a: String, b: String): Int {
    val rows = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) rows[i][0] = i
    for (j in 0..b.length) rows[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        var best = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1, rows[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, rows[i - 2][j - 2] + 1)
        rows[i][j] = best
    }
    return rows[a.length][b.length]
}

/**
 * Asks the system spell checker about the word being typed while it is typed, so an answer is
 * usually ready by the time Space is pressed. The checker only proposes; Nboard decides whether
 * to replace. Requests are serialized (one in flight, newest word queued) and answers cached.
 *
 * Call [prefetch], [verdict] and [close] from the main thread.
 */
internal class SystemSpellCheck(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "nboard-spellcheck").apply { isDaemon = true } }
    private val lock = Object()
    private val verdicts = object : LinkedHashMap<String, SpellVerdict>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpellVerdict>) = size > CACHE_SIZE
    }
    private var session: SpellCheckerSession? = null
    private var sessionLocale: Locale? = null
    private var unavailable = false
    private var inFlight: String? = null
    private var inFlightSinceMs = 0L
    private var queued: String? = null

    /** Milliseconds the last answer took; -1 before the first. Debug and benchmark use. */
    @Volatile var lastLatencyMs = -1L
        private set

    fun prefetch(word: String, locale: Locale) {
        val key = word.lowercase(locale)
        if (key.length < MIN_WORD_LENGTH || key.length > MAX_WORD_LENGTH) return
        synchronized(lock) {
            if (key in verdicts || key == inFlight) return
            val active = ensureSession(locale) ?: return
            if (inFlight != null && SystemClock.uptimeMillis() - inFlightSinceMs < STALE_REQUEST_MS) {
                queued = key
                return
            }
            send(active, key)
        }
    }

    /**
     * The cached answer for [word]. When it is still being checked, waits up to [waitMs] for it.
     * Waiting needs the executor-based session (Android 12+); older versions answer on the main thread.
     */
    fun verdict(word: String, locale: Locale, waitMs: Long = 0L): SpellVerdict? {
        val key = word.lowercase(locale)
        synchronized(lock) {
            if (sessionLocale != locale) return null
            verdicts[key]?.let { return it }
            if (waitMs <= 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            if (key != inFlight && key != queued) return null
            val deadline = SystemClock.uptimeMillis() + waitMs
            while (true) {
                verdicts[key]?.let { return it }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L || (key != inFlight && key != queued)) return null
                lock.wait(remaining)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            session?.close()
            session = null
            sessionLocale = null
            inFlight = null
            queued = null
            verdicts.clear()
        }
        executor.shutdown()
    }

    private fun ensureSession(locale: Locale): SpellCheckerSession? {
        if (unavailable) return null
        if (sessionLocale == locale) session?.let { return it }
        session?.close()
        session = null
        verdicts.clear()
        inFlight = null
        queued = null
        val manager = context.getSystemService(TextServicesManager::class.java)
        if (manager == null || !manager.isSpellCheckerEnabled) return null
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) =
                onAnswer(results?.firstOrNull())
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) =
                onAnswer(results?.firstOrNull()?.takeIf { it.suggestionsCount > 0 }?.getSuggestionsInfoAt(0))
        }
        val created = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val params = SpellCheckerSession.SpellCheckerSessionParams.Builder()
                    .setLocale(locale)
                    .setSupportedAttributes(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY or
                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                        SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS)
                    .build()
                manager.newSpellCheckerSession(params, executor, listener)
            } else {
                @Suppress("DEPRECATION")
                manager.newSpellCheckerSession(null, locale, listener, false)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "System spell checker unavailable", error)
            null
        }
        if (created == null) {
            unavailable = true
            return null
        }
        session = created
        sessionLocale = locale
        return created
    }

    private fun send(active: SpellCheckerSession, key: String) {
        inFlight = key
        inFlightSinceMs = SystemClock.uptimeMillis()
        queued = null
        try {
            active.getSentenceSuggestions(arrayOf(TextInfo(key)), MAX_SUGGESTIONS)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Spell check request failed", error)
            inFlight = null
        }
    }

    private fun onAnswer(info: SuggestionsInfo?) {
        synchronized(lock) {
            val key = inFlight ?: return
            lastLatencyMs = SystemClock.uptimeMillis() - inFlightSinceMs
            verdicts[key] = info.toVerdict()
            inFlight = null
            lock.notifyAll()
            val next = queued
            val active = session
            if (next != null && active != null && next !in verdicts) send(active, next) else queued = null
        }
    }

    private fun SuggestionsInfo?.toVerdict(): SpellVerdict {
        if (this == null) return SpellVerdict(looksLikeTypo = false, recommended = false, suggestions = emptyList())
        val attributes = suggestionsAttributes
        val suggestions = (0 until suggestionsCount.coerceAtLeast(0)).mapNotNull { getSuggestionAt(it) }
        return SpellVerdict(
            looksLikeTypo = attributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0 &&
                attributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY == 0,
            recommended = attributes and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
            suggestions = suggestions
        )
    }

    private companion object {
        const val TAG = "NboardSpellCheck"
        const val CACHE_SIZE = 256
        const val MIN_WORD_LENGTH = 3
        const val MAX_WORD_LENGTH = 24
        const val MAX_SUGGESTIONS = 3
        /** A request this old has likely been dropped by the checker; stop waiting on it. */
        const val STALE_REQUEST_MS = 1_500L
    }
}
