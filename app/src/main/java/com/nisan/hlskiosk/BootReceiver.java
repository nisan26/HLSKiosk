package com.nisan.hlskiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(Config.TAG, "BootReceiver: " + action);
        if (action == null) return;

        // Accept any of the boot-like actions
        if (action.equals(Intent.ACTION_BOOT_COMPLETED)
            || action.equals("android.intent.action.LOCKED_BOOT_COMPLETED")
            || action.equals("android.intent.action.QUICKBOOT_POWERON")
            || action.equals("com.htc.intent.action.QUICKBOOT_POWERON")) {

            // Launch player
            Intent i = new Intent(context, PlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                context.startActivity(i);
                Log.i(Config.TAG, "BootReceiver: player launched");
            } catch (Exception e) {
                Log.e(Config.TAG, "BootReceiver: launch failed", e);
            }

            // Schedule the resurrection watchdog
            WatchdogScheduler.schedule(context);
        }
    }
}
