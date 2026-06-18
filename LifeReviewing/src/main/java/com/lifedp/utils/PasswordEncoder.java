package com.lifedp.utils;



import cn.hutool.crypto.digest.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码编码器。BCrypt + 兼容旧 MD5 加盐格式（登录时自动升级）。
 */
public class PasswordEncoder {

    /** BCrypt cost factor（2^10 = 1024 轮迭代） */
    private static final int BCRYPT_ROUNDS = 10;

    private static final char OLD_SEPARATOR = '@';
    private static final int OLD_SALT_BYTES = 20;

    /* ==================== 公开 API ==================== */

    /** BCrypt 编码 */
    public static String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    /** 验密。兼容旧 MD5 格式和新 BCrypt 格式。 */
    public static boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        if (isLegacy(encodedPassword)) {
            return matchLegacy(encodedPassword, rawPassword);
        }
        try {
            return BCrypt.checkpw(rawPassword, encodedPassword);
        } catch (Exception e) {
            // BCrypt.checkpw 对格式不正确的密文抛异常
            return false;
        }
    }

    /** 是否需要从旧格式迁移到 BCrypt */
    public static boolean needsUpgrade(String encodedPassword) {
        return isLegacy(encodedPassword);
    }

    /* ==================== 内部 ==================== */

    private static boolean isLegacy(String s) {
        return s != null && s.indexOf(OLD_SEPARATOR) >= 0;
    }

    private static boolean matchLegacy(String encoded, String raw) {
        int sep = encoded.indexOf(OLD_SEPARATOR);
        if (sep <= 0) return false;
        String salt = encoded.substring(0, sep);
        String expected = salt + OLD_SEPARATOR + CryptoUtils.md5Hex((raw + salt).getBytes(StandardCharsets.UTF_8));
        return CryptoUtils.constantTimeEquals(encoded, expected);
    }

    /* ==================== 仅用于测试数据初始化 ==================== */

    /** @deprecated 新代码请用 {@link #encode} */
    @Deprecated
    public static String encodeLegacy(String raw) {
        byte[] b = new byte[OLD_SALT_BYTES];
        new SecureRandom().nextBytes(b);
        String salt = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return salt + OLD_SEPARATOR + CryptoUtils.md5Hex((raw + salt).getBytes(StandardCharsets.UTF_8));
    }
}
