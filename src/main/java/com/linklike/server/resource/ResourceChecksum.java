package com.linklike.server.resource;

/**
 * Hailstorm / Stardust 下载校验和 —— 移植自 Python 版 {@code resource_checksum.py}。
 *
 * <p>{@code XXH64(wire bytes, seed = wire bytes 长度)}。已对照 5.1.0 的 libblitz
 * 下载器与官方缓存资源验证过。输入必须是<b>最终线上下载字节</b>：压缩、加密之后的密文，
 * 或打了 bundle 前缀（{@code AB00}）之后的内容。这<b>不是</b>标签、路径与密钥派生用的 CRC64。
 *
 * <p>Python 版用纯 Python 实现以避免给部署包引入依赖；Java 版同样不依赖外部库，
 * 全部算术依赖 long 的自然溢出（等价于按 2^64 取模）。
 */
public final class ResourceChecksum {

    private ResourceChecksum() {
    }

    // XXH64 标准素数（十进制超过 Long.MAX_VALUE，用无符号十六进制字面量书写）。
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private static long round(long accumulator, long word) {
        return Long.rotateLeft(accumulator + word * P2, 31) * P1;
    }

    /**
     * 整份可下载文件的 XXH64（无符号），以文件长度作种子。
     *
     * @param data 压缩/加密之后、或已带 bundle 前缀的最终字节
     */
    public static long resourceChecksum(byte[] data) {
        int size = data.length;
        int pos = 0;
        long value;

        if (size >= 32) {
            long v1 = size + P1 + P2;
            long v2 = size + P2;
            long v3 = size;
            long v4 = size - P1;
            while (pos <= size - 32) {
                v1 = round(v1, readLongLE(data, pos));
                v2 = round(v2, readLongLE(data, pos + 8));
                v3 = round(v3, readLongLE(data, pos + 16));
                v4 = round(v4, readLongLE(data, pos + 24));
                pos += 32;
            }
            value = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            for (long accumulator : new long[] {v1, v2, v3, v4}) {
                value = (value ^ round(0L, accumulator)) * P1 + P4;
            }
        } else {
            value = size + P5;
        }

        value += size;
        while (pos <= size - 8) {
            value = Long.rotateLeft(value ^ round(0L, readLongLE(data, pos)), 27) * P1 + P4;
            pos += 8;
        }
        if (pos <= size - 4) {
            value = Long.rotateLeft(value ^ (readIntLE(data, pos) * P1), 23) * P2 + P3;
            pos += 4;
        }
        while (pos < size) {
            value = Long.rotateLeft(value ^ (((long) (data[pos] & 0xFF)) * P5), 11) * P1;
            pos++;
        }

        value ^= value >>> 33;
        value *= P2;
        value ^= value >>> 29;
        value *= P3;
        return value ^ (value >>> 32);
    }

    /** 无符号十六进制形式，便于写进目录 JSON。 */
    public static String toUnsignedHex(long value) {
        return Long.toUnsignedString(value, 16);
    }

    private static long readLongLE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24)
                | ((long) (data[offset + 4] & 0xFF) << 32)
                | ((long) (data[offset + 5] & 0xFF) << 40)
                | ((long) (data[offset + 6] & 0xFF) << 48)
                | ((long) (data[offset + 7] & 0xFF) << 56);
    }

    /** 读 4 字节小端无符号整数（对应 Python 的 {@code struct '<I'}）。 */
    private static long readIntLE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }
}
