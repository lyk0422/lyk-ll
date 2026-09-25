package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发测试：
 * <ul>
 *   <li>公示期满自动生效 vs 期内异议受理并发竞争，必须形成唯一终态；</li>
 *   <li>同一 requestId 并发提交，仅落一条备案且两个调用都拿到首次结果。</li>
 * </ul>
 */
class FilingConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private PublicNoticeEffector publicNoticeEffector;

    @Autowired
    private FilingPort filingPort;

    private static final Instant EXPIRES_EXPECTED = Instant.parse("2026-07-10T00:00:00Z");

    /**
     * 恰第 10 日 24:00(UTC) 同时发起“期满生效”和“异议受理”，
     * 多轮执行确认：不得既生效又受理异议，终态唯一。
     */
    @Test
    void expiryAndObjectionRaceProducesExactlyOneTerminalState() throws Exception {
        int rounds = 20;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            final String product = "P-RACE-" + round;
            FilingResultResponse submitted =
                    filingService.submit(FilingFixtures.ordinarySubmit("REQ-RACE-" + round, product));
            long recordId = filingPort.findLatestByNo(submitted.filing().filingNo()).orElseThrow().id();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger effectiveWins = new AtomicInteger();
            AtomicInteger objectionWins = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable expiryTask = () -> {
                try {
                    start.await();
                    FilingRecord record = filingPort.findById(recordId).orElseThrow();
                    if (publicNoticeEffector.effectuate(record, EXPIRES_EXPECTED)) {
                        effectiveWins.incrementAndGet();
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };
            Runnable objectionTask = () -> {
                try {
                    start.await();
                    filingService.acceptObjection(submitted.filing().filingNo(),
                            EXPIRES_EXPECTED, "恰期满当时异议-轮次" + round);
                    objectionWins.incrementAndGet();
                } catch (ConflictException e) {
                    // 期满分支已获胜：异议必然被拒，不计入异常
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread expiryThread = new Thread(expiryTask, "expiry-" + round);
            Thread objectionThread = new Thread(objectionTask, "objection-" + round);
            expiryThread.start();
            objectionThread.start();
            start.countDown();
            expiryThread.join(10_000);
            objectionThread.join(10_000);

            assertThat(error.get()).isNull();
            assertThat(effectiveWins.get() + objectionWins.get())
                    .as("第 %d 轮：两个分支必须恰好一个获胜", round)
                    .isEqualTo(1);

            FilingStatus finalStatus = filingPort.findById(recordId).orElseThrow().status();
            if (effectiveWins.get() == 1) {
                assertThat(finalStatus).isEqualTo(FilingStatus.EFFECTIVE);
                assertThat(filingPort.findActiveSlot(product)).isPresent();
            } else {
                assertThat(finalStatus).isEqualTo(FilingStatus.OBJECTION_REVIEW);
                assertThat(filingPort.findActiveSlot(product)).isEmpty();
            }
        }
    }

    /**
     * 同一 requestId 同参数并发提交：数据库主键冲突 + 等待回填保证只有一条备案，
     * 两个调用方都返回同一首次结果。
     */
    @Test
    void concurrentSameRequestSubmissionsAreIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String requestId = "REQ-CONC-" + round;
                String product = "P-CONC-" + round;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                java.util.List<FilingResultResponse> responses =
                        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(
                                filingService.submit(FilingFixtures.ordinarySubmit(requestId, product)));
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

                // 恰好一个是首次处理，另一个是重放
                long replays = responses.stream().filter(FilingResultResponse::idempotentReplay).count();
                long initials = responses.stream().filter(r -> !r.idempotentReplay()).count();
                assertThat(replays).isEqualTo(1);
                assertThat(initials).isEqualTo(1);
                assertThat(responses.get(0).filing().filingNo())
                        .isEqualTo(responses.get(1).filing().filingNo());
                // 只落一条备案、只占一份幂等记录
                assertThat(filingPort.findByProduct(product)).hasSize(1);
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
     * 并发简易变更：同一有效备案同时发起两次不同 requestId 的包装变更，
     * 最终只有一个新版本生效，旧版本恰好被替代一次（唯一有效备案 + 原子切换）。
     */
    @Test
    void concurrentSimpleChangesLeaveSingleEffectiveVersion() throws Exception {
        filingService.submit(FilingFixtures.ordinarySubmit("REQ-S-1", "P-SC-RACE"));
        clock.setInstant(EXPIRES_EXPECTED);
        filingService.expireDuePublicNotices(null);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Runnable changeA = () -> runChange("REQ-S-2", "包装版本A", start, success, conflicts, error);
        Runnable changeB = () -> runChange("REQ-S-3", "包装版本B", start, success, conflicts, error);

        Thread t1 = new Thread(changeA);
        Thread t2 = new Thread(changeB);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(error.get()).isNull();
        // 恰好一个变更成功；另一个因旧版本已被替代（条件 UPDATE 0 行）回滚
        assertThat(success.get() + conflicts.get()).isEqualTo(2);
        assertThat(success.get()).isEqualTo(1);
        var slot = filingPort.findActiveSlot("P-SC-RACE").orElseThrow();
        assertThat(slot.versionNo()).isEqualTo(2);
        assertThat(filingPort.findVersion("SYN-F-000001", 1).orElseThrow().status())
                .isEqualTo(FilingStatus.SUPERSEDED);
    }

    private void runChange(String requestId, String packaging, CountDownLatch start,
                           AtomicInteger success, AtomicInteger conflicts,
                           AtomicReference<Throwable> error) {
        try {
            start.await();
            filingService.change("P-SC-RACE", FilingFixtures.packagingChange(requestId, packaging));
            success.incrementAndGet();
        } catch (ConflictException | org.springframework.dao.DataIntegrityViolationException e) {
            // 业务409 或版本唯一索引竞争（HTTP 层同样映射为409）：输家事务回滚
            conflicts.incrementAndGet();
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
    }
}
