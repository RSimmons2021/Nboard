package com.nboard.ime

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class KeyboardTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeStyleFor(KeyboardModeSettings.loadThemeMode(this)))
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset * 3, inset, inset)
        }
        content.addView(TextView(this).apply { text = "Try your keyboard"; textSize = 26f })
        content.addView(TextView(this).apply {
            text = "Type, backspace, and tap predictions to feel the motion. Hold Q–P for 1–0. Open the tools arrow for clipboard and settings."
            textSize = 15f
            setPadding(0, 20, 0, 20)
        })
        val input = EditText(this).apply {
            hint = "Start typing here…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        content.addView(input)
        content.addView(MaterialButton(this).apply {
            text = "Choose keyboard"
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        })
        setContentView(content)
        input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 250)
    }
}
