package com.example.cameraphonedata.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 账号配置中心 —— 从 AccountList 读取预置账号。
 *
 * 【安全改进】
 * 1. 对外暴露的 Account 对象不再包含 password 字段。
 * 2. 内部使用 char[] 存储密码，支持 Arrays.fill 清零。
 * 3. authenticate 接收 char[]，比对完成后由调用方清零输入。
 */
public class AccountConfig {

    /** 对外暴露：不含密码 */
    public static class Account {
        public final String username;
        public final String role;
        public final String displayName;

        public Account(String username, String role, String displayName) {
            this.username = username;
            this.role = role;
            this.displayName = displayName;
        }
    }

    /** 内部使用：含密码 */
    private static class InternalAccount {
        final String username;
        final char[] passwordChars;
        final String role;
        final String displayName;

        InternalAccount(String username, char[] passwordChars, String role, String displayName) {
            this.username = username;
            this.passwordChars = passwordChars;
            this.role = role;
            this.displayName = displayName;
        }
    }

    private static final List<InternalAccount> INTERNAL_ACCOUNTS = new ArrayList<>();
    static {
        for (AccountList.AccountEntry entry : AccountList.ACCOUNTS) {
            INTERNAL_ACCOUNTS.add(new InternalAccount(
                    entry.username,
                    entry.passwordChars,
                    entry.role,
                    entry.displayName
            ));
        }
    }

    public static List<Account> getAllAccounts() {
        List<Account> list = new ArrayList<>();
        for (InternalAccount ia : INTERNAL_ACCOUNTS) {
            list.add(new Account(ia.username, ia.role, ia.displayName));
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 验证账号密码。成功返回不含密码的 Account 对象，失败返回 null。
     * 安全：统一返回 null，不暴露是账号不存在还是密码错误。
     *
     * 【注意】调用方必须在比对完成后自行清零 inputPassword。
     */
    public static Account authenticate(String username, char[] inputPassword) {
        if (username == null || inputPassword == null) return null;
        String u = username.trim();
        for (InternalAccount ia : INTERNAL_ACCOUNTS) {
            if (ia.username.equals(u) && Arrays.equals(ia.passwordChars, inputPassword)) {
                return new Account(ia.username, ia.role, ia.displayName);
            }
        }
        return null;
    }

    public static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    /**
     * 【可选】清零内存中所有预置密码。
     * 调用后预置账号列表将失效，直到下次应用重启重新加载。
     */
    public static void clearAllPasswords() {
        for (InternalAccount ia : INTERNAL_ACCOUNTS) {
            Arrays.fill(ia.passwordChars, '\0');
        }
    }
}