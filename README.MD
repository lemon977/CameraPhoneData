# CameraPhoneData

> Android 手机相机标定 + 原始视频采集 + IMU 惯性传感器同步采集 + 阿里云 OSS 上传。
> 横屏全屏，60 秒分段，人手检测，IMU 健康巡检，21 个账号登录。

---

## 技术栈

| 领域 | 技术 |
|------|------|
| 相机预览与录制 | CameraX (VideoCapture + Preview) |
| 图像处理 / 标定 | OpenCV 4.x (`opencv` 模块) |
| 人手检测 | ML Kit Pose Detection |
| 传感器采集 | Android SensorManager (陀螺仪 / 加速度 / 旋转矢量 / 重力) |
| 网络上传 | 阿里云 OSS SDK |
| 持久化 | SharedPreferences + 本地 JSON |
| 最小 SDK | 根据 `build.gradle` 配置 |

---

## 项目结构（按层）

### 1. 全局入口

| 类 | 职责 |
|----|------|
| `App` | Application 入口。加载 OpenCV native 库、安装全局崩溃捕获器 (`CrashHandler`)。不做 TTS/MLKit/相机等按需初始化，避免拖慢冷启动。 |

### 2. 配置层（`config`）

> 所有配置的"唯一数据源"，均为线程安全单例，带 `validateAndFix()` 防呆修正。

| 类 | 职责 | 关键修改点 |
|----|------|-----------|
| `AccountList` | **唯一手动修改点**。预置 21 个账号（1 管理员 + 20 采集人），密码用 `char[]` | 改密码只改这里，重新打包 |
| `AccountConfig` | 账号验证中心。对外不暴露密码，内部 `char[]` 比对，比对完 `Arrays.fill` 清零 | 无 |
| `AccountManager` | 登录态加密持久化（`EncryptedSharedPreferences`），存用户名/角色/显示名，**不存密码** | 无 |
| `CameraConfig` | 相机、录制、检测、语音的所有参数。分辨率、分段时长、人手检测阈值、防抖等 | 全局调参入口 |
| `CalibrationConfig` | 棋盘格检测与 OpenCV 标定参数。`maxRmsError` 默认 0.5，`detectionMode` 默认 2（增强） | 无 |
| `CalibrationData` | 标定结果持久化（SharedPreferences），**双维度键**：`zoom × resolution` | 支持同一手机多焦距多分辨率标定 |
| `DataConfig` | 文件夹命名规则、采集者身份 | `collectorName` 登录后自动绑定 |
| `UploadConfig` | 阿里云 OSS 配置读取入口。**仅支持 OSS** | 密钥从 `OssKeyStore` 加密读取 |
| `ImuConfig` | IMU 采集配置中心。采样率、目标输出频率、传感器选择、降级策略、批量写入策略 | `targetOutputHz=60` 推荐 |
| `ImuHealthConfig` | IMU 健康检查阈值。丢帧率、写错误、NaN/Inf、时间戳跳变、频率巡检 | 不健康时自动停止录制 |
| `FileNames` | 所有数据文件名称常量。`metadata.json`、`imu_data.jsonl` 等 | 避免硬编码分散 |
| `TimeConstants` | 所有时间相关魔法数字。进度刷新间隔、超时、WakeLock 时长等 | 统一管理 |

### 3. 相机层（`camera`）

| 类 | 职责 |
|----|------|
| `CameraManager` | CameraX 生命周期绑定、变焦控制、预览启动。支持**融合架构**（单摄变焦模拟多摄）与**物理多摄**切换。带 `cameraSessionId` 防并发竞争 |
| `CameraParamReader` | 三级降级读取相机参数：①系统出厂标定 → ②传感器物理尺寸估算 → ③人工标定覆盖。输出 `fx/fy/cx/cy` + 畸变 8 维 + `source` 优先级标记 |
| `CameraDebugDumper` | **长按上传按钮触发**。导出 Camera2 静态信息 + CameraX 动态信息 + ZoomState 实测 + 绑定测试 JSON，用于排查多摄兼容性问题 |

### 4. 标定层（`calibration`）

