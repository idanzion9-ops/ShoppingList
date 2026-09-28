package com.idanzion.shoppinglist;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * חלון הקשבה קולי (המנגנון של גרסה 1.2 שעבד, עם 3 שניות שקט אחרי הדיבור).
 * מהווידג'ט: שומר את הפריט ונסגר בלי לפתוח את האפליקציה.
 * מתוך האפליקציה (EXTRA_RETURN): מחזיר את הטקסט לאפליקציה.
 */
public class VoiceActivity extends Activity implements RecognitionListener {
    public static final String EXTRA_RETURN = "return_result";
    public static final String EXTRA_TEXT = "text";
    private static final int REQ_PERM = 31;
    private static final int REQ_FALLBACK = 32;
    /** כמה זמן לחכות שיתחילו לדבר (מילישניות) */
    private static final long WAIT_MS = 15000;
    /** כמה שקט אחרי הדיבור עד סיום ההקלטה */
    private static final long SILENCE_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startTime;
    private boolean speaking;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (done || !listening || speaking) return;
            long left = (WAIT_MS - (System.currentTimeMillis() - startTime)) / 1000;
            if (left < 0) left = 0;
            status.setText("מקשיב… דברו עכשיו (" + left + ")");
            handler.postDelayed(this, 1000);
        }
    };

    private SpeechRecognizer recognizer;
    private final List<ComponentName> services = new ArrayList<>();
    private int serviceIndex = 0;
    private TextView status, heard, mic;
    private boolean returnMode;
    private boolean listening;
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.voice_dialog);
        status = findViewById(R.id.voice_status);
        heard = findViewById(R.id.voice_heard);
        mic = findViewById(R.id.voice_mic);
        returnMode = getIntent().getBooleanExtra(EXTRA_RETURN, false);

        findViewById(R.id.voice_root).setOnClickListener(v -> cancel());
        findViewById(R.id.voice_cancel).setOnClickListener(v -> cancel());
        mic.setOnClickListener(v -> { if (!listening) start(); else if (recognizer != null) recognizer.stopListening(); });
        startTime = System.currentTimeMillis();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("צריך אישור להשתמש במיקרופון");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        } else {
            start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else fallback();
    }

    /**
     * סדר העדפה של שירותי זיהוי דיבור:
     * 1. אפליקציית Google (מקוון – תומך בעברית)
     * 2. ברירת המחדל של הטלפון
     * 3. שאר השירותים (כולל שירותים לא-מקוונים שאולי אין בהם עברית)
     */
    private void buildServiceList() {
        services.clear();
        List<ComponentName> others = new ArrayList<>();
        ComponentName googleApp = null;
        try {
            List<ResolveInfo> list = getPackageManager().queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo == null) continue;
                ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                if ("com.google.android.googlequicksearchbox".equals(ri.serviceInfo.packageName)) googleApp = cn;
                else others.add(cn);
            }
        } catch (Exception ignored) { }
        if (googleApp != null) services.add(googleApp);
        services.add(null); // ברירת המחדל של הטלפון
        services.addAll(others);
        serviceIndex = 0;
    }

    /** מעבר לשירות הבא ברשימה. מחזיר false אם אין עוד */
    private boolean nextService() {
        if (serviceIndex + 1 >= services.size()) return false;
        serviceIndex++;
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        return true;
    }

    private Intent recognizeIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "מה להוסיף לרשימה?");
        // 3 שניות שקט אחרי הדיבור לפני סיום ההקלטה
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L);
        return i;
    }

    private void start() {
        startTime = System.currentTimeMillis();
        heard.setText("");
        listen();
    }

    /** מתחיל (או מחדש) הקשבה בלי לאפס את חלון ההמתנה */
    private void listen() {
        if (done) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fallback(); return; }
        try {
            if (services.isEmpty()) buildServiceList();
            if (recognizer == null) {
                ComponentName svc = services.get(serviceIndex);
                recognizer = svc != null
                        ? SpeechRecognizer.createSpeechRecognizer(this, svc)
                        : SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
            }
            if (System.currentTimeMillis() - startTime < 500) status.setText("מתחבר…");
            listening = true;
            speaking = false;
            mic.setAlpha(1f);
            recognizer.startListening(recognizeIntent());
        } catch (Exception e) {
            fallback();
        }
    }

    /** גיבוי: מסך הדיבור המובנה של הטלפון, אם קיים */
    private void fallback() {
        try {
            startActivityForResult(recognizeIntent(), REQ_FALLBACK);
        } catch (ActivityNotFoundException e) {
            listening = false;
            status.setText("לא נמצא שירות זיהוי דיבור בטלפון.\nהתקינו או הפעילו את אפליקציית Google מחנות Play ונסו שוב.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FALLBACK) return;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res != null && !res.isEmpty()) { deliver(res.get(0)); return; }
        }
        cancel();
    }

    private void deliver(String text) {
        if (done) return;
        done = true;
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) { cancel(); return; }
        if (returnMode) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text));
        } else {
            Store.addPending(this, text);
            Toast.makeText(this, "🛒 " + Store.cleanForDisplay(text) + " – נשמר לרשימה", Toast.LENGTH_SHORT).show();
            ListWidgetProvider.refreshAll(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void cancel() {
        done = true;
        setResult(RESULT_CANCELED);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    /* ---------- RecognitionListener ---------- */
    @Override public void onReadyForSpeech(Bundle params) {
        handler.removeCallbacks(ticker);
        ticker.run();
    }
    @Override public void onBeginningOfSpeech() { speaking = true; status.setText("מקשיב…"); }
    @Override public void onRmsChanged(float rmsdB) {
        float s = 1f + Math.max(0f, Math.min(rmsdB, 10f)) / 25f;
        mic.setScaleX(s);
        mic.setScaleY(s);
    }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { status.setText("מעבד…"); mic.setScaleX(1f); mic.setScaleY(1f); }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onPartialResults(Bundle partial) {
        ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty()) heard.setText(r.get(0));
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty() && r.get(0).trim().length() > 0) deliver(r.get(0));
        else onError(SpeechRecognizer.ERROR_NO_MATCH);
    }

    @Override
    public void onError(int error) {
        listening = false;
        speaking = false;
        handler.removeCallbacks(ticker);
        if (done) return;
        // כבר נקלטו מילים – משתמשים בהן
        String partial = heard.getText().toString().trim();
        if (!partial.isEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            deliver(partial);
            return;
        }
        // עדיין לא דיברו – ממשיכים להקשיב עד שנגמר זמן ההמתנה
        boolean retryable = error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
        if (retryable && System.currentTimeMillis() - startTime < WAIT_MS) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            handler.postDelayed(this::listen, 300);
            return;
        }
        mic.setScaleX(1f);
        mic.setScaleY(1f);
        String msg;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                msg = "לא נקלט. לחצו על 🎤 ונסו שוב";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                msg = "אין חיבור לאינטרנט. לחצו על 🎤 לנסות שוב";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                fallback();
                return;
            case 12: // שפה לא נתמכת
            case 13: // שפה לא זמינה
            case 11: // השירות התנתק (בחלק מהשירותים – אין מודל שפה)
                if (nextService()) {
                    status.setText("מחפש שירות דיבור שתומך בעברית…");
                    startTime = System.currentTimeMillis();
                    handler.postDelayed(this::listen, 300);
                } else {
                    fallback();
                }
                return;
            default:
                msg = "שגיאה בזיהוי (" + error + "). לחצו על 🎤 לנסות שוב";
        }
        status.setText(msg);
    }
}
