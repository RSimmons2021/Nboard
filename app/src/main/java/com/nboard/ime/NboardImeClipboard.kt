package com.nboard.ime

import android.content.ClipDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun NboardImeService.renderRecentClipboardRow() {
    if (!isRecentClipboardChipInitialized()) {
        return
    }
    val text = latestClipboardText.orEmpty().replace('\n', ' ')
    val imageUri = latestClipboardImageUri
    if (text.isBlank() && imageUri == null) {
        return
    }

    val isImage = imageUri != null && text.isBlank()
    val display = if (isImage) {
        "Image copied"
    } else if (text.length > RECENT_CLIPBOARD_PREVIEW_CHAR_LIMIT) {
        "${text.take(RECENT_CLIPBOARD_PREVIEW_CHAR_LIMIT - 1)}…"
    } else {
        text
    }
    recentClipboardChip.text = display
    recentClipboardChip.contentDescription = if (isImage) {
        "Paste recent clipboard image"
    } else {
        "Paste recent clipboard text"
    }

    val chipDrawable = if (isImage) {
        latestClipboardImagePreview?.let { bitmap ->
            BitmapDrawable(resources, bitmap)
        } ?: uiDrawable(R.drawable.ic_clipboard_lucide)?.mutate()?.apply {
            setTint(uiColor(R.color.key_text))
        }
    } else {
        uiDrawable(R.drawable.ic_clipboard_lucide)?.mutate()?.apply {
            setTint(uiColor(R.color.key_text))
        }
    }
    recentClipboardChip.setCompoundDrawablesRelativeWithIntrinsicBounds(chipDrawable, null, null, null)
    recentClipboardChip.compoundDrawablePadding = dp(6)
    recentClipboardChip.minWidth = 0
    recentClipboardChip.minimumWidth = 0
    (recentClipboardChip.layoutParams as? LinearLayout.LayoutParams)?.width = ViewGroup.LayoutParams.WRAP_CONTENT
    if (isKeyboardRootInitialized() && keyboardRoot.width > 0) {
        recentClipboardChip.maxWidth = keyboardRoot.width - dp(58)
    } else {
        recentClipboardChip.maxWidth = dp(280)
    }
}


internal fun NboardImeService.shouldShowRecentClipboardRow(): Boolean {
    if (isAiMode || isClipboardOpen || isEmojiMode) {
        return false
    }
    val hasText = latestClipboardText.orEmpty().isNotBlank()
    val hasImage = latestClipboardImageUri != null
    if ((!hasText && !hasImage) || latestClipboardDismissed) {
        return false
    }
    return (System.currentTimeMillis() - latestClipboardAtMs) <= RECENT_CLIPBOARD_WINDOW_MS
}


internal fun NboardImeService.renderClipboardItems() {
    if (!isClipboardItemsContainerInitialized()) {
        return
    }

    clipboardItemsContainer.removeAllViews()
    val items = clipboardHistoryStore.getItems()

    if (items.isEmpty()) {
        val row = buildClipboardRow()
        row.addView(buildClipboardButton("Clipboard is empty", pinned = false, enabled = false) {
            // no-op
        })
        row.addView(buildClipboardSpacer())
        clipboardItemsContainer.addView(row)
        return
    }

    fun addSection(title: String, entries: List<com.nboard.ime.clipboard.ClipboardItem>) {
        if (entries.isEmpty()) return
        clipboardItemsContainer.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(uiColor(R.color.key_text))
            setPadding(dp(4), dp(12), dp(4), dp(8))
            applyInterTypeface(this)
        })
        val rows = entries.chunked(2)
        rows.forEachIndexed { rowIndex, rowItems ->
            val row = buildClipboardRow()
            rowItems.forEach { item ->
                val text = item.text
                val button = buildClipboardButton(text, pinned = item.pinned, enabled = true) {
                    if (isAiMode) {
                        appendPromptText(text)
                    } else {
                        currentInputConnection?.commitText(text, 1)
                    }
                    isClipboardOpen = false
                    refreshUi()
                }
                row.addView(button)
            }
            if (rowItems.size == 1) {
                row.addView(buildClipboardSpacer())
            }
            if (rowIndex < rows.lastIndex) {
                (row.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(8)
            }
            clipboardItemsContainer.addView(row)
        }
    }
    addSection("Pinned", items.filter { it.pinned })
    addSection("Saved · up to 50 clips, no expiry", items.filterNot { it.pinned })
}

internal fun NboardImeService.buildClipboardRow(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

internal fun NboardImeService.buildClipboardSpacer(): View {
    return View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(72), 1f).also {
            it.marginStart = dp(5)
        }
    }
}

