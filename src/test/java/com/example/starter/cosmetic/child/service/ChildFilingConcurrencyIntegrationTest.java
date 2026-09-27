package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;
import com.example.starter.cosmetic.child.domain.ChildRevisionStatus;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.child.web.dto.ChildChangeRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectionRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.testsupport.ChildFilingFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * 儿童分龄备案 H2 真实并发测试：
 * <ul>
 *   <li>补正使旧修订失效 vs 公示期满生效并发竞争，必须形成唯一结果；</li>
 *   <li>同一 requestId 并发提交，仅落一条备案且两个调用都拿到首次冻结结果；</li>
 *   <li>相同 expectedVersion 并发变更，仅一个成功（版本乐观锁）。</li>
 * </ul>
 */
class ChildFilingConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private ChildFilingService childFilingService;

    @Autowired
    private ChildPublicityEffector childPublicityEffector;

    @Autowired
    private ChildFilingPort childFilingPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    /**
     * 恰公示期满时刻同时发起“补正（使旧修订待审批准失效）”与“期满生效”，
     * 多轮执行确认：不得既失效又生效，结果唯一。
     */
    @Test
    void correctionAndExpiryRaceProducesExactlyOneOutcome() throws Exception {
        int rounds = 20;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            final String product = "PC-RACE-" + round;
            ChildFilingResultResponse submitted = childFilingService.submit(
                    ChildFilingFixtures.lowBandSubmit("REQ-CRACE-" + round, product));
            String filingNo = submitted.filing().filingNo();
            long revisionId = childFilingPort.findRevision(filingNo, 1).orElseThrow().id();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger correctionWins = new AtomicInteger();
            AtomicInteger expiryWins = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable correctionTask = () -> {
                try {
                    start.await();
                    ChildCorrectionRequest correction = ChildFilingFixtures.correctionOf(
                            "REQ-CRACE-C-" + round, 1,
                            ChildFilingFixtures.lowBandSubmit("REQ-X", product));
                    childFilingService.correct(filingNo, correction);
                    correctionWins.incrementAndGet();
                } catch (BusinessRejectionException e) {
                    // 期满分支已获胜：补正必然被拒（唯一结果），不计入异常
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };
            Runnable expiryTask = () -> {
                try {
                    start.await();
                    ChildRevisionRecord revision =
                            childFilingPort.findRevision(filingNo, 1).orElseThrow();
                    if (childPublicityEffector.effectuate(revision, EXPIRES_EXPECTED)) {
                        expiryWins.incrementAndGet();
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread correctionThread = new Thread(correctionTask, "correction-" + round);
            Thread expiryThread = new Thread(expiryTask, "expiry-" + round);
            correctionThread.start();
            expiryThread.start();
            start.countDown();
            correctionThread.join(10_000);
            expiryThread.join(10_000);

            assertThat(error.get()).isNull();
            assertThat(correctionWins.get() + expiryWins.get())
                    .as("第 %d 轮：补正与期满必须恰好一个获胜", round)
                    .isEqualTo(1);

            ChildRevisionRecord revision1 = childFilingPort.findRevision(filingNo, 1).orElseThrow();
            if (expiryWins.get() == 1) {
                assertThat(revision1.status()).isEqualTo(ChildRevisionStatus.EFFECTIVE);
                assertThat(childFilingPort.findFiling(filingNo).orElseThrow().status())
                        .isEqualTo(ChildFilingStatus.EFFECTIVE);
                assertThat(childFilingPort.findRevisions(filingNo)).hasSize(1);
            } else {
                assertThat(revision1.status()).isEqualTo(ChildRevisionStatus.INVALIDATED);
                assertThat(childFilingPort.findRevisions(filingNo)).hasSize(2);
                assertThat(childFilingPort.findRevision(filingNo, 2).orElseThrow().status())
                        .isEqualTo(ChildRevisionStatus.PUBLIC);
                assertThat(childFilingPort.findFiling(filingNo).orElseThrow().versionNo())
                        .isEqualTo(2);
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
                String product = "PC-CONC-" + round;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<ChildFilingResultResponse> responses =
                        Collections.synchronizedList(new ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(childFilingService.submit(
                                ChildFilingFixtures.lowBandSubmit(requestId, product)));
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

                long replays = responses.stream().filter(ChildFilingResultResponse::idempotentReplay).count();
                long initials = responses.stream().filter(r -> !r.idempotentReplay()).count();
                assertThat(replays).isEqualTo(1);
                assertThat(initials).isEqualTo(1);
                assertThat(responses.get(0).filing().filingNo())
                        .isEqualTo(responses.get(1).filing().filingNo());
                assertThat(childFilingPort.findRevisions(responses.get(0).filing().filingNo()))
                        .hasSize(1);
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
     * 相同 expectedVersion 并发简易变更：版本乐观锁保证仅一个成功，
     * 最终版本恰好 +1，旧生效修订恰好被替代一次。
     */
    @Test
    void concurrentChangesWithSameExpectedVersionHaveSingleWinner() throws Exception {
        childFilingService.submit(ChildFilingFixtures.lowBandSubmit("REQ-CV-1", "PC-V-RACE"));
        clock.setInstant(EXPIRES_EXPECTED);
        childFilingService.expireDuePublicities(null);
        String filingNo = "SYN-C-000001";

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Runnable changeA = () -> runChange(filingNo, "REQ-CV-2", "包装A", start, success, rejections, error);
        Runnable changeB = () -> runChange(filingNo, "REQ-CV-3", "包装B", start, success, rejections, error);

        Thread t1 = new Thread(changeA);
        Thread t2 = new Thread(changeB);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(error.get()).isNull();
        assertThat(success.get() + rejections.get()).isEqualTo(2);
        assertThat(success.get()).isEqualTo(1);
        // 版本恰好 +1，旧修订恰好被替代一次，生效修订唯一
        assertThat(childFilingPort.findFiling(filingNo).orElseThrow().versionNo()).isEqualTo(2);
        assertThat(childFilingPort.findRevision(filingNo, 1).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.SUPERSEDED);
        assertThat(childFilingPort.findRevision(filingNo, 2).orElseThrow().status())
                .isEqualTo(ChildRevisionStatus.EFFECTIVE);
    }

    private void runChange(String filingNo, String requestId, String packaging, CountDownLatch start,
                           AtomicInteger success, AtomicInteger rejections,
                           AtomicReference<Throwable> error) {
        try {
            start.await();
            childFilingService.change(filingNo, new ChildChangeRequest(requestId, 1, "SIMPLE",
                    null, null, null, null, null, null, null, packaging, null));
            success.incrementAndGet();
        } catch (BusinessRejectionException e) {
            // 版本乐观锁/唯一生效修订竞争落败：业务拒绝（HTTP 层映射 422）
            rejections.incrementAndGet();
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
    }
}
