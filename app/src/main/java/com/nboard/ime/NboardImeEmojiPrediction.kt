package com.nboard.ime

import android.content.Context
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.text.Editable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.isVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun NboardImeService.onEmojiChosen(emoji: String) {
    currentInputConnection?.commitText(emoji, 1)
    pendingAutoCorrection = null
    refreshAutoShiftFromContextAndRerender()
    recordEmojiUsage(emoji)
}

internal fun NboardImeService.filterEmojiCandidates(text: Editable?): List<String> {
    val query = text?.toString()?.trim().orEmpty().lowercase(Locale.US)
    return if (query.isBlank()) {
        allEmojiCatalog
    } else {
        allEmojiCatalog.filter { emoji ->
            emoji.contains(query) || emojiSearchBlob(emoji).contains(query)
        }
    }
}

internal fun NboardImeService.renderEmojiGrid() {
    if (!isEmojiGridInitialized()) {
        return
    }
    emojiRecentColumn.removeAllViews()

    val recentColumn = (emojiRecents + DEFAULT_TOP_EMOJIS)
        .distinct()
        .take(3)

    recentColumn.forEachIndexed { index, emoji ->
        emojiRecentColumn.addView(
            buildEmojiGridKey(
                emoji = emoji,
                widthDp = 52,
                heightDp = 42,
                marginEndDp = 0,
                marginBottomDp = if (index < recentColumn.lastIndex) 4 else 0
            )
        )
    }

    emojiRecentDivider.isVisible = recentColumn.isNotEmpty()

    if (emojiGridLoadedCount <= 0 ||
        emojiGridLoadedCount > allEmojiCatalog.size ||
        emojiGridRow1.childCount == 0 && emojiGridRow2.childCount == 0 && emojiGridRow3.childCount == 0
    ) {
        emojiGridRow1.removeAllViews()
        emojiGridRow2.removeAllViews()
        emojiGridRow3.removeAllViews()
        emojiGridLoadedCount = 0
        appendEmojiGridChunk(EMOJI_GRID_INITIAL_BATCH)
    }
}

internal fun NboardImeService.appendEmojiGridChunk(batchSize: Int) {
    if (!isEmojiGridInitialized() || batchSize <= 0) {
        return
    }
    if (emojiGridLoadedCount >= allEmojiCatalog.size) {
        return
    }

    val rows = arrayOf(emojiGridRow1, emojiGridRow2, emojiGridRow3)
    val end = (emojiGridLoadedCount + batchSize).coerceAtMost(allEmojiCatalog.size)
    for (index in emojiGridLoadedCount until end) {
        val emoji = allEmojiCatalog[index]
        rows[index % rows.size].addView(buildEmojiGridKey(emoji))
    }
    emojiGridLoadedCount = end
}

internal fun NboardImeService.buildEmojiGridKey(
    emoji: String,
    widthDp: Int = 52,
    heightDp: Int = 42,
    marginEndDp: Int = 4,
    marginBottomDp: Int = 0
): AppCompatButton {
    return AppCompatButton(this).apply {
        text = emoji
        setAllCaps(false)
        textSize = 18f
        background = uiDrawable(R.drawable.bg_key)
        setTextColor(uiColor(R.color.key_text))
        gravity = Gravity.CENTER
        flattenView(this)
        bindPressAction(this) { onEmojiChosen(emoji) }
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).also {
            if (marginEndDp > 0) {
                it.marginEnd = dp(marginEndDp)
            }
            if (marginBottomDp > 0) {
                it.bottomMargin = dp(marginBottomDp)
            }
        }
    }
}

