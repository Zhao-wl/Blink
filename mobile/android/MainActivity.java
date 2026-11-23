package com.zhaowl.blink;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static volatile boolean visible;
    private LockFace face;
    private boolean started;
    private final BroadcastReceiver screen=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(Intent.ACTION_USER_PRESENT.equals(i.getAction()))hideFace();
            else if(started)showFace();
        }
    };
    @Override public void onCreate(Bundle state){
        registerPlugin(BlinkTimerPlugin.class);registerPlugin(BlinkCloudPlugin.class);super.onCreate(state);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen,filter);
    }
    private void showFace(){
        if(face!=null||!LockFace.autoEnabled(this))return;
        FrameLayout root=findViewById(android.R.id.content);
        for(int i=0;i<root.getChildCount();i++)root.getChildAt(i).setVisibility(View.INVISIBLE);
        face=new LockFace(this,true,()->{
            if(getSystemService(KeyguardManager.class).isKeyguardLocked())moveTaskToBack(true);
            hideFace();
        });
        root.addView(face,new FrameLayout.LayoutParams(-1,-1));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }
    private void hideFace(){
        if(face==null)return;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        FrameLayout root=findViewById(android.R.id.content);
        root.removeView(face);face=null;
        for(int i=0;i<root.getChildCount();i++)root.getChildAt(i).setVisibility(View.VISIBLE);
    }
    @Override public void onStart(){super.onStart();started=true;}
    @Override public void onStop(){started=false;super.onStop();}
    @Override public void onResume(){super.onResume();visible=true;
        if(!getSystemService(KeyguardManager.class).isKeyguardLocked()&&getSystemService(PowerManager.class).isInteractive())hideFace();
    }
    @Override public void onPause(){
        if(!getSystemService(PowerManager.class).isInteractive())showFace();
        visible=false;super.onPause();
    }
    @Override public void onBackPressed(){if(face!=null){moveTaskToBack(true);hideFace();}else super.onBackPressed();}
    @Override public void onDestroy(){unregisterReceiver(screen);super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){setIntent(intent);super.onNewIntent(intent);}
}
