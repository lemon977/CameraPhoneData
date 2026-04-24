package com.example.cameraphonedata.calibration;

import android.util.Log;
import android.util.Size;
import com.example.cameraphonedata.config.CalibrationConfig;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 标定管理器 —— 强制 5 维 Brown-Conrady 模型（最稳定）。
 *
 * 【修复】calibrationModel 强制为 0（CALIB_FIX_K3），避免 8 维过拟合导致画面扭曲。
 * OpenCV 输出只填充前 4 维（k1,k2,p1,p2），k3 固定为 0，k4/k5/k6 固定为 0。
 */
public class CalibrationManager {
    private static final String TAG = "CalibrationManager";
    public static final int DISTORTION_DIM = 8;

    private final CalibrationConfig config;
    private final List<Mat> objectPointsList = new ArrayList<>();
    private final List<Mat> imagePointsList = new ArrayList<>();
    private int capturedCount = 0;
    private final Object lock = new Object();

    public CalibrationManager(CalibrationConfig config) {
        this.config = config;
    }

    public boolean addCalibrationImage(Mat gray) {
        MatOfPoint2f corners = new MatOfPoint2f();
        boolean found = false;

        try {
            found = PatternDetector.detectChessboard(gray, config, corners);

            if (found) {
                MatOfPoint3f objPoints = createObjectPoints();
                synchronized (lock) {
                    objectPointsList.add(objPoints);
                    imagePointsList.add(corners.clone());
                    capturedCount++;
                }
                Log.i(TAG, "添加成功: " + capturedCount + "/" + config.requiredPhotos);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "检测异常", e);
        } finally {
            if (!found && corners != null) {
                corners.release();
            }
        }
        return false;
    }

    public CalibrationResult calibrate(Size imageSize) {
        synchronized (lock) {
            if (capturedCount < config.requiredPhotos) {
                Log.e(TAG, "照片数量不足: " + capturedCount + " < " + config.requiredPhotos);
                return null;
            }

            Mat cameraMatrix = null;
            Mat distCoeffs = null;
            List<Mat> rvecs = null;
            List<Mat> tvecs = null;

            try {
                cameraMatrix = Mat.eye(3, 3, CvType.CV_64F);
                // 申请 8 维，但 CALIB_FIX_K3 下 OpenCV 只填充前 4 维，k3 强制为 0
                distCoeffs = Mat.zeros(DISTORTION_DIM, 1, CvType.CV_64F);
                rvecs = new ArrayList<>();
                tvecs = new ArrayList<>();

                // 【关键修复】强制使用 CALIB_FIX_K3（5 维），最稳定
                int flags = Calib3d.CALIB_FIX_K3;

                org.opencv.core.Size cvSize = new org.opencv.core.Size(
                        imageSize.getWidth(),
                        imageSize.getHeight()
                );

                Log.i(TAG, "开始标定计算: " + imageSize.getWidth() + "x" + imageSize.getHeight()
                        + ", flags=CALIB_FIX_K3");

                double rms = Calib3d.calibrateCamera(
                        objectPointsList, imagePointsList, cvSize,
                        cameraMatrix, distCoeffs, rvecs, tvecs,
                        flags,
                        new org.opencv.core.TermCriteria(
                                org.opencv.core.TermCriteria.EPS + org.opencv.core.TermCriteria.MAX_ITER,
                                config.maxIterations, config.epsilon)
                );

                double[] fx = new double[1], fy = new double[1],
                        cx = new double[1], cy = new double[1];
                cameraMatrix.get(0, 0, fx);
                cameraMatrix.get(1, 1, fy);
                cameraMatrix.get(0, 2, cx);
                cameraMatrix.get(1, 2, cy);

                // 读取 8 维（OpenCV 只填充前 4 维，后面保持 0）
                double[] dist = new double[DISTORTION_DIM];
                for (int i = 0; i < DISTORTION_DIM; i++) {
                    double[] val = new double[1];
                    if (i < distCoeffs.rows()) {
                        distCoeffs.get(i, 0, val);
                        dist[i] = val[0];
                    } else {
                        dist[i] = 0;
                    }
                }

                CalibrationResult result = new CalibrationResult();
                result.fx = (float) fx[0];
                result.fy = (float) fy[0];
                result.cx = (float) cx[0];
                result.cy = (float) cy[0];
                result.distortion = new float[DISTORTION_DIM];
                for (int i = 0; i < DISTORTION_DIM; i++) {
                    result.distortion[i] = (float) dist[i];
                }
                result.rmsError = rms;
                result.imageWidth = imageSize.getWidth();
                result.imageHeight = imageSize.getHeight();
                result.photosUsed = capturedCount;

                Log.i(TAG, String.format(Locale.US,
                        "标定完成(5维): RMS=%.4f, fx=%.2f, fy=%.2f, 畸变=[%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f]",
                        rms, result.fx, result.fy,
                        result.distortion[0], result.distortion[1], result.distortion[2],
                        result.distortion[3], result.distortion[4], result.distortion[5],
                        result.distortion[6], result.distortion[7]));

                return result;

            } catch (Exception e) {
                Log.e(TAG, "标定计算失败", e);
                return null;
            } finally {
                if (cameraMatrix != null) cameraMatrix.release();
                if (distCoeffs != null) distCoeffs.release();
                if (rvecs != null) {
                    for (Mat m : rvecs) if (m != null) m.release();
                }
                if (tvecs != null) {
                    for (Mat m : tvecs) if (m != null) m.release();
                }
            }
        }
    }

