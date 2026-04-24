# CameraPhoneData 类说明

&gt; 一句话：Android 手机相机标定 + 原始视频采集 + 阿里云 OSS 上传。
&gt; 横屏全屏，60 秒分段，人手检测，21 个账号登录。

---

## 配置层（config）

| 类 | 职责 | 关键修改点 |
|----|------|-----------|
| `AccountList` | **唯一手动修改点**。预置 21 个账号（1 管理员 + 20 采集人），密码用 `char[]` | 改密码只改这里，重新打包 |
| `AccountConfig` | 账号验证中心。对外不暴露密码，内部 `char[]` 比对，比对完 `Arrays.fill` 清零 | 无 |
| `AccountManager` | 登录态加密持久化（`EncryptedSharedPreferences`），存用户名/角色/显示名，**不存密码** | 无 |
| `CameraConfig` | 相机、录制、检测、语音的所有参数。单例，改这里生效全局 | 分辨率、分段时长、人手检测阈值都在这里调 |
| `CalibrationConfig` | 棋盘格检测与 OpenCV 标定参数。单例 | `maxRmsError` 默认 0.5，`detectionMode` 默认 2（增强） |
| `CalibrationData` | 标定结果持久化（SharedPreferences），**双维度键**：`zoom × resolution` | 支持同一手机多焦距多分辨率标定 |
| `DataConfig` | 文件夹命名规则、采集者身份 | `collectorName` 登录后自动绑定 |
| `UploadConfig` | 阿里云 OSS 配置读取入口。**仅支持 OSS**，WiFi/Cloud 已删除 | 密钥从 `OssKeyStore` 加密读取 |

---

## 相机层（camera）

| 类 | 职责 |
|----|------|
| `CameraManager` | CameraX 生命周期绑定、变焦控制、预览启动。带 `cameraSessionId` 防并发竞争 |
| `CameraParamReader` | 三级降级读取相机参数：①系统出厂标定 → ②传感器物理尺寸估算 → ③人工标定覆盖。输出 `fx/fy/cx/cy` + 畸变 8 维 |
| `CameraParamReader.CameraParams` | 参数数据类，含 `source` 标记来源优先级 |

---

## 标定层（calibration）

| 类 | 职责 | 注意 |
|----|------|------|
| `CalibrationActivity` | 标定界面。独立 CameraX 实例，锁定当前 zoom 和分辨率，拍 15 张棋盘格 | 进入时校验登录态，未登录直接退出 |
| `CalibrationManager` | OpenCV 标定计算。强制 **Brown-Conrady 5 维**（`CALIB_FIX_K3`），输出 `fx/fy/cx/cy` + 8 维畸变 + RMS 误差 | 只填充前 5 维，后 3 维为 0 |
| `PatternDetector` | 棋盘格检测策略：快速 → 标准 → 增强。支持缩小图像二次检测、亚像素精化、超时保护 | 屏幕反光/弱光用 `detectionMode=2` |
| `CalibrationJsonImporter` | 启动时扫描外部目录的 `calib_*.json`，导入旧标定数据到 `CalibrationData` | 不覆盖已有手动标定 |
| `CalibrationUtil` | 去畸变工具类（预留）。提供 `undistort()` 和 `getParamsForRecording()` | 数据工程师参考用 |

---

## 检测与报警层（detection / alert）

| 类 | 职责 | 关键参数 |
|----|------|---------|
| `HandDetector` | ML Kit Pose Detection 分析器。检测手腕/手肘/肩膀任意一个可见即算有手 | `confidenceThreshold=0.1f`，`intervalMs=300` |
| `HandDetectionManager` | 检测器生命周期管理。录制时开，平时关，停止时彻底 `reset()` 报警倒计时 | 无 |
| `HandAlertManager` | 无手倒计时报警。15 秒（可配置）无手触发语音/蜂鸣 | `noHandTimeoutMs` 在 `CameraConfig` 调 |
| `VoicePromptManager` | 语音播报兜底。MediaPlayer → 蜂鸣 + 振动。带 800ms 防抖 | 无 TTS，纯音频资源或蜂鸣 |

---

## 录制层（recorder / recording）

