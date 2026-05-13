package com.example.cameraphonedata.config;

import android.util.Size;
import androidx.camera.video.Quality;

import com.example.cameraphonedata.R;

/**
 * 相机配置中心 —— 所有相机、录制、检测参数的"唯一数据源"。
 * 【工程师必读】
 * 1. 本类为线程安全单例，通过 {@link #getInstance()} 获取。
 * 2. 所有字段均有防呆默认值，异常值在 {@link #validateAndFix()} 中自动修正。
 * 3. 修改配置后务必调用 {@link #validateAndFix()}，避免非法参数导致相机崩溃。
 * 4. 长时间录制场景：segmentDurationMs 建议 60s，maxSegmentCount 建议 0（无限制），
 *    防止单文件过大导致上传失败或内存溢出。
 * 5. 多品牌兼容性：wristConfidenceThreshold 不宜过低（<0.05 会误检），
 *    不宜过高（>0.5 会漏检）。
 */
public class CameraConfig {

    // ==================== 镜头角色 ====================
    public enum LensRole {
        ULTRA_WIDE, WIDE, UNKNOWN
    }

    /** 当前激活的镜头角色。由 {@link com.example.cameraphonedata.camera.CameraManager} 根据设备硬件探测结果自动维护。 */
    public LensRole currentLensRole = LensRole.WIDE;

    // ==================== 分辨率与画质 ====================
    /** 目标录制分辨率。默认 1920x1080 (FHD)。修改后需重启相机生效。 */
    public Size targetResolution = new Size(1920, 1080);
    /** CameraX 录制画质枚举。默认 FHD，与 targetResolution 保持一致。 */
    public Quality videoQuality = Quality.FHD;

    // ==================== 实时变焦状态（运行时自动更新） ====================
    /** 当前变焦倍数。由 CameraManager 从 CameraX 回调自动更新，请勿手动修改。 */
    public float currentZoom = 1.0f;
    /** 硬件最大变焦倍数。由 CameraManager 探测后写入。 */
    public float maxZoom = 1.0f;
    /** 硬件最小变焦倍数。广角/融合架构手机可能 < 1.0f。 */
    public float minZoom = 0.5f;

    // ==================== 帧率控制 ====================
    /** 目标录制帧率（fps）。0 = 使用系统默认。建议 30。部分 Samsung 设备锁定 30fps 会失败，系统会自动降级。 */
    public int targetFrameRate = 30;

    // ==================== 标定优化参数 ====================
    /** 标定时 Bitmap 采样率。2 = 宽高各 1/2，降低内存。必须是 >=1 的整数。 */
    public int bitmapSampleSize = 2;

    // ==================== 录制配置 ====================
    /** 是否录制音频。需要 RECORD_AUDIO 权限。 */
    public boolean recordAudio = true;
    /** 最大录制时长（分钟）。0 = 无限制。建议长时间采集设为 0，由分段控制文件大小。 */
    public int maxRecordMinutes = 0;
    /** 是否保存到系统相册。false = 仅保存在 App 私有目录，避免系统扫描导致卡顿。 */
    public boolean saveToGallery = false;

    // ==================== 分段录制配置 ====================
    /** 是否启用分段录制。强烈建议 true，防止单文件过大。 */
    public boolean enableSegmentRecording = true;
    /**
     * 每段最大时长（毫秒）。默认 60s = 60000ms。
     * 范围：10000 ~ 300000（10s ~ 5min）。超出范围自动修正。
     * 弱网/不稳定场景建议 30s（30000），减少重传损失。
     */
    public long segmentDurationMs = 60000;
    /**
     * 最大片段数。0 = 无限制。
     * 若设为正数，达到后自动停止录制。
     */
    public int maxSegmentCount = 0;

    // ==================== 人手检测配置 ====================
    /** 是否启用人手检测。录制期间若长时间未检测到手，触发报警/停止。 */
    public boolean enableHandDetection = true;
    /** 无手报警阈值（毫秒）。默认 15s。范围：5000 ~ 60000。 */
    public long noHandTimeoutMs = 15000;
    /**
     * MLKit Pose 关键点置信度阈值。默认 0.1。
     * 范围：0.05 ~ 0.5。过低误检，过高漏检。
     * 不同品牌手机 MLKit 模型输出分布差异大，若某品牌频繁误报，可适当调高。
     */
    public float wristConfidenceThreshold = 0.3f;
    /** 人手检测最小间隔（毫秒）。默认 300ms。防止 MLKit 过度占用 CPU。 */
    public long handDetectionIntervalMs = 300;
    /** 致命无手停止阈值（毫秒）。默认 70s。超过此时间未检测到手，强制停止录制。 */
    public long fatalNoHandTimeoutMs = 40000;

