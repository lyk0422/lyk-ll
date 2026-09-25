package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.catalog.DocumentCatalog;
import com.example.starter.domain.PublicityStatus;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.LateDocumentRequest;
import com.example.starter.dto.ReviewView;
import com.example.starter.dto.ServiceResult;
import com.example.starter.time.RaceBusinessClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 真实并发测试：
 * 1) 同一批号同时刻并发申报仅唯一受理；
 * 2) 公示期满生效与异议受理并发，行锁保证唯一终态，不得既签发又受理异议。
 */
@Import(ReviewConcurrencyTest.RaceClockConfig.class)
class ReviewConcurrencyTest extends AbstractIntegrationTest {

    /** 本测试类专用：竞态时钟作为主 BusinessClock，支持按线程标签返回时刻。 */
    @TestConfiguration
    static class RaceClockConfig {
        @Bean
        @Primary
        RaceBusinessClock raceBusinessClock() {
            return new RaceBusinessClock();
        }
    }

    @Autowired
    private RaceBusinessClock raceClock;

    @Test
    void concurrentDeclarationsOfSameBatchAcceptExactlyOne() throws Exception {
        raceClock.setFallback(Instant.parse("2026-09-01T00:00:00Z"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<ServiceResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                DeclareRequest request = declareRequest("race-declare-" + index, "B-RACE-DECLARE",
                        allDocuments(), boundaryPassingInspections());
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return reviewService.declare(request);
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发申报必须在超时前全部返回");
        pool.shutdown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<ServiceResult> future : futures) {
            statuses.add(future.get(10, TimeUnit.SECONDS).httpStatus());
        }
        long accepted = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.stream().filter(s -> s == 409).count();
        assertEquals(1, accepted, "同一批号并发申报只允许唯一受理");
        assertEquals(threads - 1, rejected, "其余并发申报必须 409 落败");
        assertEquals(1, countByBatch("B-RACE-DECLARE"));
    }

    @Test
    void publicitySettlementAndObjectionRaceProducesSingleTerminalState() throws Exception {
        // 多轮竞态：异议线程时间为期满前 1 秒（仍在公示期内），生效线程时间恰为期满日界。
        // 谁先拿到行锁谁成为唯一终态；另一个操作必须失败，绝不能两个都成功。
        for (int round = 0; round < 10; round++) {
            String batchNo = "B-RACE-PUB-" + round;
            raceClock.setFallback(Instant.parse("2026-09-07T00:00:00Z"));
            reviewService.declare(declareRequest("race-pub-d-" + round, batchNo,
                    allDocuments(), boundaryPassingInspections()));
            reviewService.evaluate(batchNo);

            ReviewView before = reviewService.getReview(batchNo);
            LocalDate endUtc = before.publicityEndUtc();
            Instant boundary = endUtc.atStartOfDay(ZoneOffset.UTC).toInstant();
            raceClock.setTime("objection", boundary.minusSeconds(1));
            raceClock.setTime("settle", boundary);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Outcome> objectionFuture = pool.submit(() ->
                    runTagged("objection", barrier, () -> {
                        try {
                            reviewService.acceptObjection(batchNo);
                            return Outcome.SUCCESS;
                        } catch (BusinessException ex) {
                            return Outcome.FAILED;
                        }
                    }));
            Future<Outcome> settleFuture = pool.submit(() ->
                    runTagged("settle", barrier, () -> {
                        try {
                            reviewService.settlePublicity(batchNo);
                            return Outcome.SUCCESS;
                        } catch (BusinessException ex) {
                            return Outcome.FAILED;
                        }
                    }));

            Outcome objectionResult = objectionFuture.get(30, TimeUnit.SECONDS);
            Outcome settleResult = settleFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            // 关键断言：两个操作不能同时成功
            assertTrue(!(objectionResult == Outcome.SUCCESS && settleResult == Outcome.SUCCESS),
                    "第 " + round + " 轮：异议受理与期满生效不得同时成功");
            assertTrue(objectionResult == Outcome.SUCCESS || settleResult == Outcome.SUCCESS,
                    "第 " + round + " 轮：必须恰好有一个操作成功形成终态");

            ReviewView after = reviewService.getReview(batchNo);
            String publicityStatus = after.publicity().status();
            if (objectionResult == Outcome.SUCCESS) {
                assertEquals(ReviewStatus.OBJECTION_REVIEW.name(), after.status(),
                        "第 " + round + " 轮：异议胜出则唯一终态为异议复核");
                assertEquals(PublicityStatus.OBJECTION_REVIEW.name(), publicityStatus);
                assertEquals(Outcome.FAILED, settleResult);
            } else {
                assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), after.status(),
                        "第 " + round + " 轮：生效胜出则唯一终态为期满生效");
                assertEquals(PublicityStatus.EFFECTIVE.name(), publicityStatus);
                assertEquals(Outcome.FAILED, objectionResult);
            }
            assertEquals(1, countPublicity(batchNo), "公示记录只能有一行，不得重复生效");
        }
    }

    @Test
    void archivedBatchRecordsLateDocumentsWithoutChangingTerminalState() {
        raceClock.setFallback(Instant.parse("2026-09-07T00:00:00Z"));
        reviewService.declare(declareRequest("arch-d", "B-ARCH-1",
                allDocuments(), boundaryPassingInspections()));
        reviewService.evaluate("B-ARCH-1");
        raceClock.setFallback(Instant.parse("2026-09-14T00:00:00Z"));
        reviewService.settlePublicity("B-ARCH-1");
        reviewService.issue("B-ARCH-1", new com.example.starter.dto.IssueRequest("arch-i"));

        Integer eventsBefore = countLateEvents("B-ARCH-1");
        ServiceResult late = reviewService.reportLateDocument("B-ARCH-1",
                new LateDocumentRequest(List.of(DocumentCatalog.STABILITY_REPORT, DocumentCatalog.BATCH_RECORD)));
        assertEquals(200, late.httpStatus());

        ReviewView view = reviewService.getReview("B-ARCH-1");
        assertEquals(ReviewStatus.ISSUED.name(), view.status(), "迟到资料不得改变签发归档终态");
        assertEquals(eventsBefore + 1, countLateEvents("B-ARCH-1"), "迟到资料只登记一条迟到事件");

        var events = reviewService.listEvents("B-ARCH-1");
        assertTrue(events.stream().anyMatch(e -> e.get("eventType").equals("LATE_DOCUMENT")));
    }

    private Outcome runTagged(String tag, CyclicBarrier barrier, java.util.concurrent.Callable<Outcome> action) {
        raceClock.tagCurrentThread(tag);
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return action.call();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            raceClock.tagCurrentThread(null);
        }
    }

    private int countByBatch(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_review WHERE batch_no = ?", Integer.class, batchNo);
        return count == null ? 0 : count;
    }

    private int countPublicity(String batchNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM publicity_record p
                JOIN batch_review r ON r.id = p.review_id
                WHERE r.batch_no = ?
                """, Integer.class, batchNo);
        return count == null ? 0 : count;
    }

    private Integer countLateEvents(String batchNo) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_event e
                JOIN batch_review r ON r.id = e.review_id
                WHERE r.batch_no = ? AND e.event_type = 'LATE_DOCUMENT'
                """, Integer.class, batchNo);
    }

    private enum Outcome {
        SUCCESS, FAILED, NEITHER
    }
}
