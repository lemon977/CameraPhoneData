package com.example.cameraphonedata.data.upload;

import java.io.File;

/**
 * 上传策略接口
 * 支持：OSS、WiFi、云盘
 */
public interface UploadStrategy {

    /** 策略唯一标识，如 "oss", "wifi", "cloud" */
    String getName();

    /** 配置是否有效（是否可启用） */
    boolean isConfigured();

    /** 上传单个文件 */
    void upload(File file, UploadCallback callback);

    /** 上传整个文件夹（可选实现） */
    void uploadFolder(File folder, UploadCallback callback);

    /** 取消上传 */
    void cancel(String uploadId);

    /** 释放资源 */
    void release();

    interface UploadCallback {
        void onStart(String uploadId, String fileName);
        void onProgress(String uploadId, long current, long total);
        void onSuccess(String uploadId, String url);
        void onFailure(String uploadId, String error);
    }
}