    private MatOfPoint3f createObjectPoints() {
        List<org.opencv.core.Point3> points = new ArrayList<>();
        for (int i = 0; i < config.chessboardRows; i++) {
            for (int j = 0; j < config.chessboardCols; j++) {
                points.add(new org.opencv.core.Point3(
                        j * config.squareSizeMm,
                        i * config.squareSizeMm,
                        0));
            }
        }
        MatOfPoint3f mat = new MatOfPoint3f();
        mat.fromList(points);
        return mat;
    }

    public int getCapturedCount() {
        synchronized (lock) { return capturedCount; }
    }

    public int getRequiredCount() { return config.requiredPhotos; }

    public boolean isComplete() {
        synchronized (lock) { return capturedCount >= config.requiredPhotos; }
    }

    public void reset() {
        synchronized (lock) {
            for (Mat m : imagePointsList) {
                if (m != null) m.release();
            }
            for (Mat m : objectPointsList) {
                if (m != null) m.release();
            }
            imagePointsList.clear();
            objectPointsList.clear();
            capturedCount = 0;
            Log.i(TAG, "已重置");
        }
    }

    public static class CalibrationResult {
        public float fx, fy, cx, cy;
        public float[] distortion;
        public double rmsError;
        public int imageWidth, imageHeight;
        public int photosUsed;

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append(String.format(Locale.US, "  \"fx\": %.4f,\n", fx));
            sb.append(String.format(Locale.US, "  \"fy\": %.4f,\n", fy));
            sb.append(String.format(Locale.US, "  \"cx\": %.4f,\n", cx));
            sb.append(String.format(Locale.US, "  \"cy\": %.4f,\n", cy));
            sb.append(String.format(Locale.US, "  \"distortion\": [%.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f, %.6f],\n",
                    distortion[0], distortion[1], distortion[2], distortion[3],
                    distortion[4], distortion[5], distortion[6], distortion[7]));
            sb.append(String.format(Locale.US, "  \"rms_error\": %.4f,\n", rmsError));
            sb.append(String.format(Locale.US, "  \"image_width\": %d,\n", imageWidth));
            sb.append(String.format(Locale.US, "  \"image_height\": %d,\n", imageHeight));
            sb.append(String.format(Locale.US, "  \"photos_used\": %d\n", photosUsed));
            sb.append("}");
            return sb.toString();
        }
    }
}