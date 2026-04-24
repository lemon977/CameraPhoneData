package com.example.cameraphonedata.data.upload;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.alibaba.sdk.android.oss.ClientConfiguration;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSPlainTextAKSKCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider;
import com.alibaba.sdk.android.oss.model.AbortMultipartUploadRequest;
import com.alibaba.sdk.android.oss.model.CompleteMultipartUploadRequest;
import com.alibaba.sdk.android.oss.model.InitiateMultipartUploadRequest;
import com.alibaba.sdk.android.oss.model.InitiateMultipartUploadResult;
import com.alibaba.sdk.android.oss.model.ListPartsRequest;
import com.alibaba.sdk.android.oss.model.ListPartsResult;
import com.alibaba.sdk.android.oss.model.PartETag;
import com.alibaba.sdk.android.oss.model.PartSummary;
import com.alibaba.sdk.android.oss.model.PutObjectRequest;
import com.alibaba.sdk.android.oss.model.UploadPartRequest;
import com.alibaba.sdk.android.oss.model.UploadPartResult;
import com.example.cameraphonedata.config.UploadConfig;
import com.example.cameraphonedata.utils.LogUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OSS 上传策略 —— 20 人采集场景优化版。
 *
 * 【网络波动应对】
 * 1. 指数退避重试：第 N 次重试等待 min(2^N 秒, 30秒)，避免拥塞雪崩。
 * 2. 大文件自动分片（默认 10MB），单片失败只重传该片，无需从头开始。
 * 3. 连接/读取超时 80s，适配弱网/工厂 WiFi/4G 边缘场景。
 * 4. 单设备并发 2，防止过多 TCP 连接被运营商或路由器限制。
 * 5. 4xx 客户端错误（如 403 签名过期）不重试，直接失败，避免无效等待。
 */
public class OssUploadStrategy implements UploadStrategy {
    private static final String TAG = "OssUploadStrategy";
    private static final long MIN_FILE_SIZE = 1024;

    private final Context context;
    private final UploadConfig config;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private volatile OSSClient ossClient;
    private volatile boolean initialized = false;

