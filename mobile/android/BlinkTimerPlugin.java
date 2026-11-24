package com.zhaowl.blink;

import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name="BlinkTimer")
public class BlinkTimerPlugin extends Plugin {
    @PluginMethod public void configureFace(PluginCall call){
        LockFace.prefs(getContext()).edit().putString("rows",call.getArray("rows",new JSArray()).toString())
            .putBoolean("auto",call.getBoolean("auto",true)).putBoolean("timed",call.getBoolean("timed",true)).apply();
        call.resolve();
    }
    @PluginMethod public void openFace(PluginCall call){
        getActivity().runOnUiThread(()->{
            getActivity().startActivity(new Intent(getActivity(),LockClockActivity.class));call.resolve();
        });
    }
    @PluginMethod public void configure(PluginCall call) {
        Context ctx = getContext();
        String plan = call.getArray("items", new JSArray()).toString();
        getActivity().runOnUiThread(()->configurePlan(ctx,call,plan));
    }
    private void configurePlan(Context ctx,PluginCall call,String plan){
        SharedPreferences prefs = ctx.getSharedPreferences("blink-timer", Context.MODE_PRIVATE);
        prefs.edit().putString("plan", plan).putString("channel", call.getString("channel","blink-00"))
            .putBoolean("lock", call.getBoolean("lock",true)).putBoolean("wake",call.getBoolean("wake",true)).apply();
        Intent service = new Intent(ctx, BlinkTimerService.class);
        try {
            BlinkTimerAlarm.schedule(ctx,BlinkTimerAlarm.next(ctx));
            if (plan.equals("[]")) ctx.stopService(service);
            else if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(service);
            else ctx.startService(service);
            call.resolve();
        } catch (RuntimeException e) { call.reject("后台计时未启动",e); }
    }
    @PluginMethod public void pendingConfirmations(PluginCall call){
        JSObject out=new JSObject();out.put("rows",ReminderAction.receipts(getContext()));call.resolve(out);
    }
    @PluginMethod public void acknowledge(PluginCall call){
        synchronized(ReminderAction.class){
        JSArray ids=call.getArray("ids",new JSArray());
        org.json.JSONArray rows=ReminderAction.receipts(getContext()),keep=new org.json.JSONArray();
        java.util.HashSet<String> saved=new java.util.HashSet<>();
        for(int i=0;i<ids.length();i++)saved.add(ids.optString(i));
        for(int i=0;i<rows.length();i++)if(!saved.contains(rows.optJSONObject(i).optString("id")))keep.put(rows.opt(i));
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
