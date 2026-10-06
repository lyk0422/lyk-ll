package com.example.starter.dimming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 规则3：车流量按5分钟滚动窗口更新，窗口内恰为0时降至保底亮度（档位1）。
 */
class TrafficZeroTest extends BaseIntegrationTest {

    @Test
    void zeroTrafficDropsToFloorLevel() throws Exception {
        var result = telemetry("TUN-A-01", "tf-1", 600.0, 0);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("TRAFFIC_FLOOR", body(result).get("action").asText());
        assertEquals(1, state("TUN-A-01").get("currentLevel").asInt());
        assertEquals("TRAFFIC", jdbc.queryForObject(
                "SELECT source FROM dimming_record WHERE circuit_code = 'TUN-A-01'",
                String.class));
    }

    @Test
    void nonZeroTrafficDoesNotTrigger() throws Exception {
        var result = telemetry("TUN-A-01", "tf-2", 600.0, 5);
        assertEquals("NONE", body(result).get("action").asText());
        assertEquals(3, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void zeroTrafficOnSeparateCircuitAlsoTriggers() throws Exception {
        telemetry("TUN-A-01", "tf-3a", 600.0, 0);
        // 第一次已为0触发降至保底；换回路验证窗口聚合
        telemetry("TUN-A-02", "tf-3b", 600.0, 0);
        assertEquals(1, state("TUN-A-02").get("currentLevel").asInt());
    }

    @Test
    void rollingWindowMixedSamplesDoNotTrigger() throws Exception {
        telemetry("TUN-A-01", "tf-4a", 600.0, 0);
        // 上一笔已触发保底，重置回路档位验证窗口内混合样本
        jdbc.update("UPDATE circuit SET current_level = 3, last_adjusted_at = NULL WHERE code = 'TUN-A-01'");
        advanceMinutes(3);
        var result = telemetry("TUN-A-01", "tf-4b", 600.0, 10);
        assertEquals("NONE", body(result).get("action").asText());
        assertEquals(3, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void samplesOlderThanFiveMinutesAreExcluded() throws Exception {
        telemetry("TUN-A-01", "tf-5a", 600.0, 8);
        advanceMinutes(6);
        // 窗口内只剩本次0流量样本，旧样本(8)已滚出窗口
        var result = telemetry("TUN-A-01", "tf-5b", 600.0, 0);
        assertEquals("TRAFFIC_FLOOR", body(result).get("action").asText());
        assertEquals(1, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void zeroTrafficDeferredWithinStabilityWindow() throws Exception {
        dim("TUN-A-01", "tf-6a", 4, "MANUAL");
        advanceMinutes(5);
        var result = telemetry("TUN-A-01", "tf-6b", 600.0, 0);
        assertEquals("NONE", body(result).get("action").asText());
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
        // 稳定期满后再报0流量即降至保底
        advanceMinutes(5);
        var later = telemetry("TUN-A-01", "tf-6c", 600.0, 0);
        assertEquals("TRAFFIC_FLOOR", body(later).get("action").asText());
        assertEquals(1, state("TUN-A-01").get("currentLevel").asInt());
    }
}
