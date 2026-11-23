package com.zhaowl.blink;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public class LockClockActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(new LockFace(this,false,this::finish));
    }
}
