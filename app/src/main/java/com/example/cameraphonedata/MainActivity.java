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
import com.example.cameraphonedata.camera.CameraManager;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.AccountConfig;
import com.example.cameraphonedata.config.AccountManager;
import com.example.cameraphonedata.config.CalibrationConfig;
import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.config.UploadConfig;
import com.example.cameraphonedata.data.upload.UploadRecord;
import com.example.cameraphonedata.data.upload.UploadState;
import com.example.cameraphonedata.domain.manager.HandDetectionManager;
import com.example.cameraphonedata.domain.manager.RecordingManager;
import com.example.cameraphonedata.domain.manager.UploadManager;
import com.example.cameraphonedata.service.UploadForegroundService;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面 —— 全屏沉浸横屏版。
 *
 * 【20人采集防呆机制】
 * 1. 录制按钮始终可点击，灰色时弹出明确提示。
 * 2. 录制中锁定变焦/标定/导出按钮。
 * 3. 标定返回后自动恢复焦距，避免回到 1.0x。
 * 4. 相机启动带并发锁，防止重复初始化。
 * 5. 上传显示 App 内进度弹窗 + 通知栏双保险。
 *
 * 【账号系统】
 * 1. 未登录时所有核心功能（录制、标定、上传）被拦截，点击即弹登录框。
 * 2. 登录成功后身份自动绑定到 DataConfig.collectorName，后续所有数据带身份。
 * 3. 用户输入密码比对完成后立即 Arrays.fill 清零，不长期占内存。
 *
 * 【内存监控】
 * 参数栏实时显示 JVM 内存占用，超过 85% 报警。
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

    private CameraParamReader.CameraParams lastCameraParams;
    private ActivityResultLauncher<Intent> calibrationLauncher;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    private final Handler uploadPollHandler = new Handler(Looper.getMainLooper());
    private Runnable uploadPollRunnable;
    private android.app.ProgressDialog uploadProgressDialog;

    private long lastToggleClickTime = 0;
    private long lastShutterTime = 0;

    private final Map<Float, Size> calibActualSizes = new HashMap<>();

    private static String[] getPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        } else {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
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
        recordingManager = new RecordingManager(this, cameraManager.getVideoRecorder());
        uploadManager = new UploadManager(this);
        voicePromptManager = new VoicePromptManager(this);
        handDetectionManager = new HandDetectionManager(this, cameraManager, voicePromptManager);
        accountManager = new AccountManager(this);

        uploadManager.clearDeletedRecords();

        recordingCoordinator = new RecordingCoordinator(this, uiManager, handDetectionManager, voicePromptManager);
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
    protected void onResume() {
        super.onResume();
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
        releaseAll();
    }

    private void initListeners() {
        cameraManager.setOnCameraReadyListener(new CameraManager.OnCameraReadyListener() {
            @Override
            public void onCameraReady(androidx.camera.core.Camera camera, CameraParamReader.CameraParams params) {
                lastCameraParams = params;
                saveAutoParams(params);
                tryMergeCalibration();

                float currentZoom = CameraConfig.getInstance().currentZoom;
                CalibrationData.CalibrationResult calib = getCurrentCalibResult();

                runOnUiThread(() -> {
                    uiManager.setParamsText(buildParamText());
                    uiManager.updateCalibrateButton(calib);
                    uiManager.updateZoomDisplay(currentZoom);
                    boolean calibrated = isCalibrated(calib);
                    uiManager.showUncalibratedBanner(!calibrated, currentZoom, getCalibWidth(), getCalibHeight());
                    updateRecordButtonState();
                });
            }

            @Override
            public void onCameraError(String error) {
                runOnUiThread(() -> {
                    uiManager.setParamsText(getString(R.string.camera_error, error));
                    Toast.makeText(MainActivity.this, getString(R.string.camera_error, error), Toast.LENGTH_LONG).show();
                });
            }
        });

        cameraManager.setOnZoomChangedListener((current, max, min) -> runOnUiThread(() -> {
            CameraConfig cfg = CameraConfig.getInstance();
            cfg.currentZoom = current;
            if (max > 0) cfg.maxZoom = max;
            if (min > 0) cfg.minZoom = min;
            uiManager.updateZoomDisplay(cfg.currentZoom);

            tryMergeCalibration();

            CalibrationData.CalibrationResult calib = getCurrentCalibResult();
            uiManager.updateCalibrateButton(calib);
            boolean calibrated = isCalibrated(calib);
            uiManager.showUncalibratedBanner(!calibrated, current, getCalibWidth(), getCalibHeight());
            updateRecordButtonState();
            uiManager.setParamsText(buildParamText());
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
                    int totalSeg = recordingManager.getTotalSegmentCount();
                    Toast.makeText(MainActivity.this,
                            String.format(Locale.CHINA, "录制完成，已保存 %d 个数据集", totalSeg),
                            Toast.LENGTH_LONG).show();
                    if (UploadConfig.getInstance(MainActivity.this).autoUpload && sessionPath != null) {
                        uploadManager.uploadSession(new File(sessionPath), new UploadManager.SimpleUploadCallback() {
                            @Override public void onStart(String fileName) {}
                            @Override public void onProgress(long current, long total) {}
                            @Override public void onSuccess(String url) { LogUtil.i(TAG, "自动上传完成: " + url); }
                            @Override public void onFailure(String error) { LogUtil.e(TAG, "自动上传失败: " + error); }
                        });
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
        uiManager.getBtnExportPC().setOnClickListener(v -> ensureLoggedIn(() -> showUploadFolderSelection()));
        uiManager.getBtnCalibrate().setOnClickListener(v -> openCalibration());
        uiManager.getBtnZoomIn().setOnClickListener(v -> adjustZoom(1));
        uiManager.getBtnZoomOut().setOnClickListener(v -> adjustZoom(-1));
        uiManager.getTvZoomInfo().setOnClickListener(new android.view.View.OnClickListener() {
            private long lastClickTime = 0;
            @Override public void onClick(android.view.View v) {
                long now = System.currentTimeMillis();
                if (now - lastClickTime < 300) {
                    uiManager.showZoomInputDialog(MainActivity.this,
                            CameraConfig.getInstance().minZoom,
                            CameraConfig.getInstance().maxZoom,
                            zoom -> cameraManager.setZoom(zoom));
                }
                lastClickTime = now;
            }
        });
    }

    // ==================== 登录系统（比对完成即清零密码） ====================

    private void ensureLoggedIn(Runnable onSuccess) {
        if (accountManager != null && accountManager.isLoggedIn()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        showLoginDialog(onSuccess);
    }

    /**
     * 登录对话框。
     * 【安全】输入密码转为 char[]，比对完成后立即 Arrays.fill 清零，不占内存。
     */
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
            String username = etUsername.getText().toString().trim();
            // 获取密码并转为 char[]
            char[] passwordChars = etPassword.getText().toString().trim().toCharArray();

            if (username.isEmpty() || passwordChars.length == 0) {
                Toast.makeText(this, R.string.login_empty_error, Toast.LENGTH_SHORT).show();
                Arrays.fill(passwordChars, '\0');
                return;
            }

            // 比对
            AccountConfig.Account account = AccountConfig.authenticate(username, passwordChars);

            // 【关键】比对完成后立即清零用户输入的密码
            Arrays.fill(passwordChars, '\0');
            etPassword.setText("");

            if (account != null) {
                accountManager.login(account.username, account.role, account.displayName);
                DataConfig.getInstance().collectorName = account.displayName;
                Toast.makeText(this, getString(R.string.login_success, account.displayName), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                refreshUi();
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else {
                Toast.makeText(this, R.string.login_fail, Toast.LENGTH_SHORT).show();
                etPassword.setText("");
            }
        });
    }

    // ==================== 上传相关（原有） ====================

    private void showUploadFolderSelection() {
        uploadManager.clearDeletedRecords();

        File baseDir = new StorageManager(this).getBaseDir(DataConfig.getInstance().baseFolderName);
        if (baseDir == null || !baseDir.exists()) {
            Toast.makeText(this, "存储目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] folders = baseDir.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            Toast.makeText(this, "没有可上传的日期文件夹", Toast.LENGTH_SHORT).show();
            return;
        }

        List<File> pending = new ArrayList<>();
        for (File f : folders) {
            UploadRecord r = uploadManager.getUploadRecord(f.getAbsolutePath());
            if (r == null || !r.isCompleted()) pending.add(f);
        }

        if (pending.isEmpty()) {
            Toast.makeText(this, "所有文件夹均已上传，请删除本地文件后继续录制", Toast.LENGTH_LONG).show();
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

    private void startUploadWithDialog(File dateFolder) {
        UploadState.getInstance().reset();

        Intent intent = new Intent(this, UploadForegroundService.class);
        intent.setAction(UploadForegroundService.ACTION_UPLOAD);
        intent.putExtra(UploadForegroundService.EXTRA_FOLDER_PATH, dateFolder.getAbsolutePath());
        ContextCompat.startForegroundService(this, intent);

        if (uploadProgressDialog != null && uploadProgressDialog.isShowing()) {
            uploadProgressDialog.dismiss();
        }
        uploadProgressDialog = new android.app.ProgressDialog(this);
        uploadProgressDialog.setTitle("正在上传");
        uploadProgressDialog.setMessage("准备中…");
        uploadProgressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        uploadProgressDialog.setMax(100);
        uploadProgressDialog.setProgress(0);
        uploadProgressDialog.setCancelable(false);
        uploadProgressDialog.setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "隐藏", (dialog, which) -> {
            dialog.dismiss();
            Toast.makeText(this, "上传仍在后台进行，请查看通知栏", Toast.LENGTH_SHORT).show();
        });
        uploadProgressDialog.show();

        startUploadPoll();
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

                if (uploadProgressDialog != null && uploadProgressDialog.isShowing()) {
                    if (state.isSuccess) {
                        uploadProgressDialog.dismiss();
                        Toast.makeText(MainActivity.this, "✅ 上传完成", Toast.LENGTH_LONG).show();
                        refreshUi();
                        return;
                    }
                    if (state.isFailure) {
                        uploadProgressDialog.dismiss();
                        Toast.makeText(MainActivity.this, "❌ 上传失败: " + state.errorMsg, Toast.LENGTH_LONG).show();
                        return;
                    }

                    int percent = state.totalBytes > 0
                            ? (int) (state.uploadedBytes * 100 / state.totalBytes) : 0;
                    uploadProgressDialog.setProgress(percent);
                    uploadProgressDialog.setMessage(
                            String.format("第 %d/%d 个文件\n%s\n%d%%",
                                    state.currentFile, state.totalFiles,
                                    state.currentFileName, percent));
                    uploadPollHandler.postDelayed(this, 500);
                }
            }
        };
        uploadPollHandler.postDelayed(uploadPollRunnable, 300);
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
        float currentZoom = CameraConfig.getInstance().currentZoom;
        int cw = getCalibWidth();
        int ch = getCalibHeight();
        return calibrationData.getCalibration(currentZoom, cw, ch);
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
        if (params.source == CameraParamReader.ParamSource.SYSTEM_FACTORY) {
            calibrationData.saveEstimateParams(params);
        } else if (params.source == CameraParamReader.ParamSource.SENSOR_ESTIMATE) {
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

        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        boolean calibrated = isCalibrated(calib);
        boolean isBlocked = uploadManager.hasUploadedButNotDeletedFolders();

        CalibrationConfig calibCfg = CalibrationConfig.getInstance();
        if (calibCfg.forceCalibrationBeforeRecord) {
            uiManager.setRecordEnabled(calibrated && !isBlocked);
        } else {
            uiManager.setRecordEnabled(!isBlocked, calibrated);
        }
    }

    private void toggleRecording() {
        ensureLoggedIn(() -> performToggleRecording());
    }

    private void performToggleRecording() {
        long now = System.currentTimeMillis();
        long debounce = CameraConfig.getInstance().recordButtonDebounceMs;
        if (now - lastToggleClickTime < debounce) {
            LogUtil.w(TAG, "录制按钮点击过快，已忽略");
            return;
        }
        lastToggleClickTime = now;

        if (recordingManager != null && recordingManager.isRecording()) {
            LogUtil.i(TAG, "执行停止录制");
            recordingManager.stop();
            return;
        }

        if (uploadManager.hasUploadedButNotDeletedFolders()) {
            LogUtil.w(TAG, "录制被拦截：存在已上传但未删除的文件夹");
            showRecordingBlockedDialog();
            return;
        }

        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        float currentZoom = CameraConfig.getInstance().currentZoom;
        int cw = getCalibWidth();
        int ch = getCalibHeight();

        if (CalibrationConfig.getInstance().forceCalibrationBeforeRecord && !isCalibrated(calib)) {
            Toast.makeText(this, getString(R.string.uncalibrated_warning, currentZoom, cw, ch), Toast.LENGTH_LONG).show();
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ 当前焦距未标定")
                    .setMessage(String.format(Locale.US,
                            "当前 %.1fx / %dx%d 尚未完成棋盘格标定，无法录制。\n\n点击\"去标定\"立即进入标定界面。",
                            currentZoom, cw, ch))
                    .setPositiveButton("去标定", (d, w) -> openCalibration())
                    .setNegativeButton("取消", null)
                    .setCancelable(false)
                    .show();
            return;
        }

        if (!cameraManager.isCameraReady()) {
            LogUtil.w(TAG, "录制被拦截：相机未就绪");
            Toast.makeText(this, "相机未就绪，请等待初始化完成", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!CameraParamReader.isParamsUsable(lastCameraParams)) {
            LogUtil.w(TAG, "录制被拦截：相机参数不可用");
            Toast.makeText(this, "未获取相机参数，请等待相机初始化完成", Toast.LENGTH_LONG).show();
            return;
        }

        LogUtil.i(TAG, "执行开始录制");
        tryMergeCalibration();
        recordingManager.start(lastCameraParams);
    }

    private void showRecordingBlockedDialog() {
        List<String> paths = uploadManager.getUploadedButNotDeletedPaths();
        StringBuilder sb = new StringBuilder();
        for (String p : paths) sb.append("• ").append(p).append("\n\n");
        new android.app.AlertDialog.Builder(this)
                .setTitle("⚠️ 录制功能已锁定")
                .setMessage("检测到已上传但未删除的本地文件，请先手动删除以释放空间。\n\n" +
                        "如已删除文件但按钮仍锁定，关闭App重新进入即可恢复。\n\n" +
                        "文件路径：\n" + sb)
                .setPositiveButton("我知道了", null)
                .show();
    }

    private void adjustZoom(int direction) {
        CameraConfig cfg = CameraConfig.getInstance();
        float delta = direction * cfg.zoomStep;
        float newZoom = Math.max(cfg.minZoom, Math.min(cfg.maxZoom, cfg.currentZoom + delta));
        cameraManager.setZoom(newZoom);
    }

    private void openCalibration() {
        ensureLoggedIn(() -> {
            cameraManager.stopCamera();
            Intent intent = new Intent(this, CalibrationActivity.class);
            float zoom = CameraConfig.getInstance().currentZoom;
            Size target = CameraConfig.getInstance().targetResolution;
            intent.putExtra(CalibrationActivity.EXTRA_ZOOM_LEVEL, zoom);
            intent.putExtra(CalibrationActivity.EXTRA_RESOLUTION_WIDTH, target.getWidth());
            intent.putExtra(CalibrationActivity.EXTRA_RESOLUTION_HEIGHT, target.getHeight());
            calibrationLauncher.launch(intent);
        });
    }

    private void handleCalibrationSuccess(Intent data) {
        final float finalZoom = (data != null)
                ? data.getFloatExtra("zoom_level", CameraConfig.getInstance().currentZoom)
                : CameraConfig.getInstance().currentZoom;

        int actualW = (data != null) ? data.getIntExtra("actual_width", getCalibWidth()) : getCalibWidth();
        int actualH = (data != null) ? data.getIntExtra("actual_height", getCalibHeight()) : getCalibHeight();
        calibActualSizes.put(finalZoom, new Size(actualW, actualH));
        LogUtil.i(TAG, String.format(Locale.US, "记录标定实际分辨率: zoom=%.1fx, %dx%d", finalZoom, actualW, actualH));

        CameraConfig.getInstance().currentZoom = finalZoom;
        tryMergeCalibration();

        runOnUiThread(() -> {
            uiManager.setParamsText(buildParamText());
            uiManager.updateCalibrateButton(getCurrentCalibResult());
            boolean calibrated = isCalibrated(getCurrentCalibResult());
            uiManager.showUncalibratedBanner(!calibrated, finalZoom, getCalibWidth(), getCalibHeight());
            updateRecordButtonState();
        });

        String zoomHint = String.format(Locale.US,
                "标定完成！当前焦距已自动设为 %.1fx。\n\n如果显示不为 %.1fx，请手动点击变焦按钮切换。",
                finalZoom, finalZoom);

        new AlertDialog.Builder(this)
                .setTitle("✅ 标定成功")
                .setMessage(zoomHint)
                .setPositiveButton("我知道了", (d, w) -> refreshUi())
                .setCancelable(false)
                .show();
    }

    private void refreshUi() {
        tryMergeCalibration();

        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        float currentZoom = CameraConfig.getInstance().currentZoom;
        int cw = getCalibWidth();
        int ch = getCalibHeight();

        uiManager.setParamsText(buildParamText());
        uiManager.updateCalibrateButton(calib);
        boolean calibrated = isCalibrated(calib);
        uiManager.showUncalibratedBanner(!calibrated, currentZoom, cw, ch);
        updateRecordButtonState();
    }

    /**
     * 构建参数显示文本。
     * 【新增】实时显示 JVM 内存占用，超过 85% 报警。
     */
    private String buildParamText() {
        if (lastCameraParams == null) return getString(R.string.camera_initializing);

        StringBuilder sb = new StringBuilder();
        CalibrationData.CalibrationResult calib = getCurrentCalibResult();
        int calibCount = (calib != null) ? calib.calibrationCount : 0;

        // 登录用户
        if (accountManager != null && accountManager.isLoggedIn()) {
            sb.append("用户:").append(accountManager.getCurrentDisplayName())
                    .append("(").append(accountManager.getCurrentRole()).append(")\n");
        } else {
            sb.append("用户:未登录(受限)\n");
        }

        String sourceTag;
        switch (lastCameraParams.source) {
            case SYSTEM_FACTORY: sourceTag = "工厂参数"; break;
            case SENSOR_ESTIMATE: sourceTag = "估算参数"; break;
            case MANUAL_CALIBRATION: sourceTag = "人工标定(" + calibCount + "次)"; break;
            default: sourceTag = "未获取";
        }
        sb.append("来源:").append(sourceTag).append("\n");

        CameraConfig cfg = CameraConfig.getInstance();
        sb.append("\n分辨率:").append(lastCameraParams.videoWidth).append("x")
                .append(lastCameraParams.videoHeight).append("\n");
        sb.append("镜头:").append(String.format(Locale.US, "%.1f-%.1fx", cfg.minZoom, cfg.maxZoom))
                .append(" 当前:").append(String.format(Locale.US, "%.1fx", cfg.currentZoom)).append("\n\n");

        sb.append("内参:\n");
        sb.append(String.format(Locale.US, "fx=%.1f fy=%.1f\n", lastCameraParams.fx, lastCameraParams.fy));
        sb.append(String.format(Locale.US, "cx=%.1f cy=%.1f\n\n", lastCameraParams.cx, lastCameraParams.cy));

        sb.append("畸变:\n");
        float[] d = lastCameraParams.distortion;
        sb.append(String.format(Locale.US, "k1=%.4f k2=%.4f\n", d[0], d[1]));
        sb.append(String.format(Locale.US, "p1=%.4f p2=%.4f\n", d[2], d[3]));
        sb.append(String.format(Locale.US, "k3=%.4f\n\n", d[4]));

        if (lastCameraParams.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION && calib != null) {
            sb.append("误差:").append(String.format(Locale.US, "%.3fpx", calib.rmsError)).append("\n");
            sb.append("日期:").append(calib.calibrationDate).append("\n\n");
        }

        StorageManager sm = new StorageManager(this);
        File baseDir = sm.getBaseDir("RobotData");
        StorageStats stats = calcStorageStats(baseDir);
        long avail = (baseDir != null) ? sm.getAvailableBytes(baseDir) : 0;

        sb.append("存储:\n");
        sb.append("已用:").append(StorageManager.formatSize(stats.usedSpace))
                .append("(").append(stats.videoCount).append("个视频)\n");
        sb.append("可用:").append(StorageManager.formatSize(avail)).append("\n");

        // 【新增】内存监控
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        long usedPercent = maxMem > 0 ? (usedMem * 100 / maxMem) : 0;
        sb.append("\n内存:").append(StorageManager.formatSize(usedMem))
                .append("/").append(StorageManager.formatSize(maxMem))
                .append("(").append(usedPercent).append("%)");
        if (usedPercent > 85) {
            sb.append(" ⚠️内存紧张");
        }

        if (cfg.enableSegmentRecording) {
            sb.append("\n分段:").append(cfg.segmentDurationMs / 1000).append("秒/段");
        }
        if (cfg.enableHandDetection) {
            sb.append(" 无手").append(cfg.noHandTimeoutMs / 1000).append("秒报警");
        }

        if (uploadManager.hasUploadedButNotDeletedFolders()) {
            sb.append("\n\n⚠️录制已锁定\n请删文件或重启App");
        }

        return sb.toString();
    }

    private StorageStats calcStorageStats(File baseDir) {
        StorageStats stats = new StorageStats();
        if (baseDir == null || !baseDir.exists()) return stats;
        File[] dates = baseDir.listFiles();
        if (dates == null) return stats;
        for (File d : dates) {
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
                    if (f.getName().endsWith(".mp4")) stats.videoCount++;
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
        } catch (Exception e) { return false; }
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
            if (now - lastShutterTime < debounce) return true;
            lastShutterTime = now;
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
            uploadManager.release();
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