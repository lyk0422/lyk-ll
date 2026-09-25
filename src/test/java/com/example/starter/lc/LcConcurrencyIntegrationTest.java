package com.example.starter.lc;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.dto.PayRequest;
import com.example.starter.lc.dto.PresentDocumentsRequest;
import com.example.starter.lc.dto.RefuseRequest;
import com.example.starter.lc.dto.SubmissionResponse;
import com.example.starter.lc.dto.WaiveDiscrepanciesRequest;
import com.example.starter.lc.repo.SubmissionRow;
import com.example.starter.lc.service.LcService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真实多线程并发 H2 测试：交单唯一受理、拒付/放弃/付款唯一终态、付款不重复核减。
 */
@SpringBootTest
class LcConcurrencyIntegrationTest extends AbstractLcIntegrationTest {

    private static final Instant PRESENTED_AT = Instant.parse("2026-09-18T10:00:00Z");

    private static final List<DocumentItem> DOCUMENTS = List.of(
            new DocumentItem("BILL_OF_LADING", "BL-2026-001"),
            new DocumentItem("COMMERCIAL_INVOICE", "INV-2026-009"));

    private static final List<DiscrepancyItem> DISCREPANCIES = List.of(
            new DiscrepancyItem("BILL_OF_LADING", "装船日期晚于信用证最迟装运日"),
            new DiscrepancyItem("COMMERCIAL_INVOICE", "发票金额与汇票金额不一致"));

    @Autowired
    private LcService lcService;

    @Override
    protected LcService service() {
        return lcService;
    }

    /** 两个受益人线程并发交单：恰有一个被受理为审单中，另一个得到 409。 */
    @Test
    void concurrentPresentationsAcceptExactlyOne() throws Exception {
        createDefaultLc("req-lc-1");
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 1; i <= threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        OperationResult result = lcService.present(new PresentDocumentsRequest(
                                "req-present-" + idx, LC_NO, new BigDecimal("8000.00"),
                                PRESENTED_AT, DOCUMENTS));
                        if (result instanceof OperationResult.Fresh fresh
                                && fresh.body() instanceof SubmissionResponse resp) {
                            return "CREATED:" + resp.status();
                        }
                        return "REPLAY";
                    } catch (LcBusinessException e) {
                        return "ERROR:" + e.getErrorCode().name();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = collect(futures);

            long created = outcomes.stream().filter(o -> o.startsWith("CREATED")).count();
            long blocked = outcomes.stream().filter(o -> o.endsWith("ALREADY_UNDER_REVIEW")).count();
            assertThat(created).isEqualTo(1);
            assertThat(blocked).isEqualTo(1);
            Integer batchCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM doc_submission WHERE lc_no = ?", Integer.class, LC_NO);
            assertThat(batchCount).isEqualTo(1);
            assertThat(row(LC_NO + "-1").status()).isEqualTo("UNDER_REVIEW");
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 有效拒付与全额放弃并发：行锁串行化，终态只能是 REFUSED 或 ACCEPTED 之一，
     * 绝不能出现既已拒付退单又付款，不符点数量始终与拒付内容一致。
     */
    @Test
    void refuseAndWaiverRaceProducesSingleTerminalState() throws Exception {
        // 重复执行多轮，提高两种交错顺序都被覆盖的概率。
        for (int round = 1; round <= 8; round++) {
            final int r = round;
            String lcNo = "LC-RACE-" + r;
            createDefaultLc("req-lc-" + r, lcNo, new BigDecimal("10000.00"));
            SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                    "req-present-" + r, lcNo, new BigDecimal("8000.00"),
                    PRESENTED_AT, DOCUMENTS)));

            String[] outcomes = runConcurrently(
                    () -> {
                        lcService.refuse(new RefuseRequest("req-refuse-" + r, batch.batchNo(),
                                Instant.parse("2026-09-24T10:00:00Z"), DISCREPANCIES));
                        return "REFUSE_OK";
                    },
                    () -> {
                        lcService.waive(new WaiveDiscrepanciesRequest(
                                "req-waive-" + r, batch.batchNo(), APPLICANT,
                                Instant.parse("2026-09-24T15:00:00Z"), DISCREPANCIES));
                        return "WAIVE_OK";
                    });

