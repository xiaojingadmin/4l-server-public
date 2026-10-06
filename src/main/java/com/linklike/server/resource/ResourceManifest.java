package com.linklike.server.resource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端「限定资源版本」（qualified resource version）的严格解析器 —— 移植自
 * Python 版 {@code resource_manifest.py}。
 *
 * <p>原注释特别说明：这<b>不是</b>真伪校验。一个格式合法的版本头并不能证明对应目录与
 * 资源真的可用。它的作用是把 {@code Rddddddd@Base64} 拆成校验和、随机种子与目录大小，
 * 并派生出目录的真实文件名与下载路径。
 *
 * <p>解析同时承担校验职责：裸版本号、占位符、长度超限、非规范 Base64 与畸形元数据
 * 一律抛 {@link IllegalArgumentException}，调用方据此在写任何响应字节之前失败。
 */
public final class ResourceManifest {

    /** 头部里的 {@code x-res-version} 值本身。 */
    private final String signature;

    /** {@code R2604100} 这样的简单版本号。 */
    private final String simpleVersion;

    private final long checksum;

    private final long seed;

    private final long size;

    /** CRC-64/ECMA-182 多项式，最高位优先（MSB-first）。 */
    private static final long CRC64_POLYNOMIAL = 0x42F0E1EBA9EA3693L;

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(R[0-9]{7})@([A-Za-z0-9+/]+={0,2})");

    /** RFC 4648 Base32 字母表（大写）。 */
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private ResourceManifest(String signature, String simpleVersion, long checksum, long seed, long size) {
        this.signature = signature;
        this.simpleVersion = simpleVersion;
        this.checksum = checksum;
        this.seed = seed;
        this.size = size;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 解析并校验限定资源版本；失败抛 {@link IllegalArgumentException}。 */
    public static ResourceManifest parse(String value) {
        if (value == null || value.length() > 128) {
            throw new IllegalArgumentException("resource_version must be a qualified resource version string");
        }
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("resource_version must have the form Rddddddd@Base64");
        }
        String simple = matcher.group(1);
        String encoded = matcher.group(2);

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource version Base64", e);
        }
        // 非规范 Base64（多余字符、错误填充）与不合理的清单长度都要拒绝。
        if (!Base64.getEncoder().encodeToString(raw).equals(encoded)
                || raw.length < 17 || raw.length > 26) {
            throw new IllegalArgumentException("Invalid resource manifest length or noncanonical Base64");
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        long checksum = buffer.getLong();
        long seed = buffer.getLong();
        long size = decodeVlq(Arrays.copyOfRange(raw, 16, raw.length));
        if (size == 0L) {
            throw new IllegalArgumentException("Resource catalog size must be positive");
        }
        return new ResourceManifest(value, simple, checksum, seed, size);
    }

    /**
     * 宽松解析：解析失败返回 {@code null}，用于「配置里可能没写版本」的场景。
     */
    public static ResourceManifest parseOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 派生值
    // ------------------------------------------------------------------

    /** 目录真实名称：md5(校验和 + simpleVersion 的 CRC64 + VLQ 长度) 的 Base32 小写。 */
    public String realName() {
        long labelCrc = crc64(simpleVersion.getBytes(StandardCharsets.US_ASCII));
        byte[] vlq = encodeVlq(size);
        byte[] payload = new byte[16 + vlq.length];
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.putLong(checksum);
        buffer.putLong(labelCrc);
        System.arraycopy(vlq, 0, payload, 16, vlq.length);
        return base32Unpadded(md5(payload)).toLowerCase(Locale.ROOT);
    }

    /** 目录下载路径：{@code /raw/ab/abcdef...}。 */
    public String catalogPath() {
        String name = realName();
        return "/raw/" + name.substring(0, 2) + "/" + name;
    }

    public String signature() {
        return signature;
    }

    public String simpleVersion() {
        return simpleVersion;
    }

    public long checksum() {
        return checksum;
    }

    public long seed() {
        return seed;
    }

    /** 目录字节数（无符号 64 位，实际取值都很小）。 */
    public long size() {
        return size;
    }

    // ------------------------------------------------------------------
    // CRC-64 / VLQ / Base32
    // ------------------------------------------------------------------

    /** CRC-64/ECMA-182，初值 0，不反转、不异或输出。 */
    public static long crc64(byte[] data) {
        long crc = 0L;
        for (byte value : data) {
            crc ^= ((long) (value & 0xFF)) << 56;
            for (int bit = 0; bit < 8; bit++) {
                // Python 判断的是移位前的最高位（crc >> 63）。
                boolean highBit = crc < 0L;
                crc <<= 1;
                if (highBit) {
                    crc ^= CRC64_POLYNOMIAL;
                }
            }
        }
        return crc;
    }

    /** 无符号 LEB128 编码；Java 的 long 按无符号语义处理。 */
    public static byte[] encodeVlq(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        while (Long.compareUnsigned(value, 128L) >= 0) {
            out.write((int) ((value & 127L) | 128L));
            value >>>= 7;
        }
        out.write((int) value);
        return out.toByteArray();
    }

    /**
     * 无符号 LEB128 解码，要求输入恰好是规范编码：不接受尾随字节、非最短表示或
     * 超出 uint64 的长度。
     */
    public static long decodeVlq(byte[] data) {
        long value = 0L;
        for (int index = 0; index < data.length; index++) {
            int current = data[index] & 0xFF;
            if (index >= 10 || (index == 9 && current > 1)) {
                throw new IllegalArgumentException("Resource size overflows uint64");
            }
            value |= ((long) (current & 127)) << (index * 7);
            if ((current & 128) == 0) {
                if (index != data.length - 1 || !Arrays.equals(encodeVlq(value), data)) {
                    throw new IllegalArgumentException(
                            "Resource size has trailing bytes or noncanonical VLQ");
                }
                return value;
            }
        }
        throw new IllegalArgumentException("Truncated resource size");
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 MD5", e);
        }
    }

    /** RFC 4648 Base32 编码，不带 {@code =} 填充（Python 版先编码再 rstrip('=')）。 */
    private static String base32Unpadded(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET[(buffer >>> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return out.toString();
    }
}
