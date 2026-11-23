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
        SharedPreferences prefs = ctx.getSharedPreferences("blink-timer", Context.MODE_PRIVATE);
        prefs.edit().putString("plan", plan).putString("channel", call.getString("channel","blink-00"))
            .putBoolean("lock", call.getBoolean("lock",true)).apply();
        Intent service = new Intent(ctx, BlinkTimerService.class);
        try {
            if (plan.equals("[]")) ctx.stopService(service);
            else if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(service);
            else ctx.startService(service);
            call.resolve();
        } catch (RuntimeException e) { call.reject("后台计时未启动",e); }
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
