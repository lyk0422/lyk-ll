package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐登记：主流程、参数非法（400 语义）、重复绑定（422 语义）、
 * 版本从 1 开始、状态查询与 REGISTER 台账。
 */
class TankServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TankService tankService;

    @Test
    void registerStartsAtVersion1AndWritesRegisterLedger() {
        TankRegisterResponse resp = TankFixtures.registerStandard(tankService);

        assertEquals(1, resp.version(), "登记成功版本从1开始");
        assertEquals(0, resp.capacityKg().compareTo(TankFixtures.CAPACITY));
        assertEquals(0, resp.balanceKg().compareTo(TankFixtures.BALANCE));
        assertEquals(0, resp.reservedKg().compareTo(BigDecimal.ZERO));

        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(resp.tankId(), status.tankId());
        assertEquals(1, status.version());
        assertEquals(0, status.availableKg().compareTo(TankFixtures.BALANCE));
        assertEquals(0, status.pendingRefillEvents());

        List<TankLedgerEntry> ledger = tankService.ledger(TankFixtures.STATION);
        assertEquals(1, ledger.size());
        assertEquals("REGISTER", ledger.get(0).entryType());
        assertEquals(0, ledger.get(0).balanceAfterKg().compareTo(TankFixtures.BALANCE));
    }

    @Test
    void safetyReserveMustBeLessThanCapacity() {
        // 恰等容量：参数非法（0≤安全储备<容量）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TankFixtures.register(tankService, TankFixtures.STATION,
                        new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO));
        assertTrue(ex.getMessage().contains("safetyReserveKg"));
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_tank"));
    }

    @Test
    void zeroSafetyReserveIsAllowed() {
        TankRegisterResponse resp = TankFixtures.register(tankService, TankFixtures.STATION,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("50"));
        assertEquals(1, resp.version());
    }

    @Test
    void initialBalanceAboveCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TankFixtures.register(tankService, TankFixtures.STATION,
                        new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100.001")));
    }

    @Test
    void moreThanThreeDecimalsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TankFixtures.register(tankService, TankFixtures.STATION,
                        new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("50.0001")));
    }

    @Test
    void duplicateStationBindingIsBusinessRejected() {
        TankFixtures.registerStandard(tankService);
        assertThrows(BusinessRejectedException.class,
                () -> TankFixtures.register(tankService, TankFixtures.STATION,
                        TankFixtures.CAPACITY, TankFixtures.RESERVE, TankFixtures.BALANCE));
        assertEquals(1, count("SELECT COUNT(*) FROM odorant_tank"));
    }

    @Test
    void sameRequestSameParamsReplaysFirstResult() {
        TankRegisterRequest request = new TankRegisterRequest(TankFixtures.STATION,
                "TANK-REPLAY", "TK-REPLAY", TankFixtures.CAPACITY, TankFixtures.RESERVE,
                TankFixtures.BALANCE, TankFixtures.T);
        TankRegisterResponse first = tankService.register(request);
        TankRegisterResponse replay = tankService.register(request);

        assertTrue(replay.idempotentReplay());
        assertEquals(first.tankId(), replay.tankId());
        assertEquals(1, count("SELECT COUNT(*) FROM odorant_tank"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger"));
    }

    @Test
    void statusOfUnboundStationIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> tankService.status("ST02"));
    }

    @Test
    void unknownStationIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> tankService.register(new TankRegisterRequest("ST-UNKNOWN", "TANK-X",
                        "TK-X", TankFixtures.CAPACITY, TankFixtures.RESERVE,
                        TankFixtures.BALANCE, LocalDateTime.now())));
    }
}
