package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.DrawLabelsRequest;
import com.example.starter.cosmetic.web.dto.DrawRecordResponse;
import com.example.starter.cosmetic.web.dto.DrawResponse;
import com.example.starter.cosmetic.web.dto.ExpectedDigestResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
 * 包装标签印次 H2 真实并发测试：
 * <ul>
 *   <li>并发领用同一印次：版本与余额由原子 UPDATE 判定，恰一个成功，张数守恒；</li>
 *   <li>领用与备案换版（禁用旧印次）竞争：终态一致，不留旧版生效后继续领用的凭据；</li>
 *   <li>并发销毁：恰一个成功；</li>
 *   <li>同一 requestId 并发领用：幂等重放，余额只扣一次。</li>
 * </ul>
 */
class LabelPrintConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private LabelPrintService labelPrintService;

    private void makeAdultEffective(String reqId, String product) {
        filingService.submit(FilingFixtures.ordinarySubmit(reqId, product));
        clock.setInstant(clock.instant().plus(11, java.time.temporal.ChronoUnit.DAYS));
        filingService.expireDuePublicNotices(null);
    }

    private void register(String reqId, String runNo, String product, int count) {
        ExpectedDigestResponse exp = labelPrintService.expectedDigest(product);
        labelPrintService.register(new RegisterPrintRunRequest(
                reqId, runNo, product, count, exp.labelDigest(), null, null));
    }

    /**
     * 100 张印次，两个线程各领 60 张（同 expectedVersion=1）：
     * 恰一个成功，另一个 409 版本冲突；最终已领 60、剩余 40，张数守恒。
     */
    @Test
    void concurrentDrawsExactlyOneWinsPerVersion() throws Exception {
        int rounds = 10;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            String product = "P-CD-" + round;
            String runNo = "PR-CD-" + round;
            makeAdultEffective("REQ-CF-" + round, product);
            register("REQ-CP-" + round, runNo, product, 100);

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable drawTask = () -> {
                try {
                    start.await();
                    labelPrintService.draw(runNo, new DrawLabelsRequest(
                            "REQ-CD-" + round + "-" + Thread.currentThread().getName(),
                            "BATCH-CD-" + round, 60, 1, null));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflicts.incrementAndGet();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread t1 = new Thread(drawTask, "A");
            Thread t2 = new Thread(drawTask, "B");
            t1.start();
            t2.start();
            start.countDown();
            t1.join(10_000);
            t2.join(10_000);

            assertThat(error.get()).isNull();
            assertThat(success.get()).as("第 %d 轮：恰一个领用成功", round).isEqualTo(1);
            assertThat(conflicts.get()).as("第 %d 轮：另一个版本冲突 409", round).isEqualTo(1);

            PrintRunResponse after = labelPrintService.getPrintRun(runNo);
            assertThat(after.drawnCount()).isEqualTo(60);
            assertThat(after.remainingCount()).isEqualTo(40);
            assertThat(after.drawnCount() + after.destroyedCount() + after.remainingCount())
                    .isEqualTo(after.printedCount());
        }
    }

    /**
     * 领用与备案换版（简易变更生效禁用旧印次）并发竞争：
     * 无论谁先谁后，终态一致——印次必为 DISABLED；若领用先成功则已领 70 且
     * 全部凭据追加风险关联，否则已领保持 60；不留禁用后继续领用的凭据。
     */
    @Test
    void drawVsDisableRaceLeavesConsistentState() throws Exception {
        int rounds = 10;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            String product = "P-RC-" + round;
            String runNo = "PR-RC-" + round;
            makeAdultEffective("REQ-RF-" + round, product);
            register("REQ-RP-" + round, runNo, product, 100);
            labelPrintService.draw(runNo, new DrawLabelsRequest(
                    "REQ-RD0-" + round, "BATCH-RC-" + round, 60, 1, null));

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger drawWon = new AtomicInteger();
            AtomicInteger drawRejected = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable changeTask = () -> {
                try {
                    start.await();
                    filingService.change(product,
                            FilingFixtures.packagingChange("REQ-RC-" + round, "合成换版包装V" + round));
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };
            Runnable drawTask = () -> {
                try {
                    start.await();
                    labelPrintService.draw(runNo, new DrawLabelsRequest(
                            "REQ-RD1-" + round, "BATCH-RC-" + round, 10, 2, null));
                    drawWon.incrementAndGet();
                } catch (UnprocessableException e) {
                    // 换版禁用已生效：领用被拒（PRINT_RUN_DISABLED）
                    assertThat(e.code()).isEqualTo("PRINT_RUN_DISABLED");
                    drawRejected.incrementAndGet();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread t1 = new Thread(changeTask, "change");
            Thread t2 = new Thread(drawTask, "draw");
            t1.start();
            t2.start();
            start.countDown();
            t1.join(10_000);
            t2.join(10_000);

            assertThat(error.get()).isNull();
            assertThat(drawWon.get() + drawRejected.get())
                    .as("第 %d 轮：领用恰有一种结局", round).isEqualTo(1);

            PrintRunResponse after = labelPrintService.getPrintRun(runNo);
            assertThat(after.status()).isEqualTo("DISABLED");
            int expectedDrawn = drawWon.get() == 1 ? 70 : 60;
            assertThat(after.drawnCount()).isEqualTo(expectedDrawn);
            // 张数守恒：印刷总数 = 已领 + 已毁 + 剩余（禁用剩余）
            assertThat(after.drawnCount() + after.destroyedCount() + after.remainingCount())
                    .isEqualTo(after.printedCount());
            assertThat(after.disabledRemainingCount()).isEqualTo(100 - expectedDrawn);

            // 全部已领凭据（含竞争中新领的）都追加了风险关联，历史未抹除
            List<DrawRecordResponse> draws = labelPrintService.listDraws(runNo);
            assertThat(draws).hasSize(drawWon.get() == 1 ? 2 : 1);
            assertThat(draws).allMatch(DrawRecordResponse::riskFlag);
            assertThat(draws.stream().mapToInt(DrawRecordResponse::quantity).sum())
                    .isEqualTo(expectedDrawn);

            // 终态后旧印次不得再领
            try {
                labelPrintService.draw(runNo, new DrawLabelsRequest(
                        "REQ-RD2-" + round, "BATCH-RC-" + round, 1, after.version(), null));
                org.assertj.core.api.Assertions.fail("禁用印次不应允许继续领用");
            } catch (UnprocessableException e) {
                assertThat(e.code()).isEqualTo("PRINT_RUN_DISABLED");
            }
        }
    }

    /**
     * 100 张印次，两个线程各销毁 60 张（同 expectedVersion=1）：
     * 恰一个成功，另一个 409；最终已毁 60、剩余 40。
     */
    @Test
    void concurrentDestroysExactlyOneWins() throws Exception {
        int rounds = 10;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            String product = "P-CX-" + round;
            String runNo = "PR-CX-" + round;
            makeAdultEffective("REQ-XF-" + round, product);
            register("REQ-XP-" + round, runNo, product, 100);

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable destroyTask = () -> {
                try {
                    start.await();
                    labelPrintService.destroy(runNo, new DestroyLabelsRequest(
                            "REQ-CX-" + round + "-" + Thread.currentThread().getName(), 60, 1, null));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflicts.incrementAndGet();
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread t1 = new Thread(destroyTask, "A");
            Thread t2 = new Thread(destroyTask, "B");
            t1.start();
            t2.start();
            start.countDown();
            t1.join(10_000);
            t2.join(10_000);

            assertThat(error.get()).isNull();
            assertThat(success.get()).as("第 %d 轮：恰一个销毁成功", round).isEqualTo(1);
            assertThat(conflicts.get()).as("第 %d 轮：另一个版本冲突 409", round).isEqualTo(1);

            PrintRunResponse after = labelPrintService.getPrintRun(runNo);
            assertThat(after.destroyedCount()).isEqualTo(60);
            assertThat(after.remainingCount()).isEqualTo(40);
        }
    }

    /**
     * 同一 requestId 同参数并发领用：只有一笔领用凭据、余额只扣一次，
     * 两个调用方都拿到首次结果（一个首次、一个重放）。
     */
    @Test
    void concurrentSameRequestDrawIsIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int r = 0; r < 6; r++) {
                final int round = r;
                String product = "P-CI-" + round;
                String runNo = "PR-CI-" + round;
                makeAdultEffective("REQ-IF-" + round, product);
                register("REQ-IP-" + round, runNo, product, 100);

                String requestId = "REQ-ID-" + round;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<DrawResponse> responses =
                        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(labelPrintService.draw(runNo, new DrawLabelsRequest(
                                requestId, "BATCH-CI-" + round, 10, 1, null)));
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

                assertThat(finished).as("第 %d 轮并发领用应在超时前完成", r).isTrue();
                assertThat(error.get()).isNull();
                assertThat(responses).hasSize(2);
                assertThat(responses.stream().filter(DrawResponse::idempotentReplay).count())
                        .isEqualTo(1);
                assertThat(responses.stream().filter(d -> !d.idempotentReplay()).count())
                        .isEqualTo(1);

                PrintRunResponse after = labelPrintService.getPrintRun(runNo);
                assertThat(after.drawnCount()).isEqualTo(10);
                assertThat(labelPrintService.listDraws(runNo)).hasSize(1);
                Integer idemCount = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM idempotency_record WHERE request_id = ?",
                        Integer.class, requestId);
                assertThat(idemCount).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
