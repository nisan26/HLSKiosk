package com.nisan.hlskiosk;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * The "nuclear option" watchdog.
 *
 * Inside the app, PlayerActivity has its own reconnect logic for stream errors.
 * But what if the entire app process dies (out of memory, native crash, etc.)?
 * That's what this is for: AlarmManager wakes us up every minute and checks
 * if the PlayerActivity is alive. If not, we relaunch it.
 */
public class WatchdogScheduler {

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, WatchdogReceiver.class);
        intent.setAction("com.nisan.hlskiosk.WATCHDOG_CHECK");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);

        long triggerAt = SystemClock.elapsedRealtime() + Config.RESURRECT_CHECK_MS;

        // Use setRepeating with INEXACT to be battery-friendly. On TV boxes
        // power isn't a concern but Android may throttle exact alarms.
        am.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            Config.RESURRECT_CHECK_MS,
            pi
        );

        Log.i(Config.TAG, "Watchdog scheduled every " + Config.RESURRECT_CHECK_MS + "ms");
    }

    static boolean isPlayerRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            String myPkg = context.getPackageName();
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (myPkg.equals(p.processName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(Config.TAG, "isPlayerRunning check failed", e);
        }
        return false;
    }
}
