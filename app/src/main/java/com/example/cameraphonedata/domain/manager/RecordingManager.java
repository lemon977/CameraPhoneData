package com.example.cameraphonedata.domain.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.data.repository.FileRepository;
import com.example.cameraphonedata.recorder.VideoRecorder;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 录制管理器 —— 录制状态机与元数据管理。
 */
public class RecordingManager {
    private static final String TAG = "RecordingManager";

    public enum State { IDLE, PREPARING, RECORDING, SPLITTING, STOPPING, COMPLETED, ERROR }

    public interface Listener {
        void onStateChanged(State state);
        void onProgress(long totalMillis, String timeText, int segmentIndex, int totalSegments);
        void onSegmentEnded(int segmentIndex, String segmentPath);
        void onCompleted(boolean success, String error, String sessionPath);
        void onStorageWarning(long remainingMB);
    }

    private final VideoRecorder videoRecorder;
    private final FileRepository fileRepository;
    private final StorageManager storageManager;
    private final PowerManager.WakeLock wakeLock;
    private final Handler mainHandler;
    private final ExecutorService ioExecutor;
    private final Handler progressHandler;

    private State state = State.IDLE;
    private Listener listener;
    private File currentSessionFolder;
    private long recordStartTime;
    private long totalRecordedMillis;
    private int currentSegmentIndex;
    private long lastSegmentStartTime;
    private long lastStorageCheckMinute = -1;
    private Runnable progressRunnable;

    public RecordingManager(Context context, VideoRecorder videoRecorder) {
        this.videoRecorder = videoRecorder;
        this.fileRepository = new FileRepository(context);
        this.storageManager = new StorageManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.progressHandler = new Handler(Looper.getMainLooper());
        this.ioExecutor = Executors.newSingleThreadExecutor();

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraApp::Recording");
        this.wakeLock.setReferenceCounted(false);

        setupVideoRecorderCallbacks();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start(CameraParamReader.CameraParams params) {
        if (state != State.IDLE && state != State.ERROR && state != State.COMPLETED) {
            LogUtil.w(TAG, "无法开始录制，当前状态: " + state);
            return;
        }
        transitionTo(State.PREPARING);

        File baseDir = storageManager.getBaseDir(DataConfig.getInstance().baseFolderName);
        StorageManager.StorageCheckResult storage = storageManager.checkStorage(baseDir);
        if (!storage.isEnough) {
            transitionTo(State.ERROR);
            notifyCompleted(false, "存储空间不足: " + storage.availableMB + "MB", null);
            return;
        }

        currentSessionFolder = fileRepository.createSessionFolder(DataConfig.getInstance().currentTaskName);
        if (currentSessionFolder == null) {
            transitionTo(State.ERROR);
            notifyCompleted(false, "无法创建保存目录", null);
            return;
        }

        videoRecorder.setSessionFolder(currentSessionFolder);
        boolean started = videoRecorder.startRecording(params);
        if (!started) {
            transitionTo(State.ERROR);
            notifyCompleted(false, "录制启动失败", null);
            return;
        }

        recordStartTime = System.currentTimeMillis();
        totalRecordedMillis = 0;
        currentSegmentIndex = 0;
        lastSegmentStartTime = recordStartTime;
        lastStorageCheckMinute = -1;

        ioExecutor.execute(() -> fileRepository.initMetadata(currentSessionFolder, params, recordStartTime));

        transitionTo(State.RECORDING);

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        }

        startProgressLoop();
    }

    public void stop() {
        if (state != State.RECORDING && state != State.SPLITTING) {
            LogUtil.w(TAG, "无法停止，当前状态: " + state);
            return;
        }
        transitionTo(State.STOPPING);
        progressHandler.removeCallbacks(progressRunnable);
        videoRecorder.stopRecording();
    }

    public boolean isRecording() {
        return state == State.RECORDING || state == State.SPLITTING || state == State.PREPARING;
    }

    public State getState() { return state; }
    public File getCurrentSessionFolder() { return currentSessionFolder; }

    public int getCurrentSegmentIndex() { return currentSegmentIndex; }

    /** 总段数 = 当前段索引 + 1（因为索引从 0 开始）。 */
    public int getTotalSegmentCount() {
        return currentSegmentIndex + 1;
    }

