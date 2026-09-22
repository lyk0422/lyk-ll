package com.example.starter.rosbag.util;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 负载与段级校验统一使用 CRC32，结果按无符号 32 位存入 long。
 */
public final class Checksums {

    private Checksums() {
    }

    /**
     * 计算文本负载的 CRC32 校验值。
     */
    public static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /**
     * 将一条消息并入段级滚动校验：依次纳入 topic、seq、纳秒时间戳、类型与负载校验值。
     */
    public static long foldMessage(long partial, String topic, long seq, long timestampNs,
                                   String typeId, long payloadChecksum) {
        CRC32 crc = new CRC32();
        crc.update(longToBytes(partial));
        crc.update(topic.getBytes(StandardCharsets.UTF_8));
        crc.update(longToBytes(seq));
        crc.update(longToBytes(timestampNs));
        crc.update(typeId.getBytes(StandardCharsets.UTF_8));
        crc.update(longToBytes(payloadChecksum));
        return crc.getValue();
    }

    private static byte[] longToBytes(long value) {
        byte[] bytes = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xffL);
            value >>= 8;
        }
        return bytes;
    }
}
