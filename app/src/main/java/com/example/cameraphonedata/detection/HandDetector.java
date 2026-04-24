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
 */
public class HandDetector implements ImageAnalysis.Analyzer {
    private static final String TAG = "HandDetector";

    private final PoseDetector poseDetector;
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

        PoseDetectorOptionsBase options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        this.poseDetector = PoseDetection.getClient(options);
        this.mlKitExecutor = Executors.newSingleThreadExecutor();
        this.timeoutHandler = new Handler(Looper.getMainLooper());
    }

    public void setOnHandDetectedListener(OnHandDetectedListener listener) {
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.i(TAG, "人手检测开关: " + enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    @ExperimentalGetImage
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (!enabled) {
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
        mlKitExecutor.shutdown();
        poseDetector.close();
        timeoutHandler.removeCallbacksAndMessages(null);
    }
}