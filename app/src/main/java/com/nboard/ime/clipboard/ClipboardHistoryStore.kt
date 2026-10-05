package com.nboard.ime.clipboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardItem(
    val text: String,
    val pinned: Boolean,
    val updatedAtMs: Long
)

class ClipboardHistoryStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    private var cachedItems: List<ClipboardItem>? = null
    private var cachedEncoded: String? = null

    fun addItem(rawText: String) {
        val text = rawText
        if (text.isBlank()) {
            return
        }

        val now = System.currentTimeMillis()
        val current = getItems().toMutableList()
        val existingIndex = current.indexOfFirst { it.text == text }
        if (existingIndex >= 0) {
            val existing = current.removeAt(existingIndex)
            current.add(0, existing.copy(updatedAtMs = now))
        } else {
            current.add(
                0,
                ClipboardItem(
                    text = text,
                    pinned = false,
                    updatedAtMs = now
                )
            )
        }

        saveItems(current)
    }

    fun setPinned(text: String, pinned: Boolean) {
        val current = getItems().toMutableList()
        val index = current.indexOfFirst { it.text == text }
        if (index < 0) {
            return
        }

        val entry = current.removeAt(index)
        current.add(
            0,
            entry.copy(
                pinned = pinned,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        saveItems(sortItems(current))
    }

    fun removeItem(text: String) {
        val filtered = getItems().filterNot { it.text == text }
        saveItems(filtered)
    }

    fun clearUnpinned() { saveItems(getItems().filter { it.pinned }) }

    fun getItems(): List<ClipboardItem> {
        val encoded = preferences.getString(KEY_ITEMS, null)
        if (encoded == cachedEncoded) cachedItems?.let { return it }
        cachedEncoded = encoded
        if (encoded.isNullOrBlank()) {
            cachedItems = emptyList()
            return emptyList()
        }
        val parsedItems = try {
            val parsed = JSONArray(encoded)
            buildList(parsed.length()) {
                for (i in 0 until parsed.length()) {
                    val item = parsed.opt(i)
                    when (item) {
                        is String -> {
                            val text = item
                            if (text.isNotBlank()) {
                                add(
                                    ClipboardItem(
                                        text = text,
                                        pinned = false,
                                        updatedAtMs = 0L
                                    )
                                )
                            }
                        }
                        is JSONObject -> {
                            val text = item.optString("text")
                            if (text.isNotBlank()) {
                                add(
                                    ClipboardItem(
                                        text = text,
                                        pinned = item.optBoolean("pinned", false),
                                        updatedAtMs = item.optLong("updatedAtMs", 0L)
                                    )
                                )
                            }
                        }
                    }
                }
            }.let { items ->
                sortItems(items).take(MAX_ITEMS).also { if (items.size > MAX_ITEMS) saveItems(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
        cachedItems = parsedItems
        return parsedItems
    }

    private fun saveItems(items: List<ClipboardItem>) {
        // Pins are protected; only the oldest unpinned clip is displaced.
        val normalized = sortItems(items).take(MAX_ITEMS)
        val encoded = JSONArray().apply {
            normalized.forEach { item ->
                put(
                    JSONObject().apply {
                        put("text", item.text)
                        put("pinned", item.pinned)
                        put("updatedAtMs", item.updatedAtMs)
                    }
                )
            }
        }
        cachedItems = normalized
        cachedEncoded = encoded.toString()
        preferences.edit().putString(KEY_ITEMS, cachedEncoded).apply()
    }

    private fun sortItems(items: List<ClipboardItem>): List<ClipboardItem> {
        return items
            .distinctBy { it.text }
            .sortedWith(
                compareByDescending<ClipboardItem> { it.pinned }
                    .thenByDescending { it.updatedAtMs }
            )
    }

    companion object {
        const val MAX_ITEMS = 50
        private const val PREFS_NAME = "nboard_clipboard"
        private const val KEY_ITEMS = "items"
    }
}
