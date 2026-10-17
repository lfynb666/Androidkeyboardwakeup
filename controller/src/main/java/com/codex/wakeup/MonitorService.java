package com.codex.wakeup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the status lifecycle. Color is written only from successful status
 * responses; actions merely start a bounded confirmation window.
 */
public final class MonitorService extends Service {
    public static final String ACTION_START = "com.codex.wakeup.START_MONITOR";
    public static final String ACTION_TAP = "com.codex.wakeup.TAP";

    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long ACTION_TIMEOUT_MS = 45_000L;
    private static final long CONFIRMATION_WINDOW_MS = 4_000L;
    private static final int NOTIFICATION_ID = 90418;
    private static final String CHANNEL_ID = "windows_wake_monitor";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean monitoring;
    private volatile boolean stopRequested;
    private volatile boolean tapRequested;

    static void start(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_TAP.equals(action)) {
            tapRequested = true;
        }
        if (!monitoring) {
            monitoring = true;
            stopRequested = false;
            executor.execute(this::monitorLoop);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        monitoring = false;
        Watchdog.cancel(this);
        IconState.set(this, IconState.State.GREY);
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void monitorLoop() {
        String token = SecureStore.readToken(this);
        if (token == null || token.length() == 0) {
            monitoring = false;
            stopRequested = true;
            IconState.set(this, IconState.State.GREY);
            stopSelf();
            return;
        }

        // Until this boot/session has a fresh answer, grey means that the
        // monitor has no valid status yet. A successful first response replaces
        // it immediately with green or red.
        IconState.set(this, IconState.State.GREY);
        Boolean lastOnline = null;
        String requestedAction = null;
        long actionDeadline = 0L;
        long targetObservedAt = 0L;

        try {
            while (!stopRequested) {
                if (tapRequested && requestedAction == null) {
                    tapRequested = false;
                    if (lastOnline != null) {
                        requestedAction = lastOnline ? "sleep" : "wake";
                        actionDeadline = SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS;
                        targetObservedAt = 0L;
                        try {
                            ApiClient.action(token, requestedAction);
                        } catch (Exception ignored) {
                            // Keep polling. The color still comes only from
                            // status responses, and a later response may show
                            // that the command actually took effect.
                        }
                    }
                } else if (tapRequested) {
                    // Ignore extra taps while a command is being confirmed.
                    tapRequested = false;
                }

                try {
                    ApiClient.Status status = ApiClient.status(token);
                    lastOnline = status.online;
                    IconState.set(this, status.online
                            ? IconState.State.GREEN : IconState.State.RED);

                    if (requestedAction != null) {
                        boolean targetOnline = "wake".equals(requestedAction);
                        if (status.online == targetOnline) {
                            if (targetObservedAt == 0L) {
                                targetObservedAt = SystemClock.elapsedRealtime();
                            } else if (SystemClock.elapsedRealtime() - targetObservedAt
                                    >= CONFIRMATION_WINDOW_MS) {
                                // The endpoint has reported the target state
                                // for the whole confirmation window.
                                stopRequested = true;
                            }
                        } else {
                            targetObservedAt = 0L;
                        }
                    }
                } catch (Exception ignored) {
                    // A failed status query is the only runtime reason to show
                    // grey. Keep retrying while the monitor is alive.
                    IconState.set(this, IconState.State.GREY);
                }

                if (requestedAction != null
                        && SystemClock.elapsedRealtime() >= actionDeadline) {
                    stopRequested = true;
                }

                Watchdog.touchAndSchedule(this);
                if (!stopRequested) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        stopRequested = true;
                    }
                }
            }
        } finally {
            monitoring = false;
            Watchdog.cancel(this);
            IconState.set(this, IconState.State.GREY);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Windows 状态监测", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("保持 Windows 唤醒控制端点状态监测");
                channel.setSound(null, null);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startAsForeground() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Windows 状态监测")
                .setContentText("正在监测 ECS 端点")
                .setOngoing(true)
                .setDefaults(0)
                .setSound(null)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }
}
