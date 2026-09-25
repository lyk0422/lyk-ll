package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.reinsurance.api.BillItemResponse;
import com.example.starter.reinsurance.api.BillResponse;
import com.example.starter.reinsurance.api.ClaimResponse;
import com.example.starter.reinsurance.api.GenerateBillRequest;
import com.example.starter.reinsurance.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 比例计算与季度出账测试：保费分保＝分出保费×比例、赔款摊回＝已决/预提×比例，
 * 账单明细合计必须等于汇总且可逐笔复算，季度按 UTC 日界归集。
 */
class BillGenerationTest extends AbstractIntegrationTest {

    @Test
    void generatesQuarterlyBillWithProportionalPremiumAndProvision() {
        treaty("T-01", "0.30", null);
        policy("P-01", "T-01", "1000.00", LocalDate.parse("2026-08-01"));
        policy("P-02", "T-01", "2000.00", LocalDate.parse("2026-08-10"));
        claim("C-01", "P-01", "500.00", LocalDate.parse("2026-08-05"));
        claim("C-02", "P-02", "200.00", LocalDate.parse("2026-08-12"));

        BillResponse bill = billService.generate(
                new GenerateBillRequest("req-gen-1", "T-01", "2026-Q3"));

        assertEquals("RB-T-01-2026-Q3", bill.billNo());
        assertEquals("PENDING", bill.status());
        assertTrue(bill.cedantConfirmed());
        assertFalse(bill.reinsurerConfirmed());

        // 保费分保 = (1000 + 2000) × 30% = 900
        assertEquals(new BigDecimal("900.00"), bill.premiumTotal());
        // 未决预提摊回 = (500 + 200) × 30% = 210
        assertEquals(new BigDecimal("210.00"), bill.recoveryTotal());
        // 净额 = 900 - 210 = 690
        assertEquals(new BigDecimal("690.00"), bill.netTotal());

        Map<String, BillItemResponse> byRef = bill.items().stream()
                .collect(Collectors.toMap(BillItemResponse::refNo, Function.identity()));
        // 逐笔复算：基数 × 比例 = 金额
        for (BillItemResponse item : bill.items()) {
            BigDecimal recomputed = Money.applyRate(item.grossAmount(), item.cessionRate());
            assertEquals(recomputed, item.amount(),
                    "明细 " + item.refNo() + " 无法用基数×比例复算");
        }
        assertEquals(new BigDecimal("300.00"), byRef.get("P-01").amount());
        assertEquals(new BigDecimal("600.00"), byRef.get("P-02").amount());
        assertEquals(new BigDecimal("150.00"), byRef.get("C-01").amount());
        assertEquals(new BigDecimal("60.00"), byRef.get("C-02").amount());

        // 明细合计 == 汇总
        BigDecimal itemPremium = bill.items().stream()
                .filter(i -> "PREMIUM".equals(i.itemType()))
                .map(BillItemResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal itemRecovery = bill.items().stream()
                .filter(i -> "CLAIM_RECOVERY".equals(i.itemType()))
                .map(BillItemResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(bill.premiumTotal(), Money.amount(itemPremium));
        assertEquals(bill.recoveryTotal(), Money.amount(itemRecovery));
        assertNotNull(bill.createdAt());
        assertNull(bill.sealedAt());
    }

    @Test
    void settlesClaimBeforeBillingShowsActualRecoveryInsteadOfProvision() {
        treaty("T-02", "0.30", null);
        policy("P-10", "T-02", "1000.00", LocalDate.parse("2026-07-01"));
        claim("C-10", "P-10", "500.00", LocalDate.parse("2026-07-05"));
        // 实际赔付 800 > 估损 500，结案补提 (800-500)×30% = 90；最终实摊 240
        close("C-10", "req-close-1", "800.00");

        ClaimResponse claimView = claimService.getClaim("C-10");
        assertEquals("FINAL_CLOSED", claimView.status());
        assertEquals(new BigDecimal("0.00"), claimView.provisionAmount());
        assertEquals(new BigDecimal("240.00"), claimView.settledAmount());

        BillResponse bill = billService.generate(
                new GenerateBillRequest("req-gen-2", "T-02", "2026-Q3"));
        // 流水：预提 +150、结案 +90；合计摊回 240
        assertEquals(new BigDecimal("240.00"), bill.recoveryTotal());
        assertEquals(new BigDecimal("300.00"), bill.premiumTotal());
        assertEquals(new BigDecimal("60.00"), bill.netTotal());

        // 账户余额在出账后仍为 240（出账不核销账户，仅挂账单号）
        assertEquals(new BigDecimal("240.00"), accountBalance(jdbcTemplate.queryForObject(
                "SELECT id FROM treaty WHERE contract_no = 'T-02'", Long.class)));
    }

    @Test
    void quarterlyBoundaryFollowsUtcDates() {
        treaty("T-03", "0.50", null);
        // 2026-Q2 末日生效的保单进 Q2 账单
        policy("P-20", "T-03", "100.00", LocalDate.parse("2026-06-30"));
        // 2026-Q3 首日生效的保单进 Q3 账单
        policy("P-21", "T-03", "100.00", LocalDate.parse("2026-07-01"));

        BillResponse q2 = billService.generate(
                new GenerateBillRequest("req-gen-q2", "T-03", "2026-Q2"));
        BillResponse q3 = billService.generate(
                new GenerateBillRequest("req-gen-q3", "T-03", "2026-Q3"));

        assertEquals(new BigDecimal("50.00"), q2.premiumTotal());
        assertEquals(new BigDecimal("50.00"), q3.premiumTotal());
        assertEquals(1, q2.items().size());
        assertEquals("P-20", q2.items().get(0).refNo());
        assertEquals("P-21", q3.items().get(0).refNo());
    }

    @Test
    void regenerationDoesNotDoubleCountItems() {
        treaty("T-04", "0.30", null);
        policy("P-30", "T-04", "1000.00", LocalDate.parse("2026-08-01"));

        BillResponse first = billService.generate(
                new GenerateBillRequest("req-gen-a", "T-04", "2026-Q3"));
        BillResponse second = billService.generate(
                new GenerateBillRequest("req-gen-b", "T-04", "2026-Q3"));

        assertEquals(first.billNo(), second.billNo());
        assertEquals(1, second.items().size());
        assertEquals(new BigDecimal("300.00"), second.premiumTotal());
    }
}
