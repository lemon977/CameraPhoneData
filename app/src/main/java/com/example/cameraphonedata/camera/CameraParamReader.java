package com.example.cameraphonedata.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.SizeF;

import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;

import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.utils.LogUtil;

import java.util.Arrays;
import java.util.Locale;

/**
 * 相机参数读取器 - 三级降级策略版
 * <p>
 * Level 1: 读取系统工厂标定参数（LENS_INTRINSIC_CALIBRATION + LENS_DISTORTION）
 * Level 2: 传感器物理参数估算（焦距 + 传感器尺寸 → fx/fy/cx/cy）
 * Level 3: 人工棋盘格标定（高精度，可选）
 */
public class CameraParamReader {
    private static final String TAG = "CameraParamReader";
    public static final int DISTORTION_DIM = 8;

    /**
     * 参数来源类型
     */
    public enum ParamSource {
        NONE("未获取", 0),
        SYSTEM_FACTORY("系统工厂参数", 1),
        SENSOR_ESTIMATE("传感器估算", 2),
        MANUAL_CALIBRATION("人工标定", 3);

        public final String displayName;
        public final int priority; // 精度优先级，越高越准

        ParamSource(String displayName, int priority) {
            this.displayName = displayName;
            this.priority = priority;
        }
    }

    public static class CameraParams {
        public float fx, fy, cx, cy;
        /** 8维畸变系数：k1,k2,p1,p2,k3,k4,k5,k6 */
        public float[] distortion = new float[DISTORTION_DIM];
        public boolean hasIntrinsics = false;
        public boolean hasDistortion = false;
        public int videoWidth, videoHeight;
        public float maxZoom = 1.0f;
        public String cameraId;

        // 物理参数（用于估算）
        public float focalLengthMm = 0f;      // 焦距(mm)
        public float sensorWidthMm = 0f;      // 传感器宽度(mm)
        public float sensorHeightMm = 0f;     // 传感器高度(mm)

