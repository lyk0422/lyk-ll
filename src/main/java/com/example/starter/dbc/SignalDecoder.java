package com.example.starter.dbc;

/**
 * 信号位级提取与数值换算工具（无状态纯逻辑）。
 *
 * <p>DBC 位编号：字节 b 内位 0 为该字节最低位（LSB），位号 = b*8 + k（k=0..7）。
 * <ul>
 *   <li>Intel（@1，小端）：startBit 为信号最低位，其余位沿线性位号 7→8→15→16 递增；</li>
 *   <li>Motorola（@0，大端，sawtooth）：startBit 为信号最高位，沿本字节位号递减，
 *       到达字节最低位后跳到下一更高字节的最高位（位号 +15，如 8→23）。</li>
 * </ul>
 */
public final class SignalDecoder {

    private SignalDecoder() {
    }

    /**
     * 按 DBC 起始位规则从负载中提取信号原始值（尚未做符号扩展，结果为非负）。
     *
     * @param payload 帧负载
     * @param signal  信号定义
     * @return 信号原始位构成的无符号值（调用方再按 length/signed 解释）
     */
    public static long extractRaw(byte[] payload, SignalDefinition signal) {
        int length = signal.length();
        long raw = 0L;
        if (signal.endian() == Endian.LITTLE) {
            // startBit 为最低位；第 i 位（i=0 为 LSB）位于线性位号 startBit + i。
            for (int i = 0; i < length; i++) {
                int bitPos = signal.startBit() + i;
                if (bitAt(payload, bitPos)) {
                    raw |= 1L << i;
                }
            }
        } else {
            // startBit 为最高位；沿 sawtooth 依次为次高位 … 最低位。
            int bitPos = signal.startBit();
            for (int i = 0; i < length; i++) {
                if (bitAt(payload, bitPos)) {
                    raw |= 1L << (length - 1 - i);
                }
                bitPos = nextBigEndianBit(bitPos);
            }
        }
        return raw;
    }

    /**
     * Motorola sawtooth：本字节内向下一位；到达字节最低位（位号 %8==0）后跳到下一字节最高位（+15）。
     */
    static int nextBigEndianBit(int bitPos) {
        if (bitPos % 8 == 0) {
            return bitPos + 15;
        }
        return bitPos - 1;
    }

    private static boolean bitAt(byte[] payload, int bitPos) {
        int byteIndex = bitPos / 8;
        int bitInByte = bitPos % 8;
        return ((payload[byteIndex] >> bitInByte) & 1) == 1;
    }

    /**
     * 按位长截取并做补码符号扩展。
     *
     * @param raw    提取出的原始值
     * @param length 信号位长（1-64）
     * @param signed 是否有符号
     * @return 解释后的整数原始值
     */
    public static long applySign(long raw, int length, boolean signed) {
        if (length >= 64) {
            // Java long 本身即 64 位补码，有符号/无符号均直接使用位模式。
            return raw;
        }
        long mask = (1L << length) - 1L;
        long value = raw & mask;
        if (signed && (value & (1L << (length - 1))) != 0L) {
            value |= ~mask;
        }
        return value;
    }

    /**
     * 物理值换算：physical = raw * factor + offset。
     */
    public static double toPhysical(long raw, double factor, double offset) {
        return raw * factor + offset;
    }
}
