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
 * חלון הקשבה קולי. האפליקציה עצמה מנהלת את זמני ההקשבה
 * (ולא שירות הדיבור של הטלפון, שנוטה לעצור מהר מדי):
 *  - WAIT_START_MS: כמה זמן לחכות שיתחילו לדבר
 *  - SILENCE_END_MS: כמה שניות שקט אחרי הדיבור עד סיום ההקלטה
 * אם שירות הדיבור נעצר באמצע – ההקשבה מתחדשת אוטומטית והטקסט מצטבר.
 */
public class VoiceActivity extends Activity implements RecognitionListener {
    public static final String EXTRA_RETURN = "return_result";
    public static final String EXTRA_TEXT = "text";
    private static final int REQ_PERM = 31;
    private static final int REQ_FALLBACK = 32;

    /** זמן המתנה לתחילת הדיבור */
    private static final long WAIT_START_MS = 2000;
    /** זמן שקט אחרי הדיבור עד סיום ההקלטה */
    private static final long SILENCE_END_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private ComponentName service;
    private TextView status, heard, mic;
    private boolean returnMode;

    private boolean active;        // אנחנו בתוך סשן הקשבה
    private boolean done;          // נמסר / בוטל
    private long deadline;
    private long sessionStart;         // מתי לסיים אם לא יהיה דיבור נוסף (0 = עוד לא מוכן)
    private final StringBuilder collected = new StringBuilder();
    private String partial = "";

    private final Runnable checker = new Runnable() {
        @Override public void run() {
            if (done || !active) return;
            long now = System.currentTimeMillis();
            // אם השירות מתעכב בהתחברות – לא מחכים לנצח
            if (deadline == 0 && now - sessionStart > 5000) deadline = now + WAIT_START_MS;
            if (deadline > 0 && now >= deadline) { finishListening(); return; }
            if (deadline > 0) {
                long left = (deadline - now + 999) / 1000;
                boolean any = collected.length() > 0 || !partial.isEmpty();
                status.setText(any ? "ממשיך להקשיב… (" + left + ")" : "מקשיב… דברו עכשיו (" + left + ")");
            }
            handler.postDelayed(this, 200);
        }
    };

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
        // לחיצה על המיקרופון: בזמן הקשבה – מסיים מיד; אחרת – מתחיל מחדש
        mic.setOnClickListener(v -> { if (active) finishListening(); else startSession(); });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("צריך אישור להשתמש במיקרופון");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        } else {
            startSession();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSession();
        else fallback();
    }

    /* ---------- ניהול סשן ---------- */

    private void startSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fallback(); return; }
        if (service == null) service = pickService();
        collected.setLength(0);
        partial = "";
        heard.setText("");
        status.setText("מתחבר…");
        deadline = 0;
        sessionStart = System.currentTimeMillis();
        active = true;
        handler.removeCallbacks(checker);
        handler.post(checker);
        listen();
    }

    /** מתחיל הקשבה עם מזהה דיבור חדש (מונע תקלות "עסוק" בהפעלה חוזרת) */
    private void listen() {
        if (done || !active) return;
        destroyRecognizer();
        try {
            recognizer = service != null
                    ? SpeechRecognizer.createSpeechRecognizer(this, service)
                    : SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            recognizer.startListening(recognizeIntent());
        } catch (Exception e) {
            active = false;
            fallback();
        }
    }

    private void restartSoon() {
        handler.postDelayed(this::listen, 150);
    }

    private void extendDeadline() {
        deadline = System.currentTimeMillis() + SILENCE_END_MS;
    }

    private String fullText() {
        String t = collected.toString().trim();
        String p = partial.trim();
        if (p.isEmpty()) return t;
        return t.isEmpty() ? p : t + " " + p;
    }

    private void finishListening() {
        active = false;
        handler.removeCallbacks(checker);
        String text = fullText();
        destroyRecognizer();
        resetMic();
        if (!text.isEmpty()) {
            deliver(text);
        } else {
            status.setText("לא נקלט דיבור. לחצו על 🎤 לנסות שוב");
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void resetMic() {
        mic.setScaleX(1f);
        mic.setScaleY(1f);
    }

    /** מעדיף שירות זיהוי של Google (תומך בעברית), אחרת ברירת המחדל של הטלפון */
    private ComponentName pickService() {
        try {
            List<ResolveInfo> list = getPackageManager().queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo != null && ri.serviceInfo.packageName.contains("google")) {
                    return new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private Intent recognizeIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "מה להוסיף לרשימה?");
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_END_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_END_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L);
        return i;
    }

    /** גיבוי: מסך הדיבור המובנה של הטלפון, אם קיים */
    private void fallback() {
        try {
            startActivityForResult(recognizeIntent(), REQ_FALLBACK);
        } catch (ActivityNotFoundException e) {
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
        active = false;
        setResult(RESULT_CANCELED);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }

    /* ---------- RecognitionListener ---------- */

    @Override
    public void onReadyForSpeech(Bundle params) {
        // הפעם הראשונה שהמיקרופון מוכן: מתחילים לספור את זמן ההמתנה לדיבור
        if (deadline == 0) deadline = System.currentTimeMillis() + WAIT_START_MS;
    }

    @Override
    public void onBeginningOfSpeech() {
        extendDeadline();
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        float s = 1f + Math.max(0f, Math.min(rmsdB, 10f)) / 25f;
        mic.setScaleX(s);
        mic.setScaleY(s);
    }

    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { resetMic(); }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onPartialResults(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty()) return;
        String p = r.get(0) == null ? "" : r.get(0).trim();
        if (!p.isEmpty() && !p.equals(partial)) {
            partial = p;
            extendDeadline();
            heard.setText(fullText());
        }
    }

    @Override
    public void onResults(Bundle b) {
        if (!active) return;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = (r != null && !r.isEmpty() && r.get(0) != null) ? r.get(0).trim() : "";
        if (t.isEmpty()) t = partial.trim();
        partial = "";
        if (!t.isEmpty()) {
            if (collected.length() > 0) collected.append(" ");
            collected.append(t);
            heard.setText(collected.toString());
            extendDeadline(); // ממשיכים להקשיב עוד 3 שניות למקרה שיש המשך
        }
        restartSoon();
    }

    @Override
    public void onError(int error) {
        if (!active || done) return;
        resetMic();
        // מה שכבר נשמע נשמר
        if (!partial.isEmpty()) {
            if (collected.length() > 0) collected.append(" ");
            collected.append(partial);
            partial = "";
            heard.setText(collected.toString());
        }
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                // השירות עצר – ממשיכים להקשיב עד שנגמר הזמן שלנו
                if (deadline == 0) deadline = System.currentTimeMillis() + WAIT_START_MS;
                restartSoon();
                return;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                if (collected.length() > 0) { finishListening(); return; }
                active = false;
                handler.removeCallbacks(checker);
                status.setText("אין חיבור לאינטרנט. לחצו על 🎤 לנסות שוב");
                return;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
            case 12: // שפה לא נתמכת
            case 13: // שפה לא זמינה
                active = false;
                handler.removeCallbacks(checker);
                fallback();
                return;
            default:
                if (collected.length() > 0) { finishListening(); return; }
                active = false;
                handler.removeCallbacks(checker);
                status.setText("שגיאה בזיהוי (" + error + "). לחצו על 🎤 לנסות שוב");
        }
    }
}
