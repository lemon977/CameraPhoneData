package com.example.cameraphonedata.data.upload;

import android.content.Context;
import com.example.cameraphonedata.utils.LogUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 上传记录本地持久化（JSON 文件）
 */
public class UploadRecordManager {
    private static final String TAG = "UploadRecordManager";
    private static final String RECORD_FILE = "upload_records.json";
    private final File recordFile;
    private final List<UploadRecord> records = new ArrayList<>();

    public UploadRecordManager(Context context) {
        this.recordFile = new File(context.getFilesDir(), RECORD_FILE);
        load();
    }

    public synchronized void saveRecord(UploadRecord record) {
        removeByFolder(record.localFolderPath);
        records.add(record);
        persist();
    }

    public synchronized UploadRecord getByFolder(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) return null;
        for (UploadRecord r : records) {
            if (r != null && folderPath.equals(r.localFolderPath)) return r;
        }
        return null;
    }

    public synchronized void removeByFolder(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) return;
        records.removeIf(r -> r != null && folderPath.equals(r.localFolderPath));
        persist();
    }

    public synchronized List<UploadRecord> getCompletedRecords() {
        List<UploadRecord> list = new ArrayList<>();
        for (UploadRecord r : records) {
            if (r.isCompleted()) list.add(r);
        }
        return list;
    }

    // ========== UploadRecordManager.java（仅展示 load 修复） ==========
    private void load() {
        if (!recordFile.exists()) return;
        try (FileInputStream fis = new FileInputStream(recordFile);
             Scanner sc = new Scanner(fis)) {
            StringBuilder sb = new StringBuilder();
            while (sc.hasNextLine()) sb.append(sc.nextLine());
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                records.add(parse(arr.getJSONObject(i)));
            }

            // 兜底清理：清理无效记录
            boolean removed = records.removeIf(r -> {
                // 【修复】增加 null 防护
                if (r == null) return true;
                boolean folderExists = r.localFolderPath != null && new File(r.localFolderPath).exists();
                boolean zipExists = r.zipPath != null && !r.zipPath.isEmpty() && new File(r.zipPath).exists();
                return !folderExists && !zipExists;
            });
            if (removed) {
                persist();
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "加载记录失败", e);
        }
    }

    /**
     * 原子写：先写入临时文件，再通过 rename 覆盖原文件。
     * 防止进程崩溃/被杀时记录文件被截断损坏。
     */
    private void persist() {
        File tempFile = new File(recordFile.getParent(), recordFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            JSONArray arr = new JSONArray();
            for (UploadRecord r : records) {
                arr.put(toJson(r));
            }
            fos.write(arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync(); // 强制刷盘，防止断电丢失
        } catch (Exception e) {
            LogUtil.e(TAG, "保存记录到临时文件失败", e);
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            return;
        }
        // 原子重命名：要么完整写入，要么保持旧文件不变
        if (!tempFile.renameTo(recordFile)) {
            LogUtil.e(TAG, "原子重命名记录文件失败");
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    private JSONObject toJson(UploadRecord r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("localFolderPath", r.localFolderPath);
        o.put("folderFingerprint", r.folderFingerprint);
        o.put("zipPath", r.zipPath);
        o.put("remoteFileName", r.remoteFileName);
        o.put("uploadId", r.uploadId);
        o.put("totalBytes", r.totalBytes);
        o.put("uploadedBytes", r.uploadedBytes);
        o.put("totalParts", r.totalParts);
        JSONArray parts = new JSONArray();
        for (int p : r.completedParts) parts.put(p);
        o.put("completedParts", parts);
        o.put("status", r.status);
        o.put("createTime", r.createTime);
        o.put("updateTime", r.updateTime);
        o.put("errorMsg", r.errorMsg);
        return o;
    }

    private UploadRecord parse(JSONObject o) throws Exception {
        UploadRecord r = new UploadRecord();
        r.localFolderPath = o.optString("localFolderPath");
        r.folderFingerprint = o.optString("folderFingerprint");
        r.zipPath = o.optString("zipPath");
        r.remoteFileName = o.optString("remoteFileName");
        r.uploadId = o.optString("uploadId");
        r.totalBytes = o.optLong("totalBytes");
        r.uploadedBytes = o.optLong("uploadedBytes");
        r.totalParts = o.optInt("totalParts");
        JSONArray parts = o.optJSONArray("completedParts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                r.completedParts.add(parts.getInt(i));
            }
        }
        r.status = o.optString("status");
        r.createTime = o.optLong("createTime");
        r.updateTime = o.optLong("updateTime");
        r.errorMsg = o.optString("errorMsg");
        return r;
    }
}