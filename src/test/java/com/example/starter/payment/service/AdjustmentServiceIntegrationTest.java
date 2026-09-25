package com.example.starter.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.payment.AbstractIntegrationTest;
import com.example.starter.payment.dto.AdjustRequest;
import com.example.starter.payment.dto.PaymentRequest;
import com.example.starter.payment.support.BusinessException;

/**
 * 挂账调账原子性 H2 事务测试：调账与原交易同事务翻转，只能计入唯一目标批次，不可重复调账。
 */
class AdjustmentServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;

    private Instant utc(int hour) {
        return java.time.LocalDate.of(2026, 9, 24).atTime(hour, 0, 0).toInstant(ZoneOffset.UTC);
    }

    @Test
    void suspendedTxnAdjustedAtomicallyIntoNextOpenBatch() {
        paymentService.post(new PaymentRequest("r1", "S1", "M001", new BigDecimal("100.00"), utc(10)));
        settlementService.closeBatch("2026-09-24");
        var suspended = paymentService.post(new PaymentRequest("r2", "S2", "M001",
                new BigDecimal("40.00"), utc(14)));
        assertThat(suspended.status()).isEqualTo("SUSPENDED");

        var resp = paymentService.adjust(new AdjustRequest("S2", "op01", "挂账调次日"));
        assertThat(resp.fromBatchDate()).isEqualTo("2026-09-24");
        assertThat(resp.toBatchDate()).isEqualTo("2026-09-25");

        // 交易已 POSTED 且归属新批次，不再挂账。
        String state = jdbc.queryForObject(
                "SELECT status || '|' || batch_date FROM payment_transaction WHERE channel_serial_no = 'S2'",
                String.class);
        assertThat(state).isEqualTo("POSTED|2026-09-25");

        // 调账留痕恰好一条。
        Integer logs = jdbc.queryForObject("SELECT COUNT(1) FROM adjustment_order WHERE channel_serial_no = 'S2'",
                Integer.class);
        assertThat(logs).isEqualTo(1);

        // 金额只计入目标批次清算一次，旧批次清算不受影响。
        var oldSettlement = settlementService.getSettlement("2026-09-24");
        assertThat(oldSettlement.items().get(0).txnCount()).isEqualTo(1);
        var newSettlement = settlementService.closeBatch("2026-09-25");
        var m001 = newSettlement.items().stream().filter(i -> i.merchantNo().equals("M001")).findFirst().orElseThrow();
        assertThat(m001.txnCount()).isEqualTo(1);
        assertThat(m001.totalAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void duplicateAdjustmentRejected() {
        paymentService.post(new PaymentRequest("r1", "S1", "M001", new BigDecimal("100.00"), utc(10)));
        settlementService.closeBatch("2026-09-24");
        paymentService.post(new PaymentRequest("r2", "S2", "M001", new BigDecimal("40.00"), utc(14)));

        paymentService.adjust(new AdjustRequest("S2", "op01", null));
        assertThatThrownBy(() -> paymentService.adjust(new AdjustRequest("S2", "op01", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
        Integer logs = jdbc.queryForObject("SELECT COUNT(1) FROM adjustment_order WHERE channel_serial_no = 'S2'",
                Integer.class);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void adjustNonSuspendedTxnRejected() {
        paymentService.post(new PaymentRequest("r1", "S1", "M001", new BigDecimal("100.00"), utc(10)));
        assertThatThrownBy(() -> paymentService.adjust(new AdjustRequest("S1", "op01", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    void adjustUnknownSerialRejectedWith404() {
        assertThatThrownBy(() -> paymentService.adjust(new AdjustRequest("NOPE", "op01", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(404));
    }
}
