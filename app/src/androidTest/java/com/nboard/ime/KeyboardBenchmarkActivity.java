package com.nboard.ime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Independent test editor; uses only Android classes because the test APK runs alone. */
public final class KeyboardBenchmarkActivity extends Activity {
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("finish", false)) finish();
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 80, 32, 32);
        TextView title = new TextView(this);
        title.setText("Nboard performance test");
        title.setTextSize(24f);
        content.addView(title);
        EditText input = new EditText(this);
        input.setHint("Synthetic typing fixture");
        input.setMinLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        content.addView(input);
        setContentView(content);
        input.requestFocus();
        input.postDelayed(() -> getSystemService(InputMethodManager.class)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 250);
    }
}
