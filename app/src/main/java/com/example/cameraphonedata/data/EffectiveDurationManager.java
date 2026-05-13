// ========== EffectiveDurationManager.java（完整代码） ==========
package com.example.cameraphonedata.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.cameraphonedata.utils.LogUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 有效采集时长持久化管理器（毫秒存储，秒级截断显示）
 *
 * 【职责】
 * 1. 按"日期_采集人"为键，累计每日有效时长（long，单位毫秒）。
 * 2. 增加 session 级防重：同一 session 只计一次。
 * 3. 后端算工资：读取 metadata.json 的 effective_duration_sec，直接除 3600 得小时。
 */
public class EffectiveDurationManager {
    private static final String TAG = "EffectiveDurationManager";
    private static final String PREFS_NAME = "effective_duration_prefs";
    private static final String KEY_PREFIX = "dur_ms_";
    private static final String KEY_PROCESSED_PREFIX = "processed_";

    private final SharedPreferences prefs;

    public EffectiveDurationManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 累加有效时长（毫秒）。
     *
     * @param collectorName 采集人姓名
     * @param durationMs    本次有效时长（毫秒），必须 > 0
     * @param sessionPath   session 路径（用于防重），null 表示不防重
     */
    public void addEffectiveDuration(String collectorName, long durationMs, String sessionPath) {
        if (collectorName == null || collectorName.isEmpty() || durationMs <= 0) {
            LogUtil.w(TAG, "addEffectiveDuration 参数非法: collector=" + collectorName + ", durationMs=" + durationMs);
            return;
        }

        // session 级防重（使用 SHA-256 前 16 位，避免 hashCode 碰撞）
        if (sessionPath != null && !sessionPath.isEmpty()) {
            String hash = sha256Prefix(sessionPath, 16);
            String processedKey = KEY_PROCESSED_PREFIX + hash;
            if (prefs.getBoolean(processedKey, false)) {
                LogUtil.i(TAG, "Session 已计入时长，跳过: " + sessionPath);
                return;
            }
            prefs.edit().putBoolean(processedKey, true).apply();
            LogUtil.d(TAG, "标记 Session 已处理: " + sessionPath + ", key=" + processedKey);
        }

        String key = buildKey(collectorName);
        long current = prefs.getLong(key, 0);
        long updated = current + durationMs;
        prefs.edit().putLong(key, updated).apply();
        LogUtil.i(TAG, String.format(Locale.CHINA, "%s 有效时长累加: +%dms = %dms (%.1f分钟) (key=%s)",
                collectorName, durationMs, updated, updated / 60000.0, key));
    }

    /**
     * 兼容旧调用（无防重，用于非录制场景）。
     */
    public void addEffectiveDuration(String collectorName, long durationMs) {
        addEffectiveDuration(collectorName, durationMs, null);
    }

    /**
     * 获取今天累计有效时长（毫秒）。
     */
    public long getTodayEffectiveDurationMs(String collectorName) {
        if (collectorName == null || collectorName.isEmpty()) return 0;
        String key = buildKey(collectorName);
        long val = prefs.getLong(key, 0);
        LogUtil.d(TAG, "getTodayEffectiveDurationMs: " + collectorName + " = " + val + "ms (key=" + key + ")");
        return val;
    }

    /**
     * 获取今天累计有效时长（秒，截断）。
     */
    public long getTodayEffectiveDurationSec(String collectorName) {
        return getTodayEffectiveDurationMs(collectorName) / 1000;
    }

    private String buildKey(String collectorName) {
        String date = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
        return KEY_PREFIX + date + "_" + collectorName;
    }

    /**
     * 计算字符串的 SHA-256 前缀。
     * 用于 session 防重键，比 String.hashCode() 更可靠（无碰撞风险）。
     */
    private String sha256Prefix(String input, int prefixLen) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(prefixLen);
            for (int i = 0; i < Math.min(prefixLen / 2, hash.length); i++) {
                hex.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // 理论上不会发生，兜底用 hashCode
            return String.valueOf(input.hashCode());
        }
    }
}