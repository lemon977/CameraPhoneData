package com.example.cameraphonedata.recorder;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频录制器 —— 基于 CameraX VideoCapture。
 * 【采集注意】
 * - 分段录制默认 60s，防止单文件过大导致上传失败或内存溢出。
 * - 所有状态标志统一使用 AtomicBoolean，避免多线程竞争与类型混淆。
 * - 停止超时 8s 强制重置，防止状态机卡死。
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;

    // 所有状态标志统一为 AtomicBoolean，避免 primitive boolean 与 AtomicBoolean 混用
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isStopping = new AtomicBoolean(false);
    private final AtomicBoolean isFinalizing = new AtomicBoolean(false);
    private final AtomicBoolean isAutoSplitting = new AtomicBoolean(false);

    private final Context context;
    private final DataConfig dataConfig;
    private final CameraConfig cameraConfig;
    private final StorageManager storageManager;
    private final Handler mainHandler;
    private final Handler stopTimeoutHandler;
    private OnRecordListener listener;

    private final AtomicInteger currentSegmentIndex = new AtomicInteger(0);
    private String baseTimestamp;
    private Runnable segmentRunnable;
    private File currentSessionFolder;
    private File videoFile;

    public interface OnRecordListener {
        void onRecordStart(String sessionPath);
        void onRecordStop(boolean success, String errorMsg, String sessionPath);
        void onStorageWarning(long remainingMB);
        void onSegmentStart(int segmentIndex, String segmentPath);
    }

    public VideoRecorder(Context context) {
        this.context = context.getApplicationContext();
        this.dataConfig = DataConfig.getInstance();
        this.cameraConfig = CameraConfig.getInstance();
        this.storageManager = new StorageManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.stopTimeoutHandler = new Handler(Looper.getMainLooper());
    }

    public void setOnRecordListener(OnRecordListener listener) { this.listener = listener; }

    public void setupRecorder(Quality quality, boolean withAudio) {
        try {
            QualitySelector qs = QualitySelector.from(quality);
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(qs)
                    .setExecutor(ContextCompat.getMainExecutor(context))
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);
            LogUtil.i(TAG, "录制器初始化: " + quality + ", 音频=" + withAudio);
        } catch (Exception e) {
            LogUtil.e(TAG, "录制器初始化失败", e);
        }
    }

    public int getTargetWidth() {
        return cameraConfig.targetResolution.getWidth();
    }

    public int getTargetHeight() {
        return cameraConfig.targetResolution.getHeight();
    }

    public VideoCapture<Recorder> getVideoCapture() { return videoCapture; }

    public void setSessionFolder(File sessionFolder) {
        this.currentSessionFolder = sessionFolder;
    }

    public String getBaseTimestamp() {
        return baseTimestamp;
    }

    public boolean startRecording(@SuppressWarnings("unused") CameraParamReader.CameraParams params) {
        if (videoCapture == null || isRecording.get() || isFinalizing.get()) {
            LogUtil.w(TAG, "无法开始录制");
            return false;
        }
        if (!checkStorage()) {
            if (listener != null) listener.onRecordStop(false, "存储空间不足", null);
            return false;
        }

        this.currentSegmentIndex.set(0);
        this.isAutoSplitting.set(false);
        this.isStopping.set(false);
        this.isFinalizing.set(false);

        if (currentSessionFolder == null) {
            LogUtil.e(TAG, "会话目录未设置");
            return false;
        }

        baseTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        boolean started = startSegmentRecording();
        if (started) {
            if (listener != null) {
                listener.onRecordStart(currentSessionFolder.getAbsolutePath());
            }
        }
        return started;
    }

    private boolean startSegmentRecording() {
        String videoFileName = String.format(Locale.CHINA, "video_%s_%03d.mp4",
                baseTimestamp, currentSegmentIndex.get() + 1);
        videoFile = new File(currentSessionFolder, videoFileName);

        try {
            FileOutputOptions options = new FileOutputOptions.Builder(videoFile).build();
            PendingRecording pending = videoCapture.getOutput().prepareRecording(context, options);

            if (cameraConfig.recordAudio && hasAudioPermission()) {
                try { pending = pending.withAudioEnabled(); }
                catch (SecurityException e) { LogUtil.w(TAG, "音频权限异常，无声录制"); }
            }

            currentRecording = pending.start(
                    ContextCompat.getMainExecutor(context),
                    this::handleRecordEvent
            );

            isRecording.set(true);

            if (cameraConfig.enableSegmentRecording && cameraConfig.segmentDurationMs > 0) {
                startSegmentTimer();
            }
            LogUtil.i(TAG, "开始片段" + (currentSegmentIndex.get() + 1));
            return true;
        } catch (Exception e) {
            LogUtil.e(TAG, "启动片段失败", e);
            isRecording.set(false);
            return false;
        }
    }

    private void startSegmentTimer() {
        mainHandler.removeCallbacks(segmentRunnable);
        segmentRunnable = () -> {
            if (isRecording.get() && !isAutoSplitting.get() && !isStopping.get() && !isFinalizing.get()) {
                LogUtil.i(TAG, "到达分段时长，自动切割");
                isAutoSplitting.set(true);
                isFinalizing.set(true);
                mainHandler.removeCallbacks(segmentRunnable);
                if (currentRecording != null) currentRecording.stop();
            }
        };
        mainHandler.postDelayed(segmentRunnable, cameraConfig.segmentDurationMs);
    }

    public void stopRecording() {
        mainHandler.removeCallbacks(segmentRunnable);
        if (!isRecording.get() || isStopping.get()) {
            LogUtil.w(TAG, "停止请求被忽略");
            return;
        }
        if (currentRecording == null) {
            // 可能处于自动分段间隙中
            forceResetState();
            stopTimeoutHandler.removeCallbacksAndMessages(null);
            if (listener != null) {
                listener.onRecordStop(true, null,
                        currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
            }
            return;
        }
        isStopping.set(true);
        isAutoSplitting.set(false);

        stopTimeoutHandler.removeCallbacksAndMessages(null);
        stopTimeoutHandler.postDelayed(() -> {
            if (isRecording.get() || isStopping.get()) {
                LogUtil.e(TAG, "停止超时，强制重置");
                forceResetState();
                if (listener != null) {
                    listener.onRecordStop(true, "已强制停止",
                            currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
                }
            }
        }, cameraConfig.stopTimeoutMs);

        try {
            currentRecording.stop();
        } catch (Exception e) {
            LogUtil.e(TAG, "停止异常", e);
            forceResetState();
            if (listener != null) {
                listener.onRecordStop(false, "停止异常: " + e.getMessage(),
                        currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
            }
        }
    }

    private void forceResetState() {
        isRecording.set(false);
        isStopping.set(false);
        isAutoSplitting.set(false);
        isFinalizing.set(false);
        currentRecording = null;
        mainHandler.removeCallbacksAndMessages(null);
        stopTimeoutHandler.removeCallbacksAndMessages(null);
    }

    private void handleRecordEvent(@NonNull VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            if (listener != null) {
                listener.onSegmentStart(currentSegmentIndex.get() + 1, videoFile.getAbsolutePath());
            }
        } else if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
            isFinalizing.set(false);
            boolean hasError = finalizeEvent.hasError();
            String errorMsg = hasError ? (finalizeEvent.getCause() != null ? finalizeEvent.getCause().getMessage() : "未知错误") : null;

            if (isAutoSplitting.get()) {
                isAutoSplitting.set(false);
                if (!hasError && isRecording.get()) {
                    if (cameraConfig.maxSegmentCount > 0 &&
                            currentSegmentIndex.get() + 1 >= cameraConfig.maxSegmentCount) {
                        forceResetState();
                        if (listener != null) {
                            listener.onRecordStop(true, "达到最大片段数",
                                    currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
                        }
                        return;
                    }

                    currentSegmentIndex.incrementAndGet();
                    mainHandler.postDelayed(() -> {
                        if (isRecording.get()) {
                            boolean next = startSegmentRecording();
                            if (!next) {
                                forceResetState();
                                if (listener != null) {
                                    listener.onRecordStop(false, "分段启动失败",
                                            currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
                                }
                            }
                        }
                    }, 300);

                } else {
                    forceResetState();
                    if (listener != null) {
                        listener.onRecordStop(!hasError, errorMsg,
                                currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
                    }
                }

            } else {
                forceResetState();
                stopTimeoutHandler.removeCallbacksAndMessages(null);
                if (listener != null) {
                    listener.onRecordStop(!hasError, errorMsg,
                            currentSessionFolder != null ? currentSessionFolder.getAbsolutePath() : null);
                }
            }
        }
    }

    private boolean checkStorage() {
        try {
            File baseDir = storageManager.getBaseDir(dataConfig.baseFolderName);
            if (baseDir == null) return false;
            StorageManager.StorageCheckResult r = storageManager.checkStorage(baseDir);
            if (!r.isEnough) {
                if (listener != null) listener.onStorageWarning(r.availableMB);
                return false;
            }
            return true;
        } catch (Exception e) {
            LogUtil.e(TAG, "存储检查异常，保守阻止录制", e);
            return false;
        }
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRecording() { return isRecording.get(); }

    public void release() { forceResetState(); }
}