package com.example.cameraphonedata;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.util.Size;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.cameraphonedata.calibration.CalibrationManager;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.config.AccountManager;
import com.example.cameraphonedata.config.CalibrationConfig;
import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.config.CameraConfig;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标定界面 —— 按 LensRole（镜头角色）采集与计算。
 */
public class CalibrationActivity extends AppCompatActivity {
    private static final String TAG = "Calibration";
    private static final int PERMISSION_CODE = 1001;
    public static final String EXTRA_LENS_ROLE = "extra_lens_role";
    public static final String EXTRA_RESOLUTION_WIDTH = "extra_resolution_width";
    public static final String EXTRA_RESOLUTION_HEIGHT = "extra_resolution_height";
    public static final String EXTRA_IS_FUSION = "extra_is_fusion";
    public static final String EXTRA_MIN_ZOOM = "extra_min_zoom";

    private PreviewView previewView;
    private TextView tvStatus, tvProgress;
    private ProgressBar progressBar;
    private Button btnCapture, btnCompute, btnReset;
    private TextView tvCalibHint;

    private ImageCapture imageCapture;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    private CalibrationManager calibManager;
    private CalibrationData calibData;
    private CalibrationConfig calibConfig;

    private Size calibImageSize = new Size(0, 0);
    private CameraConfig.LensRole calibLensRole = CameraConfig.LensRole.WIDE;

    // 【新增】融合架构参数
    private boolean isFusionArchitecture = false;
    private float fusionMinZoom = 1.0f;
    private Camera camera; // 用于控制 zoom
    private ProcessCameraProvider cameraProvider; // 【修复】保存实例用于 onDestroy 解绑
    private static final int ZOOM_ENSURE_RETRY_COUNT = 4;
    private static final long ZOOM_ENSURE_RETRY_DELAY_MS = 180L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AccountManager accountManager = new AccountManager(this);
        if (!accountManager.isLoggedIn()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        enterFullscreen();
        setContentView(R.layout.activity_calibration);

        String roleStr = getIntent().getStringExtra(EXTRA_LENS_ROLE);
        if (roleStr != null) {
            try {
                calibLensRole = CameraConfig.LensRole.valueOf(roleStr);
            } catch (Exception e) {
                calibLensRole = CameraConfig.LensRole.WIDE;
            }
        }
        int lockedW = getIntent().getIntExtra(EXTRA_RESOLUTION_WIDTH, 1920);
        int lockedH = getIntent().getIntExtra(EXTRA_RESOLUTION_HEIGHT, 1080);

        // 【新增】读取融合架构参数
        isFusionArchitecture = getIntent().getBooleanExtra(EXTRA_IS_FUSION, false);
        fusionMinZoom = getIntent().getFloatExtra(EXTRA_MIN_ZOOM, 1.0f);

        calibConfig = CalibrationConfig.getInstance();
        calibConfig.setPrintPattern9x6();
        calibConfig.lockedResolution = new Size(lockedW, lockedH);
        calibConfig.calibTargetResolution = new Size(lockedW, lockedH);

        calibManager = new CalibrationManager(calibConfig);
        calibData = new CalibrationData(this);
        executor = Executors.newSingleThreadExecutor();

        initViews();
        checkPermissionAndStart();
    }

