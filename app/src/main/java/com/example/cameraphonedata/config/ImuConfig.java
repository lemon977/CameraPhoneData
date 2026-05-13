package com.example.cameraphonedata.config;

import android.hardware.SensorManager;

/**
 * IMU 采集配置中心 —— 所有传感器参数、采样策略、降级策略的"唯一数据源"。
 * <p>
 * 1. 本类为线程安全单例，通过 {@link #getInstance()} 获取。
 * 2. 修改配置后务必调用 {@link #validateAndFix()}，避免非法参数导致传感器崩溃或丢数据。
 * 3. 采样率模式不要无脑设 FASTEST，发热会导致视频掉帧。推荐 FASTEST + targetOutputHz 做软件降采样。
 * 4. 时间戳单位建议保持 "ns"（纳秒），与 Android SensorEvent.timestamp 原生一致，避免精度损失。
 * 5. 本类只控制"采什么样"，健康检查阈值在 {@link ImuHealthConfig} 中独立配置。
 * <p>
 * 【防呆设计】
 * - batchSize: 超过 500 条才 flush，防止内存爆炸；低于 10 条频繁写磁盘。
 * - maxBufferTimeMs: 即使条数没到，超过 500ms 也强制 flush，防止最后几条丢数据。
 * - fallbackToAccelerometer: 遇到不支持线性加速度的手机，自动用原始加速度兜底。
 * - validateAndFix(): 所有越界参数自动修正到安全范围。
 * <p>
 * 【常用配置组合】
 * ┌──────────────┬─────────────────┬─────────────────┬─────────────────────────────┐
 * │ 场景          │ sampleRateMode  │ targetOutputHz  │ 说明                        │
 * ├──────────────┼─────────────────┼─────────────────┼─────────────────────────────┤
 * │ 标准室内录制   │ FASTEST         │ 60              │ 推荐，平衡精度与发热          │
 * │ 夏天户外/发热  │ GAME            │ 50              │ 降低硬件频率，减少发热        │
 * │ 后端要求高帧率 │ FASTEST         │ 100             │ 高帧率，但手机会明显发烫      │
 * │  debug 调试用  │ NORMAL          │ 5               │ 极低速，仅验证通路是否正常    │
 * └──────────────┴─────────────────┴─────────────────┴─────────────────────────────┘
 */
public class ImuConfig {

    // ==================== 总开关 ====================
    /**
     * 是否启用 IMU 采集。
     * false = 完全不注册传感器，不生成 imu 相关文件，App 行为与之前完全一致。
     *
     * 【示例】true = 开启（默认）
     *         false = 关闭（如果后端暂时不需要 IMU，或某台手机传感器有兼容性问题）
     */
    public boolean enableImuRecording = true;

    // ==================== 采样率控制 ====================
    /**
     * 传感器硬件采样率模式。决定 Android 以多高的频率向 App 推送传感器事件。
     * <p>
     * SENSOR_DELAY_FASTEST = 0  （硬件上限，通常 100~400Hz，耗电大、发热大）
     * SENSOR_DELAY_GAME      = 1  （约 50Hz，系统推荐的游戏档位，较省电）
     * SENSOR_DELAY_UI        = 2  （约 15~20Hz，仅适合 UI 动画，不推荐用于数据采集）
     * SENSOR_DELAY_NORMAL    = 3  （约 5Hz，极低，仅调试用）
     * <p>
     * 【推荐】FASTEST（0）。原因：
     *   1. 硬件采样越高，软件降采样后的信噪比越好（因为可以选最近的真实样本）。
     *   2. 配合 targetOutputHz 做软件降采样，最终输出频率由你控制，不受厂商差异影响。
     * <p>
     * 【发热大时】改 GAME（1），硬件直接从 200Hz 降到 50Hz，发热立即缓解。
     * <p>
     * 【注意】Android 12+ 使用 FASTEST 需在 AndroidManifest.xml 声明 HIGH_SAMPLING_RATE_SENSORS 权限。
     */
    public int sampleRateMode = SensorManager.SENSOR_DELAY_FASTEST;

    /**
     * 目标输出频率（Hz）。0 = 不限制，硬件来多少输出多少。
     * 常用值：50, 60, 100。
     * <p>
     * 【原理】传感器用 FASTEST 注册，获取最高硬件频率，
     *        然后在 onSensorChanged 里按时间戳做软件降采样，
     *        只保留满足 targetOutputHz 间隔的样本写入 jsonl。
     *        这样小米、华为、三星等不同厂商手机的输出频率一致。
     * <p>
     * 【示例】
     * 60 = 每 16.67ms 输出一行（推荐，与 30fps 视频帧率成整数倍，方便对齐）
     * 50 = 每 20ms 输出一行（更省电，适合 3-4 小时长时间录制）
     * 0  = 不限制，硬件原生频率直接输出（文件体积大，不同手机频率不一致）
     * <p>
     * 【注意】如果硬件实际频率低于 targetOutputHz（如某些低端机陀螺仪只有 40Hz），
     *        就按硬件上限输出，不会插值伪造数据，保证真实性。
     */
    public int targetOutputHz = 60;

