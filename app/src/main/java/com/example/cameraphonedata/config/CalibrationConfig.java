package com.example.cameraphonedata.config;

import android.util.Size;

/**
 * 标定参数配置中心
 *
 * 【工程师必读】
 * 1. 所有标定行为（棋盘格检测、亚像素精化、标定计算）均读取本类参数。
 * 2. 修改检测策略后，无需改动 PatternDetector/CalibrationManager，下次标定自动生效。
 * 3. 单例模式。
 *
 * 【20人采集场景建议】
 * - requiredPhotos: 15 张是精度与效率的平衡。光线差时可要求 20 张。
 * - maxRmsError: 1.0 像素是及格线；科研级建议 0.5。
 * - squareSizeMm: 必须与打印的棋盘格物理尺寸严格一致，否则外参错误。
 */
public class CalibrationConfig {

    // ========== 标定板几何（必须与实际打印物一致） ==========
    /** 棋盘格内角点列数（默认 9）。 */
    public int chessboardCols = 9;
    /** 棋盘格内角点行数（默认 6）。 */
    public int chessboardRows = 6;
    /** 每个方格的物理边长（毫米）。打印后务必用尺子复核。 */
    public float squareSizeMm = 20.0f;
    /** 需要采集的合格照片数。 */
    public int requiredPhotos = 15;

    // ========== 检测算法策略 ==========
    /**
     * 检测模式等级。
     * 0 = 仅快速模式；1 = 快速+标准；2 = 快速+标准+增强（默认）。
     * 屏幕反光/低光照场景建议设为 2。
     */
    public int detectionMode = 2;
    /** 是否启用亚像素角点精化。开启可提升标定精度，略微增加耗时。 */
    public boolean useSubPixel = true;
    /** 亚像素精化窗口大小（像素）。必须为奇数，代码内会自动修正。 */
    public int subPixelWindowSize = 5;

    // ========== 图像预处理参数 ==========
    /**
     * 高斯模糊核大小。0 表示不模糊；>0 且为奇数时生效。
     * 去噪用，标定板反光时可设为 3 或 5。
     */
    public int gaussianBlurSize = 3;
    /** 是否启用直方图均衡化。增强画面对比度，逆光时建议开启。 */
    public boolean useHistogramEq = true;
    /** 是否启用 CLAHE 自适应增强。适合阴影/不均匀光照，但可能引入噪声。 */
    public boolean useClahe = false;
    /** CLAHE 裁剪限制。越大增强越强。 */
    public double claheClipLimit = 3.0;

    // ========== 标定计算参数 ==========
    /** 最大允许重投影误差（像素）。超过此值标定失败，需重拍。 */
    public double maxRmsError = 0.5;
    /**
     * 标定畸变模型。
     * 0 = 标准 Brown-Conrady (k1,k2,p1,p2,k3)；
     * 1 = Rational Model (8维，含 k4,k5,k6)。
     * 默认 0，兼容性最好。
     */
    public int calibrationModel = 0;
    /** 最大迭代次数。一般无需修改。 */
    public int maxIterations = 30;
    /** 迭代收敛阈值。一般无需修改。 */
    public double epsilon = 0.001;

    // ========== 超时保护 ==========
    /** 单张图最大检测时间（毫秒）。超过则放弃，防止 ANR。 */
    public int maxDetectionTimeMs = 10000;

    // ========== 全局锁定 ==========
    /** 标定界面锁定的变焦倍数（-1 表示未锁定）。由 MainActivity 传入。 */
    public float lockedZoom = -1.0f;
    /** 是否强制要求标定后才能录制。生产环境强烈建议 true。 */
    public boolean forceCalibrationBeforeRecord = true;
    /** 标定界面锁定的分辨率（0x0 表示未锁定）。由 MainActivity 传入。 */
    public Size lockedResolution = new Size(0, 0);
    /**
     * 标定数据最终映射到的目标分辨率。
     * 默认与 CameraConfig.targetResolution 一致。
     * 标定完成后，内参会自动缩放到此分辨率存储，确保与录制分辨率匹配。
     */
    public Size calibTargetResolution = new Size(1920, 1080);

    // ========== 单例 ==========
    private static CalibrationConfig instance;

    public static CalibrationConfig getInstance() {
        if (instance == null) {
            instance = new CalibrationConfig();
        }
        return instance;
    }

    /**
     * 重置棋盘格几何参数为 9x6。
     * 注意：只改几何，不改检测策略（detectionMode/useClahe 等由工程师在上方字段控制）。
     */
    public void setPrintPattern9x6() {
        chessboardCols = 9;
        chessboardRows = 6;
        squareSizeMm = 20.0f;
    }
}