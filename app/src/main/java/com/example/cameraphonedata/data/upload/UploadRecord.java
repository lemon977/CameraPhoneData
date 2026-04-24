package com.example.cameraphonedata.data.upload;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片上传任务记录（用于断点续传）
 */
public class UploadRecord {
    public String localFolderPath;
    public String zipPath;
    public String remoteFileName;
    public String uploadId;
    public long totalBytes;
    public long uploadedBytes;
    public int totalParts;
    public List<Integer> completedParts = new ArrayList<>();
    public String status;
    public long createTime;
    public long updateTime;
    public String errorMsg;

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public boolean isUploading() {
        return "uploading".equals(status);
    }
}