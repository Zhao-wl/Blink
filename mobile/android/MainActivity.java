package com.zhaowl.blink;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static volatile boolean visible;
    @Override public void onCreate(Bundle state){registerPlugin(BlinkTimerPlugin.class);super.onCreate(state);}
    @Override public void onResume(){super.onResume();visible=true;}
    @Override public void onPause(){visible=false;super.onPause();}
    @Override protected void onNewIntent(Intent intent){setIntent(intent);super.onNewIntent(intent);}
}
