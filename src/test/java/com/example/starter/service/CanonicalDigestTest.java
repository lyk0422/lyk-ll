package com.example.starter.service;

import com.example.starter.testsupport.TestDbcFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link CanonicalDigest} 单元测试：同参摘要一致、异参摘要不同。
 */
class CanonicalDigestTest {

    @Test
    void sameArgumentsProduceSameDigest() {
        var request1 = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 0, "rpm")
                .endMessage()
                .build();
        var request2 = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 0, "rpm")
                .endMessage()
                .build();
        assertEquals(CanonicalDigest.sha256(request1), CanonicalDigest.sha256(request2));
    }

    @Test
    void differentArgumentsProduceDifferentDigest() {
        var request1 = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.25, 0, "rpm")
                .endMessage()
                .build();
        var request2 = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "EngineMsg", 2)
                .signal("Rpm", "LITTLE_ENDIAN", 0, 16, false, 0.5, 0, "rpm")
                .endMessage()
                .build();
        assertNotEquals(CanonicalDigest.sha256(request1), CanonicalDigest.sha256(request2));
    }

    @Test
    void enumEntryOrderDoesNotChangeDigest() {
        java.util.Map<Long, String> tableA = new java.util.LinkedHashMap<>();
        tableA.put(0L, "Off");
        tableA.put(1L, "On");
        java.util.Map<Long, String> tableB = new java.util.LinkedHashMap<>();
        tableB.put(1L, "On");
        tableB.put(0L, "Off");

        var request1 = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "SwitchMsg", 1)
                .signal("Switch", "LITTLE_ENDIAN", 0, 2, false, 1, 0, "",
                        "NONE", null, tableA)
                .endMessage()
                .build();
        var request2 = TestDbcFactory.version("v1", "req-1")
                .message(0x100, "SwitchMsg", 1)
                .signal("Switch", "LITTLE_ENDIAN", 0, 2, false, 1, 0, "",
                        "NONE", null, tableB)
                .endMessage()
                .build();
        // JSON 对象键序经规范化排序后一致
        assertEquals(CanonicalDigest.sha256(request1), CanonicalDigest.sha256(request2));
    }
}
