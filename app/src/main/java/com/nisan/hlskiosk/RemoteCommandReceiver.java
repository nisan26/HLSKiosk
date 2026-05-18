package com.nisan.hlskiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives commands from the PC tool via "adb shell am broadcast".
 * Registered in AndroidManifest so it works EVEN WHEN THE APP IS NOT RUNNING.
 *
 * Commands:
 *   UPDATE_URL  --es url <new_url>   : persist new URL and (re)launch player
 *   RELOAD                            : tell running player to reload current URL
 *   RESTART                           : kill and re-launch the activity
 *   GET_STATUS                        : print status to logcat for PC tool to read
 */
public class RemoteCommandReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.i(Config.TAG, "RemoteCommand received: " + action);

        switch (action) {
            case Config.ACTION_UPDATE_URL: {
                String newUrl = intent.getStringExtra(Config.EXTRA_URL);
                if (newUrl != null && !newUrl.isEmpty()) {
                    Config.setUrl(context, newUrl);
                    Config.bumpConfigVersion(context);
                    Log.i(Config.TAG, "REMOTE: URL persisted -> " + newUrl);
                    // Always (re)launch the player so the change takes effect immediately,
                    // even if the app was closed.
                    launchPlayer(context, true);
                } else {
                    Log.w(Config.TAG, "REMOTE: UPDATE_URL with empty url, ignored");
                }
                break;
            }

            case Config.ACTION_RELOAD: {
                Log.i(Config.TAG, "REMOTE: reload requested");
                // Bump version - PlayerActivity polls this and reloads when it changes
                Config.bumpConfigVersion(context);
                launchPlayer(context, false);
                break;
            }

            case Config.ACTION_RESTART: {
                Log.i(Config.TAG, "REMOTE: hard restart requested");
                launchPlayer(context, true);
                break;
            }

            case Config.ACTION_GET_STATUS: {
                String url = Config.getUrl(context);
                int ver = Config.getConfigVersion(context);
                // PC tool greps logcat for "STATUS:"
                Log.i(Config.TAG, "STATUS: url=" + url + " configVersion=" + ver);
                break;
            }
        }
    }

    private void launchPlayer(Context ctx, boolean forceRestart) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (forceRestart) {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("force_reload", true);
        }
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.e(Config.TAG, "Failed to launch player from receiver", e);
        }
    }
}
