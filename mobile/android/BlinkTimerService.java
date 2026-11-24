package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/**
 * Keeps the reminder session alive while Blink runs in the background and owns the single
 * Blink card: a countdown (Live Update on Android 16+) that turns into the reminder when due.
 */
public class BlinkTimerService extends Service {
    private static BlinkTimerService running;
    static final int TIMER=4100;
    private static final String CHANNEL="blink-countdown-tip";
    private NotificationManager manager;
    private SharedPreferences prefs;
    private JSONObject upcoming;
    private long alertExpires;
    private String alertToken;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable awakeDeadline=()->BlinkTimerAlarm.dispatch(this,true);
    private final Runnable alertExpired=()->{if(alertToken!=null)ReminderAction.clear(this,alertToken);};
    private final BroadcastReceiver presence=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){present(context,System.currentTimeMillis());}
    };

    static boolean isRunning(){return running!=null;}
    static void start(Context ctx){
        Intent service=new Intent(ctx,BlinkTimerService.class);
        try{
            if(Build.VERSION.SDK_INT>=26)ctx.startForegroundService(service);else ctx.startService(service);
        }catch(RuntimeException ignored){/* Background start not allowed: alarms still deliver. */}
    }

    /** Unlocking the phone (or using Blink) is presence: end a pause and tell the web view. */
    static void present(Context context,long at){
        SharedPreferences prefs=ReminderAction.prefs(context);
        long away=prefs.getLong("awaySince",0);
        prefs.edit().putLong("lastActive",at).putInt("unanswered",0).remove("awaySince").remove("lastRetry").apply();
        try{
            BlinkTimerPlugin.emit(context,new JSONObject().put("type","present").put("at",at).put("awaySince",away));
        }catch(JSONException ignored){/* constant keys */}
        if(away>0)BlinkTimerAlarm.dispatch(context,false);
    }

    @Override public void onCreate(){
        super.onCreate();
        running=this;
        prefs=ReminderAction.prefs(this);
        manager=getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26){
            int importance="Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                ?NotificationManager.IMPORTANCE_DEFAULT:NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel=new NotificationChannel(CHANNEL,"锁屏倒计时",importance);
            channel.setSound(null,null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
        IntentFilter filter=new IntentFilter(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(presence,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(presence,filter);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        boolean closed=intent==null&&!BlinkRules.keepSession(BlinkTimerAlarm.persistent(this),BlinkTimerAlarm.hasOpenTask(this));
        upcoming=closed?null:BlinkTimerAlarm.next(this);
        startForeground(TIMER,timerNotification(upcoming));
        if(closed){BlinkTimerAlarm.closeSession(this);return START_NOT_STICKY;}
        if(upcoming==null&&prefs.getLong("awaySince",0)==0){stopSelf();return START_NOT_STICKY;}
        scheduleAwakeDeadline();
        return START_STICKY;
    }

    private static String clock(Context c,long at){
        return android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(at));
    }

    private Notification timerNotification(JSONObject item){
        boolean display=prefs.getBoolean("lock",true);
        long now=System.currentTimeMillis(),away=prefs.getLong("awaySince",0);
        String title,text;
        if(away>0){title="Blink · 已暂停";text="没有响应提醒，解锁手机后继续";}
        else if(item==null){title="Blink 守护中";text="当前没有待发提醒";}
        else if(item.optBoolean("notice")){title="Blink 守护中 · "+clock(this,item.optLong("at"))+" 开始用眼计时";text="到点会提醒你";}
        else{title="Blink 守护中 · "+clock(this,item.optLong("at"))+" "+item.optString("title");text="下次"+item.optString("title")+"提醒";}
        long deadline=item==null?0:item.optLong("at");
        if(Build.VERSION.SDK_INT>=36&&display&&item!=null&&away==0){
            // Android 16 Live Update: lock screen card, status bar chip and a moving progress bar.
            long start=prefs.getLong("intervalStart",now);
            if(start>=deadline||now-start>6*3600000L){start=now;prefs.edit().putLong("intervalStart",now).apply();}
            Notification.ProgressStyle style=new Notification.ProgressStyle()
                .setProgress(BlinkRules.progress(start,deadline,now))
                .setProgressSegments(java.util.Collections.singletonList(new Notification.ProgressStyle.Segment(1000)));
            Notification.Builder b=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(R.drawable.blink_notification).setContentIntent(BlinkTimerAlarm.open(this,null))
                .setOngoing(true).setOnlyAlertOnce(true).setContentTitle(title).setContentText(text)
                .setWhen(deadline).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setShortCriticalText(BlinkRules.chip(deadline-now)).setStyle(style)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
            b.getExtras().putBoolean("android.requestPromotedOngoing",true);
            return b.build();
        }
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.blink_notification).setContentIntent(BlinkTimerAlarm.open(this,null)).setOngoing(true)
            .setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).setShowWhen(false)
            .setVisibility(display?NotificationCompat.VISIBILITY_PUBLIC:NotificationCompat.VISIBILITY_SECRET)
            .setContentTitle(display?title:"Blink 后台提醒");
        if(display&&item!=null&&away==0){
            b.setContentText(text).setWhen(deadline).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true);
        }else if(display)b.setContentText(text);
        return b.build();
    }

    private void scheduleAwakeDeadline(){
        handler.removeCallbacks(awakeDeadline);
        // Without exact-alarm access, keep the deadline on time while the CPU is already awake.
        if(upcoming!=null&&!BlinkTimerAlarm.exactAllowed(this))
            handler.postDelayed(awakeDeadline,Math.max(1,upcoming.optLong("at")-System.currentTimeMillis()));
    }

    static void updateCountdown(JSONObject item){
        BlinkTimerService service=running;
        if(service==null)return;
        if(item==null||service.upcoming==null||item.optLong("at")!=service.upcoming.optLong("at"))
            service.prefs.edit().putLong("intervalStart",System.currentTimeMillis()).apply();
        service.upcoming=item;
        service.scheduleAwakeDeadline();
        if(item==null&&service.prefs.getLong("awaySince",0)==0&&!BlinkTimerAlarm.persistent(service)){service.stopSelf();return;}
        if(!service.prefs.getBoolean("alertOnTimer",false))service.manager.notify(TIMER,service.timerNotification(item));
    }

    /** The reminder borrowed the countdown card; expire it on time and give the card back. */
    static void alertUntil(Context c,long expires,String token){
        BlinkTimerService service=running;if(service==null)return;
        service.alertExpires=expires;service.alertToken=token;
        service.handler.removeCallbacks(service.alertExpired);
        service.handler.postDelayed(service.alertExpired,Math.max(1,expires-System.currentTimeMillis()));
    }
    static void restoreCountdown(Context c){
        BlinkTimerService service=running;if(service==null)return;
        service.handler.removeCallbacks(service.alertExpired);
        service.alertToken=null;
        service.upcoming=BlinkTimerAlarm.next(service);
        service.manager.notify(TIMER,service.timerNotification(service.upcoming));
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        if(!BlinkTimerAlarm.persistent(this))BlinkTimerAlarm.closeSession(this);
        super.onTaskRemoved(rootIntent);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        handler.removeCallbacks(awakeDeadline);
        handler.removeCallbacks(alertExpired);
        try{unregisterReceiver(presence);}catch(IllegalArgumentException ignored){/* not registered */}
        if(running==this)running=null;
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
