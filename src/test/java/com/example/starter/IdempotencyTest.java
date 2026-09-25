package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import com.example.starter.domain.Models.BillView;
import com.example.starter.domain.Models.CloseResult;
import com.example.starter.error.ApiException;
import org.junit.jupiter.api.Test;

/**
 * 幂等语义：同键同参返回首次结果；同键异参返回 409 且不改变业务状态。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Test
    void 出账同键同参返回首次结果() {
        newTreaty("T-1", "0.8", "100000.00");
        newPolicy("T-1", "P-1", "1000.00");

        BillView first = billingService.generate("rq-same", "T-1", "2026Q3");
        BillView second = billingService.generate("rq-same", "T-1", "2026Q3");

        assertEquals(first.bill().billNo(), second.bill().billNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bill", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class));
    }

    @Test
    void 出账同键异参返回409且账单状态不变() {
        newTreaty("T-2", "0.8", "100000.00");
        newPolicy("T-2", "P-2", "1000.00");
        billingService.generate("rq-diff", "T-2", "2026Q3");

        ApiException e = assertThrows(ApiException.class,
                () -> billingService.generate("rq-diff", "T-2", "2026Q4"));

        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals(409, e.status().value());
        // 状态未变：仅首张 Q3 账单，Q4 未生成
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bill", Long.class));
        assertEquals("2026Q3", jdbc.queryForObject("SELECT quarter_label FROM bill", String.class));
    }

    @Test
    void 结案同键重放返回首次结果且账户不重复记账() {
        newTreaty("T-3", "0.8", "100000.00");
        newPolicy("T-3", "P-3", "0.00");
        claimService.register("rq-1", "P-3", "C-3", new BigDecimal("500.00"));

        CloseResult first = claimService.close("rq-close", "C-3", new BigDecimal("400.00"));
        CloseResult replay = claimService.close("rq-close", "C-3", new BigDecimal("400.00"));

        assertEquals(0, first.balanceAfter().compareTo(replay.balanceAfter()));
        assertEquals(2, treatyService.getAccount("T-3").entries().size());
        assertEquals(0, new BigDecimal("320.00").compareTo(balanceOf("T-3")));
    }

    @Test
    void 立案同键异参返回409且余额不变() {
        newTreaty("T-4", "0.8", "100000.00");
        newPolicy("T-4", "P-4", "0.00");
        claimService.register("rq-reg", "P-4", "C-4", new BigDecimal("100.00"));

        ApiException e = assertThrows(ApiException.class, () -> claimService.register(
                "rq-reg", "P-4", "C-4", new BigDecimal("200.00")));

        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals(0, new BigDecimal("80.00").compareTo(balanceOf("T-4")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM claim", Long.class));
    }
}
