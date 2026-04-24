package com.example.cameraphonedata.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.cameraphonedata.MainActivity;
import com.example.cameraphonedata.R;
import com.example.cameraphonedata.utils.LogUtil;

/**
 * 录制前台服务
 * 作用：确保App在熄屏、后台、切换应用时，录制不被系统杀死
 * <p>
 * 必须配合以下权限（已在 AndroidManifest.xml 中声明）：
 * - FOREGROUND_SERVICE
 * - FOREGROUND_SERVICE_CAMERA
 * - FOREGROUND_SERVICE_MICROPHONE
 * - WAKE_LOCK
 * - REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 */
public class RecordingForegroundService extends Service {
    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "recording_channel";
    private static final int NOTIFICATION_ID = 1001;

    private PowerManager.WakeLock wakeLock;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public RecordingForegroundService getService() {
            return RecordingForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtil.i(TAG, "前台服务创建");
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if ("STOP".equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification("正在录制机器人视觉数据...", "点击返回应用");
        startForeground(NOTIFICATION_ID, notification);
        LogUtil.i(TAG, "前台服务已启动，通知栏显示中");

        return START_STICKY; // 被杀后自动重启
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        LogUtil.i(TAG, "前台服务销毁");
    }

    /**
     * 更新通知栏文本（显示录制时长）
     */
    public void updateNotification(String content) {
        Notification notification = buildNotification(content, "点击返回应用");
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "录制服务",
                    NotificationManager.IMPORTANCE_LOW // LOW = 不弹出横幅，只显示在通知栏
            );
            channel.setDescription("保持应用在后台录制视频");
            channel.setSound(null, null); // 无声音
            channel.enableVibration(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 用你的图标，如果没有就换成 mipmap
                .setContentIntent(pendingIntent)
                .setOngoing(true) // 不可滑动删除
                .setSilent(true)  // 无提示音
                .build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "CameraApp::RecordingWakeLock"
                );
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 60 * 60 * 1000L); // 10小时
                LogUtil.i(TAG, "PARTIAL_WAKE_LOCK 已获取，CPU不休眠");
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "获取WakeLock失败", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                LogUtil.e(TAG, "释放WakeLock失败", e);
            }
        }
    }
}