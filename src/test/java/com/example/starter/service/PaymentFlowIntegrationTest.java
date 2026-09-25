package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.BatchSettlementResponse;
import com.example.starter.dto.CloseBatchRequest;
import com.example.starter.dto.PaymentRequest;
import com.example.starter.dto.PaymentResponse;
import com.example.starter.entity.SettlementOrder;
import com.example.starter.exception.ConflictException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 入账去重、requestId 幂等、封账清算与挂账调账的 H2 事务测试。 */
class PaymentFlowIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate BATCH = LocalDate.of(2026, 9, 20);
    private static final Instant IN_BATCH = Instant.parse("2026-09-20T10:00:00Z");

    @org.springframework.beans.factory.annotation.Autowired
    private PaymentService paymentService;

    @Test
    void postPaymentCalculatesFeeAndBatch() {
        PaymentResponse response = paymentService.postPayment(
                new PaymentRequest("REQ-1", "FLOW-1", "M001", 100_000L, IN_BATCH));

        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.batchDate()).isEqualTo(BATCH);
        assertThat(response.replayed()).isFalse();
        // 1000.00 元 × 0.006 = 6.00 元 = 600 分
        assertThat(response.fee()).isEqualTo(600L);
    }

    @Test
    void sameRequestIdSameParamsReturnsFirstResult() {
        PaymentResponse first = paymentService.postPayment(
                new PaymentRequest("REQ-2", "FLOW-2", "M001", 50_000L, IN_BATCH));
        PaymentResponse replay = paymentService.postPayment(
                new PaymentRequest("REQ-2", "FLOW-2", "M001", 50_000L, IN_BATCH.plusSeconds(30)));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batchDate()).isEqualTo(first.batchDate());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_txn WHERE request_id = 'REQ-2'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndDoesNotPost() {
        paymentService.postPayment(
                new PaymentRequest("REQ-3", "FLOW-3", "M001", 50_000L, IN_BATCH));

        assertThatThrownBy(() -> paymentService.postPayment(
                new PaymentRequest("REQ-3", "FLOW-3", "M001", 99_000L, IN_BATCH)))
                .isInstanceOf(ConflictException.class);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_txn WHERE request_id = 'REQ-3' AND amount = 50000", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void duplicateChannelFlowInSameBatchRejectedButAllowedInOtherBatch() {
        paymentService.postPayment(
                new PaymentRequest("REQ-4A", "FLOW-4", "M001", 10_000L, IN_BATCH));

        assertThatThrownBy(() -> paymentService.postPayment(
                new PaymentRequest("REQ-4B", "FLOW-4", "M002", 20_000L, IN_BATCH)))
                .isInstanceOf(ConflictException.class);

        // 另一批次（次日 16:00 前）允许同流水号。
        PaymentResponse otherBatch = paymentService.postPayment(
                new PaymentRequest("REQ-4C", "FLOW-4", "M001", 10_000L,
                        Instant.parse("2026-09-21T10:00:00Z")));
        assertThat(otherBatch.batchDate()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void closeBatchIsIdempotentAndDoesNotRecompute() {
        paymentService.postPayment(
                new PaymentRequest("REQ-5", "FLOW-5", "M001", 100_000L, IN_BATCH));

        BatchSettlementResponse first = paymentService.closeBatch(new CloseBatchRequest(BATCH));
        assertThat(first.status()).isEqualTo("CLOSED");
        assertThat(first.merchantCount()).isEqualTo(1);
        assertThat(first.totalAmount()).isEqualTo(100_000L);
        assertThat(first.totalFee()).isEqualTo(600L);
        assertThat(first.settledAmount()).isEqualTo(99_400L);
        assertThat(first.closedAt()).isNotNull();
        Instant firstClosedAt = first.closedAt();

        // 封账后再入账 → 挂账，重复封账不应把挂账计入清算。
        paymentService.postPayment(
                new PaymentRequest("REQ-6", "FLOW-6", "M001", 70_000L, IN_BATCH.plusSeconds(60)));

        BatchSettlementResponse second = paymentService.closeBatch(new CloseBatchRequest(BATCH));
        assertThat(second.closedAt()).isEqualTo(firstClosedAt);
        assertThat(second.totalAmount()).isEqualTo(100_000L);
        assertThat(second.merchantCount()).isEqualTo(1);
        assertThat(second.orders()).hasSize(1);
    }

    @Test
    void closeAggregatesByMerchantAndSettledIsAmountMinusFee() {
        paymentService.postPayment(
                new PaymentRequest("REQ-7A", "FLOW-7A", "M001", 100_000L, IN_BATCH));
        paymentService.postPayment(
                new PaymentRequest("REQ-7B", "FLOW-7B", "M002", 200_000L, IN_BATCH));

        BatchSettlementResponse settled = paymentService.closeBatch(new CloseBatchRequest(BATCH));

        assertThat(settled.merchantCount()).isEqualTo(2);
        assertThat(settled.totalAmount()).isEqualTo(300_000L);
        assertThat(settled.totalFee()).isEqualTo(600L + 2_000L);
        assertThat(settled.settledAmount()).isEqualTo(300_000L - 2_600L);

        SettlementOrder m001 = settled.orders().stream()
                .filter(o -> o.getMerchantNo().equals("M001")).findFirst().orElseThrow();
        assertThat(m001.getTxnCount()).isEqualTo(1);
        assertThat(m001.getSettledAmount()).isEqualTo(99_400L);
        SettlementOrder m002 = settled.orders().stream()
                .filter(o -> o.getMerchantNo().equals("M002")).findFirst().orElseThrow();
        assertThat(m002.getSettledAmount()).isEqualTo(198_000L);
    }

    @Test
    void suspendedTxnAdjustedAtomicallyIntoNextOpenBatch() {
        // 先入账一笔并封账。
        paymentService.postPayment(
                new PaymentRequest("REQ-8A", "FLOW-8A", "M001", 100_000L, IN_BATCH));
        paymentService.closeBatch(new CloseBatchRequest(BATCH));

        // 封账后到达 → 挂账。
        PaymentResponse late = paymentService.postPayment(
                new PaymentRequest("REQ-8B", "FLOW-8B", "M001", 50_000L, IN_BATCH.plusSeconds(5)));
        assertThat(late.status()).isEqualTo("SUSPENDED");

        // 调账转入次日开放批次。
        LocalDate nextBatch = BATCH.plusDays(1);
        AdjustResponse adjusted = paymentService.adjust(
                new AdjustRequest("ADJ-1", "FLOW-8B", BATCH, nextBatch));
        assertThat(adjusted.status()).isEqualTo("DONE");
        assertThat(adjusted.toBatchDate()).isEqualTo(nextBatch);
        assertThat(adjusted.amount()).isEqualTo(50_000L);
        assertThat(adjusted.replayed()).isFalse();

        // 原子性与计额校验：原批次清算不含挂账；目标批次封账清算恰含调账转入交易。
        BatchSettlementResponse origin = paymentService.getBatch(BATCH);
        assertThat(origin.totalAmount()).isEqualTo(100_000L);

        BatchSettlementResponse target = paymentService.closeBatch(new CloseBatchRequest(nextBatch));
        assertThat(target.totalAmount()).isEqualTo(50_000L);
        assertThat(target.totalFee()).isEqualTo(300L);
        assertThat(target.orders()).hasSize(1);
        assertThat(target.orders().get(0).getTxnCount()).isEqualTo(1);
    }

    @Test
    void duplicateAdjustNoReturnsFirstAdjustment() {
        paymentService.postPayment(
                new PaymentRequest("REQ-9A", "FLOW-9A", "M001", 10_000L, IN_BATCH));
        paymentService.closeBatch(new CloseBatchRequest(BATCH));
        paymentService.postPayment(
                new PaymentRequest("REQ-9B", "FLOW-9B", "M001", 10_000L, IN_BATCH.plusSeconds(5)));

        LocalDate nextBatch = BATCH.plusDays(1);
        AdjustResponse first = paymentService.adjust(
                new AdjustRequest("ADJ-2", "FLOW-9B", BATCH, nextBatch));
        AdjustResponse replay = paymentService.adjust(
                new AdjustRequest("ADJ-2", "FLOW-9B", BATCH, nextBatch));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.toBatchDate()).isEqualTo(first.toBatchDate());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment WHERE adjust_no = 'ADJ-2'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void adjustToClosedTargetRollsBackEntireTransaction() {
        paymentService.postPayment(
                new PaymentRequest("REQ-10A", "FLOW-10A", "M001", 10_000L, IN_BATCH));
        paymentService.closeBatch(new CloseBatchRequest(BATCH));
        paymentService.postPayment(
                new PaymentRequest("REQ-10B", "FLOW-10B", "M001", 20_000L, IN_BATCH.plusSeconds(5)));

        LocalDate nextBatch = BATCH.plusDays(1);
        paymentService.closeBatch(new CloseBatchRequest(nextBatch));

        assertThatThrownBy(() -> paymentService.adjust(
                new AdjustRequest("ADJ-3", "FLOW-10B", BATCH, nextBatch)))
                .isInstanceOf(com.example.starter.exception.BusinessException.class);

        // 整体回滚：交易仍挂在原批次，不留调账记录，金额不计入任一批次清算。
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_txn WHERE channel_flow_no = 'FLOW-10B'", String.class);
        assertThat(status).isEqualTo("SUSPENDED");
        Long adjustCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment WHERE adjust_no = 'ADJ-3'", Long.class);
        assertThat(adjustCount).isZero();
        assertThat(paymentService.getBatch(nextBatch).totalAmount()).isZero();
    }
}
