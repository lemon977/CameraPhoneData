package com.example.cameraphonedata.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.example.cameraphonedata.camera.CameraParamReader;

import java.util.Locale;

/**
 * 标定数据持久化 - 以 LensRole（镜头角色）为主键，支持分辨率模糊匹配。
 * 【版本兼容】
 * - v2（当前）：LensRole + 分辨率主键，支持模糊匹配（高度差≤16px）。
 * - 启动时自动迁移，旧版数据直接清空（项目初期无历史包袱）。
 */
public class CalibrationData {
    private static final String TAG = "CalibrationData";
    private static final String PREFS_NAME = "CameraCalibration";
    private static final String KEY_LENS_LIST = "calibrated_lens_list_v2";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final int CURRENT_DATA_VERSION = 2;

    private static final String KEY_FX = "fx";
    private static final String KEY_FY = "fy";
    private static final String KEY_CX = "cx";
    private static final String KEY_CY = "cy";
    private static final String KEY_DIST_PREFIX = "dist_";
    private static final String KEY_RMS = "rms";
    private static final String KEY_DATE = "date";
    private static final String KEY_PHOTOS = "photos";
    private static final String KEY_PHONE_ID = "phone_id";
    private static final String KEY_SOURCE = "param_source";
    private static final String KEY_HAS_DISTORTION = "has_distortion";
    private static final String KEY_CALIB_COUNT = "calibration_count";
    private static final String KEY_IMG_WIDTH = "img_width";
    private static final String KEY_IMG_HEIGHT = "img_height";
    private static final String KEY_ZOOM_LEVEL = "zoom_level";
    private static final String KEY_LENS_ROLE = "lens_role";

    public static final int DISTORTION_DIM = 8;

    public static class CalibrationResult {
        public float fx, fy, cx, cy;
        public float[] distortion = new float[DISTORTION_DIM];
        public double rmsError;
        public String calibrationDate;
        public int photosUsed;
        public int calibrationCount = 0;
        public CameraParamReader.ParamSource source = CameraParamReader.ParamSource.NONE;
        public boolean hasDistortion = false;
        public int imageWidth = 0;
        public int imageHeight = 0;
        public float zoomLevel = 1.0f;
        public CameraConfig.LensRole lensRole = CameraConfig.LensRole.WIDE;
    }

    private final Context context;

    public CalibrationData(Context context) {
        this.context = context.getApplicationContext();
        migrateIfNeeded();
    }

    private void migrateIfNeeded() {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int oldVersion = defaultPrefs.getInt(KEY_DATA_VERSION, 0);
        if (oldVersion < CURRENT_DATA_VERSION) {
            Log.i(TAG, "标定数据从 v" + oldVersion + " 迁移到 v" + CURRENT_DATA_VERSION);
            if (oldVersion == 0) {
                // 旧版格式完全不同，直接清空最稳妥
                clearAllCalibrations();
            }
            defaultPrefs.edit().putInt(KEY_DATA_VERSION, CURRENT_DATA_VERSION).apply();
        }
    }

