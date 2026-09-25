package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.domain.Models.AccountEntry;
import com.example.starter.domain.Models.CloseResult;
import com.example.starter.domain.Models.EntryType;
import com.example.starter.domain.Models.EstimateAdjustResult;
import com.example.starter.service.ClaimService;
import org.junit.jupiter.api.Test;

/**
 * 赔案生命周期：估损预提、结案冲销差额（同事务）、结案终态、迟到估损调整只记事件。
 */
class ClaimLifecycleTest extends BaseIntegrationTest {

    @Test
    void 立案按估损预提摊回() {
        newTreaty("T-1", "0.8", "100000.00");
        newPolicy("T-1", "P-1", "100.00");

        ClaimService.RegisterResult result = claimService.register("rq-1", "P-1", "C-1",
                new BigDecimal("500.00"));

        assertEquals(0, new BigDecimal("400.00").compareTo(result.provisionAmount()));
        assertEquals(0, new BigDecimal("400.00").compareTo(balanceOf("T-1")));
        List<AccountEntry> entries = treatyService.getAccount("T-1").entries();
        assertEquals(1, entries.size());
        assertEquals(EntryType.PROVISION, entries.get(0).entryType());
    }

    @Test
    void 结案按实际赔付冲销差额且预提冲销同事务() {
        newTreaty("T-2", "0.8", "100000.00");
        newPolicy("T-2", "P-2", "100.00");
        claimService.register("rq-1", "P-2", "C-2", new BigDecimal("500.00"));

        CloseResult result = claimService.close("rq-2", "C-2", new BigDecimal("400.00"));

        // 预提 400.00，实际摊回 320.00，冲销差额 -80.00
        assertEquals(0, new BigDecimal("400.00").compareTo(result.provisionAmount()));
        assertEquals(0, new BigDecimal("-80.00").compareTo(result.reversalAmount()));
        assertEquals(0, new BigDecimal("320.00").compareTo(result.balanceAfter()));
        assertFalse(result.replayed());
        // 同事务留下预提与冲销两条分录，余额逐步可核
        List<AccountEntry> entries = treatyService.getAccount("T-2").entries();
        assertEquals(2, entries.size());
        assertEquals(EntryType.PROVISION, entries.get(0).entryType());
        assertEquals(EntryType.REVERSAL, entries.get(1).entryType());
        assertEquals(0, new BigDecimal("320.00").compareTo(balanceOf("T-2")));
    }

    @Test
    void 结案为终态重复结案返回首次结果() {
        newTreaty("T-3", "0.8", "100000.00");
        newPolicy("T-3", "P-3", "100.00");
        claimService.register("rq-1", "P-3", "C-3", new BigDecimal("500.00"));
        CloseResult first = claimService.close("rq-2", "C-3", new BigDecimal("400.00"));

        // 不同 requestId 重复结案：返回首次结果，不再记账
        CloseResult second = claimService.close("rq-3", "C-3", new BigDecimal("999.00"));

        assertTrue(second.replayed());
        assertEquals(0, first.paidAmount().compareTo(second.paidAmount()));
        assertEquals(0, new BigDecimal("400.00").compareTo(second.paidAmount()));
        assertEquals(2, treatyService.getAccount("T-3").entries().size());
        assertEquals(0, new BigDecimal("320.00").compareTo(balanceOf("T-3")));
    }

    @Test
    void 结案后迟到估损调整只记事件不改账务() {
        newTreaty("T-4", "0.8", "100000.00");
        newPolicy("T-4", "P-4", "100.00");
        claimService.register("rq-1", "P-4", "C-4", new BigDecimal("500.00"));
        claimService.close("rq-2", "C-4", new BigDecimal("400.00"));
        BigDecimal balanceBefore = balanceOf("T-4");

        EstimateAdjustResult result = claimService.adjustEstimate("rq-3", "C-4",
                new BigDecimal("900.00"));

        assertTrue(result.recordedOnly());
        assertEquals(0, balanceBefore.compareTo(balanceOf("T-4")));
        assertEquals(2, treatyService.getAccount("T-4").entries().size());
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE event_type = 'LATE_ESTIMATE_ADJUST'",
                Long.class);
        assertEquals(1L, events);
    }

    @Test
    void 未决估损调整同步预提余额() {
        newTreaty("T-5", "0.5", "100000.00");
        newPolicy("T-5", "P-5", "100.00");
        claimService.register("rq-1", "P-5", "C-5", new BigDecimal("200.00"));

        EstimateAdjustResult result = claimService.adjustEstimate("rq-2", "C-5",
                new BigDecimal("300.00"));

        assertFalse(result.recordedOnly());
        assertEquals(0, new BigDecimal("150.00").compareTo(balanceOf("T-5")));
        List<AccountEntry> entries = treatyService.getAccount("T-5").entries();
        assertEquals(2, entries.size());
        assertEquals(EntryType.PROVISION_ADJUST, entries.get(1).entryType());
        assertEquals(0, new BigDecimal("50.00").compareTo(entries.get(1).amount()));
    }
}