| 类 | 职责 | 状态机 |
|----|------|--------|
| `VideoRecorder` | CameraX VideoCapture 封装。60 秒定时分段，文件命名，音频开关 | `IDLE → PREPARING → RECORDING → SPLITTING → STOPPING → COMPLETED` |
| `RecordingManager` | 录制状态机与元数据管理。检查存储、创建会话文件夹、启动 WakeLock | 同左 |
| `RecordingCoordinator` | UI 联动层。录制开始/停止时切换按钮状态、启停人手检测、绑定前台服务 | 无 |
| `RecordingForegroundService` | 前台服务保活。`PARTIAL_WAKE_LOCK` + 通知栏，防止熄屏被杀 | `START_STICKY` 被杀后自动重启 |

---

## 数据与上传层（data / upload）

| 类 | 职责 | 说明 |
|----|------|------|
| `FileRepository` | **所有文件 IO 唯一入口**。生成 `metadata.json`，追加 segment，结束录制时补全时间戳 | 超 100 段自动归档到 `metadata_history.json` |
| `StorageManager` | 存储路径多级降级：外部私有 → 内部私有 → 缓存。空间检测、格式化 | 异常安全，绝不抛崩溃 |
| `UploadManager` | 上传调度。逐个文件直传 OSS，不打包 zip，失败只影响单文件 | 支持后台前台服务上传 |
| `OssUploadStrategy` | 阿里云 OSS 上传实现。简单上传 + 分片上传，指数退避重试 | 分片 10MB，单片失败只重传该片 |
| `UploadRepository` | 策略注册中心。**目前只注册了 OSS**，后人加新网盘在这里 `register()` | 无 |
| `UploadRecordManager` | 上传记录本地 JSON 持久化，支持断点续传查询 | 自动清理已删除文件夹的记录 |
| `UploadRecord` | 上传任务数据类 | 无 |
| `UploadState` | 上传状态全局单例，供前台服务写入、UI 轮询读取 | 无 |
| `UploadUiHelper` | 上传对话框、日期文件夹选择、启动前台服务 | 无 |

---

## 服务层（service）

| 类 | 职责 |
|----|------|
| `RecordingForegroundService` | 录制保活前台服务。通知栏显示录制时长，WakeLock 防熄屏 |
| `UploadForegroundService` | 上传保活前台服务。通知栏显示第 N/M 个文件、总体进度百分比 |

---

## 工具层（utils）

| 类 | 职责 |
|----|------|
| `LogUtil` | 统一日志。带 `BuildConfig.DEBUG` 开关，支持 `[tag] [zoom] [resolution] msg` 格式 |
| `PermissionHelper` | 运行时权限申请：相机 + 录音 + 存储。被拒绝后弹窗引导去设置 |
| `StorageManager` | 同数据层，路径降级 + 空间检测 |

---

## UI 层

| 类 | 职责 | 注意 |
|----|------|------|
| `MainActivity` | 主界面生命周期、权限申请、相机启动、蓝牙快门拦截、登录拦截、内存监控显示 | 840 行，核心调度中心 |
| `MainUiManager` | 主界面所有 View 状态集中控制。按钮颜色、参数面板文字、变焦显示 | 录制时禁用变焦/标定/导出按钮 |
| `CalibrationActivity` | 标定专用界面。全屏预览、进度条、拍摄/计算/重置按钮 | 未登录直接 `finish()` |

---

## 资源文件（res）

| 文件 | 用途 | 修改点 |
|------|------|--------|
| `AndroidManifest.xml` | 权限、服务声明、横屏锁定、FileProvider | 加权限在这里 |
| `activity_main.xml` | 主界面：全屏 PreviewView + 右侧 180dp 信息面板 + 底部控制栏 | 调 UI 布局 |
| `activity_calibration.xml` | 标定界面：全屏预览 + 右侧控制面板 | 调按钮位置 |
| `strings.xml` | 所有界面文案、Toast、标定提示语 | 改提示文字 |
| `file_paths.xml` | FileProvider 路径配置 | 新增目录需加 `external-files-path` |
| `network_security_config.xml` | 允许明文 HTTP（已废弃，WiFi 删除后可删） | 一般不动 |

---

## 数据流向（端到端）
登录（账号绑定身份）
↓
标定（棋盘格 → OpenCV → fx/fy/cx/cy + k1~k3）
↓
录制（60s/段，mp4 + metadata.json）
↓
本地文件夹：RobotData/日期_姓名/序号_焦距/
↓
一键上传 OSS（逐个文件，不打包 zip）
↓
后端下载 → 读 metadata.json → 去畸变 → 机器人训练

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