    // ==================== 目标传感器 ====================
    /**
     * 是否采集陀螺仪（TYPE_GYROSCOPE）。
     * 几乎所有 Android 手机都有此硬件传感器。
     * 它是主时钟传感器（如果可用），决定 jsonl 的输出节奏。
     * <p>
     * 【示例】true = 采集（默认，强烈建议开启）
     *         false = 不采集（仅当陀螺仪硬件损坏时临时关闭）
     */
    public boolean useGyroscope = true;

    /**
     * 是否采集线性加速度（TYPE_LINEAR_ACCELERATION）。
     * 这是 Android 系统提供的"软件传感器"，已经去掉了重力分量，方便后端直接使用。
     * 原理：系统用加速度计 + 陀螺仪融合计算，去掉重力后的纯运动加速度。
     * <p>
     * 【示例】true = 采集（推荐，后端最方便）
     *         false = 不采集（如果手机不支持线性加速度，会用原始加速度计兜底）
     * <p>
     * 【兼容性】约 95% 手机支持。不支持时自动 fallback 到 TYPE_ACCELEROMETER（含重力），
     *          并在 imu_config.json 中标记 fallback 状态。
     */
    public boolean useLinearAcceleration = true;

    /**
     * 是否采集旋转矢量（TYPE_ROTATION_VECTOR）。
     * 这是 Android 系统提供的"软件传感器"，输出设备姿态四元数 [x,y,z,scalar]。
     * 原理：加速度计 + 磁力计 + 陀螺仪融合，输出相对地球的绝对姿态。
     * <p>
     * 【示例】true = 采集（推荐，后端可直接知道手机朝向）
     *         false = 不采集（如果只需要原始陀螺仪/加速度，可关闭以省电）
     * <p>
     * 【兼容性】约 95% 手机支持。不支持时自动 fallback 到 TYPE_GAME_ROTATION_VECTOR
     *          （无地磁校正，精度稍差但无磁力计也能用）。
     */
    public boolean useRotationVector = true;

    /**
     * 是否同时采集原始加速度计（TYPE_ACCELEROMETER，含重力）。
     * 与 TYPE_LINEAR_ACCELERATION 独立，可作为冗余备份或供后端自行去重力。
     * <p>
     * 【示例】true = 同时采集原始加速度，jsonl 中增加 "ra" 字段（推荐用于机器人学习/RL）
     *         false = 不采集（默认关闭以省电）
     * <p>
     * 【注意】如果 TYPE_LINEAR_ACCELERATION 不可用且 fallback 到 TYPE_ACCELEROMETER，
     *         则 "a" 字段已经是原始加速度，此时不会重复注册硬件。
     */
    public boolean useRawAccelerometer = true;

    // ==================== 降级策略 ====================
    /**
     * 当 TYPE_LINEAR_ACCELERATION 不可用时，是否用 TYPE_ACCELEROMETER 兜底。
     * true = 兜底，但数据中包含重力，imu_config.json 会标记 "fallback_accelerometer: true"。
     * false = 直接不存加速度通道，jsonl 里省略 "a" 字段。
     *
     * 【建议】保持 true。后端看到 fallback 标记后，可以自己减去重力（如果已知姿态）。
     */
    public boolean fallbackToAccelerometer = true;

    /**
     * 当 TYPE_ROTATION_VECTOR 不可用时，是否尝试 TYPE_GAME_ROTATION_VECTOR 兜底。
     * true = 用游戏旋转矢量替代，精度稍差（无地磁校正，yaw 角会漂移），但无磁力计也能用。
     * false = 直接不存旋转矢量，jsonl 里省略 "r" 字段。
     *
     * 【建议】保持 true。游戏旋转矢量只依赖陀螺仪 + 加速度计，兼容性更好。
     */
    public boolean fallbackToGameRotationVector = true;

    // ==================== 写文件缓冲策略 ====================
    /**
     * 批量写入条数。积累到此数量后，一次性 flush 到磁盘。
     * 范围：10 ~ 500。
     *
     * 【示例】
     * 100 = 默认，约 1~2 秒的数据（60Hz 时 1.67 秒），平衡 IO 次数与内存占用
     * 10  = 几乎逐条写（最安全，系统崩溃丢最少数据，但发热/耗电大）
     * 500 = 约 8 秒数据一次 flush（最省电，但崩溃可能丢 8 秒数据）
     *
     * 【建议】标准录制保持 100。如果要"数据正确性绝对优先"，改 10 或 20。
     */
    public int batchSize = 100;

