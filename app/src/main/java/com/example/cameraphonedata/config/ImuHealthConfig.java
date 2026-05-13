package com.example.cameraphonedata.config;

/**
 * IMU 健康检查配置 —— 所有数据质量阈值、巡检周期的唯一数据源。
 * 1. 本类为线程安全单例，通过 {@link #getInstance()} 获取。
 * 2. 修改阈值后不需要重启 App，下次 start() 录制自动生效。
 * 3. 所有阈值在 {@link #validateAndFix()} 中有防呆修正，非法值不会导致崩溃。
 * 4. 如果某台手机频繁误触发"不健康自动停止"，把对应阈值放宽即可。
 * <p>
 * 【健康检查流程】（由 RecordingManager 每 checkIntervalMs 执行一次）
 * 1. 读取当前 IMU 统计（丢帧率、写错误数、NaN 数、跳变数、实际频率）。
 * 2. 逐个检查以下判据，任一不满足 → isHealthy() 返回 false → 自动停止录制。
 * 3. 前 warmupSeconds 秒内不做判断（给传感器预热和系统调度留缓冲）。
 * <p>
 * 【判据与对应字段】
 * ┌─────────────────┬────────────────────┬─────────────────────────────────────────┐
 * │ 判据            │ 字段               │ 说明                                    │
 * ├─────────────────┼────────────────────┼─────────────────────────────────────────┤
 * │ 丢帧率过高      │ maxDropRate        │ 队列写不过来，可能手机太烫或存储卡太慢   │
 * │ 写磁盘出错      │ maxWriteErrors     │ 磁盘满了、权限被回收、SD 卡拔出          │
 * │ NaN/Inf 过多    │ maxNanRate         │ 传感器硬件漂移或厂商驱动 bug              │
 * │ 时间戳跳变      │ maxTimestampJumps  │ 系统调度异常，或传感器回调被阻塞太久      │
 * │ 实际频率过低    │ minFreqRatio       │ 手机发热降频，系统限制了传感器采样率      │
 * └─────────────────┴────────────────────┴─────────────────────────────────────────┘
 */
public class ImuHealthConfig {

    // ==================== 丢帧率判据 ====================
    /**
     * 最大允许丢帧率（0.0 ~ 1.0）。
     * 丢帧率 = droppedCount / (outputCount + droppedCount)。
     *
     * 【示例】
     * 0.15 = 15%  （推荐，手机传感器精度有限，偶发丢帧正常）
     * 0.30 = 30%  （低端机/高负载场景放宽）
     * 1.00 = 100% （等于关闭此判据，不推荐）
     *
     * 【触发场景】
     * - 手机发热，CPU 降频，写线程跟不上传感器频率
     * - 存储卡写入速度太慢（低端机常见）
     * - batchSize 设得太大，单次 flush 阻塞太久
     */
    public double maxDropRate = 0.30;

    // ==================== 写错误判据 ====================
    /**
     * 最大允许写错误次数（>=0 的整数）。
     * 手机存储偶尔会有瞬时 IO 错误，不必零容忍。
     *
     * 【示例】
     * 3 = 允许 3 次瞬时错误（推荐，手机精度有限）
     * 0 = 零容忍（只有绝对安全的场景才用）
     * 999 = 基本关闭此判据（不推荐）
     *
     * 【触发场景】
     * - 磁盘满了
     * - 用户中途撤销了存储权限
     * - SD 卡只读或损坏
     */
    public int maxWriteErrors = 3;

    // ==================== NaN/Inf 判据 ====================
    /**
     * 最大允许 NaN/Inf 比例（0.0 ~ 1.0）。
     * 手机传感器偶尔会有瞬时异常值，不必过于严格。
     *
     * 【示例】
     * 0.05 = 5%   （推荐，偶发跳变正常）
     * 0.01 = 1%   （传感器质量较好的手机）
     * 0.00 = 零容忍（只有实验室环境才用）
     *
     * 【触发场景】
     * - 传感器硬件老化/损坏
     * - 厂商驱动在特定温度/电量下返回异常值
     * - 手机摔过，陀螺仪芯片虚焊
     */
    public double maxNanRate = 0.20;

