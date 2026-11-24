package com.zhaowl.blink;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;

/** Home-screen widget: the next reminder with a live countdown and a one-tap check-in. */
public class BlinkWidget extends AppWidgetProvider {
    private static final String CHECKIN="com.zhaowl.blink.WIDGET_CHECKIN";

    static void refresh(Context context){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int[] ids=manager.getAppWidgetIds(new ComponentName(context,BlinkWidget.class));
        if(ids.length>0)manager.updateAppWidget(ids,views(context));
    }

    private static RemoteViews views(Context context){
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.blink_widget);
        JSONObject alert=ReminderAction.active(context),next=BlinkTimerAlarm.next(context);
        long away=ReminderAction.prefs(context).getLong("awaySince",0);
        long now=System.currentTimeMillis();
        if(alert!=null){
            views.setTextViewText(R.id.widget_title,"该"+alert.optString("title")+"了");
            views.setViewVisibility(R.id.widget_timer,View.GONE);
            views.setTextViewText(R.id.widget_action,ReminderAction.LABEL[Math.max(0,Math.min(2,alert.optInt("k")))]);
        }else if(away>0){
            views.setTextViewText(R.id.widget_title,"已暂停 · 回来后继续");
            views.setViewVisibility(R.id.widget_timer,View.GONE);
            views.setTextViewText(R.id.widget_action,"打开");
        }else if(next!=null){
            views.setTextViewText(R.id.widget_title,"下次"+next.optString("title"));
            views.setViewVisibility(R.id.widget_timer,View.VISIBLE);
            views.setChronometer(R.id.widget_timer,SystemClock.elapsedRealtime()+(next.optLong("at")-now),null,true);
            views.setChronometerCountDown(R.id.widget_timer,true);
            views.setTextViewText(R.id.widget_action,"打开");
        }else{
            views.setTextViewText(R.id.widget_title,"Blink");
            views.setViewVisibility(R.id.widget_timer,View.GONE);
            views.setTextViewText(R.id.widget_action,"打开");
        }
        Intent tap=new Intent(context,BlinkWidget.class).setAction(CHECKIN);
        views.setOnClickPendingIntent(R.id.widget_action,PendingIntent.getBroadcast(context,20,tap,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        views.setOnClickPendingIntent(R.id.widget_root,BlinkTimerAlarm.open(context,null));
        return views;
    }

    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){manager.updateAppWidget(ids,views(context));}

    @Override public void onReceive(Context context,Intent intent){
        if(CHECKIN.equals(intent.getAction())){
            JSONObject alert=ReminderAction.active(context);
            if(alert!=null)ReminderAction.confirm(context,alert.optString("token"));
            else{
                try{BlinkTimerAlarm.open(context,null).send();}catch(PendingIntent.CanceledException ignored){/* app gone */}
            }
            refresh(context);
            return;
        }
        super.onReceive(context,intent);
    }
}
