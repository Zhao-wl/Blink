package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes the on-device plan when the UI disappears; the system draws the countdown. */
public class BlinkTimerService extends Service {
    static boolean running;
    private static BlinkTimerService instance;
    private static final int TIMER=4100, LOCK_TIMER=4102;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private NotificationManager manager;
    private SharedPreferences prefs;
    private final Runnable tick=this::advance;
    private final boolean separateLockCard="Xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
    private long displayedDeadline;
    private JSONObject upcoming;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Runnable poll=this::pollWorkDevice;
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
        instance=this;
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
    private void pollWorkDevice(){
        String owner=prefs.getString("owner","");
        if(!shouldPollWorkDevice())return;
        handler.postDelayed(poll,60000);
        if(MainActivity.visible&&!screenLocked())return;
        network.execute(()->{
            try{
                JSONObject state=BlinkCloudPlugin.reminderState(this,owner,"status",-1);
                if(state!=null)handler.post(()->applyWorkDevice(owner,state));
            }catch(Exception ignored){/* Keep the current offline authorization. */}
        });
    }
    private void applyWorkDevice(String owner,JSONObject state){
        if(instance!=this||!owner.equals(prefs.getString("owner","")))return;
        long revision=state.optLong("revision",-1),savedRevision=prefs.getLong("leaseRevision",-1);
        if(!state.optBoolean("revoked")&&revision<savedRevision)return;
        boolean current=!state.optBoolean("revoked")&&state.optString("device").equals(state.optString("callerDevice"));
        if(state.optBoolean("revoked")){
            if(!prefs.edit().putBoolean("revoked:"+owner,true).commit())return;
            ReminderAction.clearIneligible(this);
        }else if(current&&state.isNull("requested")){
            if(!ReminderAction.lease(prefs.edit(),state).commit())return;
        }else{
            if(!ReminderAction.stopShared(this,owner,Math.max(revision,savedRevision)))return;
            if(current){
                JSONObject next=BlinkTimerAlarm.next(this);
                BlinkTimerAlarm.schedule(this,next);
                if(next==null){
                    upcoming=null;
                    handler.removeCallbacks(tick);
                    clearLockCard();
                    manager.notify(TIMER,timerNotification(null));
                }else updateCountdown(next);
                network.execute(()->{
                    try{BlinkCloudPlugin.reminderState(this,owner,"release",revision);}
                    catch(Exception ignored){/* The next check or offline expiry can finish the handoff. */}
                    finally{
                        handler.post(()->{
                            if(instance==this&&owner.equals(prefs.getString("owner","")))
                                updateCountdown(BlinkTimerAlarm.next(this));
                        });
                    }
                });
                return;
            }
        }
        JSONObject next=BlinkTimerAlarm.next(this);
        BlinkTimerAlarm.schedule(this,next);
        updateCountdown(next);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        startForeground(TIMER,timerNotification(BlinkTimerAlarm.next(this)));
        advance();
        handler.removeCallbacks(poll);
        if(upcoming!=null&&shouldPollWorkDevice())handler.postDelayed(poll,60000);
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
    private boolean shouldPollWorkDevice(){
        return prefs.getString("owner","").startsWith("blink-profile-")
            &&prefs.getLong("leaseRevision",-1)>=0;
    }
    static void updateCountdown(JSONObject next){
        BlinkTimerService service=instance;
        if(service==null)return;
        service.handler.removeCallbacks(service.tick);
        service.upcoming=next;
        if(next==null){service.clearLockCard();service.stopSelf();return;}
        service.manager.notify(TIMER,service.timerNotification(next));
        service.syncLockCard(next);
        service.handler.postDelayed(service.tick,Math.max(1,next.optLong("at")-System.currentTimeMillis()));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        running=false;
        if(instance==this)instance=null;
        handler.removeCallbacks(poll);
        network.shutdown();
        handler.removeCallbacks(tick);unregisterReceiver(wake);
        if(separateLockCard)clearLockCard();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
