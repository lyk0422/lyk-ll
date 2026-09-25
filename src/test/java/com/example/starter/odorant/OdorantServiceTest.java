package com.example.starter.odorant;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.reading.LateEventRepository;
import com.example.starter.valve.ValveStateRepository;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.OdorantDetectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行监测测试：浓度恰区间边界、恰连续 2 次锁阀、合格归零、同键重放/异参 409、封存后迟到。
 */
class OdorantServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime NOON = LocalDate.of(2026, 9, 25).atTime(12, 0);

    @Autowired
    private OdorantService odorantService;
    @Autowired
    private ValveStateRepository valveStateRepository;
    @Autowired
    private LateEventRepository lateEventRepository;

    private OdorantDetectRequest request(String requestId, LocalDateTime time, String concentration) {
        return new OdorantDetectRequest("ST01", requestId, time,
                new BigDecimal("100"), new BigDecimal(concentration));
    }

    @Test
    void boundaryConcentrationsAreQualified() {
        OdorantDetectResponse lower = odorantService.detect(request("OD-B1", NOON, "16.000"));
        assertTrue(lower.qualified(), "恰下限16.000必须合格");
        assertEquals(0, lower.breachCount());
        assertFalse(lower.injectionLocked());
        assertEquals(0, new BigDecimal("16.000").compareTo(lower.lowerBoundMgM3()));

        OdorantDetectResponse upper = odorantService.detect(request("OD-B2", NOON.plusMinutes(1), "24.000"));
        assertTrue(upper.qualified(), "恰上限24.000必须合格");
        assertEquals(0, upper.breachCount());
    }

    @Test
    void exactlyTwoConsecutiveBreachesTriggerAlarmAndLock() {
        OdorantDetectResponse first = odorantService.detect(request("OD-L1", NOON, "15.999"));
        assertFalse(first.qualified());
        assertEquals(1, first.breachCount(), "第1次越限计数为1");
        assertFalse(first.injectionLocked(), "第1次越限不得锁阀");
        assertFalse(first.alarmActive(), "第1次越限不得告警");

        OdorantDetectResponse second = odorantService.detect(request("OD-L2", NOON.plusMinutes(1), "24.001"));
        assertFalse(second.qualified());
        assertEquals(2, second.breachCount(), "恰连续第2次越限计数为2");
        assertTrue(second.injectionLocked(), "恰连续2次越限必须锁定加注阀");
        assertTrue(second.alarmActive(), "恰连续2次越限必须告警");

        assertEquals(1, count("SELECT COUNT(*) FROM lock_order WHERE station_id = 1 AND action = 'LOCK' "
                + "AND status = 'PENDING' AND trigger_request_id = 'OD-L2'"),
                "第2次越限须生成待双人确认触发锁阀单");
        assertTrue(valveStateRepository.find(1L).injectionLocked());
    }

    @Test
    void qualifiedReadingResetsBreachCount() {
        odorantService.detect(request("OD-R1", NOON, "10.000"));
        odorantService.detect(request("OD-R2", NOON.plusMinutes(1), "20.000"));
        OdorantDetectResponse third = odorantService.detect(request("OD-R3", NOON.plusMinutes(2), "10.000"));
        assertEquals(1, third.breachCount(), "合格后连续计数归零，再次越限只计第1次");
        assertFalse(third.injectionLocked(), "归零后的单次越限不得锁阀");
    }

    @Test
    void sameRequestSameParamsReturnsFirstResultWithoutReProcessing() {
        OdorantDetectRequest req = request("OD-RP1", NOON, "15.000");
        OdorantDetectResponse first = odorantService.detect(req);
        assertEquals(1, first.breachCount());
        assertFalse(first.idempotentReplay());

        OdorantDetectResponse replay = odorantService.detect(req);
        assertTrue(replay.idempotentReplay(), "同键同参必须标记为重放");
        assertEquals(1, replay.breachCount(), "重放不得再次推进连续计数");
        assertEquals(1, count("SELECT COUNT(*) FROM odorant_reading WHERE request_id = 'OD-RP1'"),
                "重放不得重复落库读数");
    }

    @Test
    void sameRequestDifferentParamsReturns409AndKeepsValveState() {
        odorantService.detect(request("OD-CF1", NOON, "15.000"));
        assertEquals(1, valveStateRepository.find(1L).breachCount());

        OdorantDetectRequest tampered = request("OD-CF1", NOON, "24.500");
        IdempotentConflictException ex = assertThrows(IdempotentConflictException.class,
                () -> odorantService.detect(tampered));
        assertTrue(ex.getMessage().contains("OD-CF1"));

        assertEquals(1, valveStateRepository.find(1L).breachCount(), "同键异参409不得改变阀门/计数状态");
        assertFalse(valveStateRepository.find(1L).injectionLocked());
    }
}
