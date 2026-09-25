package com.example.starter.lc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.example.starter.lc.testsupport.TestClockConfig;
import com.example.starter.lc.web.dto.CreateLcRequest;
import com.example.starter.lc.web.dto.DiscrepancyInput;
import com.example.starter.lc.web.dto.DocItem;
import com.example.starter.lc.web.dto.LcResponse;
import com.example.starter.lc.web.dto.NoticeRequest;
import com.example.starter.lc.web.dto.PayRequest;
import com.example.starter.lc.web.dto.PresentRequest;
import com.example.starter.lc.web.dto.PresentationResponse;
import com.example.starter.lc.web.dto.RefundRequest;
import com.example.starter.lc.web.dto.WaiverItem;
import com.example.starter.lc.web.dto.WaiverRequest;

/**
 * 信用证审单全流程的 H2（MODE=MySQL）事务与并发集成测试。
 * 覆盖：5 个工作日边界与视为接受、不符点一次性列明、部分放弃无效、改单重交重起算、
 * 同键重放/同键异参 409、拒付与放弃竞态唯一终态、额度边界、并发交单唯一受理。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class LcServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-06-01T10:00:00Z");
    /** 收到日 06-01 10:00 对应的审单截止：06-06 17:00 UTC。 */
    private static final LocalDateTime DEADLINE = LocalDateTime.parse("2026-06-06T17:00:00");

    @Autowired
    private LcService lcService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbcTemplate.update("DELETE FROM discrepancy");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM presentation_batch");
        jdbcTemplate.update("DELETE FROM lc_account");
        TestClockConfig.CLOCK.setInstant(BASE);
    }

    // ------------------------------------------------------------------
    // 1. 5 个银行工作日边界：16:59:59 前有效；17:00 整及之后无效并视为接受
    // ------------------------------------------------------------------

    @Test
    void noticeOneSecondBeforeCutoffIsValidAndLocksRejected() {
        setupLc("LC-BOUNDARY", "1000.00");
        lcService.present(presentRequest("r-present-boundary", "LC-BOUNDARY", "P-BOUNDARY", null));

        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-06T16:59:59"));
        PresentationResponse resp = lcService.notice(new NoticeRequest("r-notice-boundary",
                "P-BOUNDARY", discrepancies(false)));

        assertThat(resp.status()).isEqualTo("REJECTED");
        assertThat(resp.noticeAtUtc()).isEqualTo(LocalDateTime.parse("2026-06-06T16:59:59"));
        assertThat(resp.discrepancies()).hasSize(2);
    }

    @Test
    void noticeExactlyAtCutoffIsInvalidAndDeemedAccepted() {
        setupLc("LC-ATCUTOFF", "1000.00");
        lcService.present(presentRequest("r-present-at", "LC-ATCUTOFF", "P-ATCUTOFF", null));

        TestClockConfig.CLOCK.setDateTime(DEADLINE); // 17:00:00 整
        PresentationResponse resp = lcService.notice(new NoticeRequest("r-notice-at",
                "P-ATCUTOFF", discrepancies(false)));

        assertThat(resp.status()).isEqualTo("DEEMED_ACCEPTED");
        assertThat(resp.acceptedAtUtc()).isEqualTo(DEADLINE);
        assertThat(resp.discrepancies()).isEmpty();
        Integer discCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM discrepancy d JOIN presentation_batch p ON d.presentation_id = p.id"
                        + " WHERE p.presentation_no = 'P-ATCUTOFF'", Integer.class);
        assertThat(discCount).isZero();
    }

    @Test
    void noticeAfterDeadlineIsInvalidAndDeemedAcceptedAndThenCanPay() {
        setupLc("LC-LATE", "1000.00");
        lcService.present(presentRequest("r-present-late", "LC-LATE", "P-LATE", null));

        // 不做任何操作，第 6 天银行才“想起来”发通知。
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-07T09:00:00"));
        PresentationResponse late = lcService.notice(new NoticeRequest("r-notice-late",
                "P-LATE", discrepancies(false)));
        assertThat(late.status()).isEqualTo("DEEMED_ACCEPTED");

        // 视为接受后必须付款，额度核减。
        PresentationResponse paid = lcService.pay(new PayRequest("r-pay-late", "P-LATE"));
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(lcService.getLc("LC-LATE").amountAvailable()).isEqualByComparingTo("700.00");
    }

    @Test
    void queryingAfterDeadlineAlsoDeemsAccepted() {
        setupLc("LC-QUERY", "1000.00");
        lcService.present(presentRequest("r-present-q", "LC-QUERY", "P-QUERY", null));

        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-06T17:00:01"));
        assertThat(lcService.getPresentation("P-QUERY").status()).isEqualTo("DEEMED_ACCEPTED");
    }

    // ------------------------------------------------------------------
    // 2. 不符点一次性列明，事后不得补充
    // ------------------------------------------------------------------

    @Test
    void discrepanciesMustAllBeListedAtOnceNoSupplementAfterwards() {
        setupLc("LC-ONCE", "1000.00");
        lcService.present(presentRequest("r-present-once", "LC-ONCE", "P-ONCE", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-05T10:00:00"));

        PresentationResponse rejected = lcService.notice(new NoticeRequest("r-notice-once",
                "P-ONCE", List.of(new DiscrepancyInput("BILL_OF_LADING", "LATE_SHIPMENT", "d1"))));
        assertThat(rejected.status()).isEqualTo("REJECTED");

        // 单据状态已锁定：再次通知补充不符点被拒，且库中不符点不增加。
        assertThatThrownBy(() -> lcService.notice(new NoticeRequest("r-notice-supplement",
                "P-ONCE", List.of(new DiscrepancyInput("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH", "d2")))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("BATCH_NOT_UNDER_REVIEW"));
        Integer discCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM discrepancy d JOIN presentation_batch p ON d.presentation_id = p.id"
                        + " WHERE p.presentation_no = 'P-ONCE'", Integer.class);
        assertThat(discCount).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 3. 部分放弃无效；放弃全部后接受付款
    // ------------------------------------------------------------------

    @Test
    void partialWaiverIsInvalidAndStateStaysRejected() {
        setupLc("LC-PARTIAL", "1000.00");
        lcService.present(presentRequest("r-present-partial", "LC-PARTIAL", "P-PARTIAL", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-05T10:00:00"));
        lcService.notice(new NoticeRequest("r-notice-partial", "P-PARTIAL", discrepancies(false)));

        assertThatThrownBy(() -> lcService.waive(new WaiverRequest("r-waiver-partial",
                "P-PARTIAL", List.of(new WaiverItem("BILL_OF_LADING", "LATE_SHIPMENT")))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("PARTIAL_WAIVER"));

        assertThat(lcService.getPresentation("P-PARTIAL").status()).isEqualTo("REJECTED");
        // 部分放弃后仍可退单结案。
        PresentationResponse refunded = lcService.refund(new RefundRequest("r-refund-partial", "P-PARTIAL"));
        assertThat(refunded.status()).isEqualTo("REFUNDED");
    }

    @Test
    void waiverCoveringUnlistedDiscrepancyIsAlsoInvalid() {
        setupLc("LC-EXTRA", "1000.00");
        lcService.present(presentRequest("r-present-extra", "LC-EXTRA", "P-EXTRA", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-05T10:00:00"));
        lcService.notice(new NoticeRequest("r-notice-extra", "P-EXTRA",
                List.of(new DiscrepancyInput("BILL_OF_LADING", "LATE_SHIPMENT", "d"))));

        List<WaiverItem> tooMany = List.of(
                new WaiverItem("BILL_OF_LADING", "LATE_SHIPMENT"),
                new WaiverItem("PACKING_LIST", "NOT_LISTED"));
        assertThatThrownBy(() -> lcService.waive(
                new WaiverRequest("r-waiver-extra", "P-EXTRA", tooMany)))
                .isInstanceOf(BusinessException.class);
        assertThat(lcService.getPresentation("P-EXTRA").status()).isEqualTo("REJECTED");
    }

    @Test
    void fullWaiverAcceptsAndPayDeductsOnceWithReplay() {
        setupLc("LC-HAPPY", "1000.00");
        lcService.present(presentRequest("r-present-happy", "LC-HAPPY", "P-HAPPY", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-05T10:00:00"));
        lcService.notice(new NoticeRequest("r-notice-happy", "P-HAPPY", discrepancies(false)));

        PresentationResponse accepted = lcService.waive(new WaiverRequest(
                "r-waiver-happy", "P-HAPPY", waiverItems()));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");

        PresentationResponse paid = lcService.pay(new PayRequest("r-pay-happy", "P-HAPPY"));
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paidAtUtc()).isNotNull();
        LcResponse lcAfterPay = lcService.getLc("LC-HAPPY");
        assertThat(lcAfterPay.amountAvailable()).isEqualByComparingTo("700.00");

        // 重复付款指令返回首次结果（replayed=true），不重复扣减额度。
        PresentationResponse replay = lcService.pay(new PayRequest("r-pay-happy", "P-HAPPY"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo("PAID");
        assertThat(replay.paidAtUtc()).isEqualTo(paid.paidAtUtc());
        assertThat(lcService.getLc("LC-HAPPY").amountAvailable()).isEqualByComparingTo("700.00");

        // 换用新 requestId 再付款：仍是终态首次结果，不重复扣减额度。
        PresentationResponse again = lcService.pay(new PayRequest("r-pay-happy-again", "P-HAPPY"));
        assertThat(again.status()).isEqualTo("PAID");
        assertThat(again.paidAtUtc()).isEqualTo(paid.paidAtUtc());
        assertThat(lcService.getLc("LC-HAPPY").amountAvailable()).isEqualByComparingTo("700.00");
    }

    // ------------------------------------------------------------------
    // 4. 改单重交：新批次、重新起算审单期
    // ------------------------------------------------------------------

    @Test
    void resubmitAfterRejectionCreatesNewBatchAndRestartsReviewClock() {
        setupLc("LC-RESUB", "1000.00");
        lcService.present(presentRequest("r-present-1", "LC-RESUB", "P-RESUB-1", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-05T10:00:00"));
        lcService.notice(new NoticeRequest("r-notice-1", "P-RESUB-1", discrepancies(false)));

        // 受益人 06-08 改单重交，审单期自新收到日重新起算：截止 06-13 17:00。
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-08T09:30:00"));
        PresentationResponse resub = lcService.present(presentRequest(
                "r-present-2", "LC-RESUB", "P-RESUB-2", "P-RESUB-1"));
        assertThat(resub.status()).isEqualTo("UNDER_REVIEW");
        assertThat(resub.batchSeq()).isEqualTo(2);
        assertThat(resub.receivedAtUtc()).isEqualTo(LocalDateTime.parse("2026-06-08T09:30:00"));
        assertThat(resub.deadlineUtc()).isEqualTo(LocalDateTime.parse("2026-06-13T17:00:00"));

        assertThat(lcService.getPresentation("P-RESUB-1").status()).isEqualTo("SUPERSEDED");

        // 新批次在新截止前可正常拒付。
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-13T16:00:00"));
        PresentationResponse rejected2 = lcService.notice(new NoticeRequest(
                "r-notice-2", "P-RESUB-2",
                List.of(new DiscrepancyInput("PACKING_LIST", "WEIGHT_DIFF", "fixed docs still off"))));
        assertThat(rejected2.status()).isEqualTo("REJECTED");
    }

    @Test
    void cannotResubmitNonRejectedBatch() {
        setupLc("LC-RESUB2", "1000.00");
        lcService.present(presentRequest("r-present-x", "LC-RESUB2", "P-RX-1", null));
        // 首批次仍审单中，不能以“改单重交”取代。
        assertThatThrownBy(() -> lcService.present(presentRequest(
                "r-present-y", "LC-RESUB2", "P-RX-2", "P-RX-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("BATCH_NOT_REJECTED"));
    }

    // ------------------------------------------------------------------
    // 5. 同键重放返回首次结果；同键异参 409 且不改变状态
    // ------------------------------------------------------------------

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        setupLc("LC-IDEM", "1000.00");
        PresentRequest req = presentRequest("dup-key", "LC-IDEM", "P-IDEM", null);
        PresentationResponse first = lcService.present(req);
        PresentationResponse replay = lcService.present(req);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.presentationNo()).isEqualTo("P-IDEM");
        Integer batchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM presentation_batch WHERE lc_no = 'LC-IDEM'", Integer.class);
        assertThat(batchCount).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndDoesNotChangeState() {
        setupLc("LC-CONFLICT", "1000.00");
        lcService.present(presentRequest("same-key", "LC-CONFLICT", "P-CONF-1", null));

        PresentRequest differentParams = presentRequest("same-key", "LC-CONFLICT", "P-CONF-2", null);
        assertThatThrownBy(() -> lcService.present(differentParams))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
                    assertThat(be.getStatus().value()).isEqualTo(409);
                });
        // 单据状态未改变：不存在第二批次，原批次仍审单中。
        Integer batchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM presentation_batch WHERE lc_no = 'LC-CONFLICT'", Integer.class);
        assertThat(batchCount).isEqualTo(1);
        assertThat(lcService.getPresentation("P-CONF-1").status()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void samePayKeyOnDifferentPresentationIs409() {
        setupLc("LC-PAYC", "1000.00");
        lcService.present(presentRequest("rp1", "LC-PAYC", "P-PC-1", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-07T09:00:00"));
        lcService.pay(new PayRequest("shared-pay-key", "P-PC-1"));
        assertThatThrownBy(() -> lcService.pay(new PayRequest("shared-pay-key", "P-PC-OTHER")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
    }

    // ------------------------------------------------------------------
    // 6. 拒付与放弃/退单并发：形成唯一终态
    // ------------------------------------------------------------------

    @Test
    void waiverAndRefundRaceProducesSingleTerminalState() throws Exception {
        setupLc("LC-RACE", "1000.00");
        lcService.present(presentRequest("r-present-race", "LC-RACE", "P-RACE", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-05T10:00:00"));
        lcService.notice(new NoticeRequest("r-notice-race", "P-RACE", discrepancies(false)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger waiverOk = new AtomicInteger();
        AtomicInteger refundOk = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    lcService.waive(new WaiverRequest("r-waiver-race", "P-RACE", waiverItems()));
                    waiverOk.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                }
            }));
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    lcService.refund(new RefundRequest("r-refund-race", "P-RACE"));
                    refundOk.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                }
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(waiverOk.get() + refundOk.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);

        PresentationResponse terminal = lcService.getPresentation("P-RACE");
        assertThat(terminal.status()).isIn("ACCEPTED", "REFUNDED");
        if ("ACCEPTED".equals(terminal.status())) {
            // 放弃成功：可付款，退单必须失败，绝不允许既退单又付款。
            assertThat(lcService.pay(new PayRequest("r-pay-race", "P-RACE")).status())
                    .isEqualTo("PAID");
            assertThatThrownBy(() -> lcService.refund(new RefundRequest("r-refund-2", "P-RACE")))
                    .isInstanceOf(BusinessException.class);
        } else {
            // 退单成功：付款必须失败。
            assertThatThrownBy(() -> lcService.pay(new PayRequest("r-pay-race", "P-RACE")))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void concurrentPresentationsOnlyOneAccepted() throws Exception {
        setupLc("LC-CP", "1000.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            futures.add(pool.submit(() -> attemptPresent(start, ok, conflict,
                    presentRequest("cp-1", "LC-CP", "P-CP-1", null))));
            futures.add(pool.submit(() -> attemptPresent(start, ok, conflict,
                    presentRequest("cp-2", "LC-CP", "P-CP-2", null))));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        Integer underReview = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM presentation_batch WHERE lc_no = 'LC-CP' AND status = 'UNDER_REVIEW'",
                Integer.class);
        assertThat(underReview).isEqualTo(1);
    }

    private void attemptPresent(CountDownLatch start, AtomicInteger ok, AtomicInteger conflict,
            PresentRequest req) {
        try {
            start.await();
            lcService.present(req);
            ok.incrementAndGet();
        } catch (BusinessException e) {
            conflict.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 7. 额度边界：恰足可付，不足整笔拒绝且不核减
    // ------------------------------------------------------------------

    @Test
    void exactAvailableAmountIsPayable() {
        setupLc("LC-EXACT", "300.00");
        lcService.present(presentRequest("r-present-exact", "LC-EXACT", "P-EXACT", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-07T09:00:00"));
        PresentationResponse paid = lcService.pay(new PayRequest("r-pay-exact", "P-EXACT"));
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(lcService.getLc("LC-EXACT").amountAvailable()).isEqualByComparingTo("0.00");
    }

    @Test
    void insufficientAvailableAmountRejectsWholePaymentWithoutDeduction() {
        setupLc("LC-SHORT", "299.99");
        lcService.present(presentRequest("r-present-short", "LC-SHORT", "P-SHORT", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-07T09:00:00"));

        assertThatThrownBy(() -> lcService.pay(new PayRequest("r-pay-short", "P-SHORT")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INSUFFICIENT_AVAILABLE_AMOUNT"));

        assertThat(lcService.getLc("LC-SHORT").amountAvailable()).isEqualByComparingTo("299.99");
        assertThat(lcService.getPresentation("P-SHORT").status()).isEqualTo("DEEMED_ACCEPTED");
    }

    // ------------------------------------------------------------------
    // 7b. 并发同一 requestId 付款：唯一终态且只核减一次额度
    // ------------------------------------------------------------------

    @Test
    void concurrentSameKeyPaymentSettlesOnce() throws Exception {
        setupLc("LC-CONCUR-PAY", "1000.00");
        lcService.present(presentRequest("r-present-cp", "LC-CONCUR-PAY", "P-CPAY", null));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-07T09:00:00"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        lcService.pay(new PayRequest("same-pay-key", "P-CPAY"));
                        ok.incrementAndGet();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两次调用均返回首次结果（不抛错），但额度只核减一次，批次只有一个 PAID 终态。
        assertThat(ok.get()).isEqualTo(2);
        assertThat(errors.get()).isZero();
        assertThat(lcService.getPresentation("P-CPAY").status()).isEqualTo("PAID");
        assertThat(lcService.getLc("LC-CONCUR-PAY").amountAvailable()).isEqualByComparingTo("700.00");
    }

    // ------------------------------------------------------------------
    // 8. 交单有效期
    // ------------------------------------------------------------------
    @Test
    void presentationAfterLcExpiryIsRejected() {
        LocalDateTime expiry = LocalDateTime.parse("2026-06-10T00:00:00");
        lcService.createLc(new CreateLcRequest("LC-EXP", "Applicant", "Beneficiary",
                "USD", new BigDecimal("1000.00"), expiry));
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-11T10:00:00"));
        assertThatThrownBy(() -> lcService.present(
                presentRequest("r-present-exp", "LC-EXP", "P-EXP", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("LC_EXPIRED"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void setupLc(String lcNo, String amount) {
        lcService.createLc(new CreateLcRequest(lcNo, "APPLICANT-CO", "BENEFICIARY-CO",
                "USD", new BigDecimal(amount), LocalDateTime.parse("2027-12-31T00:00:00")));
    }

    private PresentRequest presentRequest(String requestId, String lcNo, String presentationNo,
            String supersedes) {
        return new PresentRequest(requestId, lcNo, presentationNo, supersedes,
                new BigDecimal("300.00"),
                List.of(new DocItem("BILL_OF_LADING", "BL-" + UUID.randomUUID(), "synthetic bl"),
                        new DocItem("COMMERCIAL_INVOICE", "CI-1", "synthetic invoice")));
    }

    private List<DiscrepancyInput> discrepancies(boolean unused) {
        return List.of(
                new DiscrepancyInput("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH", "发票金额与信用证不符"),
                new DiscrepancyInput("BILL_OF_LADING", "LATE_SHIPMENT", "迟装船"));
    }

    private List<WaiverItem> waiverItems() {
        return List.of(
                new WaiverItem("BILL_OF_LADING", "LATE_SHIPMENT"),
                new WaiverItem("COMMERCIAL_INVOICE", "AMOUNT_MISMATCH"));
    }
}