| 类 | 职责 | 注意 |
|----|------|------|
| `CalibrationActivity` | 标定界面。独立 CameraX 实例，锁定当前 zoom 和分辨率，拍 15 张棋盘格 | 未登录直接 `finish()` |
| `CalibrationManager` | OpenCV 标定计算。强制 **Brown-Conrady 5 维**（`CALIB_FIX_K3`），输出 `fx/fy/cx/cy` + 8 维畸变 + RMS 误差 | 只填充前 5 维，后 3 维为 0 |
| `PatternDetector` | 棋盘格检测策略：快速 → 标准 → 增强。支持缩小图像二次检测、亚像素精化、超时保护 | 屏幕反光/弱光用 `detectionMode=2` |
| `CalibrationJsonImporter` | 启动时扫描外部目录的 `calib_*.json`，导入旧标定数据到 `CalibrationData` | 不覆盖已有手动标定 |
| `CalibrationUtil` | 去畸变工具类（预留）。提供 `undistort()` 和 `getParamsForRecording()` | 数据工程师参考用 |

### 5. 检测与报警层（`detection / alert`）

| 类 | 职责 | 关键参数 |
|----|------|---------|
| `HandDetector` | ML Kit Pose Detection 分析器。检测手腕/手肘/肩膀任意一个可见即算有手 | `confidenceThreshold=0.3f`，`intervalMs=300` |
| `HandDetectionManager` | 检测器生命周期管理。录制时开，平时关，停止时彻底 `reset()` 报警倒计时 | 无 |
| `HandAlertManager` | 无手倒计时报警。15 秒（可配置）无手触发语音/蜂鸣 | `noHandTimeoutMs` 在 `CameraConfig` 调 |
| `VoicePromptManager` | 语音播报兜底。MediaPlayer → 蜂鸣 + 振动。带 800ms 防抖 | 无 TTS，纯音频资源或蜂鸣 |

### 6. 录制层（`recorder / recording`）

| 类 | 职责 | 状态机 |
|----|------|--------|
| `VideoRecorder` | CameraX VideoCapture 封装。60 秒定时分段，文件命名，音频开关 | `IDLE → PREPARING → RECORDING → SPLITTING → STOPPING → COMPLETED` |
| `ImuRecorder` | **IMU 统一采集器**。陀螺仪 / 线性加速度 / 原始加速度 / 旋转矢量 / 重力。3 线程模型（sensorThread + writerThread + 调用者线程），支持软件降采样、NaN/Inf 过滤、时间戳跳变检测、10 秒 `fd.sync()`、500MB 上限保护 | `start → stopAsync → awaitStop` |
| `RecordingManager` | 录制状态机与元数据管理。检查存储、创建会话文件夹、启动 WakeLock、IMU 健康巡检、有效时长统计 | 同左 |
| `RecordingCoordinator` | UI 联动层。录制开始/停止时切换按钮状态、启停人手检测、绑定前台服务 | 无 |
| `RecordingForegroundService` | 前台服务保活。`PARTIAL_WAKE_LOCK` + 通知栏，防止熄屏被杀 | `START_STICKY` 被杀后自动重启 |

### 7. 数据与上传层（`data / upload`）

| 类 | 职责 | 说明 |
|----|------|------|
| `FileRepository` | **所有文件 IO 唯一入口**。生成 `metadata.json` / `imu_config.json`，追加 segment，结束录制时补全时间戳、有效时长、实际分辨率 | 超 100 段自动归档到 `metadata_history.json`；原子写（`.tmp + rename + fd.sync`） |
| `EffectiveDurationManager` | 按**日期_采集人**累计有效时长（毫秒），Session 级 SHA-256 防重。后端算工资直接除 3600 得小时 | 从 `metadata.json` 提取后持久化到 SharedPreferences |
| `StorageManager` | 存储路径多级降级：外部私有 → 内部私有 → 缓存。空间检测、格式化 | 异常安全，绝不抛崩溃 |
| `UploadManager` | 上传调度。逐个文件直传 OSS，不打包 zip，失败只影响单文件 | 支持后台前台服务上传 |
| `OssUploadStrategy` | 阿里云 OSS 上传实现。简单上传 + 分片上传，指数退避重试 | 分片 10MB，单片失败只重传该片 |
| `UploadRepository` | 策略注册中心。**目前只注册了 OSS**，后人加新网盘在这里 `register()` | 无 |
| `UploadRecordManager` | 上传记录本地 JSON 持久化，支持断点续传查询 | 自动清理已删除文件夹的记录 |
| `UploadRecord` | 上传任务数据类 | 无 |
| `UploadState` | 上传状态全局单例，供前台服务写入、UI 轮询读取 | 无 |
| `UploadUiHelper` | 上传对话框、日期文件夹选择、启动前台服务 | 无 |

