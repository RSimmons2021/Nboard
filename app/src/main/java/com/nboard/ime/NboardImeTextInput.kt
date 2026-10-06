package com.nboard.ime

import android.view.inputmethod.InputConnection
import java.text.BreakIterator
import java.util.Locale

internal fun NboardImeService.isShiftActive(): Boolean {
    return manualShiftMode != ShiftMode.OFF || isAutoShiftEnabled
}

internal fun NboardImeService.handleShiftTap() {
    when {
        manualShiftMode == ShiftMode.CAPS_LOCK -> {
            manualShiftMode = ShiftMode.OFF
            shiftTapArmed = false
            refreshAutoShiftFromContext()
        }
        shiftTapArmed -> {
            manualShiftMode = ShiftMode.CAPS_LOCK
            isAutoShiftEnabled = false
            shiftTapArmed = false
        }
        isShiftActive() -> {
            manualShiftMode = ShiftMode.OFF
            isAutoShiftEnabled = false
            shiftTapArmed = true
        }
        else -> {
            manualShiftMode = ShiftMode.ONE_SHOT
            isAutoShiftEnabled = false
            shiftTapArmed = true
        }
    }
}

internal fun NboardImeService.consumeOneShotShiftIfNeeded(committedText: String): Boolean {
    if (committedText.isNotEmpty()) shiftTapArmed = false
    if (manualShiftMode == ShiftMode.ONE_SHOT && committedText.any { it.isLetter() }) {
        manualShiftMode = ShiftMode.OFF
        return true
    }
    return false
}

internal fun NboardImeService.refreshAutoShiftFromContext(beforeCursor: String? = null) {
    if (manualShiftMode != ShiftMode.OFF || isNumbersMode || isEmojiMode || isClipboardOpen || isAiMode) {
        isAutoShiftEnabled = false
        return
    }
    if (!autoCapitalizeAfterPunctuationEnabled || !smartTypingBehavior.shouldAutoSpaceAndCapitalize()) {
        isAutoShiftEnabled = false
        return
    }

    val textBeforeCursor = beforeCursor ?: currentInputConnection
        ?.getTextBeforeCursor(AUTO_SHIFT_CONTEXT_WINDOW, 0)
        ?.toString()
        .orEmpty()

    val trimmed = textBeforeCursor.trimEnd()
    if (trimmed.isEmpty()) {
        isAutoShiftEnabled = true
        return
    }
    val lastChar = trimmed.last()
    if (lastChar == '\n') {
        isAutoShiftEnabled = true
        return
    }
    val previousChar = trimmed.getOrNull(trimmed.lastIndex - 1)
    val last2Chars = if (trimmed.length >= 3) {
        trimmed.substring(trimmed.length - 3, trimmed.length - 1)
    } else {
        null
    }
    isAutoShiftEnabled = smartTypingBehavior.shouldAutoSpaceAfterChar(
        char = lastChar,
        previousChar = previousChar,
        last2Chars = last2Chars,
        nextChar = null
    )
}

internal fun NboardImeService.refreshAutoShiftFromContextAndRerender(forceRerender: Boolean = false) {
    val beforeCursor = readKeyboardContext()
    val previous = isAutoShiftEnabled
    refreshAutoShiftFromContext(beforeCursor)
    if ((forceRerender || previous != isAutoShiftEnabled) && !isNumbersMode && !isEmojiMode && !isClipboardOpen) {
        renderKeyRows()
    }
    if (isPredictionRowInitialized()) {
        refreshUi(beforeCursor)
    }
}

internal fun NboardImeService.commitSwipeWord(word: String) {
    val inputConnection = currentInputConnection ?: return
    val beforeCursor = inputConnection
        .getTextBeforeCursor(PREDICTION_CONTEXT_WINDOW, 0)
        ?.toString()
        .orEmpty()
    val sentenceContext = extractPredictionSentenceContext(beforeCursor)
    val (previousWord2, previousWord1) = extractPreviousWordsForPrediction(sentenceContext, "")

    val normalizedWord = normalizeWord(word)
    val commitWord = when {
        manualShiftMode == ShiftMode.CAPS_LOCK -> normalizedWord.uppercase(Locale.US)
        else -> wordCasing.preferred(normalizedWord, usesEnglishCasing())
            ?: if (isShiftActive()) normalizedWord.replaceFirstChar { it.uppercase(Locale.US) } else normalizedWord
    }

    inputConnection.beginBatchEdit()
    try {
        inputConnection.commitText(commitWord, 1)
        inputConnection.commitText(" ", 1)
    } finally {
        inputConnection.endBatchEdit()
    }

    recordLearnedTransition(previousWord1, normalizedWord, boost = 2)
    recordLearnedTrigram(previousWord2, previousWord1, normalizedWord, boost = 2)
    phraseWordTouched = true
    learnPredictionFromContext(inputConnection)
    pendingAutoCorrection = null
    val consumedOneShot = consumeOneShotShiftIfNeeded(commitWord)
    refreshAutoShiftFromContextAndRerender(consumedOneShot)
}