    // ==================== 时间戳跳变判据 ====================
    /**
     * 最大允许时间戳跳变次数（>=0 的整数）。
     * 手机系统调度不稳定，偶发跳变是正常的，尤其 Android 低端机。
     *
     * 【示例】
     * 50 = 允许 50 次跳变（推荐，3-4 小时长录中偶发几十次正常）
     * 10 = 较严格（高质量手机）
     * 0  = 零容忍（不推荐，手机达不到这个精度）
     * <p>
     * 【触发场景】
     * - 系统垃圾回收（GC）卡住主线程，连带影响传感器回调
     * - 其他 App 抢占 CPU（如后台更新、推送服务）
     * - 手机厂商的省电策略冻结了传感器
     */
    public int maxTimestampJumps = 100;

    // ==================== 频率判据 ====================
    /**
     * 最小允许频率比例（0.0 ~ 1.0）。
     * 实际频率 < targetOutputHz * minFreqRatio 时判定为不健康。
     *
     * 【示例】
     * 0.30 = 30% （推荐，手机发热后频率可能大幅降低，30% 是安全底线）
     * 0.50 = 50% （较严格，高质量手机）
     * 0.00 = 关闭频率检查（不推荐）
     *
     * 【触发场景】
     * - 手机发热，系统热节流（Thermal Throttling）降低传感器频率
     * - 电池电量低于 20%，厂商强制限制后台传感器
     * - 开启了省电模式
     */
    public double minFreqRatio = 0.30;

    // ==================== 预热与巡检周期 ====================
    /**
     * 预热时间（秒）。录制开始后前 N 秒不做健康判断。
     * 因为传感器启动初期频率不稳定，系统调度也在预热。
     *
     * 【示例】
     * 5  = 前 5 秒不判断（推荐）
     * 0  = 立即判断（可能导致刚启动就误停）
     * 30 = 半分钟预热（过于保守）
     */
    public int warmupSeconds = 30;

    /**
     * 健康检查间隔（毫秒）。
     * RecordingManager 会每此间隔调用一次 imuRecorder.isHealthy()。
     *
     * 【示例】
     * 30000 = 30 秒检查一次（推荐）
     * 60000 = 1 分钟一次（更省电）
     */
    public long checkIntervalMs = 30_000L;

    // ==================== 语音播报配置 ====================
    /**
     * 健康检查失败时是否语音播报。
     * true = 播报（默认，方便采集人员立即知道为什么停了）
     * false = 静默停止（只在 UI 弹窗）
     */
    public boolean enableVoiceOnHealthFailure = true;

    /**
     * 健康检查失败时的语音音频资源 ID。
     * 默认使用 R.raw.record_stop（停止录制的提示音）。
     * 如需自定义，把音频文件放入 res/raw/ 后改为此处的 R.raw.xxx。
     */
    public int healthFailureVoiceResId = com.example.cameraphonedata.R.raw.record_stop;

    // ==================== 单例（线程安全） ====================
    private static volatile ImuHealthConfig instance;
    private static final Object LOCK = new Object();

    public static ImuHealthConfig getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new ImuHealthConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 防呆校验：自动修正越界参数。
     * 【调用时机】修改配置后、启动录制前。
     */
    public void validateAndFix() {
        if (maxDropRate < 0.0) maxDropRate = 0.0;
        if (maxDropRate > 1.0) maxDropRate = 1.0;

        if (maxWriteErrors < 0) maxWriteErrors = 0;
        if (maxWriteErrors > 9999) maxWriteErrors = 9999;

        if (maxNanRate < 0.0) maxNanRate = 0.0;
        if (maxNanRate > 1.0) maxNanRate = 1.0;

        if (maxTimestampJumps < 0) maxTimestampJumps = 0;
        if (maxTimestampJumps > 9999) maxTimestampJumps = 9999;

        if (minFreqRatio < 0.0) minFreqRatio = 0.0;
        if (minFreqRatio > 1.0) minFreqRatio = 1.0;

        if (warmupSeconds < 0) warmupSeconds = 0;
        if (warmupSeconds > 60) warmupSeconds = 60;

        if (checkIntervalMs < 5_000L) checkIntervalMs = 5_000L;
        if (checkIntervalMs > 300_000L) checkIntervalMs = 300_000L;
    }
}
