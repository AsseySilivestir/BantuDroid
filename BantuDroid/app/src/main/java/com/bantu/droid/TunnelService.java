package com.bantu.droid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

/**
 * TunnelService — foreground service that keeps tunnels alive when the
 * app is backgrounded or the screen is off.
 *
 * Without a foreground service, Android kills the app process within
 * minutes of backgrounding, tearing down the WebSocket/SSH/cloudflared
 * connections. This service holds a TunnelManager instance and shows
 * a persistent notification with the public URL.
 */
public class TunnelService extends Service {

    private static final String TAG = "TunnelService";
    private static final int NOTIF_ID = 2002;
    private static final String CHANNEL_ID = "bantu_tunnel_channel";

    public static final String ACTION_START = "com.bantu.droid.ACTION_START_TUNNEL";
    public static final String ACTION_STOP  = "com.bantu.droid.ACTION_STOP_TUNNEL";
    public static final String EXTRA_PROVIDER_INDEX = "provider_index";
    public static final String EXTRA_LOCAL_PORT = "local_port";

    private TunnelManager tunnelMgr;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean tunnelActive = false;
    private String currentPublicUrl = null;
    private int providerIndex = 0;
    private int localPort = 8080;

    private static volatile TunnelService instance = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        tunnelMgr = new TunnelManager(this);
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            startFromSettings();
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        providerIndex = intent.getIntExtra(EXTRA_PROVIDER_INDEX, -1);
        localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, -1);
        if (providerIndex < 0 || localPort < 0) {
            startFromSettings();
        } else {
            startForeground(NOTIF_ID, buildNotification("Connecting...", "Starting Tunnel"));
            startTunnel();
        }
        return START_STICKY;
    }

    private void startFromSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        providerIndex = prefs.getInt("tunnel_provider_index", 0);
        localPort = prefs.getInt("default_port", 8080);
        startForeground(NOTIF_ID, buildNotification("Connecting...", "Starting Tunnel"));
        startTunnel();
    }

    private void startTunnel() {
        if (tunnelActive) return;
        if (providerIndex < 0 || providerIndex >= TunnelManager.SSH_PROVIDERS.length) providerIndex = 0;
        String providerName = TunnelManager.SSH_PROVIDERS[providerIndex][0];
        Log.i(TAG, "Starting tunnel via " + providerName + " on port " + localPort);

        tunnelMgr.startSshTunnel(providerIndex, localPort, new TunnelManager.TunnelCallback() {
            @Override public void onMessage(String msg) {
                Log.i(TAG, "[tunnel] " + msg);
                if (msg.contains("URL:") || msg.contains("established") || msg.contains("connected")) {
                    updateNotification(msg, "Tunnel Active");
                }
            }
            @Override public void onError(String err) {
                Log.e(TAG, "[tunnel error] " + err);
                updateNotification("Error: " + truncate(err, 80), "Tunnel Error");
            }
            @Override public void onConnected(String url) {
                currentPublicUrl = url;
                tunnelActive = true;
                updateNotification("Public URL: " + url, "Tunnel Active");
                Intent bcast = new Intent("com.bantu.droid.TUNNEL_CONNECTED");
                bcast.putExtra("url", url);
                sendBroadcast(bcast);
            }
            @Override public void onDisconnected() {
                tunnelActive = false;
                currentPublicUrl = null;
                updateNotification("Tunnel disconnected", "Disconnected");
                sendBroadcast(new Intent("com.bantu.droid.TUNNEL_DISCONNECTED"));
            }
        });
    }

    private void stopTunnel() {
        tunnelMgr.stopSshTunnel();
        tunnelActive = false;
        currentPublicUrl = null;
        instance = null;
    }

    private void acquireWakeLock() {
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BantuDroid::TunnelWakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(12 * 60 * 60 * 1000L);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bantu Tunnel", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps tunnels alive in background");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText, String title) {
        Intent openIntent = new Intent(this, TunnelActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, TunnelService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title != null ? title : "Bantu Tunnel")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Tunnel", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE);
        if (currentPublicUrl != null) {
            b.setStyle(new NotificationCompat.BigTextStyle()
                .bigText("Public URL: " + currentPublicUrl + "\nTap to open. Tunnel is active in background."));
        }
        return b.build();
    }

    private void updateNotification(String contentText, String title) {
        notificationManager.notify(NOTIF_ID, buildNotification(contentText, title));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public static boolean isRunning() { return instance != null && instance.tunnelActive; }
    public static String getCurrentPublicUrl() { return instance != null ? instance.currentPublicUrl : null; }

    @Override public void onDestroy() {
        stopTunnel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    public static void start(Context ctx, int providerIndex, int localPort) {
        Intent intent = new Intent(ctx, TunnelService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_PROVIDER_INDEX, providerIndex);
        intent.putExtra(EXTRA_LOCAL_PORT, localPort);
        androidx.core.content.ContextCompat.startForegroundService(ctx, intent);
    }
    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, TunnelService.class);
        intent.setAction(ACTION_STOP);
        androidx.core.content.ContextCompat.startForegroundService(ctx, intent);
    }
}
