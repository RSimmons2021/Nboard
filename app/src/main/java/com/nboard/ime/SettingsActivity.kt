package com.nboard.ime

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** A compact category menu, with the existing settings kept as detail pages. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeStyleFor(KeyboardModeSettings.loadThemeMode(this)))
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "Nboard settings"
            textSize = 26f
            setPadding(0, dp(12), 0, dp(20))
        })
        content.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Try your keyboard"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, KeyboardTestActivity::class.java)) }
        })
        val categories = listOf(
            Triple("System", "Enable keyboard and choose your default", "System"),
            Triple("Languages", "Layout, autocorrect and language profiles", "Language settings"),
            Triple("Preferences", "Number row, key vibration and shortcuts", "Preferences"),
            Triple("Text correction", "Predictions, fluid motion and typing behavior", "Text correction"),
            Triple("Clipboard", "Persistent history and pinned clips", "Clipboard"),
            Triple("Theme", "Nothing colors and keyboard font", "Theme settings"),
            Triple("AI assistance", "ChatGPT plan or your own provider", "AI settings"),
            Triple("About", "Libraries and original project", "Licences")
        )
        categories.forEach { (title, subtitle, section) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(80)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                isFocusable = true
                val value = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
                setOnClickListener {
                    if (section == "Clipboard") startActivity(Intent(this@SettingsActivity, ClipboardSettingsActivity::class.java))
                    else startActivity(Intent(this@SettingsActivity, MainActivity::class.java).putExtra("section", section))
                }
            }
            row.addView(TextView(this).apply { text = "$title  ›"; textSize = 17f })
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                alpha = 0.65f
                setPadding(0, dp(4), 0, 0)
            })
            content.addView(row)
            content.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.key_text))
                alpha = 0.1f
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20), bars.top + dp(24), dp(20), bars.bottom + dp(24))
            insets
        }
        ViewCompat.requestApplyInsets(content)
        if (!KeyboardModeSettings.loadOnboardingCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
