package com.example.cameraphonedata.utils;

import android.util.Log;
import com.example.cameraphonedata.BuildConfig;

/**
 * 日志工具类 —— 统一格式化、线程安全、关键路径可追溯。
 * 【工程师必读】
 * 1. 所有模块必须使用本类输出日志，禁止直接调用 android.util.Log。
 * 2. 格式：[TAG][线程名] 消息。便于多线程问题排查。
 * 3. DEBUG 级别日志在 release 包自动屏蔽，不影响性能。
 * 4. 异常日志务必附带 Throwable，保留完整堆栈。
 */
public class LogUtil {
    private static final String GLOBAL_TAG = "CameraApp";

    private static String threadPrefix() {
        return "[" + Thread.currentThread().getName() + "] ";
    }

    public static void d(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg);
        }
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (BuildConfig.DEBUG) {
            Log.d(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg, tr);
        }
    }

    public static void i(String tag, String msg) {
        Log.i(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg);
    }

    public static void i(String tag, String msg, Throwable tr) {
        Log.i(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg, tr);
    }

    public static void w(String tag, String msg) {
        Log.w(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        Log.w(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg, tr);
    }

    public static void e(String tag, String msg) {
        Log.e(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(GLOBAL_TAG, "[" + tag + "]" + threadPrefix() + msg, tr);
    }

    /**
     * 格式化日志：自动拼接多个字段，避免字符串拼接开销（DEBUG 下不执行）。
     */
    public static void d(String tag, Object... parts) {
        if (BuildConfig.DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(tag).append("]").append(threadPrefix());
            for (Object p : parts) sb.append(p);
            Log.d(GLOBAL_TAG, sb.toString());
        }
    }
}