    public OssUploadStrategy(Context context) {
        this.context = context.getApplicationContext();
        this.config = UploadConfig.getInstance(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(2);
        initClient();
    }

    @SuppressWarnings("deprecation")
    private void initClient() {
        if (!config.isOssConfigured()) {
            LogUtil.w(TAG, "OSS 未配置");
            initialized = false;
            return;
        }
        try {
            OSSCredentialProvider provider;
            if (config.ossStsToken != null && !config.ossStsToken.isEmpty()) {
                provider = new OSSStsTokenCredentialProvider(
                        config.getOssAccessKeyId(),
                        config.getOssAccessKeySecret(),
                        config.ossStsToken);
            } else {
                provider = new OSSPlainTextAKSKCredentialProvider(
                        config.getOssAccessKeyId(),
                        config.getOssAccessKeySecret());
            }

            ClientConfiguration clientConfig = new ClientConfiguration();
            clientConfig.setConnectionTimeout(config.ossUploadTimeout * 1000);
            clientConfig.setSocketTimeout(config.ossUploadTimeout * 1000);
            clientConfig.setMaxConcurrentRequest(2);
            clientConfig.setMaxErrorRetry(config.retryCount);

            ossClient = new OSSClient(context, config.getOssEndpoint(), provider, clientConfig);
            initialized = true;
            LogUtil.i(TAG, "OSS 客户端初始化完成，Bucket=" + config.getOssBucketName());
        } catch (Exception e) {
            LogUtil.e(TAG, "OSS 初始化失败", e);
            initialized = false;
        }
    }

    @Override
    public String getName() { return "oss"; }

    @Override
    public boolean isConfigured() {
        return initialized && config.isOssConfigured();
    }

    @Override
    public void upload(File file, UploadCallback callback) {
        String dateDir = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        String objectKey = config.ossUploadDir + dateDir + "/" + file.getName();
        upload(file, objectKey, callback);
    }

    public void upload(File file, String objectKey, UploadCallback callback) {
        if (!checkPreconditions(file, callback)) return;

        long fileSize = file.length();
        int partSize = config.multipartPartSize;

        if (fileSize > partSize) {
            executor.execute(() -> doMultipartUpload(file, objectKey, callback));
        } else {
            executor.execute(() -> doSimpleUpload(file, objectKey, callback));
        }
    }

    @Override
    public void uploadFolder(File folder, UploadCallback callback) {
        if (folder == null || !folder.isDirectory()) {
            if (callback != null) mainHandler.post(() -> callback.onFailure("", "不是有效目录"));
            return;
        }
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) upload(f, callback);
        }
    }

    // ========== 分片上传接口（供断点续传使用） ==========

    public String initiateMultipartUpload(String objectKey) throws Exception {
        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(config.getOssBucketName(), objectKey);
        InitiateMultipartUploadResult result = ossClient.initMultipartUpload(request);
        return result.getUploadId();
    }

    public PartETag uploadPart(String objectKey, String uploadId, int partNumber, byte[] data) throws Exception {
        UploadPartRequest request = new UploadPartRequest(config.getOssBucketName(), objectKey, uploadId, partNumber);
        request.setPartContent(data);
        UploadPartResult result = ossClient.uploadPart(request);
        return new PartETag(partNumber, result.getETag());
    }

    public List<PartETag> listUploadedParts(String objectKey, String uploadId) {
        List<PartETag> list = new ArrayList<>();
        try {
            ListPartsRequest request = new ListPartsRequest(config.getOssBucketName(), objectKey, uploadId);
            ListPartsResult result = ossClient.listParts(request);
            for (PartSummary part : result.getParts()) {
                list.add(new PartETag(part.getPartNumber(), part.getETag()));
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "查询已上传分片失败: " + e.getMessage());
        }
        return list;
    }

    public void completeMultipartUpload(String objectKey, String uploadId, List<PartETag> partETags) throws Exception {
        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(
                config.getOssBucketName(), objectKey, uploadId, partETags);
        ossClient.completeMultipartUpload(request);
    }

    public void abortMultipartUpload(String objectKey, String uploadId) {
        try {
            AbortMultipartUploadRequest abortRequest = new AbortMultipartUploadRequest(
                    config.getOssBucketName(), objectKey, uploadId);
            ossClient.abortMultipartUpload(abortRequest);
        } catch (Exception e) {
            LogUtil.w(TAG, "取消分片上传失败" + e.getMessage());
        }
    }

    // ========== 内部方法 ==========

    private boolean checkPreconditions(File file, UploadCallback callback) {
        if (!initialized) {
            if (callback != null) mainHandler.post(() -> callback.onFailure("", "OSS 未初始化"));
            return false;
        }
        if (file == null || !file.exists()) {
            if (callback != null) mainHandler.post(() -> callback.onFailure("", "文件不存在"));
            return false;
        }
        if (file.length() < MIN_FILE_SIZE) {
            if (callback != null) mainHandler.post(() -> callback.onFailure("", "文件太小"));
            return false;
        }
        if (!isNetworkAvailable()) {
            if (callback != null) mainHandler.post(() -> callback.onFailure("", "无网络连接"));
            return false;
        }
        return true;
    }

    /**
     * 简单上传 —— 带指数退避重试。
     * 4xx 错误不重试（通常是配置问题）。
     */
    private void doSimpleUpload(File file, String objectKey, UploadCallback callback) {
        String uploadId = UUID.randomUUID().toString();
        String fileName = file.getName();
        String url = buildUrl(objectKey);

        mainHandler.post(() -> {
            if (callback != null) callback.onStart(uploadId, fileName);
        });

        int attempt = 0;
        Exception lastError = null;
        while (attempt <= config.retryCount) {
            if (attempt > 0) {
                long backoffMs = (long) Math.min(1000 * Math.pow(2, attempt), 30000);
                try { Thread.sleep(backoffMs); } catch (InterruptedException e) { break; }
            }
            try {
                PutObjectRequest request = new PutObjectRequest(config.getOssBucketName(), objectKey, file.getAbsolutePath());
                request.setProgressCallback((req, currentSize, totalSize) ->
                        mainHandler.post(() -> {
                            if (callback != null) callback.onProgress(uploadId, currentSize, totalSize);
                        })
                );
                ossClient.putObject(request);
                LogUtil.i(TAG, "简单上传成功: " + fileName);
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(uploadId, url);
                });
                return;
            } catch (ServiceException e) {
                lastError = e;
                LogUtil.e(TAG, "OSS 服务错误: " + e.getErrorCode() + ", HTTP=" + e.getStatusCode());
                if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) break;
            } catch (Exception e) {
                lastError = e;
                LogUtil.e(TAG, "上传异常: " + fileName, e);
            }
            attempt++;
        }

        final String errMsg = lastError != null ? lastError.getMessage() : "上传失败（重试耗尽）";
        mainHandler.post(() -> {
            if (callback != null) callback.onFailure(uploadId, errMsg);
        });
    }

    /**
     * 分片上传 —— 大文件专用，单片失败只重传该片。
     */
    @SuppressWarnings("BusyWait")
    private void doMultipartUpload(File file, String objectKey, UploadCallback callback) {
        String uploadId = UUID.randomUUID().toString();
        String fileName = file.getName();
        long fileSize = file.length();
        int partSize = config.multipartPartSize;
        int totalParts = (int) ((fileSize + partSize - 1) / partSize);
        if (totalParts == 0) totalParts = 1;

        mainHandler.post(() -> {
            if (callback != null) callback.onStart(uploadId, fileName);
        });

        long uploadStartTime = System.currentTimeMillis();
        try {
            String multipartUploadId = initiateMultipartUpload(objectKey);
            List<PartETag> partETags = new ArrayList<>();
            byte[] buffer = new byte[partSize];

            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
                    long offset = (long) (partNumber - 1) * partSize;
                    raf.seek(offset);
                    int read = raf.read(buffer);
                    if (read <= 0) break;

                    byte[] partData = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);

                    com.alibaba.sdk.android.oss.model.PartETag etag = null;
                    Exception lastErr = null;
                    for (int retry = 0; retry < config.multipartRetryCount; retry++) {
                        if (retry > 0) {
                            long backoffMs = (long) Math.min(1000 * Math.pow(2, retry), 30000);
                            Thread.sleep(backoffMs);
                        }
                        try {
                            etag = uploadPart(objectKey, multipartUploadId, partNumber, partData);
                            break;
                        } catch (ServiceException e) {
                            lastErr = e;
                            LogUtil.w(TAG, "分片 " + partNumber + " 服务错误: " + e.getErrorCode());
                            if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) break;
                        } catch (Exception e) {
                            lastErr = e;
                            LogUtil.w(TAG, "分片 " + partNumber + " 失败，第" + (retry + 1) + "次重试: " + e.getMessage());
                        }
                    }

                    if (etag == null) {
                        throw new RuntimeException("分片 " + partNumber + " 上传失败: " +
                                (lastErr != null ? lastErr.getMessage() : "未知错误"));
                    }

                    partETags.add(etag);
                    long uploadedBytes = Math.min((long) partNumber * partSize, fileSize);
                    final long currentBytes = uploadedBytes;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(uploadId, currentBytes, fileSize);
                    });
                }
            }

            completeMultipartUpload(objectKey, multipartUploadId, partETags);
            long costSec = (System.currentTimeMillis() - uploadStartTime) / 1000;
            String url = buildUrl(objectKey);
            LogUtil.i(TAG, "分片上传成功: " + fileName + ", 总耗时" + costSec + "s");
            mainHandler.post(() -> {
                if (callback != null) callback.onSuccess(uploadId, url);
            });

        } catch (Exception e) {
            LogUtil.e(TAG, "分片上传失败: " + fileName, e);
            mainHandler.post(() -> {
                if (callback != null) callback.onFailure(uploadId, e.getMessage());
            });
        }
    }

    private String buildUrl(String objectKey) {
        String ep = config.getOssEndpoint();
        if (ep.startsWith("https://")) ep = ep.substring(8);
        else if (ep.startsWith("http://")) ep = ep.substring(7);
        return "https://" + config.getOssBucketName() + "." + ep + "/" + objectKey;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        );
    }

    @Override
    public void cancel(String uploadId) {}

    @Override
    public void release() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        ossClient = null;
        initialized = false;
    }
}