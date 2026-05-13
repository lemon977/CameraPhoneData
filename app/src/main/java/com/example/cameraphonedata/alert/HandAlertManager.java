package com.example.cameraphonedata.alert;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 人手缺失报警管理器 —— 双倒计时版。
 *
 * 15s 倒计时：语音报警（提醒采集员）。
 * 60s 倒计时：致命停止（当前段为错误数据，不保存）+ 语音播报原因。
 */
public class HandAlertManager {
    private static final String TAG = "HandAlertManager";

    private final Handler handler;
    private final long noHandTimeoutMs;
    private final long fatalNoHandTimeoutMs;
    private final VoicePromptManager voicePrompt;
    private final int noHandAlertRawResId;
    private final int fatalNoHandStopRawResId; // 【新增】致命停止专用音频
    private final OnFatalNoHandListener fatalListener;

    private Runnable noHandRunnable;
    private Runnable fatalRunnable;

    private final AtomicBoolean isCountingDown = new AtomicBoolean(false);
    private final AtomicBoolean isFatalCountingDown = new AtomicBoolean(false);
    private volatile boolean isReleased = false;

    public interface OnFatalNoHandListener {
        void onFatalNoHand();
    }

    public HandAlertManager(long noHandTimeoutMs, long fatalNoHandTimeoutMs,
                            VoicePromptManager voicePrompt,
                            int noHandAlertRawResId,
                            int fatalNoHandStopRawResId, // 【新增】
                            OnFatalNoHandListener fatalListener) {
        this.noHandTimeoutMs = noHandTimeoutMs;
        this.fatalNoHandTimeoutMs = fatalNoHandTimeoutMs;
        this.voicePrompt = voicePrompt;
        this.noHandAlertRawResId = noHandAlertRawResId;
        this.fatalNoHandStopRawResId = fatalNoHandStopRawResId;
        this.fatalListener = fatalListener;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void onHandDetected(boolean hasHand) {
        if (isReleased) {
            Log.d(TAG, "已释放，忽略");
            return;
        }

        if (hasHand) {
            if (isCountingDown.compareAndSet(true, false)) {
                handler.removeCallbacks(noHandRunnable);
                Log.i(TAG, "检测到手，取消报警倒计时");
            }
            if (isFatalCountingDown.compareAndSet(true, false)) {
                handler.removeCallbacks(fatalRunnable);
                Log.i(TAG, "检测到手，取消致命倒计时");
            }
        } else {
            if (isCountingDown.compareAndSet(false, true)) {
                noHandRunnable = () -> {
                    if (isReleased) return;
                    isCountingDown.set(false);
                    Log.i(TAG, "触发无手报警（15s）");
                    if (voicePrompt != null) {
                        voicePrompt.speak("画面长时间未显示人手", noHandAlertRawResId);
                    }
                };
                handler.postDelayed(noHandRunnable, noHandTimeoutMs);
                Log.d(TAG, "启动报警倒计时: " + noHandTimeoutMs + "ms");
            }

            if (isFatalCountingDown.compareAndSet(false, true)) {
                fatalRunnable = () -> {
                    if (isReleased) return;
                    isFatalCountingDown.set(false);
                    Log.i(TAG, "⏰ 触发致命无手停止（60s）");
                    // 【关键新增】立即停止前先语音告知用户原因
                    if (voicePrompt != null) {
                        voicePrompt.speak("长时间未检测到手，已停止录制并丢弃无效数据", fatalNoHandStopRawResId);
                    }
                    if (fatalListener != null) {
                        fatalListener.onFatalNoHand();
                    }
                };
                handler.postDelayed(fatalRunnable, fatalNoHandTimeoutMs);
                Log.i(TAG, "启动致命倒计时: " + fatalNoHandTimeoutMs + "ms");
            }
        }
    }

    public void reset() {
        Log.i(TAG, "reset() 被调用，清空所有倒计时");
        isCountingDown.set(false);
        isFatalCountingDown.set(false);
        handler.removeCallbacksAndMessages(null);
        noHandRunnable = null;
        fatalRunnable = null;
    }

    public void release() {
        Log.i(TAG, "release() 被调用");
        isReleased = true;
        isCountingDown.set(false);
        isFatalCountingDown.set(false);
        handler.removeCallbacksAndMessages(null);
        noHandRunnable = null;
        fatalRunnable = null;
    }
}