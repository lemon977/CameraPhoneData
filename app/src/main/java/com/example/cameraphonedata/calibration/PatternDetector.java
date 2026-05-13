package com.example.cameraphonedata.calibration;

import android.util.Log;
import com.example.cameraphonedata.config.CalibrationConfig;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 标定板检测器
 * 优化：多策略检测、超时保护、屏幕反光兼容
 */
public class PatternDetector {
    private static final String TAG = "PatternDetector";

    /**
     * 主入口：检测棋盘格
     */
    public static boolean detectChessboard(Mat gray, CalibrationConfig config, MatOfPoint2f corners) {
        if (gray == null || gray.empty()) {
            Log.e(TAG, "输入图像为null或空");
            return false;
        }

        Size patternSize = new Size(config.chessboardCols, config.chessboardRows);
        long startTime = System.currentTimeMillis();
        int maxDetectionTimeMs = config.maxDetectionTimeMs;

        Mat processed = null;
        boolean found = false;
        try {
            // 策略1: 根据配置选择预处理
            processed = preprocess(gray, config);

            // 策略2: 多模式尝试（从快到慢）
            found = tryMultipleModes(processed, patternSize, corners, startTime, maxDetectionTimeMs);

            // 策略3: 如果失败，尝试缩小图像再检测（提高稳定性）
            if (!found && config.detectionMode >= 1) {
                Log.d(TAG, "首次检测失败，尝试缩小检测");
                Mat resized = new Mat();
                Imgproc.resize(processed, resized, new Size(gray.width() / 2, gray.height() / 2));

                MatOfPoint2f tempCorners = new MatOfPoint2f();
                if (fastDetect(resized, new Size(config.chessboardCols, config.chessboardRows), tempCorners)) {
                    // 将角点坐标映射回原始尺寸
                    org.opencv.core.Point[] points = tempCorners.toArray();
                    for (int i = 0; i < points.length; i++) {
                        points[i].x *= 2;
                        points[i].y *= 2;
                    }
                    corners.fromArray(points);
                    found = true;
                    Log.i(TAG, "缩小检测成功");
                }
                resized.release();
                tempCorners.release();
            }

            // 策略4: 亚像素精化（如果找到）
            if (found && config.useSubPixel) {
                refineCorners(gray, corners, config);
            }
        } finally {
            if (processed != null) {
                processed.release();
            }
        }

        long cost = System.currentTimeMillis() - startTime;
        Log.i(TAG, "检测耗时: " + cost + "ms, 结果: " + found);
        return found;
    }

    /**
     * 尝试多种检测模式（带超时保护）
     */
    private static boolean tryMultipleModes(Mat img, Size patternSize, MatOfPoint2f corners, long startTime, int maxDetectionTimeMs) {
        // 模式1: 快速模式（CALIB_CB_FAST_CHECK）- 适用于好光线
        if (System.currentTimeMillis() - startTime < maxDetectionTimeMs) {
            if (fastDetect(img, patternSize, corners)) {
                Log.d(TAG, "快速模式检测成功");
                return true;
            }
        }

        // 模式2: 标准模式（ADAPTIVE_THRESH + NORMALIZE）
        if (System.currentTimeMillis() - startTime < maxDetectionTimeMs) {
            if (standardDetect(img, patternSize, corners)) {
                Log.d(TAG, "标准模式检测成功");
                return true;
            }
        }

        // 模式3: 增强模式（多种flags组合尝试）
        if (System.currentTimeMillis() - startTime < maxDetectionTimeMs) {
            if (enhancedDetect(img, patternSize, corners)) {
                Log.d(TAG, "增强模式检测成功");
                return true;
            }
        }

        return false;
    }

