package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.odorant.OdorantService;
import com.example.starter.query.ReconciliationResult;
import com.example.starter.query.RecomputeService;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.ReplenishRequest;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日结与储罐台账边界：日结只冻当日检测台账；封存后补液仍可入账，
 * 且不改变旧日勾稽（封存口径），储罐台账是独立永续台账。
 */
class TankDailyCloseInteractionTest extends AbstractIntegrationTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 26);

    @Autowired
    private TankService tankService;
    @Autowired
    private OdorantService odorantService;
    @Autowired
    private DailyCloseService dailyCloseService;
    @Autowired
    private RecomputeService recomputeService;

    @Test
    void laterReplenishmentDoesNotChangeSealedReconciliation() {
        tankService.register(new TankRegisterRequest("ST01", "DC-REG", "TK-DC",
                new BigDecimal("100.000"), new BigDecimal("20.000"), new BigDecimal("50.000"),
                BIZ_DATE.atTime(8, 0)));

        // 当日一次合格浓度检测，流量 100m3，理论消耗 100×20 = 2000mg
        odorantService.detect(new OdorantDetectRequest("ST01", "DC-O",
                BIZ_DATE.atTime(9, 0), new BigDecimal("100"), new BigDecimal("20.000")));
        dailyCloseService.close(new DailyCloseRequest("ST01", "DC-CLOSE",
                BIZ_DATE, new BigDecimal("2000")));

        ReconciliationResult before = recomputeService.reconcile("ST01", BIZ_DATE);
        assertTrue(before.sealed());
        assertEquals(0, new BigDecimal("100.000").compareTo(before.totalFlowM3()));
        assertEquals(0, new BigDecimal("2000.000").compareTo(before.expectedOdorantMg()));

        // 封存后补液：储罐台账独立、不被日结冻结，正常入账
        tankService.replenish(new ReplenishRequest("ST01", "DC-RP", "BATCH-DC",
                new BigDecimal("5.000"), new BigDecimal("20.000"), "H01", "H02", 1,
                BIZ_DATE.atTime(23, 0)));
        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("55.000").compareTo(status.balanceKg()));
        assertEquals(2, status.version());

        // 旧日勾稽口径不变
        ReconciliationResult after = recomputeService.reconcile("ST01", BIZ_DATE);
        assertTrue(after.sealed());
        assertEquals(0, new BigDecimal("100.000").compareTo(after.totalFlowM3()));
        assertEquals(0, new BigDecimal("2000.000").compareTo(after.expectedOdorantMg()));
        assertEquals(0, after.deviationPct().compareTo(before.deviationPct()));
    }
}
