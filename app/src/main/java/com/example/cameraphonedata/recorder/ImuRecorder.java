package com.example.cameraphonedata.recorder;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.example.cameraphonedata.config.ImuConfig;
import com.example.cameraphonedata.config.ImuHealthConfig;
import com.example.cameraphonedata.utils.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IMU 录制器 —— 陀螺仪、加速度、旋转矢量统一采集与持久化。
 *
 * 【线程模型】（3个线程，严格隔离）
 * 1. 调用者线程（通常是主线程）：只执行 start/stop/getStats，绝不阻塞。
 * 2. sensorThread（HandlerThread）：Android 传感器回调专用线程，只做"收数据→更新 lastXxx→触发 tryEmit"。
 * 3. writerThread：磁盘 IO 专用线程，从队列取数据→批量写入→定期 fd.sync()。
 *
 * 【长时间录制（3-4h）保障】
 * - 每 10 秒强制 fd.sync()，最大限度防止系统崩溃丢数据。
 * - 数据质量实时监控：NaN/Inf 过滤、时间戳跳变检测、丢帧率统计。
 * - 提供 isHealthy() 接口，外部可定期巡检，不健康时主动停止录制。
 * - 文件大小 500MB 上限保护。
 *
 * 【防呆设计】
 * - 重复 start 自动清理残留线程。
 * - stopAsync() 不阻塞调用者；awaitStop() 可选阻塞等待。
 * - 队列满时丢弃最旧数据（记录丢帧数），不阻塞传感器。
 * - 写文件异常时自动降级为只采不存（记录 error 状态），App 不崩溃。
 */
public class ImuRecorder implements SensorEventListener {
    private static final String TAG = "ImuRecorder";
    private static final String IMU_DATA_FILE = "imu_data.jsonl";
    private static final long MAX_IMU_FILE_BYTES = 500L * 1024 * 1024; // 500MB 上限
    private static final long SENSOR_STALE_NS = 50_000_000L; // 50ms，辅助传感器过期阈值
    private static final int WRITE_QUEUE_CAPACITY = 2048;
    private static final long SYNC_INTERVAL_MS = 10_000L; // 10秒强制 sync
    private static final long TIMESTAMP_JUMP_THRESHOLD_NS = 300_000_000L; // 300ms 跳变阈值（固定物理阈值，不改）
    private static final long TIMESTAMP_DRIFT_TOLERANCE_NS = 2_000_000L; // 2ms 容差，允许传感器微秒级乱序

    private final Context context;
    private final ImuConfig config;
    private final ImuHealthConfig healthConfig;
    private final SensorManager sensorManager;

    // 传感器实例
    private Sensor gyroSensor;
    private Sensor accelSensor;
    private Sensor rawAccelSensor;
    private Sensor rotSensor;
    private Sensor gravitySensor;

    // 线程
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private Thread writerThread;

