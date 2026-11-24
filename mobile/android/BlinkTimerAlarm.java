package com.zhaowl.blink;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Delivers the saved plan even when Android has stopped the countdown service. */
public class BlinkTimerAlarm extends BroadcastReceiver {
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
            if(item==null)continue;
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

    static void schedule(Context context,JSONObject item){
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        PendingIntent pending=alarm(context);
        if(item==null){manager.cancel(pending);return;}
        long at=item.optLong("at");
        if(Build.VERSION.SDK_INT<31||manager.canScheduleExactAlarms()){
            try{
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(at,open(context,null)),pending);
                return;
            }catch(SecurityException ignored){/* Permission changed between the check and scheduling. */}
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);
    }

    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        boolean restore=Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        JSONObject upcoming=restore?next(context):advance(context);
        schedule(context,upcoming);
        if(restore&&upcoming!=null){
            Intent service=new Intent(context,BlinkTimerService.class);
            try{
                if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service);
                else context.startService(service);
            }catch(IllegalStateException|SecurityException ignored){/* The scheduled reminder remains active. */}
        }
    }
}