    private void setupVideoRecorderCallbacks() {
        videoRecorder.setOnRecordListener(new VideoRecorder.OnRecordListener() {
            @Override public void onRecordStart(String sessionPath) {}

            @Override public void onRecordStop(boolean success, String errorMsg, String sessionPath) {
                if (state == State.STOPPING || state == State.RECORDING) {
                    handleRecordingEnd(success, errorMsg);
                }
            }

            @Override public void onStorageWarning(long remainingMB) {
                notifyStorageWarning(remainingMB);
            }

            /**
             * 新段开始回调。segmentIndex 为新段的 1-based 序号。
             *
             * 【关键修复】
             * - segmentIndex == 1：第一段刚开始，没有"前一段已完成"，只记录开始时间，不弹 Toast。
             * - segmentIndex >= 2：第二段及以后开始，意味着前一段（segmentIndex-1）已保存完毕。
             */
            @Override public void onSegmentStart(int segmentIndex, String segmentPath) {
                long now = System.currentTimeMillis();

                if (segmentIndex > 1) {
                    // 前一段已完成，保存元数据并通知 UI
                    int completedNumber = segmentIndex - 1; // 前一段 1-based 序号（1, 2, 3...）
                    long segmentDuration = now - lastSegmentStartTime;

                    String baseTs = videoRecorder.getBaseTimestamp();
                    String fileName = String.format(Locale.CHINA, "video_%s_%03d.mp4", baseTs, completedNumber);
                    File videoFile = new File(currentSessionFolder, fileName);

                    ioExecutor.execute(() -> fileRepository.addSegment(
                            currentSessionFolder, completedNumber,
                            videoFile, lastSegmentStartTime, segmentDuration));

                    currentSegmentIndex = completedNumber;
                    totalRecordedMillis += segmentDuration;
                    lastSegmentStartTime = now;

                    // 通知 UI：第 completedNumber 个数据集已保存（1-based）
                    notifySegmentEnded(completedNumber, videoFile.getAbsolutePath());
                } else {
                    // 第一段开始，仅记录开始时间，不弹"第0个"提示
                    lastSegmentStartTime = now;
                }
            }
        });
    }

    private void handleRecordingEnd(boolean success, String errorMsg) {
        progressHandler.removeCallbacks(progressRunnable);
        long stopTime = System.currentTimeMillis();
        long lastSegmentDuration = stopTime - lastSegmentStartTime;

        if (success && currentSessionFolder != null) {
            int lastSegmentNumber = currentSegmentIndex + 1; // 1-based 最后一段
            String baseTs = videoRecorder.getBaseTimestamp();
            String fileName = String.format(Locale.CHINA, "video_%s_%03d.mp4", baseTs, lastSegmentNumber);
            File lastFile = new File(currentSessionFolder, fileName);

            final long finalTotalDuration = totalRecordedMillis + lastSegmentDuration;
            ioExecutor.execute(() -> {
                fileRepository.addSegment(currentSessionFolder, lastSegmentNumber,
                        lastFile, lastSegmentStartTime, lastSegmentDuration);
                fileRepository.finalizeMetadata(currentSessionFolder, stopTime, finalTotalDuration);
            });
        }

        if (wakeLock.isHeld()) wakeLock.release();
        transitionTo(success ? State.COMPLETED : State.ERROR);
        notifyCompleted(success, errorMsg,
                currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
    }

    private void startProgressLoop() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (state != State.RECORDING && state != State.SPLITTING) return;
                long currentSegmentElapsed = System.currentTimeMillis() - lastSegmentStartTime;
                long totalElapsed = totalRecordedMillis + currentSegmentElapsed;
                notifyProgress(totalElapsed, formatTime(totalElapsed), currentSegmentIndex, currentSegmentIndex + 1);

                long currentMinute = totalElapsed / 60000;
                if (currentMinute >= 5 && currentMinute % 5 == 0 && currentMinute != lastStorageCheckMinute) {
                    lastStorageCheckMinute = currentMinute;
                    checkStorageAsync();
                }
                progressHandler.postDelayed(this, 1000);
            }
        };
        progressHandler.postDelayed(progressRunnable, 1000);
    }

    private void checkStorageAsync() {
        ioExecutor.execute(() -> {
            File baseDir = storageManager.getBaseDir(DataConfig.getInstance().baseFolderName);
            StorageManager.StorageCheckResult result = storageManager.checkStorage(baseDir);
            if (!result.isEnough) {
                mainHandler.post(() -> notifyStorageWarning(result.availableMB));
            }
        });
    }

    private void transitionTo(State newState) {
        this.state = newState;
        if (listener != null) mainHandler.post(() -> listener.onStateChanged(state));
    }

    private void notifyProgress(long totalMillis, String timeText, int segIdx, int totalSeg) {
        if (listener != null) mainHandler.post(() -> listener.onProgress(totalMillis, timeText, segIdx, totalSeg));
    }

    private void notifySegmentEnded(int idx, String path) {
        if (listener != null) mainHandler.post(() -> listener.onSegmentEnded(idx, path));
    }

    private void notifyCompleted(boolean success, String error, String path) {
        if (listener != null) mainHandler.post(() -> listener.onCompleted(success, error, path));
    }

    private void notifyStorageWarning(long mb) {
        if (listener != null) mainHandler.post(() -> listener.onStorageWarning(mb));
    }

    private String formatTime(long millis) {
        long s = millis / 1000, m = s / 60, h = m / 60;
        s = s % 60; m = m % 60;
        return h > 0 ? String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, s)
                : String.format(Locale.CHINA, "%02d:%02d", m, s);
    }

    public void release() {
        if (isRecording()) stop();
        progressHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
        if (wakeLock.isHeld()) wakeLock.release();
    }
}