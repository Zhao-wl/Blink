package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/**
 * Expiring notification actions: reminder check-ins and day questions (start/end of work).
 * Receipts are persisted before the alert is dismissed so a later app launch can import them.
 */
public class ReminderAction extends BroadcastReceiver {
    static final int ALERT=4101, PROMPT=4102;
    static final String[] LABEL={"开始远眺","开始离席","喝过了"};
    private static final String KEY="activeAlert";
    private static PowerManager.WakeLock screenLight;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("blink-timer",Context.MODE_PRIVATE);}

    static boolean eligible(Context c,JSONObject row){
        String owner=prefs(c).getString("owner","");
        return !owner.isEmpty()&&owner.equals(row.optString("owner"));
    }
    static JSONObject active(Context c){
        try{
            JSONObject row=new JSONObject(prefs(c).getString(KEY,"{}"));
            return row.optLong("expires")>System.currentTimeMillis()&&eligible(c,row)?row:null;
        }catch(JSONException e){return null;}
    }
    static JSONArray receipts(Context c){
        try{return new JSONArray(prefs(c).getString("receipts","[]"));}catch(JSONException e){return new JSONArray();}
    }
    static String id(JSONObject row){return row.optString("round",row.optString("token"));}
    static boolean handled(Context c,JSONObject row){
        long now=System.currentTimeMillis();
        return prefs(c).getLong("done:"+id(row),0)>now||!row.optBoolean("notice")&&prefs(c).getLong("restUntil",0)>now;
    }
    static boolean inCall(Context c){
        int mode=c.getSystemService(AudioManager.class).getMode();
        return mode==AudioManager.MODE_IN_CALL||mode==AudioManager.MODE_IN_COMMUNICATION||mode==AudioManager.MODE_RINGTONE;
    }
    private static void dismiss(Context c){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        boolean merged=prefs(c).getBoolean("alertOnTimer",false);
        prefs(c).edit().remove(KEY).remove("alertOnTimer").apply();
        manager.cancel(ALERT);
        releaseScreen();
        if(merged)BlinkTimerService.restoreCountdown(c);
    }
    static synchronized void clearIneligible(Context c){
        if(prefs(c).contains(KEY)&&active(c)==null)dismiss(c);
    }
    static synchronized void clear(Context c,String token){
        JSONObject row=active(c);
        if(row!=null&&token.equals(row.optString("token")))dismiss(c);
    }
    static synchronized boolean confirm(Context c,String token){
        JSONObject row=active(c);if(row==null||!token.equals(row.optString("token")))return false;
        try{
            long now=System.currentTimeMillis(),pause=row.optLong("rest");
            JSONArray pending=receipts(c);
            pending.put(new JSONObject().put("owner",prefs(c).getString("owner",""))
                .put("id",id(row)).put("k",row.getInt("k")).put("at",now).put("rest",pause));
            SharedPreferences.Editor edit=prefs(c).edit().putString("receipts",pending.toString())
                .putLong("done:"+id(row),now+12*60000L).putLong("lastActive",now).putInt("unanswered",0);
            if(pause>0)edit.putLong("restUntil",now+pause);
            if(!edit.commit())return false;
            dismiss(c);
            BlinkTimerAlarm.dispatch(c,false);
            // Let a running web view re-plan right away instead of waiting for the next launch.
            BlinkTimerPlugin.emit(c,new JSONObject().put("type","confirmed"));
            return true;
        }catch(JSONException e){return false;}
    }
    static synchronized void closeSession(Context c){
        prefs(c).edit().remove("plan").remove(KEY).remove("alertOnTimer").remove("restUntil").remove("awaySince")
            .putLong("closedAt",System.currentTimeMillis()).apply();
        releaseScreen();
    }
    private static void releaseScreen(){
        if(screenLight!=null&&screenLight.isHeld())screenLight.release();
        screenLight=null;
    }
    @Override public void onReceive(Context c,Intent i){
        Uri data=i.getData();if(data==null)return;
        if("confirm".equals(data.getAuthority()))confirm(c,data.getLastPathSegment());
        else if("prompt".equals(data.getAuthority())&&data.getPathSegments().size()==2)
            answerPrompt(c,data.getPathSegments().get(0),data.getPathSegments().get(1));
    }

    // ---- Day questions ("下班了？") with up to three actions ----
    static void showPrompt(Context c,JSONObject p,String channel){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled()||BlinkTimerAlarm.foreground(c))return;
        NotificationCompat.Builder b=new NotificationCompat.Builder(c,channel).setSmallIcon(R.drawable.blink_notification)
            .setContentTitle(p.optString("title")).setContentText(p.optString("body"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true)
            .setContentIntent(BlinkTimerAlarm.open(c,null)).setTimeoutAfter(30*60000L);
        JSONArray actions=p.optJSONArray("actions");
        for(int i=0;actions!=null&&i<actions.length()&&i<3;i++){
            JSONArray a=actions.optJSONArray(i);if(a==null)continue;
            Intent intent=new Intent(c,ReminderAction.class).setData(new Uri.Builder().scheme("blink").authority("prompt")
                .appendPath(p.optString("id")).appendPath(a.optString(0)).build());
            b.addAction(new NotificationCompat.Action.Builder(0,a.optString(1),
                PendingIntent.getBroadcast(c,10+i,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
                .setAuthenticationRequired(false).build());
        }
        prefs(c).edit().putString("prompt",p.optString("id")).apply();
        manager.notify(PROMPT,b.build());
    }
    static void clearPrompt(Context c,String id){
        if(id.equals(prefs(c).getString("prompt",""))){
            prefs(c).edit().remove("prompt").apply();
            c.getSystemService(NotificationManager.class).cancel(PROMPT);
        }
    }
    private static void answerPrompt(Context c,String id,String action){
        clearPrompt(c,id);
        try{BlinkTimerPlugin.emit(c,new JSONObject().put("type","prompt").put("id",id).put("action",action));}
        catch(JSONException ignored){/* constant keys */}
    }

    @SuppressWarnings("deprecation")
    static synchronized boolean post(Context c,JSONObject row,String channel,PendingIntent open){
        int kind=row.optInt("k",-1);
        boolean notice=row.optBoolean("notice");
        if((notice?kind!=3:kind<0||kind>=LABEL.length)||!eligible(c,row)||handled(c,row))return false;
        String token=row.optString("token");if(token.isEmpty())return false;
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel nc=manager.getNotificationChannel(channel);
            if(nc==null||nc.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        }
        SharedPreferences p=prefs(c);
        long now=System.currentTimeMillis(),expires=row.optLong("expires");
        NotificationCompat.Builder b=new NotificationCompat.Builder(c,channel).setSmallIcon(R.drawable.blink_notification)
            .setContentTitle((row.optBoolean("retry")?"再次提醒 · ":"Blink · ")+row.optString("title"))
            .setContentText(row.optString("body"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setOnlyAlertOnce(false);
        if(notice){
            manager.notify(PROMPT,b.setAutoCancel(true).setTimeoutAfter(Math.max(1,expires-now)).build());
            return true;
        }
        SharedPreferences.Editor editor=p.edit().putString(KEY,row.toString());
        for(String key:p.getAll().keySet())if(key.startsWith("done:")&&p.getLong(key,0)<=now)editor.remove(key);
        // Only Android 16 Live Updates show the service card on the lock screen; older ROMs (MIUI) hide it.
        boolean merged=Build.VERSION.SDK_INT>=36&&BlinkTimerService.isRunning();
        editor.putBoolean("alertOnTimer",merged).apply();
        Intent i=new Intent(c,ReminderAction.class).setData(new Uri.Builder().scheme("blink").authority("confirm").appendPath(token).build());
        PendingIntent done=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new NotificationCompat.Action.Builder(0,LABEL[kind],done).setAuthenticationRequired(false).build());
        if(merged){
            // Same id as the countdown: the card turns into the reminder and back after a check-in.
            b.setOngoing(false);BlinkTimerService.alertUntil(c,expires,token);
        }else b.setAutoCancel(true).setTimeoutAfter(Math.max(1,expires-now));
        manager.notify(merged?BlinkTimerService.TIMER:ALERT,b.build());
        PowerManager power=c.getSystemService(PowerManager.class);
        boolean allowed=manager.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL;
        if(BlinkRules.shouldWake(p.getBoolean("wake",false),!row.optBoolean("wake",!row.optBoolean("retry")),
            p.getInt("unanswered",0),power.isInteractive(),allowed,inCall(c))){
            try{
                releaseScreen();
                screenLight=power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"Blink:reminder");
                screenLight.setReferenceCounted(false);
                screenLight.acquire(15000L);
            }catch(SecurityException ignored){/* Notification remains available under device policy. */}
        }
        return true;
    }
}
