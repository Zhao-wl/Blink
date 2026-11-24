package com.zhaowl.blink;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/** An immutable, expiring notification action. Persist the receipt before dismissing the alert. */
public class ReminderAction extends android.content.BroadcastReceiver {
    static final int ALERT=4101;
    static final String[] LABEL={"开始远眺","开始离席","喝过了"};
    private static final String KEY="activeAlert";
    private static PowerManager.WakeLock screenLight;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("blink-timer",Context.MODE_PRIVATE);}
    static int bootCount(Context c){
        return android.provider.Settings.Global.getInt(c.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
    }
    static SharedPreferences.Editor lease(SharedPreferences.Editor edit,JSONObject lease){
        return edit.putLong("leaseUntil",lease.optLong("localExpires"))
            .putLong("leaseElapsed",lease.optLong("elapsedExpires"))
            .putLong("leaseRevision",lease.optLong("revision",-1))
            .putInt("leaseBoot",lease.optInt("boot",-2));
    }
    static boolean stopShared(Context c,String owner,long revision){
        SharedPreferences p=prefs(c);
        String key="blocked:"+owner;
        SharedPreferences.Editor edit=p.edit().putLong(key,Math.max(revision,p.getLong(key,-1)));
        if(owner.equals(p.getString("owner",""))){
            edit.putBoolean("legacyWork",false).putLong("workCheckedElapsed",SystemClock.elapsedRealtime())
                .putInt("workBoot",bootCount(c));
        }
        if(!edit.commit())return false;
        clearIneligible(c);
        return true;
    }
    static boolean eligible(Context c,JSONObject row){
        SharedPreferences p=prefs(c);
        String owner=p.getString("owner","");
        if(owner.isEmpty()||!owner.equals(row.optString("owner")))return false;
        if(!row.optBoolean("shared"))return true;
        return p.getBoolean("monitorWork",false) && !p.getBoolean("revoked:"+owner,false)
            && BlinkWorkScheduling.canDeliver(p.getBoolean("legacyWork",false),
                p.getLong("leaseRevision",-1),p.getLong("blocked:"+owner,-1),p.getInt("leaseBoot",-2),bootCount(c),
                p.getLong("leaseElapsed",0),SystemClock.elapsedRealtime(),p.getLong("leaseUntil",0),
                System.currentTimeMillis(),row.optLong("at"));
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
    static synchronized void clearIneligible(Context c){
        SharedPreferences p=prefs(c);
        if(p.contains(KEY)&&active(c)==null){
            p.edit().remove(KEY).apply();
            c.getSystemService(NotificationManager.class).cancel(ALERT);
            releaseScreen();
        }
    }
    static String id(JSONObject row){return row.optString("round",row.optString("token"));}
    static boolean handled(Context c,JSONObject row){
        return prefs(c).getLong("done:"+id(row),0)>System.currentTimeMillis() ||
            prefs(c).getLong("restUntil",0)>System.currentTimeMillis();
    }
    static synchronized void clear(Context c,String token){
        JSONObject row=active(c);
        if(row!=null&&token.equals(row.optString("token"))){
            prefs(c).edit().remove(KEY).apply();c.getSystemService(NotificationManager.class).cancel(ALERT);
            releaseScreen();
        }
    }
    static synchronized boolean confirm(Context c,String token){
        JSONObject row=active(c);if(row==null||!token.equals(row.optString("token")))return false;
        try{
            long now=System.currentTimeMillis(),pause=row.optLong("rest");
            JSONArray pending=receipts(c);
            JSONObject receipt=new JSONObject().put("owner",prefs(c).getString("owner",""))
                .put("id",id(row)).put("k",row.getInt("k")).put("at",now).put("rest",pause);
            pending.put(receipt);
            SharedPreferences.Editor edit=prefs(c).edit().remove(KEY).putString("receipts",pending.toString())
                .putLong("done:"+id(row),now+12*60000L);
            if(pause>0)edit.putLong("restUntil",now+pause);
            if(!edit.commit())return false;
            c.getSystemService(NotificationManager.class).cancel(ALERT);
            releaseScreen();
            BlinkTimerAlarm.dispatch(c,false);
            return true;
        }catch(JSONException e){return false;}
    }
    static synchronized void closeSession(Context c){
        prefs(c).edit().remove("plan").remove(KEY).remove("restUntil").remove("monitorWork").apply();
        releaseScreen();
    }
    private static void releaseScreen(){
        if(screenLight!=null&&screenLight.isHeld())screenLight.release();
        screenLight=null;
    }
    @Override public void onReceive(Context c,Intent i){if(i.getData()!=null)confirm(c,i.getData().getLastPathSegment());}
    @SuppressWarnings("deprecation")
    static synchronized boolean post(Context c,JSONObject row,String channel,PendingIntent open){
        int kind=row.optInt("k",-1);if(kind<0||kind>=LABEL.length||!eligible(c,row)||handled(c,row))return false;
        String token=row.optString("token");if(token.isEmpty())return false;
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel nc=manager.getNotificationChannel(channel);
            if(nc==null||nc.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        }
        SharedPreferences p=prefs(c);SharedPreferences.Editor editor=p.edit().putString(KEY,row.toString());
        for(String key:p.getAll().keySet())if(key.startsWith("done:")&&p.getLong(key,0)<=System.currentTimeMillis())editor.remove(key);
        editor.apply();
        Intent i=new Intent(c,ReminderAction.class).setData(new Uri.Builder().scheme("blink").authority("confirm").appendPath(token).build());
        PendingIntent done=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_IMMUTABLE);
        long expires=row.optLong("expires");
        if(row.optBoolean("shared")&&!p.getBoolean("legacyWork",false))expires=Math.min(expires,p.getLong("leaseUntil",0));
        manager.notify(ALERT,new NotificationCompat.Builder(c,channel).setSmallIcon(R.drawable.blink_notification)
            .setContentTitle("Blink · "+row.optString("title")).setContentText(row.optString("body"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(open).setAutoCancel(true)
            .setTimeoutAfter(Math.max(1,expires-System.currentTimeMillis()))
            .addAction(new NotificationCompat.Action.Builder(0,LABEL[kind],done).setAuthenticationRequired(false).build()).build());
        PowerManager power=c.getSystemService(PowerManager.class);
        if(p.getBoolean("wake",false)&&!power.isInteractive()&&manager.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL){
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
