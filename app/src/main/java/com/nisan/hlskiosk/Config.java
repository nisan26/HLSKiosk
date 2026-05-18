package com.nisan.hlskiosk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized configuration and SharedPreferences access.
 * Keeps the HLS URL and other settings in one place.
 */
public class Config {
    public static final String TAG = "HLSKiosk";

    // ====== ברירת מחדל למקרה של התקנה ראשונית ======
    public static final String DEFAULT_HLS_URL =
        "http://satview.ddns.net/lus300/index.m3u8";
    // =================================================

    // Broadcast actions (must match PC tool)
    public static final String ACTION_UPDATE_URL = "com.nisan.hlskiosk.UPDATE_URL";
    public static final String ACTION_RELOAD     = "com.nisan.hlskiosk.RELOAD";
    public static final String ACTION_RESTART    = "com.nisan.hlskiosk.RESTART";
    public static final String ACTION_GET_STATUS = "com.nisan.hlskiosk.GET_STATUS";
    public static final String EXTRA_URL         = "url";

    // SharedPreferences keys
    private static final String PREFS_NAME    = "HLSKioskPrefs";
    private static final String PREF_HLS_URL  = "hls_url";
    private static final String PREF_VERSION  = "config_version";

    // Stability tuning - tested values for 1GB RAM / Allwinner H313
    public static final long RECONNECT_DELAY_MS_MIN = 3000;
    public static final long RECONNECT_DELAY_MS_MAX = 20000;
    public static final long WATCHDOG_INTERVAL_MS   = 15000;
    public static final long STALL_TIMEOUT_MS       = 25000;

    public static final int BUFFER_MIN_MS = 30000;
    public static final int BUFFER_MAX_MS = 60000;
    public static final int BUFFER_PLAYBACK_MS = 2500;
    public static final int BUFFER_REBUFFER_MS = 5000;

    public static final int HTTP_CONNECT_TIMEOUT_MS = 10000;
    public static final int HTTP_READ_TIMEOUT_MS    = 10000;

    // App resurrection check interval (in case the whole process dies)
    public static final long RESURRECT_CHECK_MS = 60_000;

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getUrl(Context ctx) {
        return prefs(ctx).getString(PREF_HLS_URL, DEFAULT_HLS_URL);
    }

    public static void setUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(PREF_HLS_URL, url).apply();
    }

    public static int getConfigVersion(Context ctx) {
        return prefs(ctx).getInt(PREF_VERSION, 0);
    }

    public static void bumpConfigVersion(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(PREF_VERSION, p.getInt(PREF_VERSION, 0) + 1).apply();
    }
}
