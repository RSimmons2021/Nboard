package com.nboard.ime

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import androidx.core.view.isVisible
import kotlinx.coroutines.launch

internal fun NboardImeService.syncAiProcessingAnimations() {
    val shouldAnimate = isGenerating && isAiMode && aiPromptRow.isVisible
    if (shouldAnimate) {
        startAiProcessingAnimations()
    } else {
        stopAiProcessingAnimations()
    }
}

internal fun NboardImeService.startAiProcessingAnimations() {
    if (!isAiPromptShimmerInitialized() || !isAiPromptInputInitialized()) {
        return
    }

    if (aiPillShimmerAnimator == null) {
        aiPromptShimmer.post {
            val stripWidth = aiPromptShimmer.width.toFloat().takeIf { it > 0f } ?: dp(84).toFloat()
            val travel = aiPromptRow.width.toFloat().takeIf { it > 0f } ?: return@post
            aiPromptShimmer.layoutParams = aiPromptShimmer.layoutParams.apply {
                height = aiPromptRow.height
            }
            aiPromptShimmer.isVisible = true
            aiPillShimmerAnimator?.cancel()
            aiPillShimmerAnimator = ValueAnimator.ofFloat(-stripWidth, travel + stripWidth).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { animator ->
                    aiPromptShimmer.translationX = animator.animatedValue as Float
                }
                start()
            }
        }
    }

    if (aiTextPulseAnimator == null) {
        val baseColor = uiColor(R.color.ai_text)
        val pulseColor = uiColor(R.color.ai_text_shine)
        aiTextPulseAnimator = ValueAnimator.ofObject(ArgbEvaluator(), baseColor, pulseColor, baseColor).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                aiPromptInput.setTextColor((animator.animatedValue as Int))
            }
            start()
        }
    }
}

internal fun NboardImeService.stopAiProcessingAnimations() {
    aiPillShimmerAnimator?.cancel()
    aiPillShimmerAnimator = null
    if (isAiPromptShimmerInitialized()) {
        aiPromptShimmer.isVisible = false
        aiPromptShimmer.translationX = 0f
    }

    aiTextPulseAnimator?.cancel()
    aiTextPulseAnimator = null
    if (isAiPromptInputInitialized()) {
        aiPromptInput.setTextColor(uiColor(R.color.ai_text))
    }
}

internal fun NboardImeService.animateAiResultText() {
    if (!isAiPromptInputInitialized()) {
        return
    }

    aiTextPulseAnimator?.cancel()
    aiTextPulseAnimator = null

    val baseColor = uiColor(R.color.ai_text)
    val flashColor = uiColor(R.color.ai_text_shine)
    ValueAnimator.ofObject(ArgbEvaluator(), flashColor, baseColor).apply {
        duration = 420L
        addUpdateListener { animator ->
            aiPromptInput.setTextColor((animator.animatedValue as Int))
        }
        start()
    }
}


internal fun NboardImeService.submitAiPrompt() {
    if (isGenerating || !isAiAllowedInCurrentContext()) return
    val prompt = aiPromptInput.text?.toString()?.trim().orEmpty()
    if (prompt.isBlank()) { toast("Enter a prompt first"); return }
    val origin = captureAiSnapshot() ?: return
    val resolved = if (origin.selected.isEmpty()) prompt else buildLanguagePreservingSelectionPrompt(prompt, origin.selected)
    beginAiGeneration(resolved, AI_PROMPT_SYSTEM_INSTRUCTION, AI_REPLY_CHAR_LIMIT, origin)
}

internal fun NboardImeService.runQuickAiAction(action: QuickAiAction) {
    if (isGenerating || !isAiAllowedInCurrentContext()) return
    val origin = captureAiSnapshot() ?: return
    if (origin.selected.isBlank()) { toast("Select text first"); return }
    beginAiGeneration(buildLanguagePreservingSelectionPrompt(preciseRewriteInstruction(action), origin.selected),
        AI_QUICK_ACTION_SYSTEM_INSTRUCTION, AI_REPLY_CHAR_LIMIT, origin)
}

internal fun NboardImeService.captureAiSnapshot(): AiEditSnapshot? {
    val connection = currentInputConnection ?: return null
    val selected = connection.getSelectedText(0)?.toString().orEmpty()
    return AiEditSnapshot(aiFieldRevision, aiSelectionRevision, editorSelectionStart, editorSelectionEnd,
        connection.getTextBeforeCursor(128, 0)?.toString().orEmpty(), selected,
        connection.getTextAfterCursor(128, 0)?.toString().orEmpty())
}

