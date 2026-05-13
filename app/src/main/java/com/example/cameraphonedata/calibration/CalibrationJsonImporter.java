package com.example.cameraphonedata.calibration;

import android.content.Context;
import android.util.Log;

import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.config.CameraConfig;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标定 JSON 外部文件导入器
 * 【修复】适配 LensRole 主键体系，文件名格式同步为 calib_{phoneId}_{roleTag}_{w}x{h}.json
 */
public class CalibrationJsonImporter {
    private static final String TAG = "CalibJsonImport";

    public static void importFromJson(Context context) {
        File extDir = context.getExternalFilesDir(null);
        if (extDir == null || !extDir.isDirectory()) return;

        File[] files = extDir.listFiles();
        if (files == null) return;

        CalibrationData calibData = new CalibrationData(context);
        // 【修复】文件名格式：calib_{phoneId}_{roleTag}_{actualWidth}x{actualHeight}.json
        // roleTag 示例：wide / ultra_wide
        Pattern pattern = Pattern.compile("^calib_(.+?)_([a-z_]+)_(\\d+)x(\\d+)\\.json$");

        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;

            Matcher m = pattern.matcher(f.getName());
            if (!m.matches()) continue;

            // 【修复】消除 "might be null" 警告：正则匹配后强制判空
            String phoneId = m.group(1);
            String roleStr = m.group(2);
            String widthStr = m.group(3);
            String heightStr = m.group(4);
            if (phoneId == null || roleStr == null || widthStr == null || heightStr == null) continue;

            // 解析 LensRole（文件名是小写，枚举是大写）
            CameraConfig.LensRole lensRole;
            try {
                lensRole = CameraConfig.LensRole.valueOf(roleStr.toUpperCase());
            } catch (Exception e) {
                Log.w(TAG, "未知镜头角色: " + roleStr + ", 跳过: " + f.getName());
                continue;
            }

            int fileWidth = Integer.parseInt(widthStr);
            int fileHeight = Integer.parseInt(heightStr);

            // 如果已存在手动标定数据，跳过（不覆盖）
            CalibrationData.CalibrationResult existing = calibData.getCalibration(lensRole, fileWidth, fileHeight);
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
                result.distortion[0] = (float) dist.optDouble("k1", 0.0);
                result.distortion[1] = (float) dist.optDouble("k2", 0.0);
                result.distortion[2] = (float) dist.optDouble("p1", 0.0);
                result.distortion[3] = (float) dist.optDouble("p2", 0.0);
                result.distortion[4] = (float) dist.optDouble("k3", 0.0);
                result.distortion[5] = (float) dist.optDouble("k4", 0.0);
                result.distortion[6] = (float) dist.optDouble("k5", 0.0);
                result.distortion[7] = (float) dist.optDouble("k6", 0.0);

                result.rmsError = obj.getDouble("rms_error");
                result.calibrationDate = obj.optString("calibration_date", "");
                result.photosUsed = obj.optInt("photos_used", 0);
                result.source = CameraParamReader.ParamSource.MANUAL_CALIBRATION;
                result.hasDistortion = true;
                // 【关键】保存键统一用 target_resolution
                result.imageWidth = targetW;
                result.imageHeight = targetH;
                // zoomLevel 仅作记录，JSON 中无该字段时默认 1.0
                result.zoomLevel = (float) obj.optDouble("zoom_level", 1.0);
                result.lensRole = lensRole;

                if (!isFinite(result.fx) || !isFinite(result.fy) || !isFinite(result.cx) || !isFinite(result.cy)
                        || result.fx <= 0 || result.fy <= 0 || targetW <= 0 || targetH <= 0) {
                    Log.w(TAG, "JSON 内参非法，跳过: " + f.getName());
                    continue;
                }
                if (!Double.isFinite(result.rmsError)) {
                    result.rmsError = 999.0;
                }

                // 【修复】使用 LensRole 主键保存
                calibData.saveCalibration(result, phoneId, lensRole, targetW, targetH);
                Log.i(TAG, "已从 JSON 导入标定数据: " + f.getName()
                        + " -> 键=" + lensRole.name() + "/" + targetW + "x" + targetH);

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

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}