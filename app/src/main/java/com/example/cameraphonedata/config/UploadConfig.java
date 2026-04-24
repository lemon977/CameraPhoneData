package com.example.cameraphonedata.config;

import android.content.Context;

/**
 * 上传配置中心 —— 仅保留阿里云 OSS。
 *
 * 【工程师必读】
 * 1. 密钥通过 OssKeyStore 加密存储，本类只提供读取入口。
 * 2. 所有超时、重试、分片大小均在此调节，禁止在 OssUploadStrategy 中硬编码。
 * 3. 单例模式。
 *
 * 【20人采集场景建议】
 * - ossUploadTimeout: 工厂/野外 WiFi 不稳定，建议 80~120s。
 * - multipartPartSize: 弱网 5MB，强网 10~15MB。默认 10MB。
 * - autoUpload:  false（默认）。人工确认后再上传，避免流量失控。
 * - deleteAfterUpload: 强烈建议 false，防止误删。
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

    // ==================== OSS 连接参数（来自 BuildConfig / OssKeyStore） ====================
    /** OSS Endpoint，如 "https://oss-cn-wulanchabu.aliyuncs.com"。 */
    public String getOssEndpoint() { return keyStore.getEndpoint(); }
    /** OSS Bucket 名称。 */
    public String getOssBucketName() { return keyStore.getBucket(); }
    /** OSS AccessKey ID。 */
    public String getOssAccessKeyId() { return keyStore.getAk(); }
    /** OSS AccessKey Secret。 */
    public String getOssAccessKeySecret() { return keyStore.getSk(); }

    /**
     * STS 临时凭证 Token。
     * null 表示使用长期 AK/SK；非 null 则使用 STS 临时授权（更安全）。
     */
    public String ossStsToken = null;

    // ==================== OSS 上传路径与行为 ====================
    /**
     * OSS 远程目录前缀。
     * 不要以 "/" 开头。
     */
    public String ossUploadDir = "camera-phone-data/";

    // ==================== 超时与重试（弱网场景调这里） ====================
    /** 连接/读取超时（秒）。弱网建议 80~120。 */
    public int ossUploadTimeout = 80;
    /** 分片大小（字节）。默认 10MB。 */
    public int multipartPartSize = 10 * 1024 * 1024;
    /** 分片上传单片重试次数。 */
    public int multipartRetryCount = 5;
    /** 是否启用断点续传。大文件必开 true。 */
    public boolean enableMultipartResume = true;
    /** 简单上传重试次数。 */
    public int retryCount = 5;

    // ==================== 自动行为 ====================
    /**
     * 录制完成后是否自动上传。
     * 20人采集建议 false：由采集负责人统一点击上传，避免流量失控。
     */
    public boolean autoUpload = false;
    /**
     * 上传成功后是否自动删除本地文件。
     * 【警告】强烈建议 false。误删不可恢复。
     */
    public boolean deleteAfterUpload = false;

    // ==================== 单例 ====================
    private static UploadConfig instance;

    public static UploadConfig getInstance(Context context) {
        if (instance == null) {
            instance = new UploadConfig(context.getApplicationContext());
        }
        return instance;
    }

    /** 检查 OSS 基本配置是否完整。 */
    public boolean isOssConfigured() {
        return keyStore.isConfigured();
    }
}