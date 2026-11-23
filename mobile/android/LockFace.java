package com.zhaowl.blink;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** A read-only surface. No records, navigation or access to the underlying WebView. */
public final class LockFace extends LinearLayout {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("blink-face",Context.MODE_PRIVATE);}
    static JSONObject current(Context c){
        try{
            long now=System.currentTimeMillis();
            JSONArray rows=new JSONArray(prefs(c).getString("rows","[]"));
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                if(row.getLong("from")<=now&&now<row.getLong("until"))return row;
            }
        }catch(org.json.JSONException ignored){}
        return null;
    }
    static boolean autoEnabled(Context c){
        JSONObject row=current(c);
        return row!=null&&(row.optBoolean("work")?prefs(c).getBoolean("auto",true):prefs(c).getBoolean("timed",true));
    }
    private final TextView mode,label,clock;
    private final Runnable close;
    private final boolean automatic;
    private final Runnable update=new Runnable(){public void run(){
        JSONObject row=current(getContext());
        if(row==null||(automatic&&!autoEnabled(getContext()))){close.run();return;}
        mode.setText(row.optBoolean("work")?"Blink  ·  工作":"非工作  ·  定时用眼");
        label.setText(row.optString("label"));
        long seconds=Math.max(0,(row.optLong("until")-System.currentTimeMillis()+999)/1000);
        clock.setText(String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60));
        postDelayed(this,500);
    }};
    LockFace(Context c,boolean automatic,Runnable close){
        super(c);this.close=close;this.automatic=automatic;
        setOrientation(VERTICAL);setGravity(Gravity.CENTER);setBackgroundColor(Color.rgb(19,26,28));
        setClickable(true);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        mode=text(16,0xffa4d9c3);label=text(19,0xffd6ddda);label.setPadding(0,dp(45),0,0);
        clock=text(66,Color.WHITE);clock.setFontFeatureSettings("tnum");clock.setPadding(0,0,0,dp(42));
        Button exit=new Button(c);exit.setText("关闭计时页");exit.setTextColor(0xffc5d7ce);
        exit.setBackgroundColor(Color.TRANSPARENT);exit.setOnClickListener(v->close.run());addView(exit);
    }
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private TextView text(float size,int color){
        TextView t=new TextView(getContext());t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);addView(t);return t;
    }
    @Override protected void onWindowVisibilityChanged(int visibility){
        super.onWindowVisibilityChanged(visibility);
        if(update!=null){removeCallbacks(update);if(visibility==VISIBLE)post(update);}
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(update);post(update);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(update);super.onDetachedFromWindow();}
}
