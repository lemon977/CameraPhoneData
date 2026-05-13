package com.example.cameraphonedata.config;

import android.content.Context;
import java.io.File;
import com.example.cameraphonedata.utils.StorageManager;

/**
 * 数据采集配置
 * 职责：文件保存路径、文件夹命名规则、采集者身份标识
 * 【工程师必读】
 * 1. 本类为线程安全单例，通过 {@link #getInstance()} 获取。
 * 2. baseFolderName 修改后，旧数据仍在原目录，不会自动迁移。
 * 3. collectorName 会在登录后自动绑定，用于区分不同人采集的数据。
 * 【建议】
 * - baseFolderName: 保持 "RobotData"，后端解析脚本已适配。
 * - useDateFolder / useIndexFolder: 建议保持 true，天然防覆盖。
 * 【日期文件夹防覆盖设计】
 * 1. 格式：yyyyMMdd_HHmm_姓名（精确到分钟）。
 * 2. 同分钟内多次录制，由 getNextIndex 递增会话序号兜底（1_wide, 2_wide...）。
 * 3. 跨分钟自动新建文件夹，OSS 对象键前缀不同，天然防覆盖。
 */
public class DataConfig {
    private static volatile DataConfig instance;
    private static final Object LOCK = new Object();

    /** 根目录名。默认为 "RobotData"。不要包含特殊字符（/ \ : * ? " < > |）。 */
    public String baseFolderName = "RobotData";
    /** 是否使用日期子文件夹。true = 按日期归类，便于管理。 */
    public boolean useDateFolder = true;
    /** 是否使用序号子文件夹。true = 在日期文件夹内再按序号分目录。 */
    public boolean useIndexFolder = true;

    /** 当前任务名称。会写入 metadata.json，供后端识别。 */
    public String currentTaskName = "";

    /** 采集者姓名（用于区分不同人采集的数据，会附加到文件夹名）。 */
    public String collectorName = "";

    public static DataConfig getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new DataConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 获取下一个会话序号（异常安全）。
     * 扫描父目录下所有文件夹名，提取下划线前的数字，取最大+1。
     * @param parentDir 日期文件夹目录
     * @return 下一个可用序号（从 1 开始）
     */
    public int getNextIndex(File parentDir) {
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) return 1;
        try {
            int maxIndex = 0;
            File[] folders = parentDir.listFiles();
            if (folders != null) {
                for (File f : folders) {
                    if (f.isDirectory()) {
                        try {
                            String name = f.getName();
                            String numPart = name.contains("_") ? name.substring(0, name.indexOf("_")) : name;
                            int idx = Integer.parseInt(numPart);
                            if (idx > maxIndex) maxIndex = idx;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            // 防呆：序号过大可能表明文件系统异常
            if (maxIndex > 10000) {
                return 1;
            }
            return maxIndex + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 检查是否已有录制数据（异常安全）。
     * @param context 上下文
     * @return true = 存储目录下已有文件/文件夹
     */
    public boolean hasRecordedData(Context context) {
        if (context == null) return false;
        try {
            StorageManager sm = new StorageManager(context);
            File baseDir = sm.getBaseDir(baseFolderName);
            if (baseDir == null) return false;
            File[] files = baseDir.listFiles();
            return files != null && files.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
