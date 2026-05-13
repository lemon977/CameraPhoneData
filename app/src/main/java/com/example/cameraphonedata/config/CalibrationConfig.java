package com.example.cameraphonedata.config;

import android.util.Size;

/**
 * 标定参数配置中心
 * 【工程师必读】
 * 1. 本类为线程安全单例，通过 {@link #getInstance()} 获取。
 * 2. 所有标定行为（棋盘格检测、亚像素精化、标定计算）均读取本类参数。
 * 3. 修改检测策略后，无需改动 PatternDetector/CalibrationManager，下次标定自动生效。
 * 4. 修改配置后务必调用 {@link #validateAndFix()}，防止非法参数导致标定崩溃或 ANR。
 * 【采集场景建议】
 * - requiredPhotos: 15~20 张是精度与效率的平衡。光线差时可要求 25 张。
 * - maxRmsError: 0.5 像素是良好线；科研级建议 0.3；>1.0 则畸变校正效果差。
 * - squareSizeMm: 必须与打印的棋盘格物理尺寸严格一致（用尺子复核），否则外参错误。
 * - detectionMode: 屏幕反光/低光照场景建议 2（全策略）。好光线可设为 1 提速。
 */
public class CalibrationConfig {

    // ========== 标定板几何（必须与实际打印物一致） ==========
    /** 棋盘格内角点列数。默认 9。范围：3 ~ 30。必须与实际打印棋盘格一致。 */
    public int chessboardCols = 9;
    /** 棋盘格内角点行数。默认 6。范围：3 ~ 30。必须与实际打印棋盘格一致。 */
    public int chessboardRows = 6;
    /**
     * 每个方格的物理边长（毫米）。默认 20.0mm。
     * 范围：1.0 ~ 200.0。打印后务必用尺子复核。
     * 该值直接影响标定外参（世界坐标系尺度），填错会导致三维重建结果整体缩放错误。
     */
    public float squareSizeMm = 20.0f;
    /** 需要采集的合格照片数。默认 20。范围：5 ~ 100。太多无收益，太少精度差。 */
    public int requiredPhotos = 20;

    // ========== 检测算法策略 ==========
    /**
     * 检测模式等级。
     * 0 = 仅快速模式（最快，光线好时够用）；
     * 1 = 快速+标准（平衡）；
     * 2 = 快速+标准+增强（默认，最慢但最稳）。
     * 屏幕反光/低光照/阴影场景建议 2。
     * 范围：0 ~ 2。
     */
    public int detectionMode = 2;
    /** 是否启用亚像素角点精化。开启可提升标定精度，略微增加耗时。 */
    public boolean useSubPixel = true;
    /**
     * 亚像素精化窗口大小（像素）。默认 5。
     * 范围：3 ~ 31，且必须为奇数（代码内会自动修正为最接近的奇数）。
     * 棋盘格角点间距大时可适当增大。
     */
    public int subPixelWindowSize = 5;

    // ========== 图像预处理参数 ==========
    /**
     * 高斯模糊核大小。0 表示不模糊；>0 且为奇数时生效。
     * 去噪用，标定板反光时可设为 3 或 5。
     * 范围：0, 3, 5, 7, 9。偶数会被自动修正为下一个奇数。
     */
    public int gaussianBlurSize = 3;
    /** 是否启用直方图均衡化。增强画面对比度，逆光时建议开启。 */
    public boolean useHistogramEq = true;
    /** 是否启用 CLAHE 自适应增强。适合阴影/不均匀光照，但可能引入噪声。 */
    public boolean useClahe = false;
    /** CLAHE 裁剪限制。默认 3.0。越大增强越强，但也越容易放大噪声。范围：1.0 ~ 10.0。 */
    public double claheClipLimit = 3.0;

