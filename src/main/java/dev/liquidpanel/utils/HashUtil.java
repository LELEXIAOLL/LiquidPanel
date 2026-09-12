package dev.liquidpanel.utils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 摘要工具类。
 *
 * <p>面板密码使用 PBKDF2-HMAC-SHA256 加盐慢哈希存储，绝不落盘明文；
 * 校验一律走恒定时间比较，避免时序侧信道。
 */
public final class HashUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 迭代次数，越大越慢越安全。会写进 account.json，方便日后调整后仍能校验老密码 */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private HashUtil() {
    }

    /**
     * 生成新的随机盐（Base64）。
     */
    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return ENCODER.encodeToString(salt);
    }

    /**
     * 计算密码摘要（Base64）。
     */
    public static String hash(String password, String saltBase64, int iterations) {
        PBEKeySpec spec = null;
        try {
            spec = new PBEKeySpec(password.toCharArray(), DECODER.decode(saltBase64), iterations, KEY_LENGTH_BITS);
            byte[] key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            return ENCODER.encodeToString(key);
        } catch (Exception e) {
            throw new IllegalStateException("密码摘要计算失败", e);
        } finally {
            if (spec != null) {
                spec.clearPassword();
            }
        }
    }

    /**
     * 校验密码是否匹配。
     */
    public static boolean verify(String password, String saltBase64, int iterations, String expectedHash) {
        if (password == null || saltBase64 == null || expectedHash == null) {
            return false;
        }
        try {
            return constantTimeEquals(hash(password, saltBase64, iterations), expectedHash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 恒定时间字符串比较。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 十六进制摘要，用于给令牌做指纹等非密码场景。
     */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }
}
