package com.bantu.droid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the Bantu server running even when
 * the app is minimized or the screen is off.
 *
 * Features:
 * - WakeLock to prevent CPU sleep during server operation
 * - Foreground notification showing server status and IP
 * - Auto-restart on unexpected crashes (START_STICKY)
 * - Clean shutdown on user stop command
 *
 * Usage:
 *   Intent intent = new Intent(context, ServerService.class);
 *   intent.putExtra("file", "bantuddns.b");
 *   ContextCompat.startForegroundService(context, intent);
 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "bantu_server_channel";

    public static final String ACTION_START = "com.bantu.droid.ACTION_START";
    public static final String ACTION_STOP = "com.bantu.droid.ACTION_STOP";
    public static final String EXTRA_FILE = "file";

    private BantuEngine engine;
    private BantuProcess serverProcess;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private String currentFile;
    private String lastStatus = "Starting...";

    // ──────────────────────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new BantuEngine(this);
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            return START_NOT_STICKY;
        }

        String file = "bantuddns.b";
        if (intent != null && intent.hasExtra(EXTRA_FILE)) {
            file = intent.getStringExtra(EXTRA_FILE);
        }
        currentFile = file;

        // Start as foreground service immediately (required on Android 12+)
        Notification notif = buildNotification(
            "Starting " + currentFile + "...",
            "Initializing"
        );
        startForeground(NOTIF_ID, notif);

        // Start the Bantu server
        startServer(file);

        // Restart if killed by the system
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // Server management
    // ──────────────────────────────────────────────────────────────

    private void startServer(String file) {
        if (serverProcess != null && serverProcess.isRunning()) {
            Log.w(TAG, "Server already running");
            return;
        }

        if (!engine.isInstalled()) {
            updateNotification("Engine not installed", "Error");
            stopSelf();
            return;
        }

        try {
            serverProcess = engine.run(file);
            serverProcess.readOutput(new BantuProcess.OutputListener() {
                @Override
                public void onOutput(String line) {
                    Log.d(TAG, "[server] " + line);
                    lastStatus = line;

                    // Parse important status lines
                    if (line.contains("listening") || line.contains("Listening")) {
                        updateNotification(line, "Server Running");
                    } else if (line.contains("IP changed") || line.contains("ip changed")) {
                        updateNotification(line, "IP Updated");
                    } else if (line.contains("Error") || line.contains("error")) {
                        updateNotification(line, "Warning");
                    } else {
                        updateNotification(lastStatus, "Server Running");
                    }
                }

                @Override
                public void onError(String line) {
                    Log.e(TAG, "[server:error] " + line);
                    updateNotification("Error: " + line, "Error");
                }

                @Override
                public void onExit(int exitCode) {
                    Log.i(TAG, "Server exited with code " + exitCode);
                    if (exitCode != 0) {
                        updateNotification(
                            "Server crashed (code " + exitCode + "). Restarting...",
                            "Crashed"
                        );
                        // Auto-restart after 5 seconds
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {}
                        startServer(file);
                    } else {
                        updateNotification("Server stopped", "Stopped");
                        stopForeground(true);
                        stopSelf();
                    }
                }
            });

            Log.i(TAG, "Server started: " + file);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start server", e);
            updateNotification("Failed: " + e.getMessage(), "Error");
            stopSelf();
        }
    }

    private void stopServer() {
        if (serverProcess != null && serverProcess.isRunning()) {
            Log.i(TAG, "Stopping server...");
            serverProcess.stop();
            serverProcess = null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // WakeLock — keep CPU alive while server runs
    // ──────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BantuDroid::ServerWakeLock"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(12 * 60 * 60 * 1000L); // Max 12 hours, renewed
        Log.i(TAG, "WakeLock acquired");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Notifications
    // ──────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText, String title) {
        // Tap to open TerminalActivity
        Intent openIntent = new Intent(this, TerminalActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Stop button action
        Intent stopIntent = new Intent(this, ServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title != null ? title : "Bantu Server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Server",
                stopPi
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void updateNotification(String contentText, String title) {
        Notification notif = buildNotification(contentText, title);
        notificationManager.notify(NOTIF_ID, notif);
    }

    // ──────────────────────────────────────────────────────────────
    // Public API for checking service state
    // ──────────────────────────────────────────────────────────────

    public boolean isServerRunning() {
        return serverProcess != null && serverProcess.isRunning();
    }

    public String getServerStatus() {
        return lastStatus;
    }

    public String getCurrentFile() {
        return currentFile;
    }

    public String getUptime() {
        if (serverProcess != null) {
            return serverProcess.getUptimeFormatted();
        }
        return "0s";
    }
}
