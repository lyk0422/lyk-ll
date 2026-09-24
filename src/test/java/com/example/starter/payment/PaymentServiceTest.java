package com.example.starter.payment;

import com.example.starter.payment.service.PaymentService;
import com.example.starter.payment.service.PaymentService.PostCommand;
import com.example.starter.payment.service.PaymentService.PostResult;
import com.example.starter.payment.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入账：日切归属、requestId 幂等、流水号去重、封账后挂账。
 */
class PaymentServiceTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;

    private static PostCommand cmd(String requestId, String serialNo, long amount, String txnTime) {
        return new PostCommand(requestId, serialNo, "M0001", amount, Instant.parse(txnTime));
    }

    @Test
    void postAssignsBatchByCutoffAndComputesFee() {
        // 15:59 归当日；16:00 整点归次日；M0001 费率 30bp
        PostResult before = paymentService.post(cmd("r1", "S1", 10000, "2026-09-24T15:59:00Z"));
        assertThat(before.batchDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(before.txn().status()).isEqualTo(PayTransaction.POSTED);
        assertThat(before.txn().fee()).isEqualTo(30);
        assertThat(before.replayed()).isFalse();

        PostResult at = paymentService.post(cmd("r2", "S2", 10000, "2026-09-24T16:00:00Z"));
        assertThat(at.batchDate()).isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void sameRequestIdSameParamsReturnsFirstResult() {
        PostResult first = paymentService.post(cmd("r1", "S1", 10000, "2026-09-24T10:00:00Z"));
        PostResult second = paymentService.post(cmd("r1", "S1", 10000, "2026-09-24T10:00:00Z"));
        assertThat(second.replayed()).isTrue();
        assertThat(second.txn().id()).isEqualTo(first.txn().id());
        assertThat(countRows("pay_transaction")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        paymentService.post(cmd("r1", "S1", 10000, "2026-09-24T10:00:00Z"));
        assertThatThrownBy(() -> paymentService.post(cmd("r1", "S1", 20000, "2026-09-24T10:00:00Z")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(409);
        // 不重复入账
        assertThat(countRows("pay_transaction")).isEqualTo(1);
    }

    @Test
    void duplicateSerialNoRejected() {
        paymentService.post(cmd("r1", "S1", 10000, "2026-09-24T10:00:00Z"));
        assertThatThrownBy(() -> paymentService.post(cmd("r2", "S1", 10000, "2026-09-24T10:00:00Z")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(409);
        assertThat(countRows("pay_transaction")).isEqualTo(1);
    }

    @Test
    void postAfterCloseGoesToSuspense() {
        paymentService.post(cmd("r1", "S1", 10000, "2026-09-24T10:00:00Z"));
        settlementService.close(LocalDate.of(2026, 9, 24));

        PostResult late = paymentService.post(cmd("r2", "S2", 5000, "2026-09-24T11:00:00Z"));
        assertThat(late.txn().status()).isEqualTo(PayTransaction.SUSPENDED);
        assertThat(late.txn().batchId()).isNull();
        assertThat(late.batchDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void unknownMerchantRejected() {
        assertThatThrownBy(() -> paymentService.post(
                new PostCommand("r1", "S1", "NOPE", 100, Instant.parse("2026-09-24T10:00:00Z"))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(400);
    }

    @Test
    void invalidAmountRejected() {
        assertThatThrownBy(() -> paymentService.post(cmd("r1", "S1", 0, "2026-09-24T10:00:00Z")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(400);
    }
}
