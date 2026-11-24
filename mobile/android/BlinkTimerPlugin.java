package com.zhaowl.blink;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(name="BlinkTimer")
public class BlinkTimerPlugin extends Plugin {
    private static BlinkTimerPlugin current;
    @Override public void load(){current=this;}

    /** Delivers a native event to the web view; queued in preferences until a web view reads it. */
    static void emit(Context context,JSONObject event){
        BlinkTimerPlugin plugin=current;
        if(plugin!=null&&plugin.getBridge()!=null){
            try{plugin.notifyListeners("native",new JSObject(event.toString()),false);return;}
            catch(JSONException ignored){/* fall back to the queue */}
        }
        if("visibility".equals(event.optString("type")))return;
        SharedPreferences prefs=ReminderAction.prefs(context);
        try{
            JSONArray queue=new JSONArray(prefs.getString("events","[]"));
            queue.put(event);
            while(queue.length()>20)queue.remove(0);
            prefs.edit().putString("events",queue.toString()).apply();
        }catch(JSONException e){prefs.edit().remove("events").apply();}
    }

    @PluginMethod public void configure(PluginCall call) {
        Context ctx = getContext();
        String plan = call.getArray("items", new JSArray()).toString();
        getActivity().runOnUiThread(()->configurePlan(ctx,call,plan));
    }
    private void configurePlan(Context ctx,PluginCall call,String plan){
        if(getActivity().isFinishing()||getActivity().isDestroyed()){call.resolve();return;}
        SharedPreferences prefs = ReminderAction.prefs(ctx);
        String owner=call.getString("owner","");
        SharedPreferences.Editor edit=prefs.edit().putString("owner",owner).putString("plan",plan)
            .putString("channel",call.getString("channel","blink-00"))
            .putBoolean("lock",call.getBoolean("lock",true)).putBoolean("wake",call.getBoolean("wake",false))
            .putBoolean("persist",call.getBoolean("persist",false));
        if(!owner.equals(prefs.getString("owner",""))){
            edit.remove("restUntil").remove("deliveredAt").remove("awaySince").remove("lastRetry");
            for(String key:prefs.getAll().keySet())if(key.startsWith("done:"))edit.remove(key);
        }
        long awaySince=call.getData().optLong("awaySince",0);
        if(awaySince>0&&prefs.getLong("awaySince",0)==0)edit.putLong("awaySince",awaySince).remove("lastRetry");
        if(!edit.commit()){call.reject("无法保存后台计时");return;}
        ReminderAction.clearIneligible(ctx);
        try {
            JSONObject next=BlinkTimerAlarm.next(ctx);
            BlinkTimerAlarm.schedule(ctx,next);
            if(next==null&&awaySince==0)ctx.stopService(new Intent(ctx,BlinkTimerService.class));
            else BlinkTimerService.start(ctx);
            BlinkTimerService.updateCountdown(next);
            BlinkWidget.refresh(ctx);
            call.resolve();
        } catch (RuntimeException e) { call.reject("后台计时未启动",e); }
    }
    @PluginMethod public void pendingConfirmations(PluginCall call){
        String owner=call.getString("owner","");
        JSONArray rows=ReminderAction.receipts(getContext()),matching=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row!=null&&owner.equals(row.optString("owner")))matching.put(row);
        }
        JSObject out=new JSObject();out.put("rows",matching);call.resolve(out);
    }
    @PluginMethod public void acknowledge(PluginCall call){
        synchronized(ReminderAction.class){
        JSArray ids=call.getArray("ids",new JSArray());
        String owner=call.getString("owner","");
        JSONArray rows=ReminderAction.receipts(getContext()),keep=new JSONArray();
        java.util.HashSet<String> saved=new java.util.HashSet<>();
        for(int i=0;i<ids.length();i++)saved.add(ids.optString(i));
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
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
        if(ReminderAction.post(getContext(),row,call.getString("channel","blink-00"),BlinkTimerAlarm.open(getContext(),row.optString("token"))))call.resolve();
        else call.reject("系统通知不可用");
    }
    @PluginMethod public void prompt(PluginCall call){
        JSObject p=call.getObject("prompt");if(p==null){call.reject("Missing prompt");return;}
        ReminderAction.showPrompt(getContext(),p,call.getString("channel","blink-00"));call.resolve();
    }
    @PluginMethod public void clearPrompt(PluginCall call){
        ReminderAction.clearPrompt(getContext(),call.getString("id",""));call.resolve();
    }
    /** Activity in the app counts as presence for the native pause logic too. */
    @PluginMethod public void active(PluginCall call){
        SharedPreferences prefs=ReminderAction.prefs(getContext());
        prefs.edit().putLong("lastActive",System.currentTimeMillis()).putInt("unanswered",0).remove("awaySince").remove("lastRetry").apply();
        call.resolve();
    }
    @PluginMethod public void lifecycle(PluginCall call){
        SharedPreferences prefs=ReminderAction.prefs(getContext());
        JSObject out=new JSObject();
        long closed=prefs.getLong("closedAt",0);
        if(closed>0)out.put("closedAt",closed);
        try{out.put("events",new JSONArray(prefs.getString("events","[]")));}catch(JSONException e){out.put("events",new JSONArray());}
        prefs.edit().remove("closedAt").remove("events").apply();
        call.resolve(out);
    }

    private static boolean appOp(Context c,String permission){
        String op=AppOpsManager.permissionToOp(permission);
        if(op==null)return true;
        int mode=c.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(op,Process.myUid(),c.getPackageName());
        return mode==AppOpsManager.MODE_ALLOWED||mode==AppOpsManager.MODE_DEFAULT&&!"android:turn_screen_on".equals(op);
    }
    private static JSObject row(String label,Object state,String action,boolean persist,String hint){
        JSObject r=new JSObject();r.put("label",label);r.put("state",state==null?JSONObject.NULL:state);r.put("action",action);r.put("persist",persist);
        if(hint!=null)r.put("hint",hint);return r;
    }
    @PluginMethod public void health(PluginCall call){
        Context c=getContext();
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        JSArray rows=new JSArray();
        rows.put(row("系统通知",nm.areNotificationsEnabled(),"notifications",false,null));
        rows.put(row("准时提醒（闹钟权限）",BlinkTimerAlarm.exactAllowed(c),"exact",true,"关闭时提醒可能被系统延后数分钟"));
        rows.put(row("不限制后台耗电",c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName()),"battery",true,"否则锁屏后可能被系统停止"));
        if(Build.VERSION.SDK_INT>=28){
            android.app.ActivityManager am=c.getSystemService(android.app.ActivityManager.class);
            rows.put(row("允许后台运行",!am.isBackgroundRestricted(),"details",true,null));
        }
        if(Build.VERSION.SDK_INT>=34)rows.put(row("提醒时亮屏权限",appOp(c,"android.permission.TURN_SCREEN_ON"),"details",false,"小米、一加等机型在「其他权限」或「特殊应用权限 → 开启屏幕」中"));
        if(Build.VERSION.SDK_INT>=36)rows.put(row("锁屏实时卡片（Live Updates）",nm.canPostPromotedNotifications(),"promoted",false,null));
        String vendor=Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if(vendor.contains("xiaomi")||vendor.contains("redmi")||vendor.contains("oppo")||vendor.contains("oneplus")||vendor.contains("realme")||vendor.contains("vivo")||vendor.contains("honor")||vendor.contains("huawei"))
            rows.put(row("自启动 / 后台运行（厂商设置）",null,"autostart",true,"系统无法读取此项，请在打开的页面中允许 Blink"));
        if(vendor.contains("xiaomi")||vendor.contains("redmi")||vendor.contains("oppo")||vendor.contains("oneplus")||vendor.contains("realme"))
            rows.put(row("锁屏显示通知（厂商设置）",null,"notifications",false,"在通知设置中打开「锁屏通知」；小米还需在锁屏样式中关闭「仅显示时间」类模板"));
        JSObject out=new JSObject();out.put("rows",rows);call.resolve(out);
    }
    @PluginMethod public void openSetting(PluginCall call){
        Context c=getContext();String action=call.getString("action","details"),pkg=c.getPackageName();
        Intent intent;
        switch(action){
            case "notifications":intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,pkg);break;
            case "exact":intent=Build.VERSION.SDK_INT>=31?new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+pkg)):null;break;
            case "battery":intent=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+pkg));break;
            case "promoted":intent=Build.VERSION.SDK_INT>=36?new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,pkg):null;break;
            case "autostart":intent=autostart(c);break;
            default:intent=null;
        }
        if(intent==null)intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+pkg));
        try{getActivity().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException e){getActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        call.resolve();
    }
    private static Intent autostart(Context c){
        String[][] candidates={
            {"com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.coloros.safecenter","com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oplus.safecenter","com.oplus.safecenter.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager","com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.huawei.systemmanager","com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.hihonor.systemmanager","com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"}};
        for(String[] cn:candidates){
            Intent i=new Intent().setClassName(cn[0],cn[1]);
            if(i.resolveActivity(c.getPackageManager())!=null)return i;
        }
        return null;
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
