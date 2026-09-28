package com.idanzion.shoppinglist;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * מעטפת האפליקציה: טוענת את האפליקציה מ-GitHub Pages.
 * כל עדכון שנדחף לגיטהאב מופיע אוטומטית בפתיחה הבאה – בלי להתקין מחדש.
 */
public class MainActivity extends Activity {
    static final String APP_URL = "https://idanzion9-ops.github.io/ShoppingList/";
    private static final String APP_HOST = "idanzion9-ops.github.io";
    private static final int REQ_VOICE = 21;

    private WebView web;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0f766e"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f3f6f5"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
    }

    private void showOffline() {
        String html = "<html dir='rtl'><body style='font-family:sans-serif;text-align:center;padding:60px 20px;background:#f3f6f5;color:#1a2421'>"
                + "<div style='font-size:56px'>📶</div><h2>אין חיבור לאינטרנט</h2>"
                + "<p>בפתיחה הראשונה צריך אינטרנט. אחרי זה האפליקציה עובדת גם בלי קליטה.</p>"
                + "<button onclick='Android.reload()' style='font-size:18px;padding:14px 28px;border:0;border-radius:14px;background:#0f766e;color:#fff'>נסה שוב</button>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "לא ניתן לפתוח קישור", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) web.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) systemBack();
        });
    }

    private void systemBack() {
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (res == null || res.isEmpty()) return;
        String js = "window.onVoiceResult && window.onVoiceResult(" + JSONObject.quote(res.get(0)) + ")";
        web.evaluateJavascript(js, null);
    }

    /** פונקציות שהאפליקציה (JavaScript) יכולה לקרוא להן דרך window.Android */
    class Bridge {
        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "מה להוסיף לרשימה?");
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try {
                    startActivityForResult(i, REQ_VOICE);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "לא נמצא שירות זיהוי דיבור (אפליקציית Google)", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String takePendingItems() {
            String s = Store.takePending(MainActivity.this);
            ListWidgetProvider.refreshAll(MainActivity.this);
            return s;
        }

        @JavascriptInterface
        public void updateWidget(int count) {
            Store.setCount(MainActivity.this, count);
            ListWidgetProvider.refreshAll(MainActivity.this);
        }

        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "שיתוף"));
            });
        }

        @JavascriptInterface
        public int getNativeVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                return 1;
            }
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> web.loadUrl(APP_URL));
        }
    }
}
