package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.TankAdjustRequest;
import com.example.starter.web.dto.TankLedgerEntryView;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐登记与配置调整：主流程、安全储备/容量边界、版本乐观锁、
 * 数值等价幂等重放与同键异参 409。
 */
class TankServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 25).atTime(10, 0);

    @Autowired
    private TankService tankService;

    private TankResponse register(String requestId, String capacity, String reserve, String balance) {
        return tankService.register(new TankRegisterRequest("ST01", requestId,
                new BigDecimal(capacity), new BigDecimal(reserve), new BigDecimal(balance), T));
    }

    @Test
    void registerMainFlowStartsAtVersionOne() {
        TankResponse resp = register("REG-1", "100", "10", "50");
        assertEquals(1L, resp.version());
        assertFalse(resp.idempotentReplay());
        assertDecimalEquals("50", resp.availableKg());
        assertDecimalEquals("0", resp.reservedKg());

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(resp.tankId(), status.tankId());
        assertDecimalEquals("100", status.capacityKg());
        assertDecimalEquals("10", status.safetyReserveKg());
        assertDecimalEquals("50", status.balanceKg());
        assertEquals(1L, status.version());
        assertTrue(status.pendingRefillEvents().isEmpty());

        List<TankLedgerEntryView> ledger = tankService.ledger("ST01", T.toLocalDate());
        assertEquals(1, ledger.size());
        assertEquals("REGISTER", ledger.get(0).entryType());
        assertDecimalEquals("50", ledger.get(0).deltaBalanceKg());
    }

    @Test
    void registerRejectsReserveEqualOrAboveCapacity() {
        // 0 ≤ 安全储备 < 容量：恰等容量也拒绝
        assertThrows(BusinessRejectedException.class, () -> register("REG-2A", "100", "100", "50"));
        assertThrows(BusinessRejectedException.class, () -> register("REG-2B", "100", "150", "50"));
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_tank"));
    }

    @Test
    void registerAllowsZeroReserve() {
        TankResponse resp = register("REG-2C", "100", "0", "50");
        assertDecimalEquals("0", resp.safetyReserveKg());
    }

    @Test
    void registerRejectsBalanceOverCapacity() {
        assertThrows(BusinessRejectedException.class, () -> register("REG-3", "100", "10", "100.001"));
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_tank"));
    }

    @Test
    void registerRejectsOverThreeDecimalKg() {
        // 千克数值超过三位小数：参数非法
        assertThrows(IllegalArgumentException.class, () -> register("REG-4", "100.0001", "10", "50"));
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_tank"));
    }

    @Test
    void duplicateRegisterRejected() {
        register("REG-5A", "100", "10", "50");
        assertThrows(BusinessRejectedException.class, () -> register("REG-5B", "200", "20", "60"));
        assertEquals(1, count("SELECT COUNT(*) FROM odorant_tank"));
    }

    @Test
    void registerOnUnknownStationReturns404() {
        assertThrows(ResourceNotFoundException.class, () -> tankService.register(
                new TankRegisterRequest("NOPE", "REG-6", new BigDecimal("100"),
                        new BigDecimal("10"), new BigDecimal("50"), T)));
    }

    @Test
    void statusOnUnboundStationReturns404() {
        assertThrows(ResourceNotFoundException.class, () -> tankService.status("ST02"));
    }

    @Test
    void numericTextEquivalenceReplaysAndDifferentValueConflicts() {
        TankResponse first = register("REG-7", "100", "10", "50");
        // 整数与小数文本数值等价（100 与 100.000）：同键同参重放首次冻结结果
        TankResponse replay = register("REG-7", "100.000", "10.0", "50.00");
        assertTrue(replay.idempotentReplay());
        assertEquals(first.tankId(), replay.tankId());
        assertEquals(1, count("SELECT COUNT(*) FROM odorant_tank"));
        // 同键异参：409
        assertThrows(IdempotentConflictException.class, () -> register("REG-7", "100", "10", "51"));
    }

    @Test
    void adjustBumpsVersionAndValidates() {
        register("REG-8", "100", "10", "50");

        TankResponse adjusted = tankService.adjust(new TankAdjustRequest("ST01", "ADJ-1",
                1L, new BigDecimal("120"), new BigDecimal("20"), T.plusHours(1)));
        assertEquals(2L, adjusted.version());
        assertDecimalEquals("120", adjusted.capacityKg());
        assertDecimalEquals("20", adjusted.safetyReserveKg());

        // 旧版本再次调整：409 版本冲突
        assertThrows(VersionConflictException.class, () -> tankService.adjust(
                new TankAdjustRequest("ST01", "ADJ-2", 1L, new BigDecimal("130"),
                        new BigDecimal("30"), T.plusHours(2))));
        // 容量收缩到当前余额之下：422
        assertThrows(BusinessRejectedException.class, () -> tankService.adjust(
                new TankAdjustRequest("ST01", "ADJ-3", 2L, new BigDecimal("40"),
                        new BigDecimal("30"), T.plusHours(3))));
        // 安全储备恰等容量：422
        assertThrows(BusinessRejectedException.class, () -> tankService.adjust(
                new TankAdjustRequest("ST01", "ADJ-4", 2L, new BigDecimal("120"),
                        new BigDecimal("120"), T.plusHours(4))));

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(2L, status.version(), "失败的调整不得改变版本");
        assertDecimalEquals("120", status.capacityKg());
    }

    @Test
    void adjustOnUnboundStationReturns404() {
        assertThrows(ResourceNotFoundException.class, () -> tankService.adjust(
                new TankAdjustRequest("ST02", "ADJ-5", 1L, new BigDecimal("100"),
                        new BigDecimal("10"), T)));
    }
}
