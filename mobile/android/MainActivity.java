package com.zhaowl.blink;

import android.content.Intent;
import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static volatile boolean visible;
    private long lastTouch;
    @Override public void onCreate(Bundle state){
        registerPlugin(BlinkTimerPlugin.class);registerPlugin(BlinkCloudPlugin.class);super.onCreate(state);
        // Back leaves Blink running in the background, like Home; reminders continue.
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){moveTaskToBack(true);}
        });
    }
    @Override public void onResume(){super.onResume();visible=true;}
    @Override public void onPause(){visible=false;super.onPause();}
    @Override public void onUserInteraction(){
        super.onUserInteraction();
        long now=System.currentTimeMillis();
        if(now-lastTouch>30000){lastTouch=now;ReminderAction.prefs(this).edit().putLong("lastActive",now).apply();}
    }
    @Override public void onDestroy(){
        if(isFinishing()&&!BlinkTimerAlarm.persistent(this))BlinkTimerAlarm.closeSession(this);
        super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent){setIntent(intent);super.onNewIntent(intent);}
}
