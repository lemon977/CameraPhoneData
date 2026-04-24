package com.example.cameraphonedata.alert;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 人手缺失报警管理器
 * 修复：增加 reset() 方法，停止录制时彻底清空所有倒计时
 */
public class HandAlertManager {
    private static final String TAG = "HandAlertManager";

    private final Handler handler;
    private final long noHandTimeoutMs;
    private final VoicePromptManager voicePrompt;
    private final int rawResId;
    private Runnable noHandRunnable;
    private boolean isCountingDown = false;
    private volatile boolean isReleased = false;

    public HandAlertManager(long noHandTimeoutMs, VoicePromptManager voicePrompt, int rawResId) {
        this.noHandTimeoutMs = noHandTimeoutMs;
        this.voicePrompt = voicePrompt;
        this.rawResId = rawResId;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void onHandDetected(boolean hasHand) {
        if (isReleased) return;

        if (hasHand) {
            if (isCountingDown) {
                handler.removeCallbacks(noHandRunnable);
                isCountingDown = false;
                Log.d(TAG, "检测到手，取消报警倒计时");
            }
        } else {
            if (!isCountingDown) {
                isCountingDown = true;
                noHandRunnable = () -> {
                    if (isReleased) return;
                    isCountingDown = false;
                    Log.i(TAG, "触发无手报警");
                    if (voicePrompt != null) {
                        voicePrompt.speak("画面长时间未显示人手", rawResId);
                    }
                };
                handler.postDelayed(noHandRunnable, noHandTimeoutMs);
                Log.d(TAG, "启动无手倒计时: " + noHandTimeoutMs + "ms");
            }
        }
    }

    /**
     * 【新增】彻底重置报警状态，停止录制时调用
     */
    public void reset() {
        isCountingDown = false;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "报警倒计时已重置");
    }

    public void release() {
        isReleased = true;
        isCountingDown = false;
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "HandAlertManager已释放");
    }
}