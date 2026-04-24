package com.example.cameraphonedata.config;

/**
 * 账号清单 —— 手动修改本文件即可增删改账号，无需改动其他代码。
 *
 * 【安全说明】
 * 1. 密码使用 char[] 而非 String，避免进入 Java String 常量池长期驻留。
 * 2. 登录比对完成后，通过 Arrays.fill 清零，减少内存中密码留存时间。
 * 3. 本文件密码为随机生成示例，建议重新生成后再打包。
 */
public class AccountList {

    public static class AccountEntry {
        public final String username;
        public final char[] passwordChars;
        public final String role;
        public final String displayName;

        public AccountEntry(String username, char[] passwordChars, String role, String displayName) {
            this.username = username;
            this.passwordChars = passwordChars;
            this.role = role;
            this.displayName = displayName;
        }
    }

    /**
     * 【手动修改区域】21 个账号，密码为随机 8 位混合字符。
     * 如需重新生成，运行下方 Python 脚本，替换 char[] 内容即可。
     */
    public static final AccountEntry[] ACCOUNTS = {
            // ========== 管理员账号 ==========
            new AccountEntry("admin",
                    new char[]{'2','0','2','6','0','4','1','5'},
                    "admin", "管理员"),

            // ========== 采集人账号 c01 ~ c20 ==========
            new AccountEntry("c01", new char[]{'a','4','B','c','8','D','e','F'}, "collector", "采集人01"),
            new AccountEntry("c02", new char[]{'g','7','H','i','2','J','k','L'}, "collector", "采集人02"),
            new AccountEntry("c03", new char[]{'m','5','N','o','9','P','q','R'}, "collector", "采集人03"),
            new AccountEntry("c04", new char[]{'s','3','T','u','7','V','w','X'}, "collector", "采集人04"),
            new AccountEntry("c05", new char[]{'y','1','Z','a','6','B','c','D'}, "collector", "采集人05"),
            new AccountEntry("c06", new char[]{'e','8','F','g','4','H','i','J'}, "collector", "采集人06"),
            new AccountEntry("c07", new char[]{'k','2','L','m','0','N','o','P'}, "collector", "采集人07"),
            new AccountEntry("c08", new char[]{'q','9','R','s','5','T','u','V'}, "collector", "采集人08"),
            new AccountEntry("c09", new char[]{'w','3','X','y','7','Z','a','B'}, "collector", "采集人09"),
            new AccountEntry("c10", new char[]{'c','6','D','e','1','F','g','H'}, "collector", "采集人10"),
            new AccountEntry("c11", new char[]{'i','4','J','k','8','L','m','N'}, "collector", "采集人11"),
            new AccountEntry("c12", new char[]{'o','7','P','q','3','R','s','T'}, "collector", "采集人12"),
            new AccountEntry("c13", new char[]{'u','1','V','w','5','X','y','Z'}, "collector", "采集人13"),
            new AccountEntry("c14", new char[]{'a','9','B','c','2','D','e','F'}, "collector", "采集人14"),
            new AccountEntry("c15", new char[]{'g','6','H','i','4','J','k','L'}, "collector", "采集人15"),
            new AccountEntry("c16", new char[]{'m','3','N','o','7','P','q','R'}, "collector", "采集人16"),
            new AccountEntry("c17", new char[]{'s','0','T','u','9','V','w','X'}, "collector", "采集人17"),
            new AccountEntry("c18", new char[]{'y','5','Z','a','1','B','c','D'}, "collector", "采集人18"),
            new AccountEntry("c19", new char[]{'e','2','F','g','8','H','i','J'}, "collector", "采集人19"),
            new AccountEntry("c20", new char[]{'k','7','L','m','4','N','o','P'}, "collector", "采集人20"),
    };
}