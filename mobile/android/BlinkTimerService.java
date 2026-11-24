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
    private static BlinkTimerService running;
    private static final int TIMER=4100;
    private static final String CHANNEL="blink-countdown-tip";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService network=Executors.newSingleThreadExecutor();
    private static boolean checking;
    private NotificationManager manager;
    private SharedPreferences prefs;
    private JSONObject upcoming;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=this::pollWorkDevice;
    private final Runnable awakeDeadline=()->BlinkTimerAlarm.dispatch(this,true);
    private static boolean background(Context context){
        return !MainActivity.visible||!context.getSystemService(PowerManager.class).isInteractive()
            ||context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    @Override public void onCreate(){
        super.onCreate();
        running=this;
        prefs=getSharedPreferences("blink-timer",MODE_PRIVATE);
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
    }
    private void pollWorkDevice(){
        if(!shouldMonitorWork(this))return;
        handler.postDelayed(poll,60000);
        checkWorkDevice(getApplicationContext(),null);
    }

    private static String assigned(JSONObject state,String field){
        return state.isNull(field)?"":state.optString(field);
    }

    private static void writeWorkState(Context context,SharedPreferences.Editor edit,
                                       String owner,JSONObject state,boolean legacy,long checked,int boot){
        String caller=BlinkCloudPlugin.callerDevice(context);
        String device=assigned(state,"device"),requested=assigned(state,"requested");
        boolean current=!caller.isEmpty()&&caller.equals(device);
        boolean revoked=state.optBoolean("revoked");
        edit.putLong("workCheckedElapsed",checked).putInt("workBoot",boot).putBoolean("legacyWork",legacy)
            .putBoolean("workCandidate",!revoked&&BlinkWorkScheduling.shouldTrack(caller,device,requested,legacy))
            .putString("workState",state.toString());
        ReminderAction.lease(edit,current&&requested.isEmpty()&&!revoked?state:new JSONObject());
        if(revoked)edit.putBoolean("revoked:"+owner,true);
        if(current&&!requested.isEmpty()){
            // Stop before asking the server to transfer ownership, even if an older WebView poll finishes later.
            String block="blocked:"+owner;
            long revision=state.optLong("revision",-1);
            edit.putLong(block,Math.max(revision,ReminderAction.prefs(context).getLong(block,-1)));
        }
    }

    static void configureWork(Context context,SharedPreferences prefs,SharedPreferences.Editor edit,String owner,
                              boolean monitoring,JSONObject state,boolean legacy,long checked,int boot){
        boolean sameOwner=owner.equals(prefs.getString("owner",""));
        edit.putBoolean("monitorWork",monitoring);
        if(!sameOwner){
            edit.remove("workCheckedElapsed").remove("workBoot").remove("workState")
                .remove("workCandidate").remove("legacyWork");
            ReminderAction.lease(edit,new JSONObject());
        }
        long saved=sameOwner?prefs.getLong("workCheckedElapsed",-1):-1;
        int savedBoot=sameOwner?prefs.getInt("workBoot",-2):-2;
        if(monitoring&&BlinkWorkScheduling.acceptsObservation(saved,savedBoot,checked,boot,ReminderAction.bootCount(context)))
            writeWorkState(context,edit,owner,state,legacy,checked,boot);
    }

    static JSONObject workAuthority(Context context) throws JSONException {
        SharedPreferences prefs=ReminderAction.prefs(context);
        boolean legacy=prefs.getBoolean("legacyWork",false);
        return new JSONObject().put("legacy",legacy)
            .put("lease",legacy?JSONObject.NULL:new JSONObject(prefs.getString("workState","{}")))
            .put("checkedElapsed",prefs.getLong("workCheckedElapsed",-1)).put("boot",prefs.getInt("workBoot",-2));
    }

    static boolean applyWorkDevice(Context context,String owner,JSONObject state){
        SharedPreferences prefs=ReminderAction.prefs(context);
        if(!prefs.getBoolean("monitorWork",false)||!owner.equals(prefs.getString("owner","")))return false;
        long checked=state.optLong("checkedElapsed",-1);
        int boot=state.optInt("boot",-2);
        if(!BlinkWorkScheduling.acceptsObservation(prefs.getLong("workCheckedElapsed",-1),
            prefs.getInt("workBoot",-2),checked,boot,ReminderAction.bootCount(context)))return false;
        SharedPreferences.Editor edit=prefs.edit();
        writeWorkState(context,edit,owner,state,BlinkCloudPlugin.legacyReminderReply(state),checked,boot);
        if(!edit.commit())return false;
        ReminderAction.clearIneligible(context);
        if(!shouldMonitorWork(context))BlinkTimerAlarm.scheduleWorkCheck(context,false);
        BlinkTimerAlarm.dispatch(context,true);
        return true;
    }

    static void checkWorkDevice(Context context,BroadcastReceiver.PendingResult pending){
        if(checking||!shouldMonitorWork(context)||!background(context)){
            if(pending!=null)pending.finish();
            return;
        }
        checking=true;
        String owner=ReminderAction.prefs(context).getString("owner","");
        PowerManager.WakeLock wake=context.getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Blink:work-reminder-check");
        wake.acquire(15000);
        requestWorkDevice(context,owner,"status",-1,()->{
            checking=false;
            if(wake.isHeld())wake.release();
            if(pending!=null)pending.finish();
        });
    }

    private static void requestWorkDevice(Context context,String owner,String action,long revision,Runnable finished){
        network.execute(()->{
            JSONObject reply=null;
            try{reply=BlinkCloudPlugin.reminderState(context,owner,action,revision);}
            catch(Exception ignored){/* Keep the current offline authorization. */}
            JSONObject state=reply;
            main.post(()->{
                boolean releasing=false;
                try{
                    if(state==null||!applyWorkDevice(context,owner,state))return;
                    String requested=assigned(state,"requested"),caller=BlinkCloudPlugin.callerDevice(context);
                    if(action.equals("status")&&!requested.isEmpty()&&!caller.isEmpty()
                        &&caller.equals(assigned(state,"device"))){
                        requestWorkDevice(context,owner,"release",state.optLong("revision",-1),finished);
                        releasing=true;
                    }
                }finally{if(!releasing)finished.run();}
            });
        });
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        boolean closed=intent==null&&!BlinkTimerAlarm.hasOpenTask(this);
        upcoming=closed?null:BlinkTimerAlarm.next(this);
        startForeground(TIMER,timerNotification(upcoming));
        if(closed){BlinkTimerAlarm.closeSession(this);return START_NOT_STICKY;}
        boolean monitoring=shouldMonitorWork(this);
        if(!BlinkWorkScheduling.keepAlive(upcoming!=null,monitoring)){stopSelf();return START_NOT_STICKY;}
        handler.removeCallbacks(poll);
        if(monitoring)handler.postDelayed(poll,60000);
        scheduleAwakeDeadline();
        return START_STICKY;
    }
    private Notification timerNotification(JSONObject item){
        boolean display=item!=null&&prefs.getBoolean("lock",true);
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.blink_notification).setContentIntent(BlinkTimerAlarm.open(this,null)).setOngoing(true)
            .setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).setShowWhen(false)
            .setVisibility(display?NotificationCompat.VISIBILITY_PUBLIC:NotificationCompat.VISIBILITY_SECRET)
            .setContentTitle(display?"下次"+item.optString("title")+"提醒":"Blink 后台提醒");
        if(display){
            long deadline=item.optLong("at");
            String time=android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(deadline));
            b.setContentText("预计 "+time).setWhen(deadline)
                .setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true);
        }
        return b.build();
    }
    static boolean shouldMonitorWork(Context context){
        SharedPreferences prefs=ReminderAction.prefs(context);
        String owner=prefs.getString("owner","");
        return owner.startsWith("blink-profile-")&&prefs.getBoolean("monitorWork",false)
            &&prefs.getBoolean("workCandidate",true)&&!prefs.getBoolean("revoked:"+owner,false);
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
        service.upcoming=item;
        service.scheduleAwakeDeadline();
        if(!BlinkWorkScheduling.keepAlive(item!=null,shouldMonitorWork(service))){service.stopSelf();return;}
        service.manager.notify(TIMER,service.timerNotification(item));
    }
    @Override public void onTaskRemoved(Intent rootIntent){
        BlinkTimerAlarm.closeSession(this);
        super.onTaskRemoved(rootIntent);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        handler.removeCallbacks(poll);
        handler.removeCallbacks(awakeDeadline);
        if(running==this)running=null;
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
