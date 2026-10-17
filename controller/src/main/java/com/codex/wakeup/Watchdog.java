package com.codex.wakeup;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Best-effort stale-state guard. A reboot starts the monitor; it never clears
 * the icon merely because BOOT_COMPLETED was delivered.
 */
public final class Watchdog extends BroadcastReceiver {
    static final String ACTION_CLEAR_ICON = "com.codex.wakeup.CLEAR_ICON";
    private static final String PREFS = "monitor_runtime";
    private static final String HEARTBEAT = "heartbeat_elapsed";
    private static final int REQUEST_CODE = 90417;
    private static final long ALARM_DELAY_MS = 90_000L;
    private static final long STALE_AFTER_MS = 70_000L;

    static void touchAndSchedule(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(HEARTBEAT, SystemClock.elapsedRealtime())
                .apply();
        schedule(context);
    }

    static void clearHeartbeat(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(HEARTBEAT)
                .apply();
    }

    static void schedule(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_DELAY_MS,
                    pendingIntent(context));
        }
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.cancel(pendingIntent(context));
        }
        clearHeartbeat(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Do not set grey here. MonitorService will query /v1/status and
            // choose the icon from the first successful response.
            if (SecureStore.hasToken(context)) {
                Intent start = new Intent(context, MonitorService.class)
                        .setAction(MonitorService.ACTION_START);
                MonitorService.start(context, start);
            }
            return;
        }
        if (!ACTION_CLEAR_ICON.equals(action)) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(HEARTBEAT, 0L);
        if (last > 0L && last <= now && now - last > STALE_AFTER_MS) {
            IconState.set(context, IconState.State.GREY);
            clearHeartbeat(context);
        } else {
            // The monitor is still alive, or this alarm belonged to a prior
            // boot whose elapsed-realtime clock was reset. Keep the guard alive.
            schedule(context);
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, Watchdog.class).setAction(ACTION_CLEAR_ICON);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