        // 来源标记
        public ParamSource source = ParamSource.NONE;
        public double rmsError = 999.0;       // 标定误差（如果有）
        public String calibrationDate = "";    // 标定日期

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.CHINA, "参数来源: %s\n", source.displayName));
            sb.append(String.format(Locale.CHINA, "分辨率: %dx%d\n", videoWidth, videoHeight));

            if (hasIntrinsics) {
                sb.append(String.format(Locale.CHINA, "\n内参:\nfx=%.2f, fy=%.2f\ncx=%.2f, cy=%.2f\n",
                        fx, fy, cx, cy));
            }

            if (hasDistortion) {
                sb.append(String.format(Locale.CHINA, "\n畸变8维:\n"));
                sb.append(String.format(Locale.CHINA, "k1=%.4f, k2=%.4f, p1=%.4f, p2=%.4f\n",
                        distortion[0], distortion[1], distortion[2], distortion[3]));
                sb.append(String.format(Locale.CHINA, "k3=%.4f, k4=%.4f, k5=%.4f, k6=%.4f\n",
                        distortion[4], distortion[5], distortion[6], distortion[7]));
            } else {
                sb.append("\n畸变: 未获取（使用零畸变）\n");
            }

            if (source == ParamSource.MANUAL_CALIBRATION) {
                sb.append(String.format(Locale.CHINA, "\n标定误差: %.3f px\n日期: %s",
                        rmsError, calibrationDate));
            } else if (source == ParamSource.SENSOR_ESTIMATE) {
                sb.append(String.format(Locale.CHINA, "\n估算依据:\n焦距=%.1fmm, 传感器=%.1fx%.1fmm",
                        focalLengthMm, sensorWidthMm, sensorHeightMm));
            }

            sb.append(String.format(Locale.CHINA, "\n最大变焦: %.1fx", maxZoom));
            return sb.toString();
        }


    }

    /**
     * 主入口：三级策略获取参数
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public CameraParams readParams(Context context, Camera camera, int targetW, int targetH) {
        CameraParams params = new CameraParams();
        params.videoWidth = targetW;
        params.videoHeight = targetH;

        try {
            Camera2CameraInfo camera2CameraInfo = Camera2CameraInfo.from(camera.getCameraInfo());
            params.cameraId = camera2CameraInfo.getCameraId();

            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                LogUtil.w(TAG, "CameraManager is null");
                return params;
            }

            CameraCharacteristics chars = cm.getCameraCharacteristics(params.cameraId);

            // ========== Level 1: 读取工厂标定参数 ==========
            boolean gotFactory = readFactoryParams(chars, params);
            if (gotFactory) {
                params.source = ParamSource.SYSTEM_FACTORY;
                params.hasIntrinsics = true;
                params.hasDistortion = true;
                LogUtil.i(TAG, "✅ 读取到系统工厂标定参数");
            } else {
                LogUtil.w(TAG, "⚠️ 系统工厂参数不可用，尝试传感器估算");

                // ========== Level 2: 传感器物理参数估算 ==========
                boolean gotEstimate = estimateFromSensor(chars, params, targetW, targetH);
                if (gotEstimate) {
                    params.source = ParamSource.SENSOR_ESTIMATE;
                    params.hasIntrinsics = true;
                    // 估算无法得到畸变，默认零畸变
                    params.distortion = new float[DISTORTION_DIM];
                    params.hasDistortion = false;
                    LogUtil.i(TAG, "✅ 通过传感器参数估算内参");
                } else {
                    LogUtil.e(TAG, "❌ 无法获取任何参数，需要人工标定");
                    params.source = ParamSource.NONE;
                }
            }

            // 读取最大变焦（通用）
            Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxZoom != null && maxZoom > 1.0f) {
                params.maxZoom = maxZoom;
            }

            // 读取支持的流配置（调试用）
            android.hardware.camera2.params.StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                android.util.Size[] videoSizes = map.getOutputSizes(android.media.MediaRecorder.class);
                if (videoSizes != null) {
                    LogUtil.d(TAG, "支持的视频分辨率: " + videoSizes.length + " 种");
                }
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "读取相机参数异常: " + e.getMessage());
        }

        return params;
    }

    /**
     * Level 1: 读取系统工厂标定参数
     * @return 是否成功读取到内参
     */
    private boolean readFactoryParams(CameraCharacteristics chars, CameraParams params) {
        boolean gotIntrinsics = false;
        boolean gotDistortion = false;

        try {
            // API 28+ (Android 9.0+) 才能读取工厂标定参数
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 读取内参矩阵 [fx, fy, cx, cy, s]
                float[] intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
                if (intrinsics != null && intrinsics.length >= 4) {
                    params.fx = intrinsics[0];
                    params.fy = intrinsics[1];
                    params.cx = intrinsics[2];
                    params.cy = intrinsics[3];
                    gotIntrinsics = true;
                    LogUtil.i(TAG, String.format(Locale.US, "工厂内参: fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f",
                            params.fx, params.fy, params.cx, params.cy));
                }

                // 读取畸变系数 [k1, k2, k3, k4, k5, k6, p1, p2]
                // Android 10+ 使用 LENS_DISTORTION (Brown-Conrady)
                float[] distortion = chars.get(CameraCharacteristics.LENS_DISTORTION);
                if (distortion != null && distortion.length >= 5) {
                    // 映射到8维模型 [k1, k2, p1, p2, k3, k4, k5, k6]
                    params.distortion = new float[DISTORTION_DIM];
                    params.distortion[0] = distortion[0]; // k1
                    params.distortion[1] = distortion[1]; // k2
                    params.distortion[2] = distortion.length > 6 ? distortion[6] : 0; // p1
                    params.distortion[3] = distortion.length > 7 ? distortion[7] : 0; // p2
                    params.distortion[4] = distortion[2];  // k3
                    params.distortion[5] = distortion.length > 3 ? distortion[3] : 0; // k4
                    params.distortion[6] = distortion.length > 4 ? distortion[4] : 0; // k5
                    params.distortion[7] = distortion.length > 5 ? distortion[5] : 0; // k6
                    gotDistortion = true;
                    LogUtil.i(TAG, "工厂畸变8维: " + Arrays.toString(params.distortion));
                }
            } else {
                LogUtil.w(TAG, "API < 28，无法读取 LENS_INTRINSIC_CALIBRATION，尝试旧版API");
            }

            // API 23+ (Android 6.0+) 尝试旧版 LENS_RADIAL_DISTORTION
            if (!gotDistortion && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                float[] radialDistortion = chars.get(CameraCharacteristics.LENS_RADIAL_DISTORTION);
                if (radialDistortion != null && radialDistortion.length >= 3) {
                    params.distortion = new float[DISTORTION_DIM];
                    params.distortion[0] = radialDistortion[0]; // k1
                    params.distortion[1] = radialDistortion[1]; // k2
                    params.distortion[2] = 0; // p1
                    params.distortion[3] = 0; // p2
                    params.distortion[4] = radialDistortion[2]; // k3
                    // k4,k5,k6保持0
                    gotDistortion = true;
                    LogUtil.i(TAG, "旧版径向畸变8维: " + Arrays.toString(params.distortion));
                }
            }

        } catch (Exception e) {
            LogUtil.w(TAG, "读取工厂参数异常: " + e.getMessage());
        }

        return gotIntrinsics;
    }

    /**
     * Level 2: 通过传感器物理参数估算内参
     * 公式: fx = 焦距(mm) × 图像宽度(px) / 传感器宽度(mm)
     * 【兼容性】考虑 SENSOR_ORIENTATION：若 sensor 自然方向与 display 不同，需交换 width/height。
     */
    private boolean estimateFromSensor(CameraCharacteristics chars, CameraParams params,
                                       int imageWidth, int imageHeight) {
        try {
            // 读取焦距（mm）
            float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focalLengths != null && focalLengths.length > 0) {
                params.focalLengthMm = focalLengths[0];
            } else {
                params.focalLengthMm = 4.5f;
                LogUtil.w(TAG, "无法读取焦距，使用默认值 4.5mm");
            }

            // 焦距 Sanity Check：若 >20mm，可能是 35mm 等效焦距
            if (params.focalLengthMm > 20.0f) {
                float old = params.focalLengthMm;
                params.focalLengthMm = params.focalLengthMm / 6.0f; // 近似 crop factor
                LogUtil.w(TAG, "焦距 Sanity Check: " + old + "mm 疑似 35mm 等效，修正为 "
                        + String.format(Locale.US, "%.2f", params.focalLengthMm) + "mm");
            }
            if (params.focalLengthMm <= 0) {
                params.focalLengthMm = 4.5f;
                LogUtil.w(TAG, "焦距非法(<=0)，使用默认值 4.5mm");
            }

            // 读取传感器物理尺寸（mm）
            SizeF sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (sensorSize != null) {
                params.sensorWidthMm = sensorSize.getWidth();
                params.sensorHeightMm = sensorSize.getHeight();
            } else {
                params.sensorWidthMm = 5.76f;
                params.sensorHeightMm = 4.29f;
                LogUtil.w(TAG, "无法读取传感器尺寸，使用默认值 5.76×4.29mm");
            }

            // 传感器尺寸 Sanity Check
            if (params.sensorWidthMm <= 0.1f || params.sensorWidthMm > 50.0f
                    || params.sensorHeightMm <= 0.1f || params.sensorHeightMm > 50.0f) {
                LogUtil.w(TAG, "传感器尺寸异常: " + params.sensorWidthMm + "x" + params.sensorHeightMm
                        + "，使用默认值 5.76×4.29mm");
                params.sensorWidthMm = 5.76f;
                params.sensorHeightMm = 4.29f;
            }

            // 读取 SENSOR_ORIENTATION（影响内参计算时的宽高使用）
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int orientation = sensorOrientation != null ? sensorOrientation : 90;
            // 若 sensor 为 90° 或 270°，物理宽度和高度与视频分辨率可能对应关系不同
            // 保守策略：使用传入的 imageWidth/imageHeight 直接计算，但记录 orientation 供后续参考
            LogUtil.i(TAG, "SENSOR_ORIENTATION=" + orientation + "°, video=" + imageWidth + "x" + imageHeight);

            // 估算内参
            if (params.focalLengthMm > 0 && params.sensorWidthMm > 0) {
                params.fx = params.focalLengthMm * imageWidth / params.sensorWidthMm;
                params.fy = params.focalLengthMm * imageHeight / params.sensorHeightMm;
                params.cx = imageWidth / 2.0f;
                params.cy = imageHeight / 2.0f;

                // 内参 Sanity Check：fx/fy 应在合理范围内（如 100~20000）
                if (params.fx < 100 || params.fx > 20000 || params.fy < 100 || params.fy > 20000) {
                    LogUtil.w(TAG, "估算内参异常: fx=" + params.fx + ", fy=" + params.fy
                            + ", 可能焦距或传感器尺寸有误");
                }

                LogUtil.i(TAG, String.format(Locale.US,
                        "估算内参: fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f (焦距=%.1fmm, 传感器=%.2fx%.2fmm)",
                        params.fx, params.fy, params.cx, params.cy,
                        params.focalLengthMm, params.sensorWidthMm, params.sensorHeightMm));
                return true;
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "传感器估算失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 融合人工标定参数（覆盖自动获取的参数）
     * 修复：如果标定分辨率与当前视频分辨率不同，自动等比缩放 fx/fy/cx/cy
     */
    public static void mergeCalibrationParams(CameraParams params,
                                              CalibrationData.CalibrationResult calib) {
        if (params == null || calib == null || calib.fx <= 0) return;

        // 畸变系数与分辨率无关，直接复制（8维）
        params.distortion = calib.distortion != null ? calib.distortion.clone() : new float[DISTORTION_DIM];
        params.hasDistortion = true;
        params.source = ParamSource.MANUAL_CALIBRATION;
        params.rmsError = calib.rmsError;
        params.calibrationDate = calib.calibrationDate;

        // 分辨率适配：标定分辨率 vs 当前视频分辨率
        int cw = calib.imageWidth;
        int ch = calib.imageHeight;
        int vw = params.videoWidth;
        int vh = params.videoHeight;

        if (cw > 0 && ch > 0 && vw > 0 && vh > 0 && (cw != vw || ch != vh)) {
            float scaleX = (float) vw / cw;
            float scaleY = (float) vh / ch;
            params.fx = calib.fx * scaleX;
            params.fy = calib.fy * scaleY;
            params.cx = calib.cx * scaleX;
            params.cy = calib.cy * scaleY;
            LogUtil.i(TAG, String.format(Locale.US,
                    "标定分辨率适配: %dx%d -> %dx%d, scale=(%.3f, %.3f)",
                    cw, ch, vw, vh, scaleX, scaleY));
        } else {
            params.fx = calib.fx;
            params.fy = calib.fy;
            params.cx = calib.cx;
            params.cy = calib.cy;
        }

        LogUtil.i(TAG, "已融合人工标定参数，误差=" + calib.rmsError);
    }

    /**
     * 判断参数是否可用（用于录制前检查）
     * 【防呆】增加内参合理性校验：fx/fy 必须在合理范围内，否则即使 >0 也可能因异常值导致后端计算错误。
     */
    public static boolean isParamsUsable(CameraParams params) {
        if (params == null) return false;
        if (params.source == ParamSource.NONE) return false;
        if (!params.hasIntrinsics) return false;
        if (params.fx <= 0 || params.fy <= 0) return false;
        // 合理性：fx/fy 应在 100~20000 之间，超出此范围很可能是异常值
        if (params.fx < 100 || params.fx > 20000) return false;
        if (params.fy < 100 || params.fy > 20000) return false;
        return true;
    }
}