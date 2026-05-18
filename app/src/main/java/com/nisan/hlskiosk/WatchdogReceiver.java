package com.nisan.hlskiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean alive = WatchdogScheduler.isPlayerRunning(context);
        Log.d(Config.TAG, "Watchdog tick. Player alive=" + alive);

        if (!alive) {
            Log.w(Config.TAG, "Watchdog: player NOT running, resurrecting!");
            Intent i = new Intent(context, PlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                context.startActivity(i);
            } catch (Exception e) {
                Log.e(Config.TAG, "Watchdog: failed to launch", e);
            }
        }
    }
}