    // ========== 标定计算参数 ==========
    /**
     * 最大允许重投影误差（像素）。默认 0.5。
     * 范围：0.1 ~ 5.0。超过此值标定失败，需重拍。
     * 该值是标定质量的硬门槛，不建议放宽到 >1.0。
     */
    public double maxRmsError = 0.5;
    /**
     * 标定畸变模型。默认 0。
     * 0 = 标准 Brown-Conrady (k1,k2,p1,p2,k3)，兼容性最好；
     * 1 = Rational Model (8维，含 k4,k5,k6)，仅高级场景使用。
     */
    public int calibrationModel = 0;
    /** 最大迭代次数。默认 30。一般无需修改。范围：10 ~ 100。 */
    public int maxIterations = 30;
    /** 迭代收敛阈值。默认 0.001。一般无需修改。范围：0.0001 ~ 0.01。 */
    public double epsilon = 0.001;

    // ========== 超时保护 ==========
    /**
     * 单张图最大检测时间（毫秒）。默认 10000ms = 10s。
     * 范围：3000 ~ 60000。超过则放弃当前帧，防止 ANR。
     */
    public int maxDetectionTimeMs = 10000;

    // ========== 全局锁定 ==========
    /**
     * 标定界面锁定的分辨率（0x0 表示未锁定）。由 MainActivity 传入。
     * 锁定后标定与录制分辨率强制一致，避免内参缩放误差。
     */
    public Size lockedResolution = new Size(0, 0);
    /**
     * 标定数据最终映射到的目标分辨率。
     * 默认与 CameraConfig.targetResolution 一致（1920x1080）。
     * 标定完成后，内参会自动缩放到此分辨率存储，确保与录制分辨率匹配。
     */
    public Size calibTargetResolution = new Size(1920, 1080);
    /** 是否强制要求标定后才能录制。生产环境强烈建议 true，防止未标定数据流入后端。 */
    public boolean forceCalibrationBeforeRecord = true;

    // ========== 单例（线程安全） ==========
    private static volatile CalibrationConfig instance;
    private static final Object LOCK = new Object();

    public static CalibrationConfig getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new CalibrationConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 防呆校验：自动修正所有越界/非法参数，防止标定过程崩溃或 ANR。
     * 【调用时机】修改配置后、进入标定页面前。
     */
    public void validateAndFix() {
        if (chessboardCols < 3) chessboardCols = 3;
        if (chessboardCols > 30) chessboardCols = 30;
        if (chessboardRows < 3) chessboardRows = 3;
        if (chessboardRows > 30) chessboardRows = 30;
        if (squareSizeMm < 1.0f) squareSizeMm = 1.0f;
        if (squareSizeMm > 200.0f) squareSizeMm = 200.0f;
        if (requiredPhotos < 5) requiredPhotos = 5;
        if (requiredPhotos > 100) requiredPhotos = 100;
        if (detectionMode < 0) detectionMode = 0;
        if (detectionMode > 2) detectionMode = 2;
        if (subPixelWindowSize < 3) subPixelWindowSize = 3;
        if (subPixelWindowSize > 31) subPixelWindowSize = 31;
        // 确保为奇数
        if (subPixelWindowSize % 2 == 0) subPixelWindowSize++;
        if (gaussianBlurSize < 0) gaussianBlurSize = 0;
        if (gaussianBlurSize > 0 && gaussianBlurSize % 2 == 0) gaussianBlurSize++;
        if (gaussianBlurSize > 15) gaussianBlurSize = 15;
        if (claheClipLimit < 1.0) claheClipLimit = 1.0;
        if (claheClipLimit > 10.0) claheClipLimit = 10.0;
        if (maxRmsError < 0.1) maxRmsError = 0.1;
        if (maxRmsError > 5.0) maxRmsError = 5.0;
        if (calibrationModel < 0) calibrationModel = 0;
        if (calibrationModel > 1) calibrationModel = 1;
        if (maxIterations < 10) maxIterations = 10;
        if (maxIterations > 100) maxIterations = 100;
        if (epsilon < 0.0001) epsilon = 0.0001;
        if (epsilon > 0.01) epsilon = 0.01;
        if (maxDetectionTimeMs < 3000) maxDetectionTimeMs = 3000;
        if (maxDetectionTimeMs > 60000) maxDetectionTimeMs = 60000;
        if (lockedResolution == null) lockedResolution = new Size(0, 0);
        if (calibTargetResolution == null || calibTargetResolution.getWidth() <= 0 || calibTargetResolution.getHeight() <= 0) {
            calibTargetResolution = new Size(1920, 1080);
        }
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