internal fun NboardImeService.renderEmojiSuggestions() {
    if (!isEmojiMostUsedRowInitialized()) {
        return
    }
    emojiMostUsedRow.removeAllViews()
    if (!isEmojiSearchMode) {
        return
    }

    val query = emojiSearchInput.text?.toString()?.trim().orEmpty()
    val candidates = if (query.isBlank()) {
        (emojiRecents + DEFAULT_TOP_EMOJIS).distinct()
    } else {
        filterEmojiCandidates(emojiSearchInput.text)
    }.take(MAX_EMOJI_SEARCH_SUGGESTIONS)

    candidates.forEachIndexed { index, emoji ->
        val key = AppCompatButton(this).apply {
            text = emoji
            setAllCaps(false)
            textSize = 19f
            background = uiDrawable(R.drawable.bg_key)
            gravity = Gravity.CENTER
            setTextColor(uiColor(R.color.key_text))
            flattenView(this)
            bindPressAction(this) { onEmojiChosen(emoji) }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(42)).also {
                if (index < candidates.lastIndex) {
                    it.marginEnd = dp(6)
                }
            }
        }
        emojiMostUsedRow.addView(key)
    }
}

internal fun NboardImeService.emojiSearchBlob(emoji: String): String {
    return emojiSearchIndex.getOrPut(emoji) {
        buildString {
            append(EMOJI_KEYWORDS[emoji].orEmpty())
            var offset = 0
            while (offset < emoji.length) {
                val codePoint = emoji.codePointAt(offset)
                if (codePoint != 0x200D && codePoint != 0xFE0F) {
                    val name = Character.getName(codePoint)
                    if (!name.isNullOrBlank()) {
                        append(' ')
                        append(name.lowercase(Locale.US))
                    }
                }
                offset += Character.charCount(codePoint)
            }
        }
    }
}

internal fun NboardImeService.buildEmojiCatalog(): List<String> {
    val catalog = LinkedHashSet<String>()
    catalog.addAll(ALL_EMOJIS)

    EMOJI_SCAN_RANGES.forEach { range ->
        for (codePoint in range) {
            if (!Character.isValidCodePoint(codePoint) || !Character.isDefined(codePoint)) {
                continue
            }
            if (!isEmojiCodePoint(codePoint)) {
                continue
            }
            catalog.add(String(Character.toChars(codePoint)))
        }
    }

    Locale.getISOCountries().forEach { code ->
        if (code.length != 2) {
            return@forEach
        }
        val first = code[0].uppercaseChar()
        val second = code[1].uppercaseChar()
        if (first !in 'A'..'Z' || second !in 'A'..'Z') {
            return@forEach
        }

        val firstIndicator = 0x1F1E6 + (first.code - 'A'.code)
        val secondIndicator = 0x1F1E6 + (second.code - 'A'.code)
        catalog.add(String(intArrayOf(firstIndicator, secondIndicator), 0, 2))
    }

    catalog.add("🇪🇺")
    catalog.add("🇺🇳")
    catalog.addAll(KEYCAP_EMOJIS)
    return catalog.toList()
}

internal fun NboardImeService.isEmojiCodePoint(codePoint: Int): Boolean {
    return try {
        UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)
    } catch (_: Throwable) {
        false
    }
}

internal fun NboardImeService.preloadExtendedEmojiCatalog() {
    serviceScope.launch(Dispatchers.Default) {
        val extended = buildEmojiCatalog()
        launch(Dispatchers.Main) {
            if (extended.size <= allEmojiCatalog.size) {
                return@launch
            }
            allEmojiCatalog.clear()
            allEmojiCatalog.addAll(extended)
            emojiGridLoadedCount = 0
            if (isEmojiGridInitialized()) {
                renderEmojiGrid()
                if (isEmojiSearchMode) {
                    renderEmojiSuggestions()
                }
            }
        }
    }
}

internal fun NboardImeService.recordEmojiUsage(emoji: String) {
    emojiUsageCounts[emoji] = (emojiUsageCounts[emoji] ?: 0) + 1
    emojiRecents.remove(emoji)
    emojiRecents.addFirst(emoji)
    while (emojiRecents.size > MAX_RECENT_EMOJIS) {
        emojiRecents.removeLast()
    }
    saveEmojiUsage()
    if (isEmojiSearchMode) {
        renderEmojiSuggestions()
    }
}

