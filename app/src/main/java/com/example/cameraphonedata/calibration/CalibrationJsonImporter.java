package com.example.cameraphonedata.calibration;

import android.content.Context;
import android.util.Log;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CalibrationData;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标定 JSON 外部文件导入器
 * 【修复】导入时使用 target_resolution 作为保存键，确保与主界面查询一致
 */
public class CalibrationJsonImporter {
    private static final String TAG = "CalibJsonImport";

    public static void importFromJson(Context context) {
        File extDir = context.getExternalFilesDir(null);
        if (extDir == null || !extDir.isDirectory()) return;

        File[] files = extDir.listFiles();
        if (files == null) return;

        CalibrationData calibData = new CalibrationData(context);
        // 文件名格式：calib_{phoneId}_{zoom}x_{actualWidth}x{actualHeight}.json
        Pattern pattern = Pattern.compile("^calib_(.+?)_(\\d+\\.\\d+)x_(\\d+)x(\\d+)\\.json$");

        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;

            Matcher m = pattern.matcher(f.getName());
            if (!m.matches()) continue;

            String phoneId = m.group(1);
            float zoom = Float.parseFloat(m.group(2));
            int fileWidth = Integer.parseInt(m.group(3));
            int fileHeight = Integer.parseInt(m.group(4));

            // 如果已存在手动标定数据，跳过（不覆盖）
            CalibrationData.CalibrationResult existing = calibData.getCalibration(zoom, fileWidth, fileHeight);
            if (existing != null && existing.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION) {
                Log.i(TAG, "已存在标定数据，跳过: " + f.getName());
                continue;
            }

            try {
                String json = readFile(f);
                JSONObject obj = new JSONObject(json);

                // 【修复】读取 target_resolution 作为保存键（与录制配置一致）
                int targetW = fileWidth, targetH = fileHeight;
                if (obj.has("target_resolution")) {
                    JSONObject targetRes = obj.getJSONObject("target_resolution");
                    targetW = targetRes.getInt("width");
                    targetH = targetRes.getInt("height");
                }

                CalibrationData.CalibrationResult result = new CalibrationData.CalibrationResult();

                // camera_matrix 在导出时已按 target_resolution 缩放，直接读取即可
                JSONObject camMatrix = obj.getJSONObject("camera_matrix");
                result.fx = (float) camMatrix.getDouble("fx");
                result.fy = (float) camMatrix.getDouble("fy");
                result.cx = (float) camMatrix.getDouble("cx");
                result.cy = (float) camMatrix.getDouble("cy");

                // 畸变系数与分辨率无关，直接复制
                JSONObject dist = obj.getJSONObject("distortion_coefficients");
                result.distortion = new float[CalibrationData.DISTORTION_DIM];
                result.distortion[0] = (float) dist.getDouble("k1");
                result.distortion[1] = (float) dist.getDouble("k2");
                result.distortion[2] = (float) dist.getDouble("p1");
                result.distortion[3] = (float) dist.getDouble("p2");
                result.distortion[4] = (float) dist.getDouble("k3");
                result.distortion[5] = (float) dist.getDouble("k4");
                result.distortion[6] = (float) dist.getDouble("k5");
                result.distortion[7] = (float) dist.getDouble("k6");

                result.rmsError = obj.getDouble("rms_error");
                result.calibrationDate = obj.optString("calibration_date", "");
                result.photosUsed = obj.optInt("photos_used", 0);
                result.source = CameraParamReader.ParamSource.MANUAL_CALIBRATION;
                result.hasDistortion = true;
                // 【关键】保存键统一用 target_resolution
                result.imageWidth = targetW;
                result.imageHeight = targetH;
                result.zoomLevel = zoom;

                calibData.saveCalibration(result, phoneId, zoom, targetW, targetH);
                Log.i(TAG, "已从 JSON 导入标定数据: " + f.getName() + " -> 键=" + zoom + "x/" + targetW + "x" + targetH);

            } catch (Exception e) {
                Log.e(TAG, "导入 JSON 失败: " + f.getName(), e);
            }
        }
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             Scanner sc = new Scanner(fis)) {
            StringBuilder sb = new StringBuilder();
            while (sc.hasNextLine()) sb.append(sc.nextLine()).append("\n");
            return sb.toString();
        }
    }
}