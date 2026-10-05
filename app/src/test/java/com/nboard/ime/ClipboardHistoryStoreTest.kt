package com.nboard.ime

import android.content.SharedPreferences
import com.nboard.ime.clipboard.ClipboardHistoryStore
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ClipboardHistoryStoreTest {
    private class Preferences {
        val values = mutableMapOf<String, Any?>()
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1]; proxy }
                "apply" -> null
                "commit" -> true
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun `history survives recreation with whitespace and pins intact`() {
        val prefs = Preferences()
        val store = ClipboardHistoryStore(prefs.instance)
        store.addItem("  a code block\n\tline 2  ")
        store.setPinned("  a code block\n\tline 2  ", true)
        val recreated = ClipboardHistoryStore(prefs.instance)
        assertEquals(store.getItems(), recreated.getItems())
        assertTrue(recreated.getItems().single().pinned)
        assertEquals("  a code block\n\tline 2  ", recreated.getItems().single().text)
    }

    @Test fun `51st clip evicts oldest unpinned clip and protects pins`() {
        val prefs = Preferences()
        val store = ClipboardHistoryStore(prefs.instance)
        store.addItem("keep")
        store.setPinned("keep", true)
        repeat(50) { store.addItem("clip-$it") }
        val clips = store.getItems()
        assertEquals(50, clips.size)
        assertEquals("keep", clips.first().text)
        assertFalse(clips.any { it.text == "clip-0" })
        assertTrue(clips.any { it.text == "clip-49" })
        assertEquals(clips, ClipboardHistoryStore(prefs.instance).getItems())
    }

    @Test fun `full pinned history declines incoming clips`() {
        val store = ClipboardHistoryStore(Preferences().instance)
        repeat(50) { store.addItem("pin-$it"); store.setPinned("pin-$it", true) }
        store.addItem("incoming")
        assertEquals(50, store.getItems().size)
        assertTrue(store.getItems().all { it.pinned })
        assertFalse(store.getItems().any { it.text == "incoming" })
    }

    @Test fun `recopy promotes clip without duplicate or lost pin`() {
        val store = ClipboardHistoryStore(Preferences().instance)
        store.addItem("first")
        store.setPinned("first", true)
        store.addItem("second")
        store.addItem("first")
        assertEquals(2, store.getItems().size)
        assertTrue(store.getItems().first().pinned)
        store.clearUnpinned()
        assertEquals(listOf("first"), store.getItems().map { it.text })
    }

    @Test fun `old timestamps do not expire and legacy data migrates`() {
        val prefs = Preferences()
        prefs.values["items"] = "[\"legacy\",{\"text\":\"old pin\",\"pinned\":true,\"updatedAtMs\":1}]"
        val store = ClipboardHistoryStore(prefs.instance)
        assertEquals(listOf("old pin", "legacy"), store.getItems().map { it.text })
        store.addItem("new")
        assertEquals(3, ClipboardHistoryStore(prefs.instance).getItems().size)
    }

    @Test fun `settings changes invalidate service cache`() {
        val prefs = Preferences()
        val imeStore = ClipboardHistoryStore(prefs.instance)
        imeStore.addItem("saved")
        ClipboardHistoryStore(prefs.instance).removeItem("saved")
        assertTrue(imeStore.getItems().isEmpty())
    }
}
