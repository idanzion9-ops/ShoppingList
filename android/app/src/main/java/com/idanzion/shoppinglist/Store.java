package com.idanzion.shoppinglist;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

/** אחסון משותף בין הווידג'ט לאפליקציה */
public final class Store {
    private static final String PREFS = "shopping";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_COUNT = "count";

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** שומר טקסט שנאמר בווידג'ט עד שהאפליקציה תיפתח ותוסיף אותו לרשימה */
    public static synchronized void addPending(Context c, String text) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_PENDING, "[]"));
            arr.put(text);
            prefs(c).edit().putString(KEY_PENDING, arr.toString()).apply();
        } catch (Exception e) {
            JSONArray arr = new JSONArray();
            arr.put(text);
            prefs(c).edit().putString(KEY_PENDING, arr.toString()).apply();
        }
    }

    public static synchronized String takePending(Context c) {
        String s = prefs(c).getString(KEY_PENDING, "[]");
        prefs(c).edit().putString(KEY_PENDING, "[]").commit();
        return s;
    }

    public static int pendingCount(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_PENDING, "[]")).length();
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setCount(Context c, int count) {
        prefs(c).edit().putInt(KEY_COUNT, count).apply();
    }

    public static int getCount(Context c) {
        return prefs(c).getInt(KEY_COUNT, -1);
    }

    /** ניקוי מילות פקודה להצגה בהודעה בלבד (הניתוח המלא נעשה באפליקציה) */
    public static String cleanForDisplay(String text) {
        String[] prefixes = {"תוסיף", "תוסיפי", "הוסף", "הוסיפי", "להוסיף", "תכניס", "תרשום", "תשים",
                "בבקשה", "גם", "את", "לרשימה", "לי"};
        String t = text.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String p : prefixes) {
                if (t.equals(p)) { t = ""; changed = true; break; }
                if (t.startsWith(p + " ")) {
                    t = t.substring(p.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        if (t.endsWith(" בבקשה")) t = t.substring(0, t.length() - 6).trim();
        return t.isEmpty() ? text : t;
    }
}
