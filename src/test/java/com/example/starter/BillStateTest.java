package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.domain.Models.BillStatus;
import com.example.starter.domain.Models.BillType;
import com.example.starter.domain.Models.BillView;
import com.example.starter.domain.Models.Party;
import com.example.starter.error.ApiException;
import com.example.starter.service.BillingService;
import org.junit.jupiter.api.Test;

/**
 * 账单状态机：待确认→双方确认→封存（终态）；异议逐项列明原因；封存后拒绝修改，差错走调整单。
 */
class BillStateTest extends BaseIntegrationTest {

    private BillView newPendingBill(String treatyNo, String quarter, String requestId) {
        newTreaty(treatyNo, "0.8", "100000.00");
        newPolicy(treatyNo, treatyNo + "-P", "1000.00");
        return billingService.generate(requestId, treatyNo, quarter);
    }

    @Test
    void 双方确认后封存且封存为终态() {
        BillView bill = newPendingBill("T-1", "2026Q3", "rq-g1");
        String billNo = bill.bill().billNo();

        BillView afterCedant = billingService.confirm("rq-c1", billNo, Party.CEDANT);
        assertEquals(BillStatus.PENDING, afterCedant.bill().status());
        assertTrue(afterCedant.bill().confirmedByCedant());

        BillView sealed = billingService.confirm("rq-c2", billNo, Party.REINSURER);
        assertEquals(BillStatus.SEALED, sealed.bill().status());
        assertNotNull(sealed.bill().sealedAt());

        // 封存后重复确认：幂等成功，状态不变
        BillView again = billingService.confirm("rq-c3", billNo, Party.CEDANT);
        assertEquals(BillStatus.SEALED, again.bill().status());

        // 封存后异议一律拒绝
        ApiException e = assertThrows(ApiException.class, () -> billingService.dispute(
                "rq-d1", billNo, List.of(new BillingService.DisputeItemInput(1L, "金额不符"))));
        assertEquals("BILL_SEALED", e.code());
        assertEquals(BillStatus.SEALED, billingService.getBill(billNo).bill().status());
    }

    @Test
    void 异议须逐项列明原因且异议后拒绝确认() {
        BillView bill = newPendingBill("T-2", "2026Q3", "rq-g2");
        String billNo = bill.bill().billNo();
        long itemId = bill.items().get(0).id();

        BillingService.DisputeResult result = billingService.dispute("rq-d2", billNo,
                List.of(new BillingService.DisputeItemInput(itemId, "保费分保比例适用错误")));
        assertEquals(BillStatus.DISPUTED, result.status());
        assertEquals(1, result.itemCount());

        ApiException e = assertThrows(ApiException.class,
                () -> billingService.confirm("rq-c9", billNo, Party.CEDANT));
        assertEquals("BILL_DISPUTED", e.code());
    }

    @Test
    void 异议明细校验() {
        BillView bill = newPendingBill("T-3", "2026Q3", "rq-g3");
        String billNo = bill.bill().billNo();

        assertThrows(ApiException.class,
                () -> billingService.dispute("rq-d3", billNo, List.of()));
        assertThrows(ApiException.class, () -> billingService.dispute("rq-d4", billNo,
                List.of(new BillingService.DisputeItemInput(999999L, "明细不存在"))));
        assertEquals(BillStatus.PENDING, billingService.getBill(billNo).bill().status());
    }

    @Test
    void 差错须走下一期调整单并引用原账单号() {
        BillView bill = newPendingBill("T-4", "2026Q3", "rq-g4");
        String billNo = bill.bill().billNo();

        // 未封存账单不允许开调整单
        assertThrows(ApiException.class, () -> billingService.adjust("rq-a0", billNo, "2026Q4",
                new BigDecimal("1.00"), BigDecimal.ZERO, "差错更正"));

        billingService.confirm("rq-c1", billNo, Party.CEDANT);
        billingService.confirm("rq-c2", billNo, Party.REINSURER);

        BillView adjustment = billingService.adjust("rq-a1", billNo, "2026Q4",
                new BigDecimal("-10.00"), new BigDecimal("5.00"), "保费分保计算差错更正");
        assertEquals(BillType.ADJUSTMENT, adjustment.bill().billType());
        assertEquals(billNo, adjustment.bill().originalBillNo());
        assertEquals("2026Q4", adjustment.bill().quarterLabel());
        assertEquals(0, new BigDecimal("-5.00").compareTo(adjustment.bill().totalAmount()));
    }

    @Test
    void 同合约同季度只出一张常规账单() {
        BillView first = newPendingBill("T-5", "2026Q3", "rq-g5");

        BillView second = billingService.generate("rq-g6", "T-5", "2026Q3");

        assertEquals(first.bill().billNo(), second.bill().billNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bill", Long.class));
    }
}
