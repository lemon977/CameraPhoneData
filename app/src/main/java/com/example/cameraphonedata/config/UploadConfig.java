package com.example.cameraphonedata.config;

import android.content.Context;

/**
 * 上传配置中心 —— 仅保留阿里云 OSS。
 * 【工程师必读】
 * 1. 本类为线程安全单例，通过 {@link #getInstance(Context)} 获取。必须传 Context 以初始化加密存储。
 * 2. 密钥通过 OssKeyStore 加密存储，本类只提供读取入口，不暴露明文。
 * 3. 所有超时、重试、分片大小均在此调节，禁止在 OssUploadStrategy 中硬编码。
 * 4. 修改配置后务必调用 {@link #validateAndFix()}，防止非法参数导致上传异常。
 * 【场景建议】
 * - ossUploadTimeout: 工厂/野外 WiFi 不稳定，建议 80~120s。
 * - multipartPartSize: 弱网 5MB（5*1024*1024），强网 10~15MB。默认 10MB。
 *   注意：分片大小必须 >= 100KB 且 <= 500MB，否则 OSS 会拒绝。
 * - autoUpload: false（默认）。人工确认后再上传，避免流量失控。
 * - deleteAfterUpload: 强烈建议 false，防止误删。本 App 不自动删除任何用户数据。
 * - enableMultipartResume: true（默认）。大文件必开，支持断点续传。
 * 【安全性】
 * - STS 临时凭证（ossStsToken）比长期 AK/SK 更安全，建议生产环境使用 STS。
 * - 长期 AK/SK 通过 BuildConfig 注入，打包后不可通过 SharedPreferences 直接读取。
 */
public class UploadConfig {

    /** 上传策略类型。当前仅支持 OSS。 */
    public enum StrategyType {
        NONE,   // 不上传
        OSS     // 阿里云对象存储
    }

    /** 当前激活的上传策略。 */
    public StrategyType activeStrategy = StrategyType.OSS;

    private final OssKeyStore keyStore;

    public UploadConfig(Context context) {
        this.keyStore = new OssKeyStore(context.getApplicationContext());
    }

    // ==================== OSS 连接参数（来自 BuildConfig / OssKeyStore 加密存储） ====================
    /**
     * OSS Endpoint，如 "https://oss-cn-hangzhou.aliyuncs.com"。
     * 从加密存储读取，首次初始化时从 BuildConfig 写入。
     */
    public String getOssEndpoint() { return keyStore.getEndpoint(); }
    /** OSS Bucket 名称。 */
    public String getOssBucketName() { return keyStore.getBucket(); }
    /** OSS AccessKey ID。 */
    public String getOssAccessKeyId() { return keyStore.getAk(); }
    /** OSS AccessKey Secret。 */
    public String getOssAccessKeySecret() { return keyStore.getSk(); }

    /**
     * STS 临时凭证 Token。
     * null 表示使用长期 AK/SK；非 null 则使用 STS 临时授权（更安全，建议生产环境使用）。
     * STS Token 有过期时间，过期后需重新获取并赋值。
     */
    public String ossStsToken = null;

    // ==================== OSS 上传路径与行为 ====================
    /**
     * OSS 远程目录前缀。
     * 不要以 "/" 开头，建议以 "/" 结尾（如 "camera-phone-data/"）。
     */
    public String ossUploadDir = "camera-phone-data/";

    // ==================== 超时与重试（弱网场景调这里） ====================
    /**
     * 连接/读取超时（秒）。默认 80。
     * 范围：10 ~ 300。弱网/工厂 WiFi 不稳定建议 120。
     */
    public int ossUploadTimeout = 80;
    /**
     * 分片大小（字节）。默认 10MB = 10*1024*1024。
     * 范围：100*1024 ~ 500*1024*1024（100KB ~ 500MB）。
     * 弱网建议 5MB，强网建议 10~15MB。超过 5GB 的文件必须分片上传。
     */
    public int multipartPartSize = 10 * 1024 * 1024;
    /** 分片上传单片重试次数。默认 5。范围：1 ~ 20。 */
    public int multipartRetryCount = 5;
    /**
     * 是否启用断点续传。默认 true。
     * 大文件必开：网络中断后可从已上传的分片继续，无需从头开始。
     */
    public boolean enableMultipartResume = true;
    /** 简单上传重试次数。默认 5。范围：1 ~ 20。 */
    public int retryCount = 5;

    // ==================== 自动行为 ====================
    /**
     * 录制完成后是否自动上传。
     * 采集建议 false：由采集负责人统一点击上传，避免流量失控。
     */
    public boolean autoUpload = false;
    /**
     * 上传成功后是否自动删除本地文件。
     * 【强烈建议 false】本 App 不会主动删除任何用户数据，避免误删不可恢复。
     * 若需清理空间，由用户手动删除或通过独立清理功能操作。
     */
    public boolean deleteAfterUpload = false;

    // ==================== 单例（线程安全） ====================
    private static volatile UploadConfig instance;
    private static final Object LOCK = new Object();

    public static UploadConfig getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new UploadConfig(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * 防呆校验：自动修正所有越界/非法参数，防止 OSS 拒绝或上传异常。
     * 【调用时机】修改配置后、开始上传前。
     */
    public void validateAndFix() {
        if (ossUploadDir == null) ossUploadDir = "camera-phone-data/";
        if (!ossUploadDir.endsWith("/")) ossUploadDir += "/";
        if (ossUploadDir.startsWith("/")) ossUploadDir = ossUploadDir.substring(1);
        if (ossUploadTimeout < 10) ossUploadTimeout = 10;
        if (ossUploadTimeout > 300) ossUploadTimeout = 300;
        if (multipartPartSize < 100 * 1024) multipartPartSize = 100 * 1024;
        if (multipartPartSize > 500 * 1024 * 1024) multipartPartSize = 500 * 1024 * 1024;
        if (multipartRetryCount < 1) multipartRetryCount = 1;
        if (multipartRetryCount > 20) multipartRetryCount = 20;
        if (retryCount < 1) retryCount = 1;
        if (retryCount > 20) retryCount = 20;
    }

    /** 检查 OSS 基本配置是否完整（Endpoint/Bucket/AK/SK 均非空）。 */
    public boolean isOssConfigured() {
        return keyStore.isConfigured();
    }
}
