package com.example.starter.service;

import com.example.starter.domain.ByteOrder;

/**
 * CAN 信号位提取工具。
 *
 * <p>位编号遵循 DBC 约定：物理位 p 位于字节 p/8 的 p%8 位。</p>
 * <ul>
 *   <li>小端（Intel，{@link ByteOrder#LITTLE_ENDIAN}）：startBit 为信号最低位（LSB），
 *       位流向字节内高位，溢出后进入下一字节的 bit0。</li>
 *   <li>大端（Motorola，{@link ByteOrder#BIG_ENDIAN}）：startBit 为信号最高位（MSB），
 *       位流向字节内低位，到 bit0 后按锯齿进入下一字节的 bit7。</li>
 * </ul>
 */
public final class SignalDecoder {

    private SignalDecoder() {
    }

    /**
     * 按 DBC 起始位编号从负载中提取信号原始值。
     *
     * @param payload   帧负载
     * @param byteOrder 字节序
     * @param startBit  DBC 起始位（0..63）
     * @param bitLength 位长度（1..64）
     * @param signed    是否带符号；为 true 时按信号最高位做符号扩展
     * @return 提取并完成符号扩展后的原始值
     */
    public static long extractRaw(byte[] payload, ByteOrder byteOrder,
                                  int startBit, int bitLength, boolean signed) {
        long raw = byteOrder == ByteOrder.LITTLE_ENDIAN
                ? extractLittleEndian(payload, startBit, bitLength)
                : extractBigEndian(payload, startBit, bitLength);
        if (signed && bitLength < 64) {
            // 左移到符号位再算术右移，完成符号扩展
            raw <<= (Long.SIZE - bitLength);
            raw >>= (Long.SIZE - bitLength);
        }
        return raw;
    }

    private static long extractLittleEndian(byte[] payload, int startBit, int bitLength) {
        long raw = 0L;
        int byteIndex = startBit / 8;
        int bitInByte = startBit % 8;
        for (int i = 0; i < bitLength; i++) {
            if (isBitSet(payload, byteIndex, bitInByte)) {
                raw |= 1L << i;
            }
            bitInByte++;
            if (bitInByte == 8) {
                bitInByte = 0;
                byteIndex++;
            }
        }
        return raw;
    }

    private static long extractBigEndian(byte[] payload, int startBit, int bitLength) {
        long raw = 0L;
        int byteIndex = startBit / 8;
        int bitInByte = startBit % 8;
        for (int i = 0; i < bitLength; i++) {
            if (isBitSet(payload, byteIndex, bitInByte)) {
                raw |= 1L << (bitLength - 1 - i);
            }
            bitInByte--;
            if (bitInByte < 0) {
                // DBC Motorola 锯齿：本字节 bit0 之后进入下一字节 bit7
                bitInByte = 7;
                byteIndex++;
            }
        }
        return raw;
    }

    private static boolean isBitSet(byte[] payload, int byteIndex, int bitInByte) {
        return (payload[byteIndex] & (1 << bitInByte)) != 0;
    }
}
