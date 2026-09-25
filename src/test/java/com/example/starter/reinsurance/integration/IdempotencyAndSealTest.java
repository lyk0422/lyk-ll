package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.reinsurance.api.BillResponse;
import com.example.starter.reinsurance.api.ConfirmBillRequest;
import com.example.starter.reinsurance.api.CreateAdjustmentRequest;
import com.example.starter.reinsurance.api.DisputeBillRequest;
import com.example.starter.reinsurance.api.GenerateBillRequest;
import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * requestId 幂等：同键同参返回首次结果，同键异参 409 且不改变账单状态；
 * 封存为终态，封存后异议/修改拒绝，差错走下一期调整单。
 */
class IdempotencyAndSealTest extends AbstractIntegrationTest {

    private GenerateBillRequest gen(String requestId, String contractNo, String period) {
        return new GenerateBillRequest(requestId, contractNo, period);
    }

    @Test
    void sameKeySameParametersReplaysFirstBill() {
        treaty("T-I1", "0.30", null);
        policy("P-I1", "T-I1", "1000.00", LocalDate.parse("2026-08-01"));

        BillResponse first = billService.generate(gen("req-i1", "T-I1", "2026-Q3"));
        BillResponse replay = billService.generate(gen("req-i1", "T-I1", "2026-Q3"));

        assertEquals(first.billNo(), replay.billNo());
        assertEquals(first.status(), replay.status());
        assertEquals(first.premiumTotal(), replay.premiumTotal());

        Integer billCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bill WHERE contract_id = "
                        + "(SELECT id FROM treaty WHERE contract_no = 'T-I1')",
                Integer.class);
        assertEquals(1, billCount);
        Integer policyBilled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM policy WHERE policy_no = 'P-I1' AND bill_id IS NOT NULL",
                Integer.class);
        assertEquals(1, policyBilled);
    }

    @Test
    void sameKeyDifferentParametersReturns409WithoutChangingBill() {
        treaty("T-I2", "0.30", null);
        policy("P-I2", "T-I2", "1000.00", LocalDate.parse("2026-08-01"));
        billService.generate(gen("req-i2", "T-I2", "2026-Q3"));

        // 同 requestId、不同季度 → 409
        ApiException ex = assertThrows(ApiException.class,
                () -> billService.generate(gen("req-i2", "T-I2", "2026-Q4")));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());

        // Q4 未被创建，原账单仍是 PENDING
        BillResponse bill = billService.getBill("RB-T-I2-2026-Q3");
        assertEquals("PENDING", bill.status());
        Integer q4Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bill WHERE period = '2026-Q4'", Integer.class);
        assertEquals(0, q4Count);
    }

    @Test
    void confirmSealsBillAndRepeatedConfirmIsIdempotent() {
        treaty("T-I3", "0.30", null);
        policy("P-I3", "T-I3", "1000.00", LocalDate.parse("2026-08-01"));
        billService.generate(gen("req-i3", "T-I3", "2026-Q3"));

        BillResponse sealed = billService.confirm(
                "RB-T-I3-2026-Q3", new ConfirmBillRequest("req-conf-1"));
        assertEquals("SEALED", sealed.status());
        assertTrue(sealed.cedantConfirmed());
        assertTrue(sealed.reinsurerConfirmed());
        assertTrue(sealed.sealedAt() != null);

        // 重复确认（新 requestId）幂等成功，仍为 SEALED
        BillResponse again = billService.confirm(
                "RB-T-I3-2026-Q3", new ConfirmBillRequest("req-conf-2"));
        assertEquals("SEALED", again.status());
        assertEquals(sealed.sealedAt(), again.sealedAt());

        Integer idempotentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE operation = 'CONFIRM_BILL'",
                Integer.class);
        assertEquals(2, idempotentRows);
    }

    @Test
    void disputeRequiresItemReasonsAndIsRejectedAfterSeal() {
        treaty("T-I4", "0.30", null);
        policy("P-I4", "T-I4", "1000.00", LocalDate.parse("2026-08-01"));
        BillResponse bill = billService.generate(gen("req-i4", "T-I4", "2026-Q3"));

        // 异议逐项列明：引用不存在的行 → 400
        DisputeBillRequest badRef = new DisputeBillRequest("req-disp-bad", List.of(
                new DisputeBillRequest.DisputeItemRequest("L99", new BigDecimal("0"), "不存在的行")));
        ApiException bad = assertThrows(ApiException.class,
                () -> billService.dispute(bill.billNo(), badRef));
        assertEquals(ErrorCode.VALIDATION_FAILED, bad.getErrorCode());

        // 合法异议（使用行号 L1）
        DisputeBillRequest dispute = new DisputeBillRequest("req-disp-1", List.of(
                new DisputeBillRequest.DisputeItemRequest(
                        "L1", new BigDecimal("250.00"), "分出保费基数应为 833.33 元")));
        BillResponse disputed = billService.dispute(bill.billNo(), dispute);
        assertEquals("DISPUTED", disputed.status());
        assertEquals(1, disputed.disputes().size());
        assertEquals("250.00", disputed.disputes().get(0).expectedAmount().toPlainString());

        // 异议后确认被拒绝（409）
        ApiException confirmAfterDispute = assertThrows(ApiException.class,
                () -> billService.confirm(bill.billNo(), new ConfirmBillRequest("req-conf-x")));
        assertEquals(ErrorCode.ILLEGAL_STATE, confirmAfterDispute.getErrorCode());

        // 同键异参的异议重放 → 409
        DisputeBillRequest changed = new DisputeBillRequest("req-disp-1", List.of(
                new DisputeBillRequest.DisputeItemRequest(
                        "L1", new BigDecimal("240.00"), "分出保费基数应为 800.00 元")));
        ApiException replayConflict = assertThrows(ApiException.class,
                () -> billService.dispute(bill.billNo(), changed));
        assertEquals(409, replayConflict.getHttpStatus());
    }

    @Test
    void sealedBillRejectsDisputeAndErrorsGoToNextPeriodAdjustment() {
        treaty("T-I5", "0.30", null);
        policy("P-I5", "T-I5", "1000.00", LocalDate.parse("2026-08-01"));
        BillResponse bill = billService.generate(gen("req-i5", "T-I5", "2026-Q3"));
        billService.confirm(bill.billNo(), new ConfirmBillRequest("req-i5-conf"));

        // 封存后异议一律拒绝
        DisputeBillRequest dispute = new DisputeBillRequest("req-i5-disp", List.of(
                new DisputeBillRequest.DisputeItemRequest(
                        "L1", new BigDecimal("250.00"), "封存后异议应被拒绝")));
        ApiException rejected = assertThrows(ApiException.class,
                () -> billService.dispute(bill.billNo(), dispute));
        assertEquals(ErrorCode.ILLEGAL_STATE, rejected.getErrorCode());

        // 差错走下一期调整单，引用原账单号；基数差额 -100 × 30% = -30
        CreateAdjustmentRequest adjustment = new CreateAdjustmentRequest(
                "req-i5-adj",
                "T-I5",
                "2026-Q4",
                bill.billNo(),
                List.of(new CreateAdjustmentRequest.AdjustmentItemRequest(
                        "PREMIUM", "P-I5", new BigDecimal("-100.00"), "保单批改减费，冲减保费分保")));
        BillResponse adj = billService.createAdjustment(adjustment);
        assertEquals("ADJUSTMENT", adj.billType());
        assertEquals(bill.billNo(), adj.refBillNo());
        assertEquals("RA-T-I5-2026-Q4", adj.billNo());
        assertEquals(new BigDecimal("-30.00"), adj.premiumTotal());
        assertEquals(bill.billNo(), adj.items().get(0).refBillNo());

        // 调整单不允许开在原期或更早
        CreateAdjustmentRequest samePeriod = new CreateAdjustmentRequest(
                "req-i5-adj2",
                "T-I5",
                "2026-Q3",
                bill.billNo(),
                List.of(new CreateAdjustmentRequest.AdjustmentItemRequest(
                        "PREMIUM", "P-I5", new BigDecimal("-100.00"), "同期不允许")));
        ApiException periodBad = assertThrows(ApiException.class,
                () -> billService.createAdjustment(samePeriod));
        assertEquals(ErrorCode.VALIDATION_FAILED, periodBad.getErrorCode());
    }
}
