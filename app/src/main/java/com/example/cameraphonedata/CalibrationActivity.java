package com.example.cameraphonedata;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.cameraphonedata.calibration.CalibrationManager;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.AccountManager;
import com.example.cameraphonedata.config.CalibrationConfig;
import com.example.cameraphonedata.config.CalibrationData;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标定界面 —— 棋盘格采集与计算。
 */
public class CalibrationActivity extends AppCompatActivity {
    private static final String TAG = "Calibration";
    private static final int PERMISSION_CODE = 1001;
    public static final String EXTRA_ZOOM_LEVEL = "extra_zoom_level";
    public static final String EXTRA_RESOLUTION_WIDTH = "extra_resolution_width";
    public static final String EXTRA_RESOLUTION_HEIGHT = "extra_resolution_height";

    private PreviewView previewView;
    private TextView tvStatus, tvProgress;
    private ProgressBar progressBar;
    private Button btnCapture, btnCompute, btnReset;
    private TextView tvCalibHint;

    private ImageCapture imageCapture;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private ProcessCameraProvider cameraProvider;
    private Camera camera;

    private CalibrationManager calibManager;
    private CalibrationData calibData;
    private CalibrationConfig calibConfig;

    private Size calibImageSize = new Size(0, 0);
    private float calibZoomLevel = 1.0f;

