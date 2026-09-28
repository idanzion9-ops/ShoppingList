package com.idanzion.shoppinglist;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * מעטפת האפליקציה: טוענת את האפליקציה מ-GitHub Pages.
 * כל ההיגיון (כולל הקלטה קולית: זמנים, בחירת שירות, תצוגה) נמצא בצד ה-JavaScript
 * ומתעדכן אוטומטית מגיטהאב. כאן יש רק "גשר" פשוט למיקרופון ולשירותי זיהוי הדיבור.
 */
public class MainActivity extends Activity {
    static final String APP_URL = "https://idanzion9-ops.github.io/ShoppingList/";
    private static final String APP_HOST = "idanzion9-ops.github.io";
    private static final int REQ_MIC = 41;
    private static final int REQ_SYSVOICE = 42;
    public static final String EXTRA_ACTION = "action";

    private WebView web;
    private boolean pageReady = false;
    private String pendingAction = null;

    private SpeechRecognizer recognizer;
    private String recognizerService = null;
    private long lastRms = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0f766e"));
        pendingAction = getIntent() != null ? getIntent().getStringExtra(EXTRA_ACTION) : null;

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

        web.loadUrl(APP_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String a = intent.getStringExtra(EXTRA_ACTION);
        if (a == null) return;
        if (pageReady) js("window.onNativeAction && window.onNativeAction(" + JSONObject.quote(a) + ")");
        else pendingAction = a;
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
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
    protected void onPause() {
        super.onPause();
        // יציאה מהאפליקציה באמצע הקשבה – עוצרים את המיקרופון
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            sendVoice("cancelled", null);
        }
    }

    @Override
    protected void onDestroy() {
        destroyRecognizer();
        super.onDestroy();
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

    /* ================= זיהוי דיבור ================= */

    private void sendVoice(String type, Object value) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (value != null) o.put("value", value);
            js("window.onVoiceEvent && window.onVoiceEvent(" + o.toString() + ")");
        } catch (Exception ignored) { }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
            recognizerService = null;
        }
    }

    private Intent buildIntent(JSONObject o) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        String lang = o.optString("lang", "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, o.optBoolean("partial", true));
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, o.optString("prompt", "מה להוסיף לרשימה?"));
        long silence = o.optLong("silenceMs", 0);
        if (silence > 0) {
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence);
        }
        long min = o.optLong("minMs", 0);
        if (min > 0) i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, min);
        if (o.has("preferOffline")) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, o.optBoolean("preferOffline"));
        return i;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { sendVoice("ready", null); }
        @Override public void onBeginningOfSpeech() { sendVoice("begin", null); }
        @Override public void onRmsChanged(float rmsdB) {
            long now = System.currentTimeMillis();
            if (now - lastRms < 60) return;
            lastRms = now;
            sendVoice("rms", (double) rmsdB);
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { sendVoice("end", null); }
        @Override public void onError(int error) { sendVoice("error", error); }
        @Override public void onResults(Bundle b) { sendVoice("results", first(b)); }
        @Override public void onPartialResults(Bundle b) { sendVoice("partial", first(b)); }
        @Override public void onEvent(int eventType, Bundle params) { }
    };

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r != null && !r.isEmpty() && r.get(0) != null) ? r.get(0) : "";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            sendVoice("permission", ok);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SYSVOICE) return;
        String text = "";
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty() && r.get(0) != null) text = r.get(0);
        }
        sendVoice("sysresult", text);
    }

    /** פונקציות שהאפליקציה (JavaScript) יכולה לקרוא להן דרך window.Android */
    class Bridge {

        /* ----- הקלטה קולית ----- */

        /** רשימת שירותי זיהוי הדיבור בטלפון: [{id, name, pkg}] */
        @JavascriptInterface
        public String listServices() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                List<ResolveInfo> list = pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
                for (ResolveInfo ri : list) {
                    if (ri.serviceInfo == null) continue;
                    ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                    String label;
                    try {
                        label = pm.getApplicationLabel(pm.getApplicationInfo(cn.getPackageName(), 0)).toString();
                    } catch (Exception e) {
                        label = cn.getPackageName();
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", cn.flattenToString());
                    o.put("name", label);
                    o.put("pkg", cn.getPackageName());
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean isRecognitionAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public boolean hasMicPermission() {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestMicPermission() {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        }

        /** מתחיל הקשבה. serviceId = "default" או מזהה שירות מ-listServices. options = JSON */
        @JavascriptInterface
        public void voiceStart(String serviceId, String options) {
            runOnUiThread(() -> {
                try {
                    JSONObject o = new JSONObject(options == null || options.isEmpty() ? "{}" : options);
                    String sid = serviceId == null || serviceId.isEmpty() ? "default" : serviceId;
                    if (recognizer == null || !sid.equals(recognizerService)) {
                        destroyRecognizer();
                        ComponentName cn = "default".equals(sid) ? null : ComponentName.unflattenFromString(sid);
                        recognizer = cn != null
                                ? SpeechRecognizer.createSpeechRecognizer(MainActivity.this, cn)
                                : SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                        recognizer.setRecognitionListener(listener);
                        recognizerService = sid;
                    } else {
                        try { recognizer.cancel(); } catch (Exception ignored) { }
                    }
                    recognizer.startListening(buildIntent(o));
                    sendVoice("started", sid);
                } catch (Exception e) {
                    destroyRecognizer();
                    sendVoice("error", -2);
                }
            });
        }

        /** סיום הקשבה וקבלת תוצאה סופית */
        @JavascriptInterface
        public void voiceStop() {
            runOnUiThread(() -> { if (recognizer != null) try { recognizer.stopListening(); } catch (Exception ignored) { } });
        }

        /** ביטול הקשבה בלי תוצאה */
        @JavascriptInterface
        public void voiceCancel() {
            runOnUiThread(() -> { if (recognizer != null) try { recognizer.cancel(); } catch (Exception ignored) { } });
        }

        /** שחרור מלא של שירות הדיבור */
        @JavascriptInterface
        public void voiceDestroy() {
            runOnUiThread(MainActivity.this::destroyRecognizer);
        }

        /** מסך הדיבור המובנה של הטלפון (אם קיים). התוצאה מגיעה כאירוע sysresult */
        @JavascriptInterface
        public void voiceSystemDialog(String options) {
            runOnUiThread(() -> {
                try {
                    JSONObject o = new JSONObject(options == null || options.isEmpty() ? "{}" : options);
                    startActivityForResult(buildIntent(o), REQ_SYSVOICE);
                } catch (ActivityNotFoundException e) {
                    sendVoice("error", -3);
                } catch (Exception e) {
                    sendVoice("error", -2);
                }
            });
        }

        /** פעולה שהאפליקציה נפתחה בשבילה (למשל "voice" מהווידג'ט). מוחזרת פעם אחת */
        @JavascriptInterface
        public String getLaunchAction() {
            String a = pendingAction;
            pendingAction = null;
            return a == null ? "" : a;
        }

        /* ----- כללי ----- */

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

        /** טעינה מחדש נקייה – מוחק את המטמון ומביא את הגרסה החדשה מגיטהאב */
        @JavascriptInterface
        public void hardReload() {
            runOnUiThread(() -> {
                web.clearCache(true);
                web.loadUrl(APP_URL);
            });
        }
    }
}
