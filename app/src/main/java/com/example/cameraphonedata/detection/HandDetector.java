package com.example.cameraphonedata.detection;

import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【应急方案】继续用现有的 Pose Detection（无需改 Gradle）。
 * 俯视拍摄时画面通常有手臂，检测手腕+手肘+肩膀任意一个即可触发。
 *
 * 【内存加固】
 * 1. 增加 3 秒超时兜底：MLKit 回调延迟时强制 imageProxy.close()，防止相机缓冲区堆积。
 * 2. AtomicBoolean 标志位防止重复 close 导致崩溃。
 *
 * 【国内网络优化】
 * 1. 懒加载：构造时不再同步创建 PoseDetector，延迟到 setEnabled(true) 时才在后台线程初始化。
 *    避免 App 一启动就触发 MLKit 内部的 Firebase Remote Config 拉取（国内大概率 5 秒超时）。
 * 2. released 标志位：release() 调用后如果初始化任务刚好完成，自动 close 掉未使用的 detector，防止泄漏。
 */
public class HandDetector implements ImageAnalysis.Analyzer {
    private static final String TAG = "HandDetector";

    private volatile PoseDetector poseDetector;
    private final PoseDetectorOptionsBase options;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean initializing = new AtomicBoolean(false);
    private volatile boolean released = false;

    private final ExecutorService mlKitExecutor;
    private final float confidenceThreshold;
    private final long intervalMs;
    private final Handler timeoutHandler;
    private long lastDetectionTime = 0;
    private OnHandDetectedListener listener;
    private volatile boolean enabled = false;

    public interface OnHandDetectedListener {
        void onHandDetected(boolean hasHand);
    }

    public HandDetector(float confidenceThreshold, long intervalMs) {
        this.confidenceThreshold = confidenceThreshold;
        this.intervalMs = intervalMs;
        this.options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        this.mlKitExecutor = Executors.newSingleThreadExecutor();
        this.timeoutHandler = new Handler(Looper.getMainLooper());
    }

    public void setOnHandDetectedListener(OnHandDetectedListener listener) {
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.i(TAG, "人手检测开关: " + enabled);
        if (enabled) {
            ensureInitialized();
        }
    }

    /**
     * 懒加载初始化：把 PoseDetection.getClient() 放到后台线程，
     * 避免主线程阻塞，同时延迟 Firebase Remote Config 请求到真正需要人手检测时。
     */
    private void ensureInitialized() {
        if (initialized.get()) return;
        if (!initializing.compareAndSet(false, true)) return;

        mlKitExecutor.execute(() -> {
            try {
                Log.i(TAG, "PoseDetector 懒加载初始化开始...");
                PoseDetector detector = PoseDetection.getClient(options);
                if (released) {
                    // 已经 release 了，直接关闭，避免泄漏
                    detector.close();
                    return;
                }
                poseDetector = detector;
                initialized.set(true);
                Log.i(TAG, "PoseDetector 初始化完成");
            } catch (Exception e) {
                Log.e(TAG, "PoseDetector 初始化失败", e);
                initializing.set(false); // 允许下次重试
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    @ExperimentalGetImage
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (!enabled || !initialized.get()) {
            imageProxy.close();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastDetectionTime < intervalMs) {
            imageProxy.close();
            return;
        }
        lastDetectionTime = now;

        Image image = imageProxy.getImage();
        if (image == null) {
            imageProxy.close();
            return;
        }

        // 【关键加固】超时兜底：3 秒后如果 MLKit 还没回调，强制 close
        final ImageProxy proxyRef = imageProxy;
        final AtomicBoolean closed = new AtomicBoolean(false);
        timeoutHandler.postDelayed(() -> {
            if (closed.compareAndSet(false, true)) {
                proxyRef.close();
                Log.w(TAG, "MLKit 回调超时，强制释放图像缓冲区");
            }
        }, 3000);

        try {
            InputImage inputImage = InputImage.fromMediaImage(
                    image,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            poseDetector.process(inputImage)
                    .addOnSuccessListener(mlKitExecutor, pose -> {
                        if (closed.compareAndSet(false, true)) {
                            boolean hasHand = checkAnyUpperBodyVisible(pose);
                            Log.i(TAG, "检测完成: hasHand=" + hasHand + " enabled=" + enabled);
                            if (listener != null && enabled) {
                                listener.onHandDetected(hasHand);
                            }
                            proxyRef.close();
                        }
                    })
                    .addOnFailureListener(mlKitExecutor, e -> {
                        if (closed.compareAndSet(false, true)) {
                            Log.e(TAG, "姿态检测失败: " + e.getMessage(), e);
                            proxyRef.close();
                        }
                    });
        } catch (Exception e) {
            if (closed.compareAndSet(false, true)) {
                Log.e(TAG, "图像处理异常: " + e.getMessage(), e);
                imageProxy.close();
            }
        }
    }

    /**
     * 检测手腕/手肘/肩膀任意一个。
     */
    private boolean checkAnyUpperBodyVisible(Pose pose) {
        int[] landmarks = {
                PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER
        };

        for (int landmarkType : landmarks) {
            PoseLandmark lm = pose.getPoseLandmark(landmarkType);
            if (lm != null && lm.getInFrameLikelihood() > confidenceThreshold) {
                return true;
            }
        }
        return false;
    }

    public void release() {
        released = true;
        mlKitExecutor.shutdown();
        PoseDetector detector = poseDetector;
        if (detector != null) {
            detector.close();
        }
        timeoutHandler.removeCallbacksAndMessages(null);
    }
}
