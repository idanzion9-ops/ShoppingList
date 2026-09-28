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

        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(context, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_open, openPi);

        int count = Store.getCount(context);
        int pending = Store.pendingCount(context);
        String status;
        if (count < 0) status = "לחצו לפתיחה";
        else if (count == 0) status = "הרשימה ריקה";
        else status = count + " פריטים לקנות";
        if (pending > 0) status += " · " + pending + " חדשים";
        v.setTextViewText(R.id.widget_status, status);

        manager.updateAppWidget(id, v);
    }
}
