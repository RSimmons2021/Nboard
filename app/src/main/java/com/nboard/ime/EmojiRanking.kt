package com.nboard.ime

/** Frequency first, with stable ties. Recently choosing an emoji doesn't promote it above favorites. */
internal fun mostUsedEmojis(counts: Map<String, Int>, defaults: List<String>, limit: Int = 16): List<String> {
    val used = counts.entries.filter { it.value > 0 && it.key.isNotBlank() }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
    return (used + defaults).distinct().take(limit)
}
