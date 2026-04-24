package com.example.cameraphonedata.config;

import android.util.Size;
import androidx.camera.video.Quality;

import com.example.cameraphonedata.R;

/**
 * 相机配置中心 —— 所有相机、录制、检测参数的"唯一数据源"。
 *
 * 【工程师必读】
 * 1. 修改本类参数后，绝大部分功能无需改动其他文件即可生效（遵循"配置优先"原则）。
 * 2. 所有字段均为 public，可直接在代码中修改；建议对需要频繁调节的参数在此集中管理。
 * 3. 单例模式：CameraConfig.getInstance() 全局唯一。
 *
 * 【20人采集场景建议】
 * - targetResolution: 建议统一为 1920x1080，兼容性与画质平衡。
 * - segmentDurationMs: 60s 分段，防止单文件过大导致上传失败或内存溢出。
 * - enableHandDetection: 建议开启，确保画面有人手，避免无效数据。
 * - recordButtonDebounceMs: 800ms 足够防止误触；现场手套/厚手指可调到 1200ms。
 */
public class CameraConfig {

    // ==================== 分辨率与画质 ====================
    /**
     * 录制目标分辨率（唯一数据源）。
     * 建议值：
     *   - 1920x1080（FHD，默认）：画质与兼容性最佳，推荐。
     *   - 1280x720（HD）：存储紧张或低端机使用。
     *   - 2560x1440（QHD）：仅高端机，注意存储与发热。
     * 注意：CameraX 实际提供的分辨率可能与此略有差异（如 1088 代替 1080），
     *      标定与录制均以 CameraX 实际返回为准，误差在 16px 以内可接受。
     */
    public Size targetResolution = new Size(1920, 1080);

    /**
     * 视频质量档位，供 Recorder 初始化使用。
     * 建议与 targetResolution 保持一致：
     *   - FHD -> 1920x1080
     *   - HD  -> 1280x720
     * 若不一致，以 targetResolution 为准。
     */
    public Quality videoQuality = Quality.FHD;

    /** 启动默认变焦倍数（仅冷启动生效，运行中由系统或用户控制）。 */
    public float defaultZoom = 1.0f;

    // ==================== 实时变焦状态（运行时自动更新，只读） ====================
    /** 当前变焦倍数，由 CameraManager 从系统实时回调更新。 */
    public float currentZoom = 1.0f;
    /** 本机硬件支持的最大变焦倍数（如 5.0x、10.0x）。 */
    public float maxZoom = 1.0f;
    /** 本机硬件支持的最小变焦倍数（超广角通常为 0.5x 或 0.6x）。 */
    public float minZoom = 0.5f;

    // ==================== 标定优化参数 ====================
    /**
     * 标定时 Bitmap 采样率（降低内存）。
     * 2 表示长宽各缩小 1/2，内存降为 1/4。
     * 低端机可改为 4。
     */
    public int bitmapSampleSize = 2;

    // ==================== 录制配置 ====================
    /** 是否录制音频。关闭可减小文件体积并避免音频权限问题。 */
    public boolean recordAudio = true;
    /** 最大录制时长（分钟）。0 表示无限制。 */
    public int maxRecordMinutes = 0;
    /** 是否同时保存到系统相册。false = 仅保存在 App 私有目录，推荐。 */
    public boolean saveToGallery = false;

    // ==================== 分段录制配置 ====================
    /** 是否启用定时分段。长录制强烈建议开启，防止单文件过大。 */
    public boolean enableSegmentRecording = true;
    /** 每段时长（毫秒）。默认 60s。 */
    public long segmentDurationMs = 60000;
    /** 最大分段数，达到后自动停止。0 表示无限制。 */
    public int maxSegmentCount = 0;

    // ==================== 人手检测配置 ====================
    /** 是否启用人手检测。 */
    public boolean enableHandDetection = true;
    /** 无手报警超时（毫秒）。默认 15s。 */
    public long noHandTimeoutMs = 15000;
    /** 手腕关键点置信度阈值。过低会误报，过高会漏报。 */
    public float wristConfidenceThreshold = 0.1f;
    /** 人手检测帧间隔（毫秒）。默认 500ms，兼顾精度与发热。 */
    public long handDetectionIntervalMs = 300;

    // ==================== 语音播报配置 ====================
    /** 是否启用语音/蜂鸣提示。 */
    public boolean enableVoicePrompt = true;
    /** 各场景音频资源 ID，0 表示使用蜂鸣兜底。 */
    public int segmentSavedRawResId = R.raw.segment_saved;
    public int noHandAlertRawResId = R.raw.no_hand_alert;
    public int recordStartRawResId = R.raw.record_start;
    public int recordStopRawResId = R.raw.record_stop;

    // ==================== 按钮防抖配置（防呆） ====================
    /**
     * 录制按钮最小点击间隔（毫秒）。
     * 快速双击、蓝牙快门连击、手套误触均会被拦截。
     */
    public long recordButtonDebounceMs = 800;

    // ==================== 性能与防抖 ====================
    /** 预览 FPS 上限。30 足够采集，60 会增加发热。 */
    public int previewFpsLimit = 30;
    /** 是否启用视频防抖。开启可能导致画面裁切，标定后不建议开启。 */
    public boolean enableStabilization = false;

    // ==================== 内部超时与交互（一般不动） ====================
    /** 相机停止超时（毫秒）。超过此时间强制重置状态机。 */
    public long stopTimeoutMs = 8000;
    /** 变焦步长（每次 +/- 多少倍）。 */
    public float zoomStep = 0.1f;

    // ==================== 单例 ====================
    private static CameraConfig instance;

    public static CameraConfig getInstance() {
        if (instance == null) {
            instance = new CameraConfig();
        }
        return instance;
    }
}