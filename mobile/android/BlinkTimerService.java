package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/** Executes the on-device plan when the UI disappears; the system draws the countdown. */
public class BlinkTimerService extends Service {
    static boolean running;
    private static final int TIMER=4100, LOCK_TIMER=4102;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private NotificationManager manager;
    private SharedPreferences prefs;
    private final Runnable tick=this::advance;
    private final boolean separateLockCard="Xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
    private long displayedDeadline;
    private JSONObject upcoming;
    private final Runnable refreshLockText=()->{
        if(upcoming!=null&&upcoming.optLong("at")>System.currentTimeMillis()&&screenLocked())syncLockCard(upcoming);
    };
    private boolean screenLocked(){
        return !getSystemService(PowerManager.class).isInteractive() ||
            getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private void clearLockCard(){
        handler.removeCallbacks(refreshLockText);
        manager.cancel(LOCK_TIMER);
        displayedDeadline=0;
    }
    private final BroadcastReceiver wake=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            if(Intent.ACTION_USER_PRESENT.equals(intent.getAction()))clearLockCard();
            else advance();
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        running=true;
        prefs=getSharedPreferences("blink-timer",MODE_PRIVATE);
        manager=getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel("blink-clock","后台计时",NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null,null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
            if(separateLockCard){
                NotificationChannel lock=new NotificationChannel("blink-lock-clock","锁屏倒计时",NotificationManager.IMPORTANCE_DEFAULT);
                lock.setSound(null,null);
                lock.enableVibration(false);
                lock.setShowBadge(false);
                lock.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(lock);
            }
        }
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);
        if(separateLockCard){filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);}
        registerReceiver(wake,filter);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        startForeground(TIMER,timerNotification(BlinkTimerAlarm.next(this)));
        advance();
        return START_STICKY;
    }
    private PendingIntent openClock(){
        if(!LockFace.autoEnabled(this))return BlinkTimerAlarm.open(this,null);
        return PendingIntent.getActivity(this,3,new Intent(this,LockClockActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification timerNotification(JSONObject item){
        boolean display=prefs.getBoolean("lock",true);
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,"blink-clock")
            .setSmallIcon(R.drawable.blink_notification).setContentIntent(openClock()).setOngoing(true)
            .setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setVisibility(display&&!separateLockCard?NotificationCompat.VISIBILITY_PUBLIC:NotificationCompat.VISIBILITY_SECRET)
            .setContentTitle(item==null?"Blink 后台提醒":"下次"+item.optString("title")+"提醒");
        if(item!=null&&display)b.setWhen(item.optLong("at")).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true);
        return b.build();
    }
    private void syncLockCard(JSONObject item){
        if(!separateLockCard)return;
        if(!prefs.getBoolean("lock",true)||!screenLocked()){
            if(displayedDeadline!=0)clearLockCard();
            return;
        }
        long deadline=item.optLong("at");
        handler.removeCallbacks(refreshLockText);
        boolean awake=getSystemService(PowerManager.class).isInteractive();
        if(displayedDeadline==deadline&&!awake)return;
        String title="下次"+item.optString("title")+"提醒";
        long seconds=Math.max(0,(deadline-System.currentTimeMillis()+999)/1000);
        String remaining=String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
        Notification notification=new NotificationCompat.Builder(this,"blink-lock-clock")
            .setSmallIcon(R.drawable.blink_notification).setContentIntent(openClock()).setContentTitle(title)
            .setWhen(deadline).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
            .setGroup("blink-lock-countdown").setContentText("剩余 "+remaining)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build();
        manager.notify(LOCK_TIMER,notification);
        displayedDeadline=deadline;
        if(awake)handler.postDelayed(refreshLockText,1000);
    }
    private void advance(){
        handler.removeCallbacks(tick);
        upcoming=BlinkTimerAlarm.advance(this);
        if(upcoming==null){stopSelf();return;}
        manager.notify(TIMER,timerNotification(upcoming));
        syncLockCard(upcoming);
        handler.postDelayed(tick,Math.max(1,upcoming.optLong("at")-System.currentTimeMillis()));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        running=false;
        handler.removeCallbacks(tick);unregisterReceiver(wake);
        if(separateLockCard)clearLockCard();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
