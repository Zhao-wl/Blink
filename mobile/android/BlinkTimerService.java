package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/** Executes the on-device plan when the UI disappears; the system draws the countdown. */
public class BlinkTimerService extends Service {
    private static final int TIMER=4100, ALERT=4101;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private NotificationManager manager;
    private SharedPreferences prefs;
    private JSONArray items;
    private int cursor;
    private final Runnable tick=this::advance;
    private final BroadcastReceiver wake=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){ advance(); }
    };
    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("blink-timer",MODE_PRIVATE);
        manager=getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel("blink-clock","后台计时",NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null,null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
        registerReceiver(wake,new IntentFilter(Intent.ACTION_SCREEN_ON));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        try { items=new JSONArray(prefs.getString("plan","[]")); }
        catch(JSONException e){items=new JSONArray();}
        cursor=0;
        startForeground(TIMER,timerNotification(null));
        advance();
        return START_STICKY;
    }
    private PendingIntent open(String token){
        Intent i=new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(token!=null)i.putExtra("blinkToken",token);
        return PendingIntent.getActivity(this,token==null?0:1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification timerNotification(JSONObject item){
        boolean display=prefs.getBoolean("lock",true);
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,"blink-clock")
            .setSmallIcon(R.drawable.blink_notification).setContentIntent(open(null)).setOngoing(true)
            .setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setVisibility(display?NotificationCompat.VISIBILITY_PUBLIC:NotificationCompat.VISIBILITY_SECRET)
            .setContentTitle(item==null?"Blink 后台提醒":"下次"+item.optString("title")+"提醒");
        if(item!=null&&display)b.setWhen(item.optLong("at")).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true);
        return b.build();
    }
    private void advance(){
        handler.removeCallbacks(tick);
        if(items==null)return;
        long now=System.currentTimeMillis();
        while(cursor<items.length()){
            JSONObject item=items.optJSONObject(cursor);
            if(item==null){cursor++;continue;}
            long due=item.optLong("at");
            if(due>now){
                manager.notify(TIMER,timerNotification(item));
                handler.postDelayed(tick,due-now);
                armWakeup(due);
                return;
            }
            cursor++;

            if(!MainActivity.visible&&item.optLong("expires")>now&&due>prefs.getLong("deliveredAt",0)){
                manager.notify(ALERT,new NotificationCompat.Builder(this,prefs.getString("channel","blink-00"))
                    .setSmallIcon(R.drawable.blink_notification).setContentTitle("Blink · "+item.optString("title"))
                    .setContentText(item.optString("body")).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(open(item.optString("token"))).build());
                prefs.edit().putLong("deliveredAt",due).apply();
            }
        }
        prefs.edit().remove("plan").apply();stopSelf();
    }
    private PendingIntent alarmIntent(){
        return PendingIntent.getService(this,2,new Intent(this,BlinkTimerService.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void armWakeup(long at){
        AlarmManager am=getSystemService(AlarmManager.class);
        if(Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms())am.setAlarmClock(new AlarmManager.AlarmClockInfo(at,open(null)),alarmIntent());
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,alarmIntent());
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        handler.removeCallbacks(tick);unregisterReceiver(wake);
        getSystemService(AlarmManager.class).cancel(alarmIntent());
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
