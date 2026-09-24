package com.example.starter.payment;

import com.example.starter.payment.service.AdjustmentService;
import com.example.starter.payment.service.PaymentService;
import com.example.starter.payment.service.PaymentService.PostCommand;
import com.example.starter.payment.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 挂账与调账：调账原子性（不出现两边同时计额）、仅挂账可调账。
 */
class AdjustmentServiceTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private AdjustmentService adjustmentService;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    private void post(String requestId, String serialNo, long amount, String txnTime) {
        paymentService.post(new PostCommand(requestId, serialNo, "M0001", amount,
                Instant.parse(txnTime)));
    }

    @Test
    void adjustMovesSuspendedTxnToNextOpenBatch() {
        post("r1", "S1", 10000, "2026-09-24T10:00:00Z");
        settlementService.close(DAY);
        // 封账后到达 → 挂账
        post("r2", "S2", 5000, "2026-09-24T11:00:00Z");

        PayTransaction adjusted = adjustmentService.adjust("S2");
        assertThat(adjusted.status()).isEqualTo(PayTransaction.POSTED);
        assertThat(adjusted.batchId()).isNotNull();

        // 调账后交易归属次日（下一开放）批次
        PayBatch nextBatch = jdbc.query(
                "SELECT batch_date FROM pay_batch WHERE id = ?",
                (rs, i) -> new PayBatch(0, rs.getDate("batch_date").toLocalDate(), "", null),
                adjusted.batchId()).get(0);
        assertThat(nextBatch.batchDate()).isEqualTo(DAY.plusDays(1));

        // 原子性校验：原批次清算单不含该交易，新批次封账后恰好计额一次
        List<SettlementBill> oldBills = settlementService.billsOf(DAY);
        assertThat(oldBills).hasSize(1);
        assertThat(oldBills.get(0).totalAmount()).isEqualTo(10000);

        List<SettlementBill> newBills = settlementService.close(DAY.plusDays(1));
        assertThat(newBills).hasSize(1);
        assertThat(newBills.get(0).txnCount()).isEqualTo(1);
        assertThat(newBills.get(0).totalAmount()).isEqualTo(5000);

        // 全库计额唯一：该流水号仅一条 POSTED 记录且只属于一个批次
        Integer postedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_transaction WHERE serial_no = 'S2' AND status = 'POSTED'",
                Integer.class);
        assertThat(postedCount).isEqualTo(1);
    }

    @Test
    void adjustPostedTxnRejected() {
        post("r1", "S1", 10000, "2026-09-24T10:00:00Z");
        assertThatThrownBy(() -> adjustmentService.adjust("S1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(409);
    }

    @Test
    void adjustUnknownSerialRejected() {
        assertThatThrownBy(() -> adjustmentService.adjust("NOPE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(404);
    }
}
