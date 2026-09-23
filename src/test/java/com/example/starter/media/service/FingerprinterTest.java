package com.example.starter.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹单元测试：同参同指纹，参数或操作不同则指纹不同。
 */
class FingerprinterTest {

    private final Fingerprinter fingerprinter = new Fingerprinter(new ObjectMapper());

    @Test
    void sameOperationAndPayloadProduceSameFingerprint() {
        Map<String, Object> payload = Map.of("assetId", "asset-1", "versionNo", 2);
        assertEquals(fingerprinter.fingerprint("OP", payload), fingerprinter.fingerprint("OP", payload));
    }

    @Test
    void differentOperationOrPayloadProduceDifferentFingerprint() {
        Map<String, Object> payload = Map.of("assetId", "asset-1", "versionNo", 2);
        assertNotEquals(fingerprinter.fingerprint("OP_A", payload), fingerprinter.fingerprint("OP_B", payload));
        assertNotEquals(fingerprinter.fingerprint("OP", Map.of("versionNo", 2)),
                fingerprinter.fingerprint("OP", Map.of("versionNo", 3)));
    }
}
