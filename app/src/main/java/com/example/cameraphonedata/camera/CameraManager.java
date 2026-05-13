// ========== CameraManager.java（完整代码） ==========
package com.example.cameraphonedata.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
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
import com.example.cameraphonedata.utils.LogUtil;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相机管理器 —— 生命周期、变焦、预览、镜头角色统一管控。
 * 【2026-05-11 修复】
 * 1. 物理子相机（如红米K80/Note12Turbo）不再直接放弃绑定，保留 ID 让启动时尝试。
 * 2. 绑定失败后自动清除 ultraWideCameraId，避免用户反复点击广角时无限重试。
 * 3. 潜在融合架构启动后立即根据 realMinZoom 确认是否真有融合能力，避免界面显示"广角"实际为主摄。
 * 4. fallback 提示精确化，明确告知用户是"系统限制"而非 App 问题。
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public class CameraManager {
    private static final String TAG = "CameraManager";
    private static final float ULTRA_WIDE_FOCAL_THRESHOLD = 5.0f;

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private final CameraConfig config;

    private final VideoRecorder videoRecorder;
    private OnCameraReadyListener readyListener;
    private OnZoomChangedListener zoomListener;

    @SuppressWarnings("FieldCanBeLocal")
    private ImageAnalysis imageAnalysis;
    private ImageAnalysis.Analyzer frameAnalyzer;

    private volatile boolean cameraReady = false;
    private volatile boolean isStartingCamera = false;
    private final AtomicInteger cameraSessionId = new AtomicInteger(0);

    private boolean lensDetected = false;
    private String ultraWideCameraId = null;
    private boolean ultraWideSupport1080p = false;
    private String wideCameraId = null;

    private String fallbackReason = null;
    private boolean isFusionArchitecture = false;
    private boolean potentialFusionArchitecture = false; // 启动前标记可能支持zoom<1.0
    private float realMinZoom = 1.0f;
    private float realMaxZoom = 1.0f;

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

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isCameraReady() {
        return cameraReady && camera != null;
    }

    public String consumeFallbackReason() {
        String reason = fallbackReason;
        fallbackReason = null;
        return reason;
    }

    public boolean hasUltraWideLens() {
        return ultraWideCameraId != null || isFusionArchitecture || potentialFusionArchitecture;
    }

    public boolean isUltraWideSupported() {
        return ultraWideSupport1080p || isFusionArchitecture || potentialFusionArchitecture;
    }

    public boolean isFusionArchitecture() {
        return isFusionArchitecture;
    }

    public float getRealMinZoom() {
        return realMinZoom;
    }

    public float getRealMaxZoom() {
        return realMaxZoom;
    }

    // ==================== 核心：启动相机 ====================

    public void startCamera(PreviewView previewView) {
        if (isStartingCamera) {
            LogUtil.w(TAG, "相机启动中，忽略重复请求");
            return;
        }
        config.validateAndFix();
        isStartingCamera = true;
        final int sessionId = cameraSessionId.incrementAndGet();

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);

        future.addListener(() -> {
            try {
                if (sessionId != cameraSessionId.get()) {
                    LogUtil.w(TAG, "相机启动会话已过期，放弃绑定");
                    return;
                }

                if (!lensDetected) {
                    detectLensRoles();
                }

                String targetCameraId = null;
                if (config.currentLensRole == CameraConfig.LensRole.ULTRA_WIDE) {
                    if (ultraWideCameraId != null && ultraWideSupport1080p) {
                        targetCameraId = ultraWideCameraId;
                    } else if (isFusionArchitecture || potentialFusionArchitecture) {
                        // 【关键修复】潜在融合架构设备也保留广角角色，启动后通过ZoomState最终确认
                        targetCameraId = null;
                        LogUtil.i(TAG, "融合架构/潜在融合架构广角模式：使用默认后置");
                    } else {
                        config.currentLensRole = CameraConfig.LensRole.WIDE;
                        fallbackReason = "该机型超广角硬件存在，但系统限制第三方App访问\n"
                                + "（常见于红米，iqoo等机型）\n"
                                + "如需广角采集，建议更换为小米14/三星/Pixel/OPPO等机型";
                        LogUtil.w(TAG, "广角不可用，无独立超广角且 minZoom=" + realMinZoom + "，fallback 至主摄");
                    }
                }

                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                videoRecorder.setupRecorder(config.videoQuality, config.recordAudio);

                CameraSelector selector = buildCameraSelector(targetCameraId);

                if (frameAnalyzer != null) {
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    imageAnalysis.setAnalyzer(
                            ContextCompat.getMainExecutor(context),
                            frameAnalyzer
                    );
                    camera = tryBindLifecycle(selector, preview, imageAnalysis);
                } else {
                    camera = tryBindLifecycle(selector, preview, null);
                }

                applyFrameRateLock(camera);

                if (camera != null) {
                    observeZoomState();

                    // 【关键修复】无条件读取 ZoomState，动态检测融合架构能力
                    ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
                    if (zs != null) {
                        realMinZoom = zs.getMinZoomRatio();
                        realMaxZoom = zs.getMaxZoomRatio();
                        config.minZoom = realMinZoom;
                        config.maxZoom = realMaxZoom;
                    }

                    // 【关键修复】潜在融合架构：启动后立即根据 realMinZoom 确认是否真有融合能力
                    // 避免界面显示"广角"但实际是主摄画面
                    if (potentialFusionArchitecture && !isFusionArchitecture) {
                        if (realMinZoom < 1.0f) {
                            isFusionArchitecture = true;
                            LogUtil.i(TAG, "潜在融合架构确认成功: realMinZoom=" + realMinZoom);
                        } else {
                            potentialFusionArchitecture = false;
                            LogUtil.w(TAG, "潜在融合架构确认失败: realMinZoom=" + realMinZoom + "，无融合广角能力");
                            if (config.currentLensRole == CameraConfig.LensRole.ULTRA_WIDE) {
                                config.currentLensRole = CameraConfig.LensRole.WIDE;
                                config.currentZoom = 1.0f;
                                fallbackReason = "该机型超广角硬件存在，但系统限制第三方App访问\n"
                                        + "（常见于红米K80/Note12Turbo等机型）\n"
                                        + "如需广角采集，建议更换为小米14/三星/Pixel/OPPO等机型";
                                LogUtil.w(TAG, "潜在融合架构无广角能力，强制 fallback 主摄");
                            }
                        }
                    }

                    // 动态确认融合架构（兜底，针对启动时未及时检测到的情况）
                    if (ultraWideCameraId == null && realMinZoom < 1.0f && !isFusionArchitecture) {
                        isFusionArchitecture = true;
                        LogUtil.i(TAG, "动态确认融合架构: realMinZoom=" + realMinZoom);
                    }

                    // 【关键修复】融合架构立即校正 zoom，不再延迟 800ms
                    if (isFusionArchitecture) {
                        if (config.currentLensRole == CameraConfig.LensRole.ULTRA_WIDE) {
                            if (realMinZoom >= 1.0f) {
                                config.currentLensRole = CameraConfig.LensRole.WIDE;
                                config.currentZoom = 1.0f;
                                camera.getCameraControl().setZoomRatio(1.0f);
                                fallbackReason = "该机型超广角硬件存在，但系统限制第三方App访问\n"
                                        + "（常见于红米K80/Note12Turbo等机型）\n"
                                        + "如需广角采集，建议更换为小米14/三星/Pixel/OPPO等机型";
                                LogUtil.i(TAG, "融合架构无超广角（minZoom=" + realMinZoom + "），自动回退主摄");
                            } else {
                                float targetZoom = realMinZoom > 0 ? realMinZoom : 0.6f;
                                camera.getCameraControl().setZoomRatio(targetZoom);
                                config.currentZoom = targetZoom;
                                LogUtil.i(TAG, "融合架构立即设置广角 zoom: " + targetZoom + "x");
                            }
                        } else {
                            camera.getCameraControl().setZoomRatio(1.0f);
                            config.currentZoom = 1.0f;
                            LogUtil.i(TAG, "融合架构立即设置主摄 zoom: 1.0x");
                        }

                        if (zoomListener != null) {
                            zoomListener.onZoomChanged(config.currentZoom, config.maxZoom, config.minZoom);
                        }
                    }
                }

                // 【关键修复】融合架构：延迟 300ms 确认 zoom 真正生效，防止 1.0x 视野不对
                if (isFusionArchitecture && camera != null) {
                    mainHandler.postDelayed(() -> {
                        if (camera == null) return;
                        try {
                            ZoomState zs2 = camera.getCameraInfo().getZoomState().getValue();
                            if (zs2 != null) {
                                float actualZoom = zs2.getZoomRatio();
                                float expectedZoom = config.currentZoom;
                                if (Math.abs(actualZoom - expectedZoom) > 0.05f) {
                                    LogUtil.w(TAG, "zoom 未生效，实际=" + actualZoom + "x, 期望=" + expectedZoom + "x, 强制重设");
                                    camera.getCameraControl().setZoomRatio(expectedZoom);
                                }
                                // 同步真实边界到配置
                                realMinZoom = zs2.getMinZoomRatio();
                                realMaxZoom = zs2.getMaxZoomRatio();
                                config.minZoom = realMinZoom;
                                config.maxZoom = realMaxZoom;

                                // 【关键修复】延迟确认阶段再次动态检测融合架构
                                if (ultraWideCameraId == null && realMinZoom > 0 && realMinZoom < 1.0f && !isFusionArchitecture) {
                                    isFusionArchitecture = true;
                                    LogUtil.i(TAG, "延迟确认融合架构: realMinZoom=" + realMinZoom);
                                    if (zoomListener != null) {
                                        zoomListener.onZoomChanged(config.currentZoom, config.maxZoom, config.minZoom);
                                    }
                                } else if (zoomListener != null) {
                                    zoomListener.onZoomChanged(config.currentZoom, config.maxZoom, config.minZoom);
                                }
                            }
                        } catch (Exception e) {
                            LogUtil.e(TAG, "延迟确认 zoom 失败", e);
                        }
                    }, 300);
                }

                readAndNotifyCameraParams();

            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LogUtil.e(TAG, "相机启动失败", e);
                cameraReady = false;
                if (readyListener != null) {
                    readyListener.onCameraError("启动失败: " + e.getMessage());
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "相机初始化异常", e);
                cameraReady = false;
                if (readyListener != null) {
                    readyListener.onCameraError("初始化异常: " + e.getMessage());
                }
            } finally {
                if (sessionId == cameraSessionId.get()) {
                    isStartingCamera = false;
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    // ==================== 融合架构下切换镜头（不重启相机） ====================

    public void setZoomForLensRole(CameraConfig.LensRole role) {
        if (!isFusionArchitecture || camera == null) return;
        try {
            float target = (role == CameraConfig.LensRole.ULTRA_WIDE) ? realMinZoom : 1.0f;
            if (role == CameraConfig.LensRole.ULTRA_WIDE && realMinZoom >= 1.0f) {
                LogUtil.w(TAG, "该设备无超广角，拒绝切换");
                return;
            }
            camera.getCameraControl().setZoomRatio(target);
            config.currentZoom = target;
            config.currentLensRole = role;
            LogUtil.i(TAG, "融合架构切换镜头: " + role.name() + " -> zoom=" + target);
        } catch (Exception e) {
            LogUtil.e(TAG, "融合架构切换 zoom 失败", e);
        }
    }

    // ==================== 镜头探测（多维度加权） ====================

    private boolean isPhysicalChildCamera(android.hardware.camera2.CameraManager cm, String cameraId) {
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                for (int cap : capabilities) {
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                        return false;
                    }
                }
            }
            for (String otherId : cm.getCameraIdList()) {
                CameraCharacteristics otherChars = cm.getCameraCharacteristics(otherId);
                int[] otherCaps = otherChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (otherCaps != null) {
                    boolean isLogical = false;
                    for (int cap : otherCaps) {
                        if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                            isLogical = true;
                            break;
                        }
                    }
                    if (isLogical) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            java.util.Set<String> physicalIds = otherChars.getPhysicalCameraIds();
                            if (physicalIds != null && physicalIds.contains(cameraId)) {
                                LogUtil.w(TAG, "cameraId=" + cameraId + " 是 logical camera=" + otherId + " 的物理子相机，由逻辑相机统一调度");
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "判断 logical child 异常", e);
        }
        return false;
    }

    private float sanityCheckFocalLength(float focal, String cameraId) {
        if (focal <= 0) {
            LogUtil.w(TAG, "cameraId=" + cameraId + " 焦距非法(<=0): " + focal + "，使用默认值 4.5mm");
            return 4.5f;
        }
        if (focal > 20.0f) {
            float estimatedPhysical = focal / 6.0f;
            LogUtil.w(TAG, "cameraId=" + cameraId + " 焦距疑似 35mm 等效值: " + focal + "mm，估算物理焦距≈"
                    + String.format(java.util.Locale.US, "%.2f", estimatedPhysical) + "mm");
            return estimatedPhysical;
        }
        return focal;
    }

    private void detectLensRoles() {
        isFusionArchitecture = false;
        potentialFusionArchitecture = false;
        ultraWideCameraId = null;
        ultraWideSupport1080p = false;
        wideCameraId = null;

        android.hardware.camera2.CameraManager sysCameraManager =
                (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (sysCameraManager == null) {
            LogUtil.e(TAG, "系统 CameraManager 为空");
            return;
        }

        try {
            String[] cameraIds = sysCameraManager.getCameraIdList();
            float minFocal = Float.MAX_VALUE;
            String candidateUltraWide = null;
            String candidateWide = null;
            float bestWideScore = -1;

            LogUtil.i(TAG, "===== 开始镜头探测，共 " + cameraIds.length + " 个 cameraId =====");

            for (String id : cameraIds) {
                CameraCharacteristics chars = sysCameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;

                float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focalLengths == null || focalLengths.length == 0) continue;

                float rawFocal = focalLengths[0];
                float focal = sanityCheckFocalLength(rawFocal, id);

                // 【关键修复】判断是否为物理子相机，但不再直接跳过
                boolean isPhysicalChild = isPhysicalChildCamera(sysCameraManager, id);

                SizeF sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float sensorArea = 0;
                if (sensorSize != null) {
                    sensorArea = sensorSize.getWidth() * sensorSize.getHeight();
                }

                LogUtil.i(TAG, "探测 cameraId=" + id + ", 原始焦距=" + rawFocal + "mm, 修正后=" + focal + "mm, 传感器面积=" + sensorArea + ", 物理子相机=" + isPhysicalChild);

                // 【关键修复】超广角候选：所有后置镜头（包括物理子相机）中焦距最短的
                if (focal < minFocal) {
                    minFocal = focal;
                    candidateUltraWide = id;
                }

                // 主摄评分只考虑逻辑相机（非物理子相机）
                if (!isPhysicalChild) {
                    float score = 0;
                    if (focal >= 3.0f && focal <= 7.0f) score += 3;
                    else if (focal >= 2.5f && focal <= 8.0f) score += 1;

                    if (sensorSize != null) {
                        if (sensorArea > 28f) score += 2;
                        else if (sensorArea > 20f) score += 1;
                        else if (sensorArea < 15f) score -= 1;
                    }

                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
                        if (videoSizes != null) {
                            boolean support4K = false;
                            boolean support1080p = false;
                            for (Size s : videoSizes) {
                                if (s.getWidth() >= 3840) support4K = true;
                                if (s.getWidth() == 1920 || s.getHeight() == 1080 || s.getHeight() == 1088) support1080p = true;
                            }
                            if (support4K) score += 2;
                            if (support1080p) score += 1;
                        }
                    }

                    if (score > bestWideScore) {
                        bestWideScore = score;
                        candidateWide = id;
                    }

                    LogUtil.i(TAG, "cameraId=" + id + " 主摄评分=" + score);
                }
            }

            if (candidateUltraWide != null && minFocal < ULTRA_WIDE_FOCAL_THRESHOLD) {
                boolean isUltraWidePhysicalChild = isPhysicalChildCamera(sysCameraManager, candidateUltraWide);
                if (candidateWide != null && candidateUltraWide.equals(candidateWide)) {
                    isFusionArchitecture = true;
                    wideCameraId = candidateWide;
                    LogUtil.i(TAG, "融合架构 detected: 单颗逻辑镜头，广角通过 zoom<1.0 实现");
                } else if (isUltraWidePhysicalChild) {
                    // 【关键修复】物理子相机：先保留 ID 尝试绑定，绑定失败再 fallback 到融合架构
                    ultraWideCameraId = candidateUltraWide;
                    ultraWideSupport1080p = checkVideoSupport(sysCameraManager, ultraWideCameraId, 1920, 1080);
                    potentialFusionArchitecture = true;  // 同时标记，绑定失败时尝试 zoom 兜底
                    LogUtil.i(TAG, "物理子相机超广角: id=" + ultraWideCameraId
                            + ", 1080p=" + ultraWideSupport1080p
                            + ", 尝试绑定，失败则 fallback");
                } else {
                    ultraWideCameraId = candidateUltraWide;
                    ultraWideSupport1080p = checkVideoSupport(sysCameraManager, ultraWideCameraId, 1920, 1080);
                    LogUtil.i(TAG, "探测到独立超广角: id=" + ultraWideCameraId + ", focal=" + minFocal + "mm, 1080p=" + ultraWideSupport1080p);
                }
            } else {
                LogUtil.i(TAG, "未探测到独立超广角，最短焦距=" + minFocal + "mm");
            }

            // 【关键修复】兜底逻辑：只要有后置逻辑镜头且未找到独立超广角，就标记潜在融合架构
            // 启动相机后会通过 ZoomState 的 minZoomRatio 最终确认是否真正支持 zoom<1.0
            if (ultraWideCameraId == null && candidateWide != null) {
                int logicalBackCount = 0;
                try {
                    for (String id : cameraIds) {
                        CameraCharacteristics chars = sysCameraManager.getCameraCharacteristics(id);
                        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                        if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                        if (isPhysicalChildCamera(sysCameraManager, id)) continue;

                        int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                        if (capabilities != null) {
                            for (int cap : capabilities) {
                                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                    logicalBackCount++;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogUtil.e(TAG, "逻辑镜头兜底检测异常", e);
                }

                if (logicalBackCount >= 1) {
                    potentialFusionArchitecture = true;
                    wideCameraId = candidateWide;
                    LogUtil.i(TAG, "兜底：标记潜在融合架构，启动后通过ZoomState确认 minZoom");
                }
            }

            if (candidateWide != null) {
                wideCameraId = candidateWide;
                LogUtil.i(TAG, "探测到主摄: id=" + wideCameraId + ", score=" + bestWideScore);
            } else {
                wideCameraId = null;
                LogUtil.i(TAG, "主摄使用默认后置");
            }

            LogUtil.i(TAG, "===== 镜头探测结束: wideId=" + wideCameraId + ", ultraWideId=" + ultraWideCameraId
                    + ", ultraWideSupport1080p=" + ultraWideSupport1080p + ", isFusion=" + isFusionArchitecture + " =====");

            lensDetected = true;

        } catch (Exception e) {
            LogUtil.e(TAG, "镜头探测异常", e);
            lensDetected = false;
            ultraWideCameraId = null;
            wideCameraId = null;
            isFusionArchitecture = false;
            potentialFusionArchitecture = false;
        }
    }

    @SuppressWarnings("SameParameterValue")
    private boolean checkVideoSupport(android.hardware.camera2.CameraManager cm, String cameraId, int width, int height) {
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return false;
            Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
            if (videoSizes == null) return false;
            for (Size s : videoSizes) {
                if ((s.getWidth() == width && s.getHeight() == height) ||
                        (s.getWidth() == width && s.getHeight() == 1088)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "检查视频支持失败", e);
        }
        return false;
    }

    // ==================== CameraSelector 构建与绑定兜底 ====================

    private CameraSelector buildCameraSelector(String targetCameraId) {
        if (targetCameraId == null || targetCameraId.isEmpty()) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }
        return new CameraSelector.Builder()
                .addCameraFilter(cameraInfos -> {
                    List<CameraInfo> result = new ArrayList<>();
                    for (CameraInfo info : cameraInfos) {
                        try {
                            Camera2CameraInfo c2info = Camera2CameraInfo.from(info);
                            if (targetCameraId.equals(c2info.getCameraId())) {
                                result.add(info);
                                break;
                            }
                        } catch (Exception e) {
                            LogUtil.e(TAG, "CameraFilter 匹配异常", e);
                        }
                    }
                    return result;
                })
                .build();
    }

    private Camera tryBindLifecycle(CameraSelector selector, Preview preview, ImageAnalysis analysis) {
        try {
            if (analysis != null) {
                return cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview,
                        videoRecorder.getVideoCapture(), analysis);
            } else {
                return cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview,
                        videoRecorder.getVideoCapture());
            }
        } catch (IllegalArgumentException e) {
            LogUtil.w(TAG, "精确镜头绑定失败，fallback 到默认后置: " + e.getMessage());
            if (config.currentLensRole == CameraConfig.LensRole.ULTRA_WIDE) {
                config.currentLensRole = CameraConfig.LensRole.WIDE;
                // 【关键修复】绑定失败后禁用该物理子相机 ID，避免下次重复尝试绑定
                if (ultraWideCameraId != null) {
                    LogUtil.w(TAG, "超广角物理子相机绑定失败，标记禁用: " + ultraWideCameraId);
                    ultraWideCameraId = null;
                    ultraWideSupport1080p = false;
                }
                fallbackReason = "该机型超广角硬件存在，但系统限制第三方App访问\n"
                        + "（常见于红米K80/Note12Turbo等机型）\n"
                        + "如需广角采集，建议更换为小米14/三星/Pixel/OPPO等机型";
            }
            if (analysis != null) {
                return cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, videoRecorder.getVideoCapture(), analysis);
            } else {
                return cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, videoRecorder.getVideoCapture());
            }
        }
    }

    // ==================== 帧率锁定 ====================

    private void applyFrameRateLock(Camera camera) {
        if (config.targetFrameRate <= 0) {
            LogUtil.i(TAG, "帧率锁定已关闭，使用系统默认");
            return;
        }

        try {
            Range<Integer> exactRange = new Range<>(config.targetFrameRate, config.targetFrameRate);
            CaptureRequestOptions exactOptions = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, exactRange)
                    .build();

            Camera2CameraControl camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            camera2Control.setCaptureRequestOptions(exactOptions);

            LogUtil.i(TAG, "帧率已精确锁定: " + config.targetFrameRate + "fps");

        } catch (Exception e) {
            LogUtil.e(TAG, "精确帧率锁定失败，尝试宽松范围", e);
            try {
                int lower = Math.min(24, config.targetFrameRate);
                int upper = Math.max(config.targetFrameRate, 30);
                Range<Integer> fallbackRange = new Range<>(lower, upper);
                CaptureRequestOptions fallbackOptions = new CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fallbackRange)
                        .build();
                Camera2CameraControl camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                camera2Control.setCaptureRequestOptions(fallbackOptions);
                LogUtil.i(TAG, "帧率宽松锁定: " + lower + "-" + upper + "fps");
            } catch (Exception e2) {
                LogUtil.e(TAG, "帧率锁定完全失败", e2);
            }
        }
    }

    // ==================== 变焦与回调 ====================

    private void observeZoomState() {
        if (camera == null) return;

        camera.getCameraInfo().getZoomState().observe(lifecycleOwner, state -> {
            if (state == null) return;

            float sysMin = state.getMinZoomRatio();
            float sysMax = state.getMaxZoomRatio();
            float sysCurrent = state.getZoomRatio();

            if (sysMax > 0) {
                config.maxZoom = sysMax;
                realMaxZoom = sysMax;
            }
            if (sysMin > 0) {
                config.minZoom = sysMin;
                realMinZoom = sysMin;
            }
            config.currentZoom = sysCurrent;

            // 【关键】融合架构下：根据实际 zoom 值自动同步镜头角色，防止状态漂移
            if (isFusionArchitecture) {
                if (sysCurrent < 0.95f && realMinZoom < 1.0f) {
                    config.currentLensRole = CameraConfig.LensRole.ULTRA_WIDE;
                } else {
                    config.currentLensRole = CameraConfig.LensRole.WIDE;
                }
            }

            // 【关键修复】运行中动态发现融合架构能力（如启动时未及时检测到）
            if (!isFusionArchitecture && ultraWideCameraId == null && sysMin > 0 && sysMin < 1.0f) {
                isFusionArchitecture = true;
                LogUtil.i(TAG, "ZoomState动态确认融合架构: minZoom=" + sysMin);
                if (zoomListener != null) {
                    zoomListener.onZoomChanged(sysCurrent, sysMax, sysMin);
                }
            }

            if (zoomListener != null) {
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
            LogUtil.e(TAG, "读取相机参数失败", e);
            cameraReady = false;
            if (readyListener != null) {
                readyListener.onCameraError("读取参数失败");
            }
        }
    }

    public void stopCamera() {
        cameraReady = false;
        isStartingCamera = false;
        cameraSessionId.incrementAndGet();
        lensDetected = false;
        isFusionArchitecture = false;
        potentialFusionArchitecture = false;
        realMinZoom = 1.0f;
        realMaxZoom = 1.0f;
        mainHandler.removeCallbacksAndMessages(null);

        if (videoRecorder != null && videoRecorder.isRecording()) {
            try {
                videoRecorder.stopRecording();
            } catch (Exception e) {
                LogUtil.e(TAG, "停止录制异常", e);
            }
        }

        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                LogUtil.e(TAG, "解绑相机异常", e);
            }
        }

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }

        camera = null;
        LogUtil.i(TAG, "相机已停止");
    }

    public Camera getCamera() {
        return camera;
    }
}