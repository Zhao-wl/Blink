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
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Delivers the saved plan even when Android has stopped the countdown service. */
public class BlinkTimerAlarm extends BroadcastReceiver {
    private static final String CHECK_WORK_DEVICE="com.zhaowl.blink.CHECK_WORK_DEVICE";
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

    static JSONObject next(Context context){return scan(context,false);}
    static JSONObject advance(Context context){return scan(context,true);}

    private static JSONObject scan(Context context,boolean deliver){
        SharedPreferences prefs=ReminderAction.prefs(context);
        if(!prefs.contains("owner"))return null;
        JSONArray plan;
        try{plan=new JSONArray(prefs.getString("plan","[]"));}
        catch(JSONException e){return null;}

        long now=System.currentTimeMillis(),handledThrough=prefs.getLong("deliveredAt",0);
        long restUntil=prefs.getLong("restUntil",0);
        boolean foreground=MainActivity.visible&&context.getSystemService(PowerManager.class).isInteractive()
            &&!context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        JSONObject next=null;
        for(int i=0;i<plan.length();i++){
            JSONObject item=plan.optJSONObject(i);
            if(item==null||!ReminderAction.eligible(context,item))continue;
            long due=item.optLong("at");
            if(due<=handledThrough||due<restUntil||prefs.getLong("done:"+ReminderAction.id(item),0)>now)continue;
            if(due>now){next=item;break;}
            if(deliver&&item.optLong("expires")>now&&!foreground){
                ReminderAction.post(context,item,prefs.getString("channel","blink-00"),open(context,item.optString("token")));
            }
            handledThrough=due;
        }
        if(deliver&&handledThrough>prefs.getLong("deliveredAt",0))prefs.edit().putLong("deliveredAt",handledThrough).apply();
        return next;
    }

    static boolean exactAllowed(Context context){
        return Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void schedule(Context context,JSONObject item){
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        PendingIntent pending=alarm(context);
        if(item==null){manager.cancel(pending);return;}
        long at=item.optLong("at");
        if(exactAllowed(context)){
            try{
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(at,open(context,null)),pending);
                return;
            }catch(SecurityException ignored){/* Permission changed between the check and scheduling. */}
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);
    }

    static void scheduleWorkCheck(Context context,boolean monitoring){
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        PendingIntent pending=PendingIntent.getBroadcast(context,3,
            new Intent(context,BlinkTimerAlarm.class).setAction(CHECK_WORK_DEVICE),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        if(!monitoring){manager.cancel(pending);return;}
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            BlinkWorkScheduling.nextCheck(SystemClock.elapsedRealtime()),pending);
    }

    static void closeSession(Context context){
        ReminderAction.closeSession(context);
        schedule(context,null);
        scheduleWorkCheck(context,false);
        context.getSystemService(android.app.NotificationManager.class).cancelAll();
        context.stopService(new Intent(context,BlinkTimerService.class));
    }

    static boolean hasOpenTask(Context context){
        return !context.getSystemService(ActivityManager.class).getAppTasks().isEmpty();
    }

    static void dispatch(Context context,boolean due){
        JSONObject upcoming=due?advance(context):next(context);
        schedule(context,upcoming);
        BlinkTimerService.updateCountdown(upcoming);
    }

    @Override public void onReceive(Context context,Intent intent){
        if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())||!hasOpenTask(context)){
            closeSession(context);
            return;
        }
        if(CHECK_WORK_DEVICE.equals(intent.getAction())){
            boolean monitoring=BlinkTimerService.shouldMonitorWork(context);
            scheduleWorkCheck(context,monitoring);
            if(monitoring)BlinkTimerService.checkWorkDevice(context.getApplicationContext(),goAsync());
            return;
        }
        dispatch(context,intent.getAction()==null);
    }
}
