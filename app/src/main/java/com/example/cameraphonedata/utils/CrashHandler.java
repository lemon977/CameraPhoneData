package com.example.cameraphonedata.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;

import com.example.cameraphonedata.domain.manager.RecordingManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获处理器 —— 数据安全最后一道防线。
 *
 * 【职责】
 * 1. 捕获所有未处理异常（主线程 + 后台线程）。
 * 2. 如果正在录制，立即触发优雅停止（尽可能保存已录数据）。
 * 3. 将崩溃日志写入文件，方便后续排查。
 * 4. 最后交给系统默认处理器，避免 App 卡死。
 *
 * 【为什么重要】
 * 录制 3-4 小时过程中，任何模块（CameraX/MLKit/OSS/传感器）崩溃都会导致整段数据丢失。
 * CrashHandler 能在崩溃瞬间尝试 finalize 已录制的段，把损失降到最低。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private static final String CRASH_LOG_DIR = "crash_logs";

    private final Application application;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    // 全局弱引用，供 RecordingManager 注册自己，崩溃时紧急停止
    private static java.lang.ref.WeakReference<com.example.cameraphonedata.domain.manager.RecordingManager> recordingManagerRef;

    private CrashHandler(Application app) {
        this.application = app;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Application app) {
        CrashHandler handler = new CrashHandler(app);
        Thread.setDefaultUncaughtExceptionHandler(handler);
        LogUtil.i(TAG, "CrashHandler 已安装");
    }

    /**
     * RecordingManager 在创建时调用此方法注册自己，崩溃时会尝试紧急停止录制。
     */
    public static void registerRecordingManager(com.example.cameraphonedata.domain.manager.RecordingManager manager) {
        recordingManagerRef = new java.lang.ref.WeakReference<>(manager);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        long crashTime = System.currentTimeMillis();
        String crashInfo = buildCrashInfo(thread, throwable, crashTime);

        // 1. 写崩溃日志到文件
        try {
            saveCrashLog(crashInfo, crashTime);
        } catch (Exception e) {
            LogUtil.e(TAG, "保存崩溃日志失败", e);
        }

        // 2. 如果正在录制，尝试优雅停止（尽最大努力保存数据）
        try {
            RecordingManager recordingManager = findRecordingManager();
            if (recordingManager != null && recordingManager.isRecording()) {
                LogUtil.e(TAG, "崩溃时正在录制，尝试紧急停止...");
                recordingManager.stop();
                // 给 stop 流程 2 秒时间落盘
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "紧急停止录制失败", e);
        }

        // 3. 打印到控制台（供 logcat 抓取）
        LogUtil.e(TAG, "===== APP CRASH =====\n" + crashInfo);

        // 4. 交给系统默认处理器，让 App 正常退出（不卡死）
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        } else {
            Process.killProcess(Process.myPid());
            System.exit(1);
        }
    }

    private String buildCrashInfo(Thread thread, Throwable throwable, long crashTime) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(crashTime)));
        pw.println("Thread: " + thread.getName() + " (id=" + thread.getId() + ")");
        pw.println("Process: " + Process.myPid());
        pw.println("--- Stack Trace ---");
        throwable.printStackTrace(pw);
        pw.println("-------------------");

        return sw.toString();
    }

    private void saveCrashLog(String content, long crashTime) throws Exception {
        File dir = new File(application.getExternalFilesDir(null), CRASH_LOG_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(application.getFilesDir(), CRASH_LOG_DIR);
            dir.mkdirs();
        }
        String fileName = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date(crashTime)) + ".txt";
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    private RecordingManager findRecordingManager() {
        if (recordingManagerRef != null) {
            return recordingManagerRef.get();
        }
        return null;
    }
}
