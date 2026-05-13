package com.example.cameraphonedata;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 弹窗队列管理器 —— 防止多个 Dialog 叠加覆盖。
 * 【优先级定义】
 * 3 = 致命错误 / 权限拦截（立即清空队列，强制显示）
 * 2 = 功能拦截（未标定、录制锁定）
 * 1 = 业务提示（标定成功、上传完成）
 * 0 = 弱提示（建议用 Toast 替代）
 */
public class DialogManager {
    private static DialogManager instance;
    private final Context appContext;
    private final List<DialogRequest> queue = new ArrayList<>();
    private AlertDialog currentDialog;

    public static class DialogRequest {
        public final int priority;
        public final String title;
        public final String message;
        public final String positiveText;
        public final Runnable onPositive;
        public final boolean cancelable;

        public DialogRequest(int priority, String title, String message) {
            this(priority, title, message, "确定", null, false);
        }

        public DialogRequest(int priority, String title, String message,
                             String positiveText, Runnable onPositive, boolean cancelable) {
            this.priority = priority;
            this.title = title;
            this.message = message;
            this.positiveText = positiveText;
            this.onPositive = onPositive;
            this.cancelable = cancelable;
        }
    }

    private DialogManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized DialogManager getInstance(Context context) {
        if (instance == null) instance = new DialogManager(context);
        return instance;
    }

    /** 排队显示（等当前弹窗关闭后自动弹出） */
    public void enqueue(DialogRequest req) {
        if (req == null) return;
        queue.add(req);
        Collections.sort(queue, (a, b) -> Integer.compare(b.priority, a.priority));
    }

    /** 立即显示（清空队列，强制插队） */
    public void showImmediate(DialogRequest req) {
        if (req == null) return;
        queue.clear();
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        queue.add(req);
    }

    /** 消费队列：在 Activity.onResume 中调用 */
    public void flush(AppCompatActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (currentDialog != null && currentDialog.isShowing()) return;
        if (queue.isEmpty()) return;

        DialogRequest req = queue.remove(0);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(req.title)
                .setMessage(req.message)
                .setCancelable(req.cancelable)
                .setPositiveButton(req.positiveText, (d, w) -> {
                    if (req.onPositive != null) req.onPositive.run();
                    currentDialog = null;
                    flush(activity);
                });

        currentDialog = builder.create();
        currentDialog.show();
    }

    /** 清空队列并关闭当前弹窗 */
    public void clear() {
        queue.clear();
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        currentDialog = null;
    }
}