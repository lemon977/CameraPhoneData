package com.example.cameraphonedata.data.repository;

import android.content.Context;

import com.example.cameraphonedata.config.UploadConfig;
import com.example.cameraphonedata.data.upload.OssUploadStrategy;
import com.example.cameraphonedata.data.upload.UploadStrategy;
import com.example.cameraphonedata.utils.LogUtil;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上传仓库 - 策略调度中心
 * 仅保留 OSS 上传策略，WiFi 传输已移除
 */
public class UploadRepository {
    private static final String TAG = "UploadRepository";

    private final Map<String, UploadStrategy> strategies = new LinkedHashMap<>();
    private UploadStrategy activeStrategy;

    public UploadRepository(Context context) {
        // WiFi 局域网传输已删除，仅保留 OSS
        register(new OssUploadStrategy(context));

        setActiveStrategy(UploadConfig.getInstance(context).activeStrategy.name().toLowerCase());
    }

    private void register(UploadStrategy strategy) {
        strategies.put(strategy.getName(), strategy);
        LogUtil.i(TAG, "注册上传策略: " + strategy.getName());
    }

    /** 切换策略： "oss" | "none" */
    public void setActiveStrategy(String name) {
        UploadStrategy strategy = strategies.get(name);
        if (strategy != null && strategy.isConfigured()) {
            this.activeStrategy = strategy;
            LogUtil.i(TAG, "激活策略: " + name);
        } else {
            LogUtil.w(TAG, "策略不可用: " + name);
            for (UploadStrategy s : strategies.values()) {
                if (s.isConfigured()) { this.activeStrategy = s; break; }
            }
        }
    }
    public UploadStrategy getStrategyByName(String name) {
        return strategies.get(name);
    }

    public void uploadFile(File file, UploadStrategy.UploadCallback callback) {
        if (activeStrategy == null) {
            callback.onFailure("none", "没有可用的上传策略，请在UploadConfig中配置");
            return;
        }
        activeStrategy.upload(file, callback);
    }

    public void uploadFolder(File folder, UploadStrategy.UploadCallback callback) {
        if (activeStrategy == null) {
            callback.onFailure("none", "没有可用的上传策略");
            return;
        }
        activeStrategy.uploadFolder(folder, callback);
    }

    public void release() {
        for (UploadStrategy s : strategies.values()) s.release();
    }
}