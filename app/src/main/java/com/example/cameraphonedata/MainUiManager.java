package com.example.cameraphonedata;

import android.app.Activity;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CalibrationData;
import com.example.cameraphonedata.config.CameraConfig;
import java.util.Locale;

/**
 * 主界面 UI 管理器 —— 所有 View 状态集中控制。
 * 【镜头角色版】
 * - 标定按钮显示"广角未标定/主摄未标定"或"已标定N次"
 * - 镜头显示区显示"广角"或"主摄"
 * - 录制按钮逻辑不变
 */
public class MainUiManager {
    private final TextView tvParams, tvRecordTime, tvZoomInfo, tvServerStatus;
    private final Button btnZoomIn, btnZoomOut, btnRecord, btnExportPC, btnCalibrate;
    private boolean isRecordingState = false;

    public MainUiManager(Activity activity) {
        tvParams = activity.findViewById(R.id.tvParams);
        tvRecordTime = activity.findViewById(R.id.tvRecordTime);
        tvZoomInfo = activity.findViewById(R.id.tvZoomInfo);
        tvServerStatus = activity.findViewById(R.id.tvServerStatus);
        btnZoomIn = activity.findViewById(R.id.btnZoomIn);
        btnZoomOut = activity.findViewById(R.id.btnZoomOut);
        btnRecord = activity.findViewById(R.id.btnRecord);
        btnExportPC = activity.findViewById(R.id.btnExportPC);
        btnCalibrate = activity.findViewById(R.id.btnCalibrate);

        if (tvParams == null || tvRecordTime == null || tvZoomInfo == null || btnRecord == null) {
            throw new IllegalStateException("MainUiManager: 关键布局控件未找到，请检查 activity_main.xml");
        }

        setRecordingState(false);
    }

    public void setParamsText(CharSequence text) {
        tvParams.setText(text);
    }

    /** 根据标定状态更新标定按钮外观。 */
    public void updateCalibrateButton(CalibrationData.CalibrationResult calib) {
        if (btnCalibrate == null) return;
        boolean isCalibrated = calib != null
                && calib.source == CameraParamReader.ParamSource.MANUAL_CALIBRATION;
        if (!isCalibrated) {
            btnCalibrate.setText(R.string.calibration_status_uncalibrated);
            btnCalibrate.setBackgroundTintList(ContextCompat.getColorStateList(btnCalibrate.getContext(), android.R.color.holo_red_dark));
        } else {
            btnCalibrate.setText(String.format(Locale.CHINA, "✅ 已标定 %d 次（点击重新标定）", calib.calibrationCount));
            btnCalibrate.setBackgroundTintList(ContextCompat.getColorStateList(btnCalibrate.getContext(), android.R.color.holo_green_dark));
        }
    }

    /** 【修改】显示镜头角色标签 */
    public void updateLensDisplay(CameraConfig.LensRole role) {
        String label = (role == CameraConfig.LensRole.ULTRA_WIDE) ? "广角" : "主摄";
        tvZoomInfo.setText(label);
    }

    /** 【修改】未标定横幅按镜头角色显示 */
    public void showUncalibratedBanner(boolean show, CameraConfig.LensRole role, int width, int height) {
        if (tvServerStatus == null) return;
        if (show) {
            String label = (role == CameraConfig.LensRole.ULTRA_WIDE) ? "广角" : "主摄";
            tvServerStatus.setVisibility(android.view.View.VISIBLE);
            tvServerStatus.setBackgroundColor(0xFFB71C1C);
            tvServerStatus.setTextColor(0xFFFFFFFF);
            tvServerStatus.setText(String.format(Locale.US,
                    "⚠️ 当前 %s / %dx%d 未标定\n请先完成标定", label, width, height));
        } else {
            tvServerStatus.setVisibility(android.view.View.GONE);
        }
    }

    public void setRecordingState(boolean isRecording) {
        this.isRecordingState = isRecording;
        if (isRecording) {
            tvRecordTime.setVisibility(android.view.View.VISIBLE);
            tvRecordTime.setText("00:00:00");
        } else {
            tvRecordTime.setVisibility(android.view.View.GONE);
        }
    }

    public void setRecordEnabled(boolean enabled) {
        setRecordEnabled(enabled, true);
    }

    public void setRecordEnabled(boolean enabled, boolean isCalibrated) {
        if (btnRecord == null) return;
        btnRecord.setEnabled(enabled);
        if (!enabled) {
            btnRecord.setBackgroundResource(R.drawable.bg_shutter_locked);
            btnRecord.setAlpha(0.5f);
        } else {
            btnRecord.setAlpha(1.0f);
            if (isRecordingState) {
                btnRecord.setBackgroundResource(R.drawable.bg_shutter_record);
            } else {
                btnRecord.setBackgroundResource(R.drawable.bg_shutter_idle);
            }
        }
    }

    public void setRecordStopping() {
        if (btnRecord == null) return;
        btnRecord.setEnabled(false);              // 停止过程中禁用，防止重复点击
        btnRecord.setBackgroundResource(R.drawable.bg_shutter_record);
        btnRecord.setAlpha(0.6f);
    }

    public void updateRecordTime(String text) {
        tvRecordTime.setText(text);
    }

    public void setControlsEnabled(boolean enabled) {
        btnZoomIn.setEnabled(enabled);
        btnZoomOut.setEnabled(enabled);
        tvZoomInfo.setEnabled(enabled);
        btnCalibrate.setEnabled(enabled);
        btnExportPC.setEnabled(enabled);
        float alpha = enabled ? 1.0f : 0.4f;
        btnZoomIn.setAlpha(alpha);
        btnZoomOut.setAlpha(alpha);
        tvZoomInfo.setAlpha(alpha);
        btnCalibrate.setAlpha(alpha);
        btnExportPC.setAlpha(alpha);
    }

    /** 【新增】控制广角按钮是否可用（用于融合架构无超广角能力时禁用） */
    public void setWideButtonEnabled(boolean enabled) {
        if (btnZoomOut != null) {
            btnZoomOut.setEnabled(enabled);
            btnZoomOut.setAlpha(enabled ? 1.0f : 0.3f);
        }
    }

    public Button getBtnZoomIn() { return btnZoomIn; }
    public Button getBtnZoomOut() { return btnZoomOut; }
    public Button getBtnRecord() { return btnRecord; }
    public Button getBtnExportPC() { return btnExportPC; }
    public Button getBtnCalibrate() { return btnCalibrate; }
    public TextView getTvZoomInfo() { return tvZoomInfo; }
}