    private SharedPreferences getPrefs(CameraConfig.LensRole lensRole, int width, int height) {
        String name = PREFS_NAME + "_" + lensRole.name() + "_" + width + "x" + height;
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private void updateLensList(CameraConfig.LensRole lensRole, int width, int height) {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String listStr = defaultPrefs.getString(KEY_LENS_LIST, "");
        String key = lensRole.name() + "_" + width + "x" + height;
        if (listStr.isEmpty()) {
            listStr = key;
        } else if (!listStr.contains(key)) {
            listStr = listStr + "," + key;
        }
        defaultPrefs.edit().putString(KEY_LENS_LIST, listStr).apply();
    }

    public boolean hasAnyParams() {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String listStr = defaultPrefs.getString(KEY_LENS_LIST, "");
        return !listStr.isEmpty();
    }

    /**
     * 【模糊匹配】先精确，再允许分辨率偏差（高度≤16px，宽度≤64px）。
     * 解决 CameraX 实际输出 1088 与标定目标 1080 不匹配问题。
     */
    public boolean isCalibrated(CameraConfig.LensRole lensRole, int width, int height) {
        CalibrationResult r = getCalibration(lensRole, width, height);
        if (r == null) return false;
        return r.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION
                && r.rmsError < CalibrationConfig.getInstance().maxRmsError;
    }

    public boolean isCalibrated() {
        return isCalibrated(CameraConfig.LensRole.WIDE, 1920, 1080);
    }

    public CameraParamReader.ParamSource getParamSource(CameraConfig.LensRole lensRole, int width, int height) {
        CalibrationResult r = getCalibration(lensRole, width, height);
        if (r == null) return CameraParamReader.ParamSource.NONE;
        return r.source;
    }

    public int getCalibrationCount(CameraConfig.LensRole lensRole, int width, int height) {
        CalibrationResult r = getCalibration(lensRole, width, height);
        if (r == null) return 0;
        return r.calibrationCount;
    }

    /**
     * 对外查询入口：支持模糊匹配。
     */
    public CalibrationResult getCalibration(CameraConfig.LensRole lensRole, int width, int height) {
        // 1. 精确匹配
        CalibrationResult exact = getCalibrationExact(lensRole, width, height);
        if (exact != null) return exact;

        // 2. 模糊匹配
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String listStr = defaultPrefs.getString(KEY_LENS_LIST, "");
        if (listStr.isEmpty()) return null;

        for (String item : listStr.split(",")) {
            try {
                String[] parts = item.split("_");
                if (parts.length < 2) continue;
                CameraConfig.LensRole role = CameraConfig.LensRole.valueOf(parts[0]);
                String[] wh = parts[1].split("x");
                if (wh.length < 2) continue;
                int w = Integer.parseInt(wh[0]);
                int h = Integer.parseInt(wh[1]);

                if (role == lensRole && Math.abs(w - width) <= 64 && Math.abs(h - height) <= 16) {
                    Log.i(TAG, "模糊匹配成功: 请求 " + width + "x" + height + " -> 使用 " + w + "x" + h);
                    return getCalibrationExact(role, w, h);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public CalibrationResult getCalibration() {
        return getCalibration(CameraConfig.LensRole.WIDE, 1920, 1080);
    }

    private CalibrationResult getCalibrationExact(CameraConfig.LensRole lensRole, int width, int height) {
        SharedPreferences prefs = getPrefs(lensRole, width, height);
        if (prefs.contains(KEY_FX)) {
            return loadFromPrefs(prefs);
        }
        return null;
    }

    private CalibrationResult loadFromPrefs(SharedPreferences prefs) {
        CalibrationResult r = new CalibrationResult();
        r.fx = prefs.getFloat(KEY_FX, 0);
        r.fy = prefs.getFloat(KEY_FY, 0);
        r.cx = prefs.getFloat(KEY_CX, 0);
        r.cy = prefs.getFloat(KEY_CY, 0);
        r.rmsError = prefs.getFloat(KEY_RMS, 999f);
        r.calibrationDate = prefs.getString(KEY_DATE, "");
        r.photosUsed = prefs.getInt(KEY_PHOTOS, 0);
        r.hasDistortion = prefs.getBoolean(KEY_HAS_DISTORTION, false);
        r.calibrationCount = prefs.getInt(KEY_CALIB_COUNT, 0);
        r.imageWidth = prefs.getInt(KEY_IMG_WIDTH, 0);
        r.imageHeight = prefs.getInt(KEY_IMG_HEIGHT, 0);
        r.zoomLevel = prefs.getFloat(KEY_ZOOM_LEVEL, 1.0f);

        String roleStr = prefs.getString(KEY_LENS_ROLE, CameraConfig.LensRole.WIDE.name());
        try {
            r.lensRole = CameraConfig.LensRole.valueOf(roleStr);
        } catch (Exception ignored) {
            r.lensRole = CameraConfig.LensRole.WIDE;
        }

        String sourceStr = prefs.getString(KEY_SOURCE, CameraParamReader.ParamSource.NONE.name());
        try {
            r.source = CameraParamReader.ParamSource.valueOf(sourceStr);
        } catch (Exception ignored) {
            r.source = CameraParamReader.ParamSource.NONE;
        }

        for (int i = 0; i < DISTORTION_DIM; i++) {
            r.distortion[i] = prefs.getFloat(KEY_DIST_PREFIX + i, 0);
        }
        return r;
    }

    public void saveCalibration(CalibrationResult result, String phoneId,
                                CameraConfig.LensRole lensRole, int width, int height) {
        SharedPreferences prefs = getPrefs(lensRole, width, height);
        SharedPreferences.Editor editor = prefs.edit();

        int currentCount = getCalibrationCount(lensRole, width, height);
        if (result.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION) {
            currentCount++;
        }
        result.calibrationCount = currentCount;
        result.lensRole = lensRole;
        result.imageWidth = width;
        result.imageHeight = height;

        editor.putFloat(KEY_FX, result.fx);
        editor.putFloat(KEY_FY, result.fy);
        editor.putFloat(KEY_CX, result.cx);
        editor.putFloat(KEY_CY, result.cy);
        editor.putFloat(KEY_RMS, (float) result.rmsError);
        editor.putString(KEY_DATE, result.calibrationDate);
        editor.putInt(KEY_PHOTOS, result.photosUsed);
        editor.putString(KEY_PHONE_ID, phoneId);
        editor.putString(KEY_SOURCE, result.source != null ? result.source.name() :
                CameraParamReader.ParamSource.NONE.name());
        editor.putBoolean(KEY_HAS_DISTORTION, result.hasDistortion);
        editor.putInt(KEY_CALIB_COUNT, currentCount);
        editor.putInt(KEY_IMG_WIDTH, width);
        editor.putInt(KEY_IMG_HEIGHT, height);
        editor.putFloat(KEY_ZOOM_LEVEL, result.zoomLevel);
        editor.putString(KEY_LENS_ROLE, lensRole.name());

        for (int i = 0; i < DISTORTION_DIM; i++) {
            editor.putFloat(KEY_DIST_PREFIX + i, result.distortion[i]);
        }
        editor.apply();

        updateLensList(lensRole, width, height);
    }

    public void saveCalibration(CalibrationResult result, String phoneId) {
        int w = result.imageWidth > 0 ? result.imageWidth : 1920;
        int h = result.imageHeight > 0 ? result.imageHeight : 1080;
        CameraConfig.LensRole role = result.lensRole != null ? result.lensRole : CameraConfig.LensRole.WIDE;
        saveCalibration(result, phoneId, role, w, h);
    }

    public CalibrationResult getCalibrationForUse(CameraConfig.LensRole lensRole, int targetWidth, int targetHeight) {
        CalibrationResult result = getCalibration(lensRole, targetWidth, targetHeight);
        if (result != null && result.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION) {
            return result;
        }
        Log.w(TAG, "未找到 " + lensRole.name() + " / " + targetWidth + "x" + targetHeight + " 的标定");
        return null;
    }

    public void saveEstimateParams(CameraParamReader.CameraParams params) {
        if (params == null || !params.hasIntrinsics) return;
        CameraConfig.LensRole currentRole = CameraConfig.getInstance().currentLensRole;
        if (isCalibrated(currentRole, params.videoWidth, params.videoHeight)) return;

        CalibrationResult r = new CalibrationResult();
        r.fx = params.fx;
        r.fy = params.fy;
        r.cx = params.cx;
        r.cy = params.cy;
        r.distortion = params.distortion != null ? params.distortion.clone() : new float[DISTORTION_DIM];
        r.hasDistortion = params.hasDistortion;
        r.rmsError = 999.0;
        r.calibrationDate = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.CHINA).format(new java.util.Date());
        r.photosUsed = 0;
        r.source = params.source;
        r.calibrationCount = 0;
        r.imageWidth = params.videoWidth;
        r.imageHeight = params.videoHeight;
        r.zoomLevel = 1.0f;
        r.lensRole = currentRole;

        saveCalibration(r, getPhoneId(), currentRole, params.videoWidth, params.videoHeight);
    }

    public void saveFactoryParams(CameraParamReader.CameraParams params) {
        saveEstimateParams(params);
    }

    public void clearCalibration(CameraConfig.LensRole lensRole, int width, int height) {
        getPrefs(lensRole, width, height).edit().clear().apply();
    }

    public void clearCalibration() {
        clearCalibration(CameraConfig.LensRole.WIDE, 1920, 1080);
    }

    public void clearAllCalibrations() {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String listStr = defaultPrefs.getString(KEY_LENS_LIST, "");
        if (!listStr.isEmpty()) {
            for (String item : listStr.split(",")) {
                try {
                    String[] parts = item.split("_");
                    CameraConfig.LensRole role = CameraConfig.LensRole.valueOf(parts[0]);
                    String[] wh = parts[1].split("x");
                    int w = Integer.parseInt(wh[0]);
                    int h = Integer.parseInt(wh[1]);
                    clearCalibration(role, w, h);
                } catch (Exception ignored) {}
            }
        }
        // 【修复】保留版本号和 phone_id，只移除列表
        defaultPrefs.edit().remove(KEY_LENS_LIST).apply();
    }

    public String getPhoneId() {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = defaultPrefs.getString(KEY_PHONE_ID, null);
        if (id == null) {
            id = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) {
                id = "unknown_device_" + System.currentTimeMillis();
            }
            defaultPrefs.edit().putString(KEY_PHONE_ID, id).apply();
        }
        return id;
    }

    public String getQualityRating(CameraConfig.LensRole lensRole, int width, int height) {
        CameraParamReader.ParamSource source = getParamSource(lensRole, width, height);
        if (source == CameraParamReader.ParamSource.NONE) return "未获取";
        if (source == CameraParamReader.ParamSource.SYSTEM_FACTORY) return "优秀（工厂参数）";
        if (source == CameraParamReader.ParamSource.SENSOR_ESTIMATE) return "良好（估算）";

        CalibrationResult r = getCalibration(lensRole, width, height);
        if (r == null) return "未标定";
        if (r.rmsError < 0.3) return "优秀（标定）";
        if (r.rmsError < 0.5) return "良好（标定）";
        if (r.rmsError < 1.0) return "一般（标定）";
        return "较差（建议重新标定）";
    }

    public String getQualityRating() {
        return getQualityRating(CameraConfig.LensRole.WIDE, 1920, 1080);
    }
}