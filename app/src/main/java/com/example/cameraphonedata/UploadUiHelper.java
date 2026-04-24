package com.example.cameraphonedata;

import android.content.Context;
import android.content.Intent; // 【新增】修复 Cannot resolve symbol 'Intent'
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.domain.manager.UploadManager;
import com.example.cameraphonedata.service.UploadForegroundService;
import com.example.cameraphonedata.utils.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传UI助手
 * 修复：大文件上传改为前台服务，用户无需在App内等待，支持锁屏/后台续传
 */
public class UploadUiHelper {
    private final Context context;
    // 【删除】uiManager 字段从未使用，改为局部变量或删除避免警告
    private final UploadManager uploadManager;

    public UploadUiHelper(Context context, UploadManager uploadManager) {
        this.context = context;
        this.uploadManager = uploadManager;
    }

    /**
     * 检查录制限制（上传锁定）
     * 【保留】供 MainActivity 调用，虽然当前未显式调用，但属于预留接口
     */
    @SuppressWarnings("unused")
    public void checkRecordingRestriction() {
        uploadManager.clearDeletedRecords();
    }

    public void showRecordingBlockedDialog() {
        List<String> paths = uploadManager.getUploadedButNotDeletedPaths();
        StringBuilder sb = new StringBuilder();
        for (String p : paths) sb.append("• ").append(p).append("\n\n");
        new android.app.AlertDialog.Builder(context)
                .setTitle("⚠️ 录制功能已锁定")
                .setMessage("检测到已上传但未删除的本地文件，请先手动删除以释放空间。\n\n" +
                        "如已删除文件但按钮仍锁定，关闭App重新进入即可恢复。\n\n" +
                        "文件路径：\n" + sb)
                .setPositiveButton("我知道了", null)
                .show();
    }

    public void showDateFolderUploadDialog() {
        uploadManager.clearDeletedRecords();

        File baseDir = new StorageManager(context).getBaseDir(DataConfig.getInstance().baseFolderName);
        if (baseDir == null || !baseDir.exists()) {
            Toast.makeText(context, "存储目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] folders = baseDir.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            Toast.makeText(context, "没有可上传的日期文件夹", Toast.LENGTH_SHORT).show();
            return;
        }

        List<File> pendingFolders = new ArrayList<>();
        for (File f : folders) {
            com.example.cameraphonedata.data.upload.UploadRecord r = uploadManager.getUploadRecord(f.getAbsolutePath());
            if (r == null || !r.isCompleted()) {
                pendingFolders.add(f);
            }
        }

        if (pendingFolders.isEmpty()) {
            Toast.makeText(context, "所有文件夹均已上传，请手动删除本地文件后继续录制", Toast.LENGTH_LONG).show();
            return;
        }

        String[] names = new String[pendingFolders.size()];
        for (int i = 0; i < pendingFolders.size(); i++) names[i] = pendingFolders.get(i).getName();

        new android.app.AlertDialog.Builder(context)
                .setTitle("选择要上传的日期文件夹")
                .setItems(names, (d, which) -> startBackgroundUpload(pendingFolders.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 【关键改造】启动前台服务进行后台上传，不再阻塞UI
     */
    private void startBackgroundUpload(File dateFolder) {
        Intent intent = new Intent(context, UploadForegroundService.class);
        intent.setAction(UploadForegroundService.ACTION_UPLOAD);
        intent.putExtra(UploadForegroundService.EXTRA_FOLDER_PATH, dateFolder.getAbsolutePath());
        ContextCompat.startForegroundService(context, intent);

        Toast.makeText(context,
                "后台上传已启动：「" + dateFolder.getName() + "」\n请查看通知栏进度，可锁屏或切换应用",
                Toast.LENGTH_LONG).show();
    }
}