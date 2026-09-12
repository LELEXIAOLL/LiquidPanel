package dev.liquidpanel.utils;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 随机值工具类。
 *
 * <p>面板的密码、登录令牌都属于安全敏感数据，一律使用 {@link SecureRandom}，
 * 不要用 {@link java.util.Random}。
 */
public final class RandomUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] LOWER = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] DIGIT = "0123456789".toCharArray();
    private static final char[] ALPHANUMERIC =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private RandomUtil() {
    }

    /**
     * 生成随机密码，保证小写字母、大写字母、数字都至少出现一次。
     *
     * @param length 长度，至少为 3
     */
    public static String password(int length) {
        if (length < 3) {
            throw new IllegalArgumentException("密码长度至少为 3");
        }
        char[] chars = new char[length];
        chars[0] = LOWER[RANDOM.nextInt(LOWER.length)];
        chars[1] = UPPER[RANDOM.nextInt(UPPER.length)];
        chars[2] = DIGIT[RANDOM.nextInt(DIGIT.length)];
        for (int i = 3; i < length; i++) {
            chars[i] = ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)];
        }
        // 打乱，避免固定以「小写 + 大写 + 数字」开头
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }

    /**
     * 生成 URL 安全的随机令牌。
     *
     * @param byteLength 随机字节数，32 表示 256 位
     */
    public static String token(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 从指定字符集里生成随机字符串。
     */
    public static String string(int length, String alphabet) {
        if (alphabet == null || alphabet.isEmpty()) {
            throw new IllegalArgumentException("字符集不能为空");
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = alphabet.charAt(RANDOM.nextInt(alphabet.length()));
        }
        return new String(chars);
    }
}
