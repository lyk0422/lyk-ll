package com.example.starter.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.payment.AbstractIntegrationTest;
import com.example.starter.payment.dto.PaymentRequest;
import com.example.starter.payment.dto.SettlementItem;

/**
 * 封账清算与幂等 H2 测试：清算金额=交易额-手续费，重复封账返回首次清算单且不重算。
 */
class SettlementServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;

    private Instant utc(int hour) {
        return java.time.LocalDate.of(2026, 9, 24).atTime(hour, 0, 0).toInstant(ZoneOffset.UTC);
    }

    @Test
    void closeComputesSettlementAmountPerMerchant() {
        // M001 费率 0.6%：100.00 -> fee 0.60，清算 99.40；M002 费率 1.0%：200.00 -> fee 2.00，清算 198.00。
        paymentService.post(new PaymentRequest("r1", "S1", "M001", new BigDecimal("100.00"), utc(9)));
        paymentService.post(new PaymentRequest("r2", "S2", "M002", new BigDecimal("200.00"), utc(10)));
        paymentService.post(new PaymentRequest("r3", "S3", "M001", new BigDecimal("50.00"), utc(11)));

        var resp = settlementService.closeBatch("2026-09-24");
        assertThat(resp.status()).isEqualTo("CLOSED");
        assertThat(resp.replayed()).isFalse();
        assertThat(resp.items()).hasSize(2);

        SettlementItem m001 = item(resp, "M001");
        assertThat(m001.txnCount()).isEqualTo(2);
        assertThat(m001.totalAmount()).isEqualByComparingTo("150.00");
        assertThat(m001.totalFee()).isEqualByComparingTo("0.90");
        assertThat(m001.settledAmount()).isEqualByComparingTo("149.10");

        SettlementItem m002 = item(resp, "M002");
        assertThat(m002.txnCount()).isEqualTo(1);
        assertThat(m002.settledAmount()).isEqualByComparingTo("198.00");
    }

    @Test
    void repeatedCloseReturnsFirstSettlementWithoutRecalculation() {
        paymentService.post(new PaymentRequest("r1", "S1", "M001", new BigDecimal("100.00"), utc(9)));
        var first = settlementService.closeBatch("2026-09-24");
        assertThat(first.replayed()).isFalse();

        // 封账后到达交易：挂账，不影响已生成清算。
        var suspended = paymentService.post(new PaymentRequest("r2", "S2", "M001",
                new BigDecimal("80.00"), utc(14)));
        assertThat(suspended.status()).isEqualTo("SUSPENDED");

        var second = settlementService.closeBatch("2026-09-24");
        assertThat(second.replayed()).isTrue();
        assertThat(second.items()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.items());
        assertThat(item(second, "M001").txnCount()).isEqualTo(1);
    }

    @Test
    void closeEmptyBatchCreatesNoSettlementItemsAndIsIdempotent() {
        var first = settlementService.closeBatch("2026-09-30");
        assertThat(first.items()).isEmpty();
        var second = settlementService.closeBatch("2026-09-30");
        assertThat(second.replayed()).isTrue();
        assertThat(second.items()).isEmpty();
    }

    private SettlementItem item(com.example.starter.payment.dto.SettlementResponse resp, String merchant) {
        return resp.items().stream().filter(i -> i.merchantNo().equals(merchant)).findFirst().orElseThrow();
    }
}
