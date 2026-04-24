package com.example.cameraphonedata.config;

import android.content.Context;
import java.io.File;
import com.example.cameraphonedata.utils.StorageManager;

/**
 * 数据采集配置
 * 职责：文件保存路径、文件夹命名规则、采集者身份标识
 *
 * 【日期文件夹防覆盖设计】
 * 1. 格式：yyyyMMdd_HHmm_姓名（精确到分钟）。
 * 2. 同分钟内多次录制，由 getNextIndex 递增会话序号兜底（1_1.0x, 2_1.0x...）。
 * 3. 跨分钟自动新建文件夹，OSS 对象键前缀不同，天然防覆盖。
 */
public class DataConfig {
    private static DataConfig instance;

    /** 根目录名。 */
    public String baseFolderName = "RobotData";
    /** 是否使用日期子文件夹。 */
    public boolean useDateFolder = true;
    /** 是否使用序号子文件夹。 */
    public boolean useIndexFolder = true;

    public String currentTaskName = "";

    /** 采集者姓名（用于区分不同人采集的数据，会附加到文件夹名）。 */
    public String collectorName = "";

    public static DataConfig getInstance() {
        if (instance == null) instance = new DataConfig();
        return instance;
    }

    /**
     * 获取下一个会话序号（异常安全）。
     * 扫描父目录下所有文件夹名，提取下划线前的数字，取最大+1。
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
                            maxIndex = Math.max(maxIndex, idx);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            return maxIndex + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 检查是否已有录制数据（异常安全）。
     */
    public boolean hasRecordedData(Context context) {
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