private fun NboardImeService.beginAiGeneration(prompt: String, instruction: String, limit: Int, origin: AiEditSnapshot) {
    if (!textGenerationClient.isConfigured) { toast("Configure the selected AI provider in Nboard settings"); return }
    aiGenerationJob?.cancel()
    val generation = ++aiRequestRevision
    aiUndo = null
    aiPreview = AiPreview(origin, "", false)
    aiPromptInput.error = null
    updateAiPreviewUi()
    setGenerating(true)
    aiGenerationJob = serviceScope.launch {
        val result = textGenerationClient.generateStreaming(prompt, instruction, limit) { partial ->
            keyboardRoot.post {
                if (generation == aiRequestRevision && origin.fieldRevision == aiFieldRevision && isGenerating) {
                    aiPreview = AiPreview(origin, partial, false)
                    updateAiPreviewUi()
                }
            }
        }
        if (generation != aiRequestRevision || origin.fieldRevision != aiFieldRevision) return@launch
        setGenerating(false)
        result.onSuccess { response -> aiPreview = AiPreview(origin, response, true) }
            .onFailure { error ->
                aiPreview = null
                aiPromptInput.error = error.message ?: "AI request failed"
            }
        updateAiPreviewUi()
    }
}

internal fun NboardImeService.stopAiGeneration() {
    aiRequestRevision++
    aiGenerationJob?.cancel()
    aiGenerationJob = null
    aiPreview = null
    if (isAiPromptInputInitialized()) {
        setGenerating(false)
        updateAiPreviewUi()
    }
}

internal fun NboardImeService.applyAiPreview() {
    val preview = aiPreview?.takeIf { it.complete } ?: return
    val current = captureAiSnapshot()
    if (current == null || !preview.origin.matches(current)) {
        toast("The text or cursor changed. Select the text and try again.")
        aiPreview = null
        updateAiPreviewUi()
        return
    }
    val connection = currentInputConnection ?: return
    val start = minOf(current.selectionStart, current.selectionEnd)
    if (!connection.commitText(preview.text, 1)) { toast("This field could not apply the edit"); return }
    aiUndo = AiUndo(aiFieldRevision, start, preview.text, current.selected)
    aiPreview = null
    aiPromptInput.text?.clear()
    clearInlinePromptFocus()
    updateAiPreviewUi()
}

internal fun NboardImeService.undoAiEdit() {
    val undo = aiUndo ?: return
    val connection = currentInputConnection ?: return
    // Undo is offered only immediately, at the end of the applied text. Typing,
    // moving to another location, or switching fields cannot delete unrelated text.
    val end = undo.start + undo.inserted.length
    if (undo.fieldRevision != aiFieldRevision || editorSelectionStart != end || editorSelectionEnd != end ||
        connection.getTextBeforeCursor(undo.inserted.length, 0)?.toString() != undo.inserted) {
        toast("The text changed; undo is no longer available")
        aiUndo = null; updateAiPreviewUi(); return
    }
    connection.beginBatchEdit()
    try {
        connection.deleteSurroundingText(undo.inserted.length, 0)
        connection.commitText(undo.original, 1)
    } finally { connection.endBatchEdit() }
    aiUndo = null
    updateAiPreviewUi()
}

internal fun NboardImeService.updateAiPreviewUi() {
    if (!isAiPromptInputInitialized()) return
    aiPreviewPanel.isVisible = isAiMode && (aiPreview != null || aiUndo != null)
    aiPreviewText.text = aiPreview?.text ?: "Edit applied"
    aiPreviewText.accessibilityLiveRegion = if (isGenerating) android.view.View.ACCESSIBILITY_LIVE_REGION_NONE
        else android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    aiPreviewApply.isVisible = aiPreview != null
    aiPreviewApply.isEnabled = aiPreview?.complete == true && captureAiSnapshot() == aiPreview?.origin
    aiPreviewStop.isVisible = isGenerating
    aiPreviewDiscard.isVisible = aiPreview != null && !isGenerating
    aiPreviewUndo.isVisible = aiUndo != null
}

internal fun NboardImeService.buildLanguagePreservingSelectionPrompt(
    instruction: String,
    selectedText: String
): String {
    return buildString {
        append("Apply this instruction to the selected text and return only the transformed result.\n")
        append("Keep the output in the same language, tone and formatting as the selected text. Preserve names and numbers.\n")
        append("Do not translate unless the instruction explicitly asks for translation.\n")
        append("Instruction: ")
        append(instruction.trim())
        append("\n\nSelected text:\n")
        append(selectedText)
    }
}
