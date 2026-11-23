package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/** Executes the on-device plan when the UI disappears; the system draws the countdown. */
public class BlinkTimerService extends Service {
    private static final int TIMER=4100, ALERT=4101, LOCK_TIMER=4102;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private NotificationManager manager;
    private SharedPreferences prefs;
    private JSONArray items;
    private int cursor;
    private final Runnable tick=this::advance;
    private final boolean separateLockCard="Xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
    private long displayedDeadline;
    private final Runnable refreshLockText=()->{
        if(items!=null&&cursor<items.length()&&screenLocked()){
            JSONObject next=items.optJSONObject(cursor);
            if(next!=null&&next.optLong("at")>System.currentTimeMillis())syncLockCard(next);
        }
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
        try { items=new JSONArray(prefs.getString("plan","[]")); }
        catch(JSONException e){items=new JSONArray();}
        cursor=0;
        JSONObject upcoming=null;
        long now=System.currentTimeMillis();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item!=null&&item.optLong("at")>now){upcoming=item;break;}
        }
        startForeground(TIMER,timerNotification(upcoming));
        advance();
        return START_STICKY;
    }
    private PendingIntent open(String token){
        Intent i=new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(token!=null)i.putExtra("blinkToken",token);
        return PendingIntent.getActivity(this,token==null?0:1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent openClock(){
        if(!LockFace.autoEnabled(this))return open(null);
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
        if(displayedDeadline==deadline&&awake&&Build.VERSION.SDK_INT>=26){
            boolean present=false;
            for(android.service.notification.StatusBarNotification active:manager.getActiveNotifications()){
                if(active.getId()==LOCK_TIMER){present=true;break;}
            }
            if(!present)return;
        }
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
        if(items==null)return;
        long now=System.currentTimeMillis();
        while(cursor<items.length()){
            JSONObject item=items.optJSONObject(cursor);
            if(item==null){cursor++;continue;}
            long due=item.optLong("at");
            if(ReminderAction.handled(this,item)&&due<Math.max(now,prefs.getLong("restUntil",0))){cursor++;continue;}
            if(prefs.getLong("done:"+ReminderAction.id(item),0)>now){cursor++;continue;}
            if(due>now){
                manager.notify(TIMER,timerNotification(item));
                syncLockCard(item);
                handler.postDelayed(tick,due-now);
                armWakeup(due);
                return;
            }
            cursor++;

            if((!MainActivity.visible||screenLocked())&&item.optLong("expires")>now&&due>prefs.getLong("deliveredAt",0)){
                ReminderAction.post(this,item,prefs.getString("channel","blink-00"),open(item.optString("token")));
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
        if(separateLockCard)clearLockCard();
        getSystemService(AlarmManager.class).cancel(alarmIntent());
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
