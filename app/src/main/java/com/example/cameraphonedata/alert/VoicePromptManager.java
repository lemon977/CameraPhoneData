package com.example.cameraphonedata.alert;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.example.cameraphonedata.utils.LogUtil;

/**
 * 语音播报管理器 - 纯音频版（无 TTS）
 * 【排查加固】
 * 1. 全链路加日志，方便抓 log 定位。
 * 2. 播放间隔放宽到 200ms（原 800ms 可能拦截报警）。
 * 3. 蜂鸣走 STREAM_MUSIC（更容易听到）。
 * 4. 明确提示 MediaPlayer.create 失败原因。
 */
public class VoicePromptManager {
    private static final String TAG = "VoicePrompt";
    private static final long MIN_PLAY_INTERVAL_MS = 1500; // 避免报警被拦截

    private final Context context;
    private final Handler mainHandler;
    private volatile boolean isReleased = false;
    private long lastPlayTime = 0;
    private MediaPlayer currentPlayer;

    public VoicePromptManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 播报提示音
     *
     * @param text     日志用文本
     * @param rawResId 内置音频资源ID，0 表示无音频（蜂鸣兜底）
     */
    public void speak(String text, int rawResId) {
        if (isReleased) {
            Log.w(TAG, "已释放，忽略播报: " + text);
            return;
        }
        // 【优化】提前做间隔过滤，避免无效请求堆积在 Handler 队列中
        long now = System.currentTimeMillis();
        if (now - lastPlayTime < MIN_PLAY_INTERVAL_MS) {
            Log.d(TAG, "播放间隔太短，提前忽略: " + text + " (间隔=" + (now - lastPlayTime) + "ms)");
            return;
        }
        Log.i(TAG, "收到播报请求: " + text + ", rawResId=" + rawResId);
        mainHandler.post(() -> speakInternal(text, rawResId));
    }

    private void speakInternal(String text, int rawResId) {
        long now = System.currentTimeMillis();
        if (now - lastPlayTime < MIN_PLAY_INTERVAL_MS) {
            Log.d(TAG, "播放间隔太短，忽略: " + text + " (间隔=" + (now - lastPlayTime) + "ms)");
            return;
        }
        lastPlayTime = now;
        Log.i(TAG, "执行播报: " + text);

        releaseCurrentPlayer();

        if (rawResId != 0) {
            boolean played = playRawAudio(rawResId, text);
            if (played) {
                Log.i(TAG, "音频播放成功: " + text);
                return;
            }
            Log.w(TAG, "MediaPlayer 播放失败，降级到蜂鸣: " + text);
        } else {
            Log.w(TAG, "rawResId=0，直接走蜂鸣兜底: " + text);
        }

        fallbackBeepAndVibrate();
    }

    private boolean playRawAudio(int rawResId, String text) {
        try {
            MediaPlayer mp = MediaPlayer.create(context, rawResId);
            if (mp == null) {
                Log.e(TAG, "MediaPlayer.create 返回 null，资源ID无效或音频文件缺失: " + rawResId);
                return false;
            }
            currentPlayer = mp;
            Log.d(TAG, "MediaPlayer 创建成功，准备播放: " + text);

            mp.setOnCompletionListener(mp1 -> {
                Log.d(TAG, "音频播放完成: " + text);
                releasePlayer(mp1);
            });

            mp.setOnErrorListener((mp1, what, extra) -> {
                Log.e(TAG, "MediaPlayer 错误: what=" + what + " extra=" + extra + ", text=" + text);
                releasePlayer(mp1);
                return true;
            });

            mp.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "播放音频异常: " + text, e);
            return false;
        }
    }

    private void releaseCurrentPlayer() {
        if (currentPlayer != null) {
            try {
                currentPlayer.release();
                Log.d(TAG, "释放旧 MediaPlayer");
            } catch (Exception ignored) {}
            currentPlayer = null;
        }
    }

    private void releasePlayer(MediaPlayer mp) {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {}
        }
        if (currentPlayer == mp) {
            currentPlayer = null;
        }
    }

    private void fallbackBeepAndVibrate() {
        // 蜂鸣（改用 STREAM_MUSIC，更容易穿透静音模式）
        ToneGenerator toneGen = null;
        try {
            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500);
            Log.i(TAG, "蜂鸣兜底已触发 (STREAM_MUSIC)");
        } catch (Exception e) {
            Log.e(TAG, "蜂鸣失败", e);
        } finally {
            if (toneGen != null) {
                toneGen.release();
            }
        }

        // 振动
        try {
            Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(1200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vib.vibrate(1200);
                }
                Log.i(TAG, "振动兜底已触发");
            }
        } catch (Exception e) {
            Log.e(TAG, "振动失败", e);
        }
    }

    public void release() {
        if (isReleased) return;
        isReleased = true;
        mainHandler.removeCallbacksAndMessages(null);
        releaseCurrentPlayer();
        Log.i(TAG, "VoicePromptManager 已释放");
    }
}