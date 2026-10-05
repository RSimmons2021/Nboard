package com.nboard.ime

/** Bounded surrounding text plus field/selection revision ties a result to its origin. */
internal data class AiEditSnapshot(val fieldRevision: Long, val selectionRevision: Long,
    val selectionStart: Int, val selectionEnd: Int, val before: String, val selected: String, val after: String)

internal data class AiPreview(val origin: AiEditSnapshot, val text: String, val complete: Boolean)
internal data class AiUndo(val fieldRevision: Long, val start: Int, val inserted: String, val original: String)

internal fun AiEditSnapshot.matches(other: AiEditSnapshot): Boolean = this == other

internal fun preciseRewriteInstruction(action: QuickAiAction): String = when (action) {
    QuickAiAction.SUMMARIZE -> "Summarize the important points concisely. Preserve names, numbers, dates and commitments. Do not invent information."
    QuickAiAction.FIX_GRAMMAR -> "Correct only spelling, grammar and punctuation. Keep the wording, tone, meaning, names, numbers, paragraph breaks and formatting wherever possible. Do not rewrite already correct text."
    QuickAiAction.EXPAND -> "Clarify and expand the wording while preserving meaning, tone, names, numbers and paragraph structure. Do not invent facts, promises or details absent from the source."
}