    // ========== 预处理方法 ==========
    private static Mat preprocess(Mat gray, CalibrationConfig config) {
        Mat result = new Mat();

        // 步骤1: 高斯模糊去噪（如果配置>0且为奇数）
        if (config.gaussianBlurSize > 0 && config.gaussianBlurSize % 2 == 1) {
            Imgproc.GaussianBlur(gray, result,
                    new Size(config.gaussianBlurSize, config.gaussianBlurSize), 0);
        } else {
            gray.copyTo(result);
        }

        // 步骤2: 直方图均衡化（增强对比度）
        if (config.useHistogramEq) {
            Mat eq = new Mat();
            Imgproc.equalizeHist(result, eq);
            result.release();
            result = eq;
        }

        // 步骤3: CLAHE自适应增强（适合屏幕反光/阴影）
        if (config.useClahe) {
            Mat enhanced = new Mat();
            org.opencv.imgproc.CLAHE clahe = Imgproc.createCLAHE(config.claheClipLimit, new Size(8, 8));
            clahe.apply(result, enhanced);
            clahe.collectGarbage();
            result.release();
            result = enhanced;
        }

        return result;
    }

    // ========== 检测策略 ==========

    /**
     * 快速检测 - 适合光线好的环境，速度最快
     */
    private static boolean fastDetect(Mat img, Size patternSize, MatOfPoint2f corners) {
        try {
            return Calib3d.findChessboardCorners(img, patternSize, corners,
                    Calib3d.CALIB_CB_ADAPTIVE_THRESH |
                            Calib3d.CALIB_CB_NORMALIZE_IMAGE |
                            Calib3d.CALIB_CB_FAST_CHECK);
        } catch (Exception e) {
            Log.w(TAG, "快速检测异常", e);
            return false;
        }
    }

    /**
     * 标准检测 - 平衡速度和精度
     */
    private static boolean standardDetect(Mat img, Size patternSize, MatOfPoint2f corners) {
        try {
            return Calib3d.findChessboardCorners(img, patternSize, corners,
                    Calib3d.CALIB_CB_ADAPTIVE_THRESH |
                            Calib3d.CALIB_CB_NORMALIZE_IMAGE);
        } catch (Exception e) {
            Log.w(TAG, "标准检测异常", e);
            return false;
        }
    }

    /**
     * 增强检测 - 多种flags组合尝试，适合困难情况（屏幕反光、低光照）
     */
    private static boolean enhancedDetect(Mat img, Size patternSize, MatOfPoint2f corners) {
        // 尝试多种参数组合，从严格到宽松
        int[][] flagSets = {
                {Calib3d.CALIB_CB_ADAPTIVE_THRESH, Calib3d.CALIB_CB_NORMALIZE_IMAGE, Calib3d.CALIB_CB_FILTER_QUADS},
                {Calib3d.CALIB_CB_ADAPTIVE_THRESH, Calib3d.CALIB_CB_NORMALIZE_IMAGE, Calib3d.CALIB_CB_EXHAUSTIVE},
                {Calib3d.CALIB_CB_ADAPTIVE_THRESH, Calib3d.CALIB_CB_NORMALIZE_IMAGE, Calib3d.CALIB_CB_ACCURACY}
        };

        for (int[] flags : flagSets) {
            try {
                int combinedFlags = 0;
                for (int f : flags) combinedFlags |= f;

                if (Calib3d.findChessboardCorners(img, patternSize, corners, combinedFlags)) {
                    Log.i(TAG, "增强模式成功，flags=" + combinedFlags);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "增强检测某组flags异常", e);
            }
        }
        return false;
    }

    // ========== 角点精化 ==========
    private static void refineCorners(Mat gray, MatOfPoint2f corners, CalibrationConfig config) {
        try {
            // 确保窗口大小是奇数且合理
            int windowSize = config.subPixelWindowSize;
            if (windowSize % 2 == 0) windowSize++; // 转为奇数
            if (windowSize < 3) windowSize = 3;
            if (windowSize > 31) windowSize = 31; // 防止过大

            Imgproc.cornerSubPix(gray, corners,
                    new Size(windowSize, windowSize),
                    new Size(-1, -1), // 无死区
                    new org.opencv.core.TermCriteria(
                            org.opencv.core.TermCriteria.EPS + org.opencv.core.TermCriteria.MAX_ITER,
                            config.maxIterations, config.epsilon));
            Log.d(TAG, "亚像素精化完成");
        } catch (Exception e) {
            Log.w(TAG, "亚像素精化失败", e);
            // 精化失败不影响整体结果，仍返回检测到的角点
        }
    }
}