package com.example.cameraphonedata.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.cameraphonedata.BuildConfig;

/**
 * OSS 密钥本地加密存储
 * 修复：每次构造强制从 BuildConfig 刷新，避免 local.properties 修改后不生效
 */
public class OssKeyStore {

    private static final String PREFS_FILE = "oss_encrypted_prefs";
    private static final String KEY_AK = "oss_ak";
    private static final String KEY_SK = "oss_sk";
    private static final String KEY_BUCKET = "oss_bucket";
    private static final String KEY_ENDPOINT = "oss_endpoint";

    private final SharedPreferences encryptedPrefs;

    public OssKeyStore(Context context) {
        this.encryptedPrefs = createEncryptedPrefs(context);
        initFromBuildConfig();
    }

    private SharedPreferences createEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            android.util.Log.e("OssKeyStore", "加密存储初始化失败，回退明文", e);
            return context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE);
        }
    }

    private void initFromBuildConfig() {
        String ak = BuildConfig.OSS_AK;
        String sk = BuildConfig.OSS_SK;
        String bucket = BuildConfig.OSS_BUCKET;
        String endpoint = BuildConfig.OSS_ENDPOINT;

        android.util.Log.i("OssKeyStore", "强制刷新 OSS 配置: bucket=" + bucket + ", endpoint=" + endpoint);

        SharedPreferences.Editor editor = encryptedPrefs.edit();
        if (ak != null && !ak.isEmpty()) editor.putString(KEY_AK, ak);
        if (sk != null && !sk.isEmpty()) editor.putString(KEY_SK, sk);
        if (bucket != null && !bucket.isEmpty()) editor.putString(KEY_BUCKET, bucket);
        if (endpoint != null && !endpoint.isEmpty()) editor.putString(KEY_ENDPOINT, endpoint);
        editor.apply();
    }

    public String getAk() {
        return encryptedPrefs.getString(KEY_AK, "");
    }

    public String getSk() {
        return encryptedPrefs.getString(KEY_SK, "");
    }

    public String getBucket() {
        return encryptedPrefs.getString(KEY_BUCKET, "");
    }

    public String getEndpoint() {
        return encryptedPrefs.getString(KEY_ENDPOINT, "");
    }

    public boolean isConfigured() {
        return !getAk().isEmpty()
                && !getSk().isEmpty()
                && !getBucket().isEmpty()
                && !getEndpoint().isEmpty();
    }

    public void saveConfig(String ak, String sk, String bucket, String endpoint) {
        encryptedPrefs.edit()
                .putString(KEY_AK, ak)
                .putString(KEY_SK, sk)
                .putString(KEY_BUCKET, bucket)
                .putString(KEY_ENDPOINT, endpoint)
                .apply();
    }
}