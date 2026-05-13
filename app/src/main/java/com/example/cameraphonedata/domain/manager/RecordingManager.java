package com.example.cameraphonedata.domain.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.example.cameraphonedata.alert.VoicePromptManager;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.utils.CrashHandler;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.config.ImuHealthConfig;
import com.example.cameraphonedata.config.TimeConstants;
import com.example.cameraphonedata.data.EffectiveDurationManager;
import com.example.cameraphonedata.data.repository.FileRepository;
import com.example.cameraphonedata.recorder.ImuRecorder;
import com.example.cameraphonedata.recorder.VideoRecorder;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 录制管理器 —— 录制状态机与元数据管理。
 * 【2026-04-29 修复】改回有效时长（毫秒），不足1秒截断，用于后端计算工资。
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
    private final EffectiveDurationManager effectiveDurationManager;
    private final ImuRecorder imuRecorder;
    private final PowerManager.WakeLock wakeLock;
    private final Handler mainHandler;
    private final ExecutorService ioExecutor;
    private final Handler progressHandler;
    private final Handler healthCheckHandler;

    private State state = State.IDLE;
    private Listener listener;
    private File currentSessionFolder;
    private long recordStartTime;
    private long totalRecordedMillis;
    private final AtomicInteger currentSegmentIndex = new AtomicInteger(0);
    private long lastSegmentStartTime;
    private long lastStorageCheckMinute = -1;
    private Runnable progressRunnable;
    private Runnable healthCheckRunnable;

    private volatile boolean isFatalNoHandStop = false;
    private long effectiveDurationMs = 0; // 【改回】有效时长（毫秒）
    private volatile String healthCheckFailureReason = null; // 健康检查失败原因，传给 UI

    public RecordingManager(Context context, VideoRecorder videoRecorder,
                            EffectiveDurationManager effectiveDurationManager) {
        this.videoRecorder = videoRecorder;
        this.fileRepository = new FileRepository(context);
        this.storageManager = new StorageManager(context);
        this.effectiveDurationManager = effectiveDurationManager;
        this.imuRecorder = new ImuRecorder(context);
        CrashHandler.registerRecordingManager(this);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.progressHandler = new Handler(Looper.getMainLooper());
        this.healthCheckHandler = new Handler(Looper.getMainLooper());
        this.ioExecutor = Executors.newSingleThreadExecutor();

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraApp::Recording");
        this.wakeLock.setReferenceCounted(false);

        setupVideoRecorderCallbacks();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 【新增】设置语音播报器，用于健康检查失败时提醒用户。
     */
    public void setVoicePromptManager(VoicePromptManager voicePromptManager) {
        this.voicePromptManager = voicePromptManager;
    }
    private VoicePromptManager voicePromptManager;

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

        // 【调整】先启动 IMU，再启动视频。CameraX 视频编码器初始化有约 200-300ms 延迟，
        // 先启动 IMU 可确保人手动作起始阶段的数据被完整记录（视频开始前的前导 IMU
        // 数据将在后端通过 video_start_ms 截断对齐）。
        imuRecorder.start(currentSessionFolder);

        boolean started = videoRecorder.startRecording(params);
        if (!started) {
            imuRecorder.stopAsync(); // 视频启动失败时回滚 IMU
            transitionTo(State.ERROR);
            notifyCompleted(false, "录制启动失败", null);
            return;
        }

        recordStartTime = System.currentTimeMillis();
        totalRecordedMillis = 0;
        currentSegmentIndex.set(0);
        lastSegmentStartTime = recordStartTime;
        lastStorageCheckMinute = -1;
        isFatalNoHandStop = false;
        effectiveDurationMs = 0;

        ioExecutor.execute(() -> fileRepository.initMetadata(currentSessionFolder, params, recordStartTime));

        transitionTo(State.RECORDING);

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(TimeConstants.WAKE_LOCK_TIMEOUT_MS);
        }

        startProgressLoop();
        startHealthCheckLoop();
    }

    public void stop() {
        if (state != State.RECORDING && state != State.SPLITTING) {
            LogUtil.w(TAG, "无法停止，当前状态: " + state);
            return;
        }
        transitionTo(State.STOPPING);
        progressHandler.removeCallbacks(progressRunnable);
        healthCheckHandler.removeCallbacks(healthCheckRunnable);
        imuRecorder.stopAsync();
        videoRecorder.stopRecording();
    }

    public void fatalNoHandStop() {
        if (state != State.RECORDING && state != State.SPLITTING) {
            LogUtil.w(TAG, "致命停止被忽略，当前状态: " + state);
            return;
        }
        LogUtil.i(TAG, "收到致命无手停止信号");
        isFatalNoHandStop = true;
        stop();
    }

    public boolean isRecording() {
        return state == State.RECORDING || state == State.SPLITTING || state == State.PREPARING;
    }

    @SuppressWarnings("unused")
    public State getState() { return state; }

    @SuppressWarnings("unused")
    public File getCurrentSessionFolder() { return currentSessionFolder; }

    @SuppressWarnings("unused")
    public int getCurrentSegmentIndex() { return currentSegmentIndex.get(); }

    /** 【改回】返回有效时长（毫秒） */
    public long getEffectiveDurationMs() {
        return effectiveDurationMs;
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

            @Override public void onSegmentStart(int segmentIndex, String segmentPath) {
                long now = System.currentTimeMillis();

                if (segmentIndex > 1) {
                    int completedNumber = segmentIndex - 1;
                    long segmentDuration = now - lastSegmentStartTime;

                    String baseTs = videoRecorder.getBaseTimestamp();
                    String fileName = String.format(Locale.CHINA, "video_%s_%03d.mp4", baseTs, completedNumber);
                    File videoFile = new File(currentSessionFolder, fileName);

                    ioExecutor.execute(() -> {
                        fileRepository.addSegment(
                                currentSessionFolder, completedNumber,
                                videoFile, lastSegmentStartTime, segmentDuration);
                        // 【关键】每完成一个段，强制 sync metadata 到磁盘
                        // 防止录制中途关机/崩溃时 metadata 丢失，后端无法解析
                        fileRepository.syncMetadata(currentSessionFolder);
                    });

                    currentSegmentIndex.set(completedNumber);
                    totalRecordedMillis += segmentDuration; // 【保留】累加已完成段时长
                    lastSegmentStartTime = now;

                    notifySegmentEnded(completedNumber, videoFile.getAbsolutePath());
                } else {
                    lastSegmentStartTime = now;
                }
            }
        });
    }

    private static boolean deleteDirectoryRecursively(File dir) {
        if (dir == null || !dir.exists()) return true;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectoryRecursively(f);
                } else {
                    if (!f.delete()) {
                        LogUtil.w(TAG, "无法删除文件: " + f.getAbsolutePath());
                    }
                }
            }
        }
        return dir.delete();
    }

    private void handleRecordingEnd(boolean success, String errorMsg) {
        progressHandler.removeCallbacks(progressRunnable);
        long stopTime = System.currentTimeMillis();
        long lastSegmentDuration = stopTime - lastSegmentStartTime;
        Future<?> metadataFuture = null;

        if (success && currentSessionFolder != null) {
            int lastSegmentNumber = currentSegmentIndex.get() + 1;
            String baseTs = videoRecorder.getBaseTimestamp();
            String fileName = String.format(Locale.CHINA, "video_%s_%03d.mp4", baseTs, lastSegmentNumber);
            File lastFile = new File(currentSessionFolder, fileName);

            if (isFatalNoHandStop) {
                // 最后一段无效，删除文件，不计入时长
                if (lastFile.exists()) {
                    if (!lastFile.delete()) {
                        LogUtil.w(TAG, "致命无手：删除无效视频失败");
                    } else {
                        LogUtil.i(TAG, "致命无手：已删除无效视频 " + fileName);
                    }
                }

                if (currentSegmentIndex.get() > 0) {
                    // 前面完成的段有效，总时长 = totalRecordedMillis（最后一段已删）
                    effectiveDurationMs = totalRecordedMillis;
                    final ImuRecorder.Stats imuStats = imuRecorder.getStats();
                    metadataFuture = ioExecutor.submit(() -> {
                        imuRecorder.awaitStop((int) TimeConstants.IMU_STOP_TIMEOUT_MS);
                        fileRepository.finalizeMetadata(currentSessionFolder, stopTime, effectiveDurationMs);
                        fileRepository.writeImuConfig(currentSessionFolder, imuStats);
                    });
                    LogUtil.i(TAG, "致命无手：保留有效时长 " + effectiveDurationMs + "ms");
                } else {
                    // 整个会话无有效数据
                    if (!deleteDirectoryRecursively(currentSessionFolder)) {
                        LogUtil.w(TAG, "致命无手：删除空会话目录失败");
                    }
                    LogUtil.i(TAG, "致命无手：整个会话无有效数据，已删除目录");
                    currentSessionFolder = null;
                    effectiveDurationMs = 0;
                }
            } else {
                // 正常停止：所有段有效，总时长 = 前面段之和 + 最后一段
                effectiveDurationMs = totalRecordedMillis + lastSegmentDuration;
                final ImuRecorder.Stats imuStats = imuRecorder.getStats();
                metadataFuture = ioExecutor.submit(() -> {
                    imuRecorder.awaitStop(5000);
                    fileRepository.addSegment(currentSessionFolder, lastSegmentNumber,
                            lastFile, lastSegmentStartTime, lastSegmentDuration);
                    fileRepository.finalizeMetadata(currentSessionFolder, stopTime, effectiveDurationMs);
                    fileRepository.writeImuConfig(currentSessionFolder, imuStats);
                });
            }
        }

        // 如果健康检查导致停止，把具体原因传给 UI
        String finalErrorMsg = errorMsg;
        if (healthCheckFailureReason != null) {
            finalErrorMsg = healthCheckFailureReason;
        }

        String finalPath = (currentSessionFolder != null) ? currentSessionFolder.getAbsolutePath() : null;
        finishAfterMetadataPersist(success, finalErrorMsg, finalPath, metadataFuture);
    }

    private void finishAfterMetadataPersist(boolean success, String errorMsg, String finalPath, Future<?> metadataFuture) {
        Runnable finishRunnable = () -> {
            if (wakeLock.isHeld()) wakeLock.release();
            isFatalNoHandStop = false;
            transitionTo(success ? State.COMPLETED : State.ERROR);
            notifyCompleted(success, errorMsg, finalPath);
        };

        // 清理健康检查失败原因（已完成传递）
        healthCheckFailureReason = null;

        if (metadataFuture == null) {
            finishRunnable.run();
            return;
        }

        Thread waiter = new Thread(() -> {
            try {
                metadataFuture.get(TimeConstants.METADATA_FINALIZE_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (Exception e) {
                LogUtil.e(TAG, "等待 metadata 落盘超时或失败，继续完成流程", e);
            }
            mainHandler.post(finishRunnable);
        }, "metadata-finalize-waiter");
        waiter.start();
    }

    private void startProgressLoop() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (state != State.RECORDING && state != State.SPLITTING) return;
                long currentSegmentElapsed = System.currentTimeMillis() - lastSegmentStartTime;
                long totalElapsed = totalRecordedMillis + currentSegmentElapsed;
                notifyProgress(totalElapsed, formatTime(totalElapsed), currentSegmentIndex.get(), currentSegmentIndex.get() + 1);

                // 【新增】录制时长上限保护（使用 CameraConfig.maxRecordMinutes）
                int maxMinutes = CameraConfig.getInstance().maxRecordMinutes;
                if (maxMinutes > 0 && totalElapsed >= maxMinutes * TimeConstants.MS_PER_MINUTE) {
                    LogUtil.i(TAG, "达到最大录制时长 " + maxMinutes + " 分钟，自动停止");
                    stop();
                    return;
                }

                long currentMinute = totalElapsed / TimeConstants.MS_PER_MINUTE;
                if (currentMinute >= 5 && currentMinute % 5 == 0 && currentMinute != lastStorageCheckMinute) {
                    lastStorageCheckMinute = currentMinute;
                    checkStorageAsync();
                }
                progressHandler.postDelayed(this, TimeConstants.PROGRESS_INTERVAL_MS);
            }
        };
        progressHandler.postDelayed(progressRunnable, TimeConstants.PROGRESS_INTERVAL_MS);
    }

    /**
     * 【新增】IMU 健康巡检：按 ImuHealthConfig.checkIntervalMs 间隔检查数据质量。
     * 如果 IMU 不健康，自动停止录制，并语音播报具体原因 + 弹窗提示。
     */
    private void startHealthCheckLoop() {
        healthCheckHandler.removeCallbacks(healthCheckRunnable);
        final long intervalMs = ImuHealthConfig.getInstance().checkIntervalMs;
        healthCheckRunnable = () -> {
            if (state != State.RECORDING && state != State.SPLITTING) return;
            ImuRecorder.HealthResult hr = imuRecorder.checkHealth();
            if (!hr.healthy) {
                LogUtil.e(TAG, "IMU 健康检查失败: " + hr.reason);
                healthCheckFailureReason = "录制已停止：" + hr.reason;

                // 语音播报
                if (ImuHealthConfig.getInstance().enableVoiceOnHealthFailure
                        && voicePromptManager != null) {
                    voicePromptManager.speak(healthCheckFailureReason,
                            ImuHealthConfig.getInstance().healthFailureVoiceResId);
                }

                // 主动停止录制，healthCheckFailureReason 会在 onCompleted 里传给 UI
                stop();
                return;
            }
            healthCheckHandler.postDelayed(healthCheckRunnable, intervalMs);
        };
        healthCheckHandler.postDelayed(healthCheckRunnable, intervalMs);
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
        LogUtil.d(TAG, "状态转换: " + this.state + " -> " + newState);
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
        healthCheckHandler.removeCallbacksAndMessages(null);
        progressHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LogUtil.w(TAG, "I/O 线程池关闭超时，强制中断");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogUtil.w(TAG, "I/O 线程池中断异常", e);
            ioExecutor.shutdownNow();
        }
        if (wakeLock.isHeld()) wakeLock.release();
    }
}