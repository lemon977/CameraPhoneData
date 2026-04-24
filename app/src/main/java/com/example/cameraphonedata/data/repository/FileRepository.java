package com.example.cameraphonedata.data.repository;

import android.content.Context;
import android.os.Build;

import com.example.cameraphonedata.BuildConfig;  // 【新增】
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.utils.LogUtil;
import com.example.cameraphonedata.utils.StorageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;

/**
 * 文件仓库 - 所有文件IO统一入口
 * 职责：生成 metadata.json，合并 camera_params + segments + timestamps
 *
 * 【20人采集防内存爆炸】
 * metadata.json 超过 100 段时，旧段自动归档到 metadata_history.json
 */
public class FileRepository {
    private static final String TAG = "FileRepository";

    private final Context context;
    private final StorageManager storageManager;
    private final DataConfig dataConfig;

    public FileRepository(Context context) {
        this.context = context.getApplicationContext();
        this.storageManager = new StorageManager(context);
        this.dataConfig = DataConfig.getInstance();
    }

    /**
     * 创建会话文件夹：RobotData/yyyyMMdd_HHmm_姓名/{index}_{zoom}x/
     */
    public File createSessionFolder(String taskName) {
        try {
            File baseDir = storageManager.getBaseDir(dataConfig.baseFolderName);
            if (baseDir == null) {
                LogUtil.e(TAG, "存储目录不可用");
                return null;
            }

            // 日期精确到分钟，防止同一天多次上传覆盖 OSS
            String dateStr = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date());
            String collector = dataConfig.collectorName;
            if (collector == null || collector.trim().isEmpty()) collector = "unknown";
            String dateFolder = dateStr + "_" + collector;
            File dateDir = new File(baseDir, dateFolder);

            if (!dateDir.exists() && !dateDir.mkdirs()) {
                LogUtil.e(TAG, "创建日期目录失败: " + dateDir.getAbsolutePath());
                return null;
            }

            int index = dataConfig.getNextIndex(dateDir);
            float zoom = CameraConfig.getInstance().currentZoom;
            String sessionName = index + "_" + String.format(Locale.US, "%.1fx", zoom);
            File sessionDir = new File(dateDir, sessionName);

            if (!sessionDir.exists() && !sessionDir.mkdirs()) {
                LogUtil.e(TAG, "创建会话目录失败: " + sessionDir.getAbsolutePath());
                return null;
            }

            return sessionDir;
        } catch (Exception e) {
            LogUtil.e(TAG, "创建会话目录失败", e);
            return null;
        }
    }

    // ========== metadata.json 统一接口 ==========

    /**
     * 初始化 metadata.json（录制开始时调用）
     * 生成头部固定信息：版本、会话、设备、相机参数、录制配置
     */
    public void initMetadata(File sessionFolder, CameraParamReader.CameraParams params, long startTimeMs) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, "metadata.json");
        try {
            JSONObject meta = new JSONObject();

            // ========== 1. 数据结构版本号 = App 版本号（方便后端回溯）==========
            // 【修改】从 BuildConfig 读取真实版本，如 "1.1.0"
            meta.put("version", BuildConfig.VERSION_NAME);

            // ========== 2. session：本次采集会话信息 ==========
            JSONObject session = new JSONObject();
            // 会话序号，对应文件夹名 "1_1.0x" 中的 "1"
            session.put("index", Integer.parseInt(sessionFolder.getName().split("_")[0]));
            // 采集者姓名，登录后自动绑定，用于区分不同人
            session.put("collector", escapeJson(dataConfig.collectorName));
            // 任务名（预留字段，目前为空）
            session.put("task", escapeJson(dataConfig.currentTaskName));
            // 采集日期，格式 yyyyMMdd
            session.put("date", new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date(startTimeMs)));
            // 开始录制时间戳（毫秒）
            session.put("start_time_ms", startTimeMs);
            meta.put("session", session);

            // ========== 3. device：设备硬件信息（追溯用）==========
            JSONObject device = new JSONObject();
            // 设备唯一标识（Android ID），精确绑定设备
            device.put("phone_id", new com.example.cameraphonedata.config.CalibrationData(context).getPhoneId());
            // 手机型号，例：OPPO Find X6
            device.put("model", Build.MODEL);
            // 手机厂商，例：OPPO
            device.put("manufacturer", Build.MANUFACTURER);
            // Android 系统版本，例：14
            device.put("android_version", Build.VERSION.RELEASE);
            // API Level，例：34
            device.put("sdk_int", Build.VERSION.SDK_INT);
            // App 版本号（和 version 字段一致，冗余存储方便查看）
            device.put("app_version", BuildConfig.VERSION_NAME);
            meta.put("device", device);

            // ========== 4. camera：相机参数（后端去畸变核心数据）==========
            JSONObject camera = new JSONObject();
            CameraConfig cfg = CameraConfig.getInstance();
            if (params != null) {
                // 参数来源：SYSTEM_FACTORY / SENSOR_ESTIMATE / MANUAL_CALIBRATION
                camera.put("source", params.source.name());

                // 实际录制分辨率（去畸变时需要）
                camera.put("resolution", new JSONObject()
                        .put("width", params.videoWidth)
                        .put("height", params.videoHeight));

                // 内参矩阵：fx/fy 焦距，cx/cy 主点（已缩放到当前分辨率）
                camera.put("intrinsics", new JSONObject()
                        .put("fx", String.format(Locale.US, "%.4f", params.fx))
                        .put("fy", String.format(Locale.US, "%.4f", params.fy))
                        .put("cx", String.format(Locale.US, "%.4f", params.cx))
                        .put("cy", String.format(Locale.US, "%.4f", params.cy)));

                // 畸变系数：Brown-Conrady 5维模型（k1,k2,p1,p2,k3）
                camera.put("distortion", new JSONObject()
                        .put("k1", String.format(Locale.US, "%.6f", params.distortion[0]))
                        .put("k2", String.format(Locale.US, "%.6f", params.distortion[1]))
                        .put("p1", String.format(Locale.US, "%.6f", params.distortion[2]))
                        .put("p2", String.format(Locale.US, "%.6f", params.distortion[3]))
                        .put("k3", String.format(Locale.US, "%.6f", params.distortion[4])));

                // 标定重投影误差（像素），评估标定精度
                camera.put("rms_error", String.format(Locale.US, "%.4f", params.rmsError));
                // 标定完成时间
                camera.put("calibration_date", params.calibrationDate);
                // 物理焦距（mm），用于校验
                camera.put("focal_length_mm", String.format(Locale.US, "%.2f", params.focalLengthMm));
                // 传感器物理尺寸（mm）
                camera.put("sensor_size_mm", new JSONObject()
                        .put("width", String.format(Locale.US, "%.2f", params.sensorWidthMm))
                        .put("height", String.format(Locale.US, "%.2f", params.sensorHeightMm)));
            }
            // 变焦范围：min=最小广角倍数, max=最大长焦倍数, current=录制时实际倍数
            camera.put("zoom_range", new JSONObject()
                    .put("min", String.format(Locale.US, "%.1f", cfg.minZoom))
                    .put("max", String.format(Locale.US, "%.1f", cfg.maxZoom))
                    .put("current", String.format(Locale.US, "%.1f", cfg.currentZoom)));
            meta.put("camera", camera);

            // ========== 5. recording：录制策略配置 ==========
            JSONObject recording = new JSONObject();
            // 每段视频最大时长（毫秒），默认 60000 = 60秒
            recording.put("segment_duration_ms", cfg.segmentDurationMs);
            // 目标帧率（配置值，非实际帧率）
            recording.put("target_fps", cfg.previewFpsLimit);
            // 是否录制音频
            recording.put("has_audio", cfg.recordAudio);
            meta.put("recording", recording);

            // ========== 6. segments：视频片段清单（初始为空，逐段追加）==========
            meta.put("segments", new JSONArray());

            writeStringToFile(metaFile, meta.toString(2));
        } catch (Exception e) {
            LogUtil.e(TAG, "初始化 metadata 失败", e);
        }
    }

    /**
     * 追加一段视频信息到 metadata.json（每段完成时调用）
     * 【防内存爆炸】超过 100 段时旧段自动归档到 metadata_history.json
     */
    public void addSegment(File sessionFolder, int segmentNumber, File videoFile,
                           long startTimeMs, long durationMs) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, "metadata.json");
        if (!metaFile.exists()) return;

        try {
            String content = readStringFromFile(metaFile);
            JSONObject meta = new JSONObject(content);
            JSONArray segments = meta.getJSONArray("segments");

            // 超过 100 段时归档旧段，防止 JSON 无限膨胀
            if (segments.length() >= 100) {
                File historyFile = new File(sessionFolder, "metadata_history.json");
                writeStringToFile(historyFile, segments.toString(2));
                LogUtil.i(TAG, "metadata 旧段已归档: " + historyFile.getName());
                segments = new JSONArray();
                meta.put("segments", segments);
                meta.put("history_note", "前100段已归档到 metadata_history.json");
            }

            // 单个片段信息
            JSONObject seg = new JSONObject();
            // 片段序号（1-based）
            seg.put("index", segmentNumber);
            // 视频文件名
            seg.put("file", videoFile.getName());
            // 该段开始时间戳
            seg.put("start_time_ms", startTimeMs);
            // 该段结束时间戳
            seg.put("stop_time_ms", startTimeMs + durationMs);
            // 该段时长（毫秒）
            seg.put("duration_ms", durationMs);
            // 文件大小（字节）
            seg.put("size_bytes", videoFile.exists() ? videoFile.length() : 0);

            segments.put(seg);
            meta.put("segments", segments);

            writeStringToFile(metaFile, meta.toString(2));
        } catch (Exception e) {
            LogUtil.e(TAG, "追加 segment 到 metadata 失败", e);
        }
    }

    /**
     * 结束录制时更新 metadata.json
     * 补充 stop_time_ms 和 total_duration_ms
     */
    public void finalizeMetadata(File sessionFolder, long stopTimeMs, long totalDurationMs) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, "metadata.json");
        if (!metaFile.exists()) return;

        try {
            String content = readStringFromFile(metaFile);
            JSONObject meta = new JSONObject(content);

            JSONObject session = meta.getJSONObject("session");
            // 结束录制时间戳
            session.put("stop_time_ms", stopTimeMs);
            // 总录制时长（所有片段累加）
            session.put("total_duration_ms", totalDurationMs);
            meta.put("session", session);

            writeStringToFile(metaFile, meta.toString(2));
        } catch (Exception e) {
            LogUtil.e(TAG, "更新 metadata 失败", e);
        }
    }

    // ========== 工具方法 ==========

    private void writeStringToFile(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readStringFromFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             Scanner sc = new Scanner(fis)) {
            StringBuilder sb = new StringBuilder();
            while (sc.hasNextLine()) sb.append(sc.nextLine()).append("\n");
            return sb.toString();
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}