internal fun NboardImeService.buildClipboardButton(
    text: String,
    pinned: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
): Button {
    return AppCompatButton(this).apply {
        this.text = text
        contentDescription = if (pinned) "Pinned clip: $text. Hold to unpin or delete." else "$text. Hold to pin or delete."
        if (pinned) {
            val pin = uiDrawable(R.drawable.ic_pin_lucide)?.mutate()?.apply { setTint(uiColor(R.color.key_text)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(pin, null, null, null)
            compoundDrawablePadding = dp(6)
        }
        setAllCaps(false)
        applyInterTypeface(this)
        isEnabled = enabled
        maxLines = 2
        textSize = 13f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = uiDrawable(R.drawable.bg_chip)
        setTextColor(uiColor(R.color.key_text))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        flattenView(this)
        configureKeyTouch(
            view = this,
            repeatOnHold = false,
            longPressAction = { anchor, rawX, rawY ->
                showClipboardItemActionsPopup(anchor, text, pinned, rawX, rawY)
            },
            tapOnDown = false,
            onTap = onClick
        )
        layoutParams = LinearLayout.LayoutParams(0, dp(72), 1f).also {
            it.marginEnd = dp(5)
        }
    }
}

internal fun NboardImeService.showClipboardItemActionsPopup(
    anchor: View,
    text: String,
    pinned: Boolean,
    touchRawX: Float,
    touchRawY: Float
) {
    dismissActivePopup()

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = uiDrawable(R.drawable.bg_variant_popup)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    val optionViews = mutableListOf<View>()
    val optionActions = mutableListOf<(() -> Unit)?>()
    val optionEnabled = mutableListOf<Boolean>()

    fun addAction(iconRes: Int, selected: Boolean = false, enabled: Boolean = true, onAction: () -> Unit) {
        val action = AppCompatImageButton(this).apply {
            background = uiDrawable(
                if (selected) R.drawable.bg_popup_option_selected else R.drawable.bg_popup_option
            )
            setIcon(this, iconRes, R.color.key_text)
            contentDescription = if (iconRes == R.drawable.ic_pin_lucide) {
                if (pinned) "Unpin clip" else "Pin clip"
            } else if (iconRes == R.drawable.ic_ai_custom) "Use clip in AI assistance" else "Delete clip"
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.42f
            flattenView(this)
            layoutParams = LinearLayout.LayoutParams(
                dp(40),
                dp(40)
            ).also {
                it.marginEnd = dp(4)
            }
        }
        optionViews.add(action)
        optionActions.add {
            try {
                onAction()
                renderClipboardItems()
                refreshUi()
            } catch (error: Exception) {
                Log.e(TAG, "Clipboard action failed", error)
            }
        }
        optionEnabled.add(enabled)
        row.addView(action)
    }

    val aiEnabled = isAiAllowedInCurrentContext()
    addAction(R.drawable.ic_ai_custom, enabled = aiEnabled) {
        if (!aiEnabled) {
            return@addAction
        }
        isEmojiMode = false
        isClipboardOpen = false
        isAiMode = true
        refreshUi()
        aiPromptInput.setText(text)
        aiPromptInput.setSelection(aiPromptInput.text?.length ?: 0)
        aiPromptInput.requestFocus()
        inlineInputTarget = InlineInputTarget.AI_PROMPT
    }
    addAction(R.drawable.ic_pin_lucide, selected = pinned) {
        clipboardHistoryStore.setPinned(text, !pinned)
    }
    addAction(R.drawable.ic_trash_lucide) {
        clipboardHistoryStore.removeItem(text)
    }

    val popup = PopupWindow(
        row,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        false
    ).apply {
        isOutsideTouchable = false
        isTouchable = false
        isClippingEnabled = false
        setBackgroundDrawable(ColorDrawable(0x00000000))
        elevation = dp(6).toFloat()
        setOnDismissListener {
            if (activePopupWindow === this) {
                activePopupWindow = null
                activeSwipePopupSession = null
            }
        }
    }

    activePopupWindow = popup
    showPopupNearTouch(anchor, popup, row, touchRawX, touchRawY)
    activeSwipePopupSession = SwipePopupSession(
        optionViews = optionViews,
        optionActions = optionActions,
        optionEnabled = optionEnabled,
        selectedIndex = 0
    )
    highlightSwipePopupSelection(0)
}

internal fun NboardImeService.commitRecentClipboardImage(uri: Uri, mimeType: String?): Boolean {
    val inputConnection = currentInputConnection ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
        return false
    }

    return try {
        val resolvedMime = mimeType ?: contentResolver.getType(uri) ?: "image/*"
        val description = ClipDescription("Nboard clipboard image", arrayOf(resolvedMime))
        val contentInfo = InputContentInfo(uri, description, null)
        val committed = inputConnection.commitContent(
            contentInfo,
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null
        )
        if (!committed) {
            toast("Image paste isn't supported in this field")
        }
        committed
    } catch (_: Throwable) {
        toast("Unable to paste this image")
        false
    }
}

internal fun NboardImeService.resolveClipboardImageMimeType(
    description: ClipDescription?,
    uri: Uri?
): String? {
    var foundImage: String? = null
    if (description != null) {
        for (index in 0 until description.mimeTypeCount) {
            val mime = description.getMimeType(index) ?: continue
            if (mime.startsWith("image/")) {
                foundImage = mime
                break
            }
        }
    }

    if (foundImage != null) {
        return foundImage
    }
    if (uri == null) {
        return null
    }

    val resolverMime = contentResolver.getType(uri)
    return if (!resolverMime.isNullOrBlank() && resolverMime.startsWith("image/")) {
        resolverMime
    } else {
        null
    }
}

internal fun NboardImeService.loadRecentClipboardImagePreview(uri: Uri) {
    val previewSizePx = dp(24).coerceAtLeast(24)
    serviceScope.launch(Dispatchers.IO) {
        val decoded = decodeClipboardImagePreview(uri, previewSizePx)
        launch(Dispatchers.Main) {
            if (latestClipboardImageUri != uri) {
                return@launch
            }
            latestClipboardImagePreview = decoded
            renderRecentClipboardRow()
            if (isAiModeButtonInitialized()) {
                refreshUi()
            }
        }
    }
}

internal fun NboardImeService.decodeClipboardImagePreview(uri: Uri, targetSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    } ?: return null

    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
        return null
    }

    var sampleSize = 1
    while ((width / sampleSize) > targetSizePx * 2 || (height / sampleSize) > targetSizePx * 2) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
    }
    val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null

    val scaledWidth = ((bitmap.width * targetSizePx.toFloat() / bitmap.height).toInt()).coerceAtLeast(targetSizePx)
    return Bitmap.createScaledBitmap(bitmap, scaledWidth, targetSizePx, true)
}

