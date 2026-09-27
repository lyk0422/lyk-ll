package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.ChildrenFilingRecord;
import com.example.starter.cosmetic.domain.ChildrenFilingStatus;
import com.example.starter.cosmetic.port.ChildrenFilingPort;
import com.example.starter.cosmetic.testsupport.ChildrenFixtures;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResultResponse;
import com.example.starter.cosmetic.web.dto.CorrectChildrenFilingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 儿童备案 H2 真实并发测试：
 * <ul>
 *   <li>材料补正 vs 公示期满生效并发竞争，原子且唯一；</li>
 *   <li>同一 requestId 并发提交，仅落一条备案且两个调用都拿到首次冻结结果；</li>
 *   <li>不同 requestId 并发补正，仅一个新修订生效。</li>
 * </ul>
 */
class ChildrenFilingConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private ChildrenFilingService childrenFilingService;

    @Autowired
    private ChildrenPublicNoticeEffector publicNoticeEffector;

    @Autowired
    private ChildrenFilingPort childrenFilingPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    private CorrectChildrenFilingRequest correction(String requestId, int expectedVersion) {
        return new CorrectChildrenFilingRequest(requestId, expectedVersion, null, null, null,
                null, null,
                List.of(ChildrenFixtures.infantSafetyReport("SR-I-" + requestId),
                        ChildrenFixtures.childSafetyReport("SR-C-" + requestId)),
                null);
    }

    /**
     * 恰第 10 日 24:00(UTC) 同时发起“材料补正”和“公示期满生效”，
     * 多轮执行确认：旧修订不得既失效又生效，终态唯一。
     */
    @Test
    void correctionAndExpiryRaceProducesExactlyOneOutcome() throws Exception {
        int rounds = 20;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            final String product = "P-CRACE-" + round;
            ChildrenFilingResultResponse submitted = childrenFilingService.submit(
                    ChildrenFixtures.crossBandSubmit("REQ-CRACE-" + round, product));
            String filingNo = submitted.filing().filingNo();
            long oldRevisionId = childrenFilingPort.findLatestByNo(filingNo).orElseThrow().id();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger correctionWins = new AtomicInteger();
            AtomicInteger expiryWins = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable correctionTask = () -> {
                try {
                    start.await();
                    childrenFilingService.correct(filingNo, correction("REQ-CRACE-C" + round, 1));
                    correctionWins.incrementAndGet();
                } catch (ConflictException | UnprocessableException e) {
                    // 期满分支已获胜：补正必然被拒（竞态唯一），不计入异常
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };
            Runnable expiryTask = () -> {
                try {
                    start.await();
                    ChildrenFilingRecord record = childrenFilingPort.findById(oldRevisionId).orElseThrow();
                    if (publicNoticeEffector.effectuate(record, EXPIRES_EXPECTED)) {
                        expiryWins.incrementAndGet();
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread t1 = new Thread(correctionTask, "correction-" + round);
            Thread t2 = new Thread(expiryTask, "expiry-" + round);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(10_000);
            t2.join(10_000);

            assertThat(error.get()).isNull();
            assertThat(correctionWins.get() + expiryWins.get())
                    .as("第 %d 轮：补正与期满必须恰好一个获胜", round)
                    .isEqualTo(1);

            ChildrenFilingRecord oldRevision = childrenFilingPort.findById(oldRevisionId).orElseThrow();
            if (expiryWins.get() == 1) {
                assertThat(oldRevision.status()).isEqualTo(ChildrenFilingStatus.EFFECTIVE);
                assertThat(childrenFilingPort.findActiveSlot(product)).isPresent();
            } else {
                assertThat(oldRevision.status()).isEqualTo(ChildrenFilingStatus.REVISION_SUPERSEDED);
                // 新修订成为唯一公示版本
                List<ChildrenFilingRecord> publicRevisions = childrenFilingPort.findByProduct(product)
                        .stream().filter(x -> x.status() == ChildrenFilingStatus.PUBLIC).toList();
                assertThat(publicRevisions).hasSize(1);
                assertThat(publicRevisions.get(0).versionNo()).isEqualTo(2);
            }
        }
    }

    /**
     * 同一 requestId 同参数并发提交：数据库主键冲突 + 等待回填保证只有一条备案，
     * 两个调用方都返回同一首次冻结结果。
     */
    @Test
    void concurrentSameRequestSubmissionsAreIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String requestId = "REQ-CCONC-" + round;
                String product = "P-CCONC-" + round;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<ChildrenFilingResultResponse> responses =
                        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(childrenFilingService.submit(
                                ChildrenFixtures.crossBandSubmit(requestId, product)));
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                };

                Future<?> f1 = pool.submit(task);
                Future<?> f2 = pool.submit(task);
                start.countDown();
                boolean finished = done.await(30, TimeUnit.SECONDS);
                f1.get(1, TimeUnit.SECONDS);
                f2.get(1, TimeUnit.SECONDS);

                assertThat(finished).as("第 %d 轮并发提交应在超时前完成", round).isTrue();
                assertThat(error.get()).isNull();
                assertThat(responses).hasSize(2);
                long replays = responses.stream().filter(ChildrenFilingResultResponse::idempotentReplay).count();
                assertThat(replays).isEqualTo(1);
                assertThat(responses.get(0).filing().filingNo())
                        .isEqualTo(responses.get(1).filing().filingNo());
                assertThat(childrenFilingPort.findByProduct(product)).hasSize(1);
                Integer idemCount = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM idempotency_record WHERE request_id = ?",
                        Integer.class, requestId);
                assertThat(idemCount).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 不同 requestId 并发补正同一公示修订：仅一个新修订生成，旧修订恰好失效一次。
     */
    @Test
    void concurrentCorrectionsLeaveSingleNewRevision() throws Exception {
        ChildrenFilingResultResponse submitted =
                childrenFilingService.submit(ChildrenFixtures.crossBandSubmit("REQ-CC-1", "P-CC-1"));
        String filingNo = submitted.filing().filingNo();

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Runnable taskA = () -> runCorrection(filingNo, "REQ-CC-2", start, success, rejected, error);
        Runnable taskB = () -> runCorrection(filingNo, "REQ-CC-3", start, success, rejected, error);
        Thread t1 = new Thread(taskA);
        Thread t2 = new Thread(taskB);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(error.get()).isNull();
        assertThat(success.get() + rejected.get()).isEqualTo(2);
        assertThat(success.get()).isEqualTo(1);

        List<ChildrenFilingRecord> all = childrenFilingPort.findByProduct("P-CC-1");
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(x -> x.status() == ChildrenFilingStatus.PUBLIC)).hasSize(1);
        assertThat(all.stream().filter(x -> x.status() == ChildrenFilingStatus.REVISION_SUPERSEDED))
                .hasSize(1);
        assertThat(childrenFilingPort.currentVersion("P-CC-1")).isEqualTo(2);
    }

    private void runCorrection(String filingNo, String requestId, CountDownLatch start,
                               AtomicInteger success, AtomicInteger rejected,
                               AtomicReference<Throwable> error) {
        try {
            start.await();
            childrenFilingService.correct(filingNo, correction(requestId, 1));
            success.incrementAndGet();
        } catch (ConflictException | UnprocessableException e) {
            // 另一补正已获胜：版本冲突或旧修订已失效，本次拒绝
            rejected.incrementAndGet();
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
    }
}
