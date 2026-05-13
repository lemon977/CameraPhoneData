package com.example.cameraphonedata.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.cameraphonedata.utils.LogUtil;

/**
 * 账号管理器 —— 登录状态加密持久化，不存储密码。
 * 【加密说明】
 * 1. 登录态（用户名、角色、显示名、登录时间）使用 EncryptedSharedPreferences 存储。
 * 2. 本类不接触密码，密码校验由 AccountConfig 负责。
 * 3. 退出登录后状态彻底清空。
 */
public class AccountManager {
    private static final String TAG = "AccountManager";
    private static final String PREFS_FILE = "account_encrypted_prefs";
    private static final String KEY_IS_LOGIN = "is_login";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_ROLE = "role";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_LOGIN_TIME = "login_time";

    private final SharedPreferences encryptedPrefs;

    public AccountManager(Context context) {
        this.encryptedPrefs = createEncryptedPrefs(context.getApplicationContext());
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
            android.util.Log.e(TAG, "加密存储初始化失败，回退明文", e);
            return context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE);
        }
    }

    /**
     * 写入登录态（密码已在外部校验通过，本方法不存密码）。
     */
    public void login(String username, String role, String displayName) {
        encryptedPrefs.edit()
                .putBoolean(KEY_IS_LOGIN, true)
                .putString(KEY_USERNAME, username)
                .putString(KEY_ROLE, role)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
                .apply();

        LogUtil.i(TAG, "登录成功: " + displayName + " (" + role + ")");
    }

    public void logout() {
        encryptedPrefs.edit().clear().apply();
        LogUtil.i(TAG, "已退出登录");
    }

    public boolean isLoggedIn() {
        return encryptedPrefs.getBoolean(KEY_IS_LOGIN, false);
    }

    public String getCurrentUser() {
        return encryptedPrefs.getString(KEY_USERNAME, "");
    }

    public String getCurrentRole() {
        return encryptedPrefs.getString(KEY_ROLE, "");
    }

    public String getCurrentDisplayName() {
        return encryptedPrefs.getString(KEY_DISPLAY_NAME, "unknown");
    }

    public long getLoginTime() {
        return encryptedPrefs.getLong(KEY_LOGIN_TIME, 0);
    }
}