package com.example.cameraphonedata;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Size;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.cameraphonedata.alert.VoicePromptManager;
import com.example.cameraphonedata.calibration.CalibrationJsonImporter;
import com.example.cameraphonedata.camera.CameraDebugDumper;
import com.example.cameraphonedata.camera.CameraManager;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.AccountConfig;
import com.example.cameraphonedata.config.AccountManager;
import com.example.cameraphonedata.config.CalibrationConfig;
import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.config.FileNames;
import com.example.cameraphonedata.config.UploadConfig;
import com.example.cameraphonedata.data.EffectiveDurationManager;
import com.example.cameraphonedata.data.repository.FileRepository;
import com.example.cameraphonedata.data.upload.UploadRecord;
import com.example.cameraphonedata.data.upload.UploadState;
import com.example.cameraphonedata.domain.manager.HandDetectionManager;
import com.example.cameraphonedata.domain.manager.RecordingManager;
import com.example.cameraphonedata.domain.manager.UploadManager;
import com.example.cameraphonedata.service.UploadForegroundService;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 主界面 —— 全屏沉浸横屏版。
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private PreviewView previewView;
    private MainUiManager uiManager;
    private PermissionHelper permissionHelper;
    private RecordingCoordinator recordingCoordinator;

    private CameraManager cameraManager;
    private RecordingManager recordingManager;
    private UploadManager uploadManager;
    private HandDetectionManager handDetectionManager;
    private CalibrationData calibrationData;
    private VoicePromptManager voicePromptManager;
    private AccountManager accountManager;
    private EffectiveDurationManager effectiveDurationManager;

    private CameraParamReader.CameraParams lastCameraParams;
    private ActivityResultLauncher<Intent> calibrationLauncher;
    private CameraConfig.LensRole lensRoleBeforeCalibration = CameraConfig.LensRole.WIDE;
    private boolean shouldRestoreLensAfterCalibration = false;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Handler uploadPollHandler = new Handler(Looper.getMainLooper());
    private Runnable uploadPollRunnable;
    private AlertDialog uploadProgressDialog;
    private ProgressBar uploadProgressBar;
    private TextView uploadProgressTextView;

    private long lastRecordActionTime = 0;
    private boolean fallbackDialogShown = false;

    private File currentUploadFolder;

    private static String[] getPermissions() {
        return PermissionHelper.getRequiredPermissions();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterFullscreen();
        setContentView(R.layout.activity_main);

        CalibrationJsonImporter.importFromJson(this);

        calibrationLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    CameraConfig cfg = CameraConfig.getInstance();
                    if (shouldRestoreLensAfterCalibration) {
                        cfg.currentLensRole = lensRoleBeforeCalibration;
                    }
                    // 【关键修复】从标定界面返回后，无论成功/取消/返回键，
                    // 强制停止并重启主界面相机，防止因生命周期交错导致黑屏
                    cameraManager.stopCamera();
                    startCamera();
                    if (result.getResultCode() == RESULT_OK) {
                        handleCalibrationSuccess(result.getData());
                    }
                }
        );

        initViews();
        initManagers();
        initListeners();
        initPeriodicRefresh();

        permissionHelper = new PermissionHelper(this, getPermissions(), this::onPermissionsGranted);
        permissionHelper.checkAndRequest();
    }

    private void enterFullscreen() {
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        uiManager = new MainUiManager(this);
    }

    private void initManagers() {
        calibrationData = new CalibrationData(this);
        cameraManager = new CameraManager(this, this);
        effectiveDurationManager = new EffectiveDurationManager(this);
        recordingManager = new RecordingManager(this, cameraManager.getVideoRecorder(), effectiveDurationManager);
        uploadManager = UploadManager.getInstance(this);
        voicePromptManager = new VoicePromptManager(this);
        recordingManager.setVoicePromptManager(voicePromptManager);
        handDetectionManager = new HandDetectionManager(this, cameraManager, voicePromptManager, recordingManager);
        accountManager = new AccountManager(this);

        uploadManager.clearDeletedRecords();

        recordingCoordinator = new RecordingCoordinator(this, uiManager, handDetectionManager, voicePromptManager);

        if (accountManager.isLoggedIn()) {
            DataConfig.getInstance().collectorName = accountManager.getCurrentDisplayName();
            LogUtil.i(TAG, "已登录用户恢复采集人: " + accountManager.getCurrentDisplayName());
        }
    }

    private void onPermissionsGranted() {
        startCamera();
        requestBatteryOptimizationWhitelist();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (handDetectionManager != null) {
            handDetectionManager.setEnabled(false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DialogManager.getInstance(this).flush(this);

        if (uploadManager != null) {
            uploadManager.clearDeletedRecords();
        }

        if (!cameraManager.isCameraReady()) {
            startCamera();
        }

        if (handDetectionManager != null) {
            handDetectionManager.setEnabled(recordingManager.isRecording());
        }
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPeriodicRefresh();
        DialogManager.getInstance(this).clear();
        releaseAll();
    }

    private void initListeners() {
        cameraManager.setOnCameraReadyListener(new CameraManager.OnCameraReadyListener() {
            @Override
            public void onCameraReady(androidx.camera.core.Camera camera, CameraParamReader.CameraParams params) {
                CameraConfig cfg = CameraConfig.getInstance();
                if (shouldRestoreLensAfterCalibration && cameraManager.isFusionArchitecture()) {
                    // 标定页返回后做一次防呆恢复，确保“返回主界面仍保持进入标定前镜头”
                    cameraManager.setZoomForLensRole(lensRoleBeforeCalibration);
                    cfg.currentLensRole = lensRoleBeforeCalibration;
                    shouldRestoreLensAfterCalibration = false;
                } else if (shouldRestoreLensAfterCalibration) {
                    shouldRestoreLensAfterCalibration = false;
                }

                lastCameraParams = params;
                saveAutoParams(params);
                tryMergeCalibration();

                CalibrationData.CalibrationResult calib = getCurrentCalibResult();

                String fallbackReason = cameraManager.consumeFallbackReason();
                if (fallbackReason != null && !fallbackDialogShown) {
                    fallbackDialogShown = true;
                    DialogManager.getInstance(MainActivity.this).enqueue(new DialogManager.DialogRequest(
                            1, getString(R.string.lens_fallback_title), fallbackReason
                    ));
                }

                runOnUiThread(() -> {
                    uiManager.setParamsText(buildParamText());
                    uiManager.updateCalibrateButton(calib);

                    if (cameraManager.isFusionArchitecture()) {
                        uiManager.updateLensDisplay(cfg.currentLensRole);
                        if (cameraManager.getRealMinZoom() >= 1.0f) {
                            uiManager.setWideButtonEnabled(false);
                        } else {
                            uiManager.setWideButtonEnabled(true);
                        }
                    } else {
                        uiManager.updateLensDisplay(cfg.currentLensRole);
                        uiManager.setWideButtonEnabled(true);
                    }

                    boolean calibrated = isCalibrated(calib);
                    uiManager.showUncalibratedBanner(!calibrated, cfg.currentLensRole, getCalibWidth(), getCalibHeight());
                    updateRecordButtonState();
                });
                // 【Debug】长按"上传"按钮导出相机诊断JSON
                uiManager.getBtnExportPC().setOnLongClickListener(v -> {
                    new CameraDebugDumper(MainActivity.this, MainActivity.this).runFullDiagnostics(previewView);
                    return true;
                });
            }

            @Override
            public void onCameraError(String error) {
                runOnUiThread(() -> {
                    uiManager.setParamsText(getString(R.string.camera_error, error));
                    DialogManager.getInstance(MainActivity.this).showImmediate(new DialogManager.DialogRequest(
                            3, "相机错误", error, "确定", null, false
                    ));
                });
            }
        });

        cameraManager.setOnZoomChangedListener((current, max, min) -> runOnUiThread(() -> {
            CameraConfig cfg = CameraConfig.getInstance();
            cfg.currentZoom = current;
            if (max > 0) cfg.maxZoom = max;
            if (min > 0) cfg.minZoom = min;

            tryMergeCalibration();

            CalibrationData.CalibrationResult calib = getCurrentCalibResult();
            uiManager.updateCalibrateButton(calib);
            boolean calibrated = isCalibrated(calib);
            uiManager.showUncalibratedBanner(!calibrated, cfg.currentLensRole, getCalibWidth(), getCalibHeight());
            updateRecordButtonState();
            uiManager.setParamsText(buildParamText());
            uiManager.updateLensDisplay(cfg.currentLensRole);

            // 【关键修复】动态更新广角按钮状态（ZoomState可能运行时才确认融合架构）
            if (cameraManager.isFusionArchitecture()) {
                uiManager.setWideButtonEnabled(cameraManager.getRealMinZoom() < 1.0f);
            } else if (cameraManager.hasUltraWideLens()) {
                uiManager.setWideButtonEnabled(true);
            }
        }));

        recordingManager.setListener(new RecordingManager.Listener() {
            @Override public void onStateChanged(RecordingManager.State state) {
                switch (state) {
                    case RECORDING:
                        recordingCoordinator.onRecordingStarted();
                        uiManager.setControlsEnabled(false);
                        break;
                    case STOPPING:
                        recordingCoordinator.onRecordingStopping();
                        break;
                    case IDLE: case COMPLETED: case ERROR:
                        recordingCoordinator.onRecordingStopped();
                        uiManager.setControlsEnabled(true);
                        updateRecordButtonState();
                        break;
                }
            }
            @Override public void onProgress(long totalMillis, String timeText, int segIdx, int totalSeg) {
                recordingCoordinator.onProgress(timeText, segIdx);
            }
            @Override public void onSegmentEnded(int segIdx, String path) {
                recordingCoordinator.onSegmentEnded(segIdx);
            }
            @Override public void onCompleted(boolean success, String error, String sessionPath) {
                if (success) {
                    if (sessionPath == null) {
                        DialogManager.getInstance(MainActivity.this).showImmediate(new DialogManager.DialogRequest(
                                2, "⚠️ 录制已停止",
                                "连续60秒未检测到手部，本次数据判定为无效，已自动停止录制并丢弃。\n\n请确保画面中始终包含手部动作后重新开始。",
                                "我知道了", null, false
                        ));
                        Toast.makeText(MainActivity.this, "⚠️ 本次录制无有效数据（长时间未检测到手），请重新开始", Toast.LENGTH_LONG).show();
                    } else {
                        long effectiveMs = recordingManager.getEffectiveDurationMs();
                        long effectiveSec = effectiveMs / 1000;
                        long effectiveMin = effectiveSec / 60;
                        Toast.makeText(MainActivity.this,
                                String.format(Locale.CHINA, "录制完成，有效时长 %d分%d秒", effectiveMin, effectiveSec % 60),
                                Toast.LENGTH_LONG).show();

                        refreshUi();

                        if (UploadConfig.getInstance(MainActivity.this).autoUpload) {
                            uploadManager.uploadSession(new File(sessionPath), new UploadManager.SimpleUploadCallback() {
                                @Override public void onStart(String fileName) {}
                                @Override public void onProgress(long current, long total) {}
                                @Override public void onSuccess(String url) {
                                    LogUtil.i(TAG, "自动上传完成: " + url);
                                    mainHandler.postDelayed(MainActivity.this::refreshUi, 300);
                                }
                                @Override public void onFailure(String error) { LogUtil.e(TAG, "自动上传失败: " + error); }
                            });
                        }
                    }
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.record_failed, error), Toast.LENGTH_LONG).show();
                }
                refreshUi();
            }
            @Override public void onStorageWarning(long remainingMB) {
                Toast.makeText(MainActivity.this, getString(R.string.storage_warning_format, remainingMB), Toast.LENGTH_LONG).show();
            }
        });

        uiManager.getBtnRecord().setOnClickListener(v -> toggleRecording());
        uiManager.getBtnExportPC().setOnClickListener(v -> ensureLoggedIn(this::showUploadFolderSelection));
        uiManager.getBtnCalibrate().setOnClickListener(v -> openCalibration());
        uiManager.getBtnZoomOut().setOnClickListener(v -> switchLens(CameraConfig.LensRole.ULTRA_WIDE));
        uiManager.getBtnZoomIn().setOnClickListener(v -> switchLens(CameraConfig.LensRole.WIDE));
    }

    private long lastLensSwitchTime = 0;
    private static final long LENS_SWITCH_DEBOUNCE_MS = 1500;

    private void switchLens(CameraConfig.LensRole targetRole) {
        fallbackDialogShown = false;
        long now = System.currentTimeMillis();
        if (now - lastLensSwitchTime < LENS_SWITCH_DEBOUNCE_MS) {
            Toast.makeText(this, R.string.lens_switching_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        lastLensSwitchTime = now;

        CameraConfig cfg = CameraConfig.getInstance();
        if (cfg.currentLensRole == targetRole) {
            Toast.makeText(this, getString(R.string.lens_already_current, getLensLabel(targetRole)), Toast.LENGTH_SHORT).show();
            return;
        }
        if (targetRole == CameraConfig.LensRole.ULTRA_WIDE) {
            if (!cameraManager.hasUltraWideLens()) {
                Toast.makeText(this, R.string.lens_no_ultra_wide, Toast.LENGTH_SHORT).show();
                return;
            }
            if (cameraManager.isFusionArchitecture() && cameraManager.getRealMinZoom() >= 1.0f) {
                Toast.makeText(this, R.string.lens_no_fusion_wide, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        LogUtil.i(TAG, "切换镜头: " + cfg.currentLensRole.name() + " -> " + targetRole.name());

        if (cameraManager.isFusionArchitecture()) {
            cameraManager.setZoomForLensRole(targetRole);
            runOnUiThread(() -> {
                uiManager.updateLensDisplay(targetRole);
                uiManager.setParamsText(buildParamText());
                updateRecordButtonState();
            });
        } else {
            cameraManager.stopCamera();
            cfg.currentLensRole = targetRole;
            startCamera();
        }
    }

    private String getLensLabel(CameraConfig.LensRole role) {
        return role == CameraConfig.LensRole.ULTRA_WIDE ? "广角" : "主摄";
    }

    private void ensureLoggedIn(Runnable onSuccess) {
        if (accountManager != null && accountManager.isLoggedIn()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        DialogManager.getInstance(this).clear();
        showLoginDialog(onSuccess);
    }

    private void showLoginDialog(final Runnable onSuccess) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.login_title);
        builder.setMessage(R.string.login_message);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final EditText etUsername = new EditText(this);
        etUsername.setHint(R.string.login_username_hint);
        etUsername.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        layout.addView(etUsername);

        final EditText etPassword = new EditText(this);
        etPassword.setHint(R.string.login_password_hint);
        etPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etPassword);

        builder.setView(layout);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.login_btn, null);
        builder.setNegativeButton(R.string.login_exit, (d, w) -> finishAffinity());

        final AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // 【优化】防止快速双击导致重复验证
            v.setEnabled(false);
            etUsername.setEnabled(false);
            etPassword.setEnabled(false);

            String username = etUsername.getText().toString().trim();
            char[] passwordChars = etPassword.getText().toString().trim().toCharArray();

            if (username.isEmpty() || passwordChars.length == 0) {
                Toast.makeText(this, R.string.login_empty_error, Toast.LENGTH_SHORT).show();
                Arrays.fill(passwordChars, '\0');
                v.setEnabled(true);
                etUsername.setEnabled(true);
                etPassword.setEnabled(true);
                return;
            }

            AccountConfig.Account account = AccountConfig.authenticate(username, passwordChars);
            Arrays.fill(passwordChars, '\0');
            etPassword.setText("");

            if (account != null) {
                accountManager.login(account.username, account.role, account.displayName);
                DataConfig.getInstance().collectorName = account.displayName;
                Toast.makeText(this, getString(R.string.login_success, account.displayName), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                refreshUi();
                if (onSuccess != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(onSuccess, 300);
                }
            } else {
                Toast.makeText(this, R.string.login_fail, Toast.LENGTH_SHORT).show();
                etPassword.setText("");
                v.setEnabled(true);
                etUsername.setEnabled(true);
                etPassword.setEnabled(true);
            }
        });
    }

    private void showUploadFolderSelection() {
        uploadManager.clearDeletedRecords();

        File baseDir = new StorageManager(this).getBaseDir(DataConfig.getInstance().baseFolderName);
        if (baseDir == null || !baseDir.exists()) {
            Toast.makeText(this, "存储目录不存在: " + (baseDir != null ? baseDir.getAbsolutePath() : "null"), Toast.LENGTH_LONG).show();
            return;
        }

        File[] children = baseDir.listFiles();
        if (children == null) {
            Toast.makeText(this, "无法读取目录内容（权限或IO错误）\n路径: " + baseDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }

        List<File> pending = new ArrayList<>();
        int skippedByRecord = 0;
        int skippedByEmpty = 0;

        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            UploadRecord r = uploadManager.getUploadRecord(child.getAbsolutePath());
            if (r != null && r.isCompleted()) {
                boolean changed = uploadManager.hasFolderChangedSinceUpload(child);
                if (!changed) {
                    skippedByRecord++;
                    LogUtil.d(TAG, "上传选择跳过（已上传且未变更）: " + child.getName());
                    continue;
                } else {
                    LogUtil.i(TAG, "检测到已上传目录发生变更，允许重新上传: " + child.getName());
                }
            }
            if (hasAnyFiles(child)) {
                pending.add(child);
            } else {
                skippedByEmpty++;
                LogUtil.d(TAG, "上传选择跳过（无文件）: " + child.getName());
            }
        }

        LogUtil.i(TAG, String.format("上传扫描: 总文件夹=%d, 待上传=%d, 已上传跳过=%d, 空文件夹跳过=%d",
                children.length, pending.size(), skippedByRecord, skippedByEmpty));

        if (pending.isEmpty()) {
            StringBuilder reason = new StringBuilder();
            reason.append("没有可上传的日期文件夹\n");
            reason.append("路径: ").append(baseDir.getAbsolutePath()).append("\n\n");
            if (skippedByRecord > 0) {
                reason.append("原因: ").append(skippedByRecord).append(" 个文件夹已上传过（未删除本地文件）\n");
                reason.append("如需重新上传，请先删除对应本地文件夹");
            } else if (skippedByEmpty > 0) {
                reason.append("原因: 所有文件夹均为空");
            } else {
                reason.append("原因: 未找到任何录制数据");
            }
            Toast.makeText(this, reason.toString(), Toast.LENGTH_LONG).show();
            return;
        }

        String[] names = new String[pending.size()];
        for (int i = 0; i < pending.size(); i++) names[i] = pending.get(i).getName();

        new android.app.AlertDialog.Builder(this)
                .setTitle("选择要上传的日期文件夹")
                .setItems(names, (d, which) -> startUploadWithDialog(pending.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean hasAnyFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f == null) continue;
            if (f.isFile()) {
                String name = f.getName().toLowerCase(Locale.US);
                if (name.endsWith(FileNames.VIDEO_EXTENSION) && !name.startsWith(".") && !name.endsWith(FileNames.TEMP_EXTENSION)) return true;
            } else if (f.isDirectory()) {
                if (hasAnyFiles(f)) return true;
            }
        }
        return false;
    }

    private void startUploadWithDialog(File dateFolder) {
        if (recordingManager != null && recordingManager.isRecording()) {
            Toast.makeText(this, "录制进行中，不能上传。请先停止录制。", Toast.LENGTH_LONG).show();
            return;
        }
        if (uploadManager.isUploading()) {
            Toast.makeText(this, R.string.upload_busy_toast, Toast.LENGTH_LONG).show();
            return;
        }
        UploadState state = UploadState.getInstance();
        if (state.isUploading) {
            Toast.makeText(this, R.string.upload_state_busy_toast, Toast.LENGTH_LONG).show();
            return;
        }

        this.currentUploadFolder = dateFolder;
        state.reset();

        Intent intent = new Intent(this, UploadForegroundService.class);
        intent.setAction(UploadForegroundService.ACTION_UPLOAD);
        intent.putExtra(UploadForegroundService.EXTRA_FOLDER_PATH, dateFolder.getAbsolutePath());
        ContextCompat.startForegroundService(this, intent);

        if (uploadProgressDialog != null && uploadProgressDialog.isShowing()) {
            uploadProgressDialog.dismiss();
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, padding / 2);

        uploadProgressTextView = new TextView(this);
        uploadProgressTextView.setText(R.string.upload_preparing);
        layout.addView(uploadProgressTextView);

        uploadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        uploadProgressBar.setMax(100);
        uploadProgressBar.setProgress(0);
        layout.addView(uploadProgressBar);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.uploading_title);
        builder.setView(layout);
        builder.setCancelable(false);
        builder.setNegativeButton(R.string.upload_cancel, (dialog, which) -> {
            dialog.dismiss();
            cancelCurrentUpload();
            Toast.makeText(this, R.string.upload_cancelled_toast, Toast.LENGTH_SHORT).show();
        });
        builder.setNeutralButton(R.string.upload_background, (dialog, which) -> {
            dialog.dismiss();
            Toast.makeText(this, R.string.upload_background_toast, Toast.LENGTH_LONG).show();
        });
        uploadProgressDialog = builder.create();
        uploadProgressDialog.show();

        startUploadPoll();
    }

    private void cancelCurrentUpload() {
        if (currentUploadFolder != null) {
            UploadRecord record = uploadManager.getUploadRecord(currentUploadFolder.getAbsolutePath());
            if (record != null && record.isCompleted()) {
                LogUtil.i(TAG, "取消时发现上传已完成，补录有效时长: " + currentUploadFolder.getName());
                processEffectiveDurationFromFolder(currentUploadFolder);
                mainHandler.postDelayed(this::refreshUi, 300);
            }
        }

        uploadPollHandler.removeCallbacksAndMessages(null);
        if (uploadManager != null) {
            uploadManager.cancelUpload("");
            // 在后台线程等待上传结束，避免阻塞主线程导致 ANR
            new Thread(() -> {
                uploadManager.awaitUploadFinished(5000);
            }, "upload-await-cancel").start();
        }
        Intent stopIntent = new Intent(this, UploadForegroundService.class);
        stopService(stopIntent);
        UploadState.getInstance().reset();
        currentUploadFolder = null;
    }

    private void startUploadPoll() {
        uploadPollHandler.removeCallbacks(uploadPollRunnable);
        uploadPollRunnable = new Runnable() {
            @Override
            public void run() {
                UploadState state = UploadState.getInstance();
                if (!state.isUploading && !state.isSuccess && !state.isFailure) {
                    uploadPollHandler.postDelayed(this, 300);
                    return;
                }

                if (state.isSuccess) {
                    if (uploadProgressDialog != null && uploadProgressDialog.isShowing()) {
                        uploadProgressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, R.string.upload_success, Toast.LENGTH_LONG).show();
                    if (currentUploadFolder != null) {
                        processEffectiveDurationFromFolder(currentUploadFolder);
                        currentUploadFolder = null;
                    }
                    mainHandler.postDelayed(MainActivity.this::refreshUi, 300);
                    state.reset();
                    return;
                }

                if (state.isFailure) {
                    if (uploadProgressDialog != null && uploadProgressDialog.isShowing()) {
                        uploadProgressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, getString(R.string.upload_failed, state.errorMsg), Toast.LENGTH_LONG).show();
                    currentUploadFolder = null;
                    state.reset();
                    return;
                }

                if (uploadProgressDialog != null && uploadProgressDialog.isShowing()
                        && uploadProgressBar != null && uploadProgressTextView != null) {
                    int percent = state.totalBytes > 0
                            ? (int) (state.uploadedBytes * 100 / state.totalBytes) : 0;
                    uploadProgressBar.setProgress(percent);
                    uploadProgressTextView.setText(
                            String.format(Locale.CHINA, getString(R.string.upload_progress_format),
                                    state.currentFile, state.totalFiles,
                                    state.currentFileName, percent));
                }
                uploadPollHandler.postDelayed(this, 500);
            }
        };
        uploadPollHandler.postDelayed(uploadPollRunnable, 300);
    }

    private void processEffectiveDurationFromFolder(File folder) {
        if (folder == null || !folder.exists()) return;
        String collector = DataConfig.getInstance().collectorName;
        if (collector == null || collector.isEmpty()) return;

        FileRepository repo = new FileRepository(this);

        File directMeta = new File(folder, "metadata.json");
        if (directMeta.exists()) {
            long ms = repo.extractEffectiveDurationMs(directMeta);
            if (ms > 0) {
                effectiveDurationManager.addEffectiveDuration(collector, ms, folder.getAbsolutePath());
            }
        }

        File[] sessions = folder.listFiles(File::isDirectory);
        if (sessions != null) {
            for (File session : sessions) {
                File metaFile = new File(session, "metadata.json");
                if (!metaFile.exists()) continue;
                long ms = repo.extractEffectiveDurationMs(metaFile);
                if (ms > 0) {
                    effectiveDurationManager.addEffectiveDuration(collector, ms, session.getAbsolutePath());
                }
            }
        }
    }

    private void tryMergeCalibration() {
        if (lastCameraParams == null) return;

        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        if (calib != null && calib.fx > 0
                && calib.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION
                && calib.source.priority >= lastCameraParams.source.priority) {
            CameraParamReader.mergeCalibrationParams(lastCameraParams, calib);
        }
    }

    private CalibrationData.CalibrationResult getCurrentCalibResult() {
        CameraConfig cfg = CameraConfig.getInstance();
        int cw = getCalibWidth();
        int ch = getCalibHeight();
        return calibrationData.getCalibration(cfg.currentLensRole, cw, ch);
    }

    private boolean isCalibrated(CalibrationData.CalibrationResult calib) {
        if (calib == null) return false;
        return calib.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION
                && calib.rmsError < CalibrationConfig.getInstance().maxRmsError;
    }

    private int getCalibWidth() {
        return CameraConfig.getInstance().targetResolution.getWidth();
    }

    private int getCalibHeight() {
        return CameraConfig.getInstance().targetResolution.getHeight();
    }

    private void startCamera() {
        uiManager.setParamsText(getString(R.string.camera_initializing));
        cameraManager.startCamera(previewView);
    }

    private void saveAutoParams(CameraParamReader.CameraParams params) {
        if (params == null) return;
        if (params.source == CameraParamReader.ParamSource.SYSTEM_FACTORY
                || params.source == CameraParamReader.ParamSource.SENSOR_ESTIMATE) {
            calibrationData.saveEstimateParams(params);
        }
    }

    private void updateRecordButtonState() {
        if (accountManager == null || !accountManager.isLoggedIn()) {
            uiManager.setRecordEnabled(false, false);
            return;
        }

        if (recordingManager != null && recordingManager.isRecording()) {
            uiManager.setRecordEnabled(true);
            return;
        }

        boolean uploadingBusy = uploadManager != null && uploadManager.isUploading();
        boolean hasUndeletedData = hasAnyUndeletedDataFolders();
        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        boolean calibrated = isCalibrated(calib);
        boolean isBlocked = uploadingBusy || hasUndeletedData;

        CalibrationConfig calibCfg = CalibrationConfig.getInstance();
        if (calibCfg.forceCalibrationBeforeRecord) {
            uiManager.setRecordEnabled(calibrated && !isBlocked);
        } else {
            uiManager.setRecordEnabled(!isBlocked, calibrated);
        }
    }

    private void toggleRecording() {
        ensureLoggedIn(this::performToggleRecording);
    }

    private void performToggleRecording() {
        long now = System.currentTimeMillis();
        long debounce = CameraConfig.getInstance().recordButtonDebounceMs;
        if (now - lastRecordActionTime < debounce) {
            LogUtil.w(TAG, "录制操作过快，已忽略");
            return;
        }
        lastRecordActionTime = now;

        if (recordingManager != null && recordingManager.isRecording()) {
            LogUtil.i(TAG, "执行停止录制");
            recordingManager.stop();
            return;
        }

        if ((uploadManager != null && uploadManager.isUploading()) || hasAnyUndeletedDataFolders()) {
            if (uploadManager != null && uploadManager.isUploading()) {
                LogUtil.w(TAG, "录制被拦截：上传进行中");
                Toast.makeText(this, "上传进行中，请等待上传完成后再录制", Toast.LENGTH_LONG).show();
            } else if (hasUnuploadedDataFolders()) {
                LogUtil.w(TAG, "录制被拦截：有未上传数据");
                Toast.makeText(this, "有未上传数据，请先上传后再开始新录制", Toast.LENGTH_LONG).show();
            } else {
                LogUtil.w(TAG, "录制被拦截：数据已上传但未清理本地文件");
                Toast.makeText(this, "数据已上传但未清理，请删除本地文件后再录制", Toast.LENGTH_LONG).show();
            }
            showRecordingBlockedDialog();
            return;
        }

        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        CameraConfig cfg = CameraConfig.getInstance();

        if (CalibrationConfig.getInstance().forceCalibrationBeforeRecord && !isCalibrated(calib)) {
            String roleLabel = getLensLabel(cfg.currentLensRole);
            String msg = String.format(Locale.US,
                    "当前 %s / %dx%d 尚未完成棋盘格标定，无法录制",
                    roleLabel, getCalibWidth(), getCalibHeight());
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            DialogManager.getInstance(this).showImmediate(new DialogManager.DialogRequest(
                    2, "⚠️ 当前镜头未标定",
                    msg + "\n\n点击\"去标定\"立即进入标定界面。",
                    "去标定", this::openCalibration, false
            ));
            return;
        }

        if (!cameraManager.isCameraReady()) {
            LogUtil.w(TAG, "录制被拦截：相机未就绪");
            Toast.makeText(this, R.string.record_camera_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!CameraParamReader.isParamsUsable(lastCameraParams)) {
            LogUtil.w(TAG, "录制被拦截：相机参数不可用");
            Toast.makeText(this, R.string.record_params_not_ready, Toast.LENGTH_LONG).show();
            return;
        }

        LogUtil.i(TAG, "执行开始录制");
        tryMergeCalibration();
        recordingManager.start(lastCameraParams);
    }

    private void showRecordingBlockedDialog() {
        List<String> paths = getUndeletedDataFolderPaths();
        boolean hasUnuploaded = hasUnuploadedDataFolders();

        StringBuilder sb = new StringBuilder();
        if (hasUnuploaded) {
            sb.append("检测到本地有未上传的数据文件夹。请先上传，上传完成后删除本地文件才能继续录制。\n\n");
        } else {
            sb.append("检测到本地数据已上传但未删除。为避免数据重复，必须删除本地文件后才能继续录制。\n\n");
        }

        for (String p : paths) {
            UploadRecord r = uploadManager != null ? uploadManager.getUploadRecord(p) : null;
            String status = (r != null && r.isCompleted()) ? "【已上传】" : "【未上传】";
            sb.append("• ").append(status).append(" ").append(p).append("\n\n");
        }

        DialogManager.getInstance(this).showImmediate(new DialogManager.DialogRequest(
                2, "⚠️ 录制功能已锁定",
                sb.toString(),
                "我知道了", null, false
        ));
    }

    /**
     * 检查是否存在从未上传过的数据文件夹。
     * 用于区分拦截原因：未上传 → 提示先上传；已上传未删 → 提示删本地文件。
     */
    private boolean hasUnuploadedDataFolders() {
        List<String> paths = getUndeletedDataFolderPaths();
        if (paths.isEmpty()) return false;
        if (uploadManager == null) return true; // 保守策略
        for (String path : paths) {
            UploadRecord record = uploadManager.getUploadRecord(path);
            if (record == null || !record.isCompleted()) {
                return true; // 至少有一个没上传过
            }
        }
        return false; // 全部都有 completed 上传记录
    }

    private boolean hasAnyUndeletedDataFolders() {
        return !getUndeletedDataFolderPaths().isEmpty();
    }

    private List<String> getUndeletedDataFolderPaths() {
        List<String> pending = new ArrayList<>();
        File baseDir = new StorageManager(this).getBaseDir(DataConfig.getInstance().baseFolderName);
        if (baseDir == null || !baseDir.exists()) return pending;
        File[] children = baseDir.listFiles();
        if (children == null) return pending;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            if (hasAnyDataArtifacts(child)) {
                pending.add(child.getAbsolutePath());
            }
        }
        return pending;
    }

    private boolean hasAnyDataArtifacts(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f == null) continue;
            if (f.isFile()) {
                String name = f.getName();
                if (!name.startsWith(".") && !name.endsWith(FileNames.TEMP_EXTENSION)) return true;
            } else if (f.isDirectory()) {
                if (hasAnyDataArtifacts(f)) return true;
            }
        }
        return false;
    }

    private void openCalibration() {
        ensureLoggedIn(() -> {
            fallbackDialogShown = false;
            CameraConfig cfg = CameraConfig.getInstance();
            lensRoleBeforeCalibration = cfg.currentLensRole;
            shouldRestoreLensAfterCalibration = true;
            // 先缓存当前相机状态，再 stopCamera，避免 stop 后 realMinZoom 被重置为 1.0
            boolean isFusion = cameraManager.isFusionArchitecture();
            float minZoom = cameraManager.getRealMinZoom();
            float currentZoom = cfg.currentZoom;

            cameraManager.stopCamera();

            Intent intent = new Intent(this, CalibrationActivity.class);
            intent.putExtra(CalibrationActivity.EXTRA_LENS_ROLE, cfg.currentLensRole.name());
            Size target = cfg.targetResolution;
            intent.putExtra(CalibrationActivity.EXTRA_RESOLUTION_WIDTH, target.getWidth());
            intent.putExtra(CalibrationActivity.EXTRA_RESOLUTION_HEIGHT, target.getHeight());
            intent.putExtra(CalibrationActivity.EXTRA_IS_FUSION, isFusion);
            // 传入进入标定前的真实 minZoom；若异常则回退到当前 zoom，确保广角意图不丢失
            intent.putExtra(CalibrationActivity.EXTRA_MIN_ZOOM, minZoom > 0 ? minZoom : currentZoom);
            calibrationLauncher.launch(intent);
        });
    }

    private void handleCalibrationSuccess(@SuppressWarnings("unused") Intent data) {
        tryMergeCalibration();
        runOnUiThread(() -> {
            uiManager.setParamsText(buildParamText());
            uiManager.updateCalibrateButton(getCurrentCalibResult());
            boolean calibrated = isCalibrated(getCurrentCalibResult());
            uiManager.showUncalibratedBanner(!calibrated, CameraConfig.getInstance().currentLensRole, getCalibWidth(), getCalibHeight());
            updateRecordButtonState();
        });

        String roleLabel = getLensLabel(CameraConfig.getInstance().currentLensRole);
        DialogManager.getInstance(this).enqueue(new DialogManager.DialogRequest(
                1, "✅ 标定成功",
                String.format(Locale.US, "%s 标定已完成，参数已更新。", roleLabel),
                "我知道了", this::refreshUi, false
        ));
        DialogManager.getInstance(this).flush(this);
    }

    private void refreshUi() {
        tryMergeCalibration();

        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        CameraConfig cfg = CameraConfig.getInstance();

        uiManager.setParamsText(buildParamText());
        uiManager.updateCalibrateButton(calib);
        boolean calibrated = isCalibrated(calib);
        uiManager.showUncalibratedBanner(!calibrated, cfg.currentLensRole, getCalibWidth(), getCalibHeight());
        updateRecordButtonState();
    }

    private String buildParamText() {
        try {
            return buildParamTextInternal();
        } catch (Exception e) {
            LogUtil.e(TAG, "构建参数文本异常", e);
            return "参数加载异常: " + e.getMessage();
        }
    }

    private String getRecordBlockReason() {
        if (accountManager == null || !accountManager.isLoggedIn()) {
            return "【录制拦截】未登录";
        }
        if ((uploadManager != null && uploadManager.isUploading())) {
            return "【录制拦截】上传进行中";
        }
        if (hasAnyUndeletedDataFolders()) {
            if (hasUnuploadedDataFolders()) {
                return "【录制拦截】有未上传数据，请先上传";
            } else {
                return "【录制拦截】数据已上传但未清理，请删除本地文件后再录制";
            }
        }
        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        if (!isCalibrated(calib)) {
            String roleLabel = getLensLabel(CameraConfig.getInstance().currentLensRole);
            return "【录制拦截】" + roleLabel + " 未标定";
        }
        if (!cameraManager.isCameraReady()) {
            return "【录制拦截】相机未就绪";
        }
        if (!CameraParamReader.isParamsUsable(lastCameraParams)) {
            return "【录制拦截】相机参数异常";
        }
        return null;
    }

    private String buildParamTextInternal() {
        if (lastCameraParams == null) return getString(R.string.camera_initializing);

        StringBuilder sb = new StringBuilder();
        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        int calibCount = (calib != null) ? calib.calibrationCount : 0;
        CameraConfig cfg = CameraConfig.getInstance();

        if (accountManager != null && accountManager.isLoggedIn()) {
            sb.append("【用户】").append(accountManager.getCurrentDisplayName())
                    .append("(").append(accountManager.getCurrentRole()).append(")\n");
        } else {
            sb.append("【用户】未登录(受限)\n");
        }

        String collectorName = (accountManager != null && accountManager.isLoggedIn())
                ? accountManager.getCurrentDisplayName() : "未登录";
        // 【改回】今日有效时长（毫秒存储，界面显示分钟/小时）
        long todayMs = effectiveDurationManager.getTodayEffectiveDurationMs(collectorName);
        long todaySec = todayMs / 1000; // 截断
        long todayMin = todaySec / 60;
        long todayHour = todayMin / 60;
        long remMin = todayMin % 60;
        if (todayHour > 0) {
            sb.append("【今日有效时长】").append(todayHour).append("小时").append(remMin).append("分钟\n");
        } else {
            sb.append("【今日有效时长】").append(todayMin).append("分钟\n");
        }

        String roleLabel = getLensLabel(cfg.currentLensRole);
        sb.append("【镜头】").append(roleLabel).append("\n");
        sb.append("【实际焦距】").append(String.format(Locale.US, "%.2fx (范围 %.2f~%.2f)",
                cfg.currentZoom, cfg.minZoom, cfg.maxZoom)).append("\n");
        sb.append("【分辨率】").append(lastCameraParams.videoWidth).append("x")
                .append(lastCameraParams.videoHeight).append("\n\n");

        if (calib != null && calib.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION) {
            double rms = calib.rmsError;
            String qualityTag;
            if (rms < 0.3) qualityTag = "🟢 优秀";
            else if (rms < 0.5) qualityTag = "🟡 良好";
            else if (rms < 1.0) qualityTag = "🟠 一般";
            else qualityTag = "🔴 较差";
            sb.append("【标定】").append(qualityTag)
                    .append("  误差=").append(String.format(Locale.US, "%.2fpx", rms))
                    .append("  次数=").append(calibCount).append("\n");
            sb.append("【日期】").append(calib.calibrationDate).append("\n\n");
        } else {
            String sourceTag;
            switch (lastCameraParams.source) {
                case SYSTEM_FACTORY: sourceTag = "工厂参数"; break;
                case SENSOR_ESTIMATE: sourceTag = "估算参数"; break;
                default: sourceTag = "未获取"; break;
            }
            sb.append("【标定】").append(sourceTag).append("（精度未知，建议手动标定）\n\n");
        }

        sb.append("【内参】\n");
        sb.append("fx=").append(String.format(Locale.US, "%.2f ", lastCameraParams.fx))
                .append("fy=").append(String.format(Locale.US, "%.2f\n", lastCameraParams.fy));
        sb.append("cx=").append(String.format(Locale.US, "%.2f ", lastCameraParams.cx))
                .append("cy=").append(String.format(Locale.US, "%.2f\n\n", lastCameraParams.cy));

        sb.append("【畸变】\n");
        float[] d = lastCameraParams.distortion;
        sb.append("k1=").append(String.format(Locale.US, "%.6f ", d[0]))
                .append("k2=").append(String.format(Locale.US, "%.6f ", d[1]))
                .append("k3=").append(String.format(Locale.US, "%.6f\n", d[4]));
        sb.append("p1=").append(String.format(Locale.US, "%.6f ", d[2]))
                .append("p2=").append(String.format(Locale.US, "%.6f\n\n", d[3]));

        StorageManager sm = new StorageManager(this);
        File baseDir = sm.getBaseDir(DataConfig.getInstance().baseFolderName);
        long avail = (baseDir != null) ? sm.getAvailableBytes(baseDir) : 0;
        StorageStats stats = calcStorageStats(baseDir);

        sb.append("【存储】\n");
        sb.append("已用:").append(StorageManager.formatSize(stats.usedSpace))
                .append("(").append(stats.videoCount).append("个视频)\n");
        sb.append("可用:").append(StorageManager.formatSize(avail)).append("\n");
        if (baseDir != null) {
            sb.append("路径:").append(baseDir.getAbsolutePath()).append("\n");
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        long usedPercent = maxMem > 0 ? (usedMem * 100 / maxMem) : 0;
        sb.append("\n【内存】").append(StorageManager.formatSize(usedMem))
                .append("/").append(StorageManager.formatSize(maxMem))
                .append("(").append(usedPercent).append("%)");
        if (usedPercent > 85) sb.append(" ⚠️内存紧张");

        sb.append("\n\n【配置】");
        if (cfg.enableSegmentRecording) sb.append(" 分段").append(cfg.segmentDurationMs / 1000).append("s");
        if (cfg.enableHandDetection) sb.append(" 无手").append(cfg.noHandTimeoutMs / 1000).append("s报警");
        if (cfg.targetFrameRate > 0) sb.append(" ").append(cfg.targetFrameRate).append("fps");

        String blockReason = getRecordBlockReason();
        if (blockReason != null) {
            sb.append("\n\n╔══════════════════════╗");
            sb.append("\n║ ").append(blockReason).append(" ║");
            sb.append("\n╚══════════════════════╝");
        }

        return sb.toString();
    }

    private StorageStats calcStorageStats(File baseDir) {
        StorageStats stats = new StorageStats();
        if (baseDir == null || !baseDir.exists()) return stats;
        long startTime = System.currentTimeMillis();
        File[] dates = baseDir.listFiles();
        if (dates == null) return stats;

        int dateCount = 0;
        for (File d : dates) {
            if (System.currentTimeMillis() - startTime > 500) {
                LogUtil.w(TAG, "存储统计扫描超时(>500ms)，返回部分结果");
                break;
            }
            if (++dateCount > 100) {
                LogUtil.w(TAG, "存储统计日期文件夹超过100个，停止扫描");
                break;
            }
            if (d == null || !d.isDirectory()) continue;
            File[] sessions = d.listFiles();
            if (sessions == null) continue;
            for (File s : sessions) {
                if (s == null || !s.isDirectory()) continue;
                File[] files = s.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f == null || !f.isFile()) continue;
                    stats.usedSpace += f.length();
                    stats.totalFiles++;
                    if (f.getName().endsWith(FileNames.VIDEO_EXTENSION)) stats.videoCount++;
                }
            }
        }
        return stats;
    }

    private static class StorageStats {
        long usedSpace = 0;
        int videoCount = 0;
        int totalFiles = 0;
    }

    @SuppressLint("BatteryOptimizations")
    private void requestBatteryOptimizationWhitelist() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            new AlertDialog.Builder(this)
                    .setTitle("需要电池优化白名单")
                    .setMessage("长时间录制需要关闭电池优化，否则熄屏后系统可能停止录制。\n\n请在设置中选择\"允许\"。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("稍后", null)
                    .show();
        }
    }

    private void initPeriodicRefresh() {
        refreshRunnable = new Runnable() {
            @Override public void run() {
                updateRecordButtonState();
                refreshHandler.postDelayed(this, 5000);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, 5000);
    }

    private void stopPeriodicRefresh() {
        if (refreshRunnable != null) refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionHelper.onRequestPermissionsResult(requestCode, grantResults);
    }

    private boolean isBluetoothRemote(@NonNull android.view.KeyEvent event) {
        try {
            android.view.InputDevice device = event.getDevice();
            if (device == null) return false;
            String name = device.getName();
            if (name == null) return false;
            name = name.toLowerCase();
            return name.contains("ab") || name.contains("shutter") || name.contains("bt")
                    || name.contains("remote") || name.contains("bluetooth") || name.contains("keyboard");
        } catch (Exception e) {
            LogUtil.w(TAG, "蓝牙设备检测异常", e);
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean isDedicated = (keyCode == android.view.KeyEvent.KEYCODE_CAMERA
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK);
        boolean isVolume = (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean shouldHandle = isDedicated || (isVolume && isBluetoothRemote(event));

        if (shouldHandle && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            long now = System.currentTimeMillis();
            long debounce = CameraConfig.getInstance().recordButtonDebounceMs;
            if (now - lastRecordActionTime < debounce) return true;
            lastRecordActionTime = now;
            toggleRecording();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void releaseAll() {
        stopPeriodicRefresh();
        uploadPollHandler.removeCallbacksAndMessages(null);
        if (uploadProgressDialog != null && uploadProgressDialog.isShowing()) {
            uploadProgressDialog.dismiss();
        }
        if (uploadManager != null) {
            uploadManager.cancelUpload("");
        }
        if (recordingManager != null) recordingManager.release();
        if (handDetectionManager != null) handDetectionManager.release();
        if (voicePromptManager != null) voicePromptManager.release();
        if (cameraManager != null) {
            if (recordingManager != null && recordingManager.isRecording()) recordingManager.stop();
            cameraManager.stopCamera();
        }
    }
}