package com.nisan.hlskiosk;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends Activity {

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView statusText;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private Runnable watchdogRunnable;

    private String currentUrl;
    private int loadedConfigVersion;

    // Exponential backoff state
    private int consecutiveErrors = 0;
    private long lastPosition = -1;
    private long lastPositionTime = 0;

    // Listener that watches for network changes - so when WiFi reconnects
    // we immediately retry instead of waiting for the next backoff tick
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkInfo info = cm.getActiveNetworkInfo();
            boolean connected = info != null && info.isConnected();
            Log.d(Config.TAG, "Network change: connected=" + connected);
            if (connected && player != null) {
                // Force a quick reconnect - we might be in the middle of waiting
                if (player.getPlaybackState() == Player.STATE_IDLE
                    || player.getPlayerError() != null) {
                    Log.i(Config.TAG, "Network back -> reload player");
                    reloadPlayer();
                }
            }
        }
    };

    // Config-change watcher - polls for URL updates and reloads
    private final Runnable configWatcher = new Runnable() {
        @Override
        public void run() {
            try {
                int v = Config.getConfigVersion(PlayerActivity.this);
                if (v != loadedConfigVersion) {
                    Log.i(Config.TAG, "Config version changed (" + loadedConfigVersion + " -> " + v + ")");
                    loadedConfigVersion = v;
                    currentUrl = Config.getUrl(PlayerActivity.this);
                    reloadPlayer();
                }
            } catch (Exception e) {
                Log.e(Config.TAG, "configWatcher error", e);
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        playerView.setUseController(false);

        // Wake lock so the device never sleeps
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
            "HLSKiosk:WakeLock"
        );
        wakeLock.acquire();

        handler = new Handler(Looper.getMainLooper());
        hideSystemUI();

        // Listen to connectivity changes
        IntentFilter f = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, f);

        // Schedule app-resurrection watchdog (idempotent)
        WatchdogScheduler.schedule(this);

        currentUrl = Config.getUrl(this);
        loadedConfigVersion = Config.getConfigVersion(this);
        Log.i(Config.TAG, "PlayerActivity onCreate. URL=" + currentUrl + " ver=" + loadedConfigVersion);

        showStatus("טוען...");
        initPlayer();
        startStallWatchdog();
        handler.postDelayed(configWatcher, 2000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("force_reload", false)) {
            Log.i(Config.TAG, "onNewIntent: force_reload requested");
            currentUrl = Config.getUrl(this);
            loadedConfigVersion = Config.getConfigVersion(this);
            reloadPlayer();
        }
    }

    private void showStatus(String msg) {
        if (statusText != null) {
            statusText.setText(msg);
            statusText.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideStatusRunnable);
            handler.postDelayed(hideStatusRunnable, 4000);
        }
    }

    private final Runnable hideStatusRunnable = () -> {
        if (statusText != null) statusText.setVisibility(View.GONE);
    };

    @OptIn(markerClass = UnstableApi.class)
    private void initPlayer() {
        Log.d(Config.TAG, "Initializing player with URL: " + currentUrl);

        // LoadControl - 30 to 60 seconds of buffering for stability
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                Config.BUFFER_MIN_MS,
                Config.BUFFER_MAX_MS,
                Config.BUFFER_PLAYBACK_MS,
                Config.BUFFER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();

        // Prefer hardware video decoder - important on weak H313 SoC
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build();

        playerView.setPlayer(player);

        // HTTP factory with reasonable timeouts
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(Config.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(Config.HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("HLSKiosk/3.0");

        MediaSource mediaSource = new HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(currentUrl)));

        player.setMediaSource(mediaSource);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(Config.TAG, "Player error: " + error.getErrorCodeName()
                    + " - " + error.getMessage(), error);
                consecutiveErrors++;
                scheduleReconnect("error: " + error.getErrorCodeName());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_IDLE:
                        Log.d(Config.TAG, "STATE_IDLE");
                        break;
                    case Player.STATE_BUFFERING:
                        Log.d(Config.TAG, "STATE_BUFFERING");
                        showStatus("מתחבר...");
                        break;
                    case Player.STATE_READY:
                        Log.d(Config.TAG, "STATE_READY - playing");
                        consecutiveErrors = 0; // success - reset backoff
                        showStatus("מנגן");
                        break;
                    case Player.STATE_ENDED:
                        Log.w(Config.TAG, "STATE_ENDED (live stream shouldn't end)");
                        scheduleReconnect("stream ended");
                        break;
                }
            }
        });

        player.setPlayWhenReady(true);
        player.prepare();
    }

    private long computeBackoffMs() {
        // Exponential: 3s, 5s, 8s, 13s, capped at 20s
        long base = Config.RECONNECT_DELAY_MS_MIN;
        long backoff = (long) (base * Math.pow(1.6, Math.min(consecutiveErrors, 6)));
        return Math.min(backoff, Config.RECONNECT_DELAY_MS_MAX);
    }

    private void scheduleReconnect(final String reason) {
        long delay = computeBackoffMs();
        Log.w(Config.TAG, "Scheduling reconnect in " + delay + "ms (reason: "
            + reason + ", errors=" + consecutiveErrors + ")");
        showStatus("מנסה להתחבר מחדש...");
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, delay);
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(Config.TAG, "Reconnect attempt #" + consecutiveErrors);
            reloadPlayer();
        }
    };

    private void reloadPlayer() {
        handler.post(() -> {
            try {
                if (player != null) {
                    player.release();
                    player = null;
                }
                lastPosition = -1;
                lastPositionTime = 0;
                initPlayer();
            } catch (Exception e) {
                Log.e(Config.TAG, "Error during reload", e);
                consecutiveErrors++;
                handler.postDelayed(reconnectRunnable, computeBackoffMs());
            }
        });
    }

    private void startStallWatchdog() {
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (player != null && player.isPlaying()) {
                        long pos = player.getCurrentPosition();
                        long now = System.currentTimeMillis();
                        if (lastPosition == pos && lastPositionTime > 0) {
                            if (now - lastPositionTime > Config.STALL_TIMEOUT_MS) {
                                Log.w(Config.TAG, "Stream stalled - restarting");
                                consecutiveErrors++;
                                scheduleReconnect("stall watchdog");
                                lastPosition = -1;
                                lastPositionTime = 0;
                            }
                        } else {
                            lastPosition = pos;
                            lastPositionTime = now;
                        }
                    }
                } catch (Exception e) {
                    Log.e(Config.TAG, "Stall watchdog error", e);
                }
                handler.postDelayed(this, Config.WATCHDOG_INTERVAL_MS);
            }
        };
        handler.postDelayed(watchdogRunnable, Config.WATCHDOG_INTERVAL_MS);
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(networkReceiver); } catch (Exception ignored) {}
        if (handler != null) {
            handler.removeCallbacks(reconnectRunnable);
            if (watchdogRunnable != null) handler.removeCallbacks(watchdogRunnable);
            handler.removeCallbacks(configWatcher);
            handler.removeCallbacks(hideStatusRunnable);
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onBackPressed() {
        // kiosk - blocked
    }
}
