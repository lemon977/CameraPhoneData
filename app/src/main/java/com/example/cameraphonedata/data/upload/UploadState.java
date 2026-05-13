package com.example.cameraphonedata.data.upload;

/**
 * 上传状态全局单例 —— 供前台服务写入、主界面轮询读取。
 * 【增强】增加 isCancelling 标志，方便 UI 判断取消中状态。
 */
public class UploadState {
    public volatile boolean isUploading = false;
    public volatile boolean isCancelling = false;
    public volatile int currentFile = 0;
    public volatile int totalFiles = 0;
    public volatile String currentFileName = "";
    public volatile long uploadedBytes = 0;
    public volatile long totalBytes = 0;
    public volatile boolean isSuccess = false;
    public volatile boolean isFailure = false;
    public volatile String errorMsg = "";
    public volatile String remoteUrl = "";

    private static class Holder {
        static final UploadState INSTANCE = new UploadState();
    }

    public static UploadState getInstance() {
        return Holder.INSTANCE;
    }

    public void reset() {
        isUploading = false;
        isCancelling = false;
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

    /** 当前是否忙碌（上传中或取消中） */
    public boolean isBusy() {
        return isUploading || isCancelling;
    }
}
