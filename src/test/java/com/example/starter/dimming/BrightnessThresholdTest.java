package com.example.starter.dimming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则2：洞外亮度低于合成阈值立即提升一档并进入过渡曲线；恰等阈值不触发；过渡期间禁止下调。
 */
class BrightnessThresholdTest extends BaseIntegrationTest {

    @Test
    void brightnessEqualToThresholdDoesNotTrigger() throws Exception {
        var result = telemetry("TUN-A-01", "br-1", 500.0, 100);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("NONE", body(result).get("action").asText());
        assertEquals(3, state("TUN-A-01").get("currentLevel").asInt());
        assertTrue(state("TUN-A-01").get("transitionUntil").isNull());
        assertEquals(0, recordCount("TUN-A-01"));
    }

    @Test
    void brightnessBelowThresholdRaisesOneLevelImmediately() throws Exception {
        var result = telemetry("TUN-A-01", "br-2", 499.0, 100);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("BRIGHTNESS_RAISE", body(result).get("action").asText());
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
        assertFalse(state("TUN-A-01").get("transitionUntil").isNull());
        assertEquals(1, recordCount("TUN-A-01"));
        assertEquals("BRIGHTNESS", jdbc.queryForObject(
                "SELECT source FROM dimming_record WHERE circuit_code = 'TUN-A-01'",
                String.class));
    }

    @Test
    void brightnessRaiseBypassesStabilityWindow() throws Exception {
        dim("TUN-A-01", "br-3a", 4, "MANUAL");
        advanceMinutes(3);
        var result = telemetry("TUN-A-01", "br-3b", 100.0, 100);
        assertEquals("BRIGHTNESS_RAISE", body(result).get("action").asText());
        assertEquals(5, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void downDimForbiddenDuringTransition() throws Exception {
        telemetry("TUN-A-01", "br-4a", 100.0, 100);
        advanceMinutes(1);
        var rejected = dim("TUN-A-01", "br-4b", 2, "BRIGHTNESS");
        assertEquals(409, rejected.getResponse().getStatus());
        assertEquals("TRANSITION_IN_PROGRESS", body(rejected).get("code").asText());
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void downDimAllowedAfterTransitionAndStability() throws Exception {
        telemetry("TUN-A-01", "br-5a", 100.0, 100);
        advanceMinutes(10);
        var allowed = dim("TUN-A-01", "br-5b", 2, "MANUAL");
        assertEquals(200, allowed.getResponse().getStatus());
        assertEquals(2, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void raiseAtMaxLevelIsNoop() throws Exception {
        dim("TUN-A-01", "br-6a", 5, "MANUAL");
        advanceMinutes(10);
        var result = telemetry("TUN-A-01", "br-6b", 100.0, 100);
        assertEquals("NONE", body(result).get("action").asText());
        assertEquals(5, state("TUN-A-01").get("currentLevel").asInt());
    }
}
