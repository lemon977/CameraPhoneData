package com.example.cameraphonedata.domain.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.cameraphonedata.config.UploadConfig;
import com.example.cameraphonedata.data.repository.UploadRepository;
import com.example.cameraphonedata.data.upload.OssUploadStrategy;
import com.example.cameraphonedata.data.upload.UploadRecord;
import com.example.cameraphonedata.data.upload.UploadRecordManager;
import com.example.cameraphonedata.data.upload.UploadStrategy;
import com.example.cameraphonedata.utils.LogUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上传管理器 —— 阿里云 OSS 直传版（不打包 zip）。
 *
 * 【20人采集网络波动应对】
 * 1. 不再打包 zip：MP4 已是压缩格式，zip 无收益且浪费 CPU/磁盘/时间。
 * 2. 逐个文件上传：失败只影响单个文件，不导致整包报废。
 * 3. 【关键修复】删除"文件夹已完成则跳过"逻辑：避免同一日期文件夹后续追加数据被漏传。
 *    OSS 同名文件直接覆盖，重复上传不会导致数据错误，仅浪费少量流量。
 * 4. 每文件使用 CountDownLatch 同步等待，确保顺序可控。
 */
public class UploadManager {
    private static final String TAG = "UploadManager";

    private final Context appContext;
    private final UploadRepository repository;
    private final UploadRecordManager recordManager;
    private final Handler mainHandler;
    private final ExecutorService uploadExecutor;
    private volatile boolean isUploading = false;
    private volatile boolean cancelFlag = false;