### 8. 服务层（`service`）

| 类 | 职责 |
|----|------|
| `RecordingForegroundService` | 录制保活前台服务。通知栏显示录制时长，WakeLock 防熄屏 |
| `UploadForegroundService` | 上传保活前台服务。通知栏显示第 N/M 个文件、总体进度百分比 |

### 9. 工具层（`utils`）

| 类 | 职责 |
|----|------|
| `LogUtil` | 统一日志。带 `BuildConfig.DEBUG` 开关，支持 `[tag] [zoom] [resolution] msg` 格式 |
| `PermissionHelper` | 运行时权限申请：相机 + 录音 + 存储。被拒绝后弹窗引导去设置 |
| `StorageManager` | 同数据层，路径降级 + 空间检测 |
| `CrashHandler` | **全局崩溃捕获**。录制过程中崩溃时自动紧急停止并保存数据；崩溃日志写入 `crash_logs/` 目录 |

### 10. UI 层

| 类 | 职责 | 注意 |
|----|------|------|
| `MainActivity` | 主界面生命周期、权限申请、相机启动、蓝牙快门拦截、登录拦截、镜头角色切换、内存监控显示 | 核心调度中心。支持**融合架构**变焦切镜头与**物理多摄**切镜头 |
| `MainUiManager` | 主界面所有 View 状态集中控制。按钮颜色、参数面板文字、镜头角色显示、未标定横幅 | 录制时禁用变焦/标定/导出按钮 |
| `CalibrationActivity` | 标定专用界面。全屏预览、进度条、拍摄/计算/重置按钮 | 未登录直接 `finish()` |
| `DialogManager` | **弹窗队列管理器**。按优先级（0~3）排队显示，防止多个 Dialog 叠加覆盖。支持 `enqueue` 排队与 `showImmediate` 强制插队 | 在 `Activity.onResume` 中 `flush()` 消费队列 |

### 11. 资源文件（`res`）

| 文件 | 用途 | 修改点 |
|------|------|--------|
| `AndroidManifest.xml` | 权限、服务声明、横屏锁定、FileProvider | 加权限在这里 |
| `activity_main.xml` | 主界面：全屏 PreviewView + 右侧 180dp 信息面板 + 底部控制栏 | 调 UI 布局 |
| `activity_calibration.xml` | 标定界面：全屏预览 + 右侧控制面板 | 调按钮位置 |
| `strings.xml` | 所有界面文案、Toast、标定提示语 | 改提示文字 |
| `file_paths.xml` | FileProvider 路径配置 | 新增目录需加 `external-files-path` |

---

## 数据流向（端到端）

```
登录（账号绑定身份）
↓
标定（棋盘格 → OpenCV → fx/fy/cx/cy + k1~k5 + RMS）
↓
录制启动
├── 视频：CameraX VideoCapture → 60s/段 MP4
├── IMU：SensorManager → 软件降采样 → imu_data.jsonl
└── 元数据：metadata.json（相机参数、segment 列表、有效时长）
    └── imu_config.json（时间戳映射、传感器可用性、采样率统计）
↓
本地文件夹结构：
RobotData/
└── 日期_姓名/
    └── 序号_镜头角色/
        ├── metadata.json
        ├── metadata_history.json  (>=100 段时)
        ├── imu_config.json
        ├── imu_data.jsonl
        └── video_YYYYMMDD_HHMMSS_001.mp4
↓
一键上传 OSS（逐个文件，不打包 zip）
↓
后端下载 → 读 metadata.json / imu_config.json → 去畸变 → 视频-IMU 时间戳对齐 → 机器人训练
```

---

## 输出文件格式

### `metadata.json`

```json
{
  "schema_version": "2.2",
  "session": {
    "index": 1,
    "collector": "张三",
    "start_time_ms": 1715581200000,
    "stop_time_ms": 1715584800000,
    "effective_duration_ms": 3600000,
    "effective_duration_sec": 3600
  },
  "camera": {
    "lens_role": "WIDE",
    "intrinsics": { "fx": 1500.0, "fy": 1500.0, "cx": 960.0, "cy": 540.0 },
    "distortion": { "model": "Brown-Conrady-5", "k1": 0.1, "k2": -0.05, "p1": 0, "p2": 0, "k3": 0 }
  },
  "recording": {
    "segment_duration_ms": 60000,
    "target_fps": 30,
    "has_audio": true
  },
  "segments": [
    { "index": 1, "file": "video_20240513_120000_001.mp4", "start_time_ms": ..., "stop_time_ms": ..., "duration_ms": 60000 }
  ]
}
```

