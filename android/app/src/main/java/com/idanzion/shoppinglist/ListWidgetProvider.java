package com.idanzion.shoppinglist;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** ווידג'ט מסך הבית: כפתור מיקרופון להזנה קולית + פתיחת הרשימה */
public class ListWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    public static void refreshAll(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        int[] ids = m.getAppWidgetIds(new ComponentName(context, ListWidgetProvider.class));
        for (int id : ids) update(context, m, id);
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget);

        Intent voice = new Intent(context, VoiceActivity.class);
        voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent voicePi = PendingIntent.getActivity(context, 1, voice,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_mic, voicePi);

        // תג קטן: כמה פריטים נשארו לקנות (כולל מה שנאמר בווידג'ט ועוד לא נפתח)
        int count = Math.max(0, Store.getCount(context)) + Store.pendingCount(context);
        if (count > 0) {
            v.setTextViewText(R.id.widget_badge, count > 99 ? "99+" : String.valueOf(count));
            v.setViewVisibility(R.id.widget_badge, android.view.View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.widget_badge, android.view.View.GONE);
        }

        manager.updateAppWidget(id, v);
    }
}
