package com.example.cameraphonedata;

import android.app.Application;

import com.example.cameraphonedata.utils.CrashHandler;
import com.example.cameraphonedata.utils.LogUtil;

import org.opencv.android.OpenCVLoader;

/**
 * 全局 Application
 * <p>
 * 【职责边界】只放"所有页面都依赖、且只需初始化一次"的全局资源：
 * 1. OpenCV native 库加载（标定 + 图像处理依赖）
 * <p>
 * 【不要放】TTS、MLKit、相机、网络等按需初始化的模块，
 * 那些应该在各自 Manager 里懒加载，避免拖慢 App 冷启动。
 */
public class App extends Application {
    private static final String TAG = "App";
    private static App instance;
    private boolean openCvReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        LogUtil.i(TAG, "Application onCreate");

        // 【高优先级】安装全局崩溃捕获器，录制过程中崩溃时自动紧急停止并保存数据
        CrashHandler.install(this);

        // 全局初始化 OpenCV（失败不崩溃，标定页面会再尝试）
        initOpenCV();
    }

    private void initOpenCV() {
        try {
            openCvReady = OpenCVLoader.initDebug();
            if (openCvReady) {
                LogUtil.i(TAG, "OpenCV 初始化成功");
            } else {
                LogUtil.e(TAG, "OpenCV 初始化返回 false，可能 so 库未正确加载");
            }
        } catch (UnsatisfiedLinkError e) {
            LogUtil.e(TAG, "OpenCV so 库加载失败，请检查 opencv 模块配置", e);
        } catch (Exception e) {
            LogUtil.e(TAG, "OpenCV 初始化异常: " + e.getMessage(), e);
        }
    }

    /** 供其他模块查询 OpenCV 是否就绪 */
    public boolean isOpenCvReady() {
        return openCvReady;
    }

    public static App getInstance() {
        return instance;
    }
}