### `imu_config.json`

```json
{
  "timestamp_mapping": {
    "wall_anchor_ms": 1715581200000,
    "elapsed_anchor_ns": 123456789000000,
    "video_start_ms": 1715581200230,
    "video_stop_ms": 1715584800000,
    "timestamp_unit": "ns",
    "unit_per_ms": 1000000,
    "drift_ok": true
  },
  "sensors": {
    "requested": ["gyroscope", "linear_acceleration", "rotation_vector"],
    "available": { "gyroscope": true, "linear_acceleration": true, ... }
  },
  "sample_rate": {
    "target_output_hz": 60,
    "actual_output_hz": "59.87"
  },
  "data_format": {
    "file": "imu_data.jsonl",
    "line_fields": {
      "t_ns": "时间戳(ns)",
      "g": "陀螺仪[rad/s]",
      "a": "线性加速度[m/s²]",
      "ra": "原始加速度[m/s²]",
      "r": "旋转四元数[x,y,z,scalar]",
      "gv": "重力矢量[m/s²]"
    }
  }
}
```

### `imu_data.jsonl`

```jsonl
{"t_ns":123456789000000,"g":[0.001234,-0.000567,0.002345],"a":[0.12,-0.05,9.81],"r":[0.01,0.02,0.03,0.999]}
{"t_ns":123456789016667,"g":[0.001200,-0.000500,0.002400],"a":[0.11,-0.04,9.80],"r":[0.011,0.021,0.031,0.998]}
```

---

## 关键设计决策

| 决策 | 原因 |
|------|------|
| **char[] 密码** | 避免 Java String 常量池长期驻留，比对完 `Arrays.fill` 清零 |
| **双维度标定键** | `zoom × resolution`，同一手机不同焦距/分辨率独立标定 |
| **metadata.json 超 100 段归档** | 防止 JSON 无限膨胀导致内存爆炸 |
| **不打包 zip** | MP4 已是压缩格式，zip 无收益且浪费 CPU/磁盘/时间 |
| **OSS 直传** | 单片失败只影响单文件，不导致整包报废 |
| **账号登录拦截** | 未登录时录制/标定/上传按钮点击即弹登录框，数据强制绑定身份 |
| **IMU 先启动、视频后启动** | CameraX 编码器初始化有约 200-300ms 延迟，先启动 IMU 确保人手动作起始阶段数据完整（前导数据后端截断对齐） |
| **IMU 软件降采样** | 硬件用 `FASTEST` 注册获取最高信噪比，软件按 `targetOutputHz` 输出，消除厂商差异 |
| **IMU 三线程隔离** | sensorThread（收数据）→ writerThread（写磁盘）→ 调用者线程（start/stop），绝不阻塞传感器回调 |
| **原子写 metadata** | `.tmp + rename + fd.sync`，防止崩溃/断电导致文件损坏 |
| **有效时长截断秒** | 不足 1 秒直接舍去，后端算工资 `effective_duration_sec / 3600` 得小时 |
| **CrashHandler 紧急停止** | 录制 3-4 小时过程中任何模块崩溃，自动 finalize 已录段，把损失降到最低 |
| **弹窗队列 + 优先级** | 防止 MLKit/相机/上传等多模块同时弹 Dialog 导致叠加覆盖或 ANR |

---

## 最近重要更新

| 日期 | 变更 |
|------|------|
| 2026-04 | **IMU 采集系统**：新增陀螺仪/加速度/旋转矢量/重力同步采集，支持健康巡检与自动停止 |
| 2026-04 | **有效时长管理**：按日期_采集人累计时长，Session 防重，供后端算工资 |
| 2026-04 | **崩溃安全**：`CrashHandler` 全局捕获，崩溃时紧急停止录制并保存数据 |
| 2026-04 | **相机 Debug 导出**：长按上传按钮导出多摄架构诊断 JSON |
| 2026-04 | **Dialog 队列**：优先级排队显示，解决多模块弹窗叠加问题 |
| 2026-04 | **镜头角色**：区分融合架构（变焦模拟）与物理多摄，广角/主摄独立标定 |
| 2026-04 | **metadata 原子写**：`.tmp + rename + fd.sync` 防崩溃丢数据 |
| 2026-04 | **常量集中化**：`FileNames` + `TimeConstants` 替代散落魔法数字 |
