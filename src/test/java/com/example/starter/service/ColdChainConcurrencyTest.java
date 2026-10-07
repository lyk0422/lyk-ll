package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.ColdChainCorrectRequest;
import com.example.starter.dto.ColdChainReadingInput;
import com.example.starter.dto.ColdChainReadingsSubmitRequest;
import com.example.starter.dto.ColdChainSegmentInput;
import com.example.starter.dto.ColdChainSegmentReadingsInput;
import com.example.starter.dto.ColdChainSegmentRegisterRequest;
import com.example.starter.dto.ColdChainView;
import com.example.starter.dto.IssueRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链 H2 真实并发测试：
 * 1) 更正与签发竞争：由 review 行锁唯一序列化——更正先提交则签发被温证门禁阻断、
 *    须重核新版本；签发先成则更正仅登记风险，不回改旧证书；
 * 2) 同 requestId 并发提交：唯一受理，另一请求重放同一结果。
 */
class ColdChainConcurrencyTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant SEG_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant SEG_MID = Instant.parse("2026-09-01T00:10:00Z");
    private static final Instant SEG_END = Instant.parse("2026-09-01T00:20:00Z");
    private static final Instant SUBMIT_AT = Instant.parse("2026-09-01T03:00:00Z");

    private static List<ColdChainSegmentInput> twoSegments() {
        return List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_MID),
                new ColdChainSegmentInput(2, SEG_MID, SEG_END));
    }

    private static List<ColdChainSegmentInput> threeSegments() {
        return List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_MID),
                new ColdChainSegmentInput(2, SEG_MID, SEG_END),
                new ColdChainSegmentInput(3, SEG_END, SEG_END.plusSeconds(600)));
    }

    private static ColdChainReadingInput reading(int seq, Instant at, String temperature) {
        return new ColdChainReadingInput(seq, at, new BigDecimal(temperature));
    }

    private static List<ColdChainSegmentReadingsInput> qualifiedReadings() {
        return List.of(
                new ColdChainSegmentReadingsInput(1, List.of(
                        reading(1, SEG_START, "2.0"),
                        reading(2, SEG_START.plusSeconds(300), "5.0"),
                        reading(3, SEG_MID, "8.0"))),
                new ColdChainSegmentReadingsInput(2, List.of(
                        reading(1, SEG_MID, "4.0"),
                        reading(2, SEG_MID.plusSeconds(300), "6.0"),
                        reading(3, SEG_END, "2.0"))));
    }

    /** 准备可签发且温证合格的受控子批（版本 1）。 */
    private void prepareQualifiedControlledBatch(String batchNo, String idPrefix) {
        clock.setFixed(T0);
        reviewService.declare(declareRequest(idPrefix + "-d", batchNo,
                allDocuments(), boundaryPassingInspections()));
        reviewService.evaluate(batchNo);
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity(batchNo);
        coldChainService.registerSegments(
                new ColdChainSegmentRegisterRequest(idPrefix + "-reg", batchNo, twoSegments(), SUBMIT_AT));
        coldChainService.submitReadings(new ColdChainReadingsSubmitRequest(
                idPrefix + "-sub", batchNo, 1, qualifiedReadings(), SUBMIT_AT));
    }

    @Test
    void correctionAndIssueRaceHasExactlyOneWinnerAcrossRounds() throws Exception {
        for (int round = 0; round < 10; round++) {
            final String batchNo = "B-CC-RACE-" + round;
            final String idPrefix = "race-" + round;
            prepareQualifiedControlledBatch(batchNo, idPrefix);
            clock.setFixed(ISSUE_AT);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Integer> correctFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return coldChainService.correct(new ColdChainCorrectRequest(
                            idPrefix + "-cor", batchNo, 1, threeSegments(),
                            SUBMIT_AT.plusSeconds(600))).httpStatus();
                } catch (BusinessException ex) {
                    return ex.getHttpStatus();
                }
            });
            Future<Integer> issueFuture = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return reviewService.issue(batchNo, new IssueRequest(idPrefix + "-issue")).httpStatus();
            });

            int correctStatus = correctFuture.get(30, TimeUnit.SECONDS);
            int issueStatus = issueFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            ColdChainView view = coldChainService.getView(batchNo);
            if (issueStatus == 200) {
                // 签发先成：更正仅登记风险，版本、温证与审查终态均不回改。
                assertEquals(200, correctStatus, "签发先成时更正仅以风险登记完成");
                assertEquals(1, view.version(), "签发后更正当不得递增版本");
                assertEquals(1, view.qualifiedVersion());
                assertTrue(view.certificateValid(), "旧温证保持不变");
                assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview(batchNo).status());
                assertTrue(reviewService.listEvents(batchNo).stream()
                                .anyMatch(e -> e.get("eventType").equals("COLD_CHAIN_CORRECTION_AFTER_ISSUE")),
                        "签发先成须登记风险事件");
            } else {
                // 更正先提交：签发被温证门禁阻断，须先重核新版本。
                assertEquals(422, issueStatus, "更正先成时签发须被温证门禁阻断");
                assertEquals(200, correctStatus);
                assertEquals(2, view.version(), "更正先成版本加 1");
                assertFalse(view.certificateValid(), "旧温证资格失效");
                assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(),
                        reviewService.getReview(batchNo).status(), "拒签不得改变审查状态");

                // 重核新版本后签发放行，验证“先重核新版本”路径。
                List<ColdChainSegmentReadingsInput> newReadings = new ArrayList<>(qualifiedReadings());
                newReadings.add(new ColdChainSegmentReadingsInput(3, List.of(
                        reading(1, SEG_END, "3.0"),
                        reading(2, SEG_END.plusSeconds(300), "4.0"),
                        reading(3, SEG_END.plusSeconds(600), "5.0"))));
                assertEquals(200, coldChainService.submitReadings(new ColdChainReadingsSubmitRequest(
                        idPrefix + "-resub", batchNo, 2, newReadings, SUBMIT_AT)).httpStatus());
                assertEquals(200, reviewService.issue(batchNo,
                        new IssueRequest(idPrefix + "-issue-2")).httpStatus(), "重核合格后可签发");
            }
        }
    }

    @Test
    void concurrentSameRequestIdSubmissionsSerializeToSingleEffect() throws Exception {
        final String batchNo = "B-CC-IDEM-RACE";
        prepareQualifiedControlledBatch(batchNo, "idem-race");
        // 更正使版本到 2，温证失效，两个并发同键提交竞争重核。
        coldChainService.correct(new ColdChainCorrectRequest(
                "idem-race-cor", batchNo, 1, twoSegments(), SUBMIT_AT.plusSeconds(60)));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return coldChainService.submitReadings(new ColdChainReadingsSubmitRequest(
                        "idem-race-resub", batchNo, 2, qualifiedReadings(), SUBMIT_AT)).httpStatus();
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertTrue(statuses.stream().allMatch(s -> s == 200),
                "同键同参并发提交全部重放成功: " + statuses);
        ColdChainView view = coldChainService.getView(batchNo);
        assertTrue(view.certificateValid());
        assertEquals(2, view.qualifiedVersion(), "并发同键只产生一次重核效果");
        Integer readingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cold_chain_reading cr"
                        + " JOIN cold_chain_ledger cl ON cl.id = cr.ledger_id"
                        + " WHERE cl.batch_no = ? AND cr.ledger_version = 2",
                Integer.class, batchNo);
        assertEquals(6, readingCount, "并发同键不得重复写入读数");
    }
}
