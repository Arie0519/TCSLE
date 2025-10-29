package com.example.tcsle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * PDR測定用Foreground Service
 * バックグラウンドでも測定を継続するためのサービス
 */
public class PDRForegroundService extends Service {
    private static final String TAG = "PDRForegroundService";

    // 通知関連
    public static final String CHANNEL_ID = "PDRServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    // Binder
    private final IBinder binder = new LocalBinder();

    // WakeLock（画面OFF時も動作継続）
    private PowerManager.WakeLock wakeLock;

    /**
     * LocalBinder - Activityとの通信用
     */
    public class LocalBinder extends Binder {
        PDRForegroundService getService() {
            return PDRForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        // WakeLock取得（画面OFF時も動作継続）
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TCSLE::PDRWakeLock"
            );
            wakeLock.acquire(10 * 60 * 60 * 1000L); // 10時間（測定の最大想定時間）
            Log.d(TAG, "WakeLock acquired");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        // Foreground化（通知表示）
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        // サービスが強制終了されても再起動
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Service bound");
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");

        // WakeLock解放
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }

        // Foreground停止
        stopForeground(true);
    }

    /**
     * 通知の作成
     */
    private Notification createNotification() {
        // MainActivityを開くIntent
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        // 通知作成
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PDR測定中")
                .setContentText("位置情報を記録しています")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // スワイプで消せないようにする
                .setShowWhen(true);

        return builder.build();
    }

    /**
     * 通知の更新（測定状況を表示）
     */
    public void updateNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PDR測定中")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(true)
                .build();

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }
}