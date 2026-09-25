package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.reinsurance.api.ClaimResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 每张保单责任限额测试：分摊金额恰达限额按限额计入，超出部分留在分出公司；
 * 多赔案共享保单限额，预提/调整/结案各环节均不超限。
 */
class PerPolicyLimitTest extends AbstractIntegrationTest {

    @Test
    void provisionExactlyAtLimitIsCountedFully() {
        // 限额 300，估损 1000 × 30% = 300 恰达限额
        treaty("T-L1", "0.30", "300.00");
        policy("P-L1", "T-L1", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-L1", "P-L1", "1000.00", LocalDate.parse("2026-08-02"));

        ClaimResponse view = claimService.getClaim("CL-L1");
        assertEquals(new BigDecimal("300.00"), view.provisionAmount());
        assertEquals(new BigDecimal("300.00"), view.accountBalance());
    }

    @Test
    void provisionBeyondLimitStaysWithCedant() {
        // 限额 200，估损 1000 × 30% = 300，超出 100 留在分出公司
        treaty("T-L2", "0.30", "200.00");
        policy("P-L2", "T-L2", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-L2", "P-L2", "1000.00", LocalDate.parse("2026-08-02"));

        ClaimResponse view = claimService.getClaim("CL-L2");
        assertEquals(new BigDecimal("200.00"), view.provisionAmount());
        assertEquals(new BigDecimal("200.00"), view.accountBalance());
    }

    @Test
    void multipleClaimsSharePolicyLimitCapacity() {
        // 限额 300：第一案预提 240，第二案理论 180 但只剩 60 容量
        treaty("T-L3", "0.30", "300.00");
        policy("P-L3", "T-L3", "5000.00", LocalDate.parse("2026-08-01"));
        claim("CL-L3A", "P-L3", "800.00", LocalDate.parse("2026-08-02"));
        claim("CL-L3B", "P-L3", "600.00", LocalDate.parse("2026-08-03"));

        assertEquals(new BigDecimal("240.00"), claimService.getClaim("CL-L3A").provisionAmount());
        ClaimResponse second = claimService.getClaim("CL-L3B");
        assertEquals(new BigDecimal("60.00"), second.provisionAmount());

        // 结案不改变限额归属：第一案以 800 结案（实摊维持 240），第二案实摊仍被截在 60
        close("CL-L3A", "req-l3a", "800.00");
        close("CL-L3B", "req-l3b", "600.00");
        assertEquals(new BigDecimal("240.00"), claimService.getClaim("CL-L3A").settledAmount());
        assertEquals(new BigDecimal("60.00"), claimService.getClaim("CL-L3B").settledAmount());
        assertEquals(new BigDecimal("120.00"), claimService.getClaim("CL-L3B").cappedExcess());
        assertEquals(new BigDecimal("300.00"), accountBalance(jdbcTemplate.queryForObject(
                "SELECT id FROM treaty WHERE contract_no = 'T-L3'", Long.class)));
    }

    @Test
    void estimateUpwardAfterCappedProvisionCannotExceedLimit() {
        // 限额 200，预提已占满；估损继续上调不再增加摊回
        treaty("T-L4", "0.30", "200.00");
        policy("P-L4", "T-L4", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-L4", "P-L4", "1000.00", LocalDate.parse("2026-08-02"));
        adjust("CL-L4", "2000.00");

        ClaimResponse view = claimService.getClaim("CL-L4");
        assertEquals(new BigDecimal("200.00"), view.provisionAmount());
        assertEquals(new BigDecimal("200.00"), view.accountBalance());

        // 估损下调释放容量，再结案按实赔重算限额
        adjust("CL-L4", "400.00");
        assertEquals(new BigDecimal("120.00"), claimService.getClaim("CL-L4").provisionAmount());
        close("CL-L4", "req-l4", "1000.00");
        assertEquals(new BigDecimal("200.00"), claimService.getClaim("CL-L4").settledAmount());
        assertEquals(new BigDecimal("200.00"), claimService.getClaim("CL-L4").accountBalance());
    }

    @Test
    void accountBalanceIsNeverNegativeAcrossLedger() {
        treaty("T-L5", "0.30", null);
        policy("P-L5", "T-L5", "1000.00", LocalDate.parse("2026-08-01"));
        claim("CL-L5", "P-L5", "900.00", LocalDate.parse("2026-08-02"));
        // 结案 0：冲销 270，余额归 0，流水每一笔 balance_after 都不得为负
        close("CL-L5", "req-l5", "0.00");

        Integer negativeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_ledger WHERE balance_after < 0", Integer.class);
        assertEquals(0, negativeRows);
        BigDecimal minBalance = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MIN(balance_after), 0) FROM account_ledger", BigDecimal.class);
        assertTrue(minBalance.signum() >= 0);
        assertEquals(new BigDecimal("0.00"), accountBalance(jdbcTemplate.queryForObject(
                "SELECT id FROM treaty WHERE contract_no = 'T-L5'", Long.class)));
    }
}
