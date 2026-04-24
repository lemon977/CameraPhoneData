package com.example.cameraphonedata.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import com.example.cameraphonedata.camera.CameraParamReader;
import java.util.Locale;

/**
 * 标定数据持久化 - 严格双维度（zoom + resolution），删除旧版兼容回退
 * 畸变系数统一使用8维：k1,k2,p1,p2,k3,k4,k5,k6（不足补0）
 */
public class CalibrationData {
    private static final String TAG = "CalibrationData";
    private static final String PREFS_NAME = "CameraCalibration";
    private static final String KEY_ZOOM_LIST = "calibrated_zoom_list_v2";

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

    /** 畸变系数维度（统一8维，兼容未来高阶模型） */
    public static final int DISTORTION_DIM = 8;

    public static class CalibrationResult {
        public float fx, fy, cx, cy;
        /** 8维畸变系数：k1,k2,p1,p2,k3,k4,k5,k6 */
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
    }

    private final Context context;

    public CalibrationData(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 双维度键：zoom + 分辨率 */
    private SharedPreferences getPrefs(float zoomLevel, int width, int height) {
        String name = PREFS_NAME + "_" + zoomKey(zoomLevel) + "_" + width + "x" + height;
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private String zoomKey(float zoomLevel) {
        return String.format(Locale.US, "z%.2f", zoomLevel);
    }

    private void updateZoomList(float zoomLevel, int width, int height) {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String zoomListStr = defaultPrefs.getString(KEY_ZOOM_LIST, "");
        String key = String.format(Locale.US, "%.2f_%dx%d", zoomLevel, width, height);
        if (zoomListStr.isEmpty()) {
            zoomListStr = key;
        } else if (!zoomListStr.contains(key)) {
            zoomListStr = zoomListStr + "," + key;
        }
        defaultPrefs.edit().putString(KEY_ZOOM_LIST, zoomListStr).apply();
    }

    public boolean hasAnyParams() {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String zoomListStr = defaultPrefs.getString(KEY_ZOOM_LIST, "");
        return !zoomListStr.isEmpty();
    }

    /**
     * 精确匹配双维度查询
     */
    public boolean isCalibrated(float zoomLevel, int width, int height) {
        CalibrationResult r = getCalibration(zoomLevel, width, height);
        if (r == null) return false;
        return r.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION
                && r.rmsError < CalibrationConfig.getInstance().maxRmsError;
    }

    /** 兼容旧代码：默认检查1.0x_1920x1080 */
    public boolean isCalibrated() {
        return isCalibrated(1.0f, 1920, 1080);
    }

    public CameraParamReader.ParamSource getParamSource(float zoomLevel, int width, int height) {
        String sourceStr = getPrefs(zoomLevel, width, height).getString(KEY_SOURCE,
                CameraParamReader.ParamSource.NONE.name());
        try {
            return CameraParamReader.ParamSource.valueOf(sourceStr);
        } catch (Exception e) {
            return CameraParamReader.ParamSource.NONE;
        }
    }

    public int getCalibrationCount(float zoomLevel, int width, int height) {
        return getPrefs(zoomLevel, width, height).getInt(KEY_CALIB_COUNT, 0);
    }

    /**
     * 严格双维度查询：只查 (zoom, width, height)，不再回退旧版单维度
     */
    public CalibrationResult getCalibration(float zoomLevel, int width, int height) {
        SharedPreferences prefs = getPrefs(zoomLevel, width, height);
        if (prefs.contains(KEY_FX)) {
            return loadFromPrefs(prefs);
        }
        return null;
    }

    /** 兼容旧代码 */
    public CalibrationResult getCalibration() {
        return getCalibration(1.0f, 1920, 1080);
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

    public void saveCalibration(CalibrationResult result, String phoneId, float zoomLevel, int width, int height) {
        SharedPreferences prefs = getPrefs(zoomLevel, width, height);
        SharedPreferences.Editor editor = prefs.edit();

        int currentCount = getCalibrationCount(zoomLevel, width, height);
        if (result.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION) {
            currentCount++;
        }
        result.calibrationCount = currentCount;
        result.zoomLevel = zoomLevel;
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
        editor.putFloat(KEY_ZOOM_LEVEL, zoomLevel);

        for (int i = 0; i < DISTORTION_DIM; i++) {
            editor.putFloat(KEY_DIST_PREFIX + i, result.distortion[i]);
        }
        editor.apply();

        updateZoomList(zoomLevel, width, height);
    }

    /** 兼容旧代码 */
    public void saveCalibration(CalibrationResult result, String phoneId) {
        int w = result.imageWidth > 0 ? result.imageWidth : 1920;
        int h = result.imageHeight > 0 ? result.imageHeight : 1080;
        float z = result.zoomLevel > 0 ? result.zoomLevel : 1.0f;
        saveCalibration(result, phoneId, z, w, h);
    }

    /**
     * 获取最适合当前录制场景的标定参数（精确匹配，不再模糊匹配）
     */
    public CalibrationResult getCalibrationForUse(float currentZoom, int targetWidth, int targetHeight) {
        CalibrationResult exact = getCalibration(currentZoom, targetWidth, targetHeight);
        if (exact != null && exact.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION) {
            return exact;
        }
        Log.w(TAG, "未找到 " + currentZoom + "x / " + targetWidth + "x" + targetHeight + " 的精确标定");
        return null;
    }

    public void saveEstimateParams(CameraParamReader.CameraParams params) {
        if (params == null || !params.hasIntrinsics) return;
        if (isCalibrated(1.0f, params.videoWidth, params.videoHeight)) return;

        CalibrationResult r = new CalibrationResult();
        r.fx = params.fx;
        r.fy = params.fy;
        r.cx = params.cx;
        r.cy = params.cy;
        r.distortion = params.distortion != null ? params.distortion : new float[DISTORTION_DIM];
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

        saveCalibration(r, getPhoneId(), 1.0f, params.videoWidth, params.videoHeight);
    }

    public void saveFactoryParams(CameraParamReader.CameraParams params) {
        saveEstimateParams(params);
    }

    public void clearCalibration(float zoomLevel, int width, int height) {
        getPrefs(zoomLevel, width, height).edit().clear().apply();
    }

    public void clearCalibration() {
        clearCalibration(1.0f, 1920, 1080);
    }

    public void clearAllCalibrations() {
        SharedPreferences defaultPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String zoomListStr = defaultPrefs.getString(KEY_ZOOM_LIST, "");
        if (!zoomListStr.isEmpty()) {
            for (String item : zoomListStr.split(",")) {
                try {
                    String[] parts = item.split("_");
                    float z = Float.parseFloat(parts[0]);
                    String[] wh = parts[1].split("x");
                    int w = Integer.parseInt(wh[0]);
                    int h = Integer.parseInt(wh[1]);
                    clearCalibration(z, w, h);
                } catch (Exception ignored) {}
            }
        }
        defaultPrefs.edit().clear().apply();
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

    public String getQualityRating(float zoomLevel, int width, int height) {
        CameraParamReader.ParamSource source = getParamSource(zoomLevel, width, height);
        if (source == CameraParamReader.ParamSource.NONE) return "未获取";
        if (source == CameraParamReader.ParamSource.SYSTEM_FACTORY) return "优秀（工厂参数）";
        if (source == CameraParamReader.ParamSource.SENSOR_ESTIMATE) return "良好（估算）";

        CalibrationResult r = getCalibration(zoomLevel, width, height);
        if (r == null) return "未标定";
        if (r.rmsError < 0.3) return "优秀（标定）";
        if (r.rmsError < 0.5) return "良好（标定）";
        if (r.rmsError < 1.0) return "一般（标定）";
        return "较差（建议重新标定）";
    }

    public String getQualityRating() {
        return getQualityRating(1.0f, 1920, 1080);
    }
}