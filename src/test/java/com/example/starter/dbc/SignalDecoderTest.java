package com.example.starter.dbc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SignalDecoder} 单元测试：覆盖 Intel 小端、Motorola 大端（sawtooth）、
 * 有符号补码、跨字节布局与物理值缩放。
 */
class SignalDecoderTest {

    private SignalDefinition sig(int start, int len, Endian endian, boolean signed) {
        return new SignalDefinition("s", start, len, endian, signed, 1.0, 0.0,
                MultiplexMode.NONE, null, Map.of());
    }

    @Test
    void littleEndianSpansTwoBytesLowNibbleFirst() {
        // b0=0x34, b1=0x12 -> 线性小端 0x1234
        byte[] payload = {0x34, 0x12};
        long raw = SignalDecoder.extractRaw(payload, sig(0, 16, Endian.LITTLE, false));
        assertEquals(0x1234L, raw);
    }

    @Test
    void littleEndianUnalignedCrossesByteBoundary() {
        // 起始位 4、长度 8：b0 的 bit4..7 为低半字节(0xA)，b1 的 bit8..11 为高半字节(0xD) -> 0xDA
        byte[] payload = {(byte) 0xAB, (byte) 0xCD};
        long raw = SignalDecoder.extractRaw(payload, sig(4, 8, Endian.LITTLE, false));
        assertEquals(0xDAL, raw);
    }

    @Test
    void bigEndianStartBitSevenIsFirstTwoBytes() {
        // Motorola start 7 len16：b0 为高字节，b1 为低字节
        byte[] payload = {0x12, 0x34};
        long raw = SignalDecoder.extractRaw(payload, sig(7, 16, Endian.BIG, false));
        assertEquals(0x1234L, raw);
    }

    @Test
    void bigEndianSawtoothJumpsFromBitZeroToBitFifteen() {
        // start 7 len16：sawtooth 顺序 7..0, 15..8
        assertEquals(15, SignalDecoder.nextBigEndianBit(0));
        // start 23 len8 覆盖 b2 全部位
        byte[] payload = {0, 0, (byte) 0xAB};
        long raw = SignalDecoder.extractRaw(payload, sig(23, 8, Endian.BIG, false));
        assertEquals(0xABL, raw);
    }

    @Test
    void bigEndianSignedNegativeValueIsSignExtended() {
        // 12 位 Motorola，start 7：取 b0 全部 8 位 + b1 高 4 位；F0 09 -> 0xF00，符号扩展为 -256
        byte[] payload = {(byte) 0xF0, 0x09};
        long raw = SignalDecoder.extractRaw(payload, sig(7, 12, Endian.BIG, true));
        long signed = SignalDecoder.applySign(raw, 12, true);
        assertEquals(-256L, signed);
    }

    @Test
    void littleEndianSignedPositiveStaysPositive() {
        byte[] payload = {0x05, 0x00};
        long raw = SignalDecoder.extractRaw(payload, sig(0, 8, Endian.LITTLE, true));
        assertEquals(5L, SignalDecoder.applySign(raw, 8, true));
    }

    @Test
    void littleEndianSignedNegativeMinusOne() {
        byte[] payload = {(byte) 0xFF};
        long raw = SignalDecoder.extractRaw(payload, sig(0, 8, Endian.LITTLE, true));
        assertEquals(-1L, SignalDecoder.applySign(raw, 8, true));
    }

    @Test
    void physicalValueAppliesFactorAndOffset() {
        // 100 * 0.1 + (-50) = -40
        assertEquals(-40.0d, SignalDecoder.toPhysical(100L, 0.1d, -50.0d), 1e-9);
    }
}
