package com.nboard.ime

import android.content.Context
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
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
    // Shown and inserted in the skin tone last chosen for this emoji.
    val shown = preferredEmojiTone(emoji)
    return AppCompatButton(this).apply {
        text = shown
        setAllCaps(false)
        textSize = 24f
        background = uiDrawable(R.drawable.bg_key)
        setTextColor(uiColor(R.color.key_text))
        gravity = Gravity.CENTER
        flattenView(this)
        configureKeyTouch(
            view = this,
            repeatOnHold = false,
            longPressAction = if (EmojiTones.supportsTones(emoji)) { anchor, rawX, rawY ->
                showEmojiTonePopup(anchor, emoji, rawX, rawY)
            } else null,
            tapOnDown = false,
            onTap = { onEmojiChosen(preferredEmojiTone(emoji)) }
        )
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
    if (!isEmojiMostUsedRowInitialized() || !isEmojiMode) {
        return
    }
    val query = if (isEmojiSearchMode) emojiSearchInput.text?.toString()?.trim().orEmpty() else ""
    val candidates = if (query.isBlank()) {
        mostUsedEmojis(emojiUsageCounts, DEFAULT_TOP_EMOJIS)
    } else {
        filterEmojiCandidates(emojiSearchInput.text).take(MAX_EMOJI_SEARCH_SUGGESTIONS)
    }
    val renderKey = candidates to query.isBlank()
    if (emojiMostUsedRow.tag == renderKey) return
    emojiMostUsedRow.tag = renderKey
    emojiMostUsedRow.removeAllViews()
    emojiMostUsedRow.contentDescription = if (query.isBlank()) "Most used emojis" else "Emoji search results"
    candidates.chunked(8).forEachIndexed { rowIndex, emojis ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex == 0 && candidates.size > 8) bottomMargin = dp(4)
            }
        }
        emojis.forEach { emoji -> row.addView(buildEmojiGridKey(emoji, widthDp = 46, heightDp = 44)) }
        emojiMostUsedRow.addView(row)
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
    if (isEmojiMode) {
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
    hasPredictionSuggestions = words.any { it.isNotBlank() }
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


/** The emoji in the skin tone the user last picked for it (default yellow if never picked). */
internal fun NboardImeService.preferredEmojiTone(emoji: String): String {
    val base = EmojiTones.base(emoji)
    val tone = emojiTonePreferences[base] ?: return emoji
    return EmojiTones.withTone(base, EmojiTones.MODIFIERS.getOrNull(tone))
}

/** Hold an emoji: default plus five skin tones; slide to one and lift. The choice is remembered. */
internal fun NboardImeService.showEmojiTonePopup(anchor: View, emoji: String, rawX: Float, rawY: Float) {
    dismissActivePopup()
    val base = EmojiTones.base(emoji)
    val choices = listOf<String?>(null) + EmojiTones.MODIFIERS
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = uiDrawable(R.drawable.bg_variant_popup)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    val views = mutableListOf<View>()
    val actions = mutableListOf<(() -> Unit)?>()
    choices.forEachIndexed { index, modifier ->
        val option = EmojiTones.withTone(base, modifier)
        val view = AppCompatButton(this).apply {
            text = option
            setAllCaps(false)
            textSize = 24f
            contentDescription = if (modifier == null) "Default skin tone" else "Skin tone ${index}"
            background = uiDrawable(R.drawable.bg_popup_option)
            gravity = Gravity.CENTER
            flattenView(this)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(48)).also { if (index < choices.lastIndex) it.marginEnd = dp(2) }
        }
        views += view
        actions += {
            if (modifier == null) emojiTonePreferences.remove(base) else emojiTonePreferences[base] = index - 1
            saveEmojiTonePreferences()
            onEmojiChosen(option)
            showToneOnVisibleKeys(base, option)
        }
        row.addView(view)
    }
    val popup = android.widget.PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isOutsideTouchable = false
        isTouchable = false
        isClippingEnabled = false
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        elevation = dp(6).toFloat()
        setOnDismissListener {
            if (activePopupWindow === this) {
                activePopupWindow = null
                activeSwipePopupSession = null
            }
        }
    }
    activePopupWindow = popup
    showPopupNearTouch(anchor, popup, row, rawX, rawY)
    val current = (emojiTonePreferences[base]?.plus(1)) ?: 0
    activeSwipePopupSession = SwipePopupSession(views, actions, List(views.size) { true }, current)
    highlightSwipePopupSelection(current)
}

internal fun NboardImeService.loadEmojiTonePreferences() {
    emojiTonePreferences.clear()
    val raw = getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_EMOJI_TONES_JSON, null) ?: return
    try {
        val json = JSONObject(raw)
        json.keys().forEach { key -> json.optInt(key, -1).takeIf { it in 0..4 }?.let { emojiTonePreferences[key] = it } }
    } catch (_: Exception) {
        emojiTonePreferences.clear()
    }
}

internal fun NboardImeService.saveEmojiTonePreferences() {
    val json = JSONObject().apply { emojiTonePreferences.forEach { (base, tone) -> put(base, tone) } }
    getSharedPreferences(KeyboardModeSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_EMOJI_TONES_JSON, json.toString()).apply()
}

/** Updates already built keys for [base] (grid and most-used rows) without rebuilding the grid. */
internal fun NboardImeService.showToneOnVisibleKeys(base: String, shown: String) {
    fun visit(view: View) {
        if (view is AppCompatButton && view.text?.let { EmojiTones.base(it.toString()) } == base) view.text = shown
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    if (isEmojiGridInitialized()) listOf(emojiGridRow1, emojiGridRow2, emojiGridRow3).forEach(::visit)
    if (isEmojiMostUsedRowInitialized()) visit(emojiMostUsedRow)
}
