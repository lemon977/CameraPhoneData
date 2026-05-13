package com.example.cameraphonedata.config;

/**
 * 文件名常量 —— 集中管理所有数据文件的名称，避免硬编码分散在多处导致不一致。
 */
public final class FileNames {
    private FileNames() {}

    public static final String METADATA = "metadata.json";
    public static final String METADATA_HISTORY = "metadata_history.json";
    public static final String METADATA_BACKUP = "metadata.bak.json";

    public static final String IMU_CONFIG = "imu_config.json";
    public static final String IMU_DATA = "imu_data.jsonl";

    public static final String VIDEO_EXTENSION = ".mp4";
    public static final String TEMP_EXTENSION = ".tmp";
}
