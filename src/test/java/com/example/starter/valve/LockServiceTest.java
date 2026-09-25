package com.example.starter.valve;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.dto.LockConfirmRequest;
import com.example.starter.web.dto.LockConfirmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常处置测试：双人不同人确认生效、单人/同人不生效、复开与告警解除同事务、幂等。
 */
class LockServiceTest extends AbstractIntegrationTest {

    @Autowired
    private LockService lockService;
    @Autowired
    private ValveStateRepository valveStateRepository;

    private LockConfirmRequest request(String requestId, String action, String op1, String op2) {
        return new LockConfirmRequest("ST01", requestId, action, op1, op2);
    }

    private void forceLocked(int breachCount) {
        valveStateRepository.updateState(1L, true, true, breachCount,
                new java.math.BigDecimal("20.000"), null);
    }

    @Test
    void singleOperatorDoesNotTakeEffect() {
        LockConfirmResponse resp = lockService.confirm(request("LK-1", "LOCK", "U001", "U001"));
        assertFalse(resp.effective(), "两名签名同人等同单人操作，不生效");
        assertFalse(resp.injectionLocked(), "单人操作不得改变阀门状态");
        assertEquals(1, count("SELECT COUNT(*) FROM lock_order WHERE station_id = 1 AND status = 'PENDING'"),
                "无效确认仅留PENDING工单");
    }

    @Test
    void twoDifferentOperatorsLockEffectively() {
        LockConfirmResponse resp = lockService.confirm(request("LK-2", "LOCK", "U001", "U002"));
        assertTrue(resp.effective());
        assertTrue(resp.injectionLocked());
        assertTrue(resp.alarmActive());
        assertEquals(1, count("SELECT COUNT(*) FROM lock_order WHERE station_id = 1 AND action = 'LOCK' "
                + "AND status = 'CONFIRMED'"));
    }

    @Test
    void reopenReleasesValveAndClearsAlarmAndBreachCountAtomically() {
        forceLocked(2);
        LockConfirmResponse resp = lockService.confirm(request("LK-3", "REOPEN", "U003", "U004"));
        assertTrue(resp.effective());
        assertFalse(resp.injectionLocked(), "复开须解锁加注阀");
        assertFalse(resp.alarmActive(), "复开须同事务解除告警");

        ValveState state = valveStateRepository.find(1L);
        assertFalse(state.injectionLocked());
        assertFalse(state.alarmActive());
        assertEquals(0, state.breachCount(), "复开须把连续越限计数归零");
        assertEquals(1, count("SELECT COUNT(*) FROM lock_order WHERE station_id = 1 AND action = 'REOPEN' "
                + "AND status = 'CONFIRMED'"));
    }

    @Test
    void reopenBySingleOperatorKeepsValveLocked() {
        forceLocked(2);
        LockConfirmResponse resp = lockService.confirm(request("LK-4", "REOPEN", "U005", "U005"));
        assertFalse(resp.effective());
        assertTrue(resp.injectionLocked(), "单人复开不生效，阀门保持锁定");
        assertTrue(resp.alarmActive(), "单人复开不生效，告警保持");
        assertEquals(2, valveStateRepository.find(1L).breachCount());
    }

    @Test
    void replayKeepsFirstConfirmationOutcome() {
        LockConfirmRequest req = request("LK-5", "REOPEN", "U006", "U007");
        forceLocked(2);
        LockConfirmResponse first = lockService.confirm(req);
        assertTrue(first.effective());
        LockConfirmResponse replay = lockService.confirm(req);
        assertTrue(replay.idempotentReplay());
        assertFalse(replay.injectionLocked());
        assertEquals(1, count("SELECT COUNT(*) FROM lock_order WHERE station_id = 1 AND action = 'REOPEN'"),
                "重放不得重复生成工单");
    }

    @Test
    void sameKeyDifferentParamsReturns409AndNoStateChange() {
        forceLocked(2);
        lockService.confirm(request("LK-6", "REOPEN", "U008", "U009"));
        assertFalse(valveStateRepository.find(1L).injectionLocked());

        LockConfirmRequest tampered = request("LK-6", "LOCK", "U008", "U009");
        assertThrows(IdempotentConflictException.class, () -> lockService.confirm(tampered));
        assertFalse(valveStateRepository.find(1L).injectionLocked(), "同键异参409不得改变阀门状态");
        assertFalse(valveStateRepository.find(1L).alarmActive());
    }
}