    private void enterFullscreen() {
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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

    @Override
    protected void onResume() {
        super.onResume();
        // 【关键修复】融合架构下，Activity 变为前台后再延迟确认 zoom。
        // startCamera() 在 onCreate() 中调用时 ZoomState 可能未就绪，setZoomRatio 会被忽略。
        // onResume() 时 CameraX 已完成初始化，此时修正才能确保生效。
        if (isFusionArchitecture && camera != null) {
            float expectedZoom = (calibLensRole == CameraConfig.LensRole.ULTRA_WIDE && fusionMinZoom < 1.0f)
                    ? fusionMinZoom : 1.0f;
            mainHandler.postDelayed(() -> {
                try {
                    if (camera != null) {
                        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
                        if (zs != null) {
                            float actualZoom = zs.getZoomRatio();
                            if (Math.abs(actualZoom - expectedZoom) > 0.05f) {
                                camera.getCameraControl().setZoomRatio(expectedZoom);
                                LogUtil.i(TAG, "标定界面 onResume 修正 zoom: " + expectedZoom + "x");
                            }
                        }
                    }
                } catch (Exception e) {
                    LogUtil.e(TAG, "标定界面 onResume zoom 修正失败", e);
                }
            }, 500);
        }
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        tvStatus = findViewById(R.id.tvCalibStatus);
        tvProgress = findViewById(R.id.tvCalibProgress);
        progressBar = findViewById(R.id.progressBar);
        btnCapture = findViewById(R.id.btnCapture);
        btnCompute = findViewById(R.id.btnCompute);
        btnReset = findViewById(R.id.btnReset);
        tvCalibHint = findViewById(R.id.tvCalibHint);

        btnCapture.setOnClickListener(v -> capture());
        btnCompute.setOnClickListener(v -> computeCalibration());
        btnReset.setOnClickListener(v -> resetCalibration());

        Button btnExitCalib = findViewById(R.id.btnExitCalib);
        btnExitCalib.setOnClickListener(v -> finish());

        String roleLabel = (calibLensRole == CameraConfig.LensRole.ULTRA_WIDE) ? "广角" : "主摄";

        StringBuilder hintBuilder = new StringBuilder();
        hintBuilder.append(getString(R.string.calib_hint, calibConfig.requiredPhotos));
        hintBuilder.append("\n\n【镜头】").append(roleLabel);
        hintBuilder.append("\n【分辨率】").append(calibConfig.lockedResolution.getWidth())
                .append("x").append(calibConfig.lockedResolution.getHeight());
        if (isFusionArchitecture) {
            hintBuilder.append("\n【融合架构】minZoom=").append(fusionMinZoom).append("x");
        }
        tvCalibHint.setText(hintBuilder.toString());

        tvProgress.setText(getString(R.string.calib_progress_default, calibConfig.requiredPhotos));
        updateUI();
    }

    private void checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, PERMISSION_CODE);
        }
    }

    @SuppressWarnings("deprecation")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageCapture.Builder builder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetResolution(calibConfig.lockedResolution);

                imageCapture = builder.build();

                CameraSelector selector = buildSelectorForLensRole(this, calibLensRole);

                // 【关键】保存 Camera 实例用于 zoom 控制
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

                // 融合架构机型在 CameraX 刚绑定完成时，首次 setZoomRatio 可能被忽略。
                // 这里做一次多轮短延迟确认，保证广角角色真正落到广角视野。
                enforceFusionLensViewIfNeeded();

                tvStatus.setText(getString(R.string.calib_locked_status,
                        calibLensRole == CameraConfig.LensRole.ULTRA_WIDE ? "广角" : "主摄",
                        calibConfig.lockedResolution.getWidth(),
                        calibConfig.lockedResolution.getHeight()));
            } catch (Exception e) {
                LogUtil.e(TAG, "相机启动失败", e);
                tvStatus.setText(getString(R.string.calib_camera_error, e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void enforceFusionLensViewIfNeeded() {
        if (!isFusionArchitecture || camera == null) return;
        float targetZoom = (calibLensRole == CameraConfig.LensRole.ULTRA_WIDE && fusionMinZoom < 1.0f)
                ? fusionMinZoom : 1.0f;
        enforceZoomWithRetry(targetZoom, ZOOM_ENSURE_RETRY_COUNT);
    }

    private void enforceZoomWithRetry(float targetZoom, int retriesLeft) {
        if (camera == null) return;
        try {
            camera.getCameraControl().setZoomRatio(targetZoom);
        } catch (Exception e) {
            LogUtil.e(TAG, "标定界面设置 zoom 失败", e);
            return;
        }
        mainHandler.postDelayed(() -> {
            if (camera == null) return;
            try {
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state == null) {
                    if (retriesLeft > 0) {
                        enforceZoomWithRetry(targetZoom, retriesLeft - 1);
                    }
                    return;
                }
                float actualZoom = state.getZoomRatio();
                if (Math.abs(actualZoom - targetZoom) > 0.05f) {
                    LogUtil.w(TAG, "标定界面 zoom 未命中，期望=" + targetZoom + "x, 实际=" + actualZoom + "x, 重试剩余=" + retriesLeft);
                    if (retriesLeft > 0) {
                        enforceZoomWithRetry(targetZoom, retriesLeft - 1);
                    } else {
                        camera.getCameraControl().setZoomRatio(targetZoom);
                    }
                } else {
                    LogUtil.i(TAG, "标定界面 zoom 已命中: " + actualZoom + "x");
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "标定界面确认 zoom 失败", e);
            }
        }, ZOOM_ENSURE_RETRY_DELAY_MS);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private CameraSelector buildSelectorForLensRole(Context context, CameraConfig.LensRole role) {
        if (role == CameraConfig.LensRole.WIDE) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }

        // 【关键修复】融合架构：直接返回默认后置，通过 zoom 实现广角/主摄切换，
        // 避免用物理焦距探测逻辑把融合架构误判为无超广角
        if (isFusionArchitecture) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }

        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            LogUtil.w(TAG, "系统 CameraManager 为空，fallback 到默认后置");
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }

        String ultraWideId = null;
        float minFocal = Float.MAX_VALUE;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;

                float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focalLengths == null || focalLengths.length == 0) continue;

                float focal = focalLengths[0];
                if (focal < minFocal) {
                    minFocal = focal;
                    ultraWideId = id;
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "镜头探测异常", e);
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }

        if (ultraWideId != null && minFocal < 5.0f) {
            final String targetId = ultraWideId;
            return new CameraSelector.Builder()
                    .addCameraFilter(cameraInfos -> {
                        List<CameraInfo> result = new ArrayList<>();
                        for (CameraInfo ci : cameraInfos) {
                            try {
                                Camera2CameraInfo c2 = Camera2CameraInfo.from(ci);
                                if (targetId.equals(c2.getCameraId())) {
                                    result.add(ci);
                                    break;
                                }
                            } catch (Exception e) {
                                LogUtil.w(TAG, "镜头过滤匹配异常: " + targetId, e);
                            }
                        }
                        return result;
                    })
                    .build();
        }

        LogUtil.w(TAG, "未探测到超广角，fallback 到默认后置");
        return CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void capture() {
        if (imageCapture == null || isProcessing.get()) return;

        if (calibManager.isComplete()) {
            Toast.makeText(this, "已采集" + calibConfig.requiredPhotos + "张，请点击计算标定", Toast.LENGTH_SHORT).show();
            return;
        }

        isProcessing.set(true);
        btnCapture.setEnabled(false);
        tvStatus.setText(R.string.calib_capturing);

        File tmpDir = new File(getCacheDir(), "calib_tmp");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            LogUtil.w(TAG, "创建临时目录失败");
        }
        File tmpFile = new File(tmpDir, "calib_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(tmpFile).build();

        imageCapture.takePicture(options, executor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        executor.execute(() -> {
                            try {
                                processImage(tmpFile);
                            } finally {
                                if (tmpFile.exists() && !tmpFile.delete()) {
                                    LogUtil.w(TAG, "删除临时文件失败");
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        mainHandler.post(() -> {
                            tvStatus.setText(getString(R.string.calib_capture_failed, exception.getMessage()));
                            btnCapture.setEnabled(true);
                            isProcessing.set(false);
                        });
                        if (tmpFile.exists() && !tmpFile.delete()) {
                            LogUtil.w(TAG, "删除临时文件失败");
                        }
                    }
                });
    }

    private void processImage(File jpegFile) {
        long start = System.currentTimeMillis();
        Mat gray = null;
        Mat rgba = null;
        Bitmap bitmap = null;

        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            bitmap = BitmapFactory.decodeFile(jpegFile.getAbsolutePath(), opts);
            if (bitmap == null) {
                throw new RuntimeException("JPEG 解码失败");
            }

            if (calibImageSize.getWidth() == 0) {
                calibImageSize = new Size(bitmap.getWidth(), bitmap.getHeight());
                LogUtil.i(TAG, "标定图像尺寸锁定: " + calibImageSize.getWidth() + "x" + calibImageSize.getHeight());
            }

            rgba = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC4);
            org.opencv.android.Utils.bitmapToMat(bitmap, rgba);
            bitmap.recycle();
            bitmap = null;

            gray = new Mat();
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            rgba.release();
            rgba = null;

            boolean found = calibManager.addCalibrationImage(gray);
            long cost = System.currentTimeMillis() - start;
            LogUtil.i(TAG, "耗时: " + cost + "ms, 检测结果: " + found);

            if (!found) {
                saveDebugGray(gray);
            }

            final boolean detected = found;
            mainHandler.post(() -> {
                if (detected) {
                    updateUI();
                    int remaining = calibConfig.requiredPhotos - calibManager.getCapturedCount();
                    if (remaining <= 0) {
                        btnCompute.setEnabled(true);
                        tvStatus.setText(R.string.calib_progress_complete);
                    } else {
                        tvStatus.setText(getString(R.string.calib_progress_remaining, remaining));
                    }
                } else {
                    tvStatus.setText(R.string.calib_not_detected);
                }
                btnCapture.setEnabled(true);
                isProcessing.set(false);
            });

        } catch (OutOfMemoryError e) {
            LogUtil.e(TAG, "标定内存不足，抛出交由系统处理", e);
            throw e; // OOM 是 JVM 严重错误，不应捕获后继续使用
        } catch (Exception e) {
            LogUtil.e(TAG, "处理失败", e);
            mainHandler.post(() -> {
                tvStatus.setText(getString(R.string.calib_process_error, e.getMessage()));
                btnCapture.setEnabled(true);
                isProcessing.set(false);
            });
        } finally {
            if (gray != null) gray.release();
            if (rgba != null) rgba.release();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void saveDebugGray(Mat gray) {
        try {
            File debugDir = new File(getCacheDir(), "calib_debug");
            if (!debugDir.exists() && !debugDir.mkdirs()) return;

            File[] existing = debugDir.listFiles();
            if (existing != null && existing.length >= 10) {
                java.util.Arrays.sort(existing, Comparator.comparingLong(File::lastModified));
                if (!existing[0].delete()) {
                    LogUtil.w(TAG, "删除旧调试图失败");
                }
            }

            String name = "debug_failed_" + System.currentTimeMillis() + ".png";
            File debugFile = new File(debugDir, name);
            org.opencv.imgcodecs.Imgcodecs.imwrite(debugFile.getAbsolutePath(), gray);
            LogUtil.i(TAG, "调试图已保存: " + debugFile.getAbsolutePath());
        } catch (Exception e) {
            LogUtil.e(TAG, "保存调试图失败", e);
        }
    }

    private void computeCalibration() {
        if (calibManager.getCapturedCount() < calibConfig.requiredPhotos) {
            Toast.makeText(this, getString(R.string.calib_min_photos_required, calibConfig.requiredPhotos),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (calibImageSize.getWidth() <= 0) {
            Toast.makeText(this, R.string.calib_no_image_size, Toast.LENGTH_SHORT).show();
            return;
        }

        btnCompute.setEnabled(false);
        tvStatus.setText(R.string.calibration_computing);

        executor.execute(() -> {
            try {
                CalibrationManager.CalibrationResult result = calibManager.calibrate(calibImageSize);

                mainHandler.post(() -> {
                    if (result == null) {
                        tvStatus.setText(R.string.calib_compute_failed);
                        btnCompute.setEnabled(true);
                        return;
                    }

                    if (result.rmsError < calibConfig.maxRmsError) {
                        saveAndShowResult(result);
                    } else {
                        String msg = getString(R.string.calib_rms_too_high,
                                result.rmsError, calibConfig.maxRmsError);
                        tvStatus.setText(msg);
                        btnCompute.setEnabled(true);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.calib_dialog_title)
                                .setMessage(msg + "\n\n" + getString(R.string.calib_dialog_tips))
                                .setPositiveButton(R.string.calib_dialog_reshoot, (d, w) -> resetCalibration())
                                .setNegativeButton(R.string.calib_exit, (d, w) -> finish())
                                .setCancelable(true)
                                .show();
                    }
                });
            } catch (Exception e) {
                LogUtil.e(TAG, "标定计算异常", e);
                mainHandler.post(() -> {
                    tvStatus.setText(getString(R.string.calib_compute_error, e.getMessage()));
                    btnCompute.setEnabled(true);
                });
            }
        });
    }

    private void saveAndShowResult(CalibrationManager.CalibrationResult result) {
        int actualW = calibImageSize.getWidth();
        int actualH = calibImageSize.getHeight();
        int targetW = calibConfig.calibTargetResolution.getWidth();
        int targetH = calibConfig.calibTargetResolution.getHeight();

        float scaleX = 1.0f, scaleY = 1.0f;
        if (actualW > 0 && actualH > 0 && (actualW != targetW || actualH != targetH)) {
            scaleX = (float) targetW / actualW;
            scaleY = (float) targetH / actualH;
        }

        CalibrationData.CalibrationResult data = new CalibrationData.CalibrationResult();
        data.fx = result.fx * scaleX;
        data.fy = result.fy * scaleY;
        data.cx = result.cx * scaleX;
        data.cy = result.cy * scaleY;
        data.distortion = result.distortion;
        data.rmsError = result.rmsError;
        data.calibrationDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        data.photosUsed = result.photosUsed;
        data.source = CameraParamReader.ParamSource.MANUAL_CALIBRATION;
        data.imageWidth = targetW;
        data.imageHeight = targetH;
        data.zoomLevel = 1.0f;
        data.lensRole = calibLensRole;

        String phoneId = calibData.getPhoneId();
        calibData.saveCalibration(data, phoneId, calibLensRole, targetW, targetH);

        exportJsonToFile(result, phoneId, calibLensRole, actualW, actualH, targetW, targetH, scaleX, scaleY);

        Intent resultIntent = new Intent();
        resultIntent.putExtra("lens_role", calibLensRole.name());

        String roleLabel = (calibLensRole == CameraConfig.LensRole.ULTRA_WIDE) ? "广角" : "主摄";
        new AlertDialog.Builder(this)
                .setTitle(R.string.calib_success_title)
                .setMessage(String.format(Locale.US,
                        "镜头: %s\n实际分辨率: %dx%d\n目标配置: %dx%d\n重投影误差: %.3f 像素\n" +
                                "fx=%.2f, fy=%.2f\ncx=%.2f, cy=%.2f\n" +
                                "畸变8维: [%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f]",
                        roleLabel, actualW, actualH, targetW, targetH,
                        result.rmsError, data.fx, data.fy, data.cx, data.cy,
                        result.distortion[0], result.distortion[1], result.distortion[2],
                        result.distortion[3], result.distortion[4], result.distortion[5],
                        result.distortion[6], result.distortion[7]))
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    setResult(RESULT_OK, resultIntent);
                    finish();
                })
                .setCancelable(true)
                .show();
    }

    private void exportJsonToFile(CalibrationManager.CalibrationResult result, String phoneId,
                                  CameraConfig.LensRole lensRole, int actualWidth, int actualHeight,
                                  int targetWidth, int targetHeight,
                                  float scaleX, float scaleY) {
        java.io.FileOutputStream fos = null;
        try {
            String roleTag = lensRole.name().toLowerCase();
            File file = new File(getExternalFilesDir(null),
                    "calib_" + phoneId + "_" + roleTag
                            + "_" + actualWidth + "x" + actualHeight + ".json");
            fos = new java.io.FileOutputStream(file);

            JSONObject root = new JSONObject();
            root.put("phone_id", phoneId);
            root.put("lens_role", lensRole.name());
            root.put("actual_resolution", new JSONObject()
                    .put("width", actualWidth).put("height", actualHeight));
            root.put("target_resolution", new JSONObject()
                    .put("width", targetWidth).put("height", targetHeight));
            root.put("scale", new JSONObject()
                    .put("x", String.format(Locale.US, "%.4f", scaleX))
                    .put("y", String.format(Locale.US, "%.4f", scaleY)));
            root.put("calibration_date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
            root.put("calibration_model", "Brown-Conrady-5");

            JSONObject camMatrix = new JSONObject();
            camMatrix.put("fx", String.format(Locale.US, "%.4f", result.fx));
            camMatrix.put("fy", String.format(Locale.US, "%.4f", result.fy));
            camMatrix.put("cx", String.format(Locale.US, "%.4f", result.cx));
            camMatrix.put("cy", String.format(Locale.US, "%.4f", result.cy));
            root.put("camera_matrix", camMatrix);

            JSONObject dist = new JSONObject();
            dist.put("k1", String.format(Locale.US, "%.6f", result.distortion[0]));
            dist.put("k2", String.format(Locale.US, "%.6f", result.distortion[1]));
            dist.put("p1", String.format(Locale.US, "%.6f", result.distortion[2]));
            dist.put("p2", String.format(Locale.US, "%.6f", result.distortion[3]));
            dist.put("k3", String.format(Locale.US, "%.6f", result.distortion[4]));
            root.put("distortion_coefficients", dist);

            root.put("rms_error", String.format(Locale.US, "%.4f", result.rmsError));
            root.put("photos_used", result.photosUsed);

            fos.write(root.toString(2).getBytes());
            LogUtil.i(TAG, "标定数据已导出: " + file.getAbsolutePath());
        } catch (Exception e) {
            LogUtil.e(TAG, "导出JSON失败", e);
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void resetCalibration() {
        calibManager.reset();
        calibImageSize = new Size(0, 0);
        updateUI();
        tvStatus.setText(R.string.calib_reset_done);
    }

    private void updateUI() {
        int current = calibManager.getCapturedCount();
        int total = calibConfig.requiredPhotos;
        int displayCurrent = Math.min(current, total);
        tvProgress.setText(String.format(Locale.US, "%d/%d", displayCurrent, total));
        progressBar.setProgress(displayCurrent * 100 / total);
        btnCompute.setEnabled(current >= total);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DialogManager.getInstance(this).clear();
        // 【注意】不要在此处调用 cameraProvider.unbindAll()！
        // CalibrationActivity 的用例绑定到自身生命周期，CameraX 会自动解绑。
        // 手动 unbindAll() 会误伤 MainActivity 在 onResume() 中刚启动的相机，导致黑屏。
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        calibManager.reset();
        LogUtil.i(TAG, "标定界面销毁");
    }
}