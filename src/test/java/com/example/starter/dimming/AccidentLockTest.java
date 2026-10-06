package com.example.starter.dimming;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则4：事故告警锁定在事故模式，普通调光不得覆盖；解除须现场确认；锁定与解除同事务留痕。
 */
class AccidentLockTest extends BaseIntegrationTest {

    @Test
    void lockRaisesToAccidentLevelAndBlocksNormalDim() throws Exception {
        var locked = lockAccident("TUN-A-01", "ac-1a", "ALARM-001");
        assertEquals(200, locked.getResponse().getStatus());
        assertEquals(5, body(locked).get("currentLevel").asInt());
        assertTrue(state("TUN-A-01").get("locked").asBoolean());
        assertEquals(5, state("TUN-A-01").get("currentLevel").asInt());

        var dimRejected = dim("TUN-A-01", "ac-1b", 2, "MANUAL");
        assertEquals(409, dimRejected.getResponse().getStatus());
        assertEquals("CIRCUIT_LOCKED", body(dimRejected).get("code").asText());
        assertEquals(5, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void lockWritesAccidentRecordAndLogInSameTransaction() throws Exception {
        lockAccident("TUN-A-01", "ac-2", "ALARM-002");
        assertEquals("ACCIDENT", jdbc.queryForObject(
                "SELECT source FROM dimming_record WHERE circuit_code = 'TUN-A-01'", String.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT from_level FROM dimming_record WHERE circuit_code = 'TUN-A-01'", Integer.class));
        assertEquals(5, jdbc.queryForObject(
                "SELECT to_level FROM dimming_record WHERE circuit_code = 'TUN-A-01'", Integer.class));
        assertEquals(List.of("LOCK"), jdbc.query(
                "SELECT action FROM accident_lock_log WHERE circuit_code = 'TUN-A-01'",
                (rs, i) -> rs.getString(1)));
    }

    @Test
    void telemetrySuppressedWhileLocked() throws Exception {
        lockAccident("TUN-A-01", "ac-3a", "ALARM-003");
        var result = telemetry("TUN-A-01", "ac-3b", 100.0, 0);
        assertEquals("LOCKED_SKIP", body(result).get("action").asText());
        assertEquals(5, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void unlockRequiresOnSiteConfirmation() throws Exception {
        lockAccident("TUN-A-01", "ac-4a", "ALARM-004");
        var rejected = unlockAccident("TUN-A-01", "ac-4b", false);
        assertEquals(400, rejected.getResponse().getStatus());
        assertEquals("ONSITE_CONFIRM_REQUIRED", body(rejected).get("code").asText());
        assertTrue(state("TUN-A-01").get("locked").asBoolean());
    }

    @Test
    void unlockWithConfirmationReleasesLockAndLogs() throws Exception {
        lockAccident("TUN-A-01", "ac-5a", "ALARM-005");
        var unlocked = unlockAccident("TUN-A-01", "ac-5b", true);
        assertEquals(200, unlocked.getResponse().getStatus());
        assertFalse(state("TUN-A-01").get("locked").asBoolean());
        assertEquals(List.of("LOCK", "UNLOCK"), jdbc.query(
                "SELECT action FROM accident_lock_log WHERE circuit_code = 'TUN-A-01' ORDER BY id",
                (rs, i) -> rs.getString(1)));
        // 解除后普通调光恢复可用
        advanceMinutes(10);
        var dim = dim("TUN-A-01", "ac-5c", 3, "MANUAL");
        assertEquals(200, dim.getResponse().getStatus());
        assertEquals(3, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void doubleLockRejected() throws Exception {
        lockAccident("TUN-A-01", "ac-6a", "ALARM-006");
        var second = lockAccident("TUN-A-01", "ac-6b", "ALARM-007");
        assertEquals(409, second.getResponse().getStatus());
        assertEquals("ALREADY_LOCKED", body(second).get("code").asText());
    }

    @Test
    void unlockWithoutLockRejected() throws Exception {
        var result = unlockAccident("TUN-A-01", "ac-7", true);
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("NOT_LOCKED", body(result).get("code").asText());
    }
}
