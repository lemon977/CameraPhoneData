package com.example.cameraphonedata.utils;

import android.util.Log;
import com.example.cameraphonedata.BuildConfig;

/**
 * 日志工具类
 * 不依赖 AppConfig，避免配置类编译错误导致连锁反应
 * 新增：支持带焦距和分辨率前缀的日志格式
 */
public class LogUtil {
    private static final String GLOBAL_TAG = "CameraApp";

    public static void d(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(GLOBAL_TAG, "[" + tag + "] " + msg);
        }
    }

    public static void i(String tag, String msg) {
        Log.i(GLOBAL_TAG, "[" + tag + "] " + msg);
    }

    public static void w(String tag, String msg) {
        Log.w(GLOBAL_TAG, "[" + tag + "] " + msg);
    }

    public static void e(String tag, String msg) {
        Log.e(GLOBAL_TAG, "[" + tag + "] " + msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(GLOBAL_TAG, "[" + tag + "] " + msg, tr);
    }

    // ========== 新增：带焦距和分辨率的日志重载 ==========

    public static void d(String tag, String msg, float zoom, int width, int height) {
        if (BuildConfig.DEBUG) {
            Log.d(GLOBAL_TAG, String.format("[%s] [%.1fx] [%dx%d] %s", tag, zoom, width, height, msg));
        }
    }

    public static void i(String tag, String msg, float zoom, int width, int height) {
        Log.i(GLOBAL_TAG, String.format("[%s] [%.1fx] [%dx%d] %s", tag, zoom, width, height, msg));
    }

    public static void w(String tag, String msg, float zoom, int width, int height) {
        Log.w(GLOBAL_TAG, String.format("[%s] [%.1fx] [%dx%d] %s", tag, zoom, width, height, msg));
    }

    public static void e(String tag, String msg, float zoom, int width, int height) {
        Log.e(GLOBAL_TAG, String.format("[%s] [%.1fx] [%dx%d] %s", tag, zoom, width, height, msg));
    }

    public static void e(String tag, String msg, Throwable tr, float zoom, int width, int height) {
        Log.e(GLOBAL_TAG, String.format("[%s] [%.1fx] [%dx%d] %s", tag, zoom, width, height, msg), tr);
    }
}