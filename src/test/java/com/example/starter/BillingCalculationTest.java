package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.domain.Models.BillItem;
import com.example.starter.domain.Models.BillStatus;
import com.example.starter.domain.Models.BillView;
import org.junit.jupiter.api.Test;

/**
 * 账单比例计算、责任限额边界（恰达/超出）、UTC 季度日界与明细合计复算。
 */
class BillingCalculationTest extends BaseIntegrationTest {

    @Test
    void 比例计算与限额超出部分留在分出公司() {
        newTreaty("T-1", "0.8", "1000.00");
        newPolicy("T-1", "P-1", "1000.00"); // 保费分保 = 800.00
        claimService.register("rq-c1", "P-1", "C-1", new BigDecimal("300.00"));
        claimService.close("rq-c2", "C-1", new BigDecimal("300.00")); // 赔款摊回 = 240.00

        BillView view = billingService.generate("rq-b1", "T-1", "2026Q3");

        assertEquals(BillStatus.PENDING, view.bill().status());
        assertEquals(1, view.items().size());
        BillItem item = view.items().get(0);
        assertEquals(0, new BigDecimal("800.00").compareTo(item.premiumCession()));
        assertEquals(0, new BigDecimal("240.00").compareTo(item.claimRecovery()));
        // gross = 1040 > 限额 1000：按限额计入，超出 40 留在分出公司
        assertTrue(item.limitApplied());
        assertEquals(0, new BigDecimal("1000.00").compareTo(item.itemTotal()));
        assertEquals(0, new BigDecimal("40.00").compareTo(item.retainedAmount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(view.bill().totalAmount()));
    }

    @Test
    void 限额恰达边界按限额计入且留存为零() {
        newTreaty("T-2", "0.8", "1000.00");
        newPolicy("T-2", "P-2", "1250.00"); // 保费分保恰为 1000.00 = 限额

        BillView view = billingService.generate("rq-b2", "T-2", "2026Q3");

        BillItem item = view.items().get(0);
        assertEquals(0, new BigDecimal("1000.00").compareTo(item.itemTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.retainedAmount()));
        assertFalse(item.limitApplied());
    }

    @Test
    void 明细合计必须等于汇总金额且可逐笔复算() {
        newTreaty("T-3", "0.5", "100000.00");
        newPolicy("T-3", "P-3a", "100.00");
        newPolicy("T-3", "P-3b", "200.00");
        newPolicy("T-3", "P-3c", "300.00");
        claimService.register("rq-c3", "P-3c", "C-3", new BigDecimal("77.77"));
        claimService.close("rq-c4", "C-3", new BigDecimal("77.77"));

        BillView view = billingService.generate("rq-b3", "T-3", "2026Q3");

        assertEquals(3, view.items().size());
        BigDecimal sum = view.items().stream().map(BillItem::itemTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(view.bill().totalAmount()));
        BigDecimal premiumSum = view.items().stream().map(BillItem::premiumCession)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recoverySum = view.items().stream().map(BillItem::claimRecovery)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, premiumSum.compareTo(view.bill().totalPremiumCession()));
        assertEquals(0, recoverySum.compareTo(view.bill().totalClaimRecovery()));
        // 逐笔复算：P-3c 保费分保 150.00 + 赔款摊回 38.89(77.77×0.5 四舍五入)
        BillItem itemC = view.items().stream()
                .filter(i -> i.claimRecovery().signum() > 0).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("150.00").compareTo(itemC.premiumCession()));
        assertEquals(0, new BigDecimal("38.89").compareTo(itemC.claimRecovery()));
        // getBill 复算校验通过
        billingService.getBill(view.bill().billNo());
    }

    @Test
    void 季度按UTC日界划分已决赔款() {
        newTreaty("T-4", "1.0", "100000.00");
        newPolicy("T-4", "P-4", "0.00");
        // Q2 最后一秒结案
        clock.setInstant(Instant.parse("2026-06-30T23:59:59Z"));
        claimService.register("rq-c5", "P-4", "C-4a", new BigDecimal("10.00"));
        claimService.close("rq-c6", "C-4a", new BigDecimal("10.00"));
        // Q3 第一秒结案
        clock.setInstant(Instant.parse("2026-07-01T00:00:00Z"));
        claimService.register("rq-c7", "P-4", "C-4b", new BigDecimal("20.00"));
        claimService.close("rq-c8", "C-4b", new BigDecimal("20.00"));

        BillView q2 = billingService.generate("rq-b4", "T-4", "2026Q2");
        BillView q3 = billingService.generate("rq-b5", "T-4", "2026Q3");

        assertEquals(0, new BigDecimal("10.00").compareTo(q2.bill().totalClaimRecovery()));
        assertEquals(0, new BigDecimal("20.00").compareTo(q3.bill().totalClaimRecovery()));
    }
}
