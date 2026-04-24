package com.example.cameraphonedata.utils;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.File;

/**
 * 存储管理器 - 多级降级 + 异常保护
 * 职责：统一处理存储路径获取、空间检测、格式化，绝不抛异常
 */
public class StorageManager {
    private static final String TAG = "StorageManager";
    private static final long MIN_STORAGE_MB = 2048;
    private static final long FALLBACK_AVAILABLE = 10L * 1024 * 1024 * 1024; // 10GB 兜底

    private final Context context;

    public StorageManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 多级路径获取（降级策略）：
     * 1. 外部私有目录（首选，空间大）
     * 2. 内部私有目录（外部不可用时）
     * 3. 缓存目录（最后手段）
     * 4. null（理论上不会发生，调用方需判断）
     */
    public File getBaseDir(String folderName) {
        File dir;

        // Level 1: 外部存储私有目录
        try {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                dir = context.getExternalFilesDir(null);
                if (dir != null) {
                    File target = new File(dir, folderName);
                    if (ensureDir(target)) return target;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "外部存储不可用", e);
        }

        // Level 2: 内部私有目录
        try {
            dir = context.getFilesDir();
            if (dir != null) {
                File target = new File(dir, folderName);
                if (ensureDir(target)) {
                    Log.i(TAG, "降级到内部存储: " + target.getAbsolutePath());
                    return target;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "内部存储不可用", e);
        }

        // Level 3: 缓存目录（最后手段，空间可能很小）
        try {
            dir = context.getCacheDir();
            if (dir != null) {
                File target = new File(dir, folderName);
                if (ensureDir(target)) {
                    Log.w(TAG, "降级到缓存目录: " + target.getAbsolutePath());
                    return target;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "缓存目录不可用", e);
        }

        Log.e(TAG, "所有存储路径均不可用");
        return null;
    }

    private boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.exists()) return dir.isDirectory();
        return dir.mkdirs();
    }

    /**
     * 获取可用空间（字节），异常时返回兜底值，绝不崩溃
     */
    public long getAvailableBytes(File path) {
        if (path == null) return FALLBACK_AVAILABLE;
        try {
            StatFs stat = new StatFs(path.getPath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            Log.w(TAG, "获取可用空间失败，使用估算值", e);
            return FALLBACK_AVAILABLE;
        }
    }

    /**
     * 获取总空间（字节）
     */
    public long getTotalBytes(File path) {
        if (path == null) return 0;
        try {
            StatFs stat = new StatFs(path.getPath());
            return stat.getTotalBytes();
        } catch (Exception e) {
            Log.w(TAG, "获取总空间失败", e);
            return 0;
        }
    }

    /**
     * 检查存储是否充足
     * @return StorageCheckResult 包含是否可用、剩余MB、路径对象
     */
    public StorageCheckResult checkStorage(File baseDir) {
        if (baseDir == null) {
            return new StorageCheckResult(false, 0, "无可用存储路径");
        }
        long availableBytes = getAvailableBytes(baseDir);
        long availableMB = availableBytes / (1024 * 1024);
        boolean isEnough = availableMB >= MIN_STORAGE_MB;
        String msg = isEnough ? "OK" : "存储不足 (" + availableMB + "MB)";
        return new StorageCheckResult(isEnough, availableMB, msg);
    }

    public static String formatSize(long size) {
        if (size < 0) return "未知";
        if (size < 1024) return size + " B";
        int exp = (int) (Math.log(size) / Math.log(1024));
        if (exp < 1) exp = 1;
        if (exp > 6) exp = 6;
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format(java.util.Locale.US, "%.1f %sB", size / Math.pow(1024, exp), unit);
    }

    public static class StorageCheckResult {
        public final boolean isEnough;
        public final long availableMB;
        public final String message;

        public StorageCheckResult(boolean isEnough, long availableMB, String message) {
            this.isEnough = isEnough;
            this.availableMB = availableMB;
            this.message = message;
        }
    }
}