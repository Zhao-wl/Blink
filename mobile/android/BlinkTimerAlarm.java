package com.zhaowl.blink;

import android.app.AlarmManager;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Delivers the saved plan even when Android has stopped the countdown service. */
public class BlinkTimerAlarm extends BroadcastReceiver {
    private static final ExecutorService network=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());

    static PendingIntent open(Context context,String token){
        Intent intent=new Intent(context,MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(token!=null)intent.putExtra("blinkToken",token);
        return PendingIntent.getActivity(context,token==null?0:1,intent,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent alarm(Context context){
        return PendingIntent.getBroadcast(context,2,new Intent(context,BlinkTimerAlarm.class),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean foreground(Context context){
        return MainActivity.visible&&context.getSystemService(PowerManager.class).isInteractive()
            &&!context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    static JSONObject next(Context context){return scan(context,null);}

    /** Detects an unanswered retry: from then on nothing is delivered until the person is back. */
    private static long checkAway(Context context,long now){
        SharedPreferences prefs=ReminderAction.prefs(context);
        long away=prefs.getLong("awaySince",0);
        String last=prefs.getString("lastRetry",null);
        if(away>0||last==null)return away;
        try{
            JSONObject retry=new JSONObject(last);
            if(now<retry.optLong("expires"))return 0;
            boolean confirmed=prefs.getLong("done:"+retry.optString("id"),0)>0;
            away=BlinkRules.awayFrom(retry.optLong("at"),retry.optLong("expires"),now,confirmed,prefs.getLong("lastActive",0));
            SharedPreferences.Editor edit=prefs.edit().remove("lastRetry");
            if(away>0)edit.putLong("awaySince",away).putInt("unanswered",prefs.getInt("unanswered",0)+1);
            edit.apply();
            return away;
        }catch(JSONException e){prefs.edit().remove("lastRetry").apply();return 0;}
    }

    /** Walks the plan; with a list, collects the items due now (and marks them handled). */
    private static JSONObject scan(Context context,List<JSONObject> due){
        SharedPreferences prefs=ReminderAction.prefs(context);
        if(!prefs.contains("owner"))return null;
        JSONArray plan;
        try{plan=new JSONArray(prefs.getString("plan","[]"));}
        catch(JSONException e){return null;}
        long now=System.currentTimeMillis(),handledThrough=prefs.getLong("deliveredAt",0);
        long restUntil=prefs.getLong("restUntil",0),away=checkAway(context,now);
        boolean visible=foreground(context);
        JSONObject next=null;
        for(int i=0;i<plan.length();i++){
            JSONObject item=plan.optJSONObject(i);
            if(item==null||!ReminderAction.eligible(context,item))continue;
            long at=item.optLong("at");
            boolean notice=item.optBoolean("notice");
            if(at<=handledThrough||!notice&&at<restUntil||prefs.getLong("done:"+ReminderAction.id(item),0)>now)continue;
            if(at>now){next=item;break;}
            if(due!=null){
                if(away>0&&!notice)continue;
                long defer=BlinkRules.deferForCall(ReminderAction.inCall(context),now,item.optLong("expires"));
                if(defer>0){
                    try{next=new JSONObject(item.toString()).put("at",defer).put("deferred",true);}catch(JSONException ignored){}
                    break;
                }
                if(item.optLong("expires")>now&&!visible)due.add(item);
            }
            handledThrough=at;
        }
        if(due!=null&&handledThrough>prefs.getLong("deliveredAt",0))prefs.edit().putLong("deliveredAt",handledThrough).apply();
        return next;
    }

    static boolean exactAllowed(Context context){
        return Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void schedule(Context context,JSONObject item){
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        PendingIntent pending=alarm(context);
        long at=item==null?Long.MAX_VALUE:item.optLong("at");
        // Also wake when a retry expires, so the card can switch to "paused" on time.
        try{
            String last=ReminderAction.prefs(context).getString("lastRetry",null);
            if(last!=null)at=Math.min(at,new JSONObject(last).optLong("expires")+1000);
        }catch(JSONException ignored){/* stale value */}
        if(at==Long.MAX_VALUE){manager.cancel(pending);return;}
        if(exactAllowed(context)){
            try{
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(at,open(context,null)),pending);
                return;
            }catch(SecurityException ignored){/* Permission changed between the check and scheduling. */}
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);
    }

    static void closeSession(Context context){
        ReminderAction.closeSession(context);
        schedule(context,null);
        context.getSystemService(android.app.NotificationManager.class).cancelAll();
        context.stopService(new Intent(context,BlinkTimerService.class));
    }

    static boolean hasOpenTask(Context context){
        return !context.getSystemService(ActivityManager.class).getAppTasks().isEmpty();
    }
    static boolean persistent(Context context){return ReminderAction.prefs(context).getBoolean("persist",false);}

    private static void deliver(Context context,JSONObject item){
        String channel=ReminderAction.prefs(context).getString("channel","blink-00");
        if(ReminderAction.post(context,item,channel,open(context,item.optString("token")))&&item.optBoolean("retry")){
            try{
                ReminderAction.prefs(context).edit().putString("lastRetry",new JSONObject().put("id",ReminderAction.id(item))
                    .put("at",item.optLong("at")).put("expires",item.optLong("expires")).toString()).apply();
            }catch(JSONException ignored){/* constant keys */}
        }
    }

    static void dispatch(Context context,boolean due){
        if(!due){finish(context,next(context));return;}
        List<JSONObject> items=new ArrayList<>();
        JSONObject upcoming=scan(context,items);
        boolean needsCheck=false;
        for(JSONObject item:items)needsCheck|=item.optBoolean("shared");
        if(!needsCheck){for(JSONObject item:items)deliver(context,item);finish(context,upcoming);return;}
        // Work reminders go to the device in use: ask the server, deliver anyway when offline.
        PowerManager.WakeLock wake=context.getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Blink:presence");
        wake.acquire(10000);
        network.execute(()->{
            List<Boolean> allowed=new ArrayList<>();
            for(JSONObject item:items){
                Boolean decision=item.optBoolean("shared")?BlinkCloudPlugin.presence(context,item.optString("owner"),item.optString("round",null)):Boolean.TRUE;
                allowed.add(decision==null||decision);
            }
            main.post(()->{
                try{
                    for(int i=0;i<items.size();i++){
                        JSONObject item=items.get(i);
                        if(allowed.get(i))deliver(context,item);
                        else ReminderAction.prefs(context).edit().putLong("done:"+ReminderAction.id(item),System.currentTimeMillis()+12*60000L).apply();
                    }
                    finish(context,upcoming);
                }finally{if(wake.isHeld())wake.release();}
            });
        });
    }
    private static void finish(Context context,JSONObject upcoming){
        schedule(context,upcoming);
        BlinkTimerService.updateCountdown(upcoming);
        BlinkWidget.refresh(context);
    }

    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        boolean keep=persistent(context);
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)){
            if(!keep){closeSession(context);return;}
            ReminderAction.prefs(context).edit().remove("awaySince").remove("alertOnTimer").apply();
            JSONObject upcoming=next(context);
            schedule(context,upcoming);
            if(upcoming!=null)BlinkTimerService.start(context);
            return;
        }
        if(!BlinkRules.keepSession(keep,hasOpenTask(context))){closeSession(context);return;}
        dispatch(context,action==null);
    }
}
