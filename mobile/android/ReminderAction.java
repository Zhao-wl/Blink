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
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("blink-timer",Context.MODE_PRIVATE);}
    static JSONObject active(Context c){
        try{
            JSONObject row=new JSONObject(prefs(c).getString(KEY,"{}"));
            return row.optLong("expires")>System.currentTimeMillis()?row:null;
        }catch(JSONException e){return null;}
    }
    static JSONArray receipts(Context c){
        try{return new JSONArray(prefs(c).getString("receipts","[]"));}catch(JSONException e){return new JSONArray();}
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
        }
    }
    static synchronized boolean confirm(Context c,String token){
        JSONObject row=active(c);if(row==null||!token.equals(row.optString("token")))return false;
        try{
            long now=System.currentTimeMillis(),pause=row.optLong("rest");
            JSONArray pending=receipts(c);
            JSONObject receipt=new JSONObject().put("id",id(row)).put("k",row.getInt("k")).put("at",now).put("rest",pause);
            pending.put(receipt);
            SharedPreferences.Editor edit=prefs(c).edit().remove(KEY).putString("receipts",pending.toString())
                .putLong("done:"+id(row),now+12*60000L);
            if(pause>0)edit.putLong("restUntil",now+pause);
            if(!edit.commit())return false;
            c.getSystemService(NotificationManager.class).cancel(ALERT);return true;
        }catch(JSONException e){return false;}
    }
    @Override public void onReceive(Context c,Intent i){if(i.getData()!=null)confirm(c,i.getData().getLastPathSegment());}
    @SuppressWarnings("deprecation")
    static synchronized void post(Context c,JSONObject row,String channel,PendingIntent open){
        int kind=row.optInt("k",-1);if(kind<0||kind>=LABEL.length||handled(c,row))return;
        String token=row.optString("token");if(token.isEmpty())return;
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled())return;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel nc=manager.getNotificationChannel(channel);
            if(nc==null||nc.getImportance()==NotificationManager.IMPORTANCE_NONE)return;
        }
        SharedPreferences p=prefs(c);SharedPreferences.Editor editor=p.edit().putString(KEY,row.toString());
        for(String key:p.getAll().keySet())if(key.startsWith("done:")&&p.getLong(key,0)<=System.currentTimeMillis())editor.remove(key);
        editor.apply();
        Intent i=new Intent(c,ReminderAction.class).setData(new Uri.Builder().scheme("blink").authority("confirm").appendPath(token).build());
        PendingIntent done=PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_IMMUTABLE);
        manager.notify(ALERT,new NotificationCompat.Builder(c,channel).setSmallIcon(R.drawable.blink_notification)
            .setContentTitle("Blink · "+row.optString("title")).setContentText(row.optString("body"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(open).setAutoCancel(true)
            .setTimeoutAfter(Math.max(1,row.optLong("expires")-System.currentTimeMillis()))
            .addAction(new NotificationCompat.Action.Builder(0,LABEL[kind],done).setAuthenticationRequired(false).build()).build());
        PowerManager power=c.getSystemService(PowerManager.class);
        if(p.getBoolean("wake",true)&&!power.isInteractive()&&manager.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL){
            try{
                PowerManager.WakeLock light=power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"Blink:reminder");
                light.acquire(15000L);
            }catch(SecurityException ignored){/* Notification remains available under device policy. */}
        }
    }
}
