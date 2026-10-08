package com.nboard.ime;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** A Chromium editor in the test APK, containing only synthetic text. */
public class KeyboardBrowserTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView browser = new WebView(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                InputConnection connection = super.onCreateInputConnection(info);
                info.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
                return connection;
            }
        };
        browser.getSettings().setJavaScriptEnabled(true);
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("document.getElementById('editor').focus()", null);
                view.postDelayed(() -> getSystemService(InputMethodManager.class)
                    .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT), 300);
            }
        });
        setContentView(browser);
        browser.requestFocus();
        String mode = getIntent().getStringExtra("editorMode");
        String attributes = " id='editor' autocapitalize='none' autocomplete='off' style='font-size:24px;width:95%;min-height:160px'";
        String field = "input".equals(mode) ? "<input type='text'" + attributes + ">" :
            "contenteditable".equals(mode) ? "<div contenteditable='true'" + attributes + "></div>" :
            "<textarea" + attributes + "></textarea>";
        browser.loadDataWithBaseURL("https://keyboard-test.invalid/", "<!doctype html><meta name='viewport' content='width=device-width'><p>Keyboard browser regression test</p>" + field,
            "text/html", "UTF-8", null);
    }
}
