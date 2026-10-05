package com.nboard.ime

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nboard.ime.clipboard.ClipboardHistoryStore

class ClipboardSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeStyleFor(KeyboardModeSettings.loadThemeMode(this)))
        super.onCreate(savedInstanceState)
        val store = ClipboardHistoryStore(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding + (32 * resources.displayMetrics.density).toInt(), padding, padding)
        }
        content.addView(TextView(this).apply { text = "Clipboard"; textSize = 26f })
        content.addView(TextView(this).apply {
            text = "Keep up to 50 text clips on this phone, with no time limit. When full, the oldest unpinned clip is replaced. Pins stay; if all 50 are pinned, new clips are not saved. Open the clipboard from the keyboard toolbar; tap to paste or hold to pin or delete."
            textSize = 15f
            setPadding(0, 24, 0, 24)
        })
        content.addView(SwitchMaterial(this).apply {
            text = "Save copied text"
            isChecked = KeyboardModeSettings.loadClipboardHistoryEnabled(this@ClipboardSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                KeyboardModeSettings.saveClipboardHistoryEnabled(this@ClipboardSettingsActivity, checked)
            }
        })
        val count = TextView(this)
        fun refreshCount() {
            val items = store.getItems()
            count.text = "${items.size} saved clips · ${items.count { it.pinned }} pinned"
        }
        refreshCount()
        content.addView(count)
        content.addView(MaterialButton(this).apply {
            text = "Clear unpinned clips"
            setOnClickListener {
                AlertDialog.Builder(this@ClipboardSettingsActivity)
                    .setTitle("Clear saved clips?")
                    .setMessage("Pinned clips will be kept.")
                    .setPositiveButton("Clear") { _, _ -> store.clearUnpinned(); refreshCount() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        setContentView(content)
    }
}
