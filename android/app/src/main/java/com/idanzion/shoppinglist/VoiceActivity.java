package com.idanzion.shoppinglist;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.util.ArrayList;

/** מסך שקוף: מקשיב לדיבור, שומר את הפריט ונסגר – בלי לפתוח את האפליקציה */
public class VoiceActivity extends Activity {
    private static final int REQ = 11;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // כבר מקשיב
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "מה להוסיף לרשימה?");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "לא נמצא שירות זיהוי דיבור בטלפון (אפליקציית Google)", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res != null && !res.isEmpty() && res.get(0).trim().length() > 0) {
                String text = res.get(0).trim();
                Store.addPending(this, text);
                Toast.makeText(this, "🛒 " + Store.cleanForDisplay(text) + " – נשמר לרשימה", Toast.LENGTH_SHORT).show();
                ListWidgetProvider.refreshAll(this);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