    public UploadManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.repository = new UploadRepository(appContext);
        this.recordManager = new UploadRecordManager(appContext);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.uploadExecutor = Executors.newSingleThreadExecutor();
    }

    public interface UploadProgressListener {
        void onProgress(int currentFile, int totalFiles, String currentFileName, long uploadedBytes, long totalBytes);
        void onSuccess(String remoteUrl);
        void onFailure(String error);
    }

    /**
     * 直传日期文件夹下所有文件到 OSS，不打包 zip。
     * 【注意】不跳过任何文件，确保追加数据也能上传（OSS 同名覆盖）。
     */
    public void uploadDateFolder(File dateFolder, UploadProgressListener listener) {
        if (isUploading) {
            if (listener != null) listener.onFailure("已有上传任务进行中");
            return;
        }
        if (dateFolder == null || !dateFolder.isDirectory()) {
            if (listener != null) listener.onFailure("文件夹无效");
            return;
        }

        List<File> allFiles = collectFilesRecursive(dateFolder);
        if (allFiles.isEmpty()) {
            if (listener != null) listener.onFailure("文件夹为空");
            return;
        }

        isUploading = true;
        cancelFlag = false;

        uploadExecutor.execute(() -> {
            try {
                doDirectUpload(dateFolder, allFiles, listener);
            } catch (Exception e) {
                LogUtil.e(TAG, "上传异常", e);
                isUploading = false;
                if (listener != null) {
                    mainHandler.post(() -> listener.onFailure(e.getMessage()));
                }
            }
        });
    }

    public void cancelUpload(String folderPath) {
        cancelFlag = true;
        isUploading = false;
    }

    public boolean isUploading() {
        return isUploading;
    }

    public boolean hasUploadedButNotDeletedFolders() {
        List<UploadRecord> completed = recordManager.getCompletedRecords();
        for (UploadRecord r : completed) {
            if (new File(r.localFolderPath).exists()) return true;
        }
        return false;
    }

    public List<String> getUploadedButNotDeletedPaths() {
        List<String> list = new ArrayList<>();
        List<UploadRecord> completed = recordManager.getCompletedRecords();
        for (UploadRecord r : completed) {
            if (new File(r.localFolderPath).exists()) list.add(r.localFolderPath);
        }
        return list;
    }

    public void clearDeletedRecords() {
        List<UploadRecord> completed = recordManager.getCompletedRecords();
        for (UploadRecord r : completed) {
            boolean folderExists = r.localFolderPath != null && new File(r.localFolderPath).exists();
            if (!folderExists) {
                recordManager.removeByFolder(r.localFolderPath);
            }
        }
    }

    public UploadRecord getUploadRecord(String folderPath) {
        return recordManager.getByFolder(folderPath);
    }

    public void release() {
        cancelFlag = true;
        isUploading = false;
        repository.release();
        uploadExecutor.shutdown();
    }

    private List<File> collectFilesRecursive(File dir) {
        List<File> files = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return files;
        for (File f : children) {
            if (cancelFlag) break;
            if (f.isDirectory()) {
                files.addAll(collectFilesRecursive(f));
            } else {
                String name = f.getName();
                if (!name.startsWith(".") && !name.endsWith(".tmp")) {
                    files.add(f);
                }
            }
        }
        return files;
    }

    /**
     * 逐个文件直传核心逻辑。
     * 【20人优化】不再跳过"已上传"文件，防止追加数据漏传；OSS 同名覆盖即可。
     */
    private void doDirectUpload(File dateFolder, List<File> files, UploadProgressListener listener) throws Exception {
        UploadConfig config = UploadConfig.getInstance(appContext);
        OssUploadStrategy oss = getOssStrategy();
        if (oss == null) throw new RuntimeException("OSS 未初始化");

        final String dateFolderName = dateFolder.getName();
        long totalBytesCalc = 0;
        for (File f : files) totalBytesCalc += f.length();
        final long totalBytes = totalBytesCalc;
        final AtomicLong uploadedTotalBytes = new AtomicLong(0);
        final int totalFiles = files.size();

        for (int i = 0; i < files.size(); i++) {
            if (cancelFlag) {
                isUploading = false;
                mainHandler.post(() -> {
                    if (listener != null) listener.onFailure("用户取消");
                });
                return;
            }

            final File file = files.get(i);
            final int fileIndex = i + 1;
            final String fileName = file.getName();
            final long fileSize = file.length();
            final String relativePath = getRelativePath(dateFolder, file);
            final String objectKey = config.ossUploadDir + dateFolderName + "/" + relativePath;

            // 【删除】不再以文件夹粒度跳过文件，确保追加数据不丢失
            // OSS 同名覆盖是安全操作

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            final AtomicReference<String> errorRef = new AtomicReference<>();

            oss.upload(file, objectKey, new UploadStrategy.UploadCallback() {
                @Override public void onStart(String uploadId, String fn) {}

                @Override public void onProgress(String uploadId, long current, long total) {
                    long overall = uploadedTotalBytes.get() + current;
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onProgress(fileIndex, totalFiles, fileName, overall, totalBytes);
                        }
                    });
                }

                @Override public void onSuccess(String uploadId, String url) {
                    success.set(true);
                    latch.countDown();
                }

                @Override public void onFailure(String uploadId, String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });

            latch.await();

            if (!success.get()) {
                String err = errorRef.get() != null ? errorRef.get() : "未知错误";
                throw new RuntimeException("文件上传失败: " + fileName + " - " + err);
            }

            uploadedTotalBytes.addAndGet(fileSize);
        }

        isUploading = false;

        String bucket = config.getOssBucketName();
        String endpoint = config.getOssEndpoint()
                .replace("https://", "").replace("http://", "");
        String summaryUrl = "https://" + bucket + "." + endpoint + "/" + config.ossUploadDir + dateFolderName + "/";

        UploadRecord record = new UploadRecord();
        record.localFolderPath = dateFolder.getAbsolutePath();
        record.remoteFileName = summaryUrl;
        record.status = "completed";
        record.totalBytes = totalBytes;
        record.uploadedBytes = totalBytes;
        record.createTime = System.currentTimeMillis();
        recordManager.saveRecord(record);

        mainHandler.post(() -> {
            if (listener != null) listener.onSuccess(summaryUrl);
        });
    }

    private String getRelativePath(File baseDir, File file) {
        String base = baseDir.getAbsolutePath();
        String full = file.getAbsolutePath();
        if (full.startsWith(base)) {
            String rel = full.substring(base.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }
        return file.getName();
    }

    private OssUploadStrategy getOssStrategy() {
        UploadStrategy s = repository.getStrategyByName("oss");
        return (s instanceof OssUploadStrategy) ? (OssUploadStrategy) s : null;
    }

    public void uploadSession(File sessionFolder, SimpleUploadCallback callback) {
        if (sessionFolder == null || !sessionFolder.isDirectory()) {
            if (callback != null) callback.onFailure("目录无效");
            return;
        }
        File dateFolder = sessionFolder.getParentFile();
        if (dateFolder == null) dateFolder = sessionFolder;
        uploadDateFolder(dateFolder, new UploadProgressListener() {
            @Override public void onProgress(int c, int t, String n, long u, long tot) {}
            @Override public void onSuccess(String url) { callback.onSuccess(url); }
            @Override public void onFailure(String error) { callback.onFailure(error); }
        });
    }

    public interface SimpleUploadCallback {
        void onStart(String fileName);
        void onProgress(long current, long total);
        void onSuccess(String url);
        void onFailure(String error);
    }
}