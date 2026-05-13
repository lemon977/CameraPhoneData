package com.example.cameraphonedata.calibration;

import android.util.Log;

import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.config.CameraConfig;

/**
 * 去畸变工具类（预留，供数据工程师参考）
 * 提供基于标定参数的正确去畸变方法
 */
public class CalibrationUtil {
    private static final String TAG = "CalibrationUtil";
    public static final int DISTORTION_DIM = 8;

    /**
     * 基础去畸变（推荐）
     * 不调整视野，最不容易出错。
     * 注意：params 必须是已经按当前录制分辨率缩放过的参数！
     */
    public static void undistort(org.opencv.core.Mat src, org.opencv.core.Mat dst,
                                 CalibrationData.CalibrationResult params) {
        if (params == null) {
            Log.e(TAG, "标定参数为null，跳过去畸变");
            src.copyTo(dst);
            return;
        }

        org.opencv.core.Mat cameraMatrix = org.opencv.core.Mat.eye(3, 3, org.opencv.core.CvType.CV_64F);
        cameraMatrix.put(0, 0, params.fx);
        cameraMatrix.put(1, 1, params.fy);
        cameraMatrix.put(0, 2, params.cx);
        cameraMatrix.put(1, 2, params.cy);

        org.opencv.core.Mat distCoeffs = new org.opencv.core.Mat(DISTORTION_DIM, 1, org.opencv.core.CvType.CV_64F);
        for (int i = 0; i < DISTORTION_DIM; i++) {
            distCoeffs.put(i, 0, params.distortion[i]);
        }

        // 最基础的去畸变，不调整视野，不引入额外黑边
        org.opencv.calib3d.Calib3d.undistort(src, dst, cameraMatrix, distCoeffs);

        cameraMatrix.release();
        distCoeffs.release();
    }

    /**
     * 获取已按目标分辨率缩放好标定参数（双维度精确匹配）
     * 在MainActivity录制前调用，确保内参与当前分辨率和镜头角色匹配
     *
     * @param calibData    CalibrationData实例
     * @param lensRole     当前镜头角色（如 WIDE / ULTRA_WIDE）
     * @param recordWidth  实际录制宽度
     * @param recordHeight 实际录制高度
     */
    public static CalibrationData.CalibrationResult getParamsForRecording(
            CalibrationData calibData, CameraConfig.LensRole lensRole, int recordWidth, int recordHeight) {

        // 【修复】使用 LensRole 主键查询
        CalibrationData.CalibrationResult result =
                calibData.getCalibrationForUse(lensRole, recordWidth, recordHeight);

        if (result == null) {
            Log.e(TAG, String.format("未找到 %s / %dx%d 的标定参数，请先标定",
                    lensRole.name(), recordWidth, recordHeight));
        } else {
            Log.i(TAG, String.format("使用 %s / %dx%d 标定参数，fx=%.2f, fy=%.2f",
                    result.lensRole.name(), result.imageWidth, result.imageHeight, result.fx, result.fy));
        }
        return result;
    }
}