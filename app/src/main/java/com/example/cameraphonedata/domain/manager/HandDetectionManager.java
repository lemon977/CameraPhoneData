package com.example.cameraphonedata.domain.manager;

import android.content.Context;

import com.example.cameraphonedata.alert.HandAlertManager;
import com.example.cameraphonedata.alert.VoicePromptManager;
import com.example.cameraphonedata.camera.CameraManager;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.detection.HandDetector;

public class HandDetectionManager {
    private final CameraConfig config;
    private HandDetector detector;
    private HandAlertManager alertManager;

    public HandDetectionManager(Context context, CameraManager cameraManager, VoicePromptManager voicePrompt) {
        this.config = CameraConfig.getInstance();
        if (config.enableHandDetection) {
            this.detector = new HandDetector(config.wristConfidenceThreshold, config.handDetectionIntervalMs);
            this.alertManager = new HandAlertManager(config.noHandTimeoutMs, voicePrompt, config.noHandAlertRawResId);
            this.detector.setOnHandDetectedListener(alertManager::onHandDetected);
            cameraManager.setFrameAnalyzer(detector);
        }
    }

    public boolean isEnabled() {
        return detector != null;
    }

    /** 录制时开，平时关 */
    public void setEnabled(boolean enabled) {
        if (detector != null) {
            detector.setEnabled(enabled);
        }
        // 【关键修复】停止检测时彻底重置报警，而不是模拟检测到手
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