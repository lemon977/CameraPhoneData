package com.example.cameraphonedata.config;

import java.util.concurrent.TimeUnit;

/**
 * 时间常量 —— 集中管理所有与时间相关的魔法数字，避免散落各处。
 */
public final class TimeConstants {
    private TimeConstants() {}

    // ========== 录制相关 ==========
    /** 录制进度 UI 刷新间隔（毫秒） */
    public static final long PROGRESS_INTERVAL_MS = 1000L;
    /** 存储检查间隔（分钟） */
    public static final long STORAGE_CHECK_INTERVAL_MINUTES = 5L;
    /** WakeLock 超时（10 小时，覆盖长时间录制） */
    public static final long WAKE_LOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(10);
    /** IMU 写入线程停止等待超时（毫秒） */
    public static final long IMU_STOP_TIMEOUT_MS = 5000L;
    /** metadata 落盘等待超时（秒） */
    public static final long METADATA_FINALIZE_TIMEOUT_SEC = 8L;

    // ========== 上传相关 ==========
    /** OSS 单文件上传 latch 等待超时（分钟） */
    public static final long OSS_UPLOAD_LATCH_TIMEOUT_MIN = 5L;
    /** 上传完成后进度刷新延迟（毫秒） */
    public static final long UPLOAD_PROGRESS_FLUSH_DELAY_MS = 150L;
    /** 上传重试退避基数（毫秒） */
    public static final long RETRY_BASE_BACKOFF_MS = 1000L;
    /** 上传重试退避上限（毫秒） */
    public static final long RETRY_MAX_BACKOFF_MS = 30000L;

    // ========== IMU 相关 ==========
    /** 纳秒每秒 */
    public static final long NS_PER_SECOND = 1_000_000_000L;
    /** 纳秒每毫秒 */
    public static final long NS_PER_MS = 1_000_000L;
    /** 写队列 poll 超时（毫秒） */
    public static final long WRITE_QUEUE_POLL_TIMEOUT_MS = 200L;

    // ========== 通用 ==========
    /** 秒转毫秒 */
    public static final long MS_PER_SECOND = 1000L;
    /** 分转毫秒 */
    public static final long MS_PER_MINUTE = 60_000L;
}
