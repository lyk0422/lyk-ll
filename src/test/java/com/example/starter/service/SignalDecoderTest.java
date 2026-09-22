package com.example.starter.service;

import com.example.starter.domain.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SignalDecoder} 单元测试：DBC Intel/Motorola 位序与符号扩展。
 */
class SignalDecoderTest {

    @Test
    void littleEndianSingleByteAligned() {
        byte[] payload = {(byte) 0xA5};
        assertEquals(165L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 0, 8, false));
    }

    @Test
    void littleEndianPartialNibble() {
        // 低 4 位 0100 = 4
        byte[] payload = {(byte) 0xB4};
        assertEquals(4L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 0, 4, false));
        // 高 4 位 1011 = 11
        assertEquals(11L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 4, 4, false));
    }

    @Test
    void littleEndianTwoByteAligned() {
        // Intel 16 位：低字节在前
        byte[] payload = {0x34, 0x12};
        assertEquals(0x1234L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 0, 16, false));
    }

    @Test
    void littleEndianUnalignedAcrossByte() {
        // startBit=4, len=12：byte0 高半字节 + byte1 全部
        byte[] payload = {(byte) 0xFF, 0x01};
        // byte0 位4..7 给出低 4 位 0xF；byte1=0x01 仅位0 置位 → 0x1F
        assertEquals(0x1FL, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 4, 12, false));
    }

    @Test
    void bigEndianSingleByteAligned() {
        byte[] payload = {(byte) 0xA5};
        assertEquals(165L, SignalDecoder.extractRaw(payload, ByteOrder.BIG_ENDIAN, 7, 8, false));
    }

    @Test
    void bigEndianPartialNibble() {
        // startBit=7, len=4 读取位 7..4 = 1010 = 10
        byte[] payload = {(byte) 0xA5};
        assertEquals(10L, SignalDecoder.extractRaw(payload, ByteOrder.BIG_ENDIAN, 7, 4, false));
    }

    @Test
    void bigEndianTwoByteAligned() {
        // Motorola 16 位：startBit=7，先读完 byte0 再锯齿到 byte1 的 bit7
        byte[] payload = {0x12, 0x34};
        assertEquals(0x1234L, SignalDecoder.extractRaw(payload, ByteOrder.BIG_ENDIAN, 7, 16, false));
    }

    @Test
    void bigEndianUnalignedAcrossByte() {
        // startBit=15（byte1 的 bit7），len=12：byte1 全字节 + byte2 高半字节
        byte[] payload = {0x00, (byte) 0xAB, (byte) 0xCD};
        assertEquals(0xABCL, SignalDecoder.extractRaw(payload, ByteOrder.BIG_ENDIAN, 15, 12, false));
    }

    @Test
    void signedLittleEndianNibble() {
        // 低 4 位 1111 → -1
        byte[] payload = {0x0F};
        assertEquals(-1L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 0, 4, true));
    }

    @Test
    void signedLittleEndianTwoBytes() {
        // Intel 0xFF00 → -256
        byte[] payload = {0x00, (byte) 0xFF};
        assertEquals(-256L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 0, 16, true));
    }

    @Test
    void signedBigEndian() {
        // Motorola 16 位 0xFF00 → -256
        byte[] payload = {(byte) 0xFF, 0x00};
        assertEquals(-256L, SignalDecoder.extractRaw(payload, ByteOrder.BIG_ENDIAN, 7, 16, true));
    }

    @Test
    void unsignedDoesNotSignExtend() {
        byte[] payload = {(byte) 0xFF};
        assertEquals(255L, SignalDecoder.extractRaw(payload, ByteOrder.LITTLE_ENDIAN, 0, 8, false));
    }
}