            SubmissionRow finalRow = row(batch.batchNo());
            // 放弃只有在拒付已提交后才可能成功；无论如何终态唯一且不会是 PAID。
            assertThat(List.of("REFUSED", "ACCEPTED")).contains(finalRow.status());
            assertThat(finalRow.paidAt()).isNull();
            long refuseOk = List.of(outcomes).stream().filter("REFUSE_OK"::equals).count();
            assertThat(refuseOk).isEqualTo(1);
            if ("ACCEPTED".equals(finalRow.status())) {
                assertThat(submissionRepository.findDiscrepancies(batch.batchNo())).hasSize(2);
                assertThat(submissionRepository.findDiscrepancies(batch.batchNo()))
                        .allMatch(d -> d.waived());
            } else {
                assertThat(submissionRepository.findDiscrepancies(batch.batchNo())).hasSize(2);
            }
            // 额度从未因拒付/放弃而变动。
            assertThat(lcRow(lcNo).availableAmount()).isEqualByComparingTo("10000.00");
        }
    }

    /** 迟发拒付（转视为接受）与付款并发：要么付款成功且只核减一次，要么停在视为接受。 */
    @Test
    void lateRefusalAndPaymentRaceHasSingleOutcome() throws Exception {
        for (int round = 1; round <= 8; round++) {
            final int r = round;
            String lcNo = "LC-LATE-" + r;
            createDefaultLc("req-lc-" + r, lcNo, new BigDecimal("10000.00"));
            SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                    "req-present-" + r, lcNo, new BigDecimal("8000.00"),
                    PRESENTED_AT, DOCUMENTS)));

            String[] outcomes = runConcurrently(
                    () -> {
                        OperationResult res = lcService.refuse(new RefuseRequest(
                                "req-refuse-" + r, batch.batchNo(),
                                Instant.parse("2026-09-25T18:00:00Z"), DISCREPANCIES));
                        return "REFUSE_LATE:" + ((OperationResult.Fresh) res).httpStatus();
                    },
                    () -> {
                        lcService.pay(new PayRequest("req-pay-" + r, batch.batchNo(),
                                Instant.parse("2026-09-26T09:00:00Z")));
                        return "PAY_OK";
                    });

            SubmissionRow finalRow = row(batch.batchNo());
            BigDecimal available = lcRow(lcNo).availableAmount();
            long payOk = List.of(outcomes).stream().filter("PAY_OK"::equals).count();
            if ("PAID".equals(finalRow.status())) {
                // 付款承兑为终态：最多一个线程核减成功，额度恰好核减一次。
                assertThat(payOk).isEqualTo(1);
                assertThat(available).isEqualByComparingTo("2000.00");
            } else {
                assertThat(finalRow.status()).isEqualTo("DEEMED_ACCEPTED");
                assertThat(payOk).isZero();
                assertThat(available).isEqualByComparingTo("10000.00");
            }
        }
    }

    /** 已接受批次并发付款：恰有一笔核减，重复付款不产生第二次扣减。 */
    @Test
    void concurrentPaymentsDeductOnlyOnce() throws Exception {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), DISCREPANCIES)));
        asSubmission(lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-1", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T15:00:00Z"), DISCREPANCIES)));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 1; i <= threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    lcService.pay(new PayRequest("req-pay-" + idx, batch.batchNo(),
                            Instant.parse("2026-09-24T16:00:00Z")));
                    return "PAID";
                }));
            }
            start.countDown();
            assertThat(collect(futures)).hasSize(threads).allMatch("PAID"::equals);
            assertThat(row(batch.batchNo()).status()).isEqualTo("PAID");
            // 4 个付款指令并发，额度只能核减一次。
            assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("2000.00");
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private interface RaceTask {
        String run() throws Exception;
    }

    private String[] runConcurrently(RaceTask first, RaceTask second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> f1 = pool.submit(() -> {
                start.await();
                return callOrErrorCode(first);
            });
            Future<String> f2 = pool.submit(() -> {
                start.await();
                return callOrErrorCode(second);
            });
            start.countDown();
            return new String[] {f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS)};
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String callOrErrorCode(RaceTask task) throws Exception {
        try {
            return task.run();
        } catch (LcBusinessException e) {
            return "BIZ_ERROR:" + e.getErrorCode().name();
        }
    }

    private List<String> collect(List<Future<String>> futures) throws Exception {
        List<String> outcomes = new java.util.ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(10, TimeUnit.SECONDS));
        }
        return outcomes;
    }
}