internal fun NboardImeService.deleteOneCharacter() {
    if (!isAiPromptInputActive()) aiUndo = null
    pendingAutoInsertedSentenceSpace = false
    autoSpacedPeriod = false
    if (isAiPromptInputActive()) {
        val editable = aiPromptInput.text
        val start = aiPromptInput.selectionStart
        val end = aiPromptInput.selectionEnd
        if (!editable.isNullOrEmpty() && start >= 0 && end >= 0 && start != end) {
            val min = minOf(start, end)
            val max = maxOf(start, end)
            editable.delete(min, max)
        } else if (!editable.isNullOrEmpty()) {
            editable.delete(editable.length - 1, editable.length)
        }
        return
    }
    if (isEmojiSearchInputActive()) {
        val editable = emojiSearchInput.text
        val start = emojiSearchInput.selectionStart
        val end = emojiSearchInput.selectionEnd
        if (!editable.isNullOrEmpty() && start >= 0 && end >= 0 && start != end) {
            val min = minOf(start, end)
            val max = maxOf(start, end)
            editable.delete(min, max)
        } else if (!editable.isNullOrEmpty()) {
            editable.delete(editable.length - 1, editable.length)
        }
        return
    }
    if (tryRevertLastAutoCorrection()) {
        refreshAutoShiftFromContextAndRerender()
        return
    }
    val inputConnection = currentInputConnection ?: return
    val selectedText = inputConnection.getSelectedText(0)
    if (!selectedText.isNullOrEmpty()) {
        inputConnection.commitText("", 1)
    } else {
        deletePreviousGrapheme(inputConnection)
    }
    pendingAutoCorrection = null
    refreshAutoShiftFromContextAndRerender()
}

internal fun NboardImeService.deletePreviousGrapheme(inputConnection: InputConnection) {
    val beforeCursor = inputConnection
        .getTextBeforeCursor(maxOf(GRAPHEME_DELETE_CONTEXT_WINDOW, phraseLearningProbeSize()), 0)
        ?.toString()
        .orEmpty()
    if (beforeCursor.isEmpty()) {
        return
    }

    if (isWordChar(beforeCursor.last())) {
        retractPhraseLearning(beforeCursor)
        phraseWordTouched = true
    }
    val charsToDelete = previousGraphemeSize(beforeCursor)
    inputConnection.deleteSurroundingText(charsToDelete, 0)
}

internal fun NboardImeService.previousGraphemeSize(text: String): Int {
    if (text.isEmpty()) {
        return 1
    }
    return try {
        val breaker = BreakIterator.getCharacterInstance(Locale.getDefault())
        breaker.setText(text)
        val end = breaker.last()
        val start = breaker.previous()
        when {
            end == BreakIterator.DONE -> 1
            start == BreakIterator.DONE -> end.coerceAtLeast(1)
            else -> (end - start).coerceAtLeast(1)
        }
    } catch (_: Throwable) {
        val codePoint = text.codePointBefore(text.length)
        Character.charCount(codePoint).coerceAtLeast(1)
    }
}

