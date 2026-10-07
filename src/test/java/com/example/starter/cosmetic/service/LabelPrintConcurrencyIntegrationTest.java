package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.testsupport.LabelPrintFixtures;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.IssueLabelsRequest;
import com.example.starter.cosmetic.web.dto.LabelIssueResultResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包装标签印次台账 H2 真实并发测试：
 * <ul>
 *   <li>并发领用不超未领余额（版本谓词原子扣减，输家 409/422）；</li>
 *   <li>同一 requestId 并发领用幂等：仅一条凭据；</li>
 *   <li>新旧修订生效与领用竞争：不留旧版生效后继续领用的凭据，已领仅追加风险关联；</li>
 *   <li>并发领用与销毁：张数守恒，已领部分不能销毁退回余额。</li>
 * </ul>
 */
class LabelPrintConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private LabelPrintService labelPrintService;

    /**
     * 两个线程各领 60 张（印次共 100 张）：恰好一个成功，余额不为负；
     * 输家用新版本号重试仍因余额不足 422。
     */
    @Test
    void concurrentIssuesNeverExceedBalance() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                String product = "P-LC-BAL-" + round;
                String runNo = "RUN-LC-BAL-" + round;
                LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-LCB-S-" + round, product);
                labelPrintService.register(new RegisterPrintRunRequest(
                        "REQ-LCB-R-" + round, runNo, product, LabelPrintFixtures.adultDigest(null), null, 100));

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicInteger success = new AtomicInteger();
                AtomicInteger rejected = new AtomicInteger();
                AtomicReference<Throwable> error = new AtomicReference<>();

                final int roundNo = round;
                Runnable task = () -> {
                    try {
                        start.await();
                        labelPrintService.issue(runNo, new IssueLabelsRequest(
                                "REQ-LCB-I-" + roundNo + "-" + Thread.currentThread().threadId(),
                                "BATCH-LCB-" + roundNo, 60, 1));
                        success.incrementAndGet();
                    } catch (ConflictException | UnprocessableException e) {
                        rejected.incrementAndGet();
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

                assertThat(finished).as("第 %d 轮并发领用应在超时前完成", round).isTrue();
                assertThat(error.get()).isNull();
                assertThat(success.get()).isEqualTo(1);
                assertThat(rejected.get()).isEqualTo(1);

                PrintRunResponse run = labelPrintService.getPrintRun(runNo);
                assertThat(run.issuedCount()).isEqualTo(60);
                assertThat(run.remainingCount()).isEqualTo(40);
                // 守恒
                assertThat(run.printedCount())
                        .isEqualTo(run.issuedCount() + run.destroyedCount() + run.remainingCount());
                // 输家用当前版本号重试 60 张：余额不足 422
                assertThatThrownBy(() -> labelPrintService.issue(runNo,
                        new IssueLabelsRequest("REQ-LCB-RETRY-" + roundNo, "BATCH-LCB-" + roundNo,
                                60, run.versionNo())))
                        .isInstanceOf(UnprocessableException.class)
                        .hasMessageContaining("未领余额不足");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 同一 requestId 同参数并发领用：仅落一条凭据，双方拿到同一首次结果。
     */
    @Test
    void concurrentSameRequestIssueIsIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                String product = "P-LC-IDEM-" + round;
                String runNo = "RUN-LC-IDEM-" + round;
                LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-LCI-S-" + round, product);
                labelPrintService.register(new RegisterPrintRunRequest(
                        "REQ-LCI-R-" + round, runNo, product, LabelPrintFixtures.adultDigest(null), null, 100));

                IssueLabelsRequest request = new IssueLabelsRequest(
                        "REQ-LCI-I-" + round, "BATCH-LCI-" + round, 60, 1);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<LabelIssueResultResponse> responses =
                        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(labelPrintService.issue(runNo, request));
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

                assertThat(finished).isTrue();
                assertThat(error.get()).isNull();
                assertThat(responses).hasSize(2);
                long replays = responses.stream().filter(LabelIssueResultResponse::idempotentReplay).count();
                assertThat(replays).isEqualTo(1);
                assertThat(responses.get(0).issue().issueId())
                        .isEqualTo(responses.get(1).issue().issueId());
                // 仅一条凭据、只扣减一次
                assertThat(labelPrintService.listIssues(runNo)).hasSize(1);
                assertThat(labelPrintService.getPrintRun(runNo).issuedCount()).isEqualTo(60);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 新旧修订生效与领用竞争：一个线程让实质变更新版本期满生效（同事务禁用旧印次），
     * 另一个线程同时领用旧印次。最终不变量：印次必为 DISABLED；已领 ∈ {0, 60}；
     * 若已领 60，凭据已被追加风险关联；此后任何领用都被拒绝（不留旧版生效后的领用凭据）。
     */
    @Test
    void issueRacesWithSubstantiveChangeEffectuation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String product = "P-LC-RACE-" + round;
                String runNo = "RUN-LC-RACE-" + round;
                LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-LCR-S-" + round, product);
                labelPrintService.register(new RegisterPrintRunRequest(
                        "REQ-LCR-R-" + round, runNo, product, LabelPrintFixtures.adultDigest(null), null, 100));

                // 提交实质变更（进入公示）并推进时钟至期满，等待生效扫描与领用竞争
                clock.setInstant(clock.instant().plus(1, ChronoUnit.HOURS));
                filingService.change(product, new com.example.starter.cosmetic.web.dto.ChangeFilingRequest(
                        "REQ-LCR-C-" + round, null, null,
                        List.of("MOISTURIZING", "REFRESHING"), null, null,
                        null, null, null, null, null));
                clock.setInstant(clock.instant().plus(11, ChronoUnit.DAYS));

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicInteger issued = new AtomicInteger();
                AtomicInteger rejected = new AtomicInteger();
                AtomicReference<Throwable> error = new AtomicReference<>();

                final int roundNo = round;
                Runnable effector = () -> {
                    try {
                        start.await();
                        filingService.expireDuePublicNotices(null);
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                };
                Runnable issuer = () -> {
                    try {
                        start.await();
                        labelPrintService.issue(runNo, new IssueLabelsRequest(
                                "REQ-LCR-I-" + roundNo, "BATCH-LCR-" + roundNo, 60, 1));
                        issued.incrementAndGet();
                    } catch (ConflictException | UnprocessableException e) {
                        // 旧印次已被禁用或版本已迁移：拒绝继续领用
                        rejected.incrementAndGet();
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                };
                Future<?> f1 = pool.submit(effector);
                Future<?> f2 = pool.submit(issuer);
                start.countDown();
                boolean finished = done.await(30, TimeUnit.SECONDS);
                f1.get(1, TimeUnit.SECONDS);
                f2.get(1, TimeUnit.SECONDS);

                assertThat(finished).as("第 %d 轮竞态应在超时前完成", round).isTrue();
                assertThat(error.get()).isNull();
                assertThat(issued.get() + rejected.get()).isEqualTo(1);

                PrintRunResponse run = labelPrintService.getPrintRun(runNo);
                // 新版本必已生效并禁用旧印次
                assertThat(run.status()).isEqualTo("DISABLED");
                // 已领 ∈ {0, 60}，且守恒
                assertThat(run.issuedCount()).isIn(0, 60);
                assertThat(run.printedCount())
                        .isEqualTo(run.issuedCount() + run.destroyedCount() + run.remainingCount());
                assertThat(run.disabledRemainingCount()).isEqualTo(run.remainingCount());
                if (run.issuedCount() == 60) {
                    // 领用发生在禁用前（合法顺序），凭据已被追加风险关联，不抹历史
                    assertThat(labelPrintService.listIssues(runNo)).hasSize(1);
                    assertThat(labelPrintService.listIssues(runNo).get(0).riskFlag()).isTrue();
                } else {
                    assertThat(labelPrintService.listIssues(runNo)).isEmpty();
                }
                // 此后任何领用都被拒绝：不留旧版生效后继续领用的凭据
                assertThatThrownBy(() -> labelPrintService.issue(runNo,
                        new IssueLabelsRequest("REQ-LCR-AFTER-" + roundNo,
                                "BATCH-LCR-" + roundNo, 1, run.versionNo())))
                        .isInstanceOf(UnprocessableException.class)
                        .hasMessageContaining("已禁用");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 并发领用与销毁（同一印次，均 60 张/共 100 张）：恰好一个成功，张数守恒；
     * 若领用获胜，销毁方重试 60 张因“已领部分不能销毁退回余额”422。
     */
    @Test
    void concurrentDestroyAndIssueConserveCounts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                String product = "P-LC-DI-" + round;
                String runNo = "RUN-LC-DI-" + round;
                LabelPrintFixtures.makeAdultEffective(filingService, clock, "REQ-LCD-S-" + round, product);
                labelPrintService.register(new RegisterPrintRunRequest(
                        "REQ-LCD-R-" + round, runNo, product, LabelPrintFixtures.adultDigest(null), null, 100));

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicInteger issueWon = new AtomicInteger();
                AtomicInteger destroyWon = new AtomicInteger();
                AtomicReference<Throwable> error = new AtomicReference<>();

                final int roundNo = round;
                Runnable issuer = () -> {
                    try {
                        start.await();
                        labelPrintService.issue(runNo, new IssueLabelsRequest(
                                "REQ-LCD-I-" + roundNo, "BATCH-LCD-" + roundNo, 60, 1));
                        issueWon.incrementAndGet();
                    } catch (ConflictException | UnprocessableException e) {
                        // 版本竞争失败：输家
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                };
                Runnable destroyer = () -> {
                    try {
                        start.await();
                        labelPrintService.destroy(runNo, new DestroyLabelsRequest(
                                "REQ-LCD-D-" + roundNo, 60, 1, "合成销毁：并发"));
                        destroyWon.incrementAndGet();
                    } catch (ConflictException | UnprocessableException e) {
                        // 版本竞争失败：输家
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                };
                Future<?> f1 = pool.submit(issuer);
                Future<?> f2 = pool.submit(destroyer);
                start.countDown();
                boolean finished = done.await(30, TimeUnit.SECONDS);
                f1.get(1, TimeUnit.SECONDS);
                f2.get(1, TimeUnit.SECONDS);

                assertThat(finished).isTrue();
                assertThat(error.get()).isNull();
                assertThat(issueWon.get() + destroyWon.get()).isEqualTo(1);

                PrintRunResponse run = labelPrintService.getPrintRun(runNo);
                // 守恒：无论谁获胜，印刷总数 = 已领 + 已毁 + 剩余
                assertThat(run.printedCount())
                        .isEqualTo(run.issuedCount() + run.destroyedCount() + run.remainingCount());
                assertThat(run.issuedCount() + run.destroyedCount()).isEqualTo(60);
                assertThat(run.remainingCount()).isEqualTo(40);

                if (issueWon.get() == 1) {
                    // 已领 60 后销毁 60：已领部分不能销毁退回余额，422
                    assertThatThrownBy(() -> labelPrintService.destroy(runNo,
                            new DestroyLabelsRequest("REQ-LCD-D2-" + roundNo, 60,
                                    run.versionNo(), "合成销毁：超未领")))
                            .isInstanceOf(UnprocessableException.class)
                            .hasMessageContaining("已领部分不能销毁退回余额");
                } else {
                    // 已毁 60 后领 60：余额不足，422
                    assertThatThrownBy(() -> labelPrintService.issue(runNo,
                            new IssueLabelsRequest("REQ-LCD-I2-" + roundNo,
                                    "BATCH-LCD-" + roundNo, 60, run.versionNo())))
                            .isInstanceOf(UnprocessableException.class)
                            .hasMessageContaining("未领余额不足");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
