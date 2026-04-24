package com.example.cameraphonedata.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.cameraphonedata.MainActivity;
import com.example.cameraphonedata.R;
import com.example.cameraphonedata.domain.manager.UploadManager;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;
import com.example.cameraphonedata.data.upload.UploadState;

import java.io.File;

/**
 * 上传前台服务 - 逐个文件直传版
 *
 * 【工程说明】
 * 1. 不再打包 zip，直接遍历文件夹逐个上传 MP4/JSON/CSV
 * 2. 通知栏显示：第 N/M 个文件，文件名，总体进度
 * 3. 支持锁屏/后台/切换应用，前台服务保活
 */
public class UploadForegroundService extends Service {
    private static final String TAG = "UploadService";
    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_UPLOAD = "UPLOAD_FOLDER";
    public static final String EXTRA_FOLDER_PATH = "folder_path";

    private UploadManager uploadManager;
    private NotificationManager notificationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        uploadManager = new UploadManager(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_UPLOAD.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH);
        if (folderPath == null || folderPath.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            updateFinalNotification("上传失败", "文件夹不存在", false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isRunning) {
            LogUtil.w(TAG, "已有上传任务进行中，忽略新请求");
            return START_NOT_STICKY;
        }

        isRunning = true;
        startForeground(NOTIFICATION_ID, buildProgressNotification("准备上传", "正在扫描文件…", 0, true));

        new Thread(() -> doUpload(folder)).start();

        return START_NOT_STICKY;
    }

    private void doUpload(File folder) {
        UploadState state = UploadState.getInstance();
        state.reset();
        state.isUploading = true;

        try {
            uploadManager.uploadDateFolder(folder, new UploadManager.UploadProgressListener() {
                @Override
                public void onProgress(int currentFile, int totalFiles, String currentFileName,
                                       long uploadedBytes, long totalBytes) {
                    state.currentFile = currentFile;
                    state.totalFiles = totalFiles;
                    state.currentFileName = currentFileName;
                    state.uploadedBytes = uploadedBytes;
                    state.totalBytes = totalBytes;
                    mainHandler.post(() -> {
                        String content = String.format("第 %d/%d 个: %s\n总体 %d%%",
                                currentFile, totalFiles, currentFileName,
                                totalBytes > 0 ? (int)(uploadedBytes * 100 / totalBytes) : 0);
                        updateProgressNotification("正在上传", content,
                                totalBytes > 0 ? (int)(uploadedBytes * 100 / totalBytes) : 0);
                    });
                }

                @Override
                public void onSuccess(String remoteUrl) {
                    state.isUploading = false;
                    state.isSuccess = true;
                    state.remoteUrl = remoteUrl;
                    mainHandler.post(() -> {
                        updateFinalNotification("✅ 上传完成",
                                "所有文件已上传至网盘\n" + remoteUrl + "\n点击返回应用", true);
                        stopForeground(false);
                        stopSelf();
                    });
                }

                @Override
                public void onFailure(String error) {
                    state.isUploading = false;
                    state.isFailure = true;
                    state.errorMsg = error;
                    mainHandler.post(() -> {
                        updateFinalNotification("❌ 上传失败", error + "，点击返回应用查看", false);
                        stopForeground(false);
                        stopSelf();
                    });
                }
            });
        } catch (Exception e) {
            LogUtil.e(TAG, "上传服务异常", e);
            state.isUploading = false;
            state.isFailure = true;
            state.errorMsg = e.getMessage();
            mainHandler.post(() -> {
                updateFinalNotification("❌ 上传异常", e.getMessage(), false);
                stopForeground(false);
                stopSelf();
            });
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "数据上传服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持后台上传机器人采集数据，支持锁屏续传");
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildProgressNotification(String title, String content, int progress, boolean indeterminate) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, progress, indeterminate)
                .build();
    }

    private void updateProgressNotification(String title, String content, int progress) {
        Notification notification = buildProgressNotification(title, content, progress, false);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void updateFinalNotification(String title, String content, boolean autoCancel) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(autoCancel)
                .setSilent(true);

        if (!autoCancel) builder.setOngoing(true);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
}