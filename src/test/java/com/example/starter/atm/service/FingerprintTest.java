package com.example.starter.atm.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 业务指纹纯单元测试：指纹覆盖 endpoint 与全部业务字段；任一字段变化指纹即变化。
 */
class FingerprintTest {

    @Test
    void sameInputsSameFingerprint() {
        String a = ReplenishService.fingerprint("plan.prepare", "REQ-1", "ATM-001", "BOX-1", "100000");
        String b = ReplenishService.fingerprint("plan.prepare", "REQ-1", "ATM-001", "BOX-1", "100000");
        assertEquals(a, b);
        assertEquals(64, a.length(), "SHA-256 hex 长度应为 64");
    }

    @Test
    void differentBusinessFieldDifferentFingerprint() {
        String base = ReplenishService.fingerprint("plan.prepare", "REQ-1", "ATM-001", "BOX-1", "100000");
        assertNotEquals(base, ReplenishService.fingerprint("plan.prepare", "REQ-1", "ATM-001", "BOX-1", "100001"));
        assertNotEquals(base, ReplenishService.fingerprint("plan.prepare", "REQ-1", "ATM-002", "BOX-1", "100000"));
        // 同 requestId 跨端点也视为不同请求
        assertNotEquals(base, ReplenishService.fingerprint("plan.approve", "REQ-1", "ATM-001", "BOX-1", "100000"));
    }
}
