package com.example.cameraphonedata;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.cameraphonedata.alert.VoicePromptManager;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.domain.manager.HandDetectionManager;
import com.example.cameraphonedata.service.RecordingForegroundService;

/**
 * 录制协调器 —— 录制状态的 UI 联动、前台服务启停、人手检测开关、语音播报。
 */
public class RecordingCoordinator {
    private final Context context;
    private final MainUiManager uiManager;
    private final HandDetectionManager handDetectionManager;
    private final VoicePromptManager voicePromptManager;

    private RecordingForegroundService recordingService;
    private boolean serviceBound = false;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            recordingService = ((RecordingForegroundService.LocalBinder) service).getService();
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            recordingService = null;
        }
    };

    public RecordingCoordinator(Context context, MainUiManager uiManager,
                                HandDetectionManager handDetectionManager,
                                VoicePromptManager voicePromptManager) {
        this.context = context.getApplicationContext();
        this.uiManager = uiManager;
        this.handDetectionManager = handDetectionManager;
        this.voicePromptManager = voicePromptManager;
    }

    public void bindService() {
        Intent intent = new Intent(context, RecordingForegroundService.class);
        ContextCompat.startForegroundService(context, intent);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public void unbindService() {
        if (serviceBound) {
            context.unbindService(serviceConnection);
            serviceBound = false;
        }
        Intent intent = new Intent(context, RecordingForegroundService.class);
        intent.setAction("STOP");
        ContextCompat.startForegroundService(context, intent);
    }

    public void onRecordingStarted() {
        uiManager.setRecordingState(true);
        if (handDetectionManager != null) handDetectionManager.setEnabled(true);
        bindService();

        if (voicePromptManager != null && CameraConfig.getInstance().enableVoicePrompt) {
            voicePromptManager.speak("开始录制", CameraConfig.getInstance().recordStartRawResId);
        }
    }

    public void onRecordingStopping() {
        uiManager.setRecordStopping();
    }

    public void onRecordingStopped() {
        uiManager.setRecordingState(false);
        if (handDetectionManager != null) handDetectionManager.setEnabled(false);
        unbindService();

        if (voicePromptManager != null && CameraConfig.getInstance().enableVoicePrompt) {
            voicePromptManager.speak("录制结束", CameraConfig.getInstance().recordStopRawResId);
        }
    }

    public void onProgress(String timeText, int segIdx) {
        uiManager.updateRecordTime(String.format("%s (片段%d)", timeText, segIdx + 1));
        if (serviceBound && recordingService != null) {
            recordingService.updateNotification("录制中: " + timeText + " (片段" + (segIdx + 1) + ")");
        }
    }

    /**
     * 某段数据集保存完成提示。
     * segNumber: 1-based（第1个、第2个...），由 RecordingManager 传入，不会出现 0。
     */
    public void onSegmentEnded(int segNumber) {
        Toast.makeText(context, String.format("第 %d 个数据集已保存，继续录制…", segNumber), Toast.LENGTH_SHORT).show();
        if (voicePromptManager != null && CameraConfig.getInstance().enableVoicePrompt) {
            voicePromptManager.speak("第" + segNumber + "个数据集已保存",
                    CameraConfig.getInstance().segmentSavedRawResId);
        }
    }
}