internal fun NboardImeService.scheduleRecentClipboardExpiry() {
    recentClipboardExpiryJob?.cancel()
    if (latestClipboardDismissed) {
        return
    }
    val remaining = RECENT_CLIPBOARD_WINDOW_MS - (System.currentTimeMillis() - latestClipboardAtMs)
    if (remaining <= 0L) {
        if (isAiModeButtonInitialized()) {
            refreshUi()
        }
        return
    }
    recentClipboardExpiryJob = serviceScope.launch {
        delay(remaining)
        if (!latestClipboardDismissed && isAiModeButtonInitialized()) {
            refreshUi()
        }
    }
}

internal fun NboardImeService.captureClipboardPrimary() {
    if (!KeyboardModeSettings.loadClipboardHistoryEnabled(this)) return
    val manager = clipboardManager ?: return
    try {
        val clipData = manager.primaryClip ?: return
        if (clipData.itemCount <= 0) {
            return
        }

        val item = clipData.getItemAt(0)
        val description = manager.primaryClipDescription ?: clipData.description
        if (description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
        val itemUri = item.uri
        val imageMimeType = resolveClipboardImageMimeType(description, itemUri)

        if (itemUri != null && imageMimeType != null) {
            latestClipboardText = null
            latestClipboardImageUri = itemUri
            latestClipboardImageMimeType = imageMimeType
            latestClipboardImagePreview = null
            latestClipboardAtMs = System.currentTimeMillis()
            latestClipboardDismissed = false
            scheduleRecentClipboardExpiry()
            loadRecentClipboardImagePreview(itemUri)
            renderRecentClipboardRow()
            if (isAiModeButtonInitialized()) {
                refreshUi()
            }
            return
        }

        val itemText = item.coerceToText(this)?.toString().orEmpty()
        if (itemText.isBlank()) {
            return
        }
        clipboardHistoryStore.addItem(itemText)
        latestClipboardText = itemText
        latestClipboardImageUri = null
        latestClipboardImageMimeType = null
        latestClipboardImagePreview = null
        latestClipboardAtMs = System.currentTimeMillis()
        latestClipboardDismissed = false
        scheduleRecentClipboardExpiry()
        renderClipboardItems()
        renderRecentClipboardRow()
        if (isAiModeButtonInitialized()) {
            refreshUi()
        }
    } catch (error: SecurityException) {
        Log.w(TAG, "Clipboard access denied", error)
    } catch (error: Exception) {
        Log.w(TAG, "Clipboard read failed", error)
    }
}
