package com.example.cameraphonedata.data.repository;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import com.example.cameraphonedata.BuildConfig;
import com.example.cameraphonedata.camera.CameraParamReader;
import com.example.cameraphonedata.config.CameraConfig;
import com.example.cameraphonedata.config.DataConfig;
import com.example.cameraphonedata.config.FileNames;
import com.example.cameraphonedata.config.ImuConfig;
import com.example.cameraphonedata.recorder.ImuRecorder;
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
 * 【2026-04-29 修复】metadata 改回有效时长（毫秒+截断秒），供后端算工资。
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

    public File createSessionFolder(String taskName) {
        try {
            File baseDir = storageManager.getBaseDir(dataConfig.baseFolderName);
            if (baseDir == null) {
                LogUtil.e(TAG, "存储目录不可用");
                return null;
            }

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
            String roleTag = CameraConfig.getInstance().currentLensRole.name().toLowerCase();
            String sessionName = index + "_" + roleTag;
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

    public void initMetadata(File sessionFolder, CameraParamReader.CameraParams params, long startTimeMs) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, FileNames.METADATA);
        try {
            JSONObject meta = new JSONObject();
            meta.put("schema_version", "2.2");
            meta.put("app_version", BuildConfig.VERSION_NAME);

            meta.put("_readme",
                    "镜头角色: WIDE=主摄, ULTRA_WIDE=广角. " +
                            "不同厂商zoom实现差异极大，请以 lens_role 字段为准. " +
                            "intrinsics/distortion 保留完整浮点供算法去畸变使用. " +
                            "display 字段为截断字符串，仅供人工核对. " +
                            "畸变模型: Brown-Conrady-5 (k1,k2,p1,p2,k3). " +
                            "effective_duration_sec 为截断后整数秒，后端算工资直接除3600得小时. " +
                            "=== 视频-IMU 时间戳对齐 === " +
                            "segments[i].start_time_ms / stop_time_ms 是视频的实际 Wall Clock 时间. " +
                            "IMU 数据请参见 imu_config.json，其对齐基准应以视频段时间为准，" +
                            "不要用 session.start_time_ms 或按序号简单分组。详见 imu_config.json 的 _readme。");

            JSONObject session = new JSONObject();
            try {
                session.put("index", Integer.parseInt(sessionFolder.getName().split("_")[0]));
            } catch (Exception e) {
                LogUtil.w(TAG, "会话索引解析失败，回退为0: " + sessionFolder.getName(), e);
                session.put("index", 0);
            }
            session.put("collector", escapeJson(dataConfig.collectorName));
            session.put("task", escapeJson(dataConfig.currentTaskName));
            session.put("date", new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date(startTimeMs)));
            session.put("start_time_ms", startTimeMs);
            meta.put("session", session);

            JSONObject device = new JSONObject();
            device.put("phone_id", new com.example.cameraphonedata.config.CalibrationData(context).getPhoneId());
            device.put("model", Build.MODEL);
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("android_version", Build.VERSION.RELEASE);
            device.put("sdk_int", Build.VERSION.SDK_INT);
            meta.put("device", device);

            JSONObject camera = new JSONObject();
            CameraConfig cfg = CameraConfig.getInstance();
            if (params != null) {
                camera.put("source", params.source.name());
                camera.put("lens_role", cfg.currentLensRole.name());

                JSONObject resolution = new JSONObject();
                resolution.put("width", params.videoWidth);
                resolution.put("height", params.videoHeight);
                camera.put("resolution", resolution);

                JSONObject intrinsics = new JSONObject();
                intrinsics.put("fx", params.fx);
                intrinsics.put("fy", params.fy);
                intrinsics.put("cx", params.cx);
                intrinsics.put("cy", params.cy);
                camera.put("intrinsics", intrinsics);

                JSONObject dist = new JSONObject();
                dist.put("model", "Brown-Conrady-5");
                dist.put("k1", params.distortion[0]);
                dist.put("k2", params.distortion[1]);
                dist.put("p1", params.distortion[2]);
                dist.put("p2", params.distortion[3]);
                dist.put("k3", params.distortion[4]);
                camera.put("distortion", dist);

                JSONObject quality = new JSONObject();
                quality.put("rms_error_px", params.rmsError);
                quality.put("calibration_date", params.calibrationDate != null ? params.calibrationDate : "");
                quality.put("rating", getRatingText(params.rmsError));
                camera.put("quality", quality);

                // 只有传感器估算路径才有有效硬件参数，其他来源（人工标定）不写，避免 0 值误导
                if (params.source == CameraParamReader.ParamSource.SENSOR_ESTIMATE) {
                    JSONObject hardware = new JSONObject();
                    hardware.put("focal_length_mm", params.focalLengthMm);
                    hardware.put("sensor_width_mm", params.sensorWidthMm);
                    hardware.put("sensor_height_mm", params.sensorHeightMm);
                    camera.put("hardware", hardware);
                }

                JSONObject display = new JSONObject();
                display.put("fx", String.format(Locale.US, "%.2f", params.fx));
                display.put("fy", String.format(Locale.US, "%.2f", params.fy));
                display.put("cx", String.format(Locale.US, "%.2f", params.cx));
                display.put("cy", String.format(Locale.US, "%.2f", params.cy));
                display.put("k1", String.format(Locale.US, "%.6f", params.distortion[0]));
                display.put("k2", String.format(Locale.US, "%.6f", params.distortion[1]));
                display.put("k3", String.format(Locale.US, "%.6f", params.distortion[4]));
                display.put("p1", String.format(Locale.US, "%.6f", params.distortion[2]));
                display.put("p2", String.format(Locale.US, "%.6f", params.distortion[3]));
                display.put("rms_error_px", String.format(Locale.US, "%.3f", params.rmsError));
                camera.put("display", display);
            }

            meta.put("camera", camera);

            JSONObject recording = new JSONObject();
            recording.put("segment_duration_ms", cfg.segmentDurationMs);
            recording.put("target_fps", cfg.targetFrameRate > 0 ? cfg.targetFrameRate : cfg.previewFpsLimit);
            recording.put("fps_lock_mode", cfg.targetFrameRate > 0 ? "locked" : "auto");
            recording.put("has_audio", cfg.recordAudio);
            meta.put("recording", recording);

            meta.put("segments", new JSONArray());

            writeStringToFile(metaFile, meta.toString(2));
        } catch (Exception e) {
            LogUtil.e(TAG, "初始化 metadata 失败", e);
        }
    }

    public void addSegment(File sessionFolder, int segmentNumber, File videoFile,
                           long startTimeMs, long durationMs) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, FileNames.METADATA);
        if (!metaFile.exists()) return;

        try {
            String content = readStringFromFile(metaFile);
            JSONObject meta = new JSONObject(content);
            JSONArray segments = meta.getJSONArray("segments");

            if (segments.length() >= 100) {
                File historyFile = new File(sessionFolder, FileNames.METADATA_HISTORY);
                writeStringToFile(historyFile, segments.toString(2));
                LogUtil.i(TAG, "metadata 旧段已归档: " + historyFile.getName());
                segments = new JSONArray();
                meta.put("segments", segments);
                meta.put("history_note", "前100段已归档到 metadata_history.json");
            }

            JSONObject seg = new JSONObject();
            seg.put("index", segmentNumber);
            seg.put("file", videoFile.getName());
            seg.put("start_time_ms", startTimeMs);
            seg.put("stop_time_ms", startTimeMs + durationMs);
            seg.put("duration_ms", durationMs);
            seg.put("size_bytes", videoFile.exists() ? videoFile.length() : 0);

            segments.put(seg);
            meta.put("segments", segments);

            writeStringToFile(metaFile, meta.toString(2));
        } catch (Exception e) {
            LogUtil.e(TAG, "追加 segment 到 metadata 失败", e);
        }
    }

    /**
     * 【修改】结束录制时更新 metadata.json
     * 保存 effective_duration_ms（原始毫秒）和 effective_duration_sec（截断后整数秒）
     * 截断规则：不足1秒直接舍去（整数除法 /1000）
     */
    public void finalizeMetadata(File sessionFolder, long stopTimeMs, long effectiveDurationMs) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, FileNames.METADATA);
        if (!metaFile.exists()) {
            LogUtil.w(TAG, "finalizeMetadata: metadata.json 不存在，跳过: " + sessionFolder.getName());
            return;
        }

        try {
            String content = readStringFromFile(metaFile);
            JSONObject meta = new JSONObject(content);

            JSONObject session = meta.getJSONObject("session");
            session.put("stop_time_ms", stopTimeMs);
            // 【删除旧字段】
            session.remove("total_duration_ms");
            session.remove("effective_duration_ms_old");
            session.remove("effective_duration_min");
            session.remove("effective_segment_count");
            // 【新增】有效时长：原始毫秒 + 截断秒（后端算工资用秒）
            long effectiveSec = effectiveDurationMs / 1000; // 不足1秒舍去
            session.put("effective_duration_ms", effectiveDurationMs);
            session.put("effective_duration_sec", effectiveSec);
            meta.put("session", session);

            // 【关键修复】从视频文件读取实际分辨率，覆盖目标分辨率，确保 metadata 真实性
            try {
                JSONArray segments = meta.optJSONArray("segments");
                if (segments != null && segments.length() > 0) {
                    String firstVideoName = segments.getJSONObject(0).optString("file", "");
                    if (!firstVideoName.isEmpty()) {
                        File videoFile = new File(sessionFolder, firstVideoName);
                        if (videoFile.exists()) {
                            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                            try {
                                retriever.setDataSource(videoFile.getAbsolutePath());
                                String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                                String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                                String rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                                if (wStr != null && hStr != null) {
                                    int actualW = Integer.parseInt(wStr);
                                    int actualH = Integer.parseInt(hStr);
                                    int rotation = rotationStr != null ? Integer.parseInt(rotationStr) : 0;
                                    // 视频旋转 90°/270° 时，宽高需要交换以匹配播放方向
                                    if (rotation == 90 || rotation == 270) {
                                        int tmp = actualW; actualW = actualH; actualH = tmp;
                                    }
                                    JSONObject camera = meta.optJSONObject("camera");
                                    if (camera != null) {
                                        JSONObject actualResolution = new JSONObject();
                                        actualResolution.put("width", actualW);
                                        actualResolution.put("height", actualH);
                                        camera.put("resolution", actualResolution);
                                        meta.put("camera", camera);
                                        LogUtil.i(TAG, "metadata 已更新为视频实际分辨率: "
                                                + actualW + "x" + actualH + " (rotation=" + rotation + ")");
                                    }
                                }
                            } finally {
                                retriever.release();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LogUtil.w(TAG, "从视频读取实际分辨率失败，保留目标分辨率: " + e.getMessage());
            }

            // 先尝试原子写
            boolean written = false;
            File tmp = new File(metaFile.getParent(), metaFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(meta.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            if (tmp.renameTo(metaFile)) {
                written = true;
                LogUtil.i(TAG, "finalizeMetadata 原子写成功: " + metaFile.getAbsolutePath()
                        + ", effective_duration_ms=" + effectiveDurationMs
                        + ", effective_duration_sec=" + effectiveSec);
            } else {
                LogUtil.w(TAG, "finalizeMetadata 原子重命名失败，回退普通写");
            }

            // 原子写失败时，强制普通覆盖写
            if (!written) {
                if (tmp.exists()) tmp.delete();
                writeStringToFile(metaFile, meta.toString(2));
                LogUtil.i(TAG, "finalizeMetadata 普通写成功（回退）: " + metaFile.getAbsolutePath()
                        + ", effective_duration_sec=" + effectiveSec);
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "finalizeMetadata 失败: " + sessionFolder.getName(), e);
        }
    }

    /**
     * 【新增】写入 imu_config.json —— IMU 配置、时间戳映射、采集统计。
     * 与 metadata.json 分离，避免高频配置信息挤占低频元数据文件。
     */
    public void writeImuConfig(File sessionFolder, ImuRecorder.Stats stats) {
        if (sessionFolder == null || stats == null) return;
        File imuConfigFile = new File(sessionFolder, FileNames.IMU_CONFIG);

        // 尝试从 metadata.json 读取第一段视频的实际起止时间，用于对齐
        long videoStartMs = -1;
        long videoStopMs = -1;
        try {
            File metaFile = new File(sessionFolder, FileNames.METADATA);
            if (metaFile.exists()) {
                JSONObject meta = new JSONObject(readStringFromFile(metaFile));
                JSONArray segments = meta.optJSONArray("segments");
                if (segments != null && segments.length() > 0) {
                    JSONObject firstSeg = segments.getJSONObject(0);
                    videoStartMs = firstSeg.optLong("start_time_ms", -1);
                    videoStopMs = firstSeg.optLong("stop_time_ms", -1);
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "writeImuConfig: 读取 metadata 视频时间失败，跳过", e);
        }

        try {
            ImuConfig cfg = ImuConfig.getInstance();
            long unitPerMs = cfg.getTimestampUnitPerMs();

            JSONObject root = new JSONObject();

            // ===== 注释：时间戳转换公式 + 视频-IMU 对齐指南 =====
            JSONArray readme = new JSONArray();
            readme.put("=== 时间戳转换公式 ===");
            readme.put("将 imu_data.jsonl 中的 t_ns 转为 wall clock 毫秒：");
            readme.put("  wall_time_ms = wall_anchor_ms + (t_ns - elapsed_anchor_ns) / " + unitPerMs);
            readme.put("说明：");
            readme.put("  - t_ns 是基于 elapsedRealtimeNanos 的" + cfg.timestampUnit + "时间戳。");
            readme.put("  - elapsed_anchor_ns 是录制开始时 SystemClock.elapsedRealtimeNanos() 的值。");
            readme.put("  - wall_anchor_ms 是录制开始时 System.currentTimeMillis() 的值。");
            readme.put("  - 除以 " + unitPerMs + " 是因为 1ms = " + unitPerMs + cfg.timestampUnit + "。");
            readme.put("  - 如果 session 期间发生了用户手动调时或 NTP 大步进同步，wall 时间可能跳变。");
            readme.put("=== 验证时间基准稳定性 ===");
            readme.put("  start_offset_ms = wall_anchor_ms - elapsed_anchor_ns / " + unitPerMs);
            readme.put("  stop_offset_ms  = stop_wall_ms  - stop_elapsed_ns  / " + unitPerMs);
            readme.put("  若 |stop_offset_ms - start_offset_ms| < 100，说明时间基准稳定。");
            readme.put("=== 视频-IMU 时间戳对齐 ===");
            readme.put("【重要】CameraX 视频编码器初始化存在约 200-300ms 延迟，因此 IMU 通常比视频");
            readme.put("  早开始采集。IMU 数据包含视频开始前的前导样本，也包含视频结束后的尾部样本。");
            readme.put("【错误做法】不要用 序号/索引 做 IMU 与视频的 2:1 分组对齐！");
            readme.put("  原因：启动延迟会导致前若干帧视频没有对应 IMU，或尾部 IMU 没有对应视频。");
            readme.put("【正确做法】用时间戳最近邻匹配，并截断孤儿数据：");
            readme.put("  1. 读取本文件 timestamp_mapping.video_start_ms（第一段视频实际开始时间）。");
            readme.put("  2. 用公式 wall_time_ms = wall_anchor_ms + (t_ns - elapsed_anchor_ns) / unit_per_ms");
            readme.put("     将 IMU t_ns 换算为 Wall Clock 毫秒。");
            readme.put("  3. 只保留 wall_time_ms >= video_start_ms 的 IMU 样本（截断前导孤儿）。");
            readme.put("  4. 对每帧视频，找 wall_time_ms 最接近该帧时刻的 IMU 样本（最近邻）。");
            readme.put("  5. 只保留 wall_time_ms <= video_stop_ms 的 IMU 样本（截断尾部孤儿）。");
            readme.put("【示例 Python 代码】");
            readme.put("  import pandas as pd");
            readme.put("  imu['t_ms'] = wall_anchor_ms + (imu['t_ns'] - elapsed_anchor_ns) / unit_per_ms");
            readme.put("  imu = imu[(imu['t_ms'] >= video_start_ms) & (imu['t_ms'] <= video_stop_ms)]");
            readme.put("  aligned = pd.merge_asof(");
            readme.put("      video.assign(t_ms=video['frame_idx'] * 1000/30 + video_start_ms),");
            readme.put("      imu, on='t_ms', direction='nearest', tolerance=50)");
            root.put("_readme", readme);

            // ===== 时间戳映射 =====
            JSONObject tm = new JSONObject();
            tm.put("wall_anchor_ms", stats.wallAnchorMs);
            tm.put("elapsed_anchor_ns", stats.elapsedAnchorNs);
            tm.put("stop_wall_ms", stats.stopWallMs);
            tm.put("stop_elapsed_ns", stats.stopElapsedNs);
            tm.put("timestamp_unit", cfg.timestampUnit);
            tm.put("unit_per_ms", unitPerMs);

            // 视频段时间（用于后端对齐）
            if (videoStartMs > 0) {
                tm.put("video_start_ms", videoStartMs);
                tm.put("video_stop_ms", videoStopMs > 0 ? videoStopMs : stats.stopWallMs);
                long latencyMs = videoStartMs - stats.wallAnchorMs;
                tm.put("estimated_latency_ms", latencyMs);
                tm.put("latency_note", "video_start_ms - wall_anchor_ms，即 CameraX 编码器启动延迟");
            } else {
                tm.put("video_start_ms", stats.wallAnchorMs); // 兜底
                tm.put("video_stop_ms", stats.stopWallMs);
                tm.put("estimated_latency_ms", 0);
                tm.put("latency_note", "metadata 未找到视频段信息， latency 未知，建议按时间戳最近邻匹配");
            }

            // 自动验证时间漂移
            long startOffset = stats.wallAnchorMs - stats.elapsedAnchorNs / 1_000_000L;
            long stopOffset  = stats.stopWallMs  - stats.stopElapsedNs  / 1_000_000L;
            long driftMs = Math.abs(stopOffset - startOffset);
            tm.put("drift_check_ms", driftMs);
            tm.put("drift_ok", driftMs < 100);
            root.put("timestamp_mapping", tm);

            // ===== 传感器配置与可用性 =====
            JSONObject sensors = new JSONObject();
            JSONArray requested = new JSONArray();
            if (cfg.useGyroscope) requested.put("gyroscope");
            if (cfg.useLinearAcceleration) requested.put("linear_acceleration");
            if (cfg.useRotationVector) requested.put("rotation_vector");
            if (cfg.useRawAccelerometer) requested.put("raw_accelerometer");
            requested.put("gravity");
            sensors.put("requested", requested);

            JSONObject available = new JSONObject();
            available.put("gyroscope", stats.gyroAvailable);
            available.put("linear_acceleration", stats.accelAvailable && stats.accelIsLinear);
            available.put("rotation_vector", stats.rotAvailable && !stats.rotIsGame);
            available.put("raw_accelerometer", stats.rawAccelAvailable);
            available.put("gravity", stats.gravityAvailable);
            available.put("fallback_accelerometer", stats.accelAvailable && !stats.accelIsLinear);
            available.put("fallback_game_rotation_vector", stats.rotAvailable && stats.rotIsGame);
            sensors.put("available", available);

            JSONObject fallbackNote = new JSONObject();
            fallbackNote.put("used_accel_fallback", stats.accelAvailable && !stats.accelIsLinear);
            fallbackNote.put("used_rot_fallback", stats.rotAvailable && stats.rotIsGame);
            if (stats.accelAvailable && !stats.accelIsLinear) {
                fallbackNote.put("accel_note", "TYPE_LINEAR_ACCELERATION 不可用，使用 TYPE_ACCELEROMETER 替代，a 字段包含重力");
            }
            if (stats.rotAvailable && stats.rotIsGame) {
                fallbackNote.put("rot_note", "TYPE_ROTATION_VECTOR 不可用，使用 TYPE_GAME_ROTATION_VECTOR 替代，无地磁校正");
            }
            sensors.put("fallback", fallbackNote);
            root.put("sensors", sensors);

            // ===== 采样率统计 =====
            JSONObject rate = new JSONObject();
            rate.put("hardware_mode", cfg.getSampleRateModeName());
            rate.put("target_output_hz", cfg.targetOutputHz);
            rate.put("actual_output_hz", String.format(java.util.Locale.US, "%.2f", stats.actualOutputHz));
            rate.put("output_count", stats.outputCount);
            rate.put("gyro_raw_count", stats.gyroCount);
            rate.put("accel_raw_count", stats.accelCount);
            rate.put("raw_accel_raw_count", stats.rawAccelCount);
            rate.put("rot_raw_count", stats.rotCount);
            long durationSec = Math.max(1, (stats.stopWallMs - stats.wallAnchorMs) / 1000);
            rate.put("duration_sec", durationSec);
            root.put("sample_rate", rate);

            // ===== 数据格式说明 =====
            JSONObject fmt = new JSONObject();
            fmt.put("file", FileNames.IMU_DATA);
            fmt.put("encoding", "utf-8");
            JSONObject fields = new JSONObject();
            fields.put("t_ns", "时间戳(" + cfg.timestampUnit + ")");
            fields.put("g", "陀螺仪[rad/s] 3轴(x,y,z)");
            fields.put("a", "线性加速度[m/s²] 3轴(x,y,z)，已去重力");
            fields.put("ra", "原始加速度[m/s²] 3轴(x,y,z)，含重力（仅当 useRawAccelerometer=true 时存在）");
            fields.put("r", "旋转四元数[x,y,z,scalar]，scalar=cos(θ/2)");
            fields.put("gv", "重力矢量[m/s²] 3轴(x,y,z)，设备坐标系");
            fmt.put("line_fields", fields);
            fmt.put("master_sensor", stats.masterSensor);
            root.put("data_format", fmt);

            // ===== 质量统计 =====
            JSONObject quality = new JSONObject();
            quality.put("dropped_samples", stats.droppedCount);
            quality.put("write_errors", stats.writeErrors);
            quality.put("nan_or_inf_count", stats.nanCount);
            // 【修复】健康检测用输出样本层跳变（原始事件层跳变在多传感器下会虚高）
            quality.put("timestamp_jumps", stats.outputTimestampJumps);
            quality.put("raw_timestamp_jumps", stats.timestampJumps); // 原始事件层，内部诊断
            quality.put("written_bytes", stats.writtenBytes);
            quality.put("file_size_bytes", stats.dataFileSize);
            root.put("quality", quality);

            // ===== IMU 质量评估（后端看 imu_config.json 一个文件就够了）=====
            JSONObject imuQuality = new JSONObject();
            String trustLevel;
            if (stats.writeErrors > 0) {
                trustLevel = "unreliable";
            } else if (stats.nanCount > 100) {
                trustLevel = "unreliable";
            } else if (stats.droppedCount > 1000) {
                trustLevel = "low";
            } else if (stats.outputTimestampJumps > 1000) {
                trustLevel = "low";
            } else if (stats.outputTimestampJumps > 100) {
                trustLevel = "medium";
            } else {
                trustLevel = "high";
            }
            imuQuality.put("trust_level", trustLevel);
            imuQuality.put("timestamp_jumps", stats.outputTimestampJumps);
            imuQuality.put("raw_timestamp_jumps", stats.timestampJumps);
            imuQuality.put("dropped_samples", stats.droppedCount);
            imuQuality.put("nan_count", stats.nanCount);
            imuQuality.put("write_errors", stats.writeErrors);
            imuQuality.put("check_time_ms", System.currentTimeMillis());
            root.put("imu_quality", imuQuality);

            writeStringToFile(imuConfigFile, root.toString(2));
            LogUtil.i(TAG, "imu_config.json 写入成功: " + imuConfigFile.getAbsolutePath()
                    + ", output=" + stats.outputCount
                    + ", hz=" + String.format(java.util.Locale.US, "%.2f", stats.actualOutputHz));
        } catch (Exception e) {
            LogUtil.e(TAG, "写入 imu_config.json 失败", e);
        }
    }

    /**
     * 【新增】录制过程中强制 sync metadata 到磁盘。
     * 每完成一个视频段调用一次，防止录制中途关机/崩溃导致 metadata 丢失。
     * 使用原子写（.tmp + rename + fd.sync），确保数据落盘。
     */
    public void syncMetadata(File sessionFolder) {
        if (sessionFolder == null) return;
        File metaFile = new File(sessionFolder, FileNames.METADATA);
        if (!metaFile.exists()) return;
        try {
            String content = readStringFromFile(metaFile);
            JSONObject meta = new JSONObject(content);
            // 直接重写一次，触发 fd.sync()
            File tmp = new File(metaFile.getParent(), metaFile.getName() + ".sync.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(meta.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            if (tmp.renameTo(metaFile)) {
                LogUtil.d(TAG, "metadata sync 成功: " + metaFile.getName());
            } else {
                writeStringToFile(metaFile, meta.toString(2));
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "metadata sync 失败", e);
        }
    }

    /**
     * 【新增】从 metadata.json 读取有效时长（毫秒），支持兜底。
     * 优先读 effective_duration_ms，如果不存在则读 effective_duration_sec*1000。
     */
    public long extractEffectiveDurationMs(File metaFile) {
        if (metaFile == null || !metaFile.exists()) return 0;
        try {
            String content = readStringFromFile(metaFile);
            JSONObject meta = new JSONObject(content);
            if (!meta.has("session")) return 0;

            JSONObject session = meta.getJSONObject("session");
            if (session.has("effective_duration_ms")) {
                long ms = session.getLong("effective_duration_ms");
                LogUtil.d(TAG, "extractEffectiveDurationMs: 读到 effective_duration_ms=" + ms
                        + " from " + metaFile.getParentFile().getName());
                return ms;
            }
            // 【兜底】读秒转毫秒
            if (session.has("effective_duration_sec")) {
                long sec = session.getLong("effective_duration_sec");
                LogUtil.w(TAG, "extractEffectiveDurationMs: 兜底 effective_duration_sec=" + sec
                        + " from " + metaFile.getParentFile().getName());
                return sec * 1000;
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "extractEffectiveDurationMs 失败: " + metaFile.getAbsolutePath(), e);
        }
        return 0;
    }

    private String getRatingText(double rms) {
        if (rms <= 0 || rms >= 999) return "unknown";
        if (rms < 0.3) return "excellent";
        if (rms < 0.5) return "good";
        if (rms < 1.0) return "fair";
        return "poor";
    }

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
        return str == null ? "" : str;
    }
}