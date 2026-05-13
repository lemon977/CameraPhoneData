package com.example.cameraphonedata;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 权限申请助手
 * 职责：统一处理运行时权限申请、rationale弹窗、被拒绝引导、电池优化白名单。
 *
 * 【2026-05-11 修复】
 * 1. 新增 Android 13+ (API 33) POST_NOTIFICATIONS 权限申请。
 * 2. 新增电池优化白名单引导（采集 3-4 小时必需，否则系统随时杀死前台服务）。
 * 3. 权限被拒绝后不再直接 finish()，给用户二次确认机会。
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
            checkBatteryOptimization(() -> onAllGranted.run());
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
            checkBatteryOptimization(() -> onAllGranted.run());
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
                    .setMessage("需要相机、录音和通知权限才能正常使用应用。通知权限用于显示录制状态，确保后台不被系统杀死。")
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
                .setMessage("应用无法在没有权限的情况下运行。请前往设置开启所需权限。")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                })
                .setNegativeButton("退出", (d, w) -> activity.finish())
                .setCancelable(false).show();
    }

    // ==================== 电池优化白名单 ====================

    /**
     * 检查是否已加入电池优化白名单。
     * 如果未加入，弹窗引导用户手动开启（Android 系统限制，无法直接申请，只能跳设置页）。
     */
    private void checkBatteryOptimization(Runnable onGranted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            onGranted.run();
            return;
        }

        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            onGranted.run();
            return;
        }

        if (pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
            // 已在白名单
            onGranted.run();
            return;
        }

        // 未在白名单，引导用户
        new AlertDialog.Builder(activity)
                .setTitle("需要关闭电池优化")
                .setMessage("为保证长时间录制（3-4小时）不被系统中途杀死，请将此应用加入电池优化白名单。\n\n"
                        + "点击'去设置'" + "→" + "找到'电池优化'" + "选择'不优化'")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    try {
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        // 部分国产 ROM 不支持此 Intent， fallback 到应用详情
                        Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        fallback.setData(Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(fallback);
                    }
                    // 不阻塞，用户设置完后需自行重启或下次进入会再检查
                    onGranted.run();
                })
                .setNegativeButton("暂不去", (d, w) -> {
                    // 用户选择不去，仍然允许进入，但提示风险
                    new AlertDialog.Builder(activity)
                            .setTitle("⚠️ 风险提示")
                            .setMessage("不关闭电池优化，录制过程中可能被系统杀死，导致数据丢失。建议尽快前往设置开启。")
                            .setPositiveButton("我知道了", (d2, w2) -> onGranted.run())
                            .setCancelable(false)
                            .show();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 获取推荐的权限列表（根据 Android 版本自动适配）。
     */
    public static String[] getRequiredPermissions() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.WAKE_LOCK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要运行时申请通知权限
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        return perms.toArray(new String[0]);
    }
}
