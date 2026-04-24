package com.example.cameraphonedata;

import android.app.Activity;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CalibrationData;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 主界面 UI 管理器 —— 所有 View 状态集中控制。
 *
 * 【防呆设计】
 * - 录制按钮始终可点击（enabled=true），但视觉状态区分：灰色锁定 / 绿色空闲 / 红色录制。
 * - 用户点击灰色按钮时，由 MainActivity 弹出提示，而非无响应。
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

    public void updateZoomDisplay(float zoom) {
        tvZoomInfo.setText(String.format(Locale.US, "%.1fx", zoom));
    }

    /** 显示/隐藏未标定红色横幅。 */
    public void showUncalibratedBanner(boolean show, float zoom, int width, int height) {
        if (tvServerStatus == null) return;
        if (show) {
            tvServerStatus.setVisibility(android.view.View.VISIBLE);
            tvServerStatus.setBackgroundColor(0xFFB71C1C);
            tvServerStatus.setTextColor(0xFFFFFFFF);
            tvServerStatus.setText(String.format(Locale.US,
                    "⚠️ 当前 %.1fx / %dx%d 未标定\n请先完成标定", zoom, width, height));
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

    /**
     * 录制按钮视觉状态（始终可点击，由 MainActivity 拦截逻辑）。
     * @param enabled  true = 可录制（绿色/红色）；false = 锁定（灰色半透明）
     */
    public void setRecordEnabled(boolean enabled) {
        setRecordEnabled(enabled, true);
    }

    /**
     * @param enabled      是否可录制
     * @param isCalibrated 当前是否已标定（影响空闲状态颜色）
     */
    public void setRecordEnabled(boolean enabled, boolean isCalibrated) {
        if (btnRecord == null) return;
        btnRecord.setEnabled(true); // 始终可点击，确保灰色状态也有提示
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
        btnRecord.setBackgroundResource(R.drawable.bg_shutter_record);
        btnRecord.setAlpha(0.6f);
    }

    public void updateRecordTime(String text) {
        tvRecordTime.setText(text);
    }

    /** 录制时禁用/启用其他控件（防呆：防止录制中切换焦距或误触导出）。 */
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

    public void showZoomInputDialog(Context context, float minZoom, float maxZoom, Consumer<Float> onZoomSet) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("设置变焦倍数");
        final EditText input = new EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(String.format(Locale.US, "范围: %.1fx - %.1fx", minZoom, maxZoom));
        input.setPadding(40, 30, 40, 30);
        builder.setView(input);
        builder.setPositiveButton("确定", (d, w) -> {
            try {
                onZoomSet.accept(Float.parseFloat(input.getText().toString()));
            } catch (NumberFormatException e) {
                android.widget.Toast.makeText(context, "输入无效", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    public void showCollectorNameInputDialog(Context context, Consumer<String> onNamed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("输入你的名字");
        builder.setMessage("标定已完成！请输入你的名字，用于区分不同采集者的数据：");
        final EditText input = new EditText(context);
        input.setHint("例如：张三、李四、王五…");
        input.setMaxLines(1);
        input.setPadding(40, 30, 40, 30);
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "unknown";
            onNamed.accept(name);
        });
        builder.setNegativeButton("跳过", (dialog, which) -> onNamed.accept("unknown"));
        builder.setCancelable(false);
        builder.show();
    }

    public Button getBtnZoomIn() { return btnZoomIn; }
    public Button getBtnZoomOut() { return btnZoomOut; }
    public Button getBtnRecord() { return btnRecord; }
    public Button getBtnExportPC() { return btnExportPC; }
    public Button getBtnCalibrate() { return btnCalibrate; }
    public TextView getTvZoomInfo() { return tvZoomInfo; }
}