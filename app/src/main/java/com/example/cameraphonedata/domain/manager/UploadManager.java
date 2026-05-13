// ========== UploadManager.java（完整代码） ==========
package com.example.cameraphonedata.domain.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.cameraphonedata.config.FileNames;
import com.example.cameraphonedata.config.TimeConstants;
import com.example.cameraphonedata.config.UploadConfig;
import com.example.cameraphonedata.data.repository.UploadRepository;
import com.example.cameraphonedata.data.upload.OssUploadStrategy;
import com.example.cameraphonedata.data.upload.UploadRecord;
import com.example.cameraphonedata.data.upload.UploadRecordManager;
import com.example.cameraphonedata.data.upload.UploadStrategy;
import com.example.cameraphonedata.utils.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上传管理器 —— 阿里云 OSS 直传版（不打包 zip）。
 * 【单例版】全局唯一实例，防止 Activity 与 Service 各持一份导致状态错乱。
 * 【2026-04-29 修复】
 * 1. 僵尸状态自动清理：isUploading() 检测 currentFuture.isDone()，自动重置标志。
 * 2. uploadExecutor finally 中强制重置 isUploading，防止 latch.await() 永久阻塞导致状态泄漏。
 * 3. cancelUpload 增加对 currentFuture 的清理，确保取消后状态完全恢复。
 * 4. 上传完成强制刷新 100% 进度，防止 UI 卡在 99%。
 */
public class UploadManager {
    private static final String TAG = "UploadManager";
    private static volatile UploadManager instance;
    private static final Object LOCK = new Object();

    private final Context appContext;
    private final UploadRepository repository;
    private final UploadRecordManager recordManager;
    private final Handler mainHandler;
    private final ExecutorService uploadExecutor;
    private volatile boolean isUploading = false;
    private volatile boolean cancelFlag = false;
    private volatile Future<?> currentFuture;
    private volatile String currentFolderPath;
    private volatile String currentFileUploadId;

    private static class PreUploadCheckResult {
        final boolean ok;
        final String error;

        PreUploadCheckResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }
    }

    private UploadManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.repository = new UploadRepository(appContext);
        this.recordManager = new UploadRecordManager(appContext);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.uploadExecutor = Executors.newSingleThreadExecutor();
    }

    public static UploadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new UploadManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public interface UploadProgressListener {
        /**
         * 上传前准备阶段回调（metadata 重建、完整性校验、自动修复）。
         * @param status 当前正在做的事，如 "正在检查文件完整性..."
         */
        void onPreparing(String status);
        void onProgress(int currentFile, int totalFiles, String currentFileName, long uploadedBytes, long totalBytes);
        void onSuccess(String remoteUrl);
        void onFailure(String error);
    }

    /**
     * 直传日期文件夹下所有文件到 OSS，不打包 zip。
     * 【防呆】如果全局已有上传任务，直接回调 onFailure 拒绝。
     * @return Future 可用于等待/取消
     */
    public Future<?> uploadDateFolder(File dateFolder, UploadProgressListener listener) {
        // 【关键修复】僵尸状态自动检测：如果上一个任务其实已经完成了，自动清理
        if (isUploading()) {
            String busyMsg = "已有上传任务进行中";
            LogUtil.w(TAG, busyMsg + "，拒绝重复请求: " + dateFolder.getAbsolutePath());
            if (listener != null) {
                mainHandler.post(() -> listener.onFailure(busyMsg));
            }
            return null;
        }
        if (dateFolder == null || !dateFolder.isDirectory()) {
            if (listener != null) mainHandler.post(() -> listener.onFailure("文件夹无效"));
            return null;
        }

        // ===== 阶段一：上传前检查与修复 =====
        if (listener != null) {
            mainHandler.post(() -> listener.onPreparing("正在扫描文件夹..."));
        }

        PreUploadCheckResult preCheck = rebuildMetadataForUpload(dateFolder, listener);
        if (!preCheck.ok) {
            String rawErr = "上传前校验失败: " + preCheck.error;
            String userErr = toUserFriendlyUploadError(rawErr);
            LogUtil.e(TAG, rawErr);
            if (listener != null) mainHandler.post(() -> listener.onFailure(userErr));
            return null;
        }

        if (listener != null) {
            mainHandler.post(() -> listener.onPreparing("正在收集待上传文件..."));
        }
        List<File> allFiles = collectUploadFiles(dateFolder);

        // 【兜底】如果递归收集为空，尝试单层扫描 + 一级子目录扫描
        if (allFiles.isEmpty()) {
            LogUtil.w(TAG, "递归收集为空，尝试单层扫描兜底: " + dateFolder.getAbsolutePath());
            File[] direct = dateFolder.listFiles();
            if (direct != null) {
                for (File f : direct) {
                    if (f != null && f.isFile() && isUploadCandidate(f)) {
                        allFiles.add(f);
                    }
                }
            }
            if (allFiles.isEmpty()) {
                File[] subDirs = dateFolder.listFiles(File::isDirectory);
                if (subDirs != null) {
                    for (File sub : subDirs) {
                        File[] subFiles = sub.listFiles();
                        if (subFiles != null) {
                            for (File f : subFiles) {
                                if (f != null && f.isFile() && isUploadCandidate(f)) {
                                    allFiles.add(f);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (allFiles.isEmpty()) {
            String emptyMsg = "文件夹为空或无法访问: " + dateFolder.getName()
                    + "\n路径: " + dateFolder.getAbsolutePath();
            LogUtil.w(TAG, emptyMsg);
            if (listener != null) mainHandler.post(() -> listener.onFailure(emptyMsg));
            return null;
        }

        isUploading = true;
        cancelFlag = false;
        currentFolderPath = dateFolder.getAbsolutePath();

        final Future<?>[] futureBox = new Future<?>[1];
        futureBox[0] = uploadExecutor.submit(() -> {
            try {
                doDirectUpload(dateFolder, allFiles, listener);
            } catch (Exception e) {
                LogUtil.e(TAG, "上传异常", e);
                if (currentFuture == futureBox[0]) {
                    isUploading = false;
                    currentFolderPath = null;
                    currentFuture = null;
                }
                if (listener != null) {
                    String userErr = toUserFriendlyUploadError(e.getMessage());
                    mainHandler.post(() -> listener.onFailure(userErr));
                }
            } finally {
                // 【关键修复】无论 doDirectUpload 是正常结束、异常还是卡住后被中断，
                // finally 中强制重置全局标志，防止状态泄漏。
                // 身份校验：避免旧任务的 finally 抹除新任务的 currentFuture。
                if (currentFuture == futureBox[0]) {
                    isUploading = false;
                    currentFolderPath = null;
                    currentFileUploadId = null;
                    currentFuture = null;
                }
                LogUtil.i(TAG, "上传执行器 finally：isUploading 已强制重置");
            }
        });
        currentFuture = futureBox[0];
        return futureBox[0];
    }

    /**
     * 【关键修复】isUploading() 增加僵尸状态自动清理。
     * 如果 currentFuture 已经 done 但 isUploading 仍为 true（latch 永久阻塞导致），自动修正。
     */
    public boolean isUploading() {
        if (!isUploading) return false;
        if (currentFuture != null && currentFuture.isDone()) {
            LogUtil.w(TAG, "检测到僵尸上传状态（Future 已完成但标志未重置），自动清理");
            isUploading = false;
            currentFuture = null;
            currentFolderPath = null;
            currentFileUploadId = null;
            return false;
        }
        return true;
    }

    /**
     * 取消当前上传。
     * 【关键】通过 Future.cancel(true) 向执行线程发送中断信号，尽量让阻塞 IO 快速退出。
     */
    public void cancelUpload(String folderPath) {
        LogUtil.i(TAG, "收到取消请求: " + folderPath);
        cancelFlag = true;
        isUploading = false;
        OssUploadStrategy oss = getOssStrategy();
        if (oss != null && currentFileUploadId != null) {
            oss.cancel(currentFileUploadId);
        }
        if (currentFuture != null && !currentFuture.isDone()) {
            boolean cancelled = currentFuture.cancel(true);
            LogUtil.i(TAG, "Future cancel 结果: " + cancelled);
        }
        currentFolderPath = null;
        currentFuture = null;
        currentFileUploadId = null;
    }

    /**
     * 阻塞等待当前上传完全结束（最多 10 秒），用于取消后防呆。
     */
    public boolean awaitUploadFinished(long timeoutMs) {
        Future<?> future = currentFuture;
        if (future == null || future.isDone()) return true;
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            LogUtil.w(TAG, "等待上传结束超时或异常: " + e.getMessage());
            return false;
        }
    }

    public String getCurrentFolderPath() {
        return currentFolderPath;
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

    public boolean hasFolderChangedSinceUpload(File folder) {
        if (folder == null || !folder.exists()) return false;
        UploadRecord record = recordManager.getByFolder(folder.getAbsolutePath());
        if (record == null || !record.isCompleted()) return true;
        String current = buildFolderFingerprint(folder);
        return record.folderFingerprint == null || !record.folderFingerprint.equals(current);
    }

    public void release() {
        cancelFlag = true;
        isUploading = false;
        repository.release();
        uploadExecutor.shutdown();
        try {
            if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                uploadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            uploadExecutor.shutdownNow();
        }
    }

    private List<File> collectUploadFiles(File dir) {
        List<File> files = new ArrayList<>();
        collectFilesRecursive(dir, files);
        files.removeIf(f -> !isUploadCandidate(f));
        files.sort((a, b) -> {
            int pathCmp = a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            if (pathCmp != 0) return pathCmp;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return files;
    }

    private void collectFilesRecursive(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            LogUtil.w(TAG, "无法读取目录内容（可能无权限或IO错误）: " + dir.getAbsolutePath());
            return;
        }
        for (File f : children) {
            if (cancelFlag) break;
            if (f.isDirectory()) {
                collectFilesRecursive(f, files);
            } else {
                files.add(f);
            }
        }
    }

    private boolean isUploadCandidate(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        if (name.startsWith(".") || name.endsWith(".tmp")) return false;
        return name.endsWith(FileNames.VIDEO_EXTENSION)
                || FileNames.METADATA.equals(name)
                || FileNames.METADATA_HISTORY.equals(name)
                || FileNames.IMU_DATA.equals(name)
                || FileNames.IMU_CONFIG.equals(name);
    }

    private PreUploadCheckResult rebuildMetadataForUpload(File dateFolder, UploadProgressListener listener) {
        File[] sessions = dateFolder.listFiles(File::isDirectory);
        if (sessions == null) return new PreUploadCheckResult(true, null);
        int checkedSession = 0;
        int totalSessions = sessions.length;
        for (File session : sessions) {
            if (cancelFlag) return new PreUploadCheckResult(false, "上传被取消");
            checkedSession++;

            final int current = checkedSession;
            final int total = totalSessions;
            final String name = session.getName();
            if (listener != null) {
                mainHandler.post(() -> listener.onPreparing(
                        "正在检查会话 " + current + "/" + total + ": " + name));
            }

            String rebuildErr = rebuildOneSessionMetadata(session);
            if (rebuildErr != null) {
                return new PreUploadCheckResult(false, session.getName() + ": " + rebuildErr);
            }
            String integrityErr = validateSessionIntegrity(session);
            if (integrityErr != null) {
                return new PreUploadCheckResult(false, session.getName() + ": " + integrityErr);
            }
        }
        if (checkedSession == 0) {
            return new PreUploadCheckResult(false, "目录下没有会话文件夹");
        }
        return new PreUploadCheckResult(true, null);
    }

    private String rebuildOneSessionMetadata(File sessionFolder) {
        File metaFile = new File(sessionFolder, FileNames.METADATA);
        if (!metaFile.exists()) return "缺少 " + FileNames.METADATA;

        File[] mp4Files = sessionFolder.listFiles(f ->
                f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".mp4"));
        if (mp4Files == null) return "无法读取视频文件";
        List<File> videos = new ArrayList<>();
        Collections.addAll(videos, mp4Files);
        videos.sort(Comparator.comparingInt(this::extractVideoIndex)
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (videos.isEmpty()) return "没有可上传视频";

        try {
            JSONObject root = new JSONObject(readFile(metaFile));
            JSONArray oldSegments = root.optJSONArray("segments");
            Map<String, JSONObject> byFile = new HashMap<>();
            if (oldSegments != null) {
                for (int i = 0; i < oldSegments.length(); i++) {
                    JSONObject seg = oldSegments.optJSONObject(i);
                    if (seg == null) continue;
                    String name = seg.optString("file");
                    if (name != null && !name.isEmpty()) {
                        byFile.put(name, seg);
                    }
                }
            }

            JSONArray newSegments = new JSONArray();
            int idx = 1;
            long totalDurationMs = 0;
            for (File video : videos) {
                JSONObject seg = byFile.get(video.getName());
                if (seg == null) seg = new JSONObject();
                seg.put("index", idx++);
                seg.put("file", video.getName());
                seg.put("size_bytes", video.length());
                if (!seg.has("start_time_ms")) seg.put("start_time_ms", 0);
                if (!seg.has("stop_time_ms")) seg.put("stop_time_ms", 0);
                if (!seg.has("duration_ms")) seg.put("duration_ms", 0);
                long duration = seg.optLong("duration_ms", 0);
                if (duration > 0) totalDurationMs += duration;
                newSegments.put(seg);
            }
            root.put("segments", newSegments);
            JSONObject session = root.optJSONObject("session");
            if (session == null) {
                session = new JSONObject();
                root.put("session", session);
            }
            long effectiveSec = totalDurationMs / 1000;
            session.put("effective_duration_ms", totalDurationMs);
            session.put("effective_duration_sec", effectiveSec);
            root.put("session", session);
            backupMetadata(metaFile);
            writeFileAtomically(metaFile, root.toString(2));
            return null;
        } catch (Exception e) {
            LogUtil.e(TAG, "上传前重建 metadata 失败: " + sessionFolder.getName(), e);
            return "重建 metadata 失败: " + e.getMessage();
        }
    }

    /**
     * 【重写】上传前会话完整性校验 —— 自动修复模式。
     *
     * 【原则】数据安全优先，脏数据绝不传；但用户手动删视频是正常操作，自动修复即可。
     *
     * 【自动修复场景】
     * - 视频被用户手动删除 → 从 segments 移除该段，重新编号，重新计算时长
     * - 视频 0 字节/格式损坏 → 从 segments 移除该段（数据已不可用）
     * - segments 时长和不一致 → 以实际 segments 之和为准，修正 effective_duration
     *
     * 【阻止上传场景】
     * - 修复后没有任何有效视频
     * - IMU 数据严重异常（写错误、大量 NaN、丢帧严重）
     * - metadata.json 本身损坏无法解析
     */
    private String validateSessionIntegrity(File sessionFolder) {
        File metaFile = new File(sessionFolder, FileNames.METADATA);
        if (!metaFile.exists()) return FileNames.METADATA + " 不存在";

        boolean autoFixed = false;
        try {
            JSONObject root = new JSONObject(readFile(metaFile));
            JSONObject session = root.optJSONObject("session");
            if (session == null) return "metadata.session 缺失";

            JSONArray segments = root.optJSONArray("segments");
            if (segments == null || segments.length() == 0) {
                return "segments 为空";
            }

            // ===== 第一遍：扫描，标记有问题的段 =====
            JSONArray cleanSegments = new JSONArray();
            Set<String> seenNames = new HashSet<>();
            long totalDurationMs = 0;
            int removedCount = 0;

            for (int i = 0; i < segments.length(); i++) {
                JSONObject seg = segments.optJSONObject(i);
                if (seg == null) {
                    removedCount++;
                    autoFixed = true;
                    continue;
                }
                String fileName = seg.optString("file", "");
                if (fileName.isEmpty()) {
                    removedCount++;
                    autoFixed = true;
                    continue;
                }
                if (!seenNames.add(fileName)) {
                    LogUtil.w(TAG, "发现重复文件名，跳过: " + fileName);
                    removedCount++;
                    autoFixed = true;
                    continue;
                }

                File video = new File(sessionFolder, fileName);
                String problem = null;
                if (!video.exists()) {
                    problem = "视频已被删除";
                } else if (video.length() <= 0) {
                    problem = "视频0字节";
                } else if (!isMp4FormatValid(video)) {
                    problem = "视频格式损坏";
                }

                if (problem != null) {
                    LogUtil.w(TAG, "自动移除问题视频 [" + problem + "]: " + fileName);
                    removedCount++;
                    autoFixed = true;
                    continue; // 跳过该段，不上传该视频
                }

                long duration = seg.optLong("duration_ms", 0);
                if (duration < 0) duration = 0;
                totalDurationMs += duration;
                cleanSegments.put(seg);
            }

            // ===== 如果修过，重新编号并写回 metadata =====
            if (autoFixed) {
                if (cleanSegments.length() == 0) {
                    return "所有视频均已损坏或被删除，无有效数据可上传";
                }

                // 重新编号
                for (int i = 0; i < cleanSegments.length(); i++) {
                    JSONObject seg = cleanSegments.getJSONObject(i);
                    seg.put("index", i + 1);
                }

                root.put("segments", cleanSegments);

                // 修正有效时长
                long effectiveSec = totalDurationMs / 1000;
                session.put("effective_duration_ms", totalDurationMs);
                session.put("effective_duration_sec", effectiveSec);
                root.put("session", session);

                // 备份并原子写回
                backupMetadata(metaFile);
                writeFileAtomically(metaFile, root.toString(2));

                LogUtil.i(TAG, "自动修复完成: " + sessionFolder.getName()
                        + ", 移除 " + removedCount + " 个损坏段"
                        + ", 剩余 " + cleanSegments.length() + " 段"
                        + ", 有效时长=" + totalDurationMs + "ms");
            }

            // ===== 再次校验时长一致性（允许 1 秒误差）=====
            long finalEffectiveMs = root.getJSONObject("session").optLong("effective_duration_ms", 0);
            if (Math.abs(totalDurationMs - finalEffectiveMs) > 1000) {
                return "修复后时长仍不一致（内部错误）";
            }

            // ===== IMU 完整性校验（不自动修复，数据安全优先）=====
            String imuErr = validateImuIntegrity(sessionFolder);
            if (imuErr != null) return imuErr;

            return null;
        } catch (Exception e) {
            LogUtil.e(TAG, "上传前完整性校验失败: " + sessionFolder.getName(), e);
            return "metadata 校验失败: " + e.getMessage();
        }
    }

    private int extractVideoIndex(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        int under = base.lastIndexOf('_');
        if (under < 0 || under == base.length() - 1) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(base.substring(under + 1));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private String buildFolderFingerprint(File folder) {
        List<File> files = collectUploadFiles(folder);
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            String rel = getRelativePath(folder, f);
            sb.append(rel).append('|')
                    .append(f.length()).append('|')
                    .append(f.lastModified()).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    private String readFile(File file) throws Exception {
        java.io.FileInputStream fis = null;
        java.util.Scanner sc = null;
        try {
            fis = new java.io.FileInputStream(file);
            sc = new java.util.Scanner(fis, java.nio.charset.StandardCharsets.UTF_8.name());
            StringBuilder sb = new StringBuilder();
            while (sc.hasNextLine()) sb.append(sc.nextLine()).append('\n');
            return sb.toString();
        } finally {
            if (sc != null) sc.close();
            if (fis != null) fis.close();
        }
    }

    private void writeFileAtomically(File target, String content) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(tmp);
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } finally {
            if (fos != null) fos.close();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new RuntimeException("原子写 metadata 失败: " + target.getAbsolutePath());
        }
    }

    private void backupMetadata(File target) {
        try {
            File backup = new File(target.getParentFile(), "metadata.bak.json");
            String content = readFile(target);
            java.io.FileOutputStream fos = null;
            try {
                fos = new java.io.FileOutputStream(backup);
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                if (fos != null) fos.close();
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "备份 metadata 失败，不阻断上传: " + e.getMessage());
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            LogUtil.w(TAG, "SHA-256 计算失败，降级为 hashCode", e);
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 【原则】数据安全优先，但时间戳跳变/微乱序不阻断上传，只标记信任等级。
     * 物理损坏（写错误、大量 NaN/Inf）才阻止上传。
     *
     * 【自动修复】无（IMU 数据不做自动修复，避免引入伪造数据）。
     *
     * 【阻断场景】
     * - IMU 文件物理缺失或 0 字节
     * - 磁盘写入过程中发生错误（数据可能缺块）
     * - NaN/Inf 过多（传感器硬件损坏）
     *
     * 【信任等级】
     * - high:    跳变≤100，丢帧≤1000，无写错，无NaN
     * - medium:  跳变101~1000，或丢帧>1000
     * - low:     跳变>1000（多传感器微乱序常见于一加/小米/华为），但无物理损坏
     * - unreliable: 有写错误或 NaN>100
     */
    private String validateImuIntegrity(File sessionFolder) {
        File imuConfig = new File(sessionFolder, FileNames.IMU_CONFIG);
        File imuData = new File(sessionFolder, FileNames.IMU_DATA);

        // 如果根本没有 IMU 文件，视为 OK（IMU 可能被关闭）
        if (!imuConfig.exists() && !imuData.exists()) return null;

        // 有 config 但没数据文件
        if (imuConfig.exists() && !imuData.exists()) {
            return "imu_config.json 存在但 imu_data.jsonl 缺失";
        }
        // 有数据文件但没 config
        if (!imuConfig.exists() && imuData.exists()) {
            return "imu_data.jsonl 存在但 imu_config.json 缺失";
        }
        // 数据文件为空
        if (imuData.length() == 0) {
            return "imu_data.jsonl 为空（0字节）";
        }

        try {
            String cfgContent = readFile(imuConfig);
            JSONObject cfg = new JSONObject(cfgContent);

            // 1. 传感器可用性检查
            JSONObject sensors = cfg.optJSONObject("sensors");
            if (sensors != null) {
                JSONObject available = sensors.optJSONObject("available");
                if (available != null) {
                    boolean anySensor = available.optBoolean("gyroscope", false)
                            || available.optBoolean("linear_acceleration", false)
                            || available.optBoolean("rotation_vector", false)
                            || available.optBoolean("fallback_accelerometer", false)
                            || available.optBoolean("fallback_game_rotation_vector", false);
                    if (!anySensor) {
                        return "IMU 配置显示没有任何可用传感器";
                    }
                }
            }

            // 2. 读取质量统计
            JSONObject quality = cfg.optJSONObject("quality");
            long writeErrors = 0, dropped = 0, nanCount = 0, jumps = 0;
            if (quality != null) {
                writeErrors = quality.optLong("write_errors", 0);
                dropped = quality.optLong("dropped_samples", 0);
                nanCount = quality.optLong("nan_or_inf_count", 0);
                // 【修复】timestamp_jumps 现在存的是输出样本层跳变（更健康）
                // raw_timestamp_jumps 是原始事件层，仅做诊断，不参与健康判定
                jumps = quality.optLong("timestamp_jumps", 0);
            }

            // 3. 【核心】信任等级判定（机器人学习专用）
            // 【修复】优先复用 imu_config.json 中已有的 imu_quality（录制时已算好），
            // 避免重复计算，也保证 metadata.json 和 imu_config.json 数据一致。
            JSONObject existingImuQuality = cfg.optJSONObject("imu_quality");
            String trustLevel;
            long finalJumps = jumps, finalDropped = dropped, finalNan = nanCount, finalErrors = writeErrors;
            if (existingImuQuality != null) {
                trustLevel = existingImuQuality.optString("trust_level", "high");
                finalJumps = existingImuQuality.optLong("timestamp_jumps", jumps);
                finalDropped = existingImuQuality.optLong("dropped_samples", dropped);
                finalNan = existingImuQuality.optLong("nan_count", nanCount);
                finalErrors = existingImuQuality.optLong("write_errors", writeErrors);
            } else {
                // 兼容旧数据：imu_config.json 里没有 imu_quality 时自己算
                if (writeErrors > 0) {
                    trustLevel = "unreliable";
                } else if (nanCount > 100) {
                    trustLevel = "unreliable";
                } else if (dropped > 1000) {
                    trustLevel = "low";
                } else if (jumps > 1000) {
                    trustLevel = "low";
                } else if (jumps > 100) {
                    trustLevel = "medium";
                } else {
                    trustLevel = "high";
                }
            }

            // 4. 【关键】将信任等级写回 metadata.json，供后端算法读取
            File metaFile = new File(sessionFolder, FileNames.METADATA);
            if (metaFile.exists()) {
                try {
                    JSONObject meta = new JSONObject(readFile(metaFile));
                    JSONObject imuQuality = new JSONObject();
                    imuQuality.put("trust_level", trustLevel);
                    imuQuality.put("timestamp_jumps", finalJumps);
                    imuQuality.put("dropped_samples", finalDropped);
                    imuQuality.put("nan_count", finalNan);
                    imuQuality.put("write_errors", finalErrors);
                    imuQuality.put("check_time_ms", System.currentTimeMillis());
                    meta.put("imu_quality", imuQuality);
                    writeFileAtomically(metaFile, meta.toString(2));
                    LogUtil.i(TAG, "IMU 质量标记已写入: " + sessionFolder.getName()
                            + " -> trust_level=" + trustLevel);
                } catch (Exception e) {
                    LogUtil.w(TAG, "写入 IMU 质量标记失败，不阻断上传: " + e.getMessage());
                }
            }

            // 5. 【阻断条件】只有物理损坏才阻断；跳变/丢帧只降级，不阻断
            if (writeErrors > 0) {
                return "IMU 磁盘写入失败(" + writeErrors + "次)，数据可能不完整";
            }
            if (nanCount > 100) {
                return "IMU 传感器异常(NaN/Inf=" + nanCount + ")，陀螺仪可能损坏";
            }

            // 6. 【可选】output_count 与实际 jsonl 行数校验（允许 2% 误差）
            JSONObject rate = cfg.optJSONObject("sample_rate");
            if (rate != null) {
                long outputCount = rate.optLong("output_count", -1);
                if (outputCount >= 0) {
                    long actualLines = countJsonlLines(imuData);
                    long diff = Math.abs(outputCount - actualLines);
                    long threshold = Math.max(20, (long)(outputCount * 0.02));
                    if (diff > threshold) {
                        return "IMU 数据行数严重不匹配: config=" + outputCount
                                + ", 实际=" + actualLines + ", 差=" + diff;
                    }
                }
            }

            // 通过校验（跳变大也会走到这里，因为只标记不阻断）
            return null;

        } catch (Exception e) {
            LogUtil.e(TAG, "IMU 完整性校验异常: " + sessionFolder.getName(), e);
            return "IMU 校验失败: " + e.getMessage();
        }
    }

    /**
     * 【新增】统计 jsonl 文件实际行数（快速扫描，不解析 JSON）。
     */
    private long countJsonlLines(File file) {
        long lines = 0;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) lines++;
        } catch (Exception e) {
            LogUtil.w(TAG, "统计 jsonl 行数失败: " + e.getMessage());
        }
        return lines;
    }

    /**
     * 【新增】MP4 文件头格式校验。
     * 检查文件是否以 ftyp 标志开头（ISO Base Media File Format）。
     * 可拦截"有大小但已损坏"的 MP4 文件。
     */
    private boolean isMp4FormatValid(File file) {
        if (file == null || file.length() < 16) return false;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] header = new byte[16];
            int read = fis.read(header);
            if (read < 16) return false;
            // MP4 文件头: [4 bytes size] + "ftyp" + [4 bytes major_brand]
            // size 是大端序，ftyp 在偏移 4~7
            return header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        } catch (Exception e) {
            LogUtil.w(TAG, "MP4 文件头读取失败: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private String toUserFriendlyUploadError(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "上传失败：原因未知。\n请重试一次；若仍失败，请联系管理员。";
        }
        String msg = raw.toLowerCase(Locale.US);
        if (msg.contains("0 字节视频")) {
            return "上传失败：检测到损坏视频（0字节）。\n请删除该会话后重新采集。";
        }
        if (msg.contains("视频文件头损坏")) {
            return "上传失败：检测到损坏视频（MP4格式异常）。\n请删除该会话后重新采集。";
        }
        if (msg.contains("不存在的视频") || msg.contains("缺少 file")) {
            return "上传失败：会话数据不完整（视频被删除或缺失）。\n请恢复文件或删除该会话后重新采集。";
        }
        if (msg.contains("imu") || msg.contains("IMU")) {
            return "上传失败：IMU 传感器数据异常。\n" + raw + "\n请检查录制过程是否异常，或联系管理员。";
        }
        if (msg.contains("segments 为空") || msg.contains("没有可上传视频")) {
            return "上传失败：该会话没有可用视频。\n请确认录制成功后再上传。";
        }
        if (msg.contains("有效时长字段不一致") || msg.contains("时长和与有效时长不一致")) {
            return "上传失败：会话时长数据异常。\n请重新进入应用后再试；若仍失败，请联系管理员导出修复。";
        }
        if (msg.contains("metadata") && (msg.contains("失败") || msg.contains("不存在") || msg.contains("校验"))) {
            return "上传失败：元数据异常。\n请勿手动修改文件，建议联系管理员处理。";
        }
        if (msg.contains("用户取消")) {
            return "上传已取消。";
        }
        if (msg.contains("oss") || msg.contains("timeout") || msg.contains("网络") || msg.contains("上传超时")) {
            return "上传失败：网络不稳定或超时。\n请检查网络后重试。";
        }
        if (msg.contains("已有上传任务进行中")) {
            return "已有上传任务正在进行，请等待当前任务完成。";
        }
        return "上传失败：\n" + raw + "\n请重试；若连续失败，请联系管理员。";
    }

    /**
     * 逐个文件直传核心逻辑。
     * 【增强】单文件失败时进行重试，不直接报废整个批次。
     * 【修复】latch.await() 增加 5 分钟超时，防止 OSS 回调丢失导致永久阻塞。
     * 【修复】上传完成强制刷新 100% 进度，防止 UI 卡在 99%。
     */
    private void doDirectUpload(File dateFolder, List<File> files, UploadProgressListener listener) throws Exception {
        if (listener != null) {
            mainHandler.post(() -> listener.onPreparing("开始上传，共 " + files.size() + " 个文件..."));
        }
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
            if (cancelFlag || Thread.currentThread().isInterrupted()) {
                isUploading = false;
                currentFolderPath = null;
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

            // 单文件重试（整体重试，利用分片 checkpoint 实现断点续传）
            boolean fileSuccess = false;
            Exception lastFileError = null;
            for (int attempt = 0; attempt <= config.retryCount; attempt++) {
                if (cancelFlag || Thread.currentThread().isInterrupted()) break;
                if (attempt > 0) {
                    long backoffMs = (long) Math.min(TimeConstants.RETRY_BASE_BACKOFF_MS * Math.pow(2, attempt), TimeConstants.RETRY_MAX_BACKOFF_MS);
                    try { Thread.sleep(backoffMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    LogUtil.i(TAG, "文件重试 " + fileName + " 第" + attempt + "次");
                }

                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean success = new AtomicBoolean(false);
                final AtomicReference<String> errorRef = new AtomicReference<>();

                oss.upload(file, objectKey, new UploadStrategy.UploadCallback() {
                    @Override public void onStart(String uploadId, String fn) {
                        currentFileUploadId = uploadId;
                    }

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

                try {
                    // 【关键修复】latch 最多等 5 分钟，防止 OSS 回调丢失导致永久阻塞
                    boolean awaitSuccess = latch.await(TimeConstants.OSS_UPLOAD_LATCH_TIMEOUT_MIN, TimeUnit.MINUTES);
                    if (!awaitSuccess) {
                        LogUtil.e(TAG, "OSS 回调超时（5分钟）: " + fileName);
                        throw new RuntimeException("OSS 上传超时，回调未触发");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    isUploading = false;
                    currentFolderPath = null;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onFailure("用户取消");
                    });
                    return;
                }

                if (success.get()) {
                    fileSuccess = true;
                    break;
                } else {
                    lastFileError = new RuntimeException(errorRef.get() != null ? errorRef.get() : "未知错误");
                }
            }

            if (!fileSuccess) {
                isUploading = false;
                currentFolderPath = null;
                String errMsg = "文件上传失败: " + fileName;
                if (lastFileError != null) errMsg += " - " + lastFileError.getMessage();
                throw new RuntimeException(errMsg, lastFileError);
            }

            uploadedTotalBytes.addAndGet(fileSize);
        }

        // 【修复】所有文件上传完成，强制刷新一次 100% 进度，防止 UI 卡在 99%
        final long finalTotalBytes = totalBytes;
        final int finalTotalFiles = totalFiles;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onProgress(finalTotalFiles, finalTotalFiles, "上传完成", finalTotalBytes, finalTotalBytes);
            }
        });
        // 给主线程 150ms 处理进度刷新，再回调成功
        try { Thread.sleep(TimeConstants.UPLOAD_PROGRESS_FLUSH_DELAY_MS); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        isUploading = false;
        currentFolderPath = null;

        String bucket = config.getOssBucketName();
        String endpoint = config.getOssEndpoint()
                .replace("https://", "").replace("http://", "");
        String summaryUrl = "https://" + bucket + "." + endpoint + "/" + config.ossUploadDir + dateFolderName + "/";

        UploadRecord record = new UploadRecord();
        record.localFolderPath = dateFolder.getAbsolutePath();
        record.folderFingerprint = buildFolderFingerprint(dateFolder);
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
            if (rel.startsWith("\\")) rel = rel.substring(1);
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
        Future<?> future = uploadDateFolder(dateFolder, new UploadProgressListener() {
            @Override public void onPreparing(String status) {
                // SimpleUploadCallback 没有准备阶段回调，静默处理
            }
            @Override public void onProgress(int c, int t, String n, long u, long tot) {
                if (callback != null) callback.onProgress(u, tot);
            }
            @Override public void onSuccess(String url) { if (callback != null) callback.onSuccess(url); }
            @Override public void onFailure(String error) { if (callback != null) callback.onFailure(error); }
        });
        if (future == null && callback != null) {
            callback.onFailure("已有上传任务进行中");
        }
    }

    public interface SimpleUploadCallback {
        void onStart(String fileName);
        void onProgress(long current, long total);
        void onSuccess(String url);
        void onFailure(String error);
    }
}