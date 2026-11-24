package com.zhaowl.blink;

import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name="BlinkTimer")
public class BlinkTimerPlugin extends Plugin {
    @PluginMethod public void configure(PluginCall call) {
        Context ctx = getContext();
        String plan = call.getArray("items", new JSArray()).toString();
        getActivity().runOnUiThread(()->configurePlan(ctx,call,plan));
    }
    private void configurePlan(Context ctx,PluginCall call,String plan){
        if(getActivity().isFinishing()||getActivity().isDestroyed()){call.resolve();return;}
        SharedPreferences prefs = ctx.getSharedPreferences("blink-timer", Context.MODE_PRIVATE);
        String owner=call.getString("owner","");
        JSObject lease=call.getObject("lease",new JSObject());
        SharedPreferences.Editor edit=prefs.edit().putString("owner",owner).putString("plan",plan)
            .putString("channel",call.getString("channel","blink-00"))
            .putBoolean("lock",call.getBoolean("lock",true)).putBoolean("wake",call.getBoolean("wake",false));
        BlinkTimerService.configureWork(ctx,prefs,edit,owner,call.getBoolean("monitorWork",false),
            lease==null?new JSObject():lease,
            call.getBoolean("legacyWork",false),call.getData().optLong("workCheckedElapsed",-1),
            call.getData().optInt("workBoot",-2));
        if(!owner.equals(prefs.getString("owner",""))){
            edit.remove("restUntil").remove("deliveredAt");
            for(String key:prefs.getAll().keySet())if(key.startsWith("done:"))edit.remove(key);
        }
        if(!edit.commit()){call.reject("无法保存后台计时");return;}
        ReminderAction.clearIneligible(ctx);
        Intent service = new Intent(ctx, BlinkTimerService.class);
        try {
            org.json.JSONObject next=BlinkTimerAlarm.next(ctx);
            BlinkTimerAlarm.schedule(ctx,next);
            boolean monitoring=BlinkTimerService.shouldMonitorWork(ctx);
            BlinkTimerAlarm.scheduleWorkCheck(ctx,monitoring);
            if (!BlinkWorkScheduling.keepAlive(next!=null,monitoring)) ctx.stopService(service);
            else if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(service);
            else ctx.startService(service);
            call.resolve();
        } catch (RuntimeException e) { call.reject("后台计时未启动",e); }
    }
    @PluginMethod public void validateTap(PluginCall call){
        getActivity().runOnUiThread(()->{
            Context ctx=getContext();
            org.json.JSONObject row=ReminderAction.active(ctx);
            boolean valid=row!=null&&call.getString("owner","").equals(row.optString("owner"))
                &&call.getString("token","").equals(row.optString("token"));
            JSObject response=new JSObject().put("valid",valid);
            if(valid&&row.optBoolean("shared")){
                try{response.put("authority",BlinkTimerService.workAuthority(ctx));}
                catch(org.json.JSONException e){response.put("valid",false);}
            }
            call.resolve(response);
        });
    }
    @PluginMethod public void stopShared(PluginCall call){
        getActivity().runOnUiThread(()->{
            Context ctx=getContext();
            String owner=call.getString("owner","");
            long revision=call.getData().optLong("revision",-1);
            if(!ReminderAction.stopShared(ctx,owner,revision)){
                call.reject("无法停止旧设备提醒");return;
            }
            try{
                org.json.JSONObject next=BlinkTimerAlarm.next(ctx);
                BlinkTimerAlarm.schedule(ctx,next);
                BlinkTimerService.updateCountdown(next);
                call.resolve();
            }catch(RuntimeException e){call.reject("无法重新排程本机提醒",e);}
        });
    }
    @PluginMethod public void pendingConfirmations(PluginCall call){
        String owner=call.getString("owner","");
        org.json.JSONArray rows=ReminderAction.receipts(getContext()),matching=new org.json.JSONArray();
        for(int i=0;i<rows.length();i++){
            org.json.JSONObject row=rows.optJSONObject(i);
            if(row!=null&&owner.equals(row.optString("owner")))matching.put(row);
        }
        JSObject out=new JSObject();out.put("rows",matching);call.resolve(out);
    }
    @PluginMethod public void acknowledge(PluginCall call){
        synchronized(ReminderAction.class){
        JSArray ids=call.getArray("ids",new JSArray());
        String owner=call.getString("owner","");
        org.json.JSONArray rows=ReminderAction.receipts(getContext()),keep=new org.json.JSONArray();
        java.util.HashSet<String> saved=new java.util.HashSet<>();
        for(int i=0;i<ids.length();i++)saved.add(ids.optString(i));
        for(int i=0;i<rows.length();i++){
            org.json.JSONObject row=rows.optJSONObject(i);
            if(row!=null&&(!owner.equals(row.optString("owner"))||!saved.contains(row.optString("id"))))keep.put(row);
        }
        ReminderAction.prefs(getContext()).edit().putString("receipts",keep.toString()).apply();}
        call.resolve();
    }
    @PluginMethod public void clearReminder(PluginCall call){
        ReminderAction.clear(getContext(),call.getString("token",""));call.resolve();
    }
    @PluginMethod public void showReminder(PluginCall call){
        JSObject row=call.getObject("row");if(row==null){call.reject("Missing reminder");return;}
        Intent i=new Intent(getContext(),MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("blinkToken",row.optString("token"));
        android.app.PendingIntent open=android.app.PendingIntent.getActivity(getContext(),1,i,android.app.PendingIntent.FLAG_IMMUTABLE|android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        if(ReminderAction.post(getContext(),row,call.getString("channel","blink-00"),open))call.resolve();
        else call.reject("系统通知不可用");
    }
    @Override protected void handleOnNewIntent(Intent intent){
        String token=intent.getStringExtra("blinkToken");
        if(token!=null){JSObject payload=new JSObject();payload.put("token",token);notifyListeners("tap",payload,true);intent.removeExtra("blinkToken");}
    }
    @PluginMethod public void consumeTap(PluginCall call) {
        Intent intent=getActivity().getIntent();
        JSObject result=new JSObject();
        result.put("token",intent.getStringExtra("blinkToken"));
        intent.removeExtra("blinkToken");
        call.resolve(result);
    }
}
