package com.example.cameraphonedata.camera;

import android.content.Context;
import android.util.Log;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.recorder.VideoRecorder;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 相机管理器 —— 生命周期、变焦、预览统一管控。
 *
 * 【关键修复】增加 cameraSessionId，防止标定返回后异步回调竞争导致绑定被覆盖。
 */
public class CameraManager {
    private static final String TAG = "CameraManager";

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private final CameraConfig config;

    private VideoRecorder videoRecorder;
    private OnCameraReadyListener readyListener;
    private OnZoomChangedListener zoomListener;

    private ImageAnalysis imageAnalysis;
    private ImageAnalysis.Analyzer frameAnalyzer;

    private volatile boolean cameraReady = false;
    private volatile boolean isStartingCamera = false;
    private volatile int cameraSessionId = 0;

    public interface OnCameraReadyListener {
        void onCameraReady(Camera camera, CameraParamReader.CameraParams params);
        void onCameraError(String error);
    }

    public interface OnZoomChangedListener {
        void onZoomChanged(float currentZoom, float maxZoom, float minZoom);
    }

    public CameraManager(Context context, LifecycleOwner lifecycleOwner) {
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
        this.config = CameraConfig.getInstance();
        this.videoRecorder = new VideoRecorder(context);
    }

    public void setOnCameraReadyListener(OnCameraReadyListener listener) {
        this.readyListener = listener;
    }

    public void setOnZoomChangedListener(OnZoomChangedListener listener) {
        this.zoomListener = listener;
    }

    public void setFrameAnalyzer(ImageAnalysis.Analyzer analyzer) {
        this.frameAnalyzer = analyzer;
    }

    public VideoRecorder getVideoRecorder() {
        return videoRecorder;
    }

    public boolean isCameraReady() {
        return cameraReady && camera != null;
    }

    public void startCamera(PreviewView previewView) {
        if (isStartingCamera) {
            Log.w(TAG, "相机启动中，忽略重复请求");
            return;
        }
        isStartingCamera = true;
        cameraSessionId++;
        final int sessionId = cameraSessionId;

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);

        future.addListener(() -> {
            try {
                if (sessionId != cameraSessionId) {
                    Log.w(TAG, "相机启动会话已过期，放弃绑定");
                    return;
                }

                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                videoRecorder.setupRecorder(config.videoQuality, config.recordAudio);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

                if (frameAnalyzer != null) {
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    imageAnalysis.setAnalyzer(
                            ContextCompat.getMainExecutor(context),
                            frameAnalyzer
                    );
                    camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, selector, preview,
                            videoRecorder.getVideoCapture(), imageAnalysis);
                } else {
                    camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, selector, preview,
                            videoRecorder.getVideoCapture());
                }

                if (camera == null) {
                    throw new RuntimeException("相机绑定失败");
                }

                if (config.currentZoom > 0 && config.currentZoom != 1.0f) {
                    setZoom(config.currentZoom);
                    Log.i(TAG, "启动时恢复 zoom: " + config.currentZoom + "x");
                }

                observeZoomState();
                readAndNotifyCameraParams();

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
                cameraReady = false;
                if (readyListener != null) {
                    readyListener.onCameraError("启动失败: " + e.getMessage());
                }
            } catch (Exception e) {
                Log.e(TAG, "相机初始化异常", e);
                cameraReady = false;
                if (readyListener != null) {
                    readyListener.onCameraError("初始化异常: " + e.getMessage());
                }
            } finally {
                if (sessionId == cameraSessionId) {
                    isStartingCamera = false;
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void observeZoomState() {
        if (camera == null) return;

        camera.getCameraInfo().getZoomState().observe(lifecycleOwner, state -> {
            if (state != null && zoomListener != null) {
                float sysMin = state.getMinZoomRatio();
                float sysMax = state.getMaxZoomRatio();
                float sysCurrent = state.getZoomRatio();

                if (sysMax > 0) config.maxZoom = sysMax;
                if (sysMin > 0) config.minZoom = sysMin;
                config.currentZoom = sysCurrent;

                zoomListener.onZoomChanged(sysCurrent, sysMax, sysMin);
            }
        });
    }

    private void readAndNotifyCameraParams() {
        try {
            CameraParamReader reader = new CameraParamReader();
            CameraParamReader.CameraParams params = reader.readParams(
                    context, camera,
                    videoRecorder.getTargetWidth(),
                    videoRecorder.getTargetHeight()
            );

            cameraReady = true;

            if (readyListener != null) {
                readyListener.onCameraReady(camera, params);
            }
        } catch (Exception e) {
            Log.e(TAG, "读取相机参数失败", e);
            cameraReady = false;
            if (readyListener != null) {
                readyListener.onCameraError("读取参数失败");
            }
        }
    }

    public void setZoom(float zoom) {
        if (camera == null) {
            Log.w(TAG, "变焦失败：camera 为 null");
            return;
        }
        try {
            CameraControl control = camera.getCameraControl();
            ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();

            if (zoomState != null) {
                float maxZoom = zoomState.getMaxZoomRatio();
                float minZoom = zoomState.getMinZoomRatio();
                float clampedZoom = Math.max(minZoom, Math.min(zoom, maxZoom));

                control.setZoomRatio(clampedZoom);
                config.currentZoom = clampedZoom;
                Log.d(TAG, "变焦设置: " + clampedZoom + "x (范围: " + minZoom + "-" + maxZoom + "x)");
            }
        } catch (Exception e) {
            Log.e(TAG, "变焦失败", e);
        }
    }

    public void stopCamera() {
        cameraReady = false;
        isStartingCamera = false;
        cameraSessionId++;

        if (videoRecorder != null && videoRecorder.isRecording()) {
            try {
                videoRecorder.stopRecording();
            } catch (Exception e) {
                Log.e(TAG, "停止录制异常", e);
            }
        }

        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "解绑相机异常", e);
            }
        }

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }

        camera = null;
        Log.i(TAG, "相机已停止");
    }

    public Camera getCamera() {
        return camera;
    }
}