    static {
        OpenCVLoader.initDebug();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 【新增】登录检查：标定界面必须已登录，否则直接退出
        AccountManager accountManager = new AccountManager(this);
        if (!accountManager.isLoggedIn()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        enterFullscreen();
        setContentView(R.layout.activity_calibration);

        calibZoomLevel = getIntent().getFloatExtra(EXTRA_ZOOM_LEVEL, 1.0f);
        int lockedW = getIntent().getIntExtra(EXTRA_RESOLUTION_WIDTH, 1920);
        int lockedH = getIntent().getIntExtra(EXTRA_RESOLUTION_HEIGHT, 1080);

        calibConfig = CalibrationConfig.getInstance();
        calibConfig.setPrintPattern9x6();
        calibConfig.lockedZoom = calibZoomLevel;
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

        tvProgress.setText(getString(R.string.calib_progress_default, calibConfig.requiredPhotos));
        tvCalibHint.setText(getString(R.string.calib_hint, calibConfig.requiredPhotos)
                + "\n\n【方向】横屏(长边水平)"
                + "\n【锁定】" + calibConfig.lockedResolution.getWidth() + "x" + calibConfig.lockedResolution.getHeight());

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

                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);

                if (camera == null) {
                    throw new RuntimeException("相机绑定失败");
                }

                if (calibZoomLevel > 0) {
                    try {
                        camera.getCameraControl().setZoomRatio(calibZoomLevel);
                        Log.i(TAG, "标定界面已应用 zoom: " + calibZoomLevel + "x");
                    } catch (Exception e) {
                        Log.e(TAG, "设置标定 zoom 失败", e);
                    }
                }

                tvStatus.setText(getString(R.string.calib_locked_status,
                        calibZoomLevel,
                        calibConfig.lockedResolution.getWidth(),
                        calibConfig.lockedResolution.getHeight()));
            } catch (Exception e) {
                Log.e(TAG, "相机启动失败", e);
                tvStatus.setText(getString(R.string.calib_camera_error, e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
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
            Log.w(TAG, "创建临时目录失败");
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
                                    Log.w(TAG, "删除临时文件失败");
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
                            Log.w(TAG, "删除失败临时文件失败");
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
                Log.i(TAG, "标定图像尺寸锁定: " + calibImageSize.getWidth() + "x" + calibImageSize.getHeight());

                int targetW = calibConfig.calibTargetResolution.getWidth();
                int targetH = calibConfig.calibTargetResolution.getHeight();
                if (Math.abs(bitmap.getWidth() - targetW) > 50 || Math.abs(bitmap.getHeight() - targetH) > 50) {
                    Log.w(TAG, String.format(Locale.US,
                            "警告：实际分辨率(%dx%d)与目标配置(%dx%d)不一致，将在保存时自动映射",
                            bitmap.getWidth(), bitmap.getHeight(), targetW, targetH));
                }
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
            Log.i(TAG, "耗时: " + cost + "ms, 检测结果: " + found);

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
            Log.e(TAG, "内存不足", e);
            mainHandler.post(() -> {
                tvStatus.setText(R.string.calib_out_of_memory);
                btnCapture.setEnabled(true);
                isProcessing.set(false);
            });
        } catch (Exception e) {
            Log.e(TAG, "处理失败", e);
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
                java.util.Arrays.sort(existing, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                existing[0].delete();
            }

            String name = "debug_failed_" + System.currentTimeMillis() + ".png";
            File debugFile = new File(debugDir, name);
            org.opencv.imgcodecs.Imgcodecs.imwrite(debugFile.getAbsolutePath(), gray);
            Log.i(TAG, "调试图已保存: " + debugFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "保存调试图失败", e);
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
                                .setCancelable(false)
                                .show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "标定计算异常", e);
                mainHandler.post(() -> {
                    tvStatus.setText(getString(R.string.calib_compute_error, e.getMessage()));
                    btnCompute.setEnabled(true);
                });
            }
        });
    }

    private void saveAndShowResult(CalibrationManager.CalibrationResult result) {
        if (result.rmsError >= calibConfig.maxRmsError) {
            Toast.makeText(this,
                    String.format(Locale.US, "标定不合格（%.2f ≥ %.1f），无法保存",
                            result.rmsError, calibConfig.maxRmsError),
                    Toast.LENGTH_LONG).show();
            btnCompute.setEnabled(true);
            return;
        }

        int actualW = calibImageSize.getWidth();
        int actualH = calibImageSize.getHeight();
        int targetW = calibConfig.calibTargetResolution.getWidth();
        int targetH = calibConfig.calibTargetResolution.getHeight();

        float scaleX = 1.0f, scaleY = 1.0f;
        if (actualW > 0 && actualH > 0 && (actualW != targetW || actualH != targetH)) {
            scaleX = (float) targetW / actualW;
            scaleY = (float) targetH / actualH;
            Log.i(TAG, String.format(Locale.US,
                    "标定分辨率映射: 实际%dx%d -> 目标%dx%d, scale=(%.4f, %.4f)",
                    actualW, actualH, targetW, targetH, scaleX, scaleY));
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
        data.zoomLevel = calibZoomLevel;

        String phoneId = calibData.getPhoneId();
        calibData.saveCalibration(data, phoneId, calibZoomLevel, targetW, targetH);

        exportJsonToFile(result, phoneId, calibZoomLevel, actualW, actualH, targetW, targetH, scaleX, scaleY);

        Intent resultIntent = new Intent();
        resultIntent.putExtra("zoom_level", calibZoomLevel);
        resultIntent.putExtra("actual_width", actualW);
        resultIntent.putExtra("actual_height", actualH);

        new AlertDialog.Builder(this)
                .setTitle("标定完成")
                .setMessage(String.format(Locale.US,
                        "变焦: %.1fx\n实际分辨率: %dx%d\n目标配置: %dx%d\n重投影误差: %.3f 像素\n" +
                                "fx=%.2f, fy=%.2f\ncx=%.2f, cy=%.2f\n" +
                                "畸变8维: [%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f]",
                        calibZoomLevel, actualW, actualH, targetW, targetH,
                        result.rmsError, data.fx, data.fy, data.cx, data.cy,
                        result.distortion[0], result.distortion[1], result.distortion[2],
                        result.distortion[3], result.distortion[4], result.distortion[5],
                        result.distortion[6], result.distortion[7]))
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    setResult(RESULT_OK, resultIntent);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void exportJsonToFile(CalibrationManager.CalibrationResult result, String phoneId,
                                  float zoomLevel, int actualWidth, int actualHeight,
                                  int targetWidth, int targetHeight,
                                  float scaleX, float scaleY) {
        java.io.FileOutputStream fos = null;
        try {
            File file = new File(getExternalFilesDir(null),
                    "calib_" + phoneId + "_" + String.format(Locale.US, "%.1fx", zoomLevel)
                            + "_" + actualWidth + "x" + actualHeight + ".json");
            fos = new java.io.FileOutputStream(file);

            JSONObject root = new JSONObject();
            root.put("phone_id", phoneId);
            root.put("zoom_level", String.format(Locale.US, "%.1f", zoomLevel));
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
            Log.i(TAG, "标定数据已导出(5维): " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "导出JSON失败", e);
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
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        calibManager.reset();
        Log.i(TAG, "标定界面销毁");
    }
}