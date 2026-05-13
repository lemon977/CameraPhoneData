package com.example.cameraphonedata.domain.manager;

import android.content.Context;

import com.example.cameraphonedata.alert.HandAlertManager;
import com.example.cameraphonedata.alert.VoicePromptManager;
import com.example.cameraphonedata.camera.CameraManager;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.detection.HandDetector;
import com.example.cameraphonedata.utils.LogUtil;

public class HandDetectionManager {
    private static final String TAG = "HandDetectionManager";
    private final CameraConfig config;
    private HandDetector detector;
    private HandAlertManager alertManager;

    public HandDetectionManager(Context context, CameraManager cameraManager,
                                VoicePromptManager voicePrompt,
                                RecordingManager recordingManager) {
        this.config = CameraConfig.getInstance();
        if (config.enableHandDetection) {
            this.detector = new HandDetector(config.wristConfidenceThreshold, config.handDetectionIntervalMs);
            this.alertManager = new HandAlertManager(
                    config.noHandTimeoutMs,
                    config.fatalNoHandTimeoutMs,
                    voicePrompt,
                    config.noHandAlertRawResId,
                    config.fatalNoHandStopRawResId, // 【新增】致命停止音频配置
                    () -> {
                        if (recordingManager != null && recordingManager.isRecording()) {
                            LogUtil.i(TAG, "致命无手60s触发，通知录制管理器停止");
                            recordingManager.fatalNoHandStop();
                        }
                    }
            );
            this.detector.setOnHandDetectedListener(alertManager::onHandDetected);
            cameraManager.setFrameAnalyzer(detector);
        }
    }

    public boolean isEnabled() {
        return detector != null;
    }

    public void setEnabled(boolean enabled) {
        if (detector != null) {
            detector.setEnabled(enabled);
        }
        if (alertManager != null && !enabled) {
            alertManager.reset();
        }
    }

    public void release() {
        if (detector != null) {
            detector.release();
            detector = null;
        }
        if (alertManager != null) {
            alertManager.release();
            alertManager = null;
        }
    }
}