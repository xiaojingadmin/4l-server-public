package com.linklike.server.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 数据连携码的哈希与校验 —— 对应 Python 版 {@code usercenter.hash_code} /
 * {@code usercenter.verify_code}。
 *
 * <p>存储格式与 Python 版逐字兼容：{@code pbkdf2:<迭代次数>:<盐>:<小写 hex 摘要>}，
 * 算法 PBKDF2-HMAC-SHA256，摘要 32 字节（64 位 hex），盐是 16 字节随机数的 hex 文本
 * （Python 用 {@code salt.encode('ascii')}，即把这串 hex 字符本身当作盐的字节）。
 *
 * <p>两种实现必须互通：Python 版写入的账号要在 Java 版里校验通过，反之亦然。因此这里
 * 不复用 Spring 的 {@code PasswordEncoder}（它的格式与 Python 版不同）。
 */
public final class ConnectCode {

    /** 连携码长度限制（用户中心生成 / 校验用）。 */
    public static final int CODE_MIN_LENGTH = 8;
    public static final int CODE_MAX_LENGTH = 64;

    /** 与 Python 版一致的迭代次数，改动会让已存的连携码全部失效。 */
    public static final int PBKDF2_ROUNDS = 200_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ConnectCode() {
    }

    /** 生成 {@code pbkdf2:<rounds>:<salt>:<digest>} 形态的存储值。 */
    public static String hash(String code) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltText = HexFormat.of().formatHex(salt);
        String digest = derive(code, saltText, PBKDF2_ROUNDS);
        return "pbkdf2:" + PBKDF2_ROUNDS + ":" + saltText + ":" + digest;
    }

    /**
     * 校验连携码；存储值格式非法、参数缺失或不匹配都返回 false，从不抛异常。
     *
     * <p>与 Python 版一样用常量时间比较，避免按前缀长度泄露信息。
     */
    public static boolean verify(Object stored, Object code) {
        if (!(stored instanceof String storedText) || !(code instanceof String codeText)
                || codeText.isEmpty()) {
            return false;
        }
        String[] parts = storedText.split(":", -1);
        if (parts.length != 4 || !"pbkdf2".equals(parts[0]) || !parts[1].chars()
                .allMatch(Character::isDigit)) {
            return false;
        }
        int rounds;
        try {
            rounds = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (rounds <= 0) {
            return false;
        }
        String digest;
        try {
            digest = derive(codeText, parts[2], rounds);
        } catch (IllegalStateException e) {
            return false;
        }
        return MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
                parts[3].getBytes(StandardCharsets.US_ASCII));
    }

    /** 连携码是否合法：8-64 个可见字符，且首尾没有空白（用户中心的校验规则）。 */
    public static boolean valid(Object code) {
        if (!(code instanceof String text)) {
            return false;
        }
        return text.length() >= CODE_MIN_LENGTH && text.length() <= CODE_MAX_LENGTH
                && text.equals(text.strip())
                && printable(text);
    }

    /**
     * 对应 Python 的 {@code str.isprintable()}：只要有一个字符属于控制、格式、代理、私用、
     * 未分配、行/段分隔或非 ASCII 空格的类别就返回 false。分开实现是因为 Java 的
     * {@code Character} 没有等价的 {@code isPrintable}，而这条规则直接决定用户中心
     * 「需为 8-64 个可见字符」的报错时机。
     */
    private static boolean printable(String text) {
        return text.codePoints().allMatch(codePoint -> codePoint == ' '
                || switch (Character.getType(codePoint)) {
                    case Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                         Character.PRIVATE_USE, Character.UNASSIGNED, Character.LINE_SEPARATOR,
                         Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
                    default -> true;
                });
    }

    private static String derive(String code, String saltText, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(),
                saltText.getBytes(StandardCharsets.US_ASCII), rounds, 256);
        try {
            byte[] digest = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("无法计算连携码摘要", e);
        } finally {
            spec.clearPassword();
        }
    }
}