internal fun NboardImeService.commitKeyText(text: String) {
    if (!isAiPromptInputActive()) aiUndo = null
    if (isAiPromptInputActive()) {
        appendPromptText(text)
        return
    }
    if (isEmojiSearchInputActive()) {
        appendEmojiSearchText(text)
        return
    }

    val inputConnection = currentInputConnection ?: return
    if (text.any { it.isLetter() }) phraseWordTouched = true
    val committedChar = text.singleOrNull()
    val needsPunctuationContext = committedChar in SMART_TYPING_SENTENCE_ENDERS &&
        (pendingAutoInsertedSentenceSpace ||
            autoSpaceAfterPunctuationEnabled && smartTypingBehavior.shouldAutoSpaceAndCapitalize())
    // Ordinary letters do not need three synchronous round trips to the editor.
    // A period also needs the current token, to recognise web and email addresses.
    var beforeCursorText = if (needsPunctuationContext) {
        inputConnection.getTextBeforeCursor(if (committedChar == '.') ADDRESS_CONTEXT_WINDOW else 3, 0)?.toString().orEmpty()
    } else ""
    val hasSelection = needsPunctuationContext && !inputConnection.getSelectedText(0).isNullOrEmpty()
    if (committedChar != null &&
        committedChar in SMART_TYPING_SENTENCE_ENDERS &&
        pendingAutoInsertedSentenceSpace
    ) {
        if (!hasSelection &&
            beforeCursorText.length >= 2 &&
            beforeCursorText.last() == ' ' &&
            beforeCursorText[beforeCursorText.lastIndex - 1] in SMART_TYPING_SENTENCE_ENDERS
        ) {
            inputConnection.deleteSurroundingText(1, 0)
            beforeCursorText = beforeCursorText.dropLast(1)
        }
    }
    pendingAutoInsertedSentenceSpace = false

    val previousChar = beforeCursorText.lastOrNull()
    val last2Chars = beforeCursorText.takeLast(2).takeIf { it.length == 2 }
    val nextChar = if (needsPunctuationContext) inputConnection.getTextAfterCursor(1, 0)?.toString()?.firstOrNull() else null
    var autoCorrection: AutoCorrectionResult? = null
    var committedSuffix = text
    val periodSpaceToCheck = autoSpacedPeriod
    if (committedChar == null || !committedChar.isLetterOrDigit()) autoSpacedPeriod = false
    inputConnection.beginBatchEdit()
    try {
        if (periodSpaceToCheck && committedChar != null && AUTOCORRECT_TRIGGER_DELIMITERS.contains(committedChar)) {
            joinDomainAfterAutoSpace(inputConnection)
        }
        if (text.length == 1 && AUTOCORRECT_TRIGGER_DELIMITERS.contains(text[0])) {
            android.os.Trace.beginSection("nboard.autocorrect")
            try { autoCorrection = applyAutoCorrectionBeforeDelimiter(inputConnection) }
            finally { android.os.Trace.endSection() }
            autoCorrection?.let { committedSuffix = it.trailing + committedSuffix }
        }

        inputConnection.commitText(text, 1)
        if (committedChar != null &&
            !hasSelection &&
            autoSpaceAfterPunctuationEnabled &&
            !(committedChar == '.' && isTypingAddress(beforeCursorText)) &&
            smartTypingBehavior.shouldAutoSpaceAfterChar(
                char = committedChar,
                previousChar = previousChar,
                last2Chars = last2Chars,
                nextChar = nextChar
            )
        ) {
            inputConnection.commitText(" ", 1)
            committedSuffix += " "
            pendingAutoInsertedSentenceSpace = true
            autoSpacedPeriod = committedChar == '.'
        }
        if (text.length == 1 && AUTOCORRECT_TRIGGER_DELIMITERS.contains(text[0])) {
            android.os.Trace.beginSection("nboard.learning")
            try { learnPredictionFromContext(inputConnection) }
            finally { android.os.Trace.endSection() }
        }
    } finally {
        inputConnection.endBatchEdit()
    }
    pendingAutoCorrection = if (autoCorrection != null) {
        AutoCorrectionUndo(
            originalWord = autoCorrection.originalWord,
            correctedWord = autoCorrection.correctedWord,
            committedSuffix = committedSuffix
        )
    } else {
        null
    }
    val consumedOneShot = consumeOneShotShiftIfNeeded(text)
    refreshAutoShiftFromContextAndRerender(consumedOneShot)
}

internal fun NboardImeService.appendPromptText(text: String) {
    if (!isAiPromptInputInitialized()) {
        return
    }
    val editable = aiPromptInput.text ?: return
    editable.append(text)
    aiPromptInput.setSelection(editable.length)
}

internal fun NboardImeService.appendEmojiSearchText(text: String) {
    val editable = emojiSearchInput.text ?: return
    editable.append(text)
    emojiSearchInput.setSelection(editable.length)
}

private val DOMAIN_AFTER_AUTO_SPACE = Regex("[\\p{L}\\d-]\\. (com|org|net|io|edu|gov)$", RegexOption.IGNORE_CASE)

/** The token before a period is part of an address, e.g. "me@mail" or "www.site". */
internal fun isTypingAddress(beforeCursor: String): Boolean {
    val token = beforeCursor.takeLastWhile { !it.isWhitespace() }.lowercase(Locale.US)
    return '@' in token || token.startsWith("www") || token.startsWith("http") || '/' in token
}

/**
 * "google.com" typed in a sentence received an automatic space after its period.
 * Once the next word is a common domain ending, join it back: "google. Com" -> "google.com".
 */
internal fun NboardImeService.joinDomainAfterAutoSpace(inputConnection: InputConnection) {
    val before = inputConnection.getTextBeforeCursor(8, 0)?.toString() ?: return
    val match = DOMAIN_AFTER_AUTO_SPACE.find(before) ?: return
    val ending = match.groupValues[1]
    inputConnection.deleteSurroundingText(ending.length + 1, 0)
    inputConnection.commitText(ending.lowercase(Locale.US), 1)
}
