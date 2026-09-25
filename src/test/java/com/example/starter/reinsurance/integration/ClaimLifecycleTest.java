package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.starter.reinsurance.api.ClaimResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 赔案生命周期：未决预提、估损调整、最终结案冲销、结案终态与迟到估损只记事件。
 */
class ClaimLifecycleTest extends AbstractIntegrationTest {

    @Test
    void provisionAdjustAndSettleInOneConsistentFlow() {
        treaty("T-C1", "0.30", null);
        policy("P-C1", "T-C1", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-C1", "P-C1", "1000.00", LocalDate.parse("2026-08-02"));

        ClaimResponse afterProvision = claimService.getClaim("CL-C1");
        assertEquals("OPEN", afterProvision.status());
        // 预提 = 1000 × 30% = 300
        assertEquals(new BigDecimal("300.00"), afterProvision.provisionAmount());
        assertEquals(new BigDecimal("300.00"), afterProvision.accountBalance());

        // 估损下调到 400：释放 (1000-400)×30% = 180，预提变 120
        adjust("CL-C1", "400.00");
        ClaimResponse afterDown = claimService.getClaim("CL-C1");
        assertEquals(new BigDecimal("120.00"), afterDown.provisionAmount());
        assertEquals(new BigDecimal("120.00"), afterDown.accountBalance());

        // 估损上调到 700：补提 (700-400)×30% = 90，预提变 210
        adjust("CL-C1", "700.00");
        ClaimResponse afterUp = claimService.getClaim("CL-C1");
        assertEquals(new BigDecimal("210.00"), afterUp.provisionAmount());
        assertEquals(new BigDecimal("210.00"), afterUp.accountBalance());

        // 最终结案 900：实摊 270，相对预提 210 补提 60（预提与冲销差额同事务）
        close("CL-C1", "req-close-c1", "900.00");
        ClaimResponse afterClose = claimService.getClaim("CL-C1");
        assertEquals("FINAL_CLOSED", afterClose.status());
        assertEquals(new BigDecimal("900.00"), afterClose.paidAmount());
        assertEquals(new BigDecimal("0.00"), afterClose.provisionAmount());
        assertEquals(new BigDecimal("270.00"), afterClose.settledAmount());
        assertEquals(new BigDecimal("270.00"), afterClose.accountBalance());
    }

    @Test
    void closeReversesExcessProvisionWhenActualPaidIsLower() {
        treaty("T-C2", "0.30", null);
        policy("P-C2", "T-C2", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-C2", "P-C2", "1000.00", LocalDate.parse("2026-08-02"));
        assertEquals(new BigDecimal("300.00"), claimService.getClaim("CL-C2").accountBalance());

        // 实际赔付 200：实摊 60，冲销预提差额 240，账户回落至 60
        close("CL-C2", "req-close-c2", "200.00");
        ClaimResponse afterClose = claimService.getClaim("CL-C2");
        assertEquals(new BigDecimal("60.00"), afterClose.settledAmount());
        assertEquals(new BigDecimal("60.00"), afterClose.accountBalance());

        // 极端情况：实际赔付 0，冲销全部预提，账户恰为 0
        treaty("T-C3", "0.30", null);
        policy("P-C3", "T-C3", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-C3", "P-C3", "800.00", LocalDate.parse("2026-08-02"));
        close("CL-C3", "req-close-c3", "0.00");
        assertEquals(new BigDecimal("0.00"), claimService.getClaim("CL-C3").accountBalance());
        assertEquals(new BigDecimal("0.00"), claimService.getClaim("CL-C3").settledAmount());
    }

    @Test
    void finalCloseHappensOnlyOnceAndRepeatedCloseReturnsFirstResult() {
        treaty("T-C4", "0.30", null);
        policy("P-C4", "T-C4", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-C4", "P-C4", "500.00", LocalDate.parse("2026-08-02"));

        close("CL-C4", "req-close-c4-a", "600.00");
        // 重复结案：不同 requestId、不同金额，返回首次结果，终态不被改写
        ClaimResponse repeated = close("CL-C4", "req-close-c4-b", "999.00");
        assertEquals(new BigDecimal("600.00"), repeated.paidAmount());
        assertEquals(new BigDecimal("180.00"), repeated.settledAmount());

        Integer settleEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE claim_id = "
                        + "(SELECT id FROM claim WHERE claim_no = 'CL-C4') AND event_type = 'SETTLED'",
                Integer.class);
        assertEquals(1, settleEvents, "最终结案事件只能有一条");
    }

    @Test
    void lateEstimateAdjustmentAfterCloseIsOnlyRecordedAsEvent() {
        treaty("T-C5", "0.30", null);
        policy("P-C5", "T-C5", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-C5", "P-C5", "500.00", LocalDate.parse("2026-08-02"));
        close("CL-C5", "req-close-c5", "500.00");
        BigDecimal balanceBefore = claimService.getClaim("CL-C5").accountBalance();

        // 迟到的估损调整：只记事件，估损金额、预提、实摊、余额均不变
        adjust("CL-C5", "888.00");
        ClaimResponse after = claimService.getClaim("CL-C5");
        assertEquals(new BigDecimal("500.00"), after.estimatedAmount());
        assertEquals(balanceBefore, after.accountBalance());
        assertEquals(new BigDecimal("150.00"), after.settledAmount());

        Integer lateEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE claim_id = "
                        + "(SELECT id FROM claim WHERE claim_no = 'CL-C5') "
                        + "AND event_type = 'LATE_ESTIMATE_RECORDED'",
                Integer.class);
        assertEquals(1, lateEvents);
    }
}
