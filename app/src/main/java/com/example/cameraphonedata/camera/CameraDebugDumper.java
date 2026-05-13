package com.example.cameraphonedata.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.example.cameraphonedata.utils.LogUtil;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 相机多摄架构 Debug 导出器
 * 【调用方式】new CameraDebugDumper(context, lifecycleOwner).runFullDiagnostics(previewView);
 * 【输出位置】/Android/data/{pkg}/files/CameraDebug/camera_debug_{timestamp}.json
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public class CameraDebugDumper {
    private static final String TAG = "CameraDebug";
    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public CameraDebugDumper(Context context, LifecycleOwner lifecycleOwner) {
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
    }

    /**
     * 执行完整诊断并导出 JSON。
     */
    public void runFullDiagnostics(PreviewView previewView) {
        mainHandler.post(() -> Toast.makeText(context,
                "开始导出相机Debug信息...", Toast.LENGTH_SHORT).show());

        new Thread(() -> {
            File outFile = null;
            try {
                Log.i(TAG, "========== CameraDebug 开始 ==========");
                Log.i(TAG, "设备: " + Build.MANUFACTURER + " " + Build.MODEL);
                Log.i(TAG, "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

                JSONObject root = new JSONObject();
                root.put("dump_time", System.currentTimeMillis());
                root.put("device_model", Build.MODEL);
                root.put("device_manufacturer", Build.MANUFACTURER);
                root.put("android_version", Build.VERSION.RELEASE);
                root.put("sdk_int", Build.VERSION.SDK_INT);

                // ===== 阶段 1：Camera2 静态扫描（后台线程安全） =====
                Log.i(TAG, "[1/4] 扫描 Camera2 静态信息...");
                JSONObject camera2Info = dumpCamera2StaticInfo();
                root.put("camera2_static", camera2Info);
                Log.i(TAG, "[1/4] Camera2 扫描完成");

                // ===== 阶段 2/3/4：CameraX 操作必须在主线程 =====
                Log.i(TAG, "[2-4] CameraX 相关操作转到主线程执行...");
                final JSONObject[] cameraxHolder = new JSONObject[1];
                final JSONObject[] zoomHolder = new JSONObject[1];
                final JSONObject[] bindHolder = new JSONObject[1];

                CountDownLatch cameraLatch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        // 阶段 2
                        Log.i(TAG, "[2/4] 扫描 CameraX 动态信息...");
                        cameraxHolder[0] = dumpCameraXDynamicInfo();
                        Log.i(TAG, "[2/4] CameraX 扫描完成");

                        // 阶段 3
                        Log.i(TAG, "[3/4] 实测 ZoomState...");
                        zoomHolder[0] = dumpZoomState(previewView);
                        Log.i(TAG, "[3/4] ZoomState 实测完成");

                        // 阶段 4
                        Log.i(TAG, "[4/4] 执行绑定测试...");
                        bindHolder[0] = runBindingTest(previewView);
                        Log.i(TAG, "[4/4] 绑定测试完成");

                    } catch (Exception e) {
                        Log.e(TAG, "CameraX 主线程执行异常", e);
                    } finally {
                        cameraLatch.countDown();
                    }
                });

                boolean done = cameraLatch.await(15, TimeUnit.SECONDS);
                if (!done) {
                    Log.w(TAG, "CameraX 操作超时(15s)，使用部分结果");
                }

                root.put("camerax_dynamic", cameraxHolder[0] != null ? cameraxHolder[0] : new JSONObject());
                root.put("zoom_state", zoomHolder[0] != null ? zoomHolder[0] : new JSONObject());
                root.put("binding_test", bindHolder[0] != null ? bindHolder[0] : new JSONObject());

                // ===== 写入文件 =====
                Log.i(TAG, "准备写入文件...");
                outFile = writeJsonToFile(root);
                final File finalFile = outFile;
                Log.i(TAG, "========== CameraDebug 完成 ==========");
                Log.i(TAG, "文件路径: " + finalFile.getAbsolutePath());
                Log.i(TAG, "文件大小: " + finalFile.length() + " bytes");

                mainHandler.post(() -> Toast.makeText(context,
                        "Debug导出成功!\n路径: " + finalFile.getAbsolutePath(),
                        Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                Log.e(TAG, "Debug 导出失败", e);
                final String err = e.getMessage();
                mainHandler.post(() -> Toast.makeText(context,
                        "Debug导出失败: " + err, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ==================== Camera2 静态信息（后台线程） ====================

    private JSONObject dumpCamera2StaticInfo() throws Exception {
        JSONObject root = new JSONObject();
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            root.put("error", "CameraManager is null");
            return root;
        }

        String[] ids = cm.getCameraIdList();
        root.put("cameraId_count", ids.length);
        JSONArray idArray = new JSONArray();

        for (String id : ids) {
            JSONObject idObj = new JSONObject();
            idObj.put("id", id);

            CameraCharacteristics chars = cm.getCameraCharacteristics(id);

            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            idObj.put("lens_facing", facing != null ? facing : -1);
            idObj.put("lens_facing_name", facingName(facing));

            float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            JSONArray focalArr = new JSONArray();
            if (focalLengths != null) {
                for (float f : focalLengths) focalArr.put(String.format(Locale.US, "%.3f", f));
            }
            idObj.put("focal_lengths", focalArr);

            SizeF sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (sensorSize != null) {
                idObj.put("sensor_width_mm", String.format(Locale.US, "%.3f", sensorSize.getWidth()));
                idObj.put("sensor_height_mm", String.format(Locale.US, "%.3f", sensorSize.getHeight()));
            }

            Size pixelSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (pixelSize != null) {
                idObj.put("sensor_pixels", pixelSize.getWidth() + "x" + pixelSize.getHeight());
            }

            int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            JSONArray capArr = new JSONArray();
            boolean isLogical = false;
            if (caps != null) {
                for (int c : caps) {
                    capArr.put(capabilityName(c));
                    if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                        isLogical = true;
                    }
                }
            }
            idObj.put("capabilities", capArr);
            idObj.put("is_logical_multi_camera", isLogical);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Set<String> physicalIds = chars.getPhysicalCameraIds();
                JSONArray physArr = new JSONArray();
                if (physicalIds != null) {
                    for (String pid : physicalIds) physArr.put(pid);
                }
                idObj.put("physical_camera_ids", physArr);
            }

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            JSONArray videoResArr = new JSONArray();
            if (map != null) {
                Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
                if (videoSizes != null) {
                    for (Size s : videoSizes) {
                        videoResArr.put(s.getWidth() + "x" + s.getHeight());
                    }
                }
            }
            idObj.put("video_sizes", videoResArr);

            Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            idObj.put("max_digital_zoom", maxZoom != null ? String.format(Locale.US, "%.2f", maxZoom) : "null");

            idObj.put("is_physical_child_of_another_logical", isPhysicalChildCamera(cm, id));

            idArray.put(idObj);
        }
        root.put("camera_ids", idArray);
        return root;
    }

    // ==================== CameraX 动态信息（主线程） ====================

    private JSONObject dumpCameraXDynamicInfo() throws Exception {
        JSONObject root = new JSONObject();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        ProcessCameraProvider provider = future.get();

        List<CameraInfo> allInfos = provider.getAvailableCameraInfos();
        JSONArray infoArr = new JSONArray();
        for (CameraInfo ci : allInfos) {
            JSONObject obj = new JSONObject();
            try {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(ci);
                obj.put("camera_id", c2.getCameraId());

                ZoomState zs = ci.getZoomState().getValue();
                if (zs != null) {
                    obj.put("zoom_min", String.format(Locale.US, "%.3f", zs.getMinZoomRatio()));
                    obj.put("zoom_max", String.format(Locale.US, "%.3f", zs.getMaxZoomRatio()));
                    obj.put("zoom_current", String.format(Locale.US, "%.3f", zs.getZoomRatio()));
                }
            } catch (Exception e) {
                obj.put("error", e.getMessage());
            }
            infoArr.put(obj);
        }
        root.put("available_camera_info_count", allInfos.size());
        root.put("camera_infos", infoArr);
        return root;
    }

    // ==================== ZoomState 实测（主线程） ====================

    private JSONObject dumpZoomState(PreviewView previewView) throws Exception {
        JSONObject root = new JSONObject();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        ProcessCameraProvider provider = future.get();

        Preview preview = new Preview.Builder().build();
        if (previewView != null) {
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
        }
        Camera camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview);

        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        if (zs != null) {
            root.put("min_zoom_ratio", String.format(Locale.US, "%.3f", zs.getMinZoomRatio()));
            root.put("max_zoom_ratio", String.format(Locale.US, "%.3f", zs.getMaxZoomRatio()));
            root.put("current_zoom_ratio", String.format(Locale.US, "%.3f", zs.getZoomRatio()));
            root.put("has_zoom_under_1_0", zs.getMinZoomRatio() < 1.0f);
        } else {
            root.put("error", "ZoomState is null");
        }

        provider.unbindAll();
        return root;
    }

    // ==================== 绑定测试（主线程） ====================

    private JSONObject runBindingTest(PreviewView previewView) throws Exception {
        JSONObject root = new JSONObject();
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            root.put("error", "CameraManager null");
            return root;
        }

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        ProcessCameraProvider provider = future.get();
        Preview preview = new Preview.Builder().build();
        if (previewView != null) {
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
        }

        JSONArray tests = new JSONArray();
        String[] ids = cm.getCameraIdList();

        for (String id : ids) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;

            JSONObject test = new JSONObject();
            test.put("target_id", id);

            final String targetId = id;
            CameraSelector selector = new CameraSelector.Builder()
                    .addCameraFilter(cameraInfos -> {
                        List<CameraInfo> result = new ArrayList<>();
                        for (CameraInfo ci : cameraInfos) {
                            try {
                                Camera2CameraInfo c2 = Camera2CameraInfo.from(ci);
                                if (targetId.equals(c2.getCameraId())) {
                                    result.add(ci);
                                    break;
                                }
                            } catch (Exception e) {
                                LogUtil.w(TAG, "Camera2 信息转换异常", e);
                            }
                        }
                        return result;
                    })
                    .build();

            try {
                Camera bound = provider.bindToLifecycle(lifecycleOwner, selector, preview);
                test.put("bind_result", "SUCCESS");

                ZoomState zs = bound.getCameraInfo().getZoomState().getValue();
                if (zs != null) {
                    test.put("bound_min_zoom", String.format(Locale.US, "%.3f", zs.getMinZoomRatio()));
                    test.put("bound_max_zoom", String.format(Locale.US, "%.3f", zs.getMaxZoomRatio()));
                }
                provider.unbindAll();
            } catch (IllegalArgumentException e) {
                test.put("bind_result", "FAILED_IllegalArgumentException");
                test.put("error_msg", e.getMessage());
            } catch (Exception e) {
                test.put("bind_result", "FAILED_" + e.getClass().getSimpleName());
                test.put("error_msg", e.getMessage());
            }
            tests.put(test);
        }

        root.put("binding_tests", tests);
        provider.unbindAll();
        return root;
    }

    // ==================== 工具方法 ====================

    private boolean isPhysicalChildCamera(CameraManager cm, String cameraId) {
        try {
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
                    if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Set<String> physicalIds = otherChars.getPhysicalCameraIds();
                        if (physicalIds != null && physicalIds.contains(cameraId)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "判断 physical child 异常", e);
        }
        return false;
    }

    private File writeJsonToFile(JSONObject root) throws Exception {
        File dir = new File(context.getExternalFilesDir(null), "CameraDebug");
        if (!dir.exists()) dir.mkdirs();

        String name = "camera_debug_" + System.currentTimeMillis() + ".json";
        File file = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        return file;
    }

    private static String facingName(Integer facing) {
        if (facing == null) return "null";
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK: return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "EXTERNAL";
            default: return "UNKNOWN(" + facing + ")";
        }
    }

    private static String capabilityName(int cap) {
        switch (cap) {
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE: return "BACKWARD_COMPATIBLE";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: return "MANUAL_SENSOR";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: return "MANUAL_POST_PROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW: return "RAW";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING: return "PRIVATE_REPROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS: return "READ_SENSOR_SETTINGS";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: return "BURST_CAPTURE";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING: return "YUV_REPROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT: return "DEPTH_OUTPUT";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO: return "CONSTRAINED_HIGH_SPEED_VIDEO";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING: return "MOTION_TRACKING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: return "LOGICAL_MULTI_CAMERA";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME: return "MONOCHROME";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA: return "SECURE_IMAGE_DATA";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA: return "SYSTEM_CAMERA";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING: return "OFFLINE_PROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR: return "ULTRA_HIGH_RESOLUTION_SENSOR";
            default: return "UNKNOWN(" + cap + ")";
        }
    }
}