    // ==================== 语音播报配置 ====================
    /** 是否启用语音播报。工厂/嘈杂环境建议关闭。 */
    public boolean enableVoicePrompt = true;
    /** 片段保存成功音频资源 ID。 */
    public int segmentSavedRawResId = R.raw.segment_saved;
    /** 无手报警音频资源 ID。 */
    public int noHandAlertRawResId = R.raw.no_hand_alert;
    /** 开始录制音频资源 ID。 */
    public int recordStartRawResId = R.raw.record_start;
    /** 停止录制音频资源 ID。 */
    public int recordStopRawResId = R.raw.record_stop;
    /**
     * 致命无手强制停止音频资源 ID。0 = 使用蜂鸣兜底。
     * 如需自定义，放入 res/raw/ 后改为对应的 R.raw.xxx。
     */
    public int fatalNoHandStopRawResId = R.raw.fatal_no_hand;

    // ==================== 按钮防抖配置 ====================
    /** 录制按钮防抖间隔（毫秒）。默认 800ms。防止误触或蓝牙遥控器连发。 */
    public long recordButtonDebounceMs = 800;

    // ==================== 性能与防抖 ====================
    /** 预览帧率上限（fps）。默认 30。仅用于预览，不影响录制帧率。 */
    public int previewFpsLimit = 30;
    /** 是否启用视频防抖。部分设备不支持，开启后可能导致画面裁切。 */
    public boolean enableStabilization = false;

    // ==================== 内部超时与交互 ====================
    /** 录制停止超时（毫秒）。默认 8s。超过此时间未收到 Finalize 事件，强制重置状态机。 */
    public long stopTimeoutMs = 8000;
    /** 变焦步长。默认 0.1x。 */
    public float zoomStep = 0.1f;

    // ==================== 单例（线程安全） ====================
    private static volatile CameraConfig instance;
    private static final Object LOCK = new Object();

    public static CameraConfig getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new CameraConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 防呆校验：自动修正所有越界/非法参数，确保相机不会崩溃。
     * 【调用时机】修改配置后、启动相机前。
     */
    public void validateAndFix() {
        if (targetResolution == null || targetResolution.getWidth() <= 0 || targetResolution.getHeight() <= 0) {
            targetResolution = new Size(1920, 1080);
        }
        if (currentZoom < 0.1f) currentZoom = 0.1f;
        if (maxZoom < 1.0f) maxZoom = 1.0f;
        if (minZoom < 0.1f) minZoom = 0.1f;
        if (targetFrameRate < 0) targetFrameRate = 0;
        if (targetFrameRate > 240) targetFrameRate = 240;
        if (bitmapSampleSize < 1) bitmapSampleSize = 1;
        if (bitmapSampleSize > 8) bitmapSampleSize = 8;
        if (maxRecordMinutes < 0) maxRecordMinutes = 0;
        if (segmentDurationMs < 10000) segmentDurationMs = 10000;
        if (segmentDurationMs > 300000) segmentDurationMs = 300000;
        if (maxSegmentCount < 0) maxSegmentCount = 0;
        if (noHandTimeoutMs < 5000) noHandTimeoutMs = 5000;
        if (noHandTimeoutMs > 60000) noHandTimeoutMs = 60000;
        if (wristConfidenceThreshold < 0.01f) wristConfidenceThreshold = 0.01f;
        if (wristConfidenceThreshold > 1.0f) wristConfidenceThreshold = 1.0f;
        if (handDetectionIntervalMs < 100) handDetectionIntervalMs = 100;
        if (handDetectionIntervalMs > 2000) handDetectionIntervalMs = 2000;
        if (fatalNoHandTimeoutMs < 10000) fatalNoHandTimeoutMs = 10000;
        if (fatalNoHandTimeoutMs > 300000) fatalNoHandTimeoutMs = 300000;
        if (recordButtonDebounceMs < 200) recordButtonDebounceMs = 200;
        if (recordButtonDebounceMs > 5000) recordButtonDebounceMs = 5000;
        if (previewFpsLimit < 1) previewFpsLimit = 1;
        if (previewFpsLimit > 120) previewFpsLimit = 120;
        if (stopTimeoutMs < 3000) stopTimeoutMs = 3000;
        if (stopTimeoutMs > 30000) stopTimeoutMs = 30000;
        if (zoomStep < 0.01f) zoomStep = 0.01f;
        if (zoomStep > 1.0f) zoomStep = 1.0f;
    }
}
