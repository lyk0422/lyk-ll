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
 * 入账主流程、requestId 幂等、渠道流水去重与封账挂账的 H2 事务测试。
 */
class PaymentServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String DAY = "2026-09-24";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;

    private PaymentRequest request(String requestId, String serial, String merchant, String amount,
            Instant occurTime) {
        return new PaymentRequest(requestId, serial, merchant, new BigDecimal(amount), occurTime);
    }

    private Instant utc(int day, int hour, int minute) {
        return java.time.LocalDate.of(2026, 9, day).atTime(hour, minute, 0).toInstant(ZoneOffset.UTC);
    }

    @Test
    void postsIntoOpenBatchBeforeCutoff() {
        var resp = paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 10, 0)));
        assertThat(resp.status()).isEqualTo("POSTED");
        assertThat(resp.batchDate()).isEqualTo(DAY);
        assertThat(resp.replayed()).isFalse();
    }

    @Test
    void exactlyAtCutoffGoesToNextDayBatch() {
        var resp = paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 16, 0)));
        assertThat(resp.batchDate()).isEqualTo("2026-09-25");
        assertThat(resp.status()).isEqualTo("POSTED");
    }

    @Test
    void sameRequestIdSameParamsReturnsFirstResult() {
        var first = paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 10, 0)));
        var replay = paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 11, 0)));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batchDate()).isEqualTo(first.batchDate());
        assertThat(replay.status()).isEqualTo(first.status());
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM payment_transaction", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndDoesNotPost() {
        paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 10, 0)));
        assertThatThrownBy(() -> paymentService.post(request("r1", "S1", "M001", "200.00", utc(24, 10, 0))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM payment_transaction", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentSerialReturns409() {
        paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 10, 0)));
        assertThatThrownBy(() -> paymentService.post(request("r1", "S2", "M001", "100.00", utc(24, 10, 0))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    void duplicateChannelSerialWithinBatchRejected() {
        paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 10, 0)));
        assertThatThrownBy(() -> paymentService.post(request("r2", "S1", "M001", "100.00", utc(24, 10, 0))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM payment_transaction", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void unknownMerchantReturns404() {
        assertThatThrownBy(() -> paymentService.post(request("r1", "S1", "MXXX", "10.00", utc(24, 10, 0))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void paymentAfterCloseIsSuspended() {
        paymentService.post(request("r1", "S1", "M001", "100.00", utc(24, 10, 0)));
        settlementService.closeBatch(DAY);

        var late = paymentService.post(request("r2", "S2", "M001", "50.00", utc(24, 14, 0)));
        assertThat(late.status()).isEqualTo("SUSPENDED");
        assertThat(late.batchDate()).isEqualTo(DAY);
        // 挂账交易不计入已封账批次清算。
        var settlement = settlementService.getSettlement(DAY);
        assertThat(settlement.items()).hasSize(1);
        assertThat(settlement.items().get(0).txnCount()).isEqualTo(1);
    }
}