    /**
     * 最大缓冲时间（毫秒）。即使条数没到 batchSize，超过此时长也强制 flush。
     * 防止录制停止时最后几条数据还在内存里没写盘。
     * 范围：100 ~ 5000。
     *
     * 【示例】
     * 500 = 默认，最多等 500ms 必 flush
     * 100 = 更激进，减少丢数据风险
     * 2000 = 更省电，但意外断电可能丢 2 秒数据
     */
    public int maxBufferTimeMs = 500;

    // ==================== 时间戳配置 ====================
    /**
     * 时间戳单位。
     * "ns" = 纳秒（推荐，与 SensorEvent.timestamp 原生一致，无精度损失）
     * "us" = 微秒
     * "ms" = 毫秒（不推荐，50Hz 场景下 20ms 粒度会损失时间精度）
     *
     * 【注意】修改此字段会影响 imu_data.jsonl 中 "t_ns" 的数值大小，
     *        但 imu_config.json 里会记录 unit_per_ms，后端按公式转换即可。
     */
    public String timestampUnit = "ns";

    // ==================== 单例（线程安全） ====================
    private static volatile ImuConfig instance;
    private static final Object LOCK = new Object();

    public static ImuConfig getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new ImuConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 防呆校验：自动修正所有越界/非法参数，确保传感器采集稳定。
     * 【调用时机】修改配置后、启动 IMU 录制前（ImuRecorder.start() 会自动调用）。
     */
    public void validateAndFix() {
        // 采样率模式：只允许标准档位或正数微秒值
        boolean isStandard = sampleRateMode == SensorManager.SENSOR_DELAY_FASTEST
                || sampleRateMode == SensorManager.SENSOR_DELAY_GAME
                || sampleRateMode == SensorManager.SENSOR_DELAY_UI
                || sampleRateMode == SensorManager.SENSOR_DELAY_NORMAL;
        if (!isStandard && sampleRateMode > 0) {
            if (sampleRateMode < 1000) sampleRateMode = 1000;       // 最小 1ms
            if (sampleRateMode > 200000) sampleRateMode = 200000;   // 最大 200ms
        } else if (!isStandard) {
            sampleRateMode = SensorManager.SENSOR_DELAY_FASTEST;
        }

        if (targetOutputHz < 0) targetOutputHz = 0;
        if (targetOutputHz > 500) targetOutputHz = 500; // 超过 500Hz 无意义且危险

        if (batchSize < 10) batchSize = 10;
        if (batchSize > 500) batchSize = 500;

        if (maxBufferTimeMs < 100) maxBufferTimeMs = 100;
        if (maxBufferTimeMs > 5000) maxBufferTimeMs = 5000;

        if (!"ns".equals(timestampUnit) && !"us".equals(timestampUnit) && !"ms".equals(timestampUnit)) {
            timestampUnit = "ns";
        }
    }

    /**
     * 将纳秒时间戳转换为配置指定的单位。
     * @param timestampNs SensorEvent.timestamp（纳秒）
     * @return 转换后的值
     */
    public long convertTimestamp(long timestampNs) {
        switch (timestampUnit) {
            case "us": return timestampNs / 1000L;
            case "ms": return timestampNs / 1_000_000L;
            case "ns":
            default:   return timestampNs;
        }
    }

    /**
     * 获取时间戳单位对应的每毫秒换算系数（用于后端注释公式）。
     * 例如 "ns" 返回 1_000_000，表示 1ms = 1_000_000ns。
     */
    public long getTimestampUnitPerMs() {
        switch (timestampUnit) {
            case "us": return 1000L;
            case "ms": return 1L;
            case "ns":
            default:   return 1_000_000L;
        }
    }

    /**
     * 获取采样率模式名称（只读描述）。
     */
    public String getSampleRateModeName() {
        switch (sampleRateMode) {
            case SensorManager.SENSOR_DELAY_FASTEST: return "SENSOR_DELAY_FASTEST";
            case SensorManager.SENSOR_DELAY_GAME:    return "SENSOR_DELAY_GAME";
            case SensorManager.SENSOR_DELAY_UI:      return "SENSOR_DELAY_UI";
            case SensorManager.SENSOR_DELAY_NORMAL:  return "SENSOR_DELAY_NORMAL";
            default: return "CUSTOM(" + sampleRateMode + "ms)";
        }
    }
}