    // 队列
    private final ArrayBlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);

    // 最新样本
    private volatile SensorSample lastGyro;
    private volatile SensorSample lastAccel;
    private volatile SensorSample lastRawAccel;
    private volatile SensorSample lastRot;
    private volatile SensorSample lastGravity;

    // 时间戳锚点
    private long wallAnchorMs;
    private long elapsedAnchorNs;
    private long stopWallMs;
    private long stopElapsedNs;

    // 状态
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean writerRunning = new AtomicBoolean(false);
    private File dataFile;
    private FileOutputStream fileOutputStream;
    private BufferedWriter bufferedWriter;

    // 统计
    private final AtomicLong gyroCount = new AtomicLong(0);
    private final AtomicLong accelCount = new AtomicLong(0);
    private final AtomicLong rawAccelCount = new AtomicLong(0);
    private final AtomicLong rotCount = new AtomicLong(0);
    private final AtomicLong gravityCount = new AtomicLong(0);
    private final AtomicLong outputCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    private final AtomicLong writtenBytes = new AtomicLong(0);
    private final AtomicLong nanCount = new AtomicLong(0);        // NaN/Inf 计数
    private final AtomicLong timestampJumps = new AtomicLong(0);   // 原始事件时间戳跳变计数（内部诊断）
    private final AtomicLong outputTimestampJumps = new AtomicLong(0); // 输出样本时间戳跳变计数（健康检查用）
    private long lastOutputTimestampNs = 0;
    private long lastGyroTimestampNs = 0;
    private long targetIntervalNs = 0;

    // 健康状态（由传感器回调线程更新，外部只读）
    private volatile HealthSnapshot lastHealth = new HealthSnapshot();

    private enum MasterSensor { GYRO, ACCEL, NONE }
    private MasterSensor masterSensor = MasterSensor.GYRO;

    public ImuRecorder(Context context) {
        this.context = context.getApplicationContext();
        this.config = ImuConfig.getInstance();
        this.healthConfig = ImuHealthConfig.getInstance();
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    /**
     * 启动 IMU 采集。可重复调用，会自动清理残留线程。
     * @return true = 至少一个传感器可用且文件创建成功
     */
    public boolean start(File sessionFolder) {
        if (isRunning.get()) {
            LogUtil.w(TAG, "IMU 已在运行，忽略重复 start");
            return true;
        }
        config.validateAndFix();
        if (!config.enableImuRecording) {
            LogUtil.i(TAG, "IMU 录制已关闭");
            return false;
        }

        // 【防呆】清理可能残留的旧线程（防止上次异常退出导致线程泄漏）
        cleanupResidualThreads();

        // 重置统计
        resetStats();

        // 时间戳锚点
        wallAnchorMs = System.currentTimeMillis();
        elapsedAnchorNs = SystemClock.elapsedRealtimeNanos();

        // 降采样间隔
        targetIntervalNs = config.targetOutputHz > 0 ? 1_000_000_000L / config.targetOutputHz : 0;

        // 初始化文件（用 FileOutputStream 以便 fd.sync()）
        dataFile = new File(sessionFolder, IMU_DATA_FILE);
        try {
            fileOutputStream = new FileOutputStream(dataFile);
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LogUtil.e(TAG, "IMU 数据文件创建失败", e);
            return false;
        }

        // 注册传感器
        boolean anyRegistered = registerSensors();
        if (!anyRegistered) {
            LogUtil.e(TAG, "没有可用的 IMU 传感器");
            cleanupResidualThreads(); // 停止已启动的 sensorThread，防止线程泄漏
            closeWriter();
            return false;
        }

        // 启动写线程
        isRunning.set(true);
        writerRunning.set(true);
        writerThread = new Thread(this::writerLoop, "imu-writer");
        writerThread.start();

        LogUtil.i(TAG, "IMU 启动: master=" + masterSensor
                + ", targetHz=" + config.targetOutputHz
                + ", syncEveryMs=" + SYNC_INTERVAL_MS);
        return true;
    }

    /**
     * 异步停止：立即返回，不阻塞调用者线程。
     * 传感器会立即注销，写线程收到停止信号后自行 flush。
     */
    public void stopAsync() {
        if (!isRunning.getAndSet(false)) return;

        // 立即注销传感器（防止继续产生数据）
        unregisterSensors();

        // 记录结束锚点
        stopWallMs = System.currentTimeMillis();
        stopElapsedNs = SystemClock.elapsedRealtimeNanos();

        // 通知写线程结束
        writerRunning.set(false);

        LogUtil.i(TAG, "IMU 异步停止信号已发出");
    }

    /**
     * 阻塞等待写线程结束。应在后台线程调用，不要在主线程直接调用。
     * @param timeoutMs 最大等待毫秒
     * @return true = 正常结束；false = 超时
     */
    public boolean awaitStop(long timeoutMs) {
        if (writerThread == null || !writerThread.isAlive()) {
            closeWriter();
            return true;
        }
        try {
            writerThread.join(timeoutMs);
            boolean alive = writerThread.isAlive();
            if (alive) {
                LogUtil.w(TAG, "IMU 写线程未在 " + timeoutMs + "ms 内结束，强制中断");
                writerThread.interrupt();
            }
            closeWriter();
            LogUtil.i(TAG, "IMU 停止完成: 输出=" + outputCount.get()
                    + ", 丢弃=" + droppedCount.get()
                    + ", 写错误=" + writeErrors.get()
                    + ", NaN=" + nanCount.get()
                    + ", 原始跳变=" + timestampJumps.get()
                    + ", 输出跳变=" + outputTimestampJumps.get()
                    + ", 大小=" + (dataFile != null ? dataFile.length() : 0) + "B");
            return !alive;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeWriter();
            return false;
        }
    }

    /**
     * 获取健康检查结果。外部应每 30~60 秒调用一次巡检。
     * @return HealthResult，healthy=true 时 reason 为 null，healthy=false 时 reason 为具体原因
     */
    public HealthResult checkHealth() {
        healthConfig.validateAndFix();
        HealthSnapshot h = lastHealth;
        if (h.durationSec < healthConfig.warmupSeconds) {
            return HealthResult.ok();
        }

        // 判据1：丢帧率
        if (h.dropRate > healthConfig.maxDropRate) {
            String reason = "IMU丢帧严重(" + String.format(java.util.Locale.US, "%.1f%%", h.dropRate * 100)
                    + ")，数据可能不完整";
            LogUtil.w(TAG, reason);
            return HealthResult.fail(reason);
        }
        // 判据2：写错误
        if (h.writeErrorCount > healthConfig.maxWriteErrors) {
            String reason = "IMU磁盘写入失败(" + h.writeErrorCount + "次)，存储可能已满";
            LogUtil.w(TAG, reason);
            return HealthResult.fail(reason);
        }
        // 判据3：NaN/Inf 比例
        if (h.nanRate > healthConfig.maxNanRate) {
            String reason = "IMU传感器异常(" + String.format(java.util.Locale.US, "%.1f%%", h.nanRate * 100)
                    + "非法数值)，陀螺仪可能损坏";
            LogUtil.w(TAG, reason);
            return HealthResult.fail(reason);
        }
        // 判据4：时间戳跳变
        if (h.timestampJumpCount > healthConfig.maxTimestampJumps) {
            String reason = "IMU时间戳跳变(" + h.timestampJumpCount + "次)，无法与视频对齐";
            LogUtil.w(TAG, reason);
            return HealthResult.fail(reason);
        }
        // 判据5：实际频率低于目标频率的比例
        if (config.targetOutputHz > 0 && h.actualHz < config.targetOutputHz * healthConfig.minFreqRatio) {
            String reason = "IMU频率过低(" + String.format(java.util.Locale.US, "%.1f", h.actualHz)
                    + "Hz)，手机过热或省电模式";
            LogUtil.w(TAG, reason);
            return HealthResult.fail(reason);
        }
        return HealthResult.ok();
    }

    /** 兼容旧接口：只返回是否健康 */
    public boolean isHealthy() {
        return checkHealth().healthy;
    }

    public Stats getStats() {
        Stats s = new Stats();
        s.wallAnchorMs = wallAnchorMs;
        s.elapsedAnchorNs = elapsedAnchorNs;
        s.stopWallMs = stopWallMs;
        s.stopElapsedNs = stopElapsedNs;
        s.gyroCount = gyroCount.get();
        s.accelCount = accelCount.get();
        s.rawAccelCount = rawAccelCount.get();
        s.rotCount = rotCount.get();
        s.outputCount = outputCount.get();
        s.droppedCount = droppedCount.get();
        s.writeErrors = writeErrors.get();
        s.nanCount = nanCount.get();
        s.timestampJumps = timestampJumps.get();
        s.outputTimestampJumps = outputTimestampJumps.get();
        s.writtenBytes = writtenBytes.get();
        s.dataFileSize = dataFile != null ? dataFile.length() : 0;
        s.masterSensor = masterSensor.name().toLowerCase();
        long durSec = Math.max(1, (stopWallMs - wallAnchorMs) / 1000);
        s.actualOutputHz = (double) s.outputCount / durSec;
        s.gyroAvailable = (gyroSensor != null);
        s.accelAvailable = (accelSensor != null);
        s.rawAccelAvailable = (rawAccelSensor != null)
                || (accelSensor != null && accelSensor.getType() == Sensor.TYPE_ACCELEROMETER);
        s.rotAvailable = (rotSensor != null);
        s.gravityAvailable = (gravitySensor != null);
        s.accelIsLinear = (accelSensor != null && accelSensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION);
        s.rotIsGame = (rotSensor != null && rotSensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR);
        return s;
    }

    // ==================== SensorEventListener ====================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRunning.get()) return;

        long tNs = event.timestamp;
        float[] vals = event.values;
        int type = event.sensor.getType();

        // === 数据质量检查 ===
        // 1. 时间戳单调性 + 跳变检测（原始事件层）
        // 允许 2ms 以内的微秒级乱序，避免高频率传感器的正常抖动被误判为倒流
        long prevTs = (type == Sensor.TYPE_GYROSCOPE) ? lastGyroTimestampNs : lastOutputTimestampNs;
        if (tNs <= 0 || (prevTs > 0 && tNs < prevTs - TIMESTAMP_DRIFT_TOLERANCE_NS)) {
            timestampJumps.incrementAndGet();
            return; // 时间戳严重倒流（超过容差），丢弃
        }
        if (prevTs > 0 && tNs - prevTs > TIMESTAMP_JUMP_THRESHOLD_NS) {
            timestampJumps.incrementAndGet(); // 原始事件层记录大幅跳变，但仍保留数据
        }
        if (type == Sensor.TYPE_GYROSCOPE) {
            lastGyroTimestampNs = tNs;
        }

        // 2. NaN/Inf 检测
        if (containsInvalidFloat(vals)) {
            nanCount.incrementAndGet();
            return; // 传感器返回非法数值，丢弃整帧
        }

        SensorSample sample = new SensorSample(tNs, vals.clone());

        switch (type) {
            case Sensor.TYPE_GYROSCOPE:
                lastGyro = sample;
                gyroCount.incrementAndGet();
                if (masterSensor == MasterSensor.GYRO) tryEmit(tNs);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                lastAccel = sample;
                accelCount.incrementAndGet();
                if (masterSensor == MasterSensor.ACCEL) tryEmit(tNs);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                lastRawAccel = sample;
                rawAccelCount.incrementAndGet();
                // 如果当前 accelSensor 也是 TYPE_ACCELEROMETER（fallback 情况），
                // 同时更新 lastAccel 以保持向后兼容（"a" 字段仍然有值）
                if (accelSensor != null && accelSensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    lastAccel = sample;
                    accelCount.incrementAndGet();
                    if (masterSensor == MasterSensor.ACCEL) tryEmit(tNs);
                }
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                lastRot = sample;
                rotCount.incrementAndGet();
                break;
            case Sensor.TYPE_GRAVITY:
                lastGravity = sample;
                gravityCount.incrementAndGet();
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 长时间录制时可记录精度降级，暂不处理
    }

    // ==================== 内部方法 ====================

    private void tryEmit(long masterTimestampNs) {
        if (targetIntervalNs > 0 && masterTimestampNs - lastOutputTimestampNs < targetIntervalNs) {
            return;
        }
        // 输出样本层跳变检测：关注最终数据质量（而非原始事件噪声）
        if (lastOutputTimestampNs > 0 && masterTimestampNs - lastOutputTimestampNs > TIMESTAMP_JUMP_THRESHOLD_NS) {
            outputTimestampJumps.incrementAndGet();
        }
        lastOutputTimestampNs = masterTimestampNs;

        String line = buildJsonLine(masterTimestampNs);
        if (line == null) return;

        if (!writeQueue.offer(line)) {
            writeQueue.poll(); // 丢弃最旧
            droppedCount.incrementAndGet();
            writeQueue.offer(line);
        }
    }

    private String buildJsonLine(long masterTimestampNs) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{");
        sb.append("\"t_ns\":").append(config.convertTimestamp(masterTimestampNs));

        if (lastGyro != null && Math.abs(lastGyro.timestampNs - masterTimestampNs) <= SENSOR_STALE_NS) {
            sb.append(",\"g\":[").append(fmt(lastGyro.values[0])).append(",")
              .append(fmt(lastGyro.values[1])).append(",").append(fmt(lastGyro.values[2])).append("]");
        }
        if (lastAccel != null && Math.abs(lastAccel.timestampNs - masterTimestampNs) <= SENSOR_STALE_NS) {
            sb.append(",\"a\":[").append(fmt(lastAccel.values[0])).append(",")
              .append(fmt(lastAccel.values[1])).append(",").append(fmt(lastAccel.values[2])).append("]");
        }
        if (lastRawAccel != null && Math.abs(lastRawAccel.timestampNs - masterTimestampNs) <= SENSOR_STALE_NS) {
            sb.append(",\"ra\":[").append(fmt(lastRawAccel.values[0])).append(",")
              .append(fmt(lastRawAccel.values[1])).append(",").append(fmt(lastRawAccel.values[2])).append("]");
        }
        if (lastRot != null && Math.abs(lastRot.timestampNs - masterTimestampNs) <= SENSOR_STALE_NS) {
            sb.append(",\"r\":[").append(fmt(lastRot.values[0])).append(",")
              .append(fmt(lastRot.values[1])).append(",").append(fmt(lastRot.values[2])).append(",");
            float w = lastRot.values.length >= 4 ? lastRot.values[3] : computeScalar(lastRot.values);
            sb.append(fmt(w)).append("]");
        }
        if (lastGravity != null && Math.abs(lastGravity.timestampNs - masterTimestampNs) <= SENSOR_STALE_NS) {
            sb.append(",\"gv\":[").append(fmt(lastGravity.values[0])).append(",")
              .append(fmt(lastGravity.values[1])).append(",").append(fmt(lastGravity.values[2])).append("]");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String fmt(float v) {
        String s = String.format(java.util.Locale.US, "%.6f", v);
        int len = s.length();
        while (len > 1 && s.charAt(len - 1) == '0') len--;
        if (len > 1 && s.charAt(len - 1) == '.') len--;
        return s.substring(0, len);
    }

    private static float computeScalar(float[] xyz) {
        double s = 1.0 - xyz[0]*xyz[0] - xyz[1]*xyz[1] - xyz[2]*xyz[2];
        return (float) Math.sqrt(Math.max(0.0, s));
    }

    private static boolean containsInvalidFloat(float[] arr) {
        for (float v : arr) {
            if (Float.isNaN(v) || Float.isInfinite(v)) return true;
        }
        return false;
    }

    /**
     * 写文件线程：批量写入 + 定期 fd.sync() + 更新健康状态。
     */
    private void writerLoop() {
        long lastFlushTime = System.currentTimeMillis();
        long lastSyncTime = System.currentTimeMillis();
        int batchCount = 0;

        while (writerRunning.get() || !writeQueue.isEmpty()) {
            try {
                String line = writeQueue.poll(200, TimeUnit.MILLISECONDS);
                if (line != null) {
                    try {
                        bufferedWriter.write(line);
                        batchCount++;
                        outputCount.incrementAndGet();
                        writtenBytes.addAndGet(line.getBytes(StandardCharsets.UTF_8).length);
                    } catch (IOException e) {
                        writeErrors.incrementAndGet();
                        if (writeErrors.get() <= 5) {
                            LogUtil.e(TAG, "IMU 写文件异常", e);
                        }
                    }
                }

                long now = System.currentTimeMillis();
                boolean shouldFlush = batchCount >= config.batchSize
                        || (batchCount > 0 && now - lastFlushTime >= config.maxBufferTimeMs)
                        || !writerRunning.get();

                if (shouldFlush && batchCount > 0) {
                    try {
                        bufferedWriter.flush();
                        lastFlushTime = now;
                        batchCount = 0;
                    } catch (IOException e) {
                        writeErrors.incrementAndGet();
                        if (writeErrors.get() <= 5) {
                            LogUtil.w(TAG, "IMU flush 失败", e);
                        }
                    }
                }

                // 定期 fd.sync()：最大限度防止系统崩溃/断电丢数据
                if (now - lastSyncTime >= SYNC_INTERVAL_MS) {
                    try {
                        bufferedWriter.flush();
                        if (fileOutputStream != null) {
                            fileOutputStream.getFD().sync();
                        }
                        lastSyncTime = now;
                    } catch (IOException e) {
                        writeErrors.incrementAndGet();
                        LogUtil.w(TAG, "IMU fd.sync() 失败", e);
                    }
                    // 更新健康状态快照
                    updateHealthSnapshot(now);
                }

                if (writtenBytes.get() > MAX_IMU_FILE_BYTES) {
                    LogUtil.w(TAG, "IMU 文件超过 500MB，停止采集");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 最终 flush + sync
        try {
            if (bufferedWriter != null) bufferedWriter.flush();
            if (fileOutputStream != null) fileOutputStream.getFD().sync();
        } catch (IOException ignored) {}
    }

    private void updateHealthSnapshot(long nowMs) {
        HealthSnapshot h = new HealthSnapshot();
        long durSec = Math.max(1, (nowMs - wallAnchorMs) / 1000);
        h.durationSec = durSec;
        h.actualHz = (double) outputCount.get() / durSec;
        long total = outputCount.get() + droppedCount.get();
        h.dropRate = total > 0 ? (double) droppedCount.get() / total : 0;
        h.writeErrorCount = writeErrors.get();
        h.nanRate = total > 0 ? (double) nanCount.get() / total : 0;
        h.timestampJumpCount = outputTimestampJumps.get(); // 健康检查用输出样本跳变
        lastHealth = h;
    }

    // ==================== 传感器注册 / 注销 ====================

    private boolean registerSensors() {
        if (sensorManager == null) return false;

        sensorThread = new HandlerThread("imu-sensor");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        boolean hasGyro = false, hasAccel = false, hasRot = false;

        if (config.useGyroscope) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, config.sampleRateMode, sensorHandler);
                hasGyro = true;
            }
        }
        if (config.useLinearAcceleration) {
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (accelSensor != null) {
                sensorManager.registerListener(this, accelSensor, config.sampleRateMode, sensorHandler);
                hasAccel = true;
            } else if (config.fallbackToAccelerometer) {
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accelSensor != null) {
                    sensorManager.registerListener(this, accelSensor, config.sampleRateMode, sensorHandler);
                    hasAccel = true;
                }
            }
        }
        if (config.useRotationVector) {
            rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotSensor != null) {
                sensorManager.registerListener(this, rotSensor, config.sampleRateMode, sensorHandler);
                hasRot = true;
            } else if (config.fallbackToGameRotationVector) {
                rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (rotSensor != null) {
                    sensorManager.registerListener(this, rotSensor, config.sampleRateMode, sensorHandler);
                    hasRot = true;
                }
            }
        }
        // 原始加速度计（与线性加速度独立，避免重复注册）
        if (config.useRawAccelerometer) {
            if (accelSensor != null && accelSensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                rawAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (rawAccelSensor != null) {
                    sensorManager.registerListener(this, rawAccelSensor, config.sampleRateMode, sensorHandler);
                }
            }
            // 如果 accelSensor 已经是 TYPE_ACCELEROMETER（fallback），
            // 则 rawAccelSensor 无需单独注册，避免同一硬件重复回调
        }

        // 重力传感器：软件合成，有就采，没有不强求
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravitySensor != null) {
            sensorManager.registerListener(this, gravitySensor, config.sampleRateMode, sensorHandler);
        }

        masterSensor = hasGyro ? MasterSensor.GYRO : (hasAccel ? MasterSensor.ACCEL : MasterSensor.NONE);
        return hasGyro || hasAccel || hasRot;
    }

    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (sensorThread != null) {
            sensorThread.quitSafely();
        }
    }

    private void cleanupResidualThreads() {
        // 注销可能残留的传感器
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        // 结束可能残留的 HandlerThread
        if (sensorThread != null) {
            sensorThread.quit();
            sensorThread = null;
        }
        // 结束可能残留的写线程
        if (writerThread != null && writerThread.isAlive()) {
            writerRunning.set(false);
            try { writerThread.join(1000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writerThread = null;
        }
        closeWriter();
    }

    private void closeWriter() {
        if (bufferedWriter != null) {
            try { bufferedWriter.close(); } catch (IOException ignored) {}
            bufferedWriter = null;
        }
        if (fileOutputStream != null) {
            try { fileOutputStream.close(); } catch (IOException ignored) {}
            fileOutputStream = null;
        }
    }

    private void resetStats() {
        gyroCount.set(0); accelCount.set(0); rawAccelCount.set(0); rotCount.set(0); gravityCount.set(0);
        outputCount.set(0); droppedCount.set(0); writeErrors.set(0);
        nanCount.set(0); timestampJumps.set(0); outputTimestampJumps.set(0); writtenBytes.set(0);
        lastOutputTimestampNs = 0; lastGyroTimestampNs = 0;
        lastGyro = null; lastAccel = null; lastRawAccel = null; lastRot = null; lastGravity = null;
        lastHealth = new HealthSnapshot();
    }

    // ==================== 数据结构 ====================

    public static class HealthResult {
        public final boolean healthy;
        public final String reason;

        private HealthResult(boolean healthy, String reason) {
            this.healthy = healthy;
            this.reason = reason;
        }

        public static HealthResult ok() {
            return new HealthResult(true, null);
        }

        public static HealthResult fail(String reason) {
            return new HealthResult(false, reason);
        }
    }

    private static class SensorSample {
        final long timestampNs;
        final float[] values;
        SensorSample(long timestampNs, float[] values) {
            this.timestampNs = timestampNs;
            this.values = values;
        }
    }

    private static class HealthSnapshot {
        long durationSec;
        double actualHz;
        double dropRate;
        long writeErrorCount;
        double nanRate;
        long timestampJumpCount;
    }

    public static class Stats {
        public long wallAnchorMs;
        public long elapsedAnchorNs;
        public long stopWallMs;
        public long stopElapsedNs;
        public long gyroCount;
        public long accelCount;
        public long rawAccelCount;
        public long rotCount;
        public long outputCount;
        public long droppedCount;
        public long writeErrors;
        public long nanCount;
        public long timestampJumps;
        public long outputTimestampJumps;
        public long writtenBytes;
        public long dataFileSize;
        public String masterSensor;
        public double actualOutputHz;
        public boolean gyroAvailable;
        public boolean accelAvailable;
        public boolean rawAccelAvailable;
        public boolean rotAvailable;
        public boolean gravityAvailable;
        public boolean accelIsLinear;
        public boolean rotIsGame;
    }
}
