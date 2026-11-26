package com.tikkamasalla.geminienhanced;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

// Keeps the process (and its WebView) alive while a Gemini reply is pending
// or streaming, including when the app is swiped away or the screen is off.
// It is armed the moment a message is sent (send button or Enter seen by
// the page), not only once the page shows its stop button, so closing the
// app right after sending keeps the turn alive. Nothing runs (no service,
// no wake lock, no timer) while no reply is pending.
public class ReplyKeeperService extends Service {
    private static final String TAG = "GeminiEnhanced";

    private static final String ACTION_ARM = "com.tikkamasalla.geminienhanced.ACTION_ARM";
    private static final String ACTION_BUSY = "com.tikkamasalla.geminienhanced.ACTION_BUSY";
    private static final String ACTION_IDLE = "com.tikkamasalla.geminienhanced.ACTION_IDLE";

    private static final int NOTIF_ONGOING_ID = 1001;
    private static final int NOTIF_REPLIED_ID = 1002;
    private static final String CHANNEL_KEEP = "reply_keeper_keep";
    private static final String CHANNEL_DONE = "reply_keeper_done";

    // Safety cap: release everything after 20 minutes busy.
    private static final long BUSY_CAP_MS = 20 * 60 * 1000L;
    // A send whose reply never shows the stop button is released after this.
    private static final long ARM_WAIT_MS = 45 * 1000L;

    // The page shows its stop button.
    private static volatile boolean busy = false;
    // Uptime of the last send; 0 = no send waiting.
    private static volatile long armedAt = 0;
    // The stop button was seen since the last send (so its end is a reply).
    private static volatile boolean sawBusy = false;

    // A reply is streaming, or a send is still waiting for it to start: the
    // WebView must keep rendering and the process must stay alive.
    public static boolean needsKeep() {
        long t = armedAt;
        return busy || (t != 0 && SystemClock.uptimeMillis() - t < ARM_WAIT_MS);
    }

    // A message was just sent (normally still in the foreground, where a
    // foreground-service start is always allowed).
    public static void arm(Context context) {
        armedAt = SystemClock.uptimeMillis();
        sawBusy = busy;
        start(context, ACTION_ARM);
    }

    public static void startBusy(Context context) {
        busy = true;
        sawBusy = true;
        start(context, ACTION_BUSY);
    }

    // The service is already running, so a plain startService only
    // redelivers the intent.
    public static void setIdle(Context context) {
        busy = false;
        Context app = context.getApplicationContext();
        try {
            app.startService(new Intent(app, ReplyKeeperService.class).setAction(ACTION_IDLE));
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "setIdle: service not reachable from background", e);
        }
    }

    // The page is gone (renderer died): nothing left to keep or announce.
    public static void cancel(Context context) {
        armedAt = 0;
        sawBusy = false;
        setIdle(context);
    }

    private static void start(Context context, String action) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, ReplyKeeperService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, action + ": foreground start refused; reply not kept alive", e);
        }
    }

    private PowerManager.WakeLock wakeLock = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable capRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "busy cap reached, releasing");
            busy = false;
            armedAt = 0;
            finish(false);
        }
    };
    // Re-checks when the send wait runs out (no stop button ever seen).
    private final Runnable armRunnable = new Runnable() {
        @Override
        public void run() {
            if (!needsKeep()) {
                Log.d(TAG, "send wait over, busy seen=" + sawBusy);
                armedAt = 0;
                finish(sawBusy);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_ARM.equals(action) || ACTION_BUSY.equals(action)) {
            // Every startForegroundService must reach startForeground.
            keep(ACTION_ARM.equals(action));
            if (!busy) {
                onIdle();
            }
        } else if (ACTION_IDLE.equals(action)) {
            onIdle();
        } else {
            stopSelf();
        }
        // A restart after a process kill has no page to keep: never sticky.
        return START_NOT_STICKY;
    }

    private void keep(boolean armed) {
        acquireWakeLock();
        handler.removeCallbacks(capRunnable);
        handler.postDelayed(capRunnable, BUSY_CAP_MS);
        if (armed) {
            handler.removeCallbacks(armRunnable);
            handler.postDelayed(armRunnable, ARM_WAIT_MS + 100);
        }
        Notification notification = buildOngoingNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ONGOING_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ONGOING_ID, notification);
        }
        Log.d(TAG, "foreground keep started (" + (armed ? "send" : "busy") + ")");
    }

    // Busy is false: finish unless a send is still waiting for its reply.
    private void onIdle() {
        if (busy) {
            return;
        }
        if (!sawBusy && needsKeep()) {
            return;
        }
        armedAt = 0;
        finish(sawBusy);
    }

    private void finish(boolean replied) {
        handler.removeCallbacks(capRunnable);
        handler.removeCallbacks(armRunnable);
        sawBusy = false;
        // The page no longer needs to render: drop any off-screen hosting.
        ChatHolder.releaseToApp(this);
        releaseWakeLock();
        if (replied && !MainActivity.isForeground()) {
            notifyReplied();
        }
        stopForeground(true);
        stopSelf();
        Log.d(TAG, "foreground keep stopped (replied=" + replied + ")");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from Recents: keep running while a reply is pending,
        // otherwise there is nothing to keep alive.
        if (needsKeep()) {
            Log.d(TAG, "task removed while a reply is pending, keeping service");
        } else {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(capRunnable);
        handler.removeCallbacks(armRunnable);
        releaseWakeLock();
        super.onDestroy();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeminiEnhanced:Reply");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "wake lock release failed", e);
            }
        }
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, open, flags);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel keep = new NotificationChannel(CHANNEL_KEEP,
                getString(R.string.replying_title), NotificationManager.IMPORTANCE_LOW);
        NotificationChannel done = new NotificationChannel(CHANNEL_DONE,
                getString(R.string.replied_title), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(keep);
        nm.createNotificationChannel(done);
    }

    private Notification buildOngoingNotification() {
        createChannels();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_KEEP);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.setContentTitle(getString(R.string.replying_title))
                .setContentText(getString(R.string.replying_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .build();
    }

    private void notifyReplied() {
        createChannels();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_DONE);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        Notification notification = b.setContentTitle(getString(R.string.replied_title))
                .setContentText(getString(R.string.replied_text))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_REPLIED_ID, notification);
        }
    }
}
