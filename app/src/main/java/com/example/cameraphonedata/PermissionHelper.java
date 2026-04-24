package com.example.cameraphonedata;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 权限申请助手
 * 职责：统一处理运行时权限申请、rationale弹窗、被拒绝引导
 */
public class PermissionHelper {
    private static final int PERMISSION_CODE = 1001;

    private final AppCompatActivity activity;
    private final String[] permissions;
    private final Runnable onAllGranted;

    public PermissionHelper(AppCompatActivity activity, String[] permissions, Runnable onAllGranted) {
        this.activity = activity;
        this.permissions = permissions;
        this.onAllGranted = onAllGranted;
    }

    /** 检查权限，未授予则申请 */
    public void checkAndRequest() {
        if (hasAllPermissions()) {
            onAllGranted.run();
        } else {
            requestPermissionsWithRationale();
        }
    }

    /** 必须在 Activity.onRequestPermissionsResult 中调用 */
    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != PERMISSION_CODE) return;
        boolean allGranted = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            onAllGranted.run();
        } else {
            showPermissionDeniedDialog();
        }
    }

    private boolean hasAllPermissions() {
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissionsWithRationale() {
        boolean shouldShow = false;
        for (String p : permissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, p)) {
                shouldShow = true;
                break;
            }
        }
        if (shouldShow) {
            new AlertDialog.Builder(activity)
                    .setTitle("需要权限")
                    .setMessage("需要相机和录音权限才能录制视频")
                    .setPositiveButton("授予", (d, w) ->
                            ActivityCompat.requestPermissions(activity, permissions, PERMISSION_CODE))
                    .setNegativeButton("拒绝", (d, w) -> activity.finish())
                    .setCancelable(false).show();
        } else {
            ActivityCompat.requestPermissions(activity, permissions, PERMISSION_CODE);
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("权限被拒绝")
                .setMessage("应用无法在没有权限的情况下运行")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                })
                .setNegativeButton("退出", (d, w) -> activity.finish())
                .setCancelable(false).show();
    }
}