package com.example.cameraphonedata.data.upload;

/**
 * 上传状态全局单例 —— 供前台服务写入、主界面轮询读取。
 */
public class UploadState {
    private static UploadState instance;

    public volatile boolean isUploading = false;
    public volatile int currentFile = 0;
    public volatile int totalFiles = 0;
    public volatile String currentFileName = "";
    public volatile long uploadedBytes = 0;
    public volatile long totalBytes = 0;
    public volatile boolean isSuccess = false;
    public volatile boolean isFailure = false;
    public volatile String errorMsg = "";
    public volatile String remoteUrl = "";

    public static UploadState getInstance() {
        if (instance == null) instance = new UploadState();
        return instance;
    }

    public void reset() {
        isUploading = false;
        currentFile = 0;
        totalFiles = 0;
        currentFileName = "";
        uploadedBytes = 0;
        totalBytes = 0;
        isSuccess = false;
        isFailure = false;
        errorMsg = "";
        remoteUrl = "";
    }
}