internal fun NboardImeService.loadEmojiUsage() {
    val prefs = getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE)

    val countsRaw = prefs.getString(KEY_EMOJI_COUNTS_JSON, null)
    if (!countsRaw.isNullOrBlank()) {
        try {
            val json = JSONObject(countsRaw)
            json.keys().forEach { key ->
                emojiUsageCounts[key] = json.optInt(key, 0)
            }
        } catch (_: Exception) {
            emojiUsageCounts.clear()
        }
    }

    val recentsRaw = prefs.getString(KEY_EMOJI_RECENTS_JSON, null)
    if (!recentsRaw.isNullOrBlank()) {
        try {
            val array = JSONArray(recentsRaw)
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) {
                    emojiRecents.add(value)
                }
            }
        } catch (_: Exception) {
            emojiRecents.clear()
        }
    }
}

internal fun NboardImeService.saveEmojiUsage() {
    val countsJson = JSONObject().apply {
        emojiUsageCounts.forEach { (emoji, count) -> put(emoji, count) }
    }
    val recentsJson = JSONArray().apply {
        emojiRecents.forEach { put(it) }
    }

    getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_EMOJI_COUNTS_JSON, countsJson.toString())
        .putString(KEY_EMOJI_RECENTS_JSON, recentsJson.toString())
        .apply()
}

internal fun NboardImeService.renderPredictionRow(beforeCursor: String? = null) {
    requestPredictions(beforeCursor)
}

internal fun NboardImeService.predictionRenderContextKey(beforeCursor: String): String {
    return buildString(beforeCursor.length + 96) {
        append(keyboardLanguageMode.name)
        append('|')
        append(wordPredictionEnabled)
        append('|')
        append(isAutoShiftEnabled)
        append('|')
        append(learningDirtyUpdates)
        append('|')
        append(beforeCursor)
    }
}

internal fun NboardImeService.setPredictionWords(words: List<String>) {
    predictionRow.words = words.toList()
    hasPredictionSuggestions = words.isNotEmpty()
}

internal fun NboardImeService.shouldShowPredictionRow(): Boolean {
    if (!wordPredictionEnabled || !smartTypingBehavior.shouldPersonalize()) {
        return false
    }
    if (isToolbarOpen || isAiMode || isClipboardOpen || isEmojiMode || isNumbersMode || isGenerating) {
        return false
    }
    return !shouldShowRecentClipboardRow()
}

internal fun NboardImeService.extractPreviousWordsForPrediction(
    beforeCursor: String,
    currentFragment: String
): Pair<String?, String?> {
    if (beforeCursor.isBlank()) {
        return null to null
    }
    val reduced = if (
        currentFragment.isNotBlank() &&
        beforeCursor.endsWith(currentFragment, ignoreCase = true)
    ) {
        beforeCursor.dropLast(currentFragment.length)
    } else {
        beforeCursor
    }
    val tokens = extractPredictionTokens(reduced)
    if (tokens.isEmpty()) {
        return null to null
    }
    val previous1 = tokens.lastOrNull()
    val previous2 = if (tokens.size >= 2) tokens[tokens.lastIndex - 1] else null
    return previous2 to previous1
}

internal fun NboardImeService.extractPredictionSentenceContext(beforeCursor: String): String {
    if (beforeCursor.isBlank()) {
        return ""
    }
    val lastBoundary = maxOf(
        beforeCursor.lastIndexOf('.'),
        beforeCursor.lastIndexOf('!'),
        beforeCursor.lastIndexOf('?'),
        beforeCursor.lastIndexOf('\n')
    )
    val startIndex = if (lastBoundary >= 0) lastBoundary + 1 else 0
    return beforeCursor.substring(startIndex).trimStart()
}
